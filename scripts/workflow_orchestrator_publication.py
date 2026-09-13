"""Authority-selected source-publication composition for the orchestrator."""
import copy
try:
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_orchestrator_resume as resume
    import workflow_runtime as runtime
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_orchestrator_resume as resume
    from scripts import workflow_runtime as runtime

NODE_PATH = "workflow-orchestration/node.json"
SOURCE_PUBLICATION_REQUEST_PATH = "workflow-orchestration/source-publication-request.json"
SOURCE_PUBLICATION_RESULT_PATH = "workflow-orchestration/source-publication-result.json"
OUTCOMES = {"resolved", "missing", "unsupported", "corrupt", "ambiguous", "stale", "denied", "busy", "conflict", "uncertain", "paused"}

def _digest(value): return inspector.sha256(inspector.canonical_bytes(value))
def _put(rows, slot, binding):
    rows = [copy.deepcopy(row) for row in rows if row["slot"] != slot]
    rows.append({"slot": slot, "binding": binding}); return sorted(rows, key=lambda row: row["slot"].encode())
def _drop(rows, *slots): return [copy.deepcopy(row) for row in rows if row["slot"] not in slots]

class SourcePublicationMixin:
    """Publication methods composed by the authority-owning orchestrator."""
    def _pr_context(self, state, observation):
        final = self._active(state, "final-review"); wrapper = self._read(final, NODE_PATH, "final review node")
        candidate = self._candidate_schema(self._read(wrapper["subject_binding"], label="final review candidate"), "review")
        pr = self._publication_translate(lambda: resume.validate_pr_metadata(candidate.get("pr")), "resume")
        self._publication_require(pr["head_ref"] == "chess-echo-agent/issue-%d" % self.issue, "denied", "pr-head-ref-not-deterministic", "Draft PR must use the deterministic issue branch")
        _triage, baseline, _baseline_binding, _config = self._facts(state)
        expectation = {"repository": baseline["repository"], "base_ref": baseline["target_base"]["name"], "base_sha": observation["base"]["commit"], "head_ref": pr["head_ref"], "head_sha": observation["head"]["commit"], "title_sha256": inspector.sha256(pr["title"].encode()), "body_sha256": inspector.sha256(pr["body"].encode())}
        return pr, expectation, wrapper
    def _publication_context(self, state, inspection):
        request_binding = self._candidate_binding(state, "source-publication-request")
        result_binding = self._candidate_binding(state, "source-publication-result")
        request_projection = self._publication_translate(lambda: evidence.project(self.root, request_binding), "evidence")
        result_projection = self._publication_translate(lambda: evidence.project(self.root, result_binding), "evidence")
        request = self._read(request_binding, SOURCE_PUBLICATION_REQUEST_PATH, "source publication request")
        result = self._read(result_binding, SOURCE_PUBLICATION_RESULT_PATH, "source publication result")
        observation_binding = self._candidate_binding(state, "pr-publication-observation")
        observation = self._read(observation_binding, label="publication repository observation")
        pr, _expectation, _wrapper = self._pr_context(state, observation)
        target_ref = "refs/heads/chess-echo-agent/issue-%d" % self.issue
        selected_observation = request.get("repository_observation") if isinstance(request, dict) else None
        self._publication_require(isinstance(selected_observation, dict) and runtime.same_repository(observation, selected_observation) and runtime.clean_repository(selected_observation), "stale", "source-publication-request-stale", "Selected publication request differs from the approved repository")
        expected_request = {
            "format": runtime.SOURCE_PUBLICATION_REQUEST_FORMAT,
            "issue": self.issue,
            "family_run_id": self.family,
            "repository": selected_observation["repository"],
            "target_ref": target_ref,
            "local_commit": selected_observation["head"]["commit"],
            "local_tree": selected_observation["head"]["tree"],
            "repository_observation": selected_observation,
        }
        expected_request["request_sha256"] = _digest(expected_request)
        self._publication_require(pr["head_ref"] == target_ref[len("refs/heads/"):] and request == expected_request, "stale", "source-publication-request-stale", "Selected publication request differs from the exact issue branch and repository observation")
        self._publication_require(request_projection["decision"]["type"] == "source-publication-request" and result_projection["decision"]["type"] == "source-publication-result" and result_projection["subject"] == request_binding, "stale", "source-publication-evidence-unselected", "Source publication evidence is not bound to its selected request")
        unsigned = dict(result); result_sha256 = unsigned.pop("result_sha256", None)
        self._publication_require(set(result) == {"format", "request_sha256", "repository", "target_ref", "source_commit", "source_tree", "outcome", "code", "mutation", "idempotent", "process_result", "remote_head", "result_sha256"} and result.get("format") == runtime.SOURCE_PUBLICATION_RESULT_FORMAT and result_sha256 == _digest(unsigned), "corrupt", "source-publication-result-invalid", "Source publication result is malformed")
        remote = result["remote_head"]
        self._publication_require(result["request_sha256"] == request["request_sha256"] and result["repository"] == request["repository"] and result["target_ref"] == request["target_ref"] and result["source_commit"] == request["local_commit"] and result["source_tree"] == request["local_tree"] and result["outcome"] == "confirmed" and result["code"] in {"published", "already-published"} and result["idempotent"] is (result["code"] == "already-published") and isinstance(remote, dict), "stale", "source-publication-unconfirmed", "Source publication did not confirm the selected repository, ref, and commit")
        remote_unsigned = dict(remote); remote_sha256 = remote_unsigned.pop("observation_sha256", None)
        self._publication_require(set(remote) == {"format", "repository", "ref", "sha", "repository_observation_sha256", "observed_at", "observation_sha256"} and remote.get("format") == runtime.REMOTE_HEAD_OBSERVATION_FORMAT and remote_sha256 == _digest(remote_unsigned) and remote["repository"] == request["repository"] and remote["ref"] == request["target_ref"] and remote["sha"] == request["local_commit"] and remote["repository_observation_sha256"] == selected_observation["observation_sha256"], "stale", "source-publication-remote-mismatch", "Publication evidence does not bind the exact validated remote branch")
        history = self._history(state, inspection["authority"])
        claims = [(index, item) for index, (_binding, item) in enumerate(history) if item["pending"] is not None and item["pending"]["kind"] == "source-publication" and item["pending"]["request_binding"] == request_binding]
        self._publication_require(len(claims) == 1 and claims[0][0] + 1 < len(history), "stale", "source-publication-claim-unselected", "Publication request was not selected before execution")
        finalized = history[claims[0][0] + 1][1]
        self._publication_require(finalized["transition"] == {"type": "source-publication-finalize", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None} and self._candidate_binding(finalized, "source-publication-result") == result_binding, "stale", "source-publication-finalization-unselected", "Publication result was not selected by immutable finalization")
        return request_binding, result_binding, request, result
    def _validate_source_publication_result(self, request, result):
        self._publication_require(isinstance(result, dict), "corrupt", "source-publication-result-invalid", "Source publication provider returned no result document")
        unsigned = dict(result); digest = unsigned.pop("result_sha256", None)
        self._publication_require(set(result) == {"format", "request_sha256", "repository", "target_ref", "source_commit", "source_tree", "outcome", "code", "mutation", "idempotent", "process_result", "remote_head", "result_sha256"} and result.get("format") == runtime.SOURCE_PUBLICATION_RESULT_FORMAT and digest == _digest(unsigned), "corrupt", "source-publication-result-invalid", "Source publication provider returned a malformed result")
        self._publication_require(result["request_sha256"] == request["request_sha256"] and result["repository"] == request["repository"] and result["target_ref"] == request["target_ref"] and result["source_commit"] == request["local_commit"] and result["source_tree"] == request["local_tree"], "stale", "source-publication-result-stale", "Source publication result differs from its selected request")
        if result["outcome"] != "confirmed":
            status = result["outcome"] if result["outcome"] in OUTCOMES else "corrupt"
            self._publication_fail(status, result["code"] if isinstance(result["code"], str) else "source-publication-failed", "Source publication did not confirm the selected commit")
        remote = result["remote_head"]
        remote_unsigned = dict(remote) if isinstance(remote, dict) else {}
        remote_digest = remote_unsigned.pop("observation_sha256", None)
        expected_remote = {"format": runtime.REMOTE_HEAD_OBSERVATION_FORMAT, "repository": request["repository"], "ref": request["target_ref"], "sha": request["local_commit"], "repository_observation_sha256": request["repository_observation"]["observation_sha256"]}
        self._publication_require(set(remote or {}) == set(expected_remote) | {"observed_at", "observation_sha256"} and all(remote.get(key) == value for key, value in expected_remote.items()) and remote_digest == _digest(remote_unsigned), "stale", "source-publication-remote-mismatch", "Source publication did not reconcile the exact selected remote branch")
        self._publication_require(result["code"] in {"published", "already-published"} and type(result["idempotent"]) is bool and result["idempotent"] == (result["code"] == "already-published") and (result["mutation"] in {"attempted", "attempted-or-uncertain"} if result["code"] == "published" else result["mutation"] == "not-attempted"), "corrupt", "source-publication-result-invalid", "Source publication result has inconsistent mutation semantics")
        return result
    def _run_source_publication(self, inspection, state):
        provider = self._source_publication_provider()
        self._publication_require(provider is not None, "unsupported", "source-publication-provider-unavailable", "No reviewed source publication provider is configured")
        pending = state["pending"]
        self._publication_require(pending is not None and pending["kind"] == "source-publication" and pending["status"] == "requested", "conflict", "source-publication-not-pending", "No selected source publication is pending")
        request = self._read(pending["request_binding"], SOURCE_PUBLICATION_REQUEST_PATH, "source publication request")
        observation = request["repository_observation"]
        self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True)
        self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
        self._unchanged(inspection)
        context = self._runtime_context(state, inspection, observation)
        cancel, stop, worker = self._watch(inspection, pending, {"timeout_ms": 30000, "grace_ms": 1000})
        if self._status()["pointer_sha256"] != inspection["pointer_sha256"]: cancel.set()
        try:
            result = self._publication_translate(lambda: provider(self.root, self.issue, context, copy.deepcopy(request), cancel), "source-publication")
        finally:
            stop.set(); worker.join(2)
            self._publication_require(not worker.is_alive(), "conflict", "cancel-watcher-stuck", "Cancellation watcher did not stop")
        self._unchanged(inspection)
        self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True)
        self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
        result = self._validate_source_publication_result(request, result)
        result_binding = self._publish("source-publication-result", "publication-%s" % result["result_sha256"], pending["request_binding"], [(SOURCE_PUBLICATION_RESULT_PATH, result)], state["generation"] + 1)
        candidates = _put(state["candidates"], "source-publication-result", result_binding)
        successor = self._successor(state, inspection["authority"], pending=None, candidates=candidates, transition={"type": "source-publication-finalize", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor)
        return self._result("source-published", successor, binding, committed)
    def _claim_source_publication(self, inspection, state):
        self._satisfaction_context(state, inspection, "final-gate-satisfaction", "final", reobserve_human=True)
        self._satisfaction_context(state, inspection, "pr-publication-gate-satisfaction", "pr-publication", reobserve_human=True)
        observation_binding = self._candidate_binding(state, "pr-publication-observation")
        observation = self._read(observation_binding, label="publication repository observation")
        adapter = self._runtime_drift(lambda: self._runtime(None, state, inspection, observation), "repository-continuity-stale", "Repository changed after publication approval", {"runtime-worktree-untrusted", "runtime-phase-repository-changed"})
        current = self._publication_translate(lambda: adapter.observe_diff(self.issue, self.family, state["triage_binding"]), "runtime")
        self._publication_require(runtime.same_repository(observation, current) and runtime.clean_repository(current), "stale", "repository-continuity-stale", "Repository changed after publication approval")
        pr, _expectation, _wrapper = self._pr_context(state, current)
        request = self._publication_translate(lambda: adapter.build_source_publication_request(issue=self.issue, family_run_id=self.family, repository_observation=current, repository=current["repository"], local_commit=current["head"]["commit"], local_tree=current["head"]["tree"], target_ref="refs/heads/%s" % pr["head_ref"]), "runtime")
        request_binding = self._publish("source-publication-request", "publication-%s" % request["request_sha256"], inspection["authority"], [(SOURCE_PUBLICATION_REQUEST_PATH, request)], state["generation"] + 1)
        pending = {"attempt_id": request["request_sha256"], "kind": "source-publication", "request_binding": request_binding, "status": "requested"}
        successor = self._successor(state, inspection["authority"], pending=pending, candidates=_put(state["candidates"], "source-publication-request", request_binding), transition={"type": "source-publication-claim", "request_binding": request_binding, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor)
        self._publication_require(committed["outcome"]["code"] == "committed", "busy", "attempt-in-flight", "Another caller already owns this publication attempt")
        selected = {"authority": binding, "pointer_sha256": committed["pointer_sha256"]}
        self._selected, self._selected_inspection = successor, selected
        return self._run_source_publication(selected, successor)
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
        _publication_request, publication_result, _request_document, _result_document = self._publication_context(state, inspection)
        source_binding = publication_challenge["authority_binding"]
        fresh_pr, fresh_local = self._fresh_pr(state, source_binding)
        metadata = self._active(state, "pr-metadata")
        final = self._read(self._active(state, "final-review"), NODE_PATH, "final review node")["subject_binding"]
        rows = [("final-gate-satisfaction", final_satisfaction), ("final-review", final), ("github-pr-observation", fresh_pr), ("pr-publication-gate-satisfaction", publication_satisfaction), ("source-publication-result", publication_result)]
        authorization = final_document["human_authorization_binding"]
        _wrapper, policy_binding = self._bind(state, inspection, "pr-approval", metadata, rows, fresh_local, authorization)
        successor = self._successor(state, inspection["authority"], phase="COMPLETED", policy_state_binding=policy_binding, candidates=_drop(state["candidates"], "final-gate-satisfaction", "pr-publication-gate-satisfaction", "pr-publication-observation", "pr-metadata"), transition={"type": "complete", "request_binding": None, "result_binding": None, "authorization_binding": None, "repository_observation_binding": None})
        binding, committed = self._commit(successor); return self._result("completed", successor, binding, committed)
