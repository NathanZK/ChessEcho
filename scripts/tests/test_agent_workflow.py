import base64
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack, redirect_stderr, redirect_stdout
from unittest import mock

from scripts import agent_workflow as workflow


ISSUE = 321


def encoded(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def supervisor_result(outcome="success", exit_code=0, stderr=""):
    return {
        "format": workflow.workflow_supervisor.RESULT_FORMAT,
        "command_sha256": "0" * 64,
        "limits": {
            "timeout_ms": 10,
            "grace_ms": 1,
            "stdout_bytes": 1024,
            "stderr_bytes": 1024,
        },
        "containment": {
            "kind": "posix-process-group",
            "cleanup_scope": "original-process-group",
            "escaped_descendants": "not-observable",
            "descendant_cleanup_verified": False,
        },
        "outcome": outcome,
        "reason": "process-exited",
        "exit_code": exit_code,
        "terminating_signal": None,
        "forced_termination": False,
        "cleanup_verified": True,
        "stdout": {
            "bytes": 0,
            "base64": "",
            "observed_bytes": 0,
            "observed_sha256": "0" * 64,
        },
        "stderr": {
            "bytes": len(stderr),
            "base64": encoded(stderr),
            "observed_bytes": len(stderr),
            "observed_sha256": "0" * 64,
        },
        "supervisor_error": None,
    }


class AgentWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        (self.root / ".github").mkdir(parents=True)
        (self.root / "artifacts-src").mkdir(parents=True)
        self.write_config()
        self.git("init", "-q")
        self.git("branch", "-M", "main")
        self.git("config", "user.email", "test@example.test")
        self.git("config", "user.name", "Workflow Test")
        (self.root / ".gitignore").write_text(
            "artifacts-src/\n.agent-workflow/\n", encoding="utf-8"
        )
        self.git("add", ".")
        self.git("commit", "-qm", "baseline")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def write_config(self):
        config = {
            "target_base": "main",
            "workflow": {
                "format": workflow.CONFIG_FORMAT,
                "run_root": ".agent-workflow/runs",
                "roles": {
                    "planner": "chess-echo-planner",
                    "reviewer": "chess-echo-reviewer",
                    "test_implementer": "chess-echo-test-implementer",
                    "implementer": "chess-echo-implementer",
                },
                "approvals": {
                    "plan": "plan_approved",
                    "tests": "tests_approved",
                    "implementation": "implementation_approved",
                    "pr": "I approve this draft PR.",
                },
                "execution": {
                    "default": {
                        "timeout_ms": 5000,
                        "grace_ms": 10,
                        "output_limit_bytes": 2048,
                        "stderr_limit_bytes": 2048,
                    },
                    "git": {
                        "command": ["git"],
                        "timeout_ms": 5000,
                        "grace_ms": 10,
                        "output_limit_bytes": 2048,
                    },
                    "github": {
                        "command": ["gh"],
                        "timeout_ms": 5000,
                        "grace_ms": 10,
                        "output_limit_bytes": 2048,
                    },
                    "validation": {
                        "timeout_ms": 5000,
                        "grace_ms": 10,
                        "output_limit_bytes": 2048,
                        "stderr_limit_bytes": 2048,
                    },
                },
            },
            "validation_profiles": {
                "workflow-tooling": {
                    "checks": [
                        {
                            "name": "workflow-check",
                            "command": [
                                sys.executable,
                                "-c",
                                "print('workflow check ok')",
                            ],
                        }
                    ]
                },
                "full-stack": {
                    "checks": [
                        {
                            "name": "full-stack-check",
                            "command": [
                                sys.executable,
                                "-c",
                                "print('full stack ok')",
                            ],
                        }
                    ]
                },
            },
        }
        (self.root / ".github" / "agent-workflow.json").write_text(
            json.dumps(config, indent=2) + "\n", encoding="utf-8"
        )

    def run_cli(self, *arguments, patches=None):
        output = io.StringIO()
        error = io.StringIO()
        with ExitStack() as stack:
            if patches:
                for patch in patches:
                    stack.enter_context(patch)
            stack.enter_context(redirect_stdout(output))
            stack.enter_context(redirect_stderr(error))
            code = workflow.main([*arguments, "--root", str(self.root)])
        payload = json.loads(output.getvalue())
        return code, payload, error.getvalue()

    def write_artifact(self, name, content):
        path = self.root / "artifacts-src" / name
        path.write_text(content, encoding="utf-8")
        return path

    def git_candidate_diff(self, test_commit):
        tracked = self.git("diff", "--binary", test_commit, "--").stdout
        status = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=all"],
            cwd=self.root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout
        additions = []
        for line in status.splitlines():
            if not line.startswith("?? "):
                continue
            path = line[3:]
            completed = subprocess.run(
                ["git", "diff", "--binary", "--no-index", "--", "/dev/null", path],
                cwd=self.root,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            self.assertIn(completed.returncode, (0, 1), completed.stderr)
            additions.append(completed.stdout)
        return tracked + "".join(additions)

    def write_evidence(
        self,
        name="evidence.json",
        test_command=None,
        test_scope=None,
        exit_code=0,
        result="PASS",
        test_commit=None,
        candidate_diff=None,
        stdout="tests ok\n",
        stderr="",
    ):
        if test_command is None:
            test_command = "%s -c \"print('tests ok')\"" % sys.executable
        if test_scope is None:
            test_scope = ["src/test/ExampleTest.kt"]
        if test_commit is None:
            test_commit = self.state().get("test_commit")
        if candidate_diff is None:
            approved_test_commit = self.state().get("test_commit")
            if approved_test_commit:
                candidate_diff = self.git_candidate_diff(approved_test_commit)
        payload = {
            "test_command": test_command,
            "test_scope": test_scope,
            "exit_code": exit_code,
            "result": result,
            "test_commit": test_commit,
            "candidate_diff": candidate_diff or "",
            "stdout": stdout,
            "stderr": stderr,
        }
        path = self.root / "artifacts-src" / name
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        return "artifacts-src/" + name

    def state(self):
        path = (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "state.json"
        )
        return json.loads(path.read_text(encoding="utf-8"))

    def write_state(self, state):
        path = (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "state.json"
        )
        path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    def assert_clean_status(self):
        status_lines = [l for l in self.git("status", "--porcelain").stdout.splitlines() if l.strip()]
        self.assertEqual([], status_lines)

    def unrelated_empty_tree_commit(self):
        completed = subprocess.run(
            ["git", "commit-tree", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "-m", "unrelated base"],
            cwd=self.root,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return completed.stdout.strip()

    def bootstrap_to_reviewed_implementation(self):
        self.bootstrap_to_validation()
        self.write_artifact("implementation-review.md", "implementation review")
        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/implementation-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

    def checkout_unrelated_branch_ahead_of_target(self, branch="unrelated-work"):
        self.git("checkout", "-q", "-b", branch)
        (self.root / "src" / "test").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "test" / "HumanMoveBfsServiceTest.kt").write_text(
            "HumanMoveBfsRequest(excludedPlayers = listOf(\"p2\"))\n",
            encoding="utf-8",
        )
        self.git("add", "src/test/HumanMoveBfsServiceTest.kt")
        self.git("commit", "-qm", "f1b651b Add excluded player BFS tests")
        (self.root / "src" / "test" / "HumanMoveBfsServiceTest.kt").write_text(
            "HumanMoveBfsRequest(excludedPlayers = listOf(\"p2\"))\n"
            "HumanMoveBfsRequest(excludedPlayers = listOf(\"p3\"))\n",
            encoding="utf-8",
        )
        self.git("add", "src/test/HumanMoveBfsServiceTest.kt")
        self.git("commit", "-qm", "8af906e Refine excluded player BFS tests")
        (self.root / "src" / "main").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "main" / "HumanMoveBfsDto.kt").write_text(
            "data class HumanMoveBfsRequest(val excludedPlayers: List<String> = emptyList())\n",
            encoding="utf-8",
        )
        self.git("add", "src/main/HumanMoveBfsDto.kt")
        self.git("commit", "-qm", "55ab666 Support excluded BFS players")
        (self.root / "src" / "main" / "HumanMoveBfsDto.kt").unlink()
        self.git("add", "-A", "src/main/HumanMoveBfsDto.kt")
        self.git("commit", "-qm", "2123f935 Revert \"Support excluded BFS players\"")

    def bootstrap_to_implementation(self):
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "plan review")
        self.write_artifact("test-report.md", "tests")
        self.write_artifact("test-review.md", "test review")
        self.write_artifact("implementation-report.md", "implementation")

        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/test/ExampleTest.kt",
                "--scope",
                "src/Example.kt",
            )[0],
        )
        (self.root / "src" / "test").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "test\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "candidate tests")
        code, payload, _ = self.run_cli(
            "review-plan",
            str(ISSUE),
            "--status",
            workflow.READY,
            "--artifact",
            "artifacts-src/plan-review.md",
            "--reviewer",
            "chess-echo-reviewer",
        )
        self.assertEqual(0, code)
        self.assertTrue(payload["human_approval"]["required"])
        self.assertEqual("plan", payload["human_approval"]["plan"])
        self.assertEqual("plan review", payload["human_approval"]["review"])
        self.assertIn("approve-plan", payload["human_approval"]["approval_command"])
        self.assertEqual(
            0,
            self.run_cli(
                "approve-plan",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "plan_approved",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--failure-command",
                "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
                "--failure-contains",
                "expected failure",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-tests",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/test-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-tests",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "tests_approved",
            )[0],
        )

    def bootstrap_to_validation(self, submit=True):
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        evidence_path = self.write_evidence()
        if not submit:
            return evidence_path
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation",
                str(ISSUE),
                "--artifact",
                "artifacts-src/implementation-report.md",
                "--agent",
                "chess-echo-implementer",
                "--evidence",
                evidence_path,
            )[0],
        )
        return evidence_path

    def test_uncommitted_implementation_candidate_and_evidence_acceptance(self):
        """Accepted implementation evidence binds an uncommitted Git candidate."""
        evidence_path = self.bootstrap_to_validation(submit=True)
        st = self.state()
        self.assertEqual("VALIDATION", st["status"])
        evidence = json.loads((self.root / evidence_path).read_text(encoding="utf-8"))
        self.assertIn("src/Example.kt", evidence["candidate_diff"])
        self.assertIn("implementation", evidence["candidate_diff"])
        self.assertEqual("implementation_report", st["artifacts"]["implementation_report"]["kind"])
        # Production Implementer remains uncommitted: current HEAD is still test_commit
        current_head = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(st["test_commit"], current_head)
        # Production candidate exists in working tree
        self.assertEqual("implementation\n", (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))

    def test_submit_implementation_rejects_candidate_modified_after_evidence(self):
        """submit-implementation rejects evidence for a different candidate."""
        self.bootstrap_to_implementation()
        # Create candidate A
        (self.root / "src" / "Example.kt").write_text("candidate A\n", encoding="utf-8")
        evidence_a = self.write_evidence(name="evidence-A.json")
        recorded_a = json.loads((self.root / evidence_a).read_text(encoding="utf-8"))["candidate_diff"]
        self.assertIn("src/Example.kt", recorded_a)
        self.assertIn("candidate A", recorded_a)
        # Tamper production candidate to candidate B after test execution
        (self.root / "src" / "Example.kt").write_text("candidate B\n", encoding="utf-8")
        current_b = self.git_candidate_diff(self.state()["test_commit"])
        self.assertIn("candidate B", current_b)
        self.assertNotEqual(recorded_a, current_b)
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence_a,
        )
        self.assertEqual(1, code)
        self.assertEqual("evidence-candidate-mismatch", payload["error"]["code"])

    def test_validation_rejects_candidate_changed_after_submission(self):
        """Validation independently rejects drift from the accepted candidate."""
        self.bootstrap_to_validation()
        self.assertEqual("VALIDATION", self.state()["status"])
        (self.root / "src" / "Example.kt").write_text("candidate B\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("VALIDATION", self.state()["status"])

    def test_review_rejects_candidate_changed_after_validation(self):
        """READY review cannot advance a candidate changed after validation."""
        self.bootstrap_to_validation()
        self.write_artifact("implementation-review.md", "implementation review")
        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
            )[0],
        )
        self.assertEqual("IMPLEMENTATION_REVIEW", self.state()["status"])
        (self.root / "src" / "Example.kt").write_text("candidate B\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "review-implementation",
            str(ISSUE),
            "--status",
            workflow.READY,
            "--artifact",
            "artifacts-src/implementation-review.md",
            "--reviewer",
            "chess-echo-reviewer",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION_REVIEW", self.state()["status"])

    def test_approve_implementation_rejects_candidate_changed_after_review(self):
        """Human Gate 3 rejects candidates changed after READY review."""
        self.bootstrap_to_reviewed_implementation()
        (self.root / "src" / "Example.kt").write_text("candidate B\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "approve-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "implementation_approved",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

    def test_untracked_candidate_change_after_submission_is_detected(self):
        """Untracked-file candidate drift is part of the bound Git candidate."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("untracked A\n", encoding="utf-8")
        evidence_path = self.write_evidence()
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation",
                str(ISSUE),
                "--artifact",
                "artifacts-src/implementation-report.md",
                "--agent",
                "chess-echo-implementer",
                "--evidence",
                evidence_path,
            )[0],
        )
        (self.root / "src" / "Example.kt").write_text("untracked B\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])

    def test_submit_implementation_evidence_integrity_checks(self):
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")

        # Missing evidence
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
        )
        self.assertEqual(1, code)

        # Non-zero exit code
        ev_failed = self.write_evidence(name="ev-failed.json", exit_code=1, result="FAIL")
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            ev_failed,
        )
        self.assertEqual(1, code)
        self.assertEqual("tests-failed", payload["error"]["code"])

        # Mismatched test_commit
        ev_mismatch = self.write_evidence(name="ev-mismatch.json", test_commit="0" * 40)
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            ev_mismatch,
        )
        self.assertEqual(1, code)
        self.assertEqual("test-commit-mismatch", payload["error"]["code"])

    def test_role_and_confirmation_guards(self):
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "review")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])

        code, payload, _ = self.run_cli(
            "submit-plan",
            str(ISSUE),
            "--artifact",
            "artifacts-src/plan.md",
            "--agent",
            "chess-echo-reviewer",
            "--scope",
            "src/test/ExampleTest.kt",
            "--scope",
            "src/Example.kt",
        )
        self.assertEqual(1, code)
        self.assertEqual("role-mismatch", payload["error"]["code"])

        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/test/ExampleTest.kt",
                "--scope",
                "src/Example.kt",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-plan",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/plan-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )

        code, payload, _ = self.run_cli(
            "approve-plan",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "approved",
        )
        self.assertEqual(1, code)
        self.assertEqual("approval-confirmation-mismatch", payload["error"]["code"])

        self.assertEqual(
            0,
            self.run_cli(
                "approve-plan",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "plan_approved",
            )[0],
        )
        self.assertEqual("TEST_IMPLEMENTATION", self.state()["status"])

    def test_local_plan_approval_is_self_attested_not_independent_authorization(self):
        """#122 regression: local CLI fields are assertions, not human authorization."""
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "plan review")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/test/ExampleTest.kt",
            )[0],
        )

        code, payload, _ = self.run_cli(
            "review-plan",
            str(ISSUE),
            "--status",
            workflow.READY,
            "--artifact",
            "artifacts-src/plan-review.md",
            "--reviewer",
            "chess-echo-reviewer",
        )
        self.assertEqual(0, code)
        self.assertEqual("Approval Gate", payload["approval_gate"]["name"])
        self.assertEqual("self-attested-local-acknowledgment", payload["approval_gate"]["mechanism"])
        self.assertFalse(payload["approval_gate"]["independent_authorization"])

        code, payload, _ = self.run_cli(
            "approve-plan",
            str(ISSUE),
            "--by",
            "autonomous-agent",
            "--confirm",
            "plan_approved",
        )
        self.assertEqual(0, code)
        self.assertEqual("self-attested-local-acknowledgment", payload["approval"]["kind"])
        self.assertEqual("autonomous-agent", payload["approval"]["asserted_by"])
        self.assertFalse(payload["approval"]["independent_authorization"])
        self.assertEqual(payload["approval"], self.state()["approvals"]["plan"])

    def test_init_records_target_head_and_rejects_branch_ahead_of_target(self):
        """init records target_head and rejects pre-existing branch commits."""
        target_head = self.git("rev-parse", "origin/main").stdout.strip()
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        initialized = self.state()
        self.assertEqual(target_head, initialized["target_head"])
        self.assertEqual(target_head, initialized["base_head"])
        self.assertEqual(target_head, initialized["initial_head"])

        self.checkout_unrelated_branch_ahead_of_target()
        code, payload, _ = self.run_cli("init", str(ISSUE + 1))
        self.assertEqual(1, code)
        self.assertEqual("workflow-start-not-at-target", payload["error"]["code"])

    def test_pr_250_contamination_scenario_is_rejected_at_init(self):
        """PR #250-style unrelated ancestry and stale BFS files cannot publish."""
        target_head = self.git("rev-parse", "origin/main").stdout.strip()
        self.checkout_unrelated_branch_ahead_of_target()
        contaminated_head = self.git("rev-parse", "HEAD").stdout.strip()
        leaked_commits = self.git("rev-list", "--reverse", f"{target_head}..{contaminated_head}").stdout.splitlines()
        self.assertEqual(4, len(leaked_commits))
        self.assertIn(
            "excludedPlayers",
            (self.root / "src" / "test" / "HumanMoveBfsServiceTest.kt").read_text(encoding="utf-8"),
        )

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(1, code)
        self.assertEqual("workflow-start-not-at-target", payload["error"]["code"])
        self.assertFalse((self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}" / "state.json").exists())

        self.git("checkout", "-q", "main")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        state = self.state()
        self.git("checkout", "-q", target_head)
        self.checkout_unrelated_branch_ahead_of_target("invalid-publication")
        (self.root / "scripts").mkdir(parents=True, exist_ok=True)
        (self.root / "scripts" / "agent_workflow.py").write_text("implementation\n", encoding="utf-8")
        self.git("add", "scripts/agent_workflow.py")
        self.git("commit", "-qm", "Implement issue #321")
        invalid_head = self.git("rev-parse", "HEAD").stdout.strip()
        self.write_artifact("pr-body.md", "## What\n- change\n\n## Why\n- reason\n\n## Testing\n- run\n")
        state["status"] = "DRAFT_PR_CREATION"
        state["implementation_commit"] = invalid_head
        state["approved_scope"] = ["scripts/agent_workflow.py", "scripts/tests/test_agent_workflow.py"]
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/pr-body.md",
            "--skip-github",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-topology", payload["error"]["code"])

        self.git("checkout", "-q", "main")
        stale_path = self.root / "src" / "test" / "kotlin" / "com" / "chessecho" / "service"
        stale_path.mkdir(parents=True, exist_ok=True)
        (stale_path / "HumanMoveBfsServiceTest.kt").write_text(
            "HumanMoveBfsRequest(excludedPlayers = listOf(\"p2\"))\n",
            encoding="utf-8",
        )
        (self.root / "scripts").mkdir(parents=True, exist_ok=True)
        (self.root / "scripts" / "agent_workflow.py").write_text("implementation\n", encoding="utf-8")
        self.git("add", "src/test/kotlin/com/chessecho/service/HumanMoveBfsServiceTest.kt", "scripts/agent_workflow.py")
        self.git("commit", "-qm", "Implement issue #321")
        invalid_scope_head = self.git("rev-parse", "HEAD").stdout.strip()
        state["status"] = "DRAFT_PR_CREATION"
        state["implementation_commit"] = invalid_scope_head
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/pr-body.md",
            "--skip-github",
        )
        self.assertEqual(1, code)
        self.assertEqual("implementation-scope-drift", payload["error"]["code"])

    def test_three_gate_lifecycle_and_authoritative_commit_creation(self):
        self.bootstrap_to_validation()
        self.assertEqual("VALIDATION", self.state()["status"])

        self.write_artifact("implementation-review.md", "implementation review")
        self.write_artifact(
            "pr-body.md",
            "## What\n- change\n\n## Why\n- reason\n\n## Testing\n- run\n",
        )

        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
            )[0],
        )
        self.assertEqual("IMPLEMENTATION_REVIEW", self.state()["status"])

        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/implementation-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

        # Human Gate 3: approve-implementation creates the single authoritative commit
        target_head = self.state()["target_head"]
        approved_test_commit = self.state()["test_commit"]
        code, payload, _ = self.run_cli(
            "approve-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "implementation_approved",
        )
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])
        authoritative_commit = self.state()["implementation_commit"]
        self.assertIsNotNone(authoritative_commit)
        current_head = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(authoritative_commit, current_head)
        self.assertEqual(target_head, self.git("rev-parse", f"{current_head}^").stdout.strip())
        self.assertEqual("Implement issue #321", self.git("show", "-s", "--format=%s", current_head).stdout.strip())
        # Exactly 1 commit relative to target_head
        commit_count = int(self.git("rev-list", "--count", f"{target_head}..{current_head}").stdout.strip())
        self.assertEqual(1, commit_count)
        diff_names = [
            line.strip()
            for line in self.git("diff", "--name-only", f"{target_head}..{current_head}").stdout.splitlines()
            if line.strip()
        ]
        self.assertEqual(["src/Example.kt", "src/test/ExampleTest.kt"], sorted(diff_names))
        self.assertEqual("test\n", self.git("show", f"{current_head}:src/test/ExampleTest.kt").stdout)
        self.assertEqual("implementation\n", self.git("show", f"{current_head}:src/Example.kt").stdout)
        self.assertEqual("test\n", self.git("show", f"{approved_test_commit}:src/test/ExampleTest.kt").stdout)
        # Working tree is clean
        self.assert_clean_status()

        # Create draft PR completes workflow without redundant approval gate
        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/plan.md",
            "--skip-github",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-pr-body-format", payload["error"]["code"])

        self.assertEqual(
            0,
            self.run_cli(
                "create-draft-pr",
                str(ISSUE),
                "--title",
                "Issue 321",
                "--body-file",
                "artifacts-src/pr-body.md",
                "--skip-github",
            )[0],
        )
        self.assertEqual("WORKFLOW_COMPLETED", self.state()["status"])

    def test_approve_implementation_rejects_target_advance(self):
        """Implementation approval fails closed when the target branch advances."""
        self.bootstrap_to_reviewed_implementation()
        self.git("update-ref", "refs/remotes/origin/main", self.state()["test_commit"])

        code, payload, _ = self.run_cli(
            "approve-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "implementation_approved",
        )

        self.assertEqual(1, code)
        self.assertEqual("target-advanced", payload["error"]["code"])
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

    def test_create_draft_pr_rejects_multiple_commits_relative_to_target(self):
        """Draft PR publication requires exactly one commit from target_head."""
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        target_head = self.state()["target_head"]
        self.git("checkout", "-q", "-b", "side", target_head)
        (self.root / "side.txt").write_text("side\n", encoding="utf-8")
        self.git("add", "side.txt")
        self.git("commit", "-qm", "side branch")
        self.git("checkout", "-q", "main")
        self.git("merge", "--no-ff", "-qm", "merge unrelated side", "side")
        head = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(target_head, self.git("rev-parse", f"{head}^").stdout.strip())
        self.assertEqual(2, int(self.git("rev-list", "--count", f"{target_head}..{head}").stdout.strip()))
        self.write_artifact("pr-body.md", "## What\n- change\n\n## Why\n- reason\n\n## Testing\n- run\n")
        state = self.state()
        state["status"] = "DRAFT_PR_CREATION"
        state["implementation_commit"] = head
        state["approved_scope"] = ["side.txt"]
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/pr-body.md",
            "--skip-github",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-topology", payload["error"]["code"])

    def test_create_draft_pr_rejects_commit_not_parented_to_target(self):
        """Draft PR publication rejects commits not parented by target_head."""
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        target_head = self.state()["target_head"]
        (self.root / "first.txt").write_text("first\n", encoding="utf-8")
        self.git("add", "first.txt")
        self.git("commit", "-qm", "first")
        (self.root / "second.txt").write_text("second\n", encoding="utf-8")
        self.git("add", "second.txt")
        self.git("commit", "-qm", "second")
        head = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(target_head, self.git("rev-parse", f"{head}^").stdout.strip())
        self.write_artifact("pr-body.md", "## What\n- change\n\n## Why\n- reason\n\n## Testing\n- run\n")
        state = self.state()
        state["status"] = "DRAFT_PR_CREATION"
        state["implementation_commit"] = head
        state["approved_scope"] = ["first.txt", "second.txt"]
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/pr-body.md",
            "--skip-github",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-topology", payload["error"]["code"])

    def test_reject_implementation_returns_to_implementation_without_restart(self):
        self.bootstrap_to_validation()
        self.write_artifact("implementation-review.md", "implementation review")
        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/implementation-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        before_rejection = self.state()
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", before_rejection["status"])

        code, payload, _ = self.run_cli(
            "reject-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "needs changes",
        )

        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        rejected = self.state()
        self.assertEqual("IMPLEMENTATION", rejected["status"])
        self.assertIsNone(rejected["approvals"].get("implementation"))
        self.assertIsNone(rejected["validation"])
        # Previous approvals remain intact
        self.assertIsNotNone(rejected["approvals"]["plan"])
        self.assertIsNotNone(rejected["approvals"]["tests"])

    def test_reopen_tests_preserves_production_candidate_and_requires_gate_two_again(self):
        """Exceptional test-fixture recovery preserves production and repeats Gate 2."""
        self.bootstrap_to_implementation()
        previous_test_commit = self.state()["test_commit"]
        previous_test_approval = self.state()["approvals"]["tests"]
        (self.root / "src" / "Example.kt").write_text("implementation candidate\n", encoding="utf-8")
        production_before = (self.root / "src" / "Example.kt").read_text(encoding="utf-8")
        head_before = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.run_cli(
            "reopen-tests",
            str(ISSUE),
            "--reason",
            "approved-test-fixture-defect",
        )

        self.assertEqual(0, code)
        self.assertEqual("TEST_IMPLEMENTATION", payload["status"])
        reopened = self.state()
        self.assertEqual("TEST_IMPLEMENTATION", reopened["status"])
        self.assertIsNone(reopened["approvals"]["tests"])
        self.assertIsNone(reopened["test_commit"])
        self.assertIsNone(reopened["validation"])
        self.assertFalse(reopened["implementation_review_ready"])
        self.assertNotIn("test_report", reopened["artifacts"])
        self.assertNotIn("test_review", reopened["artifacts"])
        self.assertEqual(head_before, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(production_before, (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))
        self.assertEqual("?? src/Example.kt", self.git("status", "--porcelain", "--untracked-files=all").stdout.strip())
        self.assertEqual(1, len(reopened["test_reopenings"]))
        reopening = reopened["test_reopenings"][0]
        self.assertTrue(reopening["active"])
        self.assertEqual("approved-test-fixture-defect", reopening["reason"])
        self.assertEqual("reopen-tests", reopening["initiated_by"])
        self.assertEqual(previous_test_commit, reopening["previous_test_commit"])
        self.assertEqual(previous_test_approval, reopening["previous_test_approval"])

        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "corrected test\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "correct test fixture")
        corrected_candidate = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--failure-command",
                "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
                "--failure-contains",
                "expected failure",
            )[0],
        )
        self.assertEqual("TEST_REVIEW", self.state()["status"])
        self.assertEqual(corrected_candidate, self.state()["test_commit"])
        self.assertEqual(corrected_candidate, self.state()["test_reopenings"][0]["corrected_test_candidate"])
        self.assertEqual(production_before, (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))

        self.assertEqual(
            0,
            self.run_cli(
                "review-tests",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/test-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual("WAITING_FOR_TEST_HUMAN_APPROVAL", self.state()["status"])
        self.assertEqual(
            0,
            self.run_cli(
                "approve-tests",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "tests_approved",
            )[0],
        )
        approved = self.state()
        self.assertEqual("IMPLEMENTATION", approved["status"])
        self.assertNotEqual(previous_test_commit, approved["test_commit"])
        self.assertNotEqual(corrected_candidate, approved["test_commit"])
        self.assertFalse(approved["test_reopenings"][0]["active"])
        self.assertEqual(approved["test_commit"], approved["test_reopenings"][0]["new_test_commit"])
        self.assertEqual(production_before, (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))
        self.assertEqual("?? src/Example.kt", self.git("status", "--porcelain", "--untracked-files=all").stdout.strip())

    def test_reopen_tests_rejects_non_exceptional_states(self):
        self.bootstrap_to_implementation()

        code, payload, _ = self.run_cli(
            "reopen-tests",
            str(ISSUE),
            "--reason",
            "needs-more-tests",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-reopen-reason", payload["error"]["code"])

        state_file = self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}" / "state.json"
        state = json.loads(state_file.read_text(encoding="utf-8"))
        state["implementation_commit"] = "abc123"
        state_file.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        code, payload, _ = self.run_cli(
            "reopen-tests",
            str(ISSUE),
            "--reason",
            "approved-test-fixture-defect",
        )
        self.assertEqual(1, code)
        self.assertEqual("implementation-already-approved", payload["error"]["code"])

        state["implementation_commit"] = None
        state["draft_pr"] = {"title": "already created"}
        state_file.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
        code, payload, _ = self.run_cli(
            "reopen-tests",
            str(ISSUE),
            "--reason",
            "approved-test-fixture-defect",
        )
        self.assertEqual(1, code)
        self.assertEqual("draft-pr-already-created", payload["error"]["code"])

    def test_validation_failure_returns_to_implementation(self):
        self.bootstrap_to_validation()

        failure = mock.patch.object(
            workflow,
            "_run_validation_checks",
            return_value=[
                {
                    "name": "workflow-check",
                    "command": ["test-command"],
                    "passed": False,
                    "result": supervisor_result(outcome="nonzero-exit", exit_code=1, stderr="failed"),
                }
            ],
        )
        patches = [
            failure,
        ]

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
            patches=patches,
        )
        self.assertEqual(1, code)
        self.assertEqual("validation-failed", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION", self.state()["status"])
        self.assertFalse(self.state()["validation"]["passed"])

    def test_test_implementer_cannot_modify_production_files(self):
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "plan review")
        self.write_artifact("test-report.md", "tests")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/test/ExampleTest.kt",
                "--scope",
                "src/Example.kt",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-plan",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/plan-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-plan",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "plan_approved",
            )[0],
        )
        (self.root / "src" / "test").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "test" / "ExampleTest.kt").write_text("test\n", encoding="utf-8")
        (self.root / "src" / "Example.kt").write_text("prod modified by test implementer\n", encoding="utf-8")
        self.git("add", "src/test/ExampleTest.kt", "src/Example.kt")
        self.git("commit", "-qm", "test implementer modifies prod")
        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
            "--failure-contains",
            "expected failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("test-scope-drift", payload["error"]["code"])

    def test_production_implementer_cannot_modify_test_files(self):
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        (self.root / "src" / "test" / "ExampleTest.kt").write_text("modified test\n", encoding="utf-8")
        evidence = self.write_evidence()
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence,
        )
        self.assertEqual(1, code)
        self.assertEqual("tests-modified-after-approval", payload["error"]["code"])

    def test_submit_implementation_detects_test_modification_after_approval(self):
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        (self.root / "src" / "test" / "ExampleTest.kt").write_text("reversed assertion\n", encoding="utf-8")
        evidence = self.write_evidence()
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence,
        )
        self.assertEqual(1, code)
        self.assertEqual("tests-modified-after-approval", payload["error"]["code"])

    def test_approve_tests_creates_and_verifies_test_commit_mechanically(self):
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "plan review")
        self.write_artifact("test-report.md", "tests")
        self.write_artifact("test-review.md", "test review")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/test/ExampleTest.kt",
                "--scope",
                "src/Example.kt",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-plan",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/plan-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-plan",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "plan_approved",
            )[0],
        )
        (self.root / "src" / "test").mkdir(parents=True, exist_ok=True)
        test_file = self.root / "src" / "test" / "ExampleTest.kt"
        test_file.write_text("class ExampleTest { /* candidate content */ }\n", encoding="utf-8")
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "submit candidate tests")
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--failure-command",
                "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
                "--failure-contains",
                "expected failure",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-tests",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/test-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        head_before_approval = self.git("rev-parse", "HEAD").stdout.strip()
        state_before = self.state()
        self.assertEqual("WAITING_FOR_TEST_HUMAN_APPROVAL", state_before["status"])
        self.assertIsNone(state_before["approvals"]["tests"])
        target_head = state_before["target_head"]
        state_before["base_head"] = self.unrelated_empty_tree_commit()
        self.write_state(state_before)

        code, payload, _ = self.run_cli(
            "approve-tests",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "tests_approved",
        )
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        st = self.state()
        self.assertEqual("IMPLEMENTATION", st["status"])
        self.assertIsNotNone(st.get("test_commit"))
        test_commit = st["test_commit"]
        self.assertNotEqual(head_before_approval, test_commit)
        current_head = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(current_head, test_commit)
        # Verify approved commit diff contains exactly the intended test content and only permitted test changes
        test_diff_names = [
            line.strip()
            for line in self.git("diff", "--name-only", f"{target_head}..{test_commit}").stdout.splitlines()
            if line.strip()
        ]
        self.assertEqual(["src/test/ExampleTest.kt"], test_diff_names)
        committed_content = self.git("show", f"{test_commit}:src/test/ExampleTest.kt").stdout
        self.assertEqual("class ExampleTest { /* candidate content */ }\n", committed_content)

    def test_missing_target_head_does_not_fall_back_to_base_head(self):
        """Publication operations fail closed instead of using diagnostic base_head."""
        self.bootstrap_to_implementation()
        state = self.state()
        state.pop("target_head", None)
        state["base_head"] = self.unrelated_empty_tree_commit()
        self.write_state(state)
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        evidence = self.write_evidence()
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation",
                str(ISSUE),
                "--artifact",
                "artifacts-src/implementation-report.md",
                "--agent",
                "chess-echo-implementer",
                "--evidence",
                evidence,
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
            )[0],
        )
        self.write_artifact("implementation-review.md", "implementation review")
        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/implementation-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )

        code, payload, _ = self.run_cli(
            "approve-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "implementation_approved",
        )

        self.assertEqual(1, code)
        self.assertEqual("missing-target-head", payload["error"]["code"])

    def test_submit_implementation_rejects_agent_created_production_commit(self):
        """Production agents cannot create the authoritative implementation commit."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "agent-created implementation")
        evidence = self.write_evidence()

        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence,
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-commit-not-allowed", payload["error"]["code"])

    def test_submit_implementation_blocked_without_approved_test_commit(self):
        self.write_artifact("plan.md", "plan")
        self.write_artifact("plan-review.md", "plan review")
        self.write_artifact("implementation-report.md", "implementation")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/plan.md",
                "--agent",
                "chess-echo-planner",
                "--scope",
                "src/Example.kt",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-plan",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/plan-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-plan",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "plan_approved",
            )[0],
        )
        # Put the workflow into IMPLEMENTATION while deliberately leaving test_commit absent
        state_file = self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}" / "state.json"
        state = json.loads(state_file.read_text(encoding="utf-8"))
        state["status"] = "IMPLEMENTATION"
        state["approvals"]["tests"] = {"by": "owner", "confirmation": "tests_approved", "at": "2026-09-14T00:00:00Z"}
        state["test_commit"] = None
        state_file.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

        (self.root / "src").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        evidence = self.write_evidence(test_commit=None)

        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence,
        )
        self.assertEqual(1, code)
        self.assertEqual("missing-test-commit", payload["error"]["code"])

    def test_run_validation_fails_if_approved_tests_modified(self):
        """Approved tests remain byte-for-byte immutable after Gate 2."""
        self.bootstrap_to_validation(submit=True)
        # Modify the approved test file in working tree after submit-implementation
        (self.root / "src" / "test" / "ExampleTest.kt").write_text("tampered test\n", encoding="utf-8")
        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )
        self.assertEqual(1, code)
        self.assertEqual("tests-modified-after-approval", payload["error"]["code"])


if __name__ == "__main__":
    unittest.main()
