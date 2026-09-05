import base64
import copy
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import tempfile
import threading
import unittest
from unittest import mock

from scripts import workflow_inspector as inspector
from scripts import workflow_runtime as runtime
from scripts import workflow_work_type_policy as work_type_policy
from scripts.tests.test_workflow_work_type_policy import WorkTypeFixture


REPOSITORY = pathlib.Path(__file__).parents[2]
FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "workflow-orchestrator"
OID = "1" * 40
TREE = "2" * 40
HASH = "3" * 64
FAMILY = "0123456789abcdef0123456789abcdef"


def reference(kind="evidence-binding", digest=HASH, size=1):
    return {"kind": kind, "sha256": digest, "size": size}


def process_result(command, outcome="success", reason="process-exited", stdout=b""):
    return {
        "format": "chess-echo-process-result-v1",
        "command_sha256": hashlib.sha256(
            json.dumps(command, ensure_ascii=True, separators=(",", ":")).encode()
        ).hexdigest(),
        "limits": {
            "timeout_ms": 30_000,
            "grace_ms": 1_000,
            "output_bytes_per_stream": 8 * 1024 * 1024,
        },
        "containment": {
            "kind": "posix-process-group",
            "cleanup_scope": "original-process-group",
            "escaped_descendants": "not-observable",
            "descendant_cleanup_verified": False,
        },
        "outcome": outcome,
        "reason": reason,
        "exit_code": 0 if outcome == "success" else None,
        "terminating_signal": None,
        "forced_termination": False,
        "cleanup_verified": True,
        "stdout": {
            "bytes": len(stdout),
            "base64": base64.b64encode(stdout).decode("ascii"),
        },
        "stderr": {"bytes": 0, "base64": ""},
        "supervisor_error": None,
    }


class BootstrapFixture:
    def __init__(self, mode="inactive"):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / ".git").mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.git = self.executable("git")
        self.gh = self.executable("gh")
        self.agent = self.executable("agent")
        self.make = self.executable("make")
        config = json.loads((REPOSITORY / ".github" / "agent-workflow.json").read_text())
        config["orchestrator"] = {
            "format": "chess-echo-orchestrator-config-v1",
            "mode": mode,
            "frozen_issues": [115],
            "agent_roles": [
                {
                    "role": role,
                    "command_prefix": ["agent"],
                    "cwd": ".",
                    "timeout_ms": 5_000,
                    "grace_ms": 100,
                    "output_limit_bytes": 4_096,
                    "containment": "external-sandbox-v1",
                    "provider_name": "runtime-test-provider",
                    "provider_source_sha256": "4" * 64,
                }
                for role in ("implementer", "planner", "reviewer")
            ],
            "git": {
                "command": ["git"],
                "timeout_ms": 30_000,
                "grace_ms": 1_000,
                "output_limit_bytes": 8 * 1024 * 1024,
            },
            "github": {
                "command": ["gh"],
                "timeout_ms": 30_000,
                "grace_ms": 1_000,
                "output_limit_bytes": 512 * 1024,
            },
            "validation_path": [str(self.bin)],
            "human_approval": {
                "allowed_accounts": [
                    {"account_id": 42, "login": "nathankebede"}
                ],
                "allowed_associations": ["COLLABORATOR", "MEMBER", "OWNER"],
            },
        }
        self.config = (
            json.dumps(config, ensure_ascii=True, indent=2, sort_keys=False) + "\n"
        ).encode()
        payload = b"blob " + str(len(self.config)).encode() + b"\0" + self.config
        self.config_blob = hashlib.sha1(payload).hexdigest()
        self.calls = []

    def close(self):
        self.temporary.cleanup()

    def executable(self, name):
        path = self.bin / name
        path.write_bytes(b"#!/bin/sh\nexit 0\n")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def supervise(self, command, **options):
        command = list(command)
        self.calls.append((command, options))
        args = command[1:]
        if args[:2] == ["-c", "core.fsmonitor=false"]:
            args = args[2:]
        if args == ["api", "repos/NathanZK/ChessEcho"]:
            stdout = b'{"default_branch":"main"}'
        elif args == ["api", "repos/NathanZK/ChessEcho/commits/main"]:
            stdout = ('{"sha":"%s"}' % OID).encode()
        elif args == ["rev-parse", "--verify", "HEAD^{commit}"]:
            stdout = (OID + "\n").encode()
        elif args == [
            "rev-parse",
            "--verify",
            "refs/remotes/origin/main^{commit}",
        ]:
            stdout = (OID + "\n").encode()
        elif args == [
            "rev-parse",
            "--verify",
            "refs/remotes/origin/main^{tree}",
        ]:
            stdout = (TREE + "\n").encode()
        elif args == ["status", "--porcelain=v1", "-z", "--untracked-files=all"]:
            stdout = b""
        elif args == ["ls-files", "-v", "-z"]:
            stdout = b""
        elif args == [
            "for-each-ref",
            "--format=%(refname)%00%(objectname)",
            "refs/replace",
        ]:
            stdout = b""
        elif (
            len(args) == 2
            and args[0] == "rev-parse"
            and args[1].endswith(":.github/agent-workflow.json")
        ):
            stdout = (self.config_blob + "\n").encode()
        elif args == ["rev-parse", "HEAD:.github/agent-workflow.json"]:
            stdout = (self.config_blob + "\n").encode()
        elif (
            len(args) == 2
            and args[0] == "show"
            and args[1].endswith(":.github/agent-workflow.json")
        ):
            stdout = self.config
        elif args == ["show", "HEAD:.github/agent-workflow.json"]:
            stdout = self.config
        elif args == ["api", "repos/NathanZK/ChessEcho/issues/152"]:
            stdout = json.dumps(
                json.loads((FIXTURES / "runtime-github.json").read_text())["issue"]
            ).encode()
        elif args == ["api", "repos/NathanZK/ChessEcho/pulls/155"]:
            stdout = json.dumps(
                json.loads((FIXTURES / "runtime-github.json").read_text())[
                    "pull_request"
                ]
            ).encode()
        elif args == ["api", "repos/NathanZK/ChessEcho/issues/comments/1"]:
            stdout = json.dumps(
                json.loads((FIXTURES / "runtime-github.json").read_text())[
                    "authorization"
                ]
            ).encode()
        elif args == ["api", "repos/NathanZK/ChessEcho/pulls/155/reviews/2"]:
            stdout = json.dumps(
                json.loads((FIXTURES / "runtime-github.json").read_text())["review"]
            ).encode()
        elif args[:2] == ["api", "graphql"]:
            stdout = json.dumps(
                json.loads((FIXTURES / "runtime-github.json").read_text())[
                    "review_graphql"
                ]
            ).encode()
        else:
            return process_result(command)
        return process_result(command, stdout=stdout)

    def bootstrap(self):
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=self.supervise
        ):
            return runtime.bootstrap(
                self.root,
                "NathanZK/ChessEcho",
                self.git,
                self.gh,
                "secret-token",
            )


class WorkflowRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BootstrapFixture()

    def tearDown(self):
        self.fixture.close()

    def active_adapter(self):
        self.fixture.close()
        self.fixture = BootstrapFixture(mode="active")
        return self.fixture.bootstrap()

    def github_write_request(self, adapter):
        pull = json.loads((FIXTURES / "runtime-github.json").read_text())[
            "pull_request"
        ]
        expectation = {
            "repository": "NathanZK/ChessEcho",
            "base_ref": pull["base"]["ref"],
            "base_sha": pull["base"]["sha"],
            "head_ref": pull["head"]["ref"],
            "head_sha": pull["head"]["sha"],
            "title_sha256": inspector.sha256(pull["title"].encode()),
            "body_sha256": inspector.sha256(pull["body"].encode()),
        }
        operation = {"kind": "github-write", "name": "create-draft-pr", "role": None}
        source = {
            "config_binding": reference(),
            "config_content_sha256": inspector.sha256(self.fixture.config),
            "config_blob_oid": self.fixture.config_blob,
            "profile": None,
            "entry": "create-draft-pr",
        }
        limits = {
            "timeout_ms": 30_000,
            "grace_ms": 1_000,
            "output_limit_bytes": 512 * 1024,
        }
        authority = reference(digest="6" * 64)
        attempt = runtime.execution_attempt_id(
            authority, operation, source, [], None, limits, expectation
        )
        request = adapter.build_request(
            issue=152,
            family_run_id=FAMILY,
            attempt_id=attempt,
            authority_binding=authority,
            operation=operation,
            command_source=source,
            input_bindings=[],
            repository_before=None,
            limits=limits,
            reconciliation_expectation=expectation,
        )
        payload = {
            "repository": "NathanZK/ChessEcho",
            "base_ref": pull["base"]["ref"],
            "head_ref": pull["head"]["ref"],
            "title": pull["title"],
            "body": pull["body"],
        }
        return request, expectation, payload, pull

    def request_arguments(self, adapter, reconciliation_expectation=None):
        operation = {
            "kind": "validation",
            "name": "agent-workflow-tests",
            "role": None,
        }
        source = {
            "config_binding": reference(),
            "config_content_sha256": inspector.sha256(self.fixture.config),
            "config_blob_oid": self.fixture.config_blob,
            "profile": "workflow-tooling",
            "entry": "agent-workflow-tests",
        }
        inputs = [{"role": "tests", "binding": reference(digest="5" * 64)}]
        limits = {
            "timeout_ms": 3_600_000,
            "grace_ms": 2_000,
            "output_limit_bytes": 512 * 1024,
        }
        repository_before = self.repository_before()
        attempt = runtime.execution_attempt_id(
            reference(digest="6" * 64),
            operation,
            source,
            inputs,
            repository_before,
            limits,
            reconciliation_expectation,
        )
        return {
            "issue": 152,
            "family_run_id": FAMILY,
            "attempt_id": attempt,
            "authority_binding": reference(digest="6" * 64),
            "operation": operation,
            "command_source": source,
            "input_bindings": inputs,
            "repository_before": repository_before,
            "limits": limits,
            "reconciliation_expectation": reconciliation_expectation,
        }

    def repository_before(self):
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
        document = {
            "format": "chess-echo-work-type-diff-observation-v1",
            "repository": "NathanZK/ChessEcho",
            "issue": 152,
            "family_run_id": FAMILY,
            "triage_binding": reference(digest="a" * 64),
            "observer": {
                "name": "workflow-runtime",
                "version": "1.0.0",
                "source_sha256": inspector.sha256(
                    pathlib.Path(runtime.__file__).read_bytes()
                ),
            },
            "observed_at": "2026-09-05T00:00:00Z",
            "object_format": "sha1",
            "base": {
                "ref": "refs/remotes/origin/main",
                "commit": OID,
                "tree": TREE,
            },
            "head": {"commit": OID, "tree": TREE},
            "ancestry": {"base_is_ancestor": True, "commit_count": 0},
            "changes": [],
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
                "blob_oid": self.fixture.config_blob,
                "content_sha256": inspector.sha256(self.fixture.config),
                "size": len(self.fixture.config),
            },
            "raw_diff_sha256": inspector.sha256(
                inspector.canonical_bytes([])
            ),
        }
        document["observation_sha256"] = inspector.sha256(
            inspector.canonical_bytes(document)
        )
        return document

    def test_bootstrap_pins_remote_tip_head_config_and_executables(self):
        adapter = self.fixture.bootstrap()
        document = adapter.bootstrap_document()
        self.assertEqual(OID, document["remote_tip"])
        self.assertEqual(OID, document["initial_head"])
        self.assertEqual(OID, document["target_base"]["commit"])
        self.assertEqual(TREE, document["target_base"]["tree"])
        self.assertEqual(self.fixture.config_blob, document["config"]["blob_oid"])
        self.assertEqual("inactive", document["mode"])
        make_record = next(
            row
            for row in document["validation_executables"]
            if row["entry"] == "agent-workflow-tests"
        )
        self.assertEqual(str(self.fixture.make), make_record["path"])
        self.assertEqual(
            inspector.sha256(self.fixture.make.read_bytes()), make_record["sha256"]
        )
        self.assertNotIn("secret-token", repr(adapter))
        github_calls = [
            options
            for command, options in self.fixture.calls
            if command[0] == str(self.fixture.gh)
        ]
        self.assertEqual(
            ["GH_HOST", "GH_PROMPT_DISABLED", "GH_TOKEN", "HOME", "LANG", "LC_ALL", "PATH", "TZ"],
            sorted(github_calls[0]["env"]),
        )
        self.assertEqual("secret-token", github_calls[0]["env"]["GH_TOKEN"])

    def test_bootstrap_requires_head_and_tracking_tip_to_match_remote_tip(self):
        original = self.fixture.supervise

        def moved(command, **options):
            result = original(command, **options)
            if list(command)[-3:] == ["rev-parse", "--verify", "HEAD^{commit}"]:
                result["stdout"] = {
                    "bytes": 41,
                    "base64": base64.b64encode(("9" * 40 + "\n").encode()).decode(),
                }
            return result

        with mock.patch.object(runtime.workflow_supervisor, "supervise", side_effect=moved):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                runtime.bootstrap(
                    self.fixture.root,
                    "NathanZK/ChessEcho",
                    self.fixture.git,
                    self.fixture.gh,
                    "token",
                )
        self.assertEqual(("stale", "bootstrap-head-tip-mismatch"), (raised.exception.status, raised.exception.code))

    def test_bootstrap_retries_one_complete_mixed_snapshot(self):
        original = self.fixture.supervise
        tips = iter([OID, "9" * 40, OID, OID])
        calls = 0

        def moving(command, **options):
            nonlocal calls
            args = list(command)[1:]
            if args == ["api", "repos/NathanZK/ChessEcho/commits/main"]:
                calls += 1
                return process_result(command, stdout=json.dumps({"sha": next(tips)}).encode())
            return original(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=moving
        ):
            adapter = runtime.bootstrap(
                self.fixture.root,
                "NathanZK/ChessEcho",
                self.fixture.git,
                self.fixture.gh,
                "token",
            )
        self.assertEqual(OID, adapter.bootstrap_document()["remote_tip"])
        self.assertEqual(4, calls)

        tips = iter([OID, "9" * 40, OID, "9" * 40])
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=moving
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                runtime.bootstrap(
                    self.fixture.root,
                    "NathanZK/ChessEcho",
                    self.fixture.git,
                    self.fixture.gh,
                    "token",
                )
        self.assertEqual("bootstrap-observation-moved", raised.exception.code)

    def test_bootstrap_rejects_hidden_index_flags_replacements_and_alternates(self):
        original = self.fixture.supervise
        for case in ("assume-unchanged", "skip-worktree", "replacement", "alternate"):
            with self.subTest(case=case):
                alternate = self.fixture.root / ".git" / "objects" / "info" / "alternates"
                if alternate.exists():
                    alternate.unlink()
                if case == "alternate":
                    alternate.parent.mkdir(parents=True, exist_ok=True)
                    alternate.write_text("/untrusted/objects\n")

                def untrusted(command, **options):
                    result = original(command, **options)
                    args = list(command)[1:]
                    if args[:2] == ["-c", "core.fsmonitor=false"]:
                        args = args[2:]
                    if case == "assume-unchanged" and args == ["ls-files", "-v", "-z"]:
                        return process_result(command, stdout=b"h hidden.py\0")
                    if case == "skip-worktree" and args == ["ls-files", "-v", "-z"]:
                        return process_result(command, stdout=b"S sparse.py\0")
                    if case == "replacement" and args[:1] == ["for-each-ref"]:
                        return process_result(
                            command,
                            stdout=b"refs/replace/111\0" + b"2" * 40 + b"\n",
                        )
                    return result

                with mock.patch.object(
                    runtime.workflow_supervisor, "supervise", side_effect=untrusted
                ):
                    with self.assertRaises(runtime.RuntimeFailure):
                        runtime.bootstrap(
                            self.fixture.root,
                            "NathanZK/ChessEcho",
                            self.fixture.git,
                            self.fixture.gh,
                            "token",
                        )

    def test_config_order_and_unknown_keys_fail_closed(self):
        cases = []
        wrong_roles = json.loads(self.fixture.config)
        wrong_roles["orchestrator"]["agent_roles"].reverse()
        cases.append(wrong_roles)
        extra = json.loads(self.fixture.config)
        extra["orchestrator"]["unexpected"] = True
        cases.append(extra)
        duplicate_account = json.loads(self.fixture.config)
        duplicate_account["orchestrator"]["human_approval"]["allowed_accounts"].append(
            {"account_id": 42, "login": "other"}
        )
        cases.append(duplicate_account)
        oversized_github = json.loads(self.fixture.config)
        oversized_github["orchestrator"]["github"]["output_limit_bytes"] = 8 * 1024 * 1024
        cases.append(oversized_github)
        injected_path = json.loads(self.fixture.config)
        injected_path["orchestrator"]["validation_path"] = ["/trusted:"]
        cases.append(injected_path)
        for config in cases:
            with self.subTest(config=config["orchestrator"]):
                self.fixture.config = json.dumps(config).encode()
                with self.assertRaises(runtime.RuntimeFailure):
                    self.fixture.bootstrap()

    def test_request_binding_is_verbatim_and_attempt_is_byte_identical(self):
        adapter = self.active_adapter()
        arguments = self.request_arguments(adapter)
        expectation = arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments, reconciliation_expectation=expectation)
        published = reference(digest="7" * 64, size=777)
        with mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            result = adapter.execute(request, published)
        self.assertEqual(published, result["request_binding"])
        self.assertEqual(request["attempt_id"], result["attempt_id"])
        self.assertEqual(
            request["attempt_id"],
            runtime.execution_attempt_id(
                request["authority_binding"],
                request["operation"],
                request["command_source"],
                request["input_bindings"],
                request["repository_before"],
                request["limits"],
                expectation,
            ),
        )
        self.assertEqual(request["repository_before"], result["repository_after"])

    def test_execution_rejects_stale_repository_before_start(self):
        adapter = self.active_adapter()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments)
        changed = copy.deepcopy(request["repository_before"])
        changed["head"]["commit"] = "9" * 40
        unsigned = dict(changed)
        unsigned.pop("observation_sha256")
        changed["observation_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ) as supervise, mock.patch.object(
            runtime.Runtime, "observe_diff", return_value=changed
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(request, reference())
        self.assertEqual("repository-before-mismatch", raised.exception.code)
        self.assertFalse(
            any(pathlib.Path(call.args[0][0]) == self.fixture.make for call in supervise.call_args_list)
        )

    def test_validation_executable_replacement_is_rejected(self):
        adapter = self.active_adapter()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments)
        self.fixture.make.write_bytes(b"#!/bin/sh\nexit 1\n")
        with mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(request, reference())
        self.assertEqual("validation-executable-replaced", raised.exception.code)

    def test_attempt_mismatch_and_config_content_drift_fail_closed(self):
        adapter = self.fixture.bootstrap()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        arguments["attempt_id"] = "8" * 64
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.build_request(**arguments)
        self.assertEqual("attempt-id-mismatch", raised.exception.code)

        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        arguments["command_source"]["config_content_sha256"] = "9" * 64
        arguments["attempt_id"] = runtime.execution_attempt_id(
            arguments["authority_binding"],
            arguments["operation"],
            arguments["command_source"],
            arguments["input_bindings"],
            arguments["repository_before"],
            arguments["limits"],
            None,
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.build_request(**arguments)
        self.assertEqual("request-config-mismatch", raised.exception.code)

    def test_inactive_runtime_never_executes_validation_agent_or_write(self):
        adapter = self.fixture.bootstrap()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments)
        with mock.patch.object(runtime.workflow_supervisor, "supervise") as supervise:
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(request, reference())
        self.assertEqual(("unsupported", "runtime-inactive"), (raised.exception.status, raised.exception.code))
        supervise.assert_not_called()

    def test_caller_cancel_event_is_passed_unchanged(self):
        adapter = self.active_adapter()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments)
        cancel = threading.Event()
        result = process_result(
            [str(self.fixture.make), "agent-workflow-test"],
            outcome="terminated",
            reason="cancelled-before-start",
        )
        def supervised(command, **options):
            if pathlib.Path(command[0]) == self.fixture.make:
                return result
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ) as supervise, mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            document = adapter.execute(request, reference(), cancel_event=cancel)
        execution = next(
            call
            for call in supervise.call_args_list
            if pathlib.Path(call.args[0][0]) == self.fixture.make
        )
        self.assertIs(cancel, execution.kwargs["cancel_event"])
        self.assertEqual("cancelled", document["outcome"])
        cancel.set()
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ) as supervise:
            document = adapter.execute(request, reference(), cancel_event=cancel)
        self.assertEqual("cancelled", document["outcome"])
        self.assertIsNone(document["repository_after"])
        self.assertFalse(
            any(pathlib.Path(call.args[0][0]) == self.fixture.git for call in supervise.call_args_list)
        )

        cancel.clear()

        def late_cancel(command, **options):
            if pathlib.Path(command[0]) == self.fixture.make:
                cancel.set()
                return process_result(command)
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=late_cancel
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            document = adapter.execute(request, reference(), cancel_event=cancel)
        self.assertEqual("cancelled", document["outcome"])
        self.assertIsNone(document["repository_after"])

    def test_agent_requires_exact_injected_sandbox_provider(self):
        adapter = self.active_adapter()
        operation = {"kind": "agent", "name": "run-agent", "role": "planner"}
        source = {
            "config_binding": reference(),
            "config_content_sha256": inspector.sha256(self.fixture.config),
            "config_blob_oid": self.fixture.config_blob,
            "profile": None,
            "entry": "planner",
        }
        limits = {
            "timeout_ms": 5_000,
            "grace_ms": 100,
            "output_limit_bytes": 4_096,
        }
        authority = reference(digest="6" * 64)
        repository_before = self.repository_before()
        attempt = runtime.execution_attempt_id(
            authority, operation, source, [], repository_before, limits, None
        )
        request = adapter.build_request(
            issue=152,
            family_run_id=FAMILY,
            attempt_id=attempt,
            authority_binding=authority,
            operation=operation,
            command_source=source,
            input_bindings=[],
            repository_before=repository_before,
            limits=limits,
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.execute(request, reference())
        self.assertEqual("sandbox-provider-required", raised.exception.code)

        class WrongProvider:
            name = "wrong"
            version = "1"
            source_sha256 = "4" * 64

            def verify(self, request, process_result, candidate):
                raise AssertionError("must not run")

        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.execute(request, reference(), sandbox_provider=WrongProvider())
        self.assertEqual("sandbox-provider-mismatch", raised.exception.code)

    def test_real_fake_agent_requires_verified_external_sandbox_result(self):
        self.fixture.close()
        self.fixture = BootstrapFixture(mode="active")
        config = json.loads(self.fixture.config)
        config["orchestrator"]["validation_path"] = [
            str(FIXTURES),
            str(self.fixture.bin),
        ]
        provider_source = inspector.sha256((FIXTURES / "fake_agent.py").read_bytes())
        for row in config["orchestrator"]["agent_roles"]:
            row["command_prefix"] = ["fake_agent.py"]
            row["provider_source_sha256"] = provider_source
        self.fixture.config = (
            json.dumps(config, ensure_ascii=True, indent=2) + "\n"
        ).encode()
        payload = (
            b"blob "
            + str(len(self.fixture.config)).encode()
            + b"\0"
            + self.fixture.config
        )
        self.fixture.config_blob = hashlib.sha1(payload).hexdigest()
        adapter = self.fixture.bootstrap()
        operation = {"kind": "agent", "name": "run-agent", "role": "planner"}
        source = {
            "config_binding": reference(),
            "config_content_sha256": inspector.sha256(self.fixture.config),
            "config_blob_oid": self.fixture.config_blob,
            "profile": None,
            "entry": "planner",
        }
        limits = {
            "timeout_ms": 5_000,
            "grace_ms": 100,
            "output_limit_bytes": 4_096,
        }
        authority = reference(digest="6" * 64)
        repository_before = self.repository_before()
        attempt = runtime.execution_attempt_id(
            authority, operation, source, [], repository_before, limits, None
        )
        request = adapter.build_request(
            issue=152,
            family_run_id=FAMILY,
            attempt_id=attempt,
            authority_binding=authority,
            operation=operation,
            command_source=source,
            input_bindings=[],
            repository_before=repository_before,
            limits=limits,
        )

        class Provider:
            name = "runtime-test-provider"
            version = "1"
            source_sha256 = provider_source

            def __init__(self):
                self.calls = 0

            def verify(self, request_document, result, candidate):
                self.calls += 1
                document = {
                    "format": "chess-echo-external-sandbox-result-v1",
                    "provider": {
                        "name": self.name,
                        "version": self.version,
                        "source_sha256": self.source_sha256,
                    },
                    "request_sha256": request_document["request_sha256"],
                    "command_sha256": result["command_sha256"],
                    "repository_scope": str(adapter.root),
                    "credential_access": "denied",
                    "authority_store_access": "denied",
                    "containment": "verified",
                    "candidate_sha256": inspector.sha256(candidate),
                    "candidate_size": len(candidate),
                }
                document["result_sha256"] = inspector.sha256(
                    inspector.canonical_bytes(document)
                )
                return document

        original_supervise = runtime.workflow_supervisor.supervise
        calls = []

        def supervised(command, **options):
            calls.append((command, options))
            if pathlib.Path(command[0]) == self.fixture.git:
                return self.fixture.supervise(command, **options)
            return original_supervise(command, **options)

        provider = Provider()
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            result = adapter.execute(
                request, reference(digest="7" * 64), sandbox_provider=provider
            )
        self.assertEqual("succeeded", result["outcome"], result["process_result"])
        self.assertEqual("verified", result["sandbox"]["containment"])
        agent_call = next(
            item for item in calls if pathlib.Path(item[0][0]).name == "fake_agent.py"
        )
        self.assertEqual(["HOME", "LANG", "LC_ALL", "PATH", "TZ"], sorted(agent_call[1]["env"]))
        self.assertEqual(
            reference(digest="7" * 64),
            json.loads(agent_call[0][-1]),
        )
        self.assertEqual(1, provider.calls)

        def failed(command, **options):
            if pathlib.Path(command[0]) == self.fixture.git:
                return self.fixture.supervise(command, **options)
            return process_result(
                command,
                outcome="timeout",
                reason="execution-timeout",
                stdout=b"partial",
            )

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=failed
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            failed_result = adapter.execute(
                request, reference(digest="7" * 64), sandbox_provider=provider
            )
        self.assertEqual("failed", failed_result["outcome"])
        self.assertEqual("verified", failed_result["sandbox"]["containment"])
        self.assertEqual(2, provider.calls)

        class LyingProvider(Provider):
            version = "2"

            def verify(self, request_document, result, candidate):
                document = super().verify(request_document, result, candidate)
                document["provider"]["version"] = "1"
                unsigned = dict(document)
                unsigned.pop("result_sha256")
                document["result_sha256"] = inspector.sha256(
                    inspector.canonical_bytes(unsigned)
                )
                return document

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ), mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(
                    request,
                    reference(digest="7" * 64),
                    sandbox_provider=LyingProvider(),
                )
        self.assertEqual("sandbox-verification-failed", raised.exception.code)

    def test_uncertain_github_write_reconciles_once_without_retry(self):
        adapter = self.active_adapter()
        request, expectation, payload, pull = self.github_write_request(adapter)
        calls = []

        def supervised(command, **options):
            calls.append(command)
            args = list(command)[1:]
            if args[:2] == ["pr", "create"]:
                return process_result(command, outcome="timeout", reason="execution-timeout")
            if args[:3] == ["api", "--paginate", "--slurp"]:
                return process_result(command, stdout=json.dumps([[pull]]).encode())
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ):
            result = adapter.execute(
                request,
                reference(digest="7" * 64),
                reconciliation_expectation=expectation,
                write_payload=payload,
            )
        self.assertEqual("succeeded", result["outcome"])
        self.assertEqual("confirmed", result["reconciliation"]["status"])
        self.assertEqual(1, sum(command[1:3] == ["pr", "create"] for command in calls))
        self.assertEqual(
            1,
            sum(
                len(command) > 2
                and command[1] == "api"
                and command[2:4] == ["--paginate", "--slurp"]
                for command in calls
            ),
        )
        query = next(command[-1] for command in calls if command[1:4] == ["api", "--paginate", "--slurp"])
        self.assertIn("head=NathanZK%3Aruntime", query)

    def test_github_write_command_source_must_match_operation(self):
        adapter = self.active_adapter()
        request, expectation, _payload, _pull = self.github_write_request(adapter)
        source = copy.deepcopy(request["command_source"])
        source["entry"] = "unrelated"
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.build_request(
                issue=request["issue"],
                family_run_id=request["family_run_id"],
                attempt_id=request["attempt_id"],
                authority_binding=request["authority_binding"],
                operation=request["operation"],
                command_source=source,
                input_bindings=request["input_bindings"],
                repository_before=request["repository_before"],
                limits=request["limits"],
                reconciliation_expectation=expectation,
            )
        self.assertEqual("fixed-command-source-mismatch", raised.exception.code)

    def test_github_write_reconciliation_zero_and_multiple_fail_closed(self):
        for candidates, expected in (([], ("uncertain", "unknown")), ([1, 2], ("ambiguous", None))):
            with self.subTest(candidates=len(candidates)):
                adapter = self.active_adapter()
                request, expectation, payload, pull = self.github_write_request(adapter)

                def supervised(command, **options):
                    args = list(command)[1:]
                    if args[:2] == ["pr", "create"]:
                        return process_result(command, outcome="timeout", reason="execution-timeout")
                    if args[:3] == ["api", "--paginate", "--slurp"]:
                        return process_result(
                            command,
                            stdout=json.dumps([[copy.deepcopy(pull) for _ in candidates]]).encode(),
                        )
                    return self.fixture.supervise(command, **options)

                with mock.patch.object(
                    runtime.workflow_supervisor, "supervise", side_effect=supervised
                ):
                    if expected[0] == "ambiguous":
                        with self.assertRaises(runtime.RuntimeFailure) as raised:
                            adapter.execute(
                                request,
                                reference(digest="7" * 64),
                                reconciliation_expectation=expectation,
                                write_payload=payload,
                            )
                        self.assertEqual(
                            "github-write-reconciliation-ambiguous",
                            raised.exception.code,
                        )
                    else:
                        result = adapter.execute(
                            request,
                            reference(digest="7" * 64),
                            reconciliation_expectation=expectation,
                            write_payload=payload,
                        )
                        self.assertEqual(expected, (result["outcome"], result["reconciliation"]["status"]))

    def test_github_write_never_serializes_a_disclosed_token(self):
        adapter = self.active_adapter()
        request, expectation, payload, _pull = self.github_write_request(adapter)

        def supervised(command, **options):
            if list(command)[1:3] == ["pr", "create"]:
                return process_result(command, stdout=b"secret-token")
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(
                    request,
                    reference(digest="7" * 64),
                    reconciliation_expectation=expectation,
                    write_payload=payload,
                )
        self.assertEqual("github-token-disclosed", raised.exception.code)

    def test_github_write_rejects_token_in_payload_before_any_process(self):
        adapter = self.active_adapter()
        request, expectation, payload, _pull = self.github_write_request(adapter)
        payload["title"] = "secret-token"
        expectation["title_sha256"] = inspector.sha256(payload["title"].encode())
        request["attempt_id"] = runtime.execution_attempt_id(
            request["authority_binding"],
            request["operation"],
            request["command_source"],
            request["input_bindings"],
            request["repository_before"],
            request["limits"],
            expectation,
        )
        unsigned = dict(request)
        unsigned.pop("request_sha256")
        request["request_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with mock.patch.object(runtime.workflow_supervisor, "supervise") as supervise:
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.execute(
                    request,
                    reference(digest="7" * 64),
                    reconciliation_expectation=expectation,
                    write_payload=payload,
                )
        self.assertEqual("github-token-in-write-payload", raised.exception.code)
        supervise.assert_not_called()

    def test_precancelled_github_write_does_not_reconcile(self):
        adapter = self.active_adapter()
        request, expectation, payload, _pull = self.github_write_request(adapter)
        cancel = threading.Event()
        cancel.set()
        calls = []

        def supervised(command, **options):
            calls.append(command)
            return process_result(
                command,
                outcome="terminated",
                reason="cancelled-before-start",
            )

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=supervised
        ):
            result = adapter.execute(
                request,
                reference(digest="7" * 64),
                reconciliation_expectation=expectation,
                cancel_event=cancel,
                write_payload=payload,
            )
        self.assertEqual("cancelled", result["outcome"])
        self.assertEqual(1, len(calls))

    def test_github_observations_bind_exact_live_identity_and_challenge(self):
        adapter = self.fixture.bootstrap()
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=self.fixture.supervise
        ):
            pull = adapter.observe_pull_request(
                152, 155, reference(digest="7" * 64), "2026-09-05T00:01:00Z"
            )
            authorization = adapter.observe_authorization(
                workflow_issue=152,
                target_kind="issue",
                target_number=152,
                source_kind="issue-comment",
                source_id=1,
                challenge_binding=reference(digest="8" * 64),
                confirmation="approve exact challenge",
                source_request_binding=reference(digest="9" * 64),
                observed_at="2026-09-05T00:02:00Z",
            )
            review = adapter.observe_authorization(
                workflow_issue=152,
                target_kind="pull-request",
                target_number=155,
                source_kind="pull-request-review",
                source_id=2,
                challenge_binding=reference(digest="8" * 64),
                confirmation="approve exact challenge",
                source_request_binding=reference(digest="9" * 64),
                observed_at="2026-09-05T00:03:00Z",
            )
        self.assertEqual("OPEN", pull["state"])
        self.assertEqual(42, authorization["actor"]["account_id"])
        self.assertEqual(
            inspector.sha256(b"approve exact challenge"),
            authorization["source"]["body_sha256"],
        )
        self.assertEqual("2026-09-05T00:00:01Z", review["source"]["updated_at"])

    def test_edited_authorization_is_rejected(self):
        adapter = self.fixture.bootstrap()

        def edited(command, **options):
            args = list(command)[1:]
            if args == ["api", "repos/NathanZK/ChessEcho/issues/comments/1"]:
                value = json.loads(
                    (FIXTURES / "runtime-github.json").read_text()
                )["authorization"]
                value["updated_at"] = "2026-09-05T00:01:00Z"
                return process_result(command, stdout=json.dumps(value).encode())
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=edited
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.observe_authorization(
                    workflow_issue=152,
                    target_kind="issue",
                    target_number=152,
                    source_kind="issue-comment",
                    source_id=1,
                    challenge_binding=reference(digest="8" * 64),
                    confirmation="approve exact challenge",
                    source_request_binding=reference(digest="9" * 64),
                )
        self.assertEqual("authorization-source-edited", raised.exception.code)

        for case in ("errors", "missing-last-edited"):
            with self.subTest(case=case):
                def invalid_graphql(command, **options):
                    args = list(command)[1:]
                    if args[:2] == ["api", "graphql"]:
                        value = json.loads(
                            (FIXTURES / "runtime-github.json").read_text()
                        )["review_graphql"]
                        if case == "errors":
                            value["errors"] = [{"message": "partial response"}]
                        else:
                            value["data"]["node"].pop("lastEditedAt")
                        return process_result(command, stdout=json.dumps(value).encode())
                    return self.fixture.supervise(command, **options)

                with mock.patch.object(
                    runtime.workflow_supervisor,
                    "supervise",
                    side_effect=invalid_graphql,
                ):
                    with self.assertRaises(runtime.RuntimeFailure):
                        adapter.observe_authorization(
                            workflow_issue=152,
                            target_kind="pull-request",
                            target_number=155,
                            source_kind="pull-request-review",
                            source_id=2,
                            challenge_binding=reference(digest="8" * 64),
                            confirmation="approve exact challenge",
                            source_request_binding=reference(digest="9" * 64),
                        )

        def wrong_review_id(command, **options):
            args = list(command)[1:]
            if args == ["api", "repos/NathanZK/ChessEcho/pulls/155/reviews/2"]:
                value = json.loads(
                    (FIXTURES / "runtime-github.json").read_text()
                )["review"]
                value["id"] = 999
                return process_result(command, stdout=json.dumps(value).encode())
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=wrong_review_id
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.observe_authorization(
                    workflow_issue=152,
                    target_kind="pull-request",
                    target_number=155,
                    source_kind="pull-request-review",
                    source_id=2,
                    challenge_binding=reference(digest="8" * 64),
                    confirmation="approve exact challenge",
                    source_request_binding=reference(digest="9" * 64),
                )
        self.assertEqual("authorization-source-mismatch", raised.exception.code)

        def edited_review(command, **options):
            args = list(command)[1:]
            if args[:2] == ["api", "graphql"]:
                value = json.loads(
                    (FIXTURES / "runtime-github.json").read_text()
                )["review_graphql"]
                value["data"]["node"]["lastEditedAt"] = "2026-09-05T00:02:00Z"
                return process_result(command, stdout=json.dumps(value).encode())
            return self.fixture.supervise(command, **options)

        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=edited_review
        ):
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.observe_authorization(
                    workflow_issue=152,
                    target_kind="pull-request",
                    target_number=155,
                    source_kind="pull-request-review",
                    source_id=2,
                    challenge_binding=reference(digest="8" * 64),
                    confirmation="approve exact challenge",
                    source_request_binding=reference(digest="9" * 64),
                )
        self.assertEqual("authorization-source-edited", raised.exception.code)

    def test_public_cli_rejects_agent_provider_discovery(self):
        parser = runtime.build_parser()
        execute = parser._subparsers._group_actions[0].choices["execute"]
        option_dests = {action.dest for action in execute._actions}
        self.assertNotIn("sandbox_provider", option_dests)

    def test_frozen_issue_is_denied_before_github_lookup(self):
        adapter = self.fixture.bootstrap()
        with mock.patch.object(runtime.workflow_supervisor, "supervise") as supervise:
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.observe_issue(115)
            with self.assertRaises(runtime.RuntimeFailure):
                adapter.observe_pull_request(115, 155, reference())
            with self.assertRaises(runtime.RuntimeFailure):
                adapter.observe_authorization(
                    workflow_issue=115,
                    target_kind="pull-request",
                    target_number=155,
                    source_kind="pull-request-review",
                    source_id=2,
                    challenge_binding=reference(digest="8" * 64),
                    confirmation="approve exact challenge",
                    source_request_binding=reference(digest="9" * 64),
                )
        self.assertEqual(("denied", "issue-frozen"), (raised.exception.status, raised.exception.code))
        supervise.assert_not_called()

    def test_read_only_github_observation_retries_once_but_authorization_does_not(self):
        adapter = self.fixture.bootstrap()
        for guards, expected_calls, expected_code in (
            (["a", "b", "b", "b"], 2, None),
            (["a", "b", "b", "c"], 2, "repository-observation-moved"),
        ):
            with self.subTest(expected_code=expected_code), mock.patch.object(
                runtime.Runtime, "_repository_guard", side_effect=guards
            ), mock.patch.object(
                runtime.workflow_supervisor,
                "supervise",
                side_effect=self.fixture.supervise,
            ) as supervise:
                if expected_code is None:
                    adapter.observe_issue(152, "2026-09-05T00:00:00Z")
                else:
                    with self.assertRaises(runtime.RuntimeFailure) as raised:
                        adapter.observe_issue(152, "2026-09-05T00:00:00Z")
                    self.assertEqual(expected_code, raised.exception.code)
                issue_calls = [
                    call
                    for call in supervise.call_args_list
                    if call.args[0][1:] == [
                        "api",
                        "repos/NathanZK/ChessEcho/issues/152",
                    ]
                ]
                self.assertEqual(expected_calls, len(issue_calls))

        with mock.patch.object(
            runtime.Runtime, "_repository_guard", side_effect=["a", "b"]
        ), mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ) as supervise:
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                adapter.observe_authorization(
                    workflow_issue=152,
                    target_kind="issue",
                    target_number=152,
                    source_kind="issue-comment",
                    source_id=1,
                    challenge_binding=reference(digest="8" * 64),
                    confirmation="approve exact challenge",
                    source_request_binding=reference(digest="9" * 64),
                )
        self.assertEqual("authorization-repository-moved", raised.exception.code)
        self.assertEqual(
            1,
            sum(
                call.args[0][1:]
                == ["api", "repos/NathanZK/ChessEcho/issues/comments/1"]
                for call in supervise.call_args_list
            ),
        )

    def test_environment_does_not_inherit_credentials_or_git_redirection(self):
        adapter = self.active_adapter()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        request = adapter.build_request(**arguments)
        inherited = {
            "GH_TOKEN": "host-secret",
            "GIT_DIR": "/tmp/redirect",
            "SSH_AUTH_SOCK": "/tmp/socket",
            "GNUPGHOME": "/tmp/gnupg",
        }
        with mock.patch.dict(os.environ, inherited, clear=False), mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ) as supervise, mock.patch.object(
            runtime.Runtime,
            "observe_diff",
            return_value=request["repository_before"],
        ):
            adapter.execute(request, reference())
        execution = next(
            call
            for call in supervise.call_args_list
            if pathlib.Path(call.args[0][0]) == self.fixture.make
        )
        environment = execution.kwargs["env"]
        self.assertEqual(["HOME", "LANG", "LC_ALL", "PATH", "TZ"], sorted(environment))

    def test_document_size_and_duplicate_input_bindings_are_rejected(self):
        adapter = self.fixture.bootstrap()
        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        arguments["input_bindings"] *= 2
        arguments["attempt_id"] = runtime.execution_attempt_id(
            arguments["authority_binding"],
            arguments["operation"],
            arguments["command_source"],
            arguments["input_bindings"],
            arguments["repository_before"],
            arguments["limits"],
            None,
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.build_request(**arguments)
        self.assertEqual("duplicate-input-binding", raised.exception.code)

        arguments = self.request_arguments(adapter)
        arguments.pop("reconciliation_expectation")
        arguments["repository_before"]["changes"] = ["x" * 700_000]
        unsigned = dict(arguments["repository_before"])
        unsigned.pop("observation_sha256")
        arguments["repository_before"]["observation_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        arguments["attempt_id"] = runtime.execution_attempt_id(
            arguments["authority_binding"],
            arguments["operation"],
            arguments["command_source"],
            arguments["input_bindings"],
            arguments["repository_before"],
            arguments["limits"],
            None,
        )
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            adapter.build_request(**arguments)
        self.assertEqual("execution-result-budget", raised.exception.code)

    def test_runtime_issue_and_baseline_pass_public_work_type_classification(self):
        adapter = self.fixture.bootstrap()
        with mock.patch.object(
            runtime.workflow_supervisor, "supervise", side_effect=self.fixture.supervise
        ):
            issue, issue_source = adapter.observe_issue(152, observed_at="2026-09-05T00:00:00Z")
        fixture = WorkTypeFixture()
        try:
            fixture.issue = 152
            fixture.family = FAMILY
            fixture.source = fixture.publish_raw("issue-snapshot", issue_source)
            self.assertEqual(fixture.source, issue["source"])
            issue_envelope = fixture.publish_document(
                "issue-snapshot", issue, issue["source"]
            )
            baseline = adapter.build_baseline(152, FAMILY, issue_envelope["binding"])
            baseline_envelope = fixture.publish_document(
                "baseline", baseline, issue_envelope["binding"]
            )
            fixture.issue_document = issue
            fixture.issue_envelope = issue_envelope
            fixture.baseline_document = baseline
            fixture.baseline_envelope = baseline_envelope
            request = fixture.triage_request("implementation")
            result = work_type_policy.classify(
                fixture.root,
                request,
                issue_envelope["binding"]["sha256"],
                baseline_envelope["binding"]["sha256"],
            )
            self.assertEqual("implementation", result["classification"]["work_type"])
        finally:
            fixture.close()

    def test_authority_reader_is_not_needed_to_validate_opaque_bindings(self):
        source = pathlib.Path(runtime.__file__).read_text()
        self.assertNotIn("AuthorityReader", source)
        self.assertNotIn("validate_reference", source)

    def test_real_git_diff_observation_passes_public_completion_assessment(self):
        with tempfile.TemporaryDirectory(dir=REPOSITORY) as directory:
            root = pathlib.Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "runtime@example.test"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Runtime Test"], check=True)
            (root / ".github").mkdir()
            (root / ".github" / "agent-workflow.json").write_bytes(self.fixture.config)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-qm", "base"], check=True)
            base = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD"],
                check=True,
                text=True,
                capture_output=True,
            ).stdout.strip()
            subprocess.run(
                ["git", "-C", str(root), "update-ref", "refs/remotes/origin/main", base],
                check=True,
            )
            original_supervise = runtime.workflow_supervisor.supervise

            def supervised(command, **options):
                if pathlib.Path(command[0]) == self.fixture.gh:
                    if command[1:] == ["api", "repos/NathanZK/ChessEcho"]:
                        data = b'{"default_branch":"main"}'
                    elif command[1:] == ["api", "repos/NathanZK/ChessEcho/commits/main"]:
                        data = json.dumps({"sha": base}).encode()
                    elif command[1:] == ["api", "repos/NathanZK/ChessEcho/issues/152"]:
                        data = json.dumps(
                            json.loads((FIXTURES / "runtime-github.json").read_text())[
                                "issue"
                            ]
                        ).encode()
                    else:
                        raise AssertionError(command)
                    return process_result(command, stdout=data)
                return original_supervise(command, **options)

            with mock.patch.object(
                runtime.workflow_supervisor, "supervise", side_effect=supervised
            ):
                adapter = runtime.bootstrap(
                    root,
                    "NathanZK/ChessEcho",
                    pathlib.Path(shutil.which("git")),
                    self.fixture.gh,
                    "token",
                )
                issue, issue_source = adapter.observe_issue(
                    152, observed_at="2026-09-05T00:00:00Z"
                )
                policy_fixture = WorkTypeFixture()
                try:
                    policy_fixture.issue = 152
                    policy_fixture.family = FAMILY
                    policy_fixture.source = policy_fixture.publish_raw(
                        "issue-snapshot", issue_source
                    )
                    issue_envelope = policy_fixture.publish_document(
                        "issue-snapshot", issue, issue["source"]
                    )
                    baseline = adapter.build_baseline(
                        152, FAMILY, issue_envelope["binding"]
                    )
                    baseline_envelope = policy_fixture.publish_document(
                        "baseline", baseline, issue_envelope["binding"]
                    )
                    policy_fixture.issue_document = issue
                    policy_fixture.issue_envelope = issue_envelope
                    policy_fixture.baseline_document = baseline
                    policy_fixture.baseline_envelope = baseline_envelope
                    triage_request = policy_fixture.triage_request("implementation")
                    triage = work_type_policy.classify(
                        policy_fixture.root,
                        triage_request,
                        issue_envelope["binding"]["sha256"],
                        baseline_envelope["binding"]["sha256"],
                    )
                    triage_envelope = policy_fixture.publish_document(
                        "triage", triage, baseline_envelope["binding"]
                    )
                    (root / "scripts").mkdir()
                    (root / "scripts" / "runtime_change.py").write_text("VALUE = 1\n")
                    subprocess.run(["git", "-C", str(root), "add", "."], check=True)
                    subprocess.run(["git", "-C", str(root), "commit", "-qm", "change"], check=True)
                    observation = adapter.observe_diff(
                        152,
                        FAMILY,
                        triage_envelope["binding"],
                        observed_at="2026-09-05T00:10:00Z",
                    )
                    self.assertEqual(
                        "100644",
                        observation["changes"][0]["new_mode"],
                        observation["changes"],
                    )
                    observation_envelope = policy_fixture.publish_document(
                        "observation", observation, triage_envelope["binding"]
                    )
                    completion = policy_fixture.completion_request(
                        triage_envelope, observation_envelope
                    )
                    assessed = work_type_policy.assess_completion(
                        policy_fixture.root,
                        completion,
                        issue_envelope["binding"]["sha256"],
                        baseline_envelope["binding"]["sha256"],
                        triage_envelope["binding"]["sha256"],
                        observation_envelope["binding"]["sha256"],
                    )
                    self.assertEqual(
                        "implementation-route-conforms", assessed["outcome"]["code"]
                    )
                finally:
                    policy_fixture.close()


if __name__ == "__main__":
    unittest.main()
