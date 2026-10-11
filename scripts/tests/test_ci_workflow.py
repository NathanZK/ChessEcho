import pathlib
import unittest
import yaml

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "ci.yml"


class CiWorkflowStructureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw_content = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.raw_content)

    def test_workflow_triggers_and_permissions(self):
        # Ensure 'on' key is present as string and not parsed as boolean True
        self.assertIn("'on':", self.raw_content)
        triggers = self.workflow.get("on") or self.workflow.get(True)
        self.assertIsInstance(triggers, dict, "Expected triggers mapping under 'on'")
        self.assertIn("pull_request", triggers)
        self.assertIn("push", triggers)
        self.assertEqual(triggers["pull_request"]["branches"], ["main"])
        self.assertEqual(triggers["push"]["branches"], ["main"])

        # Least-privilege permissions
        self.assertEqual(self.workflow.get("permissions"), {"contents": "read"})

    def test_job_graph_dependency_integrity(self):
        jobs = self.workflow.get("jobs", {})
        self.assertIn("changes", jobs)
        self.assertIn("build", jobs)
        self.assertIn("frontend", jobs)
        self.assertIn("docker", jobs)
        self.assertIn("ci-gate", jobs)

        # Ensure all 'needs' targets exist in jobs
        for job_id, job_def in jobs.items():
            needs = job_def.get("needs", [])
            if isinstance(needs, str):
                needs = [needs]
            for dep in needs:
                self.assertIn(dep, jobs, f"Job '{job_id}' depends on non-existent job '{dep}'")

    def test_docker_conditional_build_precondition(self):
        jobs = self.workflow.get("jobs", {})
        docker_job = jobs.get("docker", {})
        docker_if = docker_job.get("if", "")

        self.assertIn("always()", docker_if)
        self.assertIn("needs.changes.result == 'success'", docker_if)
        self.assertIn("needs.changes.outputs.docker == 'true'", docker_if)
        self.assertIn("needs.changes.outputs.backend == 'true' && needs.build.result == 'success'", docker_if)
        self.assertIn("needs.changes.outputs.backend == 'false' && needs.build.result == 'skipped'", docker_if)

    def test_ci_gate_job_configuration(self):
        jobs = self.workflow.get("jobs", {})
        gate = jobs.get("ci-gate", {})

        self.assertEqual(gate.get("name"), "CI Gate")
        self.assertEqual(gate.get("if"), "always()")
        self.assertEqual(gate.get("timeout-minutes"), 5)

        expected_needs = {"changes", "build", "frontend", "docker"}
        actual_needs = set(gate.get("needs", []))
        self.assertEqual(expected_needs, actual_needs)

        steps = gate.get("steps", [])
        self.assertTrue(len(steps) >= 2)
        gate_steps = [s for s in steps if "./scripts/ci_gate.sh" in s.get("run", "")]
        self.assertEqual(len(gate_steps), 1, "Expected exactly one step running ./scripts/ci_gate.sh")
        step = gate_steps[0]
        env = step.get("env", {})
        self.assertIn("CHANGES_RESULT", env)
        self.assertIn("BACKEND_FILTER", env)
        self.assertIn("BACKEND_RESULT", env)
        self.assertIn("FRONTEND_FILTER", env)
        self.assertIn("FRONTEND_RESULT", env)
        self.assertIn("DOCKER_FILTER", env)
        self.assertIn("DOCKER_RESULT", env)


class DependencySubmissionWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_path = REPOSITORY_ROOT / ".github" / "workflows" / "dependency-submission.yml"
        cls.raw_content = cls.workflow_path.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.raw_content)

    def test_workflow_triggers_and_permissions(self):
        self.assertIn("'on':", self.raw_content)
        triggers = self.workflow.get("on") or self.workflow.get(True)
        self.assertIsInstance(triggers, dict)
        self.assertIn("push", triggers)
        self.assertEqual(triggers["push"]["branches"], ["main"])
        self.assertIn("workflow_dispatch", triggers)
        self.assertEqual(self.workflow.get("permissions"), {"contents": "write"})

    def test_job_and_step_configuration(self):
        jobs = self.workflow.get("jobs", {})
        self.assertIn("dependency-submission", jobs)
        job = jobs["dependency-submission"]
        self.assertEqual(job.get("runs-on"), "ubuntu-latest")
        steps = job.get("steps", [])
        uses = [s.get("uses", "") for s in steps]
        self.assertTrue(any("actions/checkout" in u for u in uses))
        self.assertTrue(any("actions/setup-java" in u for u in uses))
        self.assertTrue(any("gradle/actions/dependency-submission" in u for u in uses))


class DependencyReviewWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_path = REPOSITORY_ROOT / ".github" / "workflows" / "dependency-review.yml"
        cls.raw_content = cls.workflow_path.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.raw_content)

    def test_workflow_triggers_and_permissions(self):
        self.assertIn("'on':", self.raw_content)
        triggers = self.workflow.get("on") or self.workflow.get(True)
        self.assertIsInstance(triggers, dict)
        self.assertIn("pull_request", triggers)
        self.assertEqual(triggers["pull_request"]["branches"], ["main"])
        self.assertEqual(self.workflow.get("permissions"), {"contents": "read"})

    def test_job_and_step_configuration(self):
        jobs = self.workflow.get("jobs", {})
        self.assertIn("dependency-review", jobs)
        job = jobs["dependency-review"]
        self.assertEqual(job.get("runs-on"), "ubuntu-latest")
        steps = job.get("steps", [])
        uses = [s.get("uses", "") for s in steps]
        self.assertTrue(any("actions/checkout" in u for u in uses))
        review_steps = [s for s in steps if "actions/dependency-review-action" in s.get("uses", "")]
        self.assertEqual(len(review_steps), 1)
        step = review_steps[0]
        self.assertEqual(step.get("with", {}).get("fail-on-severity"), "high")


class CodeqlWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow_path = REPOSITORY_ROOT / ".github" / "workflows" / "codeql.yml"
        cls.raw_content = cls.workflow_path.read_text(encoding="utf-8")
        cls.workflow = yaml.safe_load(cls.raw_content)

    def test_workflow_triggers_and_permissions(self):
        self.assertIn("'on':", self.raw_content)
        triggers = self.workflow.get("on") or self.workflow.get(True)
        self.assertIsInstance(triggers, dict)
        self.assertIn("push", triggers)
        self.assertEqual(triggers["push"]["branches"], ["main"])
        self.assertIn("pull_request", triggers)
        self.assertEqual(triggers["pull_request"]["branches"], ["main"])
        self.assertIn("schedule", triggers)

        permissions = self.workflow.get("permissions", {})
        self.assertEqual(permissions.get("security-events"), "write")
        self.assertEqual(permissions.get("contents"), "read")

    def test_matrix_languages_and_build_modes(self):
        jobs = self.workflow.get("jobs", {})
        self.assertIn("analyze", jobs)
        job = jobs["analyze"]
        strategy = job.get("strategy", {})
        matrix = strategy.get("matrix", {})
        matrix_include = matrix.get("include", [])

        lang_to_mode = {item["language"]: item["build-mode"] for item in matrix_include}
        self.assertIn("actions", lang_to_mode)
        self.assertEqual(lang_to_mode["actions"], "none")

        self.assertIn("javascript-typescript", lang_to_mode)
        self.assertEqual(lang_to_mode["javascript-typescript"], "none")

        self.assertIn("python", lang_to_mode)
        self.assertEqual(lang_to_mode["python"], "none")

        self.assertIn("java-kotlin", lang_to_mode)
        self.assertEqual(lang_to_mode["java-kotlin"], "manual")

    def test_manual_build_steps_configured(self):
        jobs = self.workflow.get("jobs", {})
        job = jobs["analyze"]
        steps = job.get("steps", [])

        # Check Java 21 setup step
        java_steps = [s for s in steps if "actions/setup-java" in s.get("uses", "")]
        self.assertTrue(len(java_steps) >= 1, "Expected actions/setup-java step")
        java_step = java_steps[0]
        self.assertEqual(str(java_step.get("with", {}).get("java-version")), "21")
        self.assertIn("matrix.build-mode == 'manual'", java_step.get("if", ""))

        # Check build step
        build_steps = [s for s in steps if "./gradlew" in s.get("run", "")]
        self.assertTrue(len(build_steps) >= 1, "Expected ./gradlew build step")
        build_step = build_steps[0]
        self.assertIn("matrix.build-mode == 'manual'", build_step.get("if", ""))


if __name__ == "__main__":
    unittest.main()

