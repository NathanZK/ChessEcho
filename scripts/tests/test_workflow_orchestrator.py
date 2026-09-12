import base64
import copy
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
from scripts import workflow_issue_source as issue_source
from scripts import workflow_inspector as inspector
from scripts import workflow_local_provider as local_provider
from scripts import workflow_orchestrator as orchestrator
from scripts import workflow_orchestrator_resume as resume
from scripts import workflow_plan_revision_policy as plan_policy
from scripts import workflow_policy as policy
from scripts import workflow_runtime as runtime
from scripts import workflow_supervision_policy as supervision


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


class TestPendingResultProvider:
    def __init__(self):
        self.candidates = {}

    def prepare(self, query, binding, _data):
        self.candidates[query["query_sha256"]] = {
            "kind": query["result_kind"],
            "binding": copy.deepcopy(binding),
        }

    def __call__(self, _root, _issue, query):
        return {
            "format": "chess-echo-pending-result-candidates-v1",
            "query_sha256": query["query_sha256"],
            "candidates": [],
        }


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
        self.runtime_instances = []
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
            "supervision": {
                "format": "chess-echo-supervision-config-v1",
                "gates": [
                    {"gate": "final", "mode": "supervised"},
                    {"gate": "plan", "mode": "supervised"},
                    {"gate": "pr-publication", "mode": "supervised"},
                    {"gate": "tests", "mode": "supervised"},
                ],
            },
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
                "repos/%s" % SLUG: {"default_branch": "main", "full_name": SLUG},
                "repos/%s/commits/main" % SLUG: {"sha": self.head},
                "repos/%s/issues/%d" % (SLUG, ISSUE): {
                    "number": ISSUE, "title": "Add a workflow feature",
                    "url": "https://api.github.com/repos/%s/issues/%d" % (SLUG, ISSUE),
                    "html_url": "https://github.com/%s/issues/%d" % (SLUG, ISSUE),
                    "body": "Implement the feature.", "labels": [{"name": "enhancement"}],
                    "state": "open",
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
        def provide(_root, _issue, request):
            adapter = (
                runtime.reconstruct(self.root, request, TOKEN)
                if isinstance(request, dict)
                and request.get("format") == runtime.RECONSTRUCTION_REQUEST_FORMAT
                else self.bootstrap()
            )
            self.runtime_instances.append(adapter)
            return adapter

        adapter = provide(self.root, ISSUE, self.request())
        old_runtime, old_sandbox, old_result = (
            orchestrator.RUNTIME_PROVIDER,
            orchestrator.SANDBOX_PROVIDER,
            orchestrator.PENDING_RESULT_PROVIDER,
        )
        test.addCleanup(setattr, orchestrator, "RUNTIME_PROVIDER", old_runtime)
        test.addCleanup(setattr, orchestrator, "SANDBOX_PROVIDER", old_sandbox)
        test.addCleanup(setattr, orchestrator, "PENDING_RESULT_PROVIDER", old_result)
        orchestrator.RUNTIME_PROVIDER = provide
        orchestrator.PENDING_RESULT_PROVIDER = TestPendingResultProvider()
        orchestrator.SANDBOX_PROVIDER = lambda root, issue, role: TestSandboxProvider(
            self.root, "orchestrator-e2e-sandbox", self.agent_sha256)
        return adapter

    def publish_response_source(self):
        self.issue_source = issue_source.publish(
            self.root, SLUG, ISSUE, str(self.git), str(self.gh), TOKEN
        )
        return self.issue_source

    def request(self):
        return {
            "repository": SLUG, "git_executable": str(self.git), "gh_executable": str(self.gh),
            "trusted_issue_source": copy.deepcopy(getattr(self, "issue_source", None)),
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

    def reject(self, number, reason="Tests do not satisfy the approved contract."):
        requested = orchestrator.reject(
            self.root,
            ISSUE,
            expected_tip=self.tip(),
            reason=reason,
        )
        challenge = self.challenge()
        self.comment(challenge["confirmation"], number)
        rejected = orchestrator.reject(
            self.root,
            ISSUE,
            expected_tip=self.tip(),
            authorization={"kind": "issue-comment", "id": number},
        )
        return requested, rejected

    def calls(self):
        path = self.bin / "gh-calls.jsonl"
        return [] if not path.exists() else [json.loads(line) for line in path.read_text().splitlines()]


class CandidateOutputContractTest(unittest.TestCase):
    def _result(self, stdout):
        raw = stdout.encode()
        return {
            "outcome": "succeeded",
            "candidate_output": {
                "sha256": inspector.sha256(raw),
                "size": len(raw),
            },
            "process_result": {
                "stdout": {"base64": base64.b64encode(raw).decode("ascii")}
            },
        }

    def test_exact_reviewer_candidate_with_prose_and_fenced_json_is_rejected(self):
        stdout = (
            "No other production callers depend on exact message wording — confirms plan's "
            "Step 4 claim. The plan is accurate and complete.\n\n"
            "```json\n"
            '{"format":"chess-echo-orchestrator-agent-candidate-v1","kind":"review",'
            '"verdict":"accepted","findings":[],"pr":{}}\n'
            "```"
        )

        decoder = json.loads
        with mock.patch.object(resume.json, "loads", wraps=decoder) as loads:
            with self.assertRaises(resume.ResumeFailure) as raised:
                resume.decode_candidate(self._result(stdout), "review")

        self.assertEqual("candidate-output-invalid", raised.exception.code)
        self.assertEqual(stdout, loads.call_args.args[0])

    def test_exact_clean_reviewer_candidate_json_remains_accepted(self):
        candidate = {
            "format": resume.CANDIDATE_FORMAT,
            "kind": "review",
            "verdict": "accepted",
            "findings": [],
            "pr": {},
        }
        stdout = json.dumps(candidate, separators=(",", ":"))

        self.assertEqual(
            candidate,
            resume.decode_candidate(self._result(stdout), "review"),
        )


class OrchestratorInitializationAcceptanceTest(unittest.TestCase):
    def test_initial_planning_projects_authoritative_issue_snapshot_payload(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        facts = {
            "format": "chess-echo-acceptance-facts-v1",
            "facts": [
                {
                    "id": "planner-projection",
                    "assertion": "equals",
                    "value": "The planner reads this authoritative payload.",
                }
            ],
        }
        body = (
            "<!-- chess-echo-acceptance-facts:begin -->\n"
            + json.dumps(facts, sort_keys=True, separators=(",", ":"))
            + "\n<!-- chess-echo-acceptance-facts:end -->\n\n"
            "Project the trusted issue snapshot into initial planning."
        )
        responses = json.loads((fixture.bin / "gh-responses.json").read_text())
        responses["api"]["repos/%s/issues/%d" % (SLUG, ISSUE)]["body"] = body
        (fixture.bin / "gh-responses.json").write_text(json.dumps(responses, sort_keys=True))
        fixture.publish_response_source()
        orchestrator.init(fixture.root, ISSUE, request=fixture.request())

        state = fixture.state()
        triage = fixture.read(state["triage_binding"])
        issue_binding = triage["issue_snapshot_binding"]
        issue_snapshot = fixture.read(
            issue_binding, "workflow-work-type/issue-snapshot.json"
        )
        claimed = fixture.step()
        self.assertEqual("execution-candidate", claimed["outcome"]["code"])
        request = fixture.read(
            fixture.state()["pending"]["request_binding"],
            "workflow-orchestration/execution-request.json",
        )

        issue_input = next(
            item for item in request["input_bindings"]
            if item["role"] == "issue-snapshot"
        )
        self.assertEqual(issue_binding, issue_input["binding"])
        projected = local_provider._input_projection(fixture.root, ISSUE, request)
        projected_issue = next(
            item for item in projected if item["role"] == "issue-snapshot"
        )
        self.assertEqual(1, len(projected_issue["entries"]))
        entry = projected_issue["entries"][0]
        payload = base64.b64decode(entry["bytes_base64"], validate=True)
        self.assertEqual(
            inspector.canonical_bytes(issue_snapshot),
            payload,
        )
        self.assertEqual(issue_snapshot, json.loads(payload))
        self.assertEqual(body, issue_snapshot["body"])
        self.assertEqual(facts, plan_policy.acceptance_facts(issue_snapshot["body"]))

        coverage = {
            "format": "chess-echo-plan-acceptance-coverage-v1",
            "requirements": [
                {
                    **facts["facts"][0],
                    "unit_ids": ["planner-projection"],
                }
            ],
        }
        candidate = {
            "format": resume.CANDIDATE_FORMAT,
            "kind": "plan",
            "plan": (
                facts["facts"][0]["value"]
                + "\n<!-- chess-echo-plan-acceptance:begin -->\n"
                + json.dumps(coverage, sort_keys=True, separators=(",", ":"))
                + "\n<!-- chess-echo-plan-acceptance:end -->\n"
            ),
            "units": [
                {
                    "id": "planner-projection",
                    "title": "Project the trusted issue snapshot",
                    "start_line": 1,
                    "end_line": 1,
                    "review_class": "acceptance-criteria",
                    "dependencies": [],
                },
                {
                    "id": "acceptance-coverage",
                    "title": "Map the authoritative acceptance fact",
                    "start_line": 2,
                    "end_line": 4,
                    "review_class": "acceptance-criteria",
                    "dependencies": ["planner-projection"],
                },
            ],
            "revision": None,
        }
        raw_candidate = json.dumps(candidate, separators=(",", ":")).encode()
        candidate_result = {
            "outcome": "succeeded",
            "candidate_output": {
                "sha256": inspector.sha256(raw_candidate),
                "size": len(raw_candidate),
            },
            "process_result": {
                "stdout": {
                    "base64": base64.b64encode(raw_candidate).decode("ascii"),
                }
            },
        }
        decode_candidate = resume.decode_candidate
        self.assertEqual(candidate, decode_candidate(candidate_result, "plan"))
        with mock.patch.object(
            resume,
            "decode_candidate",
            side_effect=lambda _result, expected: decode_candidate(
                candidate_result, expected
            ),
        ), mock.patch.object(
            plan_policy,
            "evaluate_baseline",
            wraps=plan_policy.evaluate_baseline,
        ) as evaluate_baseline:
            finalized = fixture.step(request=claimed["handoff"])

        self.assertEqual("PLAN_REVIEW", finalized["phase"])
        self.assertEqual(1, evaluate_baseline.call_count)

    def test_malformed_explicit_acceptance_facts_fail_before_authority(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        responses = json.loads((fixture.bin / "gh-responses.json").read_text())
        responses["api"]["repos/%s/issues/%d" % (SLUG, ISSUE)]["body"] = (
            "<!-- chess-echo-acceptance-facts:begin -->\n"
            "null\n"
            "<!-- chess-echo-acceptance-facts:end -->"
        )
        (fixture.bin / "gh-responses.json").write_text(json.dumps(responses, sort_keys=True))
        fixture.publish_response_source()

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())

        self.assertEqual("invalid-acceptance-facts-schema", raised.exception.code)
        with self.assertRaises(orchestrator.OrchestratorFailure) as missing:
            orchestrator.status(fixture.root, ISSUE)
        self.assertEqual("orchestration-pointer-missing", missing.exception.code)


class OrchestratorLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = OrchestratorFixture()
        self.addCleanup(self.fixture.close)
        self.adapter = self.fixture.install_provider(self)
        self.fixture.publish_response_source()
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
        self.fixture.approve(7003)
        opened = self.fixture.step()
        self.assertEqual("WAITING_FOR_PR_PUBLICATION_APPROVAL", opened["phase"])
        self.fixture.approve(7004)
        return self.fixture.state()

    def _finish(self):
        self._to_pr_preparation()
        self._agent_pair()
        self._agent_pair()
        return self.fixture.step()

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
        self.assertEqual({"gate-satisfaction", "technical-plan-review"}, {row["role"] for row in wrappers["plan-approval"]["evidence"]})
        self.assertEqual({"test-diff", "test-report"}, {row["role"] for row in wrappers["test-manifest"]["evidence"]})
        self.assertEqual({"implementation-report"}, {row["role"] for row in wrappers["implementation-submission"]["evidence"]})
        self.assertEqual({"comprehensive-validation"}, {row["role"] for row in wrappers["final-review"]["evidence"]})
        self.assertEqual({"github-pr-observation"}, {row["role"] for row in wrappers["pr-metadata"]["evidence"]})
        self.assertEqual(
            {"final-gate-satisfaction", "final-review", "github-pr-observation", "pr-publication-gate-satisfaction"},
            {row["role"] for row in wrappers["pr-approval"]["evidence"]},
        )
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
        self.assertEqual(4, len(remote_calls))
        self.assertFalse(any("merge" in " ".join(call) for call in self.fixture.calls()))

    def test_all_automatic_gates_complete_without_human_gate_authorization(self):
        requested = {gate: "automatic" for gate in ("final", "plan", "pr-publication", "tests")}
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7600)

        self._agent_pair()
        self._agent_pair()
        self.assertEqual("TEST_IMPLEMENTATION", self.fixture.step()["phase"])
        self._agent_pair()
        self._agent_pair()
        self.assertEqual("IMPLEMENTATION", self.fixture.step()["phase"])
        self._agent_pair()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self.assertEqual("PR_PREPARATION", self.fixture.step()["phase"])
        self.assertEqual(
            "WAITING_FOR_PR_PUBLICATION_APPROVAL", self.fixture.step()["phase"]
        )
        self.assertEqual("PR_PREPARATION", self.fixture.step()["phase"])
        self._agent_pair()
        self._agent_pair()
        completed = self.fixture.step()
        self.assertEqual("COMPLETED", completed["phase"])

        policy_state = self.fixture.read(
            self.fixture.state()["policy_state_binding"], "workflow-policy/state.json"
        )
        nodes = {row["node"]: self.fixture.read(row["binding"]) for row in policy_state["active"]}
        for node in ("plan-approval", "test-approval", "pr-approval"):
            self.assertIsNone(nodes[node]["authorization_binding"])
        satisfactions = [
            self.fixture.read(row["binding"], supervision.SATISFACTION_PATH)
            for node in ("plan-approval", "test-approval", "pr-approval")
            for row in nodes[node]["evidence"]
            if row["role"].endswith("gate-satisfaction") or row["role"] == "gate-satisfaction"
        ]
        self.assertEqual(4, len(satisfactions))
        self.assertTrue(all(row["mode"] == "automatic" for row in satisfactions))
        self.assertTrue(all(row["human_authorization_binding"] is None for row in satisfactions))

    def test_supervision_change_requires_exact_human_authorization(self):
        original = self.fixture.state()["supervision_policy_binding"]
        requested = {
            "final": "supervised",
            "plan": "automatic",
            "pr-publication": "supervised",
            "tests": "automatic",
        }
        stale_tip = self.fixture.tip()
        opened = orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=stale_tip,
            supervision_map=requested,
        )
        self.assertEqual("supervision-change-requested", opened["outcome"]["code"])
        self.assertEqual(original, self.fixture.state()["supervision_policy_binding"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.set_supervision(
                self.fixture.root,
                ISSUE,
                expected_tip=stale_tip,
                supervision_map=requested,
            )
        self.assertEqual("expected-tip-stale", raised.exception.code)
        self.fixture.comment("approve supervision-policy-change wrong", 7601)
        with self.assertRaises(orchestrator.OrchestratorFailure):
            orchestrator.approve(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                authorization={"kind": "issue-comment", "id": 7601},
            )
        self.fixture.approve(7602)
        state = self.fixture.state()
        revised = self.fixture.read(
            state["supervision_policy_binding"], supervision.POLICY_PATH
        )
        self.assertEqual(1, revised["revision"])
        self.assertEqual(
            requested, {row["gate"]: row["mode"] for row in revised["gates"]}
        )
        self._to_plan_gate()
        self.assertEqual("policy", self.fixture.state()["pending"]["kind"])
        self.assertEqual("automatic", self.fixture.challenge()["mode"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.set_supervision(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                supervision_map=requested,
            )
        self.assertEqual("supervision-change-pending", raised.exception.code)

    def test_selected_supervision_change_revalidates_human_authorization_source(self):
        requested = {gate: "automatic" for gate in supervision.GATES}
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7609)
        self.fixture.comment("edited after policy selection", 7609)

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)

    def test_supervision_cannot_change_after_publication_satisfaction(self):
        self._to_pr_preparation()
        requested = {gate: "automatic" for gate in supervision.GATES}

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.set_supervision(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                supervision_map=requested,
            )
        self.assertEqual("supervision-change-phase", raised.exception.code)

    def test_multi_entry_execution_evidence_stays_readable_across_phases(self):
        payload = b'{"type":"user.message"}\n{"type":"result"}\n'
        attachment = {
            "path": "workflow-orchestration/copilot-transport.jsonl",
            "sha256": inspector.sha256(payload),
            "size": len(payload),
            "bytes": payload,
        }
        original = runtime.Runtime.execute_bundle

        def with_attachment(adapter, *args, **kwargs):
            bundle = original(adapter, *args, **kwargs)
            if bundle.document.get("sandbox") is None:
                return bundle
            return runtime.ExecutionBundle(bundle.document, (dict(attachment),))

        with mock.patch.object(
            runtime.Runtime, "execute_bundle", new=with_attachment
        ):
            self._agent_pair()
            self.assertEqual("PLAN_REVIEW", self.fixture.state()["phase"])
            self._agent_pair()

        binding = next(
            row["binding"]
            for row in self.fixture.state()["candidates"]
            if row["slot"] == "execution-result"
        )
        projection = evidence.project(self.fixture.root, binding)
        self.assertEqual(
            sorted([attachment["path"], orchestrator.RESULT_PATH]),
            sorted(entry["path"] for entry in projection["entries"]),
        )
        self.assertEqual(
            "WAITING_FOR_PLAN_APPROVAL", self.fixture.state()["phase"]
        )

    def _sidecar_publication(self, payload, path="workflow-orchestration/copilot-transport.jsonl"):
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        document = {"format": "chess-echo-sidecar-probe-v1", "issue": ISSUE}
        attachment = {
            "path": path,
            "sha256": inspector.sha256(payload),
            "size": len(payload),
            "bytes": payload,
        }
        binding = instance._publish(
            "execution-result",
            "attempt-%s" % ("a" * 64),
            inspection["authority"],
            [(orchestrator.RESULT_PATH, document)],
            state["generation"] + 1,
            attachments=[attachment],
        )
        return instance, binding, attachment

    def test_execution_evidence_publishes_the_document_and_sidecar_in_one_manifest(self):
        payload = b'{"type":"user.message"}\n{"type":"result"}\n'
        _instance, binding, attachment = self._sidecar_publication(payload)
        projection = evidence.project(self.fixture.root, binding)
        paths = sorted(entry["path"] for entry in projection["entries"])
        reader = inspector.AuthorityReader(self.fixture.store, ISSUE)
        entry = next(
            item
            for item in projection["entries"]
            if item["path"] == attachment["path"]
        )

        self.assertEqual(
            [attachment["path"], orchestrator.RESULT_PATH], paths
        )
        self.assertEqual(payload, reader.read_bytes(entry["payload"], "evidence-payload"))
        self.assertEqual(attachment["sha256"], entry["content_sha256"])
        self.assertEqual(len(payload), entry["size"])
        self.assertEqual(
            {"kind": "evidence-payload", "sha256": attachment["sha256"], "size": len(payload)},
            entry["payload"],
        )
        runtime.verify_result_attachments(
            projection,
            {
                "sandbox": {
                    "transport_reference": {
                        "path": attachment["path"],
                        "sha256": attachment["sha256"],
                        "size": len(payload),
                    }
                }
            },
        )

    def test_transport_reference_must_resolve_within_its_own_manifest(self):
        payload = b'{"type":"result"}\n'
        _instance, binding, attachment = self._sidecar_publication(payload)
        projection = evidence.project(self.fixture.root, binding)
        mutations = {
            "path": {"path": "workflow-orchestration/other.jsonl"},
            "sha256": {"sha256": "0" * 64},
            "size": {"size": len(payload) + 1},
        }
        for name, change in mutations.items():
            with self.subTest(name=name):
                reference = {
                    "path": attachment["path"],
                    "sha256": attachment["sha256"],
                    "size": len(payload),
                }
                reference.update(change)
                with self.assertRaises(runtime.RuntimeFailure) as raised:
                    runtime.verify_result_attachments(
                        projection, {"sandbox": {"transport_reference": reference}}
                    )
                self.assertEqual("transport-evidence-unbound", raised.exception.code)

    def test_publication_rejects_unbound_and_oversized_evidence_attachments(self):
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        payload = b"raw\n"
        cases = {
            "digest": (
                {"path": "workflow-orchestration/t.jsonl", "sha256": "0" * 64, "size": len(payload), "bytes": payload},
                "invalid-evidence-attachment",
            ),
            "size": (
                {"path": "workflow-orchestration/t.jsonl", "sha256": inspector.sha256(payload), "size": len(payload) + 1, "bytes": payload},
                "invalid-evidence-attachment",
            ),
            "duplicate-path": (
                {"path": orchestrator.RESULT_PATH, "sha256": inspector.sha256(payload), "size": len(payload), "bytes": payload},
                "invalid-evidence-attachment",
            ),
            "too-large": (
                {
                    "path": "workflow-orchestration/t.jsonl",
                    "sha256": inspector.sha256(b"x" * (orchestrator.ATTACHMENT_LIMIT + 1)),
                    "size": orchestrator.ATTACHMENT_LIMIT + 1,
                    "bytes": b"x" * (orchestrator.ATTACHMENT_LIMIT + 1),
                },
                "attachment-too-large",
            ),
        }
        for name, (attachment, code) in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                    instance._publish(
                        "execution-result",
                        "attempt-%s" % ("b" * 64),
                        inspection["authority"],
                        [(orchestrator.RESULT_PATH, {"format": "x", "issue": ISSUE})],
                        state["generation"] + 1,
                        attachments=[attachment],
                    )
                self.assertEqual(code, raised.exception.code)

    def test_unwritable_store_publishes_no_sidecar_binding(self):
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        payload = b"raw transport\n"
        attachment = {
            "path": "workflow-orchestration/copilot-transport.jsonl",
            "sha256": inspector.sha256(payload),
            "size": len(payload),
            "bytes": payload,
        }
        pointer = orchestrator.status(self.fixture.root, ISSUE)["pointer_sha256"]
        with mock.patch.object(
            orchestrator.evidence, "publish", side_effect=OSError("store is unwritable")
        ):
            with self.assertRaises(Exception):
                instance._publish(
                    "execution-result",
                    "attempt-%s" % ("c" * 64),
                    inspection["authority"],
                    [(orchestrator.RESULT_PATH, {"format": "x", "issue": ISSUE})],
                    state["generation"] + 1,
                    attachments=[attachment],
                )
        self.assertEqual(
            pointer, orchestrator.status(self.fixture.root, ISSUE)["pointer_sha256"]
        )

    def test_forged_publication_phase_policy_change_cannot_be_consumed_or_replayed(self):
        self._to_pr_preparation()
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        current = instance._supervision(state)
        configuration = {
            "format": supervision.CONFIG_FORMAT,
            "gates": [
                {"gate": gate, "mode": "automatic"} for gate in supervision.GATES
            ],
        }
        challenge = supervision.build_change_challenge(
            state["supervision_policy_binding"],
            current,
            inspection["authority"],
            configuration,
            state["phase"],
        )
        challenge_binding = instance._publish(
            "supervision-policy-change",
            "forged-publication-policy-change",
            inspection["authority"],
            [(supervision.CHANGE_PATH, challenge)],
            state["generation"] + 1,
        )
        pending = {
            "attempt_id": orchestrator._digest(
                {
                    "authority": inspection["authority"],
                    "kind": "human",
                    "challenge": challenge_binding,
                }
            ),
            "kind": "human",
            "request_binding": challenge_binding,
            "status": "requested",
        }
        forged_request = instance._successor(
            state,
            inspection["authority"],
            pending=pending,
            candidates=orchestrator._put(
                state["candidates"], "supervision-policy-change", challenge_binding
            ),
            transition={
                "type": "supervision-change-request",
                "request_binding": challenge_binding,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(forged_request)
        self.fixture.comment(challenge["confirmation"], 7611)
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.approve(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                authorization={"kind": "issue-comment", "id": 7611},
            )
        self.assertEqual("supervision-change-phase", raised.exception.code)

        inspection = instance._status()
        state = instance._selected_state(inspection)
        authorization = instance._authorization(
            state, challenge, {"kind": "issue-comment", "id": 7611}
        )
        authorization_document = instance._read(
            authorization, "workflow-orchestration/human-authorization.json"
        )
        revised = supervision.revise(
            state["supervision_policy_binding"],
            current,
            challenge_binding,
            challenge,
            authorization,
            authorization_document,
            state["previous_authority"],
            state["phase"],
        )
        revised_binding = instance._publish_supervision(
            revised,
            state["supervision_policy_binding"],
            state["supervision_policy_binding"],
            state["generation"] + 1,
        )
        forged_selection = instance._successor(
            state,
            inspection["authority"],
            supervision_policy_binding=revised_binding,
            candidates=orchestrator._drop(
                state["candidates"], "supervision-policy-change"
            ),
            transition={
                "type": "supervision-change",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(forged_selection)
        with self.assertRaises(orchestrator.OrchestratorFailure) as replayed:
            orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual("supervision-policy-unselected", replayed.exception.code)

    def test_publication_gate_blocks_and_revalidates_before_github_write(self):
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self.fixture.approve(7603)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))
        opened = self.fixture.step()
        self.assertEqual("WAITING_FOR_PR_PUBLICATION_APPROVAL", opened["phase"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("human-gate-requires-approval", raised.exception.code)
        self.fixture.approve(7604)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))
        self.fixture.comment("edited after publication approval", 7604)
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_orphaned_publication_satisfaction_is_not_authoritative(self):
        requested = {
            "final": "supervised",
            "plan": "supervised",
            "pr-publication": "automatic",
            "tests": "supervised",
        }
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7606)
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self.fixture.approve(7607)

        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._state(inspection)
        instance.family = state["family_run_id"]
        final_binding = instance._candidate_binding(state, "final-gate-satisfaction")
        final_satisfaction = instance._read(
            final_binding, supervision.SATISFACTION_PATH
        )
        transient = copy.deepcopy(state)
        transient["candidates"] = orchestrator._put(
            transient["candidates"],
            "pr-publication-observation",
            final_satisfaction["repository_observation_binding"],
        )
        subjects, repository = instance._gate_inputs(transient, "pr-publication")
        policy_document = instance._supervision(state)
        challenge = supervision.build_gate_challenge(
            state["supervision_policy_binding"],
            policy_document,
            inspection["authority"],
            "pr-publication",
            [{"slot": slot, "binding": binding} for slot, binding in subjects],
            repository,
        )
        challenge_binding = instance._publish(
            "gate-challenge",
            "orphan-challenge",
            inspection["authority"],
            [(supervision.CHALLENGE_PATH, challenge)],
            state["generation"] + 1,
        )
        decision = supervision.automatic_decision(
            state["supervision_policy_binding"],
            policy_document,
            challenge_binding,
            challenge,
            inspection["authority"],
        )
        decision_binding = instance._publish(
            "automatic-gate-decision",
            "orphan-decision",
            challenge_binding,
            [(supervision.AUTOMATIC_DECISION_PATH, decision)],
            state["generation"] + 1,
        )
        satisfaction = supervision.gate_satisfaction(
            state["supervision_policy_binding"],
            policy_document,
            inspection["authority"],
            challenge_binding,
            challenge,
            decision_binding,
            decision,
            repository,
        )
        satisfaction_binding = instance._publish(
            "gate-satisfaction",
            "orphan-satisfaction",
            challenge_binding,
            [(supervision.SATISFACTION_PATH, satisfaction)],
            state["generation"] + 1,
        )
        forged = copy.deepcopy(state)
        forged["candidates"] = orchestrator._put(
            forged["candidates"],
            "pr-publication-gate-satisfaction",
            satisfaction_binding,
        )
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            instance._satisfaction_context(
                forged,
                inspection,
                "pr-publication-gate-satisfaction",
                "pr-publication",
            )
        self.assertEqual("gate-satisfaction-unselected", raised.exception.code)

    def test_unselected_supervision_policy_cannot_replace_configured_policy(self):
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        _triage, _baseline, baseline_binding, _config = instance._facts(state)
        forged = supervision.initialize(
            ISSUE,
            state["family_run_id"],
            baseline_binding,
            {
                "format": supervision.CONFIG_FORMAT,
                "gates": [
                    {"gate": gate, "mode": "automatic"}
                    for gate in supervision.GATES
                ],
            },
        )
        forged_binding = instance._publish_supervision(
            forged, baseline_binding, None, state["generation"] + 1
        )
        successor = instance._successor(
            state,
            inspection["authority"],
            supervision_policy_binding=forged_binding,
            transition={
                "type": "supervision-change",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual("supervision-policy-unselected", raised.exception.code)

    def test_selected_satisfaction_cannot_replace_challenged_repository(self):
        requested = {
            "final": "automatic",
            "plan": "supervised",
            "pr-publication": "supervised",
            "tests": "supervised",
        }
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7608)
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()

        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        challenge_binding = state["pending"]["request_binding"]
        challenge = instance._read(challenge_binding, supervision.CHALLENGE_PATH)
        policy_document = instance._supervision(state)
        decision = supervision.automatic_decision(
            state["supervision_policy_binding"],
            policy_document,
            challenge_binding,
            challenge,
            state["previous_authority"],
        )
        decision_binding = instance._publish(
            "automatic-gate-decision",
            "substituted-repository-decision",
            challenge_binding,
            [(supervision.AUTOMATIC_DECISION_PATH, decision)],
            state["generation"] + 1,
        )
        changed = copy.deepcopy(
            instance._read(
                challenge["repository_observation_binding"],
                label="challenged repository",
            )
        )
        changed["head"]["commit"] = "f" * 40
        changed["observation_sha256"] = inspector.sha256(
            inspector.canonical_bytes(
                {key: value for key, value in changed.items() if key != "observation_sha256"}
            )
        )
        changed_binding = instance._publish(
            "work-type-diff-observation",
            "substituted-repository",
            challenge_binding,
            [("workflow-work-type/diff-observation.json", changed)],
            state["generation"] + 1,
        )
        satisfaction = supervision.gate_satisfaction(
            state["supervision_policy_binding"],
            policy_document,
            state["previous_authority"],
            challenge_binding,
            challenge,
            decision_binding,
            decision,
            changed_binding,
        )
        satisfaction_binding = instance._publish(
            "gate-satisfaction",
            "substituted-repository-satisfaction",
            challenge_binding,
            [(supervision.SATISFACTION_PATH, satisfaction)],
            state["generation"] + 1,
        )
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="PR_PREPARATION",
            candidates=orchestrator._put(
                state["candidates"], "final-gate-satisfaction", satisfaction_binding
            ),
            transition={
                "type": "final-approve",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("gate-satisfaction-repository-stale", raised.exception.code)

    def test_publication_authorization_is_rechecked_after_remote_head_preflight(self):
        self._to_pr_preparation()
        original = runtime.Runtime.observe_remote_head

        def revoke(adapter, *args, **kwargs):
            result = original(adapter, *args, **kwargs)
            self.fixture.comment("edited during remote preflight", 7004)
            return result

        with mock.patch.object(runtime.Runtime, "observe_remote_head", new=revoke):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_publication_revalidates_final_authorization_before_github_write(self):
        self._to_pr_preparation()
        self.fixture.comment("edited after publication approval", 7003)

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_publication_rejects_substituted_current_gate_subjects(self):
        self._to_pr_preparation()
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        original_node = instance._read(
            instance._active(state, "final-review"), orchestrator.NODE_PATH
        )
        candidate = copy.deepcopy(
            instance._read(
                original_node["subject_binding"],
                "workflow-orchestration/final-review.json",
            )
        )
        candidate["pr"]["title"] = "Substituted after publication approval"
        generation = state["generation"] + 1
        forged_candidate = instance._publish(
            "final-review-candidate",
            "substituted-final-review",
            original_node["subject_binding"],
            [("workflow-orchestration/final-review.json", candidate)],
            generation,
        )
        forged_node, _document = instance._node(
            "final-review",
            forged_candidate,
            [(row["role"], row["binding"]) for row in original_node["evidence"]],
            original_node["repository_observation_binding"],
            original_node["authorization_binding"],
            original_node["dependencies"],
            generation,
        )
        policy_state = copy.deepcopy(
            instance._read(state["policy_state_binding"], orchestrator.POLICY_PATH)
        )
        next(
            row for row in policy_state["active"] if row["node"] == "final-review"
        )["binding"] = forged_node
        policy_state["state_sha256"] = policy._state_digest(policy_state)
        forged_policy = instance._publish_policy(
            policy_state,
            state["policy_state_binding"],
            state["policy_state_binding"],
            generation,
        )
        successor = instance._successor(
            state,
            inspection["authority"],
            policy_state_binding=forged_policy,
            transition={
                "type": "pr-prepare",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual(
            "gate-satisfaction-current-context-stale", raised.exception.code
        )
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_publication_rejects_repository_divergence_from_final_approval(self):
        requested = {gate: "supervised" for gate in supervision.GATES}
        requested["pr-publication"] = "automatic"
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7610)
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self.fixture.approve(7003)
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)

        (self.fixture.root / "scripts" / "post-final-change.py").write_text(
            "CHANGED = True\n"
        )
        self.fixture._git("add", "scripts/post-final-change.py")
        self.fixture._git("commit", "-m", "change after final approval")
        observation = self.adapter.observe_diff(
            ISSUE, state["family_run_id"], state["triage_binding"]
        )
        observed = instance._publish(
            "work-type-diff-observation",
            "divergent-publication-observation",
            state["triage_binding"],
            [("workflow-work-type/diff-observation.json", observation)],
            state["generation"] + 1,
        )
        candidates = orchestrator._put(
            state["candidates"], "pr-publication-observation", observed
        )
        transient = copy.deepcopy(state)
        transient["candidates"] = candidates
        subjects, repository = instance._gate_inputs(transient, "pr-publication")
        _challenge, pending = instance._challenge(
            transient,
            inspection["authority"],
            "pr-publication",
            subjects,
            repository,
            state["generation"] + 1,
        )
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="WAITING_FOR_PR_PUBLICATION_APPROVAL",
            candidates=candidates,
            pending=pending,
            transition={
                "type": "publication-request",
                "request_binding": pending["request_binding"],
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)
        self.assertEqual("PR_PREPARATION", self.fixture.step()["phase"])

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("gate-repository-chain-stale", raised.exception.code)
        self.assertFalse(any(call[:2] == ["pr", "create"] for call in self.fixture.calls()))

    def test_post_write_completion_detects_local_drift_during_pr_observation(self):
        self._to_pr_preparation()
        self._agent_pair()
        self._agent_pair()
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        publication = self.fixture.read(
            next(
                row["binding"]
                for row in state["candidates"]
                if row["slot"] == "pr-publication-gate-satisfaction"
            ),
            supervision.SATISFACTION_PATH,
        )
        challenge = self.fixture.read(
            publication["challenge_binding"], supervision.CHALLENGE_PATH
        )
        original = runtime.Runtime.observe_pull_request

        def drift(adapter, *args, **kwargs):
            result = original(adapter, *args, **kwargs)
            (self.fixture.root / "scripts" / "post-write-drift.py").write_text(
                "DIRTY = True\n"
            )
            return result

        with mock.patch.object(runtime.Runtime, "observe_pull_request", new=drift):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                instance._fresh_pr(state, challenge["authority_binding"])
        self.assertEqual("final-repository-mismatch", raised.exception.code)

    def test_pr_read_handoff_cannot_cross_repository_drift(self):
        self._to_pr_preparation()
        self._agent_pair()
        candidate = self.fixture.step()
        (self.fixture.root / "scripts" / "pr-read-drift.py").write_text("DIRTY = True\n")
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(request=candidate["handoff"])
        self.assertEqual("runtime-worktree-untrusted", raised.exception.code)
        self.assertEqual(
            candidate["pointer_sha256"],
            authority.status(self.fixture.root, ISSUE)["pointer_sha256"],
        )

    def test_automatic_configuration_does_not_bypass_recovery(self):
        requested = {gate: "automatic" for gate in ("final", "plan", "pr-publication", "tests")}
        orchestrator.set_supervision(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            supervision_map=requested,
        )
        self.fixture.approve(7605)
        self.fixture.mode("malformed")
        paused = self._agent_pair()
        self.assertEqual("PAUSED", paused["phase"])
        requested_recovery = orchestrator.recover(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            authorization=None,
        )
        self.assertEqual("recovery-requested", requested_recovery["outcome"]["code"])
        self.assertEqual("human", self.fixture.state()["pending"]["kind"])
        self.assertEqual("recovery", self.fixture.challenge()["gate"])

    def test_structural_successor_cannot_bypass_supervised_plan_gate(self):
        self._to_plan_gate()
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        wrapper, policy_binding = instance._bind(
            state,
            inspection,
            "plan-approval",
            instance._candidate_binding(state, "plan-snapshot"),
            [("technical-plan-review", instance._candidate_binding(state, "plan-review"))],
            instance._candidate_binding(state, "plan-observation"),
        )
        self.assertIsNotNone(wrapper)
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="TEST_IMPLEMENTATION",
            policy_state_binding=policy_binding,
            pending=None,
            transition={
                "type": "plan-approve",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)

        with mock.patch.object(runtime.Runtime, "execute_bundle") as execute:
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
        self.assertEqual("gate-transition-unselected", raised.exception.code)
        execute.assert_not_called()

    def test_plan_approval_wrapper_cannot_substitute_approved_subject(self):
        self._to_plan_gate()
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        challenge = instance._read(
            state["pending"]["request_binding"], supervision.CHALLENGE_PATH
        )
        self.fixture.comment(challenge["confirmation"], 7612)
        authorization = instance._authorization(
            state, challenge, {"kind": "issue-comment", "id": 7612}
        )
        satisfaction = instance._publish_satisfaction(
            state,
            challenge,
            authorization,
            challenge["repository_observation_binding"],
        )
        _wrapper, policy_binding = instance._bind(
            state,
            inspection,
            "plan-approval",
            instance._candidate_binding(state, "plan-review"),
            [
                ("technical-plan-review", instance._candidate_binding(state, "plan-review")),
                ("gate-satisfaction", satisfaction),
            ],
            challenge["repository_observation_binding"],
            authorization,
        )
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="TEST_IMPLEMENTATION",
            policy_state_binding=policy_binding,
            pending=None,
            transition={
                "type": "plan-approve",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual("gate-approval-wrapper-stale", raised.exception.code)

    def test_structural_successor_cannot_bypass_mandatory_recovery(self):
        self.fixture.mode("malformed")
        self.assertEqual("PAUSED", self._agent_pair()["phase"])
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="TEST_IMPLEMENTATION",
            pending=None,
            transition={
                "type": "recover",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)

        with mock.patch.object(runtime.Runtime, "execute_bundle") as execute:
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
        self.assertEqual("recovery-transition-unselected", raised.exception.code)
        execute.assert_not_called()

    def test_structural_completion_cannot_skip_publication_and_pr_evidence(self):
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        self.fixture.approve(7613)
        instance = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = instance._status()
        state = instance._selected_state(inspection)
        successor = instance._successor(
            state,
            inspection["authority"],
            phase="COMPLETED",
            pending=None,
            transition={
                "type": "complete",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        )
        instance._commit(successor)

        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertIn(
            raised.exception.code,
            {"candidate-missing", "completion-transition-unselected"},
        )

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
        final_gate = self._agent_pair()
        self.assertEqual("WAITING_FOR_FINAL_APPROVAL", final_gate["phase"])
        self.assertEqual("final", self.fixture.challenge()["gate"])

    def test_test_gate_rejection_preserves_evidence_and_requires_fresh_review_and_approval(self):
        gate = self._to_test_gate()
        rejected_generation = gate["generation"]
        rejected_tip = gate["pointer_sha256"]
        rejected_state = copy.deepcopy(self.fixture.state())
        approval_challenge_binding = rejected_state["pending"]["request_binding"]
        approval_challenge = copy.deepcopy(self.fixture.challenge())
        rejected_manifest = next(
            row["binding"]
            for row in self.fixture.read(
                rejected_state["policy_state_binding"], orchestrator.POLICY_PATH
            )["active"]
            if row["node"] == "test-manifest"
        )
        rejected_manifest_projection = copy.deepcopy(
            evidence.project(self.fixture.root, rejected_manifest)
        )

        requested = orchestrator.reject(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            reason="Tests do not satisfy the approved contract.",
        )
        self.assertEqual(rejected_generation + 1, requested["generation"])
        self.assertEqual("WAITING_FOR_TEST_APPROVAL", requested["phase"])
        self.assertEqual("human-rejection", self.fixture.state()["pending"]["kind"])
        planned = orchestrator.plan_next(self.fixture.root, ISSUE)
        self.assertEqual(
            {
                "action": "authorize-gate-rejection",
                "command": "reject",
                "gate": "tests",
                "pending_kind": "human-rejection",
            },
            planned["next_action"],
        )
        self.assertIsNone(planned["pending_result_query"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as blocked_step:
            self.fixture.step()
        self.assertEqual("human-gate-requires-rejection", blocked_step.exception.code)
        with self.assertRaises(orchestrator.OrchestratorFailure) as blocked_cancel:
            orchestrator.cancel(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                reason="Do not reinterpret human rejection as execution.",
            )
        self.assertEqual("no-cancellable-attempt", blocked_cancel.exception.code)
        rejection_challenge_binding = self.fixture.state()["pending"]["request_binding"]
        rejection_challenge = self.fixture.challenge()
        self.assertEqual(rejected_tip, rejection_challenge["rejected_pointer_sha256"])
        self.assertEqual(rejected_generation, rejection_challenge["rejected_generation"])
        self.assertEqual(approval_challenge_binding, rejection_challenge["approval_challenge_binding"])
        self.assertEqual(rejected_manifest, rejection_challenge["artifact_binding"])

        self.fixture.comment(rejection_challenge["confirmation"], 7110)
        rejected = orchestrator.reject(
            self.fixture.root,
            ISSUE,
            expected_tip=self.fixture.tip(),
            authorization={"kind": "issue-comment", "id": 7110},
        )
        self.assertEqual(rejected_generation + 2, rejected["generation"])
        self.assertEqual("TEST_IMPLEMENTATION", rejected["phase"])
        self.assertIsNone(self.fixture.state()["pending"])
        rejection_binding = next(
            row["binding"]
            for row in self.fixture.state()["candidates"]
            if row["slot"] == "gate-rejection"
        )
        rejection = self.fixture.read(rejection_binding, supervision.REJECTION_PATH)
        self.assertEqual(rejection_challenge_binding, rejection["rejection_challenge_binding"])
        self.assertEqual(approval_challenge_binding, rejection["approval_challenge_binding"])
        self.assertEqual(rejected_manifest, rejection["artifact_binding"])
        self.assertEqual("TEST_IMPLEMENTATION", rejection["rework_phase"])
        self.assertEqual(
            rejected_manifest_projection,
            evidence.project(self.fixture.root, rejected_manifest),
        )
        historic = self.fixture.read(self.fixture.state()["previous_authority"])
        self.assertEqual("WAITING_FOR_TEST_APPROVAL", historic["phase"])
        self.assertEqual("human-rejection", historic["pending"]["kind"])
        approval_state = self.fixture.read(historic["previous_authority"])
        self.assertEqual(rejected_state, approval_state)
        self.assertEqual(approval_challenge, self.fixture.read(approval_challenge_binding))

        self.fixture.mode("rework-tests")
        self._agent_pair()
        policy_after_rework = self.fixture.read(
            self.fixture.state()["policy_state_binding"], orchestrator.POLICY_PATH
        )
        active_manifest = next(
            row["binding"]
            for row in policy_after_rework["active"]
            if row["node"] == "test-manifest"
        )
        self.assertNotEqual(rejected_manifest, active_manifest)
        self.assertIn(
            {
                "node": "test-manifest",
                "binding": rejected_manifest,
                "status": "invalidated",
                "transition_id": policy_after_rework["transition_tip"],
            },
            policy_after_rework["history"],
        )
        fresh_gate = self._agent_pair()
        self.assertEqual("WAITING_FOR_TEST_APPROVAL", fresh_gate["phase"])
        self.assertNotEqual(approval_challenge_binding, self.fixture.state()["pending"]["request_binding"])
        with self.assertRaises(orchestrator.OrchestratorFailure) as blocked:
            self.fixture.step()
        self.assertEqual("human-gate-requires-approval", blocked.exception.code)
        self.fixture.comment(approval_challenge["confirmation"], 7111)
        with self.assertRaises(orchestrator.OrchestratorFailure) as stale:
            orchestrator.approve(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                authorization={"kind": "issue-comment", "id": 7111},
            )
        self.assertEqual("authorization-confirmation-mismatch", stale.exception.code)
        approved = self.fixture.approve(7112)
        self.assertEqual("IMPLEMENTATION", approved["phase"])

    def test_rejection_fails_closed_for_unsupported_malformed_stale_and_replayed_requests(self):
        self._to_plan_gate()
        with self.assertRaises(orchestrator.OrchestratorFailure) as unsupported:
            orchestrator.reject(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                reason="Revise the plan.",
            )
        self.assertEqual("gate-rejection-unsupported", unsupported.exception.code)

        self.fixture.approve(7120)
        self._agent_pair()
        self._agent_pair()
        original_tip = self.fixture.tip()
        with self.assertRaises(orchestrator.OrchestratorFailure) as malformed:
            orchestrator.reject(
                self.fixture.root,
                ISSUE,
                expected_tip=original_tip,
                reason=" ",
            )
        self.assertEqual("rejection-reason-required", malformed.exception.code)
        with self.assertRaises(orchestrator.OrchestratorFailure) as stale:
            orchestrator.reject(
                self.fixture.root,
                ISSUE,
                expected_tip="0" * 64,
                reason="Tests are incomplete.",
            )
        self.assertEqual("expected-tip-stale", stale.exception.code)

        requested = orchestrator.reject(
            self.fixture.root,
            ISSUE,
            expected_tip=original_tip,
            reason="Tests are incomplete.",
        )
        rejection_tip = requested["pointer_sha256"]
        rejection_challenge = self.fixture.challenge()
        self.fixture.comment(rejection_challenge["confirmation"], 7121)
        with self.assertRaises(orchestrator.OrchestratorFailure) as old_approval:
            orchestrator.approve(
                self.fixture.root,
                ISSUE,
                expected_tip=rejection_tip,
                authorization={"kind": "issue-comment", "id": 7121},
            )
        self.assertEqual("no-open-gate", old_approval.exception.code)
        rejected = orchestrator.reject(
            self.fixture.root,
            ISSUE,
            expected_tip=rejection_tip,
            authorization={"kind": "issue-comment", "id": 7121},
        )
        with self.assertRaises(orchestrator.OrchestratorFailure) as replayed:
            orchestrator.reject(
                self.fixture.root,
                ISSUE,
                expected_tip=rejection_tip,
                authorization={"kind": "issue-comment", "id": 7121},
            )
        self.assertEqual("expected-tip-stale", replayed.exception.code)
        with self.assertRaises(orchestrator.OrchestratorFailure) as historical:
            orchestrator.reject(
                self.fixture.root,
                ISSUE,
                expected_tip=rejected["pointer_sha256"],
                reason="Reject history again.",
            )
        self.assertEqual("no-rejectable-gate", historical.exception.code)

    def test_existing_test_approval_path_does_not_create_rejection_evidence(self):
        self._to_test_gate()
        approved = self.fixture.approve(7122)
        self.assertEqual("IMPLEMENTATION", approved["phase"])
        self.assertNotIn(
            "gate-rejection",
            {row["slot"] for row in self.fixture.state()["candidates"]},
        )

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
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(request=candidate["handoff"])
        self.assertEqual("runtime-phase-repository-changed", raised.exception.code)
        self.assertEqual(candidate["pointer_sha256"], self.fixture.tip())

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
        original = runtime.Runtime.execute_bundle

        def counted(adapter, *args, **kwargs):
            calls.append(args[0]["operation"]["kind"])
            return original(adapter, *args, **kwargs)

        with mock.patch.object(runtime.Runtime, "execute_bundle", new=counted):
            self._agent_pair()
            self.fixture.validation_fails()
            failed = self._agent_pair()
        self.assertEqual(("paused", "unsupported-policy-transition"), tuple(failed["outcome"].values()))
        self.assertEqual("PAUSED", self.fixture.state()["phase"])
        self.assertEqual(["validation", "validation"], calls)

    def test_validation_cannot_certify_a_dirty_repository(self):
        self._to_validation()
        self.fixture.validation_drifts()
        candidate = self.fixture.step()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(request=candidate["handoff"], tip=candidate["pointer_sha256"])
        self.assertEqual(
            ("stale", "runtime-worktree-untrusted"),
            (raised.exception.status, raised.exception.code),
        )
        current = authority.status(self.fixture.root, ISSUE)
        self.assertEqual(candidate["pointer_sha256"], current["pointer_sha256"])
        self.assertEqual("VALIDATION", self.fixture.read(current["authority"])["phase"])

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
        tip = self.fixture.tip()
        (self.fixture.root / "scripts" / "preexisting.py").write_text("UNAPPROVED = True\n")
        self.fixture._git("add", "scripts/preexisting.py")
        self.fixture._git("commit", "--amend", "--no-edit")
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(tip=tip)
        self.assertEqual("runtime-phase-repository-changed", raised.exception.code)
        current = authority.status(self.fixture.root, ISSUE)
        self.assertEqual(tip, current["pointer_sha256"])
        self.assertEqual("IMPLEMENTATION", self.fixture.read(current["authority"])["phase"])

    def test_validation_refuses_a_new_head_after_implementation_submission(self):
        self._to_validation()
        tip = self.fixture.tip()
        (self.fixture.root / "scripts" / "late.py").write_text("LATE = True\n")
        self.fixture._git("add", "scripts/late.py")
        self.fixture._git("commit", "--amend", "--no-edit")
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(tip=tip)
        self.assertEqual(
            ("stale", "runtime-phase-repository-changed"),
            (raised.exception.status, raised.exception.code),
        )
        current = authority.status(self.fixture.root, ISSUE)
        self.assertEqual(tip, current["pointer_sha256"])
        self.assertEqual("VALIDATION", self.fixture.read(current["authority"])["phase"])

    def test_read_only_agents_and_test_author_fail_closed_on_repository_drift(self):
        self.fixture.mode("read-drift")
        candidate = self.fixture.step()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(request=candidate["handoff"], tip=candidate["pointer_sha256"])
        self.assertEqual("runtime-worktree-untrusted", raised.exception.code)

        other = OrchestratorFixture()
        self.addCleanup(other.close)
        other.install_provider(self)
        other.publish_response_source()
        orchestrator.init(other.root, ISSUE, request=other.request())
        self.fixture = other
        self._to_plan_gate()
        self.fixture.approve(7401)
        self.fixture.mode("test-drift")
        candidate = self.fixture.step()
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step(request=candidate["handoff"], tip=candidate["pointer_sha256"])
        self.assertEqual("runtime-worktree-untrusted", raised.exception.code)

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
        self.assertEqual("PR_PREPARATION", self.fixture.state()["phase"])
        completed = self.fixture.step()
        self.assertEqual("COMPLETED", completed["phase"])

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
        other.publish_response_source()
        orchestrator.init(other.root, ISSUE, request=other.request())
        self.fixture = other
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7402)
        (self.fixture.root / "scripts" / "late-drift.py").write_text("DIRTY = True\n")
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            orchestrator.approve(
                self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                authorization={"kind": "issue-comment", "id": 7402})
        self.assertEqual("gate-repository-mismatch", raised.exception.code)

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
        with mock.patch.object(runtime.Runtime, "execute_bundle", wraps=self.adapter.execute_bundle) as execute:
            candidate = self.fixture.step()
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
            self.assertEqual(("missing", "pending-result-not-found"), (raised.exception.status, raised.exception.code))
            self.assertEqual(1, execute.call_count)
            finalized = self.fixture.step(request=candidate["handoff"])
        self.assertEqual("PLAN_REVIEW", finalized["phase"])

    def test_fresh_adapters_reconstruct_across_test_and_implementation_commits(self):
        result = self._finish()
        self.assertEqual("COMPLETED", result["phase"])
        reconstructed = [
            adapter.reconstruction_document()
            for adapter in self.fixture.runtime_instances
            if adapter.reconstruction_document() is not None
        ]
        self.assertGreater(len(reconstructed), 10)
        self.assertEqual(
            len(self.fixture.runtime_instances),
            len({id(adapter) for adapter in self.fixture.runtime_instances}),
        )
        divergent_heads = {
            item["repository_expectation"]["observation"]["head"]["commit"]
            for item in reconstructed
            if item["repository_expectation"]["mode"] == "exact"
            and item["repository_expectation"]["observation"]["head"]["commit"]
            != self.fixture.head
        }
        self.assertGreaterEqual(len(divergent_heads), 2)
        self.assertTrue(
            all(
                item["pin"]["bootstrap"]["target_base"]["commit"] == self.fixture.head
                for item in reconstructed
            )
        )

    def test_history_reuses_one_reconstruction_per_command(self):
        self._to_pr_preparation()
        with mock.patch.object(runtime, "reconstruct", wraps=runtime.reconstruct) as reconstruct:
            before = len(self.fixture.calls())
            orchestrator.status(self.fixture.root, ISSUE)
            middle = len(self.fixture.calls())
            orchestrator.status(self.fixture.root, ISSUE)
        self.assertEqual(2, reconstruct.call_count)
        calls = self.fixture.calls()
        for start, end in ((before, middle), (middle, len(calls))):
            authorization_reads = [
                call for call in calls[start:end]
                if "/issues/comments/" in " ".join(call)
            ]
            self.assertGreaterEqual(len(authorization_reads), 4)

    def test_claim_execution_and_finalization_reconstruct_separately(self):
        before = len(self.fixture.runtime_instances)
        candidate = self.fixture.step()
        after_claim = len(self.fixture.runtime_instances)
        self.fixture.step(request=candidate["handoff"])
        after_finalize = len(self.fixture.runtime_instances)
        self.assertEqual(2, after_claim - before)
        self.assertEqual(1, after_finalize - after_claim)
        selected = self.fixture.runtime_instances[before:after_finalize]
        self.assertEqual(len(selected), len({id(adapter) for adapter in selected}))

    def test_approval_reconstructs_after_history_validation(self):
        self._to_test_gate()
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7002)
        tip = self.fixture.tip()
        before = len(self.fixture.runtime_instances)
        orchestrator.approve(
            self.fixture.root, ISSUE, expected_tip=tip,
            authorization={"kind": "issue-comment", "id": 7002},
        )
        selected = self.fixture.runtime_instances[before:]
        self.assertEqual(2, len(selected))
        self.assertEqual(2, len({id(adapter) for adapter in selected}))

    def test_published_pending_result_is_discovered_without_rerunning(self):
        candidate = self.fixture.step()
        query = orchestrator.plan_next(self.fixture.root, ISSUE)["pending_result_query"]
        binding = candidate["handoff"]["result_binding"]
        orchestrator.PENDING_RESULT_PROVIDER = lambda _root, _issue, supplied: {
            "format": "chess-echo-pending-result-candidates-v1",
            "query_sha256": supplied["query_sha256"],
            "candidates": [{"kind": "execution-result", "binding": binding}],
        }
        with mock.patch.object(
            runtime.Runtime, "execute", side_effect=AssertionError("must not rerun")
        ):
            finalized = self.fixture.step()
        self.assertEqual("PLAN_REVIEW", finalized["phase"])
        self.assertEqual(query["request_binding"], candidate["handoff"]["request_binding"])

    def test_pending_result_discovery_rejects_zero_multiple_malformed_and_wrong_results(self):
        candidate = self.fixture.step()
        valid = candidate["handoff"]["result_binding"]

        def response(candidates, query_sha256=None):
            return lambda _root, _issue, query: {
                "format": "chess-echo-pending-result-candidates-v1",
                "query_sha256": query["query_sha256"] if query_sha256 is None else query_sha256,
                "candidates": candidates,
            }

        cases = [
            ([], "pending-result-not-found"),
            (
                [
                    {"kind": "execution-result", "binding": valid},
                    {"kind": "execution-result", "binding": valid},
                ],
                "pending-result-ambiguous",
            ),
            ([{"kind": "wrong", "binding": valid}], "pending-result-candidate-invalid"),
            ([{"kind": "execution-result", "binding": {"sha256": "bad"}}], "pending-result-candidate-invalid"),
        ]
        for candidates, code in cases:
            with self.subTest(code=code):
                orchestrator.PENDING_RESULT_PROVIDER = response(candidates)
                with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                    self.fixture.step()
                self.assertEqual(code, raised.exception.code)

        runner = orchestrator.Orchestrator(self.fixture.root, ISSUE)
        inspection = runner._status()
        state = runner._selected_state(inspection)
        result = self.fixture.read(valid)
        wrong_attempt = copy.deepcopy(result)
        wrong_attempt["attempt_id"] = "f" * 64
        unsigned = copy.deepcopy(wrong_attempt)
        unsigned.pop("result_sha256")
        wrong_attempt["result_sha256"] = inspector.sha256(_canonical(unsigned))
        wrong_attempt_binding = runner._publish(
            "execution-result",
            "attempt-%s" % wrong_attempt["attempt_id"],
            state["pending"]["request_binding"],
            [("workflow-orchestration/execution-result.json", wrong_attempt)],
            state["generation"] + 1,
        )
        wrong_subject_binding = runner._publish(
            "execution-result",
            "attempt-%s" % result["attempt_id"],
            state["triage_binding"],
            [("workflow-orchestration/execution-result.json", result)],
            state["generation"] + 1,
        )
        for binding in (wrong_attempt_binding, wrong_subject_binding):
            orchestrator.PENDING_RESULT_PROVIDER = response(
                [{"kind": "execution-result", "binding": binding}]
            )
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                self.fixture.step()
            self.assertEqual("execution-handoff-stale", raised.exception.code)

        def stale(_root, _issue, query):
            orchestrator.cancel(
                self.fixture.root,
                ISSUE,
                expected_tip=self.fixture.tip(),
                reason="discovery race",
            )
            return {
                "format": "chess-echo-pending-result-candidates-v1",
                "query_sha256": query["query_sha256"],
                "candidates": [{"kind": "execution-result", "binding": valid}],
            }

        orchestrator.PENDING_RESULT_PROVIDER = stale
        with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
            self.fixture.step()
        self.assertEqual("attempt-result-stale", raised.exception.code)

    def test_idempotent_claim_loser_does_not_execute_the_process(self):
        real_commit = authority.commit

        def lose(root, bundle):
            result = real_commit(root, bundle)
            result["outcome"]["code"] = "already-committed"
            return result

        with mock.patch.object(authority, "commit", side_effect=lose), \
             mock.patch.object(runtime.Runtime, "execute_bundle", wraps=self.adapter.execute_bundle) as execute:
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
        self._to_validation()
        while self.fixture.state()["phase"] == "VALIDATION":
            self._agent_pair()
        self._agent_pair()
        challenge = self.fixture.challenge()
        self.fixture.comment(challenge["confirmation"], 7500)
        original = orchestrator.Orchestrator._fresh_local
        calls = 0

        def mutate(instance, state, expected_binding, subject_binding):
            nonlocal calls
            result = original(instance, state, expected_binding, subject_binding)
            calls += 1
            if calls == 1:
                self.fixture.comment("edited after first observation", 7500)
            return result

        with mock.patch.object(orchestrator.Orchestrator, "_fresh_local", new=mutate):
            with self.assertRaises(orchestrator.OrchestratorFailure) as raised:
                orchestrator.approve(
                    self.fixture.root, ISSUE, expected_tip=self.fixture.tip(),
                    authorization={"kind": "issue-comment", "id": 7500})
        self.assertEqual("authorization-confirmation-mismatch", raised.exception.code)


class OrchestratorGenesisAndCliTest(unittest.TestCase):
    def test_failed_history_validation_clears_command_scoped_runtime(self):
        instance = orchestrator.Orchestrator(REPOSITORY, ISSUE)
        inspection = {
            "authority": {"kind": "evidence-binding", "sha256": "0" * 64, "size": 1}
        }
        state = {"family_run_id": "issue-144-test-family"}
        sentinel = object()

        def fail(_state, _authority):
            instance._history_adapter = sentinel
            raise RuntimeError("invalid history")

        with mock.patch.object(instance, "_state", return_value=state), \
             mock.patch.object(instance, "_validate_supervision_history", side_effect=fail), \
             self.assertRaisesRegex(RuntimeError, "invalid history"):
            instance._selected_state(inspection)
        self.assertIsNone(instance._history_adapter)

    def test_init_status_and_public_dispatch(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        fixture.publish_response_source()
        initialized = orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual(("resolved", "initialized"), tuple(initialized["outcome"].values()))
        self.assertEqual("PLANNING", orchestrator.plan_next(fixture.root, ISSUE)["phase"])
        for command in (
            [sys.executable, str(REPOSITORY / "scripts" / "workflow_orchestrator.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_orchestrator", "--help"],
        ):
            result = subprocess.run(command, cwd=REPOSITORY, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_initialization_requires_published_exact_source_and_rejects_edit(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        with self.assertRaises(orchestrator.OrchestratorFailure) as missing:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("trusted-issue-source-required", missing.exception.code)

        publication = fixture.publish_response_source()
        path = inspector.object_path(fixture.store, publication["source"]["sha256"])
        path.unlink()
        with self.assertRaises(orchestrator.OrchestratorFailure) as absent:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("object-missing", absent.exception.code)

        fixture.publish_response_source()
        responses = json.loads((fixture.bin / "gh-responses.json").read_text())
        responses["api"]["repos/%s/issues/%d" % (SLUG, ISSUE)]["body"] = "edited"
        (fixture.bin / "gh-responses.json").write_text(json.dumps(responses, sort_keys=True))
        with self.assertRaises(orchestrator.OrchestratorFailure) as edited:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("issue-source-edited", edited.exception.code)
        with self.assertRaises(authority.AuthorityFailure) as authority_missing:
            authority.status(fixture.root, ISSUE)
        self.assertEqual("orchestration-pointer-missing", authority_missing.exception.code)

    def test_closed_issue_is_rejected_without_publication_or_authority(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        responses = json.loads((fixture.bin / "gh-responses.json").read_text())
        issue = responses["api"]["repos/%s/issues/%d" % (SLUG, ISSUE)]
        issue["state"] = "closed"
        issue["updated_at"] = "2026-09-05T00:01:00Z"
        (fixture.bin / "gh-responses.json").write_text(
            json.dumps(responses, sort_keys=True)
        )

        with self.assertRaises(issue_source.IssueSourceFailure) as closed:
            fixture.publish_response_source()
        self.assertEqual(
            ("denied", "issue-not-open"),
            (closed.exception.status, closed.exception.code),
        )
        self.assertFalse((fixture.store.store_dir / "objects").exists())
        with self.assertRaises(authority.AuthorityFailure) as authority_missing:
            authority.status(fixture.root, ISSUE)
        self.assertEqual(
            "orchestration-pointer-missing", authority_missing.exception.code
        )

    def test_reopened_issue_requires_fresh_publication_before_initialization(self):
        fixture = OrchestratorFixture()
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        stale_publication = fixture.publish_response_source()
        responses = json.loads((fixture.bin / "gh-responses.json").read_text())
        issue = responses["api"]["repos/%s/issues/%d" % (SLUG, ISSUE)]
        issue["state"] = "closed"
        issue["updated_at"] = "2026-09-05T00:01:00Z"
        (fixture.bin / "gh-responses.json").write_text(
            json.dumps(responses, sort_keys=True)
        )
        with self.assertRaises(issue_source.IssueSourceFailure) as closed:
            fixture.publish_response_source()
        self.assertEqual("issue-not-open", closed.exception.code)

        issue["state"] = "open"
        issue["updated_at"] = "2026-09-05T00:02:00Z"
        (fixture.bin / "gh-responses.json").write_text(
            json.dumps(responses, sort_keys=True)
        )
        with self.assertRaises(orchestrator.OrchestratorFailure) as stale:
            orchestrator.init(fixture.root, ISSUE, request=fixture.request())
        self.assertEqual("issue-source-edited", stale.exception.code)
        with self.assertRaises(authority.AuthorityFailure):
            authority.status(fixture.root, ISSUE)

        fresh_publication = fixture.publish_response_source()
        self.assertNotEqual(
            stale_publication["source"], fresh_publication["source"]
        )
        initialized = orchestrator.init(
            fixture.root, ISSUE, request=fixture.request()
        )
        self.assertEqual(
            ("resolved", "initialized"), tuple(initialized["outcome"].values())
        )

    def test_inactive_and_missing_status_are_typed(self):
        fixture = OrchestratorFixture(mode="inactive")
        self.addCleanup(fixture.close)
        fixture.install_provider(self)
        fixture.publish_response_source()
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
