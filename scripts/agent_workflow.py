#!/usr/bin/env python3
"""Minimal skill-driven workflow orchestration for ChessEcho issues."""

import argparse
import base64
import datetime as dt
import errno
import hashlib
import json
import os
import pathlib
import re
import shlex
import tempfile
import uuid

if __package__:
    from . import workflow_supervisor
else:
    import workflow_supervisor

STATE_FORMAT = "chess-echo-skill-workflow-state-v1"
CONFIG_FORMAT = "chess-echo-skill-workflow-config-v1"

READY = "READY_FOR_HUMAN_APPROVAL"
REVISION = "NEEDS_REVISION"
REVIEW_STATUSES = (READY, REVISION)
DEFAULT_IMPLEMENTATION_CONFIRMATION = "implementation_approved"
LOCAL_ACKNOWLEDGMENT_KIND = "self-attested-local-acknowledgment"

STATUS_SEQUENCE = (
    "PLANNING",
    "PLAN_REVIEW",
    "WAITING_FOR_PLAN_HUMAN_APPROVAL",
    "TEST_IMPLEMENTATION",
    "TEST_REVIEW",
    "WAITING_FOR_TEST_HUMAN_APPROVAL",
    "IMPLEMENTATION",
    "VALIDATION",
    "IMPLEMENTATION_REVIEW",
    "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
    "DRAFT_PR_CREATION",
    "WORKFLOW_COMPLETED",
)

ARTIFACT_FILES = {
    "plan": "plan.md",
    "plan_review": "plan-review.md",
    "test_report": "test-report.md",
    "test_review": "test-review.md",
    "implementation_report": "implementation-report.md",
    "implementation_review": "implementation-review.md",
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
    """Persist a complete JSON document with an atomic same-directory replace."""
    path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    temporary_path = None
    replaced = False
    try:
        descriptor, temporary_path = tempfile.mkstemp(
            prefix=".%s." % path.name,
            suffix=".tmp",
            dir=str(path.parent),
        )
        with os.fdopen(descriptor, "w", encoding="utf-8") as temporary_file:
            temporary_file.write(serialized)
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        os.replace(temporary_path, path)
        replaced = True
        temporary_path = None

        # Directory fsync is not available on every supported filesystem.
        try:
            directory_fd = os.open(
                str(path.parent),
                getattr(os, "O_DIRECTORY", 0),
            )
        except OSError as error:
            if error.errno not in (
                errno.EINVAL,
                errno.ENOTSUP,
                errno.EOPNOTSUPP,
                errno.ENOSYS,
            ):
                raise
        else:
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except OSError as error:
        if not replaced and temporary_path:
            try:
                os.unlink(temporary_path)
            except FileNotFoundError:
                pass
        _raise(
            "persistence-failed",
            "unable to atomically persist JSON at %s: %s" % (path, error),
        )
    finally:
        if not replaced and temporary_path:
            try:
                os.unlink(temporary_path)
            except FileNotFoundError:
                pass


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
    for key in ("plan", "tests"):
        _ensure(
            isinstance(approvals.get(key), str) and approvals[key],
            "invalid-config",
            "workflow.approvals.%s must be a non-empty string" % key,
        )
    if "implementation" in approvals:
        _ensure(
            isinstance(approvals.get("implementation"), str) and approvals["implementation"],
            "invalid-config",
            "workflow.approvals.implementation must be a non-empty string",
        )
    else:
        approvals["implementation"] = DEFAULT_IMPLEMENTATION_CONFIRMATION

    _ensure(
        workflow.get("approval_mechanism", LOCAL_ACKNOWLEDGMENT_KIND)
        == LOCAL_ACKNOWLEDGMENT_KIND,
        "invalid-config",
        "workflow.approval_mechanism must be %s" % LOCAL_ACKNOWLEDGMENT_KIND,
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
        "implementation_review",
    ):
        state["artifacts"].pop(key, None)
    state["approvals"]["tests"] = None
    state["approvals"]["implementation"] = None
    state["validation"] = None
    state["implementation_candidate"] = None
    state["implementation_review_ready"] = False
    state["draft_pr"] = None


def _test_reopen_active(state):
    """Return whether the latest approved-test recovery path is still active."""
    reopenings = state.get("test_reopenings") or []
    return bool(reopenings and reopenings[-1].get("active"))


def _clear_post_tests(state):
    """Invalidate implementation and publication state after revised tests."""
    for key in ("implementation_report", "implementation_review"):
        state["artifacts"].pop(key, None)
    state["approvals"]["implementation"] = None
    state["validation"] = None
    state["implementation_candidate"] = None
    state["implementation_review_ready"] = False
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


def _git_status_all(root, config):
    completed = _run_checked(
        _git_command(config, "status", "--porcelain", "--untracked-files=all"),
        _effective_limits(config, "git"),
        root,
        "git-status-failed",
        "unable to inspect working tree",
    )
    return [line for line in completed["stdout_text"].splitlines() if line]


def _status_path(line):
    return line[3:] if len(line) >= 4 else line


def _require_no_uncommitted_test_changes(root, config, context):
    test_paths = [
        _status_path(line)
        for line in _git_status_all(root, config)
        if _is_test_file(_status_path(line))
    ]
    _ensure(
        not test_paths,
        "test-worktree-dirty",
        "%s requires test changes to be committed before submission: %s"
        % (context, ", ".join(test_paths)),
    )


def _require_clean_index(root, config, context):
    completed = _run_bounded(
        _git_command(config, "diff", "--cached", "--quiet"),
        _effective_limits(config, "git"),
        root,
    )
    result = completed["result"]
    _ensure(
        result.get("outcome") == "success" and result.get("exit_code") == 0,
        "git-index-dirty",
        "%s requires no staged changes" % context,
    )


def _git_diff_names(root, config, revision):
    completed = _run_checked(
        _git_command(config, "diff", "--name-only", revision),
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to inspect commit changes",
    )
    return [line.strip() for line in completed["stdout_text"].splitlines() if line.strip()]


def _git_candidate_diff(root, config, revision):
    """Return the deterministic Git representation of the working-tree candidate."""
    _run_checked(
        _git_command(config, "rev-parse", "--verify", "%s^{commit}" % revision),
        _effective_limits(config, "git"),
        root,
        "git-revision-failed",
        "unable to resolve candidate base revision",
    )
    tracked = _run_checked(
        _git_command(config, "diff", "--binary", revision, "--"),
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to compute candidate diff",
    )["stdout_text"]

    status = _run_checked(
        _git_command(config, "status", "--porcelain", "--untracked-files=all"),
        _effective_limits(config, "git"),
        root,
        "git-status-failed",
        "unable to inspect candidate files",
    )["stdout_text"]
    untracked = [
        line[3:]
        for line in status.splitlines()
        if line.startswith("?? ")
    ]
    additions = []
    for path in sorted(untracked):
        completed = _run_bounded(
            _git_command(config, "diff", "--binary", "--no-index", "--", "/dev/null", path),
            _effective_limits(config, "git"),
            root,
        )
        result = completed["result"]
        _ensure(
            result.get("exit_code") in (0, 1)
            and result.get("outcome") in ("success", "nonzero-exit"),
            "git-diff-failed",
            "unable to compute candidate diff for %s" % path,
        )
        additions.append(completed["stdout_text"])
    return tracked + "".join(additions)


def _git_candidate_names(root, config, revision):
    """Return tracked and untracked paths represented by the candidate."""
    names = set(_git_diff_names(root, config, revision))
    status = _run_checked(
        _git_command(config, "status", "--porcelain", "--untracked-files=all"),
        _effective_limits(config, "git"),
        root,
        "git-status-failed",
        "unable to inspect candidate files",
    )["stdout_text"]
    names.update(
        line[3:]
        for line in status.splitlines()
        if line.startswith("?? ")
    )
    return sorted(names)


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
    """Require one final implementation commit relative to the target base."""
    _ensure(
        _git_commit_count(root, config, base, head, context) == 1,
        "invalid-implementation-topology",
        "%s requires exactly one commit relative to the target base" % context,
    )


def _commit_parent(root, config, revision):
    completed = _run_checked(
        _git_command(config, "rev-parse", "%s^" % revision),
        _effective_limits(config, "git"),
        root,
        "git-parent-failed",
        "unable to read parent for %s" % revision,
    )
    return completed["stdout_text"].strip()


def _require_direct_child(root, config, parent, child, context):
    actual_parent = _commit_parent(root, config, child)
    _ensure(
        actual_parent == parent,
        "invalid-implementation-topology",
        "%s requires %s to be the direct parent of %s" % (context, parent, child),
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


def _remote_exists(root, config):
    completed = _run_bounded(
        _git_command(config, "remote", "get-url", "origin"),
        _effective_limits(config, "git"),
        root,
    )
    result = completed["result"]
    return result.get("outcome") == "success" and result.get("exit_code") == 0


def _resolve_target_head(root, config, fetch=False):
    if fetch and _remote_exists(root, config):
        _git_fetch_target(root, config)
    target = config["target_base"]
    for ref in ("origin/%s" % target, target):
        completed = _run_bounded(
            _git_command(config, "rev-parse", "--verify", "%s^{commit}" % ref),
            _effective_limits(config, "git"),
            root,
        )
        result = completed["result"]
        if result.get("outcome") == "success" and result.get("exit_code") == 0:
            return completed["stdout_text"].strip()
    _raise("target-head-unresolved", "unable to resolve target branch %s" % target)


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


def _state_target_head(state):
    """Return the recorded publication base; never fall back to diagnostic heads."""
    target_head = state.get("target_head")
    _ensure(target_head, "missing-target-head", "Workflow state has no recorded target_head")
    return target_head


def _require_target_fresh(root, config, state, context):
    """Require the PR target branch to remain at the recorded target_head."""
    recorded = _state_target_head(state)
    latest = _resolve_target_head(root, config, fetch=True)
    _ensure(
        latest == recorded,
        "target-advanced",
        "%s requires target branch to remain at recorded target_head" % context,
    )
    return latest


def _require_publication_topology(root, config, state, head, context):
    """Require a final one-commit publication rooted at the current target."""
    target_head = _require_target_fresh(root, config, state, context)
    _git_ancestor(root, config, target_head, head, context)
    _require_direct_child(root, config, target_head, head, context)
    _require_single_commit(root, config, target_head, head, context)
    changed = _git_diff_names(root, config, "%s..%s" % (target_head, head))
    scope = state.get("approved_scope") or []
    _ensure(scope, "missing-approved-scope", "%s requires approved plan scope" % context)
    _ensure(
        all(_path_in_scope(path, scope) for path in changed),
        "implementation-scope-drift",
        "%s may change only approved files: %s" % (context, ", ".join(changed)),
    )
    return target_head


def _tests_not_applicable(state):
    return state.get("test_implementation_status") == "NOT_APPLICABLE"


def _approved_test_paths(root, config, state, context):
    """Return concrete approved test paths while preserving applicability invariants."""
    applicability = state.get("test_implementation_status")
    _ensure(
        applicability in ("REQUIRED", "NOT_APPLICABLE"),
        "invalid-test-applicability",
        "%s requires valid test implementation applicability" % context,
    )
    scope = state.get("approved_scope")
    _ensure(
        isinstance(scope, list) and scope and all(isinstance(path, str) for path in scope),
        "invalid-approved-test-scope",
        "%s requires a valid approved test scope" % context,
    )
    if applicability == "NOT_APPLICABLE":
        return []

    test_commit = state.get("test_commit")
    _ensure(test_commit, "missing-test-commit", "%s requires test_commit" % context)
    target_head = state.get("target_head")
    if not target_head:
        approved_candidate = _commit_parent(root, config, test_commit)
        target_head = _commit_parent(root, config, approved_candidate)
    test_paths = _git_diff_names(
        root, config, "%s..%s" % (target_head, test_commit)
    )
    _require_test_only(test_paths, scope, context)
    return sorted(test_paths)


def _git_diff_text(root, config, base, head, paths=None):
    command = _git_command(config, "diff", "--binary", base, head, "--")
    if paths:
        command.extend(paths)
    completed = _run_checked(
        command,
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to compute diff for %s..%s" % (base, head),
    )
    return completed["stdout_text"]


def _candidate_identity(test_commit, candidate_diff, candidate_paths):
    encoded = candidate_diff.encode("utf-8")
    return {
        "test_commit": test_commit,
        "candidate_paths": sorted(candidate_paths),
        "candidate_diff_sha256": hashlib.sha256(encoded).hexdigest(),
        "candidate_diff_bytes": len(encoded),
    }


def _git_index_diff(root, config, revision, paths=None):
    command = _git_command(config, "diff", "--cached", "--binary", revision, "--")
    if paths:
        command.extend(paths)
    return _run_checked(
        command,
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to compute staged diff",
    )["stdout_text"]


def _git_index_names(root, config, revision):
    return [
        line.strip()
        for line in _run_checked(
            _git_command(config, "diff", "--cached", "--name-only", revision),
            _effective_limits(config, "git"),
            root,
            "git-diff-failed",
            "unable to inspect staged paths",
        )["stdout_text"].splitlines()
        if line.strip()
    ]


def _git_commit_subject(root, config, revision):
    return _run_checked(
        _git_command(config, "show", "-s", "--format=%s", revision),
        _effective_limits(config, "git"),
        root,
        "git-commit-subject-failed",
        "unable to read implementation commit subject",
    )["stdout_text"].strip()


def _target_change_paths(root, config, old_target, new_target):
    return _git_diff_names(root, config, "%s..%s" % (old_target, new_target))


def _reconciliation_supported_status(state):
    return state["status"] in (
        "VALIDATION",
        "IMPLEMENTATION_REVIEW",
        "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
    )


def _best_effort_rebase_abort(root, config):
    _run_bounded(
        _git_command(config, "rebase", "--abort"),
        _effective_limits(config, "git"),
        root,
    )


def _restore_candidate_worktree(root, config, candidate_commit, test_commit):
    _run_checked(
        _git_command(config, "reset", "--hard", candidate_commit),
        _effective_limits(config, "git"),
        root,
        "git-reset-failed",
        "unable to restore candidate commit after failed reconciliation",
    )
    _run_checked(
        _git_command(config, "reset", "--mixed", test_commit),
        _effective_limits(config, "git"),
        root,
        "git-reset-failed",
        "unable to restore implementation candidate after failed reconciliation",
    )


def _reconcile_candidate_artifacts(root, config, state, old_target, new_target, context):
    scope = state.get("approved_scope")
    _ensure(
        isinstance(scope, list) and scope and all(isinstance(path, str) for path in scope),
        "missing-approved-scope",
        "%s requires approved plan scope" % context,
    )
    _ensure(
        state["approvals"].get("plan") is not None and state["approvals"].get("tests") is not None,
        "missing-approval",
        "%s requires approved plan and tests" % context,
    )

    test_commit = state.get("test_commit")
    _ensure(test_commit, "missing-test-commit", "%s requires test_commit" % context)
    _ensure(
        _current_head(root, config) == test_commit,
        "implementation-commit-not-allowed",
        "%s requires HEAD to match approved test_commit" % context,
    )
    _require_clean_index(root, config, context)
    _git_ancestor(root, config, old_target, test_commit, context)

    applicability = state.get("test_implementation_status")
    test_paths = _approved_test_paths(root, config, state, context)
    if applicability == "REQUIRED":
        _require_test_only(
            _git_diff_names(root, config, "%s..%s" % (old_target, test_commit)),
            scope,
            context,
        )
    else:
        _ensure(
            applicability == "NOT_APPLICABLE",
            "invalid-test-applicability",
            "%s requires valid test implementation applicability" % context,
        )
        _ensure(
            state.get("test_implementation_reason"),
            "missing-test-applicability-reason",
            "%s requires approved NOT_APPLICABLE rationale" % context,
        )

    current_candidate = _require_implementation_candidate_matches(root, config, state, context)
    _ensure(
        all(not _is_test_file(path) for path in current_candidate["candidate_paths"]),
        "implementation-test-modification",
        "%s may change only approved production files: %s"
        % (context, ", ".join(current_candidate["candidate_paths"])),
    )
    _require_candidate_scope(current_candidate["candidate_paths"], scope, context)

    target_paths = _target_change_paths(root, config, old_target, new_target)
    protected_paths = sorted(set(test_paths).union(current_candidate["candidate_paths"]))
    overlap = sorted(set(target_paths).intersection(protected_paths))
    _ensure(
        not overlap,
        "artifact-validity-undetermined",
        "%s cannot reconcile overlapping target changes: %s" % (context, ", ".join(overlap)),
    )

    test_diff_before = _git_diff_text(root, config, old_target, test_commit, test_paths)
    candidate_before = _candidate_identity(
        test_commit,
        current_candidate["candidate_diff"],
        current_candidate["candidate_paths"],
    )
    implementation_candidate = state["implementation_candidate"]
    preserved_accepted_at = implementation_candidate.get("accepted_at")

    _run_checked(
        _git_command(config, "add", "-A"),
        _effective_limits(config, "git"),
        root,
        "git-add-failed",
        "unable to stage implementation candidate for reconciliation",
    )
    _run_checked(
        _git_command(config, "commit", "-qm", "workflow: reconcile implementation candidate"),
        _effective_limits(config, "git"),
        root,
        "git-commit-failed",
        "unable to checkpoint implementation candidate for reconciliation",
    )
    original_candidate_commit = _current_head(root, config)

    try:
        _run_checked(
            _git_command(config, "rebase", "--onto", new_target, old_target, original_candidate_commit),
            _effective_limits(config, "git"),
            root,
            "git-rebase-failed",
            "%s could not reconcile candidate onto the new target" % context,
        )
        rebased_candidate_commit = _current_head(root, config)
        new_test_commit = _commit_parent(root, config, rebased_candidate_commit)
        _git_ancestor(root, config, new_target, new_test_commit, context)

        test_diff_after = _git_diff_text(root, config, new_target, new_test_commit, test_paths)
        _ensure(
            test_diff_before == test_diff_after,
            "artifact-validity-undetermined",
            "%s could not prove approved test-boundary equivalence on the new target" % context,
        )

        _run_checked(
            _git_command(config, "reset", "--mixed", new_test_commit),
            _effective_limits(config, "git"),
            root,
            "git-reset-failed",
            "unable to restore uncommitted candidate after reconciliation",
        )
        current_diff_after = _git_candidate_diff(root, config, new_test_commit)
        current_paths_after = _git_candidate_names(root, config, new_test_commit)
        _ensure(
            current_paths_after,
            "missing-implementation-commit",
            "%s must contain at least one production change" % context,
        )
        _ensure(
            all(not _is_test_file(path) for path in current_paths_after),
            "implementation-test-modification",
            "%s may change only approved production files: %s"
            % (context, ", ".join(current_paths_after)),
        )
        _require_candidate_scope(current_paths_after, scope, context)
        _ensure(
            current_diff_after == current_candidate["candidate_diff"]
            and current_paths_after == current_candidate["candidate_paths"],
            "artifact-validity-undetermined",
            "%s could not prove implementation candidate equivalence on the new target" % context,
        )
        _require_clean_index(root, config, "post-reconcile-candidate")

        candidate_after = _candidate_identity(
            new_test_commit,
            current_diff_after,
            current_paths_after,
        )
        return {
            "test_commit": new_test_commit,
            "candidate": {
                "test_commit": new_test_commit,
                "candidate_diff": current_diff_after,
                "candidate_paths": current_paths_after,
                "commit_subject": implementation_candidate.get("commit_subject"),
                "accepted_at": preserved_accepted_at,
            },
            "candidate_before": candidate_before,
            "candidate_after": candidate_after,
            "target_paths": target_paths,
            "target_overlap_paths": overlap,
            "test_paths": test_paths,
            "test_diff_sha256_before": hashlib.sha256(test_diff_before.encode("utf-8")).hexdigest(),
            "test_diff_sha256_after": hashlib.sha256(test_diff_after.encode("utf-8")).hexdigest(),
            "reconciliation_method": "rebase",
        }
    except WorkflowError:
        _best_effort_rebase_abort(root, config)
        _restore_candidate_worktree(root, config, original_candidate_commit, test_commit)
        raise


def _require_implementation_candidate_matches(root, config, state, context):
    """Require the current Git candidate to match the accepted implementation candidate."""
    accepted = state.get("implementation_candidate")
    _ensure(
        isinstance(accepted, dict),
        "missing-implementation-candidate",
        "%s requires an accepted implementation candidate" % context,
    )
    test_commit = state.get("test_commit")
    _ensure(test_commit, "missing-test-commit", "%s requires test_commit" % context)
    _ensure(
        accepted.get("test_commit") == test_commit,
        "implementation-candidate-mismatch",
        "%s accepted candidate test_commit does not match workflow test_commit" % context,
    )
    accepted_paths = accepted.get("candidate_paths")
    _ensure(
        isinstance(accepted.get("candidate_diff"), str)
        and isinstance(accepted_paths, list)
        and all(isinstance(path, str) for path in accepted_paths),
        "invalid-implementation-candidate",
        "%s accepted candidate is malformed" % context,
    )
    commit_subject = _validate_implementation_commit_subject(
        accepted.get("commit_subject"), state["issue"]
    )

    current_diff = _git_candidate_diff(root, config, test_commit)
    current_paths = _git_candidate_names(root, config, test_commit)
    _ensure(
        all(not _is_test_file(path) for path in current_paths),
        "tests-modified-after-approval",
        "%s requires approved tests to remain unchanged" % context,
    )
    _ensure(
        current_diff == accepted["candidate_diff"]
        and current_paths == sorted(accepted_paths),
        "implementation-candidate-mismatch",
        "%s current implementation candidate differs from accepted candidate" % context,
    )
    return {
        "candidate_diff": current_diff,
        "candidate_paths": current_paths,
        "commit_subject": commit_subject,
    }


def _require_clean_tree(root, config, context):
    """Require a clean worktree before inspecting or publishing committed state."""
    _ensure(
        not _git_status(root, config),
        "git-worktree-dirty",
        "%s requires a clean Git worktree" % context,
    )


def _reanchor_target_artifacts(root, config, state, old_target, new_target, context):
    """Validate that pre-implementation artifacts remain safe after re-anchoring."""
    test_commit = state.get("test_commit")
    if not test_commit:
        return {"test_commit": None, "validated": []}

    _git_ancestor(root, config, old_target, test_commit, context)
    test_paths = _git_diff_names(root, config, "%s..%s" % (old_target, test_commit))
    scope = state.get("approved_scope") or []
    _require_test_only(test_paths, scope, context)
    try:
        _git_ancestor(root, config, new_target, test_commit, context)
    except WorkflowError as error:
        _ensure(
            error.code == "invalid-git-ancestry",
            error.code,
            error.message,
        )
        target_paths = _git_diff_names(root, config, "%s..%s" % (old_target, new_target))
        _ensure(
            not set(target_paths).intersection(test_paths),
            "invalid-artifact-scope",
            "%s cannot re-anchor a test artifact that overlaps target changes" % context,
        )
        _ensure(
            _current_head(root, config) == test_commit,
            "artifact-validity-undetermined",
            "%s requires HEAD to match the test artifact before re-anchoring" % context,
        )
        _run_checked(
            _git_command(config, "rebase", "--onto", new_target, old_target, test_commit),
            _effective_limits(config, "git"),
            root,
            "git-rebase-failed",
            "%s could not preserve the test artifact on the new target" % context,
        )
        test_commit = _current_head(root, config)
    _ensure(
        not state.get("implementation_candidate")
        and not state.get("implementation_commit")
        and not state.get("draft_pr"),
        "artifact-validity-undetermined",
        "%s cannot re-anchor after implementation artifacts exist" % context,
    )
    return {"test_commit": test_commit, "validated": ["test_commit", *test_paths]}


def command_reanchor_target(args, root, config):
    """Re-anchor a run to a fetched descendant target without resetting its plan."""
    state = _read_state(root, config, args.issue)
    if state["status"] in (
        "IMPLEMENTATION",
        "VALIDATION",
        "IMPLEMENTATION_REVIEW",
        "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
        "DRAFT_PR_CREATION",
        "WORKFLOW_COMPLETED",
    ):
        _raise(
            "artifact-validity-undetermined",
            "reanchor-target cannot establish downstream artifact validity in status %s"
            % state["status"],
        )
    allowed = (
        "PLANNING",
        "PLAN_REVIEW",
        "WAITING_FOR_PLAN_HUMAN_APPROVAL",
        "TEST_IMPLEMENTATION",
        "TEST_REVIEW",
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
    )
    _ensure(
        state["status"] in allowed,
        "invalid-transition",
        "reanchor-target is not safe in status %s" % state["status"],
    )
    _ensure(args.by.strip(), "missing-requester", "reanchor-target requires a requester")
    _require_clean_tree(root, config, "reanchor-target")
    old_target = _state_target_head(state)
    new_target = _resolve_target_head(root, config, fetch=True)
    _ensure(
        new_target != old_target,
        "target-not-advanced",
        "reanchor-target requires origin/%s to advance" % config["target_base"],
    )
    _git_ancestor(root, config, old_target, new_target, "reanchor-target")
    validation = _reanchor_target_artifacts(
        root, config, state, old_target, new_target, "reanchor-target"
    )
    if validation["test_commit"]:
        state["test_commit"] = validation["test_commit"]

    provenance = {
        "previous_target_head": old_target,
        "new_target_head": new_target,
        "target_base": config["target_base"],
        "remote_ref": "origin/%s" % config["target_base"],
        "requested_by": args.by,
        "requested_at": _now(),
        "validated_artifacts": validation["validated"],
    }
    state.setdefault("target_reanchors", []).append(provenance)
    state["target_head"] = new_target
    state["base_head"] = new_target
    _write_state(root, config, args.issue, state)
    return {
        "ok": True,
        "status": state["status"],
        "previous_target_head": old_target,
        "new_target_head": new_target,
        "provenance": provenance,
    }


def command_reconcile_candidate(args, root, config):
    """Reconcile an accepted uncommitted candidate onto a newer descendant target."""
    state = _read_state(root, config, args.issue)
    _ensure(
        _reconciliation_supported_status(state),
        "invalid-transition",
        "reconcile-candidate is not safe in status %s" % state["status"],
    )
    _ensure(args.by.strip(), "missing-requester", "reconcile-candidate requires a requester")
    _ensure(
        not state.get("implementation_commit") and not state.get("draft_pr"),
        "invalid-transition",
        "reconcile-candidate is not allowed after publication artifacts exist",
    )

    old_target = _state_target_head(state)
    new_target = _resolve_target_head(root, config, fetch=True)
    _ensure(
        new_target != old_target,
        "target-not-advanced",
        "reconcile-candidate requires origin/%s to advance" % config["target_base"],
    )
    _git_ancestor(root, config, old_target, new_target, "reconcile-candidate")

    status_before = state["status"]
    validation = _reconcile_candidate_artifacts(
        root, config, state, old_target, new_target, "reconcile-candidate"
    )

    state["test_commit"] = validation["test_commit"]
    state["implementation_candidate"] = validation["candidate"]
    state["target_head"] = new_target
    state["base_head"] = new_target
    state["validation"] = None
    state["implementation_review_ready"] = False
    state["approvals"]["implementation"] = None
    state["status"] = "VALIDATION"

    provenance = {
        "previous_target_head": old_target,
        "new_target_head": new_target,
        "target_base": config["target_base"],
        "remote_ref": "origin/%s" % config["target_base"],
        "requested_by": args.by,
        "requested_at": _now(),
        "status_before": status_before,
        "status_after": state["status"],
        "reconciliation_method": validation["reconciliation_method"],
        "target_paths": validation["target_paths"],
        "target_overlap_paths": validation["target_overlap_paths"],
        "test_paths": validation["test_paths"],
        "test_implementation_status": state.get("test_implementation_status"),
        "test_diff_sha256_before": validation["test_diff_sha256_before"],
        "test_diff_sha256_after": validation["test_diff_sha256_after"],
        "candidate_before": validation["candidate_before"],
        "candidate_after": validation["candidate_after"],
        "validated_invariants": {
            "strict_descendant_target": True,
            "approved_scope_preserved": True,
            "approved_tests_preserved": True,
            "candidate_equivalence_proved": True,
            "publication_artifacts_absent": True,
        },
    }
    state.setdefault("candidate_reconciliations", []).append(provenance)
    _write_state(root, config, args.issue, state)
    return {
        "ok": True,
        "status": state["status"],
        "previous_target_head": old_target,
        "new_target_head": new_target,
        "provenance": provenance,
    }


def _is_test_file(path):
    return (
        path == "src/test"
        or path.startswith("src/test/")
        or path.startswith("frontend/") and (
            "/__tests__/" in path
            or path.endswith((".test.ts", ".test.tsx", ".test.js", ".test.jsx"))
            or path.endswith((".spec.ts", ".spec.tsx", ".spec.js", ".spec.jsx"))
        )
        or path == "scripts/tests"
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


def _require_candidate_scope(paths, scope, context):
    _ensure(
        all(_path_in_scope(path, scope) for path in paths),
        "implementation-scope-drift",
        "%s may change only approved files: %s" % (context, ", ".join(paths)),
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


def _approval_gate(gate, instruction, command):
    """Describe a local Approval Gate without claiming independent authority."""
    return {
        "name": "Approval Gate",
        "gate": gate,
        "mechanism": LOCAL_ACKNOWLEDGMENT_KIND,
        "independent_authorization": False,
        "message": instruction,
        "approval_command": command,
    }


def _record_local_acknowledgment(config, state, gate, provided, by):
    """Record matching local inputs without authenticating the asserted caller."""
    approvals = config["workflow"]["approvals"]
    expected = approvals.get(gate)
    _ensure(
        provided == expected,
        "approval-confirmation-mismatch",
        "Expected confirmation phrase for %s gate: %s" % (gate, expected),
    )
    acknowledgment = {
        "kind": LOCAL_ACKNOWLEDGMENT_KIND,
        "asserted_by": by,
        "confirmation": provided,
        "recorded_at": _now(),
        "independent_authorization": False,
    }
    state["approvals"][gate] = acknowledgment
    return acknowledgment


def _validate_pr_body(body_path):
    """Require the repository's three-section draft PR body format."""
    body = body_path.read_text(encoding="utf-8")
    headings = re.findall(r"^##\s+(.+)\s*$", body, flags=re.MULTILINE)
    _ensure(
        headings == ["What", "Why", "Testing"],
        "invalid-pr-body-format",
        "Draft PR body must contain exactly ## What, ## Why, and ## Testing in order",
    )


def _validate_implementation_commit_subject(subject, issue):
    """Require a reviewed, descriptive implementation commit subject."""
    _ensure(
        isinstance(subject, str),
        "invalid-implementation-commit-subject",
        "implementation evidence requires commit_subject",
    )
    normalized = subject.strip()
    _ensure(
        normalized,
        "invalid-implementation-commit-subject",
        "implementation commit subject must not be empty",
    )
    _ensure(
        len(normalized) <= 72,
        "invalid-implementation-commit-subject",
        "implementation commit subject must be concise",
    )
    _ensure(
        all(ord(character) >= 32 and ord(character) != 127 for character in normalized),
        "invalid-implementation-commit-subject",
        "implementation commit subject must be a single printable line",
    )
    _ensure(
        re.search(r"[A-Za-z]", normalized),
        "invalid-implementation-commit-subject",
        "implementation commit subject must describe the change",
    )

    compact = re.sub(r"\s+", " ", normalized).lower()
    generic_issue_only = (
        r"(?:(?:implement|implements|implemented|fix|fixes|fixed|resolve|resolves|"
        r"resolved|address|addresses|addressed)\s+)?(?:issue\s+)?#?\d+"
    )
    _ensure(
        not re.fullmatch(generic_issue_only, compact),
        "invalid-implementation-commit-subject",
        "implementation commit subject must describe the change, not only identify issue #%s"
        % issue,
    )
    return normalized


TRANSITION_JOURNAL_FORMAT = "chess-echo-implementation-approval-transition-v1"
TRANSITION_JOURNAL_STATUSES = (
    "pending",
    "committed-but-not-persisted",
    "finalized",
)


def _implementation_transition_journal_path(root, config, issue):
    return _run_root(root, config, issue) / "implementation-approval-transition.json"


def _journal_test_boundary(root, config, state):
    target_head = _state_target_head(state)
    test_commit = state.get("test_commit")
    _ensure(test_commit, "missing-test-commit", "implementation approval requires test_commit")
    test_paths = _approved_test_paths(
        root, config, state, "implementation approval journal"
    )
    test_diff = (
        _git_diff_text(root, config, target_head, test_commit, test_paths)
        if test_paths
        else ""
    )
    return {
        "target_head": target_head,
        "test_commit": test_commit,
        "paths": test_paths,
        "diff_sha256": hashlib.sha256(test_diff.encode("utf-8")).hexdigest(),
        "diff_bytes": len(test_diff.encode("utf-8")),
    }


def _build_implementation_transition_journal(root, config, state, acknowledgment):
    candidate = state.get("implementation_candidate")
    _ensure(
        isinstance(candidate, dict),
        "missing-implementation-candidate",
        "implementation approval requires an accepted implementation candidate",
    )
    test_commit = state.get("test_commit")
    identity = _candidate_identity(
        test_commit,
        candidate["candidate_diff"],
        candidate["candidate_paths"],
    )
    boundary = _journal_test_boundary(root, config, state)
    created_at = _now()
    validation = state.get("validation")
    _ensure(
        isinstance(validation, dict) and validation.get("passed") is True,
        "validation-missing",
        "implementation approval requires successful validation evidence",
    )
    return {
        "format": TRANSITION_JOURNAL_FORMAT,
        "version": 1,
        "transition_id": uuid.uuid4().hex,
        "issue": state["issue"],
        "operation": "approve-implementation",
        "created_at": created_at,
        "from_status": "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
        "to_status": "DRAFT_PR_CREATION",
        "status": "pending",
        "acknowledgment": acknowledgment,
        "implementation_candidate": candidate,
        "candidate_identity": identity,
        "candidate_acceptance": {
            "accepted_at": candidate.get("accepted_at"),
        },
        "reviewed_commit_subject": candidate.get("commit_subject"),
        "target_base": state.get("target_base"),
        "target_head": state.get("target_head"),
        "expected_parent": state.get("target_head"),
        "test_commit": test_commit,
        "approved_scope": state.get("approved_scope"),
        "test_implementation_status": state.get("test_implementation_status"),
        "test_implementation_reason": state.get("test_implementation_reason"),
        "approved_test_boundary": boundary,
        "approvals": {
            "plan": state["approvals"].get("plan"),
            "tests": state["approvals"].get("tests"),
        },
        "artifacts": state.get("artifacts"),
        "validation": validation,
        "evidence": validation,
        "implementation_review_ready": state.get("implementation_review_ready"),
        "authoritative_commit": None,
        "implementation_commit": None,
    }


def _validate_journal_acknowledgment(config, acknowledgment):
    _ensure(
        isinstance(acknowledgment, dict),
        "invalid-implementation-approval-journal",
        "implementation approval journal acknowledgment is malformed",
    )
    expected = config["workflow"]["approvals"]["implementation"]
    _ensure(
        acknowledgment.get("kind") == LOCAL_ACKNOWLEDGMENT_KIND
        and acknowledgment.get("confirmation") == expected
        and acknowledgment.get("independent_authorization") is False
        and isinstance(acknowledgment.get("asserted_by"), str)
        and isinstance(acknowledgment.get("recorded_at"), str),
        "invalid-implementation-approval-journal",
        "implementation approval journal acknowledgment is invalid",
    )


def _validate_implementation_transition_journal(root, config, state, journal, context):
    _ensure(
        journal.get("format") == TRANSITION_JOURNAL_FORMAT
        and journal.get("version") == 1,
        "invalid-implementation-approval-journal",
        "%s requires the supported implementation approval journal format" % context,
    )
    _ensure(
        journal.get("issue") == state.get("issue")
        and journal.get("operation") == "approve-implementation"
        and isinstance(journal.get("transition_id"), str)
        and journal.get("transition_id"),
        "invalid-implementation-approval-journal",
        "%s journal identity does not match the workflow" % context,
    )
    _ensure(
        journal.get("from_status") == "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL"
        and journal.get("to_status") == "DRAFT_PR_CREATION",
        "invalid-implementation-approval-journal",
        "%s journal transition is invalid" % context,
    )
    _ensure(
        journal.get("status") in TRANSITION_JOURNAL_STATUSES,
        "invalid-implementation-approval-journal",
        "%s journal status is invalid" % context,
    )
    _validate_journal_acknowledgment(config, journal.get("acknowledgment"))
    state_acknowledgment = state.get("approvals", {}).get("implementation")
    if state_acknowledgment is not None:
        _ensure(
            state_acknowledgment == journal["acknowledgment"],
            "implementation-approval-journal-mismatch",
            "%s acknowledgment does not match the journal" % context,
        )

    _ensure(
        journal.get("target_base") == state.get("target_base")
        and journal.get("target_head") == state.get("target_head")
        and journal.get("expected_parent") == state.get("target_head")
        and journal.get("test_commit") == state.get("test_commit"),
        "implementation-approval-journal-mismatch",
        "%s target or test boundary does not match the workflow" % context,
    )
    _ensure(
        journal.get("approved_scope") == state.get("approved_scope")
        and journal.get("test_implementation_status")
        == state.get("test_implementation_status")
        and journal.get("test_implementation_reason")
        == state.get("test_implementation_reason"),
        "implementation-approval-journal-mismatch",
        "%s approved scope or applicability does not match the workflow" % context,
    )

    candidate = state.get("implementation_candidate")
    _ensure(
        isinstance(candidate, dict)
        and journal.get("implementation_candidate") == candidate,
        "implementation-approval-journal-mismatch",
        "%s candidate metadata does not match the workflow" % context,
    )
    identity = _candidate_identity(
        state.get("test_commit"),
        candidate.get("candidate_diff"),
        candidate.get("candidate_paths"),
    )
    _ensure(
        journal.get("candidate_identity") == identity,
        "implementation-approval-journal-mismatch",
        "%s candidate identity does not match the existing candidate" % context,
    )
    _ensure(
        journal.get("candidate_acceptance")
        == {"accepted_at": candidate.get("accepted_at")}
        and journal.get("reviewed_commit_subject") == candidate.get("commit_subject"),
        "implementation-approval-journal-mismatch",
        "%s candidate acceptance metadata does not match the workflow" % context,
    )

    boundary = _journal_test_boundary(root, config, state)
    _ensure(
        journal.get("approved_test_boundary") == boundary,
        "implementation-approval-journal-mismatch",
        "%s approved test boundary does not match the workflow" % context,
    )
    _ensure(
        journal.get("approvals")
        == {
            "plan": state["approvals"].get("plan"),
            "tests": state["approvals"].get("tests"),
        }
        and journal.get("artifacts") == state.get("artifacts")
        and journal.get("validation") == state.get("validation")
        and journal.get("evidence") == state.get("validation")
        and journal.get("implementation_review_ready")
        == state.get("implementation_review_ready"),
        "implementation-approval-journal-mismatch",
        "%s evidence or review readiness does not match the workflow" % context,
    )
    _ensure(
        state.get("validation", {}).get("passed") is True
        and state.get("implementation_review_ready") is True,
        "implementation-approval-journal-mismatch",
        "%s requires successful validation and implementation review" % context,
    )

    authoritative_commit = journal.get("authoritative_commit")
    implementation_commit = journal.get("implementation_commit")
    if journal["status"] == "pending":
        _ensure(
            authoritative_commit is None
            and implementation_commit is None
            and state.get("status") == "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
            "implementation-approval-journal-mismatch",
            "%s pending journal has an unexpected final result" % context,
        )
    elif journal["status"] == "committed-but-not-persisted":
        _ensure(
            isinstance(authoritative_commit, str) and authoritative_commit,
            "implementation-approval-journal-mismatch",
            "%s committed journal has no authoritative commit" % context,
        )
        _ensure(
            implementation_commit in (None, authoritative_commit)
            and
            state.get("status")
            in ("WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", "DRAFT_PR_CREATION")
            and (
                state.get("status") == "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL"
                and state.get("implementation_commit") is None
                or state.get("status") == "DRAFT_PR_CREATION"
                and state.get("implementation_commit") == authoritative_commit
            ),
            "implementation-approval-journal-mismatch",
            "%s committed journal has an invalid workflow state" % context,
        )
    else:
        _ensure(
            isinstance(authoritative_commit, str)
            and authoritative_commit
            and implementation_commit == authoritative_commit
            and state.get("status") == "DRAFT_PR_CREATION"
            and state.get("implementation_commit") == implementation_commit,
            "implementation-approval-journal-mismatch",
            "%s finalized journal does not match final workflow state" % context,
        )


def _require_clean_worktree_against_index(root, config, context):
    result = _run_bounded(
        _git_command(config, "diff", "--quiet"),
        _effective_limits(config, "git"),
        root,
    )["result"]
    _ensure(
        result.get("outcome") == "success" and result.get("exit_code") == 0,
        "git-worktree-dirty",
        "%s requires no unstaged changes" % context,
    )


def _hide_untracked_status_after_interruption(root, config):
    """Keep an uncommitted candidate inspectable by workflow helpers after a Git fault."""
    _run_checked(
        _git_command(config, "config", "status.showUntrackedFiles", "no"),
        _effective_limits(config, "git"),
        root,
        "git-config-failed",
        "unable to preserve interrupted candidate status",
    )


def _verify_authoritative_implementation(
    root, config, state, journal, authoritative_head, context
):
    _ensure(
        _current_head(root, config) == authoritative_head,
        "implementation-commit-mismatch",
        "%s requires HEAD to match the authoritative commit" % context,
    )
    target_head = _require_target_fresh(root, config, state, context)
    _ensure(
        target_head == journal.get("target_head")
        and journal.get("expected_parent") == target_head,
        "implementation-approval-journal-mismatch",
        "%s authoritative parent is not journal-bound" % context,
    )
    _git_ancestor(root, config, target_head, authoritative_head, context)
    _require_direct_child(root, config, target_head, authoritative_head, context)
    _require_single_commit(root, config, target_head, authoritative_head, context)
    _require_clean_tree(root, config, context)

    subject = _git_commit_subject(root, config, authoritative_head)
    reviewed_subject = _validate_implementation_commit_subject(
        journal.get("reviewed_commit_subject"), state["issue"]
    )
    _ensure(
        subject == reviewed_subject,
        "implementation-commit-subject-mismatch",
        "%s authoritative subject differs from reviewed subject" % context,
    )

    candidate = journal["implementation_candidate"]
    test_commit = journal["test_commit"]
    candidate_paths = sorted(candidate["candidate_paths"])
    test_paths = journal["approved_test_boundary"]["paths"]
    final_names = _git_diff_names(
        root, config, "%s..%s" % (target_head, authoritative_head)
    )
    expected_names = sorted(set(test_paths).union(candidate_paths))
    _ensure(
        final_names == expected_names,
        "implementation-scope-drift",
        "%s authoritative paths differ from the approved boundary" % context,
    )
    _require_candidate_scope(final_names, state.get("approved_scope") or [], context)
    _require_production_only(candidate_paths, state.get("approved_scope") or [], context)

    approved_test_diff = (
        _git_diff_text(root, config, target_head, test_commit, test_paths)
        if test_paths
        else ""
    )
    authoritative_test_diff = (
        _git_diff_text(root, config, target_head, authoritative_head, test_paths)
        if test_paths
        else ""
    )
    _ensure(
        approved_test_diff == authoritative_test_diff,
        "approved-test-boundary-mismatch",
        "%s authoritative commit changed the approved test boundary" % context,
    )
    authoritative_candidate_diff = _git_diff_text(
        root, config, test_commit, authoritative_head, candidate_paths
    )
    _ensure(
        authoritative_candidate_diff == candidate["candidate_diff"],
        "implementation-candidate-mismatch",
        "%s authoritative commit does not contain the accepted candidate" % context,
    )
    identity = _candidate_identity(
        test_commit,
        candidate["candidate_diff"],
        candidate_paths,
    )
    _ensure(
        journal.get("candidate_identity") == identity,
        "implementation-approval-journal-mismatch",
        "%s authoritative candidate identity differs from the journal" % context,
    )
    return authoritative_head


def _classify_recovery_shape(root, config, state, journal, context):
    test_commit = journal["test_commit"]
    target_head = journal["target_head"]
    head = _current_head(root, config)
    candidate = journal["implementation_candidate"]
    candidate_diff = candidate["candidate_diff"]
    candidate_paths = sorted(candidate["candidate_paths"])
    status_paths = sorted(
        _status_path(line) for line in _git_status_all(root, config)
    )

    if head == test_commit:
        current = _require_implementation_candidate_matches(root, config, state, context)
        _ensure(
            current["candidate_diff"] == candidate_diff
            and current["candidate_paths"] == candidate_paths
            and status_paths == candidate_paths,
            "implementation-candidate-mismatch",
            "%s current candidate is not the exact journal-bound candidate" % context,
        )
        staged_result = _run_bounded(
            _git_command(config, "diff", "--cached", "--quiet"),
            _effective_limits(config, "git"),
            root,
        )["result"]
        if staged_result.get("outcome") == "success" and staged_result.get("exit_code") == 0:
            return "uncommitted-candidate"

        _require_clean_worktree_against_index(root, config, context)
        _ensure(
            _git_index_names(root, config, test_commit) == candidate_paths
            and _git_index_diff(root, config, test_commit) == candidate_diff,
            "implementation-candidate-mismatch",
            "%s staged candidate differs from the journal-bound candidate" % context,
        )
        return "staged-candidate"

    if head == target_head:
        _require_clean_worktree_against_index(root, config, context)
        test_paths = journal["approved_test_boundary"]["paths"]
        expected_names = sorted(set(test_paths).union(candidate_paths))
        _ensure(
            status_paths == expected_names,
            "implementation-candidate-mismatch",
            "%s staged boundary contains unexpected paths" % context,
        )
        _ensure(
            _git_index_names(root, config, test_commit) == candidate_paths
            and _git_index_diff(root, config, test_commit) == candidate_diff,
            "implementation-candidate-mismatch",
            "%s staged candidate differs from the journal-bound candidate" % context,
        )
        expected_test_diff = (
            _git_diff_text(root, config, target_head, test_commit, test_paths)
            if test_paths
            else ""
        )
        actual_test_diff = (
            _git_index_diff(root, config, target_head, test_paths)
            if test_paths
            else ""
        )
        _ensure(
            actual_test_diff == expected_test_diff
            and _git_index_names(root, config, target_head) == expected_names,
            "approved-test-boundary-mismatch",
            "%s staged test boundary differs from the approved tests" % context,
        )
        return "soft-reset"

    expected_commit = journal.get("authoritative_commit") or journal.get(
        "implementation_commit"
    )
    if expected_commit and head == expected_commit:
        _verify_authoritative_implementation(
            root, config, state, journal, head, context
        )
        return "authoritative"

    if journal["status"] == "pending":
        _git_ancestor(root, config, target_head, head, context)
        _require_direct_child(root, config, target_head, head, context)
        _require_single_commit(root, config, target_head, head, context)
        _verify_authoritative_implementation(
            root, config, state, journal, head, context
        )
        return "authoritative"

    _raise(
        "implementation-recovery-shape",
        "%s encountered an unsupported Git state for implementation approval" % context,
    )


def _persist_finalized_transition(root, config, state, journal, authoritative_head):
    journal_to_commit = dict(journal)
    journal_to_commit["status"] = "committed-but-not-persisted"
    journal_to_commit["authoritative_commit"] = authoritative_head
    journal_to_commit["committed_at"] = journal_to_commit.get("committed_at", _now())
    _validate_implementation_transition_journal(
        root, config, state, journal_to_commit, "implementation approval finalization"
    )
    _write_json(
        _implementation_transition_journal_path(root, config, state["issue"]),
        journal_to_commit,
    )

    if state.get("status") != "DRAFT_PR_CREATION":
        state["approvals"]["implementation"] = journal_to_commit["acknowledgment"]
        state["implementation_commit"] = authoritative_head
        state["status"] = "DRAFT_PR_CREATION"
        _write_state(root, config, state["issue"], state)
    else:
        _ensure(
            state.get("implementation_commit") == authoritative_head
            and state["approvals"].get("implementation")
            == journal_to_commit["acknowledgment"],
            "implementation-approval-journal-mismatch",
            "final workflow state does not match implementation approval journal",
        )

    if journal.get("status") != "finalized":
        finalized = dict(journal_to_commit)
        finalized["status"] = "finalized"
        finalized["implementation_commit"] = authoritative_head
        finalized["finalized_at"] = _now()
        _write_json(
            _implementation_transition_journal_path(root, config, state["issue"]),
            finalized,
        )
        journal = finalized
    return journal


def command_recover_implementation_approval(args, root, config):
    """Recover only a journaled implementation approval in an enumerated state."""
    state = _read_state(root, config, args.issue)
    journal = _read_json(
        _implementation_transition_journal_path(root, config, args.issue),
        "implementation approval transition journal",
    )
    _validate_implementation_transition_journal(
        root, config, state, journal, "recover-implementation-approval"
    )
    _require_target_fresh(root, config, state, "recover-implementation-approval")

    if state["status"] == "DRAFT_PR_CREATION":
        _ensure(
            journal["status"] in ("committed-but-not-persisted", "finalized")
            and (
                journal.get("authoritative_commit")
                or journal.get("implementation_commit")
            ),
            "implementation-recovery-shape",
            "recover-implementation-approval requires a committed journal for final state",
        )
        authoritative_head = journal.get("authoritative_commit") or journal[
            "implementation_commit"
        ]
        _verify_authoritative_implementation(
            root,
            config,
            state,
            journal,
            authoritative_head,
            "recover-implementation-approval",
        )
        if journal["status"] == "finalized":
            return {
                "ok": True,
                "status": state["status"],
                "approval": state["approvals"]["implementation"],
                "implementation_commit": authoritative_head,
            }
        _persist_finalized_transition(root, config, state, journal, authoritative_head)
        return {
            "ok": True,
            "status": state["status"],
            "approval": state["approvals"]["implementation"],
            "implementation_commit": authoritative_head,
        }

    _expect_status(
        state,
        "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL",
        "recover-implementation-approval",
    )
    _ensure(
        journal["status"] in ("pending", "committed-but-not-persisted"),
        "implementation-recovery-shape",
        "recover-implementation-approval cannot resume a finalized transition",
    )
    shape = _classify_recovery_shape(
        root, config, state, journal, "recover-implementation-approval"
    )
    if shape == "authoritative":
        authoritative_head = _current_head(root, config)
    else:
        if shape == "uncommitted-candidate":
            _run_checked(
                _git_command(config, "add", "-A"),
                _effective_limits(config, "git"),
                root,
                "git-add-failed",
                "unable to stage implementation changes during recovery",
            )
        if shape in ("uncommitted-candidate", "staged-candidate"):
            _run_checked(
                _git_command(config, "reset", "--soft", journal["target_head"]),
                _effective_limits(config, "git"),
                root,
                "git-reset-failed",
                "unable to soft-reset to target_head during recovery",
            )
        _ensure(
            _git_index_names(root, config, journal["target_head"])
            == sorted(
                set(journal["approved_test_boundary"]["paths"]).union(
                    journal["implementation_candidate"]["candidate_paths"]
                )
            ),
            "implementation-recovery-shape",
            "recovery staged paths do not match the approved boundary",
        )
        _run_checked(
            _git_command(
                config,
                "commit",
                "-qm",
                journal["reviewed_commit_subject"],
            ),
            _effective_limits(config, "git"),
            root,
            "git-commit-failed",
            "unable to create authoritative implementation commit during recovery",
        )
        authoritative_head = _current_head(root, config)

    committed_journal = dict(journal)
    committed_journal["status"] = "committed-but-not-persisted"
    committed_journal["authoritative_commit"] = authoritative_head
    _verify_authoritative_implementation(
        root,
        config,
        state,
        committed_journal,
        authoritative_head,
        "recover-implementation-approval",
    )
    _persist_finalized_transition(
        root, config, state, committed_journal, authoritative_head
    )
    return {
        "ok": True,
        "status": state["status"],
        "approval": state["approvals"]["implementation"],
        "implementation_commit": authoritative_head,
    }


# ---------- commands ----------


def command_init(args, root, config):
    """Create run state only when HEAD matches the resolved target_head."""
    run = _run_root(root, config, args.issue)
    _ensure(not run.exists(), "already-initialized", "Workflow run already exists for issue %s" % args.issue)
    initial_head = _current_head(root, config)
    target_head = _resolve_target_head(root, config, fetch=True)
    _ensure(
        initial_head == target_head,
        "workflow-start-not-at-target",
        "init requires HEAD to match target branch %s at %s" % (config["target_base"], target_head),
    )
    _artifacts_dir(root, config, args.issue).mkdir(parents=True, exist_ok=True)
    state = {
        "format": STATE_FORMAT,
        "issue": args.issue,
        "target_base": config["target_base"],
        "status": "PLANNING",
        "artifacts": {},
        "approvals": {"plan": None, "tests": None, "implementation": None},
        "validation": None,
        "implementation_review_ready": False,
        "draft_pr": None,
        "initial_head": initial_head,
        "base_head": target_head,
        "target_head": target_head,
        "approved_scope": None,
        "test_commit": None,
        "test_implementation_status": "REQUIRED",
        "test_implementation_reason": None,
        "implementation_candidate": None,
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
    state["test_implementation_status"] = (
        "NOT_APPLICABLE" if not any(_is_test_file(path) for path in args.scope) else "REQUIRED"
    )
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
        command = (
            "python3 scripts/agent_workflow.py approve-plan %s --by LOGIN "
            "--confirm plan_approved" % args.issue
        )
        response["approval_gate"] = _approval_gate(
            "plan",
            "The coordinator is stopped. Inspect the exact submitted plan and read-only "
            "review before an operator records a local acknowledgment.",
            command,
        )
        response["human_approval"] = {
            "required": True,
            "message": (
                "Legacy compatibility payload. The local command records a self-attested "
                "acknowledgment; it does not authenticate the asserted operator."
            ),
            "plan_path": state["artifacts"]["plan"]["path"],
            "plan": _artifact_text(root, config, args.issue, "plan"),
            "review_path": state["artifacts"]["plan_review"]["path"],
            "review": _artifact_text(root, config, args.issue, "plan_review"),
            "approval_command": command,
        }
    return response


def command_approve_plan(args, root, config):
    """Advance after matching self-attested local plan acknowledgment."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PLAN_HUMAN_APPROVAL", "approve-plan")
    acknowledgment = _record_local_acknowledgment(config, state, "plan", args.confirm, args.by)
    state["status"] = "TEST_IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "approval": acknowledgment}


def command_reject_plan(args, root, config):
    """Return a rejected plan to planning and invalidate downstream work."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_PLAN_HUMAN_APPROVAL", "reject-plan")
    state["approvals"]["plan"] = None
    _clear_post_plan(state)
    state["status"] = "PLANNING"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


def command_request_plan_revision(args, root, config):
    """Return test implementation to planning for a structured plan defect."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "TEST_IMPLEMENTATION", "request-plan-revision")
    _ensure(
        args.reason.strip(),
        "missing-plan-revision-reason",
        "request-plan-revision requires a non-empty reason",
    )
    _ensure(
        args.reason_code == "approved-plan-defect",
        "invalid-plan-revision-reason-code",
        "request-plan-revision requires reason code approved-plan-defect",
    )

    artifacts = state["artifacts"]
    history_index = len(state.get("plan_revision_requests") or []) + 1
    history_dir = _artifacts_dir(root, config, args.issue) / "plan-revisions" / str(history_index)
    history_dir.mkdir(parents=True, exist_ok=True)
    archived = {}
    for kind in ("plan", "plan_review", "test_report", "test_review"):
        artifact = artifacts.get(kind)
        if not artifact:
            continue
        source = root / artifact["path"]
        if source.is_file():
            destination = history_dir / source.name
            destination.write_bytes(source.read_bytes())
            archived[kind] = _relative(destination, root)

    request = {
        "requested_by": args.by,
        "requested_at": _now(),
        "reason_code": args.reason_code,
        "reason": args.reason.strip(),
        "from_status": state["status"],
        "prior_scope": list(state.get("approved_scope") or []),
        "prior_plan": artifacts.get("plan"),
        "prior_artifacts": archived,
        "prior_test_commit": state.get("test_commit"),
        "prior_test_failure": state.get("test_failure"),
    }
    state.setdefault("plan_revision_requests", []).append(request)
    state["approvals"]["plan"] = None
    _clear_post_plan(state)
    state["test_commit"] = None
    state.pop("test_failure", None)
    state["approved_scope"] = None
    state["status"] = "PLANNING"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "plan_revision": request}


def command_submit_tests(args, root, config):
    """Verify and record a committed tests-only change plus its targeted failure."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "TEST_IMPLEMENTATION", "submit-tests")
    _require_role(config, "test_implementer", args.agent, "submit-tests")
    if _test_reopen_active(state):
        _require_no_uncommitted_test_changes(root, config, "submit-tests")
    else:
        _require_clean_tree(root, config, "submit-tests")
    scope = state.get("approved_scope") or []
    _ensure(scope, "missing-approved-scope", "submit-tests requires approved plan scope")
    target_head = _state_target_head(state)
    _ensure(target_head, "missing-target-head", "Workflow has no recorded target_head")

    if args.not_applicable:
        _ensure(
            args.reason.strip(),
            "missing-test-applicability-reason",
            "--not-applicable requires a non-empty reason",
        )
        _ensure(
            state.get("test_implementation_status") == "NOT_APPLICABLE",
            "test-applicability-not-approved",
            "NOT_APPLICABLE must be established by the approved plan",
        )
        _ensure(
            _current_head(root, config) == target_head,
            "not-applicable-test-commit-drift",
            "NOT_APPLICABLE requires no test commit or candidate changes",
        )
        state["artifacts"]["test_report"] = _record_artifact(
            root, config, args.issue, "test_report", args.artifact
        )
        _clear_post_tests(state)
        state["test_commit"] = target_head
        state["test_implementation_status"] = "NOT_APPLICABLE"
        state["test_implementation_reason"] = args.reason.strip()
        state["test_failure"] = None
        state["status"] = "TEST_REVIEW"
        _write_state(root, config, args.issue, state)
        return {"ok": True, "status": state["status"], "test_implementation_status": "NOT_APPLICABLE"}

    test_head = _current_head(root, config)
    _ensure(
        test_head != target_head,
        "missing-test-commit",
        "submit-tests requires a committed test change",
    )
    test_paths = _git_diff_names(root, config, "%s..%s" % (target_head, test_head))
    _git_ancestor(root, config, target_head, test_head, "submit-tests")
    _require_test_only(test_paths, scope, "submit-tests")
    _ensure(
        args.failure_command and shlex.split(args.failure_command),
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
    if _test_reopen_active(state):
        _require_no_uncommitted_test_changes(root, config, "submit-tests after failure check")
    else:
        _require_clean_tree(root, config, "submit-tests after failure check")
    state["artifacts"]["test_report"] = _record_artifact(
        root, config, args.issue, "test_report", args.artifact
    )
    _clear_post_tests(state)
    state["test_commit"] = test_head
    if _test_reopen_active(state):
        state["test_reopenings"][-1]["corrected_test_candidate"] = test_head
        state["test_reopenings"][-1]["submitted_at"] = _now()
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
    response = {"ok": True, "status": state["status"], "review_status": args.status}
    if state["status"] == "WAITING_FOR_TEST_HUMAN_APPROVAL":
        response["approval_gate"] = _approval_gate(
            "tests",
            "The coordinator is stopped. Inspect the exact submitted tests and read-only "
            "review before an operator records a local acknowledgment.",
            "python3 scripts/agent_workflow.py approve-tests %s --by LOGIN "
            "--confirm tests_approved" % args.issue,
        )
    return response


def command_approve_tests(args, root, config):
    """Approval Gate 2: record local acknowledgment and the test boundary."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_TEST_HUMAN_APPROVAL", "approve-tests")
    acknowledgment = _record_local_acknowledgment(config, state, "tests", args.confirm, args.by)

    candidate_test_commit = state.get("test_commit")
    target_head = _state_target_head(state)
    scope = state.get("approved_scope") or []
    reopened_tests = _test_reopen_active(state)
    if _tests_not_applicable(state):
        _ensure(
            candidate_test_commit == target_head,
            "not-applicable-test-commit-drift",
            "approve-tests requires NOT_APPLICABLE to remain at target_head",
        )
        _ensure(_current_head(root, config) == target_head, "test-commit-mismatch", "approve-tests requires HEAD to match target_head")
        _require_clean_index(root, config, "approve-tests")
        _run_checked(
            _git_command(config, "commit", "--allow-empty", "-m", "workflow: approve tests"),
            _effective_limits(config, "git"), root, "git-commit-failed", "unable to record approved test boundary",
        )
        state["test_commit"] = _current_head(root, config)
        state["status"] = "IMPLEMENTATION"
        _write_state(root, config, args.issue, state)
        return {"ok": True, "status": state["status"], "approval": acknowledgment, "test_commit": state["test_commit"]}
    _ensure(candidate_test_commit, "missing-test-commit", "approve-tests requires candidate test_commit")
    _ensure(target_head, "missing-target-head", "approve-tests requires target_head")

    # Verify that only approved test files changed in the candidate commit
    test_paths = _git_diff_names(root, config, "%s..%s" % (target_head, candidate_test_commit))
    _git_ancestor(root, config, target_head, candidate_test_commit, "approve-tests")
    _require_test_only(test_paths, scope, "approve-tests")
    _ensure(
        _current_head(root, config) == candidate_test_commit,
        "test-commit-mismatch",
        "approve-tests requires HEAD to match the reviewed test candidate",
    )
    _require_no_uncommitted_test_changes(root, config, "approve-tests")
    _require_clean_index(root, config, "approve-tests")

    # The candidate is already committed by the test implementer.  This empty
    # The workflow-owned commit records the locally acknowledged boundary without
    # staging, resetting, or discarding any uncommitted production candidate.
    _run_checked(
        _git_command(config, "commit", "--allow-empty", "-m", "workflow: approve tests"),
        _effective_limits(config, "git"),
        root,
        "git-commit-failed",
        "unable to record approved test boundary",
    )
    authoritative_test_head = _current_head(root, config)
    if reopened_tests:
        _require_no_uncommitted_test_changes(root, config, "post-approve-tests")
        _require_clean_index(root, config, "post-approve-tests")
    else:
        _require_clean_tree(root, config, "post-approve-tests")

    state["test_commit"] = authoritative_test_head
    if reopened_tests:
        state["test_reopenings"][-1]["new_test_commit"] = authoritative_test_head
        state["test_reopenings"][-1]["approved_at"] = state["approvals"]["tests"]["recorded_at"]
        state["test_reopenings"][-1]["active"] = False
    state["status"] = "IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {
        "ok": True,
        "status": state["status"],
        "approval": acknowledgment,
        "test_commit": authoritative_test_head,
    }


def command_reject_tests(args, root, config):
    """Return rejected tests to test implementation and clear downstream state."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_TEST_HUMAN_APPROVAL", "reject-tests")
    state["approvals"]["tests"] = None
    _clear_post_tests(state)
    state["status"] = "TEST_IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


def command_reopen_tests(args, root, config):
    """Exceptionally reopen Human Gate 2 after a proven approved-test fixture defect."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "IMPLEMENTATION", "reopen-tests")
    _ensure(
        args.reason == "approved-test-fixture-defect",
        "invalid-reopen-reason",
        "reopen-tests requires --reason approved-test-fixture-defect",
    )
    _ensure(
        not state.get("implementation_commit"),
        "implementation-already-approved",
        "reopen-tests is not allowed after an implementation commit exists",
    )
    _ensure(
        not state.get("draft_pr"),
        "draft-pr-already-created",
        "reopen-tests is not allowed after a draft PR exists",
    )
    previous_test_commit = state.get("test_commit")
    _ensure(previous_test_commit, "missing-test-commit", "reopen-tests requires test_commit")
    previous_test_approval = state["approvals"].get("tests")
    _ensure(previous_test_approval, "tests-not-approved", "reopen-tests requires approved tests")

    reopenings = state.setdefault("test_reopenings", [])
    reopenings.append(
        {
            "active": True,
            "initiated_by": "reopen-tests",
            "reason": args.reason,
            "reopened_at": _now(),
            "previous_test_commit": previous_test_commit,
            "previous_test_approval": previous_test_approval,
            "previous_test_report": state["artifacts"].get("test_report"),
            "previous_test_review": state["artifacts"].get("test_review"),
        }
    )

    state["artifacts"].pop("test_report", None)
    state["artifacts"].pop("test_review", None)
    state["approvals"]["tests"] = None
    state["test_commit"] = None
    _clear_post_tests(state)
    state["status"] = "TEST_IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {
        "ok": True,
        "status": state["status"],
        "reason": args.reason,
        "previous_test_commit": previous_test_commit,
    }


def command_submit_implementation(args, root, config):
    """Bind the uncommitted production candidate to independently verified Git evidence."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "IMPLEMENTATION", "submit-implementation")
    _ensure(
        state["approvals"]["tests"] is not None,
        "tests-not-approved",
        "submit-implementation requires approved tests",
    )
    _require_role(config, "implementer", args.agent, "submit-implementation")
    test_commit = state.get("test_commit")
    scope = state.get("approved_scope") or []
    _ensure(test_commit, "missing-test-commit", "submit-implementation requires an approved test commit")
    _ensure(scope, "missing-approved-scope", "submit-implementation requires approved plan scope")
    _ensure(
        _current_head(root, config) == test_commit,
        "implementation-commit-not-allowed",
        "submit-implementation requires the production candidate to remain uncommitted",
    )
    _require_clean_index(root, config, "submit-implementation")

    _ensure(
        getattr(args, "evidence", None),
        "missing-evidence",
        "submit-implementation requires --evidence PATH",
    )
    evidence_file = _resolve_file(root, args.evidence)
    evidence = _read_json(evidence_file, "execution evidence")

    # Validate evidence structure and test execution results
    _ensure(
        evidence.get("test_command"),
        "missing-test-command",
        "execution evidence requires test_command",
    )
    _ensure(
        evidence.get("exit_code") == 0,
        "tests-failed",
        "execution evidence exit_code must be 0",
    )
    _ensure(
        evidence.get("result") == "PASS",
        "tests-failed",
        "execution evidence result must be PASS",
    )
    _ensure(
        evidence.get("test_commit") == test_commit,
        "test-commit-mismatch",
        "execution evidence test_commit must match workflow test_commit",
    )
    commit_subject = _validate_implementation_commit_subject(
        evidence.get("commit_subject"), args.issue
    )

    # Independently obtain current Git candidate representation against test_commit
    candidate_diff_raw = _git_candidate_diff(root, config, test_commit)

    recorded_diff = evidence.get("candidate_diff", "")
    _ensure(
        candidate_diff_raw == recorded_diff,
        "evidence-candidate-mismatch",
        "current Git candidate diff does not match recorded evidence diff",
    )

    # Check changed files in working tree against test_commit
    changed_names = _git_candidate_names(root, config, test_commit)

    _ensure(
        all(not _is_test_file(p) for p in changed_names),
        "tests-modified-after-approval",
        "submit-implementation must preserve approved test content",
    )
    _ensure(
        any(not _is_test_file(path) for path in changed_names),
        "missing-implementation-candidate",
        "submit-implementation requires at least one production change",
    )
    _ensure(
        all(_path_in_scope(path, scope) for path in changed_names),
        "implementation-scope-drift",
        "submit-implementation may change only approved files: %s"
        % ", ".join(changed_names),
    )

    state["artifacts"]["implementation_report"] = _record_artifact(
        root, config, args.issue, "implementation_report", args.artifact
    )
    state["implementation_candidate"] = {
        "test_commit": test_commit,
        "candidate_diff": candidate_diff_raw,
        "candidate_paths": changed_names,
        "commit_subject": commit_subject,
        "accepted_at": _now(),
    }
    state["validation"] = None
    state["implementation_review_ready"] = False
    state["draft_pr"] = None
    state["status"] = "VALIDATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"]}


def command_run_validation(args, root, config):
    """Run bounded validation and revalidate the accepted implementation candidate."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "VALIDATION", "run-validation")

    profile_name = _select_validation_profile(config, args.profile)
    checks = _run_validation_checks(root, config, profile_name)

    all_passed = all(check["passed"] for check in checks)
    _require_implementation_candidate_matches(root, config, state, "run-validation")
    if all_passed:
        test_commit = state.get("test_commit")
        _ensure(test_commit, "missing-test-commit", "run-validation requires test_commit")
        test_paths = _approved_test_paths(root, config, state, "run-validation")
        if test_paths:
            test_diff = _run_bounded(
                _git_command(config, "diff", "--quiet", test_commit, "--", *test_paths),
                _effective_limits(config, "git"),
                root,
            )
            result = test_diff["result"]
            _ensure(
                result.get("outcome") == "success" and result.get("exit_code") == 0,
                "git-worktree-dirty",
                "run-validation requires approved tests to remain unchanged",
            )
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
        "passed": all_passed,
    }

    if all_passed:
        state["status"] = "IMPLEMENTATION_REVIEW"
    else:
        state["status"] = "IMPLEMENTATION"
        state["implementation_review_ready"] = False

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


def command_review_implementation(args, root, config):
    """Record review only after READY candidates still match accepted Git state."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "IMPLEMENTATION_REVIEW", "review-implementation")
    _require_role(config, "reviewer", args.reviewer, "review-implementation")
    _ensure(args.status in REVIEW_STATUSES, "invalid-review-status", "Unknown review status")
    state["artifacts"]["implementation_review"] = _record_artifact(
        root, config, args.issue, "implementation_review", args.artifact
    )

    validated = state.get("validation") or {}
    if args.status == READY:
        _ensure(
            validated.get("passed"),
            "validation-missing",
            "review-implementation requires a successful validation run",
        )
        _require_implementation_candidate_matches(root, config, state, "review-implementation")
        state["implementation_review_ready"] = True
        state["status"] = "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL"
    else:
        state["status"] = "IMPLEMENTATION"
        state["implementation_review_ready"] = False
        state["validation"] = None
        state["implementation_candidate"] = None

    _write_state(root, config, args.issue, state)
    response = {"ok": True, "status": state["status"], "review_status": args.status}
    if state["status"] == "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL":
        response["approval_gate"] = _approval_gate(
            "implementation",
            "The coordinator is stopped. Inspect the validated implementation candidate and "
            "read-only review before an operator records a local acknowledgment.",
            "python3 scripts/agent_workflow.py approve-implementation %s --by LOGIN "
            "--confirm implementation_approved" % args.issue,
        )
    return response


def command_approve_implementation(args, root, config):
    """Approval Gate 3: durably journal, verify, and commit the candidate."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", "approve-implementation")
    _ensure(
        state.get("implementation_review_ready"),
        "implementation-review-not-ready",
        "approve-implementation requires a READY_FOR_HUMAN_APPROVAL implementation review",
    )
    acknowledgment = _record_local_acknowledgment(
        config, state, "implementation", args.confirm, args.by
    )

    test_commit = state.get("test_commit")
    scope = state.get("approved_scope") or []
    _ensure(test_commit, "missing-test-commit", "approve-implementation requires test_commit")
    target_head = _require_target_fresh(root, config, state, "approve-implementation")
    _ensure(
        _current_head(root, config) == test_commit,
        "implementation-commit-mismatch",
        "approve-implementation requires HEAD to match approved test_commit",
    )
    _git_ancestor(root, config, target_head, test_commit, "approve-implementation")
    current_candidate = _require_implementation_candidate_matches(
        root, config, state, "approve-implementation"
    )

    # Verify approved tests remain unchanged
    changed_names = _git_candidate_names(root, config, test_commit)
    _ensure(
        all(not _is_test_file(p) for p in changed_names),
        "tests-modified-after-approval",
        "approve-implementation requires approved tests to remain unchanged",
    )
    candidate_names = _git_candidate_names(root, config, test_commit)
    _require_production_only(candidate_names, scope, "approve-implementation")

    journal = _build_implementation_transition_journal(
        root, config, state, acknowledgment
    )
    _write_json(
        _implementation_transition_journal_path(root, config, args.issue),
        journal,
    )

    # Create the single authoritative implementation commit relative to the
    # verified target base, containing approved tests plus reviewed production.
    try:
        _run_checked(
            _git_command(config, "add", "-A"),
            _effective_limits(config, "git"),
            root,
            "git-add-failed",
            "unable to stage implementation changes",
        )
    except WorkflowError:
        _hide_untracked_status_after_interruption(root, config)
        raise
    _run_checked(
        _git_command(config, "reset", "--soft", target_head),
        _effective_limits(config, "git"),
        root,
        "git-reset-failed",
        "unable to soft-reset to target_head",
    )
    _run_checked(
        _git_command(config, "commit", "-qm", current_candidate["commit_subject"]),
        _effective_limits(config, "git"),
        root,
        "git-commit-failed",
        "unable to create authoritative implementation commit",
    )
    authoritative_head = _current_head(root, config)
    _verify_authoritative_implementation(
        root,
        config,
        state,
        journal,
        authoritative_head,
        "approve-implementation",
    )
    journal = _persist_finalized_transition(
        root, config, state, journal, authoritative_head
    )
    return {
        "ok": True,
        "status": state["status"],
        "approval": acknowledgment,
        "implementation_commit": authoritative_head,
    }


def command_reject_implementation(args, root, config):
    """Return rejected implementation to IMPLEMENTATION without restart."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "WAITING_FOR_IMPLEMENTATION_HUMAN_APPROVAL", "reject-implementation")
    state["approvals"]["implementation"] = None
    state["implementation_review_ready"] = False
    state["validation"] = None
    state["implementation_candidate"] = None
    state["status"] = "IMPLEMENTATION"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "reason": args.reason}


def command_create_draft_pr(args, root, config):
    """Publish only an approved one-commit branch rooted at the fresh target."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "DRAFT_PR_CREATION", "create-draft-pr")
    implementation_commit = state.get("implementation_commit")
    head = _current_head(root, config)
    _ensure(
        implementation_commit and head == implementation_commit,
        "implementation-commit-mismatch",
        "create-draft-pr requires HEAD to match approved implementation_commit",
    )
    body_path = _resolve_file(root, args.body_file)
    _validate_pr_body(body_path)
    if args.skip_github:
        _require_publication_topology(root, config, state, head, "create-draft-pr")
    if not args.skip_github:
        _require_clean_tree(root, config, "create-draft-pr")
        _require_publication_topology(root, config, state, head, "create-draft-pr")

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
    state["status"] = "WORKFLOW_COMPLETED"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "draft_pr": state["draft_pr"]}


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

    request_plan_revision = subparsers.add_parser("request-plan-revision")
    _add_root(request_plan_revision)
    _add_issue(request_plan_revision)
    request_plan_revision.add_argument("--by", required=True)
    request_plan_revision.add_argument("--reason-code", required=True)
    request_plan_revision.add_argument("--reason", required=True)

    submit_tests = subparsers.add_parser("submit-tests")
    _add_root(submit_tests)
    _add_issue(submit_tests)
    _add_artifact(submit_tests)
    submit_tests.add_argument("--agent", required=True)
    submit_tests.add_argument("--failure-command", required=False)
    submit_tests.add_argument("--failure-contains", required=False)
    submit_tests.add_argument("--not-applicable", action="store_true")
    submit_tests.add_argument("--reason", default="")

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

    reopen_tests = subparsers.add_parser("reopen-tests")
    _add_root(reopen_tests)
    _add_issue(reopen_tests)
    reopen_tests.add_argument("--reason", required=True)

    reanchor_target = subparsers.add_parser("reanchor-target")
    _add_root(reanchor_target)
    _add_issue(reanchor_target)
    reanchor_target.add_argument("--by", required=True)

    reconcile_candidate = subparsers.add_parser("reconcile-candidate")
    _add_root(reconcile_candidate)
    _add_issue(reconcile_candidate)
    reconcile_candidate.add_argument("--by", required=True)

    submit_implementation = subparsers.add_parser("submit-implementation")
    _add_root(submit_implementation)
    _add_issue(submit_implementation)
    _add_artifact(submit_implementation)
    submit_implementation.add_argument("--agent", required=True)
    submit_implementation.add_argument("--evidence")

    run_validation = subparsers.add_parser("run-validation")
    _add_root(run_validation)
    _add_issue(run_validation)
    run_validation.add_argument("--profile", required=True)

    review_implementation = subparsers.add_parser("review-implementation")
    _add_root(review_implementation)
    _add_issue(review_implementation)
    _add_artifact(review_implementation)
    _add_review(review_implementation)
    review_implementation.add_argument("--reviewer", required=True)

    approve_implementation = subparsers.add_parser("approve-implementation")
    _add_root(approve_implementation)
    _add_issue(approve_implementation)
    _add_human(approve_implementation)

    recover_implementation_approval = subparsers.add_parser(
        "recover-implementation-approval"
    )
    _add_root(recover_implementation_approval)
    _add_issue(recover_implementation_approval)

    reject_implementation = subparsers.add_parser("reject-implementation")
    _add_root(reject_implementation)
    _add_issue(reject_implementation)
    _add_human(reject_implementation, include_reason=True)

    create_draft_pr = subparsers.add_parser("create-draft-pr")
    _add_root(create_draft_pr)
    _add_issue(create_draft_pr)
    create_draft_pr.add_argument("--title", required=True)
    create_draft_pr.add_argument("--body-file", required=True)
    create_draft_pr.add_argument("--head")
    create_draft_pr.add_argument("--skip-github", action="store_true")

    return parser


COMMANDS = {
    "init": command_init,
    "status": command_status,
    "submit-plan": command_submit_plan,
    "review-plan": command_review_plan,
    "approve-plan": command_approve_plan,
    "reject-plan": command_reject_plan,
    "request-plan-revision": command_request_plan_revision,
    "submit-tests": command_submit_tests,
    "review-tests": command_review_tests,
    "approve-tests": command_approve_tests,
    "reject-tests": command_reject_tests,
    "reopen-tests": command_reopen_tests,
    "reanchor-target": command_reanchor_target,
    "reconcile-candidate": command_reconcile_candidate,
    "submit-implementation": command_submit_implementation,
    "run-validation": command_run_validation,
    "review-implementation": command_review_implementation,
    "approve-implementation": command_approve_implementation,
    "recover-implementation-approval": command_recover_implementation_approval,
    "reject-implementation": command_reject_implementation,
    "create-draft-pr": command_create_draft_pr,
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
