import argparse
import io
import json
import os
import pathlib
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import workflow_driver as driver


ISSUE = 200
POINTER_A = "a" * 64
POINTER_B = "b" * 64


def canonical(value):
    return driver._canonical(value) + b"\n"


def host_failure(status="corrupt", code="local-agent-result-invalid", message="redacted"):
    return {
        "format": driver.HOST_FAILURE_FORMAT,
        "outcome": {"status": status, "code": code, "message": message},
    }


def binding(character):
    return {"kind": "evidence-binding", "sha256": character * 64, "size": 1}


def action(name, command="step", gate=None, pending_kind=None):
    return {
        "action": name,
        "command": command,
        "gate": gate,
        "pending_kind": pending_kind,
    }


def pending_query(kind="agent"):
    value = {
        "format": "chess-echo-pending-result-query-v1",
        "issue": ISSUE,
        "family_run_id": "family",
        "authority_binding": binding("c"),
        "request_binding": binding("d"),
        "attempt_id": "e" * 64,
        "result_kind": "github-pr-observation" if kind == "github-read" else "execution-result",
    }
    value["query_sha256"] = driver._sha(driver._canonical(value))
    return value


def pending(kind="agent", status="requested"):
    return {
        "attempt_id": "e" * 64,
        "kind": kind,
        "request_binding": binding("d"),
        "status": status,
    }


def plan(
    selected,
    *,
    phase="PLANNING",
    pointer=POINTER_A,
    generation=1,
    query=None,
    pending=None,
):
    return {
        "format": driver.PLAN_FORMAT,
        "outcome": {"status": "resolved", "code": "planned"},
        "issue": ISSUE,
        "generation": generation,
        "phase": phase,
        "pointer_sha256": pointer,
        "pending": pending,
        "pending_result_query": query,
        "next_action": selected,
    }


def step_result(
    selected,
    *,
    pointer=POINTER_B,
    generation=2,
    phase="PLAN_REVIEW",
    status="resolved",
    code="advanced",
):
    return {
        "format": driver.RESULT_FORMAT,
        "outcome": {"status": status, "code": code},
        "issue": ISSUE,
        "generation": generation,
        "phase": phase,
        "authority": binding("f"),
        "pointer_sha256": pointer,
        "next_action": selected,
    }


def completed_plan(pointer=POINTER_B, generation=2):
    return plan(
        action("none-completed", "read-only"),
        phase="COMPLETED",
        pointer=pointer,
        generation=generation,
    )


class FakeRunner:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.home_observations = []

    def __call__(self, command, input_bytes):
        self.calls.append((command, input_bytes))
        home = pathlib.Path(command[command.index("--agent-home") + 1])
        metadata = home.stat()
        self.home_observations.append(
            {
                "path": home,
                "empty": not any(home.iterdir()),
                "mode": stat.S_IMODE(metadata.st_mode),
                "uid": metadata.st_uid,
            }
        )
        response = self.responses.pop(0)
        if isinstance(response, tuple):
            return subprocess.CompletedProcess(command, response[0], response[1], response[2])
        return subprocess.CompletedProcess(command, 0, canonical(response), b"")


class DriverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.state = pathlib.Path(self.temporary.name).resolve()
        self.control = self.state / "control"
        self.workspace = self.state / "workspace"
        self.agent_home = self.state / "agent-homes"
        for path in (self.control, self.workspace, self.agent_home):
            path.mkdir(mode=0o700)
        self.args = argparse.Namespace(
            issue=ISSUE,
            control_root=str(self.control),
            repository="NathanZK/ChessEcho",
            git_executable="/usr/bin/git",
            gh_executable="/usr/bin/gh",
            agent_executable="/usr/bin/copilot",
            agent_home=str(self.agent_home),
            result_store="/results",
            workspace=str(self.workspace),
            driver_state=str(self.state),
            max_steps=64,
            deadline_seconds=14_400,
            max_no_progress=2,
        )

    def tearDown(self):
        self.temporary.cleanup()

    def run_driver(self, responses, **changes):
        for name, value in changes.items():
            setattr(self.args, name, value)
        runner = FakeRunner(responses)
        journal = driver.Journal(self.state, ISSUE)
        try:
            outcome = driver.Driver(
                self.args, "github-secret", "worker-secret", runner=runner
            ).run(journal)
        finally:
            journal.close()
        return outcome, runner

    def test_every_automatic_action_dispatches_exactly_one_requestless_step(self):
        phases = {
            "request-planner": "PLANNING",
            "review-plan": "PLAN_REVIEW",
            "request-tests": "TEST_IMPLEMENTATION",
            "review-tests": "TEST_REVIEW",
            "request-implementation": "IMPLEMENTATION",
            "run-validation": "VALIDATION",
            "review-final": "FINAL_REVIEW",
            "open-pr-publication-gate": "PR_PREPARATION",
            "prepare-draft-pr": "PR_PREPARATION",
            "complete-pr-approval": "PR_PREPARATION",
        }
        for selected_name in sorted(driver.STEP_ACTIONS):
            with self.subTest(action=selected_name):
                query = pending_query() if selected_name == "execute-pending" else None
                pending_state = pending() if query else None
                phase = phases.get(selected_name, "PLANNING")
                gate = None
                pending_kind = "agent" if query is not None else None
                if selected_name == "satisfy-gate-automatically":
                    phase, gate, pending_kind = "WAITING_FOR_PLAN_APPROVAL", "plan", "policy"
                    pending_state = pending("policy")
                selected = action(selected_name, gate=gate, pending_kind=pending_kind)
                outcome, runner = self.run_driver(
                    [
                        plan(selected, phase=phase, query=query, pending=pending_state),
                        step_result(action("review-plan")),
                        completed_plan(),
                    ]
                )
                self.assertEqual("completed", outcome["outcome"])
                self.assertEqual(1, outcome["steps"])
                step_command, step_input = runner.calls[1]
                self.assertIn("step", step_command)
                self.assertNotIn("--request", step_command)
                self.assertEqual(POINTER_A, step_command[step_command.index("--expected-tip") + 1])
                self.assertEqual(b"github-secret\nworker-secret\n", step_input)

    def test_agent_operations_receive_distinct_empty_private_homes(self):
        human = action("await-human-approval", "approve", "plan")
        outcome, runner = self.run_driver(
            [
                plan(action("request-planner"), generation=0),
                step_result(action("review-plan"), generation=2),
                plan(
                    action("review-plan"),
                    phase="PLAN_REVIEW",
                    pointer=POINTER_B,
                    generation=2,
                ),
                step_result(
                    human,
                    pointer=POINTER_A,
                    generation=3,
                    phase="WAITING_FOR_PLAN_APPROVAL",
                ),
                plan(
                    human,
                    phase="WAITING_FOR_PLAN_APPROVAL",
                    pointer=POINTER_A,
                    generation=3,
                ),
            ]
        )
        self.assertEqual("human", outcome["outcome"])
        planner = runner.home_observations[1]
        reviewer = runner.home_observations[3]
        self.assertNotEqual(planner["path"], reviewer["path"])
        self.assertEqual(self.agent_home, planner["path"].parent)
        self.assertEqual(self.agent_home, reviewer["path"].parent)
        for observation in (planner, reviewer):
            self.assertTrue(observation["empty"])
            self.assertEqual(0o700, observation["mode"])
            self.assertEqual(os.getuid(), observation["uid"])

    def test_populated_prior_home_is_preserved_and_never_reused(self):
        instance = driver.Driver(
            self.args,
            "github-secret",
            "worker-secret",
            runner=FakeRunner([]),
        )
        first = instance._fresh_agent_home()
        (first / ".copilot").mkdir()
        second = instance._fresh_agent_home()
        third = instance._fresh_agent_home()
        self.assertEqual(3, len({first, second, third}))
        self.assertTrue((first / ".copilot").is_dir())
        for path in (second, third):
            self.assertEqual([], list(path.iterdir()))
            self.assertEqual(0o700, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(os.getuid(), path.stat().st_uid)

    def test_human_actions_stop_without_dispatch(self):
        cases = (
            (
                action("await-human-approval", "approve", "plan"),
                None,
                "WAITING_FOR_PLAN_APPROVAL",
            ),
            (
                action("approve-gate", "approve", "tests", "human"),
                pending("human"),
                "WAITING_FOR_TEST_APPROVAL",
            ),
            (
                action(
                    "authorize-supervision-change",
                    "approve",
                    "supervision-policy-change",
                    "human",
                ),
                pending("human"),
                "PLANNING",
            ),
        )
        for selected, pending_state, phase in cases:
            with self.subTest(action=selected["action"]):
                outcome, runner = self.run_driver(
                    [plan(selected, phase=phase, pending=pending_state)]
                )
                self.assertEqual({"outcome": "human", "code": selected["action"], "steps": 0}, outcome)
                self.assertEqual(1, len(runner.calls))

    def test_recovery_actions_stop_without_dispatch(self):
        cases = (
            (action("request-recovery", "recover"), None),
            (
                action("authorize-recovery", "recover", "recovery", "human"),
                pending("human"),
            ),
            (
                action("recover-cancelled-attempt", "recover", "recovery", "agent"),
                pending("agent", "cancel-requested"),
            ),
        )
        for selected, pending_state in cases:
            with self.subTest(action=selected["action"]):
                outcome, runner = self.run_driver(
                    [plan(selected, phase="PAUSED", pending=pending_state)]
                )
                self.assertEqual("recovery", outcome["outcome"])
                self.assertEqual(1, len(runner.calls))

    def test_malformed_noncanonical_and_unknown_plans_fail_closed(self):
        malformed = (
            (0, b"not-json\n", b""),
            (0, b'{"format":"wrong"}\n', b""),
            (0, json.dumps(completed_plan()).encode() + b"\n", b""),
            (
                0,
                canonical(plan(action("request-planner"))).replace(
                    b'"pending":null', b'"pending":{"bad":NaN}'
                ),
                b"",
            ),
            (
                0,
                canonical(plan(action("request-planner"))).replace(
                    b'"pending":null', b'"pending":{"bad":1e999}'
                ),
                b"",
            ),
            plan(action("invented-action")),
        )
        for response in malformed:
            with self.subTest(response=repr(response)[:60]):
                runner = FakeRunner([response])
                journal = driver.Journal(self.state, ISSUE)
                try:
                    with self.assertRaises(driver.DriverFailure):
                        driver.Driver(self.args, "token", runner=runner).run(journal)
                finally:
                    journal.close()

    def test_plan_next_failure_preserves_only_typed_host_diagnostics(self):
        for status, code in (
            ("busy", "attempt-in-flight"),
            ("stale", "runtime-authority-changed"),
        ):
            with self.subTest(status=status, code=code):
                secret = "message-secret-%s" % status
                runner = FakeRunner(
                    [(driver.HOST_FAILURE_RETURNCODE, canonical(host_failure(status, code, secret)), b"stderr-secret")]
                )
                journal = driver.Journal(self.state, ISSUE)
                try:
                    with self.assertRaisesRegex(driver.DriverFailure, "plan-next") as raised:
                        driver.Driver(self.args, "token", runner=runner).run(journal)
                finally:
                    journal.close()
                self.assertEqual(status, raised.exception.host_status)
                self.assertEqual(code, raised.exception.host_code)
                self.assertEqual(driver.HOST_FAILURE_RETURNCODE, raised.exception.host_returncode)
                persisted = (self.state / ("driver-issue-%d.jsonl" % ISSUE)).read_bytes()
                self.assertNotIn(secret.encode(), persisted)
                self.assertNotIn(b"stderr-secret", persisted)
                record = json.loads(persisted.splitlines()[-1])
                self.assertEqual(
                    {
                        "host_code": code,
                        "host_returncode": driver.HOST_FAILURE_RETURNCODE,
                        "host_status": status,
                        "steps": 0,
                    },
                    record["details"],
                )

    def test_step_failure_preserves_typed_host_diagnostics(self):
        runner = FakeRunner(
            [
                plan(action("request-planner")),
                (
                    driver.HOST_FAILURE_RETURNCODE,
                    canonical(host_failure("missing", "local-agent-result-failed")),
                    b"",
                ),
            ]
        )
        journal = driver.Journal(self.state, ISSUE)
        try:
            with self.assertRaisesRegex(driver.DriverFailure, "step") as raised:
                driver.Driver(self.args, "token", runner=runner).run(journal)
        finally:
            journal.close()
        self.assertEqual("missing", raised.exception.host_status)
        self.assertEqual("local-agent-result-failed", raised.exception.host_code)
        record = json.loads(
            (self.state / ("driver-issue-%d.jsonl" % ISSUE)).read_bytes().splitlines()[-1]
        )
        self.assertEqual(
            {
                "action": "request-planner",
                "host_code": "local-agent-result-failed",
                "host_returncode": driver.HOST_FAILURE_RETURNCODE,
                "host_status": "missing",
                "steps": 1,
            },
            record["details"],
        )

    def test_host_failure_output_must_be_present_bounded_strict_and_canonical(self):
        cases = (
            ("host-failure-missing", b""),
            ("host-failure-size", b"x" * (driver.MAX_DOCUMENT_BYTES + 1)),
            ("host-failure-json", b"not-json\n"),
            (
                "host-failure-canonical",
                json.dumps(host_failure(), sort_keys=False).encode() + b"\n",
            ),
            (
                "host-failure-json",
                canonical(host_failure()).replace(b'"message":"redacted"', b'"message":NaN'),
            ),
            (
                "host-failure-schema",
                canonical(
                    {
                        "format": driver.HOST_FAILURE_FORMAT,
                        "outcome": {
                            "status": "resolved",
                            "code": "not-a-failure",
                            "message": "secret",
                        },
                    }
                ),
            ),
            (
                "host-failure-schema",
                canonical(host_failure(code="x" * 129)),
            ),
        )
        for expected_code, output in cases:
            with self.subTest(expected_code=expected_code):
                runner = FakeRunner([(driver.HOST_FAILURE_RETURNCODE, output, b"stderr-secret")])
                journal = driver.Journal(self.state, ISSUE)
                try:
                    with self.assertRaises(driver.DriverFailure) as raised:
                        driver.Driver(self.args, "token", runner=runner).run(journal)
                finally:
                    journal.close()
                self.assertEqual(expected_code, raised.exception.code)
                self.assertIsNone(raised.exception.host_status)
                persisted = (self.state / ("driver-issue-%d.jsonl" % ISSUE)).read_bytes()
                self.assertNotIn(b"secret", persisted)
                self.assertNotIn(b"not-json", persisted)

    def test_host_failure_returncode_must_match_host_contract(self):
        runner = FakeRunner([(9, canonical(host_failure("busy", "attempt-in-flight")), b"")])
        journal = driver.Journal(self.state, ISSUE)
        try:
            with self.assertRaises(driver.DriverFailure) as raised:
                driver.Driver(self.args, "token", runner=runner).run(journal)
        finally:
            journal.close()
        self.assertEqual("host-failure-returncode", raised.exception.code)
        self.assertEqual(9, raised.exception.host_returncode)
        self.assertIsNone(raised.exception.host_status)

    def test_final_failure_document_excludes_host_message_and_streams(self):
        secret = "host-message-secret"
        response = subprocess.CompletedProcess(
            [],
            driver.HOST_FAILURE_RETURNCODE,
            canonical(host_failure("corrupt", "local-agent-result-invalid", secret)),
            b"stderr-secret",
        )
        output = io.BytesIO()
        stdout = io.TextIOWrapper(output)
        common = [
            "run", str(ISSUE),
            "--control-root", str(self.control),
            "--repository", "NathanZK/ChessEcho",
            "--git-executable", "/git",
            "--gh-executable", "/gh",
            "--agent-executable", "/agent",
            "--agent-home", str(self.agent_home),
            "--result-store", "/results",
            "--workspace", str(self.workspace),
            "--driver-state", str(self.state),
            "--github-token-stdin",
        ]
        with mock.patch.object(driver.Driver, "_run_process", return_value=response), mock.patch.object(
            driver.sys, "stdout", stdout
        ):
            self.assertEqual(
                driver.EXIT_FAILED,
                driver.main(common, stdin=io.StringIO("github-secret\n")),
            )
            stdout.flush()
        document = json.loads(output.getvalue())
        self.assertEqual(
            {
                "outcome": "failed",
                "code": "plan-next-failed",
                "host_returncode": driver.HOST_FAILURE_RETURNCODE,
                "host_status": "corrupt",
                "host_code": "local-agent-result-invalid",
            },
            document,
        )
        persisted = (
            output.getvalue()
            + (self.state / ("driver-issue-%d.jsonl" % ISSUE)).read_bytes()
        )
        for excluded in (secret, "stderr-secret", "github-secret"):
            self.assertNotIn(excluded.encode(), persisted)

    def test_paused_step_result_is_followed_by_fresh_recovery_plan(self):
        outcome, runner = self.run_driver(
            [
                plan(action("request-planner")),
                step_result(
                    action("request-recovery", "recover"),
                    phase="PAUSED",
                    status="paused",
                    code="attempt-not-successful",
                ),
                plan(action("request-recovery", "recover"), phase="PAUSED", pointer=POINTER_B),
            ]
        )
        self.assertEqual("recovery", outcome["outcome"])
        self.assertEqual(3, len(runner.calls))

    def test_unhashable_field_types_fail_closed(self):
        cases = []
        bad_phase = plan(action("request-planner"))
        bad_phase["phase"] = []
        cases.append(bad_phase)
        bad_pending = plan(action("execute-pending", pending_kind="agent"))
        bad_pending["pending"] = pending()
        bad_pending["pending"]["kind"] = []
        cases.append(bad_pending)
        bad_query = plan(
            action("execute-pending", pending_kind="agent"),
            pending=pending(),
            query=pending_query(),
        )
        bad_query["pending_result_query"]["result_kind"] = []
        cases.append(bad_query)
        for candidate in cases:
            with self.subTest(candidate=candidate):
                with self.assertRaises(driver.DriverFailure):
                    driver.validate_plan(candidate, ISSUE)
        bad_result = step_result(action("review-plan"))
        bad_result["outcome"]["status"] = []
        with self.assertRaises(driver.DriverFailure):
            driver.validate_step_result(bad_result, ISSUE)

    def test_max_steps_stops_before_second_dispatch(self):
        repeated = plan(action("request-planner"))
        outcome_runner = FakeRunner(
            [
                repeated,
                step_result(action("review-plan"), pointer=POINTER_A, generation=1, phase="PLANNING"),
                plan(action("review-plan"), pointer=POINTER_B, generation=2, phase="PLAN_REVIEW"),
            ]
        )
        self.args.max_steps = 1
        journal = driver.Journal(self.state, ISSUE)
        try:
            with self.assertRaisesRegex(driver.DriverFailure, "step limit"):
                driver.Driver(self.args, "token", runner=outcome_runner).run(journal)
        finally:
            journal.close()
        self.assertEqual(3, len(outcome_runner.calls))

    def test_deadline_stops_before_host_invocation(self):
        clock = mock.Mock(side_effect=[0, 10])
        runner = FakeRunner([])
        self.args.deadline_seconds = 10
        journal = driver.Journal(self.state, ISSUE)
        try:
            with self.assertRaisesRegex(driver.DriverFailure, "deadline"):
                driver.Driver(self.args, "token", runner=runner, clock=clock).run(journal)
        finally:
            journal.close()
        self.assertEqual([], runner.calls)

    def test_no_progress_repeated_plan_is_bounded(self):
        unchanged = plan(action("request-planner"))
        runner = FakeRunner(
            [
                unchanged,
                step_result(action("request-planner"), pointer=POINTER_A, generation=1, phase="PLANNING"),
                unchanged,
            ]
        )
        self.args.max_no_progress = 1
        journal = driver.Journal(self.state, ISSUE)
        try:
            with self.assertRaisesRegex(driver.DriverFailure, "repeated"):
                driver.Driver(self.args, "token", runner=runner).run(journal)
        finally:
            journal.close()
        self.assertEqual(3, len(runner.calls))

    def test_restart_pending_discovery_uses_no_request(self):
        query = pending_query()
        outcome, runner = self.run_driver(
            [
                plan(
                    action("execute-pending", pending_kind="agent"),
                    query=query,
                    pending=pending(),
                ),
                step_result(action("review-plan")),
                completed_plan(),
            ]
        )
        self.assertEqual("completed", outcome["outcome"])
        self.assertNotIn("--request", runner.calls[1][0])

    def test_existing_lock_is_busy_and_never_broken(self):
        lock = driver.IssueLock(self.state, ISSUE)
        try:
            with self.assertRaisesRegex(driver.DriverFailure, "stale lock"):
                driver.IssueLock(self.state, ISSUE)
            self.assertTrue(lock.path.is_file())
        finally:
            lock.close()

    def test_journal_is_canonical_append_only_and_contains_no_secrets(self):
        outcome, _runner = self.run_driver(
            [
                plan(action("request-planner")),
                step_result(action("review-plan")),
                completed_plan(),
            ]
        )
        self.assertEqual("completed", outcome["outcome"])
        data = (self.state / ("driver-issue-%d.jsonl" % ISSUE)).read_bytes()
        self.assertNotIn(b"github-secret", data)
        self.assertNotIn(b"worker-secret", data)
        records = []
        for line in data.splitlines(keepends=True):
            value = json.loads(line)
            self.assertEqual(canonical(value), line)
            self.assertEqual(driver.JOURNAL_FORMAT, value["format"])
            records.append(value)
        self.assertEqual(
            ["started", "planned", "dispatching", "step-returned", "planned", "completed"],
            [record["event"] for record in records],
        )

    def test_success_requires_exact_completed_and_none_completed_pair(self):
        outcome, runner = self.run_driver([completed_plan()])
        self.assertEqual({"outcome": "completed", "code": "none-completed", "steps": 0}, outcome)
        self.assertEqual(1, len(runner.calls))
        for terminal in (
            plan(action("none-completed", "read-only"), phase="PLANNING"),
            plan(action("request-planner"), phase="COMPLETED"),
        ):
            with self.subTest(phase=terminal["phase"], action=terminal["next_action"]["action"]):
                journal = driver.Journal(self.state, ISSUE)
                try:
                    with self.assertRaises(driver.DriverFailure):
                        driver.Driver(
                            self.args, "token", runner=FakeRunner([terminal])
                        ).run(journal)
                finally:
                    journal.close()

    def test_pending_query_must_be_exact_and_digest_valid(self):
        query = pending_query()
        query["query_sha256"] = "0" * 64
        with self.assertRaisesRegex(driver.DriverFailure, "stale pending-result"):
            driver.validate_plan(
                plan(
                    action("execute-pending", pending_kind="agent"),
                    query=query,
                    pending={
                        "attempt_id": "e" * 64,
                        "kind": "agent",
                        "request_binding": binding("d"),
                        "status": "requested",
                    },
                ),
                ISSUE,
            )

    def test_pending_state_and_action_must_be_exact_and_consistent(self):
        valid_pending = {
            "attempt_id": "e" * 64,
            "kind": "agent",
            "request_binding": binding("d"),
            "status": "requested",
        }
        cases = (
            plan(action("request-planner"), pending={}),
            plan(action("request-planner", pending_kind="agent")),
            plan(
                action("execute-pending", pending_kind="agent"),
                pending=valid_pending,
            ),
            plan(
                action("execute-pending", pending_kind="validation"),
                query=pending_query(),
                pending=valid_pending,
            ),
        )
        for candidate in cases:
            with self.subTest(candidate=candidate):
                with self.assertRaises(driver.DriverFailure):
                    driver.validate_plan(candidate, ISSUE)

    def test_git_read_and_github_read_pending_queries_are_correlated(self):
        for kind in ("git-read", "github-read"):
            with self.subTest(kind=kind):
                query = pending_query(kind)
                candidate = plan(
                    action("execute-pending", pending_kind=kind),
                    query=query,
                    pending=pending(kind),
                )
                self.assertIs(candidate, driver.validate_plan(candidate, ISSUE))
                wrong = dict(query)
                wrong["result_kind"] = (
                    "execution-result"
                    if kind == "github-read"
                    else "github-pr-observation"
                )
                unsigned = dict(wrong)
                unsigned.pop("query_sha256")
                wrong["query_sha256"] = driver._sha(driver._canonical(unsigned))
                with self.assertRaises(driver.DriverFailure):
                    driver.validate_plan(
                        plan(
                            action("execute-pending", pending_kind=kind),
                            query=wrong,
                            pending=pending(kind),
                        ),
                        ISSUE,
                    )

    def test_refuses_115_and_174_before_state_or_host_invocation(self):
        common = [
            "--control-root", "/control",
            "--repository", "NathanZK/ChessEcho",
            "--git-executable", "/git",
            "--gh-executable", "/gh",
            "--agent-executable", "/agent",
            "--agent-home", "/agent-home",
            "--result-store", "/results",
            "--workspace", "/workspace",
            "--driver-state", str(self.state),
            "--github-token-stdin",
        ]
        for issue in (115, 174):
            with self.subTest(issue=issue), mock.patch.object(
                driver.subprocess, "run"
            ) as invoked, mock.patch.object(driver.sys, "stdout", new=io.TextIOWrapper(io.BytesIO())):
                self.assertEqual(
                    driver.EXIT_FAILED,
                    driver.main(["run", str(issue), *common], stdin=io.StringIO("secret\n")),
                )
                invoked.assert_not_called()
                self.assertFalse((self.state / ("driver-issue-%d.lock" % issue)).exists())
                self.assertFalse((self.state / ("driver-issue-%d.jsonl" % issue)).exists())


if __name__ == "__main__":
    unittest.main()
