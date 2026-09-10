#!/usr/bin/env python3
"""Run one complete, deterministic partition of the workflow unittest suite."""

import argparse
import pathlib
import re
import subprocess
import sys
import unittest


REPOSITORY = pathlib.Path(__file__).resolve().parents[1]
TESTS = REPOSITORY / "scripts" / "tests"

if str(REPOSITORY) not in sys.path:
    sys.path.insert(0, str(REPOSITORY))


def flatten_tests(suite):
    tests = []
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            tests.extend(flatten_tests(item))
        else:
            tests.append(item)
    return tests


def discover_tests(top_level_dir=REPOSITORY):
    suite = unittest.defaultTestLoader.discover(
        start_dir=str(TESTS),
        pattern="test_*.py",
        top_level_dir=str(top_level_dir) if top_level_dir is not None else None,
    )
    return flatten_tests(suite)


def partition_tests(tests, index, count):
    if count <= 0 or index < 0 or index >= count:
        raise ValueError("shard index must select one of count positive shards")
    return [
        (position, test)
        for position, test in enumerate(tests)
        if position % count == index
    ]


def run_isolated(test, stream):
    if test.__class__.__name__ == "_FailedTest":
        return unittest.TextTestRunner(stream=stream).run(
            unittest.TestSuite([test])
        ).wasSuccessful(), 0

    completed = subprocess.run(
        [sys.executable, "-m", "unittest", test.id()],
        cwd=REPOSITORY,
        text=True,
        capture_output=True,
    )
    stream.write(completed.stdout)
    stream.write(completed.stderr)
    skipped = sum(
        int(value)
        for value in re.findall(r"skipped=(\d+)", completed.stdout + completed.stderr)
    )
    return completed.returncode == 0, skipped


def run_shard(index, count, stream=None):
    stream = stream or sys.stderr
    tests = discover_tests()
    selected = partition_tests(tests, index, count)
    if not selected:
        raise ValueError("selected workflow test shard is empty")

    print(
        "Workflow test shard %d/%d selected %d of %d discovered tests"
        % (index, count, len(selected), len(tests)),
        file=stream,
    )
    for position, test in selected:
        print(
            "SHARD_TEST position=%d id=%s" % (position, test.id()),
            file=stream,
        )
    stream.flush()

    failed = 0
    skipped = 0
    for _position, test in selected:
        successful, test_skips = run_isolated(test, stream)
        failed += not successful
        skipped += test_skips
    print(
        "Workflow test shard result: ran=%d skipped=%d failed=%d"
        % (len(selected), skipped, failed),
        file=stream,
    )
    return failed == 0


def main(argv=None, stream=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", required=True, type=int)
    parser.add_argument("--count", required=True, type=int)
    arguments = parser.parse_args(argv)
    try:
        successful = run_shard(arguments.index, arguments.count, stream)
    except ValueError as error:
        parser.error(str(error))
    return 0 if successful else 1


if __name__ == "__main__":
    raise SystemExit(main())
