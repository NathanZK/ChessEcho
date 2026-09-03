#!/usr/bin/env python3
"""Bounded, auditable repair transactions for ChessEcho workflow authority."""

import argparse
import base64
import errno
import fcntl
import json
import os
import pathlib
import stat
import sys

import workflow_inspector as inspector

try:
    import workflow_cas
except ModuleNotFoundError:
    from scripts import workflow_cas


BUNDLE_FORMAT = "chess-echo-workflow-repair-bundle-v1"
REQUEST_FORMAT = "chess-echo-workflow-repair-request-v1"
JOURNAL_FORMAT = "chess-echo-workflow-repair-journal-v1"
RESULT_FORMAT = "chess-echo-workflow-repair-result-v1"
AUDIT_FORMAT = "chess-echo-workflow-repair-audit-v1"
POINTER_BINDING_CONFIRMATION = "REPAIR ISSUE {issue} POINTER BINDING"
INTEGRITY_RESEAL_CONFIRMATION = "RESEAL ISSUE {issue} INTEGRITY"
EXIT_CODES = {"ok": 0, "stale": 7, "denied": 8, "invalid": 9, "conflict": 10}
IDENTITY_FIELDS = (
    "issue",
    "run_id",
    "family_run_id",
    "run_generation",
    "sequence",
    "state_name",
    "event_tip",
    "correction",
)
PHASES = (
    "before-journal-publication",
    "after-journal-publication",
    "before-object-publication",
    "after-object-publication",
    "before-pointer-publication",
    "after-pointer-publication",
    "before-receipt-publication",
    "after-receipt-publication",
    "before-journal-removal",
    "after-journal-removal",
)


class RepairFailure(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.audit = None
        self.bundle_sha256 = None

    def document(self):
        document = result_document(
            {"status": self.status, "code": self.code, "message": self.message}
        )
        if self.bundle_sha256 is not None:
            document["bundle_sha256"] = self.bundle_sha256
        if self.audit is not None:
            document["audit"] = self.audit
        document["result_sha256"] = inspector.sha256(
            inspector.canonical_bytes({
                key: value for key, value in document.items()
                if key != "result_sha256"
            })
        )
        return document


def phase_hook(stage):
    """Test seam for simulating interruption at a recognized transaction phase."""


def _fail(status, code, message):
    raise RepairFailure(status, code, message)


def _exact_int(value):
    return type(value) is int


def _record(data):
    return {
        "bytes_base64": base64.b64encode(data).decode("ascii"),
        "sha256": inspector.sha256(data),
        "size": len(data),
    }


def _decode_record(value, label):
    if not isinstance(value, dict) or set(value) != {
        "bytes_base64",
        "sha256",
        "size",
    }:
        _fail("invalid", "invalid-byte-record", "%s byte record is malformed" % label)
    digest = value.get("sha256")
    size = value.get("size")
    if (
        not isinstance(digest, str)
        or inspector.SHA256_RE.fullmatch(digest) is None
        or not _exact_int(size)
        or size < 0
    ):
        _fail("invalid", "invalid-byte-record", "%s hash or size is invalid" % label)
    try:
        data = base64.b64decode(value["bytes_base64"], validate=True)
    except (TypeError, ValueError):
        _fail("invalid", "invalid-base64", "%s bytes are not valid base64" % label)
    if len(data) != size or inspector.sha256(data) != digest:
        _fail("invalid", "byte-record-mismatch", "%s bytes fail hash or size" % label)
    return data


def _load_canonical(path, label):
    try:
        data = pathlib.Path(path).read_bytes()
    except OSError:
        _fail("invalid", "%s-unreadable" % label, "%s is unreadable" % label)
    try:
        value = inspector.parse_json_object(data.rstrip(b"\n"), label)
    except inspector.InspectionFailure as failure:
        _fail("invalid", failure.code, failure.message)
    if data != inspector.canonical_document(value):
        _fail("invalid", "noncanonical-%s" % label, "%s is not canonical JSON" % label)
    return value


def _validate_digest(document, field, label):
    if not isinstance(document, dict):
        _fail("invalid", "invalid-%s" % label, "%s must be an object" % label)
    digest = document.get(field)
    if not isinstance(digest, str) or inspector.SHA256_RE.fullmatch(digest) is None:
        _fail("invalid", "invalid-%s-digest" % label, "%s digest is invalid" % label)
    unhashed = dict(document)
    unhashed.pop(field)
    if inspector.sha256(inspector.canonical_bytes(unhashed)) != digest:
        _fail("invalid", "%s-digest-mismatch" % label, "%s digest does not verify" % label)


def _fixed_hex(value, length):
    return (
        isinstance(value, str)
        and len(value) == length
        and all(character in "0123456789abcdef" for character in value)
    )


def _checkpoint_reference(value, expected_kind=None):
    if not isinstance(value, dict):
        return False
    kind = value.get("kind")
    keys = {"kind", "sha256", "size"}
    if isinstance(kind, str) and kind in inspector.TYPED_PAYLOAD_KINDS:
        keys.add("encoding")
    if (
        set(value) != keys
        or not isinstance(kind, str)
        or kind not in inspector.SUPPORTED_OBJECT_KINDS
        or (expected_kind is not None and kind != expected_kind)
        or not _fixed_hex(value.get("sha256"), 64)
        or not _exact_int(value.get("size"))
        or value["size"] < 0
    ):
        return False
    return (
        value.get("encoding") == "typed-base64"
        if kind in inspector.TYPED_PAYLOAD_KINDS
        else True
    )


def _checkpoint_latest_validation(value):
    if not isinstance(value, dict) or not isinstance(value.get("status"), str):
        return False
    if value == {"status": "not-recorded"}:
        return True
    if set(value) not in ({"status", "checks"}, {"status", "attempt_id", "checks"}):
        return False
    if "attempt_id" in value and not isinstance(value["attempt_id"], str):
        return False
    if not isinstance(value["checks"], list):
        return False
    for check in value["checks"]:
        if not isinstance(check, dict):
            return False
        allowed = {"name", "status", "raw_log", "incremental_log", "result"}
        if (
            not {"name", "status"} <= set(check) <= allowed
            or not isinstance(check["name"], str)
            or not isinstance(check["status"], str)
            or any(
                field in check and not _checkpoint_reference(check[field])
                for field in ("raw_log", "incremental_log", "result")
            )
        ):
            return False
    return True


def _checkpoint_schema_valid(checkpoint):
    top_level = {
        "format",
        "canonicalization",
        "inspector",
        "authority",
        "repository",
        "observations",
        "checkpoint_sha256",
    }
    if not isinstance(checkpoint, dict) or set(checkpoint) != top_level:
        return False
    identity = checkpoint.get("inspector")
    if (
        not isinstance(identity, dict)
        or set(identity) != {"version", "source_sha256"}
        or not isinstance(identity.get("version"), str)
        or not _fixed_hex(identity.get("source_sha256"), 64)
    ):
        return False

    authority = checkpoint.get("authority")
    authority_required = {
        "issue",
        "pointer_sha256",
        "index_generation",
        "index",
        "run_id",
        "family_run_id",
        "run_generation",
        "sequence",
        "state_name",
        "event_tip",
        "envelope",
        "state",
        "history",
        "event",
        "verified_objects",
    }
    if (
        not isinstance(authority, dict)
        or set(authority) not in (authority_required, authority_required | {"correction"})
        or not _exact_int(authority.get("issue"))
        or authority["issue"] < 1
        or not _fixed_hex(authority.get("pointer_sha256"), 64)
        or not _exact_int(authority.get("index_generation"))
        or authority["index_generation"] < 0
        or not _checkpoint_reference(authority.get("index"), "issue-index")
        or not _fixed_hex(authority.get("run_id"), 32)
        or not _fixed_hex(authority.get("family_run_id"), 32)
        or not _exact_int(authority.get("run_generation"))
        or authority["run_generation"] < 0
        or not _exact_int(authority.get("sequence"))
        or authority["sequence"] < 0
        or not isinstance(authority.get("state_name"), str)
        or not _fixed_hex(authority.get("event_tip"), 64)
        or not _checkpoint_reference(authority.get("envelope"), "run-envelope")
        or not _checkpoint_reference(authority.get("state"), "run-state")
        or not _checkpoint_reference(authority.get("history"), "run-history")
        or not _checkpoint_reference(authority.get("event"), "run-event")
        or not isinstance(authority.get("verified_objects"), list)
        or (
            "correction" in authority
            and (
                not _exact_int(authority["correction"])
                or authority["correction"] < 1
            )
        )
    ):
        return False
    for item in authority["verified_objects"]:
        if (
            not isinstance(item, dict)
            or set(item) != {"sha256", "size", "kinds"}
            or not _fixed_hex(item.get("sha256"), 64)
            or not _exact_int(item.get("size"))
            or item["size"] < 0
            or not isinstance(item.get("kinds"), list)
            or not item["kinds"]
            or any(
                not isinstance(kind, str)
                or kind not in inspector.SUPPORTED_OBJECT_KINDS
                for kind in item["kinds"]
            )
            or item["kinds"] != sorted(set(item["kinds"]))
        ):
            return False
    if authority["verified_objects"] != sorted(
        authority["verified_objects"], key=lambda item: item["sha256"]
    ) or len({
        item["sha256"] for item in authority["verified_objects"]
    }) != len(authority["verified_objects"]):
        return False

    repository = checkpoint.get("repository")
    if not isinstance(repository, dict) or set(repository) != {
        "head",
        "base",
        "merge_base",
        "head_descends_from_resolved_base",
        "commit_count_from_resolved_base",
        "worktree",
    }:
        return False
    head = repository.get("head")
    base = repository.get("base")
    worktree = repository.get("worktree")
    if (
        not isinstance(head, dict)
        or set(head) != {"commit", "tree"}
        or not _fixed_hex(head.get("commit"), 40)
        or not _fixed_hex(head.get("tree"), 40)
        or not isinstance(base, dict)
        or set(base) != {
            "ref",
            "recorded_commit",
            "resolved_commit",
            "tree",
            "matches_recorded",
        }
        or not isinstance(base.get("ref"), str)
        or not _fixed_hex(base.get("recorded_commit"), 40)
        or not _fixed_hex(base.get("resolved_commit"), 40)
        or not _fixed_hex(base.get("tree"), 40)
        or type(base.get("matches_recorded")) is not bool
        or not _fixed_hex(repository.get("merge_base"), 40)
        or type(repository.get("head_descends_from_resolved_base")) is not bool
        or not _exact_int(repository.get("commit_count_from_resolved_base"))
        or repository["commit_count_from_resolved_base"] < 0
        or not isinstance(worktree, dict)
        or set(worktree) != {"clean", "status_sha256"}
        or type(worktree.get("clean")) is not bool
        or not _fixed_hex(worktree.get("status_sha256"), 64)
    ):
        return False

    observations = checkpoint.get("observations")
    return (
        isinstance(observations, dict)
        and set(observations) == {"latest_validation"}
        and _checkpoint_latest_validation(observations["latest_validation"])
    )


def validate_checkpoint(checkpoint):
    if (
        not _checkpoint_schema_valid(checkpoint)
        or checkpoint.get("format") != inspector.CHECKPOINT_FORMAT
        or checkpoint.get("canonicalization") != inspector.CANONICALIZATION
        or checkpoint.get("inspector") != inspector.inspector_identity()
    ):
        _fail("invalid", "invalid-checkpoint", "Checkpoint is not a supported public #128 checkpoint")
    _validate_digest(checkpoint, "checkpoint_sha256", "checkpoint")
    return checkpoint


def _selector(value):
    if not isinstance(value, dict) or len(value) != 1:
        _fail("invalid", "invalid-selector", "Selector must contain exactly one selector")
    if value == {"current": True}:
        return {}
    if set(value) == {"run_id"}:
        run_id = value["run_id"]
        if not isinstance(run_id, str) or inspector.RUN_ID_RE.fullmatch(run_id) is None:
            _fail("invalid", "invalid-selector", "Run selector is invalid")
        return {"run_id": run_id}
    if set(value) == {"correction"} and _exact_int(value["correction"]) and value["correction"] > 0:
        return {"correction": value["correction"]}
    _fail("invalid", "invalid-selector", "Selector is unsupported")


def _validate_request(request, issue):
    required = {
        "format",
        "issue",
        "selector",
        "operation",
        "objects",
        "operator",
        "reason",
        "confirmation",
    }
    if not isinstance(request, dict) or set(request) != required:
        _fail("invalid", "invalid-request-schema", "Repair request schema is invalid")
    if (
        request.get("format") != REQUEST_FORMAT
        or request.get("issue") != issue
        or not _exact_int(issue)
        or issue < 1
    ):
        _fail("invalid", "invalid-request-identity", "Repair request issue or format is invalid")
    _selector(request["selector"])
    for field in ("operator", "reason"):
        if not isinstance(request[field], str) or not request[field].strip():
            _fail("denied", "missing-%s" % field, "Repair %s must be nonempty" % field)
    operation = request["operation"]
    if not isinstance(operation, dict) or not isinstance(operation.get("type"), str):
        _fail("invalid", "invalid-operation", "Repair operation is malformed")
    operation_type = operation["type"]
    expected = (
        POINTER_BINDING_CONFIRMATION if operation_type == "pointer-binding"
        else INTEGRITY_RESEAL_CONFIRMATION if operation_type == "integrity-reseal"
        else None
    )
    if expected is None:
        _fail("denied", "unsupported-operation", "Repair operation is not allowlisted")
    if request["confirmation"] != expected.format(issue=issue):
        _fail("denied", "confirmation-mismatch", "Exact operation confirmation is required")
    if operation_type == "pointer-binding" and set(operation) != {"type", "binding"}:
        _fail("invalid", "invalid-operation", "Pointer-binding operation schema is invalid")
    if operation_type == "integrity-reseal" and set(operation) != {
        "type",
        "target_pointer",
        "source_inspection_failure",
    }:
        _fail("invalid", "invalid-operation", "Integrity-reseal operation schema is invalid")
    return operation_type


def _object_records(records):
    if not isinstance(records, list):
        _fail("invalid", "invalid-objects", "Bundled objects must be a list")
    decoded = {}
    normalized = []
    for item in records:
        if not isinstance(item, dict) or set(item) != {
            "kind",
            "sha256",
            "size",
            "bytes_base64",
        }:
            _fail("invalid", "invalid-object-record", "Bundled object record is malformed")
        try:
            raw_reference = {key: item[key] for key in ("kind", "sha256", "size")}
            if item["kind"] in inspector.TYPED_PAYLOAD_KINDS:
                raw_reference["encoding"] = "typed-base64"
            validated = inspector.validate_reference(raw_reference)
            reference = {key: validated[key] for key in ("kind", "sha256", "size")}
        except inspector.InspectionFailure as failure:
            _fail("invalid", failure.code, failure.message)
        data = _decode_record(
            {key: item[key] for key in ("bytes_base64", "sha256", "size")},
            "bundled object",
        )
        if reference["kind"] in inspector.JSON_OBJECT_KINDS | inspector.TYPED_PAYLOAD_KINDS:
            try:
                value = inspector.parse_json_object(data, "bundled structured object")
                canonical = inspector.canonical_bytes(value)
            except inspector.InspectionFailure as failure:
                _fail("invalid", failure.code, failure.message)
            if canonical != data:
                _fail("invalid", "noncanonical-object-json", "Bundled structured object is not canonical")
        old = decoded.get(reference["sha256"])
        if old is not None and old != data:
            _fail("invalid", "conflicting-bundled-object", "Bundled object hashes conflict")
        decoded[reference["sha256"]] = data
        normalized.append({**reference, "bytes_base64": item["bytes_base64"]})
    if len(decoded) != len(records):
        _fail("invalid", "duplicate-bundled-object", "Bundled object hashes must be unique")
    normalized.sort(key=lambda item: (item["sha256"], item["kind"]))
    return normalized, decoded


class OverlayAuthorityReader(inspector.AuthorityReader):
    def __init__(self, store, issue, overlay):
        super().__init__(store, issue)
        self.overlay = overlay

    def read_bytes(self, reference, expected_kind=None):
        normalized = inspector.validate_reference(reference, expected_kind)
        data = self.overlay.get(normalized["sha256"])
        if data is None:
            return super().read_bytes(normalized, expected_kind)
        if len(data) != normalized["size"] or inspector.sha256(data) != normalized["sha256"]:
            raise inspector.InspectionFailure(
                "corrupt",
                "object-hash-or-size-mismatch",
                "Overlay object failed SHA-256 or size verification",
                normalized["sha256"],
            )
        verified = self.verified.setdefault(
            normalized["sha256"],
            {"sha256": normalized["sha256"], "size": normalized["size"], "kinds": set()},
        )
        if verified["size"] != normalized["size"]:
            raise inspector.InspectionFailure(
                "corrupt", "conflicting-object-size", "Overlay object size conflicts"
            )
        verified["kinds"].add(normalized["kind"])
        return data


def _parse_pointer(data, issue):
    try:
        pointer = inspector.parse_json_object(data, "issue pointer")
    except inspector.InspectionFailure as failure:
        raise failure
    if inspector.canonical_bytes(pointer) != data:
        raise inspector.InspectionFailure(
            "corrupt", "noncanonical-pointer", "Issue pointer is not canonically serialized"
        )
    if (
        pointer.get("format") != inspector.POINTER_FORMAT
        or pointer.get("issue") != issue
        or not _exact_int(pointer.get("generation"))
        or pointer["generation"] < 0
        or not isinstance(pointer.get("index"), dict)
        or not isinstance(pointer.get("selection"), dict)
    ):
        raise inspector.InspectionFailure(
            "corrupt", "invalid-pointer-schema", "Issue pointer schema or identity is invalid"
        )
    return pointer


def inspect_overlay(root, issue, pointer_data, overlay, selector):
    store = inspector.resolve_store(root)
    pointer = _parse_pointer(pointer_data, issue)
    reader = OverlayAuthorityReader(store, issue, overlay)
    indexes = inspector.load_index_chain(reader, pointer, issue)
    row, binding = inspector.select_run(pointer, indexes, **_selector(selector))
    reader.correction = row.get("number")
    selected = inspector.verify_selected_run(reader, issue, row, binding)
    repository = inspector.inspect_repository(store.repository_root, selected["state_value"])
    objects = [
        {"sha256": item["sha256"], "size": item["size"], "kinds": sorted(item["kinds"])}
        for item in sorted(reader.verified.values(), key=lambda item: item["sha256"])
    ]
    authority = {
        "issue": issue,
        "pointer_sha256": inspector.sha256(pointer_data),
        "index_generation": pointer["generation"],
        "index": inspector.validate_reference(pointer["index"], "issue-index"),
        "run_id": binding["run_id"],
        "family_run_id": binding["family_run_id"],
        "run_generation": binding["generation"],
        "sequence": binding["sequence"],
        "state_name": selected["state_value"]["state"],
        "event_tip": binding["event_tip"],
        "envelope": selected["envelope"],
        "state": selected["state"],
        "history": selected["history"],
        "event": selected["event"],
        "verified_objects": objects,
    }
    if row.get("number") is not None:
        authority["correction"] = row["number"]
    return {
        "inspector": inspector.inspector_identity(),
        "authority": authority,
        "repository": repository,
        "observations": {
            "latest_validation": inspector.latest_validation_observation(selected["state_value"])
        },
    }


def _checkpoint_for(subject):
    return inspector.checkpoint_document(subject)


def _checkpoint_equal(actual, expected, code="checkpoint-mismatch", status="stale"):
    if inspector.canonical_bytes(actual) != inspector.canonical_bytes(expected):
        _fail(status, code, "Authority or repository facts do not match the checkpoint")


def _logical_identity(checkpoint):
    authority = checkpoint["authority"]
    return {field: authority.get(field) for field in IDENTITY_FIELDS}


def _read_pointer_path_exact(path):
    try:
        info = path.lstat()
    except OSError:
        _fail("stale", "source-pointer-unreadable", "Source issue pointer is unreadable")
    if not stat.S_ISREG(info.st_mode):
        _fail("conflict", "source-pointer-not-regular", "Source issue pointer is not a regular file")
    try:
        data = path.read_bytes()
    except OSError:
        _fail("stale", "source-pointer-unreadable", "Source issue pointer is unreadable")
    return data


def _read_pointer_exact(store, issue):
    path = store.store_dir / "issues" / str(issue) / "index-integrity.json"
    return path, _read_pointer_path_exact(path)


def _binding_without_internal(binding):
    return {
        key: value for key, value in binding.items()
        if key not in {"_from_pointer", "state", "history", "event"}
    }


def _validate_binding_shape(binding):
    required = {
        "run_id",
        "family_run_id",
        "number",
        "generation",
        "sequence",
        "event_tip",
        "envelope",
        "status",
        "supersedes",
    }
    if not isinstance(binding, dict) or set(binding) != required:
        _fail("invalid", "invalid-target-binding", "Target run binding schema is invalid")
    return dict(binding)


def _verified_index_binding(reader, issue, pointer, indexes, selector):
    row, _selected_binding = inspector.select_run(
        pointer, indexes, **_selector(selector)
    )
    binding = next(
        (
            index.get("run_update")
            for _reference, index in indexes
            if isinstance(index.get("run_update"), dict)
            and index["run_update"].get("run_id") == row["run_id"]
        ),
        None,
    )
    if not isinstance(binding, dict):
        _fail("denied", "source-binding-missing", "Selected run has no indexed source binding")
    try:
        selected = inspector.verify_selected_run(reader, issue, row, binding)
    except inspector.InspectionFailure as failure:
        _fail("denied", "source-%s" % failure.code, failure.message)
    return row, dict(binding), selected


def _source_preconditions(checkpoint, pointer_record, failure=None):
    authority = checkpoint["authority"]
    result = {
        "checkpoint_sha256": checkpoint["checkpoint_sha256"],
        "pointer": pointer_record,
        "index_generation": authority["index_generation"],
        "index": authority["index"],
        "run_id": authority["run_id"],
        "run_generation": authority["run_generation"],
        "sequence": authority["sequence"],
        "event_tip": authority["event_tip"],
        "repository": checkpoint["repository"],
    }
    if failure is not None:
        result["inspection_failure"] = failure
    return result


def _build_plan(issue, operation_type, source_record, target_record, objects, checkpoint):
    return {
        "issue": issue,
        "operation": operation_type,
        "source_pointer_sha256": source_record["sha256"],
        "target_pointer_sha256": target_record["sha256"],
        "publish_objects": [
            {key: item[key] for key in ("kind", "sha256", "size")} for item in objects
        ],
        "expected_checkpoint_sha256": checkpoint["checkpoint_sha256"],
        "commit_point": "atomic-issue-pointer-replace",
        "audit": "immutable-content-addressed-receipt",
    }


def _verify_pointer_binding_contract(root, bundle, decoded):
    issue = bundle["issue"]
    source_pointer = _parse_pointer(decoded["source_data"], issue)
    target_pointer = _parse_pointer(decoded["target_data"], issue)
    if (
        target_pointer["generation"] != source_pointer["generation"] + 1
        or target_pointer["format"] != source_pointer["format"]
        or target_pointer["issue"] != source_pointer["issue"]
    ):
        _fail("denied", "pointer-binding-generation", "Pointer-binding must add exactly one generation")
    store = inspector.resolve_store(root)
    source_reader = inspector.AuthorityReader(store, issue)
    source_indexes = inspector.load_index_chain(source_reader, source_pointer, issue)
    _source_row, source_binding, _source_selected = _verified_index_binding(
        source_reader, issue, source_pointer, source_indexes, bundle["selector"]
    )
    target_reader = OverlayAuthorityReader(store, issue, decoded["overlay"])
    target_indexes = inspector.load_index_chain(target_reader, target_pointer, issue)
    source_latest = source_indexes[0][1]
    target_latest = target_indexes[0][1]
    if target_pointer["index"]["sha256"] not in decoded["overlay"]:
        _fail("invalid", "missing-target-index-object", "New issue index must be bundled")
    if target_latest.get("previous_index") != inspector.validate_reference(
        source_pointer["index"], "issue-index"
    ):
        _fail("denied", "pointer-binding-predecessor", "New issue index must name the source index")
    preserved = set(source_latest) - {"generation", "previous_index", "run_update"}
    if set(target_latest) != set(source_latest) or any(
        target_latest[key] != source_latest[key] for key in preserved
    ):
        _fail("denied", "pointer-binding-table-change", "Pointer-binding must preserve index tables")
    if target_latest.get("run_update") != source_binding:
        _fail(
            "denied",
            "pointer-binding-run-update",
            "New index must republish the exact verified source binding",
        )
    _row, binding = inspector.select_run(
        target_pointer, target_indexes, **_selector(bundle["selector"])
    )
    if _binding_without_internal(binding) != source_binding:
        _fail(
            "denied",
            "pointer-binding-run-update",
            "Target pointer must retain the exact verified source authority",
        )
    if target_pointer["selection"] != source_pointer["selection"]:
        _fail("denied", "pointer-binding-selection", "Target pointer selection is not bounded")


def _verify_integrity_reseal_contract(root, bundle, decoded):
    issue = bundle["issue"]
    source_pointer = _parse_pointer(decoded["source_data"], issue)
    target_pointer = _parse_pointer(decoded["target_data"], issue)
    if (
        target_pointer.get("format") != source_pointer.get("format")
        or target_pointer.get("issue") != source_pointer.get("issue")
        or target_pointer.get("generation") != source_pointer.get("generation")
        or target_pointer.get("selection", {}).get("current_normal_run_id")
        != source_pointer.get("selection", {}).get("current_normal_run_id")
    ):
        _fail("denied", "reseal-pointer-identity", "Reseal must preserve pointer identity and generation")
    store = inspector.resolve_store(root)
    source_reader = inspector.AuthorityReader(store, issue)
    try:
        source_indexes = inspector.load_index_chain(source_reader, source_pointer, issue)
    except inspector.InspectionFailure:
        _fail("denied", "unsupported-reseal-scope", "Reseal requires a readable source index chain")
    target_reader = OverlayAuthorityReader(store, issue, decoded["overlay"])
    target_indexes = inspector.load_index_chain(target_reader, target_pointer, issue)
    source_latest = source_indexes[0][1]
    target_latest = target_indexes[0][1]
    if target_pointer["index"]["sha256"] not in decoded["overlay"]:
        _fail("invalid", "missing-target-index-object", "Resealed issue index must be bundled")
    source_update = source_latest.get("run_update")
    target_update = target_latest.get("run_update")
    source_row, source_binding = inspector.select_run(
        source_pointer, source_indexes, **_selector(bundle["selector"])
    )
    if (
        not isinstance(source_update, dict)
        or not isinstance(target_update, dict)
        or source_update.get("run_id") != source_row.get("run_id")
        or _binding_without_internal(source_binding) != source_update
        or target_update.get("run_id") != source_update.get("run_id")
    ):
        _fail("denied", "unsupported-reseal-scope", "Reseal is limited to the selected latest binding")
    old_envelope = inspector.validate_reference(
        source_update.get("envelope"), "run-envelope"
    )
    new_envelope = inspector.validate_reference(
        target_update.get("envelope"), "run-envelope"
    )
    if new_envelope["sha256"] not in decoded["overlay"]:
        _fail("invalid", "missing-resealed-envelope", "Resealed envelope must be bundled")
    allowed_objects = {
        target_pointer["index"]["sha256"],
        new_envelope["sha256"],
    }
    if {item["sha256"] for item in decoded["records"]} != allowed_objects:
        _fail(
            "denied",
            "unsupported-reseal-scope",
            "Reseal may publish only its replacement envelope and issue index",
        )
    for field in ("state", "history", "event"):
        if bundle["target"]["authority"][field] != bundle["checkpoint"]["authority"][field]:
            _fail("denied", "unsupported-reseal-scope", "Reseal cannot alter state, history, or event")
    envelope_value = target_reader.read_json(new_envelope, "run-envelope")
    marker = envelope_value.get("integrity_reseal")
    expected_marker = {
        "checkpoint_sha256": bundle["checkpoint"]["checkpoint_sha256"],
        "source_envelope_sha256": old_envelope["sha256"],
    }
    if marker != expected_marker:
        _fail("denied", "invalid-reseal-marker", "Resealed envelope lacks its exact provenance marker")
    original_envelope = dict(envelope_value)
    original_envelope.pop("integrity_reseal")
    original_bytes = inspector.canonical_bytes(original_envelope)
    if (
        len(original_bytes) != old_envelope["size"]
        or inspector.sha256(original_bytes) != old_envelope["sha256"]
    ):
        _fail("denied", "reseal-content-change", "Resealed envelope changes original logical content")
    if bundle["source"].get("inspection_failure") is not None:
        try:
            reconstructed = _checkpoint_for(
                inspect_overlay(
                    root,
                    issue,
                    decoded["source_data"],
                    {old_envelope["sha256"]: original_bytes},
                    bundle["selector"],
                )
            )
        except inspector.InspectionFailure as failure:
            _fail(
                "denied",
                "unsupported-reseal-scope",
                "Original source authority cannot be reconstructed: %s" % failure.message,
            )
        _checkpoint_equal(
            reconstructed,
            bundle["checkpoint"],
            "source-checkpoint-mismatch",
            status="denied",
        )
    normalized_index = dict(target_latest)
    normalized_update = dict(target_update)
    normalized_update["envelope"] = old_envelope
    normalized_index["run_update"] = normalized_update
    if normalized_index != source_latest:
        _fail("denied", "reseal-index-change", "Reseal may only replace the selected envelope reference")
    normalized_pointer = dict(target_pointer)
    normalized_pointer["index"] = inspector.validate_reference(
        source_pointer["index"], "issue-index"
    )
    normalized_selection = dict(target_pointer["selection"])
    if target_update["run_id"] == target_latest["current_normal_run_id"]:
        normalized_run = dict(normalized_selection["run"])
        normalized_run["envelope"] = old_envelope
        normalized_selection["run"] = normalized_run
    normalized_pointer["selection"] = normalized_selection
    if normalized_pointer != source_pointer:
        _fail("denied", "reseal-pointer-change", "Reseal may only replace bounded integrity references")


def prepare(root, issue, checkpoint_file, request_file):
    checkpoint = validate_checkpoint(_load_canonical(checkpoint_file, "checkpoint"))
    request = _load_canonical(request_file, "request")
    operation_type = _validate_request(request, issue)
    if checkpoint["authority"].get("issue") != issue:
        _fail("invalid", "checkpoint-issue-mismatch", "Checkpoint issue does not match request")
    records, overlay = _object_records(request["objects"])
    store = inspector.resolve_store(root)
    _path, source_pointer_data = _read_pointer_exact(store, issue)
    source_pointer_record = _record(source_pointer_data)
    selector = request["selector"]
    operation = request["operation"]
    source_failure = None
    try:
        source_checkpoint = _checkpoint_for(
            inspector.inspect(store.repository_root, issue, **_selector(selector))
        )
    except inspector.InspectionFailure as failure:
        source_failure = {"status": failure.status, "code": failure.code}

    if operation_type == "pointer-binding":
        if source_failure is not None:
            _fail("stale", "source-not-resolved", "Pointer-binding requires resolved source authority")
        _checkpoint_equal(source_checkpoint, checkpoint)
        pointer = _parse_pointer(source_pointer_data, issue)
        reader = inspector.AuthorityReader(store, issue)
        indexes = inspector.load_index_chain(reader, pointer, issue)
        row, source_binding, _selected = _verified_index_binding(
            reader, issue, pointer, indexes, selector
        )
        binding = _validate_binding_shape(operation["binding"])
        if binding != source_binding:
            _fail(
                "denied",
                "binding-source-mismatch",
                "Target binding must exactly equal the verified indexed source binding",
            )
        latest = indexes[0][1]
        target_index = dict(latest)
        target_index["generation"] = pointer["generation"] + 1
        target_index["previous_index"] = inspector.validate_reference(pointer["index"], "issue-index")
        target_index["run_update"] = binding
        index_data = inspector.canonical_bytes(target_index)
        index_ref = {
            "kind": "issue-index",
            "sha256": inspector.sha256(index_data),
            "size": len(index_data),
        }
        overlay[index_ref["sha256"]] = index_data
        records.append({**index_ref, "bytes_base64": base64.b64encode(index_data).decode("ascii")})
        records.sort(key=lambda item: (item["sha256"], item["kind"]))
        target_pointer = dict(pointer)
        target_pointer["generation"] += 1
        target_pointer["index"] = index_ref
        target_pointer_data = inspector.canonical_bytes(target_pointer)
    else:
        declared = operation["source_inspection_failure"]
        if declared is not None and (
            not isinstance(declared, dict)
            or set(declared) != {"status", "code"}
            or declared.get("status") not in {"missing", "corrupt"}
            or not isinstance(declared.get("code"), str)
            or not declared["code"]
        ):
            _fail("invalid", "invalid-source-failure", "Declared source inspection failure is invalid")
        if source_failure is None:
            if declared is not None:
                _fail("stale", "source-failure-mismatch", "Source authority is currently resolved")
            _checkpoint_equal(source_checkpoint, checkpoint)
        elif declared != source_failure:
            _fail("stale", "source-failure-mismatch", "Declared source failure does not match inspection")
        target_pointer_data = _decode_record(operation["target_pointer"], "target pointer")

    try:
        target_subject = inspect_overlay(
            store.repository_root, issue, target_pointer_data, overlay, selector
        )
    except inspector.InspectionFailure as failure:
        _fail("invalid", "target-%s" % failure.code, failure.message)
    target_checkpoint = _checkpoint_for(target_subject)
    if (
        _logical_identity(target_checkpoint) != _logical_identity(checkpoint)
        or target_checkpoint["repository"] != checkpoint["repository"]
        or target_checkpoint["observations"] != checkpoint["observations"]
    ):
        _fail(
            "denied",
            "logical-authority-change",
            "Repair changes lifecycle identity, sequence, event tip, or repository facts",
        )
    target_pointer_record = _record(target_pointer_data)
    plan = _build_plan(
        issue,
        operation_type,
        source_pointer_record,
        target_pointer_record,
        records,
        target_checkpoint,
    )
    bundle = {
        "format": BUNDLE_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "issue": issue,
        "selector": selector,
        "operation": {"type": operation_type},
        "authorization": {
            "operator": request["operator"],
            "reason": request["reason"],
            "confirmation": request["confirmation"],
        },
        "checkpoint": checkpoint,
        "source": _source_preconditions(
            checkpoint, source_pointer_record, source_failure
        ),
        "target": {
            "pointer": target_pointer_record,
            "checkpoint": target_checkpoint,
            "authority": target_checkpoint["authority"],
        },
        "objects": records,
        "plan": plan,
    }
    bundle["bundle_sha256"] = inspector.sha256(inspector.canonical_bytes(bundle))
    validate_bundle(bundle, store.repository_root)
    return bundle


def validate_bundle(bundle, root=None):
    if (
        not isinstance(bundle, dict)
        or bundle.get("format") != BUNDLE_FORMAT
        or bundle.get("canonicalization") != inspector.CANONICALIZATION
    ):
        _fail("invalid", "invalid-bundle-format", "Repair bundle format is unsupported")
    _validate_digest(bundle, "bundle_sha256", "bundle")
    required = {
        "format",
        "canonicalization",
        "issue",
        "selector",
        "operation",
        "authorization",
        "checkpoint",
        "source",
        "target",
        "objects",
        "plan",
        "bundle_sha256",
    }
    if set(bundle) != required or not _exact_int(bundle.get("issue")) or bundle["issue"] < 1:
        _fail("invalid", "invalid-bundle-schema", "Repair bundle schema is invalid")
    issue = bundle["issue"]
    _selector(bundle["selector"])
    validate_checkpoint(bundle["checkpoint"])
    if bundle["checkpoint"]["authority"].get("issue") != issue:
        _fail(
            "invalid",
            "bundle-issue-mismatch",
            "Bundle issue does not match its source checkpoint",
        )
    operation = bundle["operation"]
    if not isinstance(operation, dict) or set(operation) != {"type"}:
        _fail("invalid", "invalid-bundle-operation", "Bundle operation is malformed")
    operation_type = operation["type"]
    expected_confirmation = (
        POINTER_BINDING_CONFIRMATION if operation_type == "pointer-binding"
        else INTEGRITY_RESEAL_CONFIRMATION if operation_type == "integrity-reseal"
        else None
    )
    authorization = bundle["authorization"]
    if (
        expected_confirmation is None
        or not isinstance(authorization, dict)
        or set(authorization) != {"operator", "reason", "confirmation"}
        or any(
            not isinstance(authorization.get(field), str)
            or not authorization[field].strip()
            for field in ("operator", "reason")
        )
        or authorization.get("confirmation") != expected_confirmation.format(issue=issue)
    ):
        _fail("denied", "invalid-authorization", "Bundle authorization is invalid")
    records, overlay = _object_records(bundle["objects"])
    source = bundle["source"]
    target = bundle["target"]
    if not isinstance(source, dict) or set(source) not in (
        {
            "checkpoint_sha256",
            "pointer",
            "index_generation",
            "index",
            "run_id",
            "run_generation",
            "sequence",
            "event_tip",
            "repository",
        },
        {
            "checkpoint_sha256",
            "pointer",
            "index_generation",
            "index",
            "run_id",
            "run_generation",
            "sequence",
            "event_tip",
            "repository",
            "inspection_failure",
        },
    ):
        _fail("invalid", "invalid-source-preconditions", "Source preconditions are malformed")
    source_data = _decode_record(source["pointer"], "source pointer")
    if source["checkpoint_sha256"] != bundle["checkpoint"]["checkpoint_sha256"]:
        _fail("invalid", "source-checkpoint-mismatch", "Source checkpoint digest is inconsistent")
    authority = bundle["checkpoint"]["authority"]
    for source_key, authority_key in (
        ("index_generation", "index_generation"),
        ("index", "index"),
        ("run_id", "run_id"),
        ("run_generation", "run_generation"),
        ("sequence", "sequence"),
        ("event_tip", "event_tip"),
    ):
        if source[source_key] != authority[authority_key]:
            _fail("invalid", "source-precondition-mismatch", "Source preconditions are inconsistent")
    if source["repository"] != bundle["checkpoint"]["repository"]:
        _fail("invalid", "source-repository-mismatch", "Source repository facts are inconsistent")
    if (
        not isinstance(target, dict)
        or set(target) != {"pointer", "checkpoint", "authority"}
    ):
        _fail("invalid", "invalid-target", "Bundle target is malformed")
    target_data = _decode_record(target["pointer"], "target pointer")
    validate_checkpoint(target["checkpoint"])
    if target["checkpoint"]["authority"].get("issue") != issue:
        _fail(
            "invalid",
            "bundle-issue-mismatch",
            "Bundle issue does not match its target checkpoint",
        )
    if target["authority"] != target["checkpoint"]["authority"]:
        _fail("invalid", "target-authority-mismatch", "Target authority is inconsistent")
    if (
        _logical_identity(target["checkpoint"]) != _logical_identity(bundle["checkpoint"])
        or target["checkpoint"]["repository"] != bundle["checkpoint"]["repository"]
        or target["checkpoint"]["observations"] != bundle["checkpoint"]["observations"]
    ):
        _fail("denied", "logical-authority-change", "Bundle changes logical authority")
    expected_plan = _build_plan(
        issue,
        operation_type,
        source["pointer"],
        target["pointer"],
        records,
        target["checkpoint"],
    )
    if bundle["plan"] != expected_plan:
        _fail("invalid", "plan-mismatch", "Bundle mutation plan is inconsistent")
    if root is not None:
        try:
            if operation_type == "pointer-binding":
                _verify_pointer_binding_contract(root, bundle, {
                    "source_data": source_data,
                    "target_data": target_data,
                    "overlay": overlay,
                    "records": records,
                })
            else:
                _verify_integrity_reseal_contract(root, bundle, {
                    "source_data": source_data,
                    "target_data": target_data,
                    "overlay": overlay,
                    "records": records,
                })
            actual = _checkpoint_for(
                inspect_overlay(root, issue, target_data, overlay, bundle["selector"])
            )
        except inspector.InspectionFailure as failure:
            _fail("invalid", "target-%s" % failure.code, failure.message)
        _checkpoint_equal(actual, target["checkpoint"], "target-checkpoint-mismatch")
        reachable = {
            item["sha256"] for item in target["checkpoint"]["authority"]["verified_objects"]
        }
        if any(item["sha256"] not in reachable for item in records):
            _fail("denied", "unreferenced-bundled-object", "Every bundled object must be target-reachable")
    return {
        "source_data": source_data,
        "target_data": target_data,
        "overlay": overlay,
        "records": records,
    }


def result_document(outcome, bundle=None, audit=None):
    result = {
        "format": RESULT_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "outcome": outcome,
    }
    if bundle is not None:
        result.update(
            {
                "issue": bundle["issue"],
                "bundle_sha256": bundle["bundle_sha256"],
                "plan": bundle["plan"],
                "target": {
                    "checkpoint_sha256": bundle["target"]["checkpoint"]["checkpoint_sha256"],
                    "authority": bundle["target"]["authority"],
                },
            }
        )
    if audit is not None:
        result["audit"] = audit
    result["result_sha256"] = inspector.sha256(inspector.canonical_bytes(result))
    return result


def dry_run(root, bundle):
    decoded = validate_bundle(bundle)
    store = inspector.resolve_store(root)
    _pointer_path, current = _read_pointer_exact(store, bundle["issue"])
    classification = _classify(current, bundle)
    if classification == "conflict":
        _fail("stale", "source-preconditions-stale", "Bundle source pointer is stale")
    if classification == "source":
        decoded = validate_bundle(bundle, root)
        _actual_source_checkpoint(root, bundle)
        _verify_target(root, bundle, decoded)
        code = "source-applicable"
    else:
        _validate_target_contract_postcommit(root, bundle)
        _verify_live_target(root, store, bundle)
        code = "target-already-applied"
    return result_document({"status": "dry-run", "code": code}, bundle)


def _fsync_directory(path):
    workflow_cas.fsync_directory(path)


def _write_all(descriptor, data):
    workflow_cas.write_all(descriptor, data)


def _ensure_directory(path):
    workflow_cas.ensure_directory(path, _fail)


def _verify_existing(path, data):
    return workflow_cas.verify_existing(path, data, _fail)


def _publish_immutable(path, data):
    workflow_cas.publish_immutable(
        path, data, _fail, legacy_temporary_name=True
    )


def _publish_singleton(path, data):
    _ensure_directory(path.parent)
    temporary = path.parent / (".%s.repair-%s" % (path.name, os.getpid()))
    descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        try:
            _write_all(descriptor, data)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        try:
            os.link(str(temporary), str(path))
        except FileExistsError:
            _verify_existing(path, data)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
        _fsync_directory(path.parent)


def _replace_pointer(path, source_data, target_data):
    temporary = path.parent / (".index-integrity.repair-%s" % os.getpid())
    descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        try:
            _write_all(descriptor, target_data)
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        try:
            current = _read_pointer_path_exact(path)
        except RepairFailure:
            _fail(
                "conflict",
                "pointer-conflict",
                "Current pointer cannot be classified against the repair journal",
            )
        if current != source_data:
            _fail(
                "conflict",
                "pointer-conflict",
                "Current pointer is no longer the journal source",
            )
        os.replace(str(temporary), str(path))
        _fsync_directory(path.parent)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _journal_document(bundle):
    journal = {
        "format": JOURNAL_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "bundle": bundle,
    }
    journal["journal_sha256"] = inspector.sha256(inspector.canonical_bytes(journal))
    return journal


def _validate_journal(data):
    try:
        journal = inspector.parse_json_object(data, "repair journal")
    except inspector.InspectionFailure as failure:
        _fail("conflict", "malformed-journal", failure.message)
    if data != inspector.canonical_bytes(journal):
        _fail("conflict", "malformed-journal", "Repair journal is not canonical")
    if (
        set(journal) != {"format", "canonicalization", "bundle", "journal_sha256"}
        or journal.get("format") != JOURNAL_FORMAT
        or journal.get("canonicalization") != inspector.CANONICALIZATION
    ):
        _fail("conflict", "malformed-journal", "Repair journal schema is invalid")
    try:
        _validate_digest(journal, "journal_sha256", "journal")
        validate_bundle(journal["bundle"])
    except RepairFailure:
        _fail("conflict", "malformed-journal", "Embedded repair bundle is invalid")
    return journal["bundle"]


def _read_repair_journal(path):
    flags = os.O_RDONLY | os.O_NONBLOCK
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(str(path), flags)
    except FileNotFoundError:
        return None
    except OSError as error:
        if error.errno in (errno.ELOOP, errno.ENOTDIR):
            _fail("conflict", "journal-not-regular", "Repair journal is not a regular file")
        _fail("conflict", "journal-unreadable", "Repair journal is unreadable")
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            _fail("conflict", "journal-not-regular", "Repair journal is not a regular file")
        chunks = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        data = b"".join(chunks)
    except OSError:
        _fail("conflict", "journal-unreadable", "Repair journal is unreadable")
    finally:
        os.close(descriptor)
    try:
        current = os.lstat(str(path))
    except OSError:
        _fail("conflict", "journal-changed", "Repair journal changed during its read")
    if (
        not stat.S_ISREG(current.st_mode)
        or current.st_dev != info.st_dev
        or current.st_ino != info.st_ino
    ):
        _fail("conflict", "journal-changed", "Repair journal changed during its read")
    return data


def _audit_document(bundle, status="applied", code="target-verified"):
    audit = {
        "format": AUDIT_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "outcome": {"status": status, "code": code},
        "issue": bundle["issue"],
        "operation": bundle["operation"]["type"],
        "bundle_sha256": bundle["bundle_sha256"],
        "source_pointer_sha256": bundle["source"]["pointer"]["sha256"],
        "target_pointer_sha256": bundle["target"]["pointer"]["sha256"],
        "target_checkpoint_sha256": bundle["target"]["checkpoint"]["checkpoint_sha256"],
    }
    audit["audit_sha256"] = inspector.sha256(inspector.canonical_bytes(audit))
    return audit


def _classify(pointer_data, bundle):
    if pointer_data == _decode_record(bundle["source"]["pointer"], "source pointer"):
        return "source"
    if pointer_data == _decode_record(bundle["target"]["pointer"], "target pointer"):
        return "target"
    return "conflict"


def _read_pointer_for_journal(store, issue):
    try:
        return _read_pointer_exact(store, issue)
    except RepairFailure:
        _fail(
            "conflict",
            "pointer-conflict",
            "Current pointer cannot be classified against the repair journal",
        )


def _actual_source_checkpoint(
    root, bundle, allow_repaired_failure=False, journal_active=False
):
    failure_expected = bundle["source"].get("inspection_failure")
    try:
        actual = _checkpoint_for(
            inspector.inspect(root, bundle["issue"], **_selector(bundle["selector"]))
        )
    except inspector.InspectionFailure as failure:
        if journal_active and failure.code in {
            "issue-pointer-missing",
            "issue-pointer-unreadable",
        }:
            _fail(
                "conflict",
                "pointer-conflict",
                "Current pointer cannot be classified against the repair journal",
            )
        actual_failure = {"status": failure.status, "code": failure.code}
        if actual_failure != failure_expected:
            _fail("stale", "source-inspection-mismatch", "Source inspection failure changed")
        return
    if failure_expected is not None:
        if allow_repaired_failure:
            _checkpoint_equal(actual, bundle["checkpoint"])
            return
        _fail("stale", "source-inspection-mismatch", "Source unexpectedly resolves")
    _checkpoint_equal(actual, bundle["checkpoint"])


def _verify_target(root, bundle, decoded):
    try:
        actual = _checkpoint_for(
            inspect_overlay(
                root,
                bundle["issue"],
                decoded["target_data"],
                decoded["overlay"],
                bundle["selector"],
            )
        )
    except inspector.InspectionFailure as failure:
        _fail("conflict", "target-%s" % failure.code, failure.message)
    _checkpoint_equal(actual, bundle["target"]["checkpoint"], "target-checkpoint-mismatch")


def _validate_target_contract_postcommit(root, bundle):
    try:
        return validate_bundle(bundle, root)
    except RepairFailure as failure:
        _fail(
            "conflict",
            "postcondition-%s" % failure.code,
            failure.message,
        )


def _verify_live_target(root, store, bundle):
    _pointer_path, current = _read_pointer_for_journal(store, bundle["issue"])
    if _classify(current, bundle) != "target":
        _fail("conflict", "pointer-conflict", "Current pointer is not the bundle target")
    try:
        actual = _checkpoint_for(
            inspector.inspect(root, bundle["issue"], **_selector(bundle["selector"]))
        )
    except inspector.InspectionFailure as failure:
        if failure.code in {"issue-pointer-missing", "issue-pointer-unreadable"}:
            _fail(
                "conflict",
                "pointer-conflict",
                "Current pointer cannot be classified against the repair journal",
            )
        _fail("conflict", "postcondition-%s" % failure.code, failure.message)
    _checkpoint_equal(
        actual,
        bundle["target"]["checkpoint"],
        "postcondition-mismatch",
        status="conflict",
    )


def _receipt_reference(store, bundle, status="applied", code="target-verified"):
    audit = _audit_document(bundle, status, code)
    data = inspector.canonical_bytes(audit)
    digest = inspector.sha256(data)
    path = store.store_dir / "repair-audits" / "sha256" / digest[:2] / digest[2:]
    return audit, data, path, {"sha256": digest, "size": len(data)}


def _publish_failure_audit(store, bundle, failure):
    _audit, data, path, reference = _receipt_reference(
        store,
        bundle,
        failure.status,
        failure.code,
    )
    _publish_immutable(path, data)
    failure.audit = reference
    failure.bundle_sha256 = bundle["bundle_sha256"]


def _finish_locked(root, store, bundle, journal_path):
    decoded = validate_bundle(bundle)
    pointer_path, pointer_data = _read_pointer_for_journal(store, bundle["issue"])
    classification = _classify(pointer_data, bundle)
    if classification == "conflict":
        _fail("conflict", "pointer-conflict", "Current pointer is neither bundle source nor target")

    if classification == "source":
        decoded = validate_bundle(bundle, root)
        _actual_source_checkpoint(root, bundle, journal_active=True)
        for item in decoded["records"]:
            phase_hook("before-object-publication")
            data = decoded["overlay"][item["sha256"]]
            _publish_immutable(inspector.object_path(store, item["sha256"]), data)
            phase_hook("after-object-publication")
        _actual_source_checkpoint(
            root,
            bundle,
            allow_repaired_failure=True,
            journal_active=True,
        )
        _verify_target(root, bundle, decoded)
        phase_hook("before-pointer-publication")
        _replace_pointer(
            pointer_path,
            decoded["source_data"],
            decoded["target_data"],
        )
        phase_hook("after-pointer-publication")
    else:
        decoded = _validate_target_contract_postcommit(root, bundle)

    _verify_live_target(root, store, bundle)
    _audit, audit_data, audit_path, audit_ref = _receipt_reference(store, bundle)
    phase_hook("before-receipt-publication")
    _publish_immutable(audit_path, audit_data)
    phase_hook("after-receipt-publication")
    phase_hook("before-journal-removal")
    try:
        journal_path.unlink()
    except FileNotFoundError:
        pass
    _fsync_directory(journal_path.parent)
    phase_hook("after-journal-removal")
    return result_document(
        {"status": "applied", "code": "target-verified"}, bundle, audit_ref
    )


def _lock(store, issue):
    issue_dir = store.store_dir / "issues" / str(issue)
    _ensure_directory(issue_dir)
    lock_path = issue_dir / "repair.lock"
    flags = os.O_RDWR | os.O_CREAT
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(str(lock_path), flags, 0o600)
    if not stat.S_ISREG(os.fstat(descriptor).st_mode):
        os.close(descriptor)
        _fail("conflict", "unsafe-lock", "Repair lock is not a regular file")
    fcntl.flock(descriptor, fcntl.LOCK_EX)
    return descriptor, issue_dir


def apply_bundle(root, bundle):
    validate_bundle(bundle)
    store = inspector.resolve_store(root)
    descriptor, issue_dir = _lock(store, bundle["issue"])
    active_bundle = bundle
    try:
        try:
            journal_path = issue_dir / "repair-journal.json"
            journal_data = _read_repair_journal(journal_path)
            if journal_data is not None:
                existing = _validate_journal(journal_data)
                active_bundle = existing
                if existing["issue"] != bundle["issue"]:
                    _fail(
                        "conflict",
                        "journal-issue-mismatch",
                        "Repair journal issue is inconsistent with its lock",
                    )
                result = _finish_locked(root, store, existing, journal_path)
                if existing["bundle_sha256"] == bundle["bundle_sha256"]:
                    return result
                active_bundle = bundle
            _pointer_path, current = _read_pointer_exact(store, bundle["issue"])
            classification = _classify(current, bundle)
            if classification == "conflict":
                _fail("stale", "source-preconditions-stale", "Bundle source pointer is stale")
            if classification == "source":
                decoded = validate_bundle(bundle, root)
                _actual_source_checkpoint(root, bundle)
                _verify_target(root, bundle, decoded)
                journal = _journal_document(bundle)
                phase_hook("before-journal-publication")
                _publish_singleton(journal_path, inspector.canonical_bytes(journal))
                phase_hook("after-journal-publication")
            return _finish_locked(root, store, bundle, journal_path)
        except RepairFailure as failure:
            _publish_failure_audit(store, active_bundle, failure)
            raise
    finally:
        os.close(descriptor)


def recover(root, issue):
    if not _exact_int(issue) or issue < 1:
        _fail("invalid", "invalid-issue", "Issue number must be positive")
    store = inspector.resolve_store(root)
    descriptor, issue_dir = _lock(store, issue)
    try:
        journal_path = issue_dir / "repair-journal.json"
        data = _read_repair_journal(journal_path)
        if data is None:
            return result_document({"status": "clean", "code": "no-journal"})
        bundle = _validate_journal(data)
        if bundle["issue"] != issue:
            _fail("conflict", "journal-issue-mismatch", "Repair journal issue is inconsistent")
        try:
            return _finish_locked(root, store, bundle, journal_path)
        except RepairFailure as failure:
            _publish_failure_audit(store, bundle, failure)
            raise
    finally:
        os.close(descriptor)


class RepairArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("invalid", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = RepairArgumentParser(description=__doc__)
    commands = parser.add_subparsers(
        dest="command", required=True, parser_class=RepairArgumentParser
    )
    prepare_parser = commands.add_parser("prepare")
    prepare_parser.add_argument("issue", type=int)
    prepare_parser.add_argument("--root", required=True)
    prepare_parser.add_argument("--checkpoint", required=True)
    prepare_parser.add_argument("--request", required=True)
    for name in ("dry-run", "apply"):
        command = commands.add_parser(name)
        command.add_argument("--root", required=True)
        command.add_argument("--bundle", required=True)
    recover_parser = commands.add_parser("recover")
    recover_parser.add_argument("issue", type=int)
    recover_parser.add_argument("--root", required=True)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        root = pathlib.Path(args.root)
        if args.command == "prepare":
            document = prepare(root, args.issue, args.checkpoint, args.request)
        elif args.command == "dry-run":
            document = dry_run(root, _load_canonical(args.bundle, "bundle"))
        elif args.command == "apply":
            document = apply_bundle(root, _load_canonical(args.bundle, "bundle"))
        else:
            document = recover(root, args.issue)
        sys.stdout.buffer.write(inspector.canonical_document(document))
        return 0
    except RepairFailure as failure:
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return EXIT_CODES[failure.status]
    except inspector.InspectionFailure as failure:
        wrapped = RepairFailure("invalid", failure.code, failure.message)
        sys.stdout.buffer.write(inspector.canonical_document(wrapped.document()))
        return EXIT_CODES[wrapped.status]
    except OSError:
        failure = RepairFailure("conflict", "filesystem-operation-failed", "Filesystem operation failed closed")
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return EXIT_CODES[failure.status]


if __name__ == "__main__":
    sys.exit(main())
