import argparse
import ast
import hashlib
import importlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = pathlib.Path(__file__).parents[1]
PRODUCTION_MODULES = (
    "agent_workflow",
    "workflow_cas",
    "workflow_authority",
    "workflow_authority_repair",
    "workflow_evidence",
    "workflow_inspector",
    "workflow_issue_source",
    "workflow_kernel",
    "workflow_local_host",
    "workflow_local_provider",
    "workflow_migration",
    "workflow_orchestrator",
    "workflow_orchestrator_resume",
    "workflow_policy",
    "workflow_plan_revision_policy",
    "workflow_repair",
    "workflow_runtime",
    "workflow_runtime_reconstruction",
    "workflow_supervision_policy",
    "workflow_supervisor",
    "workflow_work_type_policy",
)
ORCHESTRATOR_IMPORTS = {
    "workflow_authority",
    "workflow_evidence",
    "workflow_inspector",
    "workflow_issue_source",
    "workflow_orchestrator_resume",
    "workflow_policy",
    "workflow_plan_revision_policy",
    "workflow_runtime",
    "workflow_supervision_policy",
    "workflow_work_type_policy",
}
KERNEL_EXPORTS = {
    "COMMITTED_MODE",
    "INTEGRITY_FORMAT",
    "VERSION",
    "WorkflowError",
    "adoption_transaction_path",
    "bootstrap_transaction_path",
    "canonical_history_bytes",
    "canonical_state_bytes",
    "committed_envelope",
    "decode_snapshot",
    "encoded_snapshot",
    "history_path",
    "integrity_path",
    "lock_directory",
    "locked_run",
    "parse_history",
    "parse_json_object",
    "pr_transition_transaction_path",
    "run_dir",
    "sha256",
    "state_path",
    "validate_committed_envelope",
    "validate_run_structure",
    "write_committed_snapshot",
    "write_json_atomic",
    "write_text_atomic",
}
COMMAND_HANDLERS = {
    "init": "_dispatch_init",
    "status": "_dispatch_status",
    "adopt-legacy-run": "_dispatch_adopt_legacy",
    "recover-run": "_dispatch_recover_run",
    "submit-plan": "_dispatch_submit_plan",
    "review-plan": "_dispatch_review_plan",
    "approve-plan": "_dispatch_approve_plan",
    "reject-plan": "_dispatch_reject_plan",
    "submit-tests": "_dispatch_submit_tests",
    "review-tests": "_dispatch_review_tests",
    "approve-tests": "_dispatch_approve_tests",
    "reject-tests": "_dispatch_reject_tests",
    "reopen-tests": "_dispatch_reopen_tests",
    "reopen-plan": "_dispatch_reopen_plan",
    "submit-implementation": "_dispatch_submit_implementation",
    "run-validation": "_dispatch_run_validation",
    "review-final": "_dispatch_review_final",
    "create-draft-pr": "_dispatch_create_draft_pr",
    "approve-pr": "_dispatch_approve_pr",
    "revise-pr-metadata": "_dispatch_revise_pr_metadata",
    "reject-pr": "_dispatch_reject_pr",
    "start-correction": "_dispatch_start_correction",
}


def syntax_tree(module):
    return ast.parse((SCRIPTS / ("%s.py" % module)).read_text())


def project_imports_from_tree(tree):
    imports = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            candidates = [alias.name for alias in node.names]
        elif isinstance(node, ast.ImportFrom):
            candidates = []
            if node.module:
                candidates.append(node.module)
            if node.module in (None, "scripts"):
                candidates.extend(alias.name for alias in node.names)
        else:
            continue
        for candidate in candidates:
            imports.update(set(candidate.split(".")).intersection(PRODUCTION_MODULES))
    return imports


def project_imports(module):
    return project_imports_from_tree(syntax_tree(module))


class WorkflowBoundaryTest(unittest.TestCase):
    def test_internal_dependencies_point_only_downward(self):
        self.assertEqual({"workflow_kernel"}, project_imports("agent_workflow"))
        self.assertEqual(set(), project_imports("workflow_cas"))
        self.assertEqual(
            {"workflow_cas", "workflow_evidence", "workflow_inspector"},
            project_imports("workflow_authority"),
        )
        self.assertEqual(
            {"workflow_authority", "workflow_cas", "workflow_inspector"},
            project_imports("workflow_authority_repair"),
        )
        self.assertEqual(
            {"workflow_cas", "workflow_inspector"},
            project_imports("workflow_evidence"),
        )
        self.assertEqual(set(), project_imports("workflow_kernel"))
        self.assertEqual(set(), project_imports("workflow_inspector"))
        self.assertEqual(set(), project_imports("workflow_local_host"))
        self.assertEqual(
            {"workflow_cas", "workflow_evidence", "workflow_inspector", "workflow_supervisor"},
            project_imports("workflow_local_provider"),
        )
        self.assertEqual(
            {"workflow_cas", "workflow_inspector", "workflow_runtime"},
            project_imports("workflow_issue_source"),
        )
        self.assertEqual(
            {
                "workflow_cas",
                "workflow_evidence",
                "workflow_inspector",
                "workflow_kernel",
            },
            project_imports("workflow_migration"),
        )
        self.assertEqual(
            {
                "workflow_evidence",
                "workflow_inspector",
                "workflow_migration",
            },
            project_imports("workflow_policy"),
        )
        self.assertEqual(
            {"workflow_cas", "workflow_inspector"},
            project_imports("workflow_repair"),
        )
        self.assertEqual(set(), project_imports("workflow_supervisor"))
        self.assertEqual(
            {"workflow_inspector"},
            project_imports("workflow_runtime_reconstruction"),
        )
        self.assertEqual(
            {"workflow_inspector", "workflow_runtime_reconstruction", "workflow_supervisor"},
            project_imports("workflow_runtime"),
        )
        self.assertEqual(
            {"workflow_inspector"},
            project_imports("workflow_supervision_policy"),
        )
        self.assertEqual(
            {
                "workflow_evidence",
                "workflow_inspector",
                "workflow_supervisor",
            },
            project_imports("workflow_work_type_policy"),
        )
        self.assertEqual(
            {"workflow_evidence", "workflow_inspector"},
            project_imports("workflow_plan_revision_policy"),
        )
        self.assertEqual(
            ORCHESTRATOR_IMPORTS,
            project_imports("workflow_orchestrator"),
        )
        self.assertEqual(
            {"workflow_inspector"},
            project_imports("workflow_orchestrator_resume"),
        )

    def test_dependency_check_recognizes_qualified_and_relative_imports(self):
        tree = ast.parse(
            "import scripts.agent_workflow\n"
            "from scripts import workflow_repair\n"
            "from . import workflow_kernel\n"
        )
        self.assertEqual(
            {"agent_workflow", "workflow_kernel", "workflow_repair"},
            project_imports_from_tree(tree),
        )

    def test_authority_owns_only_its_approved_persistence_boundary(self):
        authority_path = SCRIPTS / "workflow_authority.py"
        self.assertLessEqual(len(authority_path.read_text().splitlines()), 825)
        allowed_replace = {
            "workflow_authority",
            "workflow_authority_repair",
            "workflow_kernel",
            "workflow_repair",
        }
        allowed_link = {"workflow_cas", "workflow_repair"}
        for module in PRODUCTION_MODULES:
            tree = syntax_tree(module)
            calls = {
                (node.func.value.id, node.func.attr)
                for node in ast.walk(tree)
                if isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
            }
            with self.subTest(module=module):
                self.assertEqual(
                    module in allowed_replace,
                    ("os", "replace") in calls,
                )
                self.assertEqual(module in allowed_link, ("os", "link") in calls)
        authority_tree = syntax_tree("workflow_authority")
        imported = set()
        for node in ast.walk(authority_tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        self.assertTrue(
            {"subprocess", "socket", "urllib", "http", "requests"}.isdisjoint(imported)
        )
        calls = {
            (node.func.value.id, node.func.attr)
            for node in ast.walk(authority_tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
        }
        self.assertTrue(
            {
                ("workflow_cas", "ensure_directory"),
                ("workflow_cas", "fsync_directory"),
                ("workflow_cas", "publish_immutable"),
                ("workflow_cas", "write_all"),
            }.issubset(calls)
        )
        self.assertTrue({("os", "link"), ("os", "mkdir"), ("os", "write")}.isdisjoint(calls))

    def test_production_modules_have_no_shadowed_top_level_definitions(self):
        for module in PRODUCTION_MODULES:
            with self.subTest(module=module):
                names = []
                tree = syntax_tree(module)
                for node in tree.body:
                    if isinstance(
                        node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)
                    ):
                        names.append(node.name)
                self.assertEqual(len(names), len(set(names)))
                self.assertFalse(
                    any(
                        isinstance(node, ast.Call)
                        and isinstance(node.func, ast.Name)
                        and node.func.id == "globals"
                        for node in ast.walk(tree)
                    )
                )

    def test_runtime_has_a_small_exact_external_boundary(self):
        path = SCRIPTS / "workflow_runtime.py"
        self.assertLessEqual(len(path.read_text().splitlines()), 1250)
        tree = syntax_tree("workflow_runtime")
        imported = set()
        inspector_calls = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "workflow_inspector"
            ):
                inspector_calls.add(node.func.attr)
        self.assertTrue(
            {"subprocess", "socket", "urllib", "http", "requests", "importlib"}.isdisjoint(
                imported
            )
        )
        self.assertEqual(
            {"canonical_document", "resolve_store", "sha256"},
            inspector_calls,
        )
        source = path.read_text()
        self.assertNotIn("AuthorityReader", source)
        self.assertNotIn("inspect_repository", source)
        self.assertNotIn("workflow_inspector.inspect", source)

    def test_reconstruction_module_is_pure_with_no_process_or_network_access(self):
        path = SCRIPTS / "workflow_runtime_reconstruction.py"
        self.assertLessEqual(len(path.read_text().splitlines()), 500)
        tree = syntax_tree("workflow_runtime_reconstruction")
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        self.assertTrue(
            {
                "subprocess", "socket", "urllib", "http", "requests", "os", "importlib",
                "workflow_authority", "workflow_cas", "workflow_evidence", "workflow_supervisor",
            }.isdisjoint(imported)
        )
        self.assertEqual({"workflow_inspector"}, project_imports("workflow_runtime_reconstruction"))
        calls = {
            (node.func.value.id, node.func.attr)
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
        }
        self.assertTrue({name for name, _ in calls}.isdisjoint({"os", "subprocess", "socket"}))
        source = path.read_text()
        for prohibited in ("subprocess.", "socket.", "Popen", "os.system", "os.exec", ".commit(", ".publish("):
            self.assertNotIn(prohibited, source)

    def test_runtime_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        for command in (
            [sys.executable, str(SCRIPTS / "workflow_runtime.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_runtime", "--help"],
        ):
            with self.subTest(command=command):
                result = subprocess.run(
                    command, cwd=str(repository), text=True, capture_output=True
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_issue_source_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        path = SCRIPTS / "workflow_issue_source.py"
        self.assertLessEqual(len(path.read_text().splitlines()), 500)
        for command in (
            [sys.executable, str(path), "--help"],
            [sys.executable, "-m", "scripts.workflow_issue_source", "--help"],
        ):
            with self.subTest(command=command):
                result = subprocess.run(
                    command, cwd=str(repository), text=True, capture_output=True
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_issue_source_package_execution_rejects_ambient_module_shadowing(self):
        repository = SCRIPTS.parent
        with tempfile.TemporaryDirectory() as directory:
            pathlib.Path(directory, "workflow_cas.py").write_text(
                "raise RuntimeError('ambient module executed')\n"
            )
            result = subprocess.run(
                [sys.executable, "-m", "scripts.workflow_issue_source", "--help"],
                cwd=directory,
                env={"PYTHONPATH": str(repository)},
                text=True,
                capture_output=True,
            )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_runtime_config_contains_no_test_sandbox_provider(self):
        config_text = (
            SCRIPTS.parent / ".github" / "agent-workflow.json"
        ).read_text()
        config = json.loads(config_text)["orchestrator"]
        fake_hash = hashlib.sha256(
            (
                SCRIPTS
                / "tests"
                / "fixtures"
                / "workflow-orchestrator"
                / "fake_agent.py"
            ).read_bytes()
        ).hexdigest()
        self.assertNotIn("runtime-test-", config_text)
        self.assertNotIn(fake_hash, config_text)
        self.assertEqual("active", config["mode"])
        self.assertEqual(
            hashlib.sha256(
                (SCRIPTS / "workflow_local_host.py").read_bytes()
            ).hexdigest(),
            config["local_host"]["source_sha256"],
        )
        self.assertEqual(
            hashlib.sha256(
                (SCRIPTS / "workflow_local_provider.py").read_bytes()
            ).hexdigest(),
            config["local_host"]["provider"]["source_sha256"],
        )
        for row in config["agent_roles"]:
            self.assertEqual("trusted-local-worktree-v1", row["containment"])
            self.assertEqual(config["local_host"]["provider"]["name"], row["provider_name"])
            self.assertEqual(config["local_host"]["provider"]["version"], row["provider_version"])
            self.assertEqual(
                config["local_host"]["provider"]["source_sha256"],
                row["provider_source_sha256"],
            )
            self.assertEqual(
                config["local_host"]["agent"]["sha256"],
                row["agent_executable_sha256"],
            )

    def test_kernel_symbols_are_imported_not_reimplemented(self):
        tree = syntax_tree("agent_workflow")
        imported = {
            alias.asname or alias.name
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module == "workflow_kernel"
            for alias in node.names
        }
        defined = {
            node.name
            for node in tree.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
        }
        self.assertEqual(KERNEL_EXPORTS, imported)
        self.assertTrue(KERNEL_EXPORTS.isdisjoint(defined))

    def test_legacy_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        commands = (
            [sys.executable, str(SCRIPTS / "agent_workflow.py"), "--help"],
            [sys.executable, "-m", "scripts.agent_workflow", "--help"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=str(repository),
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_migration_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        commands = (
            [sys.executable, str(SCRIPTS / "workflow_migration.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_migration", "--help"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=str(repository),
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_policy_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        commands = (
            [sys.executable, str(SCRIPTS / "workflow_policy.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_policy", "--help"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=str(repository),
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_work_type_policy_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        commands = (
            [
                sys.executable,
                str(SCRIPTS / "workflow_work_type_policy.py"),
                "--help",
            ],
            [sys.executable, "-m", "scripts.workflow_work_type_policy", "--help"],
        )
        for command in commands:
            with self.subTest(command=command):
                result = subprocess.run(
                    command,
                    cwd=str(repository),
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_work_type_policy_parser_has_explicit_handlers(self):
        work_type = importlib.import_module("scripts.workflow_work_type_policy")
        parser = work_type.build_parser()
        subparsers = next(
            action
            for action in parser._actions
            if isinstance(action, argparse._SubParsersAction)
        )
        self.assertEqual(set(subparsers.choices), set(work_type.COMMAND_HANDLERS))
        for handler in work_type.COMMAND_HANDLERS.values():
            self.assertIs(handler, getattr(work_type, handler.__name__))

    def test_resume_module_has_no_authority_commit_cas_publish_or_lifecycle_transition(self):
        path = SCRIPTS / "workflow_orchestrator_resume.py"
        self.assertLessEqual(len(path.read_text().splitlines()), 500)
        tree = syntax_tree("workflow_orchestrator_resume")
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        self.assertTrue(
            {
                "subprocess", "socket", "urllib", "http", "requests", "os", "importlib",
                "workflow_authority", "workflow_cas", "workflow_evidence",
                "workflow_supervisor", "workflow_runtime",
            }.isdisjoint(imported)
        )
        self.assertEqual({"workflow_inspector"}, project_imports("workflow_orchestrator_resume"))
        calls = {
            (node.func.value.id, node.func.attr)
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
        }
        self.assertTrue(
            {("authority", "commit"), ("authority", "prepare"), ("evidence", "publish")}.isdisjoint(calls)
        )
        self.assertTrue({name for name, _ in calls}.isdisjoint({"os", "subprocess", "socket"}))
        source = path.read_text()
        for prohibited in ("subprocess.", "socket.", "os.system", ".commit(", ".publish("):
            self.assertNotIn(prohibited, source)
        self.assertNotIn("def resume_phase", source)
        for phase in (
            "PLANNING", "PLAN_REVIEW", "TEST_IMPLEMENTATION", "TEST_REVIEW",
            "IMPLEMENTATION", "VALIDATION", "FINAL_REVIEW", "PR_PREPARATION",
        ):
            self.assertNotIn('"%s"' % phase, source)

    def test_orchestrator_is_thin_and_composes_only_public_apis(self):
        path = SCRIPTS / "workflow_orchestrator.py"
        self.assertLessEqual(len(path.read_text().splitlines()), 1010)
        tree = syntax_tree("workflow_orchestrator")
        tops = [
            node for node in tree.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
        ]
        self.assertLessEqual(len(tops), 30)
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                statements = sum(
                    1 for child in ast.walk(node)
                    if isinstance(child, ast.stmt)
                    and not isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
                )
                with self.subTest(function=node.name):
                    self.assertLessEqual(statements, 60)
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add((node.module or "").split(".")[0])
        self.assertTrue(
            {"subprocess", "socket", "urllib", "http", "requests", "fcntl", "ctypes", "os"}.isdisjoint(imported)
        )
        downward = project_imports("workflow_orchestrator")
        for prohibited in ("agent_workflow", "workflow_cas", "workflow_supervisor",
                           "workflow_kernel", "workflow_repair"):
            self.assertNotIn(prohibited, downward)
        calls = {
            (node.func.value.id, node.func.attr)
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
        }
        self.assertTrue({("os", "replace"), ("os", "link")}.isdisjoint(calls))
        for module in ("workflow_cas", "workflow_supervisor", "agent_workflow"):
            self.assertFalse(any(name == module for name, _ in calls))
        private_lower_calls = {
            (node.func.value.id, node.func.attr)
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name)
            and node.func.value.id in {
                "authority", "evidence", "inspector", "plan_policy", "policy",
                "issue_source", "resume", "runtime", "supervision", "work_type_policy",
            }
            and node.func.attr.startswith("_")
        }
        self.assertEqual(set(), private_lower_calls)
        self.assertIn(("evidence", "publish"), calls)
        self.assertIn(("authority", "prepare"), calls)
        self.assertIn(("authority", "commit"), calls)

    def test_orchestrator_parser_has_explicit_handlers(self):
        module = importlib.import_module("scripts.workflow_orchestrator")
        parser = module.build_parser()
        subparsers = next(
            action for action in parser._actions
            if isinstance(action, argparse._SubParsersAction)
        )
        self.assertEqual(set(subparsers.choices), set(module.COMMAND_HANDLERS))
        self.assertEqual(
            {"status", "plan-next", "init", "step", "approve", "set-supervision", "cancel", "recover"},
            set(module.COMMAND_HANDLERS),
        )
        for handler in module.COMMAND_HANDLERS.values():
            self.assertIs(handler, getattr(module, handler.__name__))

    def test_orchestrator_cli_supports_script_and_package_execution(self):
        repository = SCRIPTS.parent
        for command in (
            [sys.executable, str(SCRIPTS / "workflow_orchestrator.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_orchestrator", "--help"],
        ):
            with self.subTest(command=command):
                result = subprocess.run(
                    command, cwd=str(repository), text=True, capture_output=True
                )
                self.assertEqual(0, result.returncode, result.stderr)

    def test_plan_revision_policy_cli_and_parser_handlers(self):
        repository = SCRIPTS.parent
        for command in (
            [sys.executable, str(SCRIPTS / "workflow_plan_revision_policy.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_plan_revision_policy", "--help"],
        ):
            with self.subTest(command=command):
                result = subprocess.run(command, cwd=str(repository), text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
        policy = importlib.import_module("scripts.workflow_plan_revision_policy")
        parser = policy.build_parser()
        subparsers = next(
            action for action in parser._actions if isinstance(action, argparse._SubParsersAction)
        )
        self.assertEqual(set(subparsers.choices), set(policy.COMMAND_HANDLERS))
        for handler in policy.COMMAND_HANDLERS.values():
            self.assertIs(handler, getattr(policy, handler.__name__))

    def test_every_parser_command_has_one_explicit_handler(self):
        workflow = importlib.import_module("scripts.agent_workflow")
        parser = workflow.build_parser()
        subparsers = next(
            action
            for action in parser._actions
            if isinstance(action, argparse._SubParsersAction)
        )
        self.assertEqual(set(subparsers.choices), set(COMMAND_HANDLERS))
        self.assertEqual(
            COMMAND_HANDLERS,
            {
                command: handler.__name__
                for command, handler in workflow.COMMAND_HANDLERS.items()
            },
        )
        for handler in workflow.COMMAND_HANDLERS.values():
            self.assertIs(handler, getattr(workflow, handler.__name__))


if __name__ == "__main__":
    unittest.main()
