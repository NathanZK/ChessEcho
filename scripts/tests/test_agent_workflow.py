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
        self.git("config", "user.email", "test@example.test")
        self.git("config", "user.name", "Workflow Test")
        (self.root / ".gitignore").write_text(
            "artifacts-src/\n.agent-workflow/\n", encoding="utf-8"
        )
        self.git("add", ".")
        self.git("commit", "-qm", "baseline")

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

    def state(self):
        path = (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "state.json"
        )
        return json.loads(path.read_text(encoding="utf-8"))

    def bootstrap_to_validation(self, submit=True):
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
        self.git("commit", "-qm", "add tests")
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
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        self.git("add", "src/Example.kt")
        base_head = self.state()["base_head"]
        self.git("reset", "-q", "--soft", base_head)
        self.git("commit", "-qm", "complete implementation")
        if not submit:
            return
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation",
                str(ISSUE),
                "--artifact",
                "artifacts-src/implementation-report.md",
                "--agent",
                "chess-echo-implementer",
            )[0],
        )

    def test_implementation_requires_one_commit_from_approved_base(self):
        self.bootstrap_to_validation(submit=False)
        (self.root / "src" / "second.kt").write_text("second\n", encoding="utf-8")
        self.git("add", "src/second.kt")
        self.git("commit", "-qm", "unrelated second commit")
        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-topology", payload["error"]["code"])

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

    def test_validation_and_pr_sequence(self):
        self.bootstrap_to_validation()
        self.assertEqual("VALIDATION", self.state()["status"])

        self.write_artifact("final-review.md", "final review")
        self.write_artifact(
            "pr-body.md",
            "## What\n- change\n\n## Why\n- reason\n\n## Testing\n- run\n",
        )

        patches = [
            mock.patch.object(workflow, "_current_head", return_value="abc123"),
        ]
        self.assertEqual(
            0,
            self.run_cli(
                "run-validation",
                str(ISSUE),
                "--profile",
                "workflow-tooling",
                patches=patches,
            )[0],
        )
        self.assertEqual("FINAL_REVIEW", self.state()["status"])

        self.assertEqual(
            0,
            self.run_cli(
                "review-final",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/final-review.md",
                "--reviewer",
                "chess-echo-reviewer",
                patches=[
                    mock.patch.object(workflow, "_current_head", return_value="abc123"),
                    mock.patch.object(workflow, "_git_commit_count", return_value=1),
                ],
            )[0],
        )

        code, payload, _ = self.run_cli(
            "create-draft-pr",
            str(ISSUE),
            "--title",
            "Issue 321",
            "--body-file",
            "artifacts-src/plan.md",
            "--skip-github",
            patches=[
                mock.patch.object(workflow, "_current_head", return_value="abc123"),
                mock.patch.object(workflow, "_git_commit_count", return_value=1),
            ],
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
                patches=[
                    mock.patch.object(workflow, "_current_head", return_value="abc123"),
                    mock.patch.object(workflow, "_git_commit_count", return_value=1),
                ],
            )[0],
        )

        code, payload, _ = self.run_cli(
            "approve-pr",
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
                "approve-pr",
                str(ISSUE),
                "--by",
                "owner",
                "--confirm",
                "I approve this draft PR.",
            )[0],
        )
        self.assertEqual("PR_APPROVED", self.state()["status"])

    def test_pr_rejection_returns_to_implementation_without_restart(self):
        self.bootstrap_to_validation()
        self.write_artifact("final-review.md", "final review")
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
                patches=[
                    mock.patch.object(workflow, "_current_head", return_value="abc123"),
                ],
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-final",
                str(ISSUE),
                "--status",
                workflow.READY,
                "--artifact",
                "artifacts-src/final-review.md",
                "--reviewer",
                "chess-echo-reviewer",
                patches=[
                    mock.patch.object(workflow, "_current_head", return_value="abc123"),
                    mock.patch.object(workflow, "_git_commit_count", return_value=1),
                ],
            )[0],
        )
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
                patches=[
                    mock.patch.object(workflow, "_current_head", return_value="abc123"),
                    mock.patch.object(workflow, "_git_commit_count", return_value=1),
                ],
            )[0],
        )
        before_rejection = self.state()
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", before_rejection["status"])
        self.assertTrue(before_rejection["final_review_ready"])
        self.assertIsNotNone(before_rejection["validation"])

        with mock.patch.object(workflow.workflow_supervisor, "supervise") as supervise:
            code, payload, _ = self.run_cli(
                "reject-pr",
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
        self.assertIsNone(rejected["approvals"]["pr"])
        self.assertFalse(rejected["final_review_ready"])
        self.assertIsNone(rejected["validation"])
        supervise.assert_not_called()

    def test_validation_failure_returns_to_implementation(self):
        self.bootstrap_to_validation()

        failure = mock.patch.object(
            workflow.workflow_supervisor,
            "supervise",
            return_value=supervisor_result(outcome="nonzero-exit", exit_code=1, stderr="failed"),
        )
        patches = [
            mock.patch.object(workflow, "_current_head", return_value="abc123"),
            mock.patch.object(workflow, "_git_status", return_value=[]),
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


if __name__ == "__main__":
    unittest.main()
