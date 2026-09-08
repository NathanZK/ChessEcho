#!/usr/bin/env python3
"""Trusted-local execution provider for the replacement workflow.

This provider separates reviewed controller code from a dedicated candidate
worktree and records deterministic execution facts. It does not provide or
claim hostile-process, filesystem, credential, or network isolation.
"""

import base64
import binascii
import copy
import hashlib
import json
import os
import pathlib
import stat

try:
    from . import workflow_cas
    from . import workflow_evidence as evidence
    from . import workflow_inspector as inspector
    from . import workflow_supervisor as supervisor
except ImportError:  # pragma: no cover - direct script loading
    import workflow_cas
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_supervisor as supervisor


NAME = "chess-echo-trusted-local"
VERSION = "1.4.0"
RESULT_FORMAT = "chess-echo-trusted-local-execution-result-v1"
PROCESS_DIAGNOSTIC_FORMAT = "chess-echo-trusted-local-process-diagnostic-v1"
DISCOVERY_FORMAT = "chess-echo-pending-result-candidates-v1"
HANDOFF_FORMAT = "chess-echo-execution-handoff-v1"
WORKTREE_BRANCH_PREFIX = "chess-echo-agent/issue-"
WORKER_TOKEN_ENV = "COPILOT_GITHUB_TOKEN"
WORKER_TOKEN_MAX_BYTES = 16 * 1024
TRUSTED_WORKER_AUTHENTICATION = {
    "mode": "trusted-local-stdin-v1",
    "trust": "trusted-local-development-v1",
    "secret_environment_key": WORKER_TOKEN_ENV,
}
AUDIT_LIMITS = {
    "timeout_ms": 30_000,
    "grace_ms": 1_000,
    "output_limit_bytes": 1024 * 1024,
}
PROMPT_LIMIT_BYTES = 64 * 1024
JSONL_MAX_BYTES = 832 * 1024
JSONL_MAX_EVENTS = 8192
JSONL_MAX_EVENT_BYTES = JSONL_MAX_BYTES
CANDIDATE_MAX_BYTES = 448 * 1024
JSONL_STARTUP_TYPES = (
    "session.mcp_servers_loaded",
    "session.skills_loaded",
    "session.tools_updated",
)
JSONL_EVENT_TYPES = frozenset(
    JSONL_STARTUP_TYPES
    + (
        "user.message",
        "assistant.turn_start",
        "model.call_start",
        "assistant.reasoning_delta",
        "assistant.tool_call_delta",
        "assistant.message",
        "assistant.reasoning",
        "tool.execution_start",
        "session.background_tasks_changed",
        "tool.execution_partial_result",
        "tool.execution_complete",
        "assistant.turn_end",
        "assistant.message_start",
        "assistant.message_delta",
        "session.usage_checkpoint",
        "assistant.idle",
    )
)
JSONL_EPHEMERAL_TYPES = frozenset(
    JSONL_STARTUP_TYPES
    + (
        "model.call_start",
        "assistant.reasoning_delta",
        "assistant.tool_call_delta",
        "assistant.reasoning",
        "session.background_tasks_changed",
        "tool.execution_partial_result",
        "assistant.message_start",
        "assistant.message_delta",
        "assistant.idle",
    )
)


class LocalProviderFailure(ValueError):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _fail(status, code, message):
    raise LocalProviderFailure(status, code, message)


def _canonical(value):
    return inspector.canonical_bytes(value)


def _sha(data):
    return inspector.sha256(data)


def _file_identity(path, label):
    try:
        source = pathlib.Path(path)
        if not source.is_absolute() or source.is_symlink():
            _fail("denied", "%s-path" % label, "%s must be an absolute regular path" % label)
        resolved = source.resolve(strict=True)
        before = resolved.stat()
        data = resolved.read_bytes()
        after = resolved.stat()
    except LocalProviderFailure:
        raise
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-unavailable" % label, "%s is unavailable: %s" % (label, error))
    if (
        not stat.S_ISREG(before.st_mode)
        or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    ):
        _fail("stale", "%s-replaced" % label, "%s changed during identity capture" % label)
    return {"path": str(resolved), "sha256": _sha(data)}


def source_identity():
    return _file_identity(pathlib.Path(__file__).absolute(), "local-provider-source")


def _process_output(result, label):
    if (
        not isinstance(result, dict)
        or result.get("format") != supervisor.RESULT_FORMAT
        or result.get("outcome") != "success"
        or result.get("exit_code") != 0
        or result.get("cleanup_verified") is not True
    ):
        _fail("missing", "%s-failed" % label, "%s did not complete successfully" % label)
    record = result.get("stdout")
    try:
        data = base64.b64decode(record["base64"], validate=True)
    except (KeyError, TypeError, ValueError, binascii.Error):
        _fail("corrupt", "%s-output" % label, "%s output is malformed" % label)
    if set(record) != {"bytes", "base64"} or record["bytes"] != len(data):
        _fail("corrupt", "%s-output" % label, "%s output identity is malformed" % label)
    return data


def _scrub_process_result(result):
    if not isinstance(result, dict):
        return
    for stream in ("stdout", "stderr"):
        record = result.get(stream)
        if isinstance(record, dict):
            record.clear()
    result.clear()


def _validate_worker_token(value):
    if not isinstance(value, str):
        _fail(
            "corrupt",
            "worker-authentication-malformed",
            "Worker authentication credential is malformed",
        )
    try:
        data = value.encode("ascii")
    except UnicodeError:
        _fail(
            "corrupt",
            "worker-authentication-malformed",
            "Worker authentication credential is malformed",
        )
    if not data or len(data) > WORKER_TOKEN_MAX_BYTES or any(
        byte < 33 or byte > 126 for byte in data
    ):
        _fail(
            "corrupt",
            "worker-authentication-malformed",
            "Worker authentication credential is malformed",
        )
    return value


def _process_discloses_secret(process, secret):
    for stream in ("stdout", "stderr"):
        record = process.get(stream) if isinstance(process, dict) else None
        if not isinstance(record, dict):
            continue
        try:
            data = base64.b64decode(record.get("base64", ""), validate=True)
        except (TypeError, ValueError, binascii.Error):
            continue
        if _bytes_disclose_secret(data, secret):
            return True
    return secret in json.dumps(process, ensure_ascii=True, sort_keys=True)


def _bytes_disclose_secret(data, secret):
    encoded = secret.encode("ascii")
    return encoded in data or base64.b64encode(encoded) in data


def _duplicate_rejector(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            _fail("ambiguous", "local-agent-jsonl-invalid", "Copilot JSONL repeats a JSON key")
        value[key] = item
    return value


def _json_constant(value):
    _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL contains a non-JSON number: %s" % value)


def _event_text(value, label):
    if not isinstance(value, str) or not value:
        _fail("corrupt", "local-agent-jsonl-invalid", "%s must be a nonempty string" % label)
    return value


def _event_data(event):
    data = event.get("data")
    if not isinstance(data, dict):
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot event data must be an object")
    return data


def _event_data_keys(data, keys, label):
    if set(data) != set(keys):
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot %s payload has an invalid schema" % label)


def _event_field_matches(data, field, expected, required=False):
    if required and field not in data:
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot %s is missing" % field)
    if field in data and data[field] != expected:
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot %s does not match its active turn" % field)


def _candidate_like(content):
    try:
        value = json.loads(
            content,
            object_pairs_hook=_duplicate_rejector,
            parse_constant=_json_constant,
        )
    except (json.JSONDecodeError, RecursionError):
        return "chess-echo-orchestrator-agent-candidate-v1" in content
    return isinstance(value, dict) and value.get("format") == "chess-echo-orchestrator-agent-candidate-v1"


def _parse_jsonl(data):
    if not isinstance(data, bytes) or not data or len(data) > JSONL_MAX_BYTES:
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL has an invalid total byte size")
    if not data.endswith(b"\n"):
        _fail("corrupt", "local-agent-jsonl-truncated", "Copilot JSONL must end with LF")
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as error:
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL is not valid UTF-8: %s" % error)
    lines = data[:-1].split(b"\n")
    if not lines or len(lines) > JSONL_MAX_EVENTS:
        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL has an invalid event count")
    events = []
    for encoded in lines:
        if not encoded or len(encoded) + 1 > JSONL_MAX_EVENT_BYTES:
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL contains an invalid physical line")
        if encoded.endswith(b"\r"):
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL framing must use LF")
        try:
            event = json.loads(
                encoded.decode("utf-8"),
                object_pairs_hook=_duplicate_rejector,
                parse_constant=_json_constant,
            )
        except (json.JSONDecodeError, RecursionError) as error:
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot JSONL is malformed: %s" % error)
        if not isinstance(event, dict):
            _fail("corrupt", "local-agent-jsonl-invalid", "Each Copilot JSONL line must be one object")
        events.append(event)
    return events


def _validate_result_event(event):
    if set(event) != {"type", "timestamp", "sessionId", "exitCode", "usage"}:
        _fail("corrupt", "local-agent-result-invalid", "Copilot terminal result has an invalid schema")
    _event_text(event["timestamp"], "result timestamp")
    _event_text(event["sessionId"], "result sessionId")
    if type(event["exitCode"]) is not int or event["exitCode"] != 0:
        _fail("missing", "local-agent-result-failed", "Copilot terminal result is not successful")
    usage = event["usage"]
    if not isinstance(usage, dict) or set(usage) != {
        "premiumRequests",
        "totalApiDurationMs",
        "sessionDurationMs",
        "codeChanges",
    }:
        _fail("corrupt", "local-agent-result-invalid", "Copilot terminal usage has an invalid schema")
    changes = usage["codeChanges"]
    if not isinstance(changes, dict) or set(changes) != {
        "filesModified",
        "linesAdded",
        "linesRemoved",
    }:
        _fail("corrupt", "local-agent-result-invalid", "Copilot terminal code changes have an invalid schema")
    numbers = (
        usage["premiumRequests"],
        usage["totalApiDurationMs"],
        usage["sessionDurationMs"],
        changes["linesAdded"],
        changes["linesRemoved"],
    )
    if any(type(value) is not int or value < 0 for value in numbers):
        _fail("corrupt", "local-agent-result-invalid", "Copilot terminal usage counters are invalid")
    if not isinstance(changes["filesModified"], list) or any(
        not isinstance(path, str) for path in changes["filesModified"]
    ):
        _fail("corrupt", "local-agent-result-invalid", "Copilot terminal modified files are invalid")


def _extract_candidate_from_jsonl(raw):
    events = _parse_jsonl(raw)
    startup_index = 0
    startup_parents = set()
    seen_ids, seen_turns, seen_messages = set(), set(), set()
    active_turn = active_turn_start = active_interaction = user_interaction = None
    active_turn_had_tools = False
    model_call_seen = False
    last_persisted = None
    user_seen = False
    tool_deltas, requested_tools, started_tools, completed_tools = set(), set(), set(), set()
    streamed_messages = set()
    reasoning_groups, summarized_reasoning = {}, set()
    turn_reasoning_id = pending_reasoning_summary = None
    candidate = None
    final_ended = idle_seen = result_seen = False

    for index, event in enumerate(events):
        event_type = event.get("type")
        if event_type == "result":
            if index != len(events) - 1 or not idle_seen or result_seen:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot result must be the final record after assistant.idle")
            _validate_result_event(event)
            result_seen = True
            continue
        if event_type not in JSONL_EVENT_TYPES:
            _fail("unsupported", "local-agent-jsonl-event", "Copilot JSONL contains an unreviewed event type")
        previous = events[index - 1] if index else None
        previous_type = previous.get("type") if isinstance(previous, dict) else None
        if pending_reasoning_summary is not None and event_type != "assistant.reasoning":
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot reasoning summary is missing after its tool request")
        if previous_type == "assistant.reasoning" and event_type != "tool.execution_start":
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot reasoning summary is not followed by tool execution")
        if previous_type == "assistant.reasoning_delta" and event_type not in {
            "assistant.reasoning_delta",
            "assistant.tool_call_delta",
        }:
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot reasoning deltas do not end at a tool call")
        allowed_outer = {"type", "timestamp", "id", "parentId", "data", "ephemeral", "agentId"}
        if set(event) - allowed_outer or not {"type", "timestamp", "id", "parentId", "data"} <= set(event):
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot event envelope has an invalid schema")
        if "agentId" in event:
            _fail("unsupported", "local-agent-jsonl-event", "Copilot subagent events are outside the reviewed transport")
        event_id = _event_text(event["id"], "event id")
        _event_text(event["timestamp"], "event timestamp")
        if event_id in seen_ids:
            _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats an event id")
        parent_id = event["parentId"]
        if parent_id is not None and (not isinstance(parent_id, str) or parent_id == event_id):
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot event parentId is invalid")
        ephemeral = event.get("ephemeral", False)
        if type(ephemeral) is not bool:
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot event ephemeral flag is invalid")
        if (
            event_type in JSONL_EPHEMERAL_TYPES
            and event.get("ephemeral") is not True
            or event_type not in JSONL_EPHEMERAL_TYPES
            and "ephemeral" in event
        ):
            _fail("corrupt", "local-agent-jsonl-invalid", "Copilot event persistence shape is invalid")
        data = _event_data(event)
        if candidate is not None and not final_ended and event_type != "assistant.turn_end":
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot record appeared between the final message and turn end")
        if final_ended and not idle_seen and event_type not in {
            "session.usage_checkpoint",
            "session.background_tasks_changed",
            "assistant.idle",
        }:
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot record appeared outside the final completion boundary")
        if idle_seen and event_type != "session.background_tasks_changed":
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot record appeared after assistant.idle")

        if not user_seen:
            if event_type in JSONL_STARTUP_TYPES:
                position = JSONL_STARTUP_TYPES.index(event_type)
                if position < startup_index:
                    _fail("corrupt", "local-agent-jsonl-sequence", "Copilot startup records are duplicated or reordered")
                startup_index = position + 1
            elif event_type == "user.message":
                user_seen = True
            else:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot JSONL is missing the root user message")
        elif event_type in JSONL_STARTUP_TYPES:
            _fail("corrupt", "local-agent-jsonl-sequence", "Copilot startup record appeared after the user message")
        elif event_type == "user.message":
            _fail("ambiguous", "local-agent-jsonl-sequence", "Copilot JSONL contains multiple user messages")
        elif parent_id not in seen_ids:
            _fail("corrupt", "local-agent-jsonl-parent", "Copilot event parentId does not resolve within the observed turn")
        if user_seen and not ephemeral and event_type != "user.message":
            if parent_id != last_persisted:
                _fail("corrupt", "local-agent-jsonl-parent", "Copilot persisted event chain is broken")
        if not ephemeral:
            last_persisted = event_id
        seen_ids.add(event_id)

        if event_type in JSONL_STARTUP_TYPES or event_type == "user.message":
            if event_type in JSONL_STARTUP_TYPES:
                field = {
                    "session.mcp_servers_loaded": "servers",
                    "session.skills_loaded": "skills",
                    "session.tools_updated": "model",
                }[event_type]
                _event_data_keys(data, {field}, event_type)
                if field == "model":
                    _event_text(data[field], "startup model")
                elif not isinstance(data[field], list):
                    _fail("corrupt", "local-agent-jsonl-invalid", "Copilot startup payload is malformed")
                startup_parent = _event_text(parent_id, "startup parentId")
                if startup_parent in startup_parents or startup_parent in seen_ids:
                    _fail("corrupt", "local-agent-jsonl-parent", "Copilot startup parentId must be distinct and unresolved")
                startup_parents.add(startup_parent)
            else:
                user_interaction = _event_text(data.get("interactionId"), "interactionId")
            continue
        if event_type == "assistant.turn_start":
            if final_ended or idle_seen or active_turn is not None:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot assistant turn starts at an invalid boundary")
            active_turn = _event_text(data.get("turnId"), "turnId")
            if active_turn in seen_turns:
                _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats a turn id")
            active_interaction = _event_text(data.get("interactionId"), "interactionId")
            if not seen_turns and active_interaction != user_interaction:
                _fail("corrupt", "local-agent-jsonl-invalid", "Initial Copilot turn does not match the user interaction")
            seen_turns.add(active_turn)
            active_turn_start = event_id
            tool_deltas = set()
            active_turn_had_tools = False
            model_call_seen = False
            turn_reasoning_id = None
            continue
        if event_type == "model.call_start":
            if (
                active_turn is None
                or model_call_seen
                or previous_type != "assistant.turn_start"
                or parent_id != active_turn_start
            ):
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot model call is outside an assistant turn")
            _event_data_keys(data, {"model", "turnId"}, "model call")
            _event_text(data.get("model"), "model")
            _event_field_matches(data, "turnId", active_turn, required=True)
            model_call_seen = True
            continue
        if event_type == "assistant.reasoning_delta":
            _event_data_keys(data, {"deltaContent", "reasoningId"}, "reasoning delta")
            if not isinstance(data.get("deltaContent"), str):
                _fail("corrupt", "local-agent-jsonl-invalid", "Copilot reasoning delta content is malformed")
            reasoning_id = _event_text(data.get("reasoningId"), "reasoningId")
            if active_turn is None or parent_id != active_turn_start:
                _fail("corrupt", "local-agent-jsonl-parent", "Copilot reasoning delta does not match its active turn")
            if previous_type == "model.call_start":
                if turn_reasoning_id is not None or reasoning_id in reasoning_groups:
                    _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats a reasoning id")
                reasoning_groups[reasoning_id] = active_turn_start
                turn_reasoning_id = reasoning_id
            elif previous_type != "assistant.reasoning_delta" or reasoning_id != turn_reasoning_id:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot reasoning delta group is malformed")
            continue
        if event_type == "assistant.tool_call_delta":
            if active_turn is None or final_ended:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot tool delta is outside an active turn")
            tool_deltas.add(_event_text(data.get("toolCallId"), "toolCallId"))
            continue
        if event_type == "assistant.message_start":
            if active_turn is None or final_ended or event.get("ephemeral") is not True:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot message start is outside the final active turn")
            message_id = _event_text(data.get("messageId"), "messageId")
            if message_id in streamed_messages:
                _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats a streamed message id")
            streamed_messages.add(message_id)
            continue
        if event_type == "assistant.message_delta":
            if active_turn is None or event.get("ephemeral") is not True:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot message delta is outside an active turn")
            if _event_text(data.get("messageId"), "messageId") not in streamed_messages:
                _fail("corrupt", "local-agent-jsonl-parent", "Copilot message delta has no matching message start")
            continue
        if event_type == "assistant.message":
            if active_turn is None or final_ended:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot assistant message is outside an active turn")
            message_id = _event_text(data.get("messageId"), "messageId")
            if message_id in seen_messages:
                _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats a message id")
            seen_messages.add(message_id)
            _event_data_keys(
                data,
                {"content", "messageId", "turnId", "interactionId", "toolRequests"},
                "assistant message",
            )
            _event_field_matches(data, "turnId", active_turn, required=True)
            _event_field_matches(data, "interactionId", active_interaction, required=True)
            content, requests = data.get("content"), data.get("toolRequests")
            if not isinstance(content, str) or not isinstance(requests, list):
                _fail("corrupt", "local-agent-jsonl-invalid", "Copilot assistant message payload is malformed")
            if requests:
                if candidate is not None or _candidate_like(content):
                    _fail("ambiguous", "local-agent-jsonl-candidate", "Candidate content appeared in a nonfinal assistant message")
                for request in requests:
                    if not isinstance(request, dict) or set(request) != {
                        "toolCallId",
                        "name",
                        "type",
                        "arguments",
                    }:
                        _fail("corrupt", "local-agent-jsonl-invalid", "Copilot tool request is malformed")
                    tool_call_id = _event_text(request.get("toolCallId"), "toolCallId")
                    _event_text(request.get("name"), "tool name")
                    if request["type"] != "function":
                        _fail("unsupported", "local-agent-jsonl-tool", "Copilot tool request type is unreviewed")
                    if tool_call_id in requested_tools:
                        _fail("ambiguous", "local-agent-jsonl-duplicate-id", "Copilot JSONL repeats a tool call id")
                    requested_tools.add(tool_call_id)
                active_turn_had_tools = True
                if tool_deltas and tool_deltas != {item["toolCallId"] for item in requests}:
                    _fail("corrupt", "local-agent-jsonl-tool", "Copilot tool requests do not match streamed tool calls")
                if turn_reasoning_id is not None:
                    pending_reasoning_summary = (turn_reasoning_id, event_id)
            elif content:
                if (
                    candidate is not None
                    or active_turn_had_tools
                    or tool_deltas
                    or turn_reasoning_id is not None
                    or requested_tools - completed_tools
                ):
                    _fail("ambiguous", "local-agent-jsonl-candidate", "Copilot JSONL contains multiple or premature final candidates")
                try:
                    candidate = content.encode("utf-8")
                except UnicodeEncodeError as error:
                    raise LocalProviderFailure(
                        "corrupt",
                        "local-agent-jsonl-invalid",
                        "Copilot final assistant content is not valid UTF-8",
                    ) from error
                if len(candidate) > CANDIDATE_MAX_BYTES:
                    _fail(
                        "unsupported",
                        "local-agent-candidate-too-large",
                        "Copilot final assistant content exceeds the candidate byte limit",
                    )
            else:
                _fail("corrupt", "local-agent-jsonl-candidate", "Copilot assistant message has neither tools nor candidate content")
            continue
        if event_type == "assistant.reasoning":
            _event_data_keys(data, {"content", "reasoningId", "rte"}, "reasoning")
            reasoning_id = _event_text(data.get("reasoningId"), "reasoningId")
            expected_reasoning = pending_reasoning_summary
            if (
                not isinstance(data.get("content"), str)
                or data.get("rte") is not True
                or expected_reasoning is None
                or previous_type != "assistant.message"
                or parent_id != expected_reasoning[1]
                or reasoning_id != expected_reasoning[0]
                or reasoning_groups.get(reasoning_id) != active_turn_start
                or reasoning_id in summarized_reasoning
            ):
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot reasoning summary is malformed or misplaced")
            summarized_reasoning.add(reasoning_id)
            pending_reasoning_summary = None
            continue
        if event_type == "tool.execution_start":
            _event_data_keys(
                data,
                {"arguments", "model", "rte", "shellToolInfo", "toolCallId", "toolName", "turnId"},
                "tool start",
            )
            tool_call_id = _event_text(data.get("toolCallId"), "toolCallId")
            _event_text(data.get("toolName"), "tool name")
            _event_text(data.get("model"), "tool model")
            if type(data.get("rte")) is not bool or not isinstance(data.get("shellToolInfo"), dict):
                _fail("corrupt", "local-agent-jsonl-invalid", "Copilot tool start metadata is malformed")
            _event_field_matches(data, "turnId", active_turn, required=True)
            if active_turn is None or tool_call_id not in requested_tools or tool_call_id in started_tools:
                _fail("corrupt", "local-agent-jsonl-tool", "Copilot tool start does not match one pending request")
            started_tools.add(tool_call_id)
            continue
        if event_type == "tool.execution_partial_result":
            if _event_text(data.get("toolCallId"), "toolCallId") not in started_tools:
                _fail("corrupt", "local-agent-jsonl-tool", "Copilot partial tool result has no matching start")
            continue
        if event_type == "tool.execution_complete":
            _event_data_keys(
                data,
                {
                    "interactionId",
                    "model",
                    "result",
                    "rte",
                    "success",
                    "toolCallId",
                    "toolTelemetry",
                    "turnId",
                },
                "tool completion",
            )
            tool_call_id = _event_text(data.get("toolCallId"), "toolCallId")
            _event_text(data.get("model"), "tool model")
            if (
                type(data.get("rte")) is not bool
                or not isinstance(data.get("result"), dict)
                or not isinstance(data.get("toolTelemetry"), dict)
            ):
                _fail("corrupt", "local-agent-jsonl-invalid", "Copilot tool completion metadata is malformed")
            _event_field_matches(data, "turnId", active_turn, required=True)
            _event_field_matches(data, "interactionId", active_interaction, required=True)
            if (
                active_turn is None
                or data.get("success") is not True
                or tool_call_id not in started_tools
                or tool_call_id in completed_tools
            ):
                _fail("corrupt", "local-agent-jsonl-tool", "Copilot tool completion does not match one successful start")
            completed_tools.add(tool_call_id)
            continue
        if event_type == "assistant.turn_end":
            turn_id = _event_text(data.get("turnId"), "turnId")
            if active_turn is None or turn_id != active_turn or started_tools - completed_tools:
                _fail("corrupt", "local-agent-jsonl-turn", "Copilot turn end does not complete the active turn")
            if candidate is not None:
                final_ended = True
            elif not active_turn_had_tools or requested_tools - completed_tools:
                _fail("corrupt", "local-agent-jsonl-turn", "Copilot nonfinal turn has no completed tool request")
            active_turn = active_turn_start = active_interaction = None
            model_call_seen = False
            turn_reasoning_id = None
            continue
        if event_type == "assistant.idle":
            if not final_ended or idle_seen or event.get("ephemeral") is not True or data:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot assistant.idle is malformed or misplaced")
            idle_seen = True
            continue
        if event_type == "session.usage_checkpoint":
            if not final_ended or idle_seen:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot usage checkpoint is outside the final boundary")
            continue
        if event_type == "session.background_tasks_changed":
            if active_turn is None and not final_ended:
                _fail("corrupt", "local-agent-jsonl-sequence", "Copilot background task event is outside an observed boundary")
            continue

    if not user_seen or active_turn is not None:
        _fail("corrupt", "local-agent-jsonl-sequence", "Copilot JSONL ended before its event sequence completed")
    if startup_parents & seen_ids:
        _fail("corrupt", "local-agent-jsonl-parent", "Copilot startup parentId unexpectedly resolves within the emitted stream")
    if requested_tools != started_tools or started_tools != completed_tools:
        _fail("corrupt", "local-agent-jsonl-tool", "Copilot JSONL has unresolved tool calls")
    if candidate is None or not final_ended or not idle_seen or not result_seen:
        _fail("missing", "local-agent-jsonl-incomplete", "Copilot JSONL is missing its final candidate boundary")
    return candidate


def _safe_root(path, label):
    try:
        supplied = pathlib.Path(path).absolute()
        resolved = supplied.resolve(strict=True)
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-unavailable" % label, "%s is unavailable: %s" % (label, error))
    if supplied != resolved or not resolved.is_dir():
        _fail("denied", "%s-redirection" % label, "%s must not use symlink redirection" % label)
    return resolved


def _safe_agent_home(path, control_root, workspace_root):
    root = _safe_root(path, "agent-home")
    metadata = root.stat()
    try:
        populated = any(root.iterdir())
    except OSError as error:
        _fail("missing", "agent-home-unavailable", "Agent home is unavailable: %s" % error)
    if (
        root in {control_root, workspace_root}
        or control_root in root.parents
        or workspace_root in root.parents
        or metadata.st_uid != os.getuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or populated
    ):
        _fail(
            "denied",
            "agent-home-not-dedicated",
            "Agent home must be a distinct, empty, operator-owned directory with mode 0700",
        )
    return root


def _git(provider, root, arguments, label):
    command = [provider.git_executable["path"], "-c", "core.fsmonitor=false", *arguments]
    result = supervisor.supervise(
        command,
        timeout_ms=AUDIT_LIMITS["timeout_ms"],
        grace_ms=AUDIT_LIMITS["grace_ms"],
        output_limit_bytes=AUDIT_LIMITS["output_limit_bytes"],
        cwd=str(root),
        env=provider.audit_environment,
    )
    return _process_output(result, label).decode("utf-8").strip()


def _worktree_identity(provider):
    root = provider.root
    dot_git = root / ".git"
    if not dot_git.is_file() or dot_git.is_symlink():
        _fail(
            "denied",
            "dedicated-worktree-required",
            "Trusted-local agent execution requires a linked worktree with a regular .git file",
        )
    top = pathlib.Path(_git(provider, root, ["rev-parse", "--show-toplevel"], "worktree-root"))
    common = pathlib.Path(_git(provider, root, ["rev-parse", "--git-common-dir"], "worktree-common"))
    branch = _git(provider, root, ["symbolic-ref", "-q", "HEAD"], "worktree-branch")
    head = _git(provider, root, ["rev-parse", "--verify", "HEAD^{commit}"], "worktree-head")
    top = top.resolve(strict=True)
    common = (root / common).resolve(strict=True) if not common.is_absolute() else common.resolve(strict=True)
    if top != root or common != provider.control_common_dir:
        _fail(
            "denied",
            "worktree-identity-mismatch",
            "Candidate workspace is not the dedicated worktree for the reviewed control repository",
        )
    expected_branch = WORKTREE_BRANCH_PREFIX + str(provider.issue)
    if branch != "refs/heads/" + expected_branch:
        _fail(
            "denied",
            "worktree-branch-mismatch",
            "Candidate workspace is not on the deterministic issue worktree branch",
        )
    return {
        "root": str(root),
        "git_common_dir": str(common),
        "branch": branch,
        "head": head,
        "identity_sha256": _sha(
            _canonical(
                {
                    "root": str(root),
                    "git_common_dir": str(common),
                    "branch": branch,
                }
            )
        ),
    }


def _input_projection(root, issue, request):
    reader = inspector.AuthorityReader(inspector.resolve_store(root), issue)
    rows, size = [], 0
    for item in request["input_bindings"]:
        projection = evidence.project(root, item["binding"])
        entries = []
        for entry in projection["entries"]:
            data = reader.read_bytes(entry["payload"], "evidence-payload")
            size += len(data)
            if size > 1024 * 1024:
                _fail("unsupported", "agent-input-projection-too-large", "Agent inputs exceed the 1 MiB projection limit")
            entries.append(
                {
                    "path": entry["path"],
                    "sha256": entry["content_sha256"],
                    "size": entry["size"],
                    "bytes_base64": base64.b64encode(data).decode("ascii"),
                }
            )
        rows.append({"role": item["role"], "binding": copy.deepcopy(item["binding"]), "entries": entries})
    return rows


def _agent_prompt(issue, role, request, request_binding, inputs):
    expected = (
        "plan"
        if request["operation"]["name"] == "write-plan"
        else "implementer"
        if role == "implementer"
        else "review"
    )
    return (
        "Execute exactly one ChessEcho replacement-workflow agent request as role %s for "
        "issue #%d. Work only in the current dedicated candidate worktree. Treat workflow "
        "authority and controller code as read-only host concerns: do not invoke workflow "
        "lifecycle commands, edit .git internals, or treat scripts/** or .github/** as trusted "
        "controller code. The operation is %s and its immutable evidence binding is %s. "
        "The exact host-projected immutable inputs are %s. "
        "Inspect the issue and repository as needed, perform only the requested phase. "
        "The final assistant response content must be exactly one JSON object of kind %s matching "
        "chess-echo-orchestrator-agent-candidate-v1. Emit no prose, Markdown fences, or other "
        "content in that final response."
        % (
            role,
            issue,
            request["operation"]["name"],
            json.dumps(request_binding, ensure_ascii=True, sort_keys=True, separators=(",", ":")),
            json.dumps(inputs, ensure_ascii=True, sort_keys=True, separators=(",", ":")),
            expected,
        )
    )


class LocalSandboxProvider:
    """Pinned provider used only by the reviewed local host."""

    def __init__(
        self,
        *,
        root,
        control_root,
        issue,
        role,
        row,
        git_executable,
        agent_executable,
        agent_home,
        trusted_worker_authentication=False,
        worker_token=None,
    ):
        self.root = _safe_root(root, "candidate-workspace")
        self.control_root = _safe_root(control_root, "control-root")
        if self.root == self.control_root:
            _fail(
                "denied",
                "candidate-control-workspace-alias",
                "Candidate and reviewed control workspaces must be distinct",
            )
        self.issue, self.role = issue, role
        self.name, self.version = NAME, VERSION
        self.source_path = "scripts/workflow_local_provider.py"
        source = source_identity()
        self.source_sha256 = source["sha256"]
        if (
            row.get("provider_name") != self.name
            or row.get("provider_version") != self.version
            or row.get("provider_source") != self.source_path
            or row.get("provider_source_sha256") != self.source_sha256
            or row.get("containment") != "trusted-local-worktree-v1"
        ):
            _fail(
                "denied",
                "local-provider-config-mismatch",
                "Local provider identity differs from the base-pinned role configuration",
            )
        self.git_executable = _file_identity(git_executable, "local-provider-git")
        self.agent_executable = _file_identity(agent_executable, "local-provider-agent")
        if (
            pathlib.Path(self.agent_executable["path"]).name != row["command_prefix"][0]
            or self.agent_executable["sha256"] != row.get("agent_executable_sha256")
        ):
            _fail(
                "denied",
                "local-agent-executable-mismatch",
                "Agent executable differs from the base-pinned identity",
            )
        self.agent_home = _safe_agent_home(
            agent_home, self.control_root, self.root
        )
        if type(trusted_worker_authentication) is not bool:
            _fail(
                "corrupt",
                "worker-authentication-mode",
                "Worker authentication mode must be explicitly enabled or disabled",
            )
        self.authentication = None
        self._worker_token = None
        if trusted_worker_authentication:
            if worker_token is None:
                _fail(
                    "missing",
                    "worker-authentication-missing",
                    "Trusted-local worker authentication credential is missing",
                )
            self._worker_token = _validate_worker_token(worker_token)
            self.authentication = copy.deepcopy(TRUSTED_WORKER_AUTHENTICATION)
        elif worker_token is not None:
            _fail(
                "denied",
                "worker-authentication-not-enabled",
                "Worker authentication credential requires explicit trusted-local mode",
            )
        self.audit_environment = {
            "PATH": os.pathsep.join(
                dict.fromkeys(
                    (
                        str(pathlib.Path(self.git_executable["path"]).parent),
                        "/usr/bin",
                        "/bin",
                    )
                )
            ),
            "HOME": "",
            "LC_ALL": "C.UTF-8",
            "LANG": "C.UTF-8",
            "TZ": "UTC",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_OPTIONAL_LOCKS": "0",
        }
        self.control_common_dir = pathlib.Path(
            _git(self, self.control_root, ["rev-parse", "--git-common-dir"], "control-common")
        )
        if not self.control_common_dir.is_absolute():
            self.control_common_dir = (self.control_root / self.control_common_dir).resolve(strict=True)
        else:
            self.control_common_dir = self.control_common_dir.resolve(strict=True)
        self.before = _worktree_identity(self)

    def execute(self, request, request_binding, command_prefix, cwd, environment, limits, cancel_event):
        if source_identity()["sha256"] != self.source_sha256:
            _fail("stale", "local-provider-replaced", "Local provider source changed before execution")
        if _file_identity(self.git_executable["path"], "local-provider-git") != self.git_executable:
            _fail("stale", "local-provider-git-replaced", "Git executable changed before execution")
        if _file_identity(self.agent_executable["path"], "local-provider-agent") != self.agent_executable:
            _fail("stale", "local-agent-executable-replaced", "Agent executable changed before execution")
        if self.authentication is None or self._worker_token is None:
            _fail(
                "missing",
                "worker-authentication-not-configured",
                "Trusted-local worker authentication was not explicitly enabled",
            )
        before = _worktree_identity(self)
        expected = request.get("repository_before", {}).get("head", {}).get("commit")
        if before != self.before or before["head"] != expected:
            _fail(
                "stale",
                "local-worktree-before-mismatch",
                "Candidate worktree identity or selected commit changed before execution",
            )
        if pathlib.Path(cwd).resolve(strict=True) != self.root:
            _fail("denied", "local-agent-cwd-mismatch", "Agent cwd must be the dedicated worktree root")
        if command_prefix != [pathlib.Path(self.agent_executable["path"]).name]:
            _fail(
                "denied",
                "local-agent-command-mismatch",
                "Agent command must be the exact base-pinned local provider entry",
            )
        if (
            not isinstance(environment, dict)
            or set(environment) != {"PATH", "HOME", "LC_ALL", "LANG", "TZ"}
            or environment["LC_ALL"] != "C.UTF-8"
            or environment["LANG"] != "C.UTF-8"
            or environment["TZ"] != "UTC"
        ):
            _fail(
                "denied",
                "local-agent-environment-mismatch",
                "Agent environment must match the exact reviewed non-secret schema",
            )
        inputs = _input_projection(self.root, self.issue, request)
        prompt = _agent_prompt(self.issue, self.role, request, request_binding, inputs)
        if request["operation"]["name"] in {"write-tests", "implement"}:
            prompt += " Commit all intended changes and leave the candidate worktree clean."
        else:
            prompt += " This is read-only: do not change or commit the candidate worktree."
        if len(prompt.encode("utf-8")) > PROMPT_LIMIT_BYTES:
            _fail("unsupported", "agent-input-projection-too-large", "Encoded agent prompt exceeds the 64 KiB argument limit")
        command = [
            self.agent_executable["path"],
            "--no-auto-update",
            "--no-color",
            "--no-remote",
            "--no-remote-export",
            "--no-ask-user",
            "--no-custom-instructions",
            "--disable-builtin-mcps",
            "--secret-env-vars=" + WORKER_TOKEN_ENV,
            "--log-level",
            "none",
            "--allow-all-tools",
            "--output-format",
            "json",
            "--silent",
            "--prompt",
            prompt,
        ]
        non_secret_environment = {
            "PATH": os.pathsep.join(
                dict.fromkeys(
                    (
                        str(pathlib.Path(self.agent_executable["path"]).parent),
                        str(pathlib.Path(self.git_executable["path"]).parent),
                        "/usr/bin",
                        "/bin",
                    )
                )
            ),
            "HOME": str(self.agent_home),
            "LC_ALL": environment["LC_ALL"],
            "LANG": environment["LANG"],
            "TZ": environment["TZ"],
        }
        token = self._worker_token
        controlled_environment = dict(non_secret_environment)
        controlled_environment[WORKER_TOKEN_ENV] = token
        try:
            process = supervisor.supervise(
                command,
                timeout_ms=limits["timeout_ms"],
                grace_ms=limits["grace_ms"],
                output_limit_bytes=limits["output_limit_bytes"],
                stderr_limit_bytes=limits["stderr_limit_bytes"],
                cwd=str(self.root),
                env=controlled_environment,
                cancel_event=cancel_event,
            )
            if _process_discloses_secret(process, token):
                _scrub_process_result(process)
                _fail(
                    "denied",
                    "worker-authentication-disclosed",
                    "Worker process disclosed its designated authentication credential",
                )
            transport = _process_bytes(process)
            parser_failure = None
            try:
                candidate = _extract_candidate_from_jsonl(transport)
            except LocalProviderFailure as error:
                if _process_succeeded(process):
                    raise
                parser_failure = error
                candidate = b""
            if parser_failure is None and _bytes_disclose_secret(candidate, token):
                _scrub_process_result(process)
                _fail(
                    "denied",
                    "worker-authentication-disclosed",
                    "Worker candidate disclosed its designated authentication credential",
                )
            process_diagnostic = _process_diagnostic(process, parser_failure)
        finally:
            controlled_environment.pop(WORKER_TOKEN_ENV, None)
            self._worker_token = None
            token = None
        after = _worktree_identity(self)
        environment_identity = {
            "keys": sorted([*non_secret_environment, WORKER_TOKEN_ENV]),
            "non_secret_values": non_secret_environment,
            "secret_keys": [WORKER_TOKEN_ENV],
            "authentication": copy.deepcopy(self.authentication),
        }
        environment_identity["sha256"] = _sha(_canonical(environment_identity))
        facts = {
            "format": RESULT_FORMAT,
            "provider": {
                "name": self.name,
                "version": self.version,
                "source": self.source_path,
                "source_sha256": self.source_sha256,
            },
            "request_sha256": request["request_sha256"],
            "authority_binding": copy.deepcopy(request["authority_binding"]),
            "input_projection_sha256": _sha(_canonical(inputs)),
            "command": {
                "argv": command,
                "argv_sha256": _sha(_canonical(command)),
                "executable": copy.deepcopy(self.agent_executable),
            },
            "workspace": {
                "identity_sha256": before["identity_sha256"],
                "root": before["root"],
                "cwd": str(self.root),
                "git_common_dir": before["git_common_dir"],
                "branch": before["branch"],
                "selected_commit": expected,
                "head_before": before["head"],
                "head_after": after["head"],
            },
            "environment": environment_identity,
            "process_result_sha256": _sha(_canonical(process)),
            "process_diagnostic": process_diagnostic,
            "transport_output": {
                "bytes": len(transport),
                "base64": base64.b64encode(transport).decode("ascii"),
            },
            "transport_sha256": _sha(transport),
            "transport_size": len(transport),
            "candidate_sha256": _sha(candidate),
            "candidate_size": len(candidate),
            "isolation": {
                "process": "bounded-posix-process-group",
                "filesystem": "not-isolated-same-uid",
                "network": "not-isolated",
                "credentials": "not-isolated",
                "authority_store": "not-isolated-same-uid",
            },
        }
        facts["result_sha256"] = _sha(_canonical(facts))
        return {
            "command": command,
            "process_result": process,
            "candidate_output": {
                "bytes": len(candidate),
                "base64": base64.b64encode(candidate).decode("ascii"),
            },
            "provider_result": facts,
        }

    def input_projection_sha256(self, request):
        return _sha(_canonical(_input_projection(self.root, self.issue, request)))


def _process_bytes(process):
    return _process_stream_bytes(process, "stdout")


def _process_succeeded(process):
    return (
        isinstance(process, dict)
        and process.get("outcome") == "success"
        and process.get("exit_code") == 0
        and process.get("cleanup_verified") is True
    )


def _process_diagnostic(process, parser_failure=None):
    if _process_succeeded(process):
        return None
    stderr = _process_stream_bytes(process, "stderr")
    stdout = _process_bytes(process)
    parser = None
    if parser_failure is not None:
        parser = {"status": parser_failure.status, "code": parser_failure.code}
    return {
        "format": PROCESS_DIAGNOSTIC_FORMAT,
        "outcome": process.get("outcome"),
        "reason": process.get("reason"),
        "exit_code": process.get("exit_code"),
        "terminating_signal": process.get("terminating_signal"),
        "stdout": {"bytes": len(stdout), "sha256": _sha(stdout)},
        "stderr": {"bytes": len(stderr), "sha256": _sha(stderr)},
        "provider_failure": {
            "status": "missing",
            "code": "local-agent-process-failed",
        },
        "parser_failure": parser,
    }


def _process_stream_bytes(process, stream):
    if (
        not isinstance(process, dict)
        or process.get("format") != supervisor.RESULT_FORMAT
    ):
        _fail("corrupt", "local-agent-output-invalid", "Agent process result is malformed")
    try:
        record = process[stream]
        data = base64.b64decode(record["base64"], validate=True)
    except (KeyError, TypeError, ValueError, binascii.Error):
        _fail("corrupt", "local-agent-output-invalid", "Agent process output is malformed")
    if set(record) != {"bytes", "base64"} or record["bytes"] != len(data):
        _fail("corrupt", "local-agent-output-invalid", "Agent output identity is malformed")
    return data


class PendingResultStore:
    """Exact, bounded result-binding index; it never scans workflow CAS."""

    def __init__(self, root, repository_root=None):
        self.root = pathlib.Path(root).absolute()
        self.repository_root = pathlib.Path(repository_root).absolute() if repository_root is not None else None
        if self.root.exists() and (self.root.is_symlink() or not self.root.is_dir()):
            _fail("denied", "result-store-redirection", "Pending-result store must be a real directory")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.root.resolve(strict=True) != self.root:
            _fail("denied", "result-store-redirection", "Pending-result store must not use symlink redirection")
        os.chmod(self.root, 0o700)

    def _path(self, query):
        digest = query.get("query_sha256") if isinstance(query, dict) else None
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            _fail("corrupt", "pending-result-query-invalid", "Pending-result query identity is invalid")
        return self.root / (digest + ".json")

    def __call__(self, _root, _issue, query):
        path = self._path(query)
        if not path.exists():
            candidates = []
        else:
            if path.is_symlink() or not path.is_file():
                _fail("denied", "pending-result-record-redirection", "Pending-result record is not regular")
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, json.JSONDecodeError) as error:
                _fail("corrupt", "pending-result-record-invalid", "Pending-result record is invalid: %s" % error)
            if (
                not isinstance(record, dict)
                or set(record) != {"format", "query", "candidate", "binding_base64"}
                or record["format"] != "chess-echo-trusted-local-pending-result-v1"
                or record["query"] != query
            ):
                _fail("stale", "pending-result-record-mismatch", "Pending-result record does not match the exact query")
            try:
                binding_data = base64.b64decode(record["binding_base64"], validate=True)
            except (TypeError, ValueError, binascii.Error):
                _fail("corrupt", "pending-result-record-invalid", "Pending-result binding bytes are invalid")
            binding = record["candidate"].get("binding") if isinstance(record["candidate"], dict) else None
            expected = {"kind": "evidence-binding", "sha256": _sha(binding_data), "size": len(binding_data)}
            if binding != expected:
                _fail("corrupt", "pending-result-record-invalid", "Pending-result binding bytes do not match the candidate")
            if self.repository_root is None:
                _fail("unsupported", "pending-result-recovery-unavailable", "Pending-result recovery requires the workflow repository root")
            store = inspector.resolve_store(self.repository_root)
            workflow_cas.publish_immutable(
                inspector.object_path(store, binding["sha256"]),
                binding_data,
                lambda code, message: _fail("corrupt", code, message),
                temporary_label="pending-result",
            )
            candidates = [record["candidate"]]
        return {
            "format": DISCOVERY_FORMAT,
            "query_sha256": query["query_sha256"],
            "candidates": candidates,
        }

    def prepare(self, query, binding, binding_data):
        path = self._path(query)
        if not isinstance(binding_data, bytes):
            _fail("corrupt", "pending-result-binding-invalid", "Pending-result binding bytes are invalid")
        expected = {"kind": "evidence-binding", "sha256": _sha(binding_data), "size": len(binding_data)}
        if binding != expected:
            _fail("conflict", "pending-result-binding-mismatch", "Pending-result binding does not match its bytes")
        record = {
            "format": "chess-echo-trusted-local-pending-result-v1",
            "query": copy.deepcopy(query),
            "candidate": {"kind": query["result_kind"], "binding": copy.deepcopy(binding)},
            "binding_base64": base64.b64encode(binding_data).decode("ascii"),
        }
        workflow_cas.publish_immutable(
            path,
            _canonical(record),
            lambda code, message: _fail("conflict", code, message),
            temporary_label="pending-result-index",
        )

    def record(self, query, handoff):
        path = self._path(query)
        if not isinstance(handoff, dict) or handoff.get("format") != HANDOFF_FORMAT:
            _fail("corrupt", "execution-handoff-invalid", "Only an exact execution handoff can be indexed")
        kind = query["result_kind"]
        binding = (
            handoff.get("pr_observation_binding")
            if kind == "github-pr-observation"
            else handoff.get("result_binding")
        )
        if not path.exists():
            _fail("conflict", "pending-result-record-not-prepared", "Pending-result record was not prepared before evidence publication")
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            _fail("corrupt", "pending-result-record-invalid", "Pending-result record is invalid: %s" % error)
        if record.get("query") != query or record.get("candidate") != {"kind": kind, "binding": binding}:
            _fail("conflict", "pending-result-record-conflict", "Pending-result identity is already bound differently")
