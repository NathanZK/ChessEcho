#!/usr/bin/env python3
"""Canonical immutable evidence for ChessEcho workflow authority."""

import argparse
import base64
import json
import pathlib
import re
import sys

try:
    import workflow_cas
    import workflow_inspector as inspector
except ModuleNotFoundError:
    from scripts import workflow_cas
    from scripts import workflow_inspector as inspector


EVIDENCE_VERSION = "1.0.0"
PUBLICATION_FORMAT = "chess-echo-evidence-publication-v1"
MANIFEST_FORMAT = "chess-echo-evidence-manifest-v1"
PROVENANCE_FORMAT = "chess-echo-evidence-provenance-v1"
BINDING_FORMAT = "chess-echo-evidence-binding-v1"
RESULT_FORMAT = "chess-echo-evidence-result-v1"
PROJECTION_FORMAT = "chess-echo-evidence-projection-v1"
V4_ADAPTER_FORMAT = "chess-echo-v4-evidence-adapter-v1"
MIGRATION_ADAPTER_FORMAT = "chess-echo-workflow-migration-v1"
MIGRATION_SOURCE_MANIFEST_FORMAT = "chess-echo-migration-source-manifest-v1"
MIGRATION_SOURCE_VARIANTS = {
    "projection-v1",
    "projection-v2",
    "projection-v3",
    "projection-v4",
    "settled-adoption",
    "durable-v4",
}

PAYLOAD_LIMIT = 64 * 1024 * 1024
STRUCTURED_OBJECT_LIMIT = 8 * 1024 * 1024
MANIFEST_ENTRY_LIMIT = 10_000
PUBLICATION_PAYLOAD_LIMIT = 512 * 1024 * 1024

OUTCOME_EXIT_CODES = {
    "resolved": 0,
    "missing": 3,
    "unsupported": 4,
    "corrupt": 5,
    "ambiguous": 6,
    "stale": 7,
}
SHA256_RE = re.compile(r"[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[0-9a-f]{32}")
EVENT_TIP_RE = SHA256_RE
SAFE_SLUG_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]*")
RFC3339_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})"
)
GIT_OID_RE = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")
ENTRY_KINDS = {"regular", "symlink", "deleted"}
ENTRY_MODES = {"regular": {"100644", "100755"}, "symlink": {"120000"}}
SOURCE_TYPES = {"workspace", "git", "v4", "external", "migration"}
LINEAGE_STATUSES = {"original", "inherited", "replacement"}
V4_EVIDENCE_KINDS = (
    inspector.TYPED_PAYLOAD_KINDS
    | inspector.RAW_OBJECT_KINDS
    | inspector.BASE64_PAYLOAD_KINDS
    | {"evidence-manifest", "validation-result"}
)


class EvidenceFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.subject = subject

    def document(self):
        outcome = {
            "status": self.status,
            "code": self.code,
            "message": self.message,
        }
        if self.subject is not None:
            outcome["subject"] = self.subject
        return {"format": RESULT_FORMAT, "outcome": outcome}


def _fail(status, code, message, subject=None):
    raise EvidenceFailure(status, code, message, subject)


def _exact_int(value):
    return type(value) is int


def _exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("corrupt", "invalid-%s-schema" % label, "%s schema is invalid" % label)


def _canonical_bytes(value, label="structured object"):
    try:
        data = inspector.canonical_bytes(value)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if len(data) > STRUCTURED_OBJECT_LIMIT:
        _fail(
            "unsupported",
            "structured-object-too-large",
            "%s exceeds the 8 MiB structured-object limit" % label,
        )
    return data


def _reference(kind, data):
    return {"kind": kind, "sha256": inspector.sha256(data), "size": len(data)}


def _validate_reference(value, expected_kind=None):
    try:
        return inspector.validate_reference(value, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)


def _validate_path(value, label="path"):
    try:
        encoded = value.encode("utf-8") if isinstance(value, str) else None
    except UnicodeEncodeError:
        encoded = None
    if (
        not isinstance(value, str)
        or not value
        or "\0" in value
        or "\\" in value
        or value.startswith("/")
        or encoded is None
        or len(encoded) > 4096
    ):
        _fail("unsupported", "invalid-evidence-path", "%s is not a supported path" % label)
    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        _fail("unsupported", "invalid-evidence-path", "%s is not normalized" % label)
    return value


def _validate_entry(value):
    _exact_keys(
        value,
        {"path", "kind", "mode", "content_sha256", "size", "payload"},
        "evidence-entry",
    )
    path = _validate_path(value["path"])
    kind = value["kind"]
    if not isinstance(kind, str) or kind not in ENTRY_KINDS:
        _fail("unsupported", "unsupported-entry-kind", "Evidence entry kind is unsupported")
    if kind == "deleted":
        if any(value[field] is not None for field in ("mode", "content_sha256", "size", "payload")):
            _fail("corrupt", "invalid-deleted-entry", "Deleted entry facts must be null")
    else:
        if (
            not isinstance(value["mode"], str)
            or value["mode"] not in ENTRY_MODES[kind]
        ):
            _fail("unsupported", "unsupported-entry-mode", "Evidence entry mode is unsupported")
        digest = value["content_sha256"]
        if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
            _fail("corrupt", "invalid-content-hash", "Evidence content hash is invalid")
        if not _exact_int(value["size"]) or value["size"] < 0:
            _fail("corrupt", "invalid-content-size", "Evidence content size is invalid")
        payload = _validate_reference(value["payload"], "evidence-payload")
        if payload["sha256"] != digest or payload["size"] != value["size"]:
            _fail(
                "corrupt",
                "entry-payload-mismatch",
                "Evidence entry facts conflict with the payload reference",
            )
    normalized = {
        "path": path,
        "kind": kind,
        "mode": value["mode"],
        "content_sha256": value["content_sha256"],
        "size": value["size"],
        "payload": value["payload"],
    }
    return normalized


def _validate_manifest(value):
    _exact_keys(value, {"kind", "format", "entries"}, "evidence-manifest")
    if value["kind"] != "evidence-manifest" or value["format"] != MANIFEST_FORMAT:
        _fail("unsupported", "unsupported-manifest-format", "Evidence manifest format is unsupported")
    entries = value["entries"]
    if not isinstance(entries, list):
        _fail("corrupt", "invalid-manifest-entries", "Manifest entries must be a list")
    if len(entries) > MANIFEST_ENTRY_LIMIT:
        _fail("unsupported", "manifest-entry-limit", "Manifest exceeds 10,000 entries")
    normalized = [_validate_entry(entry) for entry in entries]
    paths = [entry["path"] for entry in normalized]
    if len(paths) != len(set(paths)):
        _fail("ambiguous", "duplicate-evidence-path", "Manifest contains duplicate paths")
    if normalized != sorted(normalized, key=lambda entry: entry["path"].encode("utf-8")):
        _fail("corrupt", "noncanonical-entry-order", "Manifest entries are not canonically ordered")
    _canonical_bytes(value, "evidence manifest")
    return normalized


def _entry_digest(entry):
    return inspector.sha256(_canonical_bytes(entry, "evidence entry"))


def _validate_source(value):
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("type"), str)
        or value["type"] not in SOURCE_TYPES
    ):
        _fail("unsupported", "unsupported-provenance-source", "Provenance source is unsupported")
    source_type = value["type"]
    if source_type == "workspace":
        _exact_keys(value, {"type", "path"}, "workspace-source")
        _validate_path(value["path"], "workspace source path")
    elif source_type == "git":
        _exact_keys(value, {"type", "path", "commit", "oid"}, "git-source")
        _validate_path(value["path"], "Git source path")
        if (
            not isinstance(value["commit"], str)
            or GIT_OID_RE.fullmatch(value["commit"]) is None
            or not isinstance(value["oid"], str)
            or GIT_OID_RE.fullmatch(value["oid"]) is None
        ):
            _fail("corrupt", "invalid-git-provenance", "Git provenance object IDs are invalid")
    elif source_type == "v4":
        _exact_keys(value, {"type", "object"}, "v4-source")
        _validate_reference(value["object"])
    else:
        if source_type == "external":
            _exact_keys(value, {"type", "location"}, "external-source")
            _validate_path(value["location"], "external source location")
        else:
            _exact_keys(
                value,
                {"type", "object", "source_manifest", "logical_path"},
                "migration-source",
            )
            _validate_reference(value["object"])
            _validate_reference(value["source_manifest"], "migration-source-manifest")
            _validate_path(value["logical_path"], "migration logical path")
    return value


def _validate_capture(value, entry_digests):
    _exact_keys(
        value,
        {"entry_sha256", "capture_method", "captured_at", "source", "tool"},
        "evidence-capture",
    )
    if value["entry_sha256"] not in entry_digests:
        _fail(
            "corrupt",
            "provenance-entry-missing",
            "Provenance references an entry outside its manifest",
        )
    if (
        not isinstance(value["capture_method"], str)
        or SAFE_SLUG_RE.fullmatch(value["capture_method"]) is None
        or (
            value["captured_at"] is not None
            and (
                not isinstance(value["captured_at"], str)
                or RFC3339_RE.fullmatch(value["captured_at"]) is None
            )
        )
    ):
        _fail("corrupt", "invalid-capture-metadata", "Capture metadata is invalid")
    if (value["capture_method"] == "deterministic-migration") != (
        value["captured_at"] is None
    ):
        _fail(
            "corrupt",
            "invalid-capture-metadata",
            "Only deterministic migration captures omit capture time",
        )
    source = _validate_source(value["source"])
    if (source["type"] == "migration") != (
        value["capture_method"] == "deterministic-migration"
    ):
        _fail(
            "corrupt",
            "invalid-capture-metadata",
            "Deterministic migration capture metadata requires a migration source",
        )
    _exact_keys(value["tool"], {"name", "version"}, "capture-tool")
    if any(not isinstance(value["tool"][field], str) or not value["tool"][field] for field in ("name", "version")):
        _fail("corrupt", "invalid-capture-tool", "Capture tool identity is invalid")
    return value


def _capture_key(capture):
    return (
        capture["entry_sha256"],
        inspector.canonical_bytes(capture["source"]),
        capture["capture_method"],
        capture["captured_at"],
        capture["tool"]["name"],
        capture["tool"]["version"],
    )


def _validate_provenance(value, manifest_ref, entries):
    _exact_keys(value, {"kind", "format", "manifest", "captures"}, "evidence-provenance")
    if value["kind"] != "evidence-provenance" or value["format"] != PROVENANCE_FORMAT:
        _fail(
            "unsupported",
            "unsupported-provenance-format",
            "Evidence provenance format is unsupported",
        )
    if _validate_reference(value["manifest"], "evidence-manifest") != manifest_ref:
        _fail("corrupt", "provenance-manifest-mismatch", "Provenance names another manifest")
    if not isinstance(value["captures"], list):
        _fail("corrupt", "invalid-provenance-captures", "Provenance captures must be a list")
    entry_digests = {_entry_digest(entry) for entry in entries}
    captures = [_validate_capture(capture, entry_digests) for capture in value["captures"]]
    keys = [_capture_key(capture) for capture in captures]
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "duplicate-provenance", "Provenance contains duplicate captures")
    if keys != sorted(keys):
        _fail("corrupt", "noncanonical-provenance-order", "Provenance captures are not ordered")
    captured = {capture["entry_sha256"] for capture in captures}
    if captured != entry_digests:
        _fail("missing", "entry-provenance-missing", "Every manifest entry requires provenance")
    _canonical_bytes(value, "evidence provenance")
    return captures


def _validate_identity(value):
    _exact_keys(
        value,
        {
            "issue",
            "run_id",
            "family_run_id",
            "correction",
            "run_generation",
            "sequence",
            "event_tip",
        },
        "binding-identity",
    )
    if (
        not _exact_int(value["issue"])
        or value["issue"] < 1
        or not isinstance(value["run_id"], str)
        or RUN_ID_RE.fullmatch(value["run_id"]) is None
        or not isinstance(value["family_run_id"], str)
        or RUN_ID_RE.fullmatch(value["family_run_id"]) is None
        or (
            value["correction"] is not None
            and (not _exact_int(value["correction"]) or value["correction"] < 1)
        )
        or not _exact_int(value["run_generation"])
        or value["run_generation"] < 0
        or not _exact_int(value["sequence"])
        or value["sequence"] < 1
        or not isinstance(value["event_tip"], str)
        or EVENT_TIP_RE.fullmatch(value["event_tip"]) is None
    ):
        _fail("corrupt", "invalid-binding-identity", "Binding identity is invalid")
    return value


def _validate_decision(value):
    _exact_keys(value, {"type", "id"}, "binding-decision")
    if any(
        not isinstance(value[field], str) or SAFE_SLUG_RE.fullmatch(value[field]) is None
        for field in ("type", "id")
    ):
        _fail("unsupported", "unsupported-decision-identity", "Decision identity is unsupported")
    return value


def _validate_lineage(value):
    _exact_keys(value, {"status", "parent_binding"}, "binding-lineage")
    if (
        not isinstance(value["status"], str)
        or value["status"] not in LINEAGE_STATUSES
    ):
        _fail("unsupported", "unsupported-lineage-status", "Binding lineage status is unsupported")
    if value["status"] == "original":
        if value["parent_binding"] is not None:
            _fail("corrupt", "unexpected-parent-binding", "Original evidence cannot name a parent")
    else:
        _validate_reference(value["parent_binding"], "evidence-binding")
    return value


def _validate_migration(value):
    if value is None:
        return None
    _exact_keys(value, {"adapter", "source"}, "binding-migration")
    if (
        not isinstance(value["adapter"], str)
        or value["adapter"] not in {V4_ADAPTER_FORMAT, MIGRATION_ADAPTER_FORMAT}
    ):
        _fail("unsupported", "unsupported-migration-adapter", "Migration adapter is unsupported")
    source = _validate_reference(
        value["source"],
        "migration-source-manifest"
        if value["adapter"] == MIGRATION_ADAPTER_FORMAT
        else None,
    )
    if (
        value["adapter"] == MIGRATION_ADAPTER_FORMAT
        and source["size"] > STRUCTURED_OBJECT_LIMIT
    ):
        _fail(
            "unsupported",
            "structured-object-too-large",
            "Migration source manifest exceeds 8 MiB",
        )
    return value


def _validate_migration_source_manifest(value):
    _exact_keys(
        value,
        {
            "kind",
            "format",
            "variant",
            "issue",
            "correction",
            "migration_metadata",
            "objects",
        },
        "migration-source-manifest",
    )
    if (
        value["kind"] != "migration-source-manifest"
        or value["format"] != MIGRATION_SOURCE_MANIFEST_FORMAT
    ):
        _fail(
            "unsupported",
            "unsupported-migration-source-format",
            "Migration source manifest format is unsupported",
        )
    if (
        not _exact_int(value["issue"])
        or value["issue"] < 1
        or (
            value["correction"] is not None
            and (
                not _exact_int(value["correction"])
                or value["correction"] < 1
            )
        )
        or not isinstance(value["variant"], str)
        or not value["variant"]
    ):
        _fail("corrupt", "invalid-migration-source", "Migration source identity is invalid")
    if value["variant"] not in MIGRATION_SOURCE_VARIANTS:
        _fail(
            "unsupported",
            "unsupported-migration-source-variant",
            "Migration source variant is unsupported",
        )
    metadata = value["migration_metadata"]
    if (
        not isinstance(metadata, dict)
        or set(metadata) != {"status"}
        or not isinstance(metadata["status"], str)
        or metadata["status"] not in {"not-recorded", "none", "recorded"}
    ):
        _fail(
            "corrupt",
            "invalid-migration-metadata",
            "Migration source metadata status is invalid",
        )
    if not isinstance(value["objects"], list):
        _fail("corrupt", "invalid-migration-objects", "Migration objects must be a list")
    if not value["objects"]:
        _fail("missing", "migration-source-empty", "Migration source has no objects")
    if len(value["objects"]) > MANIFEST_ENTRY_LIMIT:
        _fail("unsupported", "manifest-entry-limit", "Migration source exceeds 10,000 objects")
    normalized = []
    for item in value["objects"]:
        _exact_keys(
            item,
            {
                "logical_path",
                "object",
                "encoding",
                "payload_sha256",
                "payload_size",
            },
            "migration-source-object",
        )
        _validate_path(item["logical_path"], "migration logical path")
        reference = _validate_reference(item["object"])
        if reference["size"] > PAYLOAD_LIMIT:
            _fail(
                "unsupported",
                "source-object-too-large",
                "Migration source object exceeds 64 MiB",
            )
        if (
            reference["kind"]
            in (inspector.JSON_OBJECT_KINDS | inspector.TYPED_PAYLOAD_KINDS)
            and reference["size"] > STRUCTURED_OBJECT_LIMIT
        ):
            _fail(
                "unsupported",
                "structured-object-too-large",
                "Structured migration source exceeds 8 MiB",
            )
        encoding = item["encoding"]
        if (
            encoding is not None
            and (
                not isinstance(encoding, str)
                or encoding not in {"raw", "base64", "typed-base64"}
            )
        ):
            _fail("unsupported", "unsupported-source-encoding", "Source encoding is unsupported")
        payload_digest = item["payload_sha256"]
        payload_size = item["payload_size"]
        if encoding is None:
            if payload_digest is not None or payload_size is not None:
                _fail("corrupt", "invalid-source-payload", "Unselected source has payload facts")
        elif (
            not isinstance(payload_digest, str)
            or SHA256_RE.fullmatch(payload_digest) is None
            or not _exact_int(payload_size)
            or payload_size < 0
        ):
            _fail("corrupt", "invalid-source-payload", "Selected source payload facts are invalid")
        elif payload_size > PAYLOAD_LIMIT:
            _fail(
                "unsupported",
                "payload-too-large",
                "Migration source payload exceeds 64 MiB",
            )
        normalized.append((item, reference))
    unique_payloads = {}
    for item, _reference_value in normalized:
        if item["encoding"] is None:
            continue
        previous = unique_payloads.get(item["payload_sha256"])
        if previous is not None and previous != item["payload_size"]:
            _fail(
                "corrupt",
                "source-payload-mismatch",
                "A migration payload hash has conflicting sizes",
            )
        unique_payloads[item["payload_sha256"]] = item["payload_size"]
    if sum(unique_payloads.values()) > PUBLICATION_PAYLOAD_LIMIT:
        _fail(
            "unsupported",
            "publication-payload-limit",
            "Migration source payloads exceed 512 MiB",
        )
    paths = [item["logical_path"] for item, _reference_value in normalized]
    if len(paths) != len(set(paths)):
        _fail("ambiguous", "duplicate-source-path", "Migration source paths are duplicated")
    if paths != sorted(paths, key=lambda path: path.encode("utf-8")):
        _fail("corrupt", "noncanonical-source-order", "Migration source objects are not ordered")
    _canonical_bytes(value, "migration source manifest")
    return normalized


def _decode_migration_source_payload(data, item, manifest):
    encoding = item["encoding"]
    native_payload = None
    if item["object"]["kind"] in inspector.BASE64_PAYLOAD_KINDS:
        try:
            native_payload = base64.b64decode(data, validate=True)
        except (TypeError, ValueError):
            _fail(
                "corrupt",
                "invalid-base64-payload",
                "Migration source payload is invalid",
            )
    elif item["object"]["kind"] in inspector.TYPED_PAYLOAD_KINDS:
        try:
            wrapper = inspector.parse_json_object(data, "typed migration source")
        except inspector.InspectionFailure as failure:
            _fail(failure.status, failure.code, failure.message, failure.subject)
        if (
            _canonical_bytes(wrapper, "typed migration source") != data
            or set(wrapper)
            != {
                "correction",
                "issue",
                "kind",
                "logical_path",
                "object_kind",
                "payload_base64",
            }
            or wrapper.get("kind") != "typed-evidence"
            or wrapper.get("object_kind") != item["object"]["kind"]
            or not _exact_int(wrapper.get("issue"))
            or wrapper["issue"] != manifest["issue"]
            or (
                manifest["correction"] is not None
                and not _exact_int(wrapper.get("correction"))
            )
            or wrapper.get("correction") != manifest["correction"]
            or wrapper.get("logical_path") != item["logical_path"]
            or not isinstance(wrapper.get("payload_base64"), str)
        ):
            _fail("corrupt", "invalid-typed-payload", "Typed migration source is invalid")
        try:
            native_payload = base64.b64decode(
                wrapper["payload_base64"], validate=True
            )
        except (TypeError, ValueError):
            _fail("corrupt", "invalid-typed-payload", "Typed migration payload is invalid")
    if encoding is None:
        return None
    if encoding == "raw":
        payload = data
    elif encoding == "base64":
        if item["object"]["kind"] not in inspector.BASE64_PAYLOAD_KINDS:
            _fail(
                "unsupported",
                "encoding-kind-mismatch",
                "Base64 migration source has the wrong object kind",
            )
        payload = native_payload
    else:
        if item["object"]["kind"] not in inspector.TYPED_PAYLOAD_KINDS:
            _fail(
                "unsupported",
                "encoding-kind-mismatch",
                "Typed migration source has the wrong object kind",
            )
        payload = native_payload
    if (
        len(payload) != item["payload_size"]
        or inspector.sha256(payload) != item["payload_sha256"]
    ):
        _fail("corrupt", "source-payload-mismatch", "Migration source payload facts differ")
    if len(payload) > PAYLOAD_LIMIT:
        _fail("unsupported", "payload-too-large", "Migration source payload exceeds 64 MiB")
    return payload


def _read_migration_sources(reader, binding):
    migration = binding["migration"]
    if migration is None or migration["adapter"] != MIGRATION_ADAPTER_FORMAT:
        return {}
    source_ref = migration["source"]
    manifest = _read_json(reader, source_ref, "migration-source-manifest")
    records = {}
    for item, reference in _validate_migration_source_manifest(manifest):
        data = _read_bytes(reader, reference, reference["kind"])
        payload = _decode_migration_source_payload(data, item, manifest)
        records[(reference["sha256"], item["logical_path"])] = {
            "item": item,
            "payload": payload,
            "reference": reference,
        }
    if (
        manifest["issue"] != binding["identity"]["issue"]
        or manifest["correction"] != binding["identity"]["correction"]
    ):
        _fail("corrupt", "migration-source-identity-mismatch", "Source identity differs")
    return records


def _validate_binding(value):
    _exact_keys(
        value,
        {
            "kind",
            "format",
            "identity",
            "decision",
            "subject",
            "manifest",
            "provenance",
            "lineage",
            "migration",
        },
        "evidence-binding",
    )
    if value["kind"] != "evidence-binding" or value["format"] != BINDING_FORMAT:
        _fail("unsupported", "unsupported-binding-format", "Evidence binding format is unsupported")
    _validate_identity(value["identity"])
    _validate_decision(value["decision"])
    _validate_reference(value["subject"])
    _validate_reference(value["manifest"], "evidence-manifest")
    _validate_reference(value["provenance"], "evidence-provenance")
    _validate_lineage(value["lineage"])
    _validate_migration(value["migration"])
    _canonical_bytes(value, "evidence binding")
    return value


def _decode_payloads(records):
    if not isinstance(records, list):
        _fail("corrupt", "invalid-payload-records", "Publication payloads must be a list")
    decoded = {}
    for record in records:
        _exact_keys(record, {"sha256", "size", "bytes_base64"}, "payload-record")
        digest = record["sha256"]
        size = record["size"]
        if (
            not isinstance(digest, str)
            or SHA256_RE.fullmatch(digest) is None
            or not _exact_int(size)
            or size < 0
        ):
            _fail("corrupt", "invalid-payload-record", "Payload record hash or size is invalid")
        try:
            data = base64.b64decode(record["bytes_base64"], validate=True)
        except (TypeError, ValueError):
            _fail("corrupt", "invalid-payload-base64", "Payload bytes are not valid base64")
        if len(data) > PAYLOAD_LIMIT:
            _fail("unsupported", "payload-too-large", "Payload exceeds the 64 MiB limit")
        if len(data) != size or inspector.sha256(data) != digest:
            _fail("corrupt", "payload-record-mismatch", "Payload bytes fail hash or size verification")
        if digest in decoded and decoded[digest] != data:
            _fail("ambiguous", "conflicting-payload-record", "Payload digest has conflicting bytes")
        decoded[digest] = data
    total = sum(len(data) for data in decoded.values())
    if total > PUBLICATION_PAYLOAD_LIMIT:
        _fail(
            "unsupported",
            "publication-payload-limit",
            "Publication exceeds the 512 MiB total payload budget",
        )
    return decoded


def _load_publication(value):
    _exact_keys(
        value,
        {
            "format",
            "identity",
            "decision",
            "subject",
            "lineage",
            "migration",
            "entries",
            "captures",
            "payloads",
        },
        "publication",
    )
    if value["format"] != PUBLICATION_FORMAT:
        _fail("unsupported", "unsupported-publication-format", "Publication format is unsupported")
    identity = _validate_identity(value["identity"])
    decision = _validate_decision(value["decision"])
    subject = _validate_reference(value["subject"])
    lineage = _validate_lineage(value["lineage"])
    migration = _validate_migration(value["migration"])
    payloads = _decode_payloads(value["payloads"])
    if not isinstance(value["entries"], list):
        _fail("corrupt", "invalid-manifest-entries", "Publication entries must be a list")
    entries = sorted(
        [_validate_entry(entry) for entry in value["entries"]],
        key=lambda entry: entry["path"].encode("utf-8"),
    )
    manifest = {"kind": "evidence-manifest", "format": MANIFEST_FORMAT, "entries": entries}
    _validate_manifest(manifest)
    manifest_data = _canonical_bytes(manifest, "evidence manifest")
    manifest_ref = _reference("evidence-manifest", manifest_data)
    if not isinstance(value["captures"], list):
        _fail("corrupt", "invalid-provenance-captures", "Provenance captures must be a list")
    entry_digests = {_entry_digest(entry) for entry in entries}
    captures = [
        _validate_capture(capture, entry_digests) for capture in value["captures"]
    ]
    provenance = {
        "kind": "evidence-provenance",
        "format": PROVENANCE_FORMAT,
        "manifest": manifest_ref,
        "captures": sorted(captures, key=_capture_key),
    }
    _validate_provenance(provenance, manifest_ref, entries)
    provenance_data = _canonical_bytes(provenance, "evidence provenance")
    provenance_ref = _reference("evidence-provenance", provenance_data)
    binding = {
        "kind": "evidence-binding",
        "format": BINDING_FORMAT,
        "identity": identity,
        "decision": decision,
        "subject": subject,
        "manifest": manifest_ref,
        "provenance": provenance_ref,
        "lineage": lineage,
        "migration": migration,
    }
    _validate_binding(binding)
    binding_data = _canonical_bytes(binding, "evidence binding")
    for entry in entries:
        if entry["kind"] == "deleted":
            continue
        digest = entry["content_sha256"]
        if digest not in payloads:
            _fail("missing", "publication-payload-missing", "Manifest payload bytes are missing", digest)
    unreferenced = set(payloads) - {
        entry["content_sha256"] for entry in entries if entry["kind"] != "deleted"
    }
    if unreferenced:
        _fail(
            "ambiguous",
            "unreferenced-publication-payload",
            "Publication contains payload bytes not referenced by the manifest",
        )
    return {
        "payloads": payloads,
        "manifest": (manifest, manifest_data, manifest_ref),
        "provenance": (provenance, provenance_data, provenance_ref),
        "binding": (binding, binding_data, _reference("evidence-binding", binding_data)),
    }


def _cas_fail(_status, code, message):
    if code == "immutable-object-collision":
        _fail("ambiguous", code, message)
    if code in {"immutable-destination-changed"}:
        _fail("ambiguous", code, message)
    _fail("corrupt", code, message)


def _publish(store, reference, data):
    workflow_cas.publish_immutable(
        inspector.object_path(store, reference["sha256"]),
        data,
        _cas_fail,
        temporary_label="evidence",
    )


def _preflight_external_references(root, decoded):
    binding = decoded["binding"][0]
    reader = _reader(root, binding["identity"]["issue"])
    _read_bytes(reader, binding["subject"], binding["subject"]["kind"])
    _read_migration_sources(reader, binding)
    lineage = binding["lineage"]
    if lineage["status"] == "original":
        return
    parent = _read_json(reader, lineage["parent_binding"], "evidence-binding")
    _validate_binding(parent)
    child_identity = binding["identity"]
    parent_identity = parent["identity"]
    if (
        child_identity["issue"] != parent_identity["issue"]
        or child_identity["family_run_id"] != parent_identity["family_run_id"]
        or child_identity["run_id"] == parent_identity["run_id"]
    ):
        _fail("stale", "lineage-identity-mismatch", "Parent binding identity is incompatible")
    if lineage["status"] == "inherited" and (
        binding["manifest"] != parent["manifest"]
        or binding["subject"] != parent["subject"]
    ):
        _fail(
            "stale",
            "inherited-evidence-changed",
            "Inherited evidence changed its manifest or subject",
        )


def publish(root, publication, before_binding=None):
    decoded = _load_publication(publication)
    store = inspector.resolve_store(root)
    _preflight_external_references(root, decoded)
    published = []
    for digest, data in sorted(decoded["payloads"].items()):
        reference = {"kind": "evidence-payload", "sha256": digest, "size": len(data)}
        _publish(store, reference, data)
        published.append(reference)
    for key in ("manifest", "provenance"):
        _value, data, reference = decoded[key]
        _publish(store, reference, data)
        published.append(reference)
    if before_binding is not None:
        before_binding()
    _value, data, reference = decoded["binding"]
    _publish(store, reference, data)
    published.append(reference)
    binding_ref = decoded["binding"][2]
    verification = verify(root, binding_ref)
    return {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "published"},
        "binding": binding_ref,
        "manifest": decoded["manifest"][2],
        "provenance": decoded["provenance"][2],
        "objects": sorted(published, key=lambda item: (item["sha256"], item["kind"])),
        "verification": verification["outcome"],
    }


def _reader(root, issue):
    return inspector.AuthorityReader(inspector.resolve_store(root), issue)


def _read_json(reader, reference, expected_kind):
    try:
        return reader.read_json(reference, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)


def _read_bytes(reader, reference, expected_kind):
    try:
        return reader.read_bytes(reference, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)


def _verify_graph(root, binding_ref):
    binding_ref = _validate_reference(binding_ref, "evidence-binding")
    reader = _reader(root, 0)
    binding = _read_json(reader, binding_ref, "evidence-binding")
    _validate_binding(binding)
    reader.issue = binding["identity"]["issue"]
    reader.correction = binding["identity"]["correction"]
    manifest = _read_json(reader, binding["manifest"], "evidence-manifest")
    entries = _validate_manifest(manifest)
    provenance = _read_json(reader, binding["provenance"], "evidence-provenance")
    _validate_provenance(provenance, binding["manifest"], entries)
    migration_sources = _read_migration_sources(reader, binding)
    if (
        binding["migration"] is not None
        and binding["migration"]["adapter"] == MIGRATION_ADAPTER_FORMAT
    ):
        entry_by_digest = {_entry_digest(entry): entry for entry in entries}
        for capture in provenance["captures"]:
            source = capture["source"]
            if source["type"] != "migration":
                _fail(
                    "corrupt",
                    "migration-provenance-missing",
                    "Migrated evidence requires deterministic migration provenance",
                )
            if source["source_manifest"] != binding["migration"]["source"]:
                _fail(
                    "corrupt",
                    "migration-source-manifest-mismatch",
                    "Capture names another migration source manifest",
                )
            record = migration_sources.get(
                (source["object"]["sha256"], source["logical_path"])
            )
            if record is None:
                _fail(
                    "missing",
                    "migration-source-object-missing",
                    "Capture source is absent from its migration manifest",
                )
            if record["reference"] != source["object"]:
                _fail(
                    "corrupt",
                    "migration-source-reference-mismatch",
                    "Capture source reference differs from its migration manifest",
                )
            entry = entry_by_digest[capture["entry_sha256"]]
            if record["item"]["payload_sha256"] != entry["content_sha256"]:
                _fail(
                    "corrupt",
                    "migration-payload-mismatch",
                    "Capture source payload differs from semantic evidence",
                )
    _read_bytes(reader, binding["subject"], binding["subject"]["kind"])
    for entry in entries:
        if entry["kind"] == "deleted":
            continue
        payload = _read_bytes(reader, entry["payload"], "evidence-payload")
        if len(payload) > PAYLOAD_LIMIT:
            _fail("unsupported", "payload-too-large", "Stored payload exceeds the 64 MiB limit")
        if inspector.sha256(payload) != entry["content_sha256"]:
            _fail("corrupt", "entry-payload-mismatch", "Stored payload conflicts with entry facts")
    parent = None
    if binding["lineage"]["status"] != "original":
        parent_ref = binding["lineage"]["parent_binding"]
        parent = _read_json(reader, parent_ref, "evidence-binding")
        _validate_binding(parent)
        child_identity = binding["identity"]
        parent_identity = parent["identity"]
        if (
            child_identity["issue"] != parent_identity["issue"]
            or child_identity["family_run_id"] != parent_identity["family_run_id"]
            or child_identity["run_id"] == parent_identity["run_id"]
        ):
            _fail("stale", "lineage-identity-mismatch", "Parent binding identity is incompatible")
        if binding["lineage"]["status"] == "inherited" and (
            binding["manifest"] != parent["manifest"]
            or binding["subject"] != parent["subject"]
        ):
            _fail(
                "stale",
                "inherited-evidence-changed",
                "Inherited evidence changed its manifest or subject",
            )
    return binding, manifest, provenance, entries, parent


def verify(root, binding_ref, expected=None):
    binding, _manifest, _provenance, entries, parent = _verify_graph(root, binding_ref)
    if expected is not None:
        _exact_keys(expected, {"identity", "subject"}, "verification-expectation")
        expected_identity = _validate_identity(expected["identity"])
        expected_subject = _validate_reference(expected["subject"])
        if expected_identity != binding["identity"]:
            _fail("stale", "binding-identity-stale", "Binding identity differs from expectation")
        if expected_subject != _validate_reference(binding["subject"]):
            _fail("stale", "binding-subject-stale", "Binding subject differs from expectation")
    return {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "verified"},
        "binding": binding_ref,
        "identity": binding["identity"],
        "subject": binding["subject"],
        "manifest": binding["manifest"],
        "provenance": binding["provenance"],
        "lineage": {
            "status": binding["lineage"]["status"],
            "parent_verified": parent is not None,
        },
        "entry_count": len(entries),
    }


def project(root, binding_ref):
    binding, manifest, provenance, entries, parent = _verify_graph(root, binding_ref)
    return {
        "format": PROJECTION_FORMAT,
        "authority": {
            "binding": binding_ref,
            "manifest": binding["manifest"],
            "provenance": binding["provenance"],
            "parent_binding": binding["lineage"]["parent_binding"],
        },
        "identity": binding["identity"],
        "decision": binding["decision"],
        "subject": binding["subject"],
        "lineage": binding["lineage"],
        "migration": binding["migration"],
        "entries": manifest["entries"],
        "captures": provenance["captures"],
        "parent_verified": parent is not None,
        "entry_count": len(entries),
    }


def adapt_v4(root, issue, run_id=None, correction=None):
    try:
        store = inspector.resolve_store(root)
        pointer_data, pointer = inspector.read_pointer(store, issue)
        reader = inspector.AuthorityReader(store, issue)
        indexes = inspector.load_index_chain(reader, pointer, issue)
        row, binding = inspector.select_run(pointer, indexes, run_id, correction)
        reader.correction = row.get("number")
        selected = inspector.verify_selected_run(reader, issue, row, binding)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    state = selected["state_value"]
    if "migration" not in state:
        migration = {"status": "not-recorded"}
    elif state["migration"] is None:
        migration = {"status": "none"}
    elif isinstance(state["migration"], dict):
        migration = {"status": "recorded"}
    else:
        _fail("corrupt", "invalid-v4-migration", "v4 migration metadata has an invalid type")
    objects = []
    for item in sorted(reader.verified.values(), key=lambda record: record["sha256"]):
        kinds = sorted(item["kinds"] & V4_EVIDENCE_KINDS)
        if kinds:
            objects.append(
                {"sha256": item["sha256"], "size": item["size"], "kinds": kinds}
            )
    return {
        "format": V4_ADAPTER_FORMAT,
        "outcome": {"status": "resolved", "code": "adapted-read-only"},
        "source": {
            "issue": issue,
            "pointer_sha256": inspector.sha256(pointer_data),
            "run_id": binding["run_id"],
            "family_run_id": binding["family_run_id"],
            "correction": row.get("number"),
            "run_generation": binding["generation"],
            "sequence": binding["sequence"],
            "event_tip": binding["event_tip"],
        },
        "migration": migration,
        "objects": objects,
    }


def read_verified_v4_sources(
    root, checkpoint, selections, include_migration=False
):
    """Read explicitly selected reachable v4 objects against an exact checkpoint."""
    if (
        not isinstance(checkpoint, dict)
        or checkpoint.get("format") != inspector.CHECKPOINT_FORMAT
    ):
        _fail("unsupported", "unsupported-checkpoint", "A v1 inspector checkpoint is required")
    authority = checkpoint.get("authority")
    if not isinstance(authority, dict):
        _fail("corrupt", "invalid-checkpoint", "Checkpoint authority is invalid")
    issue = authority.get("issue")
    correction = authority.get("correction")
    if not _exact_int(issue) or issue < 1:
        _fail("corrupt", "invalid-checkpoint", "Checkpoint issue is invalid")
    if not isinstance(selections, list) or not selections:
        _fail("missing", "selection-missing", "At least one reachable object must be selected")
    if len(selections) > MANIFEST_ENTRY_LIMIT:
        _fail("unsupported", "manifest-entry-limit", "Selection exceeds 10,000 objects")
    normalized_selections = []
    for selection in selections:
        if not isinstance(selection, dict) or set(selection) != {
            "logical_path", "path", "entry_kind", "mode", "encoding", "object"
        }:
            _fail("corrupt", "invalid-selection", "Durable selection schema is invalid")
        reference = _validate_reference(selection["object"])
        if reference["size"] > PAYLOAD_LIMIT:
            _fail(
                "unsupported",
                "source-object-too-large",
                "Selected source object exceeds 64 MiB",
            )
        if (
            reference["kind"]
            in (inspector.JSON_OBJECT_KINDS | inspector.TYPED_PAYLOAD_KINDS)
            and reference["size"] > STRUCTURED_OBJECT_LIMIT
        ):
            _fail(
                "unsupported",
                "structured-object-too-large",
                "Selected structured source exceeds 8 MiB",
            )
        normalized_selections.append((selection, reference))
    expected_digest = checkpoint.get("checkpoint_sha256")
    unsigned = dict(checkpoint)
    unsigned.pop("checkpoint_sha256", None)
    if (
        not isinstance(expected_digest, str)
        or inspector.sha256(inspector.canonical_bytes(unsigned)) != expected_digest
    ):
        _fail("corrupt", "checkpoint-digest-mismatch", "Checkpoint digest is invalid")
    try:
        selector = (
            {"correction": correction}
            if correction is not None
            else {"run_id": authority.get("run_id")}
        )
        inspected = inspector.inspect(root, issue, **selector)
        current = inspector.checkpoint_document(inspected)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if inspector.canonical_bytes(current) != inspector.canonical_bytes(checkpoint):
        _fail("stale", "checkpoint-stale", "Durable authority differs from the migration checkpoint")
    verified_objects = authority.get("verified_objects")
    if not isinstance(verified_objects, list):
        _fail("corrupt", "invalid-checkpoint", "Checkpoint reachable objects are invalid")
    reachable = {}
    for item in verified_objects:
        if (
            not isinstance(item, dict)
            or set(item) != {"kinds", "sha256", "size"}
            or not isinstance(item["kinds"], list)
            or not all(isinstance(kind, str) for kind in item["kinds"])
        ):
            _fail("corrupt", "invalid-checkpoint", "Checkpoint reachable objects are invalid")
        reachable[(item["sha256"], item["size"])] = set(item["kinds"])
    reader = inspector.AuthorityReader(inspector.resolve_store(root), issue)
    reader.correction = correction
    state = _read_json(
        reader,
        _validate_reference(authority.get("state"), "run-state"),
        "run-state",
    )
    if "migration" not in state:
        migration = {"status": "not-recorded"}
    elif state["migration"] is None:
        migration = {"status": "none"}
    elif isinstance(state["migration"], dict):
        migration = {"status": "recorded"}
    else:
        _fail(
            "corrupt",
            "invalid-v4-migration",
            "v4 migration metadata has an invalid type",
        )
    records = []
    seen_paths = set()
    seen_objects = {}
    for selection, reference in normalized_selections:
        logical_path = _validate_path(selection["logical_path"], "selected logical path")
        _validate_path(selection["path"], "selected evidence path")
        if logical_path in seen_paths:
            _fail("ambiguous", "duplicate-selection", "A logical path is selected more than once")
        seen_paths.add(logical_path)
        key = (reference["sha256"], reference["size"])
        if reference["kind"] not in reachable.get(key, set()):
            _fail("stale", "object-not-reachable", "Selected object is not reachable from checkpoint")
        previous = seen_objects.get(reference["sha256"])
        if previous is not None and previous != selection:
            _fail("ambiguous", "conflicting-selection", "An object has conflicting selections")
        seen_objects[reference["sha256"]] = selection
        data = _read_bytes(reader, reference, reference["kind"])
        records.append(
            {
                "logical_path": logical_path,
                "kind": reference["kind"],
                "sha256": reference["sha256"],
                "size": reference["size"],
                "bytes_base64": base64.b64encode(data).decode("ascii"),
            }
        )
    if include_migration:
        return {"records": records, "migration": migration}
    return records


def _load_json(path, label):
    try:
        data = pathlib.Path(path).read_bytes()
    except OSError as error:
        _fail("missing", "%s-unreadable" % label, "Cannot read %s: %s" % (label, error))
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
        _fail("corrupt", "invalid-%s-json" % label, "%s is invalid JSON: %s" % (label, error))
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-%s-type" % label, "%s must be a JSON object" % label)
    return value


def _binding_reference(root, digest):
    if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
        _fail("unsupported", "invalid-binding-hash", "Binding hash must be 64 lowercase hex")
    store = inspector.resolve_store(root)
    path = inspector.object_path(store, digest)
    try:
        size = path.stat().st_size
    except FileNotFoundError:
        _fail("missing", "object-missing", "Evidence binding object is missing", digest)
    except OSError as error:
        _fail("missing", "object-unreadable", "Cannot inspect evidence binding: %s" % error)
    return {"kind": "evidence-binding", "sha256": digest, "size": size}


class EvidenceArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = EvidenceArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    publish_parser = subparsers.add_parser("publish")
    publish_parser.add_argument("--root", default=".")
    publish_parser.add_argument("--request", required=True)
    for name in ("verify", "project"):
        command = subparsers.add_parser(name)
        command.add_argument("--root", default=".")
        command.add_argument("--binding", required=True)
        if name == "verify":
            command.add_argument("--expect")
    adapter = subparsers.add_parser("adapt-v4")
    adapter.add_argument("issue", type=int)
    adapter.add_argument("--root", default=".")
    selectors = adapter.add_mutually_exclusive_group()
    selectors.add_argument("--run-id")
    selectors.add_argument("--correction", type=int)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        if args.command == "publish":
            document = publish(args.root, _load_json(args.request, "publication"))
        elif args.command == "verify":
            reference = _binding_reference(args.root, args.binding)
            expected = _load_json(args.expect, "expectation") if args.expect else None
            document = verify(args.root, reference, expected)
        elif args.command == "project":
            document = project(args.root, _binding_reference(args.root, args.binding))
        else:
            document = adapt_v4(
                args.root, args.issue, run_id=args.run_id, correction=args.correction
            )
        sys.stdout.buffer.write(inspector.canonical_document(document))
        return 0
    except EvidenceFailure as failure:
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
    except inspector.InspectionFailure as failure:
        wrapped = EvidenceFailure(
            failure.status, failure.code, failure.message, failure.subject
        )
        sys.stdout.buffer.write(inspector.canonical_document(wrapped.document()))
        return OUTCOME_EXIT_CODES[wrapped.status]


if __name__ == "__main__":
    raise SystemExit(main())
