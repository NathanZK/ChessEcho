import base64
import hashlib
import io
import json
import os
import pathlib
import re
import stat
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
            "artifacts-src/\n.agent-workflow/\ngenerated/\nshould-not-run.marker\n",
            encoding="utf-8",
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
                "with-setup": {
                    "setup": [
                        {
                            "name": "provision-generated-dependency",
                            "command": [
                                sys.executable,
                                "-c",
                                "import pathlib\n"
                                "d = pathlib.Path('generated')\n"
                                "d.mkdir(exist_ok=True)\n"
                                "(d / 'marker.txt').write_text('ok')\n",
                            ],
                        }
                    ],
                    "checks": [
                        {
                            "name": "requires-generated-dependency",
                            "command": [
                                sys.executable,
                                "-c",
                                "import pathlib, sys\n"
                                "sys.exit(0 if pathlib.Path('generated/marker.txt').exists() else 1)\n",
                            ],
                        }
                    ],
                },
                "with-failing-setup": {
                    "setup": [
                        {
                            "name": "unavailable-tool",
                            "command": [sys.executable, "-c", "raise SystemExit(1)"],
                        }
                    ],
                    "checks": [
                        {
                            "name": "should-not-run",
                            "command": [
                                sys.executable,
                                "-c",
                                "import pathlib\n"
                                "pathlib.Path('should-not-run.marker').write_text('ran')\n",
                            ],
                        }
                    ],
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
        commit_subject="Update example workflow implementation",
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
            "commit_subject": commit_subject,
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

    def transition_journal_path(self):
        return (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "implementation-approval-transition.json"
        )

    def approve_implementation(self, patches=None):
        return self.run_cli(
            "approve-implementation",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "implementation_approved",
            patches=patches,
        )

    def recover_implementation_approval(self):
        return self.run_cli("recover-implementation-approval", str(ISSUE))

    def reconciliation_journal_path(self):
        return (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "implementation-target-reconciliation-transition.json"
        )

    def reconcile_implementation_target(
        self, by="owner", confirm="implementation_target_reconciled"
    ):
        return self.run_cli(
            "reconcile-implementation-target",
            str(ISSUE),
            "--by",
            by,
            "--confirm",
            confirm,
        )

    def implementation_target_recovery_journal_path(self):
        return (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "implementation-target-recovery-transition.json"
        )

    def recover_implementation_target(
        self, by="owner", confirm="implementation_target_recovery_confirmed"
    ):
        return self.run_cli(
            "recover-implementation-target",
            str(ISSUE),
            "--by",
            by,
            "--confirm",
            confirm,
        )

    def reconcile_completed_run(
        self, issue, by="owner", confirm="completed_run_reconciled", patches=None
    ):
        return self.run_cli(
            "reconcile-completed-run",
            str(issue),
            "--by",
            by,
            "--confirm",
            confirm,
            patches=patches,
        )

    def test_transition_journal_path(self):
        return (
            self.root
            / ".agent-workflow"
            / "runs"
            / f"issue-{ISSUE}"
            / "test-approval-transition.json"
        )

    def test_target_drift_preserves_intent_when_realization_is_stale(self):
        record = workflow._target_drift_record(
            "reconcile-candidate",
            "preserved",
            "stale",
            "reconcile",
        )
        self.assertEqual(
            {
                "condition": "target-drift",
                "product_intent": "preserved",
                "repository_realization": "stale",
                "disposition": "reconcile",
                "transition": "reconcile-candidate",
                "revision_class": None,
            },
            record,
        )

    def test_target_drift_preserves_intent_when_realization_is_invalidated(self):
        record = workflow._target_drift_record(
            "recover-implementation-target",
            "preserved",
            "invalidated",
            "reenter-implementation",
            "implementation",
        )
        self.assertEqual("preserved", record["product_intent"])
        self.assertEqual("invalidated", record["repository_realization"])
        self.assertEqual("implementation", record["revision_class"])

    def test_target_drift_invalidated_intent_requires_plan_revision(self):
        record = workflow._target_drift_record(
            "reconcile-completed-run",
            "invalidated",
            "invalidated",
            "start-revision",
            "plan",
        )
        self.assertEqual("invalidated", record["product_intent"])
        self.assertEqual("plan", record["revision_class"])

    def test_target_drift_rejects_ambiguous_intent_and_realization(self):
        with self.assertRaises(workflow.WorkflowError):
            workflow._target_drift_record(
                "reconcile-candidate",
                "invalidated",
                "stale",
                "reconcile",
            )

    def approve_tests(self):
        return self.run_cli(
            "approve-tests",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "tests_approved",
        )

    def recover_test_approval(self):
        return self.run_cli("recover-test-approval", str(ISSUE))

    def assert_test_transition_journal_matches_state(self):
        journal_path = self.test_transition_journal_path()
        self.assertTrue(
            journal_path.is_file(),
            "test approval journal must be durable before Git mutation",
        )
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
        state = self.state()
        self.assertEqual(ISSUE, journal["issue"])
        self.assertEqual("approve-tests", journal["operation"])
        self.assertEqual(state["target_head"], journal["target_head"])
        self.assertEqual(state["test_commit"], journal["candidate_test_commit"])
        self.assertEqual(state["approved_scope"], journal["approved_scope"])
        self.assertEqual(
            state["test_implementation_status"], journal["test_implementation_status"]
        )
        self.assertIn(
            journal["status"],
            ("pending", "committed-but-not-persisted", "finalized"),
        )
        return journal

    def assert_transition_journal_matches_state(self):
        journal_path = self.transition_journal_path()
        self.assertTrue(
            journal_path.is_file(),
            "implementation approval journal must be durable before Git mutation",
        )
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
        state = self.state()
        candidate = state["implementation_candidate"]
        identity = workflow._candidate_identity(
            state["test_commit"],
            candidate["candidate_diff"],
            candidate["candidate_paths"],
        )
        self.assertEqual(ISSUE, journal["issue"])
        self.assertEqual("approve-implementation", journal["operation"])
        self.assertEqual(
            state["implementation_candidate"], journal["implementation_candidate"]
        )
        self.assertEqual(identity, journal["candidate_identity"])
        self.assertEqual(state["target_head"], journal["target_head"])
        self.assertEqual(state["test_commit"], journal["test_commit"])
        self.assertEqual(state["approved_scope"], journal["approved_scope"])
        self.assertEqual(
            state["test_implementation_status"],
            journal["test_implementation_status"],
        )
        self.assertEqual(
            state["implementation_candidate"]["commit_subject"],
            journal["reviewed_commit_subject"],
        )
        self.assertIn(
            journal["status"],
            ("pending", "committed-but-not-persisted", "finalized"),
        )
        return journal

    def fail_checked_git_command(self, token, code):
        original = workflow._run_checked

        def injected(command, limits, cwd, error_code, context, env=None):
            if token in command:
                raise workflow.WorkflowError(code, "injected failure at %s" % token)
            return original(command, limits, cwd, error_code, context, env=env)

        return mock.patch.object(workflow, "_run_checked", side_effect=injected)

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

    def bootstrap_to_reviewed_implementation(
        self, implementation_paths=None, candidate_setup=None
    ):
        self.bootstrap_to_validation(
            implementation_paths=implementation_paths, candidate_setup=candidate_setup
        )
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

    def bootstrap_to_draft_pr_creation(self):
        """Drive a run through every gate to the terminal DRAFT_PR_CREATION status."""
        self.bootstrap_to_reviewed_implementation()
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

    def bootstrap_to_committed_candidate_pending_target_advance(
        self, implementation_paths=None, candidate_setup=None
    ):
        """Reproduce the exact #276 crash shape that `reconcile-implementation-target` exists for.

        The implementation-approval journal is durable and valid, the
        candidate commit is exactly one direct child of the journal target,
        but final state persistence failed so `status` remains
        `WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL`. The caller is expected
        to subsequently advance `origin/<target_base>` to a strict
        descendant of the returned candidate commit (not of the original
        target) to complete the reconciliation scenario.
        """
        self.bootstrap_to_reviewed_implementation(
            implementation_paths=implementation_paths, candidate_setup=candidate_setup
        )
        original_write_state = workflow._write_state

        def fail_final_state(root, config, issue, state):
            if state.get("status") == "DRAFT_PR_CREATION":
                raise workflow.WorkflowError(
                    "injected-post-commit-state-failure",
                    "injected state persistence failure after authoritative commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_final_state):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-commit-state-failure", payload["error"]["code"])
        candidate_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        journal = self.assert_transition_journal_matches_state()
        self.assertEqual("committed-but-not-persisted", journal["status"])
        self.assertEqual(candidate_commit, journal["authoritative_commit"])
        self.assertEqual(
            self.state()["target_head"],
            self.git("rev-parse", f"{candidate_commit}^").stdout.strip(),
        )
        self.assert_clean_status()
        return candidate_commit

    def bootstrap_to_pending_journal_committed_candidate_pending_target_advance(
        self, implementation_paths=None, candidate_setup=None
    ):
        """Create the pending-journal reconciliation shape introduced by issue #287.

        The implementation-approval transition journal is durably written in
        `pending` status, the authoritative Gate 3 commit is created, and then
        persistence of the committed journal update fails before state
        finalization. This leaves:
          * state status at WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL
          * implementation-approval journal status pending with
            authoritative_commit == null
          * HEAD at the exact interrupted candidate commit (direct child of
            the journal target_head)
        """
        self.bootstrap_to_reviewed_implementation(
            implementation_paths=implementation_paths, candidate_setup=candidate_setup
        )
        original_write_json = workflow._write_json
        committed_journal_write_seen = False

        def fail_committed_journal_write(path, payload):
            nonlocal committed_journal_write_seen
            if (
                payload.get("status") == "committed-but-not-persisted"
                and not committed_journal_write_seen
            ):
                committed_journal_write_seen = True
                raise workflow.WorkflowError(
                    "injected-transition-commit-journal-failure",
                    "injected implementation transition commit-journal failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_committed_journal_write):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual(
            "injected-transition-commit-journal-failure", payload["error"]["code"]
        )
        candidate_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        journal = self.assert_transition_journal_matches_state()
        self.assertEqual("pending", journal["status"])
        self.assertIsNone(journal["authoritative_commit"])
        self.assertIsNone(journal["implementation_commit"])
        self.assertEqual(
            self.state()["target_head"],
            self.git("rev-parse", f"{candidate_commit}^").stdout.strip(),
        )
        self.assert_clean_status()
        return candidate_commit

    def bootstrap_mixed_order_interrupted_candidate(self, source_shape):
        """Create a legacy tracked-then-untracked diff that Git serializes by path."""
        tracked_path = "src/ZTracked.kt"
        added_path = "src/AAdded.kt"
        paths = [tracked_path, added_path]
        (self.root / tracked_path).parent.mkdir(parents=True, exist_ok=True)
        (self.root / tracked_path).write_text("baseline\n", encoding="utf-8")
        self.git("add", tracked_path)
        self.git("commit", "-qm", "seed tracked implementation")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

        def add_untracked_candidate_file():
            (self.root / added_path).write_text("added implementation\n", encoding="utf-8")

        bootstrap = {
            "committed-but-not-persisted": (
                self.bootstrap_to_committed_candidate_pending_target_advance
            ),
            "pending": (
                self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance
            ),
        }[source_shape]
        candidate_commit = bootstrap(
            implementation_paths=paths, candidate_setup=add_untracked_candidate_file
        )
        journal = json.loads(self.transition_journal_path().read_text(encoding="utf-8"))
        candidate = journal["implementation_candidate"]
        encoded_diff = candidate["candidate_diff"].encode("utf-8")
        journal["candidate_identity"] = {
            "test_commit": journal["test_commit"],
            "candidate_paths": sorted(candidate["candidate_paths"]),
            "candidate_diff_sha256": hashlib.sha256(encoded_diff).hexdigest(),
            "candidate_diff_bytes": len(encoded_diff),
        }
        self.transition_journal_path().write_text(
            json.dumps(journal, indent=2) + "\n", encoding="utf-8"
        )
        legacy_diff = journal["implementation_candidate"]["candidate_diff"]
        unified_diff = self.git(
            "diff",
            "--binary",
            f"{journal['test_commit']}..{candidate_commit}",
            "--",
            *paths,
        ).stdout
        self.assertLess(
            legacy_diff.index(f"b/{tracked_path}"),
            legacy_diff.index(f"b/{added_path}"),
        )
        self.assertLess(
            unified_diff.index(f"b/{added_path}"),
            unified_diff.index(f"b/{tracked_path}"),
        )
        return candidate_commit

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

    def bootstrap_to_waiting_for_test_approval(self, implementation_paths=None):
        if implementation_paths is None:
            implementation_paths = ["src/Example.kt"]
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
                *sum((["--scope", path] for path in implementation_paths), []),
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

    def bootstrap_to_implementation(self, implementation_paths=None):
        self.bootstrap_to_waiting_for_test_approval(
            implementation_paths=implementation_paths
        )
        self.write_artifact("implementation-report.md", "implementation")
        self.assertEqual(0, self.approve_tests()[0])

    def bootstrap_to_test_implementation(self):
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
        self.assertEqual("TEST_IMPLEMENTATION", self.state()["status"])

    def bootstrap_to_validation(
        self, submit=True, implementation_paths=None, candidate_setup=None
    ):
        self.bootstrap_to_implementation(implementation_paths=implementation_paths)
        implementation_path = (
            implementation_paths[0] if implementation_paths else "src/Example.kt"
        )
        (self.root / implementation_path).parent.mkdir(parents=True, exist_ok=True)
        (self.root / implementation_path).write_text("implementation\n", encoding="utf-8")
        if candidate_setup:
            candidate_setup()
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

    def bootstrap_to_not_applicable_ci_validation(self):
        workflow_dir = self.root / ".github" / "workflows"
        workflow_dir.mkdir(parents=True, exist_ok=True)
        (workflow_dir / "ci.yml").write_text(
            "name: ci\njobs:\n  frontend:\n    steps:\n      - run: npm run test\n",
            encoding="utf-8",
        )
        self.git("add", ".github/workflows/ci.yml")
        self.git("commit", "-qm", "baseline ci workflow")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
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
                ".github/workflows/ci.yml",
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
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--not-applicable",
                "--reason",
                "Approved implementation scope contains no test files.",
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
        (workflow_dir / "ci.yml").write_text(
            "name: ci\njobs:\n  frontend:\n    steps:\n      - run: npm run lint\n      - run: npm run test\n",
            encoding="utf-8",
        )
        evidence_path = self.write_evidence(
            test_scope=[],
            stdout="lint baseline ok\n",
            test_command="%s -c \"print('lint baseline ok')\"" % sys.executable,
        )
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

    def bootstrap_to_not_applicable_validation(self):
        (self.root / "src").mkdir()
        (self.root / "src" / "Example.kt").write_text("baseline\n", encoding="utf-8")
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "baseline implementation")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
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
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--not-applicable",
                "--reason",
                "Approved implementation scope contains no test files.",
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
        evidence_path = self.write_evidence(test_scope=[])
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

    def test_required_validation_rejects_approved_test_mutation(self):
        """REQUIRED test implementations keep approved test content immutable."""
        self.bootstrap_to_validation()
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "mutated after approval\n", encoding="utf-8"
        )

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("tests-modified-after-approval", payload["error"]["code"])
        self.assertEqual("VALIDATION", self.state()["status"])

    def test_approved_test_paths_expand_directory_scopes_to_concrete_tests(self):
        """Directory scopes protect changed tests without treating mixed roots as tests."""
        files = {
            "src/test/foo/BarTest.kt": "bar\n",
            "scripts/tests/test_agent_workflow.py": "workflow\n",
            "frontend/src/components/Button.test.tsx": "button test\n",
        }
        production = self.root / "frontend/src/components/Button.tsx"
        production.parent.mkdir(parents=True, exist_ok=True)
        production.write_text("button production\n", encoding="utf-8")
        self.git("add", "frontend/src/components/Button.tsx")
        self.git("commit", "-qm", "frontend production baseline")
        for relative, content in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "directory-scoped tests")
        state = {
            "target_head": self.git("rev-parse", "HEAD^").stdout.strip(),
            "test_commit": self.git("rev-parse", "HEAD").stdout.strip(),
            "approved_scope": ["src/test", "scripts/tests", "frontend/src"],
            "test_implementation_status": "REQUIRED",
        }

        self.assertEqual(
            [
                "frontend/src/components/Button.test.tsx",
                "scripts/tests/test_agent_workflow.py",
                "src/test/foo/BarTest.kt",
            ],
            workflow._approved_test_paths(
                self.root,
                self.root_config(),
                state,
                "directory-scope regression",
            ),
        )

    def test_test_file_boundaries_cover_directory_roots_without_mixed_frontend_scope(self):
        """Test classification is boundary-safe for roots and mixed frontend source."""
        self.assertTrue(workflow._is_test_file("src/test"))
        self.assertTrue(workflow._is_test_file("src/test/foo/BarTest.kt"))
        self.assertTrue(workflow._is_test_file("scripts/tests"))
        self.assertTrue(workflow._is_test_file("scripts/tests/test_agent_workflow.py"))
        self.assertTrue(workflow._is_test_file("frontend/src/Button.test.tsx"))
        self.assertTrue(workflow._is_test_file("frontend/src/Button.spec.ts"))
        self.assertFalse(workflow._is_test_file("frontend/src/Button.tsx"))

    def test_not_applicable_validation_allows_non_test_implementation_change(self):
        """NOT_APPLICABLE does not compare an empty test path set as the repository."""
        self.bootstrap_to_not_applicable_validation()

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION_REVIEW", payload["status"])
        self.assertEqual("NOT_APPLICABLE", self.state()["test_implementation_status"])

    def test_validation_rejects_invalid_test_applicability_state(self):
        """Malformed applicability state fails closed instead of bypassing validation."""
        self.bootstrap_to_validation()
        state = self.state()
        state["test_implementation_status"] = None
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-test-applicability", payload["error"]["code"])
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

    def test_gate3_candidate_tree_ignores_diff_serialization_reordering(self):
        """Tree/content/mode/path equivalence tolerates diff/status text reordering.

        This is a regression test for the #276 defect: the pre-#282
        equivalence check compared concatenated ``git diff``/``git status``
        text byte-for-byte, so serialization/ordering artifacts in that text
        (unrelated to any real content, mode, or path change) could cause a
        false rejection. The replacement check is an exact Git tree id, so
        reordering or otherwise mutating only the recorded diff text must not
        cause validation/review/approval to fail.
        """
        self.bootstrap_to_validation()
        state = self.state()
        accepted_tree = state["implementation_candidate"]["candidate_tree"]
        self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", accepted_tree))
        # Corrupt only the diff text's serialization/order; the underlying
        # tree, content, mode, and path set are untouched.
        original_diff = state["implementation_candidate"]["candidate_diff"]
        state["implementation_candidate"]["candidate_diff"] = (
            "diff --git a/reordered noise\n" + original_diff[::-1]
        )
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION_REVIEW", payload["status"])
        self.assertEqual(accepted_tree, self.state()["implementation_candidate"]["candidate_tree"])

    def test_gate3_candidate_tree_still_rejects_persisted_evidence_drift(self):
        """A tampered accepted tree id fails closed even if diff/paths look unchanged."""
        self.bootstrap_to_validation()
        state = self.state()
        # Leave candidate_diff/candidate_paths exactly as recorded (what the
        # pre-#282 check alone would have accepted) but corrupt the
        # authoritative tree id, simulating persisted-evidence drift.
        state["implementation_candidate"]["candidate_tree"] = "1" * 40
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("VALIDATION", self.state()["status"])

    def test_gate3_rejects_malformed_candidate_tree(self):
        """A missing or malformed persisted tree id is rejected, not silently trusted."""
        self.bootstrap_to_validation()
        state = self.state()
        state["implementation_candidate"]["candidate_tree"] = "not-a-tree-id"
        self.write_state(state)

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-candidate", payload["error"]["code"])

    def test_gate3_rejects_candidate_path_set_change(self):
        """Adding an extra candidate path after acceptance still fails closed."""
        self.bootstrap_to_validation()
        (self.root / "src" / "Extra.kt").write_text("extra\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("VALIDATION", self.state()["status"])

    def test_gate3_rejects_candidate_mode_change(self):
        """An executable-bit change on an otherwise byte-identical file fails closed."""
        self.bootstrap_to_validation()
        target = self.root / "src" / "Example.kt"
        target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual("VALIDATION", self.state()["status"])

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

    def test_implementation_commit_subject_validation_rejects_generic_issue_only_subjects(self):
        """Commit subjects must describe the change, not only identify the issue."""
        generic_subjects = [
            "Implement issue #321",
            "implement issue 321",
            "Fix issue #321",
            "Resolve #321",
            "Issue #321",
        ]

        for subject in generic_subjects:
            with self.subTest(subject=subject):
                with self.assertRaises(workflow.WorkflowError) as raised:
                    workflow._validate_implementation_commit_subject(subject, ISSUE)
                self.assertEqual("invalid-implementation-commit-subject", raised.exception.code)

    def test_submit_implementation_rejects_missing_commit_subject(self):
        """Implementation submission fails closed when no meaningful subject is available."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        evidence_path = self.write_evidence()
        evidence = json.loads((self.root / evidence_path).read_text(encoding="utf-8"))
        del evidence["commit_subject"]
        (self.root / evidence_path).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence_path,
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-commit-subject", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION", self.state()["status"])

    def test_submit_implementation_rejects_generic_commit_subject(self):
        """Implementation submission must not accept the old generic fallback subject."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation\n", encoding="utf-8")
        evidence_path = self.write_evidence(commit_subject="Implement issue #321")

        code, payload, _ = self.run_cli(
            "submit-implementation",
            str(ISSUE),
            "--artifact",
            "artifacts-src/implementation-report.md",
            "--agent",
            "chess-echo-implementer",
            "--evidence",
            evidence_path,
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-implementation-commit-subject", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION", self.state()["status"])

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

    def test_plan_approval_rejects_modified_recorded_artifact(self):
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
        state = self.state()
        self.assertRegex(state["artifacts"]["plan"]["sha256"], r"^[0-9a-f]{64}$")
        (self.root / state["artifacts"]["plan"]["path"]).write_text(
            "modified plan", encoding="utf-8"
        )

        code, payload, _ = self.run_cli(
            "approve-plan",
            str(ISSUE),
            "--by",
            "owner",
            "--confirm",
            "plan_approved",
        )

        self.assertEqual(1, code)
        self.assertEqual("artifact-identity-mismatch", payload["error"]["code"])
        self.assertEqual("WAITING_FOR_PLAN_HUMAN_APPROVAL", self.state()["status"])

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

    def set_authoritative_remote(self, identity):
        config_path = self.root / ".github" / "agent-workflow.json"
        config = json.loads(config_path.read_text(encoding="utf-8"))
        if identity is None:
            config.pop("authoritative_remote", None)
        else:
            config["authoritative_remote"] = identity
        config_path.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")

    def remotes_dir(self):
        if not hasattr(self, "_remotes_dir"):
            self._remotes_dir = tempfile.TemporaryDirectory()
            self.addCleanup(self._remotes_dir.cleanup)
        return pathlib.Path(self._remotes_dir.name)

    def init_bare_remote(self, dirname):
        """Create a bare repository outside the worktree root for use as a remote."""
        bare_path = self.remotes_dir() / dirname
        subprocess.run(
            ["git", "init", "--bare", "-q", str(bare_path)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return bare_path

    def add_real_origin_remote(self, bare_dirname="authoritative-origin.git", commit_pending_config=True):
        """Create a real, fetchable bare remote and register it as origin.

        Existing tests never configure a real `origin` remote (they simulate
        `origin/<target_base>` with a bare tracking ref), so the new
        authoritative-remote guard is inert for them. This helper opts a
        specific test into a real remote so the guard's fetch-time behavior
        can be exercised end to end. The bare remote lives outside the
        worktree root so it never appears as untracked worktree content.
        """
        if commit_pending_config and self.git("status", "--porcelain", "--", ".github/agent-workflow.json").stdout.strip():
            self.git("add", ".github/agent-workflow.json")
            self.git("commit", "-qm", "test: configure authoritative remote")
        bare_path = self.init_bare_remote(bare_dirname)
        ref = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("push", "-q", str(bare_path), "%s:refs/heads/main" % ref)
        self.git("remote", "add", "origin", str(bare_path))
        return self.git("remote", "get-url", "origin").stdout.strip()

    def test_init_succeeds_with_correctly_configured_authoritative_remote(self):
        """A real origin matching the configured authoritative identity is trusted."""
        resolved = self.add_real_origin_remote()
        self.set_authoritative_remote(resolved)

        target_head = self.git("rev-parse", "HEAD").stdout.strip()
        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(0, code)
        self.assertEqual(target_head, self.state()["target_head"])

    def test_init_rejects_origin_redirected_by_instead_of(self):
        """A local url.*.insteadOf rewrite of origin fails closed."""
        resolved = self.add_real_origin_remote(bare_dirname="authoritative-origin.git")
        self.set_authoritative_remote(resolved)
        substitute = self.init_bare_remote("substitute-mirror.git")
        self.git(
            "push", "-q", str(substitute), "%s:refs/heads/main" % self.git("rev-parse", "HEAD").stdout.strip()
        )
        self.git("config", "url.%s.insteadOf" % substitute, resolved)

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(1, code)
        self.assertEqual("remote-not-authoritative", payload["error"]["code"])
        self.assertFalse((self.root / ".agent-workflow" / "runs" / ("issue-%s" % ISSUE)).exists())

    def test_init_rejects_arbitrary_local_mirror_path(self):
        """An origin pointed at an arbitrary local mirror path is rejected."""
        self.set_authoritative_remote("github.com/NathanZK/ChessEcho")
        mirror = self.init_bare_remote("some-local-mirror.git")
        self.git(
            "push", "-q", str(mirror), "%s:refs/heads/main" % self.git("rev-parse", "HEAD").stdout.strip()
        )
        self.git("remote", "add", "origin", str(mirror))

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(1, code)
        self.assertEqual("remote-not-authoritative", payload["error"]["code"])

    def test_init_rejects_incorrect_owner_repository_identity(self):
        """A same-host but different owner/repo origin is rejected."""
        self.set_authoritative_remote("github.com/NathanZK/ChessEcho")
        self.git("remote", "add", "origin", "https://github.com/someone-else/ChessEcho.git")

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(1, code)
        self.assertEqual("remote-not-authoritative", payload["error"]["code"])

    def test_init_fails_closed_when_resolved_remote_identity_is_ambiguous(self):
        """An unparsable/ambiguous resolved remote never defaults to trusted."""
        self.set_authoritative_remote("github.com/NathanZK/ChessEcho")
        self.git("remote", "add", "origin", "not-a-recognizable-remote-identity")

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(1, code)
        self.assertEqual("remote-not-authoritative", payload["error"]["code"])

    def test_init_is_unaffected_when_no_authoritative_remote_is_configured(self):
        """Runs without a configured expectation keep prior fetch-optional behavior."""
        self.set_authoritative_remote(None)
        target_head = self.git("rev-parse", "origin/main").stdout.strip()

        code, payload, _ = self.run_cli("init", str(ISSUE))

        self.assertEqual(0, code)
        self.assertEqual(target_head, self.state()["target_head"])

    def test_reanchor_target_rejects_origin_redirected_by_instead_of(self):
        """Target-freshness resolution also fails closed on a substituted origin."""
        bare_path = self.remotes_dir() / "authoritative-origin.git"
        self.set_authoritative_remote(str(bare_path))
        resolved = self.add_real_origin_remote(bare_dirname="authoritative-origin.git")
        self.assertEqual(str(bare_path), resolved)
        self.assertEqual([], self.git("status", "--porcelain").stdout.splitlines())
        self.bootstrap_to_planning_for_reanchor()

        advanced = self.unrelated_empty_tree_commit()
        substitute = self.init_bare_remote("reanchor-substitute.git")
        self.git("push", "-q", str(substitute), "%s:refs/heads/main" % advanced)
        self.git("config", "url.%s.insteadOf" % substitute, resolved)
        before = self.state()

        code, payload, _ = self.run_cli(
            "reanchor-target",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("remote-not-authoritative", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def bootstrap_to_planning_for_reanchor(self):
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
                "scripts/agent_workflow.py",
                "--scope",
                "scripts/tests/test_agent_workflow.py",
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

    def advance_remote_target(self, name="authorized remote merge"):
        return self.advance_remote_target_with_changes(
            {"remote.txt": "remote\n"},
            name=name,
        )

    def advance_remote_target_over_candidate(self, content=None, name="authorized overlapping remote merge"):
        candidate_path = self.root / "src" / "Example.kt"
        return self.advance_remote_target_with_changes(
            {"src/Example.kt": candidate_path.read_text(encoding="utf-8") if content is None else content},
            name=name,
        )

    def advance_remote_target_with_changes(self, changes, name="authorized remote merge"):
        base = self.state().get("target_head") or self.git("rev-parse", "origin/main").stdout.strip()
        return self.advance_remote_ref_past_commit(base, changes, name=name)

    def advance_remote_ref_past_commit(
        self, base_commit, changes, name="authorized downstream remote merge"
    ):
        """Advance `origin/<target_base>` after an interrupted local candidate commit.

        An interrupted Gate 3 candidate and its approved test commit are local,
        so reconciliation targets advance from the recorded target rather than
        inheriting either private commit.
        """
        index_path = self.root / "alt-index"
        if index_path.exists():
            index_path.unlink()
        env = os.environ.copy()
        env["GIT_INDEX_FILE"] = str(index_path)
        recorded_target = None
        try:
            recorded_target = self.state().get("target_head")
        except Exception:
            recorded_target = None
        target_base = (
            recorded_target
            if recorded_target and base_commit != recorded_target
            else base_commit
        )
        subprocess.run(
            ["git", "read-tree", target_base],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for path, content in changes.items():
            blob = subprocess.run(
                ["git", "hash-object", "-w", "--stdin"],
                cwd=self.root,
                check=True,
                env=env,
                input=content,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ).stdout.strip()
            subprocess.run(
                ["git", "update-index", "--add", "--cacheinfo", "100644", blob, path],
                cwd=self.root,
                check=True,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
        tree = subprocess.run(
            ["git", "write-tree"],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        remote_head = subprocess.run(
            ["git", "commit-tree", tree, "-p", target_base, "-m", name],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        if index_path.exists():
            index_path.unlink()
        self.git("update-ref", "refs/remotes/origin/main", remote_head)
        return remote_head

    def advance_remote_ref_from(self, parent_commit, changes, name="authorized downstream remote merge"):
        """Advance `origin/<target_base>` by committing `changes` directly onto `parent_commit`.

        Unlike `advance_remote_ref_past_commit`, this never substitutes the
        recorded `target_head` for the given parent: it is used for chained,
        multi-hop advances (issue #295) where the caller needs explicit
        control over which prior remote commit the next advance descends
        from.
        """
        index_path = self.root / "alt-index"
        if index_path.exists():
            index_path.unlink()
        env = os.environ.copy()
        env["GIT_INDEX_FILE"] = str(index_path)
        subprocess.run(
            ["git", "read-tree", parent_commit],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        for path, content in changes.items():
            blob = subprocess.run(
                ["git", "hash-object", "-w", "--stdin"],
                cwd=self.root,
                check=True,
                env=env,
                input=content,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ).stdout.strip()
            subprocess.run(
                ["git", "update-index", "--add", "--cacheinfo", "100644", blob, path],
                cwd=self.root,
                check=True,
                env=env,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
        tree = subprocess.run(
            ["git", "write-tree"],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        remote_head = subprocess.run(
            ["git", "commit-tree", tree, "-p", parent_commit, "-m", name],
            cwd=self.root,
            check=True,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()
        if index_path.exists():
            index_path.unlink()
        self.git("update-ref", "refs/remotes/origin/main", remote_head)
        return remote_head

    def reconcile_implementation_target_reanchor(self, by="owner"):
        return self.run_cli(
            "reconcile-implementation-target",
            str(ISSUE),
            "--by",
            by,
            "--confirm",
            "implementation_target_reconciliation_reanchored",
        )

    def bootstrap_to_materialized_reconciliation_pending_further_advance(
        self, implementation_paths=None, candidate_setup=None
    ):
        """Reproduce the exact issue #295 shape on top of the #276 shape.

        `reconcile-implementation-target` materializes a reconciliation commit
        onto a first advanced target, but final state persistence is
        interrupted immediately afterward -- leaving the reconciliation
        journal durably `committed` (with its `reconciled_commit` recorded)
        and `state.json` still at `WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL`.
        The caller advances `origin/<target_base>` again, past the returned
        reconciled commit, to exercise the governed re-anchor recovery.

        Returns `(candidate_commit, first_target, reconciled_commit)`.
        """
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance(
            implementation_paths=implementation_paths, candidate_setup=candidate_setup
        )
        first_target = self.advance_remote_ref_past_commit(
            candidate_commit,
            {"downstream.txt": "downstream\n"},
            name="first authorized downstream merge",
        )
        original_write_state = workflow._write_state

        def fail_final_state(root, config, issue, state):
            if state.get("status") == "DRAFT_PR_CREATION":
                raise workflow.WorkflowError(
                    "injected-post-reconciliation-state-failure",
                    "injected state persistence failure after reconciled commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_final_state):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-reconciliation-state-failure", payload["error"]["code"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        recon = json.loads(self.reconciliation_journal_path().read_text(encoding="utf-8"))
        self.assertEqual("committed", recon["status"])
        self.assertEqual(reconciled_commit, recon["reconciled_commit"])
        self.assertEqual(first_target, recon["new_target_head"])
        self.assert_clean_status()
        return candidate_commit, first_target, reconciled_commit

    def amend_head_preserving_subject(self):
        subject = self.git("show", "-s", "--format=%s", "HEAD").stdout.strip()
        self.git("add", "-A")
        self.git("commit", "--amend", "-qm", subject)
        return self.git("rev-parse", "HEAD").stdout.strip()

    def assert_candidate_state_matches(self, test_commit, expected_diff, expected_paths):
        self.assertEqual(test_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(expected_diff, self.git_candidate_diff(test_commit))
        self.assertEqual(expected_paths, workflow._git_candidate_names(self.root, self.root_config(), test_commit))

    def root_config(self):
        return workflow._load_config(self.root)

    def test_reanchor_target_preserves_scope_and_provenance_on_fast_forward(self):
        """A governed fast-forward updates only the trusted target identity."""
        self.bootstrap_to_planning_for_reanchor()
        before = self.state()
        remote_head = self.advance_remote_target()

        code, payload, _ = self.run_cli(
            "reanchor-target",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(0, code)
        self.assertEqual("TEST_IMPLEMENTATION", payload["status"])
        after = self.state()
        self.assertEqual(remote_head, after["target_head"])
        self.assertEqual(remote_head, after["base_head"])
        self.assertEqual(before["approved_scope"], after["approved_scope"])
        self.assertEqual(before["artifacts"], after["artifacts"])
        self.assertEqual(before["approvals"]["plan"], after["approvals"]["plan"])
        provenance = after["target_reanchors"][-1]
        self.assertEqual(before["target_head"], provenance["previous_target_head"])
        self.assertEqual(remote_head, provenance["new_target_head"])
        self.assertEqual("owner", provenance["requested_by"])
        self.assertTrue(provenance["requested_at"])
        self.assertEqual("origin/main", provenance["remote_ref"])
        self.assert_clean_status()

    def test_reanchor_target_rejects_non_descendant_remote(self):
        """An unrelated remote target cannot become the trusted base."""
        self.bootstrap_to_planning_for_reanchor()
        before = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.run_cli(
            "reanchor-target",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reanchor_target_rejects_dirty_worktree(self):
        """Re-anchoring never inspects or changes a dirty working tree."""
        self.bootstrap_to_planning_for_reanchor()
        before = self.state()
        self.advance_remote_target()
        (self.root / "uncommitted.txt").write_text("dirty\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "reanchor-target",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("git-worktree-dirty", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reanchor_target_fails_closed_for_invalid_downstream_artifact(self):
        """Ambiguous downstream ancestry prevents a trusted-base update."""
        self.bootstrap_to_implementation()
        before = self.state()
        self.advance_remote_target()

        code, payload, _ = self.run_cli(
            "reanchor-target",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertIn(
            payload["error"]["code"],
            {"invalid-artifact-ancestry", "artifact-validity-undetermined"},
        )
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_preserves_candidate_and_provenance_on_fast_forward(self):
        """A reconciled candidate keeps its approved meaning on a descendant target."""
        self.bootstrap_to_validation()
        before = self.state()
        expected_diff = before["implementation_candidate"]["candidate_diff"]
        expected_paths = before["implementation_candidate"]["candidate_paths"]
        remote_head = self.advance_remote_target()

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(0, code)
        self.assertEqual("VALIDATION", payload["status"])
        after = self.state()
        self.assertEqual(remote_head, after["target_head"])
        self.assertEqual(remote_head, after["base_head"])
        self.assertEqual(before["approved_scope"], after["approved_scope"])
        self.assertEqual(before["artifacts"]["implementation_report"], after["artifacts"]["implementation_report"])
        self.assertEqual(before["approvals"]["plan"], after["approvals"]["plan"])
        self.assertEqual(before["approvals"]["tests"], after["approvals"]["tests"])
        self.assertIsNone(after["validation"])
        self.assertFalse(after["implementation_review_ready"])
        self.assertNotEqual(before["test_commit"], after["test_commit"])
        self.assertEqual(after["test_commit"], after["implementation_candidate"]["test_commit"])
        self.assertEqual(expected_diff, after["implementation_candidate"]["candidate_diff"])
        self.assertEqual(expected_paths, after["implementation_candidate"]["candidate_paths"])
        self.assert_candidate_state_matches(after["test_commit"], expected_diff, expected_paths)
        provenance = after["candidate_reconciliations"][-1]
        self.assertEqual(before["target_head"], provenance["previous_target_head"])
        self.assertEqual(remote_head, provenance["new_target_head"])
        self.assertEqual("owner", provenance["requested_by"])
        self.assertEqual("rebase", provenance["reconciliation_method"])
        self.assertEqual(before["test_commit"], provenance["candidate_before"]["test_commit"])
        self.assertEqual(after["test_commit"], provenance["candidate_after"]["test_commit"])
        self.assertEqual(expected_paths, provenance["candidate_before"]["candidate_paths"])
        self.assertEqual(expected_paths, provenance["candidate_after"]["candidate_paths"])
        self.assertEqual(
            provenance["candidate_before"]["candidate_diff_sha256"],
            provenance["candidate_after"]["candidate_diff_sha256"],
        )
        self.assertEqual([], provenance["target_overlap_paths"])
        self.assertEqual(
            {
                "condition": "target-drift",
                "product_intent": "preserved",
                "repository_realization": "stale",
                "disposition": "reconcile",
                "transition": "reconcile-candidate",
                "revision_class": None,
            },
            provenance["target_drift"],
        )

    def test_reconcile_candidate_rejects_non_descendant_remote(self):
        """Candidate reconciliation rejects unrelated target ancestry."""
        self.bootstrap_to_validation()
        before = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_rejects_dirty_index(self):
        """Unexpected staged changes are rejected before reconciliation."""
        self.bootstrap_to_validation()
        before = self.state()
        self.advance_remote_target()
        (self.root / "extra.txt").write_text("unexpected\n", encoding="utf-8")
        self.git("add", "extra.txt")

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("git-index-dirty", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_rejects_scope_drift(self):
        """Approved scope must still cover the candidate exactly."""
        self.bootstrap_to_validation()
        self.advance_remote_target()
        state = self.state()
        state["approved_scope"] = ["src/test/ExampleTest.kt"]
        self.write_state(state)
        before = self.state()

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-scope-drift", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_rejects_content_drift(self):
        """Only the accepted candidate may be reconciled."""
        self.bootstrap_to_validation()
        before = self.state()
        self.advance_remote_target()
        (self.root / "src" / "Example.kt").write_text("candidate B\n", encoding="utf-8")

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_rejects_invalid_test_applicability_state(self):
        """Malformed approved test applicability fails closed."""
        self.bootstrap_to_validation()
        self.advance_remote_target()
        state = self.state()
        state["test_implementation_status"] = None
        self.write_state(state)
        before = self.state()

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("invalid-test-applicability", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_reconcile_candidate_rejects_conflicting_target_overlap(self):
        """Target changes that touch candidate paths are not safely reconcilable."""
        self.bootstrap_to_validation()
        before = self.state()
        self.advance_remote_target_over_candidate(
            content="remote overlap\n",
            name="authorized conflicting remote merge",
        )

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("artifact-validity-undetermined", payload["error"]["code"])
        self.assertEqual(before, self.state())
        self.assertEqual("implementation\n", (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))

    def test_reconcile_candidate_rejects_ambiguous_candidate_overlap_already_present_upstream(self):
        """Even identical upstream path overlap is ambiguous and fails closed."""
        self.bootstrap_to_validation()
        before = self.state()
        self.advance_remote_target_over_candidate()

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(1, code)
        self.assertEqual("artifact-validity-undetermined", payload["error"]["code"])
        self.assertEqual(before, self.state())

    def test_recover_implementation_target_invalidates_stale_candidate_after_overlap(self):
        """Issue #318: overlapping target advancement reopens implementation without reusing approval."""
        self.bootstrap_to_reviewed_implementation()
        before = self.state()
        old_candidate = before["implementation_candidate"]
        remote_head = self.advance_remote_target_over_candidate(
            content="remote overlap\n",
            name="authorized conflicting remote merge",
        )

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )
        self.assertEqual(1, code)
        self.assertEqual("artifact-validity-undetermined", payload["error"]["code"])
        self.assertEqual(before, self.state())

        code, payload, _ = self.recover_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        after = self.state()
        self.assertEqual(remote_head, after["target_head"])
        self.assertEqual(remote_head, after["base_head"])
        self.assertNotEqual(before["test_commit"], after["test_commit"])
        self.assertEqual(before["approvals"]["plan"], after["approvals"]["plan"])
        self.assertEqual(before["approvals"]["tests"], after["approvals"]["tests"])
        self.assertIsNone(after["implementation_candidate"])
        self.assertIsNone(after["approvals"]["implementation"])
        self.assertIsNone(after["validation"])
        self.assertFalse(after["implementation_review_ready"])
        self.assertEqual(after["test_commit"], self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("remote overlap\n", (self.root / "src" / "Example.kt").read_text(encoding="utf-8"))
        self.assertEqual("test\n", (self.root / "src" / "test" / "ExampleTest.kt").read_text(encoding="utf-8"))

        journal = json.loads(self.implementation_target_recovery_journal_path().read_text(encoding="utf-8"))
        self.assertEqual("finalized", journal["status"])
        self.assertEqual("recover-implementation-target", journal["operation"])
        self.assertEqual("implementation_target_recovery_confirmed", journal["acknowledgment"]["confirmation"])
        self.assertEqual(before["target_head"], journal["previous_target_head"])
        self.assertEqual(remote_head, journal["new_target_head"])
        self.assertEqual(old_candidate, journal["implementation_candidate_before"])
        provenance = after["implementation_target_recoveries"][-1]
        self.assertEqual(
            [
                "approvals.implementation",
                "implementation_candidate",
                "implementation_review_ready",
                "validation",
            ],
            provenance["invalidated_evidence"],
        )
        self.assertEqual(
            {
                "condition": "target-drift",
                "product_intent": "preserved",
                "repository_realization": "invalidated",
                "disposition": "reenter-implementation",
                "transition": "recover-implementation-target",
                "revision_class": "implementation",
            },
            provenance["target_drift"],
        )

    def test_recover_implementation_target_requires_fresh_submission_and_approval(self):
        """The old implementation approval cannot cross the recovery boundary."""
        self.bootstrap_to_reviewed_implementation()
        self.advance_remote_target_over_candidate(content="remote overlap\n")
        self.assertEqual(0, self.recover_implementation_target()[0])
        recovered = self.state()
        self.assertEqual("IMPLEMENTATION", recovered["status"])
        self.assertIsNone(recovered["approvals"]["implementation"])

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-transition", payload["error"]["code"])

        (self.root / "src" / "Example.kt").write_text("regenerated implementation\n", encoding="utf-8")
        evidence_path = self.write_evidence(
            name="regenerated-evidence.json",
            commit_subject="Regenerate recovered workflow implementation",
        )
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
        self.assertEqual(0, self.run_cli("run-validation", str(ISSUE), "--profile", "workflow-tooling")[0])
        self.write_artifact("implementation-review.md", "fresh implementation review")
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
        self.assertIsNone(self.state()["approvals"]["implementation"])
        code, payload, _ = self.approve_implementation()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual("Regenerate recovered workflow implementation", self.git("show", "-s", "--format=%s", "HEAD").stdout.strip())

    def test_recover_implementation_target_reopens_tests_when_test_boundary_overlaps(self):
        """Approved tests are invalidated, not guessed, when the new target touched them."""
        self.bootstrap_to_reviewed_implementation()
        before = self.state()
        remote_head = self.advance_remote_target_with_changes(
            {"src/test/ExampleTest.kt": "remote test change\n"},
            name="authorized overlapping test merge",
        )

        code, payload, _ = self.recover_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("TEST_IMPLEMENTATION", payload["status"])
        after = self.state()
        self.assertEqual(remote_head, after["target_head"])
        self.assertEqual(remote_head, after["base_head"])
        self.assertEqual(before["approvals"]["plan"], after["approvals"]["plan"])
        self.assertIsNone(after["approvals"]["tests"])
        self.assertIsNone(after["test_commit"])
        self.assertIsNone(after["test_failure"])
        self.assertIsNone(after["implementation_candidate"])
        self.assertIsNone(after["approvals"]["implementation"])
        self.assertEqual(remote_head, self.git("rev-parse", "HEAD").stdout.strip())
        provenance = after["implementation_target_recoveries"][-1]
        self.assertIn("approvals.tests", provenance["invalidated_evidence"])
        self.assertEqual(["src/test/ExampleTest.kt"], provenance["test_target_overlap_paths"])

    def test_recover_implementation_target_fails_closed_for_non_descendant_target(self):
        """Recovery uses the same strict target ancestry primitive as reconciliation."""
        self.bootstrap_to_reviewed_implementation()
        before = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.recover_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before, self.state())
        self.assertFalse(self.implementation_target_recovery_journal_path().exists())

    def test_recover_implementation_target_requires_explicit_authorization(self):
        """Recovery has its own confirmation phrase distinct from reconciliation gates."""
        self.bootstrap_to_reviewed_implementation()
        before = self.state()
        self.advance_remote_target_over_candidate(content="remote overlap\n")

        code, payload, _ = self.recover_implementation_target(confirm="implementation_target_reconciled")

        self.assertEqual(1, code)
        self.assertEqual(
            "implementation-target-recovery-confirmation-mismatch",
            payload["error"]["code"],
        )
        self.assertEqual(before, self.state())
        self.assertFalse(self.implementation_target_recovery_journal_path().exists())

    def test_reconcile_candidate_preserves_not_applicable_ci_candidate(self):
        """A synthetic #261-style CI-only candidate can reconcile after target advance."""
        self.bootstrap_to_not_applicable_ci_validation()
        before = self.state()
        expected_diff = before["implementation_candidate"]["candidate_diff"]
        expected_paths = before["implementation_candidate"]["candidate_paths"]
        remote_head = self.advance_remote_target()

        code, payload, _ = self.run_cli(
            "reconcile-candidate",
            str(ISSUE),
            "--by",
            "owner",
        )

        self.assertEqual(0, code)
        self.assertEqual("VALIDATION", payload["status"])
        after = self.state()
        self.assertEqual("NOT_APPLICABLE", after["test_implementation_status"])
        self.assertEqual(before["test_implementation_reason"], after["test_implementation_reason"])
        self.assertEqual(remote_head, after["target_head"])
        self.assertEqual(expected_diff, after["implementation_candidate"]["candidate_diff"])
        self.assertEqual(expected_paths, after["implementation_candidate"]["candidate_paths"])
        self.assert_candidate_state_matches(after["test_commit"], expected_diff, expected_paths)

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
        self.assertEqual(
            "Update example workflow implementation",
            self.git("show", "-s", "--format=%s", current_head).stdout.strip(),
        )
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
        self.assertEqual(
            0,
            self.run_cli(
                "create-draft-pr",
                str(ISSUE),
                "--title",
                "Issue 321",
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

    def test_failure_before_journal_durability_leaves_state_and_git_unchanged(self):
        """A journal persistence failure happens before any Git mutation."""
        self.bootstrap_to_reviewed_implementation()
        before_state = self.state()
        before_head = self.git("rev-parse", "HEAD").stdout.strip()
        original_write_json = workflow._write_json

        def fail_journal_write(path, payload):
            if path.name == "implementation-approval-transition.json":
                raise workflow.WorkflowError(
                    "injected-journal-persistence-failure",
                    "injected journal persistence failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_journal_write):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual("injected-journal-persistence-failure", payload["error"]["code"])
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(before_state, self.state())
        self.assertFalse(self.transition_journal_path().exists())

    def test_journal_durability_precedes_staging(self):
        """A staged-interruption journal binds recovery before Git staging starts."""
        self.bootstrap_to_reviewed_implementation()
        before_state = self.state()
        before_head = self.git("rev-parse", "HEAD").stdout.strip()

        with self.fail_checked_git_command("add", "injected-before-staging"):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual("injected-before-staging", payload["error"]["code"])
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(before_state, self.state())
        journal = self.assert_transition_journal_matches_state()
        self.assertEqual("pending", journal["status"])
        self.assert_clean_status()

    def test_recovery_reuses_only_the_exact_existing_candidate(self):
        """Recovery rejects candidate drift and accepts the original candidate unchanged."""
        self.bootstrap_to_reviewed_implementation()
        candidate_file = self.root / "src" / "Example.kt"
        original_candidate = candidate_file.read_text(encoding="utf-8")

        with self.fail_checked_git_command("add", "injected-before-staging"):
            self.approve_implementation()
        self.assert_transition_journal_matches_state()

        candidate_file.write_text(original_candidate + "drift\n", encoding="utf-8")
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])
        self.assertEqual(self.state()["test_commit"], self.git("rev-parse", "HEAD").stdout.strip())

        candidate_file.write_text(original_candidate, encoding="utf-8")
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(payload["implementation_commit"], self.git("rev-parse", "HEAD").stdout.strip())

    def test_staged_candidate_recovery_rejects_extra_content(self):
        """Recovery accepts only the exact candidate that was staged before interruption."""
        self.bootstrap_to_reviewed_implementation()
        with self.fail_checked_git_command("reset", "injected-before-reset"):
            self.approve_implementation()
        self.assert_transition_journal_matches_state()
        staged_check = subprocess.run(
            ["git", "diff", "--cached", "--quiet"],
            cwd=self.root,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(1, staged_check.returncode)

        (self.root / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

        (self.root / "unexpected.txt").unlink()
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])

    def test_soft_reset_recovery_requires_exact_approved_test_boundary(self):
        """Recovery resumes a soft-reset interruption only with tests plus the candidate."""
        self.bootstrap_to_reviewed_implementation()
        target_head = self.state()["target_head"]
        test_commit = self.state()["test_commit"]
        with self.fail_checked_git_command("commit", "injected-before-commit"):
            self.approve_implementation()
        self.assert_transition_journal_matches_state()
        self.assertEqual(target_head, self.git("rev-parse", "HEAD").stdout.strip())
        staged_names = [
            line.strip()
            for line in self.git("diff", "--cached", "--name-only").stdout.splitlines()
            if line.strip()
        ]
        self.assertEqual(["src/Example.kt", "src/test/ExampleTest.kt"], sorted(staged_names))
        self.assertEqual(test_commit, self.state()["test_commit"])

        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        implementation_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(target_head, self.git("rev-parse", f"{implementation_commit}^").stdout.strip())
        self.assertEqual(1, int(self.git("rev-list", "--count", f"{target_head}..{implementation_commit}").stdout.strip()))
        self.assert_clean_status()

    def test_post_commit_state_persistence_failure_recovers_without_second_commit(self):
        """The #276 post-commit failure recovers the same authoritative commit and acknowledgment."""
        self.bootstrap_to_reviewed_implementation()
        original_write_state = workflow._write_state

        def fail_final_state(root, config, issue, state):
            if state.get("status") == "DRAFT_PR_CREATION":
                raise workflow.WorkflowError(
                    "injected-post-commit-state-failure",
                    "injected state persistence failure after authoritative commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_final_state):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-commit-state-failure", payload["error"]["code"])
        authoritative_commit = self.git("rev-parse", "HEAD").stdout.strip()
        state_after_failure = self.state()
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", state_after_failure["status"])
        self.assertIsNone(state_after_failure["implementation_commit"])
        journal = self.assert_transition_journal_matches_state()
        self.assertEqual("committed-but-not-persisted", journal["status"])
        self.assertEqual(authoritative_commit, journal["authoritative_commit"])

        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(authoritative_commit, payload["implementation_commit"])
        recovered = self.state()
        self.assertEqual(authoritative_commit, recovered["implementation_commit"])
        self.assertEqual(
            journal["acknowledgment"], recovered["approvals"]["implementation"]
        )
        self.assertEqual(
            journal["acknowledgment"], payload["approval"]
        )
        self.assertEqual(1, int(self.git("rev-list", "--count", f"{recovered['target_head']}..HEAD").stdout.strip()))
        self.assert_clean_status()

        state_after_recovery = self.state()
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(authoritative_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(state_after_recovery, self.state())

    def test_final_state_persistence_before_journal_finalization_is_idempotent(self):
        """A final-state/journal ordering interruption remains recoverable and idempotent."""
        self.bootstrap_to_reviewed_implementation()
        original_write_json = workflow._write_json
        final_journal_write_seen = False

        def fail_final_journal_write(path, payload):
            nonlocal final_journal_write_seen
            if (
                path.name == "implementation-approval-transition.json"
                and payload.get("implementation_commit")
                and not final_journal_write_seen
            ):
                final_journal_write_seen = True
                raise workflow.WorkflowError(
                    "injected-journal-finalization-failure",
                    "injected journal finalization failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_final_journal_write):
            code, payload, _ = self.approve_implementation()

        self.assertEqual(1, code)
        self.assertEqual("injected-journal-finalization-failure", payload["error"]["code"])
        implementation_commit = self.state()["implementation_commit"]
        self.assertIsNotNone(implementation_commit)
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])
        self.assertEqual(implementation_commit, self.git("rev-parse", "HEAD").stdout.strip())

        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(implementation_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assert_clean_status()

    def test_atomic_state_replacement_preserves_complete_old_document_on_failure(self):
        """An interrupted atomic replacement leaves a complete old JSON document."""
        state_file = self.root / "atomic-state.json"
        old_state = {"status": "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", "value": "old"}
        new_state = {"status": "DRAFT_PR_CREATION", "value": "new"}
        state_file.write_text(json.dumps(old_state) + "\n", encoding="utf-8")

        with mock.patch.object(
            os,
            "replace",
            side_effect=OSError("injected atomic replacement failure"),
        ):
            with self.assertRaises(workflow.WorkflowError) as failure:
                workflow._write_json(state_file, new_state)

        self.assertIn("persist", failure.exception.message.lower())
        self.assertEqual(old_state, json.loads(state_file.read_text(encoding="utf-8")))

    def test_recovery_rejects_matching_direct_child_without_transition_marker(self):
        """A matching authoritative-looking commit without a journal is never adopted."""
        self.bootstrap_to_reviewed_implementation()
        target_head = self.state()["target_head"]
        candidate_subject = self.state()["implementation_candidate"]["commit_subject"]
        self.git("add", "-A")
        self.git("reset", "--soft", target_head)
        self.git("commit", "-qm", candidate_subject)
        matching_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertFalse(self.transition_journal_path().exists())

        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertIn("journal", json.dumps(payload).lower())
        self.assertEqual(matching_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"])

    def test_recovery_fails_closed_for_journal_binding_mismatches(self):
        """Acknowledgment, identity, scope, evidence, topology, and result mismatches fail closed."""
        self.bootstrap_to_reviewed_implementation()
        self.assertEqual(0, self.approve_implementation()[0])
        journal_path = self.transition_journal_path()
        original_text = journal_path.read_text(encoding="utf-8")
        original_state = self.state()
        original_head = self.git("rev-parse", "HEAD").stdout.strip()
        mutations = {
            "acknowledgment": lambda value: value["acknowledgment"].update(
                {"confirmation": "wrong-confirmation"}
            ),
            "transition": lambda value: value.update({"from_status": "IMPLEMENTATION"}),
            "candidate identity": lambda value: value["candidate_identity"].update(
                {"candidate_diff_sha256": "0" * 64}
            ),
            "candidate metadata": lambda value: value["implementation_candidate"].update(
                {"candidate_paths": ["unexpected.kt"]}
            ),
            "target and parent": lambda value: value.update(
                {"target_head": "0" * 40, "expected_parent": "0" * 40}
            ),
            "test applicability": lambda value: value.update(
                {"test_implementation_status": "NOT_APPLICABLE"}
            ),
            "scope and evidence": lambda value: value.update(
                {"approved_scope": ["unexpected.kt"], "evidence": {"passed": False}}
            ),
            "subject": lambda value: value.update(
                {"reviewed_commit_subject": "unreviewed subject"}
            ),
            "validation and review": lambda value: value.update(
                {"validation": {"passed": False}, "implementation_review_ready": False}
            ),
            "paths and commits": lambda value: value.update(
                {"candidate_paths": ["unexpected.kt"], "test_commit": "0" * 40}
            ),
            "final result": lambda value: value.update(
                {"status": "finalized", "implementation_commit": "0" * 40}
            ),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                mutated = json.loads(original_text)
                mutate(mutated)
                journal_path.write_text(
                    json.dumps(mutated, indent=2) + "\n", encoding="utf-8"
                )
                code, payload, _ = self.recover_implementation_approval()
                self.assertEqual(1, code)
                self.assertFalse(payload["ok"])
                self.assertEqual(original_head, self.git("rev-parse", "HEAD").stdout.strip())
                self.assertEqual(original_state, self.state())
                journal_path.write_text(original_text, encoding="utf-8")

    def test_recovery_rejects_target_advance_and_dirty_worktree_or_index(self):
        """Target freshness and clean-worktree invariants are recovery preconditions."""
        self.bootstrap_to_reviewed_implementation()
        with self.fail_checked_git_command("add", "injected-before-staging"):
            self.approve_implementation()
        self.assert_transition_journal_matches_state()

        advanced = self.git("commit-tree", "4b825dc642cb6eb9a060e54bf8d69288fbee4904", "-p", self.state()["target_head"], "-m", "target advance").stdout.strip()
        self.git("update-ref", "refs/remotes/origin/main", advanced)
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.git("update-ref", "refs/remotes/origin/main", self.state()["target_head"])

        (self.root / "dirty.txt").write_text("dirty\n", encoding="utf-8")
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        (self.root / "dirty.txt").unlink()
        self.assert_clean_status()

    def test_recovery_never_creates_a_pr_or_advances_another_gate(self):
        """Recovery finalizes only implementation approval and does not publish a PR."""
        self.bootstrap_to_reviewed_implementation()
        with self.fail_checked_git_command("commit", "injected-before-commit"):
            self.approve_implementation()
        self.assert_transition_journal_matches_state()
        code, payload, _ = self.recover_implementation_approval()
        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertNotIn("pull_request", payload)
        self.assertFalse((self.root / ".github" / "draft-pr-created").exists())
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])

    # ---- reconcile-implementation-target (issue #280) ----
    #
    # These tests specify the not-yet-implemented `reconcile-implementation-target`
    # command described by the issue #280 plan. They are expected to fail (with a
    # clear missing-command / missing-behavior error, e.g. an argparse rejection of
    # an unknown subcommand or a KeyError from COMMANDS) until the command is
    # implemented in `scripts/agent_workflow.py`. They exist ahead of that
    # implementation to pin down the required behavior for the Gate 3
    # `recover-implementation-approval` follow-up narrow crash shape: a committed
    # candidate whose journal target has since been superseded by an advanced
    # `origin/<target_base>`.

    def test_reconcile_implementation_target_succeeds_for_committed_candidate_and_advanced_target(self):
        """The exact #276 shape (committed candidate + advanced target) reconciles cleanly."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        original_target = self.state()["target_head"]
        candidate = self.state()["implementation_candidate"]
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit,
            {"downstream.txt": "downstream\n"},
            name="authorized downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        after = self.state()
        self.assertEqual(new_target, after["target_head"])
        self.assertNotEqual(original_target, after["target_head"])
        reconciled_commit = after["implementation_commit"]
        self.assertIsNotNone(reconciled_commit)
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        diff_names = sorted(
            line.strip()
            for line in self.git("diff", "--name-only", f"{new_target}..{reconciled_commit}").stdout.splitlines()
            if line.strip()
        )
        test_paths = json.loads(
            self.transition_journal_path().read_text(encoding="utf-8")
        )["approved_test_boundary"]["paths"]
        self.assertEqual(
            sorted(set(test_paths).union(candidate["candidate_paths"])), diff_names
        )
        self.assertEqual(
            "test\n", self.git("show", f"{reconciled_commit}:src/test/ExampleTest.kt").stdout
        )
        self.assertEqual(
            "implementation\n", self.git("show", f"{reconciled_commit}:src/Example.kt").stdout
        )
        self.assert_clean_status()
        provenance = after["implementation_target_reconciliations"][-1]
        self.assertEqual(candidate_commit, provenance["previous_candidate_commit"])
        self.assertEqual(original_target, provenance["previous_target_head"])
        self.assertEqual(new_target, provenance["new_target_head"])
        self.assertEqual(reconciled_commit, provenance["reconciled_commit"])
        self.assertEqual("owner", provenance["requested_by"])
        self.assertTrue(provenance["requested_at"])

    def test_reconcile_implementation_target_accepts_reordered_legacy_diff_for_committed_journal(self):
        """Legacy tracked-then-untracked candidate diffs reconcile after unified Git reordering."""
        candidate_commit = self.bootstrap_mixed_order_interrupted_candidate(
            "committed-but-not-persisted"
        )
        self.advance_remote_ref_past_commit(
            self.state()["test_commit"], {"downstream.txt": "downstream\n"}
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])

    def test_reconcile_implementation_target_accepts_reordered_legacy_diff_for_pending_journal(self):
        """Pending journals accept an exact candidate despite diff-section serialization order."""
        candidate_commit = self.bootstrap_mixed_order_interrupted_candidate("pending")
        self.advance_remote_ref_past_commit(
            self.state()["test_commit"], {"downstream.txt": "downstream\n"}
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])

    def test_reconcile_implementation_target_rejects_genuine_content_difference_after_reordering(self):
        """Canonical ordering must not mask an interrupted candidate content difference."""
        candidate_commit = self.bootstrap_mixed_order_interrupted_candidate("pending")
        self.advance_remote_ref_past_commit(
            self.state()["test_commit"], {"downstream.txt": "downstream\n"}
        )
        (self.root / "src" / "ZTracked.kt").write_text(
            "tampered implementation\n", encoding="utf-8"
        )
        drifted_commit = self.amend_head_preserving_subject()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("implementation-candidate-mismatch", payload["error"]["code"])
        self.assertEqual(drifted_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_rejects_non_descendant_target(self):
        """An unrelated remote target can never become the reconciliation base."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        before_state = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_leaves_original_target_unchanged_when_not_advanced(self):
        """Without a real strict-descendant advance there is nothing to reconcile."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        before_state = self.state()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertIn(
            payload["error"]["code"],
            {"target-not-advanced", "invalid-git-ancestry", "target-advanced"},
        )
        self.assertEqual(before_state["target_head"], self.state()["target_head"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_rejects_wrong_head(self):
        """A HEAD that has drifted from the journaled candidate commit fails closed."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()
        self.git("commit", "--allow-empty", "-qm", "unexpected local commit")
        diverged_head = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(diverged_head, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_rejects_candidate_and_test_boundary_tampering(self):
        """Content, path, and approved test-boundary tampering of the source journal all fail closed."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        journal_path = self.transition_journal_path()
        original_text = journal_path.read_text(encoding="utf-8")
        before_state = self.state()
        mutations = {
            "content": lambda value: value["candidate_identity"].update(
                {"candidate_diff_sha256": "0" * 64}
            ),
            "paths": lambda value: value["implementation_candidate"].update(
                {"candidate_paths": ["unexpected.kt"]}
            ),
            "test boundary": lambda value: value.update(
                {"approved_scope": ["unexpected.kt"], "test_commit": "0" * 40}
            ),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                mutated = json.loads(original_text)
                mutate(mutated)
                journal_path.write_text(
                    json.dumps(mutated, indent=2) + "\n", encoding="utf-8"
                )

                code, payload, _ = self.reconcile_implementation_target()

                self.assertEqual(1, code)
                self.assertFalse(payload["ok"])
                self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())
                self.assertEqual(before_state, self.state())
                journal_path.write_text(original_text, encoding="utf-8")

    def test_reconcile_implementation_target_rejects_clean_apply_conflict(self):
        """A downstream change that conflicts with the candidate's own paths fails closed."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        before_state = self.state()
        self.advance_remote_ref_past_commit(
            candidate_commit,
            {"src/Example.kt": "conflicting downstream implementation\n"},
            name="authorized conflicting downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertIn(
            payload["error"]["code"],
            {
                "patch-apply-conflict",
                "git-apply-conflict",
                "artifact-validity-undetermined",
                "implementation-candidate-mismatch",
            },
        )
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assert_clean_status()

    def test_reconcile_implementation_target_applies_cleanly_with_unrelated_surrounding_changes(self):
        """Unrelated downstream changes near the candidate paths must not block clean application."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        candidate_paths = self.state()["implementation_candidate"]["candidate_paths"]
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit,
            {
                "src/Unrelated.kt": "unrelated sibling implementation\n",
                "src/test/UnrelatedTest.kt": "unrelated sibling test\n",
                "README.md": "# ChessEcho\n\nunrelated downstream documentation update\n",
            },
            name="authorized unrelated surrounding downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        reconciled_commit = self.state()["implementation_commit"]
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        diff_names = sorted(
            line.strip()
            for line in self.git("diff", "--name-only", f"{new_target}..{reconciled_commit}").stdout.splitlines()
            if line.strip()
        )
        test_paths = json.loads(
            self.transition_journal_path().read_text(encoding="utf-8")
        )["approved_test_boundary"]["paths"]
        self.assertEqual(sorted(set(test_paths).union(candidate_paths)), diff_names)
        self.assertEqual(
            "unrelated sibling implementation\n",
            self.git("show", f"{reconciled_commit}:src/Unrelated.kt").stdout,
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_recovers_crash_after_commit_before_finalization(self):
        """A post-reconciliation-commit persistence failure recovers without a second commit."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )
        original_write_state = workflow._write_state

        def fail_after_new_commit(root, config, issue, state):
            if state.get("status") == "DRAFT_PR_CREATION" and state.get("target_head") == new_target:
                raise workflow.WorkflowError(
                    "injected-post-reconciliation-state-failure",
                    "injected state persistence failure after reconciled commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_after_new_commit):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-reconciliation-state-failure", payload["error"]["code"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_retries_cleanly_after_crash_before_commit(self):
        """A crash before the reconciliation commit retries into exactly one new commit."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )

        with self.fail_checked_git_command("commit", "injected-before-reconciliation-commit"):
            code, payload, _ = self.reconcile_implementation_target()
        self.assertEqual(1, code)
        self.assertEqual("injected-before-reconciliation-commit", payload["error"]["code"])
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        reconciliation_journal = json.loads(
            self.reconciliation_journal_path().read_text(encoding="utf-8")
        )
        self.assertEqual("pending", reconciliation_journal["status"])
        self.assertEqual(new_target, reconciliation_journal["new_target_head"])

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_rejects_tampered_reconciliation_journal(self):
        """A tampered pending reconciliation journal is never trusted for a retry."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        with self.fail_checked_git_command("commit", "injected-before-reconciliation-commit"):
            self.reconcile_implementation_target()
        journal_path = self.reconciliation_journal_path()
        self.assertTrue(
            journal_path.is_file(),
            "reconciliation journal must be durable before Git mutation",
        )
        tampered = json.loads(journal_path.read_text(encoding="utf-8"))
        tampered["new_target_head"] = "0" * 40
        journal_path.write_text(json.dumps(tampered, indent=2) + "\n", encoding="utf-8")
        before_state = self.state()
        before_head = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_rejects_wrong_authorization(self):
        """An incorrect confirmation phrase never authorizes reconciliation."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()

        code, payload, _ = self.reconcile_implementation_target(
            confirm="wrong-confirmation"
        )

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertFalse(self.reconciliation_journal_path().exists())

    def test_reconcile_implementation_target_is_idempotent_on_repeat_invocation(self):
        """Invoking reconciliation again after success is a pure no-op."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )

        first_code, first_payload, _ = self.reconcile_implementation_target()
        self.assertEqual(0, first_code)
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        state_after_first = self.state()

        second_code, second_payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, second_code)
        self.assertEqual("DRAFT_PR_CREATION", second_payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(state_after_first, self.state())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_supports_pending_journal_with_null_authoritative_commit(self):
        """Pending Gate 3 journals with a committed HEAD candidate reconcile successfully."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        before_state = self.state()
        original_target = before_state["target_head"]
        candidate = before_state["implementation_candidate"]
        original_journal = json.loads(self.transition_journal_path().read_text(encoding="utf-8"))
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit,
            {"downstream.txt": "downstream\n"},
            name="authorized downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        after = self.state()
        self.assertEqual(new_target, after["target_head"])
        self.assertNotEqual(original_target, after["target_head"])
        reconciled_commit = after["implementation_commit"]
        self.assertIsNotNone(reconciled_commit)
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        diff_names = sorted(
            line.strip()
            for line in self.git("diff", "--name-only", f"{new_target}..{reconciled_commit}").stdout.splitlines()
            if line.strip()
        )
        test_paths = original_journal["approved_test_boundary"]["paths"]
        self.assertEqual(
            sorted(set(test_paths).union(candidate["candidate_paths"])), diff_names
        )
        self.assertEqual(
            original_journal,
            json.loads(self.transition_journal_path().read_text(encoding="utf-8")),
        )
        provenance = after["implementation_target_reconciliations"][-1]
        self.assertEqual("pending", provenance["source_journal_status"])
        self.assertIsNone(provenance["source_authoritative_commit"])
        self.assertEqual(candidate_commit, provenance["previous_candidate_commit"])
        self.assertEqual(original_target, provenance["previous_target_head"])
        self.assertEqual(new_target, provenance["new_target_head"])
        self.assertEqual(reconciled_commit, provenance["reconciled_commit"])
        self.assert_clean_status()

    def test_reconcile_implementation_target_reapplies_missing_test_boundary(self):
        """A target without the local test commit accepts the approved test and production union."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        before_state = self.state()
        candidate_paths = before_state["implementation_candidate"]["candidate_paths"]
        test_paths = json.loads(
            self.transition_journal_path().read_text(encoding="utf-8")
        )["approved_test_boundary"]["paths"]
        new_target = self.advance_remote_target_with_changes(
            {"downstream.txt": "downstream\n"},
            name="authorized target advance without local tests",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        diff_names = sorted(
            line.strip()
            for line in self.git(
                "diff", "--name-only", f"{new_target}..{reconciled_commit}"
            ).stdout.splitlines()
            if line.strip()
        )
        self.assertEqual(sorted(set(test_paths).union(candidate_paths)), diff_names)
        self.assert_clean_status()

    def test_reconcile_implementation_target_rejects_existing_changed_test_boundary(self):
        """A test path already changed on the advanced target still fails closed."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_target_with_changes(
            {"src/test/ExampleTest.kt": "conflicting downstream test\n"},
            name="authorized target advance with changed test boundary",
        )

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("patch-apply-conflict", payload["error"]["code"])
        recon = json.loads(
            self.reconciliation_journal_path().read_text(encoding="utf-8")
        )
        self.assertEqual("pending", recon["status"])
        candidate_paths = recon["implementation_candidate"]["candidate_paths"]
        test_paths = recon["approved_test_boundary"]["paths"]
        self.git("reset", "--hard", new_target)
        self.git("checkout", candidate_commit, "--", *test_paths, *candidate_paths)
        self.git("commit", "-qm", recon["reviewed_commit_subject"])
        materialized_commit = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("approved-test-boundary-mismatch", payload["error"]["code"])
        self.assertEqual(materialized_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_rejects_path_outside_approved_union(self):
        """A materialized reconciliation with an extra path still fails closed."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_target_with_changes(
            {"downstream.txt": "downstream\n"},
            name="authorized target advance without local tests",
        )
        original_write_json = workflow._write_json
        committed_write_seen = False

        def fail_reconciled_commit_persistence(path, payload):
            nonlocal committed_write_seen
            if payload.get("status") == "committed" and not committed_write_seen:
                committed_write_seen = True
                raise workflow.WorkflowError(
                    "injected-reconciled-commit-persistence-failure",
                    "injected reconciliation commit persistence failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_reconciled_commit_persistence):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual(
            "injected-reconciled-commit-persistence-failure", payload["error"]["code"]
        )
        self.assertNotEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())
        (self.root / "unexpected.txt").write_text("unexpected\n", encoding="utf-8")
        drifted_commit = self.amend_head_preserving_subject()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("implementation-scope-drift", payload["error"]["code"])
        self.assertEqual(drifted_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_resumes_materialized_missing_test_boundary(self):
        """A pending materialized union commit is finalized without a duplicate commit."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_target_with_changes(
            {"downstream.txt": "downstream\n"},
            name="authorized target advance without local tests",
        )
        original_write_json = workflow._write_json
        committed_write_seen = False

        def fail_reconciled_commit_persistence(path, payload):
            nonlocal committed_write_seen
            if payload.get("status") == "committed" and not committed_write_seen:
                committed_write_seen = True
                raise workflow.WorkflowError(
                    "injected-reconciled-commit-persistence-failure",
                    "injected reconciliation commit persistence failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_reconciled_commit_persistence):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual(
            "injected-reconciled-commit-persistence-failure", payload["error"]["code"]
        )
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        pending_recon = json.loads(
            self.reconciliation_journal_path().read_text(encoding="utf-8")
        )
        self.assertEqual("pending", pending_recon["status"])
        self.assertIsNone(pending_recon["reconciled_commit"])

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))
        self.assert_clean_status()

    def test_reconcile_implementation_target_pending_journal_rejects_wrong_head(self):
        """Pending-journal reconciliation fails closed when HEAD is not the interrupted candidate."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()
        self.git("reset", "--hard", before_state["target_head"])
        wrong_head = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(wrong_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertFalse(self.reconciliation_journal_path().exists())

    def test_reconcile_implementation_target_pending_journal_rejects_non_direct_child(self):
        """Pending-journal reconciliation requires HEAD to be a direct child of the old target."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()
        self.git("commit", "--allow-empty", "-qm", "extra local commit")
        non_direct_head = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(non_direct_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertFalse(self.reconciliation_journal_path().exists())

    def test_reconcile_implementation_target_pending_journal_rejects_candidate_content_path_or_mode_drift(self):
        """Pending-journal reconciliation rejects interrupted candidate content/path/mode drift."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()
        journal_before = self.transition_journal_path().read_text(encoding="utf-8")
        file_path = self.root / "src" / "Example.kt"

        mutations = {
            "content": lambda: file_path.write_text("tampered implementation\n", encoding="utf-8"),
            "path": lambda: (self.root / "src" / "Unexpected.kt").write_text(
                "unexpected file\n", encoding="utf-8"
            ),
            "mode": lambda: os.chmod(
                file_path,
                os.stat(file_path).st_mode | stat.S_IXUSR,
            ),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                self.git("reset", "--hard", candidate_commit)
                if (self.root / "src" / "Unexpected.kt").exists():
                    (self.root / "src" / "Unexpected.kt").unlink()
                mutate()
                drifted_head = self.amend_head_preserving_subject()
                code, payload, _ = self.reconcile_implementation_target()
                self.assertEqual(1, code)
                self.assertFalse(payload["ok"])
                self.assertEqual(before_state, self.state())
                self.assertEqual(drifted_head, self.git("rev-parse", "HEAD").stdout.strip())
                self.assertEqual(
                    journal_before,
                    self.transition_journal_path().read_text(encoding="utf-8"),
                )
                self.assertFalse(self.reconciliation_journal_path().exists())

    def test_reconcile_implementation_target_pending_journal_rejects_test_boundary_mismatch(self):
        """Pending-journal reconciliation rejects interrupted commits that alter approved test boundary."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "tampered test boundary\n", encoding="utf-8"
        )
        drifted_head = self.amend_head_preserving_subject()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(drifted_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertFalse(self.reconciliation_journal_path().exists())

    def test_reconcile_implementation_target_pending_journal_rejects_tampered_authorization(self):
        """Pending-journal reconciliation requires untampered source authorization."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        journal_path = self.transition_journal_path()
        original = json.loads(journal_path.read_text(encoding="utf-8"))
        tampered = json.loads(journal_path.read_text(encoding="utf-8"))
        tampered["acknowledgment"]["confirmation"] = "tampered-authorization"
        journal_path.write_text(json.dumps(tampered, indent=2) + "\n", encoding="utf-8")
        before_state = self.state()
        before_head = self.git("rev-parse", "HEAD").stdout.strip()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertFalse(self.reconciliation_journal_path().exists())
        journal_path.write_text(json.dumps(original, indent=2) + "\n", encoding="utf-8")

    def test_reconcile_implementation_target_pending_journal_rejects_non_descendant_target(self):
        """Pending-journal reconciliation rejects unrelated advanced targets."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        before_state = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_pending_journal_recovers_when_reconciled_commit_persistence_crashes(self):
        """Crash after creating reconciled commit but before commit persistence retries idempotently."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )
        recon_path = self.reconciliation_journal_path()
        original_write_json = workflow._write_json
        committed_write_seen = False

        def fail_reconciled_commit_persistence(path, payload):
            nonlocal committed_write_seen
            if (
                payload.get("status") == "committed"
                and not committed_write_seen
            ):
                committed_write_seen = True
                raise workflow.WorkflowError(
                    "injected-reconciled-commit-persistence-failure",
                    "injected reconciliation commit persistence failure",
                )
            return original_write_json(path, payload)

        with mock.patch.object(workflow, "_write_json", side_effect=fail_reconciled_commit_persistence):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual(
            "injected-reconciled-commit-persistence-failure", payload["error"]["code"]
        )
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        pending_recon = json.loads(recon_path.read_text(encoding="utf-8"))
        self.assertEqual("pending", pending_recon["status"])
        self.assertIsNone(pending_recon["reconciled_commit"])

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(new_target, self.git("rev-parse", f"{reconciled_commit}^").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))
        self.assert_clean_status()

    def test_reconcile_implementation_target_pending_journal_recovers_when_provenance_persistence_crashes(self):
        """Crash after reconciliation commit persistence but before provenance state write is recoverable."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )
        original_write_state = workflow._write_state

        def fail_provenance_state_write(root, config, issue, state):
            if (
                state.get("status") == "DRAFT_PR_CREATION"
                and state.get("target_head") == new_target
            ):
                raise workflow.WorkflowError(
                    "injected-provenance-state-write-failure",
                    "injected provenance persistence failure",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_provenance_state_write):
            code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertEqual("injected-provenance-state-write-failure", payload["error"]["code"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(candidate_commit, reconciled_commit)
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        committed_recon = json.loads(self.reconciliation_journal_path().read_text(encoding="utf-8"))
        self.assertEqual("committed", committed_recon["status"])
        self.assertEqual(reconciled_commit, committed_recon["reconciled_commit"])

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))
        self.assert_clean_status()

    def test_reconcile_implementation_target_pending_journal_is_idempotent_after_success(self):
        """Repeated pending-journal reconciliation invocations are verified no-ops after success."""
        candidate_commit = self.bootstrap_to_pending_journal_committed_candidate_pending_target_advance()
        new_target = self.advance_remote_ref_past_commit(
            candidate_commit, {"downstream.txt": "downstream\n"}
        )

        first_code, first_payload, _ = self.reconcile_implementation_target()
        self.assertEqual(0, first_code)
        self.assertEqual("DRAFT_PR_CREATION", first_payload["status"])
        reconciled_commit = self.git("rev-parse", "HEAD").stdout.strip()
        state_after_first = self.state()
        recon_after_first = json.loads(
            self.reconciliation_journal_path().read_text(encoding="utf-8")
        )

        second_code, second_payload, _ = self.reconcile_implementation_target()

        self.assertEqual(0, second_code)
        self.assertEqual("DRAFT_PR_CREATION", second_payload["status"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(state_after_first, self.state())
        self.assertEqual(
            recon_after_first,
            json.loads(self.reconciliation_journal_path().read_text(encoding="utf-8")),
        )
        self.assertEqual(
            1,
            int(self.git("rev-list", "--count", f"{new_target}..{reconciled_commit}").stdout.strip()),
        )
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))
        self.assert_clean_status()

    # ---- reconcile-implementation-target re-anchor (issue #295) ----
    #
    # `reconcile-implementation-target --confirm implementation_target_reconciled`
    # requires the freshly resolved target to exactly equal the reconciliation
    # journal's recorded `new_target_head`. These tests specify the required
    # `--confirm implementation_target_reconciliation_reanchored` recovery for
    # the narrow case where `origin/<target_base>` advances again *after* a
    # reconciliation is materialized but *before* it is finalized -- the exact
    # shape discovered while recovering issue #276. They are expected to fail
    # until that capability is implemented.

    def test_reconcile_implementation_target_reanchor_advances_past_materialized_commit(self):
        """[A] A pure descendant advance re-anchors the materialized reconciliation cleanly."""
        candidate_commit, first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        candidate = self.state()["implementation_candidate"]
        second_target = self.advance_remote_ref_from(
            first_target,
            {"downstream2.txt": "downstream2\n"},
            name="second authorized downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        after = self.state()
        self.assertEqual(second_target, after["target_head"])
        reanchored_commit = after["implementation_commit"]
        self.assertIsNotNone(reanchored_commit)
        self.assertNotEqual(reconciled_commit, reanchored_commit)
        self.assertNotEqual(candidate_commit, reanchored_commit)
        self.assertEqual(reanchored_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            second_target, self.git("rev-parse", f"{reanchored_commit}^").stdout.strip()
        )
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{second_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        diff_names = sorted(
            line.strip()
            for line in self.git(
                "diff", "--name-only", f"{second_target}..{reanchored_commit}"
            ).stdout.splitlines()
            if line.strip()
        )
        test_paths = json.loads(
            self.transition_journal_path().read_text(encoding="utf-8")
        )["approved_test_boundary"]["paths"]
        self.assertEqual(
            sorted(set(test_paths).union(candidate["candidate_paths"])), diff_names
        )
        self.assertEqual(
            "implementation\n", self.git("show", f"{reanchored_commit}:src/Example.kt").stdout
        )
        self.assert_clean_status()

        recon = json.loads(self.reconciliation_journal_path().read_text(encoding="utf-8"))
        self.assertEqual(second_target, recon["new_target_head"])
        self.assertEqual(reanchored_commit, recon["reconciled_commit"])
        self.assertEqual("finalized", recon["status"])
        self.assertEqual(1, len(recon["reanchors"]))
        reanchor_entry = recon["reanchors"][0]
        self.assertEqual(first_target, reanchor_entry["from_new_target_head"])
        self.assertEqual(second_target, reanchor_entry["to_new_target_head"])
        self.assertEqual(reconciled_commit, reanchor_entry["from_reconciled_commit"])
        self.assertEqual(reanchored_commit, reanchor_entry["to_reconciled_commit"])
        self.assertEqual("finalized", reanchor_entry["status"])
        self.assertEqual("owner", reanchor_entry["requested_by"])
        # The original Gate 3 evidence and authorization are untouched.
        self.assertEqual(candidate_commit, recon["previous_candidate_commit"])
        self.assertEqual(
            "implementation_target_reconciled", recon["acknowledgment"]["confirmation"]
        )
        provenance = after["implementation_target_reconciliations"][-1]
        self.assertEqual(second_target, provenance["new_target_head"])
        self.assertEqual(reanchored_commit, provenance["reconciled_commit"])

    def test_reconcile_implementation_target_reanchor_creates_no_duplicate_commit_when_unnecessary(self):
        """[A] Re-anchoring never leaves two authoritative commits recorded as final."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        second_target = self.advance_remote_ref_from(
            reconciled_commit, {"downstream2.txt": "downstream2\n"}
        )

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, code)
        reanchored_commit = self.state()["implementation_commit"]
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{second_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        # Exactly one implementation-target-reconciliation provenance entry:
        # the re-anchor updates the existing entry in place, it does not add
        # a second one for the same underlying candidate.
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))

    def test_reconcile_implementation_target_reanchor_retries_cleanly_after_crash_before_commit(self):
        """[B] A crash before the re-anchor commit retries into exactly one new commit."""
        _candidate_commit, first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        second_target = self.advance_remote_ref_from(
            reconciled_commit, {"downstream2.txt": "downstream2\n"}
        )

        with self.fail_checked_git_command("commit", "injected-before-reanchor-commit"):
            code, payload, _ = self.reconcile_implementation_target_reanchor()
        self.assertEqual(1, code)
        self.assertEqual("injected-before-reanchor-commit", payload["error"]["code"])
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        recon = json.loads(self.reconciliation_journal_path().read_text(encoding="utf-8"))
        self.assertEqual(1, len(recon["reanchors"]))
        self.assertEqual("pending", recon["reanchors"][0]["status"])
        self.assertEqual(first_target, recon["reanchors"][0]["from_new_target_head"])
        self.assertEqual(second_target, recon["reanchors"][0]["to_new_target_head"])

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        reanchored_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(reconciled_commit, reanchored_commit)
        self.assertEqual(
            second_target, self.git("rev-parse", f"{reanchored_commit}^").stdout.strip()
        )
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{second_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_reanchor_recovers_crash_after_commit_before_finalization(self):
        """[B] A crash after the re-anchor commit recovers without a second commit."""
        _candidate_commit, first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        second_target = self.advance_remote_ref_from(
            reconciled_commit, {"downstream2.txt": "downstream2\n"}
        )
        original_write_state = workflow._write_state

        def fail_after_reanchor_commit(root, config, issue, state):
            if state.get("status") == "DRAFT_PR_CREATION" and state.get("target_head") == second_target:
                raise workflow.WorkflowError(
                    "injected-post-reanchor-state-failure",
                    "injected state persistence failure after reanchored commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_after_reanchor_commit):
            code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-reanchor-state-failure", payload["error"]["code"])
        reanchored_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.assertNotEqual(reconciled_commit, reanchored_commit)
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, code)
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        self.assertEqual(reanchored_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{second_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        self.assertEqual(1, len(self.state()["implementation_target_reconciliations"]))
        self.assert_clean_status()

    def test_reconcile_implementation_target_reanchor_is_idempotent_after_success(self):
        """[B] Invoking the re-anchor again after success is a pure verified no-op."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        second_target = self.advance_remote_ref_from(
            reconciled_commit, {"downstream2.txt": "downstream2\n"}
        )

        first_code, _first_payload, _ = self.reconcile_implementation_target_reanchor()
        self.assertEqual(0, first_code)
        reanchored_commit = self.git("rev-parse", "HEAD").stdout.strip()
        state_after_first = self.state()

        second_code, second_payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, second_code)
        self.assertEqual("DRAFT_PR_CREATION", second_payload["status"])
        self.assertEqual(reanchored_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(state_after_first, self.state())
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{second_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        self.assert_clean_status()

    def test_reconcile_implementation_target_reanchor_rejects_non_descendant_target(self):
        """[C] An unrelated remote target can never become the re-anchor base."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        before_state = self.state()
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_reanchor_rejects_unadvanced_target(self):
        """[C] Without a real further advance there is nothing to re-anchor."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        before_state = self.state()

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("target-not-advanced", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_reanchor_rejects_tampered_materialized_reconciliation(self):
        """[D] A reconciliation journal whose recorded commit/content is tampered fails closed."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        self.advance_remote_ref_from(reconciled_commit, {"downstream2.txt": "downstream2\n"})
        journal_path = self.reconciliation_journal_path()
        original_text = journal_path.read_text(encoding="utf-8")
        before_state = self.state()
        mutations = {
            "reconciled commit identity": lambda value: value.update(
                {"reconciled_commit": "0" * 40}
            ),
            "candidate content": lambda value: value["implementation_candidate"].update(
                {"candidate_diff": value["implementation_candidate"]["candidate_diff"].replace(
                    "implementation", "tampered"
                )}
            ),
            "candidate paths": lambda value: value["implementation_candidate"].update(
                {"candidate_paths": ["unexpected.kt"]}
            ),
        }

        for name, mutate in mutations.items():
            with self.subTest(name=name):
                mutated = json.loads(original_text)
                mutate(mutated)
                journal_path.write_text(
                    json.dumps(mutated, indent=2) + "\n", encoding="utf-8"
                )

                code, payload, _ = self.reconcile_implementation_target_reanchor()

                self.assertEqual(1, code)
                self.assertFalse(payload["ok"])
                self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
                self.assertEqual(before_state, self.state())
                journal_path.write_text(original_text, encoding="utf-8")

    def test_reconcile_implementation_target_reanchor_rejects_conflicting_target_path_drift(self):
        """[E] A downstream change conflicting with an approved candidate path fails closed."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        before_state = self.state()
        self.advance_remote_ref_from(
            reconciled_commit,
            {"src/Example.kt": "conflicting downstream implementation\n"},
            name="authorized conflicting downstream merge",
        )

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertIn(
            payload["error"]["code"],
            {
                "patch-apply-conflict",
                "git-apply-conflict",
                "artifact-validity-undetermined",
                "implementation-candidate-mismatch",
            },
        )
        self.assertEqual(before_state, self.state())
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())
        self.assert_clean_status()

    def test_reconcile_implementation_target_reanchor_rejects_wrong_authorization(self):
        """[E] An incorrect confirmation phrase never authorizes the base or re-anchor transition."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        self.advance_remote_ref_from(reconciled_commit, {"downstream2.txt": "downstream2\n"})
        before_state = self.state()

        code, payload, _ = self.reconcile_implementation_target(
            confirm="not-a-real-confirmation"
        )

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("reconciliation-confirmation-mismatch", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_base_confirmation_still_rejects_further_advance(self):
        """The unchanged base confirmation never silently re-anchors a further advance."""
        _candidate_commit, _first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        self.advance_remote_ref_from(reconciled_commit, {"downstream2.txt": "downstream2\n"})
        before_state = self.state()

        code, payload, _ = self.reconcile_implementation_target()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("reconciliation-journal-mismatch", payload["error"]["code"])
        self.assertEqual(before_state, self.state())
        self.assertEqual(reconciled_commit, self.git("rev-parse", "HEAD").stdout.strip())

    def test_reconcile_implementation_target_reanchor_synthetic_276_shape(self):
        """[F] Synthetic fixture modeled on the real #276 shape; no #276 artifact is touched.

        This mirrors the real discovery narrative -- a committed-but-not-
        persisted candidate materialized against a first advanced target,
        then an unrelated further merge (modeling PR #294's #293 fix landing)
        advances `origin/<target_base>` again before finalization -- using
        only synthetic, in-test Git history and workflow state for issue
        %d. It never reads, writes, or references
        `.agent-workflow/runs/issue-276/`.
        """ % ISSUE
        candidate_commit, first_target, reconciled_commit = (
            self.bootstrap_to_materialized_reconciliation_pending_further_advance()
        )
        self.assertFalse(
            (self.root / ".agent-workflow" / "runs" / "issue-276").exists(),
            "synthetic #295 fixture must never create or touch issue-276 artifacts",
        )
        synthetic_further_target = self.advance_remote_ref_from(
            reconciled_commit,
            {"unrelated-infra-fix.txt": "unrelated infra fix landed after materialization\n"},
            name="synthetic unrelated infra fix merge (models PR #294)",
        )

        code, payload, _ = self.reconcile_implementation_target_reanchor()

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("DRAFT_PR_CREATION", payload["status"])
        after = self.state()
        self.assertEqual(synthetic_further_target, after["target_head"])
        reanchored_commit = after["implementation_commit"]
        self.assertEqual(
            synthetic_further_target,
            self.git("rev-parse", f"{reanchored_commit}^").stdout.strip(),
        )
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list", "--count", f"{synthetic_further_target}..{reanchored_commit}"
                ).stdout.strip()
            ),
        )
        self.assertNotEqual(candidate_commit, reanchored_commit)
        self.assertNotEqual(reconciled_commit, reanchored_commit)
        self.assertFalse(
            (self.root / ".agent-workflow" / "runs" / "issue-276").exists(),
            "synthetic #295 fixture must never create or touch issue-276 artifacts",
        )
        self.assert_clean_status()

    def test_recover_implementation_approval_still_fails_closed_when_target_advanced_past_candidate(self):
        """Existing narrow recovery is unchanged: it never reconciles an advanced target itself."""
        candidate_commit = self.bootstrap_to_committed_candidate_pending_target_advance()
        self.advance_remote_ref_past_commit(candidate_commit, {"downstream.txt": "downstream\n"})
        before_state = self.state()

        code, payload, _ = self.recover_implementation_approval()

        self.assertEqual(1, code)
        self.assertFalse(payload["ok"])
        self.assertEqual("target-advanced", payload["error"]["code"])
        self.assertEqual(
            "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", self.state()["status"]
        )
        self.assertEqual(before_state, self.state())
        self.assertEqual(candidate_commit, self.git("rev-parse", "HEAD").stdout.strip())

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

    def test_generated_pr_body_is_evidence_grounded_and_validator_approved(self):
        """The workflow generator renders publication prose from recorded evidence."""
        self.bootstrap_to_draft_pr_creation()

        body_path = workflow._generate_pr_body(
            self.root, workflow._load_config(self.root), ISSUE, self.state()
        )

        body = body_path.read_text(encoding="utf-8")
        self.assertEqual(
            ["What", "Why", "Testing"],
            re.findall(r"^##\s+(.+?)\s*$", body, flags=re.MULTILINE),
        )
        self.assertIn("src/Example.kt", body)
        self.assertIn("workflow-tooling", body)
        self.assertIn("workflow-check", body)
        self.assertIn("scenario", body.lower())
        workflow._validate_pr_body(body_path)

    def test_generated_pr_body_fails_closed_without_successful_validation(self):
        """Missing workflow validation cannot be replaced by generic success prose."""
        self.bootstrap_to_draft_pr_creation()
        state = self.state()
        state["validation"] = None

        with self.assertRaisesRegex(workflow.WorkflowError, "validation"):
            workflow._generate_pr_body(
                self.root, workflow._load_config(self.root), ISSUE, state
            )

    def test_create_draft_pr_publishes_generated_body_not_external_body_file(self):
        """A valid external compatibility body cannot control publication content."""
        self.bootstrap_to_draft_pr_creation()
        external_body = self.write_artifact(
            "misleading-pr-body.md",
            "## What\nMisleading external change.\n\n"
            "## Why\nMisleading external reason.\n\n"
            "## Testing\nMeaningful but external scenario prose.\n",
        )
        commands = []

        def capture_github(command, limits, cwd, code, context, env=None):
            if command[:3] == ["gh", "pr", "create"]:
                commands.append(command)
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": "https://example.test/owner/repo/pull/321\n",
                    "stderr_text": "",
                }
            if command[:3] == ["gh", "pr", "view"]:
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": json.dumps(
                        {
                            "number": 321,
                            "headRefName": "workflow-branch",
                            "headRefOid": self.state()["implementation_commit"],
                            "headRepository": {"nameWithOwner": "owner/repo"},
                            "baseRefName": "main",
                            "state": "OPEN",
                            "isDraft": True,
                            "url": "https://example.test/owner/repo/pull/321",
                        }
                    ),
                    "stderr_text": "",
                }
            return original(command, limits, cwd, code, context, env=env)

        original = workflow._run_checked
        with mock.patch.object(workflow, "_run_checked", side_effect=capture_github):
            code, payload, _ = self.run_cli(
                "create-draft-pr",
                str(ISSUE),
                "--title",
                "Issue 321",
                "--body-file",
                str(external_body),
            )

        self.assertEqual(0, code, payload)
        self.assertEqual(1, len(commands))
        published_body = pathlib.Path(
            commands[0][commands[0].index("--body-file") + 1]
        )
        self.assertNotEqual(external_body.resolve(), published_body.resolve())
        self.assertNotIn("Misleading external change", published_body.read_text(encoding="utf-8"))
        self.assertEqual(
            str(published_body.resolve().relative_to(self.root.resolve())),
            self.state()["draft_pr"]["body_file"],
        )
        workflow._validate_pr_body(published_body)

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
        (self.root / "src").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "Example.kt").write_text("historical implementation\n", encoding="utf-8")
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "historical implementation")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
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
        self.assertEqual("M src/Example.kt", self.git("status", "--porcelain", "--untracked-files=all").stdout.strip())
        self.assertEqual(1, len(reopened["test_reopenings"]))
        reopening = reopened["test_reopenings"][0]
        self.assertTrue(reopening["active"])
        self.assertEqual("approved-test-fixture-defect", reopening["reason"])
        self.assertEqual("reopen-tests", reopening["initiated_by"])
        self.assertEqual(previous_test_commit, reopening["previous_test_commit"])
        self.assertEqual(previous_test_approval, reopening["previous_test_approval"])

        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "from pathlib import Path\n"
            "assert Path('src/Example.kt').read_text() == 'implementation candidate\\n', "
            "'expected failure'\n",
            encoding="utf-8",
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
                "%s -c \"exec(compile(open('src/test/ExampleTest.kt').read(), "
                "'src/test/ExampleTest.kt', 'exec'))\"" % sys.executable,
                "--failure-contains",
                "expected failure",
            )[0],
        )
        self.assertEqual("TEST_REVIEW", self.state()["status"])
        self.assertEqual(corrected_candidate, self.state()["test_commit"])
        self.assertEqual(corrected_candidate, self.state()["test_reopenings"][0]["corrected_test_candidate"])
        recorded_failure = self.state()["test_failure"]
        self.assertEqual(previous_test_commit, recorded_failure["result"]["previous_test_commit"])
        self.assertEqual(corrected_candidate, recorded_failure["result"]["corrected_test_commit"])
        fixture_manifest = recorded_failure["result"]["fixtures"]
        self.assertEqual(["src/test/ExampleTest.kt"], [item["path"] for item in fixture_manifest])
        self.assertEqual([corrected_candidate], [item["commit"] for item in fixture_manifest])
        self.assertRegex(fixture_manifest[0]["blob"], r"^[0-9a-f]{40}$")
        self.assertEqual(
            fixture_manifest,
            recorded_failure["result"]["before"]["fixtures"],
        )
        self.assertEqual(
            fixture_manifest,
            recorded_failure["result"]["after"]["fixtures"],
        )
        self.assertEqual("nonzero-exit", recorded_failure["result"]["before"]["outcome"])
        self.assertEqual("success", recorded_failure["result"]["after"]["outcome"])
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
        self.assertEqual("M src/Example.kt", self.git("status", "--porcelain", "--untracked-files=all").stdout.strip())

    def test_submit_tests_reopened_rejects_synthetic_failure_marker(self):
        """A marker command that always exits nonzero cannot pass reopened evidence."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation candidate\n", encoding="utf-8")

        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "corrected test\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "correct test fixture")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"import sys; sys.stderr.write('expected failure'); sys.exit(1)\""
            % sys.executable,
            "--failure-contains",
            "expected failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("test-did-not-pass-after-correction", payload["error"]["code"])
        state = self.state()
        self.assertEqual("TEST_IMPLEMENTATION", state["status"])
        self.assertIsNone(state["test_commit"])

    def test_submit_tests_reopened_requires_genuine_pre_correction_failure(self):
        """A command that never fails cannot be accepted as pre-correction evidence."""
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text("implementation candidate\n", encoding="utf-8")

        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "corrected test\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "correct test fixture")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"import sys; sys.stderr.write('expected failure'); sys.exit(0)\""
            % sys.executable,
            "--failure-contains",
            "expected failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("test-did-not-fail-before-correction", payload["error"]["code"])
        state = self.state()
        self.assertEqual("TEST_IMPLEMENTATION", state["status"])
        self.assertIsNone(state["test_commit"])

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

    def _seed_reconciled_reopening(self):
        """Create an active fixture reopening with a recorded target reanchor."""
        self.bootstrap_to_implementation()
        prior = self.state()
        previous_target = prior["target_head"]
        current_target = self.advance_remote_ref_from(
            previous_target,
            {"reconciliation-marker.txt": "target reconciled\n"},
            name="advance reconciliation target",
        )
        state = dict(prior)
        state["target_head"] = current_target
        state["base_head"] = current_target
        state["target_reanchors"] = [
            {
                "previous_target_head": previous_target,
                "new_target_head": current_target,
                "target_base": "main",
                "remote_ref": "origin/main",
                "requested_by": "owner",
                "requested_at": "2026-09-21T00:00:00+00:00",
                "validated_artifacts": ["plan", "plan_review", "test_report", "test_review"],
                "target_drift": {
                    "condition": "target-drift",
                    "product_intent": "preserved",
                    "repository_realization": "stale",
                    "disposition": "reanchor",
                    "transition": "reanchor-target",
                    "revision_class": None,
                },
            }
        ]
        self.write_state(state)
        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        return prior, current_target

    def _reclassify_reconciled_reopening(self):
        prior, current_target = self._seed_reconciled_reopening()
        code, payload, _ = self.run_cli(
            "reclassify-test-reopening",
            str(ISSUE),
            "--semantic",
            "approved-contract-revision",
            "--by",
            "owner",
            "--confirm",
            "contract_revision_approved",
        )
        self.assertEqual(0, code, payload)
        return prior, current_target

    def test_contract_revision_reclassification_accepts_expected_red_test_boundary(self):
        """A reconciled replacement contract remains RED until implementation."""
        prior, current_target = self._reclassify_reconciled_reopening()
        state = self.state()
        reopening = state["test_reopenings"][-1]
        self.assertEqual("approved-test-fixture-defect", reopening["reason"])
        self.assertEqual("approved-contract-revision", reopening["effective_semantic"])
        self.assertEqual(prior["test_commit"], reopening["previous_test_commit"])
        self.assertEqual(current_target, state["target_head"])

        test_path = self.root / "src" / "test" / "ContractReplacementTest.py"
        test_path.write_text(
            "from pathlib import Path\n"
            "assert Path('src/Example.kt').read_text() == 'contract behavior\\n'\n",
            encoding="utf-8",
        )
        self.git("add", "src/test/ContractReplacementTest.py")
        self.git("commit", "-qm", "add contract replacement test")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"exec(compile(open('src/test/ContractReplacementTest.py').read(), "
            "'src/test/ContractReplacementTest.py', 'exec'))\"" % sys.executable,
            "--failure-contains",
            "AssertionError",
        )
        self.assertEqual(0, code, payload)
        self.assertEqual("TEST_REVIEW", self.state()["status"])

    def test_contract_revision_reclassification_requires_bound_reconciliation(self):
        """The semantic change fails closed without valid target evidence."""
        self.bootstrap_to_implementation()
        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )

        code, payload, _ = self.run_cli(
            "reclassify-test-reopening",
            str(ISSUE),
            "--semantic",
            "approved-contract-revision",
            "--by",
            "owner",
            "--confirm",
            "contract_revision_approved",
        )
        self.assertEqual(1, code)
        self.assertEqual("missing-target-reconciliation", payload["error"]["code"])
        self.assertEqual("TEST_IMPLEMENTATION", self.state()["status"])

    def test_contract_revision_reclassification_preserves_fixture_provenance(self):
        """Reclassification records a new semantic without rewriting approval history."""
        prior, _current_target = self._reclassify_reconciled_reopening()
        reopening = self.state()["test_reopenings"][-1]
        self.assertEqual(prior["test_commit"], reopening["previous_test_commit"])
        self.assertEqual(prior["approvals"]["tests"], reopening["previous_test_approval"])
        self.assertEqual("approved-test-fixture-defect", reopening["reason"])
        self.assertEqual("approved-contract-revision", reopening["effective_semantic"])
        self.assertEqual(
            "approved-test-fixture-defect",
            reopening["semantic_reclassification"]["from"],
        )
        self.assertEqual(
            "approved-contract-revision",
            reopening["semantic_reclassification"]["to"],
        )
        self.assertEqual(1, len(self.state()["test_reopenings"]))

    def test_contract_revision_replacement_rejects_out_of_scope_changes(self):
        """Replacement contracts retain the test-only approved-scope boundary."""
        self._reclassify_reconciled_reopening()
        (self.root / "src" / "Example.kt").write_text(
            "production change\n", encoding="utf-8"
        )
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "change production during test stage")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"import sys; sys.exit(1)\"" % sys.executable,
            "--failure-contains",
            "expected failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("test-scope-drift", payload["error"]["code"])

    def test_contract_revision_replacement_rejects_synthetic_failure_marker(self):
        """Expected-RED evidence must execute the replacement test, not a marker."""
        self._reclassify_reconciled_reopening()
        test_path = self.root / "src" / "test" / "ContractReplacementTest.py"
        test_path.write_text(
            "raise AssertionError('expected contract failure')\n", encoding="utf-8"
        )
        self.git("add", "src/test/ContractReplacementTest.py")
        self.git("commit", "-qm", "add contract replacement test")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"import sys; sys.stderr.write('expected contract failure'); "
            "sys.exit(1)\"" % sys.executable,
            "--failure-contains",
            "expected contract failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("synthetic-test-failure", payload["error"]["code"])

    def test_fixture_repair_still_requires_green_after_reclassification_support(self):
        """Fixture repair remains on the existing RED-to-GREEN verifier."""
        self.bootstrap_to_implementation()
        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "corrected fixture\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "correct test fixture")

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--failure-command",
            "%s -c \"import sys; sys.stderr.write('fixture failure'); sys.exit(1)\""
            % sys.executable,
            "--failure-contains",
            "fixture failure",
        )
        self.assertEqual(1, code)
        self.assertEqual("test-did-not-pass-after-correction", payload["error"]["code"])

    def test_plan_revision_request_returns_to_planning_with_audit_history(self):
        self.bootstrap_to_test_implementation()
        prior = self.state()

        code, payload, _ = self.run_cli(
            "request-plan-revision",
            str(ISSUE),
            "--by",
            "test-implementer",
            "--reason-code",
            "approved-plan-defect",
            "--reason",
            "The approved scope omits the regression test required by the implementation.",
        )

        self.assertEqual(0, code)
        self.assertEqual("PLANNING", payload["status"])
        revised = self.state()
        self.assertEqual("PLANNING", revised["status"])
        self.assertIsNone(revised["approved_scope"])
        self.assertIsNone(revised["approvals"]["plan"])
        request = revised["plan_revision_requests"][0]
        self.assertEqual(prior["artifacts"]["plan"], request["prior_plan"])
        self.assertEqual("test-implementer", request["requested_by"])
        self.assertEqual("approved-plan-defect", request["reason_code"])
        self.assertIn("omits the regression test", request["reason"])
        self.assertEqual(prior["approved_scope"], request["prior_scope"])
        self.assertEqual("TEST_IMPLEMENTATION", request["from_status"])
        self.assertIn("plan", revised["artifacts"])
        self.assertIsNone(revised["test_commit"])
        self.assertIsNone(revised["validation"])

    def test_plan_revision_abandons_active_reopening_and_archives_its_journal(self):
        """A revised plan retires recovery state without losing its audit trail."""
        self.bootstrap_to_implementation()
        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        prior_reopening = dict(self.state()["test_reopenings"][-1])
        journal_path = self.test_transition_journal_path()
        journal_before = journal_path.read_text(encoding="utf-8")

        code, payload, _ = self.run_cli(
            "request-plan-revision",
            str(ISSUE),
            "--by",
            "test-implementer",
            "--reason-code",
            "approved-plan-defect",
            "--reason",
            "The approved recovery path no longer matches the revised plan.",
        )

        self.assertEqual(0, code)
        self.assertEqual("PLANNING", payload["status"])
        revised = self.state()
        reopening = revised["test_reopenings"][-1]
        self.assertFalse(reopening["active"])
        self.assertEqual("request-plan-revision", reopening["abandoned_by"])
        self.assertEqual(prior_reopening["previous_test_commit"], reopening["previous_test_commit"])
        request = revised["plan_revision_requests"][-1]
        self.assertEqual(prior_reopening, request["prior_active_test_reopening"])
        archived_journal = self.root / request["prior_test_approval_transition"]
        self.assertFalse(journal_path.exists())
        self.assertEqual(journal_before, archived_journal.read_text(encoding="utf-8"))

        code, payload, _ = self.recover_test_approval()
        self.assertEqual(1, code)
        self.assertEqual("missing-file", payload["error"]["code"])

    def test_fresh_tests_after_plan_revision_do_not_use_old_reopening_protocol(self):
        """Retired reopenings cannot require pre-correction evidence in a new test cycle."""
        self.bootstrap_to_implementation()
        self.assertEqual(
            0,
            self.run_cli(
                "reopen-tests",
                str(ISSUE),
                "--reason",
                "approved-test-fixture-defect",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "request-plan-revision",
                str(ISSUE),
                "--by",
                "test-implementer",
                "--reason-code",
                "approved-plan-defect",
                "--reason",
                "A new plan must establish a fresh test boundary.",
            )[0],
        )
        self.write_artifact("revised-plan.md", "revised plan")
        self.write_artifact("revised-plan-review.md", "revised plan review")
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan",
                str(ISSUE),
                "--artifact",
                "artifacts-src/revised-plan.md",
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
                "artifacts-src/revised-plan-review.md",
                "--reviewer",
                "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(0, self.run_cli(
            "approve-plan", str(ISSUE), "--by", "owner", "--confirm", "plan_approved"
        )[0])
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "fresh test\n", encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "fresh test boundary")

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
                "%s -c \"import sys; sys.stderr.write('fresh failure'); sys.exit(1)\""
                % sys.executable,
                "--failure-contains",
                "fresh failure",
            )[0],
        )
        self.assertEqual("TEST_REVIEW", self.state()["status"])

    def test_plan_revision_request_requires_structured_reason(self):
        self.bootstrap_to_test_implementation()
        for arguments, expected in (
            (("--reason-code", "approved-plan-defect", "--reason", ""), "missing-plan-revision-reason"),
            (("--reason-code", "unknown", "--reason", "scope is wrong"), "invalid-plan-revision-reason-code"),
        ):
            code, payload, _ = self.run_cli(
                "request-plan-revision",
                str(ISSUE),
                "--by",
                "test-implementer",
                *arguments,
            )
            self.assertEqual(1, code)
            self.assertEqual(expected, payload["error"]["code"])
            self.assertEqual("TEST_IMPLEMENTATION", self.state()["status"])

    def test_plan_revision_request_is_fail_closed_outside_test_implementation(self):
        self.write_artifact("plan.md", "plan")
        self.assertEqual(0, self.run_cli("init", str(ISSUE))[0])
        code, payload, _ = self.run_cli(
            "request-plan-revision",
            str(ISSUE),
            "--by",
            "test-implementer",
            "--reason-code",
            "approved-plan-defect",
            "--reason",
            "The approved plan requires a bounded correction.",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-transition", payload["error"]["code"])
        self.assertEqual("PLANNING", self.state()["status"])

    def test_plan_revision_request_is_rejected_in_implementation(self):
        self.bootstrap_to_implementation()
        code, payload, _ = self.run_cli(
            "request-plan-revision",
            str(ISSUE),
            "--by",
            "test-implementer",
            "--reason-code",
            "approved-plan-defect",
            "--reason",
            "The approved plan requires a bounded correction.",
        )
        self.assertEqual(1, code)
        self.assertEqual("invalid-transition", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION", self.state()["status"])

    def test_run_validation_executes_setup_before_checks_and_persists_evidence(self):
        self.bootstrap_to_validation()

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "with-setup",
        )

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("IMPLEMENTATION_REVIEW", self.state()["status"])
        validation = self.state()["validation"]
        self.assertTrue(validation["passed"])
        self.assertTrue(validation["setup_passed"])
        self.assertEqual(1, len(validation["setup"]))
        self.assertTrue(validation["setup"][0]["passed"])
        self.assertEqual(
            "provision-generated-dependency", validation["setup"][0]["name"]
        )
        self.assertEqual(1, len(validation["checks"]))
        self.assertTrue(validation["checks"][0]["passed"])

    def test_run_validation_setup_failure_fails_closed_without_running_checks(self):
        self.bootstrap_to_validation()

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "with-failing-setup",
        )

        self.assertEqual(1, code)
        self.assertEqual("validation-setup-failed", payload["error"]["code"])
        self.assertEqual("IMPLEMENTATION", self.state()["status"])
        validation = self.state()["validation"]
        self.assertFalse(validation["passed"])
        self.assertFalse(validation["setup_passed"])
        self.assertIsNone(validation["checks"])
        self.assertFalse((self.root / "should-not-run.marker").exists())

    def test_run_validation_without_setup_key_remains_backward_compatible(self):
        self.bootstrap_to_validation()

        code, payload, _ = self.run_cli(
            "run-validation",
            str(ISSUE),
            "--profile",
            "workflow-tooling",
        )

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        validation = self.state()["validation"]
        self.assertTrue(validation["passed"])
        self.assertIsNone(validation["setup"])
        self.assertIsNone(validation["setup_passed"])

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

    def test_test_implementation_can_be_marked_not_applicable_for_approved_work(self):
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
                "docs/engineering/agent-workflow.md",
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

        code, payload, _ = self.run_cli(
            "submit-tests",
            str(ISSUE),
            "--artifact",
            "artifacts-src/test-report.md",
            "--agent",
            "chess-echo-test-implementer",
            "--not-applicable",
            "--reason",
            "Approved task has no test-file change.",
        )
        self.assertEqual(0, code)
        self.assertEqual("TEST_REVIEW", self.state()["status"])

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

    def test_approve_tests_journal_durability_precedes_commit(self):
        """A durable test-approval journal is written before the workflow-owned commit."""
        self.bootstrap_to_waiting_for_test_approval()
        before_state = self.state()
        before_head = self.git("rev-parse", "HEAD").stdout.strip()

        with self.fail_checked_git_command("commit", "injected-before-test-commit"):
            code, payload, _ = self.approve_tests()

        self.assertEqual(1, code)
        self.assertEqual("injected-before-test-commit", payload["error"]["code"])
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(before_state, self.state())
        journal = self.assert_test_transition_journal_matches_state()
        self.assertEqual("pending", journal["status"])
        self.assert_clean_status()

    def test_approve_tests_recovers_post_commit_persistence_failure_without_second_commit(self):
        """A crash between the empty commit and state persistence recovers idempotently."""
        self.bootstrap_to_waiting_for_test_approval()
        original_write_state = workflow._write_state

        def fail_final_state(root, config, issue, state):
            if state.get("status") == "IMPLEMENTATION":
                raise workflow.WorkflowError(
                    "injected-post-commit-test-state-failure",
                    "injected state persistence failure after authoritative test commit",
                )
            return original_write_state(root, config, issue, state)

        with mock.patch.object(workflow, "_write_state", side_effect=fail_final_state):
            code, payload, _ = self.approve_tests()

        self.assertEqual(1, code)
        self.assertEqual("injected-post-commit-test-state-failure", payload["error"]["code"])
        authoritative_commit = self.git("rev-parse", "HEAD").stdout.strip()
        state_after_failure = self.state()
        self.assertEqual("WAITING_FOR_TEST_HUMAN_APPROVAL", state_after_failure["status"])
        journal = self.assert_test_transition_journal_matches_state()
        self.assertEqual("committed-but-not-persisted", journal["status"])
        self.assertEqual(authoritative_commit, journal["authoritative_commit"])

        code, payload, _ = self.recover_test_approval()
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        self.assertEqual(authoritative_commit, payload["test_commit"])
        recovered = self.state()
        self.assertEqual(authoritative_commit, recovered["test_commit"])
        self.assertEqual(journal["acknowledgment"], recovered["approvals"]["tests"])
        self.assert_clean_status()

        # A second recovery attempt is idempotent: no duplicate empty commit is created.
        state_after_recovery = self.state()
        head_after_recovery = self.git("rev-parse", "HEAD").stdout.strip()
        code, payload, _ = self.recover_test_approval()
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        self.assertEqual(head_after_recovery, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual(state_after_recovery, self.state())

    def test_recover_test_approval_fails_closed_on_missing_journal(self):
        self.bootstrap_to_waiting_for_test_approval()
        code, payload, _ = self.recover_test_approval()
        self.assertEqual(1, code)
        self.assertEqual("missing-file", payload["error"]["code"])

    def test_recover_test_approval_fails_closed_on_corrupt_journal(self):
        self.bootstrap_to_waiting_for_test_approval()
        with self.fail_checked_git_command("commit", "injected-before-test-commit"):
            self.approve_tests()
        self.test_transition_journal_path().write_text("not json", encoding="utf-8")
        code, payload, _ = self.recover_test_approval()
        self.assertEqual(1, code)
        self.assertIn(payload["error"]["code"], ("invalid-json", "malformed-test-approval-journal"))

    def test_recover_test_approval_fails_closed_on_stale_mismatched_journal(self):
        self.bootstrap_to_waiting_for_test_approval()
        with self.fail_checked_git_command("commit", "injected-before-test-commit"):
            self.approve_tests()
        journal = json.loads(self.test_transition_journal_path().read_text(encoding="utf-8"))
        journal["candidate_test_commit"] = "0" * 40
        journal["expected_parent"] = "0" * 40
        self.test_transition_journal_path().write_text(
            json.dumps(journal, indent=2) + "\n", encoding="utf-8"
        )
        code, payload, _ = self.recover_test_approval()
        self.assertEqual(1, code)
        self.assertEqual("test-approval-journal-mismatch", payload["error"]["code"])

    def test_recover_test_approval_rejects_invalid_git_topology(self):
        """Recovery never infers authorization from Git topology alone."""
        self.bootstrap_to_waiting_for_test_approval()
        with self.fail_checked_git_command("commit", "injected-before-test-commit"):
            self.approve_tests()
        self.assert_test_transition_journal_matches_state()
        (self.root / "unrelated.txt").write_text("unrelated\n", encoding="utf-8")
        self.git("add", "unrelated.txt")
        self.git("commit", "-qm", "unauthorized unrelated commit")
        code, payload, _ = self.recover_test_approval()
        self.assertEqual(1, code)
        self.assertEqual("test-approval-content-drift", payload["error"]["code"])
        self.assertEqual("WAITING_FOR_TEST_HUMAN_APPROVAL", self.state()["status"])

    def test_approve_tests_not_applicable_journal_and_recovery(self):
        """NOT_APPLICABLE test approval is journaled and recoverable identically to REQUIRED."""
        (self.root / "src").mkdir()
        (self.root / "src" / "Example.kt").write_text("baseline\n", encoding="utf-8")
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "baseline implementation")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
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
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests",
                str(ISSUE),
                "--artifact",
                "artifacts-src/test-report.md",
                "--agent",
                "chess-echo-test-implementer",
                "--not-applicable",
                "--reason",
                "Approved implementation scope contains no test files.",
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
        before_head = self.git("rev-parse", "HEAD").stdout.strip()
        with self.fail_checked_git_command("commit", "injected-before-not-applicable-commit"):
            code, payload, _ = self.approve_tests()
        self.assertEqual(1, code)
        self.assertEqual(before_head, self.git("rev-parse", "HEAD").stdout.strip())
        journal = self.assert_test_transition_journal_matches_state()
        self.assertEqual("pending", journal["status"])
        self.assertEqual([], journal["test_paths"])

        code, payload, _ = self.recover_test_approval()
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        self.assertNotEqual(before_head, self.state()["test_commit"])

    def test_approve_tests_reopening_metadata_preserved_through_journal_and_recovery(self):
        """Reopened-test acknowledgment/reopening metadata survives a crash and recovery."""
        (self.root / "src").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "Example.kt").write_text(
            "historical implementation\n", encoding="utf-8"
        )
        self.git("add", "src/Example.kt")
        self.git("commit", "-qm", "historical implementation")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        self.bootstrap_to_implementation()
        (self.root / "src" / "Example.kt").write_text(
            "implementation candidate\n", encoding="utf-8"
        )
        code, payload, _ = self.run_cli(
            "reopen-tests",
            str(ISSUE),
            "--reason",
            "approved-test-fixture-defect",
        )
        self.assertEqual(0, code)
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "from pathlib import Path\n"
            "assert Path('src/Example.kt').read_text() == 'implementation candidate\\n', "
            "'expected failure'\n",
            encoding="utf-8"
        )
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "correct test fixture")
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
                "%s -c \"exec(compile(open('src/test/ExampleTest.kt').read(), "
                "'src/test/ExampleTest.kt', 'exec'))\"" % sys.executable,
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
        with self.fail_checked_git_command("commit", "injected-before-reopened-approval-commit"):
            code, payload, _ = self.approve_tests()
        self.assertEqual(1, code)
        journal = self.assert_test_transition_journal_matches_state()
        self.assertIsNotNone(journal["reopening"])
        self.assertTrue(journal["reopening"]["active"])
        self.assertEqual("approved-test-fixture-defect", journal["reopening"]["reason"])

        code, payload, _ = self.recover_test_approval()
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        recovered = self.state()
        self.assertFalse(recovered["test_reopenings"][0]["active"])
        self.assertEqual(recovered["test_commit"], recovered["test_reopenings"][0]["new_test_commit"])

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

    # ---------- supersede-run ----------

    def test_supersede_run_retires_eligible_run_and_preserves_it_intact(self):
        """A DRAFT_PR_CREATION run can be superseded, preserving its full record."""
        self.bootstrap_to_draft_pr_creation()
        original_state = self.state()
        original_run_dir = self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}"
        original_state_bytes = (original_run_dir / "state.json").read_bytes()
        original_artifacts = sorted(
            path.name for path in (original_run_dir / "artifacts").iterdir()
        )

        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "target_head was acquired from a substituted remote",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(0, code, payload)
        self.assertTrue(payload["ok"])

        # The canonical run location is gone entirely.
        self.assertFalse(original_run_dir.exists())

        destination = self.root / payload["superseded_run_location"]
        self.assertTrue(destination.is_dir())

        # The full historical state and artifacts are preserved byte-for-byte.
        self.assertEqual(original_state_bytes, (destination / "state.json").read_bytes())
        preserved_artifacts = sorted(
            path.name for path in (destination / "artifacts").iterdir()
        )
        self.assertEqual(original_artifacts, preserved_artifacts)

        manifest = json.loads(
            (destination / "supersession-manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(workflow.SUPERSESSION_FORMAT, manifest["format"])
        self.assertEqual(ISSUE, manifest["issue"])
        self.assertEqual(
            str(pathlib.Path(".agent-workflow") / "runs" / f"issue-{ISSUE}"),
            manifest["original_run_location"],
        )
        self.assertEqual(payload["superseded_run_location"], manifest["superseded_run_location"])
        self.assertEqual("DRAFT_PR_CREATION", manifest["original_status"])
        self.assertEqual(original_state["implementation_commit"], original_state["implementation_commit"])
        self.assertEqual(
            hashlib.sha256(original_state_bytes).hexdigest(),
            manifest["original_state_sha256"],
        )
        self.assertEqual(
            "target_head was acquired from a substituted remote", manifest["reason"]
        )
        self.assertEqual("owner", manifest["authorized_by"])
        self.assertEqual("supersede_confirmed", manifest["confirmation"])
        self.assertIn("authorized_at", manifest)
        self.assertIn("workflow_head_at_supersession", manifest)
        self.assertEqual("main", manifest["target_base"])

    def test_supersede_run_requires_explicit_authorization_confirmation(self):
        """A mismatched confirmation phrase is rejected, not silently accepted."""
        self.bootstrap_to_draft_pr_creation()
        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "invalid provenance",
            "--confirm",
            "not-the-right-phrase",
        )
        self.assertEqual(1, code)
        self.assertEqual("approval-confirmation-mismatch", payload["error"]["code"])
        # The run must remain entirely untouched after a rejected attempt.
        run_dir = self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}"
        self.assertTrue(run_dir.exists())
        self.assertEqual("DRAFT_PR_CREATION", self.state()["status"])

    def test_supersede_run_requires_non_empty_reason(self):
        """An empty --reason is refused rather than accepted as a formality."""
        self.bootstrap_to_draft_pr_creation()
        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "   ",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("missing-supersession-reason", payload["error"]["code"])

    def test_supersede_run_fails_closed_when_no_run_exists(self):
        """supersede-run on an issue that was never initialized fails closed."""
        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "no run to retire",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("no-existing-run", payload["error"]["code"])

    def test_supersede_run_is_not_repeatable_on_the_same_run(self):
        """A second supersede-run attempt fails closed exactly like a missing run."""
        self.bootstrap_to_draft_pr_creation()
        first_code, first_payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "invalid provenance",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(0, first_code, first_payload)

        second_code, second_payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "invalid provenance (retry)",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(1, second_code)
        self.assertEqual("no-existing-run", second_payload["error"]["code"])

        # Only the original supersession location exists; no duplicate/partial
        # historical directory was created by the rejected retry.
        superseded_parent = self.root / ".agent-workflow" / "runs" / "superseded"
        entries = list(superseded_parent.iterdir())
        self.assertEqual(1, len(entries))

    def test_supersede_run_rejects_unsafe_active_run(self):
        """An early-stage, still-actionable run cannot be superseded."""
        self.bootstrap_to_implementation()
        self.assertEqual("IMPLEMENTATION", self.state()["status"])
        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "convenience, not a real invalidation",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("run-not-eligible-for-supersession", payload["error"]["code"])
        run_dir = self.root / ".agent-workflow" / "runs" / f"issue-{ISSUE}"
        self.assertTrue(run_dir.exists())
        self.assertEqual("IMPLEMENTATION", self.state()["status"])

    def test_supersede_run_allows_fresh_init_with_no_inherited_evidence(self):
        """After supersession, init creates an unrelated, evidence-free run."""
        self.bootstrap_to_draft_pr_creation()
        old_implementation_commit = self.state()["implementation_commit"]
        old_target_head = self.state()["target_head"]

        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "target_head was acquired from a substituted remote",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(0, code, payload)

        # The workflow's own HEAD is the current authoritative target base;
        # init requires HEAD to already sit at the resolved target.
        self.git("checkout", "-q", "main")
        current_head = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("update-ref", "refs/remotes/origin/main", current_head)

        code, payload, _ = self.run_cli("init", str(ISSUE))
        self.assertEqual(0, code, payload)
        self.assertEqual("PLANNING", payload["status"])

        fresh_state = self.state()
        self.assertIsNone(fresh_state["implementation_commit"])
        self.assertIsNone(fresh_state["test_commit"])
        self.assertIsNone(fresh_state["draft_pr"])
        self.assertIsNone(fresh_state["approvals"]["plan"])
        self.assertIsNone(fresh_state["approvals"]["tests"])
        self.assertIsNone(fresh_state["approvals"]["implementation"])
        self.assertNotEqual(old_implementation_commit, fresh_state.get("implementation_commit"))
        self.assertEqual(current_head, fresh_state["target_head"])
        # The retired run's stale target is not reused as the fresh baseline.
        self.assertNotEqual(old_target_head, fresh_state["target_head"])

    def test_supersede_run_removes_issue_from_every_normal_command(self):
        """Normal, mutating workflow commands cannot touch a superseded run."""
        self.bootstrap_to_draft_pr_creation()
        code, _, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "target_head was acquired from a substituted remote",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(0, code)

        for arguments in (
            ("status", str(ISSUE)),
            ("approve-implementation", str(ISSUE), "--by", "owner", "--confirm", "implementation_approved"),
            ("reject-implementation", str(ISSUE), "--by", "owner", "--reason", "x"),
            ("run-validation", str(ISSUE), "--profile", "workflow-tooling"),
            ("recover-implementation-approval", str(ISSUE)),
            ("reanchor-target", str(ISSUE), "--by", "owner"),
            ("reconcile-candidate", str(ISSUE), "--by", "owner"),
            ("create-draft-pr", str(ISSUE), "--title", "x", "--body-file", "artifacts-src/plan.md"),
        ):
            code, payload, _ = self.run_cli(*arguments)
            self.assertEqual(1, code, arguments)
            self.assertEqual("missing-file", payload["error"]["code"], arguments)

    def test_supersede_run_manifest_captures_authoritative_workflow_head(self):
        """The manifest binds the transition to the workflow's own current HEAD, not stale state."""
        self.bootstrap_to_draft_pr_creation()
        expected_head = self.git("rev-parse", "HEAD").stdout.strip()
        code, payload, _ = self.run_cli(
            "supersede-run",
            str(ISSUE),
            "--by",
            "owner",
            "--reason",
            "target_head was acquired from a substituted remote",
            "--confirm",
            "supersede_confirmed",
        )
        self.assertEqual(0, code)
        self.assertEqual(expected_head, payload["manifest"]["workflow_head_at_supersession"])


class RevisionAndPrRevisionTest(AgentWorkflowTest):
    """Tests for start-revision, cosmetic revision-class enforcement, and
    publish-pr-revision/recover-pr-revision (issue #302)."""

    PARENT_ISSUE = 9001
    CHILD_ISSUE = 9002

    def state_for(self, issue):
        path = self.root / ".agent-workflow" / "runs" / ("issue-%s" % issue) / "state.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def write_state_for(self, issue, state):
        path = self.root / ".agent-workflow" / "runs" / ("issue-%s" % issue) / "state.json"
        path.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    def patch_gh_and_push(self, pr_json=None, push_error=None, pr_json_sequence=None):
        original = workflow._run_checked
        responses = iter(pr_json_sequence or [])
        self.gh_view_calls = []
        self.git_push_calls = []

        def injected(command, limits, cwd, code, context, env=None):
            if command[:3] == ["gh", "pr", "view"]:
                self.gh_view_calls.append(command)
                response = next(responses) if pr_json_sequence is not None else pr_json
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": json.dumps(response),
                    "stderr_text": "",
                }
            if command[:2] == ["git", "push"]:
                self.git_push_calls.append(command)
                if push_error:
                    raise workflow.WorkflowError(push_error, "injected push failure")
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": "",
                    "stderr_text": "",
                }
            return original(command, limits, cwd, code, context, env=env)

        return mock.patch.object(workflow, "_run_checked", side_effect=injected)

    def patch_gh_view_and_reflect_push(self, initial_pr):
        original = workflow._run_checked
        self.gh_view_calls = []
        self.git_push_calls = []
        pushed_head = None

        def injected(command, limits, cwd, code, context, env=None):
            nonlocal pushed_head
            if command[:3] == ["gh", "pr", "view"]:
                self.gh_view_calls.append(command)
                response = dict(initial_pr)
                if pushed_head is not None:
                    response["headRefOid"] = pushed_head
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": json.dumps(response),
                    "stderr_text": "",
                }
            if command[:2] == ["git", "push"]:
                self.git_push_calls.append(command)
                pushed_head = command[3].split(":refs/heads/", 1)[0]
                return {
                    "command": command,
                    "result": {"outcome": "success", "exit_code": 0},
                    "stdout_text": "",
                    "stderr_text": "",
                }
            return original(command, limits, cwd, code, context, env=env)

        return mock.patch.object(workflow, "_run_checked", side_effect=injected)

    def completed_run_reconciliation_journal_path(self, issue):
        return (
            self.root
            / ".agent-workflow"
            / "runs"
            / ("issue-%s" % issue)
            / "completed-run-reconciliation-transition.json"
        )

    def bootstrap_completed_parent(self, issue, profile="workflow-tooling"):
        """Drive a small, real run through every gate to WORKFLOW_COMPLETED."""
        self.write_artifact("parent-plan.md", "parent plan")
        self.write_artifact("parent-plan-review.md", "parent plan review")
        self.write_artifact("parent-test-report.md", "parent tests")
        self.write_artifact("parent-test-review.md", "parent test review")
        self.write_artifact("parent-impl-report.md", "parent implementation")
        self.write_artifact("parent-impl-review.md", "parent implementation review")
        self.write_artifact(
            "parent-pr-body.md",
            "## What\n- x\n\n## Why\n- y\n\n## Testing\n- z\n",
        )

        self.assertEqual(0, self.run_cli("init", str(issue))[0])
        self.assertEqual(
            0,
            self.run_cli(
                "submit-plan", str(issue),
                "--artifact", "artifacts-src/parent-plan.md",
                "--agent", "chess-echo-planner",
                "--scope", "docs/example.md",
                "--scope", "src/main/Example.kt",
                "--scope", "src/test/ExampleTest.kt",
            )[0],
        )
        (self.root / "src" / "test").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "test" / "ExampleTest.kt").write_text("test\n", encoding="utf-8")
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "candidate tests for parent")
        self.assertEqual(
            0,
            self.run_cli(
                "review-plan", str(issue),
                "--status", workflow.READY,
                "--artifact", "artifacts-src/parent-plan-review.md",
                "--reviewer", "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli("approve-plan", str(issue), "--by", "owner", "--confirm", "plan_approved")[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests", str(issue),
                "--artifact", "artifacts-src/parent-test-report.md",
                "--agent", "chess-echo-test-implementer",
                "--failure-command",
                "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
                "--failure-contains", "expected failure",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-tests", str(issue),
                "--status", workflow.READY,
                "--artifact", "artifacts-src/parent-test-review.md",
                "--reviewer", "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli("approve-tests", str(issue), "--by", "owner", "--confirm", "tests_approved")[0],
        )
        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        (self.root / "docs" / "example.md").write_text(
            "# Example\n\n```mermaid\nflowchart LR\n    A --> B\n```\n",
            encoding="utf-8",
        )
        test_commit = self.state_for(issue)["test_commit"]
        candidate_diff = self.git_candidate_diff(test_commit)
        evidence = {
            "test_command": "%s -c \"print('tests ok')\"" % sys.executable,
            "test_scope": ["src/test/ExampleTest.kt"],
            "exit_code": 0,
            "result": "PASS",
            "test_commit": test_commit,
            "candidate_diff": candidate_diff,
            "commit_subject": "Add example documentation for issue #%s" % issue,
            "stdout": "tests ok\n",
            "stderr": "",
        }
        evidence_path = self.root / "artifacts-src" / ("parent-evidence-%s.json" % issue)
        evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation", str(issue),
                "--artifact", "artifacts-src/parent-impl-report.md",
                "--agent", "chess-echo-implementer",
                "--evidence", "artifacts-src/parent-evidence-%s.json" % issue,
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli("run-validation", str(issue), "--profile", profile)[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation", str(issue),
                "--status", workflow.READY,
                "--artifact", "artifacts-src/parent-impl-review.md",
                "--reviewer", "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-implementation", str(issue), "--by", "owner", "--confirm", "implementation_approved",
            )[0],
        )
        self.assertEqual("DRAFT_PR_CREATION", self.state_for(issue)["status"])
        self.assertEqual(
            0,
            self.run_cli(
                "create-draft-pr", str(issue),
                "--title", "Parent PR",
                "--body-file", "artifacts-src/parent-pr-body.md",
                "--skip-github",
            )[0],
        )
        self.assertEqual("WORKFLOW_COMPLETED", self.state_for(issue)["status"])
        # --skip-github never calls gh, so identity is populated explicitly
        # by callers that need publish-pr-revision's parent linkage.
        return self.state_for(issue)

    def advance_main_past(self, issue):
        """Simulate the parent run's PR having merged: fast-forward origin/main."""
        state = self.state_for(issue)
        self.assertEqual(self.git("rev-parse", "HEAD").stdout.strip(), state["implementation_commit"])
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def set_parent_draft_pr(
        self,
        issue,
        number,
        head_ref_name,
        head_ref_oid,
        url="https://example.test/pr/1",
        repository="owner/repo",
    ):
        state = self.state_for(issue)
        state["draft_pr"]["number"] = number
        state["draft_pr"]["head_ref_name"] = head_ref_name
        state["draft_pr"]["head_ref_oid"] = head_ref_oid
        state["draft_pr"]["url"] = url
        state["draft_pr"]["repository"] = repository
        self.write_state_for(issue, state)

    def test_start_revision_requires_eligible_parent_status(self):
        code, payload, _ = self.run_cli(
            "start-revision", str(self.CHILD_ISSUE),
            "--parent-issue", str(self.PARENT_ISSUE),
            "--class", "cosmetic",
            "--by", "tester",
        )
        self.assertEqual(1, code)
        self.assertEqual("no-parent-run", payload["error"]["code"])

        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        state = self.state_for(self.PARENT_ISSUE)
        state["status"] = "IMPLEMENTATION"
        self.write_state_for(self.PARENT_ISSUE, state)
        code, payload, _ = self.run_cli(
            "start-revision", str(self.CHILD_ISSUE),
            "--parent-issue", str(self.PARENT_ISSUE),
            "--class", "cosmetic",
            "--by", "tester",
        )
        self.assertEqual(1, code)
        self.assertEqual("parent-run-not-eligible", payload["error"]["code"])

    def test_start_revision_cosmetic_inherits_plan_and_tests_and_enters_implementation(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        parent_state = self.state_for(self.PARENT_ISSUE)

        code, payload, _ = self.run_cli(
            "start-revision", str(self.CHILD_ISSUE),
            "--parent-issue", str(self.PARENT_ISSUE),
            "--class", "cosmetic",
            "--by", "tester",
        )
        self.assertEqual(0, code)
        self.assertEqual("IMPLEMENTATION", payload["status"])
        child_state = self.state_for(self.CHILD_ISSUE)
        self.assertEqual(parent_state["approved_scope"], child_state["approved_scope"])
        self.assertIsNotNone(child_state["approvals"]["plan"])
        self.assertIsNotNone(child_state["approvals"]["tests"])
        self.assertEqual(child_state["target_head"], child_state["test_commit"])
        self.assertEqual(self.PARENT_ISSUE, child_state["parent_run"]["issue"])
        self.assertEqual("cosmetic", child_state["parent_run"]["class"])

    def test_start_revision_test_class_inherits_plan_only_and_enters_test_implementation(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)

        code, payload, _ = self.run_cli(
            "start-revision", str(self.CHILD_ISSUE),
            "--parent-issue", str(self.PARENT_ISSUE),
            "--class", "test",
            "--by", "tester",
        )
        self.assertEqual(0, code)
        self.assertEqual("TEST_IMPLEMENTATION", payload["status"])
        child_state = self.state_for(self.CHILD_ISSUE)
        self.assertIsNotNone(child_state["approvals"]["plan"])
        self.assertIsNone(child_state["approvals"]["tests"])
        self.assertIsNone(child_state["test_commit"])

    def test_start_revision_plan_class_inherits_nothing_and_enters_planning(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)

        code, payload, _ = self.run_cli(
            "start-revision", str(self.CHILD_ISSUE),
            "--parent-issue", str(self.PARENT_ISSUE),
            "--class", "plan",
            "--by", "tester",
        )
        self.assertEqual(0, code)
        self.assertEqual("PLANNING", payload["status"])
        child_state = self.state_for(self.CHILD_ISSUE)
        self.assertIsNone(child_state["approved_scope"])
        self.assertIsNone(child_state["approvals"]["plan"])
        self.assertIsNone(child_state["approvals"]["tests"])

    def test_submit_implementation_rejects_non_cosmetic_change_for_cosmetic_revision(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "cosmetic",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)

        # An in-scope but non-documentation change while claiming a cosmetic
        # revision must fail closed, independent of the agent's claimed class.
        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        code_file = self.root / "docs" / "example.md"
        code_file.write_text("# Example\nCosmetic wording fix.\n", encoding="utf-8")
        in_scope_code_file = self.root / "src" / "main" / "Example.kt"
        in_scope_code_file.parent.mkdir(parents=True, exist_ok=True)
        in_scope_code_file.write_text("class Example\n", encoding="utf-8")

        self.write_artifact("child-plan.md", "n/a")  # placeholder to keep artifacts-src populated
        evidence_path = self.write_evidence(
            name="child-evidence-cosmetic.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Add cosmetic documentation fix for issue #%s" % self.CHILD_ISSUE,
        )

        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(1, code)
        self.assertEqual("revision-class-mismatch", payload["error"]["code"])

    def test_submit_implementation_accepts_cosmetic_change_for_cosmetic_revision(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "cosmetic",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)

        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        doc_file = self.root / "docs" / "example.md"
        doc_file.write_text(
            "# Example\n\n```mermaid\nflowchart TB\n    A --> B\n```\n",
            encoding="utf-8",
        )

        evidence_path = self.write_evidence(
            name="child-evidence-cosmetic-ok.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Add cosmetic documentation fix for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(0, code)
        self.assertEqual("VALIDATION", payload["status"])

    def _prepare_reanchored_test_revision(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "test",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        reanchored_target = self.advance_remote_ref_past_commit(
            child_state["target_head"],
            {"already-merged.txt": "unrelated target change\n"},
            name="authorized unrelated merge before test revision",
        )
        code, payload, _ = self.run_cli(
            "reanchor-target", str(self.CHILD_ISSUE), "--by", "tester"
        )
        self.assertEqual(0, code)
        self.assertEqual(reanchored_target, payload["new_target_head"])
        self.assertEqual(0, self.git("reset", "--hard", reanchored_target).returncode)

        test_file = self.root / "src" / "test" / "ExampleTest.kt"
        test_file.write_text("revised test\n", encoding="utf-8")
        self.git("add", "src/test/ExampleTest.kt")
        self.git("commit", "-qm", "revise example test")
        self.write_artifact("child-test-report.md", "child tests")
        self.write_artifact("child-test-review.md", "child test review")
        self.assertEqual(
            0,
            self.run_cli(
                "submit-tests", str(self.CHILD_ISSUE),
                "--artifact", "artifacts-src/child-test-report.md",
                "--agent", "chess-echo-test-implementer",
                "--failure-command",
                "%s -c \"print('expected failure'); import sys; sys.exit(1)\"" % sys.executable,
                "--failure-contains", "expected failure",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "review-tests", str(self.CHILD_ISSUE),
                "--status", workflow.READY,
                "--artifact", "artifacts-src/child-test-review.md",
                "--reviewer", "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-tests", str(self.CHILD_ISSUE),
                "--by", "owner", "--confirm", "tests_approved",
            )[0],
        )
        return self.state_for(self.CHILD_ISSUE)

    def test_test_revision_submit_implementation_ignores_unrelated_reanchored_target_changes(self):
        child_state = self._prepare_reanchored_test_revision()
        test_commit = child_state["test_commit"]
        implementation_file = self.root / "src" / "main" / "Example.kt"
        implementation_file.parent.mkdir(parents=True, exist_ok=True)
        implementation_file.write_text("revised implementation\n", encoding="utf-8")
        evidence_path = self.write_evidence(
            name="child-evidence-reanchored.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Revise example implementation for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(0, code)
        self.assertEqual("VALIDATION", payload["status"])
        self.assertEqual(
            "implementation",
            self.state_for(self.CHILD_ISSUE)["revision_classification"]["required"],
        )

    def test_test_revision_submit_implementation_escalates_genuine_out_of_scope_change(self):
        child_state = self._prepare_reanchored_test_revision()
        test_commit = child_state["test_commit"]
        (self.root / "README.md").write_text("out of scope\n", encoding="utf-8")
        evidence_path = self.write_evidence(
            name="child-evidence-out-of-scope.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Revise example implementation for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(1, code)
        self.assertEqual("revision-class-mismatch", payload["error"]["code"])

    def test_implementation_revision_rejects_changed_approved_test(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "implementation",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)
        (self.root / "src" / "main").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "main" / "Example.kt").write_text(
            "class Example\n", encoding="utf-8"
        )
        (self.root / "src" / "test" / "ExampleTest.kt").write_text(
            "changed approved test\n", encoding="utf-8"
        )
        evidence_path = self.write_evidence(
            name="child-evidence-changed-test.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Change example implementation for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(1, code)
        self.assertEqual("revision-class-mismatch", payload["error"]["code"])

    def test_implementation_revision_rejects_new_test_path(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "implementation",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)
        (self.root / "src" / "main").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "main" / "Example.kt").write_text(
            "class Example\n", encoding="utf-8"
        )
        (self.root / "src" / "test" / "NewExampleTest.kt").write_text(
            "new test\n", encoding="utf-8"
        )
        evidence_path = self.write_evidence(
            name="child-evidence-new-test.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Change example implementation for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(1, code)
        self.assertEqual("revision-class-mismatch", payload["error"]["code"])

    def test_implementation_revision_rejects_deleted_approved_test(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", "implementation",
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)
        (self.root / "src" / "main").mkdir(parents=True, exist_ok=True)
        (self.root / "src" / "main" / "Example.kt").write_text(
            "class Example\n", encoding="utf-8"
        )
        (self.root / "src" / "test" / "ExampleTest.kt").unlink()
        evidence_path = self.write_evidence(
            name="child-evidence-deleted-test.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Change example implementation for issue #%s" % self.CHILD_ISSUE,
        )
        code, payload, _ = self.run_cli(
            "submit-implementation", str(self.CHILD_ISSUE),
            "--artifact", "artifacts-src/parent-impl-report.md",
            "--agent", "chess-echo-implementer",
            "--evidence", evidence_path,
        )
        self.assertEqual(1, code)
        self.assertEqual("revision-class-mismatch", payload["error"]["code"])

    def test_cosmetic_markdown_classifier_accepts_layout_only_change(self):
        before = "```mermaid\nflowchart LR\n    A --> B\n```\n"
        after = "```mermaid\nflowchart TB\n    A --> B\n```\n"
        self.assertTrue(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/diagram.md", before, after
            )
        )

    def test_cosmetic_markdown_classifier_rejects_workflow_rule_change(self):
        self.assertFalse(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/agent-workflow.md",
                "The command must verify the approved scope.\n",
                "The command may skip verification of the approved scope.\n",
            )
        )

    def test_cosmetic_markdown_classifier_rejects_operational_command_change(self):
        self.assertFalse(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/agent-workflow.md",
                "`publish-pr-revision --confirm pr_revision_confirmed`\n",
                "`publish-pr-revision --confirm bypassed`\n",
            )
        )

    def test_cosmetic_markdown_classifier_rejects_security_requirement_change(self):
        self.assertFalse(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/agent-workflow.md",
                "Unexpected divergence must fail closed.\n",
                "Unexpected divergence may be ignored.\n",
            )
        )

    def test_cosmetic_markdown_classifier_rejects_acceptance_criteria_change(self):
        self.assertFalse(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/agent-workflow.md",
                "- [ ] Verify the live PR head.\n",
                "- [ ] Trust the recorded PR head.\n",
            )
        )

    def test_cosmetic_markdown_classifier_rejects_architecture_behavior_change(self):
        self.assertFalse(
            workflow._is_cosmetic_markdown_change(
                "docs/engineering/agent-workflow-architecture.md",
                "Only one authoritative implementation commit is published.\n",
                "Multiple unrelated implementation commits may be published.\n",
            )
        )

    def test_revision_boundary_classifier_orders_all_boundaries(self):
        scope = ["docs/example.md", "src/main/Example.kt", "src/test/ExampleTest.kt"]
        self.assertEqual(
            "cosmetic",
            workflow._classify_revision_boundary(
                parent_scope=scope,
                approved_scope=scope,
                changed_paths=["docs/example.md"],
                changed_test_paths=[],
                cosmetic_content_valid=True,
                plan_content_changed=False,
            ),
        )
        self.assertEqual(
            "implementation",
            workflow._classify_revision_boundary(
                parent_scope=scope,
                approved_scope=scope,
                changed_paths=["src/main/Example.kt"],
                changed_test_paths=[],
                cosmetic_content_valid=False,
                plan_content_changed=False,
            ),
        )
        self.assertEqual(
            "test",
            workflow._classify_revision_boundary(
                parent_scope=scope,
                approved_scope=scope,
                changed_paths=["src/test/ExampleTest.kt", "src/main/Example.kt"],
                changed_test_paths=["src/test/ExampleTest.kt"],
                cosmetic_content_valid=False,
                plan_content_changed=False,
            ),
        )
        self.assertEqual(
            "plan",
            workflow._classify_revision_boundary(
                parent_scope=scope,
                approved_scope=scope + ["src/main/NewBehavior.kt"],
                changed_paths=["src/main/NewBehavior.kt"],
                changed_test_paths=[],
                cosmetic_content_valid=False,
                plan_content_changed=True,
            ),
        )

    def test_revision_boundary_rejects_every_narrower_claim(self):
        self.assertFalse(workflow._revision_claim_covers("cosmetic", "implementation"))
        self.assertFalse(workflow._revision_claim_covers("implementation", "test"))
        self.assertFalse(workflow._revision_claim_covers("test", "plan"))
        self.assertTrue(workflow._revision_claim_covers("plan", "implementation"))

    def bootstrap_child_at_draft_pr_creation(self, revision_class="implementation"):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.advance_main_past(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE, number=300, head_ref_name="parent-branch",
            head_ref_oid="0" * 40,
        )
        self.assertEqual(
            0,
            self.run_cli(
                "start-revision", str(self.CHILD_ISSUE),
                "--parent-issue", str(self.PARENT_ISSUE),
                "--class", revision_class,
                "--by", "tester",
            )[0],
        )
        child_state = self.state_for(self.CHILD_ISSUE)
        test_commit = child_state["test_commit"]
        self.assertEqual(0, self.git("checkout", "-q", test_commit).returncode)
        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        doc_file = self.root / "docs" / "example.md"
        doc_file.write_text("# Example\nCosmetic wording fix.\n", encoding="utf-8")
        evidence_path = self.write_evidence(
            name="child-evidence-publish.json",
            test_scope=["src/test/ExampleTest.kt"],
            test_commit=test_commit,
            candidate_diff=self.git_candidate_diff(test_commit),
            commit_subject="Add cosmetic documentation fix for issue #%s" % self.CHILD_ISSUE,
        )
        self.assertEqual(
            0,
            self.run_cli(
                "submit-implementation", str(self.CHILD_ISSUE),
                "--artifact", "artifacts-src/parent-impl-report.md",
                "--agent", "chess-echo-implementer",
                "--evidence", evidence_path,
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli("run-validation", str(self.CHILD_ISSUE), "--profile", "workflow-tooling")[0],
        )
        self.write_artifact("child-impl-review.md", "child implementation review")
        self.assertEqual(
            0,
            self.run_cli(
                "review-implementation", str(self.CHILD_ISSUE),
                "--status", workflow.READY,
                "--artifact", "artifacts-src/child-impl-review.md",
                "--reviewer", "chess-echo-reviewer",
            )[0],
        )
        self.assertEqual(
            0,
            self.run_cli(
                "approve-implementation", str(self.CHILD_ISSUE), "--by", "owner",
                "--confirm", "implementation_approved",
            )[0],
        )
        self.assertEqual("DRAFT_PR_CREATION", self.state_for(self.CHILD_ISSUE)["status"])

    def test_publish_pr_revision_requires_revision_run(self):
        self.bootstrap_completed_parent(self.PARENT_ISSUE)
        state = self.state_for(self.PARENT_ISSUE)
        state["status"] = "DRAFT_PR_CREATION"
        self.write_state_for(self.PARENT_ISSUE, state)
        code, payload, _ = self.run_cli(
            "publish-pr-revision", str(self.PARENT_ISSUE),
            "--target-pr", "300",
            "--by", "owner",
            "--confirm", "pr_revision_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("not-a-revision-run", payload["error"]["code"])

    def test_publish_pr_revision_requires_matching_target_pr(self):
        self.bootstrap_child_at_draft_pr_creation()
        code, payload, _ = self.run_cli(
            "publish-pr-revision", str(self.CHILD_ISSUE),
            "--target-pr", "999",
            "--by", "owner",
            "--confirm", "pr_revision_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("pr-identity-mismatch", payload["error"]["code"])

    def test_publish_pr_revision_fails_closed_on_head_divergence(self):
        self.bootstrap_child_at_draft_pr_creation()
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "f" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli(
                "publish-pr-revision", str(self.CHILD_ISSUE),
                "--target-pr", "300",
                "--by", "owner",
                "--confirm", "pr_revision_confirmed",
            )
        self.assertEqual(1, code)
        self.assertEqual("pr-head-diverged", payload["error"]["code"])

    def test_publish_pr_revision_success_pushes_and_completes(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        live_pr_before = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "0" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        live_pr_after = dict(
            live_pr_before, headRefOid=child_state["implementation_commit"]
        )
        with self.patch_gh_and_push(
            pr_json_sequence=[live_pr_before, live_pr_after]
        ):
            code, payload, _ = self.run_cli(
                "publish-pr-revision", str(self.CHILD_ISSUE),
                "--target-pr", "300",
                "--by", "owner",
                "--confirm", "pr_revision_confirmed",
            )
        self.assertEqual(0, code)
        self.assertEqual("WORKFLOW_COMPLETED", payload["status"])
        child_state = self.state_for(self.CHILD_ISSUE)
        self.assertEqual(300, child_state["draft_pr"]["number"])
        self.assertEqual(
            child_state["implementation_commit"], child_state["draft_pr"]["head_ref_oid"]
        )
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
        self.assertEqual("finalized", journal["status"])
        self.assertEqual(2, len(self.gh_view_calls))
        self.assertIn(
            "--force-with-lease=refs/heads/parent-branch:%s" % ("0" * 40),
            self.git_push_calls[0],
        )

    def test_publish_pr_revision_rejects_non_draft_pr(self):
        self.bootstrap_child_at_draft_pr_creation()
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": False,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "0" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli(
                "publish-pr-revision", str(self.CHILD_ISSUE),
                "--target-pr", "300",
                "--by", "owner",
                "--confirm", "pr_revision_confirmed",
            )
        self.assertEqual(1, code)
        self.assertEqual("pr-not-draft", payload["error"]["code"])
        self.assertEqual([], self.git_push_calls)

    def test_publish_pr_revision_rejects_repository_mismatch(self):
        self.bootstrap_child_at_draft_pr_creation()
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "0" * 40,
            "headRepository": {"nameWithOwner": "attacker/fork"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli(
                "publish-pr-revision", str(self.CHILD_ISSUE),
                "--target-pr", "300",
                "--by", "owner",
                "--confirm", "pr_revision_confirmed",
            )
        self.assertEqual(1, code)
        self.assertEqual("pr-repository-mismatch", payload["error"]["code"])
        self.assertEqual([], self.git_push_calls)

    def test_publish_pr_revision_keeps_pending_journal_on_post_push_divergence(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        before = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "0" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        diverged = dict(before, headRefOid="f" * 40)
        with self.patch_gh_and_push(pr_json_sequence=[before, diverged]):
            code, payload, _ = self.run_cli(
                "publish-pr-revision", str(self.CHILD_ISSUE),
                "--target-pr", "300",
                "--by", "owner",
                "--confirm", "pr_revision_confirmed",
            )
        self.assertEqual(1, code)
        self.assertEqual("pr-revision-post-push-mismatch", payload["error"]["code"])
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
        self.assertEqual("pending", journal["status"])
        self.assertEqual("DRAFT_PR_CREATION", self.state_for(self.CHILD_ISSUE)["status"])
        self.assertEqual(child_state["implementation_commit"], journal["new_head"])

    def test_recover_pr_revision_finalizes_after_interrupted_push(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        new_head = child_state["implementation_commit"]
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal_path.write_text(
            json.dumps(
                {
                    "format": workflow.PR_REVISION_JOURNAL_FORMAT,
                    "version": 1,
                    "transition_id": "abc",
                    "issue": self.CHILD_ISSUE,
                    "operation": "publish-pr-revision",
                    "created_at": "2020-01-01T00:00:00+00:00",
                    "status": "pending",
                    "target_pr": 300,
                    "target_repository": "owner/repo",
                    "target_base": "main",
                    "target_branch": "parent-branch",
                    "expected_head": "0" * 40,
                    "new_head": new_head,
                    "requested_by": "owner",
                    "confirmation": "pr_revision_confirmed",
                }
            ),
            encoding="utf-8",
        )
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": new_head,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli("recover-pr-revision", str(self.CHILD_ISSUE))
        self.assertEqual(0, code)
        self.assertTrue(payload["recovered"])
        self.assertEqual("WORKFLOW_COMPLETED", self.state_for(self.CHILD_ISSUE)["status"])

    def test_recover_pr_revision_retry_required_when_push_never_happened(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        new_head = child_state["implementation_commit"]
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal_path.write_text(
            json.dumps(
                {
                    "format": workflow.PR_REVISION_JOURNAL_FORMAT,
                    "version": 1,
                    "transition_id": "abc",
                    "issue": self.CHILD_ISSUE,
                    "operation": "publish-pr-revision",
                    "created_at": "2020-01-01T00:00:00+00:00",
                    "status": "pending",
                    "target_pr": 300,
                    "target_repository": "owner/repo",
                    "target_base": "main",
                    "target_branch": "parent-branch",
                    "expected_head": "0" * 40,
                    "new_head": new_head,
                    "requested_by": "owner",
                    "confirmation": "pr_revision_confirmed",
                }
            ),
            encoding="utf-8",
        )
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "0" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli("recover-pr-revision", str(self.CHILD_ISSUE))
        self.assertEqual(0, code)
        self.assertTrue(payload["retry_required"])
        self.assertNotEqual("WORKFLOW_COMPLETED", self.state_for(self.CHILD_ISSUE)["status"])

    def test_recover_pr_revision_fails_closed_on_ambiguous_head(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        new_head = child_state["implementation_commit"]
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal_path.write_text(
            json.dumps(
                {
                    "format": workflow.PR_REVISION_JOURNAL_FORMAT,
                    "version": 1,
                    "transition_id": "abc",
                    "issue": self.CHILD_ISSUE,
                    "operation": "publish-pr-revision",
                    "created_at": "2020-01-01T00:00:00+00:00",
                    "status": "pending",
                    "target_pr": 300,
                    "target_repository": "owner/repo",
                    "target_base": "main",
                    "target_branch": "parent-branch",
                    "expected_head": "0" * 40,
                    "new_head": new_head,
                    "requested_by": "owner",
                    "confirmation": "pr_revision_confirmed",
                }
            ),
            encoding="utf-8",
        )
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": "e" * 40,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=live_pr):
            code, payload, _ = self.run_cli("recover-pr-revision", str(self.CHILD_ISSUE))
        self.assertEqual(1, code)
        self.assertEqual("pr-revision-recovery-ambiguous", payload["error"]["code"])

    def test_recover_pr_revision_revalidates_full_pr_state_before_finalizing(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        new_head = child_state["implementation_commit"]
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal_path.write_text(
            json.dumps(
                {
                    "format": workflow.PR_REVISION_JOURNAL_FORMAT,
                    "version": 1,
                    "transition_id": "abc",
                    "issue": self.CHILD_ISSUE,
                    "operation": "publish-pr-revision",
                    "created_at": "2020-01-01T00:00:00+00:00",
                    "status": "pending",
                    "target_pr": 300,
                    "target_repository": "owner/repo",
                    "target_base": "main",
                    "target_branch": "parent-branch",
                    "expected_head": "0" * 40,
                    "new_head": new_head,
                    "requested_by": "owner",
                    "confirmation": "pr_revision_confirmed",
                }
            ),
            encoding="utf-8",
        )
        closed_pr_at_new_head = {
            "number": 300,
            "state": "CLOSED",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": new_head,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_and_push(pr_json=closed_pr_at_new_head):
            code, payload, _ = self.run_cli(
                "recover-pr-revision", str(self.CHILD_ISSUE)
            )
        self.assertEqual(1, code)
        self.assertEqual("pr-revision-recovery-state-mismatch", payload["error"]["code"])
        self.assertEqual("pending", json.loads(journal_path.read_text())["status"])
        self.assertEqual("DRAFT_PR_CREATION", self.state_for(self.CHILD_ISSUE)["status"])

    def test_recover_pr_revision_rejects_journal_bound_to_other_run(self):
        self.bootstrap_child_at_draft_pr_creation()
        child_state = self.state_for(self.CHILD_ISSUE)
        journal_path = (
            self.root / ".agent-workflow" / "runs" / ("issue-%s" % self.CHILD_ISSUE)
            / "pr-revision-transition.json"
        )
        journal_path.write_text(
            json.dumps(
                {
                    "format": workflow.PR_REVISION_JOURNAL_FORMAT,
                    "version": 1,
                    "transition_id": "abc",
                    "issue": 9999,
                    "operation": "publish-pr-revision",
                    "created_at": "2020-01-01T00:00:00+00:00",
                    "status": "pending",
                    "target_pr": 300,
                    "target_repository": "owner/repo",
                    "target_base": "main",
                    "target_branch": "parent-branch",
                    "expected_head": "0" * 40,
                    "new_head": child_state["implementation_commit"],
                    "requested_by": "owner",
                    "confirmation": "pr_revision_confirmed",
                }
            ),
            encoding="utf-8",
        )
        code, payload, _ = self.run_cli(
            "recover-pr-revision", str(self.CHILD_ISSUE)
        )
        self.assertEqual(1, code)
        self.assertEqual("pr-revision-journal-mismatch", payload["error"]["code"])

    def test_reconcile_completed_run_succeeds_and_preserves_pr_identity(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        original_target = parent_state["target_head"]
        original_implementation = parent_state["implementation_commit"]
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=original_implementation,
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        new_target = self.advance_remote_ref_past_commit(
            original_target,
            {"unrelated.txt": "downstream unrelated change\n"},
            name="authorized unrelated downstream merge",
        )
        initial_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": original_implementation,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }

        with self.patch_gh_view_and_reflect_push(initial_pr):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("WORKFLOW_COMPLETED", payload["status"])
        after = self.state_for(self.PARENT_ISSUE)
        self.assertEqual(new_target, after["target_head"])
        self.assertEqual(300, after["draft_pr"]["number"])
        self.assertEqual("parent-branch", after["draft_pr"]["head_ref_name"])
        self.assertEqual("owner/repo", after["draft_pr"]["repository"])
        self.assertNotEqual(original_implementation, after["implementation_commit"])
        self.assertEqual(after["implementation_commit"], after["draft_pr"]["head_ref_oid"])
        self.assertEqual(
            1,
            int(
                self.git(
                    "rev-list",
                    "--count",
                    "%s..%s" % (new_target, after["implementation_commit"]),
                ).stdout.strip()
            ),
        )
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("finalized", journal["status"])
        self.assertEqual(original_target, journal["previous_target_head"])
        self.assertEqual(new_target, journal["new_target_head"])
        self.assertEqual(300, journal["draft_pr"]["number"])
        self.assertEqual("parent-branch", journal["draft_pr"]["head_ref_name"])
        self.assertEqual(
            {
                "condition": "target-drift",
                "product_intent": "preserved",
                "repository_realization": "stale",
                "disposition": "reconcile",
                "transition": "reconcile-completed-run",
                "revision_class": None,
            },
            journal["target_drift"],
        )
        self.assertEqual(1, len(self.git_push_calls))
        self.assertIn(
            "--force-with-lease=refs/heads/parent-branch:%s" % original_implementation,
            self.git_push_calls[0],
        )
        scratch_root = (
            self.root
            / ".agent-workflow"
            / "runs"
            / ("issue-%s" % self.PARENT_ISSUE)
            / ".scratch"
        )
        self.assertFalse(
            scratch_root.exists() and any(scratch_root.iterdir()),
            "scratch reconciliation worktree must be cleaned up",
        )

    def test_reconcile_completed_run_scratch_setup_provisions_dependency_and_reconciles(self):
        parent_state = self.bootstrap_completed_parent(
            self.PARENT_ISSUE, profile="with-setup"
        )
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        new_target = self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"unrelated.txt": "downstream unrelated change\n"},
        )
        initial_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": parent_state["implementation_commit"],
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }

        with self.patch_gh_view_and_reflect_push(initial_pr):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("WORKFLOW_COMPLETED", payload["status"])
        after = self.state_for(self.PARENT_ISSUE)
        self.assertEqual(new_target, after["target_head"])
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("finalized", journal["status"])
        self.assertEqual("reconciled", journal["outcome"])
        self.assertIsNotNone(journal["setup"])
        self.assertTrue(all(step["passed"] for step in journal["setup"]))

    def test_reconcile_completed_run_scratch_setup_failure_fails_closed_without_test_revision(
        self,
    ):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"unrelated.txt": "downstream unrelated change\n"},
        )
        failing_setup = [
            {
                "name": "unavailable-tool",
                "command": [sys.executable, "-c", "raise SystemExit(1)"],
                "cwd": ".",
                "passed": False,
                "result": {"outcome": "nonzero-exit", "exit_code": 1},
            }
        ]

        with mock.patch.object(
            workflow, "_run_validation_setup", return_value=failing_setup
        ):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(1, code)
        self.assertEqual("validation-setup-failed", payload["error"]["code"])
        after = self.state_for(self.PARENT_ISSUE)
        self.assertEqual("WORKFLOW_COMPLETED", after["status"])
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("pending", journal["status"])
        self.assertIsNone(journal.get("revision_issue"))
        self.assertIsNone(journal.get("outcome"))

    def test_reconcile_completed_run_loads_setup_newly_added_at_authoritative_target(
        self,
    ):
        """A stale caller checkout lacking a newly-introduced setup step must
        not silently skip it: reconciliation must load and run the setup
        declared by the authoritative target itself (issue #313)."""
        parent_state = self.bootstrap_completed_parent(
            self.PARENT_ISSUE, profile="workflow-tooling"
        )
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        # The caller's own live .github/agent-workflow.json (still checked
        # out at the old implementation_commit) has no setup step for
        # "workflow-tooling" -- this simulates the stale-caller-config shape
        # from the #109 acceptance attempt.
        stale_config = json.loads(
            (self.root / ".github" / "agent-workflow.json").read_text(encoding="utf-8")
        )
        self.assertNotIn(
            "setup", stale_config["validation_profiles"]["workflow-tooling"]
        )

        # The authoritative target (origin/main) advances with a config
        # change that adds a required setup step to the same profile name.
        authoritative_config = json.loads(json.dumps(stale_config))
        authoritative_config["validation_profiles"]["workflow-tooling"]["setup"] = [
            {
                "name": "provision-generated-dependency",
                "command": [
                    sys.executable,
                    "-c",
                    "import pathlib\n"
                    "d = pathlib.Path('generated')\n"
                    "d.mkdir(exist_ok=True)\n"
                    "(d / 'marker.txt').write_text('ok')\n",
                ],
            }
        ]
        new_target = self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {
                ".github/agent-workflow.json": json.dumps(
                    authoritative_config, indent=2
                )
                + "\n",
                "unrelated.txt": "downstream unrelated change\n",
            },
        )
        initial_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": parent_state["implementation_commit"],
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }

        with self.patch_gh_view_and_reflect_push(initial_pr):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("WORKFLOW_COMPLETED", payload["status"])
        after = self.state_for(self.PARENT_ISSUE)
        self.assertEqual(new_target, after["target_head"])
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("finalized", journal["status"])
        self.assertEqual("reconciled", journal["outcome"])
        # If the stale caller config were used, "setup" would be None
        # because the caller's on-disk profile has no setup step at all.
        self.assertIsNotNone(journal["setup"])
        self.assertTrue(all(step["passed"] for step in journal["setup"]))

    def test_reconcile_completed_run_fails_closed_when_authoritative_config_invalid(
        self,
    ):
        """If the authoritative target's configuration cannot be obtained or
        verified, reconciliation must fail closed with no fallback to the
        caller's stale configuration (issue #313)."""
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        broken_config = json.loads(
            (self.root / ".github" / "agent-workflow.json").read_text(encoding="utf-8")
        )
        # An authoritative config with no validation profiles at all fails
        # the same schema validation applied at startup.
        broken_config["validation_profiles"] = {}
        self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {
                ".github/agent-workflow.json": json.dumps(broken_config, indent=2)
                + "\n",
            },
        )

        code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(1, code)
        self.assertEqual(
            "authoritative-config-unverifiable", payload["error"]["code"]
        )
        after = self.state_for(self.PARENT_ISSUE)
        self.assertEqual("WORKFLOW_COMPLETED", after["status"])
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual("pending", journal["status"])
        self.assertIsNone(journal.get("revision_issue"))
        self.assertIsNone(journal.get("outcome"))
        scratch_root = (
            self.root
            / ".agent-workflow"
            / "runs"
            / ("issue-%s" % self.PARENT_ISSUE)
            / ".scratch"
        )
        self.assertFalse(
            scratch_root.exists() and any(scratch_root.iterdir()),
            "scratch reconciliation worktree must be cleaned up",
        )

    def test_reconcile_completed_run_starts_test_revision_when_validation_fails(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"unrelated.txt": "downstream unrelated change\n"},
        )
        failed_checks = [
            {
                "name": "workflow-check",
                "command": [sys.executable, "-c", "raise SystemExit(1)"],
                "cwd": ".",
                "passed": False,
                "result": {"outcome": "nonzero-exit", "exit_code": 1},
            }
        ]
        with mock.patch.object(
            workflow, "_run_validation_checks", return_value=failed_checks
        ):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("test", payload["revision"]["class"])
        revision_issue = payload["revision"]["issue"]
        self.assertNotEqual(self.PARENT_ISSUE, revision_issue)
        revision_state = self.state_for(revision_issue)
        self.assertEqual("TEST_IMPLEMENTATION", revision_state["status"])
        self.assertEqual(self.PARENT_ISSUE, revision_state["parent_run"]["issue"])
        self.assertEqual("test", revision_state["parent_run"]["class"])

    def test_reconcile_completed_run_starts_implementation_revision_on_in_scope_conflict(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"docs/example.md": "# Example\n\nconflicting downstream documentation\n"},
            name="authorized conflicting downstream merge",
        )

        code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("implementation", payload["revision"]["class"])
        revision_state = self.state_for(payload["revision"]["issue"])
        self.assertEqual("IMPLEMENTATION", revision_state["status"])
        self.assertEqual("implementation", revision_state["parent_run"]["class"])
        journal = json.loads(
            self.completed_run_reconciliation_journal_path(
                self.PARENT_ISSUE
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(
            {
                "condition": "target-drift",
                "product_intent": "preserved",
                "repository_realization": "invalidated",
                "disposition": "start-revision",
                "transition": "reconcile-completed-run",
                "revision_class": "implementation",
            },
            journal["target_drift"],
        )

    def test_reconcile_completed_run_classifies_out_of_scope_conflict_as_plan(self):
        self.assertEqual(
            "plan",
            workflow._classify_completed_run_revision_boundary(
                approved_scope=["docs/example.md", "src/test/ExampleTest.kt"],
                test_paths=["src/test/ExampleTest.kt"],
                conflict_paths=["README.md"],
                replay_clean=False,
                validation_passed=True,
            ),
        )

    def test_reconcile_completed_run_fails_closed_on_non_descendant_target(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        before = self.state_for(self.PARENT_ISSUE)
        unrelated = self.unrelated_empty_tree_commit()
        self.git("update-ref", "refs/remotes/origin/main", unrelated)

        code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)

        self.assertEqual(1, code)
        self.assertEqual("invalid-git-ancestry", payload["error"]["code"])
        self.assertEqual(before, self.state_for(self.PARENT_ISSUE))

    def test_recover_completed_run_reconciliation_retries_superseded_tooling_failure(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        first_target = self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"unrelated.txt": "first downstream change\n"},
        )
        unavailable_tool = [
            {
                "name": "frontend lint",
                "command": ["eslint"],
                "cwd": ".",
                "passed": False,
                "result": {
                    "outcome": "nonzero-exit",
                    "exit_code": 127,
                    "stderr": "sh: eslint: command not found\n",
                },
            }
        ]
        with mock.patch.object(
            workflow, "_run_validation_checks", return_value=unavailable_tool
        ):
            code, payload, _ = self.reconcile_completed_run(self.PARENT_ISSUE)
        self.assertEqual(0, code)
        original_revision = payload["revision"]["issue"]
        original_journal_path = self.completed_run_reconciliation_journal_path(
            self.PARENT_ISSUE
        )
        original_journal = json.loads(original_journal_path.read_text(encoding="utf-8"))
        self.assertEqual("finalized", original_journal["status"])
        self.assertEqual("revision", original_journal["outcome"])

        authoritative_config = json.loads(
            (self.root / ".github" / "agent-workflow.json").read_text(encoding="utf-8")
        )
        authoritative_config["validation_profiles"]["workflow-tooling"]["setup"] = [
            {
                "name": "provision-eslint",
                "command": [sys.executable, "-c", "print('setup ok')"],
            }
        ]
        new_target = self.advance_remote_ref_from(
            first_target,
            {".github/agent-workflow.json": json.dumps(authoritative_config, indent=2) + "\n"},
        )
        live_pr = {
            "number": 300,
            "state": "OPEN",
            "isDraft": True,
            "baseRefName": "main",
            "headRefName": "parent-branch",
            "headRefOid": parent_state["implementation_commit"],
            "headRepository": {"nameWithOwner": "owner/repo"},
            "url": "https://example.test/pr/300",
        }
        with self.patch_gh_view_and_reflect_push(live_pr):
            code, payload, _ = self.run_cli(
                "recover-completed-run-reconciliation",
                str(self.PARENT_ISSUE),
                "--by",
                "owner",
                "--confirm",
                "completed_run_recovery_confirmed",
            )
        self.assertEqual(0, code)
        self.assertTrue(payload["ok"])
        self.assertEqual("WORKFLOW_COMPLETED", self.state_for(self.PARENT_ISSUE)["status"])
        self.assertEqual(first_target, original_journal["new_target_head"])
        self.assertEqual("TEST_IMPLEMENTATION", self.state_for(original_revision)["status"])
        recovery = json.loads(
            (
                self.root
                / ".agent-workflow"
                / "runs"
                / ("issue-%s" % self.PARENT_ISSUE)
                / "completed-run-reconciliation-recovery-transition.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(new_target, recovery["new_target_head"])
        self.assertEqual("reconciled", recovery["outcome"])

    def test_recover_completed_run_reconciliation_rejects_ordinary_finalized_revision(self):
        parent_state = self.bootstrap_completed_parent(self.PARENT_ISSUE)
        self.set_parent_draft_pr(
            self.PARENT_ISSUE,
            number=300,
            head_ref_name="parent-branch",
            head_ref_oid=parent_state["implementation_commit"],
            url="https://example.test/pr/300",
            repository="owner/repo",
        )
        self.advance_remote_ref_past_commit(
            parent_state["target_head"],
            {"unrelated.txt": "downstream change\n"},
        )
        with mock.patch.object(
            workflow,
            "_run_validation_checks",
            return_value=[
                {
                    "name": "workflow-check",
                    "command": [sys.executable, "-c", "raise SystemExit(1)"],
                    "cwd": ".",
                    "passed": False,
                    "result": {"outcome": "nonzero-exit", "exit_code": 1, "stderr": ""},
                }
            ],
        ):
            self.assertEqual(0, self.reconcile_completed_run(self.PARENT_ISSUE)[0])
        before = self.state_for(self.PARENT_ISSUE)
        code, payload, _ = self.run_cli(
            "recover-completed-run-reconciliation",
            str(self.PARENT_ISSUE),
            "--by",
            "owner",
            "--confirm",
            "completed_run_recovery_confirmed",
        )
        self.assertEqual(1, code)
        self.assertEqual("completed-run-recovery-not-eligible", payload["error"]["code"])
        self.assertEqual(before, self.state_for(self.PARENT_ISSUE))


    def validate_pr_body(self, content):
        body = self.root / "pr-body.md"
        body.write_text(content, encoding="utf-8")
        return workflow._validate_pr_body(body)

    def assert_invalid_pr_body(self, content):
        with self.assertRaises(workflow.WorkflowError):
            self.validate_pr_body(content)

    def test_governed_pr_body_enforces_structural_readability_boundaries(self):
        """PR prose checks reject unusable references and command-only testing."""
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nSee #345\n\n## Testing\nCovered the regression.\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nhttps://example.test/345\n\n## Testing\nCovered the regression.\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nABC-123\n\n## Testing\nCovered the regression.\n"
        )
        self.assert_invalid_pr_body(
            "## What\n\n## Why\nA concise reason.\n\n## Testing\nCovered the regression.\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nA concise reason.\n\n## Testing\n./gradlew test\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nA concise reason.\n\n"
            "## Testing\nRan make agent-workflow-test\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nA concise reason.\n\n"
            "## Testing\nValidation: npm run lint\n"
        )
        for testing in ("383 tests passed", "383 passing", "Tests: 383"):
            self.assert_invalid_pr_body(
                "## What\nA concise change.\n\n## Why\nA concise reason.\n\n"
                "## Testing\n%s\n" % testing
            )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nA concise reason.\n\n"
            "## Testing\nNot applicable.\n"
        )
        self.assert_invalid_pr_body(
            "## What\nA concise change.\n\n## Why\nA concise reason.\n\n"
            "## Testing\nNot applicable: blah.\n"
        )

    def test_governed_pr_body_accepts_ordinary_structural_content(self):
        """Ordinary concise, non-ASCII, and command-led explanatory prose remains valid."""
        self.validate_pr_body(
            "## What\nAdd the governed body check.\n\n"
            "## Why\nKeep published descriptions usable.\n\n"
            "## Testing\nRan make agent-workflow-test; regression cases passed.\n"
        )
        self.validate_pr_body(
            "## What\n修正 PR の説明形式。\n\n"
            "## Why\nレビュー時に意図を読みやすくするため。\n\n"
            "## Testing\nNot applicable — this documentation-only change has no executable behavior.\n"
        )

    def test_governed_pr_body_preserves_exact_heading_contract(self):
        """Structural content checks do not relax the exact heading contract."""
        self.assert_invalid_pr_body(
            "## What\nA change.\n\n## Why\nA reason.\n\n## Notes\n"
            "## Testing\nA scenario was covered.\n"
        )


if __name__ == "__main__":
    unittest.main()
