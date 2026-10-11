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


if __name__ == "__main__":
    unittest.main()
