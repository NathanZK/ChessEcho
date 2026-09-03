import base64
import contextlib
import hashlib
import io
import json
import pathlib
import re
import subprocess
import tempfile
import unittest

from scripts import agent_workflow as workflow


MODULE_PATH = pathlib.Path(__file__).parents[1] / "agent_workflow.py"

CORRECTION_CLASSIFICATIONS = (
    "metadata-only",
    "implementation-only",
    "test-contract",
    "architecture",
)
ARTIFACT_TOKENS = (
    "artifact:plan",
    "artifact:plan_review",
    "artifact:test_report",
    "artifact:test_review",
    "artifact:implementation_report",
    "artifact:final_review",
)
APPROVAL_TOKENS = ("approval:plan", "approval:tests", "approval:pr")
EVIDENCE_TOKENS = (
    "evidence:validation",
    "evidence:validated_fingerprint",
    "evidence:validated_head",
    "evidence:validated_base",
    "evidence:validated_test_fingerprint",
    "evidence:validation_evidence",
    "evidence:final_review",
    "evidence:draft_pr",
)
CORRECTION_TOKENS = frozenset(ARTIFACT_TOKENS + APPROVAL_TOKENS + EVIDENCE_TOKENS)
INHERITED_TOKENS = {
    "metadata-only": frozenset(
        ARTIFACT_TOKENS + ("approval:plan", "approval:tests") + EVIDENCE_TOKENS
    ),
    "implementation-only": frozenset({
        "artifact:plan",
        "artifact:plan_review",
        "artifact:test_report",
        "artifact:test_review",
        "approval:plan",
        "approval:tests",
    }),
    "test-contract": frozenset({
        "artifact:plan", "artifact:plan_review", "approval:plan",
    }),
    "architecture": frozenset(),
}
STATUS_SUMMARY_KEYS = (
    "issue",
    "state",
    "scope",
    "target_base",
    "approvals",
    "validation",
    "final_review",
    "validated_head",
    "validated_base",
    "validated_test_fingerprint",
    "validation_evidence",
    "draft_pr",
)
CORRECTION_ADDRESSED_COMMANDS = (
    ("status", ()),
    ("submit-plan", ("--artifact", "plan.md", "--agent", "chess-echo-planner")),
    ("submit-tests", ("--artifact", "tests.md", "--agent", "chess-echo-implementer")),
    ("submit-implementation", ("--artifact", "impl.md", "--agent", "chess-echo-implementer")),
    ("review-plan", ("--artifact", "r.md", "--reviewer", "chess-echo-reviewer", "--status", workflow.READY)),
    ("review-tests", ("--artifact", "r.md", "--reviewer", "chess-echo-reviewer", "--status", workflow.READY)),
    ("review-final", ("--artifact", "r.md", "--reviewer", "chess-echo-reviewer", "--status", workflow.READY)),
    ("approve-plan", ("--by", "human", "--confirm", "plan_approved")),
    ("approve-tests", ("--by", "human", "--confirm", "tests_approved")),
    ("approve-pr", ("--by", "human", "--confirm", "I approve this draft PR.")),
    ("reject-plan", ("--by", "human", "--reason", "why")),
    ("reject-tests", ("--by", "human", "--reason", "why")),
    ("reject-pr", ("--by", "human", "--reason", "why")),
    ("reopen-tests", ("--by", "human", "--reason", "why")),
    ("reopen-plan", ("--by", "human", "--reason", "why")),
    ("run-validation", ()),
    ("create-draft-pr", ("--title", "Fix issue", "--body-file", "pr.md")),
    ("revise-pr-metadata", ("--by", "human", "--reason", "why", "--title", "T", "--body-file", "pr.md")),
)


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
        self.dirty_status_output = None

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
        if (
            command[0:3] == ["git", "rev-parse", "--verify"]
            and len(command) > 3
            and command[3].endswith("^{commit}")
        ):
            revision = command[3][: -len("^{commit}")]
            return subprocess.CompletedProcess(command, 0, revision + "\n", "")
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
            return subprocess.CompletedProcess(command, 0, self.dirty_status_output or "", "")
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

    def advance_to_pr_approved(self):
        body = self.advance_to_pr_gate()
        self.run_cli("approve-pr", "42", "--by", "human", "--confirm", "I approve this draft PR.")
        return body

    def initialize_workflow_tooling(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        config_dir = self.root / ".agent-workflow"
        config_dir.mkdir()
        (config_dir / "config.json").write_text(json.dumps({
            "target_base": "main",
            "validation_profiles": {
                "backend": {
                    "test_paths": ["tests/**/*"],
                    "checks": [{"name": "lint", "command": ["lint"]}],
                },
                "workflow-tooling": {
                    "test_paths": ["scripts/tests/**/*"],
                    "checks": [
                        {
                            "name": "agent-workflow-tests",
                            "command": ["make", "agent-workflow-test"],
                            "cwd": ".",
                        }
                    ],
                },
            }
        }))
        (self.root / "tests").mkdir()
        (self.root / "tests" / "behavior.test").write_text("expected behavior")
        (self.root / "scripts" / "tests").mkdir(parents=True)
        (self.root / "scripts" / "tests" / "placeholder.test").write_text("workflow tooling")
        self.issue_file = self.root / "issue.md"
        self.issue_file.write_text("## Acceptance criteria\n\n1. It works.\n")
        self.runner = FakeRunner()
        self.run_cli(
            "init", "42", "--scope", "workflow-tooling", "--issue-file", str(self.issue_file),
            "--title", "Workflow tooling issue",
        )

    def start_correction(self, classification, *extra, expected=0):
        self.run_cli(
            "start-correction", "42",
            "--classification", classification,
            "--by", "human",
            "--reason", "Post-approval correction",
            *extra,
            expected=expected,
        )

    def source_dir(self):
        return self.root / ".agent-workflow" / "runs" / "issue-42"

    def correction_dir(self, number):
        return self.source_dir() / "corrections" / str(number)

    def correction_state(self, number):
        return json.loads((self.correction_dir(number) / "state.json").read_text())

    def correction_artifact(self, number, name, content="artifact"):
        directory = self.correction_dir(number) / "artifacts"
        self.assertTrue(
            directory.is_dir(),
            "correction %s must own an artifacts directory" % number,
        )
        path = directory / name
        path.write_text(content)
        return str(path)

    def run_bytes(self, directory):
        return (
            (directory / "state.json").read_bytes(),
            (directory / "history.jsonl").read_bytes(),
        )

    def integrity_path(self, directory=None):
        return (directory or self.source_dir()) / "integrity.json"

    def integrity(self, directory=None):
        return json.loads(self.integrity_path(directory).read_text())

    def authoritative_bytes(self, directory=None):
        directory = directory or self.source_dir()
        return self.run_bytes(directory) + (self.integrity_path(directory).read_bytes(),)

    def write_projections(self, state, directory=None, write_history=True):
        directory = directory or self.source_dir()
        (directory / "state.json").write_text(
            json.dumps(state, indent=2, sort_keys=True) + "\n"
        )
        if write_history:
            (directory / "history.jsonl").write_text(
                "".join(
                    json.dumps(event, sort_keys=True) + "\n"
                    for event in state["history"]
                )
            )

    def make_legacy(self, version=3, directory=None):
        directory = directory or self.source_dir()
        state = json.loads((directory / "state.json").read_text())
        state["version"] = version
        self.write_projections(state, directory)
        if self.integrity_path(directory).exists():
            self.integrity_path(directory).unlink()
        return self.run_bytes(directory)

    def adopt_legacy(self, *extra, correction=None, expected=0):
        arguments = [
            "adopt-legacy-run", "42", "--by", "NathanZK",
            "--reason", "Trust the pre-integrity run after review",
            "--confirm", "legacy_run_trusted",
        ]
        if correction is not None:
            arguments.extend(("--correction", str(correction)))
        arguments.extend(extra)
        self.run_cli(*arguments, expected=expected)

    def settle_implementation_correction(self, number, head):
        self.drive_correction_to_pr_gate(number, head=head)

    def assert_hashes_match_bytes(self, parent, source_bytes):
        self.assertEqual(
            hashlib.sha256(source_bytes[0]).hexdigest(), parent["state_sha256"]
        )
        self.assertEqual(
            hashlib.sha256(source_bytes[1]).hexdigest(), parent["history_sha256"]
        )

    def assert_rejected_without_authority_change(
        self, arguments, directory=None, expected=2
    ):
        directory = directory or self.source_dir()
        before = (
            (directory / "state.json").read_bytes(),
            (directory / "history.jsonl").read_bytes(),
            self.integrity_path(directory).read_bytes()
            if self.integrity_path(directory).exists() else None,
        )
        calls = list(self.runner.calls)
        self.run_cli(*arguments, expected=expected)
        after = (
            (directory / "state.json").read_bytes(),
            (directory / "history.jsonl").read_bytes(),
            self.integrity_path(directory).read_bytes()
            if self.integrity_path(directory).exists() else None,
        )
        self.assertEqual(before, after)
        self.assertEqual(calls, self.runner.calls)

    def capture_status(self, *extra):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            self.run_cli("status", "42", *extra)
        return json.loads(stream.getvalue())

    def assert_usage_error(self, *arguments):
        stream = io.StringIO()
        with contextlib.redirect_stderr(stream):
            with self.assertRaises(SystemExit) as raised:
                workflow.main(["--root", str(self.root), *arguments], runner=self.runner)
        self.assertEqual(2, raised.exception.code)
        return stream.getvalue()

    def assert_no_correction(self, number):
        self.assertFalse((self.correction_dir(number) / "state.json").exists())

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
        self.assert_rejected_without_authority_change((
            "create-draft-pr", "42", "--title", "Fix issue", "--body-file", body,
        ))
        self.assert_rejected_without_authority_change(("recover-run", "42"))

    def test_version_two_draft_recovery_reconstructs_frozen_evidence(self):
        self.advance_to_pr_gate()
        state_path = self.root / ".agent-workflow" / "runs" / "issue-42" / "state.json"
        state = self.state()
        state["version"] = 2
        state["state"] = "DRAFT_PR_CREATED"
        state.pop("validation_evidence")
        state_path.write_text(json.dumps(state))
        body = self.artifact("pr-recovery.md", self.pr_body())
        self.assert_rejected_without_authority_change((
            "create-draft-pr", "42", "--title", "Fix issue", "--body-file", body,
        ))
        self.assert_rejected_without_authority_change(("recover-run", "42"))
        self.assert_rejected_without_authority_change((
            "adopt-legacy-run", "42", "--by", "NathanZK",
            "--reason", "Trust the pre-integrity run after review",
            "--confirm", "legacy_run_trusted",
        ))

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

    # --- Issue #117: linked correction runs ------------------------------

    def test_start_correction_requires_pr_gate_state(self):
        self.advance_to_implementation()
        self.start_correction("metadata-only", expected=2)
        self.assert_no_correction(1)
        self.assertEqual("IMPLEMENTATION", self.state()["state"])

    def test_start_correction_from_waiting_for_pr_human_approval(self):
        self.advance_to_pr_gate()
        source = self.state()
        self.start_correction("metadata-only")
        child = self.correction_state(1)
        parent = child["parent_run"]
        self.assertEqual(42, parent["issue"])
        self.assertIsNone(parent["correction"])
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", parent["state"])
        self.assertEqual(source["validated_head"], parent["validated_head"])
        self.assertEqual(source["validated_base"], parent["validated_base"])
        self.assertEqual(
            hashlib.sha256((self.source_dir() / "state.json").read_bytes()).hexdigest(),
            parent["state_sha256"],
        )
        self.assertEqual(
            hashlib.sha256((self.source_dir() / "history.jsonl").read_bytes()).hexdigest(),
            parent["history_sha256"],
        )
        correction = child["correction"]
        self.assertEqual(1, correction["number"])
        self.assertEqual("metadata-only", correction["classification"])
        self.assertEqual("Post-approval correction", correction["reason"])
        self.assertEqual("human", correction["requested_by"])
        self.assertTrue(correction["created_at"])
        self.assertEqual(source["issue"], child["issue"])
        self.assertEqual(1, len(child["history"]))
        self.assertEqual("CORRECTION_CREATED", child["history"][0]["event"])
        self.assertTrue((self.correction_dir(1) / "history.jsonl").is_file())

    def test_start_correction_from_pr_approved(self):
        self.advance_to_pr_approved()
        self.start_correction("implementation-only")
        child = self.correction_state(1)
        self.assertEqual("PR_APPROVED", child["parent_run"]["state"])
        self.assertEqual("abc123", child["parent_run"]["validated_head"])
        self.assertEqual("base123", child["parent_run"]["validated_base"])
        self.assertEqual("PR_APPROVED", self.state()["state"])

    def test_invalid_classification_is_rejected(self):
        self.advance_to_pr_gate()
        message = self.assert_usage_error(
            "start-correction", "42", "--classification", "bogus",
            "--by", "human", "--reason", "Post-approval correction",
        )
        self.assertIn("--classification", message)
        self.assertIn("bogus", message)
        self.assert_no_correction(1)

    def test_correction_actor_and_reason_are_required(self):
        self.advance_to_pr_gate()
        missing_actor = self.assert_usage_error(
            "start-correction", "42", "--classification", "metadata-only",
            "--reason", "Post-approval correction",
        )
        self.assertIn("--by", missing_actor)
        missing_reason = self.assert_usage_error(
            "start-correction", "42", "--classification", "metadata-only",
            "--by", "human",
        )
        self.assertIn("--reason", missing_reason)
        self.assert_no_correction(1)

    def test_metadata_only_inherits_full_evidence(self):
        self.advance_to_pr_gate()
        source = self.state()
        self.start_correction("metadata-only")
        child = self.correction_state(1)
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", child["state"])
        self.assertEqual(
            {
                "plan", "plan_review", "test_report", "test_review",
                "implementation_report", "final_review",
            },
            set(child["artifacts"]),
        )
        for kind, record in source["artifacts"].items():
            self.assertEqual(record, child["artifacts"][kind])
        self.assertEqual({"plan", "tests"}, set(child["approvals"]))
        self.assertEqual(source["approvals"]["plan"], child["approvals"]["plan"])
        self.assertEqual(source["approvals"]["tests"], child["approvals"]["tests"])
        self.assertEqual(source["validation"], child["validation"])
        for field in (
            "validated_fingerprint", "validated_head", "validated_base",
            "validated_test_fingerprint", "validation_evidence", "final_review",
            "draft_pr",
        ):
            self.assertIsNotNone(child[field])
            self.assertEqual(source[field], child[field])
        revised = self.correction_artifact(1, "revised-pr.md", self.pr_body(what="Clarified."))
        self.run_cli(
            "revise-pr-metadata", "42", "--correction", "1", "--by", "human",
            "--reason", "Clarify", "--title", "Clarified fix", "--body-file", revised,
            expected=2,
        )
        self.runner.pr_title = "Clarified fix"
        self.runner.pr_body = pathlib.Path(revised).read_text()
        self.run_cli(
            "revise-pr-metadata", "42", "--correction", "1", "--by", "human",
            "--reason", "Clarify", "--title", "Clarified fix", "--body-file", revised,
        )
        self.run_cli(
            "approve-pr", "42", "--correction", "1", "--by", "human",
            "--confirm", "I approve this draft PR.",
        )
        self.assertEqual("PR_APPROVED", self.correction_state(1)["state"])
        self.assertTrue(self.correction_state(1)["approvals"]["pr"]["approved"])
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.assertNotIn("pr", self.state()["approvals"])

    def test_metadata_only_refuses_changed_workspace_head_or_dirty_tree(self):
        cases = (
            ("workspace", lambda: (self.root / "production.txt").write_text("changed")),
            ("head", lambda: setattr(self.runner, "head", "def456")),
            (
                "dirty",
                lambda: setattr(
                    self.runner, "dirty_status_output", " M production.txt\0"
                ),
            ),
        )
        for case, mutate in cases:
            with self.subTest(case=case):
                self.tearDown()
                self.setUp()
                self.advance_to_pr_gate()
                mutate()
                self.start_correction("metadata-only", expected=2)
                self.assert_no_correction(1)

    def test_implementation_only_clears_downstream_evidence(self):
        self.advance_to_pr_gate()
        source = self.state()
        self.start_correction("implementation-only")
        child = self.correction_state(1)
        self.assertEqual("IMPLEMENTATION", child["state"])
        self.assertEqual({}, child["validation"])
        for field in (
            "validated_fingerprint", "validated_head", "validated_base",
            "validated_test_fingerprint", "validation_evidence", "final_review",
            "draft_pr",
        ):
            self.assertIsNone(child[field])
        self.assertEqual(
            {"plan", "plan_review", "test_report", "test_review"},
            set(child["artifacts"]),
        )
        self.assertEqual({"plan", "tests"}, set(child["approvals"]))
        self.assertEqual(
            source["approvals"]["tests"]["test_fingerprint"],
            child["approvals"]["tests"]["test_fingerprint"],
        )
        self.assertEqual(
            source["approvals"]["plan"]["non_test_fingerprint"],
            child["approvals"]["plan"]["non_test_fingerprint"],
        )

    def test_test_contract_rederives_non_test_fingerprint(self):
        self.advance_to_pr_gate()
        source = self.state()
        (self.root / "production.txt").write_text("implemented behavior")
        self.start_correction("test-contract")
        child = self.correction_state(1)
        self.assertEqual("TEST_IMPLEMENTATION", child["state"])
        self.assertEqual({"plan", "plan_review"}, set(child["artifacts"]))
        self.assertEqual({"plan"}, set(child["approvals"]))
        self.assertNotEqual(
            source["approvals"]["plan"]["non_test_fingerprint"],
            child["approvals"]["plan"]["non_test_fingerprint"],
        )
        self.run_cli(
            "submit-tests", "42", "--correction", "1",
            "--artifact", self.artifact("test-report.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("TEST_IMPLEMENTATION", self.correction_state(1)["state"])
        self.run_cli(
            "submit-tests", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "test-report.md", "corrected tests"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        child = self.correction_state(1)
        self.assertEqual("TEST_REVIEW", child["state"])
        self.assertEqual(
            ".agent-workflow/runs/issue-42/corrections/1/artifacts/test-report.md",
            child["artifacts"]["test_report"]["path"],
        )

    def test_architecture_correction_starts_empty(self):
        self.advance_to_pr_gate()
        source = self.state()
        self.start_correction("architecture")
        child = self.correction_state(1)
        self.assertEqual("PLANNING", child["state"])
        self.assertEqual({}, child["artifacts"])
        self.assertEqual({}, child["approvals"])
        self.assertEqual({}, child["validation"])
        for field in ("required_checks", "test_paths", "target_base", "scope"):
            self.assertEqual(source[field], child[field])
        self.run_cli(
            "submit-plan", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "plan.md", "corrected plan"),
            "--agent", workflow.ROLE_NAMES["planner"],
        )
        self.assertEqual("PLAN_REVIEW", self.correction_state(1)["state"])

    def test_source_run_files_are_untouched_by_a_fork(self):
        for classification in CORRECTION_CLASSIFICATIONS:
            with self.subTest(classification=classification):
                self.tearDown()
                self.setUp()
                self.advance_to_pr_gate()
                before = self.run_bytes(self.source_dir())
                self.start_correction(classification)
                self.assertEqual(before, self.run_bytes(self.source_dir()))

    def test_correction_ancestry_is_enforced(self):
        self.advance_to_pr_gate()
        self.runner.base_is_ancestor = False
        self.start_correction("implementation-only", expected=2)
        self.assert_no_correction(1)
        self.runner.base_is_ancestor = True
        self.runner.commit_count = 2
        self.start_correction("implementation-only", expected=2)
        self.assert_no_correction(1)
        self.runner.commit_count = 0
        self.start_correction("implementation-only")
        self.assertEqual("IMPLEMENTATION", self.correction_state(1)["state"])

    def test_one_commit_invariant_anchors_on_the_source_head(self):
        self.advance_to_pr_gate()
        source_log = (self.source_dir() / "validation" / "lint.log").read_bytes()
        self.start_correction("implementation-only")
        self.run_cli(
            "submit-implementation", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        self.run_cli("run-validation", "42", "--correction", "1")
        child = self.correction_state(1)
        self.assertEqual("FINAL_REVIEW", child["state"])
        self.assertIn(["git", "rev-list", "--count", "abc123..HEAD"], self.runner.calls)
        self.assertTrue(child["validation_evidence"]["base_ref"].startswith("parent-run-head:"))
        self.assertEqual("abc123", child["validated_base"])
        self.assertEqual(
            ".agent-workflow/runs/issue-42/corrections/1/validation/lint.log",
            child["validation"]["lint"]["log"],
        )
        self.assertTrue((self.correction_dir(1) / "validation" / "lint.log").is_file())
        self.assertEqual(
            source_log, (self.source_dir() / "validation" / "lint.log").read_bytes()
        )
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])

    def test_correction_retains_head_and_single_commit_safeguards(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.run_cli(
            "submit-implementation", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        self.runner.commit_count = 2
        self.run_cli("run-validation", "42", "--correction", "1", expected=2)
        self.assertEqual("VALIDATION", self.correction_state(1)["state"])
        self.runner.commit_count = 1
        self.run_cli("run-validation", "42", "--correction", "1")
        self.runner.head = "def456"
        self.run_cli(
            "review-final", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
            expected=2,
        )
        self.assertEqual("FINAL_REVIEW", self.correction_state(1)["state"])
        self.runner.head = "abc123"
        self.run_cli(
            "review-final", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
        )
        (self.root / "production.txt").write_text("changed after final review")
        self.run_cli(
            "create-draft-pr", "42", "--correction", "1", "--title", "Fix issue",
            "--body-file", self.correction_artifact(1, "pr.md", self.pr_body()),
            expected=2,
        )
        self.assertEqual(
            1, sum(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls)
        )

    def test_child_run_is_excluded_from_fingerprint_and_worktree(self):
        self.advance_to_pr_gate()
        before = workflow.files_fingerprint(self.root)
        self.start_correction("implementation-only")
        self.assertEqual(before, workflow.files_fingerprint(self.root))
        self.runner.dirty_status_output = (
            "?? .agent-workflow/runs/issue-42/corrections/1/state.json\0"
        )
        workflow.require_clean_worktree(self.root, self.runner)

    def test_status_output_contract_is_preserved_for_normal_runs(self):
        self.advance_to_pr_gate()
        state = self.state()
        summary = self.capture_status()
        self.assertEqual(set(STATUS_SUMMARY_KEYS) | {"corrections"}, set(summary))
        self.assertNotIn("artifacts", summary)
        self.assertNotIn("validated_fingerprint", summary)
        self.assertEqual([], summary["corrections"])
        for key in STATUS_SUMMARY_KEYS:
            if key == "validation":
                self.assertEqual(
                    {name: result["status"] for name, result in state["validation"].items()},
                    summary["validation"],
                )
            else:
                self.assertEqual(state[key], summary[key])

    def test_status_reports_corrections_and_child_addressing(self):
        self.advance_to_pr_gate()
        self.start_correction("metadata-only")
        self.start_correction("metadata-only")
        summary = self.capture_status()
        self.assertEqual([1, 2], [entry["number"] for entry in summary["corrections"]])
        for entry in summary["corrections"]:
            self.assertEqual("metadata-only", entry["classification"])
            self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", entry["state"])
            self.assertEqual("human", entry["requested_by"])
            self.assertTrue(entry["created_at"])
        child_summary = self.capture_status("--correction", "1")
        self.assertEqual(
            set(STATUS_SUMMARY_KEYS) | {"correction", "parent_run"},
            set(child_summary),
        )
        self.assertEqual(1, child_summary["correction"]["number"])
        self.assertEqual("abc123", child_summary["parent_run"]["validated_head"])

    def test_implementation_only_escalates_to_reopen_tests(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        (self.root / "tests" / "behavior.test").write_text("weaker behavior")
        self.run_cli(
            "submit-implementation", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("IMPLEMENTATION", self.correction_state(1)["state"])
        self.run_cli(
            "reopen-tests", "42", "--correction", "1", "--by", "human",
            "--reason", "The approved expectation was incomplete",
        )
        child = self.correction_state(1)
        self.assertEqual("TEST_IMPLEMENTATION", child["state"])
        self.assertFalse(child["approvals"]["tests"]["approved"])
        self.assertEqual("WAITING_FOR_PR_HUMAN_APPROVAL", self.state()["state"])
        self.assertTrue(self.state()["approvals"]["tests"]["approved"])

    def test_test_contract_escalates_to_reopen_plan(self):
        self.advance_to_pr_gate()
        self.start_correction("test-contract")
        (self.root / "production.txt").write_text("unexpected production change")
        self.run_cli(
            "submit-tests", "42", "--correction", "1",
            "--artifact", self.correction_artifact(1, "test-report.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
            expected=2,
        )
        self.assertEqual("TEST_IMPLEMENTATION", self.correction_state(1)["state"])
        self.run_cli(
            "reopen-plan", "42", "--correction", "1", "--by", "human",
            "--reason", "The correction needs a material design change",
        )
        child = self.correction_state(1)
        self.assertEqual("PLANNING", child["state"])
        self.assertFalse(child["approvals"]["plan"]["approved"])
        self.assertTrue(self.state()["approvals"]["plan"]["approved"])

    def drive_correction_to_pr_gate(self, number, head=None):
        self.run_cli(
            "submit-implementation", "42", "--correction", str(number),
            "--artifact", self.correction_artifact(number, "implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        if head is not None:
            self.runner.head = head
            self.runner.pr_head = head
        self.run_cli("run-validation", "42", "--correction", str(number))
        self.run_cli(
            "review-final", "42", "--correction", str(number),
            "--artifact", self.correction_artifact(number, "final-review.md"),
            "--reviewer", workflow.ROLE_NAMES["reviewer"], "--status", workflow.READY,
        )
        self.run_cli(
            "create-draft-pr", "42", "--correction", str(number),
            "--title", "Fix issue",
            "--body-file", self.correction_artifact(number, "pr.md", self.pr_body()),
        )
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL", self.correction_state(number)["state"]
        )

    def test_sibling_guard_blocks_active_work_and_allows_chaining(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.assertEqual("IMPLEMENTATION", self.correction_state(1)["state"])
        self.start_correction("implementation-only", expected=2)
        self.assert_no_correction(2)
        self.drive_correction_to_pr_gate(1, head="def456")
        self.start_correction("implementation-only", "--from-correction", "1")
        chained = self.correction_state(2)
        self.assertEqual(2, chained["correction"]["number"])
        self.assertEqual(1, chained["parent_run"]["correction"])
        self.assertEqual("def456", self.correction_state(1)["validated_head"])
        self.assertEqual("def456", chained["parent_run"]["validated_head"])
        self.assertEqual(
            hashlib.sha256((self.correction_dir(1) / "state.json").read_bytes()).hexdigest(),
            chained["parent_run"]["state_sha256"],
        )
        self.assertEqual(
            hashlib.sha256(
                (self.correction_dir(1) / "history.jsonl").read_bytes()
            ).hexdigest(),
            chained["parent_run"]["history_sha256"],
        )
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL", chained["parent_run"]["state"]
        )

    def test_stale_inherited_artifact_fails_the_correction_closed(self):
        self.advance_to_pr_gate()
        self.start_correction("metadata-only")
        (self.source_dir() / "artifacts" / "plan.md").write_text("changed after approval")
        self.run_cli(
            "approve-pr", "42", "--correction", "1", "--by", "human",
            "--confirm", "I approve this draft PR.",
            expected=2,
        )
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL", self.correction_state(1)["state"]
        )

    def test_inherited_and_invalidated_evidence_is_exhaustive_and_disjoint(self):
        for classification in CORRECTION_CLASSIFICATIONS:
            with self.subTest(classification=classification):
                self.tearDown()
                self.setUp()
                self.advance_to_pr_gate()
                self.start_correction(classification)
                correction = self.correction_state(1)["correction"]
                inherited = set(correction["inherited"])
                invalidated = set(correction["invalidated"])
                self.assertEqual(len(inherited), len(correction["inherited"]))
                self.assertEqual(len(invalidated), len(correction["invalidated"]))
                self.assertEqual(CORRECTION_TOKENS, inherited | invalidated)
                self.assertEqual(set(), inherited & invalidated)
                self.assertEqual(INHERITED_TOKENS[classification], inherited)

    def test_chained_correction_leaves_every_ancestor_byte_identical(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.drive_correction_to_pr_gate(1, head="def456")
        source_before = self.run_bytes(self.source_dir())
        correction_before = self.run_bytes(self.correction_dir(1))
        self.start_correction("implementation-only", "--from-correction", "1")
        self.run_cli(
            "submit-implementation", "42", "--correction", "2",
            "--artifact", self.correction_artifact(2, "implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        before_validation = len(self.runner.calls)
        self.run_cli("run-validation", "42", "--correction", "2")
        validation_calls = self.runner.calls[before_validation:]
        self.assertIn(["git", "rev-list", "--count", "def456..HEAD"], validation_calls)
        self.assertNotIn(["git", "rev-list", "--count", "abc123..HEAD"], validation_calls)
        self.assertEqual("def456", self.correction_state(2)["validated_base"])
        self.assertEqual("FINAL_REVIEW", self.correction_state(2)["state"])
        self.assertEqual(source_before, self.run_bytes(self.source_dir()))
        self.assertEqual(correction_before, self.run_bytes(self.correction_dir(1)))

    def test_metadata_only_correction_requires_an_open_draft(self):
        self.advance_to_pr_gate()
        self.start_correction("metadata-only")
        self.runner.pr_state = "CLOSED"
        revised = self.correction_artifact(1, "revised-pr.md", self.pr_body(what="Clarified."))
        self.run_cli(
            "revise-pr-metadata", "42", "--correction", "1", "--by", "human",
            "--reason", "Clarify", "--title", "Clarified fix", "--body-file", revised,
            expected=2,
        )
        self.assertEqual(1, len(self.pr_mutations()))
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL", self.correction_state(1)["state"]
        )

    def test_code_changing_correction_opens_a_new_draft_when_none_is_open(self):
        self.advance_to_pr_approved()
        self.start_correction("implementation-only")
        self.runner.pr_list_output = "[]"
        self.drive_correction_to_pr_gate(1)
        self.assertEqual(
            2, sum(call[0:3] == ["gh", "pr", "create"] for call in self.runner.calls)
        )
        self.assertEqual("PR_APPROVED", self.state()["state"])

    def test_every_downstream_command_accepts_correction_addressing(self):
        self.advance_to_pr_gate()
        before = self.run_bytes(self.source_dir())
        for command, arguments in CORRECTION_ADDRESSED_COMMANDS:
            with self.subTest(command=command):
                self.run_cli(
                    command, "42", *arguments, "--correction", "9", expected=2
                )
                self.assertEqual(before, self.run_bytes(self.source_dir()))

    def test_stray_state_less_correction_directory_never_blocks_the_run(self):
        self.advance_to_pr_gate()
        self.run_cli("status", "42", "--correction", "9", expected=2)
        self.assert_no_correction(9)
        self.correction_dir(9).mkdir(parents=True, exist_ok=True)
        self.assertEqual([], self.capture_status()["corrections"])
        self.start_correction("metadata-only")
        self.assertEqual(1, self.correction_state(1)["correction"]["number"])
        self.assertTrue((self.correction_dir(1) / "state.json").is_file())
        self.assert_no_correction(10)
        self.assertEqual(
            [1], [entry["number"] for entry in self.capture_status()["corrections"]]
        )

    # --- Issue #120: bounded validation and guarded authority -------------

    def test_routine_artifact_transition_is_bounded_and_has_no_subprocess_work(self):
        self.runner.calls.clear()
        self.run_cli(
            "submit-plan", "42", "--artifact", self.artifact("plan.md"),
            "--agent", workflow.ROLE_NAMES["planner"],
        )
        self.assertEqual("PLAN_REVIEW", self.state()["state"])
        self.assertEqual([], self.runner.calls)
        self.assertEqual("PLAN_SUBMITTED", self.state()["history"][-1]["event"])

    def test_run_validation_retains_deep_repository_and_check_evidence(self):
        self.advance_to_implementation()
        self.run_cli(
            "submit-implementation", "42",
            "--artifact", self.artifact("implementation.md"),
            "--agent", workflow.ROLE_NAMES["implementer"],
        )
        self.runner.calls.clear()
        self.run_cli("run-validation", "42")
        calls = self.runner.calls
        self.assertEqual(2, sum(call[:3] == ["git", "rev-parse", "HEAD"] for call in calls))
        self.assertEqual(
            2, sum(call[:3] == ["git", "status", "--porcelain=v1"] for call in calls)
        )
        self.assertEqual(
            2, sum(call[:3] == ["git", "merge-base", "--is-ancestor"] for call in calls)
        )
        self.assertEqual(
            2, sum(call[:3] == ["git", "rev-list", "--count"] for call in calls)
        )
        self.assertIn(["lint"], calls)
        self.assertIn(["tests"], calls)
        evidence = self.state()["validation_evidence"]
        self.assertEqual(self.runner.head, evidence["head"])
        self.assertEqual(self.runner.base, evidence["base"])
        self.assertEqual(1, evidence["commit_count"])
        self.assertEqual(
            self.state()["approvals"]["tests"]["test_fingerprint"],
            evidence["approved_test_fingerprint"],
        )

    def test_v4_initialization_and_transition_create_verifiable_envelopes(self):
        state = self.state()
        envelope = self.integrity()
        self.assertEqual(4, state["version"])
        self.assertEqual("v4-committed", envelope["mode"])
        self.assertEqual(42, envelope["issue"])
        self.assertIsNone(envelope["correction"])
        self.assertEqual(state["history"][-1]["sequence"], envelope["sequence"])
        self.assertEqual(state, envelope["state"])
        self.assertEqual(state["history"], envelope["history"])
        self.assertEqual(
            hashlib.sha256((self.source_dir() / "state.json").read_bytes()).hexdigest(),
            envelope["state_sha256"],
        )
        self.assertEqual(
            hashlib.sha256((self.source_dir() / "history.jsonl").read_bytes()).hexdigest(),
            envelope["history_sha256"],
        )
        previous = self.integrity_path().read_bytes()
        self.run_cli(
            "submit-plan", "42", "--artifact", self.artifact("plan.md"),
            "--agent", workflow.ROLE_NAMES["planner"],
        )
        self.assertNotEqual(previous, self.integrity_path().read_bytes())
        self.capture_status()

    def test_history_append_delete_reorder_and_replace_are_rejected(self):
        mutations = {
            "append": lambda lines: lines + [lines[-1]],
            "delete": lambda lines: lines[:-1],
            "reorder": lambda lines: list(reversed(lines)),
            "replace": lambda lines: [
                json.dumps(dict(json.loads(lines[0]), actor="forger"), sort_keys=True)
            ] + lines[1:],
        }
        for name, mutate in mutations.items():
            with self.subTest(mutation=name):
                self.tearDown()
                self.setUp()
                self.run_cli(
                    "submit-plan", "42", "--artifact", self.artifact("plan.md"),
                    "--agent", workflow.ROLE_NAMES["planner"],
                )
                history_path = self.source_dir() / "history.jsonl"
                lines = history_path.read_text().splitlines()
                history_path.write_text("\n".join(mutate(lines)) + "\n")
                self.assert_rejected_without_authority_change(("status", "42"))

    def test_forged_lifecycle_state_is_rejected_before_transition(self):
        state = self.state()
        state["state"] = "WAITING_FOR_PLAN_HUMAN_APPROVAL"
        state["history"][-1]["state"] = state["state"]
        self.write_projections(state)
        self.assert_rejected_without_authority_change((
            "approve-plan", "42", "--by", "forger", "--confirm", "plan_approved",
        ))

    def test_forged_approvals_are_rejected_by_real_gate_commands(self):
        for approval in ("plan", "tests", "pr"):
            with self.subTest(approval=approval):
                self.tearDown()
                self.setUp()
                if approval == "plan":
                    self.run_cli(
                        "submit-plan", "42", "--artifact", self.artifact("plan.md"),
                        "--agent", workflow.ROLE_NAMES["planner"],
                    )
                    self.run_cli(
                        "review-plan", "42",
                        "--artifact", self.artifact("plan-review.md"),
                        "--reviewer", workflow.ROLE_NAMES["reviewer"],
                        "--status", workflow.READY,
                    )
                    self.run_cli(
                        "approve-plan", "42", "--by", "human",
                        "--confirm", "plan_approved",
                    )
                    command = (
                        "submit-tests", "42",
                        "--artifact", self.artifact("test-report.md"),
                        "--agent", workflow.ROLE_NAMES["implementer"],
                    )
                elif approval == "tests":
                    self.advance_to_implementation()
                    command = (
                        "submit-implementation", "42",
                        "--artifact", self.artifact("implementation.md"),
                        "--agent", workflow.ROLE_NAMES["implementer"],
                    )
                elif approval == "pr":
                    self.advance_to_pr_approved()
                    command = (
                        "start-correction", "42",
                        "--classification", "implementation-only",
                        "--by", "human",
                        "--reason", "Post-approval correction",
                    )
                state = self.state()
                state["approvals"][approval].update(
                    {"by": "forger", "confirmation": "forged"}
                )
                self.write_projections(state)
                self.assertFalse(self.correction_dir(1).exists())
                self.assert_rejected_without_authority_change(command)
                self.assertFalse(self.correction_dir(1).exists())

    def test_coherent_state_and_history_forgery_is_rejected_by_envelope(self):
        state = self.state()
        state["state"] = "PR_APPROVED"
        state["approvals"]["plan"] = {"approved": True, "by": "forger"}
        state["history"][-1]["state"] = "PR_APPROVED"
        self.write_projections(state)
        self.assert_rejected_without_authority_change(("status", "42"))

    def test_missing_corrupt_stale_and_wrong_identity_integrity_fail_closed(self):
        def missing(path, envelope):
            path.unlink()

        def corrupt(path, envelope):
            path.write_text("{")

        def stale(path, envelope):
            envelope["state_sha256"] = "0" * 64
            path.write_text(json.dumps(envelope))

        def wrong_identity(path, envelope):
            envelope["issue"] = 99
            path.write_text(json.dumps(envelope))

        for name, mutation in (
            ("missing", missing),
            ("corrupt", corrupt),
            ("stale", stale),
            ("wrong-identity", wrong_identity),
        ):
            with self.subTest(case=name):
                self.tearDown()
                self.setUp()
                path = self.integrity_path()
                mutation(path, self.integrity())
                self.assert_rejected_without_authority_change(("status", "42"))

    def test_active_v4_recovery_restores_committed_state_without_replaying_action(self):
        for projections in ("state-only", "state-and-history"):
            with self.subTest(projections=projections):
                self.tearDown()
                self.setUp()
                self.advance_to_implementation()
                committed = self.state()
                forged = json.loads(json.dumps(committed))
                forged["state"] = "VALIDATION"
                forged["approvals"]["pr"] = {"approved": True, "by": "forger"}
                forged["history"].append({
                    "sequence": len(forged["history"]) + 1,
                    "timestamp": "2099-01-01T00:00:00+00:00",
                    "event": "IMPLEMENTATION_SUBMITTED",
                    "actor": "forger",
                    "state": "VALIDATION",
                    "details": {},
                })
                self.write_projections(
                    forged, write_history=projections == "state-and-history"
                )
                self.runner.calls.clear()
                self.run_cli("recover-run", "42")
                recovered = self.state()
                self.assertEqual(committed["state"], recovered["state"])
                self.assertEqual(committed["approvals"], recovered["approvals"])
                self.assertEqual(
                    committed["history"], recovered["history"][:-1]
                )
                event = recovered["history"][-1]
                self.assertEqual("RUN_INTEGRITY_RECOVERED", event["event"])
                self.assertEqual("chess-echo-orchestrator", event["actor"])
                self.assertEqual([], self.runner.calls)
                self.assertEqual({"plan", "tests"}, set(recovered["approvals"]))
                self.assertNotIn("implementation_report", recovered["artifacts"])
                self.run_cli(
                    "submit-implementation", "42",
                    "--artifact", self.artifact("implementation.md"),
                    "--agent", workflow.ROLE_NAMES["implementer"],
                )
                self.assertEqual("VALIDATION", self.state()["state"])

    def test_recovery_fails_closed_for_legacy_ambiguous_and_settled_runs(self):
        self.make_legacy()
        self.assert_rejected_without_authority_change(("recover-run", "42"))

        self.tearDown()
        self.setUp()
        self.assert_rejected_without_authority_change(("recover-run", "42"))

        self.tearDown()
        self.setUp()
        self.integrity_path().write_text("{")
        self.assert_rejected_without_authority_change(("recover-run", "42"))

        self.tearDown()
        self.setUp()
        self.advance_to_pr_gate()
        settled = self.state()
        settled["draft_pr"]["title"] = "forged"
        self.write_projections(settled)
        self.assert_rejected_without_authority_change(("recover-run", "42"))

    def test_recover_run_independently_rejects_invalid_envelopes_without_effects(self):
        def stale(envelope):
            envelope["state_sha256"] = "0" * 64

        def wrong_issue(envelope):
            envelope["issue"] = 99

        def wrong_correction(envelope):
            envelope["correction"] = 1

        def invalid_snapshot_sequence(envelope):
            envelope["history"][-1]["sequence"] += 1

        for name, mutation in (
            ("stale", stale),
            ("wrong-root-identity", wrong_issue),
            ("wrong-correction-identity", wrong_correction),
            ("invalid-snapshot-sequence", invalid_snapshot_sequence),
        ):
            with self.subTest(case=name):
                self.tearDown()
                self.setUp()
                self.advance_to_implementation()
                envelope = self.integrity()
                mutation(envelope)
                self.integrity_path().write_text(json.dumps(envelope))
                committed = self.state()
                lifecycle = committed["state"]
                approvals = json.loads(json.dumps(committed["approvals"]))
                history = json.loads(json.dumps(committed["history"]))
                self.runner.calls.clear()
                self.assert_rejected_without_authority_change(("recover-run", "42"))
                unchanged = self.state()
                self.assertEqual(lifecycle, unchanged["state"])
                self.assertEqual(approvals, unchanged["approvals"])
                self.assertEqual(history, unchanged["history"])
                self.assertEqual([], self.runner.calls)

    def test_unadopted_legacy_run_rejects_reads_and_transitions(self):
        self.make_legacy()
        self.assert_rejected_without_authority_change(("status", "42"))
        self.assert_rejected_without_authority_change((
            "submit-plan", "42", "--artifact", self.artifact("plan.md"),
            "--agent", workflow.ROLE_NAMES["planner"],
        ))
        with self.assertRaises(workflow.WorkflowError):
            workflow.read_run_state(self.root, 42)

    def test_active_v1_v2_v3_adoption_audits_reason_and_exact_prior_hashes(self):
        reason = "Trust the pre-integrity run after review"
        for version in (1, 2, 3):
            with self.subTest(version=version):
                self.tearDown()
                self.setUp()
                before = self.make_legacy(version)
                self.runner.calls.clear()
                self.adopt_legacy()
                state = self.state()
                self.assertEqual(4, state["version"])
                self.assertEqual("PLANNING", state["state"])
                self.assertEqual({}, state["approvals"])
                event = state["history"][-1]
                self.assertEqual("LEGACY_RUN_ADOPTED", event["event"])
                self.assertEqual("NathanZK", event["actor"])
                self.assertEqual(2, len(state["history"]))
                self.assertEqual(version, event["details"]["legacy_version"])
                self.assertEqual(reason, event["details"]["reason"])
                self.assert_hashes_match_bytes(event["details"], before)
                self.assertEqual("v4-committed", self.integrity()["mode"])
                self.assertEqual([], self.runner.calls)

    def test_legacy_adoption_requires_the_exact_confirmation(self):
        before = self.make_legacy()
        self.assert_rejected_without_authority_change((
            "adopt-legacy-run", "42", "--by", "NathanZK",
            "--reason", "Reviewed legacy run",
            "--confirm", "legacy-run-trusted",
        ))
        self.assertEqual(before, self.run_bytes(self.source_dir()))

    def test_invalid_conflicting_and_re_adoption_attempts_fail_closed(self):
        for case in ("inconsistent", "structurally-invalid"):
            with self.subTest(case=case):
                self.tearDown()
                self.setUp()
                self.make_legacy()
                if case == "inconsistent":
                    history = self.source_dir() / "history.jsonl"
                    history.write_text(
                        history.read_text().replace("WORKFLOW_INITIALIZED", "FORGED")
                    )
                else:
                    state = self.state()
                    del state["history"][0]["sequence"]
                    self.write_projections(state)
                self.assert_rejected_without_authority_change((
                    "adopt-legacy-run", "42", "--by", "NathanZK",
                    "--reason", "Reviewed legacy run",
                    "--confirm", "legacy_run_trusted",
                ))

        self.tearDown()
        self.setUp()
        self.make_legacy()
        self.adopt_legacy()
        self.assert_rejected_without_authority_change((
            "adopt-legacy-run", "42", "--by", "NathanZK",
            "--reason", "Trust the pre-integrity run after review",
            "--confirm", "legacy_run_trusted",
        ))

        self.tearDown()
        self.setUp()
        self.advance_to_pr_gate()
        self.make_legacy()
        self.adopt_legacy()
        self.assert_rejected_without_authority_change((
            "adopt-legacy-run", "42", "--by", "NathanZK",
            "--reason", "A conflicting trust rationale",
            "--confirm", "legacy_run_trusted",
        ))

    def test_settled_legacy_adoption_is_sidecar_only_and_verifies_status(self):
        self.advance_to_pr_gate()
        before = self.make_legacy()
        self.adopt_legacy()
        self.assertEqual(before, self.run_bytes(self.source_dir()))
        sidecar = self.integrity()
        self.assertEqual("settled-legacy-adoption", sidecar["mode"])
        self.assertEqual(3, sidecar["legacy_version"])
        self.assertEqual("NathanZK", sidecar["adopted_by"])
        self.assertEqual("legacy_run_trusted", sidecar["confirmation"])
        self.assertEqual("Trust the pre-integrity run after review", sidecar["reason"])
        self.assertEqual(
            hashlib.sha256(before[0]).hexdigest(), sidecar["state_sha256"]
        )
        self.assertEqual(
            hashlib.sha256(before[1]).hexdigest(), sidecar["history_sha256"]
        )
        encoded_sidecar = json.dumps(sidecar)
        self.assertIn(base64.b64encode(before[0]).decode(), encoded_sidecar)
        self.assertIn(base64.b64encode(before[1]).decode(), encoded_sidecar)
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL", self.capture_status()["state"]
        )
        self.assertEqual(before, self.run_bytes(self.source_dir()))

    def test_settled_legacy_correction_adoption_is_sidecar_only(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.settle_implementation_correction(1, "def456")
        child = self.correction_dir(1)
        before = self.make_legacy(directory=child)
        self.adopt_legacy(correction=1)
        self.assertEqual(before, self.run_bytes(child))
        sidecar = self.integrity(child)
        self.assertEqual("settled-legacy-adoption", sidecar["mode"])
        self.assertEqual(42, sidecar["issue"])
        self.assertEqual(1, sidecar["correction"])
        self.assertEqual(3, sidecar["legacy_version"])
        self.assertEqual("NathanZK", sidecar["adopted_by"])
        self.assertEqual("legacy_run_trusted", sidecar["confirmation"])
        self.assertEqual("Trust the pre-integrity run after review", sidecar["reason"])
        self.assert_hashes_match_bytes(sidecar, before)
        encoded_sidecar = json.dumps(sidecar)
        self.assertIn(base64.b64encode(before[0]).decode(), encoded_sidecar)
        self.assertIn(base64.b64encode(before[1]).decode(), encoded_sidecar)
        self.assertEqual(
            "WAITING_FOR_PR_HUMAN_APPROVAL",
            self.capture_status("--correction", "1")["state"],
        )
        self.assertEqual(before, self.run_bytes(child))

    def test_chaining_from_adopted_legacy_preserves_every_settled_ancestor(self):
        self.advance_to_pr_gate()
        root_before = self.make_legacy()
        self.adopt_legacy()
        self.start_correction("implementation-only")
        child = self.correction_state(1)
        self.assertEqual(4, child["version"])
        self.assert_hashes_match_bytes(child["parent_run"], root_before)
        self.assertEqual("v4-committed", self.integrity(self.correction_dir(1))["mode"])
        self.settle_implementation_correction(1, "def456")
        child_before = self.run_bytes(self.correction_dir(1))
        self.start_correction(
            "implementation-only", "--from-correction", "1"
        )
        second = self.correction_state(2)
        self.assert_hashes_match_bytes(second["parent_run"], child_before)
        self.assertEqual(root_before, self.run_bytes(self.source_dir()))
        self.assertEqual(child_before, self.run_bytes(self.correction_dir(1)))

    def test_tampered_correction_source_read_is_isolated(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.settle_implementation_correction(1, "def456")
        child = self.correction_dir(1)
        child_state = self.correction_state(1)
        child_state["state"] = "PR_APPROVED"
        self.write_projections(child_state, child)
        before = self.authoritative_bytes(child)
        self.start_correction(
            "implementation-only", "--from-correction", "1", expected=2
        )
        self.assertEqual(before, self.authoritative_bytes(child))
        self.assert_no_correction(2)

    def test_tampered_latest_correction_read_is_isolated(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.settle_implementation_correction(1, "def456")
        child = self.correction_dir(1)
        child_state = self.correction_state(1)
        child_state["state"] = "PR_APPROVED"
        self.write_projections(child_state, child)
        before = self.authoritative_bytes(child)
        self.start_correction("implementation-only", expected=2)
        self.assertEqual(before, self.authoritative_bytes(child))
        self.assert_no_correction(2)

    def test_tampered_sibling_correction_read_is_isolated(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.settle_implementation_correction(1, "def456")
        self.start_correction("implementation-only", "--from-correction", "1")
        self.settle_implementation_correction(2, "fed789")
        sibling = self.correction_dir(1)
        sibling_state = self.correction_state(1)
        sibling_state["state"] = "PR_APPROVED"
        self.write_projections(sibling_state, sibling)
        before = self.authoritative_bytes(sibling)
        self.start_correction(
            "implementation-only", "--from-correction", "2", expected=2
        )
        self.assertEqual(before, self.authoritative_bytes(sibling))
        self.assert_no_correction(3)

    def test_unadopted_legacy_correction_rejects_summary_and_correction_source(self):
        self.advance_to_pr_gate()
        self.start_correction("implementation-only")
        self.settle_implementation_correction(1, "def456")
        child = self.correction_dir(1)
        before = self.make_legacy(directory=child)
        self.run_cli("status", "42", expected=2)
        self.start_correction(
            "implementation-only", "--from-correction", "1", expected=2
        )
        self.assertEqual(before, self.run_bytes(child))
        self.assert_no_correction(2)

    def test_parser_covers_guarded_adoption_recovery_and_command_categories(self):
        parser = workflow.build_parser()
        adopted = parser.parse_args([
            "adopt-legacy-run", "42", "--by", "NathanZK", "--reason", "reviewed",
            "--confirm", "legacy_run_trusted", "--correction", "2",
        ])
        self.assertEqual("adopt-legacy-run", adopted.command)
        self.assertEqual(2, adopted.correction)
        recovered = parser.parse_args(["recover-run", "42", "--correction", "2"])
        self.assertEqual("recover-run", recovered.command)
        self.assertEqual(2, recovered.correction)
        self.assertFalse(hasattr(recovered, "agent"))
        guarded = {
            parser.parse_args([command, "42", *arguments]).command
            for command, arguments in CORRECTION_ADDRESSED_COMMANDS
        }
        self.assertEqual(
            {command for command, _ in CORRECTION_ADDRESSED_COMMANDS}, guarded
        )

    def test_guide_declares_the_complete_bounded_validation_policy(self):
        repository = MODULE_PATH.parents[1]
        guide = (repository / "docs/engineering/agent-workflow.md").read_text()

        def policy_section(document):
            lines = document.splitlines()
            for index, line in enumerate(lines):
                match = re.match(r"^(#{2,4})\s+(.+)$", line)
                if not match:
                    continue
                title = match.group(2).lower()
                if "bounded" not in title or "validation" not in title:
                    continue
                level = len(match.group(1))
                end = len(lines)
                for candidate in range(index + 1, len(lines)):
                    next_heading = re.match(r"^(#+)\s+", lines[candidate])
                    if next_heading and len(next_heading.group(1)) <= level:
                        end = candidate
                        break
                return "\n".join(lines[index:end]).lower()
            self.fail("guide must contain an explicit bounded-validation policy section")

        policy = policy_section(guide)
        for declaration in (
            "uncertainty or risk",
            "impact and reversibility",
            "source insufficiency",
            "smallest probe",
            "stopping result",
        ):
            self.assertRegex(
                policy,
                r"(?m)^\s*(?:[-*]\s*)?(?:\*\*)?%s(?:\*\*)?\s*:"
                % re.escape(declaration),
            )

        required_concepts = {
            "validation levels": ("mandatory validation", "optional/deep validation"),
            "mandatory evidence": (
                "issue", "contract", "symbol", "signature", "call site",
                "acceptance", "precondition", "test", "approval", "integrity",
                "migration", "recovery", "git", "pull request",
            ),
            "sufficient stopping evidence": (
                "source alignment", "executability", "acceptance coverage",
                "relevant risk", "open findings", "sufficient evidence",
            ),
            "repeat-pass reasons": (
                "changed scope", "changed source", "new evidence",
                "open finding", "newly named risk",
            ),
            "prohibited routine techniques": (
                "broad reinspection", "scratch implementation", "transcribed harness",
                "copied harness", "mutation campaign", "exhaustive experiment",
            ),
            "high-risk triggers": (
                "integrity", "approval", "security", "migration", "recovery",
                "irreversible", "destructive", "external contract",
                "external dependency", "final certification", "material uncertainty",
            ),
            "fail-closed exceptions": (
                "contradiction", "unknown lifecycle", "unknown signature",
                "insufficient high-risk evidence", "fail closed",
            ),
            "authority controls": (
                "adopt-legacy-run", "recover-run", "direct authority mutation",
            ),
        }
        for contract, terms in required_concepts.items():
            with self.subTest(contract=contract):
                missing = [term for term in terms if term not in policy]
                self.assertEqual([], missing)

    def test_each_role_declares_its_phase_specific_validation_obligations(self):
        repository = MODULE_PATH.parents[1]
        profiles = {
            role: (repository / ".github/agents" / ("chess-echo-%s.md" % role))
            .read_text()
            for role in ("planner", "reviewer", "implementer", "orchestrator")
        }

        def bounded_section(role, document):
            lines = document.splitlines()
            for index, line in enumerate(lines):
                match = re.match(r"^(#{2,4})\s+(.+)$", line)
                if not match:
                    continue
                title = match.group(2).lower()
                if "bounded" not in title or "validation" not in title:
                    continue
                level = len(match.group(1))
                end = len(lines)
                for candidate in range(index + 1, len(lines)):
                    next_heading = re.match(r"^(#+)\s+", lines[candidate])
                    if next_heading and len(next_heading.group(1)) <= level:
                        end = candidate
                        break
                return "\n".join(lines[index:end]).lower()
            self.fail("%s profile must have an explicit bounded-validation section" % role)

        sections = {
            role: bounded_section(role, content)
            for role, content in profiles.items()
        }
        common = (
            "mandatory", "optional", "deep", "uncertainty", "impact",
            "reversibility", "source insufficiency", "smallest probe",
            "stopping result", "direct authority mutation",
        )
        for role, content in sections.items():
            with self.subTest(role=role):
                self.assertEqual([], [term for term in common if term not in content])

        role_contracts = {
            "planner": (
                "source alignment", "executability", "implementation-level testing",
                "source insufficiency", "stop",
            ),
            "reviewer": (
                "targeted", "evidence-based", "stop", "open finding",
                "high-risk", "implementation-level testing",
            ),
            "implementer": (
                "test authoring", "approved tests", "routine", "bounded",
                "configured validation", "high-risk",
            ),
            "orchestrator": (
                "routine execution", "status", "documented preconditions",
                "adopt-legacy-run", "recover-run", "high-risk",
                "inferred approval",
            ),
        }
        for role, terms in role_contracts.items():
            with self.subTest(role_contract=role):
                self.assertEqual(
                    [], [term for term in terms if term not in sections[role]]
                )

    def test_workflow_tooling_profile_initialises(self):
        self.tearDown()
        self.initialize_workflow_tooling()
        state = self.state()
        self.assertEqual("workflow-tooling", state["scope"])
        self.assertEqual(["scripts/tests/**/*"], state["test_paths"])
        self.assertEqual(
            [
                {
                    "name": "agent-workflow-tests",
                    "command": ["make", "agent-workflow-test"],
                    "cwd": ".",
                }
            ],
            state["required_checks"],
        )

    def test_repository_config_defines_the_workflow_tooling_profile(self):
        config = json.loads(
            (MODULE_PATH.parents[1] / ".agent-workflow" / "config.json").read_text()
        )
        profile = config["validation_profiles"]["workflow-tooling"]
        self.assertIn("scripts/tests/**/*", profile["test_paths"])
        self.assertEqual(
            [{"name": "agent-workflow-tests", "command": ["make", "agent-workflow-test"], "cwd": "."}],
            profile["checks"],
        )


if __name__ == "__main__":
    unittest.main()
