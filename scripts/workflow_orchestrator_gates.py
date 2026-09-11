"""Approval-gate and rejection/rework composition for the orchestrator."""
import copy

try:
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_policy as policy
    import workflow_runtime as runtime
    import workflow_supervision_policy as supervision
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_policy as policy
    from scripts import workflow_runtime as runtime
    from scripts import workflow_supervision_policy as supervision


STATE_PATH = "workflow-orchestration/state.json"
NODE_PATH = "workflow-orchestration/node.json"
POLICY_PATH = "workflow-policy/state.json"
RESULT_PATH = runtime.EXECUTION_RESULT_PATH
AUTHORIZATION_FORMAT = "chess-echo-human-authorization-v1"
GATES = {
    "WAITING_FOR_PLAN_APPROVAL": "plan",
    "WAITING_FOR_TEST_APPROVAL": "tests",
    "WAITING_FOR_FINAL_APPROVAL": "final",
    "WAITING_FOR_PR_PUBLICATION_APPROVAL": "pr-publication",
}


def _digest(value):
    return inspector.sha256(inspector.canonical_bytes(value))


def _with_digest(value, field):
    value = copy.deepcopy(value)
    value[field] = _digest(value)
    return value


def _put(rows, slot, binding):
    rows = [copy.deepcopy(row) for row in rows if row["slot"] != slot]
    rows.append({"slot": slot, "binding": binding})
    return sorted(rows, key=lambda row: row["slot"].encode())


def _drop(rows, *slots):
    return [copy.deepcopy(row) for row in rows if row["slot"] not in slots]


class ApprovalGateMixin:
    """Gate-focused methods composed by the authority-owning orchestrator."""

    def _authorization_document(self, state, challenge, supplied, challenge_binding=None, historical=False):
        self._gate_require(isinstance(supplied, dict), "unsupported", "authorization-required", "A GitHub authorization source is required")
        self._gate_require(supplied.get("kind") in {"issue-comment", "pull-request-review"}, "unsupported", "authorization-source", "Authorization source is unsupported")
        self._gate_require(type(supplied.get("id")) is int and supplied["id"] > 0, "corrupt", "authorization-id", "Authorization source ID is invalid")
        source = {"kind": supplied["kind"], "id": supplied["id"]}
        if source["kind"] == "pull-request-review":
            self._gate_fail("unsupported", "authorization-target", "Pre-publication gates require an issue comment")
        challenge_binding = challenge_binding or state["pending"]["request_binding"]
        adapter = self._history_adapter if historical and self._history_adapter is not None else self._runtime_drift(
            lambda: self._runtime(supplied),
            "gate-repository-mismatch",
            "Repository changed after the gate challenge",
            {"runtime-worktree-untrusted", "runtime-phase-repository-changed"},
            guard=self._selected is not None and self._selected["phase"] in GATES,
        )
        if historical:
            self._history_adapter = adapter
        observed = self._gate_translate(
            lambda: adapter.observe_authorization(
                workflow_issue=self.issue,
                target_kind="issue",
                target_number=self.issue,
                source_kind=source["kind"],
                source_id=source["id"],
                challenge_binding=challenge_binding,
                confirmation=challenge["confirmation"],
                source_request_binding=challenge_binding,
            ),
            "runtime",
        )
        decision = challenge.get("decision")
        self._gate_require(decision in {"approve", "reject"}, "corrupt", "authorization-decision-invalid", "Human challenge decision is invalid")
        return _with_digest(
            {
                "format": AUTHORIZATION_FORMAT,
                "challenge_binding": challenge_binding,
                "decision": decision,
                "actor": {"provider": "github", **observed["actor"]},
                "source": {"repository": observed["repository"], **observed["source"]},
                "confirmation": challenge["confirmation"],
            },
            "authorization_sha256",
        )

    def _authorization(self, state, challenge, supplied):
        document = self._authorization_document(state, challenge, supplied)
        return self._publish(
            "human-authorization",
            "authorization-%s" % document["authorization_sha256"],
            state["pending"]["request_binding"],
            [("workflow-orchestration/human-authorization.json", document)],
            state["generation"] + 1,
        )

    def _rejection_artifact(self, state, gate):
        self._gate_require(gate == "tests", "unsupported", "gate-rejection-unsupported", "Gate has no safe rejection/rework target", gate)
        return self._active(state, "test-manifest")

    def _validate_rejection_request_transition(self, prior_binding, prior, current):
        pending, gate = prior["pending"], GATES.get(prior["phase"])
        if current["transition"]["type"] != "rejection-request":
            return False
        self._gate_require(gate == "tests" and pending is not None and pending["kind"] == "human" and pending["status"] == "requested", "stale", "gate-rejection-transition-unselected", "Rejection request did not start from the supported current human gate")
        approval = self._read(pending["request_binding"], supervision.CHALLENGE_PATH, "approval challenge")
        self._check_challenge(prior, approval, gate, prior["previous_authority"])
        subjects, repository = self._gate_inputs(prior, gate)
        expected_subjects = [{"slot": slot, "binding": binding} for slot, binding in sorted(subjects, key=lambda row: row[0].encode())]
        self._gate_require(approval["mode"] == "supervised" and approval["subjects"] == expected_subjects and approval["repository_observation_binding"] == repository, "stale", "gate-rejection-transition-unselected", "Rejected approval challenge is stale")
        rejection_pending = current["pending"]
        self._gate_require(rejection_pending is not None and rejection_pending["kind"] == "human-rejection" and rejection_pending["status"] == "requested", "stale", "gate-rejection-transition-unselected", "Rejection request is not the selected pending action")
        challenge_binding = rejection_pending["request_binding"]
        challenge = self._read(challenge_binding, supervision.REJECTION_CHALLENGE_PATH, "gate rejection challenge")
        self._gate_translate(lambda: supervision.validate_rejection_challenge(challenge, prior["supervision_policy_binding"], self._supervision(prior), prior_binding, current["previous_pointer_sha256"], prior["generation"], prior["phase"], pending["request_binding"], approval, self._rejection_artifact(prior, gate), supervision.REWORK_TARGETS[gate]["rework_phase"]), "supervision-policy")
        projection = self._gate_translate(lambda: evidence.project(self.root, challenge_binding), "evidence")
        expected_pending = {"attempt_id": _digest({"authority": prior_binding, "kind": "human-rejection", "challenge": challenge_binding}), "kind": "human-rejection", "request_binding": challenge_binding, "status": "requested"}
        expected_transition = {"type": "rejection-request", "request_binding": challenge_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}
        expected_candidates = _put(prior["candidates"], "human-challenge", challenge_binding)
        self._gate_require(projection["decision"]["type"] == "human-challenge" and projection["subject"] == prior_binding and current["phase"] == prior["phase"] and current["pending"] == expected_pending and current["candidates"] == expected_candidates and current["policy_state_binding"] == prior["policy_state_binding"] and current["supervision_policy_binding"] == prior["supervision_policy_binding"] and current["transition"] == expected_transition, "stale", "gate-rejection-transition-unselected", "Rejection request is not bound to the selected gate")
        return True

    def _validate_rejection_completion_transition(self, prior, current):
        pending, gate = prior["pending"], GATES.get(prior["phase"])
        if pending is None or pending["kind"] != "human-rejection":
            return False
        self._gate_require(gate == "tests" and pending["status"] == "requested" and current["transition"]["type"] == "tests-reject", "stale", "gate-rejection-transition-unselected", "Gate rejection did not select the supported rework transition")
        approval_state = self._read(prior["previous_authority"], STATE_PATH, "rejected gate state")
        approval_pending = approval_state["pending"]
        self._gate_require(approval_state["phase"] == prior["phase"] and approval_pending is not None and approval_pending["kind"] == "human" and approval_pending["status"] == "requested", "stale", "gate-rejection-transition-unselected", "Rejected gate state is not the selected predecessor")
        approval_binding = approval_pending["request_binding"]
        approval = self._read(approval_binding, supervision.CHALLENGE_PATH, "approval challenge")
        challenge_binding = pending["request_binding"]
        challenge = self._read(challenge_binding, supervision.REJECTION_CHALLENGE_PATH, "gate rejection challenge")
        artifact = self._rejection_artifact(approval_state, gate)
        self._gate_translate(lambda: supervision.validate_rejection_challenge(challenge, prior["supervision_policy_binding"], self._supervision(prior), prior["previous_authority"], prior["previous_pointer_sha256"], approval_state["generation"], approval_state["phase"], approval_binding, approval, artifact, supervision.REWORK_TARGETS[gate]["rework_phase"]), "supervision-policy")
        rejection_binding = self._candidate_binding(current, "gate-rejection")
        rejection_projection = self._gate_translate(lambda: evidence.project(self.root, rejection_binding), "evidence")
        rejection = self._read(rejection_binding, supervision.REJECTION_PATH, "gate rejection")
        authorization_binding = rejection["human_authorization_binding"]
        authorization_projection = self._gate_translate(lambda: evidence.project(self.root, authorization_binding), "evidence")
        authorization = self._read(authorization_binding, "workflow-orchestration/human-authorization.json", "rejection authorization")
        source = {"kind": authorization["source"]["kind"], "id": authorization["source"]["id"]}
        observed = self._authorization_document(prior, challenge, source, challenge_binding, historical=True)
        self._gate_require(observed == authorization, "stale", "authorization-source-stale", "Rejection authorization source changed")
        self._gate_translate(lambda: supervision.validate_rejection(rejection, prior["supervision_policy_binding"], self._supervision(prior), prior["previous_authority"], prior["previous_pointer_sha256"], challenge_binding, challenge, approval, authorization_binding, authorization), "supervision-policy")
        expected_candidates = _put(_drop(prior["candidates"], "execution-request", "execution-result", "test-review", "human-challenge", "human-authorization"), "gate-rejection", rejection_binding)
        expected_transition = {"type": "tests-reject", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None}
        self._gate_require(rejection_projection["decision"]["type"] == "gate-rejection" and rejection_projection["subject"] == challenge_binding and authorization_projection["decision"]["type"] == "human-authorization" and authorization_projection["subject"] == challenge_binding and current["phase"] == "TEST_IMPLEMENTATION" and current["pending"] is None and current["candidates"] == expected_candidates and current["policy_state_binding"] == prior["policy_state_binding"] and current["supervision_policy_binding"] == prior["supervision_policy_binding"] and current["transition"] == expected_transition, "stale", "gate-rejection-transition-unselected", "Gate rejection is not selected by exact human authorization")
        return True

    def _replace_test_manifest(self, state, inspection, subject, evidence_rows, repository, rejection):
        current = self._read(state["policy_state_binding"], POLICY_PATH, "policy state")
        active = {row["node"]: row["binding"] for row in current["active"]}
        dependencies = [{"node": parent, "binding": active[parent]} for parent in policy.DEPENDENCIES["test-manifest"]]
        wrapper, _document = self._node("test-manifest", subject, evidence_rows, repository, None, dependencies, state["generation"] + 1)
        chain = self._policy_chain(state, inspection)
        operation = {"type": "reopen", "target": "tests", "reason": rejection["reason"], "replacements": [{"node": "test-manifest", "binding": wrapper}]}
        request = {"format": policy.REQUEST_FORMAT, "issue": self.issue, "family_run_id": self.family, "state": current, "expected_state_sha256": current["state_sha256"], "bindings": self._policy_inputs(chain, [wrapper]), "authority_chain": chain, "operation": operation}
        result = self._gate_translate(lambda: policy.evaluate(self.root, request, state["policy_state_binding"]["sha256"]), "policy")
        self._gate_require(result["outcome"] == {"status": "resolved", "code": "evaluated"} and result["changed_roots"] == ["test-manifest"], "paused", "unsupported-policy-transition", "Policy did not authorize rejected test replacement")
        return wrapper, self._publish_policy(result["next_state"], state["policy_state_binding"], state["policy_state_binding"], state["generation"] + 1)

    def _phase_repository(self, state, phase):
        rejection_binding = self._candidate_binding(state, "gate-rejection", required=False)
        if phase == "TEST_IMPLEMENTATION" and rejection_binding is not None:
            rejection = self._read(rejection_binding, supervision.REJECTION_PATH, "gate rejection")
            return self._read(rejection["repository_observation_binding"], label="rejected test repository observation")
        if phase == "PR_PREPARATION":
            satisfaction = self._candidate_binding(state, "pr-publication-gate-satisfaction", required=False)
            if satisfaction is not None:
                return self._read(self._read(satisfaction, supervision.SATISFACTION_PATH, "publication satisfaction")["repository_observation_binding"], label="publication repository observation")
        nodes = {"TEST_IMPLEMENTATION": "plan-approval", "TEST_REVIEW": "test-manifest", "IMPLEMENTATION": "test-approval", "VALIDATION": "implementation-submission", "FINAL_REVIEW": "validation", "PR_PREPARATION": "final-review"}
        if phase in {"PLANNING", "PLAN_REVIEW"}:
            result = self._candidate_binding(state, "execution-result", required=False)
            return None if result is None else self._read(result, RESULT_PATH, "phase execution result")["repository_after"]
        if phase in GATES and state["pending"] is not None:
            path = supervision.REJECTION_CHALLENGE_PATH if state["pending"]["kind"] == "human-rejection" else supervision.CHALLENGE_PATH
            challenge = self._read(state["pending"]["request_binding"], path, "gate challenge")
            binding = challenge["repository_observation_binding"]
            return None if binding is None else self._read(binding, label="gate repository observation")
        if phase not in nodes:
            return None
        wrapper = self._read(self._active(state, nodes[phase]), NODE_PATH, "%s node" % nodes[phase])
        return self._read(wrapper["repository_observation_binding"], label="selected repository observation")

    def _tests_submit(self, state, inspection, report_binding, after, rows):
        self._gate_require(after is not None, "stale", "repository-after-missing", "Test attempt lacks a repository observation")
        triage, baseline, _baseline_binding, _config = self._facts(state)
        profile = next(item for item in baseline["profiles"] if item["id"] == triage["classification"]["validation_profile"])
        observation = self._read(after, label="test observation")
        if not (runtime.clean_repository(observation) and runtime.test_scope(observation, profile["test_paths"])):
            return self._pause(state, inspection, rows, "test-scope-drift")
        rejection_binding = self._candidate_binding(state, "gate-rejection", required=False)
        if rejection_binding is None:
            _wrapper, policy_binding = self._bind(state, inspection, "test-manifest", report_binding, [("test-diff", after), ("test-report", report_binding)], after)
        else:
            rejection = self._read(rejection_binding, supervision.REJECTION_PATH, "gate rejection")
            _wrapper, policy_binding = self._replace_test_manifest(state, inspection, report_binding, [("test-diff", after), ("test-report", report_binding)], after, rejection)
        successor = self._successor(state, inspection["authority"], phase="TEST_REVIEW", policy_state_binding=policy_binding, candidates=_drop(rows, "test-manifest", "gate-rejection", "test-review", "human-challenge", "human-authorization"), transition={"type": "tests-request", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor)
        return self._result("executed", successor, binding, committed)

    def _rejection_inputs(self, state, phase):
        rejection_binding = self._candidate_binding(state, "gate-rejection", required=False)
        if phase != "TEST_IMPLEMENTATION" or rejection_binding is None:
            return []
        rejection = self._read(rejection_binding, supervision.REJECTION_PATH, "gate rejection")
        subjects = {row["slot"]: row["binding"] for row in rejection["subjects"]}
        return [
            ("gate-rejection", rejection_binding),
            ("rejected-approval-challenge", rejection["approval_challenge_binding"]),
            ("rejected-test-manifest", subjects["test-manifest"]),
            ("rejected-test-review", subjects["test-review"]),
        ]

    def approve(self, expected_tip, supplied):
        inspection = self._status()
        self._expect(inspection, expected_tip)
        state = self._selected_state(inspection)
        self._gate_require(state["pending"] is not None and state["pending"]["kind"] == "human" and state["pending"]["status"] == "requested", "conflict", "no-open-gate", "No human approval request is open")
        if state["phase"] not in GATES:
            return self._approve_supervision_change(state, inspection, supplied)
        challenge = self._read(state["pending"]["request_binding"], supervision.CHALLENGE_PATH, "gate challenge")
        gate = GATES[state["phase"]]
        self._check_challenge(state, challenge, gate, state["previous_authority"])
        expected_subjects, expected_repository = self._gate_inputs(state, gate)
        expected_subjects = [{"slot": slot, "binding": binding} for slot, binding in sorted(expected_subjects, key=lambda row: row[0].encode())]
        self._gate_require(challenge["mode"] == "supervised" and challenge["subjects"] == expected_subjects and challenge["repository_observation_binding"] == expected_repository, "stale", "challenge-stale", "Pending human challenge has stale evidence")
        authorization = self._authorization(state, challenge, supplied)
        fresh_repository = None
        if gate in {"final", "pr-publication"}:
            self._fresh_local(state, challenge["repository_observation_binding"], state["pending"]["request_binding"])
            self._gate_require(self._authorization(state, challenge, supplied) == authorization, "stale", "authorization-source-stale", "Final authorization source changed before completion")
            fresh_repository = self._fresh_local(state, challenge["repository_observation_binding"], state["pending"]["request_binding"])
        return self._complete_gate(state, inspection, challenge, authorization, fresh_repository)

    def _request_rejection(self, state, inspection, reason):
        pending, gate = state["pending"], GATES.get(state["phase"])
        self._gate_require(gate is not None and pending is not None and pending["kind"] == "human" and pending["status"] == "requested", "conflict", "no-rejectable-gate", "No current supervised approval gate can be rejected")
        self._gate_require(gate in supervision.REWORK_TARGETS, "unsupported", "gate-rejection-unsupported", "Gate has no safe rejection/rework target", gate)
        self._gate_require(isinstance(reason, str) and reason == reason.strip() and reason and len(reason.encode("utf-8")) <= 4096, "unsupported", "rejection-reason-required", "Rejection needs a trimmed nonempty reason at most 4096 bytes")
        approval_binding = pending["request_binding"]
        approval = self._read(approval_binding, supervision.CHALLENGE_PATH, "approval challenge")
        self._check_challenge(state, approval, gate, state["previous_authority"])
        subjects, repository = self._gate_inputs(state, gate)
        expected_subjects = [{"slot": slot, "binding": binding} for slot, binding in sorted(subjects, key=lambda row: row[0].encode())]
        self._gate_require(approval["mode"] == "supervised" and approval["subjects"] == expected_subjects and approval["repository_observation_binding"] == repository, "stale", "challenge-stale", "Pending human challenge has stale evidence")
        artifact = self._rejection_artifact(state, gate)
        target = supervision.REWORK_TARGETS[gate]
        challenge = self._gate_translate(lambda: supervision.build_rejection_challenge(state["supervision_policy_binding"], self._supervision(state), inspection["authority"], inspection["pointer_sha256"], state["generation"], state["phase"], approval_binding, approval, artifact, reason, target["rework_phase"]), "supervision-policy")
        challenge_binding = self._publish("human-challenge", "challenge-%s" % challenge["challenge_sha256"], inspection["authority"], [(supervision.REJECTION_CHALLENGE_PATH, challenge)], state["generation"] + 1)
        rejection_pending = {"attempt_id": _digest({"authority": inspection["authority"], "kind": "human-rejection", "challenge": challenge_binding}), "kind": "human-rejection", "request_binding": challenge_binding, "status": "requested"}
        successor = self._successor(state, inspection["authority"], pending=rejection_pending, candidates=_put(state["candidates"], "human-challenge", challenge_binding), transition={"type": "rejection-request", "request_binding": challenge_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor)
        return self._result("rejection-requested", successor, binding, committed)

    def _complete_rejection(self, state, inspection, supplied):
        pending, gate = state["pending"], GATES.get(state["phase"])
        self._gate_require(gate == "tests" and pending is not None and pending["kind"] == "human-rejection" and pending["status"] == "requested", "conflict", "no-open-rejection", "No current gate rejection challenge is open")
        approval_state = self._read(state["previous_authority"], STATE_PATH, "rejected gate state")
        approval_pending = approval_state["pending"]
        self._gate_require(approval_state["phase"] == state["phase"] and approval_pending is not None and approval_pending["kind"] == "human" and approval_pending["status"] == "requested", "stale", "gate-rejection-context-stale", "Rejected approval gate is no longer exact")
        approval_binding = approval_pending["request_binding"]
        approval = self._read(approval_binding, supervision.CHALLENGE_PATH, "approval challenge")
        challenge_binding = pending["request_binding"]
        challenge = self._read(challenge_binding, supervision.REJECTION_CHALLENGE_PATH, "gate rejection challenge")
        artifact = self._rejection_artifact(approval_state, gate)
        self._gate_translate(lambda: supervision.validate_rejection_challenge(challenge, state["supervision_policy_binding"], self._supervision(state), state["previous_authority"], state["previous_pointer_sha256"], approval_state["generation"], approval_state["phase"], approval_binding, approval, artifact, supervision.REWORK_TARGETS[gate]["rework_phase"]), "supervision-policy")
        authorization = self._authorization(state, challenge, supplied)
        self._gate_require(self._authorization(state, challenge, supplied) == authorization, "stale", "authorization-source-stale", "Rejection authorization source changed before completion")
        authorization_document = self._read(authorization, "workflow-orchestration/human-authorization.json", "rejection authorization")
        rejection = self._gate_translate(lambda: supervision.gate_rejection(state["supervision_policy_binding"], self._supervision(state), state["previous_authority"], state["previous_pointer_sha256"], challenge_binding, challenge, approval, authorization, authorization_document), "supervision-policy")
        rejection_binding = self._publish("gate-rejection", "rejection-%s" % rejection["rejection_sha256"], challenge_binding, [(supervision.REJECTION_PATH, rejection)], state["generation"] + 1)
        candidates = _put(_drop(state["candidates"], "execution-request", "execution-result", "test-review", "human-challenge", "human-authorization"), "gate-rejection", rejection_binding)
        successor = self._successor(state, inspection["authority"], phase=supervision.REWORK_TARGETS[gate]["rework_phase"], pending=None, candidates=candidates, transition={"type": "tests-reject", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor)
        return self._result("rejected", successor, binding, committed)

    def reject(self, expected_tip, supplied, reason):
        inspection = self._status()
        self._expect(inspection, expected_tip)
        state = self._selected_state(inspection)
        if state["pending"] is not None and state["pending"]["kind"] == "human-rejection":
            self._gate_require(reason is None, "conflict", "rejection-reason-already-bound", "Open rejection challenge already binds its reason")
            return self._complete_rejection(state, inspection, supplied)
        self._gate_require(supplied is None, "conflict", "rejection-challenge-required", "Request a rejection challenge before supplying authorization")
        return self._request_rejection(state, inspection, reason)
