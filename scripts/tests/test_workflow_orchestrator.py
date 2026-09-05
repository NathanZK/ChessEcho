import base64
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from scripts import workflow_authority as authority
from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_orchestrator as orchestrator
from scripts import workflow_plan_revision_policy as plan_policy
from scripts import workflow_policy as policy
from scripts import workflow_runtime as runtime


REPOSITORY = pathlib.Path(__file__).parents[2]
FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "workflow-orchestrator"
SLUG, ISSUE = "NathanZK/ChessEcho", 144
TOKEN, ACCOUNT_ID, LOGIN = "orchestrator-e2e-secret-token", 95658930, "NathanZK"


def _canonical(value):
    return inspector.canonical_bytes(value)


class TestSandboxProvider:
    def __init__(self, root, name, source_sha256, version="1"):
        self.root, self.name, self.source_sha256, self.version = root, name, source_sha256, version

    def verify(self, request, process, candidate):
        value = {
            "format": "chess-echo-external-sandbox-result-v1",
            "provider": {"name": self.name, "version": self.version, "source_sha256": self.source_sha256},
            "request_sha256": request["request_sha256"],
            "command_sha256": process.get("command_sha256"),
            "repository_scope": str(self.root),
            "credential_access": "denied",
            "authority_store_access": "denied",
            "containment": "verified",
            "candidate_sha256": inspector.sha256(candidate),
            "candidate_size": len(candidate),
        }
        value["result_sha256"] = inspector.sha256(_canonical(value))
        return value


class OrchestratorFixture:
    """A real Git/CAS/evidence/policy/runtime fixture with bounded local commands."""

    def __init__(self, mode="active"):
        self.temporary = tempfile.TemporaryDirectory(dir=str(REPOSITORY))
        self.workspace = pathlib.Path(self.temporary.name)
        self.root, self.bin = self.workspace / "repo", self.workspace / "toolbin"
        self.root.mkdir()
        self.bin.mkdir()
        self.agent = self._install(FIXTURES / "fake_agent.py", "agent")
        self.gh = self._install(FIXTURES / "fake_gh.py", "gh")
        self.git = shutil.which("git") or self._skip("git")
        self.agent_sha256 = inspector.sha256(self.agent.read_bytes())
        self.config_bytes = self._write_repository(mode)
        self.head = self._git("rev-parse", "HEAD").strip()
        self._git("update-ref", "refs/remotes/origin/main", self.head)
        self.store = inspector.resolve_store(self.root)
        self.store.store_dir.mkdir(parents=True, exist_ok=True)
        self._write_gh()

    def _skip(self, name):
        raise unittest.SkipTest("%s is required" % name)

    def close(self):
        self.temporary.cleanup()

    def _install(self, source, name):
        target = self.bin / name
        target.write_text("#!%s\n%s" % (sys.executable, source.read_text().split("\n", 1)[1]))
        target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        return target

    def _git(self, *arguments):
        return subprocess.run(
            [self.git, *arguments], cwd=self.root, check=True, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={
                "PATH": os.environ.get("PATH", ""), "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@example.test",
                "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@example.test",
                "HOME": str(self.root),
            },
        ).stdout

    def _paths(self):
        paths = [str(self.bin)]
        for candidate in (pathlib.Path(sys.executable).parent, pathlib.Path("/usr/bin"),
                          pathlib.Path("/bin"), pathlib.Path("/usr/local/bin")):
            if candidate.is_dir() and str(candidate) not in paths:
                paths.append(str(candidate))
        return paths

    def _config(self, mode):
        config = json.loads((REPOSITORY / ".github" / "agent-workflow.json").read_text())
        config["target_base"] = "main"
        config["validation_profiles"]["workflow-tooling"] = {
            "test_paths": ["scripts/tests/**/*"],
            "checks": [
                {"name": "fixture-check-a", "command": ["make", "fixture-check-a"]},
                {"name": "fixture-check-b", "command": ["make", "fixture-check-b"]},
            ],
        }
        config["orchestrator"] = {
            "format": "chess-echo-orchestrator-config-v1", "mode": mode, "frozen_issues": [115],
            "agent_roles": [
                {
                    "role": role, "command_prefix": ["agent", role], "cwd": ".",
                    "timeout_ms": 5000, "grace_ms": 100, "output_limit_bytes": 4096,
                    "containment": "external-sandbox-v1", "provider_name": "orchestrator-e2e-sandbox",
                    "provider_source_sha256": self.agent_sha256,
                }
                for role in ("implementer", "planner", "reviewer")
            ],
            "git": {"command": ["git"], "timeout_ms": 30000, "grace_ms": 1000, "output_limit_bytes": 8388608},
            "github": {"command": ["gh"], "timeout_ms": 30000, "grace_ms": 1000, "output_limit_bytes": 524288},
            "validation_path": self._paths(),
            "human_approval": {
                "allowed_accounts": [{"account_id": ACCOUNT_ID, "login": LOGIN}],
                "allowed_associations": ["COLLABORATOR", "MEMBER", "OWNER"],
            },
        }
        return (json.dumps(config, ensure_ascii=True, indent=2) + "\n").encode()

    def _write_repository(self, mode):
        self._git("init", "-q")
        config = self._config(mode)
        (self.root / ".github").mkdir()
        (self.root / ".github" / "agent-workflow.json").write_bytes(config)
        (self.root / "scripts").mkdir()
        (self.root / "scripts" / "keep.txt").write_text("seed\n")
        (self.root / "Makefile").write_text(
            "fixture-check-a:\n\t@if test -f ../toolbin/validation-drift; then echo dirty > scripts/validation-drift.py; fi\n"
            "\t@test ! -f ../toolbin/validation-fail\n"
            "fixture-check-b:\n\t@test ! -f ../toolbin/validation-fail\n"
        )
        self._git("add", "-A")
        self._git("commit", "-qm", "seed")
        return config

    def _write_gh(self, extra=None):
        data = {
            "api": {
                "repos/%s" % SLUG: {"default_branch": "main"},
                "repos/%s/commits/main" % SLUG: {"sha": self.head},
                "repos/%s/issues/%d" % (SLUG, ISSUE): {
                    "number": ISSUE, "title": "Add a workflow feature",
                    "html_url": "https://github.com/%s/issues/%d" % (SLUG, ISSUE),
                    "body": "Implement the feature.", "labels": [{"name": "enhancement"}],
                    "updated_at": "2026-09-05T00:00:00Z",
                },
            },
            "graphql": {},
        }
        data.update(extra or {})
        (self.bin / "gh-responses.json").write_text(json.dumps(data, sort_keys=True))

    def bootstrap(self):
        return runtime.bootstrap(self.root, SLUG, str(self.git), str(self.gh), TOKEN)

    def install_provider(self, test):
        adapter = self.bootstrap()
        old_runtime, old_sandbox = orchestrator.RUNTIME_PROVIDER, orchestrator.SANDBOX_PROVIDER
        test.addCleanup(setattr, orchestrator, "RUNTIME_PROVIDER", old_runtime)
        test.addCleanup(setattr, orchestrator, "SANDBOX_PROVIDER", old_sandbox)
        orchestrator.RUNTIME_PROVIDER = lambda root, issue, request: adapter
        orchestrator.SANDBOX_PROVIDER = lambda root, issue, role: TestSandboxProvider(
            self.root, "orchestrator-e2e-sandbox", self.agent_sha256)
        return adapter

    def seed_response_source(self):
        raw = json.dumps(
            json.loads((self.bin / "gh-responses.json").read_text())["api"][
                "repos/%s/issues/%d" % (SLUG, ISSUE)
            ],
            separators=(",", ":"),
        ).encode()
        reference = {"kind": "issue-snapshot", "sha256": inspector.sha256(raw), "size": len(raw)}
        from scripts import workflow_cas
        workflow_cas.publish_immutable(
            inspector.object_path(self.store, reference["sha256"]), raw,
            lambda _status, code, _message: (_ for _ in ()).throw(AssertionError(code)),
            temporary_label="e2e",
        )

    def request(self):
        return {
            "repository": SLUG, "git_executable": str(self.git), "gh_executable": str(self.gh),
            "classification": {
                "work_type": "implementation", "basis": "The issue needs code.",
                "deliverable": {"storage": "git", "kind": "implementation-change", "locations": ["scripts"]},
                "executable_change_expected": True, "expected_scope": [{"kind": "subtree", "path": "scripts"}],
                "validation_profile": "workflow-tooling", "unresolved_ambiguities": [],
            },
        }

    def read(self, binding, path=None):
        projection = evidence.project(self.root, binding)
        entry = next(item for item in projection["entries"] if path is None or item["path"] == path)
        reader = inspector.AuthorityReader(self.store, ISSUE)
        return json.loads(reader.read_bytes(entry["payload"], "evidence-payload"))

    def state(self):
        return self.read(orchestrator.status(self.root, ISSUE)["authority"])

    def tip(self):
        return orchestrator.status(self.root, ISSUE)["pointer_sha256"]

    def step(self, request=None, tip=None):
        return orchestrator.step(self.root, ISSUE, expected_tip=tip or self.tip(), request=request)

    def mode(self, value):
        (self.bin / "agent-mode").write_text(value)

    def validation_fails(self):
        (self.bin / "validation-fail").write_text("yes")

    def validation_drifts(self):
        (self.bin / "validation-drift").write_text("yes")

    def set_uncertain_create(self):
        data = json.loads((self.bin / "gh-responses.json").read_text())
        data["create_mode"] = "uncertain-after-write"
        (self.bin / "gh-responses.json").write_text(json.dumps(data, sort_keys=True))

    def comment(self, confirmation, number):
        data = json.loads((self.bin / "gh-responses.json").read_text())
        data["api"]["repos/%s/issues/comments/%d" % (SLUG, number)] = {
            "id": number, "node_id": "IC_%d" % number,
            "html_url": "https://github.com/%s/issues/%d#issuecomment-%d" % (SLUG, ISSUE, number),
            "issue_url": "https://api.github.com/repos/%s/issues/%d" % (SLUG, ISSUE),
            "created_at": "2026-09-05T00:00:00Z", "updated_at": "2026-09-05T00:00:00Z",
            "body": confirmation, "user": {"id": ACCOUNT_ID, "login": LOGIN}, "author_association": "OWNER",
        }
        (self.bin / "gh-responses.json").write_text(json.dumps(data, sort_keys=True))

    def challenge(self):
        return self.read(self.state()["pending"]["request_binding"])

    def approve(self, number):
        challenge = self.challenge()
        self.comment(challenge["confirmation"], number)
        return orchestrator.approve(
            self.root, ISSUE, expected_tip=self.tip(), authorization={"kind": "issue-comment", "id": number})

    def calls(self):
        path = self.bin / "gh-calls.jsonl"
        return [] if not path.exists() else [json.loads(line) for line in path.read_text().splitlines()]


class OrchestratorLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = OrchestratorFixture()
        self.addCleanup(self.fixture.close)
        self.adapter = self.fixture.install_provider(self)
        self.fixture.seed_response_source()
        orchestrator.init(self.fixture.root, ISSUE, request=self.fixture.request())

    def _agent_pair(self):
        candidate = self.fixture.step()
        self.assertEqual("execution-candidate", candidate["outcome"]["code"])
        return self.fixture.step(request=candidate["handoff"])

    def _to_plan_gate(self):
        self._agent_pair()
        return self._agent_pair()

    def _to_test_gate(self):
        self._to_plan_gate()
        self.fixture.approve(7001)
        self._agent_pair()
        return self._agent_pair()

    def _to_validation(self):
        self._to_test_gate()
        self.fixture.approve(7002)
        return self._agent_pair()

    def _to_pr_preparation(self):
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        return self.fixture.state()

    def _finish(self):
        self._to_pr_preparation()
        self._agent_pair()
        self._agent_pair()
        return self.fixture.approve(7003)

    def test_real_component_happy_path_binds_every_node_and_completes_draft(self):
        with mock.patch.object(policy, "evaluate", wraps=policy.evaluate) as evaluate, \
             mock.patch.object(plan_policy, "evaluate_baseline", wraps=plan_policy.evaluate_baseline) as baseline:
            result = self._finish()
        self.assertEqual("COMPLETED", result["phase"])
        self.assertEqual({"status": "resolved", "code": "completed"}, result["outcome"])
        self.assertEqual(2, baseline.call_count)
        bound = [call.args[1]["operation"]["node"] for call in evaluate.call_args_list]
        self.assertEqual(
            ["plan-approval", "test-manifest", "test-approval", "implementation-submission",
             "validation", "final-review", "pr-metadata", "pr-approval"],
            bound,
        )
        state = self.fixture.state()
        policy_state = self.fixture.read(state["policy_state_binding"], "workflow-policy/state.json")
        self.assertEqual(list(policy.NODE_ORDER), [item["node"] for item in policy_state["active"]])
        nodes = {item["node"]: item["binding"] for item in policy_state["active"]}
        wrappers = {node: self.fixture.read(binding) for node, binding in nodes.items()}
        for node, wrapper in wrappers.items():
            self.assertEqual(node, wrapper["node"])
            self.assertEqual(list(policy.DEPENDENCIES[node]), [row["node"] for row in wrapper["dependencies"]])
        self.assertEqual({"technical-plan-review"}, {row["role"] for row in wrappers["plan-approval"]["evidence"]})
        self.assertEqual({"test-diff", "test-report"}, {row["role"] for row in wrappers["test-manifest"]["evidence"]})
        self.assertEqual({"implementation-report"}, {row["role"] for row in wrappers["implementation-submission"]["evidence"]})
        self.assertEqual({"comprehensive-validation"}, {row["role"] for row in wrappers["final-review"]["evidence"]})
        self.assertEqual({"github-pr-observation"}, {row["role"] for row in wrappers["pr-metadata"]["evidence"]})
        validation = self.fixture.read(wrappers["validation"]["subject_binding"])
        self.assertEqual(["fixture-check-a", "fixture-check-b"], [row["name"] for row in validation["checks"]])
        self.assertEqual("pass", validation["status"])
        self.assertEqual(
            "test-report",
            evidence.project(self.fixture.root, wrappers["test-manifest"]["subject_binding"])["decision"]["type"],
        )
        self.assertEqual(
            "implementation-report",
            evidence.project(self.fixture.root, wrappers["implementation-submission"]["subject_binding"])["decision"]["type"],
        )
        for node in ("plan-approval", "test-approval", "pr-approval"):
            self.assertIsNotNone(wrappers[node]["authorization_binding"])
        wrapper = wrappers["pr-metadata"]
        observation = self.fixture.read(wrapper["evidence"][0]["binding"])
        self.assertEqual(("OPEN", True), (observation["state"], observation["draft"]))
        remote_calls = [call for call in self.fixture.calls() if "/git/matching-refs/heads/issue-144" in " ".join(call)]
        self.assertEqual(2, len(remote_calls))
        self.assertFalse(any("merge" in " ".join(call) for call in self.fixture.calls()))

    def test_technical_reviews_do_not_cross_exact_human_gates(self):
        plan_gate = self._to_plan_gate()
        self.assertEqual("WAITING_FOR_PLAN_APPROVAL", plan_gate["phase"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("human-gate-requires-approval", raised.exception.code)
        self.fixture.comment("approve plan wrong", 7101)
        with self.assertRaises(orchestrator.OrchestratorFailure):
            orchestrator.approve(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                                 authorization={"kind": "issue-comment", "id": 7101})
        self.fixture.approve(7102)
        self._agent_pair()
        test_gate = self._agent_pair()
        self.assertEqual("WAITING_FOR_TEST_APPROVAL", test_gate["phase"])
        self.fixture.approve(7103)
        self._agent_pair()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self._agent_pair()
        final_gate = self._agent_pair()
        self.assertEqual("WAITING_FOR_FINAL_APPROVAL", final_gate["phase"])
        self.assertEqual("final", self.fixture.challenge()["gate"])

    def test_plan_revision_is_policy_checked_once_before_approval(self):
        self.fixture.mode("revision")
        self._agent_pair()
        reviewed = self._agent_pair()
        self.assertEqual("PLANNING", reviewed["phase"])
        with mock.patch.object(plan_policy, "evaluate_revision", wraps=plan_policy.evaluate_revision) as revised:
            self._agent_pair()
            gate = self._agent_pair()
        self.assertTrue(revised.called)
        self.assertEqual("WAITING_FOR_PLAN_APPROVAL", gate["phase"])
        self.assertIn("plan-revision", [row["slot"] for row in self.fixture.state()["candidates"]])

    def test_planner_handoff_cannot_cross_repository_drift(self):
        candidate = self.fixture.step()
        (self.fixture.root / "scripts" / "after-plan.py").write_text("DRIFT = True\n")
        self.fixture._git("add", "scripts/after-plan.py")
        self.fixture._git("commit", "-m", "drift after planner")
        finalized = self.fixture.step(request=candidate["handoff"])
        self.assertEqual("PLAN_REVIEW", finalized["phase"])
        tip = self.fixture.tip()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("repository-continuity-stale", raised.exception.code)
        self.assertEqual(tip, self.fixture.tip())

    def test_postapproval_plan_revision_state_pauses_without_reusing_approval(self):
        self._to_plan_gate()
        self.fixture.approve(7301)
        inspection = orchestrator.status(self.fixture.root, ISSUE)
        runner = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        runner.family = self.fixture.state()["family_run_id"]
        historic = next(
            state for _binding, state in reversed(runner._history(self.fixture.state(), inspection["authority"]))
            if {"plan-snapshot", "plan-review"} <= {row["slot"] for row in state["candidates"]}
        )
        successor = runner._successor(
            self.fixture.state(), inspection["authority"], phase="PLANNING",
            candidates=historic["candidates"],
            transition={
                "type": "plan-reopen", "request_binding": None, "result_binding": None,
                "authorization_binding": None, "repository_observation_binding": None,
            },
        )
        runner._commit(successor)
        paused = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(paused["outcome"].values()))
        self.assertEqual("PAUSED", paused["phase"])

    def test_validation_runs_each_check_in_a_separate_step_and_failure_pauses(self):
        self._to_validation()
        calls = []
        original = runtime.Runtime.execute

        def counted(adapter, *args, **kwargs):
            calls.append(args[0]["operation"]["kind"])
            return original(adapter, *args, **kwargs)

        with mock.patch.object(runtime.Runtime, "execute", new=counted):
            self._agent_pair()
            self.fixture.validation_fails()
            failed = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(failed["outcome"].values()))
        self.assertEqual("PAUSED", self.fixture.state()["phase"])
        self.assertEqual(["validation", "validation"], calls)

    def test_validation_cannot_certify_a_dirty_repository(self):
        self._to_validation()
        self.fixture.validation_drifts()
        paused = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(paused["outcome"].values()))
        self.assertEqual("PAUSED", self.fixture.state()["phase"])

    def test_implementation_preserves_approved_test_content(self):
        self._to_test_gate()
        self.fixture.approve(7400)
        self.fixture.mode("rewrite-tests")
        paused = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(paused["outcome"].values()))
        self.assertEqual("PAUSED", self.fixture.state()["phase"])

    def test_rejected_test_review_commits_a_recoverable_pause(self):
        self._to_plan_gate()
        self.fixture.approve(7399)
        self._agent_pair()
        self.fixture.mode("reject-review")
        paused = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(paused["outcome"].values()))
        self.assertEqual("PAUSED", self.fixture.state()["phase"])
        self.assertEqual("recover", orchestrator.plan_next(self.fixture.root, ISSUE)["next_action"]["command"])

    def test_implementation_cannot_start_from_postapproval_repository_drift(self):
        self._to_test_gate()
        self.fixture.approve(7403)
        (self.fixture.root / "scripts" / "preexisting.py").write_text("UNAPPROVED = True\n")
        self.fixture._git("add", "scripts/preexisting.py")
        self.fixture._git("commit", "--amend", "--no-edit")
        tip = self.fixture.tip()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("repository-continuity-stale", raised.exception.code)
        self.assertEqual(tip, self.fixture.tip())
        self.assertEqual("IMPLEMENTATION", self.fixture.state()["phase"])

    def test_validation_refuses_a_new_head_after_implementation_submission(self):
        self._to_validation()
        (self.fixture.root / "scripts" / "late.py").write_text("LATE = True\n")
        self.fixture._git("add", "scripts/late.py")
        self.fixture._git("commit", "--amend", "--no-edit")
        tip = self.fixture.tip()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual(("stale", "repository-continuity-stale"), (raised.exception.status, raised.exception.code))
        self.assertEqual(tip, self.fixture.tip())
        self.assertEqual("VALIDATION", self.fixture.state()["phase"])

    def test_read_only_agents_and_test_author_fail_closed_on_repository_drift(self):
        self.fixture.mode("read-drift")
        paused = self._agent_pair()
        self.assertEqual(("paused", "read-only-agent-repository-drift"), tuple(paused["outcome"].values()))

        other = OrchestratorFixture()
        self.addCleanup(other.close)
        other.install_provider(self)
        other.seed_response_source()
        orchestrator.init(other.root, ISSUE, request=other.request())
        self.fixture = other
        self._to_plan_gate()
        self.fixture.approve(7401)
        self.fixture.mode("test-drift")
        paused = self._agent_pair()
        self.assertEqual(("paused", "test-scope-drift"), tuple(paused["outcome"].values()))

    def test_uncertain_pr_write_is_reconciled_without_a_second_create(self):
        self.fixture.set_uncertain_create()
        self._to_pr_preparation()
        self._agent_pair()
        write_result = self.fixture.read(
            next(row["binding"] for row in self.fixture.state()["candidates"]
                 if row["slot"] == "execution-result")
        )
        self.assertEqual("nonzero-exit", write_result["process_result"]["outcome"])
        self.assertEqual("confirmed", write_result["reconciliation"]["status"])
        creates = [call for call in self.fixture.calls() if call[:2] == ["pr", "create"]]
        self.assertEqual(1, len(creates))
        self._agent_pair()
        self.assertEqual("WAITING_FOR_FINAL_APPROVAL", self.fixture.state()["phase"])

    def test_final_gate_rejects_empty_body_sections_and_local_drift(self):
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self.fixture.mode("empty-pr")
        paused = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(paused["outcome"].values()))

        other = OrchestratorFixture()
        self.addCleanup(other.close)
        other.install_provider(self)
        other.seed_response_source()
        orchestrator.init(other.root, ISSUE, request=other.request())
        self.fixture = other
        self._to_pr_preparation()
        self._agent_pair()
        self._agent_pair()
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7402)
        (self.fixture.root / "scripts" / "late-drift.py").write_text("DIRTY = True\n")
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.approve(
                self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                authorization={"kind": "issue-comment", "id": 7402})
        self.assertEqual("final-repository-mismatch", raised.exception.code)

    def test_cancel_keeps_cancel_requested_pending_and_recovery_uses_github_gate(self):
        self.fixture.mode("sleep")
        stale_tip = self.fixture.tip()
        outcome = {}

        def cancel():
            for _unused in range(200):
                if (self.fixture.bin / "agent-started").exists():
                    outcome["cancelled"] = orchestrator.cancel(
                        self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                        reason="operator stop")
                    return
                time.sleep(0.05)
            outcome["error"] = "process did not start"

        thread = threading.Thread(target=cancel)
        thread.start()
        with self.assertRaises(orchestrator.OrchestratorFailure) as execution:
            self.fixture.step(tip=stale_tip)
        thread.join(7)
        self.assertFalse(thread.is_alive())
        self.assertNotIn("error", outcome)
        cancelled = outcome["cancelled"]
        self.assertEqual("cancel-requested", cancelled["outcome"]["code"])
        self.assertEqual("cancel-requested", self.fixture.state()["pending"]["status"])
        self.assertIn(execution.exception.status, {"missing", "stale"})
        with self.assertRaises(orchestrator.OrchestratorFailure) as late:
            self.fixture.step(tip=stale_tip)
        self.assertEqual("stale", late.exception.status)
        requested = orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), authorization=None)
        self.assertEqual("recovery-requested", requested["outcome"]["code"])
        self.assertEqual("PAUSED", requested["phase"])
        self.assertEqual(
            {"action": "authorize-recovery", "command": "recover", "gate": "recovery", "pending_kind": "human"},
            orchestrator.plan_next(self.fixture.root, ISSUE)["next_action"],
        )
        with self.assertRaises(orchestrator.OrchestratorFailure):
            orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                                 authorization={"acknowledge": "recover"})
        challenge = self.fixture.challenge()
        self.assertEqual("recovery", challenge["gate"])
        self.fixture.comment(challenge["confirmation"], 7201)
        recovered = orchestrator.recover(
            self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
            authorization={"kind": "issue-comment", "id": 7201})
        self.assertEqual("PLANNING", recovered["phase"])

    def test_cancelled_pr_write_can_only_reconcile_and_never_create_again(self):
        self._to_pr_preparation()
        self.fixture.step()
        orchestrator.cancel(
            self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), reason="operator stop")
        orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip())
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7202)
        orchestrator.recover(
            self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
            authorization={"kind": "issue-comment", "id": 7202})
        claimed = self.fixture.step(request={"pr_number": 1})
        self.assertEqual("github-read", claimed["next_action"]["pending_kind"])
        self.assertEqual(1, sum(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_failed_reconciliation_preserves_historical_write_claim(self):
        self._to_pr_preparation()
        self.fixture.step()
        orchestrator.cancel(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), reason="stop write")
        orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip())
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7210)
        orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), authorization={"kind": "issue-comment", "id": 7210})
        data = json.loads((self.fixture.bin / "gh-responses.json").read_text())
        data["api"].pop("repos/%s/pulls/1" % SLUG, None)
        (self.fixture.bin / "gh-responses.json").write_text(json.dumps(data, sort_keys=True))
        with self.assertRaises(orchestrator.OrchestratorFailure):
            self.fixture.step(request={"pr_number": 1})
        orchestrator.cancel(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), reason="stop failed read")
        orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip())
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7211)
        orchestrator.recover(self.fixture.root, ISSUE, expected_tip=self.fixture.tip(), authorization={"kind": "issue-comment", "id": 7211})
        data = json.loads((self.fixture.bin / "gh-responses.json").read_text())
        data["api"]["repos/%s/pulls/1" % SLUG] = data["created_pr"]
        (self.fixture.bin / "gh-responses.json").write_text(json.dumps(data, sort_keys=True))
        candidate = self.fixture.step(request={"pr_number": 1})
        self.assertEqual("github-read", candidate["next_action"]["pending_kind"])
        self.assertEqual(1, sum(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_cancellation_committed_before_runtime_prevents_process_start(self):
        self.fixture.mode("sleep")
        event = threading.Event()

        def cancel_before_watch(instance, inspection, pending, limits):
            orchestrator.cancel(instance.root, instance.issue, expected_tip=inspection["pointer_sha256"], reason="prelaunch")
            worker = threading.Thread(target=lambda: None)
            worker.start()
            return event, threading.Event(), worker

        with mock.patch.object(orchestrator.Orchestrator, "_watch", new=cancel_before_watch):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
        self.assertEqual("attempt-result-stale", raised.exception.code)
        self.assertFalse((self.fixture.bin / "agent-started").exists())

    def test_stale_concurrent_step_cannot_execute_a_claimed_attempt(self):
        tip = self.fixture.tip()
        self.fixture.step(tip=tip)
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(tip=tip)
        self.assertEqual("busy", raised.exception.status)
        self.assertEqual("attempt-in-flight", raised.exception.code)

    def test_current_tip_without_exact_handoff_cannot_duplicate_execution(self):
        with mock.patch.object(runtime.Runtime, "execute", wraps=self.adapter.execute) as execute:
            candidate = self.fixture.step()
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
            self.assertEqual(("busy", "attempt-in-flight"), (raised.exception.status, raised.exception.code))
            self.assertEqual(1, execute.call_count)
            finalized = self.fixture.step(request=candidate["handoff"])
        self.assertEqual("PLAN_REVIEW", finalized["phase"])

    def test_idempotent_claim_loser_does_not_execute_the_process(self):
        real_commit = authority.commit

        def lose(root, bundle):
            result = real_commit(root, bundle)
            result["outcome"]["code"] = "already-committed"
            return result

        with mock.patch.object(authority, "commit", side_effect=lose), \
             mock.patch.object(runtime.Runtime, "execute", wraps=self.adapter.execute) as execute:
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
        self.assertEqual(("busy", "attempt-in-flight"), (raised.exception.status, raised.exception.code))
        self.assertEqual(0, execute.call_count)
        self.assertEqual("agent", self.fixture.state()["pending"]["kind"])

    def test_restart_and_malformed_agent_output_fail_closed(self):
        self._agent_pair()
        restarted = orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual("PLAN_REVIEW", restarted["phase"])
        self.fixture.mode("malformed")
        malformed = self._agent_pair()
        self.assertEqual("PAUSED", malformed["phase"])
        self.assertEqual("candidate-output-invalid", malformed["outcome"]["code"])

    def test_final_authorization_is_reobserved_after_repository_and_pr_checks(self):
        self._to_pr_preparation()
        self._agent_pair()
        self._agent_pair()
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7500)
        original = orchestrator.Orchestrator._fresh_final

        def mutate(instance, state):
            result = original(instance, state)
            self.fixture.comment("edited after first observation", 7500)
            return result

        with mock.patch.object(orchestrator.Orchestrator, "_fresh_final", new=mutate):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                orchestrator.approve(
                    self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                    authorization={"kind": "issue-comment", "id": 7500})
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)


class OrchestratorGenesisAndCliTest(unittest.TestCase):
    def test_init_status_and_public_dispatch(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        fixture.seed_response_source()
        initialized = orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual(("resolved", "initialized"), tuple(initialized["outcome"].values()))
        self.assertEqual("PLANNING", orchestrator.plan_next(fixture.root, ISSUE)["phase"])
        for command in (
            [sys.executable, str(REPOSITORY / "scripts" / "workflow_orchestrator.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_orchestrator", "--help"],
        ):
            result = subprocess.run(command, cwd=REPOSITORY, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_inactive_and_missing_status_are_typed(self):
        fixture = OrchestratorFixture(mode="inactive")
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        fixture.seed_response_source()
        with self.assertRaises(orchestrator.OrchestratorFailure) as inactive:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("orchestrator-inactive", inactive.exception.code)
        with self.assertRaises(orchestrator.OrchestratorFailure) as missing:
            orchestrator.status(fixture.root, ISSUE)
        self.assertEqual("orchestration-pointer-missing", missing.exception.code)

    def test_frozen_issue_is_denied_before_authority_lookup(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        with mock.patch.object(authority, "status", side_effect=AssertionError("authority lookup")):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                orchestrator.init(fixture.root, 115, request=fixture.request())
        self.assertEqual("issue-frozen", raised.exception.code)
        with mock.patch.object(authority, "status", side_effect=AssertionError("authority lookup")):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                orchestrator.status(fixture.root, 115)
        self.assertEqual("issue-frozen", raised.exception.code)

    def test_legacy_authority_blocks_fresh_initialization_before_runtime(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        with mock.patch.object(inspector, "inspect", return_value={"outcome": {"status": "resolved"}}), \
             mock.patch.object(orchestrator, "RUNTIME_PROVIDER", side_effect=AssertionError("runtime")):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("legacy-authority-owned", raised.exception.code)

    def test_cli_payload_loader_rejects_nonregular_and_oversized_inputs(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        with self.assertRaises(orchestrator.OrchestratorFailure) as nonregular:
            orchestrator._load(fixture.root, "request")
        self.assertEqual("request-not-regular", nonregular.exception.code)
        oversized = fixture.workspace / "oversized.json"
        oversized.write_bytes(b"x" * (orchestrator.LIMIT + 1))
        with self.assertRaises(orchestrator.OrchestratorFailure) as too_large:
            orchestrator._load(oversized, "request")
        self.assertEqual("request-too-large", too_large.exception.code)


if __name__ == "__main__":
    unittest.main()
