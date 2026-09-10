import collections
import io
import subprocess
import sys
import unittest
from unittest import mock

from scripts import run_workflow_test_shard as shard_runner


class WorkflowTestShardTest(unittest.TestCase):
    def test_package_discovery_matches_the_serial_test_identity_multiset(self):
        serial = shard_runner.discover_tests(top_level_dir=None)
        packaged = shard_runner.discover_tests()

        self.assertEqual(
            collections.Counter(test.id() for test in serial),
            collections.Counter(
                test.id().removeprefix("scripts.tests.")
                for test in packaged
            ),
        )

    def test_partitions_preserve_every_discovered_position_and_module(self):
        tests = shard_runner.discover_tests()
        partitions = [
            shard_runner.partition_tests(tests, index, 4)
            for index in range(4)
        ]
        mapped = [item for partition in partitions for item in partition]

        self.assertEqual(
            list(range(len(tests))),
            sorted(position for position, _test in mapped),
        )
        self.assertEqual(len(tests), len({position for position, _test in mapped}))
        self.assertEqual(
            collections.Counter(test.id().rsplit(".", 2)[0] for test in tests),
            collections.Counter(
                test.id().rsplit(".", 2)[0] for _position, test in mapped
            ),
        )

    def test_partitions_are_deterministic_without_deduplicating_test_ids(self):
        class DuplicateIdTest(unittest.TestCase):
            def test_value(self):
                pass

        duplicate = DuplicateIdTest("test_value")
        tests = [duplicate, duplicate, DuplicateIdTest("test_value")]

        first = shard_runner.partition_tests(tests, 0, 2)
        second = shard_runner.partition_tests(tests, 0, 2)

        self.assertEqual([0, 2], [position for position, _test in first])
        self.assertEqual(
            [test.id() for _position, test in first],
            [test.id() for _position, test in second],
        )
        self.assertEqual(2, len(first))

    def test_invalid_coordinates_and_test_failures_return_nonzero(self):
        for index, count in ((-1, 4), (4, 4), (0, 0)):
            with self.subTest(index=index, count=count):
                with self.assertRaises(ValueError):
                    shard_runner.partition_tests([], index, count)

        class SelectedTest(unittest.TestCase):
            def test_selected(self):
                pass

        with mock.patch.object(
            shard_runner,
            "discover_tests",
            return_value=[SelectedTest("test_selected")],
        ), mock.patch.object(
            shard_runner.subprocess,
            "run",
            return_value=subprocess.CompletedProcess([], 1, "", "expected"),
        ):
            self.assertFalse(shard_runner.run_shard(0, 1, io.StringIO()))

    def test_script_execution_preserves_repository_import_path(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(shard_runner.REPOSITORY / "scripts" / "run_workflow_test_shard.py"),
                "--index",
                "0",
                "--count",
                "100000",
            ],
            cwd=shard_runner.REPOSITORY,
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn(
            "selected 1 of %d discovered tests" % len(shard_runner.discover_tests()),
            completed.stderr,
        )
        self.assertNotIn("_FailedTest", completed.stderr)


if __name__ == "__main__":
    unittest.main()
