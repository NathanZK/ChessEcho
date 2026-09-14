#!/usr/bin/env python3
"""Minimal skill-driven workflow orchestration for ChessEcho issues."""

import argparse
import base64
import datetime as dt
import json
import pathlib
import re
import shlex

if __package__:
    from . import workflow_supervisor
else:
    import workflow_supervisor

STATE_FORMAT = "chess-echo-skill-workflow-state-v1"
CONFIG_FORMAT = "chess-echo-skill-workflow-config-v1"

READY = "READY_FOR_HUMAN_APPROVAL"
REVISION = "NEEDS_REVISION"
REVIEW_STATUSES = (READY, REVISION)

STATUS_SEQUENCE = (
    "PLANNING",
    "PLAN_REVIEW",
    "WAITING_FOR_PLAN_HUMAN_APPROVAL",
    "TEST_IMPLEMENTATION",
    "TEST_REVIEW",
    "WAITING_FOR_TEST_HUMAN_APPROVAL",
    "IMPLEMENTATION",
    "VALIDATION",
    "FINAL_REVIEW",
    "WAITING_FOR_PR_HUMAN_APPROVAL",
    "PR_APPROVED",
)

ARTIFACT_FILES = {
    "plan": "plan.md",
    "plan_review": "plan-review.md",
    "test_report": "test-report.md",
    "test_review": "test-review.md",
    "implementation_report": "implementation-report.md",
    "final_review": "final-review.md",
}

class WorkflowError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


# ---------- core helpers ----------


def _now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def _raise(code, message):
    raise WorkflowError(code, message)


def _ensure(condition, code, message):
    if not condition:
        _raise(code, message)


def _read_json(path, label):
    """Read one required JSON object and convert malformed input to workflow errors."""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        _raise("missing-file", "%s is missing: %s" % (label, path))
    except json.JSONDecodeError as error:
        _raise(
            "invalid-json",
            "%s at %s is not valid JSON (%s)" % (label, path, error),
        )
    _ensure(
        isinstance(value, dict),
        "invalid-json",
        "%s at %s must contain a JSON object" % (label, path),
    )
    return value


def _write_json(path, payload):
    """Persist a JSON object in the issue-local run directory."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _artifact_root(root, config):
    """Resolve the configured root for issue-local workflow runs."""
    relative = config["workflow"].get("run_root", ".agent-workflow/runs")
    location = (root / relative).resolve()
    return location


def _run_root(root, config, issue):
    """Return the filesystem directory for one issue's workflow state."""
    return _artifact_root(root, config) / ("issue-%s" % issue)


def _state_path(root, config, issue):
    return _run_root(root, config, issue) / "state.json"


def _artifacts_dir(root, config, issue):
    return _run_root(root, config, issue) / "artifacts"


def _read_state(root, config, issue):
    """Load and validate the current persisted gate state."""
    state_file = _state_path(root, config, issue)
    state = _read_json(state_file, "workflow state")
    _ensure(
        state.get("format") == STATE_FORMAT,
        "invalid-state-format",
        "Workflow state at %s has unsupported format" % state_file,
    )
    _ensure(
        state.get("status") in STATUS_SEQUENCE,
        "invalid-state",
        "Workflow state at %s has unknown status" % state_file,
    )
    return state


def _write_state(root, config, issue, state):
    """Persist state and refresh its modification timestamp."""
    state["updated_at"] = _now()
    _write_json(_state_path(root, config, issue), state)


def _expect_status(state, expected, operation):
    _ensure(
        state["status"] == expected,
        "invalid-transition",
        "%s requires status %s (current: %s)"
        % (operation, expected, state["status"]),
    )


def _relative(path, root):
    try:
        return str(path.resolve().relative_to(root.resolve()))
    except ValueError:
        return str(path.resolve())


def _resolve_file(root, supplied):
    candidate = pathlib.Path(supplied)
    if not candidate.is_absolute():
        candidate = root / candidate
    candidate = candidate.resolve()
    _ensure(candidate.is_file(), "artifact-missing", "File not found: %s" % candidate)
    return candidate


def _record_artifact(root, config, issue, kind, supplied_path):
    """Copy a submitted report into stable run-local storage without asserting trust."""
    source = _resolve_file(root, supplied_path)
    destination = _artifacts_dir(root, config, issue) / ARTIFACT_FILES[kind]
    destination.parent.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes()
    destination.write_bytes(data)
    return {
        "kind": kind,
        "path": _relative(destination, root),
        "source": _relative(source, root),
        "recorded_at": _now(),
    }


def _artifact_text(root, config, issue, kind):
    """Read the exact run-local report for human inspection at an approval gate."""
    artifact = _artifacts_dir(root, config, issue) / ARTIFACT_FILES[kind]
    return artifact.read_text(encoding="utf-8")


def _load_config(root):
    """Load and validate roles, approvals, command limits, and check profiles."""
    path = root / ".github" / "agent-workflow.json"
    config = _read_json(path, "workflow config")
    workflow = config.get("workflow")
    _ensure(
        isinstance(workflow, dict),
        "invalid-config",
        "workflow config is missing the workflow object",
    )
    _ensure(
        workflow.get("format") == CONFIG_FORMAT,
        "invalid-config-format",
        "workflow.format must be %s" % CONFIG_FORMAT,
    )
    _ensure(
        isinstance(config.get("target_base"), str) and config["target_base"],
        "invalid-config",
        "target_base must be a non-empty string",
    )

    roles = workflow.get("roles")
    _ensure(isinstance(roles, dict), "invalid-config", "workflow.roles must be an object")
    for key in ("planner", "reviewer", "test_implementer", "implementer"):
        _ensure(
            isinstance(roles.get(key), str) and roles[key],
            "invalid-config",
            "workflow.roles.%s must be a non-empty string" % key,
        )

    approvals = workflow.get("approvals")
    _ensure(
        isinstance(approvals, dict),
        "invalid-config",
        "workflow.approvals must be an object",
    )
    for key in ("plan", "tests", "pr"):
        _ensure(
            isinstance(approvals.get(key), str) and approvals[key],
            "invalid-config",
            "workflow.approvals.%s must be a non-empty string" % key,
        )

    execution = workflow.get("execution")
    _ensure(
        isinstance(execution, dict),
        "invalid-config",
        "workflow.execution must be an object",
    )

    for kind in ("git", "github"):
        section = execution.get(kind)
        _ensure(isinstance(section, dict), "invalid-config", "workflow.execution.%s missing" % kind)
        _ensure(
            isinstance(section.get("command"), list)
            and section["command"]
            and all(isinstance(x, str) and x for x in section["command"]),
            "invalid-config",
            "workflow.execution.%s.command must be a non-empty string list" % kind,
        )

    validation_profiles = config.get("validation_profiles")
    _ensure(
        isinstance(validation_profiles, dict) and validation_profiles,
        "invalid-config",
        "validation_profiles must be a non-empty object",
    )
    return config


def _role_name(config, role_key):
    return config["workflow"]["roles"][role_key]


def _require_role(config, role_key, provided, operation):
    expected = _role_name(config, role_key)
    _ensure(
        provided == expected,
        "role-mismatch",
        "%s requires --%s %s" % (operation, role_key.replace("_", "-"), expected),
    )


def _clear_post_plan(state):
    """Invalidate all downstream state after a new or revised plan."""
    for key in (
        "test_report",
        "test_review",
        "implementation_report",
        "final_review",
    ):
        state["artifacts"].pop(key, None)
    state["approvals"]["tests"] = None
    state["approvals"]["pr"] = None
    state["validation"] = None
    state["final_review_ready"] = False
    state["draft_pr"] = None


def _clear_post_tests(state):
    """Invalidate implementation and publication state after revised tests."""
    for key in ("implementation_report", "final_review"):
        state["artifacts"].pop(key, None)
    state["approvals"]["pr"] = None
    state["validation"] = None
    state["final_review_ready"] = False
    state["draft_pr"] = None


def _emit(payload):
    print(json.dumps(payload, indent=2, sort_keys=True))


# ---------- bounded command execution ----------


def _effective_limits(config, key):
    """Resolve bounded execution limits for one command category."""
    execution = config["workflow"]["execution"]
    default = execution.get("default", {})
    section = execution.get(key, {})

    def pick(name, fallback):
        value = section.get(name, default.get(name, fallback))
        _ensure(
            type(value) is int and value >= (0 if name == "grace_ms" else 1),
            "invalid-config",
            "workflow.execution.%s.%s is invalid" % (key, name),
        )
        return value

    stderr_limit = section.get(
        "stderr_limit_bytes", default.get("stderr_limit_bytes")
    )
    if stderr_limit is not None:
        _ensure(
            type(stderr_limit) is int and stderr_limit >= 1,
            "invalid-config",
            "workflow.execution.%s.stderr_limit_bytes is invalid" % key,
        )

    return {
        "timeout_ms": pick("timeout_ms", 30000),
        "grace_ms": pick("grace_ms", 1000),
        "output_limit_bytes": pick("output_limit_bytes", 524288),
        "stderr_limit_bytes": stderr_limit,
    }


def _decode_output(result, stream):
    encoded = result.get(stream, {}).get("base64", "")
    raw = base64.b64decode(encoded)
    return raw.decode("utf-8", errors="replace")


def _run_bounded(command, limits, cwd):
    """Run a command through the process supervisor and decode retained output."""
    result = workflow_supervisor.supervise(
        command,
        timeout_ms=limits["timeout_ms"],
        grace_ms=limits["grace_ms"],
        output_limit_bytes=limits["output_limit_bytes"],
        stderr_limit_bytes=limits.get("stderr_limit_bytes"),
        cwd=str(cwd),
    )
    return {
        "command": command,
        "result": result,
        "stdout_text": _decode_output(result, "stdout"),
        "stderr_text": _decode_output(result, "stderr"),
    }


def _run_checked(command, limits, cwd, code, context):
    """Run a bounded command and raise a workflow error unless it succeeds."""
    completed = _run_bounded(command, limits, cwd)
    result = completed["result"]
    ok = result.get("outcome") == "success" and result.get("exit_code") == 0
    if not ok:
        message = "%s failed (%s, exit=%s)" % (
            context,
            result.get("outcome"),
            result.get("exit_code"),
        )
        stderr = completed["stderr_text"].strip()
        if stderr:
            message = "%s: %s" % (message, stderr)
        _raise(code, message)
    return completed


def _git_command(config, *parts):
    return list(config["workflow"]["execution"]["git"]["command"]) + list(parts)


def _github_command(config, *parts):
    return list(config["workflow"]["execution"]["github"]["command"]) + list(parts)


def _current_head(root, config):
    limits = _effective_limits(config, "git")
    completed = _run_checked(
        _git_command(config, "rev-parse", "HEAD"),
        limits,
        root,
        "git-head-failed",
        "unable to read HEAD",
    )
    return completed["stdout_text"].strip()


def _git_status(root, config):
    completed = _run_checked(
        _git_command(config, "status", "--porcelain"),
        _effective_limits(config, "git"),
        root,
        "git-status-failed",
        "unable to inspect working tree",
    )
    return [line for line in completed["stdout_text"].splitlines() if line]


def _git_diff_names(root, config, revision):
    completed = _run_checked(
        _git_command(config, "diff", "--name-only", revision),
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to inspect commit changes",
    )
    return [line.strip() for line in completed["stdout_text"].splitlines() if line.strip()]


def _git_commit_count(root, config, base, head, context):
    """Return the number of commits in the candidate range from base to head."""
    completed = _run_checked(
        _git_command(config, "rev-list", "--count", "%s..%s" % (base, head)),
        _effective_limits(config, "git"),
        root,
        "git-commit-count-failed",
        "unable to count commits for %s" % context,
    )
    try:
        return int(completed["stdout_text"].strip())
    except ValueError:
        _raise("invalid-git-commit-count", "Git returned an invalid commit count for %s" % context)


def _require_single_commit(root, config, base, head, context):
    """Require one final implementation commit relative to the approved base."""
    _ensure(
        _git_commit_count(root, config, base, head, context) == 1,
        "invalid-implementation-topology",
        "%s requires exactly one commit relative to the approved base" % context,
    )


def _git_ancestor(root, config, ancestor, descendant, context):
    """Require Git ancestry before accepting a candidate transition."""
    completed = _run_bounded(
        _git_command(config, "merge-base", "--is-ancestor", ancestor, descendant),
        _effective_limits(config, "git"),
        root,
    )
    result = completed["result"]
    _ensure(
        result.get("outcome") == "success" and result.get("exit_code") == 0,
        "invalid-git-ancestry",
        "%s: %s is not an ancestor of %s" % (context, ancestor, descendant),
    )


def _git_fetch_target(root, config):
    target = config["target_base"]
    return _run_checked(
        _git_command(config, "fetch", "origin", target),
        _effective_limits(config, "git"),
        root,
        "git-fetch-failed",
        "unable to fetch target branch",
    )


def _known_target_head(root, config):
    completed = _run_bounded(
        _git_command(config, "rev-parse", "origin/%s" % config["target_base"]),
        _effective_limits(config, "git"),
        root,
    )
    result = completed["result"]
    if result.get("outcome") == "success" and result.get("exit_code") == 0:
        return completed["stdout_text"].strip()
    return None


def _require_clean_tree(root, config, context):
    """Require a clean worktree before inspecting or publishing committed state."""
    _ensure(
        not _git_status(root, config),
        "git-worktree-dirty",
        "%s requires a clean Git worktree" % context,
    )


def _is_test_file(path):
    return (
        path.startswith("src/test/")
        or path.startswith("frontend/") and (
            "/__tests__/" in path
            or path.endswith((".test.ts", ".test.tsx", ".test.js", ".test.jsx"))
            or path.endswith((".spec.ts", ".spec.tsx", ".spec.js", ".spec.jsx"))
        )
        or path.startswith("scripts/tests/")
    )


def _path_in_scope(path, scope):
    return any(path == allowed or path.startswith(allowed.rstrip("/") + "/") for allowed in scope)


def _require_test_only(paths, scope, context):
    """Require that a commit contains only approved test paths."""
    _ensure(paths, "missing-test-commit", "%s must contain at least one test change" % context)
    _ensure(
        all(_is_test_file(path) and _path_in_scope(path, scope) for path in paths),
        "test-scope-drift",
        "%s may change only approved test files: %s" % (context, ", ".join(paths)),
    )


def _require_production_only(paths, scope, context):
    """Require that a commit contains only approved non-test paths."""
    _ensure(
        paths,
        "missing-implementation-commit",
        "%s must contain at least one production change" % context,
    )
    _ensure(
        all(not _is_test_file(path) and _path_in_scope(path, scope) for path in paths),
        "implementation-test-modification",
        "%s may change only approved production files: %s" % (context, ", ".join(paths)),
    )


def _select_validation_profile(config, requested):
    """Validate and return the explicitly selected configured check profile."""
    profiles = config["validation_profiles"]
    _ensure(
        requested in profiles,
        "unknown-profile",
        "Unknown validation profile: %s" % requested,
    )
    return requested


def _run_validation_checks(root, config, profile_name):
    """Run every command in a configured profile under bounded supervision."""
    profile = config["validation_profiles"][profile_name]
    checks = profile.get("checks")
    _ensure(
        isinstance(checks, list) and checks,
        "invalid-config",
        "validation profile %s has no checks" % profile_name,
    )
    limits = _effective_limits(config, "validation")
    results = []

    for index, check in enumerate(checks):
        _ensure(
            isinstance(check, dict) and isinstance(check.get("command"), list),
            "invalid-config",
            "validation check #%d in profile %s is invalid" % (index, profile_name),
        )
        name = check.get("name", "check-%d" % (index + 1))
        command = check["command"]
        cwd = root / check.get("cwd", ".")
        completed = _run_bounded(command, limits, cwd)
        result = completed["result"]
        passed = result.get("outcome") == "success" and result.get("exit_code") == 0
        results.append(
            {
                "name": name,
                "command": command,
                "cwd": _relative(cwd, root),
                "passed": passed,
                "result": result,
            }
        )
    return results


def _require_confirmation(config, state, gate, provided, by):
    """Record an approval only when its configured exact phrase is supplied."""
    expected = config["workflow"]["approvals"][gate]
    _ensure(
        provided == expected,
        "approval-confirmation-mismatch",
        "Expected confirmation phrase for %s gate: %s" % (gate, expected),
    )
    state["approvals"][gate] = {
        "by": by,
        "confirmation": provided,
        "at": _now(),
    }


def _validate_pr_body(body_path):
    """Require the repository's three-section draft PR body format."""
    body = body_path.read_text(encoding="utf-8")
    headings = re.findall(r"^##\s+(.+)\s*$", body, flags=re.MULTILINE)
    _ensure(
        headings == ["What", "Why", "Testing"],
        "invalid-pr-body-format",
        "Draft PR body must contain exactly ## What, ## Why, and ## Testing in order",
    )


# ---------- commands ----------


def command_init(args, root, config):
    """Create the issue-local run state and record the trusted starting commit."""
    run = _run_root(root, config, args.issue)
    _ensure(not run.exists(), "already-initialized", "Workflow run already exists for issue %s" % args.issue)
    _artifacts_dir(root, config, args.issue).mkdir(parents=True, exist_ok=True)
    state = {
        "format": STATE_FORMAT,
        "issue": args.issue,
        "target_base": config["target_base"],
        "status": "PLANNING",
        "artifacts": {},
        "approvals": {"plan": None, "tests": None, "pr": None},
        "validation": None,
        "final_review_ready": False,
        "draft_pr": None,
        "base_head": _current_head(root, config),
        "target_head": _known_target_head(root, config),
        "approved_scope": None,
        "test_commit": None,
        "implementation_commit": None,
        "created_at": _now(),
        "updated_at": _now(),
    }
    _write_state(root, config, args.issue, state)
    return {"ok": True, "issue": args.issue, "status": state["status"], "run_dir": _relative(run, root)}


def command_status(args, root, config):
    """Return the persisted gate state for one issue-local workflow run."""
    state = _read_state(root, config, args.issue)
    return {
        "ok": True,
        "issue": args.issue,
        "run_dir": _relative(_run_root(root, config, args.issue), root),
        "state": state,
    }


def command_submit_plan(args, root, config):
    """Store a planner report and bind its approved file scope to the run."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "PLANNING", "submit-plan")
    _require_role(config, "planner", args.agent, "submit-plan")
    _ensure(args.scope, "missing-approved-scope", "submit-plan requires at least one approved path")
    state["artifacts"]["plan"] = _record_artifact(root, config, args.issue, "plan", args.artifact)
    state["approved_scope"] = args.scope
    _clear_post_plan(state)
    state["status"] = "PLAN_REVIEW"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "artifact": state["artifacts"]["plan"]}


def command_review_plan(args, root, config):
    """Store the read-only plan review and route it to approval or revision."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "PLAN_REVIEW", "review-plan")
    _require_role(config, "reviewer", args.reviewer, "review-plan")
    _ensure(args.status in REVIEW_STATUSES, "invalid-review-status", "Unknown review status")
    state["artifacts"]["plan_review"] = _record_artifact(
        root, config, args.issue, "plan_review", args.artifact
    )
    state["status"] = (
        "WAITING_FOR_PLAN_HUMAN_APPROVAL" if args.status == READY else "PLANNING"
    )
    if args.status == REVISION:
        _clear_post_plan(state)
    _write_state(root, config, args.issue, state)
    response = {"ok": True, "status": state["status"], "review_status": args.status}
    if state["status"] == "WAITING_FOR_PLAN_HUMAN_APPROVAL":
        response["human_approval"] = {
            "required": True,
            "message": (
                "The coordinator is stopped. Inspect the exact submitted plan and read-only "
                "review, then run approve-plan with the configured confirmation phrase."
            ),
            "plan_path": state["artifacts"]["plan"]["path"],
            "plan": _artifact_text(root, config, args.issue, "plan"),
            "review_path": state["artifacts"]["plan_review"]["path"],
            "review": _artifact_text(root, config, args.issue, "plan_review"),
            "approval_command": (
                "python3 scripts/agent_workflow.py approve-plan %s --by LOGIN "
                "--confirm plan_approved" % args.issue
            ),
        }
    return response


def command_approve_plan(args, root, config):
    """Advance to test implementation only after the exact plan approval."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PLAN_HUMAN_APPROVAL", "approve-plan")
    _require_confirmation(config, state, "plan", args.confirm, args.by)
    state["status"] = "TEST_IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "approved_by": args.by}


def command_reject_plan(args, root, config):
    """Return a rejected plan to planning and invalidate downstream work."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PLAN_HUMAN_APPROVAL", "reject-plan")
    state["approvals"]["plan"] = None
    _clear_post_plan(state)
    state["status"] = "PLANNING"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


def command_submit_tests(args, root, config):
    """Verify and record a committed tests-only change plus its targeted failure."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "TEST_IMPLEMENTATION", "submit-tests")
    _require_role(config, "test_implementer", args.agent, "submit-tests")
    _require_clean_tree(root, config, "submit-tests")
    scope = state.get("approved_scope") or []
    _ensure(scope, "missing-approved-scope", "submit-tests requires approved plan scope")
    base_head = state.get("base_head")
    _ensure(base_head, "missing-base-commit", "Workflow has no recorded base commit")
    test_head = _current_head(root, config)
    _ensure(
        test_head != base_head,
        "missing-test-commit",
        "submit-tests requires a committed test change",
    )
    test_paths = _git_diff_names(root, config, "%s..%s" % (base_head, test_head))
    _git_ancestor(root, config, base_head, test_head, "submit-tests")
    _require_test_only(test_paths, scope, "submit-tests")
    _ensure(
        shlex.split(args.failure_command),
        "missing-test-failure-check",
        "submit-tests requires a targeted failure command",
    )
    failure = _run_bounded(
        shlex.split(args.failure_command),
        _effective_limits(config, "validation"),
        root,
    )
    failure_result = failure["result"]
    failure_output = failure["stdout_text"] + failure["stderr_text"]
    _ensure(
        failure_result.get("outcome") == "nonzero-exit"
        and failure_result.get("exit_code") != 0,
        "test-did-not-fail",
        "targeted test command did not fail before implementation",
    )
    _ensure(
        args.failure_contains in failure_output,
        "unexpected-test-failure",
        "targeted test failed without the expected behavioral message",
    )
    _require_clean_tree(root, config, "submit-tests after failure check")
    state["artifacts"]["test_report"] = _record_artifact(
        root, config, args.issue, "test_report", args.artifact
    )
    _clear_post_tests(state)
    state["test_commit"] = test_head
    state["test_failure"] = {
        "command": args.failure_command,
        "contains": args.failure_contains,
        "result": failure_result,
    }
    state["status"] = "TEST_REVIEW"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"]}


def command_review_tests(args, root, config):
    """Store the read-only test review and route it to approval or revision."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "TEST_REVIEW", "review-tests")
    _require_role(config, "reviewer", args.reviewer, "review-tests")
    _ensure(args.status in REVIEW_STATUSES, "invalid-review-status", "Unknown review status")
    state["artifacts"]["test_review"] = _record_artifact(
        root, config, args.issue, "test_review", args.artifact
    )
    state["status"] = (
        "WAITING_FOR_TEST_HUMAN_APPROVAL" if args.status == READY else "TEST_IMPLEMENTATION"
    )
    if args.status == REVISION:
        _clear_post_tests(state)
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "review_status": args.status}


def command_approve_tests(args, root, config):
    """Advance to implementation only after the exact test approval."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_TEST_HUMAN_APPROVAL", "approve-tests")
    _require_confirmation(config, state, "tests", args.confirm, args.by)
    state["status"] = "IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "approved_by": args.by}


def command_reject_tests(args, root, config):
    """Return rejected tests to test implementation and clear downstream state."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_TEST_HUMAN_APPROVAL", "reject-tests")
    state["approvals"]["tests"] = None
    _clear_post_tests(state)
    state["status"] = "TEST_IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


def command_submit_implementation(args, root, config):
    """Verify and record a committed production-only change after approved tests."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "IMPLEMENTATION", "submit-implementation")
    _ensure(
        state["approvals"]["tests"] is not None,
        "tests-not-approved",
        "submit-implementation requires approved tests",
    )
    _require_role(config, "implementer", args.agent, "submit-implementation")
    _require_clean_tree(root, config, "submit-implementation")
    test_commit = state.get("test_commit")
    scope = state.get("approved_scope") or []
    _ensure(test_commit, "missing-test-commit", "submit-implementation requires a committed test change")
    _ensure(scope, "missing-approved-scope", "submit-implementation requires approved plan scope")
    implementation_head = _current_head(root, config)
    _ensure(
        implementation_head != test_commit,
        "missing-implementation-commit",
        "submit-implementation requires a committed final implementation",
    )
    _git_ancestor(root, config, state["base_head"], test_commit, "submit-implementation")
    _git_ancestor(root, config, state["base_head"], implementation_head, "submit-implementation")
    _require_single_commit(root, config, state["base_head"], implementation_head, "submit-implementation")
    test_paths = set(_git_diff_names(root, config, "%s..%s" % (state["base_head"], test_commit)))
    implementation_paths = _git_diff_names(
        root, config, "%s..%s" % (state["base_head"], implementation_head)
    )
    _ensure(
        test_paths.issubset(implementation_paths),
        "tests-not-preserved",
        "submit-implementation must preserve the approved test files",
    )
    test_diff = _run_bounded(
        _git_command(
            config,
            "diff",
            "--quiet",
            test_commit,
            implementation_head,
            "--",
            *sorted(test_paths),
        ),
        _effective_limits(config, "git"),
        root,
    )
    _ensure(
        test_diff["result"].get("outcome") == "success"
        and test_diff["result"].get("exit_code") == 0,
        "tests-modified-after-approval",
        "submit-implementation must preserve approved test content",
    )
    _ensure(
        any(not _is_test_file(path) for path in implementation_paths),
        "missing-implementation-commit",
        "submit-implementation requires a committed production change",
    )
    _ensure(
        all(_path_in_scope(path, scope) for path in implementation_paths),
        "implementation-scope-drift",
        "submit-implementation may change only approved files: %s"
        % ", ".join(implementation_paths),
    )
    state["artifacts"]["implementation_report"] = _record_artifact(
        root, config, args.issue, "implementation_report", args.artifact
    )
    state["validation"] = None
    state["final_review_ready"] = False
    state["draft_pr"] = None
    state["implementation_commit"] = implementation_head
    state["approvals"]["pr"] = None
    state["status"] = "VALIDATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"]}


def command_run_validation(args, root, config):
    """Run the selected bounded validation profile and retain its results in state."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "VALIDATION", "run-validation")
    _require_clean_tree(root, config, "run-validation")

    profile_name = _select_validation_profile(config, args.profile)
    checks = _run_validation_checks(root, config, profile_name)
    _require_clean_tree(root, config, "run-validation after checks")

    all_passed = all(check["passed"] for check in checks)
    state["validation"] = {
        "profile": profile_name,
        "ran_at": _now(),
        "checks": [
            {
                "name": check["name"],
                "command": check["command"],
                "passed": check["passed"],
                "result": check["result"],
            }
            for check in checks
        ],
        "validated_head": _current_head(root, config),
        "passed": all_passed,
    }

    if all_passed:
        state["status"] = "FINAL_REVIEW"
    else:
        state["status"] = "IMPLEMENTATION"
        state["final_review_ready"] = False

    _write_state(root, config, args.issue, state)
    _ensure(
        all_passed,
        "validation-failed",
        "Validation failed for profile %s" % profile_name,
    )

    return {
        "ok": True,
        "status": state["status"],
        "profile": profile_name,
        "checks": state["validation"]["checks"],
    }


def command_review_final(args, root, config):
    """Record the final read-only review only against the current validated HEAD."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "FINAL_REVIEW", "review-final")
    _require_role(config, "reviewer", args.reviewer, "review-final")
    _require_clean_tree(root, config, "review-final")
    _ensure(args.status in REVIEW_STATUSES, "invalid-review-status", "Unknown review status")
    state["artifacts"]["final_review"] = _record_artifact(
        root, config, args.issue, "final_review", args.artifact
    )

    validated = state.get("validation") or {}
    head = _current_head(root, config)

    if args.status == READY:
        _ensure(
            validated.get("passed"),
            "validation-missing",
            "review-final requires a successful validation run",
        )
        _ensure(
            validated.get("validated_head") == head,
            "validation-stale",
            "HEAD changed after validation; rerun submit-implementation and run-validation",
        )
        state["final_review_ready"] = True
    else:
        state["status"] = "IMPLEMENTATION"
        state["final_review_ready"] = False
        state["validation"] = None

    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "review_status": args.status}


def command_create_draft_pr(args, root, config):
    """Reconcile the target branch, then publish a reviewed draft PR."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "FINAL_REVIEW", "create-draft-pr")
    _ensure(
        state.get("final_review_ready"),
        "final-review-not-ready",
        "create-draft-pr requires a READY_FOR_HUMAN_APPROVAL final review",
    )
    validated = state.get("validation") or {}
    head = _current_head(root, config)
    _ensure(
        validated.get("validated_head") == head,
        "validation-stale",
        "HEAD changed after validation; rerun submit-implementation and run-validation",
    )
    body_path = _resolve_file(root, args.body_file)
    _validate_pr_body(body_path)
    _require_single_commit(root, config, state["base_head"], head, "create-draft-pr")
    if not args.skip_github:
        _require_clean_tree(root, config, "create-draft-pr")
        _git_fetch_target(root, config)
        latest_target = _run_checked(
            _git_command(config, "rev-parse", "origin/%s" % config["target_base"]),
            _effective_limits(config, "git"),
            root,
            "git-target-head-failed",
            "unable to read fetched target branch",
        )["stdout_text"].strip()
        previous_target = state.get("target_head")
        if previous_target is None:
            state["target_head"] = latest_target
            _write_state(root, config, args.issue, state)
        elif previous_target != latest_target:
            rebase = _run_bounded(
                _git_command(config, "rebase", "origin/%s" % config["target_base"]),
                _effective_limits(config, "git"),
                root,
            )
            result = rebase["result"]
            _ensure(
                result.get("outcome") == "success" and result.get("exit_code") == 0,
                "rebase-required-human-intervention",
                "target advanced and rebase did not complete cleanly; resolve only within approved scope",
            )
            _require_clean_tree(root, config, "post-rebase")
            state["target_head"] = latest_target
            state["validation"] = None
            state["final_review_ready"] = False
            state["status"] = "IMPLEMENTATION"
            _write_state(root, config, args.issue, state)
            _raise(
                "rebase-requires-review",
                "target advanced; rebase completed, so rerun validation and final review before publication",
            )
        _git_ancestor(root, config, latest_target, head, "create-draft-pr")

    github_limits = _effective_limits(config, "github")
    command = _github_command(
        config,
        "pr",
        "create",
        "--draft",
        "--base",
        config["target_base"],
        "--title",
        args.title,
        "--body-file",
        str(body_path),
    )
    if args.head:
        command.extend(["--head", args.head])

    publication = {"command": [shlex.join(command)], "executed": not args.skip_github}
    if not args.skip_github:
        completed = _run_checked(
            command,
            github_limits,
            root,
            "draft-pr-failed",
            "unable to create draft PR",
        )
        publication["stdout"] = completed["stdout_text"].strip()

    state["draft_pr"] = {
        "created_at": _now(),
        "title": args.title,
        "body_file": _relative(body_path, root),
        "publication": publication,
    }
    state["status"] = "WAITING_FOR_PR_HUMAN_APPROVAL"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "draft_pr": state["draft_pr"]}


def command_approve_pr(args, root, config):
    """Record the final local approval gate before normal GitHub review and merge."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PR_HUMAN_APPROVAL", "approve-pr")
    _require_confirmation(config, state, "pr", args.confirm, args.by)
    state["status"] = "PR_APPROVED"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "approved_by": args.by}


def command_reject_pr(args, root, config):
    """Return a rejected draft PR to implementation and invalidate its review."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PR_HUMAN_APPROVAL", "reject-pr")
    state["approvals"]["pr"] = None
    state["final_review_ready"] = False
    state["validation"] = None
    state["status"] = "IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


# ---------- argparse ----------


def _add_issue(parser):
    parser.add_argument("issue", type=int)


def _add_root(parser):
    parser.add_argument("--root", default=".")


def _add_artifact(parser):
    parser.add_argument("--artifact", required=True)


def _add_review(parser):
    parser.add_argument("--status", choices=REVIEW_STATUSES, required=True)


def _add_human(parser, include_reason=False):
    parser.add_argument("--by", required=True)
    if include_reason:
        parser.add_argument("--reason", required=True)
    else:
        parser.add_argument("--confirm", required=True)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    init = subparsers.add_parser("init")
    _add_root(init)
    _add_issue(init)

    status = subparsers.add_parser("status")
    _add_root(status)
    _add_issue(status)

    submit_plan = subparsers.add_parser("submit-plan")
    _add_root(submit_plan)
    _add_issue(submit_plan)
    _add_artifact(submit_plan)
    submit_plan.add_argument("--agent", required=True)
    submit_plan.add_argument("--scope", action="append", required=True)

    review_plan = subparsers.add_parser("review-plan")
    _add_root(review_plan)
    _add_issue(review_plan)
    _add_artifact(review_plan)
    _add_review(review_plan)
    review_plan.add_argument("--reviewer", required=True)

    approve_plan = subparsers.add_parser("approve-plan")
    _add_root(approve_plan)
    _add_issue(approve_plan)
    _add_human(approve_plan)

    reject_plan = subparsers.add_parser("reject-plan")
    _add_root(reject_plan)
    _add_issue(reject_plan)
    _add_human(reject_plan, include_reason=True)

    submit_tests = subparsers.add_parser("submit-tests")
    _add_root(submit_tests)
    _add_issue(submit_tests)
    _add_artifact(submit_tests)
    submit_tests.add_argument("--agent", required=True)
    submit_tests.add_argument("--failure-command", required=True)
    submit_tests.add_argument("--failure-contains", required=True)

    review_tests = subparsers.add_parser("review-tests")
    _add_root(review_tests)
    _add_issue(review_tests)
    _add_artifact(review_tests)
    _add_review(review_tests)
    review_tests.add_argument("--reviewer", required=True)

    approve_tests = subparsers.add_parser("approve-tests")
    _add_root(approve_tests)
    _add_issue(approve_tests)
    _add_human(approve_tests)

    reject_tests = subparsers.add_parser("reject-tests")
    _add_root(reject_tests)
    _add_issue(reject_tests)
    _add_human(reject_tests, include_reason=True)

    submit_implementation = subparsers.add_parser("submit-implementation")
    _add_root(submit_implementation)
    _add_issue(submit_implementation)
    _add_artifact(submit_implementation)
    submit_implementation.add_argument("--agent", required=True)

    run_validation = subparsers.add_parser("run-validation")
    _add_root(run_validation)
    _add_issue(run_validation)
    run_validation.add_argument("--profile", required=True)

    review_final = subparsers.add_parser("review-final")
    _add_root(review_final)
    _add_issue(review_final)
    _add_artifact(review_final)
    _add_review(review_final)
    review_final.add_argument("--reviewer", required=True)

    create_draft_pr = subparsers.add_parser("create-draft-pr")
    _add_root(create_draft_pr)
    _add_issue(create_draft_pr)
    create_draft_pr.add_argument("--title", required=True)
    create_draft_pr.add_argument("--body-file", required=True)
    create_draft_pr.add_argument("--head")
    create_draft_pr.add_argument("--skip-github", action="store_true")

    approve_pr = subparsers.add_parser("approve-pr")
    _add_root(approve_pr)
    _add_issue(approve_pr)
    _add_human(approve_pr)

    reject_pr = subparsers.add_parser("reject-pr")
    _add_root(reject_pr)
    _add_issue(reject_pr)
    _add_human(reject_pr, include_reason=True)

    return parser


COMMANDS = {
    "init": command_init,
    "status": command_status,
    "submit-plan": command_submit_plan,
    "review-plan": command_review_plan,
    "approve-plan": command_approve_plan,
    "reject-plan": command_reject_plan,
    "submit-tests": command_submit_tests,
    "review-tests": command_review_tests,
    "approve-tests": command_approve_tests,
    "reject-tests": command_reject_tests,
    "submit-implementation": command_submit_implementation,
    "run-validation": command_run_validation,
    "review-final": command_review_final,
    "create-draft-pr": command_create_draft_pr,
    "approve-pr": command_approve_pr,
    "reject-pr": command_reject_pr,
}


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    root = pathlib.Path(args.root).resolve()

    try:
        config = _load_config(root)
        payload = COMMANDS[args.command](args, root, config)
        _emit(payload)
        return 0
    except WorkflowError as error:
        _emit({"ok": False, "error": {"code": error.code, "message": error.message}})
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
