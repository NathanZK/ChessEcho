"""Regression checks for ChessEcho's Fidenaut consumer contract."""

import json
import pathlib
import re
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG_PATH = REPOSITORY_ROOT / ".github" / "agent-workflow.json"
DOC_PATH = REPOSITORY_ROOT / "docs" / "engineering" / "agent-workflow.md"
PROVIDER_REPOSITORY = "github.com/NathanZK/Fidenaut"
PROVIDER_REVISION = "c01e94575aecb3a3edfd26c35c1c6f2095b29000"
CONSUMER_TEST_PATH = "scripts/tests/test_fidenaut_consumer.py"


class FidenautConsumerContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        cls.documentation = DOC_PATH.read_text(encoding="utf-8")

    def test_consumer_configuration_pins_the_provider_revision(self):
        provider = self.config.get("provider_runtime")
        self.assertIsNotNone(
            provider,
            "consumer config must pin the provider runtime",
        )
        self.assertEqual(PROVIDER_REPOSITORY, provider.get("repository"))
        self.assertEqual(PROVIDER_REVISION, provider.get("revision"))

    def test_consumer_validation_is_separate_from_provider_tests(self):
        profile = self.config["validation_profiles"].get("fidenaut-consumer")
        self.assertIsNotNone(
            profile,
            "consumer config must define a Fidenaut consumer validation profile",
        )
        self.assertEqual([CONSUMER_TEST_PATH], profile.get("test_paths"))
        checks = profile.get("checks", [])
        self.assertEqual(1, len(checks))
        self.assertEqual(
            [
                "python3",
                "-m",
                "unittest",
                "scripts.tests.test_fidenaut_consumer",
            ],
            checks[0].get("command"),
        )
        self.assertNotIn("run_agent_workflow_tests", str(checks))
        self.assertNotIn("agent-workflow-test", str(checks))

    def test_operational_documentation_uses_the_external_provider_boundary(self):
        for required in (
            PROVIDER_REVISION,
            "--consumer-root",
            "--provider-runtime-root",
            "--provider-manifest",
            "fidenaut-provider-runtime-manifest-v1",
            "scripts/agent_workflow.py",
            "scripts/workflow_supervisor.py",
        ):
            with self.subTest(required=required):
                self.assertTrue(
                    required in self.documentation,
                    "operational documentation must include %s" % required,
                )
        self.assertTrue(
            re.search(
                r"provider runtime.{0,100}outside the ChessEcho worktree",
                self.documentation,
                re.IGNORECASE | re.DOTALL,
            ),
            "documentation must keep the provider runtime outside the consumer worktree",
        )

    def test_consumer_does_not_add_a_provider_launcher(self):
        self.assertFalse(
            (REPOSITORY_ROOT / "scripts" / "fidenaut_consumer.py").exists(),
            "ChessEcho must invoke Fidenaut directly instead of adding a launcher",
        )

    def test_embedded_workflow_test_suites_are_retired(self):
        for path in (
            REPOSITORY_ROOT / "scripts" / "tests" / "test_agent_workflow.py",
            REPOSITORY_ROOT / "scripts" / "tests" / "test_workflow_supervisor.py",
        ):
            with self.subTest(path=path.name):
                self.assertFalse(
                    path.exists(),
                    "embedded workflow test suite must not remain: %s" % path.name,
                )


if __name__ == "__main__":
    unittest.main()
