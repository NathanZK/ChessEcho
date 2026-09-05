#!/usr/bin/env python3
"""Expected-tip authority selector for inactive orchestration state."""

import argparse
import base64
import fcntl
import os
import pathlib
import re
import stat
import sys
import threading

try:
    import workflow_cas
    import workflow_evidence
    import workflow_inspector
except ModuleNotFoundError:
    from scripts import workflow_cas
    from scripts import workflow_evidence
    from scripts import workflow_inspector

POINTER_FORMAT, STATE_FORMAT = ("chess-echo-orchestration-pointer-v1",
                                "chess-echo-orchestration-state-v1")
BUNDLE_FORMAT = "chess-echo-orchestration-authority-bundle-v1"
INSPECTION_FORMAT, CHECKPOINT_FORMAT = ("chess-echo-orchestration-inspection-v1",
                                        "chess-echo-orchestration-checkpoint-v1")
RESULT_FORMAT = "chess-echo-orchestration-authority-result-v1"
CANONICALIZATION = "utf8-json-sort-keys-compact-ascii-v1"
POINTER_LIMIT, STATE_LIMIT, BUNDLE_LIMIT = 4 * 1024, 2 * 1024 * 1024, 16 * 1024
CHECKPOINT_LIMIT, CHAIN_LIMIT, STALE_TEMPORARY_LIMIT = 4 * 1024 * 1024, 10_000, 1_024
SHA256_RE, RUN_ID_RE, TEMPORARY_PREFIX = re.compile(r"[0-9a-f]{64}"), re.compile(r"[0-9a-f]{32}"), ".pointer.json.authority-"
OUTCOME_EXIT_CODES = {
    "resolved": 0, "missing": 3, "unsupported": 4, "corrupt": 5,
    "ambiguous": 6, "stale": 7, "conflict": 10,
}
IMPLEMENTATION_PHASES = frozenset(
    """INTAKE PLANNING PLAN_REVIEW WAITING_FOR_PLAN_APPROVAL TEST_IMPLEMENTATION
    TEST_REVIEW WAITING_FOR_TEST_APPROVAL IMPLEMENTATION VALIDATION FINAL_REVIEW
    PR_PREPARATION WAITING_FOR_FINAL_APPROVAL COMPLETED PAUSED""".split()
)
CANDIDATE_SLOTS = frozenset(
    """plan-snapshot plan-revision plan-review test-manifest test-review
    implementation-report validation final-review pr-metadata artifact artifact-review
    documentation-content-check documentation-diff-check human-challenge
    human-authorization cutover-authorization revocation-intent execution-request
    execution-result""".split()
)
PENDING_KINDS = frozenset("agent validation git-read github-read github-write human".split())
PENDING_STATUSES = frozenset(("requested", "cancel-requested"))
TRANSITION_TYPES = frozenset(
    """initialize classify plan-request plan-review plan-revision plan-approve plan-reject
    plan-revoke plan-reopen tests-request tests-review tests-approve tests-reject
    tests-revoke tests-reopen implementation-request implementation-submit
    cause-establish fix-identify fix-apply implementation-replace targeted-verify
    validation-request validation-record final-review pr-prepare pr-reconcile
    final-approve final-reject final-revoke artifact-request artifact-review
    artifact-accept artifact-reject artifact-revoke cancel-request abandon pause
    recover cutover complete""".split()
)
class AuthorityFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status, self.code, self.message, self.subject = status, code, message, subject

    def document(self):
        outcome = {"status": self.status, "code": self.code, "message": self.message}
        if self.subject is not None:
            outcome["subject"] = self.subject
        return {"format": RESULT_FORMAT, "outcome": outcome}
def phase_hook(_stage):  # Test seam for commit interruption.
    pass
def _fail(status, code, message, subject=None):
    raise AuthorityFailure(status, code, message, subject)
def _require(condition, status, code, message, subject=None):
    if not condition:
        _fail(status, code, message, subject)
def _keys(value, expected, label, code=None):
    _require(isinstance(value, dict) and set(value) == set(expected), "corrupt",
             code or "invalid-%s-schema" % label, "%s schema is invalid" % label)
def _canonical(value):
    try:
        return workflow_inspector.canonical_bytes(value)
    except workflow_inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except (TypeError, ValueError) as failure:
        _fail("corrupt", "invalid-authority-json", "Invalid canonical JSON: %s" % failure)
def _issue(value):
    _require(type(value) is int and value > 0, "corrupt", "invalid-authority-issue",
             "Issue number must be positive")
    return value
def _reference(value, label="binding"):
    _keys(value, {"kind", "sha256", "size"}, "%s-reference" % label,
          "authority-binding-invalid")
    try:
        return workflow_inspector.validate_reference(value, "evidence-binding")
    except workflow_inspector.InspectionFailure as failure:
        _fail(failure.status, "authority-binding-invalid", "%s reference is invalid" % label,
              failure.subject)
def _record(data):
    return {"bytes_base64": base64.b64encode(data).decode("ascii"),
            "sha256": workflow_inspector.sha256(data), "size": len(data)}
def _decode_record(value, label):
    _keys(value, {"bytes_base64", "sha256", "size"}, "%s-pointer-record" % label,
          "authority-bundle-invalid")
    valid = (isinstance(value["sha256"], str)
             and SHA256_RE.fullmatch(value["sha256"]) is not None
             and type(value["size"]) is int and value["size"] >= 0)
    _require(valid, "corrupt", "authority-bundle-invalid",
             "%s pointer record hash or size is invalid" % label)
    try:
        data = base64.b64decode(value["bytes_base64"], validate=True)
    except (TypeError, ValueError):
        _fail("corrupt", "authority-bundle-invalid",
              "%s pointer record is not valid base64" % label)
    _require(len(data) == value["size"] and workflow_inspector.sha256(data) == value["sha256"],
             "corrupt", "authority-bundle-invalid",
             "%s pointer record does not match its bytes" % label)
    return data
def _store(root):
    try:
        return workflow_inspector.resolve_store(root)
    except workflow_inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except (OSError, RuntimeError, TypeError, ValueError) as failure:
        _fail("missing", "authority-root-unreadable", "Authority root cannot be resolved: %s" % failure)
def _safe_directory(store, issue, create=False):
    root = store.store_dir
    path = root / "orchestration" / "issues" / str(_issue(issue))
    parts = (root, root / "orchestration", root / "orchestration" / "issues", path)
    for part in parts:
        try:
            info = part.lstat()
        except FileNotFoundError:
            if not create:
                break
            workflow_cas.ensure_directory(part, _cas_fail)
            info = part.lstat()
        _require(stat.S_ISDIR(info.st_mode), "conflict", "authority-directory-conflict", "Authority directory is unsafe")
    return path
def _pointer_path(store, issue):
    return _safe_directory(store, issue) / "pointer.json"
def _read_regular(path, missing_ok=False):
    try:
        before = os.lstat(str(path))
    except FileNotFoundError:
        if missing_ok:
            return None
        _fail("missing", "orchestration-pointer-missing",
              "Issue has no orchestration authority pointer")
    except OSError:
        _fail("conflict", "pointer-unreadable", "Orchestration pointer cannot be inspected")
    _require(stat.S_ISREG(before.st_mode), "conflict", "pointer-not-regular", "Pointer is not regular")
    try:
        descriptor = os.open(str(path), os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except FileNotFoundError:
        if missing_ok:
            return None
        _fail("missing", "orchestration-pointer-missing",
              "Issue has no orchestration authority pointer")
    except OSError:
        _fail("conflict", "pointer-not-regular", "Pointer changed or is not regular")
    try:
        opened = os.fstat(descriptor)
        same = (stat.S_ISREG(opened.st_mode) and opened.st_dev == before.st_dev
                and opened.st_ino == before.st_ino)
        _require(same, "conflict", "pointer-not-regular", "Pointer changed or is not regular")
        data = os.read(descriptor, POINTER_LIMIT + 1)
    except OSError:
        _fail("conflict", "pointer-unreadable", "Orchestration pointer cannot be read")
    finally:
        os.close(descriptor)
    try:
        after = os.lstat(str(path))
    except OSError:
        _fail("conflict", "pointer-source-mismatch", "Pointer changed during read")
    unchanged = (stat.S_ISREG(after.st_mode) and after.st_dev == opened.st_dev
                 and after.st_ino == opened.st_ino and after.st_size == opened.st_size
                 and after.st_mtime_ns == opened.st_mtime_ns)
    _require(unchanged, "conflict", "pointer-source-mismatch", "Pointer changed during read")
    return data
def _parse_pointer(data, issue):
    _require(len(data) <= POINTER_LIMIT, "unsupported", "orchestration-pointer-too-large", "Pointer exceeds 4 KiB")
    try:
        pointer = workflow_inspector.parse_json_object(data, "orchestration pointer")
    except workflow_inspector.InspectionFailure:
        _fail("corrupt", "invalid-orchestration-pointer", "Pointer is not canonical JSON")
    _keys(pointer, {"format", "issue", "generation", "authority"},
          "orchestration-pointer", "invalid-orchestration-pointer")
    _require(pointer["format"] == POINTER_FORMAT, "unsupported",
             "unsupported-orchestration-pointer", "Pointer format is unsupported")
    valid = (type(pointer["issue"]) is int and pointer["issue"] == issue
             and type(pointer["generation"]) is int and pointer["generation"] >= 0)
    _require(valid, "corrupt", "invalid-orchestration-pointer", "Pointer identity is invalid")
    pointer["authority"] = _reference(pointer["authority"], "authority")
    _require(_canonical(pointer) == data, "corrupt", "invalid-orchestration-pointer", "Pointer is noncanonical")
    return pointer
def _read_pointer(store, issue, missing_ok=False):
    data = _read_regular(_pointer_path(store, issue), missing_ok)
    return (None, None) if data is None else (data, _parse_pointer(data, issue))
def _binding_failure(failure, message="Evidence binding is invalid"):
    status = getattr(failure, "status", "corrupt")
    _fail(status if status in OUTCOME_EXIT_CODES else "corrupt",
          "authority-binding-invalid", message, getattr(failure, "subject", None))
def _projection(root, reference, issue, family, cache):
    reference = _reference(reference)
    key = (reference["kind"], reference["sha256"], reference["size"])
    if key not in cache:
        try:
            workflow_evidence.verify(root, reference)
            cache[key] = workflow_evidence.project(root, reference)
        except (workflow_evidence.EvidenceFailure,
                workflow_inspector.InspectionFailure, OSError) as failure:
            _binding_failure(failure)
    identity = cache[key].get("identity", {})
    valid = (identity.get("issue") == issue
             and (family is None or identity.get("family_run_id") == family))
    _require(valid, "stale", "authority-lineage-stale", "Binding issue or family is stale", reference["sha256"])
    return cache[key]
def _related_projection(root, reference, state, cache, subject=None):
    projected = _projection(
        root, reference, state["issue"], state["family_run_id"], cache)
    identity = projected["identity"]
    generation = identity.get("run_generation")
    valid = (identity.get("correction") is None and type(generation) is int
             and 0 <= generation <= state["generation"]
             and identity.get("sequence") == generation + 1
             and projected["lineage"] == {"status": "original", "parent_binding": None}
             and projected["migration"] is None
             and (subject is None or projected["subject"] == subject))
    _require(valid, "stale", "authority-lineage-stale", "Related binding is stale")
    return projected
def _state_shape(state, issue, generation):
    _keys(state, {
        "format", "issue", "family_run_id", "generation", "previous_authority",
        "previous_pointer_sha256", "route", "phase", "triage_binding",
        "policy_state_binding", "candidates", "pending", "cutover", "transition",
        "state_sha256",
    }, "orchestration-state", "authority-binding-invalid")
    _require(state["format"] == STATE_FORMAT, "unsupported", "authority-binding-invalid", "State format is unsupported")
    valid = (type(state["issue"]) is int and state["issue"] == issue
             and isinstance(state["family_run_id"], str)
             and RUN_ID_RE.fullmatch(state["family_run_id"]) is not None
             and type(state["generation"]) is int and state["generation"] == generation)
    _require(valid, "stale", "authority-lineage-stale", "State identity or generation is stale")
    _require(state["route"] == "implementation", "unsupported", "unsupported-route-not-activated", "Only implementation is activated")
    _require(isinstance(state["phase"], str) and state["phase"] in IMPLEMENTATION_PHASES, "unsupported", "authority-binding-invalid", "Phase is unsupported")
    state["triage_binding"] = _reference(state["triage_binding"], "triage")
    state["policy_state_binding"] = _reference(state["policy_state_binding"], "policy-state")
    _require(isinstance(state["candidates"], list), "corrupt", "authority-binding-invalid", "Candidates must be a list")
    normalized = []
    for item in state["candidates"]:
        _keys(item, {"slot", "binding"}, "candidate", "authority-binding-invalid")
        _require(isinstance(item["slot"], str) and item["slot"] in CANDIDATE_SLOTS, "unsupported", "authority-binding-invalid", "Candidate slot is unsupported")
        normalized.append({"slot": item["slot"],
                           "binding": _reference(item["binding"], "candidate")})
    slots = [item["slot"] for item in normalized]
    _require(slots == sorted(slots, key=lambda item: item.encode("utf-8")), "ambiguous", "authority-binding-invalid", "Candidates are not sorted")
    _require(len(slots) == len(set(slots)), "ambiguous", "authority-binding-invalid", "Candidate slots are duplicated")
    state["candidates"] = normalized
    pending = state["pending"]
    if pending is not None:
        _keys(pending, {"attempt_id", "kind", "request_binding", "status"},
              "pending-request", "authority-binding-invalid")
        valid = (isinstance(pending["attempt_id"], str)
                 and SHA256_RE.fullmatch(pending["attempt_id"]) is not None
                 and isinstance(pending["kind"], str) and pending["kind"] in PENDING_KINDS
                 and isinstance(pending["status"], str) and pending["status"] in PENDING_STATUSES)
        _require(valid, "corrupt", "authority-binding-invalid", "Pending request identity is invalid")
        pending["request_binding"] = _reference(
            pending["request_binding"], "pending-request")
    cutover = state["cutover"]
    _keys(cutover, {"mode", "legacy_checkpoint_sha256", "migration_binding"},
          "cutover", "authority-binding-invalid")
    _require(cutover["mode"] == "new-run", "unsupported", "unsupported-cutover-not-activated", "Migrated-v4 is not activated")
    _require(cutover["legacy_checkpoint_sha256"] is None
             and cutover["migration_binding"] is None, "corrupt",
             "authority-binding-invalid", "New-run authority names migration evidence")
    transition = state["transition"]
    fields = {"type", "request_binding", "result_binding", "authorization_binding",
              "repository_observation_binding"}
    _keys(transition, fields, "transition", "authority-binding-invalid")
    _require(isinstance(transition["type"], str) and transition["type"] in TRANSITION_TYPES, "unsupported", "authority-binding-invalid", "Transition is unsupported")
    for field in fields - {"type"}:
        if transition[field] is not None:
            transition[field] = _reference(transition[field], field.replace("_", "-"))
    digest, unsigned = state["state_sha256"], dict(state)
    unsigned.pop("state_sha256")
    valid = (isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None
             and workflow_inspector.sha256(_canonical(unsigned)) == digest)
    _require(valid, "corrupt", "authority-binding-invalid", "State digest is invalid")
    return state
def _related(root, state, cache):
    issue, family = state["issue"], state["family_run_id"]
    policy = _projection(root, state["policy_state_binding"], issue, family, cache)
    _require(policy["decision"]["type"] == "policy-state", "corrupt", "authority-binding-invalid", "Policy decision is invalid")
    _projection(root, state["triage_binding"], issue, family, cache)
    for item in state["candidates"]:
        _related_projection(root, item["binding"], state, cache)
    if state["pending"] is not None:
        pending = state["pending"]
        _require(state["generation"] > 0, "stale", "authority-lineage-stale", "Genesis cannot have a pending request")
        decision = _related_projection(
            root, pending["request_binding"], state, cache,
            state["previous_authority"])["decision"]["type"]
        expected = "human-challenge" if pending["kind"] == "human" else "execution-request"
        _require(decision == expected, "stale", "authority-binding-invalid", "Pending request decision is invalid")
    for field, reference in state["transition"].items():
        if field != "type" and reference is not None:
            subject = (state["previous_authority"] if field == "request_binding"
                       else state["transition"]["request_binding"])
            _require(subject is not None, "stale", "authority-lineage-stale", "Transition has no request subject")
            _related_projection(root, reference, state, cache, subject)
def _preflight_state_size(root, issue, reference):
    try:
        reader = workflow_inspector.AuthorityReader(
            workflow_inspector.resolve_store(root), issue)
        binding = reader.read_json(reference, "evidence-binding")
        manifest = reader.read_json(binding["manifest"], "evidence-manifest")
    except (KeyError, TypeError, workflow_inspector.InspectionFailure) as failure:
        _binding_failure(failure, "Authority binding manifest is invalid")
    entries = manifest.get("entries")
    if isinstance(entries, list) and len(entries) == 1 and isinstance(entries[0], dict):
        size = entries[0].get("size")
        _require(type(size) is not int or size <= STATE_LIMIT, "unsupported", "orchestration-state-too-large", "State exceeds 2 MiB")
def _state_payload(root, issue, projection):
    entries = projection.get("entries")
    valid = (isinstance(entries, list) and len(entries) == 1
             and entries[0].get("path") == "workflow-orchestration/state.json"
             and entries[0].get("kind") == "regular"
             and entries[0].get("mode") == "100644")
    _require(valid, "corrupt", "authority-binding-invalid", "State payload entry is invalid")
    _require(entries[0]["size"] <= STATE_LIMIT, "unsupported", "orchestration-state-too-large", "State exceeds 2 MiB")
    try:
        reader = workflow_inspector.AuthorityReader(
            workflow_inspector.resolve_store(root), issue)
        data = reader.read_bytes(entries[0]["payload"], "evidence-payload")
        state = workflow_inspector.parse_json_object(data, "orchestration state")
    except workflow_inspector.InspectionFailure as failure:
        _binding_failure(failure, "Orchestration state payload is invalid")
    _require(len(data) <= STATE_LIMIT, "unsupported", "orchestration-state-too-large", "State exceeds 2 MiB")
    _require(_canonical(state) == data, "corrupt", "authority-binding-invalid", "State is noncanonical")
    return data, state
def _state_binding(root, pointer, cache):
    reference = _reference(pointer["authority"], "authority")
    _preflight_state_size(root, pointer["issue"], reference)
    projected = _projection(root, reference, pointer["issue"], None, cache)
    identity, family = projected["identity"], projected["identity"].get("family_run_id")
    _require(isinstance(family, str) and RUN_ID_RE.fullmatch(family) is not None, "corrupt", "authority-binding-invalid", "Authority family is invalid")
    data, state = _state_payload(root, pointer["issue"], projected)
    state = _state_shape(state, pointer["issue"], pointer["generation"])
    expected_identity = {
        "issue": pointer["issue"],
        "run_id": workflow_inspector.sha256(b"orchestration-state-v1\0" + data)[:32],
        "family_run_id": family, "correction": None,
        "run_generation": pointer["generation"],
        "sequence": pointer["generation"] + 1,
        "event_tip": workflow_inspector.sha256(b"orchestration-tip-v1\0" + data),
    }
    _require(state["family_run_id"] == family, "stale", "authority-lineage-stale", "Authority families differ")
    _require(identity == expected_identity, "corrupt", "authority-binding-invalid", "Authority identity is invalid")
    expected_decision = {"type": "orchestration-state",
                         "id": "generation-%d" % pointer["generation"]}
    _require(projected["decision"] == expected_decision, "corrupt", "authority-binding-invalid", "Authority decision is invalid")
    _require(projected["migration"] is None, "unsupported", "authority-binding-invalid", "Migrated state is not activated")
    generation = pointer["generation"]
    if generation == 0:
        valid = (state["previous_authority"] is None
                 and state["previous_pointer_sha256"] is None
                 and projected["subject"] == state["policy_state_binding"]
                 and projected["lineage"] == {
                     "status": "original", "parent_binding": None,
                 })
    else:
        previous = _reference(state["previous_authority"], "previous-authority")
        prior = {"format": POINTER_FORMAT, "issue": pointer["issue"],
                 "generation": generation - 1, "authority": previous}
        valid = (projected["subject"] == previous
                 and projected["lineage"] == {
                     "status": "replacement", "parent_binding": previous,
                 }
                 and state["previous_pointer_sha256"]
                 == workflow_inspector.sha256(_canonical(prior)))
    _require(valid, "stale", "authority-lineage-stale", "Authority lineage is stale")
    _related(root, state, cache)
    return state
def _chain(root, pointer):
    rows, cache, current, tip = [], {}, pointer, None
    while True:
        _require(len(rows) < CHAIN_LIMIT, "unsupported", "authority-chain-limit", "Chain exceeds 10,000 generations")
        state = _state_binding(root, current, cache)
        tip = state if tip is None else tip
        rows.append({"generation": current["generation"], "binding": current["authority"],
                     "state_sha256": state["state_sha256"]})
        if current["generation"] == 0:
            break
        current = {"format": POINTER_FORMAT, "issue": current["issue"],
                   "generation": current["generation"] - 1,
                   "authority": state["previous_authority"]}
    rows.reverse()
    return tip, rows
def _inspection(root, issue):
    issue = _issue(issue)
    data, pointer = _read_pointer(_store(root), issue)
    state, chain = _chain(root, pointer)
    return {
        "format": INSPECTION_FORMAT, "canonicalization": CANONICALIZATION,
        "outcome": {"status": "resolved", "code": "verified"}, "issue": issue,
        "pointer_sha256": workflow_inspector.sha256(data), "pointer": pointer,
        "authority": pointer["authority"], "state_sha256": state["state_sha256"],
        "chain": chain, "chain_length": len(chain),
    }
def status(root, issue):
    return _inspection(root, issue)
def checkpoint(root, issue):
    document = _inspection(root, issue)
    document["format"] = CHECKPOINT_FORMAT
    document["checkpoint_sha256"] = workflow_inspector.sha256(_canonical(document))
    _require(len(_canonical(document)) <= CHECKPOINT_LIMIT, "unsupported", "authority-checkpoint-too-large", "Checkpoint exceeds 4 MiB")
    return document
def _target(issue, generation, candidate):
    pointer = {"format": POINTER_FORMAT, "issue": issue, "generation": generation,
               "authority": _reference(candidate, "candidate")}
    data = _canonical(pointer)
    _require(len(data) <= POINTER_LIMIT, "unsupported", "orchestration-pointer-too-large", "Pointer exceeds 4 KiB")
    return data, pointer
def _operation_id(issue, source_digest, target_digest, candidate):
    return workflow_inspector.sha256(_canonical({
        "issue": issue, "source_sha256": source_digest,
        "target_sha256": target_digest, "candidate_binding": candidate,
    }))
def prepare(root, issue, candidate_binding):
    issue, store = _issue(issue), _store(root)
    source_data, source = _read_pointer(store, issue, missing_ok=True)
    candidate = _reference(candidate_binding, "candidate")
    target_data, target = _target(
        issue, 0 if source is None else source["generation"] + 1, candidate)
    state, _rows = _chain(root, target)
    if source is None:
        valid = state["previous_authority"] is None
    else:
        valid = (state["previous_authority"] == source["authority"]
                 and state["previous_pointer_sha256"]
                 == workflow_inspector.sha256(source_data))
    _require(valid, "stale", "authority-lineage-stale", "Candidate does not extend current pointer")
    source_record, target_record = (
        None if source_data is None else _record(source_data), _record(target_data))
    bundle = {
        "format": BUNDLE_FORMAT, "issue": issue, "source": source_record,
        "target": target_record, "candidate_binding": candidate,
        "operation_id": _operation_id(
            issue, None if source_record is None else source_record["sha256"],
            target_record["sha256"], candidate),
    }
    bundle["bundle_sha256"] = workflow_inspector.sha256(_canonical(bundle))
    _require(len(_canonical(bundle)) <= BUNDLE_LIMIT, "unsupported", "authority-bundle-too-large", "Bundle exceeds 16 KiB")
    return bundle
def validate_bundle(bundle, root):
    _require(len(_canonical(bundle)) <= BUNDLE_LIMIT, "unsupported", "authority-bundle-too-large", "Bundle exceeds 16 KiB")
    _keys(bundle, {"format", "issue", "source", "target", "candidate_binding",
                   "operation_id", "bundle_sha256"},
          "authority-bundle", "authority-bundle-invalid")
    _require(bundle["format"] == BUNDLE_FORMAT, "unsupported",
             "authority-bundle-invalid", "Bundle format is unsupported")
    issue, candidate = _issue(bundle["issue"]), _reference(
        bundle["candidate_binding"], "candidate")
    source_data = None if bundle["source"] is None else _decode_record(
        bundle["source"], "source")
    target_data = _decode_record(bundle["target"], "target")
    source = None if source_data is None else _parse_pointer(source_data, issue)
    target = _parse_pointer(target_data, issue)
    valid = (target["authority"] == candidate
             and ((source is None and target["generation"] == 0)
                  or (source is not None
                      and target["generation"] == source["generation"] + 1)))
    _require(valid, "stale", "authority-lineage-stale",
             "Bundle source and target are not consecutive")
    operation_id = _operation_id(
        issue, None if source_data is None else workflow_inspector.sha256(source_data),
        workflow_inspector.sha256(target_data), candidate)
    unsigned = dict(bundle)
    unsigned.pop("bundle_sha256")
    _require(bundle["operation_id"] == operation_id, "corrupt", "authority-bundle-invalid", "Bundle operation ID is invalid")
    _require(isinstance(bundle["bundle_sha256"], str)
             and bundle["bundle_sha256"]
             == workflow_inspector.sha256(_canonical(unsigned)), "corrupt",
             "authority-bundle-invalid", "Bundle digest is invalid")
    state, _rows = _chain(root, target)
    if source is not None:
        valid = (state["previous_authority"] == source["authority"]
                 and state["previous_pointer_sha256"]
                 == workflow_inspector.sha256(source_data))
        _require(valid, "stale", "authority-lineage-stale",
                 "Bundle candidate does not extend its source")
    return {"issue": issue, "source_data": source_data, "source": source,
            "target_data": target_data, "target": target}
def _cas_fail(_status, code, _message):
    if code == "immutable-destination-not-regular":
        _fail("conflict", "pointer-not-regular", "Pointer is not a regular file")
    if code == "immutable-object-collision":
        _fail("conflict", "pointer-target-conflict",
              "Another pointer target won genesis publication")
    _fail("conflict", "authority-storage-conflict",
          "Authority storage operation failed closed")
def _lock(store, issue):
    descriptor = None
    try:
        directory = _safe_directory(store, issue, create=True)
        descriptor = os.open(
            str(directory / "authority.lock"),
            os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    except AuthorityFailure:
        raise
    except OSError:
        _fail("conflict", "authority-lock-conflict", "Authority lock is unsafe")
    try:
        _require(stat.S_ISREG(os.fstat(descriptor).st_mode), "conflict",
                 "authority-lock-conflict", "Authority lock is not regular")
        fcntl.flock(descriptor, fcntl.LOCK_EX)
    except OSError:
        if descriptor is not None:
            os.close(descriptor)
        _fail("conflict", "authority-lock-conflict", "Authority lock failed closed")
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, directory
def _cleanup(directory):
    try:
        paths = [path for path in directory.iterdir()
                 if path.name.startswith(TEMPORARY_PREFIX)]
        _require(len(paths) <= STALE_TEMPORARY_LIMIT, "conflict",
                 "authority-temporary-conflict", "Too many authority temporaries")
        _require(all(stat.S_ISREG(path.lstat().st_mode) for path in paths), "conflict",
                 "authority-temporary-conflict", "Authority temporary is not regular")
        for path in paths:
            path.unlink()
        if paths:
            workflow_cas.fsync_directory(directory)
    except AuthorityFailure:
        raise
    except OSError:
        _fail("conflict", "authority-temporary-conflict",
              "Authority temporary changed or cannot be removed")
def _replace(path, source, target, operation_id):
    temporary = path.parent / ("%s%s-%s-%s" % (
        TEMPORARY_PREFIX, os.getpid(), threading.get_ident(), operation_id[:16]))
    descriptor, replaced = None, False
    try:
        descriptor = os.open(
            str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        workflow_cas.write_all(descriptor, target)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        phase_hook("after-temporary-fsync")
        phase_hook("before-pointer-replace")
        _require(_read_regular(path, missing_ok=True) == source, "conflict",
                 "pointer-source-mismatch", "Pointer no longer matches source")
        os.replace(str(temporary), str(path))
        replaced = True
        phase_hook("after-pointer-replace")
        workflow_cas.fsync_directory(path.parent)
        phase_hook("after-directory-fsync")
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if not replaced:
            try:
                temporary.unlink()
                workflow_cas.fsync_directory(path.parent)
            except FileNotFoundError:
                pass
def _result(bundle, pointer, code):
    return {
        "format": RESULT_FORMAT, "outcome": {"status": "resolved", "code": code},
        "operation_id": bundle["operation_id"],
        "pointer_sha256": bundle["target"]["sha256"], "pointer": pointer,
        "authority": pointer["authority"],
    }
def commit(root, bundle):
    phase_hook("before-candidate-verification")
    decoded = validate_bundle(bundle, root)
    phase_hook("after-candidate-verification")
    store, descriptor = _store(root), None
    descriptor, directory = _lock(store, decoded["issue"])
    try:
        _cleanup(directory)
        path = _pointer_path(store, decoded["issue"])
        current = _read_regular(path, missing_ok=True)
        if current == decoded["target_data"]:
            workflow_cas.fsync_directory(directory)
            return _result(bundle, decoded["target"], "already-committed")
        if decoded["source_data"] is None:
            _require(current is None, "conflict", "pointer-target-conflict",
                     "Another authority target is already selected")
            phase_hook("before-pointer-publication")
            workflow_cas.publish_immutable(
                path, decoded["target_data"], _cas_fail, temporary_label="authority")
            phase_hook("after-pointer-publication")
        else:
            _require(current == decoded["source_data"], "conflict",
                     "pointer-source-mismatch", "Pointer differs from expected source")
            _replace(path, decoded["source_data"], decoded["target_data"],
                     bundle["operation_id"])
        _require(_read_regular(path) == decoded["target_data"], "conflict",
                 "pointer-target-conflict", "Committed pointer differs from target")
        return _result(bundle, decoded["target"], "committed")
    except AuthorityFailure:
        raise
    except OSError:
        _fail("conflict", "authority-storage-conflict",
              "Authority commit filesystem operation failed closed")
    finally:
        os.close(descriptor)
def _load(path, label, limit):
    try:
        with pathlib.Path(path).open("rb") as stream:
            data = stream.read(limit + 2)
    except OSError:
        _fail("missing", "%s-unreadable" % label, "%s is unreadable" % label)
    candidate = data[:-1] if data.endswith(b"\n") else data
    _require(len(candidate) <= limit, "unsupported", "authority-bundle-too-large",
             "Bundle exceeds the 16 KiB limit")
    try:
        value = workflow_inspector.parse_json_object(candidate, label)
    except workflow_inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    _require(data in (_canonical(value), workflow_inspector.canonical_document(value)),
             "corrupt", "noncanonical-%s" % label, "%s is not canonical JSON" % label)
    return value
def _binding_reference(root, digest):
    _require(isinstance(digest, str) and SHA256_RE.fullmatch(digest) is not None,
             "unsupported", "authority-binding-invalid",
             "Candidate binding hash must be 64 lowercase hexadecimal characters")
    path = workflow_inspector.object_path(_store(root), digest)
    try:
        info = path.lstat()
    except FileNotFoundError:
        _fail("missing", "authority-binding-invalid", "Candidate binding is missing", digest)
    except OSError:
        _fail("missing", "authority-binding-invalid",
              "Candidate binding cannot be inspected", digest)
    _require(stat.S_ISREG(info.st_mode), "conflict", "authority-binding-invalid",
             "Candidate binding is not a regular file", digest)
    return {"kind": "evidence-binding", "sha256": digest, "size": info.st_size}
class AuthorityArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)
def build_parser():
    parser = AuthorityArgumentParser(description=__doc__)
    commands = parser.add_subparsers(
        dest="command", required=True, parser_class=AuthorityArgumentParser)
    for name in ("status", "checkpoint"):
        command = commands.add_parser(name)
        command.add_argument("issue", type=int)
        command.add_argument("--root", required=True)
    command = commands.add_parser("prepare")
    command.add_argument("issue", type=int)
    command.add_argument("--root", required=True)
    command.add_argument("--candidate-binding", required=True)
    command = commands.add_parser("commit")
    command.add_argument("--root", required=True)
    command.add_argument("--bundle", required=True)
    return parser
def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        if args.command == "status":
            document = status(args.root, args.issue)
        elif args.command == "checkpoint":
            document = checkpoint(args.root, args.issue)
        elif args.command == "prepare":
            document = prepare(
                args.root, args.issue, _binding_reference(args.root, args.candidate_binding))
        else:
            document = commit(
                args.root, _load(args.bundle, "authority-bundle", BUNDLE_LIMIT))
        sys.stdout.buffer.write(workflow_inspector.canonical_document(document))
        return 0
    except AuthorityFailure as failure:
        sys.stdout.buffer.write(workflow_inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
    except (workflow_evidence.EvidenceFailure,
            workflow_inspector.InspectionFailure) as failure:
        wrapped = AuthorityFailure(
            failure.status, "authority-binding-invalid",
            "Authority evidence verification failed", failure.subject)
        sys.stdout.buffer.write(workflow_inspector.canonical_document(wrapped.document()))
        return OUTCOME_EXIT_CODES[wrapped.status]
    except OSError:
        failure = AuthorityFailure("conflict", "authority-storage-conflict",
                                   "Authority filesystem operation failed closed")
        sys.stdout.buffer.write(workflow_inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]
if __name__ == "__main__":
    raise SystemExit(main())
