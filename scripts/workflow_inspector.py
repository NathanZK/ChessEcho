#!/usr/bin/env python3
"""Independent read-only inspector for ChessEcho workflow authority."""

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass


try:
    LOADED_SOURCE_SHA256 = hashlib.sha256(
        pathlib.Path(__file__).resolve().read_bytes()
    ).hexdigest()
    LOADED_SOURCE_ERROR = None
except OSError as source_error:
    LOADED_SOURCE_SHA256 = None
    LOADED_SOURCE_ERROR = str(source_error)

INSPECTOR_VERSION = "1.0.0"
INSPECTION_FORMAT = "chess-echo-workflow-inspection-v1"
CHECKPOINT_FORMAT = "chess-echo-workflow-checkpoint-v1"
CANONICALIZATION = "utf8-json-sort-keys-compact-ascii-v1"
STORE_NAME = "chess-echo-agent-workflow"
POINTER_FORMAT = "chess-echo-issue-index-pointer-v1"

OUTCOME_EXIT_CODES = {
    "resolved": 0,
    "missing": 3,
    "unsupported": 4,
    "corrupt": 5,
    "ambiguous": 6,
}

JSON_OBJECT_KINDS = {
    "context-capsule",
    "context-reuse-manifest",
    "evidence-manifest",
    "issue-index",
    "legacy-migration-manifest",
    "run-envelope",
    "run-event",
    "run-history",
    "run-state",
    "test-manifest",
    "test-reconciliation",
    "test-reuse-manifest",
    "validation-result",
}
BASE64_PAYLOAD_KINDS = {"plan-change-manifest"}
RAW_OBJECT_KINDS = {
    "issue-snapshot",
    "legacy-raw-evidence",
    "pr-body",
    "pr-title",
    "preserved-implementation-snapshot",
    "validation-incremental-log",
    "validation-log",
}
TYPED_PAYLOAD_KINDS = {
    "final_review",
    "implementation_report",
    "plan",
    "plan_review",
    "test_report",
    "test_review",
}
SUPPORTED_OBJECT_KINDS = (
    JSON_OBJECT_KINDS | RAW_OBJECT_KINDS | BASE64_PAYLOAD_KINDS
    | TYPED_PAYLOAD_KINDS
)
STRUCTURAL_PREDECESSOR_KEYS = {
    "previous_envelope",
    "previous_event",
    "previous_history",
    "previous_index",
    "previous_state",
}
REFERENCE_FIELDS = frozenset({"kind", "sha256", "size"})
GIT_REDIRECT_ENVIRONMENT = frozenset({
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CEILING_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_CONFIG",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_SYSTEM",
    "GIT_DIR",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_GRAFT_FILE",
    "GIT_GLOB_PATHSPECS",
    "GIT_ICASE_PATHSPECS",
    "GIT_IMPLICIT_WORK_TREE",
    "GIT_INDEX_FILE",
    "GIT_INTERNAL_SUPER_PREFIX",
    "GIT_LITERAL_PATHSPECS",
    "GIT_NAMESPACE",
    "GIT_NOGLOB_PATHSPECS",
    "GIT_OBJECT_DIRECTORY",
    "GIT_PREFIX",
    "GIT_REPLACE_REF_BASE",
    "GIT_SHALLOW_FILE",
    "GIT_SUPER_PREFIX",
    "GIT_WORK_TREE",
})
GIT_REDIRECT_ENVIRONMENT_PREFIXES = (
    "GIT_CONFIG_KEY_",
    "GIT_CONFIG_VALUE_",
)
SHA256_RE = re.compile(r"[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[0-9a-f]{32}")


class InspectionFailure(Exception):
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
        return {
            "format": INSPECTION_FORMAT,
            "outcome": outcome,
        }


@dataclass(frozen=True)
class Store:
    repository_root: pathlib.Path
    common_dir: pathlib.Path
    store_dir: pathlib.Path


def canonical_bytes(value):
    try:
        _reject_noncanonical_numbers(value)
        return json.dumps(
            value,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except RecursionError:
        raise InspectionFailure(
            "corrupt", "json-too-deep", "JSON nesting exceeds the inspector limit"
        )


def canonical_document(value):
    return canonical_bytes(value) + b"\n"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def _reject_noncanonical_numbers(value):
    if isinstance(value, float):
        raise InspectionFailure(
            "unsupported",
            "floating-point-json",
            "Floating-point JSON values are outside the checkpoint contract",
        )
    if isinstance(value, dict):
        for child in value.values():
            _reject_noncanonical_numbers(child)
    elif isinstance(value, list):
        for child in value:
            _reject_noncanonical_numbers(child)


def parse_json_object(data, label):
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as error:
        raise InspectionFailure(
            "corrupt", "invalid-json", "%s is not valid UTF-8 JSON: %s" % (label, error)
        )
    if not isinstance(value, dict):
        raise InspectionFailure(
            "corrupt", "invalid-json-type", "%s must contain a JSON object" % label
        )
    return value


def _exact_int(value):
    return type(value) is int


def _admin_path(raw, base):
    if not isinstance(raw, str) or not raw.strip() or "\0" in raw:
        raise InspectionFailure(
            "corrupt", "invalid-git-admin-path", "Git administrative path is malformed"
        )
    path = pathlib.Path(raw.strip())
    if not path.is_absolute():
        path = base / path
    return pathlib.Path(os.path.abspath(str(path)))


def resolve_store(root):
    root = pathlib.Path(root).resolve()
    dotgit = root / ".git"
    if dotgit.is_dir():
        common = dotgit.resolve()
    elif dotgit.is_file():
        try:
            marker = dotgit.read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError) as error:
            raise InspectionFailure(
                "missing", "git-admin-unreadable", "Cannot read .git file: %s" % error
            )
        if not marker.startswith("gitdir: "):
            raise InspectionFailure(
                "corrupt", "git-admin-malformed", "Linked-worktree .git file is malformed"
            )
        admin = _admin_path(marker[8:], root)
        commondir = admin / "commondir"
        if commondir.is_file():
            try:
                common = _admin_path(commondir.read_text(encoding="utf-8"), admin)
            except (OSError, UnicodeError) as error:
                raise InspectionFailure(
                    "missing",
                    "git-common-dir-unreadable",
                    "Cannot read Git common-dir marker: %s" % error,
                )
        else:
            common = admin
    else:
        raise InspectionFailure(
            "missing", "git-admin-missing", "Repository has no .git administrative path"
        )
    if not common.is_dir():
        raise InspectionFailure(
            "missing", "git-common-dir-missing", "Git common directory does not exist"
        )
    return Store(root, common, common / STORE_NAME)


def object_path(store, digest):
    return store.store_dir / "objects" / "sha256" / digest[:2] / digest[2:]


def validate_reference(reference, expected_kind=None):
    if not isinstance(reference, dict):
        raise InspectionFailure(
            "corrupt", "invalid-object-reference", "Immutable object reference is not an object"
        )
    kind = reference.get("kind")
    digest = reference.get("sha256")
    size = reference.get("size")
    if expected_kind is not None and kind != expected_kind:
        raise InspectionFailure(
            "corrupt",
            "wrong-object-kind",
            "Expected object kind %s, found %r" % (expected_kind, kind),
            digest,
        )
    if kind not in SUPPORTED_OBJECT_KINDS:
        raise InspectionFailure(
            "unsupported",
            "unsupported-object-kind",
            "Object kind %r is not supported by inspector v%s" % (kind, INSPECTOR_VERSION),
            digest,
        )
    if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
        raise InspectionFailure(
            "corrupt", "invalid-object-hash", "Immutable object hash is invalid"
        )
    if not _exact_int(size) or size < 0:
        raise InspectionFailure(
            "corrupt", "invalid-object-size", "Immutable object size is invalid", digest
        )
    normalized = {"kind": kind, "sha256": digest, "size": size}
    encoding = reference.get("encoding")
    if kind in TYPED_PAYLOAD_KINDS and encoding != "typed-base64":
        raise InspectionFailure(
            "corrupt",
            "missing-typed-payload-encoding",
            "Typed evidence reference lacks its required encoding",
            digest,
        )
    if kind not in TYPED_PAYLOAD_KINDS and encoding is not None:
        raise InspectionFailure(
            "corrupt",
            "unexpected-object-encoding",
            "Non-typed object declares an encoding",
            digest,
        )
    if encoding is not None:
        normalized["encoding"] = "typed-base64"
    return normalized


class AuthorityReader:
    def __init__(self, store, issue):
        self.store = store
        self.issue = issue
        self.correction = None
        self.verified = {}
        self.decoded_json = {}

    def read_bytes(self, reference, expected_kind=None):
        reference = validate_reference(reference, expected_kind)
        digest = reference["sha256"]
        path = object_path(self.store, digest)
        try:
            before = path.stat()
            data = path.read_bytes()
            after = path.stat()
        except FileNotFoundError:
            raise InspectionFailure(
                "missing", "object-missing", "Immutable object is missing", digest
            )
        except OSError as error:
            raise InspectionFailure(
                "missing", "object-unreadable", "Cannot read immutable object: %s" % error, digest
            )
        if (
            before.st_dev != after.st_dev
            or before.st_ino != after.st_ino
            or before.st_size != after.st_size
            or before.st_mtime_ns != after.st_mtime_ns
        ):
            raise InspectionFailure(
                "corrupt", "object-changed-during-read", "Immutable object changed during read", digest
            )
        if len(data) != reference["size"] or sha256(data) != digest:
            raise InspectionFailure(
                "corrupt",
                "object-hash-or-size-mismatch",
                "Immutable object failed SHA-256 or size verification",
                digest,
            )
        verified = self.verified.setdefault(
            digest,
            {"sha256": digest, "size": reference["size"], "kinds": set()},
        )
        if verified["size"] != reference["size"]:
            raise InspectionFailure(
                "corrupt",
                "conflicting-object-size",
                "The same object hash is referenced with conflicting sizes",
                digest,
            )
        verified["kinds"].add(reference["kind"])
        return data

    def read_json(self, reference, expected_kind=None):
        normalized = validate_reference(reference, expected_kind)
        digest = normalized["sha256"]
        if digest in self.decoded_json:
            return self.decoded_json[digest]
        data = self.read_bytes(normalized, expected_kind)
        value = parse_json_object(data, "%s object" % normalized["kind"])
        if canonical_bytes(value) != data:
            raise InspectionFailure(
                "corrupt",
                "noncanonical-object-json",
                "Structured immutable object is not canonically serialized",
                digest,
            )
        self.decoded_json[digest] = value
        return value

    def verify_evidence_reference(self, reference, enclosing=None, descend=True):
        normalized = validate_reference(reference)
        kind = normalized["kind"]
        data = self.read_bytes(normalized)
        value = None
        if normalized.get("encoding") == "typed-base64":
            wrapper = parse_json_object(data, "typed immutable object")
            if (
                canonical_bytes(wrapper) != data
                or set(wrapper) != {
                    "correction",
                    "issue",
                    "kind",
                    "logical_path",
                    "object_kind",
                    "payload_base64",
                }
                or wrapper.get("kind") != "typed-evidence"
                or wrapper.get("object_kind") != kind
                or not _exact_int(wrapper.get("issue"))
                or wrapper.get("issue") != self.issue
                or (
                    self.correction is None
                    and wrapper.get("correction") is not None
                )
                or (
                    self.correction is not None
                    and (
                        not _exact_int(wrapper.get("correction"))
                        or wrapper.get("correction") != self.correction
                    )
                )
                or not isinstance(wrapper.get("logical_path"), str)
            ):
                raise InspectionFailure(
                    "corrupt",
                    "invalid-typed-payload",
                    "Typed evidence wrapper is invalid",
                    normalized["sha256"],
                )
            try:
                payload = base64.b64decode(wrapper["payload_base64"], validate=True)
            except (TypeError, ValueError):
                raise InspectionFailure(
                    "corrupt",
                    "invalid-typed-payload",
                    "Typed evidence payload is not valid base64",
                    normalized["sha256"],
                )
            if isinstance(enclosing, dict) and "sha256" in enclosing:
                raw_digest = enclosing["sha256"]
                if (
                    not isinstance(raw_digest, str)
                    or SHA256_RE.fullmatch(raw_digest) is None
                    or sha256(payload) != raw_digest
                ):
                    raise InspectionFailure(
                        "corrupt",
                        "typed-payload-hash-mismatch",
                        "Decoded evidence payload does not match its recorded hash",
                        normalized["sha256"],
                    )
        elif kind in BASE64_PAYLOAD_KINDS:
            try:
                payload = base64.b64decode(data, validate=True)
            except (TypeError, ValueError):
                raise InspectionFailure(
                    "corrupt",
                    "invalid-base64-payload",
                    "Encoded evidence payload is not valid base64",
                    normalized["sha256"],
                )
            if (
                not isinstance(enclosing, dict)
                or enclosing.get("encoded") != "base64"
                or not isinstance(enclosing.get("sha256"), str)
                or sha256(payload) != enclosing["sha256"]
            ):
                raise InspectionFailure(
                    "corrupt",
                    "base64-payload-hash-mismatch",
                    "Decoded evidence payload does not match its recorded hash",
                    normalized["sha256"],
                )
        elif kind in JSON_OBJECT_KINDS:
            value = parse_json_object(data, "%s object" % kind)
            if canonical_bytes(value) != data:
                raise InspectionFailure(
                    "corrupt",
                    "noncanonical-object-json",
                    "Structured evidence object is not canonically serialized",
                    normalized["sha256"],
                )
            self.decoded_json[normalized["sha256"]] = value
        if descend and value is not None:
            self.walk_references(value, payload_root=True)

    def walk_references(self, value, parent=None, key=None, payload_root=False):
        if isinstance(value, dict):
            shape = _reference_shape(value, payload_root)
            if shape == "malformed":
                raise InspectionFailure(
                    "corrupt",
                    "invalid-object-reference-shape",
                    "Immutable object reference is missing kind, sha256, or size",
                )
            if shape == "complete":
                self.verify_evidence_reference(
                    value,
                    enclosing=parent,
                    descend=key not in STRUCTURAL_PREDECESSOR_KEYS,
                )
                return
            for child_key, child in value.items():
                self.walk_references(child, value, child_key)
        elif isinstance(value, list):
            for child in value:
                self.walk_references(child, parent, key)


def _reference_shape(value, payload_root=False):
    if (
        not isinstance(value, dict)
        or value.get("kind") in {"regular", "symlink"}
        or "bytes_base64" in value
    ):
        return "ordinary"
    present = REFERENCE_FIELDS & set(value)
    if present == REFERENCE_FIELDS:
        return "complete"
    if len(present) >= 2:
        if payload_root and present == {"kind", "sha256"}:
            return "ordinary"
        return "malformed"
    return "ordinary"


def read_pointer(store, issue):
    path = store.store_dir / "issues" / str(issue) / "index-integrity.json"
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        projection = store.repository_root / ".agent-workflow" / "runs" / (
            "issue-%s" % issue
        )
        if projection.exists():
            raise InspectionFailure(
                "unsupported",
                "projection-only-run",
                "A worktree projection exists without a supported durable v4 pointer",
            )
        raise InspectionFailure(
            "missing", "issue-pointer-missing", "Issue has no durable authority pointer"
        )
    except OSError as error:
        raise InspectionFailure(
            "missing", "issue-pointer-unreadable", "Cannot read issue pointer: %s" % error
        )
    pointer = parse_json_object(data, "issue pointer")
    if pointer.get("format") != POINTER_FORMAT:
        raise InspectionFailure(
            "unsupported",
            "unsupported-pointer-format",
            "Pointer format %r is unsupported" % pointer.get("format"),
        )
    if (
        not _exact_int(pointer.get("issue"))
        or pointer.get("issue") != issue
        or not _exact_int(pointer.get("generation"))
        or pointer["generation"] < 0
        or not isinstance(pointer.get("index"), dict)
        or not isinstance(pointer.get("selection"), dict)
    ):
        raise InspectionFailure(
            "corrupt", "invalid-pointer-schema", "Issue pointer schema or identity is invalid"
        )
    if canonical_bytes(pointer) != data:
        raise InspectionFailure(
            "corrupt", "noncanonical-pointer", "Issue pointer is not canonically serialized"
        )
    return data, pointer


def load_index_chain(reader, pointer, issue):
    current_ref = validate_reference(pointer["index"], "issue-index")
    indexes = []
    seen = set()
    expected_generation = pointer["generation"]
    while True:
        if current_ref["sha256"] in seen:
            raise InspectionFailure(
                "corrupt", "index-cycle", "Issue-index predecessor chain contains a cycle"
            )
        seen.add(current_ref["sha256"])
        index = reader.read_json(current_ref, "issue-index")
        if (
            index.get("kind") != "issue-index"
            or not _exact_int(index.get("issue"))
            or index.get("issue") != issue
            or not _exact_int(index.get("generation"))
            or index.get("generation") != expected_generation
        ):
            raise InspectionFailure(
                "corrupt",
                "index-identity-mismatch",
                "Issue-index identity or generation is inconsistent",
                current_ref["sha256"],
            )
        _validate_index_tables(index)
        indexes.append((current_ref, index))
        previous = index.get("previous_index")
        if previous is None:
            if expected_generation != 0:
                raise InspectionFailure(
                    "corrupt", "index-chain-ended-early", "Issue-index chain ended before generation zero"
                )
            break
        current_ref = validate_reference(previous, "issue-index")
        expected_generation -= 1
        if expected_generation < 0:
            raise InspectionFailure(
                "corrupt", "index-generation-underflow", "Issue-index generation chain is invalid"
            )
    return indexes


def _validate_index_tables(index):
    attempts = index.get("attempts")
    corrections = index.get("corrections")
    current = index.get("current_normal_run_id")
    if not isinstance(attempts, list) or not isinstance(corrections, list):
        raise InspectionFailure(
            "corrupt", "invalid-index-tables", "Issue-index run tables are invalid"
        )
    run_ids = []
    correction_numbers = []
    for row in attempts + corrections:
        if not isinstance(row, dict) or not isinstance(row.get("run_id"), str):
            raise InspectionFailure(
                "corrupt", "invalid-run-row", "Issue-index contains an invalid run row"
            )
        run_ids.append(row["run_id"])
    for row in attempts:
        if row.get("number") is not None:
            raise InspectionFailure(
                "corrupt",
                "invalid-run-row",
                "Normal run number must be null",
            )
    for row in corrections:
        if not _exact_int(row.get("number")):
            raise InspectionFailure(
                "corrupt", "invalid-correction-row", "Correction number is invalid"
            )
        correction_numbers.append(row["number"])
    if len(run_ids) != len(set(run_ids)):
        raise InspectionFailure(
            "ambiguous", "duplicate-run-id", "Issue-index maps a run ID more than once"
        )
    if len(correction_numbers) != len(set(correction_numbers)):
        raise InspectionFailure(
            "ambiguous", "duplicate-correction-number", "Issue-index maps a correction number more than once"
        )
    current_matches = [row for row in attempts if row["run_id"] == current]
    if len(current_matches) != 1:
        raise InspectionFailure(
            "ambiguous", "ambiguous-current-run", "Current normal run is not uniquely indexed"
        )


def select_run(pointer, indexes, run_id=None, correction=None):
    if correction is not None and not _exact_int(correction):
        raise InspectionFailure(
            "unsupported",
            "invalid-correction",
            "Correction selector must be an exact integer",
        )
    latest = indexes[0][1]
    if (
        pointer["selection"].get("current_normal_run_id")
        != latest["current_normal_run_id"]
    ):
        raise InspectionFailure(
            "corrupt",
            "pointer-selection-mismatch",
            "Pointer selection conflicts with the current issue index",
        )
    rows = latest["attempts"] + latest["corrections"]
    selected = []
    if correction is not None:
        selected = [
            row for row in latest["corrections"] if row.get("number") == correction
        ]
    elif run_id is not None:
        selected = [row for row in rows if row["run_id"] == run_id]
    else:
        selected = [
            row for row in latest["attempts"]
            if row["run_id"] == latest["current_normal_run_id"]
        ]
    if not selected:
        raise InspectionFailure(
            "missing", "run-selection-missing", "Requested run is not indexed"
        )
    if len(selected) != 1:
        raise InspectionFailure(
            "ambiguous", "run-selection-ambiguous", "Requested run resolves more than once"
        )
    selected_id = selected[0]["run_id"]
    binding = None
    for _reference, index in indexes:
        update = index.get("run_update")
        if isinstance(update, dict) and update.get("run_id") == selected_id:
            binding = update
            break
    pointer_run = pointer.get("selection", {}).get("run")
    if selected_id == latest["current_normal_run_id"]:
        if (
            not isinstance(pointer_run, dict)
            or pointer_run.get("run_id") != selected_id
            or binding is None
        ):
            raise InspectionFailure(
                "corrupt",
                "pointer-binding-mismatch",
                "Pointer has no binding for the indexed current run",
            )
        for source in (binding, selected[0]):
            for key, value in source.items():
                if pointer_run.get(key) != value:
                    raise InspectionFailure(
                        "corrupt",
                        "pointer-binding-mismatch",
                        "Pointer selection conflicts with the issue-index binding",
                    )
        binding = dict(pointer_run)
        binding["_from_pointer"] = True
    if not isinstance(binding, dict):
        raise InspectionFailure(
            "missing", "run-binding-missing", "Selected run has no addressable binding"
        )
    return selected[0], binding


def verify_selected_run(reader, issue, row, binding):
    for field in ("run_id", "family_run_id", "event_tip"):
        if not isinstance(binding.get(field), str) or not binding[field]:
            raise InspectionFailure(
                "corrupt", "invalid-run-binding", "Run binding field %s is invalid" % field
            )
    if (
        RUN_ID_RE.fullmatch(binding["run_id"]) is None
        or not _exact_int(binding.get("generation"))
        or not _exact_int(binding.get("sequence"))
        or binding["generation"] < 0
        or binding["sequence"] < 1
        or SHA256_RE.fullmatch(binding["event_tip"]) is None
    ):
        raise InspectionFailure(
            "corrupt", "invalid-run-binding", "Run binding identity or counters are invalid"
        )
    row_number = row.get("number")
    binding_number = binding.get("number")
    if (
        row_number is None
        and binding_number is not None
    ) or (
        row_number is not None
        and (
            not _exact_int(row_number)
            or not _exact_int(binding_number)
            or binding_number != row_number
        )
    ):
        raise InspectionFailure(
            "corrupt",
            "invalid-run-binding",
            "Run binding correction identity is invalid",
        )
    envelope_ref = validate_reference(binding.get("envelope"), "run-envelope")
    envelope = reader.read_json(envelope_ref, "run-envelope")
    if (
        not _exact_int(envelope.get("issue"))
        or not _exact_int(envelope.get("generation"))
        or not _exact_int(envelope.get("sequence"))
    ):
        raise InspectionFailure(
            "corrupt",
            "invalid-envelope-counters",
            "Run envelope identity or counters are not exact integers",
        )
    required_envelope = {
        "kind": "run-envelope",
        "issue": issue,
        "run_id": binding["run_id"],
        "generation": binding["generation"],
        "sequence": binding["sequence"],
        "event_tip": binding["event_tip"],
    }
    for key, expected in required_envelope.items():
        if envelope.get(key) != expected:
            raise InspectionFailure(
                "corrupt", "envelope-binding-mismatch", "Run envelope conflicts with its binding"
            )
    state_ref = validate_reference(envelope.get("state"), "run-state")
    history_ref = validate_reference(envelope.get("history"), "run-history")
    event_ref = validate_reference(envelope.get("event"), "run-event")
    state = reader.read_json(state_ref, "run-state")
    history = reader.read_json(history_ref, "run-history")
    event = reader.read_json(event_ref, "run-event")
    for value, label in ((state, "state"), (history, "history")):
        if (
            value.get("run_id") != binding["run_id"]
            or not _exact_int(value.get("generation"))
            or value.get("generation") != binding["generation"]
        ):
            raise InspectionFailure(
                "corrupt",
                "%s-binding-mismatch" % label,
                "Run %s conflicts with its binding" % label,
            )
    if (
        state.get("kind") != "run-state"
        or not _exact_int(state.get("event_sequence"))
        or state.get("event_sequence") != binding["sequence"]
        or state.get("event_tip") != binding["event_tip"]
        or not isinstance(state.get("state"), str)
    ):
        raise InspectionFailure(
            "corrupt", "state-tip-mismatch", "Run state has an inconsistent authority tip"
        )
    state_issue = state.get("issue")
    state_correction = state.get("correction")
    if (
        not isinstance(state_issue, dict)
        or not _exact_int(state_issue.get("number"))
        or state_issue.get("number") != issue
        or state.get("family_run_id") != binding["family_run_id"]
        or (
            row.get("number") is None
            and state_correction is not None
        )
        or (
            row.get("number") is not None
            and (
                not isinstance(state_correction, dict)
                or not _exact_int(state_correction.get("number"))
                or state_correction.get("number") != row["number"]
            )
        )
    ):
        raise InspectionFailure(
            "corrupt",
            "state-identity-mismatch",
            "Run state conflicts with the selected issue or run identity",
        )
    events = history.get("events")
    if history.get("kind") != "run-history" or not isinstance(events, list):
        raise InspectionFailure(
            "corrupt", "invalid-run-history", "Run history object is invalid"
        )
    if len(events) != binding["sequence"]:
        raise InspectionFailure(
            "corrupt", "history-length-mismatch", "Run history length does not match sequence"
        )
    for sequence, history_event in enumerate(events, 1):
        if (
            not isinstance(history_event, dict)
            or not _exact_int(history_event.get("sequence"))
            or history_event.get("sequence") != sequence
        ):
            raise InspectionFailure(
                "corrupt", "history-sequence-gap", "Run history sequence is not contiguous"
            )
    if events[-1].get("state") != state["state"]:
        raise InspectionFailure(
            "corrupt",
            "state-history-mismatch",
            "Run state does not match the final history event",
        )
    legacy_anchor = read_legacy_event_anchor(reader, state)
    verify_event_chain(reader, event_ref, event, events, binding, legacy_anchor)
    if binding.get("_from_pointer"):
        for key, expected in (
            ("state", state_ref),
            ("history", history_ref),
            ("event", event_ref),
        ):
            if binding.get(key) != expected:
                raise InspectionFailure(
                    "corrupt",
                    "pointer-binding-mismatch",
                    "Pointer run binding conflicts with its envelope",
                )
    reader.walk_references(state, payload_root=True)
    reader.walk_references(history, payload_root=True)
    for predecessor_key in ("previous_envelope", "previous_history"):
        predecessor = envelope.get(predecessor_key)
        if predecessor is not None:
            reader.verify_evidence_reference(predecessor, descend=False)
    for key in (
        "run_id", "family_run_id", "number", "status", "supersedes"
    ):
        if row.get(key) != binding.get(key):
            raise InspectionFailure(
                "corrupt",
                "run-row-binding-mismatch",
                "Selected run row conflicts with binding",
            )
    return {
        "row": row,
        "binding": binding,
        "envelope": envelope_ref,
        "state": state_ref,
        "history": history_ref,
        "event": event_ref,
        "state_value": state,
    }


def read_legacy_event_anchor(reader, state):
    migration = state.get("migration")
    if not isinstance(migration, dict):
        return None
    raw = migration.get("raw_evidence")
    history_record = raw.get("history.jsonl") if isinstance(raw, dict) else None
    reference = (
        history_record.get("object")
        if isinstance(history_record, dict) and "object" in history_record
        else None
    )
    if _reference_shape(history_record) == "complete":
        reference = history_record
    reference_shape = _reference_shape(reference)
    if reference_shape == "malformed":
        raise InspectionFailure(
            "corrupt",
            "invalid-object-reference-shape",
            "Immutable object reference is missing kind, sha256, or size",
        )
    if reference_shape != "complete":
        raise InspectionFailure(
            "unsupported",
            "unsupported-legacy-anchor",
            "Migrated run lacks addressable legacy history metadata",
        )
    data = reader.read_bytes(
        validate_reference(reference, "legacy-raw-evidence")
    )
    try:
        lines = data.decode("utf-8").splitlines()
        events = [json.loads(line) for line in lines]
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise InspectionFailure(
            "corrupt",
            "invalid-legacy-history",
            "Legacy history metadata is invalid: %s" % error,
        )
    if (
        not events
        or any(not isinstance(item, dict) for item in events)
        or any(
            not _exact_int(item.get("sequence"))
            or item.get("sequence") != sequence
            for sequence, item in enumerate(events, 1)
        )
    ):
        raise InspectionFailure(
            "corrupt",
            "invalid-legacy-history",
            "Legacy history metadata is not contiguous",
        )
    event = dict(events[-1])
    if "at" not in event and isinstance(event.get("timestamp"), str):
        event["at"] = event["timestamp"]
    return {"sequence": len(events), "event": event}


def verify_event_chain(
    reader, event_ref, event, history_events, binding, legacy_anchor=None
):
    current_ref = event_ref
    current = event
    seen = set()
    expected_sequence = binding["sequence"]
    first = True
    while True:
        digest = current_ref["sha256"]
        if digest in seen:
            raise InspectionFailure(
                "corrupt", "event-cycle", "Run-event predecessor chain contains a cycle"
            )
        seen.add(digest)
        actual_sequence = current.get("sequence")
        migration_anchor = (
            not first
            and _exact_int(actual_sequence)
            and actual_sequence == expected_sequence + 1
            and current.get("previous_event") is None
        )
        if (
            current.get("kind") != "run-event"
            or current.get("run_id") != binding["run_id"]
            or (
                actual_sequence != expected_sequence
                and not migration_anchor
            )
        ):
            raise InspectionFailure(
                "corrupt",
                "event-binding-mismatch",
                "Run event sequence %r conflicts with expected sequence %s"
                % (actual_sequence, expected_sequence),
                digest,
            )
        if (
            not _exact_int(actual_sequence)
            or actual_sequence < 1
            or actual_sequence > len(history_events)
        ):
            raise InspectionFailure(
                "corrupt",
                "event-sequence-out-of-range",
                "Run event sequence is outside the verified history",
                digest,
            )
        self_hash = current.get("sha256")
        unhashed = dict(current)
        unhashed.pop("sha256", None)
        if self_hash != sha256(canonical_bytes(unhashed)):
            raise InspectionFailure(
                "corrupt", "event-self-hash-mismatch", "Run event self-hash is invalid", digest
            )
        projected = {
            key: value for key, value in current.items()
            if key not in {"kind", "run_id", "previous_event", "sha256"}
        }
        if projected != history_events[actual_sequence - 1]:
            raise InspectionFailure(
                "corrupt", "event-history-mismatch", "Run event does not match history"
            )
        previous = current.get("previous_event")
        if previous is None:
            if actual_sequence == 1:
                break
            if not (
                migration_anchor
                and isinstance(legacy_anchor, dict)
                and actual_sequence == legacy_anchor.get("sequence")
                and projected == legacy_anchor.get("event")
            ):
                raise InspectionFailure(
                    "corrupt",
                    "event-chain-truncated",
                    "Run-event chain ended without a verified initial or migration anchor",
                    digest,
                )
            break
        if actual_sequence == 1:
            raise InspectionFailure(
                "corrupt", "event-chain-overrun", "Initial run event has a predecessor"
            )
        current_ref = validate_reference(previous, "run-event")
        current = reader.read_json(current_ref, "run-event")
        expected_sequence = actual_sequence - 1
        first = False
    if event.get("sha256") != binding["event_tip"]:
        raise InspectionFailure(
            "corrupt", "event-tip-mismatch", "Selected event does not match the authority tip"
        )


def _git(root, operation, *values):
    if operation == "head":
        command = ["git", "rev-parse", "--verify", "HEAD^{commit}"]
    elif operation == "head-tree":
        command = ["git", "rev-parse", "--verify", "%s^{tree}" % values[0]]
    elif operation == "base":
        command = ["git", "rev-parse", "--verify", "%s^{commit}" % values[0]]
    elif operation == "base-tree":
        command = ["git", "rev-parse", "--verify", "%s^{tree}" % values[0]]
    elif operation == "merge-base":
        command = ["git", "merge-base", values[0], values[1]]
    elif operation == "ancestor":
        command = ["git", "merge-base", "--is-ancestor", values[0], values[1]]
    elif operation == "commit-count":
        command = ["git", "rev-list", "--count", "%s..%s" % (values[0], values[1])]
    elif operation == "status":
        command = [
            "git", "status", "--porcelain=v1", "-z", "--untracked-files=all",
            "--", ".", ":(exclude).agent-workflow/runs/**",
        ]
    else:
        raise InspectionFailure(
            "unsupported", "unsupported-git-operation", "Git operation is not allowlisted"
        )
    environment = {
        key: value for key, value in os.environ.items()
        if key not in GIT_REDIRECT_ENVIRONMENT
        and not key.startswith(GIT_REDIRECT_ENVIRONMENT_PREFIXES)
    }
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    environment["GIT_NO_LAZY_FETCH"] = "1"
    result = subprocess.run(
        ["git", "-c", "core.fsmonitor=false", *command[1:]],
        cwd=str(root),
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if operation == "ancestor" and result.returncode == 1:
        return False
    if result.returncode != 0:
        status = "missing" if operation in {"base", "base-tree", "merge-base"} else "corrupt"
        raise InspectionFailure(
            status,
            "git-%s-failed" % operation,
            "Read-only Git operation %s failed" % operation,
        )
    if operation == "status":
        return result.stdout
    if operation == "ancestor":
        return True
    try:
        return result.stdout.decode("ascii").strip()
    except UnicodeDecodeError:
        raise InspectionFailure(
            "corrupt", "git-output-nonascii", "Git returned non-ASCII object data"
        )


def inspect_repository(root, state):
    baseline = state.get("evidence_baseline")
    if (
        not isinstance(baseline, dict)
        or baseline.get("kind") != "target-base"
        or not isinstance(baseline.get("ref"), str)
        or not isinstance(baseline.get("sha"), str)
        or re.fullmatch(r"[0-9a-f]{40}", baseline["sha"]) is None
    ):
        raise InspectionFailure(
            "unsupported",
            "unsupported-repository-baseline",
            "Selected run lacks a supported target-base evidence record",
        )
    base_ref = baseline["ref"]
    head = _git(root, "head")
    head_tree = _git(root, "head-tree", head)
    resolved_base = _git(root, "base", base_ref)
    base_tree = _git(root, "base-tree", resolved_base)
    merge_base = _git(root, "merge-base", head, resolved_base)
    ancestor = _git(root, "ancestor", resolved_base, head)
    commit_count = _git(root, "commit-count", resolved_base, head)
    status = _git(root, "status")
    if (
        _git(root, "head") != head
        or _git(root, "base", base_ref) != resolved_base
        or _git(root, "status") != status
    ):
        raise InspectionFailure(
            "corrupt",
            "repository-changed-during-read",
            "Repository authority changed during inspection",
        )
    if (
        re.fullmatch(r"[0-9a-f]{40}", head) is None
        or re.fullmatch(r"[0-9a-f]{40}", head_tree) is None
        or re.fullmatch(r"[0-9a-f]{40}", resolved_base) is None
        or re.fullmatch(r"[0-9a-f]{40}", base_tree) is None
        or re.fullmatch(r"[0-9a-f]{40}", merge_base) is None
        or not commit_count.isdigit()
    ):
        raise InspectionFailure(
            "corrupt", "invalid-git-object-id", "Git returned an invalid object identifier"
        )
    return {
        "head": {"commit": head, "tree": head_tree},
        "base": {
            "ref": base_ref,
            "recorded_commit": baseline["sha"],
            "resolved_commit": resolved_base,
            "tree": base_tree,
            "matches_recorded": resolved_base == baseline["sha"],
        },
        "merge_base": merge_base,
        "head_descends_from_resolved_base": ancestor,
        "commit_count_from_resolved_base": int(commit_count),
        "worktree": {
            "clean": status == b"",
            "status_sha256": sha256(status),
        },
    }


def latest_validation_observation(state):
    attempts = state.get("validation_attempts")
    if not isinstance(attempts, list) or not attempts:
        return {"status": "not-recorded"}
    attempt = attempts[-1]
    if not isinstance(attempt, dict) or not isinstance(attempt.get("status"), str):
        raise InspectionFailure(
            "corrupt", "invalid-validation-attempt", "Latest validation attempt is invalid"
        )
    result = {
        "status": attempt["status"],
    }
    if isinstance(attempt.get("attempt_id"), str):
        result["attempt_id"] = attempt["attempt_id"]
    attempt_checks = attempt.get("checks")
    if not isinstance(attempt_checks, list):
        raise InspectionFailure(
            "corrupt",
            "invalid-validation-checks",
            "Latest validation checks must be a list",
        )
    checks = []
    for check in attempt_checks:
        if not isinstance(check, dict) or not isinstance(check.get("name"), str):
            raise InspectionFailure(
                "corrupt", "invalid-validation-check", "Validation check record is invalid"
            )
        item = {"name": check["name"], "status": check.get("status", "unknown")}
        for field in ("raw_log", "incremental_log"):
            if _reference_shape(check.get(field)) == "complete":
                item[field] = validate_reference(check[field])
        result_record = check.get("result")
        if (
            isinstance(result_record, dict)
            and _reference_shape(result_record.get("object")) == "complete"
        ):
            item["result"] = validate_reference(result_record["object"])
        checks.append(item)
    result["checks"] = checks
    return result


def inspector_identity():
    if LOADED_SOURCE_SHA256 is None:
        raise InspectionFailure(
            "missing",
            "inspector-source-unreadable",
            "Inspector source was unreadable when loaded: %s" % LOADED_SOURCE_ERROR,
        )
    try:
        source = pathlib.Path(__file__).resolve().read_bytes()
    except OSError as error:
        raise InspectionFailure(
            "missing", "inspector-source-unreadable", "Cannot hash inspector source: %s" % error
        )
    current_sha256 = sha256(source)
    if current_sha256 != LOADED_SOURCE_SHA256:
        raise InspectionFailure(
            "corrupt",
            "inspector-source-changed",
            "Inspector source changed after the executing module was loaded",
        )
    return {
        "version": INSPECTOR_VERSION,
        "source_sha256": current_sha256,
    }


def inspect(root, issue, run_id=None, correction=None):
    store = resolve_store(root)
    pointer_data, pointer = read_pointer(store, issue)
    reader = AuthorityReader(store, issue)
    indexes = load_index_chain(reader, pointer, issue)
    row, binding = select_run(pointer, indexes, run_id, correction)
    reader.correction = row.get("number")
    selected = verify_selected_run(reader, issue, row, binding)
    repository = inspect_repository(store.repository_root, selected["state_value"])
    objects = [
        {
            "sha256": item["sha256"],
            "size": item["size"],
            "kinds": sorted(item["kinds"]),
        }
        for item in sorted(
            reader.verified.values(), key=lambda item: item["sha256"]
        )
    ]
    authority = {
        "issue": issue,
        "pointer_sha256": sha256(pointer_data),
        "index_generation": pointer["generation"],
        "index": validate_reference(pointer["index"], "issue-index"),
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
        "inspector": inspector_identity(),
        "authority": authority,
        "repository": repository,
        "observations": {
            "latest_validation": latest_validation_observation(
                selected["state_value"]
            ),
        },
    }


def inspection_document(subject):
    return {
        "format": INSPECTION_FORMAT,
        "canonicalization": CANONICALIZATION,
        "outcome": {"status": "resolved"},
        **subject,
    }


def checkpoint_document(subject):
    checkpoint = {
        "format": CHECKPOINT_FORMAT,
        "canonicalization": CANONICALIZATION,
        **subject,
    }
    checkpoint["checkpoint_sha256"] = sha256(canonical_bytes(checkpoint))
    return checkpoint


class InspectorArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise InspectionFailure(
            "unsupported", "invalid-cli", "Invalid command line: %s" % message
        )


def build_parser():
    parser = InspectorArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(
        dest="command", required=True, parser_class=InspectorArgumentParser
    )
    for command in ("inspect", "checkpoint"):
        command_parser = subparsers.add_parser(command)
        command_parser.add_argument("issue", type=int)
        command_parser.add_argument("--root", default=".")
        selectors = command_parser.add_mutually_exclusive_group()
        selectors.add_argument("--run-id")
        selectors.add_argument("--correction", type=int)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        if args.issue < 1:
            raise InspectionFailure(
                "unsupported", "invalid-issue", "Issue number must be positive"
            )
        if args.run_id is not None and RUN_ID_RE.fullmatch(args.run_id) is None:
            raise InspectionFailure(
                "unsupported",
                "invalid-run-id",
                "Run ID must be 32 lowercase hexadecimal characters",
            )
        subject = inspect(
            pathlib.Path(args.root),
            args.issue,
            run_id=args.run_id,
            correction=args.correction,
        )
        document = (
            checkpoint_document(subject)
            if args.command == "checkpoint"
            else inspection_document(subject)
        )
        sys.stdout.buffer.write(canonical_document(document))
        return 0
    except InspectionFailure as failure:
        sys.stdout.buffer.write(canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
    except OSError as error:
        failure = InspectionFailure(
            "missing", "filesystem-read-failed", "Read-only filesystem access failed: %s" % error
        )
        sys.stdout.buffer.write(canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]


if __name__ == "__main__":
    sys.exit(main())
