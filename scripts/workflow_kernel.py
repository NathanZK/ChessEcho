#!/usr/bin/env python3
"""Low-level integrity and persistence primitives for the legacy workflow CLI."""

import base64
import copy
import fcntl
import hashlib
import json
import os
import tempfile
from contextlib import contextmanager


VERSION = 4
INTEGRITY_FORMAT = "chess-echo-run-integrity-v4"
COMMITTED_MODE = "v4-committed"


class WorkflowError(Exception):
    pass


def run_dir(root, issue, correction=None):
    directory = root / ".agent-workflow" / "runs" / ("issue-%s" % issue)
    if correction is None:
        return directory
    return directory / "corrections" / str(int(correction))


def state_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "state.json"


def history_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "history.jsonl"


def integrity_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "integrity.json"


def adoption_transaction_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "adoption-transaction.json"


def bootstrap_transaction_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "bootstrap-transaction.json"


def pr_transition_transaction_path(root, issue, correction=None):
    return run_dir(root, issue, correction) / "pr-transition-transaction.json"


def canonical_state_bytes(state):
    return (json.dumps(state, indent=2, sort_keys=True) + "\n").encode()


def canonical_history_bytes(history):
    return "".join(
        json.dumps(entry, sort_keys=True) + "\n" for entry in history
    ).encode()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def parse_json_object(data, label):
    try:
        value = json.loads(data.decode())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise WorkflowError("%s is not valid JSON: %s" % (label, error))
    if not isinstance(value, dict):
        raise WorkflowError("%s must contain a JSON object" % label)
    return value


def parse_history(data):
    events = []
    try:
        text = data.decode()
    except UnicodeDecodeError as error:
        raise WorkflowError("Workflow history is not UTF-8: %s" % error)
    for line_number, line in enumerate(text.splitlines(), 1):
        if not line:
            raise WorkflowError("Workflow history contains an empty line")
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise WorkflowError(
                "Workflow history line %s is not valid JSON: %s"
                % (line_number, error)
            )
        if not isinstance(event, dict):
            raise WorkflowError("Workflow history events must be JSON objects")
        events.append(event)
    return events


def validate_run_structure(state, events, issue, correction, versions):
    version = state.get("version", 1)
    if type(version) is not int or version not in versions:
        raise WorkflowError("Workflow schema version is invalid: %r" % version)
    issue_record = state.get("issue")
    if (
        not isinstance(issue_record, dict)
        or type(issue_record.get("number")) is not int
        or issue_record.get("number") != issue
    ):
        raise WorkflowError("Workflow state does not belong to issue %s" % issue)
    for field in ("artifacts", "approvals", "validation"):
        if not isinstance(state.get(field), dict):
            raise WorkflowError("Workflow state field %s must be an object" % field)
    string_fields = ("scope", "target_base") if version == VERSION else ("scope",)
    for field in string_fields:
        if not isinstance(state.get(field), str) or not state[field]:
            raise WorkflowError(
                "Workflow state field %s must be a non-empty string" % field
            )
    if not isinstance(state.get("required_checks"), list):
        raise WorkflowError("Workflow required_checks must be a list")
    if not isinstance(state.get("test_paths"), list):
        raise WorkflowError("Workflow test_paths must be a list")
    embedded = state.get("history")
    if not isinstance(embedded, list) or embedded != events:
        raise WorkflowError("Embedded workflow history does not match history.jsonl")
    correction_record = state.get("correction")
    if correction is None:
        if correction_record is not None:
            raise WorkflowError("Root workflow state has a correction identity")
    elif (
        not isinstance(correction_record, dict)
        or type(correction_record.get("number")) is not int
        or correction_record.get("number") != correction
    ):
        raise WorkflowError(
            "Workflow state does not belong to correction %s" % correction
        )
    for sequence, event in enumerate(events, 1):
        if type(event.get("sequence")) is not int or event.get("sequence") != sequence:
            raise WorkflowError("Workflow history sequence must be contiguous")
        for field in ("timestamp", "event", "actor", "state", "details"):
            if field not in event:
                raise WorkflowError(
                    "Workflow history event is missing %s" % field
                )
        if (
            not isinstance(event["timestamp"], str)
            or not isinstance(event["event"], str)
            or not isinstance(event["actor"], str)
            or not isinstance(event["state"], str)
            or not isinstance(event["details"], dict)
        ):
            raise WorkflowError("Workflow history event has invalid field types")
    if not events:
        raise WorkflowError("Workflow history must contain at least one event")
    if not isinstance(state.get("state"), str) or events[-1]["state"] != state["state"]:
        raise WorkflowError(
            "Latest workflow history state does not match current state"
        )
    return state


def validate_committed_envelope(envelope, issue, correction):
    if (
        envelope.get("format") != INTEGRITY_FORMAT
        or envelope.get("mode") != COMMITTED_MODE
    ):
        raise WorkflowError("Integrity record is not a v4 committed envelope")
    if (
        type(envelope.get("issue")) is not int
        or envelope.get("issue") != issue
        or envelope.get("correction") != correction
        or (correction is not None and type(envelope.get("correction")) is not int)
    ):
        raise WorkflowError("Integrity record has the wrong run identity")
    state = envelope.get("state")
    events = envelope.get("history")
    if not isinstance(state, dict) or not isinstance(events, list):
        raise WorkflowError("Integrity envelope snapshot is invalid")
    validate_run_structure(state, events, issue, correction, {VERSION})
    state_data = canonical_state_bytes(state)
    history_data = canonical_history_bytes(events)
    if envelope.get("sequence") != events[-1]["sequence"]:
        raise WorkflowError("Integrity envelope sequence is stale")
    if envelope.get("state_sha256") != sha256(state_data):
        raise WorkflowError("Integrity envelope state hash is stale")
    if envelope.get("history_sha256") != sha256(history_data):
        raise WorkflowError("Integrity envelope history hash is stale")
    return state, state_data, history_data


def encoded_snapshot(state_data, history_data):
    return {
        "state_sha256": sha256(state_data),
        "history_sha256": sha256(history_data),
        "state_bytes": base64.b64encode(state_data).decode(),
        "history_bytes": base64.b64encode(history_data).decode(),
    }


def decode_snapshot(record, prefix, issue, correction):
    try:
        state_data = base64.b64decode(
            record["%sstate_bytes" % prefix], validate=True
        )
        history_data = base64.b64decode(
            record["%shistory_bytes" % prefix], validate=True
        )
    except (KeyError, TypeError, ValueError) as error:
        raise WorkflowError("Transaction snapshot payload is invalid: %s" % error)
    if (
        record.get("%sstate_sha256" % prefix) != sha256(state_data)
        or record.get("%shistory_sha256" % prefix) != sha256(history_data)
    ):
        raise WorkflowError("Transaction snapshot hashes are invalid")
    state = parse_json_object(state_data, "Transaction state snapshot")
    events = parse_history(history_data)
    validate_run_structure(state, events, issue, correction, {VERSION})
    if (
        state_data != canonical_state_bytes(state)
        or history_data != canonical_history_bytes(events)
    ):
        raise WorkflowError("Transaction snapshot is not canonically serialized")
    return state, state_data, history_data


def write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        dir=str(path.parent), prefix=".state-", text=True
    )
    try:
        with os.fdopen(descriptor, "w") as handle:
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def write_text_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        dir=str(path.parent), prefix=".history-", text=True
    )
    try:
        with os.fdopen(descriptor, "w") as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def lock_directory(directory):
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".lock").open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield


@contextmanager
def locked_run(root, issue, correction=None):
    with lock_directory(run_dir(root, issue)):
        if correction is None:
            yield
        else:
            with lock_directory(run_dir(root, issue, correction)):
                yield


def committed_envelope(issue, correction, state, state_data, history_data):
    return {
        "format": INTEGRITY_FORMAT,
        "mode": COMMITTED_MODE,
        "issue": issue,
        "correction": correction,
        "sequence": state["history"][-1]["sequence"],
        "state_sha256": sha256(state_data),
        "history_sha256": sha256(history_data),
        "state": copy.deepcopy(state),
        "history": copy.deepcopy(state["history"]),
    }


def write_committed_snapshot(
    root, issue, correction, state, state_data, history_data
):
    write_text_atomic(state_path(root, issue, correction), state_data.decode())
    write_text_atomic(history_path(root, issue, correction), history_data.decode())
    write_json_atomic(
        integrity_path(root, issue, correction),
        committed_envelope(issue, correction, state, state_data, history_data),
    )
