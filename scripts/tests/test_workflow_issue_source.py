import concurrent.futures
import copy
import json
import pathlib
import tempfile
import unittest
from unittest import mock

from scripts import workflow_cas
from scripts import workflow_inspector as inspector
from scripts import workflow_issue_source as issue_source
from scripts import workflow_runtime as runtime
from scripts.tests.test_workflow_runtime import BootstrapFixture


SLUG = "NathanZK/ChessEcho"
ISSUE = 152


class WorkflowIssueSourceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = BootstrapFixture()
        self.addCleanup(self.fixture.close)
        self.adapter = self.fixture.bootstrap()

    def publish(self):
        with mock.patch.object(
            issue_source.runtime, "bootstrap", return_value=self.adapter
        ), mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ):
            return issue_source.publish(
                self.fixture.root,
                SLUG,
                ISSUE,
                self.fixture.git,
                self.fixture.gh,
                "secret-token",
            )

    def observation(self):
        with mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ):
            return self.adapter.observe_issue(
                ISSUE, observed_at="2026-09-05T00:00:00Z"
            )

    def test_publishes_only_exact_observed_bytes_and_returns_bound_reference(self):
        publication = self.publish()
        source = publication["source"]
        store = inspector.resolve_store(self.fixture.root)
        raw = inspector.object_path(store, source["sha256"]).read_bytes()
        self.assertEqual(source["sha256"], inspector.sha256(raw))
        self.assertEqual(source["size"], len(raw))
        self.assertEqual(self.adapter.bootstrap_document(), publication["bootstrap"])
        self.assertEqual(
            {"git", "github"}, set(publication["bootstrap"]["executables"])
        )
        objects = [
            path
            for path in (store.store_dir / "objects" / "sha256").glob("*/*")
            if path.is_file()
        ]
        self.assertEqual([inspector.object_path(store, source["sha256"])], objects)
        self.assertFalse((store.store_dir / "issues").exists())
        self.assertFalse((self.fixture.root / ".agent-workflow").exists())

    def test_identical_and_concurrent_publication_is_idempotent(self):
        snapshot, raw = self.observation()
        later = copy.deepcopy(snapshot)
        later["captured_at"] = "2026-09-05T00:00:01Z"
        unsigned = dict(later)
        unsigned.pop("snapshot_sha256")
        later["snapshot_sha256"] = inspector.sha256(inspector.canonical_bytes(unsigned))
        with mock.patch.object(
            issue_source.runtime, "bootstrap", return_value=self.adapter
        ), mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ), mock.patch.object(
            runtime.Runtime,
            "observe_issue",
            side_effect=[(snapshot, raw), (later, raw)],
        ):
            first = issue_source.publish(
                self.fixture.root,
                SLUG,
                ISSUE,
                self.fixture.git,
                self.fixture.gh,
                "secret-token",
            )
            later_publication = issue_source.publish(
                self.fixture.root,
                SLUG,
                ISSUE,
                self.fixture.git,
                self.fixture.gh,
                "secret-token",
            )
        self.assertEqual(first, later_publication)
        with mock.patch.object(
            issue_source.runtime, "bootstrap", return_value=self.adapter
        ), mock.patch.object(
            runtime.workflow_supervisor,
            "supervise",
            side_effect=self.fixture.supervise,
        ), concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            results = list(
                executor.map(
                    lambda _index: issue_source.publish(
                        self.fixture.root,
                        SLUG,
                        ISSUE,
                        self.fixture.git,
                        self.fixture.gh,
                        "secret-token",
                    ),
                    range(8),
                )
            )
        self.assertTrue(all(result == first for result in results))

    def test_identity_hash_size_and_snapshot_mismatches_fail_before_publication(self):
        snapshot, raw = self.observation()
        malformed = (
            ("wrong-repository", snapshot, raw, "runtime-bootstrap-identity"),
            (
                "wrong-issue",
                snapshot,
                json.dumps(
                    {
                        **json.loads(raw),
                        "number": ISSUE + 1,
                        "url": "https://api.github.com/repos/%s/issues/%d"
                        % (SLUG, ISSUE + 1),
                        "html_url": "https://github.com/%s/issues/%d"
                        % (SLUG, ISSUE + 1),
                    }
                ).encode(),
                "issue-identity-mismatch",
            ),
            (
                "float-issue",
                snapshot,
                json.dumps({**json.loads(raw), "number": float(ISSUE)}).encode(),
                "issue-identity-mismatch",
            ),
            (
                "boolean-issue",
                snapshot,
                json.dumps({**json.loads(raw), "number": True}).encode(),
                "issue-identity-mismatch",
            ),
            (
                "wrong-api",
                snapshot,
                json.dumps({**json.loads(raw), "url": "https://api.github.com/repos/other/repo/issues/152"}).encode(),
                "issue-identity-mismatch",
            ),
            (
                "wrong-html",
                snapshot,
                json.dumps({**json.loads(raw), "html_url": "https://github.com/other/repo/issues/152"}).encode(),
                "issue-identity-mismatch",
            ),
            (
                "wrong-hash",
                {**snapshot, "source": {**snapshot["source"], "sha256": "0" * 64}},
                raw,
                "issue-snapshot-relationship",
            ),
            (
                "wrong-size",
                {**snapshot, "source": {**snapshot["source"], "size": len(raw) + 1}},
                raw,
                "issue-snapshot-relationship",
            ),
            (
                "wrong-snapshot",
                {**snapshot, "title": "substituted"},
                raw,
                "issue-snapshot-relationship",
            ),
            ("malformed", snapshot, b'{"number":', "invalid-json"),
        )
        for name, candidate_snapshot, candidate_raw, code in malformed:
            with self.subTest(name=name), mock.patch.object(
                issue_source.runtime,
                "bootstrap",
                return_value=self.adapter,
            ), mock.patch.object(
                runtime.Runtime,
                "observe_issue",
                return_value=(candidate_snapshot, candidate_raw),
            ):
                repository = "other/repo" if name == "wrong-repository" else SLUG
                with self.assertRaises(issue_source.IssueSourceFailure) as raised:
                    issue_source.publish(
                        self.fixture.root,
                        repository,
                        ISSUE,
                        self.fixture.git,
                        self.fixture.gh,
                        "secret-token",
                    )
            self.assertEqual(code, raised.exception.code)
        self.assertFalse(
            (inspector.resolve_store(self.fixture.root).store_dir / "objects").exists()
        )

    def test_collision_fails_closed_without_overwrite(self):
        _snapshot, raw = self.observation()
        store = inspector.resolve_store(self.fixture.root)
        path = inspector.object_path(store, inspector.sha256(raw))
        workflow_cas.publish_immutable(
            path,
            b"conflicting",
            lambda status, code, message: (_ for _ in ()).throw(
                AssertionError((status, code, message))
            ),
        )
        with self.assertRaises(issue_source.IssueSourceFailure) as raised:
            self.publish()
        self.assertEqual("immutable-object-collision", raised.exception.code)
        self.assertEqual(b"conflicting", path.read_bytes())

    def test_interruption_has_no_success_marker_and_restart_converges(self):
        snapshot, raw = self.observation()
        store = inspector.resolve_store(self.fixture.root)
        path = inspector.object_path(store, snapshot["source"]["sha256"])
        with mock.patch.object(
            issue_source.workflow_cas,
            "publish_immutable",
            side_effect=KeyboardInterrupt,
        ):
            with self.assertRaises(KeyboardInterrupt):
                self.publish()
        self.assertFalse(path.exists())
        publication = self.publish()
        self.assertEqual(raw, path.read_bytes())
        self.assertEqual(snapshot["source"], publication["source"])

    def test_frozen_issue_is_denied_before_runtime_and_store_access(self):
        with mock.patch.object(
            issue_source.runtime, "bootstrap", side_effect=AssertionError("runtime")
        ), mock.patch.object(
            issue_source.inspector,
            "resolve_store",
            side_effect=AssertionError("store"),
        ):
            with self.assertRaises(issue_source.IssueSourceFailure) as raised:
                issue_source.publish(
                    self.fixture.root,
                    SLUG,
                    115,
                    self.fixture.git,
                    self.fixture.gh,
                    "secret-token",
                )
        self.assertEqual(("denied", "issue-frozen"), (raised.exception.status, raised.exception.code))

    def test_validation_rejects_edit_and_caller_substitution(self):
        publication = self.publish()
        snapshot, raw = self.observation()
        bootstrap = self.adapter.bootstrap_document()
        self.assertEqual(
            publication["source"],
            issue_source.validate_for_initialization(
                publication, bootstrap, snapshot, raw, SLUG, ISSUE
            ),
        )
        edited_raw = json.dumps({**json.loads(raw), "body": "edited"}).encode()
        edited_snapshot = copy.deepcopy(snapshot)
        edited_snapshot["body"] = "edited"
        edited_snapshot["source"] = {
            "kind": "issue-snapshot",
            "sha256": inspector.sha256(edited_raw),
            "size": len(edited_raw),
        }
        unsigned = dict(edited_snapshot)
        unsigned.pop("snapshot_sha256")
        edited_snapshot["snapshot_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with self.assertRaises(issue_source.IssueSourceFailure) as raised:
            issue_source.validate_for_initialization(
                publication, bootstrap, edited_snapshot, edited_raw, SLUG, ISSUE
            )
        self.assertEqual("issue-source-edited", raised.exception.code)

        forged = copy.deepcopy(publication)
        forged["source"]["sha256"] = "0" * 64
        unsigned = dict(forged)
        unsigned.pop("publication_sha256")
        forged["publication_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with self.assertRaises(issue_source.IssueSourceFailure) as raised:
            issue_source.validate_for_initialization(
                forged, bootstrap, snapshot, raw, SLUG, ISSUE
            )
        self.assertEqual("issue-source-edited", raised.exception.code)

    def test_public_entry_point_has_no_caller_payload_or_reference(self):
        command = issue_source.build_parser()._subparsers._group_actions[0].choices[
            "publish"
        ]
        option_dests = {action.dest for action in command._actions}
        self.assertTrue(
            {"issue", "root", "repository", "git_executable", "gh_executable"}
            <= option_dests
        )
        self.assertTrue(
            {"request", "payload", "bytes", "source", "reference"}.isdisjoint(
                option_dests
            )
        )


if __name__ == "__main__":
    unittest.main()
