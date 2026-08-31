import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).parents[1] / "agent_workflow.py"
SPEC = importlib.util.spec_from_file_location("agent_workflow", MODULE_PATH)
workflow = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workflow)


class FakeRunner:
    def __init__(self):
        self.fail_commands = set()
        self.calls = []
        self.has_pr = False
        self.head = "abc123"
        self.base = "base123"
        self.base_is_ancestor = True
        self.commit_count = 1
        self.pr_base = "main"
        self.pr_head = self.head
        self.pr_title = "Fix issue"
        self.pr_body = ""
        self.pr_is_draft = True
        self.pr_state = "OPEN"
        self.pr_lookup_error = None
        self.pr_list_output = None
        self.pr_view_error = None
        self.multiple_prs = False
        self.command_effects = {}

    def pull_request(self):
        return {
            "url": "https://github.test/NathanZK/ChessEcho/pull/1",
            "isDraft": self.pr_is_draft,
            "state": self.pr_state,
            "baseRefName": self.pr_base,
            "headRefOid": self.pr_head,
            "title": self.pr_title,
            "body": self.pr_body,
        }

    def __call__(self, command, **kwargs):
        self.calls.append(command)
        if command[0:3] == ["git", "rev-parse", "HEAD"]:
            return subprocess.CompletedProcess(command, 0, self.head + "\n", "")
        if command[0:3] == ["git", "rev-parse", "--verify"]:
            return subprocess.CompletedProcess(command, 0, self.base + "\n", "")
        if command[0:3] == ["git", "merge-base", "--is-ancestor"]:
            return subprocess.CompletedProcess(command, 0 if self.base_is_ancestor else 1, "", "")
        if command[0:3] == ["git", "rev-list", "--count"]:
            return subprocess.CompletedProcess(command, 0, str(self.commit_count) + "\n", "")
        if command[0:3] == ["git", "symbolic-ref", "--quiet"]:
            return subprocess.CompletedProcess(command, 0, "feature\n", "")
        if command[0:3] == ["git", "ls-files", "-v"]:
            return subprocess.CompletedProcess(command, 0, "H production.txt\n", "")
        if command[0:3] == ["git", "status", "--porcelain=v1"]:
            return subprocess.CompletedProcess(command, 0, "", "")
        if command[0:3] == ["gh", "pr", "create"]:
            self.has_pr = True
            self.pr_title = command[command.index("--title") + 1]
            body_path = pathlib.Path(command[command.index("--body-file") + 1])
            self.pr_body = body_path.read_text()
            if "--base" in command:
                self.pr_base = command[command.index("--base") + 1]
            self.pr_head = self.head
            return subprocess.CompletedProcess(command, 0, "https://github.test/NathanZK/ChessEcho/pull/1\n", "")
        if command[0:3] == ["gh", "pr", "list"]:
            if self.pr_lookup_error:
                return subprocess.CompletedProcess(command, 1, "", self.pr_lookup_error)
            if self.pr_list_output is not None:
                return subprocess.CompletedProcess(command, 0, self.pr_list_output, "")
            matches = [] if not self.has_pr else [self.pull_request()]
            if self.multiple_prs and matches:
                matches.append(dict(matches[0], url="https://github.test/NathanZK/ChessEcho/pull/2"))
            return subprocess.CompletedProcess(command, 0, json.dumps(matches), "")
        if command[0:3] == ["gh", "pr", "view"]:
            if self.pr_view_error:
                error = self.pr_view_error
                self.pr_view_error = None
                return subprocess.CompletedProcess(command, 1, "", error)
            if not self.has_pr:
                return subprocess.CompletedProcess(command, 1, "", "no pull request")
            return subprocess.CompletedProcess(command, 0, json.dumps(self.pull_request()), "")
        effect = self.command_effects.get(command[0])
        if effect:
            effect()
        returncode = 1 if command[0] in self.fail_commands else 0
        return subprocess.CompletedProcess(command, returncode, "output", "error" if returncode else "")


class AgentWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.initialize()

    def initialize(self, target_base="main", checks=None, expected_init=0):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        config_dir = self.root / ".agent-workflow"
        config_dir.mkdir()
        (config_dir / "config.json").write_text(json.dumps({
            "target_base": target_base,
            "validation_profiles": {
                "backend": {
                    "test_paths": ["tests/**/*"],
                    "checks": checks if checks is not None else [
                        {"name": "lint", "command": ["lint"]},
                        {"name": "tests", "command": ["tests"]},
                    ]
                }
            }
        }))
        (self.root / "tests").mkdir()
        (self.root / "tests" / "behavior.test").write_text("expected behavior")
        self.issue_file = self.root / "issue.md"
        self.issue_file.write_text("## Acceptance criteria\n\n1. It works.\n")
        self.runner = FakeRunner()
        self.run_cli(
            "init", "42", "--scope", "backend", "--issue-file", str(self.issue_file),
            "--title", "Test issue",
            expected=expected_init,
        )

    def tearDown(self):
        self.temporary.cleanup()

    def run_cli(self, *arguments, expected=0):
        result = workflow.main(["--root", str(self.root), *arguments], runner=self.runner)
        self.assertEqual(expected, result)

    def artifact(self, name, content="artifact"):
        path = self.root / ".agent-workflow" / "runs" / "issue-42" / "artifacts" / name
        path.write_text(content)
        return str(path)

    def state(self):
        path = self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json"
        return json.loads(path.read_text())

    def pr_body(self, what="Changed behavior.", why="Required rationale.", testing="Tests passed."):
        return "## What\n%s\n\n## Why\n%s\n\n## Testing\n%s\n" % (
            what, why, testing,
        )

    def pr_mutations(self):
        return [
            call for call in self.runner.calls
            if call[0:3] == ["gh", "pr", "create"]
        ]

    def advance_to_implementation(self):
        self.run_cli("submit-plan", "42", "--artifact", self.artifact("plan.md"), "--agent", workflow.ROLE_NAMES["planner"])
        self.run_cli("review-plan", "42", "--artifact", self.artifact("plan-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        self.run_cli("approve-plan", "42", "--by", "human", "--confirm", "plan_approved")
        self.run_cli("submit-tests", "42", "--artifact", self.artifact("test-report.md"), "--agent", workflow.ROLE_NAMES["implementer"])
        self.run_cli("review-tests", "42", "--artifact", self.artifact("test-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        self.run_cli("approve-tests", "42", "--by", "human", "--confirm", "tests_approved")

    def advance_to_final_review(self):
        self.advance_to_implementation()
        self.run_cli("submit-implementation", "42", "--artifact", self.artifact("implementation.md"), "--agent", workflow.ROLE_NAMES["implementer"])
        self.run_cli("run-validation", "42")

    def advance_to_pr_gate(self):
        self.advance_to_final_review()
        self.run_cli("review-final", "42", "--artifact", self.artifact("final-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        body = self.artifact("pr.md", self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body)
        return body

    def test_happy_path_requires_every_gate(self):
        self.advance_to_final_review()
        self.assertEqual("FINAL_REVIEW", self.state()["state"])
        self.run_cli("review-final", "42", "--artifact", self.artifact("final-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        body = self.artifact("pr.md", self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body)
        create_call = next(
            call for call in self.runner.calls if call[0:3] == ["gh", "pr", "create"]
        )
        self.assertEqual("NathanZK/ChessEcho", create_call[create_call.index("--repo") + 1])
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.run_cli("approve-pr", "42", "--by", "human", "--confirm", "I approve this draft PR.")
        self.assertEqual("PR_APPROVED", self.state()["state"])

    def test_changed_head_and_content_require_revalidation(self):
        self.advance_to_final_review()
        self.runner.head = "def456"
        (self.root / "production.txt").write_text("changed")
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
            expected=2,
        )
        self.assertEqual("FINAL_REVIEW", self.state()["state"])

    def test_draft_pr_defaults_to_configured_target_base(self):
        self.temporary.cleanup()
        self.initialize(target_base="release")
        self.advance_to_pr_gate()
        self.assertEqual("release", self.runner.pr_base)

    def test_reviewer_readiness_cannot_replace_human_plan_approval(self):
        self.run_cli("submit-plan", "42", "--artifact", self.artifact("plan.md"), "--agent", workflow.ROLE_NAMES["planner"])
        self.run_cli("review-plan", "42", "--artifact", self.artifact("review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        self.run_cli("submit-tests", "42", "--artifact", self.artifact("tests.md"), "--agent", workflow.ROLE_NAMES["implementer"], expected=2)
        self.assertEqual("WAITING_FOR_PLAN_HUMAN_APPROVAL", self.state()["state"])

    def test_changed_plan_cannot_receive_human_approval(self):
        plan = self.artifact("plan.md")
        self.run_cli("submit-plan", "42", "--artifact", plan, "--agent", workflow.ROLE_NAMES["planner"])
        self.run_cli("review-plan", "42", "--artifact", self.artifact("review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        pathlib.Path(plan).write_text("changed after review")
        self.run_cli(
            "approve-plan", "42", "--by", "human", "--confirm", "plan_approved",
            expected=2,
        )
        self.assertEqual("WAITING_FOR_PLAN_HUMAN_APPROVAL", self.state()["state"])

    def test_production_changes_are_blocked_during_test_phase(self):
        self.run_cli("submit-plan", "42", "--artifact", self.artifact("plan.md"), "--agent", workflow.ROLE_NAMES["planner"])
        self.run_cli("review-plan", "42", "--artifact", self.artifact("review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        self.run_cli("approve-plan", "42", "--by", "human", "--confirm", "plan_approved")
        (self.root / "production.txt").write_text("implemented too early")
        self.run_cli(
            "submit-tests", "42",
            "--artifact", self.artifact("test-report.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("TEST_IMPLEMENTATION", self.state()["state"])

    def test_revision_loops_return_to_producer(self):
        self.run_cli("submit-plan", "42", "--artifact", self.artifact("plan.md"), "--agent", workflow.ROLE_NAMES["planner"])
        self.run_cli("review-plan", "42", "--artifact", self.artifact("review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.REVISION)
        self.assertEqual("PLANNING", self.state()["state"])

    def test_human_can_reopen_an_approved_plan(self):
        self.advance_to_implementation()
        self.run_cli(
            "reopen-plan", "42", "--by", "human",
            "--reason", "Implementation exposed a material design gap",
        )
        state = self.state()
        self.assertEqual("PLANNING", state["state"])
        self.assertFalse(state["approvals"]["plan"]["approved"])
        self.assertFalse(state["approvals"]["tests"]["approved"])

    def test_reopened_plan_discards_superseded_implementation_report(self):
        self.advance_to_final_review()
        self.run_cli(
            "reopen-plan", "42", "--by", "human",
            "--reason", "Implementation exposed a material design gap",
        )
        state = self.state()
        self.assertNotIn("implementation_report", state["artifacts"])
        self.run_cli(
            "submit-plan", "42",
            "--artifact", self.artifact("revised-plan.md", "revised plan"),
            "--agent", workflow.ROLE_NAMES["planner"],
        )
        self.assertEqual("PLAN_REVIEW", self.state()["state"])

    def test_failed_validation_returns_to_implementation(self):
        self.advance_to_implementation()
        self.run_cli("submit-implementation", "42", "--artifact", self.artifact("implementation.md"), "--agent", workflow.ROLE_NAMES["implementer"])
        self.runner.fail_commands.add("tests")
        self.run_cli("run-validation", "42", expected=2)
        state = self.state()
        self.assertEqual("IMPLEMENTATION", state["state"])
        self.assertEqual("FAIL", state["validation"]["tests"]["status"])

    def test_draft_pr_is_blocked_before_final_review(self):
        self.advance_to_implementation()
        body = self.root / "pr.md"
        body.write_text(self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", str(body), expected=2)
        self.assertFalse(any(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls))

    def test_artifacts_must_be_in_durable_run_directory(self):
        outside = self.root / "outside.md"
        outside.write_text("plan")
        self.run_cli("submit-plan", "42", "--artifact", str(outside), "--agent", workflow.ROLE_NAMES["planner"], expected=2)
        self.assertEqual("PLANNING", self.state()["state"])

    def test_approved_test_changes_block_implementation(self):
        self.advance_to_implementation()
        (self.root / "tests" / "behavior.test").write_text("weaker behavior")
        self.run_cli(
            "submit-implementation", "42",
            "--artifact", self.artifact("implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("IMPLEMENTATION", self.state()["state"])
        self.run_cli(
            "reopen-tests", "42", "--by", "human",
            "--reason", "The approved expectation was incomplete",
        )
        self.assertEqual("TEST_IMPLEMENTATION", self.state()["state"])

    def test_approved_artifact_changes_block_implementation(self):
        self.advance_to_implementation()
        plan = self.root / ".agent-workflow" / "runs" / "issue-42" / "artifacts" / "plan.md"
        plan.write_text("changed after approval")
        self.run_cli(
            "submit-implementation", "42",
            "--artifact", self.artifact("implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("IMPLEMENTATION", self.state()["state"])

    def test_workspace_changes_after_review_block_draft_pr(self):
        self.advance_to_final_review()
        self.run_cli("review-final", "42", "--artifact", self.artifact("final-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
        (self.root / "production.txt").write_text("changed after review")
        body = self.root / "pr.md"
        body.write_text(self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", str(body), expected=2)
        self.assertFalse(any(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls))

    def test_final_review_revision_recovers_after_workspace_change(self):
        self.advance_to_final_review()
        (self.root / "production.txt").write_text("revision needed")
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.REVISION,
        )
        state = self.state()
        self.assertEqual("IMPLEMENTATION", state["state"])
        self.assertIsNone(state["validated_fingerprint"])
        self.assertIsNone(state["validated_head"])
        self.assertFalse(state["validation"])
        self.assertEqual(workflow.REVISION, state["artifacts"]["final_review"]["status"])
        self.assertEqual("abc123", state["artifacts"]["final_review"]["subject_head"])
        body = self.artifact("pr.md", self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body, expected=2)

    def test_ready_review_rejects_post_validation_workspace_change(self):
        self.advance_to_final_review()
        (self.root / "production.txt").write_text("changed")
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
            expected=2,
        )
        self.assertEqual("FINAL_REVIEW", self.state()["state"])

    def test_changed_head_with_unchanged_tree_requires_revalidation(self):
        self.advance_to_final_review()
        self.runner.head = "def456"
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
            expected=2,
        )
        self.assertEqual("FINAL_REVIEW", self.state()["state"])

    def test_final_validation_records_head_and_base(self):
        self.advance_to_final_review()
        state = self.state()
        self.assertEqual("abc123", state["validated_head"])
        self.assertEqual("base123", state["validated_base"])
        self.assertEqual(
            state["approvals"]["tests"]["test_fingerprint"],
            state["validated_test_fingerprint"],
        )
        self.assertEqual(state["validated_head"], state["validation_evidence"]["head"])
        self.assertEqual(state["validated_base"], state["validation_evidence"]["base"])

    def test_tracking_ref_advance_after_validation_preserves_frozen_evidence(self):
        self.advance_to_pr_gate()
        evidence = dict(self.state()["validation_evidence"])
        self.runner.base = "new-origin-main"
        revised = self.artifact("revised-pr.md", self.pr_body(what="Clarified."))
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify", "--title", "Clarified fix", "--body-file", revised,
            expected=2,
        )
        self.runner.pr_title = "Clarified fix"
        self.runner.pr_body = pathlib.Path(revised).read_text()
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify", "--title", "Clarified fix", "--body-file", revised,
        )
        self.assertEqual(evidence, self.state()["validation_evidence"])
        self.run_cli(
            "approve-pr", "42", "--by", "human",
            "--confirm", "I approve this draft PR.",
        )

    def test_validation_rejects_repository_changes_during_checks(self):
        cases = (
            ("workspace", lambda: (self.root / "production.txt").write_text("changed")),
            ("head", lambda: setattr(self.runner, "head", "changed-head")),
            ("base", lambda: setattr(self.runner, "base", "changed-base")),
            ("tracked-test", lambda: (self.root / "tests" / "behavior.test").write_text("changed")),
        )
        for case, effect in cases:
            with self.subTest(case=case):
                self.tearDown()
                self.initialize()
                self.advance_to_implementation()
                self.run_cli(
                    "submit-implementation", "42",
                    "--artifact", self.artifact("implementation.md"),
                    "--agent", workflow.ROLE_NAMES["implementer"],
                )
                self.runner.command_effects["lint"] = effect
                self.run_cli("run-validation", "42", expected=2)
                state = self.state()
                self.assertEqual("IMPLEMENTATION", state["state"])
                self.assertIsNone(state["validation_evidence"])
                self.assertFalse(state["validation"])
                self.assertEqual("VALIDATION_INVALIDATED", state["history"][-1]["event"])

    def test_validation_config_rejects_unsafe_checks(self):
        invalid_checks = (
            [{"name": "../escape", "command": ["lint"]}],
            [{"name": "lint", "command": []}],
            [{"name": "lint", "command": [""]}],
            [{"name": "lint", "command": "lint"}],
            [{"name": "lint", "command": ["lint"], "cwd": "../outside"}],
        )
        for checks in invalid_checks:
            with self.subTest(checks=checks):
                self.tearDown()
                self.initialize(checks=checks, expected_init=2)
                self.assertFalse(
                    (self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json").exists()
                )

    def test_validation_rejects_non_ancestor_base(self):
        self.advance_to_implementation()
        self.run_cli("submit-implementation", "42", "--artifact", self.artifact("implementation.md"), "--agent", workflow.ROLE_NAMES["implementer"])
        self.runner.base_is_ancestor = False
        self.run_cli("run-validation", "42", expected=2)
        self.assertEqual("VALIDATION", self.state()["state"])
        self.assertFalse(any(call[0] in ("lint", "tests") for call in self.runner.calls))

    def test_validation_rejects_non_single_commit_history(self):
        self.advance_to_implementation()
        self.run_cli("submit-implementation", "42", "--artifact", self.artifact("implementation.md"), "--agent", workflow.ROLE_NAMES["implementer"])
        self.runner.commit_count = 2
        self.run_cli("run-validation", "42", expected=2)
        self.assertEqual("VALIDATION", self.state()["state"])
        self.assertFalse(any(call[0] in ("lint", "tests") for call in self.runner.calls))

    def test_draft_pr_invalid_inputs_never_mutate_github(self):
        invalid_cases = (
            ("body", lambda: None),
            ("base", lambda: None),
            ("head", lambda: setattr(self.runner, "head", "changed")),
            ("count", lambda: setattr(self.runner, "commit_count", 2)),
        )
        for case, mutate in invalid_cases:
            with self.subTest(case=case):
                self.tearDown()
                self.setUp()
                self.advance_to_final_review()
                self.run_cli("review-final", "42", "--artifact", self.artifact("final-review.md"), "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY)
                body_content = "missing headings" if case == "body" else self.pr_body()
                body = self.artifact("pr.md", body_content)
                mutate()
                arguments = ["create-draft-pr", "42", "--title", "Fix issue", "--body-file", body]
                if case == "base":
                    arguments.extend(["--base", "develop"])
                self.run_cli(*arguments, expected=2)
                self.assertFalse(any(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls))

    def test_pr_body_contract_rejects_invalid_sections(self):
        invalid_bodies = (
            "## What\nx\n## Why\ny\n",
            "## What\nx\n## What\ny\n## Why\nz\n## Testing\nt\n",
            "## Why\ny\n## What\nx\n## Testing\nt\n",
            "## What\nx\n## Why\ny\n## Testing\nt\n## Risks\nz\n",
            "## What\n\n## Why\ny\n## Testing\nt\n",
            "preamble\n## What\nx\n## Why\ny\n## Testing\nt\n",
            "## What   \nx\n## Why\ny\n## Testing\nt\n",
            "## What\nx\n```\n## Why\n```\n## Testing\nt\n",
            "## What\nx\n<!--\n## Why\n-->\n## Testing\nt\n",
            "## What\n<!-- hidden -->\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\n```\nhidden\n```\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\nvisible\n<!-- hidden -->## Why\nvisible\n## Testing\nvisible\n",
            "## What\nvisible\n## Why\nvisible\n## Testing\nvisible\n\nExtra\n-----\n",
            "## What\nvisible\n## Why\nvisible\n## Testing\nvisible\n> ## Extra\n",
            "## What\n[only]: https://example.com\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\nvisible\n## Why\nvisible\n## Testing\nvisible\n> Extra\n> ---\n",
            "## What\nvisible\n## Why\nvisible\n## Testing\nvisible\nExtra\n-\n",
            "## What\nvisible\n## Why\nvisible\n## Testing\nvisible\n> Extra\n> -\n",
            "## What\n[only]:\n  https://example.com\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\n[only]:\nhttps://example.com\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\n[only]:\nhttps://example.com \"title\"\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\n[only]:\nhttps://example.com\n\"title\"\n## Why\nvisible\n## Testing\nvisible\n",
            "## What\n[only]: https://example.com\n\"title\"\n## Why\nvisible\n## Testing\nvisible\n",
        )
        for body in invalid_bodies:
            with self.subTest(body=body):
                with self.assertRaises(workflow.WorkflowError):
                    workflow.validate_pr_body(body)

    def test_pr_body_contract_accepts_visible_content(self):
        workflow.validate_pr_body(self.pr_body())

    def test_metadata_only_revision_preserves_final_evidence(self):
        self.advance_to_pr_gate()
        validated_head = self.state()["validated_head"]
        evidence = dict(self.state()["validation_evidence"])
        revised = self.artifact("revised-pr.md", self.pr_body(what="Revised summary."))
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify summary", "--title", "Better title", "--body-file", revised,
            expected=2,
        )
        self.runner.pr_title = "Better title"
        self.runner.pr_body = pathlib.Path(revised).read_text()
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify summary", "--title", "Better title", "--body-file", revised,
        )
        state = self.state()
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", state["state"])
        self.assertEqual(validated_head, state["validated_head"])
        self.assertEqual(evidence, state["validation_evidence"])
        self.assertEqual("Better title", self.runner.pr_title)
        self.run_cli("approve-pr", "42", "--by", "human", "--confirm", "I approve this draft PR.")

    def test_metadata_only_revision_blocks_changed_final_state(self):
        mutations = (
            ("workspace", lambda: (self.root / "production.txt").write_text("changed")),
            ("head", lambda: setattr(self.runner, "head", "changed")),
            ("count", lambda: setattr(self.runner, "commit_count", 2)),
        )
        for case, mutate in mutations:
            with self.subTest(case=case):
                self.tearDown()
                self.setUp()
                self.advance_to_pr_gate()
                revised = self.artifact("revised-pr.md", self.pr_body(what="Revised."))
                mutate()
                self.run_cli(
                    "revise-pr-metadata", "42", "--by", "human",
                    "--reason", "Clarify", "--title", "Better", "--body-file", revised,
                    expected=2,
                )
                self.assertFalse(any(call[0:3] == ["gh", "api", "--method"] for call in self.runner.calls))

    def test_metadata_only_revision_rejects_invalid_body_without_github_mutation(self):
        self.advance_to_pr_gate()
        revised = self.artifact("revised-pr.md", "## What\nMissing sections.\n")
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify", "--title", "Better", "--body-file", revised,
            expected=2,
        )
        self.assertFalse(any(call[0:3] == ["gh", "api", "--method"] for call in self.runner.calls))

    def test_metadata_revision_rejects_external_metadata_change(self):
        self.advance_to_pr_gate()
        self.runner.pr_title = "Externally changed"
        revised = self.artifact("revised-pr.md", self.pr_body(what="Revised."))
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify", "--title", "Better", "--body-file", revised,
            expected=2,
        )
        self.assertFalse(any(call[0:3] == ["gh", "api", "--method"] for call in self.runner.calls))

    def test_metadata_revision_never_writes_remote_metadata(self):
        self.advance_to_pr_gate()
        original_fingerprint = self.state()["draft_pr"]["pr_fingerprint"]
        revised = self.artifact("revised-pr.md", self.pr_body(what="Revised."))
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Clarify", "--title", "Better", "--body-file", revised,
            expected=2,
        )
        self.assertEqual(original_fingerprint, self.state()["draft_pr"]["pr_fingerprint"])
        self.assertEqual("Fix issue", self.runner.pr_title)
        self.assertFalse(any(call[0:2] == ["gh", "api"] for call in self.runner.calls))

    def test_pr_lookup_failure_never_creates_or_edits(self):
        cases = (
            ("failure", lambda: setattr(self.runner, "pr_lookup_error", "authentication failed")),
            ("invalid-json", lambda: setattr(self.runner, "pr_list_output", "{")),
            ("multiple", lambda: (setattr(self.runner, "has_pr", True), setattr(self.runner, "multiple_prs", True))),
        )
        for case, configure in cases:
            with self.subTest(case=case):
                self.tearDown()
                self.initialize()
                self.advance_to_final_review()
                self.run_cli(
                    "review-final", "42", "--artifact", self.artifact("final-review.md"),
                    "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
                )
                configure()
                body = self.artifact("pr.md", self.pr_body())
                self.run_cli(
                    "create-draft-pr", "42", "--title", "Fix issue", "--body-file", body,
                    expected=2,
                )
                self.assertFalse(self.pr_mutations())

    def test_remote_creation_is_adopted_after_local_persistence_failure(self):
        self.advance_to_final_review()
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
        )
        body = self.artifact("pr.md", self.pr_body())
        self.runner.pr_view_error = "network unavailable"
        self.run_cli(
            "create-draft-pr", "42", "--title", "Fix issue", "--body-file", body,
            expected=2,
        )
        self.assertTrue(self.runner.has_pr)
        self.assertEqual("FINAL_REVIEW", self.state()["state"])
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body)
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.assertEqual(
            1,
            sum(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls),
        )

    def test_draft_pr_created_recovery_rechecks_live_pr(self):
        self.advance_to_pr_gate()
        state_path = self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json"
        state = self.state()
        state["state"] = "DRAFT_PR_CREATED"
        state_path.write_text(json.dumps(state))
        body = self.artifact("pr-recovery.md", self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body)
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.runner.pr_body = "externally changed"
        state = self.state()
        state["state"] = "DRAFT_PR_CREATED"
        state_path.write_text(json.dumps(state))
        self.run_cli(
            "create-draft-pr", "42", "--title", "Fix issue", "--body-file", body,
            expected=2,
        )

    def test_version_two_draft_recovery_reconstructs_frozen_evidence(self):
        self.advance_to_pr_gate()
        state_path = self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json"
        state = self.state()
        state["version"] = 2
        state["state"] = "DRAFT_PR_CREATED"
        state.pop("validation_evidence")
        state_path.write_text(json.dumps(state))
        body = self.artifact("pr-recovery.md", self.pr_body())
        self.run_cli("create-draft-pr", "42", "--title", "Fix issue", "--body-file", body)
        migrated = self.state()
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", migrated["state"])
        self.assertEqual(2, migrated["validation_evidence"]["migrated_from_version"])

    def test_approval_rejects_external_remote_invariants(self):
        cases = (
            ("head", lambda: setattr(self.runner, "pr_head", "changed")),
            ("base", lambda: setattr(self.runner, "pr_base", "release")),
            ("state", lambda: setattr(self.runner, "pr_state", "CLOSED")),
            ("title", lambda: setattr(self.runner, "pr_title", "changed")),
            ("body", lambda: setattr(self.runner, "pr_body", "changed")),
        )
        for case, mutate in cases:
            with self.subTest(case=case):
                self.tearDown()
                self.initialize()
                self.advance_to_pr_gate()
                mutate()
                self.run_cli(
                    "approve-pr", "42", "--by", "human",
                    "--confirm", "I approve this draft PR.", expected=2,
                )
                self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])

    def test_implementation_rejection_clears_final_evidence(self):
        self.advance_to_pr_gate()
        self.run_cli(
            "reject-pr", "42", "--by", "human",
            "--reason", "Implementation must change",
        )
        state = self.state()
        self.assertEqual("IMPLEMENTATION", state["state"])
        self.assertFalse(state["validation"])
        self.assertIsNone(state["validated_head"])
        self.assertIsNone(state["final_review"])
        self.assertIsNone(state["draft_pr"])

    def test_implementation_rejection_can_complete_a_new_final_cycle(self):
        self.advance_to_pr_gate()
        self.run_cli(
            "reject-pr", "42", "--by", "human",
            "--reason", "Implementation must change",
        )
        self.runner.head = "def456"
        self.runner.pr_head = "def456"
        self.run_cli(
            "submit-implementation", "42",
            "--artifact", self.artifact("implementation-2.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        self.run_cli("run-validation", "42")
        self.run_cli(
            "review-final", "42",
            "--artifact", self.artifact("final-review-2.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
        )
        body = self.artifact("pr-2.md", self.pr_body(what="Revised implementation."))
        self.run_cli(
            "create-draft-pr", "42", "--title", "Revised fix", "--body-file", body,
            expected=2,
        )
        self.assertFalse(any(
            call[0:3] == ["gh", "api", "--method"] for call in self.runner.calls
        ))
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Match revised implementation",
            "--title", "Revised fix", "--body-file", body,
            expected=2,
        )
        self.runner.pr_title = "Revised fix"
        self.runner.pr_body = pathlib.Path(body).read_text()
        self.run_cli(
            "revise-pr-metadata", "42", "--by", "human",
            "--reason", "Match revised implementation",
            "--title", "Revised fix", "--body-file", body,
        )
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.assertEqual("Revised fix", self.runner.pr_title)
        self.assertFalse(any(call[0:2] == ["gh", "api"] for call in self.runner.calls))

    def test_tests_can_be_reopened_from_validation(self):
        self.advance_to_implementation()
        (self.root / "production.txt").write_text("implemented behavior")
        self.run_cli(
            "submit-implementation", "42",
            "--artifact", self.artifact("implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        (self.root / "tests" / "behavior.test").write_text("needs correction")
        self.run_cli("run-validation", "42", expected=2)
        self.run_cli(
            "reopen-tests", "42", "--by", "human",
            "--reason", "Approved test is incorrect",
        )
        state = self.state()
        self.assertEqual("TEST_IMPLEMENTATION", state["state"])
        self.assertNotIn("implementation_report", state["artifacts"])
        self.run_cli(
            "submit-tests", "42",
            "--artifact", self.artifact("revised-test-report.md", "revised tests"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        self.assertEqual("TEST_REVIEW", self.state()["state"])

    def test_changed_implementation_report_blocks_final_review(self):
        self.advance_to_final_review()
        report = self.root / ".agent-workflow" / "runs" / "issue-42" / "artifacts" / "implementation.md"
        report.write_text("changed after validation")
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
            expected=2,
        )
        self.assertEqual("FINAL_REVIEW", self.state()["state"])

    def test_changed_implementation_report_can_recover_through_revision(self):
        self.advance_to_final_review()
        report = self.root / ".agent-workflow" / "runs" / "issue-42" / "artifacts" / "implementation.md"
        report.write_text("changed after validation")
        self.run_cli(
            "review-final", "42", "--artifact", self.artifact("final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"],
            "--status", workflow.REVISION,
        )
        self.assertEqual("IMPLEMENTATION", self.state()["state"])

    def test_pr_approval_rechecks_frozen_base_and_history(self):
        for case in ("evidence", "count"):
            with self.subTest(case=case):
                self.tearDown()
                self.setUp()
                self.advance_to_pr_gate()
                if case == "evidence":
                    state_path = self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json"
                    state = self.state()
                    state["validated_base"] = "corrupt"
                    state_path.write_text(json.dumps(state))
                else:
                    self.runner.commit_count = 2
                self.run_cli(
                    "approve-pr", "42", "--by", "human",
                    "--confirm", "I approve this draft PR.", expected=2,
                )
                self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])

    def test_fingerprint_includes_executable_mode(self):
        path = self.root / "production.sh"
        path.write_text("#!/bin/sh\n")
        before = workflow.files_fingerprint(self.root)
        path.chmod(0o755)
        after = workflow.files_fingerprint(self.root)
        self.assertNotEqual(before, after)

    def test_fingerprint_excludes_only_repository_run_directory(self):
        nested_root = (
            self.root / "ancestor" / "runs" / ".agent-workflow" / "repository"
        )
        nested_root.mkdir(parents=True)
        source = nested_root / "source.txt"
        source.write_text("before")
        before = workflow.files_fingerprint(nested_root)
        source.write_text("after")
        after = workflow.files_fingerprint(nested_root)
        self.assertNotEqual(before, after)

    def test_repository_config_includes_frontend_test_support(self):
        config = json.loads(
            (MODULE_PATH.parents[1] / ".agent-workflow" / "config.json").read_text()
        )
        for profile_name in ("frontend", "full-stack"):
            paths = config["validation_profiles"][profile_name]["test_paths"]
            self.assertIn("frontend/src/**/__tests__/**/*", paths)
            self.assertIn("frontend/src/**/__mocks__/**/*", paths)
            self.assertIn("frontend/test/**/*", paths)
            self.assertIn("frontend/tests/**/*", paths)

    def test_pr_repository_verification_is_case_insensitive(self):
        workflow.verify_pr_repository(
            {"url": "https://github.test/nathanzk/chessecho/pull/1"},
            "NathanZK/ChessEcho",
        )


if __name__ == "__main__":
    unittest.main()
