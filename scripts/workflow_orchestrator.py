#!/usr/bin/env python3
"""One-step, inactive-by-default composition for the replacement workflow."""
import argparse, base64, copy, fnmatch, json, pathlib, sys, threading
try:
    import workflow_authority as authority
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_plan_revision_policy as plan_policy
    import workflow_policy as policy
    import workflow_runtime as runtime
    import workflow_work_type_policy as work_type_policy
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_authority as authority
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_plan_revision_policy as plan_policy
    from scripts import workflow_policy as policy
    from scripts import workflow_runtime as runtime
    from scripts import workflow_work_type_policy as work_type_policy
VERSION, STATE_FORMAT, NODE_FORMAT = "1.1.0", authority.STATE_FORMAT, "chess-echo-workflow-node-v1"
CHALLENGE_FORMAT, AUTHORIZATION_FORMAT, CANDIDATE_FORMAT, RESULT_FORMAT, FAILURE_FORMAT = "chess-echo-human-challenge-v1", "chess-echo-human-authorization-v1", "chess-echo-orchestrator-agent-candidate-v1", "chess-echo-orchestration-orchestrator-result-v1", "chess-echo-orchestration-orchestrator-failure-v1"
STATE_PATH, NODE_PATH, POLICY_PATH = "workflow-orchestration/state.json", "workflow-orchestration/node.json", "workflow-policy/state.json"
LIMIT = 2 * 1024 * 1024
OUTCOMES = {"resolved": 0, "missing": 3, "unsupported": 4, "corrupt": 5, "ambiguous": 6, "stale": 7, "denied": 8, "busy": 9, "conflict": 10, "uncertain": 11, "paused": 12}
AGENT_PHASES = {"PLANNING": ("planner", "write-plan"), "PLAN_REVIEW": ("reviewer", "review-plan"), "TEST_IMPLEMENTATION": ("implementer", "write-tests"), "TEST_REVIEW": ("reviewer", "review-tests"), "IMPLEMENTATION": ("implementer", "implement"), "FINAL_REVIEW": ("reviewer", "review-final")}
CLAIM_TYPES = {"PLANNING": "plan-request", "PLAN_REVIEW": "plan-review", "TEST_IMPLEMENTATION": "tests-request", "TEST_REVIEW": "tests-review", "IMPLEMENTATION": "implementation-request", "VALIDATION": "validation-request", "FINAL_REVIEW": "final-review", "PR_PREPARATION": "pr-prepare"}
GATES = {"WAITING_FOR_PLAN_APPROVAL": "plan", "WAITING_FOR_TEST_APPROVAL": "tests", "WAITING_FOR_FINAL_APPROVAL": "final"}
GATE_NEXT = {"plan": "TEST_IMPLEMENTATION", "tests": "IMPLEMENTATION", "final": "COMPLETED"}
FROZEN_ISSUES = frozenset({115})
RUNTIME_PROVIDER = None
SANDBOX_PROVIDER = None
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
    except (authority.AuthorityFailure, evidence.EvidenceFailure, inspector.InspectionFailure, plan_policy.PlanRevisionPolicyFailure, policy.PolicyFailure, runtime.RuntimeFailure, work_type_policy.WorkTypePolicyFailure) as error:
        status = getattr(error, "status", "corrupt")
        _fail(status if status in OUTCOMES else "corrupt", getattr(error, "code", label), getattr(error, "message", str(error)), getattr(error, "subject", None))
    except (OSError, UnicodeError, ValueError) as error:
        _fail("corrupt", "%s-failed" % label, "%s failed closed: %s" % (label, error))
def _put(rows, slot, binding):
    rows = [copy.deepcopy(row) for row in rows if row["slot"] != slot]
    rows.append({"slot": slot, "binding": binding}); return sorted(rows, key=lambda row: row["slot"].encode())
def _drop(rows, *slots):
    return [copy.deepcopy(row) for row in rows if row["slot"] not in slots]
def _source(value):
    _require(isinstance(value, dict), "unsupported", "authorization-required", "A GitHub authorization source is required")
    _require(value.get("kind") in {"issue-comment", "pull-request-review"}, "unsupported", "authorization-source", "Authorization source is unsupported")
    _require(type(value.get("id")) is int and value["id"] > 0, "corrupt", "authorization-id", "Authorization source ID is invalid")
    return {"kind": value["kind"], "id": value["id"]}
def _next(state):
    pending, phase = state["pending"], state["phase"]
    if pending is not None:
        if pending["status"] == "cancel-requested": return {"action": "recover-cancelled-attempt", "command": "recover", "gate": "recovery", "pending_kind": pending["kind"]}
        if pending["kind"] == "human" and phase == "PAUSED": return {"action": "authorize-recovery", "command": "recover", "gate": "recovery", "pending_kind": "human"}
        return {"action": "approve-gate" if pending["kind"] == "human" else "execute-pending", "command": "approve" if pending["kind"] == "human" else "step", "gate": GATES.get(phase), "pending_kind": pending["kind"]}
    actions = {"PLANNING": "request-planner", "PLAN_REVIEW": "review-plan", "TEST_IMPLEMENTATION": "request-tests", "TEST_REVIEW": "review-tests", "IMPLEMENTATION": "request-implementation", "VALIDATION": "run-validation", "FINAL_REVIEW": "review-final", "PR_PREPARATION": "prepare-draft-pr", "PAUSED": "request-recovery", "COMPLETED": "none-completed"}
    return {"action": "await-human-approval" if phase in GATES else actions.get(phase, "unknown"), "command": "approve" if phase in GATES else "recover" if phase == "PAUSED" else "read-only" if phase == "COMPLETED" else "step", "gate": GATES.get(phase), "pending_kind": None}
class Orchestrator:
    def __init__(self, root, issue):
        _require(type(issue) is int and issue > 0, "unsupported", "invalid-issue", "Issue must be a positive integer")
        _require(issue not in FROZEN_ISSUES, "denied", "issue-frozen", "Issue is frozen before any workflow lookup", str(issue))
        self.root, self.issue, self.family = pathlib.Path(root), issue, None
    def _runtime(self, request):
        _require(RUNTIME_PROVIDER is not None, "unsupported", "runtime-provider-unavailable", "No reviewed runtime provider is configured")
        adapter = RUNTIME_PROVIDER(self.root, self.issue, request)
        _require(adapter is not None, "unsupported", "runtime-unavailable", "Runtime provider returned no adapter"); return adapter
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
    def _publish(self, decision_type, decision_id, subject, rows, generation, lineage=None, identity=None):
        encoded, entries = [], []
        for path, value in rows:
            data = value if isinstance(value, bytes) else _canonical(value)
            _require(isinstance(data, bytes) and len(data) <= LIMIT, "unsupported", "document-too-large", "Orchestration evidence exceeds 2 MiB")
            reference = {"kind": "evidence-payload", "sha256": inspector.sha256(data), "size": len(data)}
            encoded.append((path, data)); entries.append({"path": path, "kind": "regular", "mode": "100644", "content_sha256": reference["sha256"], "size": reference["size"], "payload": reference})
        entries.sort(key=lambda entry: entry["path"].encode()); blob = b"\0".join(data for _path, data in encoded)
        identity = identity or {"issue": self.issue, "run_id": inspector.sha256(b"orchestration-node-v1\0" + blob)[:32], "family_run_id": self.family, "correction": None, "run_generation": generation, "sequence": generation + 1, "event_tip": inspector.sha256(b"orchestration-node-tip-v1\0" + blob)}
        captures = [{"entry_sha256": inspector.sha256(_canonical(entry)), "capture_method": "orchestration-composition", "captured_at": "1970-01-01T00:00:00Z", "source": {"type": "workspace", "path": entry["path"]}, "tool": {"name": "workflow-orchestrator", "version": VERSION}} for entry in entries]
        publication = {"format": evidence.PUBLICATION_FORMAT, "identity": identity, "decision": {"type": decision_type, "id": decision_id}, "subject": subject, "lineage": lineage or {"status": "original", "parent_binding": None}, "migration": None, "entries": entries, "captures": captures, "payloads": [{"sha256": inspector.sha256(data), "size": len(data), "bytes_base64": base64.b64encode(data).decode()} for _path, data in encoded]}
        return _translate(lambda: evidence.publish(self.root, publication)["binding"], "evidence")
    def _publish_state(self, state):
        data, generation = _canonical(state), state["generation"]
        identity = {"issue": self.issue, "run_id": inspector.sha256(b"orchestration-state-v1\0" + data)[:32], "family_run_id": self.family, "correction": None, "run_generation": generation, "sequence": generation + 1, "event_tip": inspector.sha256(b"orchestration-tip-v1\0" + data)}
        subject, lineage = (state["previous_authority"], {"status": "replacement", "parent_binding": state["previous_authority"]}) if generation else (state["policy_state_binding"], {"status": "original", "parent_binding": None})
        return self._publish("orchestration-state", "generation-%d" % generation, subject, [(STATE_PATH, data)], generation, lineage, identity)
    def _commit(self, state):
        binding = self._publish_state(state); bundle = _translate(lambda: authority.prepare(self.root, self.issue, binding), "authority")
        return binding, _translate(lambda: authority.commit(self.root, bundle), "authority")
    def _base(self, **changes):
        state = {"format": STATE_FORMAT, "issue": self.issue, "family_run_id": self.family, "generation": 0, "previous_authority": None, "previous_pointer_sha256": None, "route": "implementation", "phase": "PLANNING", "triage_binding": None, "policy_state_binding": None, "candidates": [], "pending": None, "cutover": {"mode": "new-run", "legacy_checkpoint_sha256": None, "migration_binding": None}, "transition": {"type": "initialize", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}}
        state.update(changes); return _with_digest(state, "state_sha256")
    def _successor(self, state, previous, **changes):
        pointer = _canonical({"format": authority.POINTER_FORMAT, "issue": self.issue, "generation": state["generation"], "authority": previous})
        next_state = {"format": STATE_FORMAT, "issue": self.issue, "family_run_id": self.family, "generation": state["generation"] + 1, "previous_authority": previous, "previous_pointer_sha256": inspector.sha256(pointer), "route": "implementation", "phase": state["phase"], "triage_binding": state["triage_binding"], "policy_state_binding": state["policy_state_binding"], "candidates": copy.deepcopy(state["candidates"]), "pending": None, "cutover": {"mode": "new-run", "legacy_checkpoint_sha256": None, "migration_binding": None}, "transition": {"type": "classify", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}}
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
    def _history(self, state, binding):
        rows, current, current_binding = [], state, binding
        for _index in range(authority.CHAIN_LIMIT):
            rows.append((current_binding, current))
            if current["generation"] == 0: return list(reversed(rows))
            current_binding = current["previous_authority"]; current = self._read(current_binding, STATE_PATH, "prior orchestration state")
        _fail("unsupported", "authority-chain-limit", "Orchestration history exceeds its bound")
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
        adapter = self._runtime(request); bootstrap = _translate(adapter.bootstrap_document, "runtime")
        _require(bootstrap["mode"] == "active", "unsupported", "orchestrator-inactive", "New orchestration is disabled by configuration")
        issue_document, raw = _translate(lambda: adapter.observe_issue(self.issue), "runtime"); source = issue_document.get("source")
        _require(isinstance(source, dict), "corrupt", "response-source-invalid", "Issue source is invalid")
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
        implementation, _node = self._node("implementation-a", baseline_binding, [("issue-snapshot", issue_binding), ("triage", triage_binding)], baseline_binding, None, [], 0)
        policy_state = _translate(lambda: policy.initialize(self.root, self.issue, self.family, {"binding": implementation, "migration_plan": None}), "policy")
        policy_binding = self._publish_policy(policy_state, implementation, None, 0); state = self._base(triage_binding=triage_binding, policy_state_binding=policy_binding)
        binding, committed = self._commit(state); return self._result("initialized", state, binding, committed)
    def plan(self):
        inspection = self._status(); state = self._state(inspection)
        return {"format": "chess-echo-orchestration-plan-v1", "outcome": {"status": "resolved", "code": "planned"}, "issue": self.issue, "generation": state["generation"], "phase": state["phase"], "pointer_sha256": inspection["pointer_sha256"], "pending": state["pending"], "next_action": _next(state)}
    def _command_source(self, baseline_binding, baseline, config, operation, role=None, profile=None):
        source = {"config_binding": baseline_binding, "config_content_sha256": baseline["config"]["content_sha256"], "config_blob_oid": baseline["config"]["blob_oid"], "profile": profile, "entry": operation["name"]}
        if operation["kind"] == "agent":
            source["entry"], source["profile"] = role, None; row = next((item for item in config["agent_roles"] if item["role"] == role), None)
            _require(row is not None, "corrupt", "agent-role-missing", "Configured agent role is missing"); limits = {key: row[key] for key in ("timeout_ms", "grace_ms", "output_limit_bytes")}
        elif operation["kind"] == "validation": limits = dict(runtime.VALIDATION_LIMITS)
        else: limits = {key: config["github"][key] for key in ("timeout_ms", "grace_ms", "output_limit_bytes")}
        return source, limits
    def _inputs(self, state, extra=()):
        rows = [("policy-state", state["policy_state_binding"]), ("triage", state["triage_binding"])] + list(extra)
        return [{"role": role, "binding": binding} for role, binding in sorted(rows, key=lambda row: (row[0], row[1]["sha256"]))]
    def _validation_records(self, state, inspection):
        records, seen = [], set()
        for _binding, item in self._history(state, inspection["authority"]):
            result_binding = self._candidate_binding(item, "execution-result", required=False)
            if result_binding is None or result_binding["sha256"] in seen: continue
            result = self._read(result_binding, label="execution result"); request = self._read(result["request_binding"], label="execution request")
            if request["operation"]["kind"] == "validation":
                records.append((request["operation"]["name"], result["request_binding"], result_binding)); seen.add(result_binding["sha256"])
        return records
    def _prior_pr_result(self, state, inspection):
        for _binding, item in reversed(self._history(state, inspection["authority"])):
            result_binding = self._candidate_binding(item, "execution-result", required=False)
            if result_binding is None: continue
            result = self._read(result_binding, label="execution result"); request = self._read(result["request_binding"], label="execution request")
            if request["operation"]["kind"] == "github-write": return result, result_binding
        return None, None
    def _unresolved_pr_request(self, state, inspection):
        for _authority, item in reversed(self._history(state, inspection["authority"])):
            binding = self._candidate_binding(item, "execution-request", required=False)
            if binding is not None and self._read(binding, label="execution request")["operation"]["kind"] == "github-write": return binding
        return None
    def _pr_context(self, state, observation):
        final = self._active(state, "final-review"); wrapper = self._read(final, NODE_PATH, "final review node")
        candidate = self._candidate_schema(self._read(wrapper["subject_binding"], label="final review candidate"), "review"); pr = candidate.get("pr")
        _require(isinstance(pr, dict) and set(pr) == {"head_ref", "title", "body"}, "corrupt", "pr-metadata-invalid", "Final review did not supply exact draft PR metadata")
        _require(all(isinstance(pr[key], str) and pr[key] for key in ("head_ref", "title", "body")), "corrupt", "pr-metadata-invalid", "Draft PR metadata is incomplete")
        _require(self._valid_pr_body(pr["body"]), "denied", "pr-body-headings", "Draft PR body must contain only nonempty What, Why, and Testing sections")
        _triage, baseline, _baseline_binding, _config = self._facts(state)
        expectation = {"repository": baseline["repository"], "base_ref": baseline["target_base"]["name"], "base_sha": observation["base"]["commit"], "head_ref": pr["head_ref"], "head_sha": observation["head"]["commit"], "title_sha256": inspector.sha256(pr["title"].encode()), "body_sha256": inspector.sha256(pr["body"].encode())}
        return pr, expectation, wrapper
    def _valid_pr_body(self, body):
        headings = ("## What", "## Why", "## Testing")
        lines, positions = body.splitlines(), []
        for heading in headings:
            matches = [index for index, line in enumerate(lines) if line == heading]
            if len(matches) != 1: return False
            positions.append(matches[0])
        if positions != sorted(positions) or any(line.startswith("## ") and line not in headings for line in lines): return False
        bounds = positions[1:] + [len(lines)]
        return all(any(line.strip() for line in lines[start + 1:end]) for start, end in zip(positions, bounds))
    def _pr_number(self, result, supplied):
        if isinstance(supplied, dict) and type(supplied.get("pr_number")) is int and supplied["pr_number"] > 0: return supplied["pr_number"]
        _require(result is not None, "uncertain", "pr-number-unknown", "A cancelled or uncertain PR write requires an explicit pull request number")
        external = result.get("reconciliation", {}).get("external_identity")
        try: raw = base64.b64decode(result["process_result"]["stdout"]["base64"]).decode().strip()
        except (KeyError, TypeError, ValueError, UnicodeError): raw = ""
        value = external or raw; _require(value.startswith("https://github.com/") and "/pull/" in value, "uncertain", "pr-number-unknown", "PR write needs an exact reconciled pull request number")
        try: return int(value.rsplit("/", 1)[1])
        except ValueError: _fail("uncertain", "pr-number-unknown", "PR write returned an invalid pull request URL")
    def _claim(self, inspection, state, adapter, supplied):
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
        before = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        expected_repository = self._phase_repository(state, phase)
        if expected_repository is not None:
            if not (self._same_repository(expected_repository, before) and self._clean_repository(before)):
                _fail("stale", "repository-continuity-stale", "Repository changed after the selected phase evidence; restore it before retrying")
        elif phase == "PLANNING":
            _require(self._clean_repository(before) and before["head"]["commit"] == before["base"]["commit"] and before["ancestry"] == {"base_is_ancestor": True, "commit_count": 0} and not before["changes"], "stale", "repository-continuity-stale", "Initial planning must use the clean bootstrap base")
        if operation["kind"] == "github-write": _pr, expectation, _wrapper = self._pr_context(state, before)
        active = {row["node"]: row["binding"] for row in self._read(state["policy_state_binding"], POLICY_PATH, "policy state")["active"]}
        required = {
            "TEST_IMPLEMENTATION": ("plan-approval",), "TEST_REVIEW": ("plan-approval", "test-manifest"),
            "IMPLEMENTATION": ("plan-approval", "test-approval", "test-manifest"),
            "VALIDATION": ("implementation-submission",),
            "FINAL_REVIEW": ("plan-approval", "test-approval", "implementation-submission", "validation"),
            "PR_PREPARATION": ("plan-approval", "test-approval", "implementation-submission", "validation", "final-review"),
        }.get(phase, ())
        extra = [("baseline", baseline_binding)] + [(node, active[node]) for node in required]
        if phase == "PLAN_REVIEW": extra.append(("plan-snapshot", self._candidate_binding(state, "plan-snapshot")))
        if phase == "PLANNING" and self._candidate_binding(state, "plan-snapshot", required=False) is not None:
            extra += [("plan-snapshot", self._candidate_binding(state, "plan-snapshot")),
                      ("plan-review", self._candidate_binding(state, "plan-review"))]
        source, limits = self._command_source(baseline_binding, baseline, config, operation, role, profile); inputs = self._inputs(state, extra)
        attempt = _translate(lambda: runtime.execution_attempt_id(inspection["authority"], operation, source, inputs, before, limits, expectation), "runtime")
        request = _translate(lambda: adapter.build_request(issue=self.issue, family_run_id=self.family, attempt_id=attempt, authority_binding=inspection["authority"], operation=operation, command_source=source, input_bindings=inputs, repository_before=before, limits=limits, reconciliation_expectation=expectation), "runtime")
        request_binding = self._publish("execution-request", "attempt-%s" % attempt, inspection["authority"], [("workflow-orchestration/execution-request.json", request)], state["generation"] + 1)
        before_binding = self._publish("repository-observation", "before-%s" % attempt, request_binding, [("workflow-orchestration/repository-observation.json", before)], state["generation"] + 1)
        pending = {"attempt_id": attempt, "kind": operation["kind"], "request_binding": request_binding, "status": "requested"}
        successor = self._successor(state, inspection["authority"], pending=pending, candidates=_put(state["candidates"], "execution-request", request_binding), transition={"type": CLAIM_TYPES[phase], "request_binding": request_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": before_binding})
        binding, committed = self._commit(successor); _require(committed["outcome"]["code"] == "committed", "busy", "attempt-in-flight", "Another caller already owns this execution attempt")
        selected = {"authority": binding, "pointer_sha256": committed["pointer_sha256"]}
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
    def _unchanged(self, inspection):
        _require(self._status()["pointer_sha256"] == inspection["pointer_sha256"], "stale", "attempt-result-stale", "Authority changed while an attempt executed")
    def _execution_result(self, state, request_binding, result):
        result_binding = self._publish("execution-result", "attempt-%s" % result["attempt_id"], request_binding, [("workflow-orchestration/execution-result.json", result)], state["generation"] + 1)
        after = result.get("repository_after")
        after_binding = None if after is None else self._publish("work-type-diff-observation", "observation-%s" % after["observation_sha256"], state["triage_binding"], [("workflow-work-type/diff-observation.json", after)], state["generation"] + 1)
        return result_binding, after_binding, _put(_put(state["candidates"], "execution-request", request_binding), "execution-result", result_binding)
    def _pause(self, state, inspection, rows, code):
        successor = self._successor(state, inspection["authority"], phase="PAUSED", pending=None, candidates=rows, transition={"type": "pause", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result(code, successor, binding, committed, "paused")
    def _candidate(self, result, expected):
        record = result.get("candidate_output")
        _require(result.get("outcome") == "succeeded" and isinstance(record, dict) and set(record) == {"sha256", "size"}, "corrupt", "candidate-output-invalid", "Agent result has no successful candidate output")
        try: raw = base64.b64decode(result["process_result"]["stdout"]["base64"], validate=True); value = json.loads(raw.decode(), object_pairs_hook=lambda pairs: self._no_duplicates(pairs))
        except (KeyError, TypeError, ValueError, UnicodeError, json.JSONDecodeError, RecursionError) as error: _fail("corrupt", "candidate-output-invalid", "Agent output is invalid: %s" % error)
        _require(record == {"sha256": inspector.sha256(raw), "size": len(raw)} and isinstance(value, dict), "corrupt", "candidate-output-invalid", "Agent output does not match its result record")
        return self._candidate_schema(value, expected)
    def _candidate_schema(self, value, expected):
        _require(value.get("format") == CANDIDATE_FORMAT and value.get("kind") == expected, "corrupt", "candidate-output-invalid", "Agent output has the wrong candidate kind")
        if expected == "implementer":
            _require(set(value) == {"format", "kind", "report"} and isinstance(value["report"], str) and value["report"], "corrupt", "candidate-output-invalid", "Implementer candidate is invalid")
        if expected == "review":
            _require(set(value) == {"format", "kind", "verdict", "findings", "pr"} and isinstance(value["findings"], list) and isinstance(value["pr"], dict), "corrupt", "candidate-output-invalid", "Review candidate is invalid")
        return value
    def _candidate_artifact(self, state, result, candidate):
        details = {
            "TEST_IMPLEMENTATION": ("test-report", "workflow-orchestration/test-report.json"),
            "TEST_REVIEW": ("technical-test-review", "workflow-orchestration/test-review.json"),
            "IMPLEMENTATION": ("implementation-report", "workflow-orchestration/implementation-report.json"),
            "FINAL_REVIEW": ("final-review-candidate", "workflow-orchestration/final-review.json"),
        }.get(state["phase"])
        if details is None: return result
        digest = _digest(candidate)
        return self._publish(details[0], "%s-%s" % (details[0], digest), result, [(details[1], candidate)], state["generation"] + 1)
    def _no_duplicates(self, pairs):
        value = {}
        for key, item in pairs:
            _require(key not in value, "ambiguous", "candidate-output-invalid", "Agent output repeats a JSON key", key); value[key] = item
        return value
    def _snapshot(self, state, candidate, predecessor=None):
        _require(set(candidate) == {"format", "kind", "plan", "units", "revision"}, "corrupt", "candidate-output-invalid", "Plan candidate has an invalid schema")
        _require(isinstance(candidate["plan"], str) and candidate["plan"].endswith("\n") and not candidate["plan"].endswith("\n\n"), "corrupt", "candidate-output-invalid", "Plan candidate must use one trailing LF")
        lines, units = candidate["plan"].splitlines(True), []; _require(isinstance(candidate["units"], list) and candidate["units"], "corrupt", "candidate-output-invalid", "Plan candidate has no units")
        for raw in candidate["units"]:
            _require(isinstance(raw, dict) and set(raw) == {"id", "title", "start_line", "end_line", "review_class", "dependencies"}, "corrupt", "candidate-output-invalid", "Plan unit schema is invalid")
            start, end = raw["start_line"], raw["end_line"]; _require(type(start) is int and type(end) is int and 1 <= start <= end <= len(lines), "corrupt", "candidate-output-invalid", "Plan unit range is invalid")
            unit = copy.deepcopy(raw); unit["content_sha256"] = inspector.sha256("".join(lines[start - 1:end]).encode()); units.append(unit)
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
        _require(set(candidate) == {"format", "kind", "verdict", "findings", "pr"}, "corrupt", "candidate-output-invalid", "Review candidate has an invalid schema")
        verdict = candidate["verdict"]; _require(verdict in {"accepted", "needs-revision", "full-review-required"}, "corrupt", "candidate-output-invalid", "Review verdict is invalid")
        findings = []
        for raw in candidate["findings"]:
            _require(isinstance(raw, dict) and set(raw) == {"unit_ids", "category", "detail"}, "corrupt", "candidate-output-invalid", "Review finding schema is invalid")
            row = {"introduced_plan_binding": snapshot["binding"], **raw}; row["id"] = "finding-" + _digest({"introduced_plan_binding": row["introduced_plan_binding"], "severity": "blocking", "category": row["category"], "unit_ids": row["unit_ids"], "detail": row["detail"]}); row["severity"] = "blocking"; findings.append(row)
        findings.sort(key=lambda row: row["id"]); units, escalated = snapshot["document"]["units"], verdict == "full-review-required"
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
        review = self._review(candidate, snapshot, state["generation"] + 1, revision, prior_review); rows = _put(rows, "plan-review", review["binding"])
        if revision is None:
            request = _with_digest({"format": plan_policy.BASELINE_REQUEST_FORMAT, "plan": snapshot, "current_review": review}, "request_sha256"); verdict = _translate(lambda: plan_policy.evaluate_baseline(self.root, request, snapshot_binding["sha256"], review["binding"]["sha256"]), "plan-policy")
        else:
            request = _with_digest({"format": plan_policy.REVISION_REQUEST_FORMAT, "prior_plan": prior, "prior_review": prior_review, "current_plan": snapshot, "revision": revision, "current_review": review}, "request_sha256"); verdict = _translate(lambda: plan_policy.evaluate_revision(self.root, request, prior["binding"]["sha256"], prior_review["binding"]["sha256"], snapshot_binding["sha256"], revision_binding["sha256"], review["binding"]["sha256"]), "plan-policy")
        code = verdict["outcome"]["code"]
        if code == "technical-review-accepted":
            _challenge, pending = self._challenge(inspection["authority"], "plan", [("plan-review", review["binding"]), ("plan-snapshot", snapshot_binding)], after, state["generation"] + 1); phase = "WAITING_FOR_PLAN_APPROVAL"
        elif code == "technical-review-needs-revision": pending, phase = None, "PLANNING"
        elif code == "technical-review-escalated": pending, phase = None, "PLAN_REVIEW"
        else: _fail("paused", "plan-policy-unexpected", "Plan policy returned an unsupported technical verdict")
        successor = self._successor(state, inspection["authority"], phase=phase, candidates=rows, pending=pending, transition={"type": "plan-review", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _repository_key(self, observation):
        fields = ("repository", "issue", "family_run_id", "triage_binding", "object_format", "base", "head", "ancestry", "changes", "workspace", "git_trust", "head_config", "raw_diff_sha256")
        return {field: observation[field] for field in fields}
    def _clean_repository(self, observation):
        workspace, trust = observation["workspace"], observation["git_trust"]
        clean = not any(workspace[field] for field in ("staged", "unstaged", "untracked_non_ignored", "assume_unchanged", "skip_worktree"))
        trusted = trust["no_replace_objects"] is True and not trust["replacement_refs"] and trust["git_replace_ref_base"] is None and trust["git_graft_file"] is None and trust["info_grafts_present"] is False and not trust["environment_redirections"] and not trust["alternate_object_directories"]
        return clean and trusted
    def _same_repository(self, before, after):
        return after is not None and self._repository_key(before) == self._repository_key(after)
    def _test_scope(self, observation, scope):
        paths = [change[key] for change in observation["changes"] for key in ("old_path", "new_path") if change[key] is not None]
        patterns = [item for item in scope if isinstance(item, str)]
        patterns += [item.replace("**/", "") for item in patterns]
        return bool(paths) and all(any(fnmatch.fnmatchcase(path, item) for item in patterns) for path in paths)
    def _test_changes(self, observation, patterns):
        normalized = list(patterns) + [item.replace("**/", "") for item in patterns]
        return [change for change in observation["changes"] if any(path is not None and any(fnmatch.fnmatchcase(path, pattern) for pattern in normalized) for path in (change["old_path"], change["new_path"]))]
    def _phase_repository(self, state, phase):
        nodes = {"TEST_IMPLEMENTATION": "plan-approval", "TEST_REVIEW": "test-manifest", "IMPLEMENTATION": "test-approval", "VALIDATION": "implementation-submission", "FINAL_REVIEW": "validation", "PR_PREPARATION": "final-review"}
        if phase in {"PLANNING", "PLAN_REVIEW"}:
            result = self._candidate_binding(state, "execution-result", required=False)
            return None if result is None else self._read(result, label="phase execution result")["repository_after"]
        if phase not in nodes: return None
        wrapper = self._read(self._active(state, nodes[phase]), NODE_PATH, "%s node" % nodes[phase])
        return self._read(wrapper["repository_observation_binding"], label="selected repository observation")
    def _tests_submit(self, state, inspection, report_binding, after, rows):
        _require(after is not None, "stale", "repository-after-missing", "Test attempt lacks a repository observation")
        triage, baseline, _baseline_binding, _config = self._facts(state); profile = next(item for item in baseline["profiles"] if item["id"] == triage["classification"]["validation_profile"])
        observation = self._read(after, label="test observation")
        if not (self._clean_repository(observation) and self._test_scope(observation, profile["test_paths"])):
            return self._pause(state, inspection, rows, "test-scope-drift")
        _wrapper, policy_binding = self._bind(state, inspection, "test-manifest", report_binding, [("test-diff", after), ("test-report", report_binding)], after)
        successor = self._successor(state, inspection["authority"], phase="TEST_REVIEW", policy_state_binding=policy_binding, candidates=_drop(rows, "test-manifest"), transition={"type": "tests-request", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _review_submit(self, state, inspection, review_binding, after, rows, candidate):
        if candidate["verdict"] != "accepted": return self._pause(state, inspection, rows, "unsupported-policy-transition")
        manifest = self._active(state, "test-manifest"); document = self._read(manifest, NODE_PATH, "test manifest node")
        _challenge, pending = self._challenge(inspection["authority"], "tests", [("test-manifest", manifest), ("test-review", review_binding)], document["repository_observation_binding"], state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="WAITING_FOR_TEST_APPROVAL", candidates=_put(rows, "test-review", review_binding), pending=pending, transition={"type": "tests-review", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
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
        if self._test_changes(observation, profile["test_paths"]) != approved["changes"]:
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
        stable = self._clean_repository(first_before) and self._same_repository(implementation_observation, first_before)
        for _name, request, result in records:
            request_document = self._read(request, label="validation request")
            result_document = self._read(result, label="validation result")
            stable = stable and result_document["outcome"] == "succeeded" and self._same_repository(first_before, request_document["repository_before"]) and self._same_repository(first_before, result_document["repository_after"])
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
        if not (candidate["verdict"] == "accepted" and valid_pr and self._valid_pr_body(pr["body"])):
            return self._pause(state, inspection, rows, "unsupported-policy-transition")
        validation_node = self._active(state, "validation"); validation = self._read(validation_node, NODE_PATH, "validation node")
        _wrapper, policy_binding = self._bind(state, inspection, "final-review", review_binding, [("comprehensive-validation", validation["subject_binding"])], validation["repository_observation_binding"])
        successor = self._successor(state, inspection["authority"], phase="PR_PREPARATION", policy_state_binding=policy_binding, candidates=_drop(rows, "final-review"), transition={"type": "final-review", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _after_agent(self, state, inspection, request_binding, candidate_binding, after, rows, candidate):
        phase = state["phase"]
        if phase == "PLANNING": return self._plan_submit(state, inspection, rows, candidate)
        if phase == "PLAN_REVIEW": return self._plan_review(state, inspection, after, rows, candidate)
        if phase == "TEST_IMPLEMENTATION": return self._tests_submit(state, inspection, candidate_binding, after, rows)
        if phase == "TEST_REVIEW": return self._review_submit(state, inspection, candidate_binding, after, rows, candidate)
        if phase == "IMPLEMENTATION": return self._implementation_submit(state, inspection, candidate_binding, after, rows)
        return self._final_submit(state, inspection, candidate_binding, after, rows, candidate)
    def _challenge(self, authority_binding, gate, subjects, repository, generation):
        core = {"format": CHALLENGE_FORMAT, "issue": self.issue, "family_run_id": self.family, "gate": gate, "decision": "approve", "authority_binding": authority_binding, "subjects": [{"slot": slot, "binding": binding} for slot, binding in sorted(subjects, key=lambda item: item[0].encode())], "repository_observation_binding": repository}
        digest = _digest(core); document = dict(core); document.update({"confirmation": "approve %s %s" % (gate, digest), "challenge_sha256": digest})
        binding = self._publish("human-challenge", "challenge-%s" % digest, authority_binding, [("workflow-orchestration/human-challenge.json", document)], generation)
        return binding, {"attempt_id": _digest({"authority": authority_binding, "kind": "human", "challenge": binding}), "kind": "human", "request_binding": binding, "status": "requested"}
    def _check_challenge(self, challenge, gate, predecessor):
        keys = {"format", "issue", "family_run_id", "gate", "decision", "authority_binding", "subjects", "repository_observation_binding", "confirmation", "challenge_sha256"}
        _require(isinstance(challenge, dict) and set(challenge) == keys and isinstance(challenge["subjects"], list), "corrupt", "challenge-stale", "Pending human challenge has an invalid schema")
        core = dict(challenge); confirmation, digest = core.pop("confirmation"), core.pop("challenge_sha256")
        _require(core["format"] == CHALLENGE_FORMAT and core["issue"] == self.issue and core["family_run_id"] == self.family and core["gate"] == gate and core["decision"] == "approve" and core["authority_binding"] == predecessor and digest == _digest(core) and confirmation == "approve %s %s" % (gate, digest), "stale", "challenge-stale", "Pending human challenge is not exact")
    def _run_pr_read(self, state, inspection, pending, request):
        try: number = int(request["operation"]["name"].rsplit("-", 1)[1])
        except (KeyError, TypeError, ValueError): _fail("corrupt", "pr-read-request-invalid", "PR observation request has no valid number")
        self._unchanged(inspection); adapter = self._runtime(None); observation = _translate(lambda: adapter.observe_pull_request(self.issue, number, pending["request_binding"]), "runtime"); self._unchanged(inspection)
        observed = self._publish("github-pr-observation", "pr-%d-%s" % (number, observation["observation_sha256"]), pending["request_binding"], [("workflow-orchestration/github-pr-observation.json", observation)], state["generation"] + 1)
        handoff = {"format": "chess-echo-execution-handoff-v1", "authority_binding": inspection["authority"], "request_binding": pending["request_binding"], "result_binding": None, "repository_after_binding": None, "pr_observation_binding": observed}
        return self._handoff_result(state, inspection, handoff)
    def _finalize_pr_read(self, state, inspection, request, observed):
        observation = self._read(observed, label="PR observation"); number = observation["number"]
        _pr, expected, _final = self._pr_context(state, request["repository_before"])
        _require(all(observation[key] == expected[key] for key in expected) and observation["state"] == "OPEN" and observation["draft"] is True, "stale", "pr-reconciliation-mismatch", "Observed PR is not the exact open draft requested")
        before, final = state["transition"]["repository_observation_binding"], self._active(state, "final-review")
        wrapper, policy_binding = self._bind(state, inspection, "pr-metadata", final, [("github-pr-observation", observed)], before)
        validation = self._read(self._active(state, "validation"), NODE_PATH, "validation node")["subject_binding"]
        _challenge, human = self._challenge(inspection["authority"], "final", [("final-review", self._read(final, NODE_PATH, "final review node")["subject_binding"]), ("pr-metadata", wrapper), ("validation", validation)], before, state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="WAITING_FOR_FINAL_APPROVAL", policy_state_binding=policy_binding, pending=human, candidates=_drop(_put(state["candidates"], "pr-metadata", wrapper), "pr-metadata"), transition={"type": "pr-reconcile", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("executed", successor, binding, committed)
    def _run_pending(self, inspection, state, supplied):
        pending = state["pending"]; _require(pending["status"] == "requested", "paused", "cancel-requested", "Cancelled attempt requires human recovery")
        _require(pending["kind"] != "human", "unsupported", "human-gate-requires-approval", "Human gates require approve")
        request = self._read(pending["request_binding"], label="execution request")
        if pending["kind"] == "github-read": return self._run_pr_read(state, inspection, pending, request)
        adapter = self._runtime(supplied); cancel, stop, worker = self._watch(inspection, pending, request["limits"])
        if self._status()["pointer_sha256"] != inspection["pointer_sha256"]: cancel.set()
        try:
            options = {"cancel_event": cancel}
            if pending["kind"] == "agent":
                _require(SANDBOX_PROVIDER is not None, "unsupported", "sandbox-provider-unavailable", "Agent execution requires a reviewed sandbox provider")
                options["sandbox_provider"] = SANDBOX_PROVIDER(self.root, self.issue, request["operation"]["role"])
            if pending["kind"] == "github-write":
                payload, expectation, _wrapper = self._pr_context(state, request["repository_before"])
                options.update({"reconciliation_expectation": expectation, "write_payload": {"repository": expectation["repository"], "base_ref": expectation["base_ref"], "head_ref": payload["head_ref"], "title": payload["title"], "body": payload["body"]}})
            try: result = _translate(lambda: adapter.execute(request, pending["request_binding"], **options), "runtime")
            except OrchestratorFailure:
                self._unchanged(inspection)
                if cancel.is_set(): _fail("stale", "attempt-result-stale", "Cancelled attempt cannot become authoritative")
                raise
        finally:
            stop.set(); worker.join(2)
            _require(not worker.is_alive(), "conflict", "cancel-watcher-stuck", "Cancellation watcher did not stop")
        self._unchanged(inspection); result_binding, after, rows = self._execution_result(state, pending["request_binding"], result)
        handoff = {"format": "chess-echo-execution-handoff-v1", "authority_binding": inspection["authority"], "request_binding": pending["request_binding"], "result_binding": result_binding, "repository_after_binding": after, "pr_observation_binding": None}
        return self._handoff_result(state, inspection, handoff)
    def _verified_handoff(self, state, inspection, supplied):
        keys = {"format", "authority_binding", "request_binding", "result_binding", "repository_after_binding", "pr_observation_binding"}
        _require(isinstance(supplied, dict) and set(supplied) == keys and supplied["format"] == "chess-echo-execution-handoff-v1" and supplied["authority_binding"] == inspection["authority"] and supplied["request_binding"] == state["pending"]["request_binding"], "busy", "attempt-in-flight", "Pending execution requires its exact result handoff")
        request = self._read(supplied["request_binding"], label="execution request")
        if state["pending"]["kind"] == "github-read":
            projection = _translate(lambda: evidence.project(self.root, supplied["pr_observation_binding"]), "evidence")
            _require(supplied["result_binding"] is None and supplied["repository_after_binding"] is None and projection["decision"]["type"] == "github-pr-observation" and projection["subject"] == supplied["request_binding"], "stale", "execution-handoff-stale", "PR observation handoff is stale")
            return request, None, None, supplied["pr_observation_binding"]
        projection = _translate(lambda: evidence.project(self.root, supplied["result_binding"]), "evidence")
        result = self._read(supplied["result_binding"], label="execution result")
        unsigned = dict(result); digest = unsigned.pop("result_sha256", None)
        _require(projection["decision"]["type"] == "execution-result" and projection["subject"] == supplied["request_binding"] and result.get("format") == runtime.RESULT_FORMAT and result.get("request_binding") == supplied["request_binding"] and result.get("attempt_id") == state["pending"]["attempt_id"] and digest == _digest(unsigned), "stale", "execution-handoff-stale", "Execution result handoff is stale")
        after = supplied["repository_after_binding"]
        _require((after is None) == (result.get("repository_after") is None), "stale", "execution-handoff-stale", "Repository result handoff is incomplete")
        if after is not None: _require(self._read(after, label="repository observation") == result["repository_after"], "stale", "execution-handoff-stale", "Repository result handoff differs")
        return request, result, after, None
    def _finalize(self, inspection, state, supplied):
        pending = state["pending"]; request, result, after, observed = self._verified_handoff(state, inspection, supplied)
        if observed is not None: return self._finalize_pr_read(state, inspection, request, observed)
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
            if not (self._same_repository(request["repository_before"], result["repository_after"]) and self._clean_repository(result["repository_after"])):
                return self._pause(state, inspection, rows, "read-only-agent-repository-drift")
        expected = "plan" if state["phase"] == "PLANNING" else "implementer" if state["phase"] in {"TEST_IMPLEMENTATION", "IMPLEMENTATION"} else "review"
        try: candidate = self._candidate(result, expected)
        except OrchestratorFailure: return self._pause(state, inspection, rows, "candidate-output-invalid")
        candidate_binding = self._candidate_artifact(state, result_binding, candidate)
        return self._after_agent(state, inspection, pending["request_binding"], candidate_binding, after, rows, candidate)
    def advance(self, expected_tip, request):
        inspection = self._status(); state = self._state(inspection); self.family = state["family_run_id"]
        if state["pending"] is not None and expected_tip != inspection["pointer_sha256"]: _fail("busy" if state["pending"]["status"] == "requested" else "stale", "attempt-in-flight", "Another attempt owns this state")
        self._expect(inspection, expected_tip)
        if state["pending"] is not None: _require(state["pending"]["kind"] != "human", "unsupported", "human-gate-requires-approval", "Human gates require approve or recover"); return self._finalize(inspection, state, request)
        _require(state["phase"] not in GATES, "unsupported", "human-gate-requires-approval", "Human gates require approve")
        _require(state["phase"] not in {"PAUSED", "COMPLETED"}, "unsupported", "phase-not-steppable", "Phase is not executable", state["phase"])
        return self._claim(inspection, state, self._runtime(request), request)
    def _authorization(self, state, challenge, supplied):
        source = _source(supplied); target_kind, target_number = "issue", self.issue
        if source["kind"] == "pull-request-review":
            _require(challenge["gate"] == "final", "unsupported", "authorization-target", "Only final approval accepts a pull-request review")
            metadata = self._read(self._active(state, "pr-metadata"), NODE_PATH, "PR metadata node")
            observed = next(row["binding"] for row in metadata["evidence"] if row["role"] == "github-pr-observation"); target_kind, target_number = "pull-request", self._read(observed)["number"]
        observed = _translate(lambda: self._runtime(supplied).observe_authorization(workflow_issue=self.issue, target_kind=target_kind, target_number=target_number, source_kind=source["kind"], source_id=source["id"], challenge_binding=state["pending"]["request_binding"], confirmation=challenge["confirmation"], source_request_binding=state["pending"]["request_binding"]), "runtime")
        document = _with_digest({"format": AUTHORIZATION_FORMAT, "challenge_binding": state["pending"]["request_binding"], "decision": "approve", "actor": {"provider": "github", **observed["actor"]}, "source": {"repository": observed["repository"], **observed["source"]}, "confirmation": challenge["confirmation"]}, "authorization_sha256")
        return self._publish("human-authorization", "authorization-%s" % document["authorization_sha256"], state["pending"]["request_binding"], [("workflow-orchestration/human-authorization.json", document)], state["generation"] + 1)
    def _gate_inputs(self, state, gate):
        if gate == "plan":
            return [("plan-review", self._candidate_binding(state, "plan-review")), ("plan-snapshot", self._candidate_binding(state, "plan-snapshot"))], None
        if gate == "tests":
            subject = self._active(state, "test-manifest")
            return [("test-manifest", subject), ("test-review", self._candidate_binding(state, "test-review"))], self._read(subject, NODE_PATH)["repository_observation_binding"]
        metadata = self._active(state, "pr-metadata"); document = self._read(metadata, NODE_PATH)
        validation = self._read(self._active(state, "validation"), NODE_PATH)["subject_binding"]
        final = self._read(self._active(state, "final-review"), NODE_PATH)["subject_binding"]
        return [("final-review", final), ("pr-metadata", metadata), ("validation", validation)], document["repository_observation_binding"]
    def _fresh_final(self, state):
        metadata = self._read(self._active(state, "pr-metadata"), NODE_PATH, "PR metadata node")
        previous = next(row["binding"] for row in metadata["evidence"] if row["role"] == "github-pr-observation")
        expected = self._read(previous, label="PR observation")
        adapter = self._runtime(None)
        local = _translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        prior_local = self._read(metadata["repository_observation_binding"], label="PR local observation")
        _require(self._same_repository(prior_local, local) and self._clean_repository(local) and local["head"]["commit"] == expected["head_sha"] and local["base"]["commit"] == expected["base_sha"], "stale", "final-repository-mismatch", "Local repository changed after PR metadata was selected")
        current = _translate(lambda: adapter.observe_pull_request(self.issue, expected["number"], state["pending"]["request_binding"]), "runtime")
        fields = ("repository", "number", "url", "state", "draft", "base_ref", "base_sha", "head_ref", "head_sha", "title_sha256", "body_sha256")
        _require(all(current[field] == expected[field] for field in fields) and current["state"] == "OPEN" and current["draft"], "stale", "pr-reconciliation-mismatch", "Draft PR changed before final completion")
        pr_binding = self._publish("github-pr-observation", "pr-%d-%s" % (current["number"], current["observation_sha256"]), state["pending"]["request_binding"], [("workflow-orchestration/github-pr-observation.json", current)], state["generation"] + 1)
        local_binding = self._publish("work-type-diff-observation", "observation-%s" % local["observation_sha256"], state["triage_binding"], [("workflow-work-type/diff-observation.json", local)], state["generation"] + 1)
        return pr_binding, local_binding
    def approve(self, expected_tip, supplied):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._state(inspection); self.family = state["family_run_id"]
        _require(state["pending"] is not None and state["pending"]["kind"] == "human" and state["pending"]["status"] == "requested" and state["phase"] in GATES, "conflict", "no-open-gate", "No human approval gate is open")
        challenge = self._read(state["pending"]["request_binding"], label="human challenge"); gate = GATES[state["phase"]]
        self._check_challenge(challenge, gate, state["previous_authority"])
        expected_subjects, expected_repository = self._gate_inputs(state, gate)
        expected_subjects = [{"slot": slot, "binding": binding} for slot, binding in sorted(expected_subjects, key=lambda row: row[0].encode())]
        _require(challenge["subjects"] == expected_subjects and (expected_repository is None or challenge["repository_observation_binding"] == expected_repository), "stale", "challenge-stale", "Pending human challenge has stale evidence")
        authorization = self._authorization(state, challenge, supplied)
        fresh_pr, fresh_local = self._fresh_final(state) if gate == "final" else (None, None)
        if gate == "final":
            _require(self._authorization(state, challenge, supplied) == authorization, "stale", "authorization-source-stale", "Final authorization source changed before completion")
            fresh_pr, fresh_local = self._fresh_final(state)
        if gate == "plan": subject, rows, repository = self._candidate_binding(state, "plan-snapshot"), [("technical-plan-review", self._candidate_binding(state, "plan-review"))], challenge["repository_observation_binding"]
        elif gate == "tests":
            subject = self._active(state, "test-manifest"); rows, repository = [("technical-test-review", self._candidate_binding(state, "test-review"))], self._read(subject, NODE_PATH)["repository_observation_binding"]
        else:
            subject = self._active(state, "pr-metadata")
            final = self._read(self._active(state, "final-review"), NODE_PATH, "final review node")["subject_binding"]
            rows = [("github-pr-observation", fresh_pr), ("final-review", final)]; repository = fresh_local
        node = {"plan": "plan-approval", "tests": "test-approval", "final": "pr-approval"}[gate]
        _wrapper, policy_binding = self._bind(state, inspection, node, subject, rows, repository, authorization)
        successor = self._successor(state, inspection["authority"], phase=GATE_NEXT[gate], policy_state_binding=policy_binding, candidates=_drop(state["candidates"], "plan-snapshot", "plan-revision", "plan-review", "test-review", "human-challenge", "human-authorization"), transition={"type": {"plan": "plan-approve", "tests": "tests-approve", "final": "final-approve"}[gate], "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("completed" if gate == "final" else "approved", successor, binding, committed)
    def cancel(self, expected_tip, reason):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._state(inspection); self.family = state["family_run_id"]; pending = state["pending"]
        _require(pending is not None and pending["status"] == "requested" and pending["kind"] != "human", "conflict", "no-cancellable-attempt", "No executable attempt is pending")
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
                result = self._read(result_binding, label="execution result"); return result["request_binding"], self._read(result["request_binding"], label="execution request"), result
        _fail("corrupt", "recovery-state-unrecoverable", "Paused state has no recorded safe attempt")
    def _resume_phase(self, request):
        operation = request["operation"]
        if operation["kind"] == "validation": return "VALIDATION"
        if operation["kind"] in {"github-read", "github-write"}: return "PR_PREPARATION"
        names = {name: phase for phase, (_role, name) in AGENT_PHASES.items()}
        _require(operation["name"] in names, "corrupt", "recovery-state-unrecoverable", "Attempt operation cannot reconstruct a safe phase")
        return names[operation["name"]]
    def recover(self, expected_tip, supplied):
        inspection = self._status(); self._expect(inspection, expected_tip); state = self._state(inspection); self.family = state["family_run_id"]; pending = state["pending"]
        if pending is not None and pending["kind"] == "human":
            challenge = self._read(pending["request_binding"], label="recovery challenge")
            _require(state["phase"] == "PAUSED", "conflict", "not-recoverable", "Pending human request is not a recovery challenge")
            self._check_challenge(challenge, "recovery", state["previous_authority"])
            _require(challenge["subjects"] and len(challenge["subjects"]) == 1 and challenge["subjects"][0].get("slot") == "pending-attempt", "corrupt", "recovery-state-unrecoverable", "Recovery challenge does not bind one recorded attempt")
            _authorization = self._authorization(state, challenge, supplied); request = self._read(challenge["subjects"][0]["binding"], label="recovery request"); phase = self._resume_phase(request)
            successor = self._successor(state, inspection["authority"], phase=phase, pending=None, candidates=_put(state["candidates"], "human-authorization", _authorization), transition={"type": "recover", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
            binding, committed = self._commit(successor); return self._result("recovered", successor, binding, committed)
        _require(state["phase"] == "PAUSED" or (pending is not None and pending["status"] == "cancel-requested"), "conflict", "not-paused", "Recovery needs a paused or cancelled attempt")
        request_binding, request, result = self._recovery_attempt(state, inspection)
        _require(not (request["operation"]["kind"] == "validation" and result and result["outcome"] != "succeeded"), "paused", "unsupported-policy-transition", "Failed validation needs a replacement policy transition")
        _challenge, human = self._challenge(inspection["authority"], "recovery", [("pending-attempt", request_binding)], None, state["generation"] + 1)
        successor = self._successor(state, inspection["authority"], phase="PAUSED", pending=human, transition={"type": "recover", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("recovery-requested", successor, binding, committed)
def status(root, issue, **_kwargs):
    return Orchestrator(root, issue)._status()
def plan_next(root, issue, **_kwargs):
    return Orchestrator(root, issue).plan()
def init(root, issue, request=None, **_kwargs):
    return Orchestrator(root, issue).initialize(request or {})
def step(root, issue, expected_tip=None, request=None, **_kwargs):
    return Orchestrator(root, issue).advance(expected_tip, request)
def approve(root, issue, expected_tip=None, authorization=None, **_kwargs):
    return Orchestrator(root, issue).approve(expected_tip, authorization)
def cancel(root, issue, expected_tip=None, reason=None, **_kwargs):
    return Orchestrator(root, issue).cancel(expected_tip, reason)
def recover(root, issue, expected_tip=None, authorization=None, **_kwargs):
    return Orchestrator(root, issue).recover(expected_tip, authorization)
def _dispatch(handler, args, payload):
    if handler is init: return handler(args.root, args.issue, request=payload)
    if handler in (status, plan_next): return handler(args.root, args.issue)
    if handler is step: return handler(args.root, args.issue, expected_tip=args.expected_tip, request=payload)
    if handler is cancel: return handler(args.root, args.issue, expected_tip=args.expected_tip, reason=args.reason)
    return handler(args.root, args.issue, expected_tip=args.expected_tip, authorization=payload)
class OrchestratorArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)
def build_parser():
    parser = OrchestratorArgumentParser(description=__doc__); commands = parser.add_subparsers(dest="command", required=True, parser_class=OrchestratorArgumentParser)
    for name in ("status", "plan-next"):
        command = commands.add_parser(name); command.add_argument("issue", type=int); command.add_argument("--root", required=True)
    command = commands.add_parser("init"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--request", required=True)
    command = commands.add_parser("step"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--request")
    command = commands.add_parser("approve"); command.add_argument("issue", type=int); command.add_argument("--root", required=True); command.add_argument("--expected-tip", required=True); command.add_argument("--authorization", required=True)
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
        document = _dispatch(COMMAND_HANDLERS[args.command], args, payload); sys.stdout.buffer.write(inspector.canonical_document(document))
        return OUTCOMES.get(document["outcome"]["status"], 0)
    except OrchestratorFailure as error:
        sys.stdout.buffer.write(inspector.canonical_document(error.document())); return OUTCOMES[error.status]
COMMAND_HANDLERS = {"status": status, "plan-next": plan_next, "init": init, "step": step, "approve": approve, "cancel": cancel, "recover": recover}
if __name__ == "__main__":
    raise SystemExit(main())
