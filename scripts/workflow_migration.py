#!/usr/bin/env python3
"""Deterministic, immutable compatibility adapter for legacy workflow evidence."""

import argparse
import base64
import datetime as dt
import json
import pathlib
import re
import sys

try:
    import workflow_cas
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_kernel as kernel
except ModuleNotFoundError:
    from scripts import workflow_cas
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_kernel as kernel


MIGRATION_VERSION = "1.0.0"
REQUEST_FORMAT = "chess-echo-workflow-migration-request-v1"
PLAN_FORMAT = "chess-echo-workflow-migration-plan-v1"
SOURCE_MANIFEST_FORMAT = "chess-echo-migration-source-manifest-v1"
RESULT_FORMAT = "chess-echo-workflow-migration-result-v1"
CANONICAL_VARIANT = "canonical-binding"
PROJECTION_VARIANTS = {
    "projection-v1": 1,
    "projection-v2": 2,
    "projection-v3": 3,
    "projection-v4": 4,
}
SETTLED_VARIANT = "settled-adoption"
DURABLE_VARIANT = "durable-v4"
LEGACY_ADOPTION_CONFIRMATION = (
    "legacy_run_trusted"
)
LEGACY_STATES = {
    "PLANNING",
    "PLAN_REVIEW",
    "WAITING_FOR_PLAN_HUMAN_APPROVAL",
    "TEST_IMPLEMENTATION",
    "TEST_REVIEW",
    "WAITING_FOR_TEST_HUMAN_APPROVAL",
    "IMPLEMENTATION",
    "VALIDATION",
    "FINAL_REVIEW",
    "DRAFT_PR_CREATED",
    "WAITING_FOR_PR_HUMAN_APPROVAL",
    "PR_APPROVED",
}
LEGACY_EVENTS = {
    "WORKFLOW_INITIALIZED",
    "PLAN_SUBMITTED",
    "PLAN_REVIEWED",
    "PLAN_HUMAN_APPROVED",
    "PLAN_HUMAN_REJECTED",
    "PLAN_HUMAN_REOPENED",
    "TESTS_SUBMITTED",
    "TESTS_REVIEWED",
    "TESTS_HUMAN_APPROVED",
    "TESTS_HUMAN_REJECTED",
    "TESTS_HUMAN_REOPENED",
    "IMPLEMENTATION_SUBMITTED",
    "VALIDATION_COMPLETED",
    "VALIDATION_INVALIDATED",
    "IMPLEMENTATION_REVIEWED",
    "DRAFT_PR_CREATED",
    "PR_HUMAN_APPROVAL_REQUESTED",
    "PR_HUMAN_APPROVED",
    "PR_HUMAN_REJECTED",
    "PR_METADATA_HUMAN_REVISED",
    "CORRECTION_CREATED",
}
LEGACY_EVENT_STATES = {
    "WORKFLOW_INITIALIZED": {"PLANNING"},
    "PLAN_SUBMITTED": {"PLAN_REVIEW"},
    "PLAN_REVIEWED": {"PLANNING", "WAITING_FOR_PLAN_HUMAN_APPROVAL"},
    "PLAN_HUMAN_APPROVED": {"TEST_IMPLEMENTATION"},
    "PLAN_HUMAN_REJECTED": {"PLANNING"},
    "PLAN_HUMAN_REOPENED": {"PLANNING"},
    "TESTS_SUBMITTED": {"TEST_REVIEW"},
    "TESTS_REVIEWED": {"TEST_IMPLEMENTATION", "IMPLEMENTATION"},
    "TESTS_HUMAN_APPROVED": {"IMPLEMENTATION"},
    "TESTS_HUMAN_REJECTED": {"TEST_IMPLEMENTATION"},
    "TESTS_HUMAN_REOPENED": {"TEST_IMPLEMENTATION"},
    "IMPLEMENTATION_SUBMITTED": {"VALIDATION"},
    "VALIDATION_COMPLETED": {"VALIDATION", "FINAL_REVIEW"},
    "VALIDATION_INVALIDATED": {"IMPLEMENTATION"},
    "IMPLEMENTATION_REVIEWED": {"IMPLEMENTATION", "DRAFT_PR_CREATED"},
    "DRAFT_PR_CREATED": {"DRAFT_PR_CREATED"},
    "PR_HUMAN_APPROVAL_REQUESTED": {"WAITING_FOR_PR_HUMAN_APPROVAL"},
    "PR_HUMAN_APPROVED": {"PR_APPROVED"},
    "PR_HUMAN_REJECTED": {"WAITING_FOR_PR_HUMAN_APPROVAL"},
    "PR_METADATA_HUMAN_REVISED": {"WAITING_FOR_PR_HUMAN_APPROVAL"},
    "CORRECTION_CREATED": {
        "WAITING_FOR_PR_HUMAN_APPROVAL",
        "IMPLEMENTATION",
        "TEST_IMPLEMENTATION",
        "PLANNING",
    },
}
CORRECTION_ARTIFACT_KINDS = (
    "plan",
    "plan_review",
    "test_report",
    "test_review",
    "implementation_report",
    "final_review",
)
CORRECTION_APPROVAL_KEYS = ("plan", "tests", "pr")
CORRECTION_EVIDENCE_FIELDS = (
    "validation",
    "validated_fingerprint",
    "validated_head",
    "validated_base",
    "validated_test_fingerprint",
    "validation_evidence",
    "final_review",
    "draft_pr",
)
CORRECTION_PROFILES = {
    "metadata-only": {
        "state": "WAITING_FOR_PR_HUMAN_APPROVAL",
        "artifacts": CORRECTION_ARTIFACT_KINDS,
        "approvals": ("plan", "tests"),
        "evidence": CORRECTION_EVIDENCE_FIELDS,
    },
    "implementation-only": {
        "state": "IMPLEMENTATION",
        "artifacts": ("plan", "plan_review", "test_report", "test_review"),
        "approvals": ("plan", "tests"),
        "evidence": (),
    },
    "test-contract": {
        "state": "TEST_IMPLEMENTATION",
        "artifacts": ("plan", "plan_review"),
        "approvals": ("plan",),
        "evidence": (),
    },
    "architecture": {
        "state": "PLANNING",
        "artifacts": (),
        "approvals": (),
        "evidence": (),
    },
}
VARIANTS = set(PROJECTION_VARIANTS) | {
    SETTLED_VARIANT,
    DURABLE_VARIANT,
    CANONICAL_VARIANT,
}
SHA256_RE = re.compile(r"[0-9a-f]{64}")
OUTCOME_EXIT_CODES = {
    "resolved": 0,
    "missing": 3,
    "unsupported": 4,
    "corrupt": 5,
    "ambiguous": 6,
    "stale": 7,
}


class MigrationFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.subject = subject

    def document(self):
        outcome = {"status": self.status, "code": self.code, "message": self.message}
        if self.subject is not None:
            outcome["subject"] = self.subject
        return {"format": RESULT_FORMAT, "outcome": outcome}


def _fail(status, code, message, subject=None):
    raise MigrationFailure(status, code, message, subject)


def _exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("corrupt", "invalid-%s-schema" % label, "%s schema is invalid" % label)


def _translate(action):
    try:
        return action()
    except evidence.EvidenceFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except kernel.WorkflowError as failure:
        _fail("corrupt", "invalid-v4-envelope", str(failure))


def _path(value, label):
    try:
        encoded = value.encode("utf-8") if isinstance(value, str) else None
    except UnicodeEncodeError:
        encoded = None
    if (
        not isinstance(value, str)
        or not value
        or value.startswith("/")
        or "\\" in value
        or "\0" in value
        or any(part in {"", ".", ".."} for part in value.split("/"))
        or encoded is None
        or len(encoded) > 4096
    ):
        _fail("unsupported", "invalid-path", "%s is not a normalized relative path" % label)
    return value


def _reference(kind, data, encoding=None):
    reference = {"kind": kind, "sha256": inspector.sha256(data), "size": len(data)}
    if encoding is not None:
        reference["encoding"] = encoding
    return reference


def _decode_record(record):
    _exact_keys(
        record,
        {"logical_path", "kind", "sha256", "size", "bytes_base64"},
        "source-record",
    )
    logical_path = _path(record["logical_path"], "source logical path")
    if (
        not isinstance(record["sha256"], str)
        or SHA256_RE.fullmatch(record["sha256"]) is None
        or type(record["size"]) is not int
        or record["size"] < 0
        or not isinstance(record["kind"], str)
    ):
        _fail("corrupt", "invalid-source-record", "Source record facts are invalid")
    encoded = record["bytes_base64"]
    if not isinstance(encoded, str):
        _fail("corrupt", "invalid-source-base64", "Source bytes are not valid base64")
    if record["size"] > evidence.PAYLOAD_LIMIT:
        _fail("unsupported", "source-object-too-large", "Source object exceeds 64 MiB")
    maximum_encoded_size = 4 * ((evidence.PAYLOAD_LIMIT + 2) // 3)
    if len(encoded) > maximum_encoded_size:
        _fail("unsupported", "source-object-too-large", "Encoded source exceeds 64 MiB")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (TypeError, ValueError):
        _fail("corrupt", "invalid-source-base64", "Source bytes are not valid base64")
    if len(data) != record["size"] or inspector.sha256(data) != record["sha256"]:
        _fail("corrupt", "source-record-mismatch", "Source bytes fail hash or size verification")
    if record["kind"] not in inspector.SUPPORTED_OBJECT_KINDS:
        _fail("unsupported", "unsupported-source-kind", "Source object kind is unsupported")
    if (
        record["kind"]
        in (inspector.JSON_OBJECT_KINDS | inspector.TYPED_PAYLOAD_KINDS)
        and len(data) > evidence.STRUCTURED_OBJECT_LIMIT
    ):
        _fail("unsupported", "structured-object-too-large", "Structured source exceeds 8 MiB")
    encoding = "typed-base64" if record["kind"] in inspector.TYPED_PAYLOAD_KINDS else None
    reference = _translate(lambda: inspector.validate_reference(
        _reference(record["kind"], data, encoding)
    ))
    return logical_path, data, reference


def _reject_json_constant(constant):
    raise ValueError("non-standard JSON constant %s" % constant)


def _parse_json(data, label):
    try:
        value = json.loads(
            data.decode("utf-8"),
            parse_constant=_reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError) as error:
        _fail("corrupt", "invalid-source-json", "%s is invalid JSON: %s" % (label, error))
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-source-json", "%s must be a JSON object" % label)
    _translate(lambda: inspector.canonical_bytes(value))
    return value


def _canonical_state_bytes(state):
    return (
        json.dumps(
            state,
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
            allow_nan=False,
        )
        + "\n"
    ).encode()


def _parse_history(data):
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        _fail("corrupt", "invalid-history", "History is not UTF-8: %s" % error)
    events = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line:
            _fail("corrupt", "invalid-history", "History contains an empty line")
        try:
            event = json.loads(line, parse_constant=_reject_json_constant)
        except (json.JSONDecodeError, ValueError, RecursionError) as error:
            _fail("corrupt", "invalid-history", "History line %s is invalid: %s" % (number, error))
        if not isinstance(event, dict):
            _fail("corrupt", "invalid-history", "History events must be JSON objects")
        _translate(lambda: inspector.canonical_bytes(event))
        events.append(event)
    if not events:
        _fail("missing", "history-missing", "Workflow history is empty")
    try:
        canonical = "".join(
            json.dumps(item, ensure_ascii=True, sort_keys=True) + "\n"
            for item in events
        ).encode()
    except (RecursionError, ValueError):
        _fail(
            "corrupt",
            "invalid-history",
            "History exceeds canonical JSON number or depth limits",
        )
    if canonical != data:
        _fail("corrupt", "noncanonical-history", "History bytes are not canonical legacy JSONL")
    return events


def _json_identity(value):
    return json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()


def _validate_legacy_correction(state, correction):
    if correction is None:
        if state.get("parent_run") is not None:
            _fail("corrupt", "unexpected-parent-run", "Root projection names a parent run")
        return
    correction_record = state.get("correction")
    _exact_keys(
        correction_record,
        {
            "number",
            "classification",
            "reason",
            "requested_by",
            "created_at",
            "inherited",
            "invalidated",
        },
        "legacy-correction",
    )
    if (
        type(correction_record["number"]) is not int
        or correction_record["number"] != correction
        or not all(
            isinstance(correction_record[field], str)
            and correction_record[field].strip()
            for field in ("classification", "reason", "requested_by", "created_at")
        )
        or not isinstance(correction_record["inherited"], list)
        or not isinstance(correction_record["invalidated"], list)
        or not all(
            isinstance(item, str) and item
            for field in ("inherited", "invalidated")
            for item in correction_record[field]
        )
    ):
        _fail(
            "corrupt",
            "invalid-legacy-correction",
            "Legacy correction metadata is invalid",
        )
    classification = correction_record["classification"]
    profile = CORRECTION_PROFILES.get(classification)
    if profile is None:
        _fail(
            "unsupported",
            "unsupported-correction-classification",
            "Legacy correction classification is unsupported",
        )
    inherited = []
    invalidated = []
    for kind in CORRECTION_ARTIFACT_KINDS:
        (inherited if kind in profile["artifacts"] else invalidated).append(
            "artifact:%s" % kind
        )
    for key in CORRECTION_APPROVAL_KEYS:
        (inherited if key in profile["approvals"] else invalidated).append(
            "approval:%s" % key
        )
    for field in CORRECTION_EVIDENCE_FIELDS:
        (inherited if field in profile["evidence"] else invalidated).append(
            "evidence:%s" % field
        )
    if (
        correction_record["inherited"] != inherited
        or correction_record["invalidated"] != invalidated
    ):
        _fail(
            "corrupt",
            "invalid-correction-profile",
            "Legacy correction inheritance does not match its classification",
        )
    parent_run = state.get("parent_run")
    _exact_keys(
        parent_run,
        {
            "issue",
            "correction",
            "state",
            "validated_head",
            "validated_base",
            "state_sha256",
            "history_sha256",
        },
        "legacy-parent-run",
    )
    if (
        type(parent_run["issue"]) is not int
        or parent_run["issue"] < 1
        or (
            parent_run["correction"] is not None
            and (
                type(parent_run["correction"]) is not int
                or parent_run["correction"] < 1
            )
        )
        or not isinstance(parent_run["state"], str)
        or parent_run["state"]
        not in {"WAITING_FOR_PR_HUMAN_APPROVAL", "PR_APPROVED"}
        or any(
            value is not None and not isinstance(value, str)
            for value in (
                parent_run["validated_head"],
                parent_run["validated_base"],
            )
        )
        or any(
            not isinstance(parent_run[field], str)
            or SHA256_RE.fullmatch(parent_run[field]) is None
            for field in ("state_sha256", "history_sha256")
        )
    ):
        _fail(
            "corrupt",
            "invalid-legacy-parent-run",
            "Legacy parent-run facts are invalid",
        )
    correction_history = state.get("history")
    if (
        not isinstance(correction_history, list)
        or not correction_history
        or not isinstance(correction_history[0], dict)
    ):
        _fail(
            "corrupt",
            "invalid-correction-bootstrap",
            "Legacy correction bootstrap history is invalid",
        )
    first_event = correction_history[0]
    if (
        first_event.get("event") != "CORRECTION_CREATED"
        or first_event.get("state") != profile["state"]
        or first_event.get("actor") != correction_record["requested_by"]
        or first_event.get("timestamp") != correction_record["created_at"]
        or first_event.get("details")
        != {
            "number": correction,
            "classification": classification,
            "reason": correction_record["reason"],
            "parent_correction": parent_run["correction"],
        }
    ):
        _fail(
            "corrupt",
            "invalid-correction-bootstrap",
            "Legacy correction bootstrap event is invalid",
        )


def _validate_settled_envelope(envelope, issue, correction, version):
    _exact_keys(
        envelope,
        {
            "adopted_at",
            "adopted_by",
            "confirmation",
            "correction",
            "format",
            "history_bytes",
            "history_sha256",
            "issue",
            "legacy_version",
            "mode",
            "reason",
            "state_bytes",
            "state_sha256",
        },
        "settled-adoption",
    )
    if (
        type(envelope["issue"]) is not int
        or envelope["issue"] != issue
        or (
            correction is not None
            and type(envelope["correction"]) is not int
        )
        or envelope["correction"] != correction
        or type(envelope["legacy_version"]) is not int
        or envelope["legacy_version"] != version
        or envelope["confirmation"] != LEGACY_ADOPTION_CONFIRMATION
        or any(
            not isinstance(envelope[field], str) or not envelope[field].strip()
            for field in ("adopted_by", "reason")
        )
    ):
        _fail("corrupt", "invalid-integrity-record", "Settled adoption metadata is invalid")
    adopted_at = envelope["adopted_at"]
    if not isinstance(adopted_at, str):
        _fail("corrupt", "invalid-integrity-record", "Settled adoption timestamp is invalid")
    try:
        parsed = dt.datetime.fromisoformat(adopted_at.replace("Z", "+00:00"))
    except ValueError:
        _fail("corrupt", "invalid-integrity-record", "Settled adoption timestamp is invalid")
    if parsed.tzinfo is None:
        _fail("corrupt", "invalid-integrity-record", "Settled adoption timestamp has no timezone")


def _validate_projection(source, records):
    by_path = {record["logical_path"]: decoded for record, decoded in records}
    if len(by_path) != len(records):
        _fail("ambiguous", "duplicate-source-path", "Source contains duplicate logical paths")
    if any(path.endswith("transaction.json") for path in by_path):
        _fail(
            "unsupported",
            "incomplete-legacy-transaction",
            "Incomplete legacy transactions cannot be migrated",
        )
    if "state.json" not in by_path or "history.jsonl" not in by_path:
        _fail("missing", "projection-record-missing", "state.json and history.jsonl are required")
    state_data = by_path["state.json"][1]
    history_data = by_path["history.jsonl"][1]
    structural_paths = {"state.json", "history.jsonl"}
    if source["variant"] in {"projection-v4", SETTLED_VARIANT}:
        structural_paths.add("integrity.json")
    if any(
        by_path[path][2]["kind"] != "legacy-raw-evidence"
        for path in structural_paths
        if path in by_path
    ):
        _fail(
            "unsupported",
            "unsupported-structural-record-kind",
            "Projection structure records must be legacy raw evidence",
        )
    state = _parse_json(state_data, "state.json")
    events = _parse_history(history_data)
    version = state.get("version", 1)
    expected = (
        PROJECTION_VARIANTS.get(source["variant"])
        if source["variant"] in PROJECTION_VARIANTS
        else version
    )
    if type(version) is not int or version != expected:
        _fail("unsupported", "unsupported-projection-version", "Projection version is unsupported")
    if source["variant"] == SETTLED_VARIANT and version not in {1, 2, 3}:
        _fail(
            "unsupported",
            "unsupported-projection-version",
            "Settled adoption must contain a legacy v1-v3 projection",
        )
    if (
        source["variant"] in PROJECTION_VARIANTS
        and version < 4
        and "integrity.json" in by_path
    ):
        _fail(
            "unsupported",
            "unexpected-integrity-record",
            "Plain v1-v3 projections cannot include an integrity sidecar",
        )
    issue_record = state.get("issue")
    if (
        not isinstance(issue_record, dict)
        or type(issue_record.get("number")) is not int
        or issue_record["number"] != source["issue"]
    ):
        _fail("corrupt", "projection-identity-mismatch", "Projection has the wrong issue")
    correction = source["correction"]
    _validate_legacy_correction(state, correction)
    if _json_identity(state.get("history")) != _json_identity(events):
        _fail("corrupt", "state-history-mismatch", "Embedded and projected history differ")
    if _canonical_state_bytes(state) != state_data:
        _fail("corrupt", "noncanonical-state", "State bytes are not canonical legacy JSON")
    for sequence, event in enumerate(events, 1):
        if type(event.get("sequence")) is not int or event["sequence"] != sequence:
            _fail("corrupt", "invalid-history-sequence", "History sequence is not contiguous")
        if not all(field in event for field in ("timestamp", "event", "actor", "state", "details")):
            _fail("corrupt", "invalid-history-event", "History event fields are incomplete")
        if (
            not all(
                isinstance(event[field], str)
                for field in ("timestamp", "event", "actor", "state")
            )
            or not isinstance(event["details"], dict)
        ):
            _fail("corrupt", "invalid-history-event", "History event field types are invalid")
    if not isinstance(state.get("state"), str) or events[-1].get("state") != state["state"]:
        _fail("corrupt", "state-history-mismatch", "Current state differs from final history event")
    _translate(
        lambda: kernel.validate_run_structure(
            state, events, source["issue"], correction, {version}
        )
    )
    for check in state["required_checks"]:
        if (
            not isinstance(check, dict)
            or not isinstance(check.get("name"), str)
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", check["name"]) is None
            or not isinstance(check.get("command"), list)
            or not check["command"]
            or any(not isinstance(part, str) or not part for part in check["command"])
            or (
                "cwd" in check
                and (not isinstance(check["cwd"], str) or not check["cwd"])
            )
        ):
            _fail(
                "corrupt",
                "invalid-required-check",
                "Legacy validation check structure is invalid",
            )
    if any(not isinstance(path, str) or not path for path in state["test_paths"]):
        _fail("corrupt", "invalid-test-path", "Legacy test path structure is invalid")
    if any(
        not isinstance(record, dict)
        for field in ("artifacts", "approvals", "validation")
        for record in state[field].values()
    ):
        _fail(
            "corrupt",
            "invalid-legacy-record",
            "Legacy nested workflow records must be objects",
        )
    if state["state"] not in LEGACY_STATES or any(
        event["state"] not in LEGACY_STATES
        or event["event"] not in LEGACY_EVENTS
        or event["state"] not in LEGACY_EVENT_STATES[event["event"]]
        for event in events
    ):
        _fail(
            "unsupported",
            "unsupported-legacy-lifecycle",
            "Projection contains an unknown lifecycle state or event",
        )
    if (
        correction is None
        and source["variant"] in PROJECTION_VARIANTS
        and events[0]["event"] != "WORKFLOW_INITIALIZED"
    ):
        _fail(
            "unsupported",
            "unsupported-legacy-lifecycle",
            "Legacy root history does not begin with workflow initialization",
        )
    if source["variant"] in {"projection-v4", SETTLED_VARIANT}:
        if "integrity.json" not in by_path:
            _fail("missing", "integrity-record-missing", "A committed integrity record is required")
        envelope = _parse_json(by_path["integrity.json"][1], "integrity.json")
        expected_mode = (
            "v4-committed" if source["variant"] == "projection-v4"
            else "settled-legacy-adoption"
        )
        if (
            envelope.get("format") != "chess-echo-run-integrity-v4"
            or envelope.get("mode") != expected_mode
            or type(envelope.get("issue")) is not int
            or envelope["issue"] != source["issue"]
            or envelope.get("correction") != correction
            or (
                correction is not None
                and type(envelope.get("correction")) is not int
            )
            or envelope.get("state_sha256") != inspector.sha256(state_data)
            or envelope.get("history_sha256") != inspector.sha256(history_data)
            or (
                expected_mode == "v4-committed"
                and type(envelope.get("sequence")) is not int
            )
        ):
            _fail("corrupt", "invalid-integrity-record", "Integrity record does not bind the projection")
        if expected_mode == "v4-committed":
            committed, committed_state, committed_history = _translate(
                lambda: kernel.validate_committed_envelope(
                    envelope, source["issue"], correction
                )
            )
            if (
                committed != state
                or committed_state != state_data
                or committed_history != history_data
            ):
                _fail("corrupt", "invalid-integrity-record", "Committed snapshot differs from projection")
        else:
            _validate_settled_envelope(
                envelope, source["issue"], correction, version
            )
            if state["state"] not in {
                "WAITING_FOR_PR_HUMAN_APPROVAL",
                "PR_APPROVED",
            }:
                _fail(
                    "corrupt",
                    "invalid-integrity-record",
                    "Settled adoption does not contain a settled run",
                )
            try:
                adopted_state = base64.b64decode(envelope["state_bytes"], validate=True)
                adopted_history = base64.b64decode(envelope["history_bytes"], validate=True)
            except (KeyError, TypeError, ValueError):
                _fail("corrupt", "invalid-integrity-record", "Settled adoption payload is invalid")
            if adopted_state != state_data or adopted_history != history_data:
                _fail("corrupt", "invalid-integrity-record", "Settled adoption bytes differ")
    if "migration" not in state:
        migration = {"status": "not-recorded"}
    elif state["migration"] is None:
        migration = {"status": "none"}
    elif isinstance(state["migration"], dict):
        migration = {"status": "recorded"}
    else:
        _fail("corrupt", "invalid-migration-metadata", "Migration metadata has an invalid type")
    return state, events, state_data, history_data, migration


def _decode_selected_payload(data, reference, selection, issue, correction):
    encoding = selection["encoding"]
    if encoding == "raw":
        return data
    if encoding == "base64":
        if reference["kind"] not in inspector.BASE64_PAYLOAD_KINDS:
            _fail("unsupported", "encoding-kind-mismatch", "Base64 encoding is invalid for source kind")
        try:
            return base64.b64decode(data, validate=True)
        except (TypeError, ValueError):
            _fail("corrupt", "invalid-base64-payload", "Selected payload is not valid base64")
    if encoding != "typed-base64" or reference["kind"] not in inspector.TYPED_PAYLOAD_KINDS:
        _fail("unsupported", "encoding-kind-mismatch", "Selected encoding is unsupported")
    wrapper = _parse_json(data, "typed payload")
    if (
        _translate(lambda: inspector.canonical_bytes(wrapper)) != data
        or set(wrapper) != {
            "correction", "issue", "kind", "logical_path", "object_kind", "payload_base64"
        }
        or wrapper.get("kind") != "typed-evidence"
        or wrapper.get("object_kind") != reference["kind"]
        or type(wrapper.get("issue")) is not int
        or wrapper["issue"] != issue
        or (
            correction is not None
            and type(wrapper.get("correction")) is not int
        )
        or wrapper.get("correction") != correction
        or wrapper.get("logical_path") != selection["logical_path"]
    ):
        _fail("corrupt", "invalid-typed-payload", "Typed payload wrapper is invalid")
    try:
        return base64.b64decode(wrapper["payload_base64"], validate=True)
    except (TypeError, ValueError):
        _fail("corrupt", "invalid-typed-payload", "Typed payload bytes are not valid base64")


def _normalize_selection(selection, durable=False):
    keys = {"logical_path", "path", "entry_kind", "mode", "encoding"}
    if durable:
        keys.add("object")
    _exact_keys(selection, keys, "selection")
    logical_path = _path(selection["logical_path"], "selected logical path")
    path = _path(selection["path"], "evidence path")
    if (
        not isinstance(selection["entry_kind"], str)
        or selection["entry_kind"] not in {"regular", "symlink"}
    ):
        _fail("unsupported", "unsupported-entry-kind", "Migration supports regular files and symlinks")
    modes = {"regular": {"100644", "100755"}, "symlink": {"120000"}}
    if (
        not isinstance(selection["mode"], str)
        or selection["mode"] not in modes[selection["entry_kind"]]
    ):
        _fail("unsupported", "unsupported-entry-mode", "Evidence mode is unsupported")
    if (
        not isinstance(selection["encoding"], str)
        or selection["encoding"] not in {"raw", "base64", "typed-base64"}
    ):
        _fail("unsupported", "unsupported-encoding", "Selection encoding is unsupported")
    return logical_path, path


def _request_source(request, supplied_records=None):
    source = request["source"]
    if not isinstance(source, dict):
        _fail("corrupt", "invalid-source-schema", "Migration source must be an object")
    variant = source.get("variant")
    if not isinstance(variant, str) or variant not in VARIANTS:
        _fail("unsupported", "unsupported-source-variant", "Migration source variant is unsupported")
    if variant == CANONICAL_VARIANT:
        _exact_keys(source, {"variant", "binding"}, "canonical-source")
        return variant, [], [], None
    if variant == DURABLE_VARIANT:
        _exact_keys(source, {"variant", "checkpoint", "selection"}, "durable-source")
        if not isinstance(source["checkpoint"], dict):
            _fail("corrupt", "invalid-checkpoint", "Durable checkpoint must be an object")
        selections = source["selection"]
        if not isinstance(selections, list):
            _fail("corrupt", "invalid-selection", "Durable selections must be a list")
        if len(selections) > evidence.MANIFEST_ENTRY_LIMIT:
            _fail("unsupported", "manifest-entry-limit", "Selection exceeds 10,000 objects")
        records = supplied_records
        migration = None
        if records is None:
            snapshot = _translate(
                lambda: evidence.read_verified_v4_sources(
                    request["_root"],
                    source["checkpoint"],
                    selections,
                    include_migration=True,
                )
            )
            records = snapshot["records"]
            migration = snapshot["migration"]
        elif not isinstance(records, list):
            _fail("corrupt", "invalid-source-collections", "Source records must be a list")
        return variant, records, selections, migration
    _exact_keys(
        source,
        {"variant", "issue", "correction", "records", "selection"},
        "projection-source",
    )
    if type(source["issue"]) is not int or source["issue"] < 1:
        _fail("unsupported", "invalid-issue", "Issue must be a positive integer")
    correction = source["correction"]
    if correction is not None and (type(correction) is not int or correction < 1):
        _fail("unsupported", "invalid-correction", "Correction must be a positive integer or null")
    if not isinstance(source["records"], list) or not isinstance(source["selection"], list):
        _fail("corrupt", "invalid-source-collections", "Records and selections must be lists")
    if (
        len(source["records"]) > evidence.MANIFEST_ENTRY_LIMIT
        or len(source["selection"]) > evidence.MANIFEST_ENTRY_LIMIT
    ):
        _fail("unsupported", "manifest-entry-limit", "Source exceeds 10,000 objects")
    return (
        variant,
        source["records"] if supplied_records is None else supplied_records,
        source["selection"],
        None,
    )


def _build_source_manifest(variant, issue, correction, records, selections, migration):
    if len(records) > evidence.MANIFEST_ENTRY_LIMIT:
        _fail("unsupported", "manifest-entry-limit", "Source exceeds 10,000 objects")
    decoded = []
    seen = set()
    for record in records:
        logical_path, data, reference = _decode_record(record)
        if logical_path in seen:
            _fail("ambiguous", "duplicate-source-path", "Source contains duplicate logical paths")
        seen.add(logical_path)
        decoded.append((record, (logical_path, data, reference)))
    selected = {}
    selected_objects = {}
    for selection in selections:
        logical_path, path = _normalize_selection(selection, durable=variant == DURABLE_VARIANT)
        if logical_path in selected or path in {value["path"] for value in selected.values()}:
            _fail("ambiguous", "duplicate-selection", "Selection contains duplicate paths")
        selected[logical_path] = selection
        if variant == DURABLE_VARIANT:
            reference = _translate(lambda: inspector.validate_reference(selection["object"]))
            previous = selected_objects.get(reference["sha256"])
            if previous is not None and previous != selection:
                _fail("ambiguous", "conflicting-selection", "An object has conflicting selections")
            selected_objects[reference["sha256"]] = selection
    by_path = {item[1][0]: item[1] for item in decoded}
    if set(selected) - set(by_path):
        _fail("missing", "selected-source-missing", "A selected source object is missing")
    objects = []
    payloads = {}
    entry_specs = []
    for logical_path, data, reference in (item[1] for item in decoded):
        selection = selected.get(logical_path)
        payload = None
        if reference["kind"] in (
            inspector.TYPED_PAYLOAD_KINDS | inspector.BASE64_PAYLOAD_KINDS
        ):
            _decode_selected_payload(
                data,
                reference,
                {
                    "encoding": (
                        "typed-base64"
                        if reference["kind"] in inspector.TYPED_PAYLOAD_KINDS
                        else "base64"
                    ),
                    "logical_path": logical_path,
                },
                issue,
                correction,
            )
        if selection is not None:
            if variant == DURABLE_VARIANT:
                expected = _translate(lambda: inspector.validate_reference(selection["object"]))
                if expected != reference:
                    _fail("stale", "selected-object-mismatch", "Selected source reference differs")
            payload = _decode_selected_payload(data, reference, selection, issue, correction)
            if len(payload) > evidence.PAYLOAD_LIMIT:
                _fail("unsupported", "payload-too-large", "Decoded payload exceeds 64 MiB")
            payloads[inspector.sha256(payload)] = payload
            entry_specs.append((selection, payload, reference))
        objects.append(
            {
                "logical_path": logical_path,
                "object": reference,
                "encoding": selection["encoding"] if selection else None,
                "payload_sha256": inspector.sha256(payload) if payload is not None else None,
                "payload_size": len(payload) if payload is not None else None,
            }
        )
    if len(entry_specs) > evidence.MANIFEST_ENTRY_LIMIT:
        _fail("unsupported", "manifest-entry-limit", "Selection exceeds 10,000 entries")
    if sum(len(data) for data in payloads.values()) > evidence.PUBLICATION_PAYLOAD_LIMIT:
        _fail("unsupported", "publication-payload-limit", "Decoded payloads exceed 512 MiB")
    manifest = {
        "kind": "migration-source-manifest",
        "format": SOURCE_MANIFEST_FORMAT,
        "variant": variant,
        "issue": issue,
        "correction": correction,
        "migration_metadata": migration,
        "objects": sorted(objects, key=lambda item: item["logical_path"].encode("utf-8")),
    }
    manifest_data = inspector.canonical_bytes(manifest)
    if len(manifest_data) > evidence.STRUCTURED_OBJECT_LIMIT:
        _fail("unsupported", "structured-object-too-large", "Source manifest exceeds 8 MiB")
    manifest_ref = _reference("migration-source-manifest", manifest_data)
    return decoded, entry_specs, payloads, manifest, manifest_data, manifest_ref


def _parent(root, reference):
    if reference is None:
        return None
    return _translate(lambda: evidence._verify_graph(root, reference))


def _validate_projection_parent(root, state, issue, parent_graph):
    facts = state["parent_run"]
    binding = parent_graph[0]
    migration_record = binding["migration"]
    if (
        migration_record is None
        or migration_record["adapter"] != evidence.MIGRATION_ADAPTER_FORMAT
    ):
        _fail(
            "unsupported",
            "parent-source-unverifiable",
            "Legacy correction parent has no migration source manifest",
        )
    reader = evidence._reader(root, issue)
    source_manifest = evidence._read_json(
        reader,
        migration_record["source"],
        "migration-source-manifest",
    )
    validated = evidence._validate_migration_source_manifest(source_manifest)
    by_path = {item["logical_path"]: reference for item, reference in validated}
    if "state.json" not in by_path or "history.jsonl" not in by_path:
        _fail(
            "unsupported",
            "parent-source-unverifiable",
            "Legacy correction parent does not preserve state and history",
        )
    parent_state_data = evidence._read_bytes(
        reader, by_path["state.json"], by_path["state.json"]["kind"]
    )
    parent_state = _parse_json(parent_state_data, "parent state.json")
    expected = {
        "issue": binding["identity"]["issue"],
        "correction": binding["identity"]["correction"],
        "state": parent_state.get("state"),
        "validated_head": parent_state.get("validated_head"),
        "validated_base": parent_state.get("validated_base"),
        "state_sha256": by_path["state.json"]["sha256"],
        "history_sha256": by_path["history.jsonl"]["sha256"],
    }
    if facts != expected:
        _fail(
            "stale",
            "parent-run-facts-mismatch",
            "Legacy correction parent facts do not match the exact parent binding",
        )


def _assemble(root, request, supplied_records=None, supplied_migration=None):
    _exact_keys(request, {"format", "source", "decision", "lineage"}, "request")
    if request["format"] != REQUEST_FORMAT:
        _fail("unsupported", "unsupported-request-format", "Migration request format is unsupported")
    local = dict(request)
    local["_root"] = root
    variant, records, selections, snapshot_migration = _request_source(
        local, supplied_records
    )
    if variant == CANONICAL_VARIANT:
        if request["decision"] is not None or request["lineage"] is not None:
            _fail("corrupt", "invalid-canonical-request", "Canonical no-op has no decision or lineage")
        binding = _translate(lambda: inspector.validate_reference(
            request["source"]["binding"], "evidence-binding"
        ))
        verification = _translate(lambda: evidence.verify(root, binding))
        body = {
            "format": PLAN_FORMAT,
            "canonicalization": inspector.CANONICALIZATION,
            "request": request,
            "operation": "no-op",
            "binding": binding,
            "verification": verification["outcome"],
        }
        body["plan_sha256"] = inspector.sha256(inspector.canonical_bytes(body))
        return body
    _exact_keys(request["lineage"], {"status", "parent_binding", "subject"}, "lineage-request")
    status = request["lineage"]["status"]
    if not isinstance(status, str) or status not in evidence.LINEAGE_STATUSES:
        _fail("unsupported", "unsupported-lineage-status", "Lineage status is unsupported")
    source = request["source"]
    if variant == DURABLE_VARIANT:
        checkpoint = source["checkpoint"]
        if not isinstance(checkpoint, dict):
            _fail("corrupt", "invalid-checkpoint", "Durable checkpoint must be an object")
        authority = checkpoint.get("authority", {})
        if not isinstance(authority, dict):
            _fail("corrupt", "invalid-checkpoint", "Checkpoint authority must be an object")
        issue = authority.get("issue")
        correction = authority.get("correction")
        if type(issue) is not int or issue < 1:
            _fail("corrupt", "invalid-checkpoint", "Checkpoint issue is invalid")
        state_data = history_data = None
        if supplied_migration is None:
            migration = snapshot_migration
        else:
            if (
                not isinstance(supplied_migration, dict)
                or not isinstance(supplied_migration.get("status"), str)
                or supplied_migration["status"]
                not in {"not-recorded", "none", "recorded"}
                or set(supplied_migration) != {"status"}
            ):
                _fail(
                    "corrupt",
                    "invalid-migration-metadata",
                    "Planned durable migration metadata is invalid",
                )
            migration = supplied_migration
        identity = {
            "issue": issue,
            "run_id": authority.get("run_id"),
            "family_run_id": authority.get("family_run_id"),
            "correction": correction,
            "run_generation": authority.get("run_generation"),
            "sequence": authority.get("sequence"),
            "event_tip": authority.get("event_tip"),
        }
    else:
        pairs = [(record, _decode_record(record)) for record in records]
        state, events, state_data, history_data, migration = _validate_projection(source, pairs)
        issue = source["issue"]
        correction = source["correction"]
        seed = {
            "issue": issue,
            "correction": correction,
            "state_sha256": inspector.sha256(state_data),
            "history_sha256": inspector.sha256(history_data),
        }
        run_id = inspector.sha256(inspector.canonical_bytes(seed))[:32]
        identity = {
            "issue": issue,
            "run_id": run_id,
            "family_run_id": run_id,
            "correction": correction,
            "run_generation": 0,
            "sequence": events[-1]["sequence"],
            "event_tip": inspector.sha256(history_data),
        }
    parent_ref = request["lineage"]["parent_binding"]
    parent_graph = _parent(root, parent_ref)
    if correction is not None and parent_graph is None:
        _fail("missing", "correction-parent-missing", "Corrections require an exact parent binding")
    if status == "original" and parent_ref is not None:
        _fail("corrupt", "unexpected-parent-binding", "Original lineage cannot name a parent")
    if status != "original" and parent_graph is None:
        _fail("missing", "parent-binding-missing", "Non-original lineage requires a parent")
    if parent_graph is not None:
        parent_binding = parent_graph[0]
        if (
            parent_binding["identity"]["issue"] != issue
            or parent_binding["identity"]["run_id"] == identity["run_id"]
        ):
            _fail("stale", "lineage-identity-mismatch", "Parent identity is incompatible")
        parent_family = parent_binding["identity"]["family_run_id"]
        if variant == DURABLE_VARIANT:
            if identity["family_run_id"] != parent_family:
                _fail(
                    "stale",
                    "lineage-family-mismatch",
                    "Durable family identity differs from its parent",
                )
        else:
            identity["family_run_id"] = parent_family
            if correction is not None:
                _validate_projection_parent(root, state, issue, parent_graph)
    decoded, entry_specs, payloads, source_manifest, source_data, source_ref = (
        _build_source_manifest(variant, issue, correction, records, selections, migration)
    )
    subject = request["lineage"]["subject"]
    if subject is None:
        subject = parent_graph[0]["subject"] if status == "inherited" else source_ref
    else:
        subject = _translate(lambda: inspector.validate_reference(subject))
    entries = []
    captures = []
    payload_records = []
    for selection, payload, source_object in entry_specs:
        digest = inspector.sha256(payload)
        entry = {
            "path": selection["path"],
            "kind": selection["entry_kind"],
            "mode": selection["mode"],
            "content_sha256": digest,
            "size": len(payload),
            "payload": {"kind": "evidence-payload", "sha256": digest, "size": len(payload)},
        }
        entries.append(entry)
        captures.append(
            {
                "entry_sha256": evidence._entry_digest(entry),
                "capture_method": "deterministic-migration",
                "captured_at": None,
                "source": {
                    "type": "migration",
                    "object": source_object,
                    "source_manifest": source_ref,
                    "logical_path": selection["logical_path"],
                },
                "tool": {"name": "workflow_migration", "version": MIGRATION_VERSION},
            }
        )
    for digest, payload in sorted(payloads.items()):
        payload_records.append(
            {
                "sha256": digest,
                "size": len(payload),
                "bytes_base64": base64.b64encode(payload).decode("ascii"),
            }
        )
    publication = {
        "format": evidence.PUBLICATION_FORMAT,
        "identity": identity,
        "decision": request["decision"],
        "subject": subject,
        "lineage": {"status": status, "parent_binding": parent_ref},
        "migration": {"adapter": evidence.MIGRATION_ADAPTER_FORMAT, "source": source_ref},
        "entries": entries,
        "captures": captures,
        "payloads": payload_records,
    }
    prepared = _translate(lambda: evidence._load_publication(publication))
    if status == "inherited" and (
        prepared["manifest"][2] != parent_graph[0]["manifest"]
        or subject != parent_graph[0]["subject"]
    ):
        _fail(
            "stale",
            "inherited-evidence-changed",
            "Inherited evidence changed its manifest or subject",
        )
    if subject != source_ref:
        _translate(
            lambda: evidence._read_bytes(
                evidence._reader(root, issue), subject, subject["kind"]
            )
        )
    source_objects = [record for record, _decoded in decoded]
    body = {
        "format": PLAN_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "request": request,
        "operation": "publish",
        "precondition": source["checkpoint"] if variant == DURABLE_VARIANT else None,
        "source_objects": source_objects,
        "source_manifest": source_manifest,
        "publication": publication,
        "expected": {
            "source_manifest": source_ref,
            "manifest": prepared["manifest"][2],
            "provenance": prepared["provenance"][2],
            "binding": prepared["binding"][2],
        },
    }
    body["plan_sha256"] = inspector.sha256(inspector.canonical_bytes(body))
    return body


def plan(root, request):
    return _assemble(pathlib.Path(root), request)


def _validate_plan(root, value):
    if not isinstance(value, dict) or value.get("format") != PLAN_FORMAT:
        _fail("unsupported", "unsupported-plan-format", "Migration plan format is unsupported")
    digest = value.get("plan_sha256")
    unsigned = dict(value)
    unsigned.pop("plan_sha256", None)
    calculated = _translate(lambda: inspector.sha256(inspector.canonical_bytes(unsigned)))
    if not isinstance(digest, str) or calculated != digest:
        _fail("corrupt", "plan-digest-mismatch", "Migration plan digest is invalid")
    request = value.get("request")
    source = request.get("source") if isinstance(request, dict) else None
    durable = (
        isinstance(source, dict)
        and source.get("variant") == DURABLE_VARIANT
    )
    records = (
        value.get("source_objects")
        if value.get("operation") == "publish" and durable
        else None
    )
    source_manifest = value.get("source_manifest")
    if value.get("operation") == "publish" and not isinstance(source_manifest, dict):
        _fail("corrupt", "invalid-source-manifest", "Planned source manifest must be an object")
    planned_migration = (
        source_manifest.get("migration_metadata")
        if value.get("operation") == "publish"
        else None
    )
    rebuilt = _assemble(
        pathlib.Path(root),
        request,
        supplied_records=records,
        supplied_migration=planned_migration,
    )
    if inspector.canonical_bytes(rebuilt) != inspector.canonical_bytes(value):
        _fail("corrupt", "plan-content-mismatch", "Migration plan does not match its request")
    return rebuilt


def _assert_precondition(root, plan_value):
    checkpoint = plan_value.get("precondition")
    if checkpoint is None:
        return
    source = plan_value["request"]["source"]
    snapshot = _translate(
        lambda: evidence.read_verified_v4_sources(
            root,
            checkpoint,
            source["selection"],
            include_migration=True,
        )
    )
    if _translate(
        lambda: inspector.canonical_bytes(snapshot["records"])
    ) != _translate(lambda: inspector.canonical_bytes(plan_value["source_objects"])):
        _fail(
            "stale",
            "selected-sources-stale",
            "Durable selected source objects differ from the plan",
        )
    if (
        snapshot["migration"]
        != plan_value["source_manifest"]["migration_metadata"]
    ):
        _fail(
            "stale",
            "migration-metadata-stale",
            "Durable migration metadata differs from the plan",
        )


def dry_run(root, plan_value):
    checked = _validate_plan(root, plan_value)
    _assert_precondition(pathlib.Path(root), checked)
    return {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "dry-run"},
        "plan_sha256": checked["plan_sha256"],
        "operation": checked["operation"],
        "binding": checked["binding"] if checked["operation"] == "no-op" else checked["expected"]["binding"],
    }


def _cas_fail(_status, code, message):
    if code in {"immutable-object-collision", "immutable-destination-changed"}:
        _fail("ambiguous", code, message)
    _fail("corrupt", code, message)


def _publish_object(store, reference, data):
    workflow_cas.publish_immutable(
        inspector.object_path(store, reference["sha256"]),
        data,
        _cas_fail,
        temporary_label="migration",
    )


def apply(root, plan_value):
    root = pathlib.Path(root)
    checked = _validate_plan(root, plan_value)
    if checked["operation"] == "no-op":
        return {
            "format": RESULT_FORMAT,
            "outcome": {"status": "resolved", "code": "already-canonical"},
            "plan_sha256": checked["plan_sha256"],
            "binding": checked["binding"],
            "objects": [],
        }
    _assert_precondition(root, checked)
    store = inspector.resolve_store(root)
    published = []
    for record in sorted(checked["source_objects"], key=lambda item: item["sha256"]):
        _logical_path, data, reference = _decode_record(record)
        _publish_object(store, reference, data)
        published.append(reference)
    source_data = inspector.canonical_bytes(checked["source_manifest"])
    source_ref = checked["expected"]["source_manifest"]
    _publish_object(store, source_ref, source_data)
    published.append(source_ref)
    result = _translate(lambda: evidence.publish(
        root,
        checked["publication"],
        before_binding=lambda: _assert_precondition(root, checked),
    ))
    if result["binding"] != checked["expected"]["binding"]:
        _fail("corrupt", "binding-result-mismatch", "Evidence publication returned another binding")
    published.extend(result["objects"])
    return {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "applied"},
        "plan_sha256": checked["plan_sha256"],
        "binding": result["binding"],
        "source_manifest": source_ref,
        "objects": sorted(
            {item["sha256"]: item for item in published}.values(),
            key=lambda item: (item["sha256"], item["kind"]),
        ),
    }


def verify(root, plan_value):
    checked = _validate_plan(root, plan_value)
    binding = checked["binding"] if checked["operation"] == "no-op" else checked["expected"]["binding"]
    verification = _translate(lambda: evidence.verify(root, binding))
    return {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "verified"},
        "plan_sha256": checked["plan_sha256"],
        "binding": binding,
        "verification": verification["outcome"],
    }


def _load_json(path, label):
    try:
        data = pathlib.Path(path).read_bytes()
    except OSError as error:
        _fail("missing", "%s-unreadable" % label, "Cannot read %s: %s" % (label, error))
    try:
        value = json.loads(
            data.decode("utf-8"),
            parse_constant=_reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, RecursionError) as error:
        _fail("corrupt", "invalid-%s-json" % label, "%s is invalid JSON: %s" % (label, error))
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-%s-type" % label, "%s must be a JSON object" % label)
    return value


class MigrationArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise MigrationFailure("unsupported", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = MigrationArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_parser = commands.add_parser("plan")
    plan_parser.add_argument("--root", default=".")
    plan_parser.add_argument("--request", required=True)
    for name in ("dry-run", "apply", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--root", default=".")
        command.add_argument("--plan", required=True)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        if args.command == "plan":
            document = plan(args.root, _load_json(args.request, "request"))
        else:
            plan_value = _load_json(args.plan, "plan")
            action = {"dry-run": dry_run, "apply": apply, "verify": verify}[args.command]
            document = action(args.root, plan_value)
        sys.stdout.buffer.write(inspector.canonical_document(document))
        return 0
    except MigrationFailure as failure:
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
    except (OSError, UnicodeError) as error:
        failure = MigrationFailure("missing", "filesystem-access-failed", str(error))
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]


if __name__ == "__main__":
    sys.exit(main())
