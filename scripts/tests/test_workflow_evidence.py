import base64
import concurrent.futures
import copy
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import workflow_cas
from scripts import workflow_inspector as inspector


sys.modules.setdefault("workflow_cas", workflow_cas)
sys.modules.setdefault("workflow_inspector", inspector)
from scripts import workflow_evidence as evidence


SCRIPTS = pathlib.Path(__file__).parents[1]
INSPECTOR_TEST_PATH = SCRIPTS / "tests" / "test_workflow_inspector.py"
SPEC = importlib.util.spec_from_file_location(
    "workflow_inspector_fixture", INSPECTOR_TEST_PATH
)
FIXTURES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURES)
InspectorFixture = FIXTURES.InspectorFixture


class EvidenceFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.authority = InspectorFixture(self.root)
        self.payload = b"canonical payload\n"

    def close(self):
        self.temporary.cleanup()

    def entry(self, path="src/example.txt", payload=None, kind="regular", mode="100644"):
        payload = self.payload if payload is None else payload
        digest = hashlib.sha256(payload).hexdigest()
        return {
            "path": path,
            "kind": kind,
            "mode": mode,
            "content_sha256": digest,
            "size": len(payload),
            "payload": {"kind": "evidence-payload", "sha256": digest, "size": len(payload)},
        }

    def deleted(self, path="src/deleted.txt"):
        return {
            "path": path,
            "kind": "deleted",
            "mode": None,
            "content_sha256": None,
            "size": None,
            "payload": None,
        }

    def capture(self, entry, source=None, captured_at="2026-09-03T00:00:00Z"):
        source = source or {"type": "workspace", "path": entry["path"]}
        return {
            "entry_sha256": evidence._entry_digest(entry),
            "capture_method": "fixture",
            "captured_at": captured_at,
            "source": source,
            "tool": {"name": "test", "version": "1"},
        }

    def publication(
        self,
        entries=None,
        captures=None,
        payloads=None,
        identity=None,
        lineage=None,
        migration=None,
    ):
        entries = entries or [self.entry()]
        captures = captures or [self.capture(entry) for entry in entries]
        if payloads is None:
            unique = {}
            for entry in entries:
                if entry["kind"] != "deleted":
                    unique[entry["content_sha256"]] = self.payload
            payloads = [
                {
                    "sha256": digest,
                    "size": len(data),
                    "bytes_base64": base64.b64encode(data).decode("ascii"),
                }
                for digest, data in unique.items()
            ]
        identity = identity or {
            "issue": self.authority.issue,
            "run_id": self.authority.run_id,
            "family_run_id": self.authority.run_id,
            "correction": None,
            "run_generation": 0,
            "sequence": 1,
            "event_tip": json.loads(
                self.authority.object_path(self.authority.refs["run-event"]).read_text()
            )["sha256"],
        }
        return {
            "format": evidence.PUBLICATION_FORMAT,
            "identity": identity,
            "decision": {"type": "test-contract", "id": "decision-1"},
            "subject": self.authority.refs["run-state"],
            "lineage": lineage or {"status": "original", "parent_binding": None},
            "migration": migration,
            "entries": entries,
            "captures": captures,
            "payloads": payloads,
        }

    def publish(self, **kwargs):
        return evidence.publish(self.root, self.publication(**kwargs))

    def object_path(self, reference):
        return self.authority.object_path(reference)

    def snapshot(self):
        store = self.authority.store
        result = {}
        for path in sorted(store.rglob("*")):
            relative = path.relative_to(store).as_posix()
            if path.is_file():
                result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
            elif path.is_symlink():
                result[relative] = ("symlink", path.readlink().as_posix())
        return result


class WorkflowEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = EvidenceFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, action):
        with self.assertRaises(evidence.EvidenceFailure) as raised:
            action()
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def test_limits_are_the_approved_contract(self):
        self.assertEqual(64 * 1024 * 1024, evidence.PAYLOAD_LIMIT)
        self.assertEqual(8 * 1024 * 1024, evidence.STRUCTURED_OBJECT_LIMIT)
        self.assertEqual(10_000, evidence.MANIFEST_ENTRY_LIMIT)
        self.assertEqual(512 * 1024 * 1024, evidence.PUBLICATION_PAYLOAD_LIMIT)

    def test_publish_verify_and_project_are_canonical(self):
        result = self.fixture.publish()
        verified = evidence.verify(self.fixture.root, result["binding"])
        projection = evidence.project(self.fixture.root, result["binding"])
        self.assertEqual("resolved", result["outcome"]["status"])
        self.assertEqual("resolved", verified["outcome"]["status"])
        self.assertEqual(evidence.PROJECTION_FORMAT, projection["format"])
        self.assertEqual(1, projection["entry_count"])
        self.assertEqual(
            inspector.canonical_bytes(projection),
            inspector.canonical_bytes(json.loads(inspector.canonical_bytes(projection))),
        )

    def test_semantic_identity_excludes_provenance(self):
        first = self.fixture.publish()
        entry = self.fixture.entry()
        second = self.fixture.publish(
            captures=[
                self.fixture.capture(
                    entry,
                    source={
                        "type": "git",
                        "path": entry["path"],
                        "commit": "1" * 40,
                        "oid": "2" * 40,
                    },
                    captured_at="2026-09-03T00:00:01Z",
                )
            ]
        )
        self.assertEqual(first["manifest"], second["manifest"])
        self.assertNotEqual(first["provenance"], second["provenance"])
        self.assertNotEqual(first["binding"], second["binding"])

    def test_regular_executable_symlink_deleted_and_sparse_source(self):
        regular = self.fixture.entry("a", payload=self.fixture.payload)
        executable = self.fixture.entry("b", payload=self.fixture.payload, mode="100755")
        symlink = self.fixture.entry(
            "c", payload=self.fixture.payload, kind="symlink", mode="120000"
        )
        deleted = self.fixture.deleted("d")
        entries = [regular, executable, symlink, deleted]
        captures = [
            self.fixture.capture(regular),
            self.fixture.capture(executable),
            self.fixture.capture(symlink),
            self.fixture.capture(
                deleted,
                source={
                    "type": "git",
                    "path": "d",
                    "commit": "1" * 40,
                    "oid": "2" * 40,
                },
            ),
        ]
        result = self.fixture.publish(entries=entries, captures=captures)
        projection = evidence.project(self.fixture.root, result["binding"])
        self.assertEqual(["a", "b", "c", "d"], [item["path"] for item in projection["entries"]])
        payload_objects = [
            item for item in result["objects"] if item["kind"] == "evidence-payload"
        ]
        self.assertEqual(1, len(payload_objects))

    def test_duplicate_paths_are_ambiguous(self):
        entry = self.fixture.entry()
        request = self.fixture.publication(entries=[entry, copy.deepcopy(entry)])
        self.assert_failure(
            "ambiguous",
            "duplicate-evidence-path",
            lambda: evidence.publish(self.fixture.root, request),
        )

    def test_missing_and_unreferenced_payloads_fail_closed(self):
        request = self.fixture.publication(payloads=[])
        self.assert_failure(
            "missing",
            "publication-payload-missing",
            lambda: evidence.publish(self.fixture.root, request),
        )
        request = self.fixture.publication()
        request["payloads"].append(
            {
                "sha256": hashlib.sha256(b"extra").hexdigest(),
                "size": 5,
                "bytes_base64": base64.b64encode(b"extra").decode("ascii"),
            }
        )
        self.assert_failure(
            "ambiguous",
            "unreferenced-publication-payload",
            lambda: evidence.publish(self.fixture.root, request),
        )

    def test_payload_and_structured_limits_fail_closed(self):
        with mock.patch.object(evidence, "PAYLOAD_LIMIT", 3):
            self.assert_failure(
                "unsupported",
                "payload-too-large",
                lambda: evidence.publish(self.fixture.root, self.fixture.publication()),
            )
        with mock.patch.object(evidence, "STRUCTURED_OBJECT_LIMIT", 32):
            self.assert_failure(
                "unsupported",
                "structured-object-too-large",
                lambda: evidence.publish(self.fixture.root, self.fixture.publication()),
            )

    def test_manifest_and_total_payload_limits_fail_closed(self):
        entries = [self.fixture.deleted("p/%05d" % index) for index in range(3)]
        with mock.patch.object(evidence, "MANIFEST_ENTRY_LIMIT", 2):
            self.assert_failure(
                "unsupported",
                "manifest-entry-limit",
                lambda: evidence.publish(
                    self.fixture.root,
                    self.fixture.publication(
                        entries=entries,
                        captures=[self.fixture.capture(entry) for entry in entries],
                        payloads=[],
                    ),
                ),
            )
        with mock.patch.object(evidence, "PUBLICATION_PAYLOAD_LIMIT", 3):
            self.assert_failure(
                "unsupported",
                "publication-payload-limit",
                lambda: evidence.publish(self.fixture.root, self.fixture.publication()),
            )

    def test_corrupt_and_missing_stored_payload_fail_closed(self):
        result = self.fixture.publish()
        projection = evidence.project(self.fixture.root, result["binding"])
        payload_path = self.fixture.object_path(projection["entries"][0]["payload"])
        payload_path.write_bytes(b"corrupt")
        self.assert_failure(
            "corrupt",
            "object-hash-or-size-mismatch",
            lambda: evidence.verify(self.fixture.root, result["binding"]),
        )
        payload_path.unlink()
        self.assert_failure(
            "missing",
            "object-missing",
            lambda: evidence.verify(self.fixture.root, result["binding"]),
        )

    def test_expectation_mismatch_is_stale(self):
        result = self.fixture.publish()
        actual_identity = result_identity(self.fixture, result)
        exact = {
            "identity": actual_identity,
            "subject": self.fixture.authority.refs["run-state"],
        }
        self.assertEqual(
            "resolved",
            evidence.verify(
                self.fixture.root, result["binding"], exact
            )["outcome"]["status"],
        )
        expectation = {
            "identity": {
                **actual_identity,
                "sequence": 2,
            },
            "subject": self.fixture.authority.refs["run-state"],
        }
        self.assert_failure(
            "stale",
            "binding-identity-stale",
            lambda: evidence.verify(self.fixture.root, result["binding"], expectation),
        )
        boolean_identity = copy.deepcopy(exact)
        boolean_identity["identity"]["sequence"] = True
        self.assert_failure(
            "corrupt",
            "invalid-binding-identity",
            lambda: evidence.verify(
                self.fixture.root, result["binding"], boolean_identity
            ),
        )
        boolean_subject = copy.deepcopy(exact)
        boolean_subject["subject"]["size"] = True
        self.assert_failure(
            "corrupt",
            "invalid-object-size",
            lambda: evidence.verify(
                self.fixture.root, result["binding"], boolean_subject
            ),
        )

    def test_inheritance_requires_exact_manifest_and_subject(self):
        parent = self.fixture.publish()
        identity = {
            **result_identity(self.fixture, parent),
            "run_id": "f" * 32,
            "correction": 1,
            "run_generation": 0,
        }
        inherited = self.fixture.publish(
            identity=identity,
            lineage={"status": "inherited", "parent_binding": parent["binding"]},
        )
        self.assertEqual(
            "inherited",
            evidence.verify(self.fixture.root, inherited["binding"])["lineage"]["status"],
        )
        changed = self.fixture.entry(payload=b"changed")
        request = self.fixture.publication(
            entries=[changed],
            captures=[self.fixture.capture(changed)],
            payloads=[
                {
                    "sha256": changed["content_sha256"],
                    "size": changed["size"],
                    "bytes_base64": base64.b64encode(b"changed").decode("ascii"),
                }
            ],
            identity={**identity, "run_id": "e" * 32},
            lineage={"status": "inherited", "parent_binding": parent["binding"]},
        )
        self.assert_failure(
            "stale",
            "inherited-evidence-changed",
            lambda: evidence.publish(self.fixture.root, request),
        )

    def test_replacement_allows_changed_manifest(self):
        parent = self.fixture.publish()
        changed = self.fixture.entry(payload=b"changed")
        request = self.fixture.publication(
            entries=[changed],
            captures=[self.fixture.capture(changed)],
            payloads=[
                {
                    "sha256": changed["content_sha256"],
                    "size": changed["size"],
                    "bytes_base64": base64.b64encode(b"changed").decode("ascii"),
                }
            ],
            identity={
                **result_identity(self.fixture, parent),
                "run_id": "e" * 32,
                "correction": 1,
            },
            lineage={"status": "replacement", "parent_binding": parent["binding"]},
        )
        replacement = evidence.publish(self.fixture.root, request)
        self.assertNotEqual(parent["manifest"], replacement["manifest"])

    def test_lineage_rejects_another_family(self):
        parent = self.fixture.publish()
        request = self.fixture.publication(
            identity={
                **result_identity(self.fixture, parent),
                "run_id": "e" * 32,
                "family_run_id": "d" * 32,
                "correction": 1,
            },
            lineage={"status": "inherited", "parent_binding": parent["binding"]},
        )
        self.assert_failure(
            "stale",
            "lineage-identity-mismatch",
            lambda: evidence.publish(self.fixture.root, request),
        )

    def test_explicit_null_migration_is_valid(self):
        result = self.fixture.publish(migration=None)
        self.assertIsNone(evidence.project(self.fixture.root, result["binding"])["migration"])

    def test_concurrent_identical_publication_is_idempotent(self):
        request = self.fixture.publication()
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
            results = list(
                executor.map(
                    lambda _index: evidence.publish(self.fixture.root, request),
                    range(12),
                )
            )
        self.assertEqual(1, len({item["binding"]["sha256"] for item in results}))

    def test_collision_is_ambiguous(self):
        request = self.fixture.publication()
        digest = request["payloads"][0]["sha256"]
        path = inspector.object_path(inspector.resolve_store(self.fixture.root), digest)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"collision")
        self.assert_failure(
            "ambiguous",
            "immutable-object-collision",
            lambda: evidence.publish(self.fixture.root, request),
        )

    def test_interrupted_publication_has_no_binding_commit_point(self):
        request = self.fixture.publication()
        decoded = evidence._load_publication(request)
        binding_path = self.fixture.object_path(decoded["binding"][2])
        real_publish = evidence._publish

        def interrupt(store, reference, data):
            if reference["kind"] == "evidence-binding":
                raise KeyboardInterrupt
            return real_publish(store, reference, data)

        with mock.patch.object(evidence, "_publish", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                evidence.publish(self.fixture.root, request)
        self.assertFalse(binding_path.exists())

    def test_v4_adapter_is_read_only_and_types_absent_null_and_recorded_migration(self):
        before = self.fixture.snapshot()
        absent = evidence.adapt_v4(self.fixture.root, self.fixture.authority.issue)
        self.assertEqual("not-recorded", absent["migration"]["status"])
        self.assertEqual(before, self.fixture.snapshot())

        self.fixture.authority.rewrite_state(lambda state: state.update({"migration": None}))
        null_snapshot = self.fixture.snapshot()
        null = evidence.adapt_v4(self.fixture.root, self.fixture.authority.issue)
        self.assertEqual("none", null["migration"]["status"])
        self.assertEqual(null_snapshot, self.fixture.snapshot())

        event = json.loads(
            self.fixture.authority.object_path(
                self.fixture.authority.refs["run-event"]
            ).read_text()
        )
        history_event = {
            key: value for key, value in event.items()
            if key not in {"kind", "previous_event", "run_id", "sha256"}
        }
        legacy_history = (json.dumps(history_event, sort_keys=True) + "\n").encode()
        legacy_ref = self.fixture.authority.install(
            "legacy-raw-evidence", legacy_history, raw=True
        )
        self.fixture.authority.rewrite_state(
            lambda state: state.update(
                {
                    "migration": {
                        "raw_evidence": {
                            "history.jsonl": {"object": legacy_ref}
                        }
                    }
                }
            )
        )
        recorded_snapshot = self.fixture.snapshot()
        recorded = evidence.adapt_v4(self.fixture.root, self.fixture.authority.issue)
        self.assertEqual("recorded", recorded["migration"]["status"])
        self.assertEqual(recorded_snapshot, self.fixture.snapshot())

    def test_cli_emits_canonical_json(self):
        request = self.fixture.publication()
        request_path = self.fixture.root / "publication.json"
        request_path.write_text(json.dumps(request))
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPTS / "workflow_evidence.py"),
                "publish",
                "--root",
                str(self.fixture.root),
                "--request",
                str(request_path),
            ],
            check=True,
            stdout=subprocess.PIPE,
        )
        self.assertEqual(inspector.canonical_document(json.loads(result.stdout)), result.stdout)

    def test_projection_size_is_independent_of_payload_size(self):
        small = self.fixture.publish()
        small_size = len(inspector.canonical_bytes(evidence.project(self.fixture.root, small["binding"])))
        payload = b"x" * (1024 * 1024)
        entry = self.fixture.entry(payload=payload)
        large = self.fixture.publish(
            entries=[entry],
            captures=[self.fixture.capture(entry)],
            payloads=[
                {
                    "sha256": entry["content_sha256"],
                    "size": len(payload),
                    "bytes_base64": base64.b64encode(payload).decode("ascii"),
                }
            ],
        )
        large_size = len(inspector.canonical_bytes(evidence.project(self.fixture.root, large["binding"])))
        self.assertLess(abs(large_size - small_size), 64)


def result_identity(fixture, result):
    return evidence.project(fixture.root, result["binding"])["identity"]


class FrozenIssue115EvidenceAdapterTest(unittest.TestCase):
    def test_frozen_issue_115_adapter_is_read_only_when_available(self):
        repository = SCRIPTS.parent
        store = inspector.resolve_store(repository)
        pointer = store.store_dir / "issues" / "115" / "index-integrity.json"
        if not pointer.is_file():
            self.skipTest("frozen #115 durable pointer is not available")
        before = pointer.read_bytes()
        result = evidence.adapt_v4(repository, 115)
        self.assertEqual("resolved", result["outcome"]["status"])
        self.assertEqual(before, pointer.read_bytes())


if __name__ == "__main__":
    unittest.main()
