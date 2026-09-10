import base64
import contextlib
import copy
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import workflow_inspector as inspector
from scripts import workflow_local_host as host
from scripts import workflow_local_provider as provider
from scripts import workflow_orchestrator_resume as resume
from scripts import workflow_runtime as runtime


REPOSITORY = pathlib.Path(__file__).parents[2]
PINNED_CLI_FIXTURE = (
    pathlib.Path(__file__).parent
    / "fixtures"
    / "workflow-local-provider"
    / "pinned-cli-reasoning.jsonl"
)
FINAL_REASONING_FIXTURE = (
    pathlib.Path(__file__).parent
    / "fixtures"
    / "workflow-local-provider"
    / "pinned-cli-final-reasoning.jsonl"
)
DENIED_TOOL_FIXTURES = tuple(
    pathlib.Path(__file__).parent
    / "fixtures"
    / "workflow-local-provider"
    / ("copilot-denied-tool-probe-%d.jsonl" % index)
    for index in (1, 2)
)
ASSISTANT_MESSAGE_METADATA_FIXTURE = (
    pathlib.Path(__file__).parent
    / "fixtures"
    / "workflow-local-provider"
    / "assistant-message-metadata.json"
)
CANDIDATE = (
    '{"format":"chess-echo-orchestrator-agent-candidate-v1",'
    '"kind":"plan","plan":"Canary.\\n","units":[{"id":"canary",'
    '"title":"Canary","start_line":1,"end_line":1,"review_class":'
    '"ordinary","dependencies":[]}],"revision":null}'
)


def _sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def _event(event_type, event_id, parent_id, data, ephemeral=False):
    value = {
        "type": event_type,
        "timestamp": "2026-01-01T00:00:00Z",
        "id": event_id,
        "parentId": parent_id,
        "data": data,
    }
    if ephemeral:
        value["ephemeral"] = True
    return value


def _jsonl(candidate=CANDIDATE, include_tool=True):
    events = [
        _event("session.mcp_servers_loaded", "s1", "external-1", {"servers": []}, True),
        _event("session.skills_loaded", "s2", "external-2", {"skills": []}, True),
        _event("session.tools_updated", "s3", "external-3", {"model": "test-model"}, True),
        _event("user.message", "u1", "external-4", {"content": "prompt", "interactionId": "i1"}),
    ]
    parent = "u1"
    if include_tool:
        events.extend(
            [
                _event("assistant.turn_start", "t1s", parent, {"turnId": "1", "interactionId": "i1"}),
                _event("model.call_start", "m1", "t1s", {"model": "test-model", "turnId": "1"}, True),
                _event("assistant.tool_call_delta", "d1", "t1s", {"toolCallId": "tool-1", "inputDelta": "{}"}, True),
                _event(
                    "assistant.message",
                    "a1",
                    "t1s",
                    {
                        "content": "I will inspect the repository.",
                        "messageId": "message-1",
                        "turnId": "1",
                        "interactionId": "i1",
                        "toolRequests": [{"toolCallId": "tool-1", "name": "view", "type": "function", "arguments": {}}],
                    },
                ),
                _event(
                    "tool.execution_start",
                    "x1",
                    "a1",
                    {
                        "arguments": {},
                        "model": "test-model",
                        "rte": False,
                        "shellToolInfo": {},
                        "toolCallId": "tool-1",
                        "toolName": "view",
                        "turnId": "1",
                    },
                ),
                _event("session.background_tasks_changed", "b1", "x1", {}, True),
                _event("tool.execution_partial_result", "p1", "x1", {"toolCallId": "tool-1", "partialOutput": "working"}, True),
                _event(
                    "tool.execution_complete",
                    "x2",
                    "x1",
                    {
                        "interactionId": "i1",
                        "model": "test-model",
                        "result": {"content": "done"},
                        "rte": False,
                        "success": True,
                        "toolCallId": "tool-1",
                        "toolTelemetry": {},
                        "turnId": "1",
                    },
                ),
                _event("assistant.turn_end", "t1e", "x2", {"turnId": "1"}),
            ]
        )
        parent = "t1e"
    events.extend(
        [
            _event("assistant.turn_start", "t2s", parent, {"turnId": "2", "interactionId": "i2"}),
            _event("model.call_start", "m2", "t2s", {"model": "test-model", "turnId": "2"}, True),
            _event("assistant.message_start", "ms2", "t2s", {"messageId": "message-2"}, True),
            _event("assistant.message_delta", "md2", "t2s", {"messageId": "message-2", "deltaContent": candidate}, True),
            _event(
                "assistant.message",
                "a2",
                "t2s",
                {
                    "content": candidate,
                    "messageId": "message-2",
                    "turnId": "2",
                    "interactionId": "i2",
                    "toolRequests": [],
                },
            ),
            _event("assistant.turn_end", "t2e", "a2", {"turnId": "2"}),
            _event("session.usage_checkpoint", "usage", "t2e", {}),
            _event("assistant.idle", "idle", "usage", {}, True),
            _event("session.background_tasks_changed", "b2", "usage", {}, True),
            {
                "type": "result",
                "timestamp": "2026-01-01T00:00:01Z",
                "sessionId": "session-1",
                "exitCode": 0,
                "usage": {
                    "premiumRequests": 1,
                    "totalApiDurationMs": 1,
                    "sessionDurationMs": 2,
                    "codeChanges": {"filesModified": [], "linesAdded": 0, "linesRemoved": 0},
                },
            },
        ]
    )
    return b"".join(
        json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
        for event in events
    )


def _jsonl_events(data):
    return [json.loads(line) for line in data.decode("utf-8").splitlines()]


def _encode_events(events):
    return b"".join(
        json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
        for event in events
    )


def _stream_record(retained, observed):
    return {
        "bytes": len(retained),
        "base64": base64.b64encode(retained).decode("ascii"),
        "observed_bytes": len(observed),
        "observed_sha256": inspector.sha256(observed),
    }


def _process_result(
    command,
    *,
    stdout=b"",
    stderr=b"",
    outcome="success",
    reason="process-exited",
    exit_code=0,
    terminating_signal=None,
    output_limit_bytes=832 * 1024,
    stderr_limit_bytes=64 * 1024,
    stdout_sink=None,
):
    if stdout_sink is not None:
        stdout_sink(stdout)
        retained = b""
    else:
        retained = stdout[:output_limit_bytes]
    return {
        "format": provider.supervisor.RESULT_FORMAT,
        "command_sha256": inspector.sha256(inspector.canonical_bytes(command)),
        "limits": {
            "timeout_ms": 5_000,
            "grace_ms": 100,
            "stdout_bytes": output_limit_bytes,
            "stderr_bytes": stderr_limit_bytes,
        },
        "containment": {
            "kind": "posix-process-group",
            "cleanup_scope": "original-process-group",
            "escaped_descendants": "not-observable",
            "descendant_cleanup_verified": False,
        },
        "outcome": outcome,
        "reason": reason,
        "exit_code": exit_code,
        "terminating_signal": terminating_signal,
        "forced_termination": outcome in {"output-limit", "terminated"},
        "cleanup_verified": True,
        "stdout": _stream_record(retained, stdout),
        "stderr": _stream_record(stderr[:stderr_limit_bytes], stderr),
        "supervisor_error": None,
    }


class LocalProviderFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(dir=str(REPOSITORY))
        self.parent = pathlib.Path(self.temporary.name)
        self.control = self.parent / "control"
        self.workspace = self.parent / "workspace"
        self.bin = self.parent / "bin"
        self.home = self.parent / "home"
        for path in (self.control, self.bin, self.home):
            path.mkdir()
        self.home.chmod(0o700)
        self.git = pathlib.Path(shutil.which("git"))
        self.agent = self.bin / "agent"
        self.worker_token = "github_pat_REDACTED_TEST_ONLY_1234567890"
        self.agent.write_bytes(b"#!/bin/sh\ncat <<'JSONL'\n" + _jsonl() + b"JSONL\n")
        self.agent.chmod(self.agent.stat().st_mode | stat.S_IXUSR)
        self._git(self.control, "init", "-q")
        (self.control / "README").write_text("control\n")
        self._git(self.control, "add", "README")
        self._git(self.control, "commit", "-qm", "control")
        self.head = self._git(self.control, "rev-parse", "HEAD").strip()
        self._git(
            self.control,
            "worktree",
            "add",
            "-q",
            "-b",
            "chess-echo-agent/issue-175",
            str(self.workspace),
            self.head,
        )

    def close(self):
        self.temporary.cleanup()

    def _git(self, cwd, *arguments):
        return subprocess.run(
            [
                str(self.git),
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=fixture@example.test",
                *arguments,
            ],
            cwd=str(cwd),
            env={"PATH": os.environ.get("PATH", ""), "HOME": str(self.home)},
            text=True,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout

    def row(self, **overrides):
        row = {
            "role": "planner",
            "command_prefix": ["agent"],
            "cwd": ".",
            "timeout_ms": 5_000,
            "grace_ms": 100,
            "output_limit_bytes": runtime.LOCAL_PROVIDER_STDOUT_LIMIT_BYTES,
            "stderr_limit_bytes": runtime.LOCAL_PROVIDER_STDERR_LIMIT_BYTES,
            "containment": "trusted-local-worktree-v1",
            "provider_name": provider.NAME,
            "provider_version": provider.VERSION,
            "provider_source": "scripts/workflow_local_provider.py",
            "provider_source_sha256": provider.source_identity()["sha256"],
            "agent_executable_sha256": _sha(self.agent),
        }
        row.update(overrides)
        return row

    def request(self):
        value = {
            "format": "chess-echo-execution-request-v1",
            "issue": 175,
            "family_run_id": "a" * 32,
            "attempt_id": "b" * 64,
            "authority_binding": {
                "kind": "evidence-binding",
                "sha256": "c" * 64,
                "size": 1,
            },
            "operation": {"kind": "agent", "name": "write-plan", "role": "planner"},
            "command_source": {},
            "input_bindings": [],
            "repository_before": {
                "head": {"commit": self.head},
            },
            "limits": {
                "timeout_ms": 5_000,
                "grace_ms": 100,
                "output_limit_bytes": runtime.LOCAL_PROVIDER_STDOUT_LIMIT_BYTES,
                "stderr_limit_bytes": runtime.LOCAL_PROVIDER_STDERR_LIMIT_BYTES,
            },
        }
        value["request_sha256"] = inspector.sha256(inspector.canonical_bytes(value))
        return value

    def environment(self):
        return {
            "PATH": "",
            "HOME": "",
            "LC_ALL": "C.UTF-8",
            "LANG": "C.UTF-8",
            "TZ": "UTC",
        }

    def instance(
        self,
        *,
        trusted_worker_authentication=True,
        worker_token=None,
        **row_overrides,
    ):
        if worker_token is None and trusted_worker_authentication:
            worker_token = self.worker_token
        return provider.LocalSandboxProvider(
            root=self.workspace,
            control_root=self.control,
            issue=175,
            role="planner",
            row=self.row(**row_overrides),
            git_executable=self.git,
            agent_executable=self.agent,
            agent_home=self.home,
            trusted_worker_authentication=trusted_worker_authentication,
            worker_token=worker_token,
        )


class TrustedLocalProviderTest(unittest.TestCase):
    def setUp(self):
        self.fixture = LocalProviderFixture()
        self.addCleanup(self.fixture.close)

    def _execute_through_runtime(
        self,
        candidate=CANDIDATE,
        prompt_bytes=None,
        repository_after=None,
        supervise=None,
        bundle=False,
        transport=None,
    ):
        self.fixture.agent.write_bytes(
            b"#!/bin/sh\ncat <<'JSONL'\n"
            + (_jsonl(candidate=candidate) if transport is None else transport)
            + b"JSONL\n"
        )
        self.fixture.agent.chmod(self.fixture.agent.stat().st_mode | stat.S_IXUSR)
        sandbox_provider = self.fixture.instance()
        request = self.fixture.request()
        request["repository_before"].update(
            {
                "triage_binding": {
                    "kind": "evidence-binding",
                    "sha256": "e" * 64,
                    "size": 1,
                },
                "observed_at": "2026-01-01T00:00:00Z",
            }
        )
        request.pop("request_sha256")
        request["request_sha256"] = inspector.sha256(
            inspector.canonical_bytes(request)
        )
        binding = {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1}
        adapter = object.__new__(runtime.Runtime)
        adapter.root = self.fixture.workspace
        adapter._config = {"agent_roles": [self.fixture.row()]}
        suffix = " This is read-only: do not change or commit the candidate worktree."
        prompt_capacity = None if prompt_bytes is None else prompt_bytes - len(
            suffix.encode("utf-8")
        )
        escaped_pattern = '"\\\n'
        prompt_context = (
            mock.patch.object(
                provider,
                "_agent_prompt",
                return_value=(
                    escaped_pattern * (prompt_capacity // len(escaped_pattern))
                    + escaped_pattern[: prompt_capacity % len(escaped_pattern)]
                ),
            )
            if prompt_bytes is not None
            else contextlib.nullcontext()
        )
        original_supervise = provider.supervisor.supervise

        def selected_supervise(command, **options):
            if command[0] == str(self.fixture.agent):
                return supervise(command, **options)
            return original_supervise(command, **options)

        supervise_context = (
            mock.patch.object(
                provider.supervisor, "supervise", side_effect=selected_supervise
            )
            if supervise is not None
            else contextlib.nullcontext()
        )
        with mock.patch.object(
            runtime.Runtime, "_base_config", return_value={"mode": "active"}
        ), mock.patch.object(
            runtime.Runtime, "_validate_request", return_value=request
        ), mock.patch.object(
            runtime.Runtime, "_ensure_config_current"
        ), mock.patch.object(
            runtime.Runtime,
            "_resolve_command",
            return_value=(
                ["agent"],
                self.fixture.workspace,
                self.fixture.environment(),
            ),
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            side_effect=(
                request["repository_before"],
                repository_after or request["repository_before"],
            ),
        ), mock.patch.object(
            provider, "_input_projection", return_value=[]
        ), prompt_context, supervise_context:
            executed = adapter.execute_bundle(
                request,
                binding,
                sandbox_provider=sandbox_provider,
            )
            return executed if bundle else executed.document

    def test_agent_prompt_requires_exact_candidate_in_final_response(self):
        prompt = provider._agent_prompt(
            175,
            "planner",
            self.fixture.request(),
            {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
            [],
        )

        self.assertIn(
            "The final assistant response content must be exactly one JSON object",
            prompt,
        )
        self.assertIn(
            "Emit no prose, Markdown fences, or other content in that final response.",
            prompt,
        )

    def test_reviewer_prompts_communicate_exact_candidate_contract(self):
        expected_outer_keys = ["format", "kind", "verdict", "findings", "pr"]
        for operation in ("review-plan", "review-tests", "review-final"):
            with self.subTest(operation=operation):
                request = self.fixture.request()
                request["operation"] = {
                    "kind": "agent",
                    "name": operation,
                    "role": "reviewer",
                }
                contract = provider._review_candidate_contract(operation)
                prompt = provider._agent_prompt(
                    175,
                    "reviewer",
                    request,
                    {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                    [],
                )

                self.assertEqual(expected_outer_keys, contract["required"])
                self.assertFalse(contract["additionalProperties"])
                self.assertEqual(
                    provider.CANDIDATE_FORMAT,
                    contract["properties"]["format"]["const"],
                )
                self.assertEqual("review", contract["properties"]["kind"]["const"])
                self.assertEqual(
                    ["accepted", "needs-revision", "full-review-required"],
                    contract["properties"]["verdict"]["enum"],
                )
                finding = contract["properties"]["findings"]["items"]
                self.assertEqual(
                    ["unit_ids", "category", "detail"],
                    finding["required"],
                )
                self.assertFalse(finding["additionalProperties"])
                self.assertIn(
                    json.dumps(
                        contract,
                        ensure_ascii=True,
                        sort_keys=True,
                        separators=(",", ":"),
                    ),
                    prompt,
                )
                self.assertIn("Additional outer keys are forbidden.", prompt)
                self.assertIn(
                    "The final assistant response content must be exactly one JSON object",
                    prompt,
                )
                self.assertIn(
                    "Emit no prose, Markdown fences, or other content in that final response.",
                    prompt,
                )

                pr = contract["properties"]["pr"]
                if operation == "review-final":
                    self.assertEqual(["head_ref", "title", "body"], pr["required"])
                    self.assertFalse(pr["additionalProperties"])
                    self.assertTrue(
                        all(pr["properties"][key]["minLength"] == 1 for key in pr["required"])
                    )
                else:
                    self.assertIn("may be empty", pr["description"])

    def test_prompted_review_candidate_shape_matches_strict_decoder(self):
        self.assertEqual(resume.CANDIDATE_FORMAT, provider.CANDIDATE_FORMAT)
        for operation in ("review-plan", "review-tests", "review-final"):
            with self.subTest(operation=operation):
                candidate = {
                    "format": provider.CANDIDATE_FORMAT,
                    "kind": "review",
                    "verdict": "accepted",
                    "findings": [],
                    "pr": (
                        {
                            "head_ref": "issue-198",
                            "title": "Report the STANDARD time-control alias",
                            "body": (
                                "## What\nReport STANDARD.\n\n"
                                "## Why\nMatch the accepted alias.\n\n"
                                "## Testing\nRun the backend checks.\n"
                            ),
                        }
                        if operation == "review-final"
                        else {}
                    ),
                }

                self.assertEqual(
                    candidate,
                    resume.candidate_schema(candidate, "review"),
                )
                if operation == "review-plan":
                    self.assertEqual(
                        ("accepted", []),
                        resume.validate_review_candidate(
                            candidate,
                            {
                                "kind": "evidence-binding",
                                "sha256": "d" * 64,
                                "size": 1,
                            },
                        ),
                    )
                if operation == "review-final":
                    self.assertEqual(
                        candidate["pr"],
                        resume.validate_pr_metadata(candidate["pr"]),
                    )

    def test_jsonl_tool_flow_extracts_exact_candidate_and_ignores_intermediate_prose(self):
        raw = _jsonl()
        candidate = provider._extract_candidate_from_jsonl(raw)

        self.assertEqual(CANDIDATE.encode("utf-8"), candidate)
        self.assertNotEqual(inspector.sha256(raw), inspector.sha256(candidate))
        self.assertNotEqual(len(raw), len(candidate))
        result = {
            "outcome": "succeeded",
            "candidate_output": {
                "sha256": inspector.sha256(candidate),
                "size": len(candidate),
            },
            "process_result": {
                "stdout": {
                    "bytes": len(candidate),
                    "base64": base64.b64encode(candidate).decode("ascii"),
                }
            },
        }
        self.assertEqual(
            json.loads(CANDIDATE),
            resume.decode_candidate(result, "plan"),
        )

    def test_observed_pinned_cli_ephemeral_reasoning_fixture_is_accepted(self):
        raw = PINNED_CLI_FIXTURE.read_bytes()
        events = _jsonl_events(raw)
        expected = next(
            event["data"]["content"]
            for event in events
            if event.get("type") == "assistant.message"
            and event["data"]["toolRequests"] == []
        ).encode("utf-8")

        self.assertEqual(expected, provider._extract_candidate_from_jsonl(raw))
        self.assertEqual(
            [
                "session.mcp_servers_loaded",
                "session.skills_loaded",
                "session.tools_updated",
                "user.message",
            ],
            [event["type"] for event in events[:4]],
        )
        self.assertTrue(
            all(
                event.get("ephemeral") is True
                for event in events
                if event["type"]
                in {
                    *provider.JSONL_STARTUP_TYPES,
                    "model.call_start",
                    "assistant.reasoning_delta",
                    "assistant.reasoning",
                }
            )
        )

    def test_observed_reasoning_to_final_message_fixture_is_accepted(self):
        raw = FINAL_REASONING_FIXTURE.read_bytes()

        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(raw),
        )
        self.assertEqual(
            [
                "assistant.reasoning_delta",
                "assistant.reasoning_delta",
                "assistant.message_start",
                "assistant.message_delta",
                "assistant.message",
                "assistant.reasoning",
                "assistant.turn_end",
            ],
            [
                event["type"]
                for event in _jsonl_events(raw)
                if event["type"]
                in {
                    "assistant.reasoning_delta",
                    "assistant.message_start",
                    "assistant.message_delta",
                    "assistant.message",
                    "assistant.reasoning",
                    "assistant.turn_end",
                }
            ],
        )

    def test_observed_denied_tool_probe_fixtures_are_accepted(self):
        for fixture in DENIED_TOOL_FIXTURES:
            with self.subTest(fixture=fixture.name):
                self.assertEqual(
                    b"PROBE_COMPLETE",
                    provider._extract_candidate_from_jsonl(fixture.read_bytes()),
                )

    def test_opaque_denied_tool_transition_accepts_no_normal_background_events(self):
        events = _jsonl_events(DENIED_TOOL_FIXTURES[0].read_bytes())
        ids = {event.get("id") for event in events}
        opaque = next(
            event
            for event in events
            if event["type"] == "session.background_tasks_changed"
            and event["parentId"] not in ids
        )
        completion = events[events.index(opaque) + 1]
        start = next(
            event
            for event in events
            if event["type"] == "tool.execution_start"
            and event["data"]["toolCallId"] == completion["data"]["toolCallId"]
        )
        events = [
            event
            for event in events
            if not (
                event["type"] == "session.background_tasks_changed"
                and event["parentId"] == start["id"]
            )
        ]

        self.assertEqual(
            b"PROBE_COMPLETE",
            provider._extract_candidate_from_jsonl(_encode_events(events)),
        )

    def test_opaque_denied_tool_transition_rejects_near_misses(self):
        def changed(mutator):
            events = _jsonl_events(DENIED_TOOL_FIXTURES[0].read_bytes())
            ids = {event.get("id") for event in events}
            opaque = next(
                event
                for event in events
                if event["type"] == "session.background_tasks_changed"
                and event["parentId"] not in ids
                and events.index(event) > 3
            )
            completion = events[events.index(opaque) + 1]
            start = next(
                event
                for event in events
                if event["type"] == "tool.execution_start"
                and event["data"]["toolCallId"] == completion["data"]["toolCallId"]
            )
            mutator(events, opaque, completion, start)
            return _encode_events(events)

        def add_second_active_tool(events, _opaque, completion, start):
            message = next(
                event
                for event in events
                if event["type"] == "assistant.message"
                and event["data"]["toolRequests"]
            )
            request = copy.deepcopy(message["data"]["toolRequests"][0])
            request["toolCallId"] = "second-tool-call"
            message["data"]["toolRequests"].append(request)
            delta = next(
                event
                for event in events
                if event["type"] == "assistant.tool_call_delta"
            )
            second_delta = copy.deepcopy(delta)
            second_delta["id"] = "second-tool-delta"
            second_delta["data"]["toolCallId"] = "second-tool-call"
            events.insert(events.index(message), second_delta)
            second_start = copy.deepcopy(start)
            second_start["id"] = "second-tool-start"
            second_start["parentId"] = start["id"]
            second_start["data"]["toolCallId"] = "second-tool-call"
            events.insert(events.index(start) + 1, second_start)

        cases = {
            "without-active-tool": changed(
                lambda _events, _opaque, _completion, start: start.update(
                    {
                        "type": "session.background_tasks_changed",
                        "data": {},
                        "ephemeral": True,
                    }
                )
            ),
            "wrong-successor": changed(
                lambda _events, opaque, completion, _start: completion.update(
                    {
                        "type": "session.background_tasks_changed",
                        "parentId": opaque["parentId"],
                        "data": {},
                        "ephemeral": True,
                    }
                )
            ),
            "different-parent": changed(
                lambda _events, _opaque, completion, _start: completion.__setitem__(
                    "parentId", "different-opaque-parent"
                )
            ),
            "resolved-parent": changed(
                lambda _events, opaque, completion, start: (
                    opaque.__setitem__("parentId", start["id"]),
                    completion.__setitem__("parentId", start["id"]),
                )
            ),
            "different-tool-call": changed(
                lambda _events, _opaque, completion, _start: completion["data"].__setitem__(
                    "toolCallId", "different-tool-call"
                )
            ),
            "successful-completion": changed(
                lambda _events, _opaque, completion, _start: completion["data"].__setitem__(
                    "success", True
                )
            ),
            "different-error": changed(
                lambda _events, _opaque, completion, _start: completion["data"][
                    "error"
                ].__setitem__("code", "failed")
            ),
            "different-message": changed(
                lambda _events, _opaque, completion, _start: completion["data"][
                    "error"
                ].__setitem__("message", "Permission denied")
            ),
            "different-telemetry": changed(
                lambda _events, _opaque, completion, _start: completion["data"][
                    "toolTelemetry"
                ]["properties"].__setitem__(
                    "shell_error_category", "execution_failed"
                )
            ),
            "completion-without-transition": changed(
                lambda events, opaque, _completion, _start: events.remove(opaque)
            ),
            "arbitrary-unresolved-parent": changed(
                lambda events, _opaque, _completion, start: next(
                    event
                    for event in events
                    if event["type"] == "session.background_tasks_changed"
                    and event["parentId"] == start["id"]
                ).__setitem__("parentId", "arbitrary-unresolved-parent")
            ),
            "reused-opaque-parent": changed(
                lambda events, opaque, completion, _start: events.insert(
                    events.index(completion) + 1,
                    _event(
                        "session.background_tasks_changed",
                        "reused-opaque-parent-event",
                        opaque["parentId"],
                        {},
                        True,
                    ),
                )
            ),
            "multiple-active-tools": changed(add_second_active_tool),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_successful_tool_execution_remains_accepted(self):
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(_jsonl()),
        )

    def test_reasoning_to_message_path_rejects_invalid_transitions(self):
        def changed(mutator):
            events = _jsonl_events(FINAL_REASONING_FIXTURE.read_bytes())
            mutator(events)
            return _encode_events(events)

        def event(events, event_id):
            return next(item for item in events if item.get("id") == event_id)

        cases = {
            "reasoning-skips-message-start": changed(
                lambda events: events.remove(event(events, "message-start"))
            ),
            "reasoning-skips-message-delta": changed(
                lambda events: events.remove(event(events, "message-delta"))
            ),
            "reasoning-message-parent": changed(
                lambda events: event(events, "message-start").__setitem__(
                    "parentId", "user-message"
                )
            ),
            "reasoning-delta-parent": changed(
                lambda events: event(events, "message-delta").__setitem__(
                    "parentId", "user-message"
                )
            ),
            "reasoning-delta-message-id": changed(
                lambda events: event(events, "message-delta")["data"].__setitem__(
                    "messageId", "other-message"
                )
            ),
            "reasoning-message-interruption": changed(
                lambda events: events.insert(
                    next(
                        index
                        for index, item in enumerate(events)
                        if item.get("id") == "message-delta"
                    ),
                    _event(
                        "session.background_tasks_changed",
                        "message-interruption",
                        "turn-start",
                        {},
                        True,
                    ),
                )
            ),
            "reasoning-message-id": changed(
                lambda events: event(events, "assistant-message")["data"].__setitem__(
                    "messageId", "other-message"
                )
            ),
            "reasoning-summary-missing": changed(
                lambda events: events.remove(event(events, "reasoning-summary"))
            ),
            "reasoning-summary-successor": changed(
                lambda events: events.insert(
                    next(
                        index
                        for index, item in enumerate(events)
                        if item.get("id") == "turn-end"
                    ),
                    _event(
                        "session.background_tasks_changed",
                        "invalid-successor",
                        "assistant-message",
                        {},
                        True,
                    ),
                )
            ),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_observed_assistant_message_metadata_is_accepted_without_changing_candidate(self):
        events = _jsonl_events(_jsonl())
        metadata = json.loads(ASSISTANT_MESSAGE_METADATA_FIXTURE.read_text())
        for event in events:
            if event["type"] == "assistant.message":
                event["data"].update(copy.deepcopy(metadata))
                for request in event["data"]["toolRequests"]:
                    request["intentionSummary"] = "Inspect repository state"
                    request["toolTitle"] = "Inspect"

        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(_encode_events(events)),
        )

    def test_assistant_message_required_fields_remain_required_and_typed(self):
        def changed(mutator):
            events = _jsonl_events(_jsonl())
            message = next(
                event
                for event in events
                if event["type"] == "assistant.message"
                and event["data"]["toolRequests"] == []
            )
            message["data"].update(
                json.loads(ASSISTANT_MESSAGE_METADATA_FIXTURE.read_text())
            )
            mutator(message["data"])
            return _encode_events(events)

        cases = {
            "missing-content": changed(lambda data: data.pop("content")),
            "missing-message-id": changed(lambda data: data.pop("messageId")),
            "missing-turn-id": changed(lambda data: data.pop("turnId")),
            "missing-interaction-id": changed(lambda data: data.pop("interactionId")),
            "missing-tool-requests": changed(lambda data: data.pop("toolRequests")),
            "malformed-content": changed(lambda data: data.__setitem__("content", {})),
            "malformed-message-id": changed(lambda data: data.__setitem__("messageId", "")),
            "malformed-turn-id": changed(lambda data: data.__setitem__("turnId", 2)),
            "malformed-interaction-id": changed(
                lambda data: data.__setitem__("interactionId", None)
            ),
            "malformed-tool-requests": changed(
                lambda data: data.__setitem__("toolRequests", {})
            ),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure) as raised:
                    provider._extract_candidate_from_jsonl(raw)
                self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

    def test_assistant_message_outer_envelope_remains_closed(self):
        events = _jsonl_events(_jsonl())
        message = next(
            event for event in events if event["type"] == "assistant.message"
        )
        message["untrusted"] = True

        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

    def test_tool_request_consumed_fields_remain_required_and_typed(self):
        def changed(mutator):
            events = _jsonl_events(_jsonl())
            request = next(
                event
                for event in events
                if event["type"] == "assistant.message"
                and event["data"]["toolRequests"]
            )["data"]["toolRequests"][0]
            request["intentionSummary"] = "Inspect repository state"
            mutator(request)
            return _encode_events(events)

        cases = {
            "missing-tool-call-id": changed(
                lambda request: request.pop("toolCallId")
            ),
            "missing-name": changed(lambda request: request.pop("name")),
            "missing-type": changed(lambda request: request.pop("type")),
            "missing-arguments": changed(lambda request: request.pop("arguments")),
            "malformed-tool-call-id": changed(
                lambda request: request.__setitem__("toolCallId", "")
            ),
            "malformed-name": changed(
                lambda request: request.__setitem__("name", "")
            ),
            "malformed-arguments": changed(
                lambda request: request.__setitem__("arguments", "invalid")
            ),
            "unreviewed-type": changed(
                lambda request: request.__setitem__("type", "unknown")
            ),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_unconsumed_tool_start_metadata_is_optional_but_typed_when_present(self):
        events = _jsonl_events(_jsonl())
        tool_start = next(
            event for event in events if event["type"] == "tool.execution_start"
        )
        tool_start["data"].pop("shellToolInfo")
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(_encode_events(events)),
        )

        tool_start["data"]["shellToolInfo"] = "invalid"
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

        tool_start["data"]["shellToolInfo"] = {}
        tool_start["data"]["unexpected"] = True
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

    def test_jsonl_rejects_candidate_in_tool_request_turn(self):
        events = _jsonl_events(_jsonl())
        events = [
            event
            for event in events
            if event.get("id") not in {"t1e", "t2s", "m2", "ms2", "md2"}
        ]
        final_message = next(event for event in events if event.get("id") == "a2")
        final_message["parentId"] = "x2"
        final_message["data"]["turnId"] = "1"
        final_message["data"]["interactionId"] = "i1"
        final_turn_end = next(event for event in events if event.get("id") == "t2e")
        final_turn_end["data"]["turnId"] = "1"

        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("local-agent-jsonl-candidate", raised.exception.code)

    def test_jsonl_silent_semantics_do_not_require_tool_delta_count(self):
        events = _jsonl_events(_jsonl())
        delta = next(event for event in events if event["type"] == "assistant.tool_call_delta")
        events.insert(events.index(delta), {**copy.deepcopy(delta), "id": "d0"})

        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(_encode_events(events)),
        )

    def test_jsonl_startup_and_progress_records_are_optional(self):
        events = [
            event
            for event in _jsonl_events(_jsonl())
            if event["type"]
            not in {
                "session.mcp_servers_loaded",
                "session.skills_loaded",
                "session.tools_updated",
                "assistant.tool_call_delta",
                "session.background_tasks_changed",
                "tool.execution_partial_result",
                "assistant.message_start",
                "assistant.message_delta",
                "session.usage_checkpoint",
            }
        ]
        exact = " \n" + CANDIDATE + "\n "
        next(
            event
            for event in events
            if event["type"] == "assistant.message" and event["data"]["messageId"] == "message-2"
        )["data"]["content"] = exact
        for event in events:
            if event.get("parentId") == "usage":
                event["parentId"] = "t2e"

        self.assertEqual(
            exact.encode("utf-8"),
            provider._extract_candidate_from_jsonl(_encode_events(events)),
        )

    def test_final_prose_is_extracted_unchanged_and_rejected_by_existing_decoder(self):
        candidate = provider._extract_candidate_from_jsonl(_jsonl(candidate="not JSON"))
        result = {
            "outcome": "succeeded",
            "candidate_output": {
                "sha256": inspector.sha256(candidate),
                "size": len(candidate),
            },
            "process_result": {
                "stdout": {
                    "bytes": len(candidate),
                    "base64": base64.b64encode(candidate).decode("ascii"),
                }
            },
        }

        self.assertEqual(b"not JSON", candidate)
        with self.assertRaises(resume.ResumeFailure):
            resume.decode_candidate(result, "plan")

    def test_jsonl_framing_and_json_fail_closed(self):
        valid = _jsonl()
        malformed_cases = {
            "blank-line": valid.replace(b"\n", b"\n\n", 1),
            "malformed-json": b"{]\n",
            "duplicate-key": b'{"type":"x","type":"y"}\n',
            "non-object": b"[]\n",
            "invalid-utf8": b"\xff\n",
            "missing-lf": valid[:-1],
            "truncated-record": valid + b'{"type":\n',
        }
        for name, raw in malformed_cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_jsonl_lone_surrogate_is_a_typed_provider_failure(self):
        raw = _jsonl(candidate="SURROGATE").replace(
            b'"content":"SURROGATE"',
            b'"content":"\\ud800"',
        )

        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(raw)
        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)
        self.assertIsInstance(raised.exception.__cause__, UnicodeEncodeError)

    def test_jsonl_explicit_transport_bounds_fail_closed(self):
        raw = _jsonl()
        first_line_size = len(raw.split(b"\n", 1)[0]) + 1
        bounds = (
            ("event-count", "JSONL_MAX_EVENTS", 1),
            ("event-bytes", "JSONL_MAX_EVENT_BYTES", first_line_size - 1),
        )
        for name, setting, value in bounds:
            with self.subTest(name=name), mock.patch.object(provider, setting, value):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_jsonl_accepts_exact_configured_event_byte_bound_only(self):
        self.assertEqual(851_968, provider.JSONL_MAX_EVENT_BYTES)
        events = _jsonl_events(_jsonl())
        partial = next(
            event
            for event in events
            if event["type"] == "tool.execution_partial_result"
        )
        partial["data"]["partialOutput"] = ""
        base = len(_encode_events([partial]))
        partial["data"]["partialOutput"] = "x" * (
            provider.JSONL_MAX_EVENT_BYTES - base
        )
        bounded = _encode_events(events)

        self.assertEqual(
            provider.JSONL_MAX_EVENT_BYTES,
            len(_encode_events([partial])),
        )
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(bounded),
        )
        partial["data"]["partialOutput"] += "x"
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

    def test_jsonl_accepts_exact_event_count_bound_only(self):
        self.assertEqual(32_768, provider.JSONL_MAX_EVENTS)
        events = _jsonl_events(_jsonl())
        insertion = next(
            index
            for index, event in enumerate(events)
            if event["type"] == "tool.execution_partial_result"
        )
        additions = [
            _event(
                "session.background_tasks_changed",
                "count-%d" % index,
                "x1",
                {},
                True,
            )
            for index in range(provider.JSONL_MAX_EVENTS - len(events))
        ]
        bounded = _encode_events(events[:insertion] + additions + events[insertion:])
        over = _encode_events(
            events[:insertion]
            + additions
            + [
                _event(
                    "session.background_tasks_changed",
                    "count-over",
                    "x1",
                    {},
                    True,
                )
            ]
            + events[insertion:]
        )

        self.assertEqual(
            provider.JSONL_MAX_EVENTS,
            len(_jsonl_events(bounded)),
        )
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            provider._extract_candidate_from_jsonl(bounded),
        )
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(over)
        self.assertEqual("local-agent-jsonl-invalid", raised.exception.code)

    def test_jsonl_accepts_exact_candidate_bound_only(self):
        self.assertEqual(458_752, provider.CANDIDATE_MAX_BYTES)
        events = [
            event
            for event in _jsonl_events(_jsonl())
            if event["type"]
            not in {"assistant.message_start", "assistant.message_delta"}
        ]
        final = next(
            event
            for event in events
            if event["type"] == "assistant.message"
            and event["data"]["toolRequests"] == []
        )
        final["data"]["content"] = "x" * provider.CANDIDATE_MAX_BYTES
        bounded = _encode_events(events)
        self.assertLessEqual(len(bounded), provider.TRANSPORT_MAX_BYTES)
        self.assertEqual(
            b"x" * provider.CANDIDATE_MAX_BYTES,
            provider._extract_candidate_from_jsonl(bounded),
        )

        final["data"]["content"] += "x"
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider._extract_candidate_from_jsonl(_encode_events(events))
        self.assertEqual("unsupported", raised.exception.status)
        self.assertEqual("local-agent-candidate-too-large", raised.exception.code)

    def test_jsonl_protocol_relationships_fail_closed(self):
        def changed(mutator):
            events = _jsonl_events(_jsonl())
            mutator(events)
            return _encode_events(events)

        cases = {
            "unknown-event": changed(lambda events: events.__setitem__(5, {**events[5], "type": "session.error"})),
            "abort-event": changed(lambda events: events.__setitem__(5, {**events[5], "type": "assistant.abort"})),
            "duplicate-event-id": changed(lambda events: events[6].__setitem__("id", events[5]["id"])),
            "duplicate-user": changed(lambda events: events.insert(4, {**copy.deepcopy(events[3]), "id": "u2"})),
            "broken-parent": changed(lambda events: events[12].__setitem__("parentId", "missing")),
            "broken-interaction": changed(
                lambda events: next(
                    event for event in events if event["type"] == "tool.execution_complete"
                )["data"].__setitem__("interactionId", "wrong")
            ),
            "missing-interaction": changed(
                lambda events: next(
                    event for event in events if event["type"] == "assistant.message"
                )["data"].pop("interactionId")
            ),
            "unmatched-tool-call": changed(
                lambda events: (
                    events.pop(next(index for index, event in enumerate(events) if event["type"] == "tool.execution_complete")),
                    next(event for event in events if event["type"] == "assistant.turn_end").__setitem__("parentId", "x1"),
                )
            ),
            "final-message-with-tools": changed(
                lambda events: next(
                    event
                    for event in events
                    if event["type"] == "assistant.message" and event["data"]["messageId"] == "message-2"
                )["data"].__setitem__(
                    "toolRequests",
                    [{"toolCallId": "late", "name": "view", "type": "function", "arguments": {}}],
                )
            ),
            "candidate-like-nonfinal": changed(
                lambda events: next(
                    event
                    for event in events
                    if event["type"] == "assistant.message" and event["data"]["messageId"] == "message-1"
                )["data"].__setitem__("content", CANDIDATE)
            ),
            "escaped-candidate-like-nonfinal": changed(
                lambda events: next(
                    event
                    for event in events
                    if event["type"] == "assistant.message" and event["data"]["messageId"] == "message-1"
                )["data"].__setitem__(
                    "content",
                    CANDIDATE.replace("candidate-v1", "candidate-\\u00761"),
                )
            ),
            "nonzero-result": changed(lambda events: events[-1].__setitem__("exitCode", 1)),
            "noninteger-result": changed(lambda events: events[-1].__setitem__("exitCode", False)),
            "malformed-result": changed(lambda events: events[-1].pop("usage")),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_observed_ephemeral_reasoning_shapes_fail_closed(self):
        def changed(mutator):
            events = _jsonl_events(PINNED_CLI_FIXTURE.read_bytes())
            mutator(events)
            return _encode_events(events)

        def event(events, event_id):
            return next(item for item in events if item.get("id") == event_id)

        def insert_before(events, event_id, item):
            events.insert(
                next(
                    index
                    for index, value in enumerate(events)
                    if value.get("id") == event_id
                ),
                item,
            )

        cases = {
            "startup-persistence": changed(
                lambda events: event(events, "startup-mcp").pop("ephemeral")
            ),
            "startup-schema": changed(
                lambda events: event(events, "startup-skills")["data"].update(
                    {"extra": []}
                )
            ),
            "startup-type": changed(
                lambda events: event(events, "startup-tools")["data"].__setitem__(
                    "model", []
                )
            ),
            "startup-parent-resolves": changed(
                lambda events: event(events, "startup-mcp").__setitem__(
                    "parentId", "user-message"
                )
            ),
            "startup-parent-repeats": changed(
                lambda events: event(events, "startup-skills").__setitem__(
                    "parentId", "external-startup-a"
                )
            ),
            "model-persistence": changed(
                lambda events: event(events, "model-call-1").pop("ephemeral")
            ),
            "model-schema": changed(
                lambda events: event(events, "model-call-1")["data"].update(
                    {"interactionId": "interaction-1"}
                )
            ),
            "model-parent": changed(
                lambda events: event(events, "model-call-1").__setitem__(
                    "parentId", "user-message"
                )
            ),
            "reasoning-delta-persistence": changed(
                lambda events: event(events, "reasoning-delta-1").pop("ephemeral")
            ),
            "reasoning-delta-schema": changed(
                lambda events: event(events, "reasoning-delta-1")["data"].update(
                    {"extra": ""}
                )
            ),
            "reasoning-delta-parent": changed(
                lambda events: event(events, "reasoning-delta-1").__setitem__(
                    "parentId", "model-call-1"
                )
            ),
            "reasoning-delta-group": changed(
                lambda events: event(events, "reasoning-delta-2")["data"].__setitem__(
                    "reasoningId", "other-reasoning"
                )
            ),
            "reasoning-delta-sequence": changed(
                lambda events: insert_before(
                    events,
                    "reasoning-delta-1",
                    _event(
                        "session.background_tasks_changed",
                        "between-model-and-reasoning",
                        "turn-start-1",
                        {},
                        True,
                    ),
                )
            ),
            "reasoning-persistence": changed(
                lambda events: event(events, "reasoning-summary-1").pop("ephemeral")
            ),
            "reasoning-schema": changed(
                lambda events: event(events, "reasoning-summary-1")["data"].update(
                    {"extra": True}
                )
            ),
            "reasoning-rte": changed(
                lambda events: event(events, "reasoning-summary-1")["data"].__setitem__(
                    "rte", False
                )
            ),
            "reasoning-parent": changed(
                lambda events: event(events, "reasoning-summary-1").__setitem__(
                    "parentId", "turn-start-1"
                )
            ),
            "reasoning-id": changed(
                lambda events: event(events, "reasoning-summary-1")["data"].__setitem__(
                    "reasoningId", "other-reasoning"
                )
            ),
            "reasoning-cannot-be-final": changed(
                lambda events: event(events, "assistant-message-1")[
                    "data"
                ].__setitem__("toolRequests", [])
            ),
            "reasoning-summary-sequence": changed(
                lambda events: insert_before(
                    events,
                    "reasoning-summary-1",
                    _event(
                        "session.background_tasks_changed",
                        "before-reasoning-summary",
                        "assistant-message-1",
                        {},
                        True,
                    ),
                )
            ),
            "reasoning-tool-sequence": changed(
                lambda events: insert_before(
                    events,
                    "tool-start-1",
                    _event(
                        "session.background_tasks_changed",
                        "after-reasoning-summary",
                        "assistant-message-1",
                        {},
                        True,
                    ),
                )
            ),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_jsonl_final_boundary_failures_are_rejected(self):
        def without(event_type):
            return _encode_events(
                event for event in _jsonl_events(_jsonl()) if event["type"] != event_type
            )

        events = _jsonl_events(_jsonl())
        trailing = copy.deepcopy(events[-2])
        trailing["id"] = "trailing"
        multiple = _jsonl_events(_jsonl())
        final_index = next(
            index
            for index, event in enumerate(multiple)
            if event["type"] == "assistant.message" and event["data"]["messageId"] == "message-2"
        )
        second = copy.deepcopy(multiple[final_index])
        second["id"], second["parentId"], second["data"]["messageId"] = "a3", "a2", "message-3"
        multiple[final_index + 1]["parentId"] = "a3"
        multiple.insert(final_index + 1, second)
        cases = {
            "missing-turn-end": without("assistant.turn_end"),
            "missing-idle": without("assistant.idle"),
            "missing-result": _encode_events(_jsonl_events(_jsonl())[:-1]),
            "trailing-record": _jsonl() + _encode_events([trailing]),
            "multiple-finals": _encode_events(multiple),
        }
        for name, raw in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(provider.LocalProviderFailure):
                    provider._extract_candidate_from_jsonl(raw)

    def test_execution_binds_command_workspace_authority_environment_and_result(self):
        instance = self.fixture.instance()
        request = self.fixture.request()
        binding = {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1}
        projected = [{"role": "issue-source", "binding": binding, "entries": [{"path": "issue.json", "bytes_base64": "e30=", "sha256": "a" * 64, "size": 2}]}]
        launched = []
        launched_commands = []
        original_supervise = provider.supervisor.supervise

        def supervise(command, **options):
            if command[0] == str(self.fixture.agent):
                launched_commands.append(copy.deepcopy(command))
                launched.append(copy.deepcopy(options))
            return original_supervise(command, **options)

        with mock.patch.object(
            provider, "_input_projection", return_value=projected
        ), mock.patch.object(
            provider.supervisor, "supervise", side_effect=supervise
        ):
            result = instance.execute(
                request,
                binding,
                ["agent"],
                str(self.fixture.workspace),
                self.fixture.environment(),
                request["limits"],
                None,
            )
        process, facts = result["process_result"], result["provider_result"]
        self.assertEqual("success", process["outcome"])
        self.assertEqual(process["command_sha256"], facts["command"]["argv_sha256"])
        self.assertEqual(request["authority_binding"], facts["authority_binding"])
        self.assertEqual(self.fixture.head, facts["workspace"]["selected_commit"])
        self.assertEqual(str(self.fixture.workspace), facts["workspace"]["cwd"])
        self.assertEqual(inspector.sha256(inspector.canonical_bytes(projected)), facts["input_projection_sha256"])
        self.assertIn("exact host-projected immutable inputs", facts["command"]["argv"][-1])
        self.assertIn("read-only: do not change or commit", facts["command"]["argv"][-1])
        self.assertIn("--disable-builtin-mcps", facts["command"]["argv"])
        self.assertEqual(
            ["--output-format", "json"],
            facts["command"]["argv"][
                facts["command"]["argv"].index("--output-format") :
                facts["command"]["argv"].index("--output-format") + 2
            ],
        )
        self.assertIn("--silent", facts["command"]["argv"])
        self.assertEqual(facts["command"]["argv"], launched_commands[0])
        self.assertEqual(1, launched_commands[0].count("--stream"))
        self.assertEqual(
            ["--stream", "off", "--prompt"],
            launched_commands[0][-4:-1],
        )
        self.assertIn(
            "--secret-env-vars=COPILOT_GITHUB_TOKEN",
            facts["command"]["argv"],
        )
        self.assertEqual(
            {
                "COPILOT_GITHUB_TOKEN",
                "HOME",
                "LANG",
                "LC_ALL",
                "PATH",
                "TZ",
            },
            set(launched[0]["env"]),
        )
        self.assertEqual(
            self.fixture.worker_token,
            launched[0]["env"]["COPILOT_GITHUB_TOKEN"],
        )
        self.assertNotIn("GH_TOKEN", launched[0]["env"])
        self.assertNotIn("GITHUB_TOKEN", launched[0]["env"])
        self.assertEqual(
            runtime.LOCAL_PROVIDER_STDOUT_LIMIT_BYTES,
            launched[0]["output_limit_bytes"],
        )
        self.assertEqual(
            runtime.LOCAL_PROVIDER_STDERR_LIMIT_BYTES,
            launched[0]["stderr_limit_bytes"],
        )
        self.assertEqual(
            ["COPILOT_GITHUB_TOKEN"], facts["environment"]["secret_keys"]
        )
        self.assertEqual(
            instance.authentication, facts["environment"]["authentication"]
        )
        self.assertNotIn("values", facts["environment"])
        self.assertNotIn(self.fixture.worker_token, repr(result))
        self.assertNotIn(self.fixture.worker_token, json.dumps(facts["command"]))
        self.assertNotIn(self.fixture.worker_token, repr(instance))
        self.assertIsNone(instance._worker_token)
        for path in self.fixture.parent.rglob("*"):
            if path.is_file():
                self.assertNotIn(self.fixture.worker_token.encode(), path.read_bytes())
        self.assertEqual("not-isolated-same-uid", facts["isolation"]["filesystem"])
        self.assertEqual(
            facts["result_sha256"],
            inspector.sha256(
                inspector.canonical_bytes(
                    {key: value for key, value in facts.items() if key != "result_sha256"}
                )
            ),
        )

    def test_provider_identity_mismatch_fails_before_execution(self):
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            self.fixture.instance(provider_source_sha256="0" * 64)
        self.assertEqual("local-provider-config-mismatch", raised.exception.code)

    def test_agent_executable_replacement_fails_before_launch(self):
        instance = self.fixture.instance()
        self.fixture.agent.write_text("#!/bin/sh\nexit 7\n")
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            instance.execute(
                self.fixture.request(),
                {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                ["agent"],
                str(self.fixture.workspace),
                self.fixture.environment(),
                self.fixture.request()["limits"],
                None,
            )
        self.assertEqual("local-agent-executable-replaced", raised.exception.code)

    def test_agent_home_must_be_dedicated_empty_and_private(self):
        (self.fixture.home / ".copilot").mkdir()
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            self.fixture.instance()
        self.assertEqual("agent-home-not-dedicated", raised.exception.code)

        nested = self.fixture.workspace / "agent-home"
        nested.mkdir(mode=0o700)
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider.LocalSandboxProvider(
                root=self.fixture.workspace,
                control_root=self.fixture.control,
                issue=175,
                role="planner",
                row=self.fixture.row(),
                git_executable=self.fixture.git,
                agent_executable=self.fixture.agent,
                agent_home=nested,
                trusted_worker_authentication=True,
                worker_token=self.fixture.worker_token,
            )
        self.assertEqual("agent-home-not-dedicated", raised.exception.code)

        (self.fixture.home / ".copilot").rmdir()
        self.fixture.home.chmod(0o755)
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            self.fixture.instance()
        self.assertEqual("agent-home-not-dedicated", raised.exception.code)

    def test_trusted_worker_authentication_fails_closed_before_worker_launch(self):
        binding = {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1}
        request = self.fixture.request()
        cases = (
            (
                self.fixture.instance(trusted_worker_authentication=False),
                "worker-authentication-not-configured",
            ),
            (
                lambda: self.fixture.instance(worker_token="\n"),
                "worker-authentication-malformed",
            ),
        )
        original_supervise = provider.supervisor.supervise

        def reject_worker(command, **options):
            if command[0] == str(self.fixture.agent):
                raise AssertionError("worker must not launch")
            return original_supervise(command, **options)

        for supplied, expected in cases:
            with self.subTest(expected=expected):
                if callable(supplied):
                    with self.assertRaises(provider.LocalProviderFailure) as raised:
                        supplied()
                else:
                    with mock.patch.object(
                        provider, "_input_projection", return_value=[]
                    ), mock.patch.object(
                        provider.supervisor,
                        "supervise",
                        side_effect=reject_worker,
                    ):
                        with self.assertRaises(provider.LocalProviderFailure) as raised:
                            supplied.execute(
                                request,
                                binding,
                                ["agent"],
                                str(self.fixture.workspace),
                                self.fixture.environment(),
                                request["limits"],
                                None,
                            )
            self.assertEqual(expected, raised.exception.code)
            self.assertNotIn(self.fixture.worker_token, str(raised.exception))

    def test_worker_secret_disclosure_is_scrubbed_and_never_returned(self):
        self.fixture.agent.write_text(
            "#!/bin/sh\nprintf '%s' \"$COPILOT_GITHUB_TOKEN\"\nexit 7\n"
        )
        self.fixture.agent.chmod(self.fixture.agent.stat().st_mode | stat.S_IXUSR)
        instance = self.fixture.instance()
        with mock.patch.object(provider, "_input_projection", return_value=[]):
            with self.assertRaises(provider.LocalProviderFailure) as raised:
                instance.execute(
                    self.fixture.request(),
                    {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                    ["agent"],
                    str(self.fixture.workspace),
                    self.fixture.environment(),
                    self.fixture.request()["limits"],
                    None,
                )
        self.assertEqual("worker-authentication-disclosed", raised.exception.code)
        self.assertNotIn(self.fixture.worker_token, str(raised.exception))
        self.assertNotIn(self.fixture.worker_token, repr(raised.exception))

    def test_candidate_secret_disclosure_is_scanned_independently(self):
        self.fixture.agent.write_bytes(
            b"#!/bin/sh\ncat <<'JSONL'\n"
            + _jsonl(candidate=self.fixture.worker_token)
            + b"JSONL\nexit 7\n"
        )
        self.fixture.agent.chmod(self.fixture.agent.stat().st_mode | stat.S_IXUSR)
        instance = self.fixture.instance()
        with mock.patch.object(provider, "_input_projection", return_value=[]), mock.patch.object(
            provider, "_process_discloses_secret", return_value=False
        ):
            with self.assertRaises(provider.LocalProviderFailure) as raised:
                instance.execute(
                    self.fixture.request(),
                    {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                    ["agent"],
                    str(self.fixture.workspace),
                    self.fixture.environment(),
                    self.fixture.request()["limits"],
                    None,
                )
        self.assertEqual("worker-authentication-disclosed", raised.exception.code)

    def test_nonzero_process_without_candidate_disclosure_remains_process_failure(self):
        stderr = b"non-secret diagnostic stderr"

        def failed(command, **options):
            return _process_result(
                command,
                stdout_sink=options.get("stdout_sink"),
                stdout=_jsonl(),
                stderr=stderr,
                outcome="nonzero-exit",
                reason="process-exited",
                exit_code=7,
            )

        result = self._execute_through_runtime(supervise=failed)
        diagnostic = result["sandbox"]["process_diagnostic"]
        self.assertEqual("failed", result["outcome"])
        self.assertEqual(
            {"status": "missing", "code": "local-agent-process-failed"},
            diagnostic["provider_failure"],
        )
        self.assertEqual(7, diagnostic["exit_code"])
        self.assertIsNone(diagnostic["terminating_signal"])
        self.assertIsNone(diagnostic["parser_failure"])

    def test_failed_process_diagnostics_bind_supervisor_reason_and_stream_identities(self):
        transport = _jsonl()[:-1]
        stderr = b"bounded stderr"

        def output_limited(command, **options):
            return _process_result(
                command,
                stdout_sink=options.get("stdout_sink"),
                stdout=transport,
                stderr=stderr,
                outcome="output-limit",
                reason="per-stream-output-limit",
                exit_code=None,
                terminating_signal=15,
            )

        result = self._execute_through_runtime(supervise=output_limited)
        diagnostic = result["sandbox"]["process_diagnostic"]
        self.assertEqual("failed", result["outcome"])
        self.assertEqual("output-limit", diagnostic["outcome"])
        self.assertEqual("per-stream-output-limit", diagnostic["reason"])
        self.assertEqual(15, diagnostic["terminating_signal"])
        self.assertEqual(
            {"bytes": len(transport), "sha256": inspector.sha256(transport)},
            diagnostic["stdout"],
        )
        self.assertEqual(
            {"bytes": len(stderr), "sha256": inspector.sha256(stderr)},
            diagnostic["stderr"],
        )
        self.assertEqual(
            {"status": "corrupt", "code": "local-agent-jsonl-truncated"},
            diagnostic["parser_failure"],
        )
        encoded = inspector.canonical_bytes(diagnostic)
        self.assertNotIn(transport, encoded)
        self.assertNotIn(stderr, encoded)
        self.assertNotIn(b"base64", encoded)

    def test_failed_process_diagnostics_distinguish_supervisor_conditions(self):
        transport = _jsonl()[:-1]

        def cancelled(command, **options):
            return _process_result(
                command,
                stdout_sink=options.get("stdout_sink"),
                stdout=transport,
                outcome="terminated",
                reason="cancelled",
                exit_code=None,
                terminating_signal=15,
            )

        diagnostic = self._execute_through_runtime(
            supervise=cancelled
        )["sandbox"]["process_diagnostic"]
        self.assertEqual("terminated", diagnostic["outcome"])
        self.assertEqual("cancelled", diagnostic["reason"])
        self.assertEqual(15, diagnostic["terminating_signal"])

    def test_malformed_process_diagnostic_status_fails_closed(self):
        process = _process_result(
            ["agent"],
            stdout=b"truncated",
            outcome="output-limit",
            reason="per-stream-output-limit",
            exit_code=None,
            terminating_signal=15,
        )
        diagnostic = provider._process_diagnostic(
            process,
            provider.LocalProviderFailure(
                "corrupt", "local-agent-jsonl-truncated", "truncated"
            ),
        )
        diagnostic["parser_failure"]["status"] = ["corrupt"]

        with self.assertRaises(runtime.RuntimeFailure) as raised:
            runtime._process_diagnostic(
                diagnostic,
                process,
                len(b"truncated"),
                inspector.sha256(b"truncated"),
            )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

    def test_successful_jsonl_execution_has_no_failure_diagnostic(self):
        result = self._execute_through_runtime()
        self.assertEqual("succeeded", result["outcome"])
        self.assertIsNone(result["sandbox"]["process_diagnostic"])

    def test_successful_process_with_truncated_jsonl_remains_transport_failure(self):
        transport = _jsonl()[:-1]

        def truncated(command, **options):
            return _process_result(command, stdout=transport, stdout_sink=options.get("stdout_sink"))

        bundle = self._execute_through_runtime(supervise=truncated, bundle=True)
        result = bundle.document
        diagnostic = result["sandbox"]["process_diagnostic"]

        self.assertEqual("failed", result["outcome"])
        self.assertEqual("success", diagnostic["outcome"])
        self.assertEqual(
            {"status": "corrupt", "code": "local-agent-jsonl-truncated"},
            diagnostic["parser_failure"],
        )
        self.assertEqual(diagnostic["parser_failure"], diagnostic["provider_failure"])
        self.assertEqual(0, result["candidate_output"]["size"])
        self.assertEqual(
            [(provider.TRANSPORT_ATTACHMENT_PATH, transport)],
            [(item["path"], item["bytes"]) for item in bundle.attachments],
        )

    def test_successful_process_with_unreviewed_event_preserves_the_raw_sidecar(self):
        events = _jsonl_events(_jsonl())
        events[-2]["type"] = "session.title_changed"
        transport = _encode_events(events[:-1]) + _encode_events(events[-1:])

        def unknown(command, **options):
            return _process_result(
                command, stdout=transport, stdout_sink=options.get("stdout_sink")
            )

        bundle = self._execute_through_runtime(supervise=unknown, bundle=True)
        result = bundle.document
        diagnostic = result["sandbox"]["process_diagnostic"]

        self.assertEqual("failed", result["outcome"])
        self.assertEqual("unsupported", diagnostic["parser_failure"]["status"])
        self.assertEqual(
            "local-agent-jsonl-event", diagnostic["parser_failure"]["code"]
        )
        self.assertEqual(0, result["candidate_output"]["size"])
        self.assertEqual(transport, bundle.attachments[0]["bytes"])
        self.assertEqual(
            inspector.sha256(transport),
            result["sandbox"]["transport_reference"]["sha256"],
        )

    def test_successful_process_with_malformed_jsonl_preserves_the_raw_sidecar(self):
        transport = _jsonl() + b'{"type":\n'

        def malformed(command, **options):
            return _process_result(
                command, stdout=transport, stdout_sink=options.get("stdout_sink")
            )

        bundle = self._execute_through_runtime(supervise=malformed, bundle=True)
        result = bundle.document

        self.assertEqual("failed", result["outcome"])
        self.assertEqual(
            "local-agent-jsonl-invalid",
            result["sandbox"]["process_diagnostic"]["parser_failure"]["code"],
        )
        self.assertEqual(0, result["candidate_output"]["size"])
        self.assertEqual(transport, bundle.attachments[0]["bytes"])

    def test_large_delta_traffic_beyond_the_output_limit_still_yields_the_candidate(self):
        events = _jsonl_events(_jsonl())
        insertion = next(
            index
            for index, event in enumerate(events)
            if event["type"] == "assistant.tool_call_delta"
        )
        filler = "z" * 4096
        noise = [
            _event(
                "assistant.tool_call_delta",
                "delta-%d" % index,
                "t1s",
                {"toolCallId": "tool-1", "inputDelta": filler},
                True,
            )
            for index in range(260)
        ]
        transport = _encode_events(
            events[:insertion] + noise + events[insertion:]
        )
        limit = runtime.LOCAL_PROVIDER_STDOUT_LIMIT_BYTES
        self.assertGreater(len(transport), limit)

        bundle = self._execute_through_runtime(transport=transport, bundle=True)
        result = bundle.document

        self.assertEqual("succeeded", result["outcome"])
        self.assertIsNone(result["sandbox"]["process_diagnostic"])
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            base64.b64decode(
                result["process_result"]["stdout"]["base64"], validate=True
            ),
        )
        self.assertEqual(len(transport), result["sandbox"]["transport_size"])
        self.assertGreater(result["sandbox"]["transport_size"], limit)
        self.assertEqual(transport, bundle.attachments[0]["bytes"])
        self.assertEqual(
            len(CANDIDATE.encode("utf-8")),
            result["process_result"]["stdout"]["observed_bytes"],
        )

    def test_transport_beyond_the_sidecar_bound_fails_closed_without_a_sidecar(self):
        transport = _jsonl()
        with mock.patch.object(
            provider, "TRANSPORT_MAX_BYTES", len(transport) - 1
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                self._execute_through_runtime(transport=transport)
        self.assertEqual("unsupported", raised.exception.status)
        self.assertEqual("local-agent-transport-too-large", raised.exception.code)

    def test_transport_exactly_at_the_sidecar_bound_is_preserved(self):
        transport = _jsonl()
        with mock.patch.object(provider, "TRANSPORT_MAX_BYTES", len(transport)):
            bundle = self._execute_through_runtime(transport=transport, bundle=True)
        self.assertEqual(transport, bundle.attachments[0]["bytes"])

    def test_credential_disclosed_late_in_the_stream_is_denied_without_a_sidecar(self):
        events = _jsonl_events(_jsonl())
        final = next(
            event
            for event in events
            if event["type"] == "tool.execution_complete"
        )
        final["data"]["result"] = {"content": self.fixture.worker_token}
        transport = _encode_events(events)

        with self.assertRaises(runtime.RuntimeFailure) as raised:
            self._execute_through_runtime(transport=transport)
        self.assertEqual("denied", raised.exception.status)
        self.assertEqual("worker-authentication-disclosed", raised.exception.code)

    def test_transport_sink_detects_credentials_split_across_chunk_boundaries(self):
        token = self.fixture.worker_token
        payload = b"prefix " + token.encode("ascii") + b" suffix\n"
        for width in (1, 3, 7, 11):
            with self.subTest(width=width):
                sink = provider._TransportSink(token)
                for start in range(0, len(payload), width):
                    sink(payload[start : start + width])
                sink.finish()
                self.assertTrue(sink.disclosed)
                self.assertEqual(b"", sink.transport())
                self.assertIsNone(sink.candidate)

    def test_transport_sink_detects_base64_encoded_credentials(self):
        token = self.fixture.worker_token
        payload = base64.b64encode(token.encode("ascii"))
        sink = provider._TransportSink(token)
        sink(payload)
        self.assertTrue(sink.disclosed)
        self.assertEqual(b"", sink.transport())

    def test_incremental_decoding_matches_whole_buffer_decoding(self):
        cases = {
            "valid": (_jsonl(), None),
            "truncated": (_jsonl()[:-1], "local-agent-jsonl-truncated"),
            "malformed": (_jsonl() + b'{"type":\n', "local-agent-jsonl-invalid"),
            "blank-line": (_jsonl().replace(b"\n", b"\n\n", 1), "local-agent-jsonl-invalid"),
            "missing-idle": (
                _encode_events(
                    [
                        event
                        for event in _jsonl_events(_jsonl())
                        if event["type"] != "assistant.idle"
                    ]
                ),
                "local-agent-jsonl-sequence",
            ),
        }
        for name, (raw, code) in cases.items():
            for width in (1, 7, 4096, len(raw)):
                with self.subTest(name=name, width=width):
                    decoder = provider._JsonlCandidateDecoder()

                    def run():
                        for start in range(0, len(raw), width):
                            decoder.feed(raw[start : start + width])
                        return decoder.finish()

                    if code is None:
                        self.assertEqual(CANDIDATE.encode("utf-8"), run())
                    else:
                        with self.assertRaises(
                            provider.LocalProviderFailure
                        ) as raised:
                            run()
                        self.assertEqual(code, raised.exception.code)

    def test_write_phase_requires_a_clean_commit_and_rejects_oversized_prompt(self):
        instance = self.fixture.instance()
        request = self.fixture.request()
        request["operation"] = {"kind": "agent", "name": "write-tests", "role": "planner"}
        request["request_sha256"] = inspector.sha256(inspector.canonical_bytes({key: value for key, value in request.items() if key != "request_sha256"}))
        with mock.patch.object(provider, "_input_projection", return_value=[]):
            result = instance.execute(
                request,
                {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                ["agent"],
                str(self.fixture.workspace),
                self.fixture.environment(),
                request["limits"],
                None,
            )
        self.assertIn("Commit all intended changes", result["command"][-1])
        instance = self.fixture.instance()
        with mock.patch.object(
            provider,
            "_input_projection",
            return_value=[{"bytes_base64": "a" * provider.PROMPT_LIMIT_BYTES}],
        ):
            with self.assertRaises(provider.LocalProviderFailure) as raised:
                instance.execute(
                    request,
                    {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1},
                    ["agent"],
                    str(self.fixture.workspace),
                    self.fixture.environment(),
                    request["limits"],
                    None,
                )
        self.assertEqual("agent-input-projection-too-large", raised.exception.code)

    def test_runtime_accepts_only_the_exact_local_execution_attestation(self):
        instance = self.fixture.instance()
        request = self.fixture.request()
        binding = {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1}
        read_only_suffix = " This is read-only: do not change or commit the candidate worktree."
        self.assertEqual(
            provider.PROMPT_LIMIT_BYTES,
            runtime.LOCAL_PROVIDER_PROMPT_LIMIT_BYTES,
        )
        with mock.patch.object(
            provider,
            "_agent_prompt",
            return_value="p" * (22_090 - len(read_only_suffix.encode("utf-8"))),
        ):
            executed = instance.execute(
                request,
                binding,
                ["agent"],
                str(self.fixture.workspace),
                self.fixture.environment(),
                request["limits"],
                None,
            )
        attachments = executed["evidence_attachments"]
        transport = attachments[0]["bytes"]
        candidate = base64.b64decode(
            executed["candidate_output"]["base64"], validate=True
        )
        facts = executed["provider_result"]
        self.assertEqual(22_090, len(executed["command"][-1].encode("utf-8")))
        self.assertEqual(
            inspector.sha256(inspector.canonical_bytes(executed["command"])),
            executed["process_result"]["command_sha256"],
        )
        self.assertEqual(
            executed["process_result"]["command_sha256"],
            facts["command"]["argv_sha256"],
        )
        self.assertEqual(inspector.sha256(transport), facts["transport_sha256"])
        self.assertEqual(len(transport), facts["transport_size"])
        self.assertEqual(
            {
                "path": provider.TRANSPORT_ATTACHMENT_PATH,
                "sha256": inspector.sha256(transport),
                "size": len(transport),
            },
            facts["transport_reference"],
        )
        self.assertEqual(0, executed["process_result"]["stdout"]["bytes"])
        self.assertEqual(
            len(transport),
            executed["process_result"]["stdout"]["observed_bytes"],
        )
        self.assertNotIn("transport_output", facts)
        self.assertNotEqual(facts["transport_sha256"], facts["candidate_sha256"])
        self.assertEqual(
            executed["command"],
            runtime._local_provider_command(
                executed["command"], "local-provider-command"
            ),
        )
        runtime._process_document(
            executed["process_result"],
            executed["command"],
            request["limits"],
            "execution",
        )
        adapter = object.__new__(runtime.Runtime)
        adapter.root = self.fixture.workspace
        adapter._validate_sandbox(
            executed["provider_result"],
            request,
            executed["process_result"],
            {"sha256": inspector.sha256(candidate), "size": len(candidate)},
            self.fixture.row(),
            instance,
            attachments,
        )
        replaced = json.loads(json.dumps(facts))
        replaced["command"]["executable"]["sha256"] = "0" * 64
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter._validate_sandbox(
                replaced,
                request,
                executed["process_result"],
                {"sha256": inspector.sha256(candidate), "size": len(candidate)},
                self.fixture.row(),
                instance,
                attachments,
            )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

        replaced = json.loads(json.dumps(facts))
        replaced["transport_reference"]["sha256"] = "0" * 64
        replaced["result_sha256"] = inspector.sha256(
            inspector.canonical_bytes(
                {key: value for key, value in replaced.items() if key != "result_sha256"}
            )
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter._validate_sandbox(
                replaced,
                request,
                executed["process_result"],
                {"sha256": inspector.sha256(candidate), "size": len(candidate)},
                self.fixture.row(),
                instance,
                attachments,
            )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

        replaced = json.loads(json.dumps(facts))
        replaced["command"]["argv"][-1] += "x"
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter._validate_sandbox(
                replaced,
                request,
                executed["process_result"],
                {"sha256": inspector.sha256(candidate), "size": len(candidate)},
                self.fixture.row(),
                instance,
                attachments,
            )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

        replaced = json.loads(json.dumps(facts))
        replaced["environment"]["secret_keys"] = ["GH_TOKEN"]
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter._validate_sandbox(
                replaced,
                request,
                executed["process_result"],
                {"sha256": inspector.sha256(candidate), "size": len(candidate)},
                self.fixture.row(),
                instance,
                attachments,
            )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

        replaced_process = json.loads(json.dumps(executed["process_result"]))
        replaced_process["command_sha256"] = "0" * 64
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            runtime._process_document(
                replaced_process,
                executed["command"],
                request["limits"],
                "execution",
            )
        self.assertEqual("invalid-process-result", raised.exception.code)

        for field, value in (
            ("format", "chess-echo-process-result-v1"),
            ("stdout_bytes", request["limits"]["output_limit_bytes"] - 1),
            ("stderr_bytes", request["limits"]["stderr_limit_bytes"] + 1),
        ):
            replaced_process = copy.deepcopy(executed["process_result"])
            if field == "format":
                replaced_process[field] = value
            else:
                replaced_process["limits"][field] = value
            with self.subTest(field=field), self.assertRaises(
                runtime.RuntimeFailure
            ) as raised:
                runtime._process_document(
                    replaced_process,
                    executed["command"],
                    request["limits"],
                    "execution",
                )
            self.assertEqual("invalid-process-result", raised.exception.code)

        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter._validate_sandbox(
                executed["provider_result"],
                request,
                executed["process_result"],
                {
                    "sha256": inspector.sha256(candidate),
                    "size": provider.CANDIDATE_MAX_BYTES + 1,
                },
                self.fixture.row(),
                instance,
                attachments,
            )
        self.assertEqual("local-provider-candidate-too-large", raised.exception.code)

    def test_runtime_execute_adapts_candidate_and_binds_the_raw_transport_sidecar(self):
        bundle = self._execute_through_runtime(bundle=True)
        result = bundle.document
        candidate = CANDIDATE.encode("utf-8")
        transport = _jsonl()
        persisted_candidate = base64.b64decode(
            result["process_result"]["stdout"]["base64"],
            validate=True,
        )
        reconstructed_process = copy.deepcopy(result["process_result"])
        reconstructed_process["stdout"] = {
            "bytes": 0,
            "base64": "",
            "observed_bytes": len(transport),
            "observed_sha256": inspector.sha256(transport),
        }

        self.assertEqual(candidate, persisted_candidate)
        self.assertEqual(
            {"sha256": inspector.sha256(candidate), "size": len(candidate)},
            result["candidate_output"],
        )
        self.assertEqual(
            inspector.sha256(transport),
            result["sandbox"]["transport_sha256"],
        )
        self.assertEqual(len(transport), result["sandbox"]["transport_size"])
        self.assertEqual(
            result["sandbox"]["process_result_sha256"],
            inspector.sha256(inspector.canonical_bytes(reconstructed_process)),
        )
        self.assertEqual(
            [
                {
                    "path": provider.TRANSPORT_ATTACHMENT_PATH,
                    "sha256": inspector.sha256(transport),
                    "size": len(transport),
                    "bytes": transport,
                }
            ],
            list(bundle.attachments),
        )
        self.assertEqual(
            json.loads(CANDIDATE),
            resume.decode_candidate(result, "plan"),
        )

    def test_runtime_preserves_raw_transport_candidate_and_stderr_blobs(self):
        transport = _jsonl()
        stderr = b"synthetic bounded stderr"

        def captured(command, **options):
            return _process_result(command, stdout=transport, stderr=stderr, stdout_sink=options.get("stdout_sink"))

        bundle = self._execute_through_runtime(supervise=captured, bundle=True)
        result = bundle.document

        self.assertEqual(transport, bundle.attachments[0]["bytes"])
        self.assertEqual(
            provider.TRANSPORT_ATTACHMENT_PATH, bundle.attachments[0]["path"]
        )
        self.assertEqual(
            inspector.sha256(transport), result["sandbox"]["transport_sha256"]
        )
        self.assertEqual(
            CANDIDATE.encode("utf-8"),
            base64.b64decode(
                result["process_result"]["stdout"]["base64"],
                validate=True,
            ),
        )
        self.assertEqual(
            stderr,
            base64.b64decode(
                result["process_result"]["stderr"]["base64"],
                validate=True,
            ),
        )

    def test_runtime_execute_leaves_invalid_candidate_for_strict_decoder_rejection(self):
        result = self._execute_through_runtime(candidate="not JSON")

        self.assertEqual(
            b"not JSON",
            base64.b64decode(
                result["process_result"]["stdout"]["base64"],
                validate=True,
            ),
        )
        with self.assertRaises(resume.ResumeFailure) as raised:
            resume.decode_candidate(result, "plan")
        self.assertEqual("candidate-output-invalid", raised.exception.code)

    def test_trusted_local_maximum_output_fits_persisted_result_budget(self):
        config = json.loads(
            (REPOSITORY / ".github" / "agent-workflow.json").read_text()
        )["orchestrator"]
        limits = {
            key: config["agent_roles"][0][key]
            for key in (
                "timeout_ms",
                "grace_ms",
                "output_limit_bytes",
                "stderr_limit_bytes",
            )
        }
        self.assertEqual(832 * 1024, limits["output_limit_bytes"])
        self.assertEqual(448 * 1024, provider.CANDIDATE_MAX_BYTES)
        self.assertEqual(64 * 1024, limits["stderr_limit_bytes"])
        self.assertEqual(
            limits["output_limit_bytes"], provider.JSONL_MAX_EVENT_BYTES
        )
        self.assertEqual(8 * 1024 * 1024, provider.TRANSPORT_MAX_BYTES)
        self.assertEqual(
            provider.TRANSPORT_MAX_BYTES, runtime.ATTACHMENT_LIMIT_BYTES
        )
        self.assertEqual(
            runtime.LOCAL_PROVIDER_CANDIDATE_LIMIT_BYTES,
            provider.CANDIDATE_MAX_BYTES,
        )
        self.assertTrue(
            all(
                row["output_limit_bytes"] == limits["output_limit_bytes"]
                and row["stderr_limit_bytes"] == limits["stderr_limit_bytes"]
                for row in config["agent_roles"]
            )
        )

        family = "a" * 32

        def repository_observation(target_size):
            workspace = {
                "staged": [],
                "unstaged": [],
                "untracked_non_ignored": [],
                "assume_unchanged": [],
                "skip_worktree": [],
            }
            workspace["status_sha256"] = inspector.sha256(
                inspector.canonical_bytes(workspace)
            )
            change = {
                "status": "A",
                "old_mode": "000000",
                "new_mode": "100644",
                "old_oid": "0" * 40,
                "new_oid": "1" * 40,
                "old_path": None,
                "new_path": "",
            }
            document = {
                "format": "chess-echo-work-type-diff-observation-v1",
                "repository": "NathanZK/ChessEcho",
                "issue": 183,
                "family_run_id": family,
                "triage_binding": {
                    "kind": "evidence-binding",
                    "sha256": "a" * 64,
                    "size": 1,
                },
                "observer": {
                    "name": "workflow-runtime",
                    "version": runtime.RUNTIME_VERSION,
                    "source_sha256": inspector.sha256(
                        pathlib.Path(runtime.__file__).read_bytes()
                    ),
                },
                "observed_at": "2026-01-01T00:00:00Z",
                "object_format": "sha1",
                "base": {
                    "ref": "refs/remotes/origin/main",
                    "commit": self.fixture.head,
                    "tree": self.fixture.head,
                },
                "head": {
                    "commit": self.fixture.head,
                    "tree": self.fixture.head,
                },
                "ancestry": {"base_is_ancestor": True, "commit_count": 1},
                "changes": [change],
                "workspace": workspace,
                "git_trust": {
                    "no_replace_objects": True,
                    "replacement_refs": [],
                    "git_replace_ref_base": None,
                    "git_graft_file": None,
                    "info_grafts_present": False,
                    "environment_redirections": [],
                    "alternate_object_directories": [],
                },
                "head_config": {
                    "path": ".github/agent-workflow.json",
                    "blob_oid": "2" * 40,
                    "content_sha256": "3" * 64,
                    "size": 1,
                },
                "raw_diff_sha256": "4" * 64,
            }
            document["observation_sha256"] = "5" * 64
            base_size = len(inspector.canonical_bytes(document))
            self.assertGreaterEqual(target_size, base_size)
            change["new_path"] = "x" * (target_size - base_size)
            unsigned = dict(document)
            unsigned.pop("observation_sha256")
            document["observation_sha256"] = inspector.sha256(
                inspector.canonical_bytes(unsigned)
            )
            self.assertEqual(target_size, len(inspector.canonical_bytes(document)))
            return runtime._repository_document(document, 183, family)

        stdout_charge = len(
            base64.b64encode(b"x" * limits["output_limit_bytes"])
        )
        candidate_charge = len(
            base64.b64encode(b"x" * provider.CANDIDATE_MAX_BYTES)
        )
        stderr_charge = len(
            base64.b64encode(b"x" * limits["stderr_limit_bytes"])
        )
        self.assertEqual(1_135_960, stdout_charge)
        self.assertEqual(611_672, candidate_charge)
        self.assertEqual(87_384, stderr_charge)
        self.assertEqual(
            1_835_016,
            stdout_charge + candidate_charge + stderr_charge,
        )
        self.assertEqual(3 * 611_672, 1_835_016)
        prompt_charge = 2 * provider.PROMPT_LIMIT_BYTES + 2
        self.assertEqual(131_074, prompt_charge)
        maximum_repository_size = (
            runtime.MAX_DOCUMENT_BYTES
            - stdout_charge
            - candidate_charge
            - stderr_charge
            - prompt_charge
            - runtime.EXECUTION_RESULT_HEADROOM_BYTES
        )
        self.assertEqual(65_526, maximum_repository_size)
        maximum_repository = repository_observation(maximum_repository_size)

        result = self._execute_through_runtime(
            prompt_bytes=provider.PROMPT_LIMIT_BYTES
        )
        self.assertEqual(
            provider.PROMPT_LIMIT_BYTES,
            len(result["sandbox"]["command"]["argv"][-1].encode("utf-8")),
        )
        serialized_prompt_size = len(
            inspector.canonical_bytes(result["sandbox"]["command"]["argv"][-1])
        )
        self.assertIn('"\\\n', result["sandbox"]["command"]["argv"][-1])
        self.assertGreaterEqual(
            serialized_prompt_size,
            2 * provider.PROMPT_LIMIT_BYTES - 128,
        )
        self.assertLessEqual(
            serialized_prompt_size,
            2 * provider.PROMPT_LIMIT_BYTES + 2,
        )
        stdout_encoded = base64.b64encode(
            b"x" * limits["output_limit_bytes"]
        ).decode("ascii")
        candidate_encoded = base64.b64encode(
            b"x" * provider.CANDIDATE_MAX_BYTES
        ).decode("ascii")
        stderr_encoded = base64.b64encode(
            b"x" * limits["stderr_limit_bytes"]
        ).decode("ascii")
        maximum = copy.deepcopy(result)
        maximum["repository_after"] = maximum_repository
        maximum["process_result"]["stdout"] = {
            "bytes": provider.CANDIDATE_MAX_BYTES,
            "base64": candidate_encoded,
        }
        maximum["process_result"]["stderr"] = {
            "bytes": limits["stderr_limit_bytes"],
            "base64": stderr_encoded,
        }
        maximum["sandbox"]["transport_output"] = {
            "bytes": limits["output_limit_bytes"],
            "base64": stdout_encoded,
        }
        maximum["sandbox"]["transport_size"] = limits["output_limit_bytes"]
        maximum["sandbox"]["candidate_size"] = provider.CANDIDATE_MAX_BYTES
        maximum["candidate_output"]["size"] = provider.CANDIDATE_MAX_BYTES
        maximum.pop("result_sha256")
        maximum = runtime._with_digest(maximum, "result_sha256")
        self.assertLessEqual(
            len(inspector.canonical_bytes(maximum)),
            runtime.MAX_DOCUMENT_BYTES,
        )

        adapter = object.__new__(runtime.Runtime)
        adapter.repository = "NathanZK/ChessEcho"
        adapter._config = {
            "frozen_issues": [],
            "agent_roles": [
                {
                    "role": "planner",
                    "containment": "trusted-local-worktree-v1",
                }
            ],
        }
        repository_before = maximum_repository
        operation = {"kind": "agent", "name": "write-plan", "role": "planner"}
        source = {"entry": "planner", "profile": None}
        inputs = []
        authority = {
            "kind": "evidence-binding",
            "sha256": "c" * 64,
            "size": 1,
        }

        def build_with_limits(selected_limits):
            attempt_id = runtime.execution_attempt_id(
                authority,
                operation,
                source,
                inputs,
                repository_before,
                selected_limits,
                None,
            )
            with mock.patch.object(
                runtime.Runtime, "_base_config", return_value={"mode": "active"}
            ), mock.patch.object(
                runtime.Runtime, "_validate_command_source", return_value=source
            ), mock.patch.object(
                runtime.Runtime, "_validate_inputs", return_value=inputs
            ), mock.patch.object(
                runtime.Runtime, "_expected_limits", return_value=selected_limits
            ):
                return adapter.build_request(
                    issue=183,
                    family_run_id="a" * 32,
                    attempt_id=attempt_id,
                    authority_binding=authority,
                    operation=operation,
                    command_source=source,
                    input_bindings=inputs,
                    repository_before=repository_before,
                    limits=selected_limits,
                )

        self.assertEqual(limits, build_with_limits(limits)["limits"])
        oversized_repository = repository_observation(
            maximum_repository_size + 1
        )
        repository_before = oversized_repository
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            build_with_limits(limits)
        self.assertEqual("execution-result-budget", raised.exception.code)
        repository_before = maximum_repository
        for field in ("output_limit_bytes", "stderr_limit_bytes"):
            oversized_limits = {
                **limits,
                field: limits[field] + 3,
            }
            with self.subTest(field=field), self.assertRaises(
                runtime.RuntimeFailure
            ) as raised:
                build_with_limits(oversized_limits)
            self.assertEqual("execution-result-budget", raised.exception.code)

        oversized_repository_after = repository_observation(
            runtime.MAX_DOCUMENT_BYTES - 1024
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            self._execute_through_runtime(
                repository_after=oversized_repository_after
            )
        self.assertEqual("document-too-large", raised.exception.code)

    def test_workspace_branch_and_symlink_redirection_fail_closed(self):
        self.fixture._git(self.fixture.workspace, "branch", "-m", "wrong")
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            self.fixture.instance()
        self.assertEqual("worktree-branch-mismatch", raised.exception.code)

        link = self.fixture.parent / "workspace-link"
        link.symlink_to(self.fixture.workspace, target_is_directory=True)
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            provider.LocalSandboxProvider(
                root=link,
                control_root=self.fixture.control,
                issue=175,
                role="planner",
                row=self.fixture.row(),
                git_executable=self.fixture.git,
                agent_executable=self.fixture.agent,
                agent_home=self.fixture.home,
            )
        self.assertEqual("candidate-workspace-redirection", raised.exception.code)

    def test_pending_result_store_preserves_exact_query_identity(self):
        store = provider.PendingResultStore(self.fixture.parent / "results", self.fixture.workspace)
        query = {
            "format": "chess-echo-pending-result-query-v1",
            "issue": 175,
            "family_run_id": "a" * 32,
            "authority_binding": {"kind": "evidence-binding", "sha256": "b" * 64, "size": 1},
            "request_binding": {"kind": "evidence-binding", "sha256": "c" * 64, "size": 1},
            "attempt_id": "d" * 64,
            "result_kind": "execution-result",
            "query_sha256": "e" * 64,
        }
        binding_data = b"x"
        binding = {"kind": "evidence-binding", "sha256": inspector.sha256(binding_data), "size": 1}
        handoff = {
            "format": provider.HANDOFF_FORMAT,
            "authority_binding": query["authority_binding"],
            "request_binding": query["request_binding"],
            "result_binding": binding,
            "repository_after_binding": None,
            "pr_observation_binding": None,
        }
        self.assertEqual([], store(None, None, query)["candidates"])
        store.prepare(query, binding, binding_data)
        self.assertEqual(
            [{"kind": "execution-result", "binding": binding}],
            store(None, None, query)["candidates"],
        )
        self.assertEqual(
            binding_data,
            inspector.object_path(inspector.resolve_store(self.fixture.workspace), binding["sha256"]).read_bytes(),
        )
        store.record(query, handoff)
        self.assertEqual(
            [{"kind": "execution-result", "binding": binding}],
            store(None, None, query)["candidates"],
        )
        changed = dict(query)
        changed["authority_binding"] = {
            "kind": "evidence-binding",
            "sha256": "1" * 64,
            "size": 1,
        }
        with self.assertRaises(provider.LocalProviderFailure) as raised:
            store(None, None, changed)
        self.assertEqual("pending-result-record-mismatch", raised.exception.code)

    def test_host_rejects_reused_workspace_on_wrong_branch(self):
        self.fixture._git(self.fixture.workspace, "checkout", "-qb", "wrong")
        with self.assertRaises(host.LocalHostFailure) as raised:
            host._validate_workspace(
                self.fixture.control,
                self.fixture.workspace,
                175,
                {"path": str(self.fixture.git)},
            )
        self.assertEqual("candidate-workspace-branch-mismatch", raised.exception.code)


class TrustedLocalHostProcessTest(unittest.TestCase):
    def test_host_exposes_only_explicit_trusted_worker_mode(self):
        actions = {
            action.dest: action
            for action in host.build_parser()._actions
        }
        self.assertIn("trusted_worker_auth_stdin", actions)
        self.assertFalse(actions["trusted_worker_auth_stdin"].default)
        config = json.loads(
            (REPOSITORY / ".github" / "agent-workflow.json").read_text()
        )
        self.assertEqual(
            provider.TRUSTED_WORKER_AUTHENTICATION,
            config["orchestrator"]["local_host"]["worker_authentication"],
        )

        source = pathlib.Path(provider.__file__).read_text()
        self.assertNotIn("os.environ", source)
        self.assertNotIn("~/.copilot", source)
        self.assertNotIn(".config/gh", source)
        self.assertNotIn("gh auth", source)
        self.assertIn(
            "trusted_worker_authentication=worker_token_reader is not None",
            pathlib.Path(host.__file__).read_text(),
        )

    def test_isolated_fresh_process_rejects_candidate_import_shadowing(self):
        with tempfile.TemporaryDirectory(dir=str(REPOSITORY)) as temporary:
            root = pathlib.Path(temporary)
            control, malicious, bin_dir, home = (
                root / "control",
                root / "malicious",
                root / "bin",
                root / "home",
            )
            for path in (control / "scripts", control / ".github", malicious, bin_dir, home):
                path.mkdir(parents=True, exist_ok=True)
            for relative in host.CONTROL_SOURCES:
                source = REPOSITORY / relative
                target = control / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            shutil.copy2(
                REPOSITORY / "scripts" / "workflow_local_host.py",
                control / "scripts" / "workflow_local_host.py",
            )
            agent = bin_dir / "agent"
            agent.write_text("#!/bin/sh\nexit 0\n")
            agent.chmod(agent.stat().st_mode | stat.S_IXUSR)
            python = pathlib.Path(sys.executable).resolve(strict=True)
            host_source = control / "scripts" / "workflow_local_host.py"
            provider_source = control / "scripts" / "workflow_local_provider.py"
            config = json.loads((REPOSITORY / ".github" / "agent-workflow.json").read_text())
            local = config["orchestrator"]["local_host"]
            local.update(
                {
                    "source_sha256": _sha(host_source),
                    "provider": {
                        "name": provider.NAME,
                        "version": provider.VERSION,
                        "source": "scripts/workflow_local_provider.py",
                        "source_sha256": _sha(provider_source),
                    },
                    "python": {
                        "path": str(python),
                        "sha256": _sha(python),
                    },
                    "agent": {"name": "agent", "sha256": _sha(agent)},
                }
            )
            for row in config["orchestrator"]["agent_roles"]:
                row["command_prefix"] = ["agent"]
                row["provider_version"] = provider.VERSION
                row["provider_source_sha256"] = _sha(provider_source)
                row["agent_executable_sha256"] = _sha(agent)
            (control / ".github" / "agent-workflow.json").write_text(
                json.dumps(config)
            )
            gh = bin_dir / "gh"
            gh.write_text("#!/bin/sh\nexit 1\n")
            gh.chmod(gh.stat().st_mode | stat.S_IXUSR)
            git = pathlib.Path(shutil.which("git"))
            subprocess.run([git, "init", "-q"], cwd=control, check=True)
            subprocess.run(
                [git, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                 "add", "-A"],
                cwd=control,
                check=True,
            )
            subprocess.run(
                [git, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test",
                 "commit", "-qm", "control"],
                cwd=control,
                check=True,
            )
            head = subprocess.run(
                [git, "rev-parse", "HEAD"],
                cwd=control,
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            ).stdout.strip()
            subprocess.run(
                [git, "update-ref", "refs/remotes/origin/main", head],
                cwd=control,
                check=True,
            )
            gh.write_text(
                "#!/bin/sh\ncase \"$2\" in\n"
                "  */commits/main) printf '%s' '{\"sha\":\"%s\"}' ;;\n"
                "  *) printf '%s' '{\"default_branch\":\"main\",\"full_name\":\"NathanZK/ChessEcho\"}' ;;\n"
                "esac\n" % ("%s", head, "%s")
            )
            gh.chmod(gh.stat().st_mode | stat.S_IXUSR)
            subprocess.run(
                [git, "remote", "add", "origin", "https://github.com/NathanZK/ChessEcho.git"],
                cwd=control,
                check=True,
            )
            marker = root / "candidate-imported"
            (malicious / "workflow_orchestrator.py").write_text(
                "import pathlib\npathlib.Path(%r).write_text('bad')\n"
                % str(marker)
            )
            workspace_parent = root / "workspaces"
            command = [
                str(python),
                "-I",
                str(host_source),
                "--control-root",
                str(control),
                "--repository",
                "NathanZK/ChessEcho",
                "--git-executable",
                str(git),
                "--gh-executable",
                str(bin_dir / "gh"),
                "--agent-executable",
                str(agent),
                "--agent-home",
                str(home),
                "--result-store",
                str(root / "results"),
                "--github-token-stdin",
                "prepare-workspace",
                "176",
                "--workspace-parent",
                str(workspace_parent),
            ]
            result = subprocess.run(
                command,
                cwd=malicious,
                env={"PATH": os.environ.get("PATH", ""), "PYTHONPATH": str(malicious)},
                input="token\n",
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual(0, result.returncode, (result.stdout, result.stderr))
            self.assertFalse(marker.exists())
            document = json.loads(result.stdout)
            self.assertEqual("chess-echo-trusted-local-workspace-v1", document["format"])
            self.assertEqual("chess-echo-agent/issue-176", document["branch"])
            self.assertEqual(local["python"], document["control_plane"]["python"])
            self.assertEqual(str(python), document["control_plane"]["python"]["path"])
            self.assertEqual(
                "",
                subprocess.run(
                    [git, "status", "--porcelain=v1", "--untracked-files=all"],
                    cwd=control,
                    check=True,
                    text=True,
                    stdout=subprocess.PIPE,
                ).stdout,
            )
            direct_gh = subprocess.run(
                [gh, "api", "repos/NathanZK/ChessEcho/commits/main"],
                cwd=document["workspace"],
                env={"PATH": os.pathsep.join((str(bin_dir), "/usr/bin", "/bin"))},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual({"sha": head}, json.loads(direct_gh.stdout), direct_gh.stderr)
            bootstrap = subprocess.run(
                command[:-4]
                + [
                    "--trusted-worker-auth-stdin",
                    "bootstrap",
                    "176",
                    "--workspace",
                    document["workspace"],
                ],
                cwd=malicious,
                env={"PATH": os.environ.get("PATH", ""), "PYTHONPATH": str(malicious)},
                input="token\n",
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual(
                0,
                bootstrap.returncode,
                (bootstrap.stdout, bootstrap.stderr),
            )
            self.assertEqual(
                "chess-echo-trusted-local-bootstrap-v1",
                json.loads(bootstrap.stdout)["format"],
            )
            self.assertFalse(marker.exists())

            replaced = json.loads((control / ".github" / "agent-workflow.json").read_text())
            replaced["orchestrator"]["local_host"]["python"] = {
                "path": str(agent.resolve(strict=True)),
                "sha256": _sha(agent),
            }
            (control / ".github" / "agent-workflow.json").write_text(json.dumps(replaced))
            subprocess.run([git, "add", ".github/agent-workflow.json"], cwd=control, check=True)
            subprocess.run(
                [
                    git,
                    "-c",
                    "user.name=Fixture",
                    "-c",
                    "user.email=fixture@example.test",
                    "commit",
                    "-qm",
                    "replace python identity",
                ],
                cwd=control,
                check=True,
            )
            replaced_head = subprocess.run(
                [git, "rev-parse", "HEAD"],
                cwd=control,
                check=True,
                text=True,
                stdout=subprocess.PIPE,
            ).stdout.strip()
            subprocess.run(
                [git, "update-ref", "refs/remotes/origin/main", replaced_head],
                cwd=control,
                check=True,
            )
            mismatch = subprocess.run(
                command,
                cwd=malicious,
                env={"PATH": os.environ.get("PATH", ""), "PYTHONPATH": str(malicious)},
                input="token\n",
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual(2, mismatch.returncode, (mismatch.stdout, mismatch.stderr))
            self.assertEqual(
                "python-executable-mismatch",
                json.loads(mismatch.stdout)["outcome"]["code"],
            )


if __name__ == "__main__":
    unittest.main()
