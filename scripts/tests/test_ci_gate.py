import os
import pathlib
import subprocess
import unittest

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPOSITORY_ROOT / "scripts" / "ci_gate.sh"


class CiGateTest(unittest.TestCase):
    def run_gate(self, env_vars: dict) -> subprocess.CompletedProcess:
        # Inherit system PATH, but isolate gate variables
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            **env_vars,
        }
        return subprocess.run(
            [str(SCRIPT_PATH)],
            capture_output=True,
            text=True,
            env=env,
        )

    def test_s01_backend_only_pass(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "success",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "success",
        })
        self.assertEqual(res.returncode, 0, f"Expected 0, got {res.returncode}: {res.stderr}")
        self.assertIn("All applicable component checks succeeded", res.stdout)

    def test_s02_frontend_only_pass(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "false",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "true",
            "FRONTEND_RESULT": "success",
            "DOCKER_FILTER": "false",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 0, f"Expected 0, got {res.returncode}: {res.stderr}")
        self.assertIn("All applicable component checks succeeded", res.stdout)

    def test_s03_docker_only_pass(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "false",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "success",
        })
        self.assertEqual(res.returncode, 0, f"Expected 0, got {res.returncode}: {res.stderr}")
        self.assertIn("All applicable component checks succeeded", res.stdout)

    def test_s04_mixed_pass(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "success",
            "FRONTEND_FILTER": "true",
            "FRONTEND_RESULT": "success",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "success",
        })
        self.assertEqual(res.returncode, 0, f"Expected 0, got {res.returncode}: {res.stderr}")
        self.assertIn("All applicable component checks succeeded", res.stdout)

    def test_s05_docs_only_pass(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "false",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "false",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 0, f"Expected 0, got {res.returncode}: {res.stderr}")
        self.assertIn("All applicable component checks succeeded", res.stdout)

    def test_s06_backend_failure_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "failure",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("build job was 'failure'", res.stdout)

    def test_s07_frontend_failure_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "false",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "true",
            "FRONTEND_RESULT": "failure",
            "DOCKER_FILTER": "false",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("frontend job was 'failure'", res.stdout)

    def test_s08_docker_failure_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "success",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "failure",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("docker job was 'failure'", res.stdout)

    def test_s09_changes_failure_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "failure",
            "BACKEND_FILTER": "",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("Changes detection job did not succeed", res.stdout)

    def test_s10_changes_cancelled_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "cancelled",
            "BACKEND_FILTER": "",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("Changes detection job did not succeed", res.stdout)

    def test_s11_job_cancelled_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "cancelled",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("build job was 'cancelled'", res.stdout)

    def test_s12_bypass_trap_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "true",
            "BACKEND_RESULT": "skipped",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "true",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("build job was 'skipped' (expected 'success')", res.stdout)

    def test_s13_invalid_filter_output_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "invalid_val",
            "BACKEND_RESULT": "success",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "false",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("Missing or invalid filter output for backend", res.stdout)

    def test_s14_unexpected_job_run_blocked(self):
        res = self.run_gate({
            "CHANGES_RESULT": "success",
            "BACKEND_FILTER": "false",
            "BACKEND_RESULT": "success",
            "FRONTEND_FILTER": "false",
            "FRONTEND_RESULT": "skipped",
            "DOCKER_FILTER": "false",
            "DOCKER_RESULT": "skipped",
        })
        self.assertEqual(res.returncode, 1)
        self.assertIn("build job was 'success' (expected 'skipped')", res.stdout)


if __name__ == "__main__":
    unittest.main()
