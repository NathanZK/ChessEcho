import argparse
import ast
import importlib
import pathlib
import subprocess
import sys
import unittest


SCRIPTS = pathlib.Path(__file__).parents[1]
PRODUCTION_MODULES = (
    "agent_workflow",
    "workflow_cas",
    "workflow_authority",
    "workflow_evidence",
    "workflow_inspector",
    "workflow_kernel",
    "workflow_migration",
    "workflow_policy",
    "workflow_plan_revision_policy",
    "workflow_repair",
    "workflow_supervisor",
    "workflow_work_type_policy",
)
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
            {"workflow_cas", "workflow_inspector"},
            project_imports("workflow_evidence"),
        )
        self.assertEqual(set(), project_imports("workflow_kernel"))
        self.assertEqual(set(), project_imports("workflow_inspector"))
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
        self.assertLessEqual(len(authority_path.read_text().splitlines()), 700)
        allowed_replace = {
            "workflow_authority",
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
