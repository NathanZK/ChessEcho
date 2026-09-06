#!/usr/bin/env python3
"""Authorized restoration of an exact orchestration authority pointer."""

import argparse
import base64
import errno
import fcntl
import os
import pathlib
import stat
import sys
import threading

try:
    import workflow_authority as authority
    import workflow_cas
    import workflow_inspector as inspector
except ModuleNotFoundError:
    from scripts import workflow_authority as authority
    from scripts import workflow_cas
    from scripts import workflow_inspector as inspector


REQUEST_FORMAT = "chess-echo-orchestration-repair-request-v1"
BUNDLE_FORMAT = "chess-echo-orchestration-repair-bundle-v1"
JOURNAL_FORMAT = "chess-echo-orchestration-repair-journal-v1"
RECEIPT_FORMAT = "chess-echo-orchestration-repair-receipt-v1"
RESULT_FORMAT = "chess-echo-orchestration-repair-result-v1"
OPERATION = "restore-checkpoint"
CONFIRMATION = "RESTORE ISSUE {issue} EXACT PRE-CORRUPTION ORCHESTRATION POINTER"
QUIESCENCE = "noncooperating-orchestration-pointer-writers-stopped"
SELECTION = "exact-independently-captured-pre-corruption-checkpoint"
DOCUMENT_LIMIT = 8 * 1024 * 1024
JOURNAL_LIMIT = DOCUMENT_LIMIT + 1024
REQUEST_LIMIT = 64 * 1024
AUTHORIZATION_TEXT_LIMIT = 4 * 1024
STALE_TEMPORARY_LIMIT = 1_024
REPAIR_TEMPORARY_PREFIX = ".pointer.json.repair-"
JOURNAL_TEMPORARY_PREFIX = ".repair-journal.json.orchestration-repair-"
AUTHORITY_TEMPORARY_PREFIX = authority.TEMPORARY_PREFIX
EXIT_CODES = {
    "resolved": 0, "missing": 3, "unsupported": 4, "corrupt": 5,
    "ambiguous": 6, "stale": 7, "denied": 8, "invalid": 9, "conflict": 10,
}
FROZEN_ISSUES = frozenset({115})


class OrchestrationRepairFailure(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message

    def document(self):
        return result_document({
            "status": self.status, "code": self.code, "message": self.message,
        })


def phase_hook(_stage):
    """Test seam for interruption at a durable transaction boundary."""


def _fail(status, code, message):
    raise OrchestrationRepairFailure(status, code, message)


def _require(condition, status, code, message):
    if not condition:
        _fail(status, code, message)


def _issue(value):
    _require(type(value) is int and value > 0, "invalid", "invalid-issue",
             "Issue must be a positive integer")
    _require(value not in FROZEN_ISSUES, "denied", "issue-frozen",
             "Frozen issue 115 is not a repair target")
    return value


def _keys(value, expected, code, message):
    _require(isinstance(value, dict) and set(value) == set(expected),
             "invalid", code, message)


def _canonical(value):
    try:
        return inspector.canonical_bytes(value)
    except inspector.InspectionFailure as failure:
        _fail("invalid", failure.code, failure.message)


def _digest(document, field, label):
    _require(isinstance(document, dict), "invalid", "invalid-%s" % label,
             "%s must be an object" % label)
    value = document.get(field)
    unsigned = dict(document)
    unsigned.pop(field, None)
    _require(isinstance(value, str)
             and inspector.SHA256_RE.fullmatch(value) is not None
             and value == inspector.sha256(_canonical(unsigned)),
             "invalid", "%s-digest-mismatch" % label,
             "%s digest is invalid" % label)


def _record(data):
    return {
        "bytes_base64": base64.b64encode(data).decode("ascii"),
        "sha256": inspector.sha256(data),
        "size": len(data),
    }


def _decode_record(value, label):
    _keys(value, {"bytes_base64", "sha256", "size"}, "invalid-byte-record",
          "%s byte record is malformed" % label)
    _require(isinstance(value["sha256"], str)
             and inspector.SHA256_RE.fullmatch(value["sha256"]) is not None
             and type(value["size"]) is int and value["size"] >= 0,
             "invalid", "invalid-byte-record",
             "%s byte record identity is invalid" % label)
    try:
        data = base64.b64decode(value["bytes_base64"], validate=True)
    except (TypeError, ValueError):
        _fail("invalid", "invalid-byte-record",
              "%s bytes are not valid base64" % label)
    _require(len(data) == value["size"]
             and inspector.sha256(data) == value["sha256"],
             "invalid", "byte-record-mismatch",
             "%s bytes fail hash or size verification" % label)
    return data


def _read_input(path, label, limit, missing_ok=False):
    flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        before = os.lstat(str(path))
        descriptor = os.open(str(path), flags)
    except FileNotFoundError:
        if missing_ok:
            return None
        _fail("invalid", "%s-unreadable" % label,
              "%s is not a safe input file" % label)
    except OSError as error:
        code = "not-regular" if error.errno in (errno.ELOOP, errno.ENOTDIR) else "unreadable"
        _fail("invalid", "%s-%s" % (label, code), "%s is not a safe input file" % label)
    try:
        opened = os.fstat(descriptor)
        _require(stat.S_ISREG(before.st_mode) and stat.S_ISREG(opened.st_mode)
                 and before.st_dev == opened.st_dev and before.st_ino == opened.st_ino,
                 "invalid", "%s-not-regular" % label,
                 "%s is not a regular file" % label)
        chunks, size = [], 0
        while size <= limit:
            chunk = os.read(descriptor, min(1024 * 1024, limit + 1 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        data = b"".join(chunks)
    except OSError:
        _fail("invalid", "%s-unreadable" % label, "%s cannot be read" % label)
    finally:
        os.close(descriptor)
    try:
        after = os.lstat(str(path))
    except OSError:
        _fail("invalid", "%s-changed" % label, "%s changed during read" % label)
    _require(stat.S_ISREG(after.st_mode) and after.st_dev == opened.st_dev
             and after.st_ino == opened.st_ino and after.st_size == opened.st_size
             and after.st_mtime_ns == opened.st_mtime_ns,
             "invalid", "%s-changed" % label, "%s changed during read" % label)
    _require(len(data) <= limit, "invalid", "%s-too-large" % label,
             "%s exceeds the bounded input limit" % label)
    return data


def _load_canonical(path, label, limit=DOCUMENT_LIMIT):
    data = _read_input(pathlib.Path(path), label, limit)
    candidate = data[:-1] if data.endswith(b"\n") else data
    try:
        value = inspector.parse_json_object(candidate, label)
    except inspector.InspectionFailure as failure:
        _fail("invalid", failure.code, failure.message)
    _require(data == inspector.canonical_document(value), "invalid",
             "noncanonical-%s" % label, "%s is not canonical JSON" % label)
    return value


def _translate_authority(action, status="invalid", prefix="target"):
    try:
        return action()
    except authority.AuthorityFailure as failure:
        _fail(status, "%s-%s" % (prefix, failure.code), failure.message)


def _validate_checkpoint(checkpoint, root=None):
    _translate_authority(lambda: authority.validate_checkpoint(checkpoint))
    if root is None:
        return None
    return _translate_authority(lambda: authority.verify_checkpoint(root, checkpoint))


def _target_record(checkpoint):
    return _record(_canonical(checkpoint["pointer"]))


def _authority_context(checkpoint, state):
    return {
        "checkpoint_sha256": checkpoint["checkpoint_sha256"],
        "authority": checkpoint["authority"],
        "state_sha256": checkpoint["state_sha256"],
        "family_run_id": state["family_run_id"],
        "generation": state["generation"],
        "phase": state["phase"],
        "policy_state_binding": state["policy_state_binding"],
        "supervision_policy_binding": state["supervision_policy_binding"],
    }


def _validate_source_for_issue(source, target_data, issue):
    if source == {
        "status": "missing", "code": "orchestration-pointer-missing",
    }:
        return None
    _keys(source, {"status", "code", "pointer"}, "invalid-source-observation",
          "Source observation schema is invalid")
    data = _decode_record(source["pointer"], "source pointer")
    if source["status"] == "resolved" and source["code"] == "target-present":
        _require(data == target_data, "invalid", "source-target-mismatch",
                 "Resolved source must be the exact repair target")
        _translate_authority(lambda: authority.parse_pointer(data, issue),
                             prefix="source")
        return data
    _require(source["status"] == "corrupt",
             "invalid", "invalid-source-observation",
             "Only exact missing, malformed-v1, or target-present sources are supported")
    try:
        authority.parse_pointer(data, issue)
    except authority.AuthorityFailure as failure:
        _require(
            {"status": failure.status, "code": failure.code}
            == {"status": source["status"], "code": source["code"]},
            "invalid", "source-failure-mismatch",
            "Source pointer failure does not match its exact bytes",
        )
        _require(len(data) <= authority.POINTER_LIMIT, "denied",
                 "oversized-source-not-repairable",
                 "An oversized pointer cannot be captured exactly by this repair")
        return data
    _fail("denied", "unsafe-rollback",
          "A valid non-target pointer cannot be replaced by repair")


def _legacy_absent(root, issue):
    try:
        inspector.inspect(root, issue)
    except inspector.InspectionFailure as failure:
        if failure.code == "issue-pointer-missing":
            return
        _fail("denied", "legacy-authority-ambiguous",
              "Legacy authority is not proven absent: %s" % failure.code)
    _fail("denied", "legacy-authority-owned",
          "Active legacy authority forbids orchestration pointer repair")


def _safe_issue_directory(store, issue, create=False):
    root = store.store_dir
    path = root / "orchestration" / "issues" / str(issue)
    for part in (root, root / "orchestration", root / "orchestration" / "issues", path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            if not create:
                break
            workflow_cas.ensure_directory(part, _fail)
            info = part.lstat()
        _require(stat.S_ISDIR(info.st_mode), "conflict",
                 "authority-directory-conflict",
                 "Orchestration authority directory is unsafe")
    return path


def _read_pointer(path):
    flags = os.O_RDONLY | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        before = os.lstat(str(path))
    except FileNotFoundError:
        return None
    except OSError:
        _fail("conflict", "pointer-unreadable",
              "Orchestration pointer cannot be inspected")
    _require(stat.S_ISREG(before.st_mode), "conflict", "pointer-not-regular",
             "Orchestration pointer is not a regular file")
    try:
        descriptor = os.open(str(path), flags)
    except FileNotFoundError:
        return None
    except OSError:
        _fail("conflict", "pointer-not-regular",
              "Orchestration pointer changed or is not regular")
    try:
        opened = os.fstat(descriptor)
        _require(stat.S_ISREG(opened.st_mode) and opened.st_dev == before.st_dev
                 and opened.st_ino == before.st_ino,
                 "conflict", "pointer-not-regular",
                 "Orchestration pointer changed or is not regular")
        data = os.read(descriptor, authority.POINTER_LIMIT + 1)
    except OSError:
        _fail("conflict", "pointer-unreadable",
              "Orchestration pointer cannot be read")
    finally:
        os.close(descriptor)
    try:
        after = os.lstat(str(path))
    except OSError:
        _fail("conflict", "pointer-changed",
              "Orchestration pointer changed during read")
    _require(stat.S_ISREG(after.st_mode) and after.st_dev == opened.st_dev
             and after.st_ino == opened.st_ino and after.st_size == opened.st_size
             and after.st_mtime_ns == opened.st_mtime_ns,
             "conflict", "pointer-changed",
             "Orchestration pointer changed during read")
    _require(len(data) <= authority.POINTER_LIMIT, "denied",
             "oversized-source-not-repairable",
             "Oversized pointer state is not repairable")
    return data


def _source_observation(root, issue, target_data):
    store = inspector.resolve_store(root)
    pointer_data = _read_pointer(
        _safe_issue_directory(store, issue) / "pointer.json")
    if pointer_data is None:
        return {"status": "missing", "code": "orchestration-pointer-missing"}
    record = _record(pointer_data)
    if pointer_data == target_data:
        return {"status": "resolved", "code": "target-present", "pointer": record}
    try:
        authority.parse_pointer(pointer_data, issue)
    except authority.AuthorityFailure as failure:
        _require(failure.status == "corrupt", "denied",
                 "source-not-repairable",
                 "Unsupported pointer formats are not repairable")
        return {"status": failure.status, "code": failure.code, "pointer": record}
    _fail("denied", "unsafe-rollback",
          "A valid non-target pointer cannot be replaced by repair")


def _request(request, issue, source, target, context):
    _keys(request, {
        "format", "issue", "operation", "operator", "reason", "confirmation",
        "selection", "quiescence", "source", "target_pointer",
        "authority_context",
    }, "invalid-request-schema", "Repair request schema is invalid")
    _require(request["format"] == REQUEST_FORMAT and request["issue"] == issue,
             "invalid", "invalid-request-identity",
             "Repair request issue or format is invalid")
    _require(request["operation"] == {"type": OPERATION}, "denied",
             "unsupported-operation",
             "Only exact checkpoint restoration is supported")
    _require(all(isinstance(request[field], str) and request[field].strip()
                 for field in ("operator", "reason")),
             "denied", "missing-authorization",
             "Operator and reason must be nonempty")
    _require(all(len(request[field].encode("utf-8")) <= AUTHORIZATION_TEXT_LIMIT
                 for field in ("operator", "reason")),
             "denied", "authorization-too-large",
             "Operator and reason must fit the bounded authorization record")
    _require(request["confirmation"] == CONFIRMATION.format(issue=issue)
             and request["selection"] == SELECTION
             and request["quiescence"] == QUIESCENCE,
             "denied", "authorization-mismatch",
             "Exact repair authorization and quiescence confirmations are required")
    _require(request["source"] == source, "stale", "source-observation-mismatch",
             "Authorized source observation does not match the live pointer")
    _require(request["target_pointer"] == target, "denied",
             "target-authorization-mismatch",
             "Authorization does not bind the exact target pointer bytes")
    _require(request["authority_context"] == context, "denied",
             "authority-context-mismatch",
             "Authorization does not bind the verified authority context")
    return {
        key: request[key] for key in (
            "operator", "reason", "confirmation", "selection", "quiescence",
            "source", "target_pointer", "authority_context",
        )
    }


def _plan(issue, source, target, checkpoint):
    return {
        "issue": issue,
        "operation": OPERATION,
        "source": source,
        "target_pointer_sha256": target["sha256"],
        "target_checkpoint_sha256": checkpoint["checkpoint_sha256"],
        "commit_point": "atomic-orchestration-pointer-replace",
        "journal": "durable-singleton-before-commit",
        "receipt": "immutable-content-addressed-after-verification",
    }


def prepare(root, issue, checkpoint_file, request_file):
    issue = _issue(issue)
    checkpoint = _load_canonical(
        checkpoint_file, "checkpoint", authority.CHECKPOINT_LIMIT + 1)
    verified = _validate_checkpoint(checkpoint, root)
    _require(checkpoint["issue"] == issue, "invalid",
             "checkpoint-issue-mismatch",
             "Checkpoint issue does not match the requested issue")
    _legacy_absent(root, issue)
    target = _target_record(checkpoint)
    state = verified["state"]
    context = _authority_context(checkpoint, state)
    source = _source_observation(root, issue, _decode_record(target, "target pointer"))
    request = _load_canonical(request_file, "request", REQUEST_LIMIT + 1)
    authorization = _request(request, issue, source, target, context)
    bundle = {
        "format": BUNDLE_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "issue": issue,
        "operation": {"type": OPERATION},
        "authorization": authorization,
        "checkpoint": checkpoint,
        "source": source,
        "target": {
            "pointer": target,
            "checkpoint_sha256": checkpoint["checkpoint_sha256"],
            "authority": checkpoint["authority"],
        },
        "plan": _plan(issue, source, target, checkpoint),
    }
    bundle["bundle_sha256"] = inspector.sha256(_canonical(bundle))
    validate_bundle(bundle, root)
    return bundle


def validate_bundle(bundle, root=None):
    _require(isinstance(bundle, dict) and len(_canonical(bundle)) <= DOCUMENT_LIMIT,
             "invalid", "invalid-bundle", "Repair bundle is malformed or too large")
    _keys(bundle, {
        "format", "canonicalization", "issue", "operation", "authorization",
        "checkpoint", "source", "target", "plan", "bundle_sha256",
    }, "invalid-bundle-schema", "Repair bundle schema is invalid")
    _require(bundle["format"] == BUNDLE_FORMAT
             and bundle["canonicalization"] == inspector.CANONICALIZATION,
             "invalid", "invalid-bundle-format",
             "Repair bundle format is unsupported")
    _digest(bundle, "bundle_sha256", "bundle")
    issue = _issue(bundle["issue"])
    _require(bundle["operation"] == {"type": OPERATION}, "denied",
             "unsupported-operation",
             "Only exact checkpoint restoration is supported")
    checkpoint = bundle["checkpoint"]
    verified = _validate_checkpoint(checkpoint, root)
    _require(checkpoint["issue"] == issue, "invalid", "bundle-issue-mismatch",
             "Bundle and checkpoint issues differ")
    target = bundle["target"]
    _keys(target, {"pointer", "checkpoint_sha256", "authority"},
          "invalid-target", "Repair target is malformed")
    target_data = _decode_record(target["pointer"], "target pointer")
    _require(target["pointer"] == _target_record(checkpoint)
             and target_data == _canonical(checkpoint["pointer"])
             and target["checkpoint_sha256"] == checkpoint["checkpoint_sha256"]
             and target["authority"] == checkpoint["authority"],
             "invalid", "target-checkpoint-mismatch",
             "Target pointer does not exactly match the checkpoint")
    source_data = _validate_source_for_issue(bundle["source"], target_data, issue)
    authorization = bundle["authorization"]
    _keys(authorization, {
        "operator", "reason", "confirmation", "selection", "quiescence",
        "source", "target_pointer", "authority_context",
    }, "invalid-authorization", "Repair authorization is malformed")
    _require(all(isinstance(authorization[field], str)
                 and authorization[field].strip()
                 for field in ("operator", "reason")),
             "denied", "missing-authorization",
             "Operator and reason must be nonempty")
    _require(all(len(authorization[field].encode("utf-8"))
                 <= AUTHORIZATION_TEXT_LIMIT
                 for field in ("operator", "reason")),
             "denied", "authorization-too-large",
             "Operator and reason must fit the bounded authorization record")
    _require(authorization["confirmation"] == CONFIRMATION.format(issue=issue)
             and authorization["selection"] == SELECTION
             and authorization["quiescence"] == QUIESCENCE
             and authorization["source"] == bundle["source"]
             and authorization["target_pointer"] == target["pointer"],
             "denied", "authorization-mismatch",
             "Authorization does not bind this exact repair")
    if root is not None:
        _legacy_absent(root, issue)
        context = _authority_context(checkpoint, verified["state"])
        _require(authorization["authority_context"] == context, "denied",
                 "authority-context-mismatch",
                 "Authorization does not bind the verified authority context")
    else:
        context = authorization["authority_context"]
        _keys(context, {
            "checkpoint_sha256", "authority", "state_sha256", "family_run_id",
            "generation", "phase", "policy_state_binding",
            "supervision_policy_binding",
        }, "invalid-authority-context", "Authority context is malformed")
        _require(context["checkpoint_sha256"] == checkpoint["checkpoint_sha256"]
                 and context["authority"] == checkpoint["authority"]
                 and context["state_sha256"] == checkpoint["state_sha256"],
                 "denied", "authority-context-mismatch",
                 "Authority context does not bind the checkpoint")
    expected_plan = _plan(issue, bundle["source"], target["pointer"], checkpoint)
    _require(bundle["plan"] == expected_plan, "invalid", "plan-mismatch",
             "Repair plan is inconsistent")
    return {"source_data": source_data, "target_data": target_data}


def result_document(outcome, bundle=None, receipt=None):
    result = {
        "format": RESULT_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "outcome": outcome,
    }
    if bundle is not None:
        result.update({
            "issue": bundle["issue"],
            "bundle_sha256": bundle["bundle_sha256"],
            "plan": bundle["plan"],
            "target": {
                "checkpoint_sha256": bundle["checkpoint"]["checkpoint_sha256"],
                "authority": bundle["checkpoint"]["authority"],
            },
        })
    if receipt is not None:
        result["receipt"] = receipt
    result["result_sha256"] = inspector.sha256(_canonical(result))
    return result


def _classify(current, decoded):
    if current == decoded["target_data"]:
        return "target"
    if current == decoded["source_data"]:
        return "source"
    return "conflict"


def _verify_live_target(root, bundle):
    actual = _translate_authority(
        lambda: authority.checkpoint(root, bundle["issue"]),
        status="conflict", prefix="postcondition",
    )
    _require(_canonical(actual) == _canonical(bundle["checkpoint"]),
             "conflict", "postcondition-mismatch",
             "Published pointer does not select the authorized checkpoint")


def dry_run(root, bundle):
    decoded = validate_bundle(bundle, root)
    store = inspector.resolve_store(root)
    current = _read_pointer(
        _safe_issue_directory(store, bundle["issue"]) / "pointer.json")
    classification = _classify(current, decoded)
    _require(classification != "conflict", "stale",
             "source-preconditions-stale",
             "Live pointer is neither the authorized source nor target")
    if classification == "target":
        _verify_live_target(root, bundle)
    code = "target-already-applied" if classification == "target" else "source-applicable"
    return result_document({"status": "resolved", "code": code}, bundle)


def _journal_document(bundle):
    journal = {
        "format": JOURNAL_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "bundle": bundle,
    }
    journal["journal_sha256"] = inspector.sha256(_canonical(journal))
    return journal


def _validate_journal(data):
    try:
        journal = inspector.parse_json_object(data, "orchestration repair journal")
    except inspector.InspectionFailure as failure:
        _fail("conflict", "malformed-journal", failure.message)
    _require(data == _canonical(journal), "conflict", "malformed-journal",
             "Repair journal is not canonical")
    _require(
        isinstance(journal, dict)
        and set(journal)
        == {"format", "canonicalization", "bundle", "journal_sha256"},
        "conflict", "malformed-journal", "Repair journal schema is invalid")
    _require(journal["format"] == JOURNAL_FORMAT
             and journal["canonicalization"] == inspector.CANONICALIZATION,
             "conflict", "malformed-journal",
             "Repair journal format is unsupported")
    try:
        _digest(journal, "journal_sha256", "journal")
        validate_bundle(journal["bundle"])
    except OrchestrationRepairFailure:
        _fail("conflict", "malformed-journal",
              "Repair journal or embedded bundle is invalid")
    return journal["bundle"]


def _read_journal(path):
    try:
        data = _read_input(path, "journal", JOURNAL_LIMIT, missing_ok=True)
    except OrchestrationRepairFailure as failure:
        if failure.code.startswith("journal-"):
            _fail("conflict", failure.code, failure.message)
        raise
    return data


def _publish_singleton(path, data):
    workflow_cas.publish_immutable(
        path, data, _fail, temporary_label="orchestration-repair")


def _receipt(store, bundle):
    receipt = {
        "format": RECEIPT_FORMAT,
        "canonicalization": inspector.CANONICALIZATION,
        "outcome": {"status": "applied", "code": "target-verified"},
        "issue": bundle["issue"],
        "operation": OPERATION,
        "bundle_sha256": bundle["bundle_sha256"],
        "source": bundle["source"],
        "target_pointer_sha256": bundle["target"]["pointer"]["sha256"],
        "target_checkpoint_sha256": bundle["checkpoint"]["checkpoint_sha256"],
        "authorization": bundle["authorization"],
    }
    receipt["receipt_sha256"] = inspector.sha256(_canonical(receipt))
    data = _canonical(receipt)
    digest = inspector.sha256(data)
    path = (store.store_dir / "orchestration-repair-receipts" / "sha256"
            / digest[:2] / digest[2:])
    return data, path, {"sha256": digest, "size": len(data)}


def _lock(store, issue):
    directory = _safe_issue_directory(store, issue, create=True)
    path = directory / "authority.lock"
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(str(path), flags, 0o600)
        _require(stat.S_ISREG(os.fstat(descriptor).st_mode), "conflict",
                 "authority-lock-conflict", "Authority lock is not regular")
        fcntl.flock(descriptor, fcntl.LOCK_EX)
    except OrchestrationRepairFailure:
        if "descriptor" in locals():
            os.close(descriptor)
        raise
    except OSError:
        if "descriptor" in locals():
            os.close(descriptor)
        _fail("conflict", "authority-lock-conflict",
              "Authority lock failed closed")
    return descriptor, directory


def _cleanup_repair_temporaries(directory):
    try:
        entries = list(directory.iterdir())
        repair = [path for path in entries
                  if path.name.startswith(
                      (REPAIR_TEMPORARY_PREFIX, JOURNAL_TEMPORARY_PREFIX))]
        authority_pending = [path for path in entries
                             if path.name.startswith(AUTHORITY_TEMPORARY_PREFIX)]
        _require(not authority_pending, "conflict",
                 "interrupted-authority-commit",
                 "A known authority commit temporary requires its exact commit bundle")
        _require(len(repair) <= STALE_TEMPORARY_LIMIT, "conflict",
                 "repair-temporary-conflict",
                 "Too many stale repair temporaries")
        _require(all(stat.S_ISREG(path.lstat().st_mode) for path in repair),
                 "conflict", "repair-temporary-conflict",
                 "A repair temporary is not regular")
        for path in repair:
            path.unlink()
        if repair:
            workflow_cas.fsync_directory(directory)
    except OrchestrationRepairFailure:
        raise
    except OSError:
        _fail("conflict", "repair-temporary-conflict",
              "Repair temporaries changed or cannot be removed")


def _replace_pointer(path, source_data, target_data, operation_id):
    temporary = path.parent / ("%s%s-%s-%s" % (
        REPAIR_TEMPORARY_PREFIX, os.getpid(), threading.get_ident(),
        operation_id[:16]))
    descriptor, replaced = None, False
    try:
        descriptor = os.open(
            str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        workflow_cas.write_all(descriptor, target_data)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        current = _read_pointer(path)
        _require(current == source_data, "conflict", "pointer-source-mismatch",
                 "Pointer no longer matches the authorized source")
        os.replace(str(temporary), str(path))
        replaced = True
        workflow_cas.fsync_directory(path.parent)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if not replaced:
            try:
                temporary.unlink()
                workflow_cas.fsync_directory(path.parent)
            except FileNotFoundError:
                pass


def _finish_locked(root, store, directory, bundle, journal_path):
    decoded = validate_bundle(bundle, root)
    pointer_path = directory / "pointer.json"
    current = _read_pointer(pointer_path)
    classification = _classify(current, decoded)
    _require(classification != "conflict", "conflict", "pointer-conflict",
             "Pointer is neither the journal source nor target")
    if classification == "source":
        _legacy_absent(root, bundle["issue"])
        phase_hook("before-target-verification")
        validate_bundle(bundle, root)
        phase_hook("after-target-verification")
        phase_hook("before-pointer-publication")
        _replace_pointer(
            pointer_path, decoded["source_data"], decoded["target_data"],
            bundle["bundle_sha256"])
        phase_hook("after-pointer-publication")
    phase_hook("before-postcommit-verification")
    _verify_live_target(root, bundle)
    phase_hook("after-postcommit-verification")
    receipt_data, receipt_path, receipt_ref = _receipt(store, bundle)
    phase_hook("before-receipt-publication")
    workflow_cas.publish_immutable(
        receipt_path, receipt_data, _fail,
        temporary_label="orchestration-repair-receipt")
    phase_hook("after-receipt-publication")
    phase_hook("before-journal-removal")
    try:
        journal_path.unlink()
    except FileNotFoundError:
        pass
    workflow_cas.fsync_directory(journal_path.parent)
    phase_hook("after-journal-removal")
    return result_document(
        {"status": "resolved", "code": "target-verified"},
        bundle, receipt_ref)


def apply_bundle(root, bundle):
    decoded = validate_bundle(bundle, root)
    store = inspector.resolve_store(root)
    descriptor, directory = _lock(store, bundle["issue"])
    try:
        _cleanup_repair_temporaries(directory)
        journal_path = directory / "repair-journal.json"
        journal_data = _read_journal(journal_path)
        if journal_data is not None:
            existing = _validate_journal(journal_data)
            _require(existing["issue"] == bundle["issue"], "conflict",
                     "journal-issue-mismatch",
                     "Repair journal belongs to another issue")
            result = _finish_locked(
                root, store, directory, existing, journal_path)
            _require(existing["bundle_sha256"] == bundle["bundle_sha256"],
                     "conflict", "journal-operation-mismatch",
                     "A different repair journal was recovered")
            return result
        current = _read_pointer(directory / "pointer.json")
        classification = _classify(current, decoded)
        _require(classification != "conflict", "stale",
                 "source-preconditions-stale",
                 "Live pointer is neither the authorized source nor target")
        if classification == "source":
            validate_bundle(bundle, root)
            journal = _journal_document(bundle)
            phase_hook("before-journal-publication")
            _publish_singleton(journal_path, _canonical(journal))
            phase_hook("after-journal-publication")
        return _finish_locked(root, store, directory, bundle, journal_path)
    finally:
        os.close(descriptor)


def recover(root, issue):
    issue = _issue(issue)
    store = inspector.resolve_store(root)
    descriptor, directory = _lock(store, issue)
    try:
        _cleanup_repair_temporaries(directory)
        journal_path = directory / "repair-journal.json"
        data = _read_journal(journal_path)
        if data is None:
            return result_document({"status": "resolved", "code": "no-journal"})
        bundle = _validate_journal(data)
        _require(bundle["issue"] == issue, "conflict",
                 "journal-issue-mismatch",
                 "Repair journal belongs to another issue")
        return _finish_locked(root, store, directory, bundle, journal_path)
    finally:
        os.close(descriptor)


class RepairArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("invalid", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = RepairArgumentParser(description=__doc__)
    commands = parser.add_subparsers(
        dest="command", required=True, parser_class=RepairArgumentParser)
    command = commands.add_parser("prepare")
    command.add_argument("issue", type=int)
    command.add_argument("--root", required=True)
    command.add_argument("--checkpoint", required=True)
    command.add_argument("--request", required=True)
    for name in ("dry-run", "apply"):
        command = commands.add_parser(name)
        command.add_argument("--root", required=True)
        command.add_argument("--bundle", required=True)
    command = commands.add_parser("recover")
    command.add_argument("issue", type=int)
    command.add_argument("--root", required=True)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        root = pathlib.Path(args.root)
        if args.command == "prepare":
            document = prepare(
                root, args.issue, args.checkpoint, args.request)
        elif args.command == "dry-run":
            document = dry_run(
                root, _load_canonical(
                    args.bundle, "bundle", DOCUMENT_LIMIT + 1))
        elif args.command == "apply":
            document = apply_bundle(
                root, _load_canonical(
                    args.bundle, "bundle", DOCUMENT_LIMIT + 1))
        else:
            document = recover(root, args.issue)
        sys.stdout.buffer.write(inspector.canonical_document(document))
        return 0
    except OrchestrationRepairFailure as failure:
        sys.stdout.buffer.write(
            inspector.canonical_document(failure.document()))
        return EXIT_CODES[failure.status]
    except (authority.AuthorityFailure,
            inspector.InspectionFailure) as failure:
        wrapped = OrchestrationRepairFailure(
            "conflict", failure.code, failure.message)
        sys.stdout.buffer.write(
            inspector.canonical_document(wrapped.document()))
        return EXIT_CODES[wrapped.status]
    except OSError:
        failure = OrchestrationRepairFailure(
            "conflict", "filesystem-operation-failed",
            "Filesystem operation failed closed")
        sys.stdout.buffer.write(
            inspector.canonical_document(failure.document()))
        return EXIT_CODES[failure.status]


if __name__ == "__main__":
    raise SystemExit(main())
