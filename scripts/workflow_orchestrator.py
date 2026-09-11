#!/usr/bin/env python3
"""One-step, inactive-by-default composition for the replacement workflow."""
import argparse, base64, copy, json, pathlib, sys, threading
try:
    import workflow_authority as authority
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_orchestrator_gates as gates
    import workflow_orchestrator_resume as resume
    import workflow_plan_revision_policy as plan_policy, workflow_policy as policy
    import workflow_runtime as runtime, workflow_issue_source as issue_source
    import workflow_supervision_policy as supervision
    import workflow_work_type_policy as work_type_policy
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_authority as authority
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_orchestrator_gates as gates
    from scripts import workflow_orchestrator_resume as resume
    from scripts import workflow_plan_revision_policy as plan_policy, workflow_policy as policy
    from scripts import workflow_runtime as runtime, workflow_issue_source as issue_source
    from scripts import workflow_supervision_policy as supervision
    from scripts import workflow_work_type_policy as work_type_policy
VERSION, STATE_FORMAT, NODE_FORMAT = "1.4.0", authority.STATE_FORMAT, "chess-echo-workflow-node-v1"; CHALLENGE_FORMAT, RECOVERY_CHALLENGE_FORMAT, CANDIDATE_FORMAT, RESULT_FORMAT, FAILURE_FORMAT = supervision.CHALLENGE_FORMAT, "chess-echo-human-challenge-v1", "chess-echo-orchestrator-agent-candidate-v1", "chess-echo-orchestration-orchestrator-result-v1", "chess-echo-orchestration-orchestrator-failure-v1"
STATE_PATH, NODE_PATH, POLICY_PATH, SUPERVISION_PATH, RUNTIME_PIN_PATH, RESULT_PATH = "workflow-orchestration/state.json", "workflow-orchestration/node.json", "workflow-policy/state.json", supervision.POLICY_PATH, "workflow-orchestration/runtime-reconstruction.json", runtime.EXECUTION_RESULT_PATH
LIMIT, ATTACHMENT_LIMIT = 2 * 1024 * 1024, runtime.ATTACHMENT_LIMIT_BYTES
OUTCOMES = {"resolved": 0, "missing": 3, "unsupported": 4, "corrupt": 5, "ambiguous": 6, "stale": 7, "denied": 8, "busy": 9, "conflict": 10, "uncertain": 11, "paused": 12}
AGENT_PHASES = {"PLANNING": ("planner", "write-plan"), "PLAN_REVIEW": ("reviewer", "review-plan"), "TEST_IMPLEMENTATION": ("implementer", "write-tests"), "TEST_REVIEW": ("reviewer", "review-tests"), "IMPLEMENTATION": ("implementer", "implement"), "FINAL_REVIEW": ("reviewer", "review-final")}
RESUME_PHASES = ({"validation": "VALIDATION", "github-read": "PR_PREPARATION", "github-write": "PR_PREPARATION"}, {name: phase for phase, (_role, name) in AGENT_PHASES.items()})
CLAIM_TYPES = {"PLANNING": "plan-request", "PLAN_REVIEW": "plan-review", "TEST_IMPLEMENTATION": "tests-request", "TEST_REVIEW": "tests-review", "IMPLEMENTATION": "implementation-request", "VALIDATION": "validation-request", "FINAL_REVIEW": "final-review", "PR_PREPARATION": "pr-prepare"}
REQUIRED_NODES = {"TEST_IMPLEMENTATION": ("plan-approval",), "TEST_REVIEW": ("plan-approval", "test-manifest"), "IMPLEMENTATION": ("plan-approval", "test-approval", "test-manifest"), "VALIDATION": ("implementation-submission",), "FINAL_REVIEW": ("plan-approval", "test-approval", "implementation-submission", "validation"), "PR_PREPARATION": ("plan-approval", "test-approval", "implementation-submission", "validation", "final-review")}
CANDIDATE_ARTIFACTS = {"TEST_IMPLEMENTATION": ("test-report", "workflow-orchestration/test-report.json"), "TEST_REVIEW": ("technical-test-review", "workflow-orchestration/test-review.json"), "IMPLEMENTATION": ("implementation-report", "workflow-orchestration/implementation-report.json"), "FINAL_REVIEW": ("final-review-candidate", "workflow-orchestration/final-review.json")}
GATES = gates.GATES
GATE_NEXT = {"plan": "TEST_IMPLEMENTATION", "tests": "IMPLEMENTATION", "final": "PR_PREPARATION", "pr-publication": "PR_PREPARATION"}; PHASE_ORIGINS = {"PLANNING": {"PLANNING", "PLAN_REVIEW", "TEST_IMPLEMENTATION", "PAUSED"}, "PLAN_REVIEW": {"PLANNING", "PLAN_REVIEW", "PAUSED"}, "WAITING_FOR_PLAN_APPROVAL": {"PLAN_REVIEW"}, "TEST_IMPLEMENTATION": {"WAITING_FOR_PLAN_APPROVAL", "TEST_IMPLEMENTATION", "PAUSED"}, "TEST_REVIEW": {"TEST_IMPLEMENTATION", "TEST_REVIEW", "PAUSED"}, "WAITING_FOR_TEST_APPROVAL": {"TEST_REVIEW"}, "IMPLEMENTATION": {"WAITING_FOR_TEST_APPROVAL", "IMPLEMENTATION", "PAUSED"}, "VALIDATION": {"IMPLEMENTATION", "VALIDATION", "PAUSED"}, "FINAL_REVIEW": {"VALIDATION", "FINAL_REVIEW", "PAUSED"}, "WAITING_FOR_FINAL_APPROVAL": {"FINAL_REVIEW"}, "PR_PREPARATION": {"WAITING_FOR_FINAL_APPROVAL", "PR_PREPARATION", "WAITING_FOR_PR_PUBLICATION_APPROVAL", "PAUSED"}, "WAITING_FOR_PR_PUBLICATION_APPROVAL": {"PR_PREPARATION"}, "COMPLETED": {"PR_PREPARATION"}}
FROZEN_ISSUES = frozenset({115})
RUNTIME_PROVIDER = None
SANDBOX_PROVIDER = None
PENDING_RESULT_PROVIDER = None
_UNSET = object()
class OrchestratorFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message); self.status, self.code, self.message, self.subject = status, code, message, subject
    def document(self):
        outcome = {"status": self.status, "code": self.code, "message": self.message}
        if self.subject is not None: outcome["subject"] = self.subject
        return {"format": FAILURE_FORMAT, "outcome": outcome}
def _fail(status, code, message, subject=None): raise OrchestratorFailure(status, code, message, subject)
def _require(condition, status, code, message, subject=None):
    if not condition: _fail(status, code, message, subject)
def _canonical(value): return inspector.canonical_bytes(value)
def _digest(value): return inspector.sha256(_canonical(value))
def _with_digest(value, field):
    value = copy.deepcopy(value); value[field] = _digest(value); return value
def _translate(action, label):
    try: return action()
    except OrchestratorFailure: raise
    except (authority.AuthorityFailure, evidence.EvidenceFailure, inspector.InspectionFailure, issue_source.IssueSourceFailure, plan_policy.PlanRevisionPolicyFailure, policy.PolicyFailure, resume.ResumeFailure, runtime.RuntimeFailure, supervision.SupervisionPolicyFailure, work_type_policy.WorkTypePolicyFailure) as error:
        status = getattr(error, "status", "corrupt")
        _fail(status if status in OUTCOMES else "corrupt", getattr(error, "code", label), getattr(error, "message", str(error)), getattr(error, "subject", None))
    except (OSError, UnicodeError, ValueError) as error:
        _fail("corrupt", "%s-failed" % label, "%s failed closed: %s" % (label, error))
def _put(rows, slot, binding):
    rows = [copy.deepcopy(row) for row in rows if row["slot"] != slot]
    rows.append({"slot": slot, "binding": binding}); return sorted(rows, key=lambda row: row["slot"].encode())
def _drop(rows, *slots):
    return [copy.deepcopy(row) for row in rows if row["slot"] not in slots]
def _next(state):
    pending, phase = state["pending"], state["phase"]
    if pending is not None:
        if pending["status"] == "cancel-requested": return {"action": "recover-cancelled-attempt", "command": "recover", "gate": "recovery", "pending_kind": pending["kind"]}
        if pending["kind"] == "human-rejection": return {"action": "authorize-gate-rejection", "command": "reject", "gate": GATES.get(phase), "pending_kind": "human-rejection"}
        if pending["kind"] == "human" and phase == "PAUSED": return {"action": "authorize-recovery", "command": "recover", "gate": "recovery", "pending_kind": "human"}
        if pending["kind"] == "human" and phase not in GATES: return {"action": "authorize-supervision-change", "command": "approve", "gate": "supervision-policy-change", "pending_kind": "human"}
        if pending["kind"] == "policy": return {"action": "satisfy-gate-automatically", "command": "step", "gate": GATES.get(phase), "pending_kind": "policy"}
        return {"action": "approve-gate" if pending["kind"] == "human" else "execute-pending", "command": "approve" if pending["kind"] == "human" else "step", "gate": GATES.get(phase), "pending_kind": pending["kind"]}
    slots = {row["slot"] for row in state["candidates"]}
    pr_action = "complete-pr-approval" if "pr-metadata" in slots else "prepare-draft-pr" if "pr-publication-gate-satisfaction" in slots else "open-pr-publication-gate"
    actions = {"PLANNING": "request-planner", "PLAN_REVIEW": "review-plan", "TEST_IMPLEMENTATION": "request-tests", "TEST_REVIEW": "review-tests", "IMPLEMENTATION": "request-implementation", "VALIDATION": "run-validation", "FINAL_REVIEW": "review-final", "PR_PREPARATION": pr_action, "PAUSED": "request-recovery", "COMPLETED": "none-completed"}
    return {"action": "await-human-approval" if phase in GATES else actions.get(phase, "unknown"), "command": "approve" if phase in GATES else "recover" if phase == "PAUSED" else "read-only" if phase == "COMPLETED" else "step", "gate": GATES.get(phase), "pending_kind": None}
class Orchestrator(gates.ApprovalGateMixin):
    _gate_fail, _gate_require, _gate_translate = staticmethod(_fail), staticmethod(_require), staticmethod(_translate)
    def __init__(self, root, issue):
        _require(type(issue) is int and issue > 0, "unsupported", "invalid-issue", "Issue must be a positive integer")
        _require(issue not in FROZEN_ISSUES, "denied", "issue-frozen", "Issue is frozen before any workflow lookup", str(issue))
        self.root, self.issue, self.family = pathlib.Path(root), issue, None
        self._selected_inspection, self._selected, self._history_adapter = None, None, None
    def _runtime_pin(self, state):
        implementation = self._read(self._active(state, "implementation-a"), NODE_PATH, "implementation root")
        pins = [row["binding"] for row in implementation["evidence"] if row["role"] == "runtime-reconstruction"]
        _require(len(pins) == 1, "stale", "runtime-pin-unselected", "Selected implementation root has no unique runtime pin")
        projection = _translate(lambda: evidence.project(self.root, pins[0]), "evidence")
        pin = self._read(pins[0], RUNTIME_PIN_PATH, "runtime reconstruction pin")
        _require(projection["decision"]["type"] == "runtime-reconstruction" and projection["subject"] == state["triage_binding"], "stale", "runtime-pin-unselected", "Runtime reconstruction pin is not selected by triage")
        return pins[0], pin
    def _runtime_expectation(self, state):
        pending = state["pending"]
        if pending is not None and pending["kind"] not in {"human", "human-rejection", "policy"}:
            return "trusted-current", None
        expected = self._phase_repository(state, state["phase"])
        if expected is not None:
            return "exact", expected
        return ("clean-base", None) if state["phase"] == "PLANNING" else ("trusted-current", None)
    def _runtime(self, request, state=None, inspection=None, expected_repository=_UNSET):
        _require(RUNTIME_PROVIDER is not None, "unsupported", "runtime-provider-unavailable", "No reviewed runtime provider is configured")
        state = state or self._selected
        inspection = inspection or self._selected_inspection
        if state is None:
            adapter = _translate(lambda: RUNTIME_PROVIDER(self.root, self.issue, request), "runtime")
            _require(adapter is not None, "unsupported", "runtime-unavailable", "Runtime provider returned no adapter")
            return adapter
        _require(inspection is not None, "corrupt", "runtime-authority-missing", "Runtime reconstruction requires selected authority")
        triage, baseline, baseline_binding, _config = self._facts(state)
        pin_binding, pin = self._runtime_pin(state)
        if expected_repository is _UNSET:
            repository_mode, expected_repository = self._runtime_expectation(state)
        else:
            repository_mode = "exact" if expected_repository is not None else "trusted-current"
        context = _translate(lambda: runtime.build_reconstruction_request(pin_binding=pin_binding, pin_document=pin, baseline_binding=baseline_binding, baseline_document=baseline, triage_binding=state["triage_binding"], triage_document=triage, authority_binding=inspection["authority"], repository_mode=repository_mode, repository_observation=expected_repository), "runtime")
        adapter = _translate(lambda: RUNTIME_PROVIDER(self.root, self.issue, context), "runtime")
        _require(adapter is not None, "unsupported", "runtime-unavailable", "Runtime provider returned no adapter")
        _require(callable(getattr(adapter, "reconstruction_document", None)), "stale", "runtime-reconstruction-unverified", "Runtime provider did not reconstruct the selected evidence")
        returned = _translate(adapter.reconstruction_document, "runtime")
        _translate(lambda: resume.verify_reconstruction_response(returned, context), "resume")
        current = _translate(lambda: authority.status(self.root, self.issue), "authority")
        _require(current["pointer_sha256"] == inspection["pointer_sha256"] and current["authority"] == inspection["authority"], "stale", "runtime-authority-changed", "Authority changed during runtime reconstruction")
        return adapter
    def _runtime_drift(self, action, drift_code, drift_message, codes, guard=True):
        try:
            return action()
        except OrchestratorFailure as error:
            if guard and error.code in codes:
                _fail("stale", drift_code, drift_message)
            raise
    def _status(self, missing_ok=False):
        legacy = self._legacy_present()
        try: document = _translate(lambda: authority.status(self.root, self.issue), "authority")
        except OrchestratorFailure as error:
            if error.code == "orchestration-pointer-missing":
                if legacy: _fail("unsupported", "legacy-authority-owned", "Existing legacy authority is not eligible for fresh initialization")
                if missing_ok: return None
            raise
        _require(not legacy, "conflict", "dual-authority-detected", "Legacy and replacement authority both exist")
        return document
    def _legacy_present(self):
        try:
            inspector.inspect(self.root, self.issue)
            return True
        except inspector.InspectionFailure as error:
            if error.code == "issue-pointer-missing": return False
            _fail(error.status, error.code, error.message, error.subject)
    def _bytes(self, binding, path=None, label="evidence document"):
        projection = _translate(lambda: evidence.project(self.root, binding), "evidence")
        matches = [entry for entry in projection.get("entries", []) if path is None or entry["path"] == path]
        _require(len(matches) == 1 and (path is not None or len(projection["entries"]) == 1), "corrupt", "evidence-entry-mismatch", "%s has an unexpected payload shape" % label)
        reader = _translate(lambda: inspector.AuthorityReader(inspector.resolve_store(self.root), self.issue), "inspector")
        return _translate(lambda: reader.read_bytes(matches[0]["payload"], "evidence-payload"), "inspector")
    def _read(self, binding, path=None, label="evidence document"):
        data = self._bytes(binding, path, label); value = _translate(lambda: inspector.parse_json_object(data, label), "inspector")
        _require(_canonical(value) == data, "corrupt", "noncanonical-document", "%s is not canonical" % label); return value
    def _state(self, inspection):
        return self._read(inspection["authority"], STATE_PATH, "orchestration state")
    def _selected_state(self, inspection):
        state = self._state(inspection); self.family = state["family_run_id"]; self._history_adapter = None
        self._selected_inspection, self._selected = inspection, state
        try: self._validate_supervision_history(state, inspection["authority"])
        finally: self._history_adapter = None
        return state
    def _publish(self, decision_type, decision_id, subject, rows, generation, lineage=None, identity=None, before_binding_reference=None, attachments=()):
        encoded, entries = [], []
        for path, value in rows:
            data = value if isinstance(value, bytes) else _canonical(value)
            _require(isinstance(data, bytes) and len(data) <= LIMIT, "unsupported", "document-too-large", "Orchestration evidence exceeds 2 MiB")
            reference = {"kind": "evidence-payload", "sha256": inspector.sha256(data), "size": len(data)}
            encoded.append((path, data)); entries.append({"path": path, "kind": "regular", "mode": "100644", "content_sha256": reference["sha256"], "size": reference["size"], "payload": reference})
        for item in attachments:
            (data, path) = (item["bytes"], item["path"])
            _require(isinstance(data, bytes) and len(data) <= ATTACHMENT_LIMIT, "unsupported", "attachment-too-large", "Orchestration evidence attachment exceeds its reviewed limit")
            _require(isinstance(path, str) and path not in [entry["path"] for entry in entries] and inspector.sha256(data) == item["sha256"] and len(data) == item["size"], "corrupt", "invalid-evidence-attachment", "Orchestration evidence attachment does not bind its exact bytes")
            encoded.append((path, data)); entries.append({"path": path, "kind": "regular", "mode": "100644", "content_sha256": item["sha256"], "size": item["size"], "payload": {"kind": "evidence-payload", "sha256": item["sha256"], "size": item["size"]}})
        entries.sort(key=lambda entry: entry["path"].encode()); blob = b"\0".join(data for _path, data in encoded)
        identity = identity or {"issue": self.issue, "run_id": inspector.sha256(b"orchestration-node-v1\0" + blob)[:32], "family_run_id": self.family, "correction": None, "run_generation": generation, "sequence": generation + 1, "event_tip": inspector.sha256(b"orchestration-node-tip-v1\0" + blob)}
        captures = [{"entry_sha256": inspector.sha256(_canonical(entry)), "capture_method": "orchestration-composition", "captured_at": "1970-01-01T00:00:00Z", "source": {"type": "workspace", "path": entry["path"]}, "tool": {"name": "workflow-orchestrator", "version": VERSION}} for entry in entries]
        publication = {"format": evidence.PUBLICATION_FORMAT, "identity": identity, "decision": {"type": decision_type, "id": decision_id}, "subject": subject, "lineage": lineage or {"status": "original", "parent_binding": None}, "migration": None, "entries": entries, "captures": captures, "payloads": [{"sha256": inspector.sha256(data), "size": len(data), "bytes_base64": base64.b64encode(data).decode()} for _path, data in encoded]}
        return _translate(lambda: evidence.publish(self.root, publication, before_binding_reference=before_binding_reference)["binding"], "evidence")
    def _publish_state(self, state):
        data, generation = _canonical(state), state["generation"]
        identity = {"issue": self.issue, "run_id": inspector.sha256(b"orchestration-state-v1\0" + data)[:32], "family_run_id": self.family, "correction": None, "run_generation": generation, "sequence": generation + 1, "event_tip": inspector.sha256(b"orchestration-tip-v1\0" + data)}
        subject, lineage = (state["previous_authority"], {"status": "replacement", "parent_binding": state["previous_authority"]}) if generation else (state["policy_state_binding"], {"status": "original", "parent_binding": None})
        return self._publish("orchestration-state", "generation-%d" % generation, subject, [(STATE_PATH, data)], generation, lineage, identity)
    def _commit(self, state):
        binding = self._publish_state(state); bundle = _translate(lambda: authority.prepare(self.root, self.issue, binding), "authority")
        return binding, _translate(lambda: authority.commit(self.root, bundle), "authority")
    def _base(self, **changes):
        state = {"format": STATE_FORMAT, "issue": self.issue, "family_run_id": self.family, "generation": 0, "previous_authority": None, "previous_pointer_sha256": None, "route": "implementation", "phase": "PLANNING", "triage_binding": None, "policy_state_binding": None, "supervision_policy_binding": None, "candidates": [], "pending": None, "cutover": {"mode": "new-run", "legacy_checkpoint_sha256": None, "migration_binding": None}, "transition": {"type": "initialize", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}}
        state.update(changes); return _with_digest(state, "state_sha256")
    def _successor(self, state, previous, **changes):
        pointer = _canonical({"format": authority.POINTER_FORMAT, "issue": self.issue, "generation": state["generation"], "authority": previous})
        next_state = {"format": STATE_FORMAT, "issue": self.issue, "family_run_id": self.family, "generation": state["generation"] + 1, "previous_authority": previous, "previous_pointer_sha256": inspector.sha256(pointer), "route": "implementation", "phase": state["phase"], "triage_binding": state["triage_binding"], "policy_state_binding": state["policy_state_binding"], "supervision_policy_binding": state["supervision_policy_binding"], "candidates": copy.deepcopy(state["candidates"]), "pending": None, "cutover": {"mode": "new-run", "legacy_checkpoint_sha256": None, "migration_binding": None}, "transition": {"type": "classify", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}}
        next_state.update(changes); return _with_digest(next_state, "state_sha256")
    def _result(self, code, state, binding, committed, status="resolved"):
        return {"format": RESULT_FORMAT, "outcome": {"status": status, "code": code}, "issue": self.issue, "generation": state["generation"], "phase": state["phase"], "authority": binding, "pointer_sha256": committed["pointer_sha256"], "next_action": _next(state)}
    def _handoff_result(self, state, inspection, handoff):
        return {"format": RESULT_FORMAT, "outcome": {"status": "resolved", "code": "execution-candidate"}, "issue": self.issue, "generation": state["generation"], "phase": state["phase"], "authority": inspection["authority"], "pointer_sha256": inspection["pointer_sha256"], "next_action": {"action": "finalize-pending", "command": "step", "gate": None, "pending_kind": state["pending"]["kind"]}, "handoff": handoff}
    def _expect(self, inspection, expected_tip):
        _require(isinstance(expected_tip, str) and expected_tip == inspection["pointer_sha256"], "stale", "expected-tip-stale", "Expected tip is not current")
    def _family(self, bootstrap, source):
        seed = {"format": "chess-echo-family-seed-v1", "repository": bootstrap["repository"], "issue": self.issue, "issue_source": source, "base": {"ref": bootstrap["target_base"]["ref"], "commit": bootstrap["target_base"]["commit"], "tree": bootstrap["target_base"]["tree"]}, "config": {key: bootstrap["config"][key] for key in ("blob_oid", "content_sha256", "size")}}
        return _digest(seed)[:32]
    def _facts(self, state):
        triage = self._read(state["triage_binding"], label="work type triage"); baseline_binding = triage["baseline_binding"]
        baseline = self._read(baseline_binding, label="work type baseline")
        try: config = json.loads(base64.b64decode(baseline["config"]["bytes_base64"], validate=True))
        except (KeyError, TypeError, ValueError, UnicodeError, json.JSONDecodeError, RecursionError) as error: _fail("corrupt", "baseline-config-invalid", "Baseline configuration cannot be decoded: %s" % error)
        _require(isinstance(config, dict) and isinstance(config.get("orchestrator"), dict), "corrupt", "baseline-config-invalid", "Baseline has no valid orchestrator configuration")
        return triage, baseline, baseline_binding, config["orchestrator"]
    def _candidate_binding(self, state, slot, required=True):
        matches = [row["binding"] for row in state["candidates"] if row["slot"] == slot]
        _require(not required or len(matches) == 1, "stale", "candidate-missing", "Required %s candidate is absent" % slot)
        return matches[0] if matches else None
    def _active(self, state, node):
        document = self._read(state["policy_state_binding"], POLICY_PATH, "policy state"); matches = [row for row in document["active"] if row["node"] == node]
        _require(len(matches) == 1, "stale", "policy-node-inactive", "Policy node is not active", node); return matches[0]["binding"]
    def _supervision(self, state):
        document = self._read(state["supervision_policy_binding"], SUPERVISION_PATH, "supervision policy")
        return _translate(lambda: supervision.validate_policy(document, self.issue, self.family), "supervision-policy")
    def _history(self, state, binding):
        rows, current, current_binding = [], state, binding
        for _index in range(authority.CHAIN_LIMIT):
            rows.append((current_binding, current))
            if current["generation"] == 0: return list(reversed(rows))
            current_binding = current["previous_authority"]; current = self._read(current_binding, STATE_PATH, "prior orchestration state")
        _fail("unsupported", "authority-chain-limit", "Orchestration history exceeds its bound")
    def _validate_selected_transition(self, prior_binding, prior, current_binding, current):
        gate = GATES.get(prior["phase"])
        rejection_open = self._validate_rejection_request_transition(prior_binding, prior, current) if current["transition"]["type"] == "rejection-request" else False
        rejection_close = self._validate_rejection_completion_transition(prior, current) if prior["pending"] is not None and prior["pending"]["kind"] == "human-rejection" else False
        if gate is not None and not rejection_open and not rejection_close: _require(current["phase"] == GATE_NEXT[gate] and current["pending"] is None and current["supervision_policy_binding"] == prior["supervision_policy_binding"] and current["transition"] == {"type": {"plan": "plan-approve", "tests": "tests-approve", "final": "final-approve", "pr-publication": "publication-approve"}[gate], "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}, "stale", "gate-transition-unselected", "Selected authority history bypasses gate satisfaction"); slot = "%s-gate-satisfaction" % gate; wrapper = self._read(self._active(current, {"plan": "plan-approval", "tests": "test-approval"}[gate]), NODE_PATH) if gate in {"plan", "tests"} else None; matches = [row["binding"] for row in wrapper["evidence"] if row["role"] == "gate-satisfaction"] if wrapper is not None else []; _require(wrapper is None or len(matches) == 1, "stale", "gate-transition-unselected", "Gate approval node does not select one satisfaction"); satisfaction = matches[0] if matches else self._candidate_binding(current, slot); transient = copy.deepcopy(current); transient["candidates"] = _put(transient["candidates"], slot, satisfaction); selected_satisfaction, satisfaction_document, satisfaction_challenge = self._satisfaction_context(transient, {"authority": current_binding}, slot, gate, reobserve_human=True, historical=True); subjects = {row["slot"]: row["binding"] for row in satisfaction_challenge["subjects"]}; wrapper_evidence = {row["role"]: row["binding"] for row in wrapper["evidence"]} if wrapper is not None else {}; _require(wrapper is None or (wrapper["node"] == {"plan": "plan-approval", "tests": "test-approval"}[gate] and wrapper["subject_binding"] == subjects[{"plan": "plan-snapshot", "tests": "test-manifest"}[gate]] and len(wrapper["evidence"]) == 2 and wrapper_evidence == {"gate-satisfaction": selected_satisfaction, {"plan": "technical-plan-review", "tests": "technical-test-review"}[gate]: subjects[{"plan": "plan-review", "tests": "test-review"}[gate]]} and wrapper["repository_observation_binding"] == satisfaction_document["repository_observation_binding"] and wrapper["authorization_binding"] == satisfaction_document["human_authorization_binding"]), "stale", "gate-approval-wrapper-stale", "Gate approval wrapper differs from the selected satisfaction")
        allowed = PHASE_ORIGINS.get(current["phase"]); _require((rejection_open or rejection_close or allowed is None or prior["phase"] in allowed) and not (current["phase"] == "PLANNING" and prior["phase"] == "TEST_IMPLEMENTATION" and current["transition"]["type"] != "plan-reopen"), "stale", "phase-transition-unselected", "Selected authority history skips a required lifecycle phase")
        if current["phase"] == "COMPLETED": final_satisfaction, _final_document, _final_challenge = self._satisfaction_context(prior, {"authority": prior_binding}, "final-gate-satisfaction", "final", reobserve_human=True, historical=True); publication_satisfaction, _publication_document, _publication_challenge = self._satisfaction_context(prior, {"authority": prior_binding}, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True, historical=True); metadata = self._candidate_binding(prior, "pr-metadata"); approval = self._read(self._active(current, "pr-approval"), NODE_PATH, "PR approval node"); approval_evidence = {row["role"]: row["binding"] for row in approval["evidence"]}; write_result, _write_binding = self._prior_pr_result(prior, {"authority": prior_binding}); _require(current["transition"]["type"] == "complete" and current["pending"] is None and metadata == self._active(prior, "pr-metadata") and write_result is not None and write_result.get("reconciliation", {}).get("status") == "confirmed" and approval["subject_binding"] == metadata and len(approval["evidence"]) == 4 and approval_evidence.get("final-gate-satisfaction") == final_satisfaction and approval_evidence.get("pr-publication-gate-satisfaction") == publication_satisfaction and approval_evidence.get("final-review") == self._read(self._active(prior, "final-review"), NODE_PATH)["subject_binding"] and "github-pr-observation" in approval_evidence, "stale", "completion-transition-unselected", "Completion lacks selected publication and reconciled PR evidence")
        prior_pending = prior["pending"]; recovery_open = current["transition"]["type"] == "recover" and current["phase"] == "PAUSED" and current["pending"] is not None and current["pending"]["kind"] == "human" and not (prior_pending is not None and prior_pending["kind"] == "human"); recovery_close = prior["phase"] == "PAUSED" and prior_pending is not None and prior_pending["kind"] == "human" and current["phase"] != "PAUSED"
        if recovery_open: request_binding, _request, _result = self._recovery_attempt(prior, {"authority": prior_binding}); challenge = self._read(current["pending"]["request_binding"], "workflow-orchestration/human-challenge.json", "recovery challenge"); self._check_recovery_challenge(challenge, current["previous_authority"]); _require(current["transition"]["request_binding"] == current["pending"]["request_binding"] and challenge["subjects"] == [{"slot": "pending-attempt", "binding": request_binding}], "stale", "recovery-transition-unselected", "Recovery request does not select the recorded attempt")
        if recovery_close: challenge_binding = prior_pending["request_binding"]; challenge = self._read(challenge_binding, "workflow-orchestration/human-challenge.json", "recovery challenge"); self._check_recovery_challenge(challenge, prior["previous_authority"]); authorization = self._candidate_binding(current, "human-authorization"); projection = _translate(lambda: evidence.project(self.root, authorization), "evidence"); document = self._read(authorization, "workflow-orchestration/human-authorization.json", "recovery authorization"); source = {"kind": document["source"]["kind"], "id": document["source"]["id"]}; observed = self._authorization_document(prior, challenge, source, challenge_binding, historical=True); request = self._read(challenge["subjects"][0]["binding"], label="recovery request"); _require(current["transition"]["type"] == "recover" and current["pending"] is None and current["phase"] == self._resume_phase(request) and projection["decision"]["type"] == "human-authorization" and projection["subject"] == challenge_binding and document == observed, "stale", "recovery-transition-unselected", "Recovery was not selected by exact live human authorization")
        sensitive = prior["phase"] == "PAUSED" or (prior_pending is not None and prior_pending["status"] == "cancel-requested") or current["transition"]["type"] == "recover"; _require(not sensitive or recovery_open or recovery_close, "stale", "recovery-transition-unselected", "Selected authority history bypasses mandatory human recovery")
    def _validate_supervision_history(self, state, authority_binding):
        history = self._history(state, authority_binding); _genesis_binding, genesis = history[0]
        _triage, _baseline, baseline_binding, config = self._facts(genesis); selected = genesis["supervision_policy_binding"]; document = self._read(selected, SUPERVISION_PATH, "initial supervision policy")
        expected = _translate(lambda: supervision.initialize(self.issue, self.family, baseline_binding, config["supervision"]), "supervision-policy"); projection = _translate(lambda: evidence.project(self.root, selected), "evidence")
        _require(document == expected and projection["decision"]["type"] == "supervision-policy" and projection["subject"] == baseline_binding and projection["lineage"] == {"status": "original", "parent_binding": None}, "stale", "supervision-policy-unselected", "Initial supervision policy does not match the trusted baseline configuration")
        for index in range(1, len(history)):
            _current_authority, current = history[index]; current_binding = current["supervision_policy_binding"]
            prior_authority, prior = history[index - 1]; self._validate_selected_transition(prior_authority, prior, _current_authority, current)
            if current_binding == selected: continue
            pending = prior["pending"]
            _require(prior["phase"] not in set(GATES) | {"PAUSED", "PR_PREPARATION", "COMPLETED"} and pending is not None and pending["kind"] == "human" and pending["status"] == "requested" and self._candidate_binding(prior, "supervision-policy-change", required=False) == pending["request_binding"] and current["transition"]["type"] == "supervision-change" and current["pending"] is None, "stale", "supervision-policy-unselected", "Supervision policy replacement was not selected by an authorized change transition")
            challenge_binding = pending["request_binding"]; challenge_projection = _translate(lambda: evidence.project(self.root, challenge_binding), "evidence")
            challenge = self._read(challenge_binding, supervision.CHANGE_PATH, "supervision policy change"); next_document = self._read(current_binding, SUPERVISION_PATH, "revised supervision policy")
            authorization_binding = next_document["authorization_binding"]; authorization_projection = _translate(lambda: evidence.project(self.root, authorization_binding), "evidence")
            authorization_document = self._read(authorization_binding, "workflow-orchestration/human-authorization.json", "supervision policy authorization"); source = {"kind": authorization_document["source"]["kind"], "id": authorization_document["source"]["id"]}; observed_authorization = self._authorization_document(prior, challenge, source, challenge_binding, historical=True)
            _require(observed_authorization == authorization_document, "stale", "supervision-change-authorization-stale", "Supervision policy authorization source changed"); expected = _translate(lambda: supervision.revise(selected, document, challenge_binding, challenge, authorization_binding, authorization_document, prior["previous_authority"], prior["phase"]), "supervision-policy"); projection = _translate(lambda: evidence.project(self.root, current_binding), "evidence")
            _require(challenge_projection["decision"]["type"] == "supervision-policy-change" and challenge_projection["subject"] == prior["previous_authority"] and authorization_projection["decision"]["type"] == "human-authorization" and authorization_projection["subject"] == challenge_binding and next_document == expected and projection["decision"]["type"] == "supervision-policy" and projection["subject"] == selected and projection["lineage"] == {"status": "replacement", "parent_binding": selected}, "stale", "supervision-policy-unselected", "Supervision policy replacement is not exact")
            selected, document = current_binding, next_document
        _require(selected == state["supervision_policy_binding"], "stale", "supervision-policy-unselected", "Selected supervision policy is not authorized")
    def _policy_chain(self, state, inspection):
        rows, seen = [], set()
        for _binding, orchestration_state in self._history(state, inspection["authority"]):
            reference = orchestration_state["policy_state_binding"]
            if reference["sha256"] not in seen:
                rows.append({"binding": reference, "state": self._read(reference, POLICY_PATH, "policy state")}); seen.add(reference["sha256"])
        return rows
    def _policy_inputs(self, chain, extra):
        references = {}
        for entry in chain:
            references[entry["binding"]["sha256"]] = entry["binding"]
            for row in entry["state"]["active"] + entry["state"]["history"]:
                references[row["binding"]["sha256"]] = row["binding"]
                for dependency in row.get("dependencies", []): references[dependency["binding"]["sha256"]] = dependency["binding"]
            for row in entry["state"]["convergence"]["evidence"]: references[row["binding"]["sha256"]] = row["binding"]
        for reference in extra: references[reference["sha256"]] = reference
        return [{"binding": references[key], "migration_plan": None} for key in sorted(references)]
    def _node(self, node, subject, evidence_rows, repository, authorization, dependencies, generation):
        evidence_rows = sorted([{"role": role, "binding": binding} for role, binding in evidence_rows], key=lambda row: row["role"].encode())
        _require(len({row["role"] for row in evidence_rows}) == len(evidence_rows), "corrupt", "duplicate-node-evidence", "Node evidence roles must be unique")
        document = _with_digest({"format": NODE_FORMAT, "issue": self.issue, "family_run_id": self.family, "node": node, "subject_binding": subject, "dependencies": dependencies, "evidence": evidence_rows, "repository_observation_binding": repository, "authorization_binding": authorization}, "node_sha256")
        return self._publish(node, "%s-%s" % (node, document["node_sha256"]), subject, [(NODE_PATH, document)], generation), document
    def _publish_policy(self, document, subject, previous, generation):
        lineage = {"status": "original", "parent_binding": None} if previous is None else {"status": "replacement", "parent_binding": previous}
        return self._publish("policy-state", "generation-%d" % document["generation"], subject, [(POLICY_PATH, document)], generation, lineage)
    def _publish_supervision(self, document, subject, previous, generation):
        lineage = {"status": "original", "parent_binding": None} if previous is None else {"status": "replacement", "parent_binding": previous}
        decision_id = "revision-%d-%s" % (document["revision"], document["policy_sha256"])
        return self._publish("supervision-policy", decision_id, subject, [(SUPERVISION_PATH, document)], generation, lineage)
    def _bind(self, state, inspection, node, subject, evidence_rows, repository, authorization=None):
        current = self._read(state["policy_state_binding"], POLICY_PATH, "policy state"); active = {row["node"]: row["binding"] for row in current["active"]}
        dependencies = [{"node": parent, "binding": active[parent]} for parent in policy.DEPENDENCIES[node]]
        wrapper, _document = self._node(node, subject, evidence_rows, repository, authorization, dependencies, state["generation"] + 1)
        chain = self._policy_chain(state, inspection); operation = {"type": "bind", "node": node, "binding": wrapper, "dependencies": dependencies, "reason": "Activate %s" % node}
        request = {"format": policy.REQUEST_FORMAT, "issue": self.issue, "family_run_id": self.family, "state": current, "expected_state_sha256": current["state_sha256"], "bindings": self._policy_inputs(chain, [wrapper]), "authority_chain": chain, "operation": operation}
        result = _translate(lambda: policy.evaluate(self.root, request, state["policy_state_binding"]["sha256"]), "policy")
        _require(result["outcome"] == {"status": "resolved", "code": "evaluated"}, "paused", "unsupported-policy-transition", "Policy did not authorize node activation")
        return wrapper, self._publish_policy(result["next_state"], state["policy_state_binding"], state["policy_state_binding"], state["generation"] + 1)
    def initialize(self, request):
        _require(self._status(missing_ok=True) is None, "conflict", "already-initialized", "Issue already has an orchestration pointer")
        _require(isinstance(request, dict) and isinstance(request.get("trusted_issue_source"), dict), "missing", "trusted-issue-source-required", "Initialization requires a trusted pre-genesis issue source publication")
        adapter = self._runtime(request); bootstrap = _translate(adapter.bootstrap_document, "runtime")
        _require(bootstrap["mode"] == "active", "unsupported", "orchestrator-inactive", "New orchestration is disabled by configuration")
        issue_document, raw = _translate(lambda: adapter.observe_issue(self.issue), "runtime"); source = _translate(lambda: issue_source.validate_for_initialization(request["trusted_issue_source"], bootstrap, issue_document, raw, bootstrap["repository"], self.issue), "issue-source")
        _translate(lambda: plan_policy.acceptance_facts(issue_document["body"]), "plan-policy")
        reader = _translate(lambda: inspector.AuthorityReader(inspector.resolve_store(self.root), self.issue), "inspector")
        _require(_translate(lambda: reader.read_bytes(source, source["kind"]), "inspector") == raw, "stale", "response-source-missing", "Live issue source is absent or changed")
        self.family = self._family(bootstrap, source)
        issue_binding = self._publish("work-type-issue-snapshot", "issue-%d-%s" % (self.issue, issue_document["snapshot_sha256"]), source, [("workflow-work-type/issue-snapshot.json", issue_document)], 0)
        baseline = _translate(lambda: adapter.build_baseline(self.issue, self.family, issue_binding), "runtime")
        baseline_binding = self._publish("work-type-baseline", "baseline-%s" % baseline["baseline_sha256"], issue_binding, [("workflow-work-type/baseline.json", baseline)], 0)
        classification = request.get("classification") if isinstance(request, dict) else None
        _require(isinstance(classification, dict), "unsupported", "classification-required", "Initialization requires a classification")
        triage_request = _with_digest({"format": work_type_policy.TRIAGE_REQUEST_FORMAT, "issue_snapshot": {"binding": issue_binding, "document": issue_document}, "baseline": {"binding": baseline_binding, "document": baseline}, "classification": classification}, "request_sha256")
        triage = _translate(lambda: work_type_policy.classify(self.root, triage_request, issue_binding["sha256"], baseline_binding["sha256"]), "work-type")
        _require(triage["route"]["work_type"] == "implementation", "unsupported", "unsupported-route-not-activated", "Only implementation routing is activated")
        triage_binding = self._publish("work-type-triage", "triage-%s" % triage["result_sha256"], baseline_binding, [("workflow-work-type/triage.json", triage)], 0)
        runtime_pin = _translate(lambda: adapter.build_reconstruction_pin(self.issue, self.family, baseline_binding, triage_binding), "runtime")
        runtime_binding = self._publish("runtime-reconstruction", "pin-%s" % runtime_pin["pin_sha256"], triage_binding, [(RUNTIME_PIN_PATH, runtime_pin)], 0)
        implementation, _node = self._node("implementation-a", baseline_binding, [("issue-snapshot", issue_binding), ("runtime-reconstruction", runtime_binding), ("triage", triage_binding)], baseline_binding, None, [], 0)
        policy_state = _translate(lambda: policy.initialize(self.root, self.issue, self.family, {"binding": implementation, "migration_plan": None}), "policy")
        try: config = json.loads(base64.b64decode(baseline["config"]["bytes_base64"], validate=True))
        except (KeyError, TypeError, ValueError, UnicodeError, json.JSONDecodeError, RecursionError) as error: _fail("corrupt", "baseline-config-invalid", "Baseline configuration cannot be decoded: %s" % error)
        supervision_state = _translate(lambda: supervision.initialize(self.issue, self.family, baseline_binding, config["orchestrator"]["supervision"]), "supervision-policy")
        supervision_binding = self._publish_supervision(supervision_state, baseline_binding, None, 0)
        policy_binding = self._publish_policy(policy_state, implementation, None, 0); state = self._base(triage_binding=triage_binding, policy_state_binding=policy_binding, supervision_policy_binding=supervision_binding)
        binding, committed = self._commit(state); return self._result("initialized", state, binding, committed)
    def plan(self):
        inspection = self._status(); state = self._selected_state(inspection)
        return {"format": "chess-echo-orchestration-plan-v1", "outcome": {"status": "resolved", "code": "planned"}, "issue": self.issue, "generation": state["generation"], "phase": state["phase"], "pointer_sha256": inspection["pointer_sha256"], "pending": state["pending"], "pending_result_query": self._pending_result_query(state, inspection), "next_action": _next(state)}
    def inspect(self):
        inspection = self._status(); state = self._selected_state(inspection)
        result = copy.deepcopy(inspection)
        result["pending_result_query"] = self._pending_result_query(state, inspection)
        return result
    def _pending_result_query(self, state, inspection):
        return resume.pending_result_query(self.issue, self.family, inspection["authority"], state["pending"])
    def _validation_records(self, state, inspection):
        records, seen = [], set()
        for _binding, item in self._history(state, inspection["authority"]):
            result_binding = self._candidate_binding(item, "execution-result", required=False)
            if result_binding is None or result_binding["sha256"] in seen: continue
            result = self._read(result_binding, RESULT_PATH, "execution result"); request = self._read(result["request_binding"], label="execution request")
            if request["operation"]["kind"] == "validation":
                records.append((request["operation"]["name"], result["request_binding"], result_binding)); seen.add(result_binding["sha256"])
        return records
    def _prior_pr_result(self, state, inspection):
        for _binding, item in reversed(self._history(state, inspection["authority"])):
            result_binding = self._candidate_binding(item, "execution-result", required=False)
            if result_binding is None: continue
            result = self._read(result_binding, RESULT_PATH, "execution result"); request = self._read(result["request_binding"], label="execution request")
            if request["operation"]["kind"] == "github-write": return result, result_binding
        return None, None
    def _unresolved_pr_request(self, state, inspection):
        for _authority, item in reversed(self._history(state, inspection["authority"])):
            binding = self._candidate_binding(item, "execution-request", required=False)
            if binding is not None and self._read(binding, label="execution request")["operation"]["kind"] == "github-write": return binding
        return None
    def _pr_context(self, state, observation):
        final = self._active(state, "final-review"); wrapper = self._read(final, NODE_PATH, "final review node")
        candidate = self._candidate_schema(self._read(wrapper["subject_binding"], label="final review candidate"), "review")
        pr = _translate(lambda: resume.validate_pr_metadata(candidate.get("pr")), "resume")
        _triage, baseline, _baseline_binding, _config = self._facts(state)
        expectation = {"repository": baseline["repository"], "base_ref": baseline["target_base"]["name"], "base_sha": observation["base"]["commit"], "head_ref": pr["head_ref"], "head_sha": observation["head"]["commit"], "title_sha256": inspector.sha256(pr["title"].encode()), "body_sha256": inspector.sha256(pr["body"].encode())}
        return pr, expectation, wrapper
    def _pr_number(self, result, supplied):
        return _translate(lambda: resume.resolve_pr_number(result, supplied), "resume")
    def _claim(self, inspection, state, supplied):
        phase = state["phase"]; triage, baseline, baseline_binding, config = self._facts(state); role = profile = expectation = None
        if phase in AGENT_PHASES:
            role, name = AGENT_PHASES[phase]; operation = {"kind": "agent", "name": name, "role": role}
        elif phase == "VALIDATION":
            profile = triage["classification"]["validation_profile"]; checks = next(item["checks"] for item in baseline["profiles"] if item["id"] == profile)
            done = [name for name, _request, _result in self._validation_records(state, inspection)]
            _require(done == [item["name"] for item in checks[:len(done)]], "stale", "validation-order-stale", "Validation history does not match the profile order")
            _require(len(done) < len(checks), "corrupt", "validation-already-complete", "Validation completion was not bound")
            operation = {"kind": "validation", "name": checks[len(done)]["name"], "role": None}
        elif phase == "PR_PREPARATION":
            prior, _prior_binding = self._prior_pr_result(state, inspection)
            unresolved = self._unresolved_pr_request(state, inspection)
            operation = {"kind": "github-write", "name": "create-draft-pr", "role": None} if prior is None and unresolved is None else {"kind": "github-read", "name": "observe-draft-pr-%d" % self._pr_number(prior, supplied), "role": None}
        else: _fail("unsupported", "phase-not-steppable", "Phase has no executable action", phase)
        if operation["kind"] == "github-write": self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True); self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
        expected_repository = self._phase_repository(state, phase)
        adapter = self._runtime_drift(lambda: self._runtime(supplied, state, inspection), "repository-continuity-stale", "Repository changed after the selected phase evidence; restore it before retrying", {"runtime-worktree-untrusted", "runtime-phase-repository-changed", "runtime-initial-repository-changed"})
        before = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        if expected_repository is not None:
            if not (runtime.same_repository(expected_repository, before) and runtime.clean_repository(before)):
                _fail("stale", "repository-continuity-stale", "Repository changed after the selected phase evidence; restore it before retrying")
        elif phase == "PLANNING":
            _require(runtime.clean_repository(before) and before["head"]["commit"] == before["base"]["commit"] and before["ancestry"] == {"base_is_ancestor": True, "commit_count": 0} and not before["changes"], "stale", "repository-continuity-stale", "Initial planning must use the clean bootstrap base")
        if operation["kind"] == "github-write": _pr, expectation, _wrapper = self._pr_context(state, before)
        active = {row["node"]: row["binding"] for row in self._read(state["policy_state_binding"], POLICY_PATH, "policy state")["active"]}
        required = REQUIRED_NODES.get(phase, ())
        extra = [("baseline", baseline_binding)] + [(node, active[node]) for node in required]
        if phase == "PLANNING": extra.append(("issue-snapshot", triage["issue_snapshot_binding"]))
        if phase == "PLAN_REVIEW": extra.append(("plan-snapshot", self._candidate_binding(state, "plan-snapshot")))
        if phase == "PLANNING" and self._candidate_binding(state, "plan-snapshot", required=False) is not None:
            extra += [("plan-snapshot", self._candidate_binding(state, "plan-snapshot")),
                      ("plan-review", self._candidate_binding(state, "plan-review"))]
        extra += self._rejection_inputs(state, phase)
        source, limits = _translate(lambda: runtime.command_source(baseline_binding, baseline, config, operation, role, profile), "runtime"); inputs = runtime.execution_inputs(state, extra)
        attempt = _translate(lambda: runtime.execution_attempt_id(inspection["authority"], operation, source, inputs, before, limits, expectation), "runtime")
        request = _translate(lambda: adapter.build_request(issue=self.issue, family_run_id=self.family, attempt_id=attempt, authority_binding=inspection["authority"], operation=operation, command_source=source, input_bindings=inputs, repository_before=before, limits=limits, reconciliation_expectation=expectation), "runtime")
        request_binding = self._publish("execution-request", "attempt-%s" % attempt, inspection["authority"], [("workflow-orchestration/execution-request.json", request)], state["generation"] + 1)
        before_binding = self._publish("repository-observation", "before-%s" % attempt, request_binding, [("workflow-orchestration/repository-observation.json", before)], state["generation"] + 1)
        pending = {"attempt_id": attempt, "kind": operation["kind"], "request_binding": request_binding, "status": "requested"}
        successor = self._successor(state, inspection["authority"], pending=pending, candidates=_put(state["candidates"], "execution-request", request_binding), transition={"type": CLAIM_TYPES[phase], "request_binding": request_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": before_binding})
        binding, committed = self._commit(successor); _require(committed["outcome"]["code"] == "committed", "busy", "attempt-in-flight", "Another caller already owns this execution attempt")
        selected = {"authority": binding, "pointer_sha256": committed["pointer_sha256"]}
        self._selected, self._selected_inspection = successor, selected
        return self._run_pending(selected, successor, supplied)
    def _watch(self, inspection, pending, limits):
        cancelled, stop = threading.Event(), threading.Event()
        def watch():
            while not stop.wait(0.1):
                try:
                    current = authority.status(self.root, self.issue)
                    if current["pointer_sha256"] == inspection["pointer_sha256"]: continue
                    active = self._state(current).get("pending")
                    if active and active["attempt_id"] == pending["attempt_id"] and active["status"] == "cancel-requested": cancelled.set()
                    else: cancelled.set()
                    return
                except (OrchestratorFailure, authority.AuthorityFailure, evidence.EvidenceFailure, inspector.InspectionFailure, OSError, KeyError, TypeError):
                    cancelled.set(); return
        worker = threading.Thread(target=watch, name="workflow-cancel-watch", daemon=True); worker.start(); return cancelled, stop, worker
    def _unchanged(self, inspection): _require(self._status()["pointer_sha256"] == inspection["pointer_sha256"], "stale", "attempt-result-stale", "Authority changed while an attempt executed")
    def _execution_result(self, state, inspection, request_binding, result, attachments=()):
        query = self._pending_result_query(state, inspection); recorder = getattr(PENDING_RESULT_PROVIDER, "prepare", None); _require(callable(recorder), "unsupported", "pending-result-provider-unavailable", "Pending-result provider cannot durably index execution results")
        binding = self._publish("execution-result", "attempt-%s" % result["attempt_id"], request_binding, [(RESULT_PATH, result)], state["generation"] + 1, before_binding_reference=lambda reference, data: recorder(copy.deepcopy(query), copy.deepcopy(reference), data), attachments=attachments)
        if attachments: _translate(lambda: runtime.verify_result_attachments(_translate(lambda: evidence.project(self.root, binding), "evidence"), result), "runtime")
        after = result.get("repository_after")
        after_binding = None if after is None else self._publish("work-type-diff-observation", "observation-%s" % after["observation_sha256"], state["triage_binding"], [("workflow-work-type/diff-observation.json", after)], state["generation"] + 1)
        return binding, after_binding, _put(_put(state["candidates"], "execution-request", request_binding), "execution-result", binding)
    def _pause(self, state, inspection, rows, code):
        successor = self._successor(state, inspection["authority"], phase="PAUSED", pending=None, candidates=rows, transition={"type": "pause", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result(code, successor, binding, committed, "paused")
    def _candidate(self, result, expected): return _translate(lambda: resume.decode_candidate(result, expected), "resume")
    def _candidate_schema(self, value, expected): return _translate(lambda: resume.candidate_schema(value, expected), "resume")
    def _candidate_artifact(self, state, result, candidate):
        details = CANDIDATE_ARTIFACTS.get(state["phase"])
        if details is None: return result
        digest = _digest(candidate)
        return self._publish(details[0], "%s-%s" % (details[0], digest), result, [(details[1], candidate)], state["generation"] + 1)
    def _snapshot(self, state, candidate, predecessor=None):
        _lines, units = _translate(lambda: resume.validate_plan_candidate(candidate), "resume")
        triage, _baseline, baseline_binding, _config = self._facts(state)
        document = {"format": plan_policy.SNAPSHOT_FORMAT, "issue": self.issue, "family_run_id": self.family, "revision": 1 if predecessor is None else predecessor["document"]["revision"] + 1, "context": {"issue_snapshot_binding": triage["issue_snapshot_binding"], "baseline_binding": baseline_binding, "triage_binding": state["triage_binding"]}, "predecessor": None if predecessor is None else {"plan_binding": predecessor["binding"], "review_binding": predecessor["review_binding"]}, "plan": {"path": plan_policy.PLAN_PATH, "content_sha256": inspector.sha256(candidate["plan"].encode()), "size": len(candidate["plan"].encode())}, "units": units}
        document = _with_digest(document, "snapshot_sha256")
        binding = self._publish("plan-snapshot", "snapshot-%s" % document["snapshot_sha256"], state["triage_binding"], [(plan_policy.PLAN_PATH, candidate["plan"].encode()), (plan_policy.SNAPSHOT_PATH, document)], state["generation"] + 1)
        return {"binding": binding, "document": document}
    def _revision(self, state, candidate, prior, review, current):
        value = candidate["revision"]; _require(isinstance(value, dict) and set(value) == {"diff", "changes"} and isinstance(value["diff"], str) and isinstance(value["changes"], list), "corrupt", "candidate-output-invalid", "Revision candidate is invalid")
        dispositions = [{"finding_id": finding["id"], "status": "addressed", "unit_ids": finding["unit_ids"], "reason": "Addressed by the proposed revision."} for finding in review["document"]["findings"]]
        document = {"format": plan_policy.REVISION_FORMAT, "issue": self.issue, "family_run_id": self.family, "prior_plan_binding": prior["binding"], "prior_review_binding": review["binding"], "current_plan_binding": current["binding"], "diff": {"format": plan_policy.DIFF_FORMAT, "path": plan_policy.DIFF_PATH, "algorithm": "sequence-matcher-unified-3-autojunk-false-v1", "old_label": "a/plan.md", "new_label": "b/plan.md", "context_lines": 3, "content_sha256": inspector.sha256(value["diff"].encode()), "size": len(value["diff"].encode())}, "changes": value["changes"], "dispositions": dispositions}
        document = _with_digest(document, "revision_sha256")
        binding = self._publish("plan-revision", "revision-%s" % document["revision_sha256"], review["binding"], [(plan_policy.REVISION_PATH, document), (plan_policy.DIFF_PATH, value["diff"].encode())], state["generation"] + 1)
        return {"binding": binding, "document": document}
    def _review(self, candidate, snapshot, generation, revision=None, prior_review=None):
        verdict, findings = _translate(lambda: resume.validate_review_candidate(candidate, snapshot["binding"]), "resume")
        units, escalated = snapshot["document"]["units"], verdict == "full-review-required"
        coverage = [{"unit_id": unit["id"], "content_sha256": unit["content_sha256"], "method": "incremental" if escalated else "full", "source_review_binding": None} for unit in units]
        outcomes = [] if prior_review is None else [{"finding_id": row["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Addressed by this review."} for row in prior_review["document"]["findings"]]
        document = {"format": plan_policy.REVIEW_FORMAT, "issue": self.issue, "family_run_id": self.family, "plan_binding": snapshot["binding"], "revision_binding": None if revision is None else revision["binding"], "mode": "incremental" if escalated else "full", "reviewer": {"role": "independent-reviewer", "actor": "configured-reviewer"}, "coverage": coverage, "prior_finding_outcomes": outcomes, "findings": findings, "dependency_assessment": {"status": "unbounded" if escalated else "complete", "reviewed_units": [unit["id"] for unit in units], "reason": "A full review is required." if escalated else "All plan units were reviewed."}, "verdict": verdict, "full_review_reason": "Escalated by reviewer." if escalated else None}
        document = _with_digest(document, "review_sha256"); subject = snapshot["binding"] if revision is None else revision["binding"]
        return {"binding": self._publish("plan-review", "review-%s" % document["review_sha256"], subject, [(plan_policy.REVIEW_PATH, document)], generation), "document": document}
    def _plan_submit(self, state, inspection, rows, candidate):
        prior_binding = self._candidate_binding(state, "plan-snapshot", required=False)
        active = self._read(state["policy_state_binding"], POLICY_PATH, "policy state")["active"]
        if prior_binding is not None and any(item["node"] == "plan-approval" for item in active):
            return self._pause(state, inspection, rows, "unsupported-policy-transition")
        if prior_binding is None:
            snapshot = self._snapshot(state, candidate); request = _with_digest({"format": plan_policy.BASELINE_REQUEST_FORMAT, "plan": snapshot, "current_review": None}, "request_sha256")
            verdict = _translate(lambda: plan_policy.evaluate_baseline(self.root, request, snapshot["binding"]["sha256"]), "plan-policy"); rows = _put(rows, "plan-snapshot", snapshot["binding"])
        else:
            if self._candidate_binding(state, "plan-revision", required=False) is not None:
                return self._pause(state, inspection, rows, "unsupported-policy-transition")
            review_binding = self._candidate_binding(state, "plan-review"); prior = {"binding": prior_binding, "document": self._read(prior_binding, plan_policy.SNAPSHOT_PATH)}; review = {"binding": review_binding, "document": self._read(review_binding, plan_policy.REVIEW_PATH)}
            snapshot = self._snapshot(state, candidate, {"binding": prior_binding, "document": prior["document"], "review_binding": review_binding}); revision = self._revision(state, candidate, prior, review, snapshot)
            request = _with_digest({"format": plan_policy.REVISION_REQUEST_FORMAT, "prior_plan": prior, "prior_review": review, "current_plan": snapshot, "revision": revision, "current_review": None}, "request_sha256")
            verdict = _translate(lambda: plan_policy.evaluate_revision(self.root, request, prior_binding["sha256"], review_binding["sha256"], snapshot["binding"]["sha256"], revision["binding"]["sha256"]), "plan-policy"); rows = _put(_put(rows, "plan-snapshot", snapshot["binding"]), "plan-revision", revision["binding"])
        _require(verdict["outcome"]["code"] == "review-required", "paused", "plan-policy-unexpected", "Plan policy did not request technical review")
        successor = self._successor(state, inspection["authority"], phase="PLAN_REVIEW", candidates=rows, transition={"type": "plan-request", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _plan_review(self, state, inspection, after, rows, candidate):
        snapshot_binding = self._candidate_binding(state, "plan-snapshot"); snapshot = {"binding": snapshot_binding, "document": self._read(snapshot_binding, plan_policy.SNAPSHOT_PATH)}
        revision_binding = self._candidate_binding(state, "plan-revision", required=False); revision = None if revision_binding is None else {"binding": revision_binding, "document": self._read(revision_binding, plan_policy.REVISION_PATH)}; prior = prior_review = None
        if revision is not None:
            prior_binding, review_binding = revision["document"]["prior_plan_binding"], revision["document"]["prior_review_binding"]
            prior, prior_review = {"binding": prior_binding, "document": self._read(prior_binding, plan_policy.SNAPSHOT_PATH)}, {"binding": review_binding, "document": self._read(review_binding, plan_policy.REVIEW_PATH)}
        review = self._review(candidate, snapshot, state["generation"] + 1, revision, prior_review); rows = _put(_put(rows, "plan-review", review["binding"]), "plan-observation", after)
        if revision is None:
            request = _with_digest({"format": plan_policy.BASELINE_REQUEST_FORMAT, "plan": snapshot, "current_review": review}, "request_sha256"); verdict = _translate(lambda: plan_policy.evaluate_baseline(self.root, request, snapshot_binding["sha256"], review["binding"]["sha256"]), "plan-policy")
        else:
            request = _with_digest({"format": plan_policy.REVISION_REQUEST_FORMAT, "prior_plan": prior, "prior_review": prior_review, "current_plan": snapshot, "revision": revision, "current_review": review}, "request_sha256"); verdict = _translate(lambda: plan_policy.evaluate_revision(self.root, request, prior["binding"]["sha256"], prior_review["binding"]["sha256"], snapshot_binding["sha256"], revision_binding["sha256"], review["binding"]["sha256"]), "plan-policy")
        code = verdict["outcome"]["code"]
        if code == "technical-review-accepted":
            _challenge, pending = self._challenge(state, inspection["authority"], "plan", [("plan-review", review["binding"]), ("plan-snapshot", snapshot_binding)], after, state["generation"] + 1); phase = "WAITING_FOR_PLAN_APPROVAL"
        elif code == "technical-review-needs-revision": pending, phase = None, "PLANNING"
        elif code == "technical-review-escalated": pending, phase = None, "PLAN_REVIEW"
        else: _fail("paused", "plan-policy-unexpected", "Plan policy returned an unsupported technical verdict")
        successor = self._successor(state, inspection["authority"], phase=phase, candidates=rows, pending=pending, transition={"type": "plan-review", "request_binding": None if pending is None else pending["request_binding"], "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _review_submit(self, state, inspection, review_binding, after, rows, candidate):
        if candidate["verdict"] != "accepted": return self._pause(state, inspection, rows, "unsupported-policy-transition")
        manifest = self._active(state, "test-manifest"); document = self._read(manifest, NODE_PATH, "test manifest node")
        _challenge, pending = self._challenge(state, inspection["authority"], "tests", [("test-manifest", manifest), ("test-review", review_binding)], document["repository_observation_binding"], state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="WAITING_FOR_TEST_APPROVAL", candidates=_put(rows, "test-review", review_binding), pending=pending, transition={"type": "tests-review", "request_binding": pending["request_binding"], "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _implementation_submit(self, state, inspection, report_binding, after, rows):
        _require(after is not None, "stale", "repository-after-missing", "Implementation attempt lacks a repository observation")
        triage, baseline, baseline_binding, _config = self._facts(state); observation = self._read(after, label="implementation observation")
        issue_binding = triage["issue_snapshot_binding"]; issue = self._read(issue_binding, label="issue snapshot")
        request = _with_digest({"format": work_type_policy.COMPLETION_REQUEST_FORMAT, "issue_snapshot": {"binding": issue_binding, "document": issue}, "baseline": {"binding": baseline_binding, "document": baseline}, "triage": {"binding": state["triage_binding"], "document": triage}, "observation": {"binding": after, "document": observation}, "artifact": None, "review": None, "acceptance": None, "documentation_content_check": None, "documentation_diff_check": None}, "request_sha256")
        assessment = _translate(lambda: work_type_policy.assess_completion(self.root, request, issue_binding["sha256"], baseline_binding["sha256"], state["triage_binding"]["sha256"], after["sha256"]), "work-type")
        _require(assessment["outcome"]["code"] == "implementation-route-conforms" and observation["ancestry"] == {"base_is_ancestor": True, "commit_count": 1}, "stale", "implementation-observation-invalid", "Implementation must be one clean policy-conforming commit")
        manifest = self._read(self._active(state, "test-manifest"), NODE_PATH, "test manifest node")
        approved = self._read(manifest["repository_observation_binding"], label="approved test observation")
        profile = next(item for item in baseline["profiles"] if item["id"] == triage["classification"]["validation_profile"])
        if runtime.test_changes(observation, profile["test_paths"]) != approved["changes"]:
            return self._pause(state, inspection, rows, "unsupported-policy-transition")
        _wrapper, policy_binding = self._bind(state, inspection, "implementation-submission", report_binding, [("implementation-report", report_binding)], after)
        successor = self._successor(state, inspection["authority"], phase="VALIDATION", policy_state_binding=policy_binding, candidates=_drop(rows, "implementation-report"), transition={"type": "implementation-submit", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _validation_submit(self, state, inspection, request_binding, result_binding, after, rows):
        _require(after is not None, "stale", "repository-after-missing", "Validation lacks post-observation")
        triage, baseline, _baseline_binding, _config = self._facts(state); profile = next(item for item in baseline["profiles"] if item["id"] == triage["classification"]["validation_profile"])
        records = self._validation_records(state, inspection) + [(self._read(request_binding, label="execution request")["operation"]["name"], request_binding, result_binding)]
        expected = [item["name"] for item in profile["checks"]]; _require([item[0] for item in records] == expected[:len(records)], "stale", "validation-order-stale", "Validation results are out of profile order")
        first_before = self._read(records[0][1], label="validation request")["repository_before"]
        implementation_node = self._read(self._active(state, "implementation-submission"), NODE_PATH, "implementation node")
        implementation_observation = self._read(implementation_node["repository_observation_binding"], label="implementation observation")
        stable = runtime.clean_repository(first_before) and runtime.same_repository(implementation_observation, first_before)
        for _name, request, result in records:
            request_document = self._read(request, label="validation request")
            result_document = self._read(result, RESULT_PATH, "validation result")
            stable = stable and result_document["outcome"] == "succeeded" and runtime.same_repository(first_before, request_document["repository_before"]) and runtime.same_repository(first_before, result_document["repository_after"])
        if not stable: return self._pause(state, inspection, rows, "unsupported-policy-transition")
        if len(records) < len(expected):
            successor = self._successor(state, inspection["authority"], candidates=rows, transition={"type": "validation-record", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
            binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
        pre = state["transition"]["repository_observation_binding"]; _require(pre is not None, "corrupt", "validation-pre-observation-missing", "Validation request lacks its pre-observation")
        implementation = self._active(state, "implementation-submission"); observed = self._read(after, label="validation observation")
        validation = {"format": "chess-echo-comprehensive-validation-v1", "issue": self.issue, "family_run_id": self.family, "triage_binding": state["triage_binding"], "implementation_binding": implementation, "base_observation_binding": pre, "pre_observation_binding": pre, "checks": [{"name": name, "request_binding": request, "result_binding": result} for name, request, result in records], "post_observation_binding": after, "head": observed["head"]["commit"], "base": observed["base"]["commit"], "status": "pass"}
        validation = _with_digest(validation, "validation_sha256")
        validation_binding = self._publish("comprehensive-validation", "validation-%s" % validation["validation_sha256"], implementation, [("workflow-orchestration/comprehensive-validation.json", validation)], state["generation"] + 1)
        evidence_rows = [("check-%03d-execution-request" % index, request) for index, (_name, request, _result) in enumerate(records)] + [("check-%03d-execution-result" % index, result) for index, (_name, _request, result) in enumerate(records)]
        _wrapper, policy_binding = self._bind(state, inspection, "validation", validation_binding, evidence_rows, after)
        successor = self._successor(state, inspection["authority"], phase="FINAL_REVIEW", policy_state_binding=policy_binding, candidates=_drop(rows, "validation"), transition={"type": "validation-record", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _final_submit(self, state, inspection, review_binding, after, rows, candidate):
        pr = candidate.get("pr")
        valid_pr = isinstance(pr, dict) and set(pr) == {"head_ref", "title", "body"} and all(isinstance(pr.get(key), str) and pr[key] for key in ("head_ref", "title", "body"))
        if not (candidate["verdict"] == "accepted" and valid_pr and resume.valid_pr_body(pr["body"])):
            return self._pause(state, inspection, rows, "unsupported-policy-transition")
        validation_node = self._active(state, "validation"); validation = self._read(validation_node, NODE_PATH, "validation node")
        _wrapper, policy_binding = self._bind(state, inspection, "final-review", review_binding, [("comprehensive-validation", validation["subject_binding"])], validation["repository_observation_binding"])
        _challenge, pending = self._challenge(state, inspection["authority"], "final", [("final-review", review_binding), ("validation", validation["subject_binding"])], validation["repository_observation_binding"], state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="WAITING_FOR_FINAL_APPROVAL", policy_state_binding=policy_binding, candidates=_drop(rows, "final-review"), pending=pending, transition={"type": "final-review", "request_binding": pending["request_binding"], "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _after_agent(self, state, inspection, request_binding, candidate_binding, after, rows, candidate):
        phase = state["phase"]
        if phase == "PLANNING": return self._plan_submit(state, inspection, rows, candidate)
        if phase == "PLAN_REVIEW": return self._plan_review(state, inspection, after, rows, candidate)
        if phase == "TEST_IMPLEMENTATION": return self._tests_submit(state, inspection, candidate_binding, after, rows)
        if phase == "TEST_REVIEW": return self._review_submit(state, inspection, candidate_binding, after, rows, candidate)
        if phase == "IMPLEMENTATION": return self._implementation_submit(state, inspection, candidate_binding, after, rows)
        return self._final_submit(state, inspection, candidate_binding, after, rows, candidate)
    def _challenge(self, state, authority_binding, gate, subjects, repository, generation):
        policy_document = self._supervision(state)
        subject_rows = [{"slot": slot, "binding": binding} for slot, binding in sorted(subjects, key=lambda item: item[0].encode())]
        document = _translate(lambda: supervision.build_gate_challenge(state["supervision_policy_binding"], policy_document, authority_binding, gate, subject_rows, repository), "supervision-policy")
        binding = self._publish("gate-challenge", "challenge-%s" % document["challenge_sha256"], authority_binding, [(supervision.CHALLENGE_PATH, document)], generation)
        kind = "human" if document["mode"] == "supervised" else "policy"
        return binding, {"attempt_id": _digest({"authority": authority_binding, "kind": kind, "challenge": binding}), "kind": kind, "request_binding": binding, "status": "requested"}
    def _check_challenge(self, state, challenge, gate, predecessor):
        return _translate(lambda: supervision.validate_gate_challenge(challenge, state["supervision_policy_binding"], self._supervision(state), predecessor, gate), "supervision-policy")
    def _recovery_challenge(self, authority_binding, subjects, generation):
        core = {"format": RECOVERY_CHALLENGE_FORMAT, "issue": self.issue, "family_run_id": self.family, "gate": "recovery", "decision": "approve", "authority_binding": authority_binding, "subjects": [{"slot": slot, "binding": binding} for slot, binding in sorted(subjects, key=lambda item: item[0].encode())], "repository_observation_binding": None}
        digest = _digest(core); document = dict(core); document.update({"confirmation": "approve recovery %s" % digest, "challenge_sha256": digest})
        binding = self._publish("human-challenge", "challenge-%s" % digest, authority_binding, [("workflow-orchestration/human-challenge.json", document)], generation)
        return binding, {"attempt_id": _digest({"authority": authority_binding, "kind": "human", "challenge": binding}), "kind": "human", "request_binding": binding, "status": "requested"}
    def _check_recovery_challenge(self, challenge, predecessor): _translate(lambda: resume.verify_recovery_challenge(challenge, predecessor, self.issue, self.family), "resume")
    def _run_pr_read(self, state, inspection, pending, request):
        try: number = int(request["operation"]["name"].rsplit("-", 1)[1])
        except (KeyError, TypeError, ValueError): _fail("corrupt", "pr-read-request-invalid", "PR observation request has no valid number")
        self._unchanged(inspection); adapter = self._runtime(None, state, inspection, request["repository_before"]); observation = _translate(lambda: adapter.observe_pull_request(self.issue, number, pending["request_binding"]), "runtime"); self._unchanged(inspection)
        query = self._pending_result_query(state, inspection); recorder = getattr(PENDING_RESULT_PROVIDER, "prepare", None); _require(callable(recorder), "unsupported", "pending-result-provider-unavailable", "Pending-result provider cannot durably index PR observations")
        observed = self._publish("github-pr-observation", "pr-%d-%s" % (number, observation["observation_sha256"]), pending["request_binding"], [("workflow-orchestration/github-pr-observation.json", observation)], state["generation"] + 1, before_binding_reference=lambda reference, data: recorder(copy.deepcopy(query), copy.deepcopy(reference), data))
        handoff = resume.build_handoff(inspection["authority"], pending["request_binding"], pr_observation_binding=observed)
        return self._handoff_result(state, inspection, handoff)
    def _finalize_pr_read(self, state, inspection, request, observed):
        observation = self._read(observed, label="PR observation"); number = observation["number"]
        _pr, expected, _final = self._pr_context(state, request["repository_before"])
        _require(all(observation[key] == expected[key] for key in expected) and observation["state"] == "OPEN" and observation["draft"] is True, "stale", "pr-reconciliation-mismatch", "Observed PR is not the exact open draft requested")
        before, final = state["transition"]["repository_observation_binding"], self._active(state, "final-review")
        wrapper, policy_binding = self._bind(state, inspection, "pr-metadata", final, [("github-pr-observation", observed)], before)
        successor = self._successor(state, inspection["authority"], phase="PR_PREPARATION", policy_state_binding=policy_binding, pending=None, candidates=_put(state["candidates"], "pr-metadata", wrapper), transition={"type": "pr-reconcile", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _run_pending(self, inspection, state, supplied):
        pending = state["pending"]; _require(pending["status"] == "requested", "paused", "cancel-requested", "Cancelled attempt requires human recovery")
        _require(pending["kind"] not in {"human", "human-rejection", "policy"}, "unsupported", "gate-requires-satisfaction", "Gate challenges require an explicit human or policy decision")
        request = self._read(pending["request_binding"], label="execution request")
        if pending["kind"] == "github-read": return self._run_pr_read(state, inspection, pending, request)
        adapter = self._runtime(supplied, state, inspection, request["repository_before"]); cancel, stop, worker = self._watch(inspection, pending, request["limits"])
        if self._status()["pointer_sha256"] != inspection["pointer_sha256"]: cancel.set()
        try:
            options = {"cancel_event": cancel}
            if pending["kind"] == "agent":
                _require(SANDBOX_PROVIDER is not None, "unsupported", "sandbox-provider-unavailable", "Agent execution requires a reviewed sandbox provider")
                options["sandbox_provider"] = SANDBOX_PROVIDER(self.root, self.issue, request["operation"]["role"])
            if pending["kind"] == "github-write":
                payload, expectation, _wrapper = self._pr_context(state, request["repository_before"])
                def pre_write_check(_remote_head):
                    self._unchanged(inspection)
                    self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True); self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
                    self._unchanged(inspection)
                    return True
                options.update({"reconciliation_expectation": expectation, "write_payload": {"repository": expectation["repository"], "base_ref": expectation["base_ref"], "head_ref": payload["head_ref"], "title": payload["title"], "body": payload["body"]}, "pre_write_check": pre_write_check})
            try: bundle = _translate(lambda: adapter.execute_bundle(request, pending["request_binding"], **options), "runtime")
            except OrchestratorFailure:
                self._unchanged(inspection)
                if cancel.is_set(): _fail("stale", "attempt-result-stale", "Cancelled attempt cannot become authoritative")
                raise
        finally:
            stop.set(); worker.join(2)
            _require(not worker.is_alive(), "conflict", "cancel-watcher-stuck", "Cancellation watcher did not stop")
        result = bundle.document; self._unchanged(inspection); result_binding, after, rows = self._execution_result(state, inspection, pending["request_binding"], result, bundle.attachments)
        handoff = resume.build_handoff(inspection["authority"], pending["request_binding"], result_binding=result_binding, repository_after_binding=after)
        return self._handoff_result(state, inspection, handoff)
    def _discover_pending(self, state, inspection):
        _require(PENDING_RESULT_PROVIDER is not None, "busy", "attempt-in-flight", "Pending execution requires its exact result handoff or reviewed result discovery")
        query = self._pending_result_query(state, inspection)
        try:
            discovery = PENDING_RESULT_PROVIDER(self.root, self.issue, copy.deepcopy(query))
        except OrchestratorFailure:
            raise
        except (OSError, TypeError, ValueError) as error:
            _fail("corrupt", "pending-result-discovery-failed", "Pending result discovery failed closed: %s" % error)
        kind, binding = _translate(lambda: resume.validate_discovery_response(discovery, query), "resume")
        self._unchanged(inspection)
        return resume.build_handoff(inspection["authority"], state["pending"]["request_binding"], result_binding=None if kind == "github-pr-observation" else binding, pr_observation_binding=binding if kind == "github-pr-observation" else None)
    def _verified_handoff(self, state, inspection, supplied):
        _translate(lambda: resume.validate_handoff_shape(supplied, inspection["authority"], state["pending"]["request_binding"]), "resume")
        request = self._read(supplied["request_binding"], label="execution request")
        if state["pending"]["kind"] == "github-read":
            projection = _translate(lambda: evidence.project(self.root, supplied["pr_observation_binding"]), "evidence")
            identity = projection["identity"]
            _require(supplied["result_binding"] is None and supplied["repository_after_binding"] is None and identity["issue"] == self.issue and identity["family_run_id"] == self.family and projection["decision"]["type"] == "github-pr-observation" and projection["subject"] == supplied["request_binding"], "stale", "execution-handoff-stale", "PR observation handoff is stale")
            return request, None, None, supplied["pr_observation_binding"]
        projection = _translate(lambda: evidence.project(self.root, supplied["result_binding"]), "evidence")
        result = self._read(supplied["result_binding"], RESULT_PATH, "execution result")
        unsigned = dict(result); digest = unsigned.pop("result_sha256", None)
        identity = projection["identity"]
        _require(identity["issue"] == self.issue and identity["family_run_id"] == self.family and projection["decision"]["type"] == "execution-result" and projection["subject"] == supplied["request_binding"] and result.get("format") == runtime.RESULT_FORMAT and result.get("request_binding") == supplied["request_binding"] and result.get("attempt_id") == state["pending"]["attempt_id"] and digest == _digest(unsigned), "stale", "execution-handoff-stale", "Execution result handoff is stale")
        _translate(lambda: runtime.verify_result_attachments(projection, result), "runtime")
        after = supplied["repository_after_binding"]
        if after is None and result.get("repository_after") is not None:
            observation = result["repository_after"]
            after = self._publish("work-type-diff-observation", "observation-%s" % observation["observation_sha256"], state["triage_binding"], [("workflow-work-type/diff-observation.json", observation)], state["generation"] + 1)
        _require((after is None) == (result.get("repository_after") is None), "stale", "execution-handoff-stale", "Repository result handoff is incomplete")
        if after is not None: _require(self._read(after, label="repository observation") == result["repository_after"], "stale", "execution-handoff-stale", "Repository result handoff differs")
        return request, result, after, None
    def _finalize(self, inspection, state, supplied):
        pending = state["pending"]; request, result, after, observed = self._verified_handoff(state, inspection, supplied)
        if observed is not None:
            self._runtime(None, state, inspection, request["repository_before"])
            return self._finalize_pr_read(state, inspection, request, observed)
        self._runtime(None, state, inspection, result["repository_after"])
        result_binding, rows = supplied["result_binding"], _put(_put(state["candidates"], "execution-request", pending["request_binding"]), "execution-result", supplied["result_binding"])
        if pending["kind"] == "github-write":
            remote = result.get("reconciliation", {}).get("remote_head")
            before = request["repository_before"]
            head_ref = self._pr_context(state, before)[0]["head_ref"]
            _require(isinstance(remote, dict) and remote.get("format") == runtime.REMOTE_HEAD_OBSERVATION_FORMAT and remote.get("repository") == before["repository"] and remote.get("ref") == "refs/heads/%s" % head_ref and remote.get("sha") == before["head"]["commit"] and remote.get("repository_observation_sha256") == before["observation_sha256"], "stale", "trusted-remote-head-missing", "Draft PR result lacks the trusted remote-head observation")
            if result["outcome"] not in {"succeeded", "uncertain"}: return self._pause(state, inspection, rows, "pr-write-failed")
            successor = self._successor(state, inspection["authority"], candidates=rows, transition={"type": "pr-prepare", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
            binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
        if result["outcome"] != "succeeded": return self._pause(state, inspection, rows, "unsupported-policy-transition" if pending["kind"] == "validation" else "attempt-not-successful")
        if pending["kind"] == "validation": return self._validation_submit(state, inspection, pending["request_binding"], result_binding, after, rows)
        if state["phase"] in {"PLANNING", "PLAN_REVIEW", "TEST_REVIEW", "FINAL_REVIEW"}:
            if not (runtime.same_repository(request["repository_before"], result["repository_after"]) and runtime.clean_repository(result["repository_after"])):
                return self._pause(state, inspection, rows, "read-only-agent-repository-drift")
        expected = "plan" if state["phase"] == "PLANNING" else "implementer" if state["phase"] in {"TEST_IMPLEMENTATION", "IMPLEMENTATION"} else "review"
        try: candidate = self._candidate(result, expected)
        except OrchestratorFailure: return self._pause(state, inspection, rows, "candidate-output-invalid")
        candidate_binding = self._candidate_artifact(state, result_binding, candidate)
        return self._after_agent(state, inspection, pending["request_binding"], candidate_binding, after, rows, candidate)
    def advance(self, expected_tip, request):
        inspection = self._status(); state = self._selected_state(inspection)
        if state["pending"] is not None and expected_tip != inspection["pointer_sha256"]: _fail("busy" if state["pending"]["status"] == "requested" else "stale", "attempt-in-flight", "Another attempt owns this state")
        self._expect(inspection, expected_tip)
        if state["pending"] is not None:
            if state["pending"]["kind"] == "policy": return self._automatic(inspection, state)
            _require(state["pending"]["kind"] != "human-rejection", "unsupported", "human-gate-requires-rejection", "Human rejection challenges require reject")
            _require(state["pending"]["kind"] != "human", "unsupported", "human-gate-requires-approval", "Human gates require approve or recover")
            if request is None:
                request = self._discover_pending(state, inspection)
            return self._finalize(inspection, state, request)
        _require(state["phase"] not in GATES, "unsupported", "human-gate-requires-approval", "Human gates require approve")
        _require(state["phase"] not in {"PAUSED", "COMPLETED"}, "unsupported", "phase-not-steppable", "Phase is not executable", state["phase"])
        if state["phase"] == "PR_PREPARATION":
            if self._candidate_binding(state, "pr-metadata", required=False) is not None:
                return self._complete_pr_approval(state, inspection)
            if self._candidate_binding(state, "pr-publication-gate-satisfaction", required=False) is None:
                return self._open_publication_gate(state, inspection)
        return self._claim(inspection, state, request)
    def _gate_inputs(self, state, gate):
        if gate == "plan":
            return [("plan-review", self._candidate_binding(state, "plan-review")), ("plan-snapshot", self._candidate_binding(state, "plan-snapshot"))], self._candidate_binding(state, "plan-observation")
        if gate == "tests":
            subject = self._active(state, "test-manifest")
            return [("test-manifest", subject), ("test-review", self._candidate_binding(state, "test-review"))], self._read(subject, NODE_PATH)["repository_observation_binding"]
        final_node = self._active(state, "final-review"); final = self._read(final_node, NODE_PATH)
        validation = self._read(self._active(state, "validation"), NODE_PATH)["subject_binding"]
        if gate == "final":
            return [("final-review", final["subject_binding"]), ("validation", validation)], final["repository_observation_binding"]
        satisfaction = self._candidate_binding(state, "final-gate-satisfaction")
        repository = self._candidate_binding(state, "pr-publication-observation")
        return [("final-gate-satisfaction", satisfaction), ("final-review", final["subject_binding"]), ("validation", validation)], repository
    def _fresh_local(self, state, expected_binding, subject_binding):
        expected = self._read(expected_binding, label="gate repository observation")
        adapter = self._runtime_drift(lambda: self._runtime(None), "gate-repository-mismatch", "Repository changed after the gate challenge", {"runtime-worktree-untrusted", "runtime-phase-repository-changed"})
        local = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        _require(runtime.same_repository(expected, local) and runtime.clean_repository(local), "stale", "gate-repository-mismatch", "Repository changed after the gate challenge")
        return self._publish("work-type-diff-observation", "observation-%s" % local["observation_sha256"], subject_binding, [("workflow-work-type/diff-observation.json", local)], state["generation"] + 1)
    def _fresh_pr(self, state, source_binding):
        metadata = self._read(self._active(state, "pr-metadata"), NODE_PATH, "PR metadata node")
        previous = next(row["binding"] for row in metadata["evidence"] if row["role"] == "github-pr-observation")
        expected = self._read(previous, label="PR observation")
        adapter = self._runtime_drift(lambda: self._runtime(None), "final-repository-mismatch", "Local repository changed after PR metadata was selected", {"runtime-worktree-untrusted", "runtime-phase-repository-changed"})
        local = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        prior_local = self._read(metadata["repository_observation_binding"], label="PR local observation")
        _require(runtime.same_repository(prior_local, local) and runtime.clean_repository(local) and local["head"]["commit"] == expected["head_sha"] and local["base"]["commit"] == expected["base_sha"], "stale", "final-repository-mismatch", "Local repository changed after PR metadata was selected")
        current = _translate(lambda: adapter.observe_pull_request(self.issue, expected["number"], source_binding), "runtime")
        fields = ("repository", "number", "url", "state", "draft", "base_ref", "base_sha", "head_ref", "head_sha", "title_sha256", "body_sha256")
        _require(all(current[field] == expected[field] for field in fields) and current["state"] == "OPEN" and current["draft"], "stale", "pr-reconciliation-mismatch", "Draft PR changed before final completion")
        trailing = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        _require(runtime.same_repository(local, trailing) and runtime.clean_repository(trailing) and trailing["head"]["commit"] == current["head_sha"] and trailing["base"]["commit"] == current["base_sha"], "stale", "final-repository-mismatch", "Local repository changed while PR freshness was observed")
        pr_binding = self._publish("github-pr-observation", "pr-%d-%s" % (current["number"], current["observation_sha256"]), source_binding, [("workflow-orchestration/github-pr-observation.json", current)], state["generation"] + 1)
        local_binding = self._publish("work-type-diff-observation", "observation-%s" % trailing["observation_sha256"], state["triage_binding"], [("workflow-work-type/diff-observation.json", trailing)], state["generation"] + 1)
        return pr_binding, local_binding
    def _satisfaction_context(self, state, inspection, slot, gate, reobserve_human=False, historical=False):
        binding = self._candidate_binding(state, slot)
        projection = _translate(lambda: evidence.project(self.root, binding), "evidence")
        document = self._read(binding, supervision.SATISFACTION_PATH, "gate satisfaction")
        challenge_binding = document["challenge_binding"]
        history = self._history(state, inspection["authority"])
        selected = [(index, item) for index, (_authority_binding, item) in enumerate(history) if item["pending"] is not None and item["pending"]["request_binding"] == challenge_binding]
        _require(len(selected) == 1 and selected[0][0] + 1 < len(history), "stale", "gate-satisfaction-unselected", "Gate satisfaction challenge was not selected in authority history")
        index, gate_state = selected[0]; successor = history[index + 1][1]
        selected_binding = self._candidate_binding(successor, slot, required=False)
        _require(GATES.get(gate_state["phase"]) == gate and selected_binding == binding, "stale", "gate-satisfaction-unselected", "Gate satisfaction was not selected by the matching gate transition")
        challenge = self._read(challenge_binding, supervision.CHALLENGE_PATH, "gate challenge")
        policy_binding = gate_state["supervision_policy_binding"]
        policy_document = self._read(policy_binding, SUPERVISION_PATH, "supervision policy")
        self._check_challenge(gate_state, challenge, gate, gate_state["previous_authority"])
        expected_subjects, expected_repository = self._gate_inputs(gate_state, gate); expected_subjects = [{"slot": name, "binding": reference} for name, reference in sorted(expected_subjects, key=lambda row: row[0].encode())]
        expected_kind = "human" if challenge["mode"] == "supervised" else "policy"
        _require(projection["decision"]["type"] == "gate-satisfaction" and projection["subject"] == challenge_binding and gate_state["pending"]["kind"] == expected_kind and challenge["subjects"] == expected_subjects and challenge["repository_observation_binding"] == expected_repository, "stale", "gate-satisfaction-stale", "Gate satisfaction binding is stale")
        if gate in {"final", "pr-publication"}: current_subjects, current_repository = self._gate_inputs(state, gate); current_subjects = [{"slot": name, "binding": reference} for name, reference in sorted(current_subjects, key=lambda row: row[0].encode())]; _require(challenge["subjects"] == current_subjects and challenge["repository_observation_binding"] == current_repository, "stale", "gate-satisfaction-current-context-stale", "Current gate subjects differ from the selected satisfaction")
        challenged_repository = self._read(challenge["repository_observation_binding"], label="challenged repository observation")
        satisfied_repository = self._read(document["repository_observation_binding"], label="satisfied repository observation")
        _require(runtime.same_repository(challenged_repository, satisfied_repository) and runtime.clean_repository(satisfied_repository), "stale", "gate-satisfaction-repository-stale", "Gate satisfaction repository does not match its challenge")
        if gate == "pr-publication": final_document = self._read(self._candidate_binding(state, "final-gate-satisfaction"), supervision.SATISFACTION_PATH, "final gate satisfaction"); final_repository = self._read(final_document["repository_observation_binding"], label="final satisfaction repository observation"); _require(runtime.same_repository(final_repository, challenged_repository), "stale", "gate-repository-chain-stale", "Publication repository differs from final approval")
        mechanism = document["human_authorization_binding"] or document["automatic_decision_binding"]
        mechanism_projection = _translate(lambda: evidence.project(self.root, mechanism), "evidence")
        expected_type = "human-authorization" if document["mode"] == "supervised" else "automatic-gate-decision"
        _require(mechanism_projection["decision"]["type"] == expected_type and mechanism_projection["subject"] == challenge_binding, "stale", "gate-satisfaction-mechanism-stale", "Gate satisfaction mechanism is stale")
        path = "workflow-orchestration/human-authorization.json" if document["mode"] == "supervised" else supervision.AUTOMATIC_DECISION_PATH
        mechanism_document = self._read(mechanism, path, "gate satisfaction mechanism")
        _translate(lambda: supervision.validate_satisfaction(document, policy_binding, policy_document, gate_state["previous_authority"], challenge_binding, challenge, mechanism, mechanism_document), "supervision-policy")
        _require(document["gate"] == gate, "stale", "gate-satisfaction-stale", "Gate satisfaction selects the wrong gate")
        if reobserve_human and document["mode"] == "supervised":
            source = {"kind": mechanism_document["source"]["kind"], "id": mechanism_document["source"]["id"]}
            observed = self._authorization_document(state, challenge, source, challenge_binding, historical=historical)
            _require(observed == mechanism_document, "stale", "authorization-source-stale", "Publication authorization source changed before the write")
        return binding, document, challenge
    def _open_publication_gate(self, state, inspection):
        _binding, final_satisfaction, _challenge = self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True)
        observed = self._fresh_local(state, final_satisfaction["repository_observation_binding"], state["triage_binding"])
        candidates = _put(state["candidates"], "pr-publication-observation", observed)
        transient = copy.deepcopy(state); transient["candidates"] = candidates
        subjects, repository = self._gate_inputs(transient, "pr-publication")
        _challenge, pending = self._challenge(transient, inspection["authority"], "pr-publication", subjects, repository, state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="WAITING_FOR_PR_PUBLICATION_APPROVAL", candidates=candidates, pending=pending, transition={"type": "publication-request", "request_binding": pending["request_binding"], "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("publication-approval-requested", successor, binding, committed)
    def _complete_pr_approval(self, state, inspection):
        final_satisfaction, final_document, _final_challenge = self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True)
        publication_satisfaction, _publication_document, publication_challenge = self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
        source_binding = publication_challenge["authority_binding"]
        fresh_pr, fresh_local = self._fresh_pr(state, source_binding)
        metadata = self._active(state, "pr-metadata")
        final = self._read(self._active(state, "final-review"), NODE_PATH, "final review node")["subject_binding"]
        rows = [("final-gate-satisfaction", final_satisfaction), ("final-review", final), ("github-pr-observation", fresh_pr), ("pr-publication-gate-satisfaction", publication_satisfaction)]
        authorization = final_document["human_authorization_binding"]
        _wrapper, policy_binding = self._bind(state, inspection, "pr-approval", metadata, rows, fresh_local, authorization)
        successor = self._successor(state, inspection["authority"], phase="COMPLETED", policy_state_binding=policy_binding, candidates=_drop(state["candidates"], "final-gate-satisfaction", "pr-publication-gate-satisfaction", "pr-publication-observation", "pr-metadata"), transition={"type": "complete", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("completed", successor, binding, committed)
    def _publish_satisfaction(self, state, challenge, mechanism, repository):
        challenge_binding = state["pending"]["request_binding"]
        path = "workflow-orchestration/human-authorization.json" if challenge["mode"] == "supervised" else supervision.AUTOMATIC_DECISION_PATH
        mechanism_document = self._read(mechanism, path, "gate satisfaction mechanism")
        document = _translate(lambda: supervision.gate_satisfaction(state["supervision_policy_binding"], self._supervision(state), state["previous_authority"], challenge_binding, challenge, mechanism, mechanism_document, repository), "supervision-policy")
        return self._publish("gate-satisfaction", "satisfaction-%s" % document["satisfaction_sha256"], challenge_binding, [(supervision.SATISFACTION_PATH, document)], state["generation"] + 1)
    def _complete_gate(self, state, inspection, challenge, mechanism, repository=None):
        gate = challenge["gate"]
        repository = repository or challenge["repository_observation_binding"]
        satisfaction = self._publish_satisfaction(state, challenge, mechanism, repository)
        authorization = mechanism if challenge["mode"] == "supervised" else None
        transition = {"type": {"plan": "plan-approve", "tests": "tests-approve", "final": "final-approve", "pr-publication": "publication-approve"}[gate], "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}
        if gate == "final":
            candidates = _put(state["candidates"], "final-gate-satisfaction", satisfaction)
            successor = self._successor(state, inspection["authority"], phase=GATE_NEXT[gate], candidates=candidates, transition=transition)
        elif gate == "pr-publication":
            candidates = _put(state["candidates"], "pr-publication-gate-satisfaction", satisfaction)
            successor = self._successor(state, inspection["authority"], phase=GATE_NEXT[gate], candidates=candidates, transition=transition)
        elif gate == "plan": subject, rows = self._candidate_binding(state, "plan-snapshot"), [("technical-plan-review", self._candidate_binding(state, "plan-review")), ("gate-satisfaction", satisfaction)]
        elif gate == "tests":
            subject = self._active(state, "test-manifest"); rows = [("technical-test-review", self._candidate_binding(state, "test-review")), ("gate-satisfaction", satisfaction)]
        if gate in {"plan", "tests"}:
            node = {"plan": "plan-approval", "tests": "test-approval"}[gate]
            _wrapper, policy_binding = self._bind(state, inspection, node, subject, rows, repository, authorization)
            successor = self._successor(state, inspection["authority"], phase=GATE_NEXT[gate], policy_state_binding=policy_binding, candidates=_drop(state["candidates"], "plan-snapshot", "plan-revision", "plan-review", "plan-observation", "test-review", "human-challenge", "human-authorization"), transition=transition)
        binding, committed = self._commit(successor); return self._result("approved", successor, binding, committed)
    def _automatic(self, inspection, state):
        _require(state["pending"]["status"] == "requested" and state["phase"] in GATES, "conflict", "no-automatic-gate", "No automatic gate is open")
        challenge_binding = state["pending"]["request_binding"]; challenge = self._read(challenge_binding, supervision.CHALLENGE_PATH, "gate challenge"); gate = GATES[state["phase"]]
        self._check_challenge(state, challenge, gate, state["previous_authority"])
        expected_subjects, expected_repository = self._gate_inputs(state, gate)
        expected_subjects = [{"slot": slot, "binding": binding} for slot, binding in sorted(expected_subjects, key=lambda row: row[0].encode())]
        _require(challenge["subjects"] == expected_subjects and challenge["repository_observation_binding"] == expected_repository, "stale", "challenge-stale", "Pending automatic challenge has stale evidence")
        fresh_repository = None
        if gate in {"final", "pr-publication"}:
            self._fresh_local(state, challenge["repository_observation_binding"], challenge_binding)
        document = _translate(lambda: supervision.automatic_decision(state["supervision_policy_binding"], self._supervision(state), challenge_binding, challenge, state["previous_authority"]), "supervision-policy")
        decision = self._publish("automatic-gate-decision", "decision-%s" % document["decision_sha256"], challenge_binding, [(supervision.AUTOMATIC_DECISION_PATH, document)], state["generation"] + 1)
        if gate in {"final", "pr-publication"}:
            fresh_repository = self._fresh_local(state, challenge["repository_observation_binding"], challenge_binding)
        return self._complete_gate(state, inspection, challenge, decision, fresh_repository)
    def set_supervision(self, expected_tip, requested):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._selected_state(inspection)
        _require(state["pending"] is None, "conflict", "supervision-change-pending", "Supervision cannot change while a gate or execution is pending")
        _require(state["phase"] not in set(GATES) | {"PAUSED", "PR_PREPARATION", "COMPLETED"}, "conflict", "supervision-change-phase", "Supervision cannot change in a waiting, publication, or terminal phase")
        _require(isinstance(requested, dict), "unsupported", "supervision-request-invalid", "Supervision request must be an exact gate-to-mode map")
        try: rows = [{"gate": gate, "mode": requested[gate]} for gate in sorted(requested, key=lambda value: value.encode())]
        except (AttributeError, TypeError): _fail("unsupported", "supervision-request-invalid", "Supervision request gates must be strings")
        current = self._supervision(state)
        configuration = {"format": supervision.CONFIG_FORMAT, "gates": rows}
        challenge = _translate(lambda: supervision.build_change_challenge(state["supervision_policy_binding"], current, inspection["authority"], configuration, state["phase"]), "supervision-policy")
        challenge_binding = self._publish("supervision-policy-change", "challenge-%s" % challenge["challenge_sha256"], inspection["authority"], [(supervision.CHANGE_PATH, challenge)], state["generation"] + 1)
        pending = {"attempt_id": _digest({"authority": inspection["authority"], "kind": "human", "challenge": challenge_binding}), "kind": "human", "request_binding": challenge_binding, "status": "requested"}
        successor = self._successor(state, inspection["authority"], pending=pending, candidates=_put(state["candidates"], "supervision-policy-change", challenge_binding), transition={"type": "supervision-change-request", "request_binding": challenge_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("supervision-change-requested", successor, binding, committed)
    def _approve_supervision_change(self, state, inspection, supplied):
        challenge_binding = state["pending"]["request_binding"]; _require(state["phase"] not in set(GATES) | {"PAUSED", "PR_PREPARATION", "COMPLETED"}, "conflict", "supervision-change-phase", "Supervision cannot change in a waiting, publication, or terminal phase")
        challenge_projection = _translate(lambda: evidence.project(self.root, challenge_binding), "evidence")
        challenge = self._read(challenge_binding, supervision.CHANGE_PATH, "supervision policy change challenge")
        _require(self._candidate_binding(state, "supervision-policy-change") == challenge_binding and challenge_projection["decision"]["type"] == "supervision-policy-change" and challenge_projection["subject"] == state["previous_authority"], "stale", "supervision-change-stale", "Supervision change evidence is stale")
        current = self._supervision(state)
        _translate(lambda: supervision.validate_change_challenge(challenge, state["supervision_policy_binding"], current, state["previous_authority"], state["phase"]), "supervision-policy")
        authorization = self._authorization(state, challenge, supplied)
        _require(self._authorization(state, challenge, supplied) == authorization, "stale", "authorization-source-stale", "Supervision change authorization source changed before completion")
        authorization_document = self._read(authorization, "workflow-orchestration/human-authorization.json", "supervision policy authorization")
        next_policy = _translate(lambda: supervision.revise(state["supervision_policy_binding"], current, challenge_binding, challenge, authorization, authorization_document, state["previous_authority"], state["phase"]), "supervision-policy")
        policy_binding = self._publish_supervision(next_policy, state["supervision_policy_binding"], state["supervision_policy_binding"], state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], supervision_policy_binding=policy_binding, candidates=_drop(state["candidates"], "supervision-policy-change"), transition={"type": "supervision-change", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("supervision-changed", successor, binding, committed)
    def cancel(self, expected_tip, reason):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._selected_state(inspection); pending = state["pending"]
        _require(pending is not None and pending["status"] == "requested" and pending["kind"] not in {"human", "human-rejection", "policy"}, "conflict", "no-cancellable-attempt", "No executable attempt is pending")
        _require(isinstance(reason, str) and reason.strip(), "unsupported", "cancel-reason-required", "Cancellation needs a nonempty reason")
        request = self._read(pending["request_binding"], label="execution request")
        rebound = self._publish("execution-request", "attempt-%s" % pending["attempt_id"], inspection["authority"], [("workflow-orchestration/execution-request.json", request)], state["generation"] + 1)
        replacement = dict(pending); replacement.update({"request_binding": rebound, "status": "cancel-requested"})
        successor = self._successor(state, inspection["authority"], pending=replacement, candidates=_put(state["candidates"], "execution-request", rebound), transition={"type": "cancel-request", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("cancel-requested", successor, binding, committed)
    def _recovery_attempt(self, state, inspection):
        pending = state["pending"]
        if pending is not None and pending["status"] == "cancel-requested": return pending["request_binding"], self._read(pending["request_binding"], label="execution request"), None
        for _binding, item in reversed(self._history(state, inspection["authority"])):
            result_binding = self._candidate_binding(item, "execution-result", required=False)
            if result_binding is not None:
                result = self._read(result_binding, RESULT_PATH, "execution result"); return result["request_binding"], self._read(result["request_binding"], label="execution request"), result
        _fail("corrupt", "recovery-state-unrecoverable", "Paused state has no recorded safe attempt")
    def _resume_phase(self, request):
        operation = request["operation"]
        phase = RESUME_PHASES[1].get(operation["name"]) if operation["kind"] == "agent" else RESUME_PHASES[0].get(operation["kind"])
        _require(phase is not None, "corrupt", "recovery-state-unrecoverable", "Attempt operation cannot reconstruct a safe phase")
        return phase
    def recover(self, expected_tip, supplied):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._selected_state(inspection); pending = state["pending"]
        if pending is not None and pending["kind"] == "human":
            challenge = self._read(pending["request_binding"], "workflow-orchestration/human-challenge.json", "recovery challenge")
            _require(state["phase"] == "PAUSED", "conflict", "not-recoverable", "Pending human request is not a recovery challenge")
            self._check_recovery_challenge(challenge, state["previous_authority"])
            _require(challenge["subjects"] and len(challenge["subjects"]) == 1 and challenge["subjects"][0].get("slot") == "pending-attempt", "corrupt", "recovery-state-unrecoverable", "Recovery challenge does not bind one recorded attempt")
            _authorization = self._authorization(state, challenge, supplied); request = self._read(challenge["subjects"][0]["binding"], label="recovery request"); phase = self._resume_phase(request)
            successor = self._successor(state, inspection["authority"], phase=phase, pending=None, candidates=_put(state["candidates"], "human-authorization", _authorization), transition={"type": "recover", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
            binding, committed = self._commit(successor); return self._result("recovered", successor, binding, committed)
        _require(state["phase"] == "PAUSED" or (pending is not None and pending["status"] == "cancel-requested"), "conflict", "not-paused", "Recovery needs a paused or cancelled attempt")
        request_binding, request, result = self._recovery_attempt(state, inspection)
        _require(not (request["operation"]["kind"] == "validation" and result and result["outcome"] != "succeeded"), "paused", "unsupported-policy-transition", "Failed validation needs a replacement policy transition")
        _challenge, human = self._recovery_challenge(inspection["authority"], [("pending-attempt", request_binding)], state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="PAUSED", pending=human, transition={"type": "recover", "request_binding": human["request_binding"], "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("recovery-requested", successor, binding, committed)
def status(root, issue, **_kwargs): return Orchestrator(root, issue).inspect()
def plan_next(root, issue, **_kwargs): return Orchestrator(root, issue).plan()
def init(root, issue, request=None, **_kwargs): return Orchestrator(root, issue).initialize(request or {})
def step(root, issue, expected_tip=None, request=None, **_kwargs): return Orchestrator(root, issue).advance(expected_tip, request)
def approve(root, issue, expected_tip=None, authorization=None, **_kwargs): return Orchestrator(root, issue).approve(expected_tip, authorization)
def reject(root, issue, expected_tip=None, authorization=None, reason=None, **_kwargs): return Orchestrator(root, issue).reject(expected_tip, authorization, reason)
def set_supervision(root, issue, expected_tip=None, supervision_map=None, **_kwargs): return Orchestrator(root, issue).set_supervision(expected_tip, supervision_map)
def cancel(root, issue, expected_tip=None, reason=None, **_kwargs): return Orchestrator(root, issue).cancel(expected_tip, reason)
def recover(root, issue, expected_tip=None, authorization=None, **_kwargs): return Orchestrator(root, issue).recover(expected_tip, authorization)
def _dispatch(handler, args, payload):
    if handler is init: return handler(args.root, args.issue, request=payload)
    if handler in (status, plan_next): return handler(args.root, args.issue)
    if handler is step: return handler(args.root, args.issue, expected_tip=args.expected_tip, request=payload)
    if handler is reject: return handler(args.root, args.issue, expected_tip=args.expected_tip, authorization=payload, reason=args.reason)
    if handler is set_supervision: return handler(args.root, args.issue, expected_tip=args.expected_tip, supervision_map=payload)
    if handler is cancel: return handler(args.root, args.issue, expected_tip=args.expected_tip, reason=args.reason)
    return handler(args.root, args.issue, expected_tip=args.expected_tip, authorization=payload)
class OrchestratorArgumentParser(argparse.ArgumentParser):
    def error(self, message): _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)
def build_parser():
    parser = OrchestratorArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="command", required=True, parser_class=OrchestratorArgumentParser)
    for name in ("status", "plan-next"):
        command = commands.add_parser(name); command.add_argument("issue", type=int); command.add_argument("--root", required=True)
    command = commands.add_parser("init"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--request", required=True)
    command = commands.add_parser("step"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--request")
    command = commands.add_parser("approve"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--authorization", required=True)
    command = commands.add_parser("reject"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--reason"); command.add_argument("--authorization")
    command = commands.add_parser("set-supervision"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--supervision", required=True)
    command = commands.add_parser("recover"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--authorization")
    command = commands.add_parser("cancel"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--reason", required=True)
    return parser
def _load(path, label):
    try:
        source = pathlib.Path(path)
        _require(source.is_file() and not source.is_symlink(), "denied", "%s-not-regular" % label, "%s must be a regular file" % label)
        with source.open("rb") as stream: data = stream.read(LIMIT + 1)
    except OSError as error: _fail("missing", "%s-unreadable" % label, "%s cannot be read: %s" % (label, error))
    _require(len(data) <= LIMIT, "unsupported", "%s-too-large" % label, "%s exceeds 2 MiB" % label)
    return _translate(lambda: inspector.parse_json_object(data, label), "inspector")
def main(argv=None):
    try:
        args = build_parser().parse_args(argv); payload = _load(args.request, "orchestration-request") if getattr(args, "request", None) else None
        if getattr(args, "authorization", None): payload = _load(args.authorization, "authorization")
        if getattr(args, "supervision", None): payload = _load(args.supervision, "supervision")
        document = _dispatch(COMMAND_HANDLERS[args.command], args, payload); sys.stdout.buffer.write(inspector.canonical_document(document))
        return OUTCOMES.get(document["outcome"]["status"], 0)
    except OrchestratorFailure as error:
        sys.stdout.buffer.write(inspector.canonical_document(error.document())); return OUTCOMES[error.status]
COMMAND_HANDLERS = {"status": status, "plan-next": plan_next, "init": init, "step": step, "approve": approve, "reject": reject, "set-supervision": set_supervision, "cancel": cancel, "recover": recover}
if __name__ == "__main__":
    raise SystemExit(main())
