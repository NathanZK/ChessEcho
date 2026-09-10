#!/usr/bin/env python3
"""Reviewed Phase 1 host for the replacement workflow orchestrator.

Run this file with ``python3 -I`` from a clean control checkout at the selected
remote base. Candidate work executes in a distinct deterministic linked
worktree. This host does not provide OS-level hostile-process containment.
"""

import argparse
import hashlib
import json
import os
import pathlib
import stat
import subprocess
import sys

sys.dont_write_bytecode = True


NAME = "chess-echo-trusted-local-host"
VERSION = "1.3.1"
CONFIG_FORMAT = "chess-echo-trusted-local-host-config-v1"
CONTROL_SOURCES = (
    "scripts/workflow_authority.py",
    "scripts/workflow_cas.py",
    "scripts/workflow_evidence.py",
    "scripts/workflow_inspector.py",
    "scripts/workflow_issue_source.py",
    "scripts/workflow_kernel.py",
    "scripts/workflow_local_provider.py",
    "scripts/workflow_migration.py",
    "scripts/workflow_orchestrator.py",
    "scripts/workflow_orchestrator_resume.py",
    "scripts/workflow_plan_revision_policy.py",
    "scripts/workflow_policy.py",
    "scripts/workflow_runtime.py",
    "scripts/workflow_runtime_reconstruction.py",
    "scripts/workflow_supervision_policy.py",
    "scripts/workflow_supervisor.py",
    "scripts/workflow_work_type_policy.py",
)
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024


class LocalHostFailure(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message

    def document(self):
        return {
            "format": "chess-echo-trusted-local-host-failure-v1",
            "outcome": {
                "status": self.status,
                "code": self.code,
                "message": self.message,
            },
        }


def _fail(status, code, message):
    raise LocalHostFailure(status, code, message)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _canonical(value):
    return json.dumps(
        value, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _regular(path, label, executable=False, allow_symlink=False):
    try:
        supplied = pathlib.Path(path).absolute()
        if supplied.is_symlink() and not allow_symlink:
            _fail("denied", "%s-redirection" % label, "%s must not be a symlink" % label)
        resolved = supplied.resolve(strict=True)
        metadata = resolved.stat()
        data = resolved.read_bytes()
    except LocalHostFailure:
        raise
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-unavailable" % label, "%s is unavailable: %s" % (label, error))
    if (supplied != resolved and not allow_symlink) or not stat.S_ISREG(metadata.st_mode):
        _fail("denied", "%s-not-regular" % label, "%s must be one regular file" % label)
    if executable and not os.access(resolved, os.X_OK):
        _fail("denied", "%s-not-executable" % label, "%s is not executable" % label)
    return {"path": str(resolved), "sha256": _sha(data)}


def _directory(path, label, create=False):
    supplied = pathlib.Path(path).absolute()
    try:
        if create:
            supplied.mkdir(mode=0o700, parents=True, exist_ok=True)
        resolved = supplied.resolve(strict=True)
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-unavailable" % label, "%s is unavailable: %s" % (label, error))
    if supplied != resolved or supplied.is_symlink() or not resolved.is_dir():
        _fail("denied", "%s-redirection" % label, "%s must not use symlink redirection" % label)
    return resolved


def _git(executable, root, arguments, label, maximum=MAX_DOCUMENT_BYTES):
    environment = {
        "PATH": os.pathsep.join((str(pathlib.Path(executable).parent), "/usr/bin", "/bin")),
        "HOME": "",
        "LC_ALL": "C.UTF-8",
        "LANG": "C.UTF-8",
        "TZ": "UTC",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_ASKPASS": "",
    }
    try:
        result = subprocess.run(
            [executable, "-c", "core.fsmonitor=false", *arguments],
            cwd=str(root),
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        _fail("missing", "%s-failed" % label, "%s failed: %s" % (label, error))
    if result.returncode != 0 or len(result.stdout) > maximum or len(result.stderr) > maximum:
        _fail("stale", "%s-failed" % label, "%s did not complete safely" % label)
    return result.stdout


def _parse(data, label):
    if not isinstance(data, bytes) or not data or len(data) > MAX_DOCUMENT_BYTES:
        _fail("unsupported", "%s-size" % label, "%s is empty or too large" % label)
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        _fail("corrupt", "%s-json" % label, "%s is invalid JSON: %s" % (label, error))
    if not isinstance(value, dict):
        _fail("corrupt", "%s-type" % label, "%s must be a JSON object" % label)
    return value


def _control_identity(control_root, git_executable, agent_executable):
    if not sys.flags.isolated:
        _fail(
            "denied",
            "isolated-python-required",
            "Run the reviewed local host with python3 -I",
        )
    root = _directory(control_root, "control-root")
    script = _regular(pathlib.Path(__file__).absolute(), "local-host-source")
    if pathlib.Path(script["path"]).parent.parent != root:
        _fail("denied", "local-host-location", "Host source is outside the control checkout")
    git_record = _regular(git_executable, "git-executable", executable=True, allow_symlink=True)
    agent_record = _regular(agent_executable, "agent-executable", executable=True, allow_symlink=True)
    head = _git(git_record["path"], root, ["rev-parse", "--verify", "HEAD^{commit}"], "control-head").decode().strip()
    base = _git(git_record["path"], root, ["rev-parse", "--verify", "refs/remotes/origin/main^{commit}"], "control-base").decode().strip()
    status = _git(git_record["path"], root, ["status", "--porcelain=v1", "-z", "--untracked-files=all"], "control-status")
    if head != base or status:
        _fail(
            "stale",
            "control-checkout-unreviewed",
            "Control checkout must be clean and exactly match refs/remotes/origin/main",
        )
    config_path = root / ".github" / "agent-workflow.json"
    config_record = _regular(config_path, "control-config")
    committed_config = _git(
        git_record["path"],
        root,
        ["show", "%s:.github/agent-workflow.json" % head],
        "control-config",
    )
    if config_path.read_bytes() != committed_config:
        _fail("stale", "control-config-replaced", "Control config differs from the selected base")
    config_root = _parse(committed_config, "control-config")
    config = config_root.get("orchestrator")
    local = config.get("local_host") if isinstance(config, dict) else None
    keys = {
        "format",
        "name",
        "version",
        "source",
        "source_sha256",
        "provider",
        "python",
        "agent",
        "workspace_branch_prefix",
        "worker_authentication",
    }
    if not isinstance(local, dict) or set(local) != keys or local["format"] != CONFIG_FORMAT:
        _fail("corrupt", "local-host-config", "Base config has no exact trusted-local host configuration")
    if config.get("mode") != "active":
        _fail("unsupported", "local-host-inactive", "Phase 1 local execution is not active in the selected base")
    expected_host = {
        "name": NAME,
        "version": VERSION,
        "source": "scripts/workflow_local_host.py",
        "source_sha256": script["sha256"],
    }
    if {key: local[key] for key in expected_host} != expected_host:
        _fail("denied", "local-host-identity-mismatch", "Host identity differs from base configuration")
    provider_source = _regular(root / local["provider"]["source"], "local-provider-source")
    if local["provider"] != {
        "name": "chess-echo-trusted-local",
        "version": "1.5.6",
        "source": "scripts/workflow_local_provider.py",
        "source_sha256": provider_source["sha256"],
    }:
        _fail("denied", "local-provider-identity-mismatch", "Provider identity differs from base configuration")
    python_record = _regular(sys.executable, "python-executable", executable=True, allow_symlink=True)
    if local["python"] != python_record:
        _fail("denied", "python-executable-mismatch", "Python executable differs from base configuration")
    if local["agent"] != {
        "name": pathlib.Path(agent_record["path"]).name,
        "sha256": agent_record["sha256"],
    }:
        _fail("denied", "agent-executable-mismatch", "Agent executable differs from base configuration")
    if local["workspace_branch_prefix"] != "chess-echo-agent/issue-":
        _fail("denied", "workspace-branch-prefix", "Workspace branch prefix is not the reviewed value")
    if local["worker_authentication"] != {
        "mode": "trusted-local-stdin-v1",
        "trust": "trusted-local-development-v1",
        "secret_environment_key": "COPILOT_GITHUB_TOKEN",
    }:
        _fail(
            "denied",
            "worker-authentication-config",
            "Worker authentication differs from the reviewed trusted-local mode",
        )
    sources = []
    for relative in CONTROL_SOURCES:
        current = _regular(root / relative, "control-source")
        committed = _git(git_record["path"], root, ["show", "%s:%s" % (head, relative)], "control-source")
        if current["sha256"] != _sha(committed):
            _fail("stale", "control-source-replaced", "Control source differs from the selected base: %s" % relative)
        sources.append({"path": relative, "sha256": current["sha256"]})
    identity = {
        "format": "chess-echo-trusted-local-control-plane-v1",
        "repository_root": str(root),
        "commit": head,
        "config": config_record,
        "host": expected_host,
        "provider": dict(local["provider"]),
        "python": python_record,
        "agent": agent_record,
        "sources": sources,
    }
    identity["identity_sha256"] = _sha(_canonical(identity))
    return root, config_root, identity, git_record, agent_record


def _load_modules(control_root, identity):
    scripts = str(control_root / "scripts")
    sys.path[:] = [scripts] + [
        item
        for item in sys.path
        if item
        and pathlib.Path(item).absolute().resolve() not in {control_root, pathlib.Path.cwd().resolve()}
    ]
    modules = {}
    for name in (pathlib.Path(relative).stem for relative in CONTROL_SOURCES):
        module = __import__(name)
        path = pathlib.Path(module.__file__).resolve(strict=True)
        if path.parent != control_root / "scripts":
            _fail("denied", "control-import-substitution", "Imported %s outside the control checkout" % name)
        modules[name] = module
    expected = {row["path"]: row["sha256"] for row in identity["sources"]}
    for relative, digest in expected.items():
        if _regular(control_root / relative, "control-source")["sha256"] != digest:
            _fail("stale", "control-source-replaced", "Control source changed during import: %s" % relative)
    return modules


def _workspace_path(parent, repository, issue):
    key = _sha(repository.encode("utf-8"))[:16]
    return parent / key / ("issue-%d" % issue)


def _validate_workspace(control_root, workspace, issue, git_record):
    workspace = _directory(workspace, "candidate-workspace")
    git_file = workspace / ".git"
    if git_file.is_symlink() or not git_file.is_file():
        _fail("denied", "candidate-workspace-git-redirection", "Candidate worktree .git must be one regular file")
    top = pathlib.Path(_git(git_record["path"], workspace, ["rev-parse", "--show-toplevel"], "workspace-top").decode().strip())
    if top.resolve(strict=True) != workspace:
        _fail("denied", "candidate-workspace-root-mismatch", "Candidate worktree top-level path is not the deterministic workspace")
    candidate_common = pathlib.Path(_git(git_record["path"], workspace, ["rev-parse", "--git-common-dir"], "workspace-common").decode().strip())
    control_common = pathlib.Path(_git(git_record["path"], control_root, ["rev-parse", "--git-common-dir"], "control-common").decode().strip())
    if not candidate_common.is_absolute():
        candidate_common = workspace / candidate_common
    if not control_common.is_absolute():
        control_common = control_root / control_common
    if candidate_common.resolve(strict=True) != control_common.resolve(strict=True):
        _fail("denied", "candidate-workspace-common-dir-mismatch", "Candidate workspace is not a linked worktree of the reviewed checkout")
    branch = _git(git_record["path"], workspace, ["symbolic-ref", "-q", "HEAD"], "workspace-branch").decode().strip()
    if branch != "refs/heads/chess-echo-agent/issue-%d" % issue:
        _fail("denied", "candidate-workspace-branch-mismatch", "Candidate workspace is on the wrong deterministic branch")
    if workspace == control_root:
        _fail("denied", "candidate-workspace-control-alias", "Candidate workspace must be distinct from the reviewed checkout")
    return workspace


def _prepare_workspace(modules, control_root, repository, issue, parent, git_record):
    provider = modules["workflow_local_provider"]
    parent = _directory(parent, "workspace-parent", create=True)
    workspace = _workspace_path(parent, repository, issue)
    branch = provider.WORKTREE_BRANCH_PREFIX + str(issue)
    if not workspace.exists():
        workspace.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        result = modules["workflow_supervisor"].supervise(
            [
                git_record["path"],
                "-c",
                "core.fsmonitor=false",
                "worktree",
                "add",
                "-b",
                branch,
                str(workspace),
                "HEAD",
            ],
            timeout_ms=30_000,
            grace_ms=1_000,
            output_limit_bytes=1024 * 1024,
            cwd=str(control_root),
            env={
                "PATH": os.pathsep.join((str(pathlib.Path(git_record["path"]).parent), "/usr/bin", "/bin")),
                "HOME": "",
                "LC_ALL": "C.UTF-8",
                "LANG": "C.UTF-8",
                "TZ": "UTC",
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_NO_REPLACE_OBJECTS": "1",
                "GIT_OPTIONAL_LOCKS": "0",
                "GIT_TERMINAL_PROMPT": "0",
                "GIT_ASKPASS": "",
            },
        )
        if result.get("outcome") != "success":
            _fail("missing", "workspace-create-failed", "Dedicated worktree creation failed")
    return _validate_workspace(control_root, workspace, issue, git_record)


def _install(
    modules,
    args,
    config_root,
    identity,
    git_record,
    agent_record,
    token,
    worker_token_reader,
):
    provider_module = modules["workflow_local_provider"]
    orchestrator = modules["workflow_orchestrator"]
    runtime = modules["workflow_runtime"]
    workspace = _validate_workspace(pathlib.Path(identity["repository_root"]), args.workspace, args.issue, git_record)
    result_store = provider_module.PendingResultStore(args.result_store, workspace)
    roles = config_root["orchestrator"]["agent_roles"]

    def runtime_provider(_root, _issue, request):
        if isinstance(request, dict) and request.get("format") == runtime.RECONSTRUCTION_REQUEST_FORMAT:
            return runtime.reconstruct(workspace, request, token)
        return runtime.bootstrap(
            workspace,
            args.repository,
            git_record["path"],
            args.gh_executable,
            token,
        )

    def sandbox_provider(_root, issue, role):
        row = next((item for item in roles if item["role"] == role), None)
        if row is None:
            _fail("corrupt", "local-role-missing", "Base config has no requested agent role")
        worker_token = worker_token_reader() if worker_token_reader else None
        return provider_module.LocalSandboxProvider(
            root=workspace,
            control_root=identity["repository_root"],
            issue=issue,
            role=role,
            row=row,
            git_executable=git_record["path"],
            agent_executable=agent_record["path"],
            agent_home=args.agent_home,
            trusted_worker_authentication=worker_token_reader is not None,
            worker_token=worker_token,
        )

    orchestrator.RUNTIME_PROVIDER = runtime_provider
    orchestrator.SANDBOX_PROVIDER = sandbox_provider
    orchestrator.PENDING_RESULT_PROVIDER = result_store
    return orchestrator, result_store, workspace


def _load_document(path, label):
    try:
        source = pathlib.Path(path)
        if source.is_symlink() or not source.is_file():
            _fail("denied", "%s-not-regular" % label, "%s must be a regular file" % label)
        data = source.read_bytes()
    except OSError as error:
        _fail("missing", "%s-unreadable" % label, "%s cannot be read: %s" % (label, error))
    return _parse(data, label)


def _read_worker_token(stream):
    token = stream.readline().rstrip("\n")
    if not token:
        _fail(
            "missing",
            "worker-authentication-missing",
            "Trusted-local worker credential must be supplied as the next standard-input line",
        )
    return token


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control-root", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--git-executable", required=True)
    parser.add_argument("--gh-executable", required=True)
    parser.add_argument("--agent-executable", required=True)
    parser.add_argument("--agent-home", required=True)
    parser.add_argument("--result-store", required=True)
    parser.add_argument("--github-token-stdin", action="store_true", required=True)
    parser.add_argument(
        "--trusted-worker-auth-stdin",
        action="store_true",
        help="Trust the local worker and read its credential as the second stdin line",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare-workspace")
    prepare.add_argument("issue", type=int)
    prepare.add_argument("--workspace-parent", required=True)
    for name in ("bootstrap", "publish-issue-source", "status", "plan-next"):
        command = commands.add_parser(name)
        command.add_argument("issue", type=int)
        command.add_argument("--workspace", required=True)
    init = commands.add_parser("init")
    init.add_argument("issue", type=int)
    init.add_argument("--workspace", required=True)
    init.add_argument("--request", required=True)
    step = commands.add_parser("step")
    step.add_argument("issue", type=int)
    step.add_argument("--workspace", required=True)
    step.add_argument("--expected-tip", required=True)
    step.add_argument("--request")
    for name in ("approve", "recover"):
        command = commands.add_parser(name)
        command.add_argument("issue", type=int)
        command.add_argument("--workspace", required=True)
        command.add_argument("--expected-tip", required=True)
        command.add_argument("--authorization")
    cancel = commands.add_parser("cancel")
    cancel.add_argument("issue", type=int)
    cancel.add_argument("--workspace", required=True)
    cancel.add_argument("--expected-tip", required=True)
    cancel.add_argument("--reason", required=True)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        control_root, config, identity, git_record, agent_record = _control_identity(
            args.control_root, args.git_executable, args.agent_executable
        )
        modules = _load_modules(control_root, identity)
        if args.command == "prepare-workspace":
            workspace = _prepare_workspace(
                modules,
                control_root,
                args.repository,
                args.issue,
                args.workspace_parent,
                git_record,
            )
            document = {
                "format": "chess-echo-trusted-local-workspace-v1",
                "issue": args.issue,
                "repository": args.repository,
                "workspace": str(workspace),
                "branch": modules["workflow_local_provider"].WORKTREE_BRANCH_PREFIX + str(args.issue),
                "control_plane": identity,
            }
        else:
            token = sys.stdin.readline().rstrip("\n")
            if not token:
                _fail("missing", "github-token-missing", "GitHub token must be supplied on standard input")
            worker_token_reader = (
                lambda: _read_worker_token(sys.stdin)
            ) if args.trusted_worker_auth_stdin else None
            orchestrator, result_store, workspace = _install(
                modules,
                args,
                config,
                identity,
                git_record,
                agent_record,
                token,
                worker_token_reader,
            )
            if args.command == "bootstrap":
                adapter = orchestrator.RUNTIME_PROVIDER(workspace, args.issue, {})
                document = {
                    "format": "chess-echo-trusted-local-bootstrap-v1",
                    "issue": args.issue,
                    "control_plane": identity,
                    "runtime": adapter.bootstrap_document(),
                }
            elif args.command == "publish-issue-source":
                document = modules["workflow_issue_source"].publish(
                    workspace,
                    args.repository,
                    args.issue,
                    git_record["path"],
                    args.gh_executable,
                    token,
                )
            elif args.command == "status":
                document = orchestrator.status(workspace, args.issue)
            elif args.command == "plan-next":
                document = orchestrator.plan_next(workspace, args.issue)
            elif args.command == "init":
                document = orchestrator.init(
                    workspace, args.issue, request=_load_document(args.request, "orchestration-request")
                )
            elif args.command == "step":
                supplied = _load_document(args.request, "execution-handoff") if args.request else None
                document = orchestrator.step(
                    workspace,
                    args.issue,
                    expected_tip=args.expected_tip,
                    request=supplied,
                )
                handoff = document.get("handoff") if isinstance(document, dict) else None
                if handoff is not None:
                    query = orchestrator.plan_next(workspace, args.issue)["pending_result_query"]
                    result_store.record(query, handoff)
            elif args.command == "cancel":
                document = orchestrator.cancel(
                    workspace, args.issue, expected_tip=args.expected_tip, reason=args.reason
                )
            else:
                supplied = (
                    _load_document(args.authorization, "authorization")
                    if args.authorization
                    else None
                )
                handler = orchestrator.approve if args.command == "approve" else orchestrator.recover
                document = handler(
                    workspace,
                    args.issue,
                    expected_tip=args.expected_tip,
                    authorization=supplied,
                )
        sys.stdout.buffer.write(_canonical(document) + b"\n")
        return 0
    except LocalHostFailure as error:
        sys.stdout.buffer.write(_canonical(error.document()) + b"\n")
        return 2
    except Exception as error:
        if all(hasattr(error, field) for field in ("status", "code", "message")):
            document = {
                "format": "chess-echo-trusted-local-host-failure-v1",
                "outcome": {
                    "status": error.status,
                    "code": error.code,
                    "message": error.message,
                },
            }
            sys.stdout.buffer.write(_canonical(document) + b"\n")
            return 2
        raise


if __name__ == "__main__":
    raise SystemExit(main())
