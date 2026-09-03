#!/usr/bin/env python3
"""Durable, guarded state machine for ChessEcho's issue agent workflow."""

import argparse
import base64
import copy
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
from urllib.parse import urlparse

if __package__:
    from .workflow_kernel import (
        COMMITTED_MODE,
        INTEGRITY_FORMAT,
        VERSION,
        WorkflowError,
        adoption_transaction_path,
        bootstrap_transaction_path,
        canonical_history_bytes,
        canonical_state_bytes,
        committed_envelope,
        decode_snapshot,
        encoded_snapshot,
        history_path,
        integrity_path,
        lock_directory,
        locked_run,
        parse_history,
        parse_json_object,
        pr_transition_transaction_path,
        run_dir,
        sha256,
        state_path,
        validate_committed_envelope,
        validate_run_structure,
        write_committed_snapshot,
        write_json_atomic,
        write_text_atomic,
    )
else:
    from workflow_kernel import (
        COMMITTED_MODE,
        INTEGRITY_FORMAT,
        VERSION,
        WorkflowError,
        adoption_transaction_path,
        bootstrap_transaction_path,
        canonical_history_bytes,
        canonical_state_bytes,
        committed_envelope,
        decode_snapshot,
        encoded_snapshot,
        history_path,
        integrity_path,
        lock_directory,
        locked_run,
        parse_history,
        parse_json_object,
        pr_transition_transaction_path,
        run_dir,
        sha256,
        state_path,
        validate_committed_envelope,
        validate_run_structure,
        write_committed_snapshot,
        write_json_atomic,
        write_text_atomic,
    )

SETTLED_LEGACY_MODE = "settled-legacy-adoption"
ADOPTION_TRANSACTION_FORMAT = "chess-echo-legacy-adoption-transaction-v1"
BOOTSTRAP_TRANSACTION_FORMAT = "chess-echo-bootstrap-transaction-v1"
PR_TRANSITION_TRANSACTION_FORMAT = "chess-echo-pr-transition-transaction-v1"
ACTIVE_ADOPTION_MODE = "active-legacy-adoption"
SETTLED_CONVERSION_MODE = "settled-legacy-conversion"
ROOT_BOOTSTRAP_MODE = "root-initialization"
CORRECTION_BOOTSTRAP_MODE = "correction-initialization"
PR_TRANSITION_MODE = "pr-gate-transition"
SETTLED_STATES = ("WAITING_FOR_PR_HUMAN_APPROVAL", "PR_APPROVED")
LEGACY_ADOPTION_CONFIRMATION = "legacy_run_trusted"
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
READY = "READY_FOR_HUMAN_APPROVAL"
REVISION = "NEEDS_REVISION"
REVIEW_STATUSES = (READY, REVISION)
ROLE_NAMES = {
    "planner": "chess-echo-planner",
    "reviewer": "chess-echo-reviewer",
    "implementer": "chess-echo-implementer",
}
APPROVAL_CONFIRMATIONS = {
    "plan": "plan_approved",
    "tests": "tests_approved",
    "pr": "I approve this draft PR.",
}
GENERATED_DIRECTORIES = {
    ".git", ".gradle", ".next", "__pycache__", "build", "coverage", "node_modules"
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
CORRECTION_SOURCE_STATES = ("WAITING_FOR_PR_HUMAN_APPROVAL", "PR_APPROVED")
CORRECTION_CLASSES = {
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
CORRECTION_CLASSIFICATIONS = tuple(CORRECTION_CLASSES)


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def correction_of(args):
    value = getattr(args, "correction", None)
    return None if value is None else int(value)


def validate_legacy_lifecycle(state, events):
    if state["state"] not in LEGACY_STATES:
        raise WorkflowError("Legacy workflow lifecycle state is unknown: %s" % state["state"])
    for event in events:
        if event["state"] not in LEGACY_STATES:
            raise WorkflowError(
                "Legacy workflow event state is unknown: %s" % event["state"]
            )
        if event["event"] not in LEGACY_EVENTS:
            raise WorkflowError(
                "Legacy workflow history event is unknown: %s" % event["event"]
            )


def validate_legacy_payload(state_data, history_data, issue, correction):
    state = parse_json_object(state_data, "Workflow state")
    events = parse_history(history_data)
    validate_run_structure(state, events, issue, correction, {1, 2, 3})
    if history_data != canonical_history_bytes(events):
        raise WorkflowError("Legacy history is not canonically serialized")
    validate_legacy_lifecycle(state, events)
    return state, events


def read_projection_bytes(root, issue, correction=None):
    state_file = state_path(root, issue, correction)
    if not state_file.is_file():
        if correction is not None:
            raise WorkflowError(
                "Issue %s has no correction %s; start it with start-correction"
                % (issue, correction)
            )
        raise WorkflowError("Issue %s has no workflow run; initialize it first" % issue)
    history_file = history_path(root, issue, correction)
    if not history_file.is_file():
        raise WorkflowError("Workflow history is missing for issue %s" % issue)
    return state_file.read_bytes(), history_file.read_bytes()


def normalize_legacy_state(root, state):
    state = copy.deepcopy(state)
    previous_version = state.get("version", 1)
    state["version"] = VERSION
    state.setdefault("target_base", load_config(root)["target_base"])
    state.setdefault("validated_head", None)
    state.setdefault("validated_base", None)
    state.setdefault("validated_test_fingerprint", None)
    state.setdefault("validation_evidence", None)
    if (
        previous_version == 2
        and state.get("validation_evidence") is None
        and state.get("validated_head")
        and state.get("validated_base")
        and state.get("validated_fingerprint")
        and state.get("validated_test_fingerprint")
    ):
        state["validation_evidence"] = {
            "head": state["validated_head"],
            "base": state["validated_base"],
            "base_ref": "origin/%s" % state["target_base"],
            "commit_count": 1,
            "workspace_fingerprint": state["validated_fingerprint"],
            "approved_test_fingerprint": state["validated_test_fingerprint"],
            "migrated_from_version": 2,
        }
    return state


def validate_transaction_identity(record, expected_format, expected_mode, issue, correction):
    if (
        record.get("format") != expected_format
        or record.get("mode") != expected_mode
    ):
        raise WorkflowError("Transaction format or mode is invalid")
    if (
        type(record.get("issue")) is not int
        or record.get("issue") != issue
        or record.get("correction") != correction
        or (correction is not None and type(record.get("correction")) is not int)
    ):
        raise WorkflowError("Transaction has the wrong run identity")


def bootstrap_record(mode, issue, correction, request, state_data, history_data, issue_data=None):
    record = {
        "format": BOOTSTRAP_TRANSACTION_FORMAT,
        "mode": mode,
        "issue": issue,
        "correction": correction,
        "request": copy.deepcopy(request),
    }
    record.update(encoded_snapshot(state_data, history_data))
    if issue_data is not None:
        record.update({
            "issue_sha256": sha256(issue_data),
            "issue_bytes": base64.b64encode(issue_data).decode(),
        })
    return record


def decode_bootstrap_record(record, mode, issue, correction, request):
    validate_transaction_identity(
        record, BOOTSTRAP_TRANSACTION_FORMAT, mode, issue, correction
    )
    if record.get("request") != request:
        raise WorkflowError("Creation command conflicts with the incomplete transaction")
    state, state_data, history_data = decode_snapshot(record, "", issue, correction)
    expected_event = (
        "WORKFLOW_INITIALIZED"
        if mode == ROOT_BOOTSTRAP_MODE
        else "CORRECTION_CREATED"
    )
    if state["history"][-1]["event"] != expected_event:
        raise WorkflowError("Creation transaction has the wrong initial event")
    issue_data = None
    if mode == ROOT_BOOTSTRAP_MODE:
        try:
            issue_data = base64.b64decode(record["issue_bytes"], validate=True)
        except (KeyError, TypeError, ValueError) as error:
            raise WorkflowError("Creation issue snapshot is invalid: %s" % error)
        if record.get("issue_sha256") != sha256(issue_data):
            raise WorkflowError("Creation issue snapshot hash is invalid")
    elif "issue_bytes" in record or "issue_sha256" in record:
        raise WorkflowError("Correction creation transaction has an issue snapshot")
    return state, state_data, history_data, issue_data


def finish_bootstrap_transaction(root, issue, correction, mode, request):
    transaction_file = bootstrap_transaction_path(root, issue, correction)
    if not transaction_file.is_file():
        raise WorkflowError("Creation transaction is missing")
    transaction = parse_json_object(
        transaction_file.read_bytes(), "Creation transaction"
    )
    state, state_data, history_data, issue_data = decode_bootstrap_record(
        transaction, mode, issue, correction, request
    )
    state_file = state_path(root, issue, correction)
    history_file = history_path(root, issue, correction)
    record_file = integrity_path(root, issue, correction)
    live_state = state_file.read_bytes() if state_file.is_file() else None
    live_history = history_file.read_bytes() if history_file.is_file() else None
    if live_state not in (None, state_data):
        raise WorkflowError("State projection conflicts with the creation transaction")
    if live_history not in (None, history_data):
        raise WorkflowError("History projection conflicts with the creation transaction")
    if correction is None:
        issue_file = run_dir(root, issue) / "issue.md"
        live_issue = issue_file.read_bytes() if issue_file.is_file() else None
        if live_issue not in (None, issue_data):
            raise WorkflowError("Issue projection conflicts with the creation transaction")
    if record_file.is_file():
        record = parse_json_object(record_file.read_bytes(), "Integrity record")
        _, committed_state, committed_history = validate_committed_envelope(
            record, issue, correction
        )
        if committed_state != state_data or committed_history != history_data:
            raise WorkflowError("Integrity record conflicts with the creation transaction")

    directory = run_dir(root, issue, correction)
    (directory / "artifacts").mkdir(parents=True, exist_ok=True)
    if correction is None:
        write_text_atomic(directory / "issue.md", issue_data.decode())
    write_text_atomic(state_file, state_data.decode())
    write_text_atomic(history_file, history_data.decode())
    write_json_atomic(
        record_file,
        committed_envelope(issue, correction, state, state_data, history_data),
    )
    transaction_file.unlink()
    return state


def validate_adoption_timestamp(value, label):
    if not isinstance(value, str) or not value:
        raise WorkflowError("%s is invalid" % label)
    try:
        parsed = dt.datetime.fromisoformat(value)
    except ValueError:
        raise WorkflowError("%s is invalid" % label)
    if parsed.tzinfo is None:
        raise WorkflowError("%s must include a timezone" % label)


def decode_legacy_record(record, issue, correction, expected_format, allowed_modes):
    if record.get("format") != expected_format:
        raise WorkflowError("Legacy adoption record format is invalid")
    if record.get("mode") not in allowed_modes:
        raise WorkflowError("Legacy adoption record mode is invalid")
    if (
        type(record.get("issue")) is not int
        or record.get("issue") != issue
        or record.get("correction") != correction
        or (
            correction is not None
            and type(record.get("correction")) is not int
        )
    ):
        raise WorkflowError("Legacy adoption record has the wrong run identity")
    if record.get("confirmation") != LEGACY_ADOPTION_CONFIRMATION:
        raise WorkflowError("Legacy adoption confirmation is invalid")
    for field in ("adopted_by", "reason"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            raise WorkflowError("Legacy adoption %s is invalid" % field)
    validate_adoption_timestamp(record.get("adopted_at"), "Legacy adoption timestamp")
    try:
        state_data = base64.b64decode(record["state_bytes"], validate=True)
        history_data = base64.b64decode(record["history_bytes"], validate=True)
    except (KeyError, TypeError, ValueError) as error:
        raise WorkflowError("Legacy adoption payload is invalid: %s" % error)
    if (
        record.get("state_sha256") != sha256(state_data)
        or record.get("history_sha256") != sha256(history_data)
    ):
        raise WorkflowError("Legacy adoption payload hashes are invalid")
    state, events = validate_legacy_payload(
        state_data, history_data, issue, correction
    )
    if (
        type(record.get("legacy_version")) is not int
        or record["legacy_version"] != state.get("version", 1)
    ):
        raise WorkflowError("Legacy adoption version does not match")
    return state, events, state_data, history_data


def validate_settled_legacy_envelope(
    envelope, issue, correction, state_data, history_data
):
    state, _, adopted_state, adopted_history = decode_legacy_record(
        envelope,
        issue,
        correction,
        INTEGRITY_FORMAT,
        {SETTLED_LEGACY_MODE},
    )
    if state["state"] not in SETTLED_STATES:
        raise WorkflowError("Settled legacy adoption refers to an active run")
    if adopted_state != state_data or adopted_history != history_data:
        raise WorkflowError("Settled legacy adoption payload does not match projections")
    return state


def verified_run(root, issue, correction=None):
    if bootstrap_transaction_path(root, issue, correction).exists():
        raise WorkflowError(
            "Creation is incomplete; rerun the exact initialization command"
        )
    if pr_transition_transaction_path(root, issue, correction).exists():
        raise WorkflowError(
            "PR-gate transition is incomplete; use recover-run"
        )
    if adoption_transaction_path(root, issue, correction).exists():
        raise WorkflowError(
            "Legacy adoption is incomplete; rerun the exact controlled command"
        )
    state_data, history_data = read_projection_bytes(root, issue, correction)
    integrity_file = integrity_path(root, issue, correction)
    if not integrity_file.is_file():
        raw_state = parse_json_object(state_data, "Workflow state")
        if raw_state.get("version", 1) == VERSION:
            raise WorkflowError(
                "V4 run has no integrity envelope and cannot be read or recovered"
            )
        raise WorkflowError(
            "Run has no integrity record; use adopt-legacy-run after explicit review"
        )
    envelope = parse_json_object(integrity_file.read_bytes(), "Integrity record")
    mode = envelope.get("mode")
    if mode == COMMITTED_MODE:
        state, expected_state, expected_history = validate_committed_envelope(
            envelope, issue, correction
        )
        if state_data != expected_state or history_data != expected_history:
            raise WorkflowError(
                "Workflow projections do not match committed integrity; use recover-run"
            )
        live_state = parse_json_object(state_data, "Workflow state")
        live_history = parse_history(history_data)
        validate_run_structure(live_state, live_history, issue, correction, {VERSION})
        return state, state_data, history_data
    if mode == SETTLED_LEGACY_MODE:
        state = validate_settled_legacy_envelope(
            envelope, issue, correction, state_data, history_data
        )
        return normalize_legacy_state(root, state), state_data, history_data
    raise WorkflowError("Integrity record mode is invalid")


def load_state(root, issue, correction=None):
    return verified_run(root, issue, correction)[0]


def read_run_state(root, issue, correction=None):
    state, state_data, _ = verified_run(root, issue, correction)
    return state, sha256(state_data)


def run_history_bytes(root, issue, correction=None):
    return verified_run(root, issue, correction)[2]


def correction_numbers(root, issue):
    directory = run_dir(root, issue) / "corrections"
    if not directory.is_dir():
        return []
    numbers = []
    for entry in directory.iterdir():
        if not entry.is_dir() or not entry.name.isdigit():
            continue
        if str(int(entry.name)) != entry.name:
            continue
        if (entry / "bootstrap-transaction.json").exists():
            continue
        if not (entry / "state.json").is_file():
            continue
        numbers.append(int(entry.name))
    return sorted(numbers)


def load_correction_summaries(root, issue):
    summaries = []
    for number in correction_numbers(root, issue):
        state, _ = read_run_state(root, issue, number)
        correction = state.get("correction") or {}
        summaries.append({
            "number": number,
            "classification": correction.get("classification"),
            "state": state["state"],
            "requested_by": correction.get("requested_by"),
            "created_at": correction.get("created_at"),
        })
    return summaries


def append_event(state, event, actor, details=None):
    entry = {
        "sequence": len(state["history"]) + 1,
        "timestamp": now(),
        "event": event,
        "actor": actor,
        "state": state["state"],
        "details": details or {},
    }
    state["history"].append(entry)
    state["updated_at"] = entry["timestamp"]


def sync_history(root, issue, state, correction=None):
    return canonical_history_bytes(state.get("history", [])).decode()


def persist(root, issue, state, event, actor, details=None, correction=None):
    append_event(state, event, actor, details)
    state_data = canonical_state_bytes(state)
    history_data = canonical_history_bytes(state["history"])
    write_committed_snapshot(
        root, issue, correction, state, state_data, history_data
    )


def prefixed_snapshot(prefix, state_data, history_data):
    return {
        "%s%s" % (prefix, key): value
        for key, value in encoded_snapshot(state_data, history_data).items()
    }


def persist_pr_transition(
    root, issue, state, event, actor, details=None, correction=None
):
    record_file = integrity_path(root, issue, correction)
    record = parse_json_object(record_file.read_bytes(), "Integrity record")
    source, source_state_data, source_history_data = validate_committed_envelope(
        record, issue, correction
    )
    if source["state"] != "WAITING_FOR_PR_HUMAN_APPROVAL":
        raise WorkflowError("PR transition source is not at the human approval gate")
    live_state, live_history = read_projection_bytes(root, issue, correction)
    if live_state != source_state_data or live_history != source_history_data:
        raise WorkflowError(
            "Workflow projections do not match committed integrity; use recover-run"
        )

    append_event(state, event, actor, details)
    target_state_data = canonical_state_bytes(state)
    target_history_data = canonical_history_bytes(state["history"])
    transaction = {
        "format": PR_TRANSITION_TRANSACTION_FORMAT,
        "mode": PR_TRANSITION_MODE,
        "issue": issue,
        "correction": correction,
        "event": event,
    }
    transaction.update(
        prefixed_snapshot("source_", source_state_data, source_history_data)
    )
    transaction.update(
        prefixed_snapshot("target_", target_state_data, target_history_data)
    )
    transaction_file = pr_transition_transaction_path(root, issue, correction)
    write_json_atomic(transaction_file, transaction)
    write_committed_snapshot(
        root, issue, correction, state, target_state_data, target_history_data
    )
    transaction_file.unlink()


def adoption_record(
    mode, issue, correction, legacy_version, state_data, history_data,
    adopted_at, adopted_by, reason,
):
    return {
        "format": ADOPTION_TRANSACTION_FORMAT,
        "mode": mode,
        "issue": issue,
        "correction": correction,
        "legacy_version": legacy_version,
        "state_sha256": sha256(state_data),
        "history_sha256": sha256(history_data),
        "state_bytes": base64.b64encode(state_data).decode(),
        "history_bytes": base64.b64encode(history_data).decode(),
        "adopted_at": adopted_at,
        "adopted_by": adopted_by,
        "confirmation": LEGACY_ADOPTION_CONFIRMATION,
        "reason": reason,
    }


def adopted_snapshot(root, transaction, legacy_state):
    state = normalize_legacy_state(root, legacy_state)
    entry = {
        "sequence": len(state["history"]) + 1,
        "timestamp": transaction["adopted_at"],
        "event": "LEGACY_RUN_ADOPTED",
        "actor": transaction["adopted_by"],
        "state": state["state"],
        "details": {
            "legacy_version": transaction["legacy_version"],
            "state_sha256": transaction["state_sha256"],
            "history_sha256": transaction["history_sha256"],
            "reason": transaction["reason"],
        },
    }
    state["history"].append(entry)
    state["updated_at"] = entry["timestamp"]
    return state


def committed_record_matches(record, issue, correction, state_data, history_data):
    state, committed_state, committed_history = validate_committed_envelope(
        record, issue, correction
    )
    return (
        committed_state == state_data
        and committed_history == history_data
        and state["history"][-1]["event"] == "LEGACY_RUN_ADOPTED"
    )


def finish_adoption_transaction(root, issue, correction, expected_mode):
    transaction_file = adoption_transaction_path(root, issue, correction)
    if not transaction_file.is_file():
        raise WorkflowError("Legacy adoption transaction is missing")
    transaction = parse_json_object(
        transaction_file.read_bytes(), "Legacy adoption transaction"
    )
    legacy_state, _, legacy_state_data, legacy_history_data = decode_legacy_record(
        transaction,
        issue,
        correction,
        ADOPTION_TRANSACTION_FORMAT,
        {expected_mode},
    )
    if (
        expected_mode == ACTIVE_ADOPTION_MODE
        and legacy_state["state"] in SETTLED_STATES
    ):
        raise WorkflowError("Active adoption transaction refers to a settled run")
    if (
        expected_mode == SETTLED_CONVERSION_MODE
        and legacy_state["state"] != "WAITING_FOR_PR_HUMAN_APPROVAL"
    ):
        raise WorkflowError("Settled conversion transaction has an invalid lifecycle")
    adopted = adopted_snapshot(root, transaction, legacy_state)
    adopted_state_data = canonical_state_bytes(adopted)
    adopted_history_data = canonical_history_bytes(adopted["history"])
    state_file = state_path(root, issue, correction)
    history_file = history_path(root, issue, correction)
    live_state = state_file.read_bytes() if state_file.is_file() else None
    live_history = history_file.read_bytes() if history_file.is_file() else None
    if live_state not in (legacy_state_data, adopted_state_data):
        raise WorkflowError("State projection conflicts with the adoption transaction")
    if live_history not in (legacy_history_data, adopted_history_data):
        raise WorkflowError("History projection conflicts with the adoption transaction")

    record_file = integrity_path(root, issue, correction)
    if record_file.is_file():
        record = parse_json_object(record_file.read_bytes(), "Integrity record")
        if record.get("mode") == SETTLED_LEGACY_MODE:
            settled = validate_settled_legacy_envelope(
                record, issue, correction, legacy_state_data, legacy_history_data
            )
            if (
                expected_mode != SETTLED_CONVERSION_MODE
                or record["adopted_by"] != transaction["adopted_by"]
                or record["reason"] != transaction["reason"]
                or record["confirmation"] != transaction["confirmation"]
                or record["adopted_at"] != transaction["adopted_at"]
                or settled["state"] != "WAITING_FOR_PR_HUMAN_APPROVAL"
            ):
                raise WorkflowError(
                    "Integrity record conflicts with the adoption transaction"
                )
        elif not committed_record_matches(
            record, issue, correction, adopted_state_data, adopted_history_data
        ):
            raise WorkflowError("Integrity record conflicts with the adoption transaction")
    elif expected_mode == SETTLED_CONVERSION_MODE:
        raise WorkflowError("Settled conversion integrity sidecar is missing")

    write_text_atomic(state_file, adopted_state_data.decode())
    write_text_atomic(history_file, adopted_history_data.decode())
    write_json_atomic(
        record_file,
        committed_envelope(
            issue, correction, adopted, adopted_state_data, adopted_history_data
        ),
    )
    transaction_file.unlink()
    return adopted


def correction_depends_on_source_bytes(
    root, issue, source_correction, source_state_sha256, source_history_sha256
):
    for number in correction_numbers(root, issue):
        if number == source_correction:
            continue
        child, _, _ = verified_run(root, issue, number)
        parent = child.get("parent_run") or {}
        if (
            parent.get("issue") == issue
            and parent.get("correction") == source_correction
            and parent.get("state_sha256") == source_state_sha256
            and parent.get("history_sha256") == source_history_sha256
        ):
            return number
    pending = pending_correction_bootstrap(root, issue)
    if pending is not None:
        transaction_file = bootstrap_transaction_path(root, issue, pending)
        transaction = parse_json_object(
            transaction_file.read_bytes(), "Correction creation transaction"
        )
        request = transaction.get("request")
        if not isinstance(request, dict):
            raise WorkflowError("Correction creation request identity is invalid")
        child, _, _, _ = decode_bootstrap_record(
            transaction,
            CORRECTION_BOOTSTRAP_MODE,
            issue,
            pending,
            request,
        )
        parent = child.get("parent_run") or {}
        if (
            parent.get("issue") == issue
            and parent.get("correction") == source_correction
            and parent.get("state_sha256") == source_state_sha256
            and parent.get("history_sha256") == source_history_sha256
        ):
            return pending
    return None


def prepare_settled_transition(root, issue, correction):
    transaction_file = adoption_transaction_path(root, issue, correction)
    if transaction_file.exists():
        finish_adoption_transaction(
            root, issue, correction, SETTLED_CONVERSION_MODE
        )
        return
    record_file = integrity_path(root, issue, correction)
    if not record_file.is_file():
        return
    record = parse_json_object(record_file.read_bytes(), "Integrity record")
    if record.get("mode") == COMMITTED_MODE:
        state, state_data, history_data = validate_committed_envelope(
            record, issue, correction
        )
        if state["state"] != "WAITING_FOR_PR_HUMAN_APPROVAL":
            return
        dependent = correction_depends_on_source_bytes(
            root, issue, correction, sha256(state_data), sha256(history_data)
        )
        if dependent is not None:
            raise WorkflowError(
                "Correction %s depends on the exact committed source bytes"
                % dependent
            )
        return
    if record.get("mode") != SETTLED_LEGACY_MODE:
        return
    state_data, history_data = read_projection_bytes(root, issue, correction)
    state = validate_settled_legacy_envelope(
        record, issue, correction, state_data, history_data
    )
    if state["state"] == "PR_APPROVED":
        return
    dependent = correction_depends_on_source_bytes(
        root, issue, correction, sha256(state_data), sha256(history_data)
    )
    if dependent is not None:
        raise WorkflowError(
            "Correction %s depends on the exact settled legacy source bytes"
            % dependent
        )
    transaction = adoption_record(
        SETTLED_CONVERSION_MODE,
        issue,
        correction,
        state.get("version", 1),
        state_data,
        history_data,
        record["adopted_at"],
        record["adopted_by"],
        record["reason"],
    )
    write_json_atomic(transaction_file, transaction)
    finish_adoption_transaction(root, issue, correction, SETTLED_CONVERSION_MODE)


def require_state(state, *allowed):
    if state["state"] not in allowed:
        raise WorkflowError(
            "Action is invalid in %s; expected %s"
            % (state["state"], " or ".join(allowed))
        )


def artifact_record(root, issue, artifact, producer, correction=None):
    path = pathlib.Path(artifact)
    if not path.is_absolute():
        path = root / path
    path = path.resolve()
    artifact_root = (run_dir(root, issue, correction) / "artifacts").resolve()
    try:
        relative = path.relative_to(artifact_root)
    except ValueError:
        raise WorkflowError("Artifact must be inside %s" % artifact_root)
    if not path.is_file():
        raise WorkflowError("Artifact does not exist: %s" % path)
    return {
        "path": str(path.relative_to(root.resolve())),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "producer": producer,
        "recorded_at": now(),
        "name": str(relative),
    }


def artifact_is_unchanged(root, record):
    path = root / record["path"]
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"]


def clear_after_revision(state):
    state["validation"] = {}
    state["validated_fingerprint"] = None
    state["validated_head"] = None
    state["validated_base"] = None
    state["validated_test_fingerprint"] = None
    state["validation_evidence"] = None
    state["final_review"] = None
    state["draft_pr"] = None


def matching_paths(root, patterns):
    paths = []
    for pattern in patterns:
        paths.extend(
            path for path in root.glob(pattern)
            if path.is_file() or path.is_symlink()
        )
    return set(paths)


def files_fingerprint(root, patterns=None, excluded_paths=None):
    hasher = hashlib.sha256()
    excluded_paths = excluded_paths or set()
    if patterns is None:
        listed = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=str(root), text=True, capture_output=True,
        )
        if listed.returncode == 0:
            paths = [
                root / item
                for item in listed.stdout.split("\0")
                if item
            ]
        else:
            paths = [
                path for path in root.rglob("*")
                if (path.is_file() or path.is_symlink())
                and not GENERATED_DIRECTORIES.intersection(path.parts)
                and path.name != ".DS_Store"
                and path.suffix != ".pyc"
            ]
        paths = [
            path for path in paths
            if (path.is_file() or path.is_symlink())
            and path.relative_to(root).parts[:2] != (".agent-workflow", "runs")
        ]
    else:
        paths = matching_paths(root, patterns)
    for path in sorted(set(paths) - excluded_paths):
        relative = path.relative_to(root)
        file_stat = path.lstat()
        hasher.update(str(relative).encode())
        hasher.update(b"\0")
        hasher.update(str(stat.S_IFMT(file_stat.st_mode)).encode())
        hasher.update(b":")
        hasher.update(str(stat.S_IMODE(file_stat.st_mode)).encode())
        hasher.update(b"\0")
        if path.is_symlink():
            hasher.update(os.readlink(path).encode())
        else:
            hasher.update(path.read_bytes())
        hasher.update(b"\0")
    return hasher.hexdigest()


def non_test_fingerprint(root, test_patterns):
    return files_fingerprint(
        root, excluded_paths=matching_paths(root, test_patterns)
    )


def require_test_only_phase_unchanged(root, state):
    approved = state["approvals"]["plan"].get("non_test_fingerprint")
    current = non_test_fingerprint(root, state["test_paths"])
    if current != approved:
        raise WorkflowError(
            "Non-test files changed during test implementation; reopen the plan or revert them"
        )


def load_config(root):
    path = root / ".github" / "agent-workflow.json"
    with path.open() as handle:
        return json.load(handle)


def resolve_inside_root(root, value, label):
    if not isinstance(value, str) or not value:
        raise WorkflowError("%s must be a non-empty string" % label)
    path = (root / value).resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError:
        raise WorkflowError("%s must resolve inside the repository" % label)
    return path


def validate_check(root, check):
    if not isinstance(check, dict):
        raise WorkflowError("Validation checks must be objects")
    name = check.get("name")
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
        raise WorkflowError("Validation check names must be safe filename slugs")
    command = check.get("command")
    if (
        not isinstance(command, list)
        or not command
        or any(not isinstance(part, str) or not part for part in command)
    ):
        raise WorkflowError("Validation check %s must have a non-empty string command list" % name)
    cwd_value = check.get("cwd", ".")
    cwd = resolve_inside_root(root, cwd_value, "Validation check cwd")
    if not cwd.is_dir():
        raise WorkflowError("Validation check cwd does not exist: %s" % cwd_value)
    return {
        "name": name,
        "command": list(command),
        "cwd": cwd_value,
    }


def fetch_issue(issue, repo, runner):
    command = [
        "gh", "issue", "view", str(issue), "--repo", repo,
        "--json", "number,title,url,body,labels,milestone",
    ]
    result = runner(command, text=True, capture_output=True)
    if result.returncode != 0:
        raise WorkflowError("Could not read issue: %s" % result.stderr.strip())
    return json.loads(result.stdout)


def infer_scope(issue_data):
    labels = {label["name"] for label in issue_data.get("labels", [])}
    if "full-stack" in labels or {"backend", "frontend"} <= labels:
        return "full-stack"
    if "frontend" in labels:
        return "frontend"
    if "backend" in labels:
        return "backend"
    return None


def init_bootstrap_request(args):
    return {
        "command": "init",
        "issue": args.issue,
        "repo": args.repo,
        "scope": args.scope,
        "issue_file": args.issue_file,
        "title": args.title,
        "url": args.url,
        "actor": args.actor,
    }


def command_init(args, root, runner):
    directory = run_dir(root, args.issue)
    path = state_path(root, args.issue)
    request = init_bootstrap_request(args)
    with locked_run(root, args.issue):
        transaction_file = bootstrap_transaction_path(root, args.issue)
        if transaction_file.exists():
            finish_bootstrap_transaction(
                root, args.issue, None, ROOT_BOOTSTRAP_MODE, request
            )
            return
        if (
            path.exists()
            or history_path(root, args.issue).exists()
            or integrity_path(root, args.issue).exists()
            or (directory / "issue.md").exists()
        ):
            raise WorkflowError("Workflow run already exists for issue %s" % args.issue)
        if args.issue_file:
            issue_source = pathlib.Path(args.issue_file)
            issue_data = {
                "number": args.issue,
                "title": args.title or "Issue %s" % args.issue,
                "url": args.url,
                "body": issue_source.read_text(),
                "labels": [],
                "milestone": None,
            }
        else:
            issue_data = fetch_issue(args.issue, args.repo, runner)
        inferred_scope = infer_scope(issue_data)
        if args.scope and inferred_scope and args.scope != inferred_scope:
            raise WorkflowError(
                "Explicit scope %s conflicts with issue-label scope %s"
                % (args.scope, inferred_scope)
            )
        scope = inferred_scope or args.scope
        if not scope:
            raise WorkflowError("Cannot infer validation scope; pass --scope")
        config = load_config(root)
        target_base = config.get("target_base")
        if not target_base:
            raise WorkflowError("Workflow configuration must define target_base")
        profiles = config.get("validation_profiles", {})
        if scope not in profiles:
            raise WorkflowError("Unknown validation scope: %s" % scope)
        profile = profiles[scope]
        checks = profile.get("checks", [])
        test_paths = profile.get("test_paths", [])
        if not checks:
            raise WorkflowError("Validation profile %s has no checks" % scope)
        if not test_paths:
            raise WorkflowError("Validation profile %s has no test paths" % scope)
        checks = [validate_check(root, check) for check in checks]
        names = [check["name"] for check in checks]
        if len(names) != len(set(names)):
            raise WorkflowError("Validation check names must be unique")

        issue_text = "# %s\n\nSource: %s\n\n%s\n" % (
            issue_data["title"],
            issue_data.get("url") or "local issue snapshot",
            issue_data.get("body") or "",
        )
        timestamp = now()
        state = {
            "version": VERSION,
            "issue": {
                "number": args.issue,
                "repo": args.repo,
                "title": issue_data["title"],
                "url": issue_data.get("url"),
            },
            "scope": scope,
            "target_base": target_base,
            "state": "PLANNING",
            "required_checks": checks,
            "test_paths": test_paths,
            "artifacts": {},
            "approvals": {},
            "validation": {},
            "validated_fingerprint": None,
            "validated_head": None,
            "validated_base": None,
            "validated_test_fingerprint": None,
            "validation_evidence": None,
            "final_review": None,
            "draft_pr": None,
            "history": [],
            "created_at": timestamp,
            "updated_at": timestamp,
        }
        append_event(state, "WORKFLOW_INITIALIZED", args.actor, {"scope": scope})
        state_data = canonical_state_bytes(state)
        history_data = canonical_history_bytes(state["history"])
        write_json_atomic(
            transaction_file,
            bootstrap_record(
                ROOT_BOOTSTRAP_MODE,
                args.issue,
                None,
                request,
                state_data,
                history_data,
                issue_text.encode(),
            ),
        )
        finish_bootstrap_transaction(
            root, args.issue, None, ROOT_BOOTSTRAP_MODE, request
        )


def submit_artifact(args, root, kind, expected, target, event):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, expected)
        verify_approved_artifacts(
            root, state, include_implementation=kind != "implementation_report"
        )
        expected_role = "planner" if kind == "plan" else "implementer"
        if args.agent != ROLE_NAMES[expected_role]:
            raise WorkflowError("%s must submit %s" % (ROLE_NAMES[expected_role], kind))
        if kind == "implementation_report":
            current_tests = files_fingerprint(root, state["test_paths"])
            approved_tests = state["approvals"]["tests"].get("test_fingerprint")
            if current_tests != approved_tests:
                raise WorkflowError(
                    "Approved tests changed; explicitly reopen test implementation"
                )
        elif kind == "test_report":
            require_test_only_phase_unchanged(root, state)
        record = artifact_record(root, args.issue, args.artifact, args.agent, correction)
        state["artifacts"][kind] = record
        state["state"] = target
        persist(
            root, args.issue, state, event, args.agent, {"artifact": record}, correction
        )


def review(args, root, runner, kind, expected, revision_target, waiting_target, event):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, expected)
        verify_approved_artifacts(
            root,
            state,
            include_implementation=not (
                kind == "final_review" and args.status == REVISION
            ),
        )
        if args.reviewer != ROLE_NAMES["reviewer"]:
            raise WorkflowError("%s must perform reviews" % ROLE_NAMES["reviewer"])
        if kind == "final_review":
            current_fingerprint = files_fingerprint(root)
            current_head = git_head(root, runner)
            if args.status == READY:
                if current_fingerprint != state.get("validated_fingerprint"):
                    raise WorkflowError("Workspace changed after validation; record NEEDS_REVISION")
                if current_head != state.get("validated_head"):
                    raise WorkflowError("Git revision changed after validation; record NEEDS_REVISION")
        record = artifact_record(root, args.issue, args.artifact, args.reviewer, correction)
        record["status"] = args.status
        if kind == "plan_review":
            record["subject_sha256"] = state["artifacts"]["plan"]["sha256"]
        elif kind == "test_review":
            require_test_only_phase_unchanged(root, state)
            record["subject_sha256"] = state["artifacts"]["test_report"]["sha256"]
            record["subject_test_fingerprint"] = files_fingerprint(
                root, state["test_paths"]
            )
        elif kind == "final_review":
            record["subject_head"] = current_head
            record["subject_workspace_fingerprint"] = current_fingerprint
        state["artifacts"][kind] = record
        if args.status == REVISION:
            state["state"] = revision_target
            if kind == "final_review":
                clear_after_revision(state)
        else:
            state["state"] = waiting_target
            if kind == "final_review":
                state["final_review"] = {
                    "status": READY,
                    "reviewer": args.reviewer,
                    "recorded_at": now(),
                    "artifact": record["path"],
                    "sha256": record["sha256"],
                    "workspace_fingerprint": current_fingerprint,
                    "head": current_head,
                }
        persist(
            root, args.issue, state, event, args.reviewer,
            {"status": args.status, "artifact": record},
            correction,
        )


def approval(args, root, expected, target, key, event):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, expected)
        verify_approved_artifacts(root, state)
        required_confirmation = APPROVAL_CONFIRMATIONS[key]
        if args.confirm != required_confirmation:
            raise WorkflowError(
                "Explicit confirmation must be exactly: %s" % required_confirmation
            )
        if key == "plan":
            subject = state["artifacts"]["plan"]
            review_record = state["artifacts"]["plan_review"]
            if not artifact_is_unchanged(root, subject):
                raise WorkflowError("Plan changed after submission; review it again")
            if not artifact_is_unchanged(root, review_record):
                raise WorkflowError("Plan review changed after submission")
            if review_record.get("subject_sha256") != subject["sha256"]:
                raise WorkflowError("Plan review does not match the submitted plan")
        elif key == "tests":
            require_test_only_phase_unchanged(root, state)
            subject = state["artifacts"]["test_report"]
            review_record = state["artifacts"]["test_review"]
            current_tests = files_fingerprint(root, state["test_paths"])
            if not artifact_is_unchanged(root, subject):
                raise WorkflowError("Test report changed after submission; review it again")
            if not artifact_is_unchanged(root, review_record):
                raise WorkflowError("Test review changed after submission")
            if review_record.get("subject_sha256") != subject["sha256"]:
                raise WorkflowError("Test review does not match the submitted report")
            if review_record.get("subject_test_fingerprint") != current_tests:
                raise WorkflowError("Tests changed after review; review them again")
        approval_record = {
            "approved": True,
            "by": args.by,
            "at": now(),
            "confirmation": args.confirm,
        }
        if key == "tests":
            approval_record["test_fingerprint"] = current_tests
        elif key == "plan":
            approval_record["non_test_fingerprint"] = non_test_fingerprint(
                root, state["test_paths"]
            )
        state["approvals"][key] = approval_record
        state["state"] = target
        persist(root, args.issue, state, event, args.by, None, correction)


def rejection(args, root, expected, target, key, event):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        if key == "pr":
            prepare_settled_transition(root, args.issue, correction)
        state = load_state(root, args.issue, correction)
        require_state(state, expected)
        state["approvals"][key] = {
            "approved": False, "by": args.by, "at": now(), "reason": args.reason,
        }
        state["state"] = target
        if key == "pr":
            clear_after_revision(state)
        writer = persist_pr_transition if key == "pr" else persist
        writer(
            root, args.issue, state, event, args.by, {"reason": args.reason}, correction
        )


def command_reopen_tests(args, root):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, "IMPLEMENTATION", "VALIDATION", "FINAL_REVIEW")
        state["approvals"]["tests"] = {
            "approved": False,
            "by": args.by,
            "at": now(),
            "reason": args.reason,
            "reopened": True,
        }
        clear_after_revision(state)
        state["artifacts"].pop("implementation_report", None)
        state["approvals"]["plan"]["non_test_fingerprint"] = non_test_fingerprint(
            root, state["test_paths"]
        )
        state["state"] = "TEST_IMPLEMENTATION"
        persist(
            root, args.issue, state, "TESTS_HUMAN_REOPENED", args.by,
            {"reason": args.reason}, correction,
        )


def command_reopen_plan(args, root):
    allowed = (
        "TEST_IMPLEMENTATION",
        "TEST_REVIEW",
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "IMPLEMENTATION",
        "VALIDATION",
        "FINAL_REVIEW",
    )
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, *allowed)
        state["approvals"]["plan"] = {
            "approved": False,
            "by": args.by,
            "at": now(),
            "reason": args.reason,
            "reopened": True,
        }
        if "tests" in state["approvals"]:
            state["approvals"]["tests"] = {
                "approved": False,
                "by": args.by,
                "at": now(),
                "reason": "Plan reopened: %s" % args.reason,
                "reopened": True,
            }
        clear_after_revision(state)
        state["artifacts"].pop("implementation_report", None)
        state["state"] = "PLANNING"
        persist(
            root, args.issue, state, "PLAN_HUMAN_REOPENED", args.by,
            {"reason": args.reason}, correction,
        )


def validation_passes(state):
    required = {check["name"] for check in state["required_checks"]}
    return required and all(
        state["validation"].get(name, {}).get("status") == "PASS"
        for name in required
    )


def verify_approved_artifacts(root, state, include_implementation=True):
    approved_plan = state["approvals"].get("plan", {}).get("approved")
    if approved_plan:
        for key in ("plan", "plan_review"):
            if not artifact_is_unchanged(root, state["artifacts"][key]):
                raise WorkflowError("Approved %s artifact changed; reopen the plan" % key)
    approved_tests = state["approvals"].get("tests", {}).get("approved")
    if approved_tests:
        for key in ("test_report", "test_review"):
            if not artifact_is_unchanged(root, state["artifacts"][key]):
                raise WorkflowError("Approved %s artifact changed; reopen tests" % key)
    if (state.get("final_review") or {}).get("status") == READY:
        if not artifact_is_unchanged(root, state["artifacts"]["final_review"]):
            raise WorkflowError("Final review artifact changed; review again")
    if include_implementation and "implementation_report" in state["artifacts"]:
        if not artifact_is_unchanged(root, state["artifacts"]["implementation_report"]):
            raise WorkflowError("Implementation report changed; resubmit implementation")


def git_head(root, runner):
    result = runner(
        ["git", "rev-parse", "HEAD"], cwd=str(root), text=True, capture_output=True
    )
    if result.returncode != 0:
        raise WorkflowError("Cannot determine the current Git revision")
    return result.stdout.strip()


def git_output(root, runner, command, error):
    result = runner(command, cwd=str(root), text=True, capture_output=True)
    if result.returncode != 0:
        raise WorkflowError(error)
    return result.stdout.strip()


def verify_git_against_base(root, runner, base, base_ref):
    head = git_head(root, runner)
    ancestor = runner(
        ["git", "merge-base", "--is-ancestor", base, "HEAD"],
        cwd=str(root), text=True, capture_output=True,
    )
    if ancestor.returncode != 0:
        raise WorkflowError("Current HEAD must descend from validated base %s" % base)
    count_text = git_output(
        root,
        runner,
        ["git", "rev-list", "--count", "%s..HEAD" % base],
        "Cannot count commits relative to validated base %s" % base,
    )
    try:
        count = int(count_text)
    except ValueError:
        raise WorkflowError("Git returned an invalid commit count")
    if count != 1:
        raise WorkflowError(
            "Final validation requires exactly one issue commit relative to %s" % base
        )
    return {"head": head, "base": base, "base_ref": base_ref, "commit_count": count}


def resolve_validation_git(root, state, runner):
    anchor = (state.get("parent_run") or {}).get("validated_head")
    if anchor:
        base_ref = "parent-run-head:%s" % anchor
        base = git_output(
            root,
            runner,
            ["git", "rev-parse", "--verify", "%s^{commit}" % anchor],
            "Cannot resolve the source run's validated head %s; restore it before validation"
            % anchor,
        )
    else:
        base_ref = "origin/%s" % state["target_base"]
        base = git_output(
            root,
            runner,
            ["git", "rev-parse", "--verify", base_ref],
            "Cannot resolve local tracking ref %s; fetch it before final validation" % base_ref,
        )
    return verify_git_against_base(root, runner, base, base_ref)


def verify_correction_ancestry(root, runner, anchor):
    ancestor = runner(
        ["git", "merge-base", "--is-ancestor", anchor, "HEAD"],
        cwd=str(root), text=True, capture_output=True,
    )
    if ancestor.returncode != 0:
        raise WorkflowError(
            "Current HEAD must descend from the source run's validated head %s" % anchor
        )
    count_text = git_output(
        root,
        runner,
        ["git", "rev-list", "--count", "%s..HEAD" % anchor],
        "Cannot count commits relative to the source run's validated head %s" % anchor,
    )
    try:
        count = int(count_text)
    except ValueError:
        raise WorkflowError("Git returned an invalid commit count")
    if count not in (0, 1):
        raise WorkflowError(
            "A correction may hold at most one commit relative to the source run's validated head %s"
            % anchor
        )
    return count


def capture_validation_snapshot(root, state, runner, expected_base=None):
    require_clean_worktree(root, runner)
    git_state = resolve_validation_git(root, state, runner)
    if expected_base is not None and git_state["base"] != expected_base:
        raise WorkflowError("Target-base tracking ref changed during validation")
    return {
        **git_state,
        "workspace_fingerprint": files_fingerprint(root),
        "approved_test_fingerprint": files_fingerprint(root, state["test_paths"]),
    }


def require_clean_worktree(root, runner):
    index = runner(
        ["git", "ls-files", "-v"], cwd=str(root), text=True, capture_output=True
    )
    if index.returncode != 0:
        raise WorkflowError("Cannot inspect Git index flags")
    hidden = [
        line for line in index.stdout.splitlines()
        if line and (line[0].islower() or line[0] == "S")
    ]
    if hidden:
        raise WorkflowError(
            "Clear assume-unchanged and skip-worktree flags before final validation"
        )
    result = runner(
        ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"],
        cwd=str(root), text=True, capture_output=True,
    )
    if result.returncode != 0:
        raise WorkflowError("Cannot inspect the Git worktree")
    relevant = []
    records = result.stdout.split("\0")
    index = 0
    while index < len(records):
        entry = records[index]
        index += 1
        if not entry:
            continue
        status_code = entry[:2]
        paths = [entry[3:]]
        if "R" in status_code or "C" in status_code:
            if index < len(records) and records[index]:
                paths.append(records[index])
                index += 1
        if any(not path.startswith(".agent-workflow/runs/") for path in paths):
            relevant.append(entry)
    if relevant:
        raise WorkflowError("Commit all non-run workflow changes before final validation")


PR_FIELDS = "url,isDraft,state,baseRefName,headRefOid,title,body"


def read_pr(root, runner, reference):
    command = ["gh", "pr", "view", reference, "--json", PR_FIELDS]
    result = runner(command, cwd=str(root), text=True, capture_output=True)
    if result.returncode != 0:
        raise WorkflowError("Could not read draft pull request: %s" % result.stderr.strip())
    try:
        pull_request = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise WorkflowError("GitHub returned invalid pull request metadata")
    if not isinstance(pull_request, dict):
        raise WorkflowError("GitHub returned unexpected pull request metadata")
    return pull_request


def current_branch(root, runner):
    return git_output(
        root,
        runner,
        ["git", "symbolic-ref", "--quiet", "--short", "HEAD"],
        "Cannot determine the current branch",
    )


def find_open_pr(root, state, runner):
    command = [
        "gh", "pr", "list",
        "--repo", state["issue"]["repo"],
        "--head", current_branch(root, runner),
        "--state", "open",
        "--limit", "2",
        "--json", PR_FIELDS,
    ]
    result = runner(command, cwd=str(root), text=True, capture_output=True)
    if result.returncode != 0:
        raise WorkflowError("Could not query draft pull requests: %s" % result.stderr.strip())
    try:
        matches = json.loads(result.stdout)
    except json.JSONDecodeError:
        raise WorkflowError("GitHub returned invalid pull request list metadata")
    if not isinstance(matches, list):
        raise WorkflowError("GitHub returned unexpected pull request list metadata")
    if len(matches) > 1:
        raise WorkflowError("Multiple open pull requests match the current branch")
    return matches[0] if matches else None


def verify_pr_repository(pull_request, repo):
    expected = repo.split("/")
    parts = [part for part in urlparse(pull_request.get("url", "")).path.split("/") if part]
    if (
        len(expected) != 2
        or len(parts) < 4
        or [part.casefold() for part in parts[:2]]
        != [part.casefold() for part in expected]
        or parts[2] != "pull"
        or not parts[3].isdigit()
    ):
        raise WorkflowError("Draft pull request belongs to an unexpected repository")


def pr_fingerprint(pull_request):
    relevant = {
        key: pull_request.get(key)
        for key in ("url", "isDraft", "state", "baseRefName", "headRefOid", "title", "body")
    }
    encoded = json.dumps(relevant, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(encoded).hexdigest()


def verify_pr(pull_request, base, head):
    if not pull_request:
        raise WorkflowError("The draft pull request no longer exists")
    if not pull_request.get("isDraft") or pull_request.get("state") != "OPEN":
        raise WorkflowError("Pull request must remain an open draft")
    if pull_request.get("baseRefName") != base:
        raise WorkflowError("Draft pull request targets an unexpected base branch")
    if pull_request.get("headRefOid") != head:
        raise WorkflowError("Draft pull request head does not match the reviewed Git revision")


def read_expected_pr_body(root, body_file):
    path = pathlib.Path(body_file)
    if not path.is_absolute():
        path = root / path
    if not path.is_file():
        raise WorkflowError("Pull request body file does not exist: %s" % path)
    body = path.read_text()
    validate_pr_body(body)
    return body


def visible_markdown_lines(body):
    visible_lines = []
    in_comment = False
    fence = None
    for raw_line in body.splitlines():
        fence_match = re.match(r"^ {0,3}(`{3,}|~{3,})", raw_line)
        if fence_match and not in_comment:
            marker = fence_match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        line = raw_line
        visible = ""
        while line:
            if in_comment:
                if "-->" not in line:
                    line = ""
                    continue
                line = line.split("-->", 1)[1]
                in_comment = False
            if "<!--" not in line:
                visible += line
                break
            before, line = line.split("<!--", 1)
            visible += before
            in_comment = True
        visible_lines.append((raw_line, visible))
    return visible_lines


def strip_blockquote_prefix(line):
    depth = 0
    while True:
        match = re.match(r"^ {0,3}> ?", line)
        if not match:
            return line, depth
        line = line[match.end():]
        depth += 1


def validate_pr_body(body):
    visible_lines = visible_markdown_lines(body)
    quoted_lines = [
        strip_blockquote_prefix(visible)
        for _, visible in visible_lines
    ]
    if any(
        depth and re.match(r"^ {0,3}##(?!#)", content)
        for content, depth in quoted_lines
    ):
        raise WorkflowError("Pull request body cannot contain blockquoted level-2 headings")
    for index, (content, depth) in enumerate(quoted_lines):
        previous_content, previous_depth = (
            quoted_lines[index - 1] if index else ("", -1)
        )
        if (
            index > 0
            and re.match(r"^ {0,3}-+\s*$", content)
            and previous_content.strip()
            and previous_depth == depth
        ):
            raise WorkflowError("Pull request body cannot contain Setext level-2 headings")
    heading_entries = [
        (index, raw_line)
        for index, (raw_line, visible) in enumerate(visible_lines)
        if raw_line == visible
        and re.match(r"^ {0,3}##(?!#)", raw_line)
    ]
    headings = []
    headings.extend(line for _, line in heading_entries)
    expected = ["## What", "## Why", "## Testing"]
    if headings != expected:
        raise WorkflowError(
            "Pull request body must contain exactly ## What, ## Why, and ## Testing in order"
        )
    positions = [index for index, _ in heading_entries]
    if any(visible.strip() for _, visible in visible_lines[:positions[0]]):
        raise WorkflowError("Pull request body cannot contain content before ## What")
    sections = [
        visible_lines[
            positions[index] + 1:
            positions[index + 1] if index + 1 < len(expected) else None
        ]
        for index in range(len(expected))
    ]
    if any(not section_has_visible_content(section) for section in sections):
        raise WorkflowError("Every required pull request body section must be non-empty")


def section_has_visible_content(section):
    reference_state = None
    for _, visible in section:
        text = visible.strip()
        if not text:
            continue
        if reference_state == "destination":
            destination = re.match(
                r"^(?:<[^>]*>|\S+?)(?:\s+(?:\"[^\"]*\"|'[^']*'|\([^)]*\)))?\s*$",
                text,
            )
            reference_state = None
            if destination:
                has_inline_title = bool(
                    re.search(r"\s(?:\"[^\"]*\"|'[^']*'|\([^)]*\))\s*$", text)
                )
                reference_state = None if has_inline_title else "optional_title"
                continue
        elif reference_state == "optional_title":
            reference_state = None
            if re.match(r"^(?:\"[^\"]*\"|'[^']*'|\([^)]*\))\s*$", text):
                continue
        reference = re.match(r"^\[[^\]]+\]:\s*(.*)$", text)
        if reference:
            remainder = reference.group(1)
            if not remainder:
                reference_state = "destination"
            elif re.search(r"\s(?:\"[^\"]*\"|'[^']*'|\([^)]*\))\s*$", remainder):
                reference_state = None
            else:
                reference_state = "optional_title"
            continue
        text = re.sub(r"<[^>]*>", "", text)
        text = re.sub(r"[\s#>*_`~\[\]()!+-]", "", text)
        if text:
            return True
    return False


def verify_frozen_final_state(root, state, runner, requested_base=None):
    if not validation_passes(state):
        raise WorkflowError("All required validation checks must pass")
    final_review = state.get("final_review") or {}
    if final_review.get("status") != READY:
        raise WorkflowError("Final reviewer readiness is required")
    current_fingerprint = files_fingerprint(root)
    if current_fingerprint != state.get("validated_fingerprint"):
        raise WorkflowError("Workspace changed after validation; validate again")
    if current_fingerprint != final_review.get("workspace_fingerprint"):
        raise WorkflowError("Workspace changed after final review; review again")
    if not artifact_is_unchanged(root, state["artifacts"]["final_review"]):
        raise WorkflowError("Final review artifact changed after submission")
    require_clean_worktree(root, runner)
    evidence = state.get("validation_evidence")
    if not evidence:
        raise WorkflowError("Frozen validation evidence is missing; validate again")
    git_state = verify_git_against_base(
        root, runner, evidence["base"], evidence["base_ref"]
    )
    if requested_base is not None and requested_base != state["target_base"]:
        raise WorkflowError("Requested base does not match the configured target base")
    if git_state["head"] != state.get("validated_head"):
        raise WorkflowError("Git revision changed after validation; validate again")
    if git_state["head"] != final_review.get("head"):
        raise WorkflowError("Git revision changed after final review; review again")
    if evidence.get("base") != state.get("validated_base"):
        raise WorkflowError("Validated base evidence is inconsistent; validate again")
    if (
        state.get("validated_test_fingerprint")
        != state["approvals"]["tests"].get("test_fingerprint")
    ):
        raise WorkflowError("Validated test evidence no longer matches the approved tests")
    return current_fingerprint, git_state


def command_run_validation(args, root, runner):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, "VALIDATION")
        if args.agent != ROLE_NAMES["implementer"]:
            raise WorkflowError("%s must run validation" % ROLE_NAMES["implementer"])
        verify_approved_artifacts(root, state)
        current_test_fingerprint = files_fingerprint(root, state["test_paths"])
        if current_test_fingerprint != state["approvals"]["tests"].get("test_fingerprint"):
            raise WorkflowError("Approved tests changed; explicitly reopen test implementation")
        before = capture_validation_snapshot(root, state, runner)
        state["validation"] = {}
        log_directory = run_dir(root, args.issue, correction) / "validation"
        log_directory.mkdir(exist_ok=True)
        failed = False
        for check in state["required_checks"]:
            check = validate_check(root, check)
            cwd = resolve_inside_root(root, check["cwd"], "Validation check cwd")
            started = now()
            result = runner(
                check["command"], cwd=str(cwd), text=True, capture_output=True
            )
            status = "PASS" if result.returncode == 0 else "FAIL"
            failed = failed or status == "FAIL"
            log_path = log_directory / ("%s.log" % check["name"])
            log_path.write_text(
                "$ %s\n\nSTDOUT:\n%s\n\nSTDERR:\n%s\n"
                % (" ".join(check["command"]), result.stdout, result.stderr)
            )
            state["validation"][check["name"]] = {
                "status": status,
                "command": check["command"],
                "cwd": check.get("cwd", "."),
                "started_at": started,
                "completed_at": now(),
                "returncode": result.returncode,
                "log": str(log_path.relative_to(root)),
            }
        try:
            after = capture_validation_snapshot(
                root, state, runner, expected_base=before["base"]
            )
            stable = all(
                after[key] == before[key]
                for key in (
                    "head",
                    "base",
                    "workspace_fingerprint",
                    "approved_test_fingerprint",
                    "commit_count",
                )
            )
            if not stable:
                raise WorkflowError("Repository evidence changed during validation")
        except WorkflowError as error:
            state["state"] = "IMPLEMENTATION"
            state["validation"] = {}
            state["final_review"] = None
            state["validated_fingerprint"] = None
            state["validated_head"] = None
            state["validated_base"] = None
            state["validated_test_fingerprint"] = None
            state["validation_evidence"] = None
            persist(
                root,
                args.issue,
                state,
                "VALIDATION_INVALIDATED",
                args.agent,
                {"reason": str(error), "before": before},
                correction,
            )
            raise WorkflowError("Validation evidence changed while checks ran: %s" % error)
        state["state"] = "IMPLEMENTATION" if failed else "FINAL_REVIEW"
        if failed:
            state["final_review"] = None
            state["validated_fingerprint"] = None
            state["validated_head"] = None
            state["validated_base"] = None
            state["validated_test_fingerprint"] = None
            state["validation_evidence"] = None
        else:
            state["validated_fingerprint"] = after["workspace_fingerprint"]
            state["validated_head"] = after["head"]
            state["validated_base"] = after["base"]
            state["validated_test_fingerprint"] = after["approved_test_fingerprint"]
            state["validation_evidence"] = after
        persist(
            root, args.issue, state, "VALIDATION_COMPLETED", args.agent,
            {
                "passed": not failed,
                **after,
            },
            correction,
        )
        if failed:
            raise WorkflowError("Validation failed; see the run's validation logs")


def command_create_draft_pr(args, root, runner):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        require_state(state, "FINAL_REVIEW", "DRAFT_PR_CREATED")
        verify_approved_artifacts(root, state)
        base = args.base or state["target_base"]
        expected_body = read_expected_pr_body(root, args.body_file)
        current_fingerprint, git_state = verify_frozen_final_state(
            root, state, runner, requested_base=base
        )
        head = git_state["head"]
        if state["state"] == "FINAL_REVIEW":
            pull_request = find_open_pr(root, state, runner)
            if pull_request:
                verify_pr(pull_request, base, head)
                verify_pr_repository(pull_request, state["issue"]["repo"])
                if pull_request.get("title") != args.title or pull_request.get("body") != expected_body:
                    raise WorkflowError(
                        "Existing draft metadata differs; use revise-pr-metadata"
                    )
                url = pull_request["url"]
            else:
                command = [
                    "gh", "pr", "create",
                    "--repo", state["issue"]["repo"],
                    "--draft", "--title", args.title, "--body-file", args.body_file,
                ]
                command.extend(["--base", base])
                result = runner(command, cwd=str(root), text=True, capture_output=True)
                if result.returncode != 0:
                    raise WorkflowError("Draft PR creation failed: %s" % result.stderr.strip())
                url = result.stdout.strip().splitlines()[-1]
                pull_request = read_pr(root, runner, url)
                verify_pr(pull_request, base, head)
                verify_pr_repository(pull_request, state["issue"]["repo"])
                if pull_request.get("title") != args.title or pull_request.get("body") != expected_body:
                    raise WorkflowError("Created draft pull request metadata does not match the request")
            state["draft_pr"] = {
                "url": url,
                "base": base,
                "head": head,
                "pr_fingerprint": pr_fingerprint(pull_request),
                "workspace_fingerprint": current_fingerprint,
                "created_at": now(),
                "created_by": args.actor,
            }
            state["state"] = "DRAFT_PR_CREATED"
            persist(
                root, args.issue, state, "DRAFT_PR_CREATED", args.actor, {"url": url},
                correction,
            )
        else:
            draft = state["draft_pr"]
            pull_request = read_pr(root, runner, draft["url"])
            verify_pr(pull_request, draft["base"], draft["head"])
            verify_pr_repository(pull_request, state["issue"]["repo"])
            if pr_fingerprint(pull_request) != draft["pr_fingerprint"]:
                raise WorkflowError("Draft pull request changed before recovery")
            if pull_request.get("title") != args.title or pull_request.get("body") != expected_body:
                raise WorkflowError("Draft pull request metadata does not match recovery request")
        state["state"] = "WAITING_FOR_PR_HUMAN_APPROVAL"
        persist(
            root, args.issue, state, "PR_HUMAN_APPROVAL_REQUESTED", args.actor, None,
            correction,
        )


def command_revise_pr_metadata(args, root, runner):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        prepare_settled_transition(root, args.issue, correction)
        state = load_state(root, args.issue, correction)
        require_state(state, "WAITING_FOR_PR_HUMAN_APPROVAL", "FINAL_REVIEW")
        from_pr_gate = state["state"] == "WAITING_FOR_PR_HUMAN_APPROVAL"
        verify_approved_artifacts(root, state)
        expected_body = read_expected_pr_body(root, args.body_file)
        current_fingerprint, git_state = verify_frozen_final_state(root, state, runner)
        draft = state.get("draft_pr")
        if draft is None:
            pull_request = find_open_pr(root, state, runner)
            if not pull_request:
                raise WorkflowError("No existing draft pull request is available to revise")
            verify_pr(pull_request, state["target_base"], git_state["head"])
            verify_pr_repository(pull_request, state["issue"]["repo"])
            draft = {
                "url": pull_request["url"],
                "base": state["target_base"],
                "head": git_state["head"],
                "pr_fingerprint": pr_fingerprint(pull_request),
                "workspace_fingerprint": current_fingerprint,
                "created_at": now(),
                "created_by": args.by,
            }
            state["draft_pr"] = draft
        if draft["base"] != state["target_base"]:
            raise WorkflowError("Recorded draft base does not match the configured target base")
        if draft["head"] != git_state["head"]:
            raise WorkflowError("Recorded draft head does not match the reviewed Git revision")
        pull_request = read_pr(root, runner, draft["url"])
        verify_pr(pull_request, draft["base"], draft["head"])
        verify_pr_repository(pull_request, state["issue"]["repo"])
        live_fingerprint = pr_fingerprint(pull_request)
        already_applied = (
            pull_request.get("title") == args.title
            and pull_request.get("body") == expected_body
        )
        if live_fingerprint != draft["pr_fingerprint"] and not already_applied:
            raise WorkflowError("Draft pull request metadata changed outside the workflow")
        if not already_applied:
            raise WorkflowError(
                "Apply the exact requested title and body on the draft, then rerun this command"
            )
        draft["pr_fingerprint"] = live_fingerprint
        draft.update({
            "workspace_fingerprint": current_fingerprint,
            "metadata_revised_at": now(),
            "metadata_revised_by": args.by,
            "metadata_revision_reason": args.reason,
        })
        state["approvals"]["pr"] = {
            "approved": False,
            "by": args.by,
            "at": now(),
            "reason": args.reason,
            "metadata_only": True,
        }
        state["state"] = "WAITING_FOR_PR_HUMAN_APPROVAL"
        writer = persist_pr_transition if from_pr_gate else persist
        writer(
            root, args.issue, state, "PR_METADATA_HUMAN_REVISED", args.by,
            {"reason": args.reason, "url": draft["url"]}, correction,
        )


def command_approve_pr(args, root, runner):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        prepare_settled_transition(root, args.issue, correction)
        state = load_state(root, args.issue, correction)
        require_state(state, "WAITING_FOR_PR_HUMAN_APPROVAL")
        verify_approved_artifacts(root, state)
        if args.confirm != APPROVAL_CONFIRMATIONS["pr"]:
            raise WorkflowError(
                "Explicit confirmation must be exactly: %s"
                % APPROVAL_CONFIRMATIONS["pr"]
            )
        draft = state["draft_pr"]
        current_fingerprint, git_state = verify_frozen_final_state(root, state, runner)
        if current_fingerprint != draft["workspace_fingerprint"]:
            raise WorkflowError("Workspace changed after draft PR creation")
        if git_state["head"] != draft["head"]:
            raise WorkflowError("Git revision changed after draft PR creation")
        pull_request = read_pr(root, runner, draft["url"])
        verify_pr(pull_request, draft["base"], draft["head"])
        verify_pr_repository(pull_request, state["issue"]["repo"])
        if pr_fingerprint(pull_request) != draft["pr_fingerprint"]:
            raise WorkflowError("Draft pull request metadata changed after creation")
        state["approvals"]["pr"] = {
            "approved": True,
            "by": args.by,
            "at": now(),
            "confirmation": args.confirm,
            "pr_fingerprint": draft["pr_fingerprint"],
        }
        state["state"] = "PR_APPROVED"
        persist_pr_transition(
            root, args.issue, state, "PR_HUMAN_APPROVED", args.by, None, correction
        )


def command_adopt_legacy(args, root):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        if not args.by.strip() or not args.reason.strip():
            raise WorkflowError("Adopter identity and reason must be non-empty")
        if args.confirm != LEGACY_ADOPTION_CONFIRMATION:
            raise WorkflowError(
                "Explicit confirmation must be exactly: %s"
                % LEGACY_ADOPTION_CONFIRMATION
            )
        transaction_file = adoption_transaction_path(root, args.issue, correction)
        if transaction_file.exists():
            transaction = parse_json_object(
                transaction_file.read_bytes(), "Legacy adoption transaction"
            )
            decode_legacy_record(
                transaction,
                args.issue,
                correction,
                ADOPTION_TRANSACTION_FORMAT,
                {ACTIVE_ADOPTION_MODE},
            )
            if (
                transaction["adopted_by"] != args.by
                or transaction["reason"] != args.reason
                or transaction["confirmation"] != args.confirm
            ):
                raise WorkflowError(
                    "Adoption command conflicts with the incomplete transaction"
                )
            finish_adoption_transaction(
                root, args.issue, correction, ACTIVE_ADOPTION_MODE
            )
            return

        record_path = integrity_path(root, args.issue, correction)
        if record_path.exists():
            existing = parse_json_object(record_path.read_bytes(), "Integrity record")
            if existing.get("mode") == SETTLED_LEGACY_MODE:
                state_data, history_data = read_projection_bytes(
                    root, args.issue, correction
                )
                validate_settled_legacy_envelope(
                    existing, args.issue, correction, state_data, history_data
                )
                if (
                    existing["adopted_by"] != args.by
                    or existing["reason"] != args.reason
                    or existing["confirmation"] != args.confirm
                ):
                    raise WorkflowError(
                        "Settled legacy adoption trust metadata conflicts"
                    )
                return
            raise WorkflowError("Run already has an integrity record; adoption is refused")

        state_data, history_data = read_projection_bytes(root, args.issue, correction)
        state, _ = validate_legacy_payload(
            state_data, history_data, args.issue, correction
        )
        legacy_version = state.get("version", 1)
        if state["state"] in SETTLED_STATES:
            write_json_atomic(record_path, {
                "format": INTEGRITY_FORMAT,
                "mode": SETTLED_LEGACY_MODE,
                "issue": args.issue,
                "correction": correction,
                "legacy_version": legacy_version,
                "state_sha256": sha256(state_data),
                "history_sha256": sha256(history_data),
                "state_bytes": base64.b64encode(state_data).decode(),
                "history_bytes": base64.b64encode(history_data).decode(),
                "adopted_at": now(),
                "adopted_by": args.by,
                "confirmation": args.confirm,
                "reason": args.reason,
            })
            return
        transaction = adoption_record(
            ACTIVE_ADOPTION_MODE,
            args.issue,
            correction,
            legacy_version,
            state_data,
            history_data,
            now(),
            args.by,
            args.reason,
        )
        write_json_atomic(transaction_file, transaction)
        finish_adoption_transaction(
            root, args.issue, correction, ACTIVE_ADOPTION_MODE
        )


def decode_pr_transition_record(record, issue, correction):
    validate_transaction_identity(
        record,
        PR_TRANSITION_TRANSACTION_FORMAT,
        PR_TRANSITION_MODE,
        issue,
        correction,
    )
    source, source_state_data, source_history_data = decode_snapshot(
        record, "source_", issue, correction
    )
    target, target_state_data, target_history_data = decode_snapshot(
        record, "target_", issue, correction
    )
    event = record.get("event")
    target_states = {
        "PR_HUMAN_APPROVED": "PR_APPROVED",
        "PR_HUMAN_REJECTED": "IMPLEMENTATION",
        "PR_METADATA_HUMAN_REVISED": "WAITING_FOR_PR_HUMAN_APPROVAL",
    }
    if (
        source["state"] != "WAITING_FOR_PR_HUMAN_APPROVAL"
        or event not in target_states
        or target["state"] != target_states[event]
        or len(target["history"]) != len(source["history"]) + 1
        or target["history"][:-1] != source["history"]
        or target["history"][-1]["event"] != event
    ):
        raise WorkflowError("PR transition transaction lifecycle is invalid")
    return (
        source,
        source_state_data,
        source_history_data,
        target,
        target_state_data,
        target_history_data,
    )


def recover_pr_transition(root, issue, correction):
    transaction_file = pr_transition_transaction_path(root, issue, correction)
    transaction = parse_json_object(
        transaction_file.read_bytes(), "PR transition transaction"
    )
    (
        source,
        source_state_data,
        source_history_data,
        target,
        target_state_data,
        target_history_data,
    ) = decode_pr_transition_record(transaction, issue, correction)
    record_file = integrity_path(root, issue, correction)
    if not record_file.is_file():
        raise WorkflowError("PR transition recovery requires its committed envelope")
    envelope = parse_json_object(record_file.read_bytes(), "Integrity record")
    committed, committed_state, committed_history = validate_committed_envelope(
        envelope, issue, correction
    )
    state_file = state_path(root, issue, correction)
    history_file = history_path(root, issue, correction)
    live_state = state_file.read_bytes() if state_file.is_file() else None
    live_history = history_file.read_bytes() if history_file.is_file() else None

    if (
        committed_state == source_state_data
        and committed_history == source_history_data
    ):
        if live_state not in (source_state_data, target_state_data):
            raise WorkflowError(
                "State projection conflicts with the PR transition transaction"
            )
        if live_history not in (source_history_data, target_history_data):
            raise WorkflowError(
                "History projection conflicts with the PR transition transaction"
            )
        write_text_atomic(state_file, source_state_data.decode())
        write_text_atomic(history_file, source_history_data.decode())
        transaction_file.unlink()
        return source

    if (
        committed_state == target_state_data
        and committed_history == target_history_data
    ):
        if live_state != target_state_data or live_history != target_history_data:
            raise WorkflowError(
                "Committed PR transition projections are not byte-exact"
            )
        if committed != target:
            raise WorkflowError("Committed PR transition snapshot conflicts")
        transaction_file.unlink()
        return target
    raise WorkflowError("Committed envelope conflicts with the PR transition transaction")


def command_recover_run(args, root):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        if bootstrap_transaction_path(root, args.issue, correction).exists():
            raise WorkflowError(
                "Recovery is unavailable while creation is incomplete"
            )
        if adoption_transaction_path(root, args.issue, correction).exists():
            raise WorkflowError(
                "Recovery is unavailable while legacy adoption is incomplete"
            )
        if pr_transition_transaction_path(root, args.issue, correction).exists():
            recover_pr_transition(root, args.issue, correction)
            return
        record_path = integrity_path(root, args.issue, correction)
        if not record_path.is_file():
            raise WorkflowError("Recovery requires an existing v4 committed envelope")
        envelope = parse_json_object(record_path.read_bytes(), "Integrity record")
        state, committed_state, committed_history = validate_committed_envelope(
            envelope, args.issue, correction
        )
        if state["state"] in SETTLED_STATES:
            raise WorkflowError("Settled runs are immutable and cannot be recovered")
        state_file = state_path(root, args.issue, correction)
        history_file = history_path(root, args.issue, correction)
        live_state = state_file.read_bytes() if state_file.is_file() else None
        live_history = history_file.read_bytes() if history_file.is_file() else None
        if live_state == committed_state and live_history == committed_history:
            raise WorkflowError("Workflow projections already match committed integrity")
        observed = {
            "observed_state_sha256": sha256(live_state) if live_state is not None else None,
            "observed_history_sha256": (
                sha256(live_history) if live_history is not None else None
            ),
            "committed_sequence": envelope["sequence"],
        }
        recovered = copy.deepcopy(state)
        persist(
            root,
            args.issue,
            recovered,
            "RUN_INTEGRITY_RECOVERED",
            "chess-echo-orchestrator",
            observed,
            correction,
        )


def build_correction_state(
    root, args, source_state, source_correction, source_state_sha256,
    source_history_sha256, number,
):
    profile = CORRECTION_CLASSES[args.classification]
    missing_artifacts = [
        kind for kind in profile["artifacts"] if kind not in source_state["artifacts"]
    ]
    if missing_artifacts:
        raise WorkflowError(
            "Source run is missing inheritable artifacts: %s"
            % ", ".join(missing_artifacts)
        )
    missing_approvals = [
        key for key in profile["approvals"]
        if not source_state["approvals"].get(key, {}).get("approved")
    ]
    if missing_approvals:
        raise WorkflowError(
            "Source run is missing inheritable approvals: %s"
            % ", ".join(missing_approvals)
        )
    inherited = []
    invalidated = []
    for kind in CORRECTION_ARTIFACT_KINDS:
        target = inherited if kind in profile["artifacts"] else invalidated
        target.append("artifact:%s" % kind)
    for key in CORRECTION_APPROVAL_KEYS:
        target = inherited if key in profile["approvals"] else invalidated
        target.append("approval:%s" % key)
    for field in CORRECTION_EVIDENCE_FIELDS:
        target = inherited if field in profile["evidence"] else invalidated
        target.append("evidence:%s" % field)
    timestamp = now()
    state = {
        "version": VERSION,
        "issue": source_state["issue"],
        "scope": source_state["scope"],
        "target_base": source_state["target_base"],
        "state": profile["state"],
        "required_checks": source_state["required_checks"],
        "test_paths": source_state["test_paths"],
        "artifacts": {
            kind: dict(source_state["artifacts"][kind]) for kind in profile["artifacts"]
        },
        "approvals": {
            key: dict(source_state["approvals"][key]) for key in profile["approvals"]
        },
        "validation": {},
        "validated_fingerprint": None,
        "validated_head": None,
        "validated_base": None,
        "validated_test_fingerprint": None,
        "validation_evidence": None,
        "final_review": None,
        "draft_pr": None,
        "history": [],
        "created_at": timestamp,
        "updated_at": timestamp,
        "correction": {
            "number": number,
            "classification": args.classification,
            "reason": args.reason,
            "requested_by": args.by,
            "created_at": timestamp,
            "inherited": inherited,
            "invalidated": invalidated,
        },
        "parent_run": {
            "issue": args.issue,
            "correction": source_correction,
            "state": source_state["state"],
            "validated_head": source_state["validated_head"],
            "validated_base": source_state["validated_base"],
            "state_sha256": source_state_sha256,
            "history_sha256": source_history_sha256,
        },
    }
    for field in profile["evidence"]:
        state[field] = source_state[field]
    if args.classification == "test-contract":
        state["approvals"]["plan"]["non_test_fingerprint"] = non_test_fingerprint(
            root, state["test_paths"]
        )
    return state


def correction_bootstrap_request(args):
    return {
        "command": "start-correction",
        "issue": args.issue,
        "classification": args.classification,
        "by": args.by,
        "reason": args.reason,
        "from_correction": args.from_correction,
    }


def pending_correction_bootstrap(root, issue):
    directory = run_dir(root, issue) / "corrections"
    if not directory.is_dir():
        return None
    pending = []
    for entry in directory.iterdir():
        transaction = entry / "bootstrap-transaction.json"
        if not transaction.exists():
            continue
        if (
            not entry.is_dir()
            or not entry.name.isdigit()
            or str(int(entry.name)) != entry.name
        ):
            raise WorkflowError("Correction creation transaction has an invalid path")
        pending.append(int(entry.name))
    if len(pending) > 1:
        raise WorkflowError("Multiple correction creation transactions exist")
    return pending[0] if pending else None


def command_start_correction(args, root, runner):
    source_correction = args.from_correction
    request = correction_bootstrap_request(args)
    with locked_run(root, args.issue):
        pending = pending_correction_bootstrap(root, args.issue)
        if pending is not None:
            with lock_directory(run_dir(root, args.issue, pending)):
                finish_bootstrap_transaction(
                    root,
                    args.issue,
                    pending,
                    CORRECTION_BOOTSTRAP_MODE,
                    request,
                )
            return
        source_state, source_state_data, source_history_data = verified_run(
            root, args.issue, source_correction
        )
        source_state_sha256 = sha256(source_state_data)
        source_history_sha256 = sha256(source_history_data)
        require_state(source_state, *CORRECTION_SOURCE_STATES)
        anchor = source_state.get("validated_head")
        if not anchor or not source_state.get("validated_base"):
            raise WorkflowError(
                "The source run has no validated head and base to anchor a correction on"
            )
        existing = correction_numbers(root, args.issue)
        if existing:
            latest = max(existing)
            if source_correction != latest:
                latest_state, _ = read_run_state(root, args.issue, latest)
                if latest_state.get("validated_head") != anchor:
                    raise WorkflowError(
                        "Correction %s has newer validated evidence; start from it with "
                        "--from-correction %s" % (latest, latest)
                    )
        for number in existing:
            if number == source_correction:
                continue
            sibling, _ = read_run_state(root, args.issue, number)
            if sibling["state"] not in CORRECTION_SOURCE_STATES:
                raise WorkflowError(
                    "Correction %s is still in flight in %s; finish it before starting another"
                    % (number, sibling["state"])
                )
        number = (max(existing) if existing else 0) + 1
        if state_path(root, args.issue, number).exists():
            raise WorkflowError(
                "Correction %s already exists for issue %s" % (number, args.issue)
            )
        verify_correction_ancestry(root, runner, anchor)
        require_clean_worktree(root, runner)
        if args.classification == "metadata-only":
            verify_approved_artifacts(root, source_state)
            if files_fingerprint(root) != source_state["validated_fingerprint"]:
                raise WorkflowError(
                    "Workspace changed since the source run was validated; "
                    "start a code-changing correction instead"
                )
            if git_head(root, runner) != anchor:
                raise WorkflowError(
                    "Git revision changed since the source run was validated; "
                    "start a code-changing correction instead"
                )
        state = build_correction_state(
            root, args, source_state, source_correction, source_state_sha256,
            source_history_sha256, number,
        )
        directory = run_dir(root, args.issue, number)
        for projection in (
            state_path(root, args.issue, number),
            history_path(root, args.issue, number),
            integrity_path(root, args.issue, number),
        ):
            if projection.exists():
                raise WorkflowError(
                    "Correction %s has conflicting partial creation files" % number
                )
        append_event(
            state,
            "CORRECTION_CREATED",
            args.by,
            {
                "number": number,
                "classification": args.classification,
                "reason": args.reason,
                "parent_correction": source_correction,
            },
        )
        state_data = canonical_state_bytes(state)
        history_data = canonical_history_bytes(state["history"])
        transaction_file = bootstrap_transaction_path(root, args.issue, number)
        write_json_atomic(
            transaction_file,
            bootstrap_record(
                CORRECTION_BOOTSTRAP_MODE,
                args.issue,
                number,
                request,
                state_data,
                history_data,
            ),
        )
        with lock_directory(directory):
            finish_bootstrap_transaction(
                root,
                args.issue,
                number,
                CORRECTION_BOOTSTRAP_MODE,
                request,
            )


def command_status(args, root):
    correction = correction_of(args)
    with locked_run(root, args.issue, correction):
        state = load_state(root, args.issue, correction)
        summary = {
            "issue": state["issue"],
            "state": state["state"],
            "scope": state["scope"],
            "target_base": state["target_base"],
            "approvals": state["approvals"],
            "validation": {
                name: result["status"] for name, result in state["validation"].items()
            },
            "final_review": state["final_review"],
            "validated_head": state.get("validated_head"),
            "validated_base": state.get("validated_base"),
            "validated_test_fingerprint": state.get("validated_test_fingerprint"),
            "validation_evidence": state.get("validation_evidence"),
            "draft_pr": state["draft_pr"],
        }
        if correction is None:
            summary["corrections"] = load_correction_summaries(root, args.issue)
        else:
            summary["correction"] = state["correction"]
            summary["parent_run"] = state["parent_run"]
    print(json.dumps(summary, indent=2, sort_keys=True))


def add_artifact_arguments(parser, role):
    parser.add_argument("issue", type=int)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--%s" % role, required=True)
    parser.add_argument("--correction", type=int, default=None)


def add_human_arguments(parser, rejection=False):
    parser.add_argument("issue", type=int)
    parser.add_argument("--by", required=True)
    if rejection:
        parser.add_argument("--reason", required=True)
    else:
        parser.add_argument("--confirm", required=True)
    parser.add_argument("--correction", type=int, default=None)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root")
    subparsers = parser.add_subparsers(dest="command", required=True)

    init = subparsers.add_parser("init")
    init.add_argument("issue", type=int)
    init.add_argument("--repo", default="NathanZK/ChessEcho")
    init.add_argument(
        "--scope", choices=("backend", "frontend", "full-stack", "workflow-tooling")
    )
    init.add_argument("--issue-file")
    init.add_argument("--title")
    init.add_argument("--url")
    init.add_argument("--actor", default="chess-echo-orchestrator")

    status = subparsers.add_parser("status")
    status.add_argument("issue", type=int)
    status.add_argument("--correction", type=int, default=None)

    adopt = subparsers.add_parser(
        "adopt-legacy-run",
        help="explicitly trust and adopt a structurally consistent v1-v3 run",
    )
    adopt.add_argument("issue", type=int)
    adopt.add_argument("--by", required=True)
    adopt.add_argument("--reason", required=True)
    adopt.add_argument("--confirm", required=True)
    adopt.add_argument("--correction", type=int, default=None)

    recover = subparsers.add_parser(
        "recover-run",
        help="restore active projections from the last valid v4 committed envelope",
    )
    recover.add_argument("issue", type=int)
    recover.add_argument("--correction", type=int, default=None)

    start_correction = subparsers.add_parser(
        "start-correction",
        help="fork an immutable, linked correction run from a settled run",
    )
    start_correction.add_argument("issue", type=int)
    start_correction.add_argument(
        "--classification", required=True, choices=CORRECTION_CLASSIFICATIONS
    )
    start_correction.add_argument("--by", required=True)
    start_correction.add_argument("--reason", required=True)
    start_correction.add_argument("--from-correction", type=int, default=None)

    for name, role in (
        ("submit-plan", "agent"),
        ("submit-tests", "agent"),
        ("submit-implementation", "agent"),
    ):
        add_artifact_arguments(subparsers.add_parser(name), role)

    for name in ("review-plan", "review-tests", "review-final"):
        review_parser = subparsers.add_parser(name)
        add_artifact_arguments(review_parser, "reviewer")
        review_parser.add_argument("--status", required=True, choices=REVIEW_STATUSES)

    for name in ("approve-plan", "approve-tests", "approve-pr"):
        add_human_arguments(subparsers.add_parser(name))
    for name in ("reject-plan", "reject-tests", "reject-pr"):
        add_human_arguments(subparsers.add_parser(name), rejection=True)
    add_human_arguments(subparsers.add_parser("reopen-tests"), rejection=True)
    add_human_arguments(subparsers.add_parser("reopen-plan"), rejection=True)

    validate = subparsers.add_parser("run-validation")
    validate.add_argument("issue", type=int)
    validate.add_argument("--agent", default="chess-echo-implementer")
    validate.add_argument("--correction", type=int, default=None)

    draft = subparsers.add_parser("create-draft-pr")
    draft.add_argument("issue", type=int)
    draft.add_argument("--title", required=True)
    draft.add_argument("--body-file", required=True)
    draft.add_argument("--base")
    draft.add_argument("--actor", default="chess-echo-orchestrator")
    draft.add_argument("--correction", type=int, default=None)
    revise_metadata = subparsers.add_parser(
        "revise-pr-metadata",
        help="apply a human-authorized title/body-only revision to the frozen draft",
    )
    add_human_arguments(revise_metadata, rejection=True)
    revise_metadata.add_argument("--title", required=True)
    revise_metadata.add_argument("--body-file", required=True)
    return parser


def _dispatch_init(args, root, runner):
    command_init(args, root, runner)


def _dispatch_status(args, root, runner):
    command_status(args, root)


def _dispatch_adopt_legacy(args, root, runner):
    command_adopt_legacy(args, root)


def _dispatch_recover_run(args, root, runner):
    command_recover_run(args, root)


def _dispatch_submit_plan(args, root, runner):
    submit_artifact(
        args, root, "plan", "PLANNING", "PLAN_REVIEW", "PLAN_SUBMITTED"
    )


def _dispatch_review_plan(args, root, runner):
    review(
        args,
        root,
        runner,
        "plan_review",
        "PLAN_REVIEW",
        "PLANNING",
        "WAITING_FOR_PLAN_HUMAN_APPROVAL",
        "PLAN_REVIEWED",
    )


def _dispatch_approve_plan(args, root, runner):
    approval(
        args,
        root,
        "WAITING_FOR_PLAN_HUMAN_APPROVAL",
        "TEST_IMPLEMENTATION",
        "plan",
        "PLAN_HUMAN_APPROVED",
    )


def _dispatch_reject_plan(args, root, runner):
    rejection(
        args,
        root,
        "WAITING_FOR_PLAN_HUMAN_APPROVAL",
        "PLANNING",
        "plan",
        "PLAN_HUMAN_REJECTED",
    )


def _dispatch_submit_tests(args, root, runner):
    submit_artifact(
        args,
        root,
        "test_report",
        "TEST_IMPLEMENTATION",
        "TEST_REVIEW",
        "TESTS_SUBMITTED",
    )


def _dispatch_review_tests(args, root, runner):
    review(
        args,
        root,
        runner,
        "test_review",
        "TEST_REVIEW",
        "TEST_IMPLEMENTATION",
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "TESTS_REVIEWED",
    )


def _dispatch_approve_tests(args, root, runner):
    approval(
        args,
        root,
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "IMPLEMENTATION",
        "tests",
        "TESTS_HUMAN_APPROVED",
    )


def _dispatch_reject_tests(args, root, runner):
    rejection(
        args,
        root,
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "TEST_IMPLEMENTATION",
        "tests",
        "TESTS_HUMAN_REJECTED",
    )


def _dispatch_reopen_tests(args, root, runner):
    command_reopen_tests(args, root)


def _dispatch_reopen_plan(args, root, runner):
    command_reopen_plan(args, root)


def _dispatch_submit_implementation(args, root, runner):
    submit_artifact(
        args,
        root,
        "implementation_report",
        "IMPLEMENTATION",
        "VALIDATION",
        "IMPLEMENTATION_SUBMITTED",
    )


def _dispatch_run_validation(args, root, runner):
    command_run_validation(args, root, runner)


def _dispatch_review_final(args, root, runner):
    review(
        args,
        root,
        runner,
        "final_review",
        "FINAL_REVIEW",
        "IMPLEMENTATION",
        "FINAL_REVIEW",
        "IMPLEMENTATION_REVIEWED",
    )


def _dispatch_create_draft_pr(args, root, runner):
    command_create_draft_pr(args, root, runner)


def _dispatch_approve_pr(args, root, runner):
    command_approve_pr(args, root, runner)


def _dispatch_revise_pr_metadata(args, root, runner):
    command_revise_pr_metadata(args, root, runner)


def _dispatch_reject_pr(args, root, runner):
    rejection(
        args,
        root,
        "WAITING_FOR_PR_HUMAN_APPROVAL",
        "IMPLEMENTATION",
        "pr",
        "PR_HUMAN_REJECTED",
    )


def _dispatch_start_correction(args, root, runner):
    command_start_correction(args, root, runner)


COMMAND_HANDLERS = {
    "init": _dispatch_init,
    "status": _dispatch_status,
    "adopt-legacy-run": _dispatch_adopt_legacy,
    "recover-run": _dispatch_recover_run,
    "submit-plan": _dispatch_submit_plan,
    "review-plan": _dispatch_review_plan,
    "approve-plan": _dispatch_approve_plan,
    "reject-plan": _dispatch_reject_plan,
    "submit-tests": _dispatch_submit_tests,
    "review-tests": _dispatch_review_tests,
    "approve-tests": _dispatch_approve_tests,
    "reject-tests": _dispatch_reject_tests,
    "reopen-tests": _dispatch_reopen_tests,
    "reopen-plan": _dispatch_reopen_plan,
    "submit-implementation": _dispatch_submit_implementation,
    "run-validation": _dispatch_run_validation,
    "review-final": _dispatch_review_final,
    "create-draft-pr": _dispatch_create_draft_pr,
    "approve-pr": _dispatch_approve_pr,
    "revise-pr-metadata": _dispatch_revise_pr_metadata,
    "reject-pr": _dispatch_reject_pr,
    "start-correction": _dispatch_start_correction,
}


def dispatch(args, root, runner):
    handler = COMMAND_HANDLERS.get(args.command)
    if handler is None:
        raise WorkflowError("Unknown command: %s" % args.command)
    handler(args, root, runner)


def main(argv=None, runner=subprocess.run):
    parser = build_parser()
    args = parser.parse_args(argv)
    root = pathlib.Path(args.root).resolve()
    try:
        dispatch(args, root, runner)
        return 0
    except (WorkflowError, OSError, json.JSONDecodeError) as error:
        print("error: %s" % error, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
