import base64
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
from scripts import workflow_runtime as runtime


REPOSITORY = pathlib.Path(__file__).parents[2]


def _sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


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
        self.agent.write_text(
            "#!/bin/sh\n"
            "printf '%s' '{\"format\":\"chess-echo-orchestrator-agent-candidate-v1\","
            "\"kind\":\"plan\",\"plan\":\"Canary.\\n\",\"units\":[{\"id\":\"canary\","
            "\"title\":\"Canary\",\"start_line\":1,\"end_line\":1,\"review_class\":"
            "\"ordinary\",\"dependencies\":[]}],\"revision\":null}'\n"
        )
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
            "output_limit_bytes": 16_384,
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
                "output_limit_bytes": 16_384,
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

    def test_execution_binds_command_workspace_authority_environment_and_result(self):
        instance = self.fixture.instance()
        request = self.fixture.request()
        binding = {"kind": "evidence-binding", "sha256": "d" * 64, "size": 1}
        projected = [{"role": "issue-source", "binding": binding, "entries": [{"path": "issue.json", "bytes_base64": "e30=", "sha256": "a" * 64, "size": 2}]}]
        launched = []
        original_supervise = provider.supervisor.supervise

        def supervise(command, **options):
            if command[0] == str(self.fixture.agent):
                launched.append(copy.deepcopy(options["env"]))
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
            set(launched[0]),
        )
        self.assertEqual(
            self.fixture.worker_token, launched[0]["COPILOT_GITHUB_TOKEN"]
        )
        self.assertNotIn("GH_TOKEN", launched[0])
        self.assertNotIn("GITHUB_TOKEN", launched[0])
        self.assertEqual(
            ["COPILOT_GITHUB_TOKEN"], facts["environment"]["secret_keys"]
        )
        self.assertEqual(
            instance.authentication, facts["environment"]["authentication"]
        )
        self.assertNotIn("values", facts["environment"])
        self.assertNotIn(self.fixture.worker_token, json.dumps(result))
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
            "#!/bin/sh\nprintf '%s' \"$COPILOT_GITHUB_TOKEN\"\n"
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
        candidate = base64.b64decode(
            executed["process_result"]["stdout"]["base64"], validate=True
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
