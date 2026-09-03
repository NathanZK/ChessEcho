import concurrent.futures
import pathlib
import tempfile
import unittest
from unittest import mock

from scripts import workflow_cas


class CasFailure(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code


def fail(status, code, message):
    raise CasFailure(status, code, message)


class WorkflowCasTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.path = self.root / "objects" / "sha256" / "aa" / "object"

    def tearDown(self):
        self.temporary.cleanup()

    def test_publication_is_immutable_and_idempotent(self):
        workflow_cas.publish_immutable(self.path, b"payload", fail)
        workflow_cas.publish_immutable(self.path, b"payload", fail)
        self.assertEqual(b"payload", self.path.read_bytes())

    def test_concurrent_identical_publication_is_idempotent(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = [
                executor.submit(
                    workflow_cas.publish_immutable, self.path, b"same", fail
                )
                for _ in range(16)
            ]
            for future in futures:
                future.result()
        self.assertEqual(b"same", self.path.read_bytes())

    def test_collision_fails_closed(self):
        workflow_cas.publish_immutable(self.path, b"first", fail)
        with self.assertRaises(CasFailure) as raised:
            workflow_cas.publish_immutable(self.path, b"second", fail)
        self.assertEqual("immutable-object-collision", raised.exception.code)
        self.assertEqual(b"first", self.path.read_bytes())

    def test_symlink_destination_fails_closed(self):
        self.path.parent.mkdir(parents=True)
        target = self.root / "target"
        target.write_bytes(b"target")
        self.path.symlink_to(target)
        with self.assertRaises(CasFailure) as raised:
            workflow_cas.publish_immutable(self.path, b"payload", fail)
        self.assertEqual("immutable-destination-not-regular", raised.exception.code)
        self.assertEqual(b"target", target.read_bytes())

    def test_interruption_before_link_leaves_no_object_or_temporary(self):
        real_link = workflow_cas.os.link

        def interrupt(source, destination):
            if pathlib.Path(destination) == self.path:
                raise KeyboardInterrupt
            return real_link(source, destination)

        with mock.patch.object(workflow_cas.os, "link", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                workflow_cas.publish_immutable(self.path, b"payload", fail)
        self.assertFalse(self.path.exists())
        self.assertEqual([], list(self.path.parent.iterdir()))


if __name__ == "__main__":
    unittest.main()
