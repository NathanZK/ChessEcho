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
VERSION = "1.0.0"
RESULT_FORMAT = "chess-echo-trusted-local-execution-result-v1"
DISCOVERY_FORMAT = "chess-echo-pending-result-candidates-v1"
HANDOFF_FORMAT = "chess-echo-execution-handoff-v1"
WORKTREE_BRANCH_PREFIX = "chess-echo-agent/issue-"
AUDIT_LIMITS = {
    "timeout_ms": 30_000,
    "grace_ms": 1_000,
    "output_limit_bytes": 1024 * 1024,
}
PROMPT_LIMIT_BYTES = 64 * 1024


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


def _safe_root(path, label):
    try:
        supplied = pathlib.Path(path).absolute()
        resolved = supplied.resolve(strict=True)
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-unavailable" % label, "%s is unavailable: %s" % (label, error))
    if supplied != resolved or not resolved.is_dir():
        _fail("denied", "%s-redirection" % label, "%s must not use symlink redirection" % label)
    return resolved


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
        "Inspect the issue and repository as needed, perform only the requested phase, "
        "and print only one compact JSON object of kind %s matching "
        "chess-echo-orchestrator-agent-candidate-v1. Do not wrap it in Markdown."
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
        self.agent_home = _safe_root(agent_home, "agent-home")
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
            "--allow-all-tools",
            "--silent",
            "--prompt",
            prompt,
        ]
        controlled_environment = {
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
        process = supervisor.supervise(
            command,
            timeout_ms=limits["timeout_ms"],
            grace_ms=limits["grace_ms"],
            output_limit_bytes=limits["output_limit_bytes"],
            cwd=str(self.root),
            env=controlled_environment,
            cancel_event=cancel_event,
        )
        candidate = _process_bytes(process)
        after = _worktree_identity(self)
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
            "environment": {
                "keys": sorted(controlled_environment),
                "values": controlled_environment,
                "sha256": _sha(_canonical(controlled_environment)),
            },
            "process_result_sha256": _sha(_canonical(process)),
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
            "provider_result": facts,
        }

    def input_projection_sha256(self, request):
        return _sha(_canonical(_input_projection(self.root, self.issue, request)))


def _process_bytes(process):
    try:
        record = process["stdout"]
        candidate = base64.b64decode(record["base64"], validate=True)
    except (KeyError, TypeError, ValueError, binascii.Error):
        _fail("corrupt", "local-agent-output-invalid", "Agent process output is malformed")
    if set(record) != {"bytes", "base64"} or record["bytes"] != len(candidate):
        _fail("corrupt", "local-agent-output-invalid", "Agent output identity is malformed")
    return candidate


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
