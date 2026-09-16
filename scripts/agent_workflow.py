#!/usr/bin/env python3
"""Minimal skill-driven workflow orchestration for ChessEcho issues."""

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import shlex
import stat
import tempfile

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
    """Persist a JSON object in the issue-local run directory."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _write_json_durable(path, payload):
    """Atomically persist a journal before any workflow-owned Git mutation."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=".workflow-journal-", suffix=".tmp", dir=str(path.parent)
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        try:
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        except OSError:
            # Atomic replacement remains valid on filesystems without
            # directory fsync support.
            pass
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


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


def _test_approval_journal_path(root, config, issue):
    return _run_root(root, config, issue) / "test-approval-transition.json"


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
    state.pop("test_implementation_status", None)
    state.pop("test_implementation_reason", None)


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


def _git_commit_diff(root, config, base, head):
    """Return a binary diff between two committed revisions."""
    return _run_checked(
        _git_command(config, "diff", "--binary", "%s..%s" % (base, head), "--"),
        _effective_limits(config, "git"),
        root,
        "git-diff-failed",
        "unable to compute committed test diff",
    )["stdout_text"]


def _canonicalize_diff(diff):
    """Make patch section ordering irrelevant while retaining patch content."""
    # Some evidence producers join split sections without restoring the
    # separator on the first section. Normalize that harmless serialization
    # variation before ordering sections.
    if diff and not diff.startswith("diff --git "):
        diff = "diff --git " + diff
    lines = diff.splitlines(keepends=True)
    starts = [index for index, line in enumerate(lines) if line.startswith("diff --git ")]
    if len(starts) <= 1:
        return diff
    prefix = "".join(lines[: starts[0]])
    sections = [
        "".join(lines[start : starts[index + 1] if index + 1 < len(starts) else len(lines)])
        for index, start in enumerate(starts)
    ]
    return prefix + "".join(
        sorted(sections, key=lambda section: section.splitlines()[0])
    )


def _git_tree_mode(root, config, revision, path):
    """Read the committed mode for a path, including paths deleted in a candidate."""
    output = _run_checked(
        _git_command(config, "ls-tree", "-r", "-z", revision, "--", path),
        _effective_limits(config, "git"),
        root,
        "git-tree-failed",
        "unable to inspect candidate base tree",
    )["stdout_text"]
    entry = next((item for item in output.split("\0") if item), "")
    if not entry:
        return None
    return entry.split("\t", 1)[0].split(" ", 1)[0]


def _candidate_tree(root, config, revision, paths):
    """Capture changed path, content, and mode identity from the worktree."""
    snapshot = []
    for path in sorted(paths):
        candidate = root / path
        exists = os.path.lexists(str(candidate))
        mode = None
        content_hash = None
        if exists:
            metadata = candidate.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                mode = "120000"
                content = os.readlink(candidate).encode("utf-8", errors="surrogateescape")
            elif stat.S_ISREG(metadata.st_mode):
                mode = "100755" if metadata.st_mode & stat.S_IXUSR else "100644"
                content = candidate.read_bytes()
            else:
                mode = "100000"
                content = b""
            content_hash = hashlib.sha256(content).hexdigest()
        else:
            mode = _git_tree_mode(root, config, revision, path)
        snapshot.append(
            {
                "path": path,
                "exists": exists,
                "mode": mode,
                "content_sha256": content_hash,
            }
        )
    return snapshot


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

    current_diff = _git_candidate_diff(root, config, test_commit)
    current_paths = _git_candidate_names(root, config, test_commit)
    _ensure(
        all(not _is_test_file(path) for path in current_paths),
        "tests-modified-after-approval",
        "%s requires approved tests to remain unchanged" % context,
    )
    accepted_canonical = accepted.get("candidate_diff_canonical")
    if not isinstance(accepted_canonical, str):
        accepted_canonical = _canonicalize_diff(accepted["candidate_diff"])
    _ensure(
        _canonicalize_diff(accepted["candidate_diff"]) == accepted_canonical,
        "implementation-candidate-mismatch",
        "%s persisted candidate evidence differs from its accepted identity" % context,
    )
    current_tree = _candidate_tree(root, config, test_commit, current_paths)
    accepted_tree = accepted.get("candidate_tree")
    _ensure(
        isinstance(accepted_tree, list)
        and current_tree == accepted_tree,
        "implementation-candidate-mismatch",
        "%s current candidate tree differs from accepted tree" % context,
    )
    _ensure(
        _canonicalize_diff(current_diff) == accepted_canonical
        and current_paths == sorted(accepted_paths),
        "implementation-candidate-mismatch",
        "%s current implementation candidate differs from accepted candidate" % context,
    )
    scope = state.get("approved_scope") or []
    _ensure(
        all(_path_in_scope(path, scope) for path in current_paths),
        "implementation-scope-drift",
        "%s may change only approved files: %s" % (context, ", ".join(current_paths)),
    )
    return {
        "candidate_diff": current_diff,
        "candidate_paths": current_paths,
        "candidate_tree": current_tree,
    }


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
    journal = _test_approval_journal_path(root, config, args.issue)
    if journal.exists():
        journal.unlink()
    state["approved_scope"] = None
    state["status"] = "PLANNING"
    _write_state(root, config, args.issue, state)
    return {"ok": True, "status": state["status"], "plan_revision": request}


def command_submit_tests(args, root, config):
    """Verify and record a committed tests-only change plus its targeted failure."""
    state = _read_state(root, config, args.issue)
    _expect_status(state, "TEST_IMPLEMENTATION", "submit-tests")
    _require_role(config, "test_implementer", args.agent, "submit-tests")
    if getattr(args, "not_applicable", False):
        _ensure(
            args.reason and args.reason.strip(),
            "missing-not-applicable-reason",
            "submit-tests --not-applicable requires a non-empty --reason",
        )
        _ensure(
            _current_head(root, config) == _state_target_head(state),
            "not-applicable-test-commit",
            "NOT_APPLICABLE test approval requires HEAD at target_head",
        )
        _require_clean_tree(root, config, "submit-tests --not-applicable")
    elif _test_reopen_active(state):
        _require_no_uncommitted_test_changes(root, config, "submit-tests")
    else:
        _require_clean_tree(root, config, "submit-tests")
    scope = state.get("approved_scope") or []
    _ensure(scope, "missing-approved-scope", "submit-tests requires approved plan scope")
    target_head = _state_target_head(state)
    _ensure(target_head, "missing-target-head", "Workflow has no recorded target_head")
    test_head = _current_head(root, config)
    if getattr(args, "not_applicable", False):
        test_paths = []
        failure_result = None
        test_head = target_head
    else:
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
    if getattr(args, "not_applicable", False) and not (root / args.artifact).is_file():
        state["artifacts"].pop("test_report", None)
    else:
        state["artifacts"]["test_report"] = _record_artifact(
            root, config, args.issue, "test_report", args.artifact
        )
    _clear_post_tests(state)
    state["test_commit"] = test_head
    state["test_implementation_status"] = (
        "NOT_APPLICABLE" if getattr(args, "not_applicable", False) else "REQUIRED"
    )
    if getattr(args, "not_applicable", False):
        state["test_implementation_reason"] = args.reason.strip()
    else:
        state.pop("test_implementation_reason", None)
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
    journal_path = _test_approval_journal_path(root, config, args.issue)
    _ensure(
        not journal_path.exists(),
        "test-approval-journal-present",
        "approve-tests requires recovery of the existing test approval journal",
    )
    acknowledgment = _local_acknowledgment(config, "tests", args.confirm, args.by)
    journal = _prepare_test_approval_journal(
        args, root, config, state, acknowledgment
    )
    _write_json_durable(journal_path, journal)
    return _complete_test_approval(root, config, args.issue, state, journal)


def _local_acknowledgment(config, gate, provided, by):
    """Validate local approval input without mutating persisted workflow state."""
    expected = config["workflow"]["approvals"].get(gate)
    _ensure(
        provided == expected,
        "approval-confirmation-mismatch",
        "Expected confirmation phrase for %s gate: %s" % (gate, expected),
    )
    return {
        "kind": LOCAL_ACKNOWLEDGMENT_KIND,
        "asserted_by": by,
        "confirmation": provided,
        "recorded_at": _now(),
        "independent_authorization": False,
    }


def _prepare_test_approval_journal(args, root, config, state, acknowledgment):
    """Validate Gate 2 and bind every value needed for crash recovery."""
    target_head = _require_target_fresh(root, config, state, "approve-tests")
    candidate_test_commit = state.get("test_commit")
    status = state.get("test_implementation_status", "REQUIRED")
    scope = list(state.get("approved_scope") or [])
    reopened = _test_reopen_active(state)
    _ensure(candidate_test_commit, "missing-test-commit", "approve-tests requires candidate test_commit")
    _ensure(scope, "missing-approved-scope", "approve-tests requires approved plan scope")
    _ensure(
        _current_head(root, config) == candidate_test_commit,
        "test-commit-mismatch",
        "approve-tests requires HEAD to match the reviewed test candidate",
    )
    _git_ancestor(root, config, target_head, candidate_test_commit, "approve-tests")
    _require_no_uncommitted_test_changes(root, config, "approve-tests")
    _require_clean_index(root, config, "approve-tests")

    if status == "NOT_APPLICABLE":
        _ensure(
            candidate_test_commit == target_head,
            "test-approval-journal-mismatch",
            "NOT_APPLICABLE approval must be based at target_head",
        )
        test_paths = []
        candidate_diff = ""
    else:
        _ensure(
            status == "REQUIRED",
            "test-approval-journal-mismatch",
            "Unknown test implementation applicability",
        )
        test_paths = _git_diff_names(
            root, config, "%s..%s" % (target_head, candidate_test_commit)
        )
        _require_test_only(test_paths, scope, "approve-tests")
        candidate_diff = _git_commit_diff(root, config, target_head, candidate_test_commit)

    latest_reopen = None
    if reopened:
        latest_reopen = json.loads(json.dumps(state["test_reopenings"][-1]))
    return {
        "format": "chess-echo-test-approval-transition-v1",
        "issue": state["issue"],
        "operation": "approve-tests",
        "status": "pending",
        "from_status": "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "to_status": "IMPLEMENTATION",
        "authorization": acknowledgment,
        "target_base": state.get("target_base"),
        "target_head": target_head,
        "approved_scope": scope,
        "test_implementation_status": status,
        "applicability": status,
        "test_implementation_reason": state.get("test_implementation_reason"),
        "parent_head": candidate_test_commit,
        "resulting_commit": None,
        "reviewed_test_candidate": {
            "test_commit": candidate_test_commit,
            "candidate_diff": candidate_diff,
            "candidate_paths": test_paths,
        },
        "reviewed_test_evidence": {
            "test_report": state["artifacts"].get("test_report"),
            "test_review": state["artifacts"].get("test_review"),
            "test_failure": state.get("test_failure"),
        },
        "reopened_test_metadata": latest_reopen,
    }


def _journal_error(code, message):
    _raise(code, message)


def _read_test_approval_journal(root, config, issue):
    path = _test_approval_journal_path(root, config, issue)
    _ensure(path.is_file(), "test-approval-journal-missing", "No test approval journal exists")
    try:
        journal = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        _journal_error("test-approval-journal-corrupt", "Unable to read test approval journal: %s" % error)
    _ensure(
        isinstance(journal, dict)
        and journal.get("format") == "chess-echo-test-approval-transition-v1",
        "test-approval-journal-corrupt",
        "Test approval journal has an unsupported format",
    )
    _ensure(
        journal.get("status") in ("pending", "finalized"),
        "test-approval-journal-corrupt",
        "Test approval journal has an invalid status",
    )
    return journal


def _validate_test_approval_journal(root, config, state, journal):
    """Fail closed unless the journal still describes the exact reviewed transition."""
    if state["status"] not in (
        "WAITING_FOR_TEST_HUMAN_APPROVAL",
        "IMPLEMENTATION",
    ):
        _journal_error("test-approval-journal-stale", "Test approval journal is stale for current workflow state")
    if journal.get("issue") != state.get("issue") or journal.get("operation") != "approve-tests":
        _journal_error("test-approval-journal-mismatch", "Test approval journal operation binding differs")
    if journal.get("from_status") != "WAITING_FOR_TEST_HUMAN_APPROVAL" or journal.get("to_status") != "IMPLEMENTATION":
        _journal_error("test-approval-journal-mismatch", "Test approval journal transition binding differs")
    if journal.get("status") == "finalized" and state["status"] != "IMPLEMENTATION":
        _journal_error("test-approval-journal-stale", "Finalized test approval journal is not paired with implementation state")
    if journal.get("status") == "finalized" and not isinstance(
        journal.get("resulting_commit"), str
    ):
        _journal_error("test-approval-journal-corrupt", "Finalized journal has no resulting commit")
    if journal.get("target_base") != state.get("target_base"):
        _journal_error("test-approval-journal-mismatch", "Test approval journal target base differs")
    if journal.get("target_head") != _state_target_head(state):
        _journal_error("test-approval-journal-mismatch", "Test approval journal target head differs")
    if journal.get("approved_scope") != list(state.get("approved_scope") or []):
        _journal_error("test-approval-journal-mismatch", "Test approval journal scope differs")
    if journal.get("test_implementation_status", "REQUIRED") != state.get(
        "test_implementation_status", "REQUIRED"
    ):
        _journal_error("test-approval-journal-mismatch", "Test approval journal applicability differs")
    if journal.get("applicability") != journal.get("test_implementation_status"):
        _journal_error("test-approval-journal-mismatch", "Test approval journal applicability binding differs")
    if journal.get("test_implementation_reason") != state.get("test_implementation_reason"):
        _journal_error("test-approval-journal-mismatch", "Test approval journal applicability reason differs")
    authorization = journal.get("authorization")
    expected = config["workflow"]["approvals"].get("tests")
    if (
        not isinstance(authorization, dict)
        or authorization.get("confirmation") != expected
        or not authorization.get("asserted_by")
        or authorization.get("independent_authorization") is not False
    ):
        _journal_error("test-approval-journal-mismatch", "Test approval journal authorization differs")
    candidate = journal.get("reviewed_test_candidate")
    if not isinstance(candidate, dict):
        _journal_error("test-approval-journal-corrupt", "Reviewed test candidate is missing")
    expected_candidate_commit = state.get("test_commit")
    if state["status"] == "IMPLEMENTATION":
        expected_candidate_commit = journal.get("parent_head")
    if candidate.get("test_commit") != expected_candidate_commit:
        _journal_error("test-approval-journal-mismatch", "Reviewed test candidate differs")
    if journal.get("reviewed_test_evidence") != {
        "test_report": state["artifacts"].get("test_report"),
        "test_review": state["artifacts"].get("test_review"),
        "test_failure": state.get("test_failure"),
    }:
        _journal_error("test-approval-journal-mismatch", "Reviewed test evidence differs")
    active_reopen = _test_reopen_active(state)
    current_reopen = state.get("test_reopenings", [])[-1] if active_reopen else None
    journal_reopen = journal.get("reopened_test_metadata")
    if state["status"] == "IMPLEMENTATION" and journal_reopen is not None:
        expected_reopen = json.loads(json.dumps(journal_reopen))
        if journal.get("resulting_commit") or state.get("test_commit"):
            expected_reopen["new_test_commit"] = (
                journal.get("resulting_commit") or state.get("test_commit")
            )
            expected_reopen["approved_at"] = journal["authorization"]["recorded_at"]
            expected_reopen["active"] = False
        current_reopen = state.get("test_reopenings", [])[-1] if state.get("test_reopenings") else None
    if state["status"] == "WAITING_FOR_TEST_HUMAN_APPROVAL":
        metadata_matches = journal_reopen == current_reopen
    else:
        metadata_matches = journal_reopen is None or current_reopen == expected_reopen
    if not metadata_matches:
        _journal_error("test-approval-journal-mismatch", "Reopened-test metadata differs")
    return candidate


def _validate_reviewed_test_candidate(root, config, state, journal, candidate):
    """Recompute the reviewed test boundary rather than trusting journal bytes."""
    target_head = journal["target_head"]
    parent = journal.get("parent_head")
    _ensure(parent, "test-approval-journal-corrupt", "Test approval journal has no parent head")
    _git_ancestor(root, config, target_head, parent, "test approval recovery")
    status = journal.get("test_implementation_status", "REQUIRED")
    if status == "NOT_APPLICABLE":
        _ensure(
            parent == target_head,
            "test-approval-journal-topology",
            "NOT_APPLICABLE test approval parent is not target_head",
        )
        paths = []
        diff = ""
    else:
        paths = _git_diff_names(root, config, "%s..%s" % (target_head, parent))
        _require_test_only(
            paths, state.get("approved_scope") or [], "test approval recovery"
        )
        diff = _git_commit_diff(root, config, target_head, parent)
    _ensure(
        candidate.get("test_commit") == parent
        and candidate.get("candidate_paths") == paths
        and candidate.get("candidate_diff") == diff,
        "test-approval-journal-mismatch",
        "Reviewed test candidate no longer matches Git",
    )


def _is_test_boundary_commit(root, config, parent, revision):
    if _commit_parent(root, config, revision) != parent:
        return False
    subject = _run_checked(
        _git_command(config, "show", "-s", "--format=%s", revision),
        _effective_limits(config, "git"),
        root,
        "git-show-failed",
        "unable to inspect test approval boundary",
    )["stdout_text"].strip()
    if subject != "workflow: approve tests":
        return False
    return not _git_diff_names(root, config, "%s..%s" % (parent, revision))


def _complete_test_approval(root, config, issue, state, journal):
    """Finish or replay the journaled empty boundary commit exactly once."""
    candidate = _validate_test_approval_journal(root, config, state, journal)
    _validate_reviewed_test_candidate(root, config, state, journal, candidate)
    parent = journal["parent_head"]
    current = _current_head(root, config)
    resulting = journal.get("resulting_commit")
    if state["status"] == "IMPLEMENTATION":
        _ensure(
            current == state.get("test_commit"),
            "test-approval-journal-topology",
            "Implementation state does not match current test boundary",
        )
        _ensure(
            state["approvals"].get("tests") == journal["authorization"],
            "test-approval-journal-mismatch",
            "Implementation state does not match journal authorization",
        )
        _ensure(
            resulting is None or state.get("test_commit") == resulting,
            "test-approval-journal-mismatch",
            "Implementation state does not match resulting test boundary",
        )
        _ensure(
            current != parent,
            "test-approval-journal-topology",
            "Implementation state cannot be completed before its boundary commit",
        )
    if resulting is not None and current == parent:
        _journal_error(
            "test-approval-journal-mismatch",
            "Journal claims a resulting commit before the boundary exists",
        )
    if resulting is not None and current != parent:
        _ensure(
            _is_test_boundary_commit(root, config, parent, current),
            "test-approval-journal-topology",
            "Current HEAD is not the journaled test boundary",
        )
        _ensure(
            resulting == current,
            "test-approval-journal-mismatch",
            "Resulting test boundary differs",
        )
    if current == parent:
        _run_checked(
            _git_command(config, "commit", "--allow-empty", "-m", "workflow: approve tests"),
            _effective_limits(config, "git"),
            root,
            "git-commit-failed",
            "unable to record approved test boundary",
        )
        current = _current_head(root, config)
    elif not _is_test_boundary_commit(root, config, parent, current):
        _journal_error("test-approval-journal-topology", "Current HEAD is not the journaled test boundary")
    if resulting and resulting != current:
        _journal_error("test-approval-journal-mismatch", "Resulting test boundary differs")
    journal["resulting_commit"] = current
    reopened = journal.get("reopened_test_metadata") is not None
    if reopened:
        _require_no_uncommitted_test_changes(root, config, "post-approve-tests")
        _require_clean_index(root, config, "post-approve-tests")
    else:
        _require_clean_tree(root, config, "post-approve-tests")
    if state["status"] == "WAITING_FOR_TEST_HUMAN_APPROVAL":
        state["approvals"]["tests"] = journal["authorization"]
        state["test_commit"] = current
        if reopened:
            state["test_reopenings"][-1]["new_test_commit"] = current
            state["test_reopenings"][-1]["approved_at"] = journal["authorization"]["recorded_at"]
            state["test_reopenings"][-1]["active"] = False
        state["status"] = "IMPLEMENTATION"
        _write_state(root, config, issue, state)
    else:
        _ensure(
            state.get("test_commit") == current
            and state["approvals"].get("tests") == journal["authorization"],
            "test-approval-journal-mismatch",
            "Workflow state does not match finalized test approval",
        )
    journal["status"] = "finalized"
    journal["finalized_at"] = journal.get("finalized_at") or _now()
    _write_json_durable(_test_approval_journal_path(root, config, issue), journal)
    return {
        "ok": True,
        "status": state["status"],
        "approval": journal["authorization"],
        "test_commit": current,
    }


def command_recover_tests_approval(args, root, config):
    """Recover a durable Gate 2 journal without accepting new approval input."""
    state = _read_state(root, config, args.issue)
    journal = _read_test_approval_journal(root, config, args.issue)
    _validate_test_approval_journal(root, config, state, journal)
    _require_target_fresh(root, config, state, "recover-tests-approval")
    return _complete_test_approval(root, config, args.issue, state, journal)


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
    journal = _test_approval_journal_path(root, config, args.issue)
    if journal.exists():
        journal.unlink()
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

    # Independently obtain current Git candidate representation against test_commit
    candidate_diff_raw = _git_candidate_diff(root, config, test_commit)

    recorded_diff = evidence.get("candidate_diff", "")
    _ensure(
        candidate_diff_raw == recorded_diff,
        "evidence-candidate-mismatch",
        "current Git candidate diff does not match recorded evidence diff",
    )

    # Check that approved tests remain byte-for-byte unchanged relative to test_commit
    # First check working tree changes for test files:
    test_diff = _run_bounded(
        _git_command(
            config,
            "diff",
            "--quiet",
            test_commit,
            "--",
            *sorted([path for path in scope if _is_test_file(path)] or ["."]),
        ),
        _effective_limits(config, "git"),
        root,
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
        "candidate_diff_canonical": _canonicalize_diff(candidate_diff_raw),
        "candidate_paths": changed_names,
        "candidate_tree": _candidate_tree(root, config, test_commit, changed_names),
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
        scope = state.get("approved_scope") or []
        _ensure(test_commit, "missing-test-commit", "run-validation requires test_commit")
        test_paths = sorted([path for path in scope if _is_test_file(path)] or ["."])
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
    """Approval Gate 3: record local acknowledgment and commit the candidate."""
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
    _require_implementation_candidate_matches(root, config, state, "approve-implementation")

    # Verify approved tests remain unchanged
    changed_names = _git_candidate_names(root, config, test_commit)
    _ensure(
        all(not _is_test_file(p) for p in changed_names),
        "tests-modified-after-approval",
        "approve-implementation requires approved tests to remain unchanged",
    )
    candidate_names = _git_candidate_names(root, config, test_commit)
    _require_production_only(candidate_names, scope, "approve-implementation")

    # Create the single authoritative implementation commit relative to the
    # verified target base, containing approved tests plus reviewed production.
    _run_checked(
        _git_command(config, "add", "-A"),
        _effective_limits(config, "git"),
        root,
        "git-add-failed",
        "unable to stage implementation changes",
    )
    _run_checked(
        _git_command(config, "reset", "--soft", target_head),
        _effective_limits(config, "git"),
        root,
        "git-reset-failed",
        "unable to soft-reset to target_head",
    )
    _run_checked(
        _git_command(config, "commit", "-qm", "Implement issue #%s" % args.issue),
        _effective_limits(config, "git"),
        root,
        "git-commit-failed",
        "unable to create authoritative implementation commit",
    )
    authoritative_head = _current_head(root, config)
    _require_clean_tree(root, config, "post-implementation-commit")
    _require_publication_topology(root, config, state, authoritative_head, "approve-implementation")

    state["implementation_commit"] = authoritative_head
    state["status"] = "DRAFT_PR_CREATION"
    _write_state(root, config, args.issue, state)
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
    submit_tests.add_argument("--failure-command")
    submit_tests.add_argument("--failure-contains")
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

    recover_tests_approval = subparsers.add_parser("recover-tests-approval")
    _add_root(recover_tests_approval)
    _add_issue(recover_tests_approval)

    reject_tests = subparsers.add_parser("reject-tests")
    _add_root(reject_tests)
    _add_issue(reject_tests)
    _add_human(reject_tests, include_reason=True)

    reopen_tests = subparsers.add_parser("reopen-tests")
    _add_root(reopen_tests)
    _add_issue(reopen_tests)
    reopen_tests.add_argument("--reason", required=True)

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
    "recover-tests-approval": command_recover_tests_approval,
    "reject-tests": command_reject_tests,
    "reopen-tests": command_reopen_tests,
    "submit-implementation": command_submit_implementation,
    "run-validation": command_run_validation,
    "review-implementation": command_review_implementation,
    "approve-implementation": command_approve_implementation,
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
