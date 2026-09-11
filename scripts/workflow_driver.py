#!/usr/bin/env python3
"""Bounded driver for the reviewed trusted-local workflow host."""

import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
import time


VERSION = "1.1.0"
PLAN_FORMAT = "chess-echo-orchestration-plan-v1"
RESULT_FORMAT = "chess-echo-orchestration-orchestrator-result-v1"
JOURNAL_FORMAT = "chess-echo-workflow-driver-event-v1"
HOST_FAILURE_FORMAT = "chess-echo-trusted-local-host-failure-v1"
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
DEFAULT_MAX_STEPS = 64
DEFAULT_DEADLINE_SECONDS = 14_400
DEFAULT_MAX_NO_PROGRESS = 2
HOST_FAILURE_RETURNCODE = 2
HOST_FAILURE_STATUSES = frozenset(
    {
        "ambiguous",
        "busy",
        "conflict",
        "corrupt",
        "denied",
        "missing",
        "paused",
        "stale",
        "uncertain",
        "unsupported",
    }
)
SAFE_CODE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
FROZEN_ISSUES = frozenset({115, 174})
PHASES = frozenset(
    {
        "PLANNING",
        "PLAN_REVIEW",
        "WAITING_FOR_PLAN_APPROVAL",
        "TEST_IMPLEMENTATION",
        "TEST_REVIEW",
        "WAITING_FOR_TEST_APPROVAL",
        "IMPLEMENTATION",
        "VALIDATION",
        "FINAL_REVIEW",
        "WAITING_FOR_FINAL_APPROVAL",
        "PR_PREPARATION",
        "WAITING_FOR_PR_PUBLICATION_APPROVAL",
        "PAUSED",
        "COMPLETED",
    }
)
GATES = {
    "WAITING_FOR_PLAN_APPROVAL": "plan",
    "WAITING_FOR_TEST_APPROVAL": "tests",
    "WAITING_FOR_FINAL_APPROVAL": "final",
    "WAITING_FOR_PR_PUBLICATION_APPROVAL": "pr-publication",
}
IDLE_STEP_ACTIONS = {
    "PLANNING": "request-planner",
    "PLAN_REVIEW": "review-plan",
    "TEST_IMPLEMENTATION": "request-tests",
    "TEST_REVIEW": "review-tests",
    "IMPLEMENTATION": "request-implementation",
    "VALIDATION": "run-validation",
    "FINAL_REVIEW": "review-final",
}
AGENT_STEP_ACTIONS = frozenset(
    action
    for phase, action in IDLE_STEP_ACTIONS.items()
    if phase != "VALIDATION"
)
PR_STEP_ACTIONS = frozenset(
    {"open-pr-publication-gate", "prepare-draft-pr", "complete-pr-approval"}
)
STEP_ACTIONS = frozenset(
    {
        "request-planner",
        "review-plan",
        "satisfy-gate-automatically",
        "request-tests",
        "review-tests",
        "request-implementation",
        "run-validation",
        "review-final",
        "open-pr-publication-gate",
        "prepare-draft-pr",
        "complete-pr-approval",
        "execute-pending",
    }
)
HUMAN_ACTIONS = frozenset(
    {
        "await-human-approval",
        "approve-gate",
        "authorize-gate-rejection",
        "authorize-supervision-change",
    }
)
RECOVERY_ACTIONS = frozenset(
    {
        "request-recovery",
        "authorize-recovery",
        "recover-cancelled-attempt",
    }
)
EXIT_COMPLETED = 0
EXIT_FAILED = 1
EXIT_HUMAN = 2
EXIT_RECOVERY = 3
EXIT_BOUNDED = 4
EXIT_BUSY = 5


class DriverFailure(Exception):
    def __init__(
        self,
        outcome,
        code,
        message,
        host_returncode=None,
        host_status=None,
        host_code=None,
    ):
        super().__init__(message)
        self.outcome, self.code, self.message = outcome, code, message
        self.host_returncode = host_returncode
        self.host_status = host_status
        self.host_code = host_code


def _fail(outcome, code, message):
    raise DriverFailure(outcome, code, message)


def _canonical(value):
    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _hex_digest(value):
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _strict_object(data, label):
    if not isinstance(data, bytes) or not data or len(data) > MAX_DOCUMENT_BYTES:
        _fail("failed", "%s-size" % label, "%s is empty or too large" % label)

    def reject_duplicate(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate key")
            value[key] = item
        return value

    try:
        value = json.loads(
            data.decode("utf-8"),
            object_pairs_hook=reject_duplicate,
            parse_constant=lambda _value: (_ for _ in ()).throw(ValueError("constant")),
        )
    except (UnicodeError, json.JSONDecodeError, ValueError, RecursionError):
        _fail("failed", "%s-json" % label, "%s is not strict JSON" % label)
    try:
        canonical = _canonical(value) + b"\n"
    except (TypeError, ValueError, RecursionError):
        _fail("failed", "%s-json" % label, "%s contains unsupported JSON values" % label)
    if not isinstance(value, dict) or data != canonical:
        _fail("failed", "%s-canonical" % label, "%s is not one canonical JSON document" % label)
    return value


def _validate_host_failure(data, returncode):
    if returncode != HOST_FAILURE_RETURNCODE:
        _fail(
            "failed",
            "host-failure-returncode",
            "Reviewed local host used an unsupported failure return code",
        )
    if not isinstance(data, bytes):
        _fail("failed", "host-failure-type", "Reviewed local host failure output is not bytes")
    if not data:
        _fail("failed", "host-failure-missing", "Reviewed local host failure output is missing")
    if len(data) > MAX_DOCUMENT_BYTES:
        _fail("failed", "host-failure-size", "Reviewed local host failure output is too large")
    value = _strict_object(data, "host-failure")
    outcome = value.get("outcome")
    if (
        set(value) != {"format", "outcome"}
        or value["format"] != HOST_FAILURE_FORMAT
        or not isinstance(outcome, dict)
        or set(outcome) != {"status", "code", "message"}
        or not isinstance(outcome["status"], str)
        or outcome["status"] not in HOST_FAILURE_STATUSES
        or not isinstance(outcome["code"], str)
        or SAFE_CODE_RE.fullmatch(outcome["code"]) is None
        or not isinstance(outcome["message"], str)
    ):
        _fail(
            "failed",
            "host-failure-schema",
            "Reviewed local host failure output has an unsupported schema",
        )
    return {"status": outcome["status"], "code": outcome["code"]}


def _binding(value):
    return (
        isinstance(value, dict)
        and set(value) == {"kind", "sha256", "size"}
        and value["kind"] == "evidence-binding"
        and _hex_digest(value["sha256"])
        and type(value["size"]) is int
        and value["size"] > 0
    )


def _validate_action(value):
    if (
        not isinstance(value, dict)
        or set(value) != {"action", "command", "gate", "pending_kind"}
        or not isinstance(value["action"], str)
        or not isinstance(value["command"], str)
        or (value["gate"] is not None and not isinstance(value["gate"], str))
        or (value["pending_kind"] is not None and not isinstance(value["pending_kind"], str))
    ):
        _fail("failed", "plan-next-action", "plan-next returned a malformed action")
    return value


def _validate_query(value, issue):
    if value is None:
        return
    keys = {
        "format",
        "issue",
        "family_run_id",
        "authority_binding",
        "request_binding",
        "attempt_id",
        "result_kind",
        "query_sha256",
    }
    if (
        not isinstance(value, dict)
        or set(value) != keys
        or value["format"] != "chess-echo-pending-result-query-v1"
        or value["issue"] != issue
        or not isinstance(value["family_run_id"], str)
        or not value["family_run_id"]
        or not _binding(value["authority_binding"])
        or not _binding(value["request_binding"])
        or not _hex_digest(value["attempt_id"])
        or not isinstance(value["result_kind"], str)
        or value["result_kind"] not in {"execution-result", "github-pr-observation"}
        or not _hex_digest(value["query_sha256"])
    ):
        _fail("failed", "plan-next-query", "plan-next returned a malformed pending-result query")
    unsigned = dict(value)
    digest = unsigned.pop("query_sha256")
    if _sha(_canonical(unsigned)) != digest:
        _fail("failed", "plan-next-query-digest", "plan-next returned a stale pending-result query")


def _validate_pending(value):
    if value is None:
        return None
    if (
        not isinstance(value, dict)
        or set(value) != {"attempt_id", "kind", "request_binding", "status"}
        or not _hex_digest(value["attempt_id"])
        or not isinstance(value["kind"], str)
        or value["kind"]
        not in {
            "agent",
            "validation",
            "git-read",
            "github-read",
            "github-write",
            "human",
            "human-rejection",
            "policy",
        }
        or not _binding(value["request_binding"])
        or not isinstance(value["status"], str)
        or value["status"] not in {"requested", "cancel-requested"}
        or (value["status"] == "cancel-requested" and value["kind"] in {"human", "human-rejection", "policy"})
    ):
        _fail("failed", "plan-next-pending", "plan-next returned malformed pending state")
    return value


def validate_plan(value, issue):
    keys = {
        "format",
        "outcome",
        "issue",
        "generation",
        "phase",
        "pointer_sha256",
        "pending",
        "pending_result_query",
        "next_action",
    }
    if (
        set(value) != keys
        or value["format"] != PLAN_FORMAT
        or value["outcome"] != {"status": "resolved", "code": "planned"}
        or value["issue"] != issue
        or type(value["generation"]) is not int
        or value["generation"] < 0
        or not isinstance(value["phase"], str)
        or value["phase"] not in PHASES
        or not _hex_digest(value["pointer_sha256"])
    ):
        _fail("failed", "plan-next-schema", "plan-next returned an unsupported document")
    action = _validate_action(value["next_action"])
    pending = _validate_pending(value["pending"])
    _validate_query(value["pending_result_query"], issue)
    query = value["pending_result_query"]
    gate = GATES.get(value["phase"])
    if pending is None:
        if query is not None or action["pending_kind"] is not None:
            _fail("failed", "plan-next-pending-action", "plan-next action invents pending work")
        if gate is not None:
            expected = action == {
                "action": "await-human-approval",
                "command": "approve",
                "gate": gate,
                "pending_kind": None,
            }
        elif value["phase"] in IDLE_STEP_ACTIONS:
            expected = action == {
                "action": IDLE_STEP_ACTIONS[value["phase"]],
                "command": "step",
                "gate": None,
                "pending_kind": None,
            }
        elif value["phase"] == "PR_PREPARATION":
            expected = (
                action["action"] in PR_STEP_ACTIONS
                and action["command"] == "step"
                and action["gate"] is None
            )
        elif value["phase"] == "PAUSED":
            expected = action == {
                "action": "request-recovery",
                "command": "recover",
                "gate": None,
                "pending_kind": None,
            }
        elif value["phase"] == "COMPLETED":
            expected = action == {
                "action": "none-completed",
                "command": "read-only",
                "gate": None,
                "pending_kind": None,
            }
        else:
            expected = False
    elif action["pending_kind"] != pending["kind"]:
        expected = False
    elif pending["status"] == "cancel-requested":
        expected = action == {
            "action": "recover-cancelled-attempt",
            "command": "recover",
            "gate": "recovery",
            "pending_kind": pending["kind"],
        }
    elif pending["kind"] == "policy":
        expected = action == {
            "action": "satisfy-gate-automatically",
            "command": "step",
            "gate": gate,
            "pending_kind": "policy",
        }
    elif pending["kind"] == "human" and value["phase"] == "PAUSED":
        expected = action == {
            "action": "authorize-recovery",
            "command": "recover",
            "gate": "recovery",
            "pending_kind": "human",
        }
    elif pending["kind"] == "human" and gate is None:
        expected = action == {
            "action": "authorize-supervision-change",
            "command": "approve",
            "gate": "supervision-policy-change",
            "pending_kind": "human",
        }
    elif pending["kind"] == "human-rejection":
        expected = action == {
            "action": "authorize-gate-rejection",
            "command": "reject",
            "gate": gate,
            "pending_kind": "human-rejection",
        }
    elif pending["kind"] == "human":
        expected = action == {
            "action": "approve-gate",
            "command": "approve",
            "gate": gate,
            "pending_kind": "human",
        }
    else:
        expected = action == {
            "action": "execute-pending",
            "command": "step",
            "gate": gate,
            "pending_kind": pending["kind"],
        }
    if not expected:
        _fail("failed", "plan-next-pending-action", "plan-next action is inconsistent with pending work")
    should_query = (
        pending is not None
        and pending["kind"] not in {"human", "human-rejection", "policy"}
        and pending["status"] == "requested"
    )
    if (query is not None) != should_query:
        _fail("failed", "plan-next-query-action", "Pending-result query is inconsistent with pending work")
    if query is not None and (
        query["attempt_id"] != pending["attempt_id"]
        or query["request_binding"] != pending["request_binding"]
        or query["result_kind"]
        != ("github-pr-observation" if pending["kind"] == "github-read" else "execution-result")
    ):
        _fail("failed", "plan-next-query-pending", "Pending-result query selects different pending work")
    if query is not None and (
        action["command"] != "step" or action["action"] != "execute-pending"
    ):
        _fail("failed", "plan-next-query-action", "Pending-result discovery is not paired with execution")
    return value


def validate_step_result(value, issue):
    required = {
        "format",
        "outcome",
        "issue",
        "generation",
        "phase",
        "authority",
        "pointer_sha256",
        "next_action",
    }
    if (
        set(value) not in (required, required | {"handoff"})
        or value["format"] != RESULT_FORMAT
        or not isinstance(value["outcome"], dict)
        or set(value["outcome"]) != {"status", "code"}
        or not isinstance(value["outcome"]["status"], str)
        or value["outcome"]["status"] not in {"resolved", "paused"}
        or not isinstance(value["outcome"]["code"], str)
        or not value["outcome"]["code"]
        or value["issue"] != issue
        or type(value["generation"]) is not int
        or value["generation"] < 0
        or not isinstance(value["phase"], str)
        or value["phase"] not in PHASES
        or not _binding(value["authority"])
        or not _hex_digest(value["pointer_sha256"])
    ):
        _fail("failed", "step-schema", "step returned an unsupported document")
    _validate_action(value["next_action"])
    if "handoff" in value and not isinstance(value["handoff"], dict):
        _fail("failed", "step-handoff", "step returned a malformed handoff")
    return value


def _secure_directory(path):
    directory = pathlib.Path(path).absolute()
    try:
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        metadata = directory.lstat()
    except OSError:
        _fail("failed", "driver-state-unavailable", "Driver state directory is unavailable")
    if directory.is_symlink() or not stat.S_ISDIR(metadata.st_mode):
        _fail("failed", "driver-state-unsafe", "Driver state must be a real directory")
    return directory


def _agent_home_root(path, control_root, workspace_root):
    supplied = pathlib.Path(path).absolute()
    try:
        root = supplied.resolve(strict=True)
        control = pathlib.Path(control_root).absolute().resolve(strict=True)
        workspace = pathlib.Path(workspace_root).absolute().resolve(strict=True)
        metadata = root.lstat()
        entries = list(root.iterdir())
    except (OSError, RuntimeError, ValueError):
        _fail(
            "failed",
            "agent-home-root-unavailable",
            "Agent home allocation root is unavailable",
        )
    if (
        supplied != root
        or root.is_symlink()
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.getuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or root in {control, workspace}
        or control in root.parents
        or workspace in root.parents
    ):
        _fail(
            "failed",
            "agent-home-root-unsafe",
            "Agent home allocation root must be a private real directory outside the control and candidate worktrees",
        )
    for entry in entries:
        try:
            entry_metadata = entry.lstat()
        except OSError:
            _fail(
                "failed",
                "agent-home-root-unsafe",
                "Agent home allocation root contains an unsafe entry",
            )
        if (
            not entry.name.startswith("execution-")
            or entry.is_symlink()
            or not stat.S_ISDIR(entry_metadata.st_mode)
            or entry_metadata.st_uid != os.getuid()
            or stat.S_IMODE(entry_metadata.st_mode) != 0o700
        ):
            _fail(
                "failed",
                "agent-home-root-unsafe",
                "Agent home allocation root contains an entry not created for an isolated execution",
            )
    return root


class Journal:
    def __init__(self, directory, issue):
        self.issue = issue
        self.sequence = 0
        self.path = directory / ("driver-issue-%d.jsonl" % issue)
        flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            self.descriptor = os.open(str(self.path), flags, 0o600)
            metadata = os.fstat(self.descriptor)
        except OSError:
            _fail("failed", "journal-unavailable", "Driver journal is unavailable")
        if not stat.S_ISREG(metadata.st_mode):
            os.close(self.descriptor)
            _fail("failed", "journal-unsafe", "Driver journal must be a regular file")

    def record(self, event, **fields):
        allowed = {
            "action",
            "code",
            "generation",
            "host_code",
            "host_returncode",
            "host_status",
            "no_progress",
            "phase",
            "pointer_sha256",
            "reason",
            "steps",
        }
        if set(fields) - allowed:
            _fail("failed", "journal-field", "Driver journal field is not audit-safe")
        document = {
            "format": JOURNAL_FORMAT,
            "version": VERSION,
            "issue": self.issue,
            "sequence": self.sequence,
            "event": event,
            "details": fields,
        }
        data = _canonical(document) + b"\n"
        try:
            written = os.write(self.descriptor, data)
            if written != len(data):
                _fail("failed", "journal-short-write", "Driver journal write was incomplete")
            os.fsync(self.descriptor)
        except OSError:
            _fail("failed", "journal-write", "Driver journal could not be persisted")
        self.sequence += 1

    def close(self):
        os.close(self.descriptor)


class IssueLock:
    def __init__(self, directory, issue):
        self.path = directory / ("driver-issue-%d.lock" % issue)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        if hasattr(os, "O_NOFOLLOW"):
            flags |= os.O_NOFOLLOW
        try:
            self.descriptor = os.open(str(self.path), flags, 0o600)
            os.write(self.descriptor, ("%d\n" % os.getpid()).encode("ascii"))
            os.fsync(self.descriptor)
        except FileExistsError:
            _fail("busy", "driver-lock-present", "Another driver or a stale lock owns this issue")
        except OSError:
            _fail("failed", "driver-lock-unavailable", "Driver lock could not be created")

    def close(self):
        try:
            os.close(self.descriptor)
            self.path.unlink()
        except OSError:
            _fail("failed", "driver-lock-release", "Driver lock could not be released")


class Driver:
    def __init__(self, args, github_token, worker_token=None, runner=None, clock=None):
        self.args = args
        self.github_token = github_token
        self.worker_token = worker_token
        self.runner = runner or self._run_process
        self.clock = clock or time.monotonic
        self.started = self.clock()
        self.agent_home_root = _agent_home_root(
            args.agent_home,
            args.control_root,
            args.workspace,
        )
        self.agent_homes = set()
        self.prefix = [
            "/usr/bin/python3",
            "-I",
            str(pathlib.Path(args.control_root) / "scripts" / "workflow_local_host.py"),
            "--control-root",
            args.control_root,
            "--repository",
            args.repository,
            "--git-executable",
            args.git_executable,
            "--gh-executable",
            args.gh_executable,
            "--agent-executable",
            args.agent_executable,
        ]
        self.suffix = [
            "--result-store",
            args.result_store,
            "--github-token-stdin",
        ]

    @staticmethod
    def _run_process(command, input_bytes):
        return subprocess.run(
            command,
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def _expired(self):
        return self.clock() - self.started >= self.args.deadline_seconds

    def _fresh_agent_home(self):
        try:
            created = pathlib.Path(
                tempfile.mkdtemp(
                    prefix="execution-%04d-" % (len(self.agent_homes) + 1),
                    dir=str(self.agent_home_root),
                )
            )
            created.chmod(0o700)
            resolved = created.resolve(strict=True)
            metadata = resolved.lstat()
            empty = not any(resolved.iterdir())
        except (OSError, RuntimeError, ValueError):
            _fail(
                "failed",
                "agent-home-allocation-failed",
                "Fresh agent home could not be allocated",
            )
        if (
            created != resolved
            or created.is_symlink()
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700
            or not empty
            or resolved.parent != self.agent_home_root
            or any(
                previous == resolved
                or previous in resolved.parents
                or resolved in previous.parents
                for previous in self.agent_homes
            )
        ):
            _fail(
                "failed",
                "agent-home-allocation-unsafe",
                "Fresh agent home does not satisfy the per-execution isolation contract",
            )
        self.agent_homes.add(resolved)
        return resolved

    def _invoke(self, command, expected_tip=None, agent_execution=False):
        agent_home = (
            self._fresh_agent_home()
            if agent_execution
            else self.agent_home_root
        )
        argv = [
            *self.prefix,
            "--agent-home",
            str(agent_home),
            *self.suffix,
        ]
        secret_input = self.github_token + "\n"
        if command == "step" and self.worker_token is not None:
            argv.append("--trusted-worker-auth-stdin")
            secret_input += self.worker_token + "\n"
        argv.extend([command, str(self.args.issue), "--workspace", self.args.workspace])
        if expected_tip is not None:
            argv.extend(["--expected-tip", expected_tip])
        try:
            completed = self.runner(argv, secret_input.encode("utf-8"))
        except (OSError, subprocess.SubprocessError):
            _fail("failed", "host-invocation", "Reviewed local host invocation failed")
        if completed.returncode != 0:
            return completed, None
        return completed, _strict_object(completed.stdout, command)

    @staticmethod
    def _raise_host_failure(journal, command, completed, steps, action=None):
        details = {"host_returncode": completed.returncode, "steps": steps}
        if action is not None:
            details["action"] = action
        try:
            failure = _validate_host_failure(completed.stdout, completed.returncode)
        except DriverFailure as error:
            journal.record("host-failed", **details)
            raise DriverFailure(
                error.outcome,
                error.code,
                error.message,
                host_returncode=completed.returncode,
            ) from None
        details.update(
            {
                "host_status": failure["status"],
                "host_code": failure["code"],
            }
        )
        journal.record("host-failed", **details)
        raise DriverFailure(
            "failed",
            "%s-failed" % command,
            "Reviewed local host rejected %s" % command,
            host_returncode=completed.returncode,
            host_status=failure["status"],
            host_code=failure["code"],
        )

    def run(self, journal):
        steps = 0
        previous = None
        no_progress = 0
        journal.record("started", steps=steps, no_progress=no_progress)
        while True:
            if self._expired():
                _fail("bounded", "deadline-reached", "Driver dispatch deadline was reached")
            completed, raw_plan = self._invoke("plan-next")
            if completed.returncode != 0:
                self._raise_host_failure(journal, "plan-next", completed, steps)
            plan = validate_plan(raw_plan, self.args.issue)
            action = plan["next_action"]
            fingerprint = _sha(
                _canonical(
                    {
                        "generation": plan["generation"],
                        "phase": plan["phase"],
                        "pointer_sha256": plan["pointer_sha256"],
                        "pending_result_query": plan["pending_result_query"],
                        "next_action": action,
                    }
                )
            )
            no_progress = no_progress + 1 if fingerprint == previous else 0
            previous = fingerprint
            journal.record(
                "planned",
                action=action["action"],
                generation=plan["generation"],
                no_progress=no_progress,
                phase=plan["phase"],
                pointer_sha256=plan["pointer_sha256"],
                steps=steps,
            )
            if no_progress >= self.args.max_no_progress:
                _fail("bounded", "no-progress", "plan-next repeated without authoritative progress")
            terminal_action = {
                    "action": "none-completed",
                    "command": "read-only",
                    "gate": None,
                    "pending_kind": None,
                }
            if plan["phase"] == "COMPLETED" or action == terminal_action:
                if plan["phase"] != "COMPLETED" or action != terminal_action:
                    _fail(
                        "failed",
                        "terminal-mismatch",
                        "Terminal phase and action do not match exactly",
                    )
                journal.record("completed", code="none-completed", steps=steps)
                return {"outcome": "completed", "code": "none-completed", "steps": steps}
            if action["action"] in HUMAN_ACTIONS and action["command"] in {"approve", "reject"}:
                journal.record("stopped", action=action["action"], reason="human", steps=steps)
                return {"outcome": "human", "code": action["action"], "steps": steps}
            if action["action"] in RECOVERY_ACTIONS and action["command"] == "recover":
                journal.record("stopped", action=action["action"], reason="recovery", steps=steps)
                return {"outcome": "recovery", "code": action["action"], "steps": steps}
            if action["action"] not in STEP_ACTIONS or action["command"] != "step":
                _fail("failed", "action-not-automatic", "plan-next did not select an allowed step action")
            if steps >= self.args.max_steps:
                _fail("bounded", "max-steps", "Driver step limit was reached")
            if self._expired():
                _fail("bounded", "deadline-reached", "Driver dispatch deadline was reached")
            journal.record(
                "dispatching",
                action=action["action"],
                phase=plan["phase"],
                pointer_sha256=plan["pointer_sha256"],
                steps=steps,
            )
            completed, raw_result = self._invoke(
                "step",
                plan["pointer_sha256"],
                agent_execution=action["action"] in AGENT_STEP_ACTIONS,
            )
            steps += 1
            if completed.returncode != 0:
                self._raise_host_failure(
                    journal,
                    "step",
                    completed,
                    steps,
                    action=action["action"],
                )
            result = validate_step_result(raw_result, self.args.issue)
            journal.record(
                "step-returned",
                action=action["action"],
                code=result["outcome"]["code"],
                generation=result["generation"],
                phase=result["phase"],
                pointer_sha256=result["pointer_sha256"],
                steps=steps,
            )


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run")
    run.add_argument("issue", type=int)
    run.add_argument("--control-root", required=True)
    run.add_argument("--repository", required=True)
    run.add_argument("--git-executable", required=True)
    run.add_argument("--gh-executable", required=True)
    run.add_argument("--agent-executable", required=True)
    run.add_argument("--agent-home", required=True)
    run.add_argument("--result-store", required=True)
    run.add_argument("--workspace", required=True)
    run.add_argument("--driver-state", required=True)
    run.add_argument("--github-token-stdin", action="store_true", required=True)
    run.add_argument("--trusted-worker-auth-stdin", action="store_true")
    run.add_argument("--max-steps", type=int, default=DEFAULT_MAX_STEPS)
    run.add_argument("--deadline-seconds", type=int, default=DEFAULT_DEADLINE_SECONDS)
    run.add_argument("--max-no-progress", type=int, default=DEFAULT_MAX_NO_PROGRESS)
    return parser


def _result(outcome, code, message=None, steps=None):
    value = {"outcome": outcome, "code": code}
    if message is not None:
        value["message"] = message
    if steps is not None:
        value["steps"] = steps
    return value


def main(argv=None, stdin=None):
    lock = journal = None
    try:
        args = build_parser().parse_args(argv)
        if args.issue in FROZEN_ISSUES:
            _fail("denied", "issue-refused", "This issue is refused before host invocation")
        if args.issue <= 0:
            _fail("failed", "invalid-issue", "Issue must be positive")
        if args.max_steps <= 0 or args.deadline_seconds <= 0 or args.max_no_progress <= 0:
            _fail("failed", "invalid-bound", "All driver bounds must be positive")
        stream = stdin or sys.stdin
        github_token = stream.readline().rstrip("\n")
        if not github_token:
            _fail("failed", "github-token-missing", "GitHub token is required on standard input")
        worker_token = None
        if args.trusted_worker_auth_stdin:
            worker_token = stream.readline().rstrip("\n")
            if not worker_token:
                _fail("failed", "worker-token-missing", "Worker token is required on standard input")
        directory = _secure_directory(args.driver_state)
        lock = IssueLock(directory, args.issue)
        journal = Journal(directory, args.issue)
        outcome = Driver(args, github_token, worker_token).run(journal)
        exit_code = {
            "completed": EXIT_COMPLETED,
            "human": EXIT_HUMAN,
            "recovery": EXIT_RECOVERY,
        }[outcome["outcome"]]
        sys.stdout.buffer.write(_canonical(outcome) + b"\n")
        return exit_code
    except DriverFailure as error:
        if journal is not None:
            try:
                journal.record("terminated", code=error.code, reason=error.outcome)
            except DriverFailure:
                pass
        result = _result(
            error.outcome,
            error.code,
            None if error.host_returncode is not None else error.message,
        )
        if error.host_returncode is not None:
            result["host_returncode"] = error.host_returncode
        if error.host_status is not None:
            result["host_status"] = error.host_status
            result["host_code"] = error.host_code
        sys.stdout.buffer.write(_canonical(result) + b"\n")
        return {
            "busy": EXIT_BUSY,
            "bounded": EXIT_BOUNDED,
            "human": EXIT_HUMAN,
            "recovery": EXIT_RECOVERY,
        }.get(error.outcome, EXIT_FAILED)
    finally:
        if journal is not None:
            journal.close()
        if lock is not None:
            try:
                lock.close()
            except DriverFailure:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
