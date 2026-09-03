import base64
import concurrent.futures
import copy
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_migration as migration


SCRIPTS = pathlib.Path(__file__).parents[1]
REPOSITORY = SCRIPTS.parent
FIXTURES = SCRIPTS / "tests" / "fixtures" / "workflow-migration"
INSPECTOR_TEST = SCRIPTS / "tests" / "test_workflow_inspector.py"
SPEC = importlib.util.spec_from_file_location("migration_inspector_fixture", INSPECTOR_TEST)
FIXTURE_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE_MODULE)
InspectorFixture = FIXTURE_MODULE.InspectorFixture

EXPECTED = {
    "settled-adoption.json": (
        "e56af693bfd7bcd32fa257ff5a8a39bb47b13e42df59ec8a992d11d3bd5dd8ca",
        "949752e547b949a8d305ba4dca728f192fe3e0cda5800781e661a3f0b11547fe",
    ),
    "v1.json": (
        "5ce6d2f0c463b79cecbab6d1316db91e85a3df10d8afceba1a4ffe2ac8e31935",
        "bff1c3d8fe14265cd657ea12df4a2e7a3a56c54559c8985f22a5b82a79829eba",
    ),
    "v2.json": (
        "63c5b4d7166e7f61b8cc4894c25769fcc1943e96edafbd28c93fae04c4be426a",
        "df78c1a5baaa26ab4d7e3f009d76b610b522ec35694c4b64d4cedaebe451e60a",
    ),
    "v3.json": (
        "e5eb443c62fec9f8538a409db33cb80cba19719afec2163cbf3252e9c59d9278",
        "5e55ce49296cca730c61c45f596a579cdeaf2cb776b0b0132a23b6ff1575128b",
    ),
    "v4.json": (
        "ed4d627d1c4f6d637a695d68c335cbebaa4ce9385d5b027f805b2853b882a204",
        "427b833070c8f31111ff8c8ae38727a7dec1620e657931922ee55e87d6c13f19",
    ),
}


class MigrationFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def close(self):
        self.temporary.cleanup()

    @staticmethod
    def request(name="v1.json"):
        return json.loads((FIXTURES / name).read_text())

    def snapshot(self):
        store = inspector.resolve_store(self.root).store_dir
        if not store.exists():
            return {}
        return {
            path.relative_to(store).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(store.rglob("*"))
            if path.is_file()
        }


class WorkflowMigrationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = MigrationFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, action):
        with self.assertRaises(migration.MigrationFailure) as raised:
            action()
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def test_all_projection_fixtures_have_exact_deterministic_hashes(self):
        for name, (plan_hash, binding_hash) in EXPECTED.items():
            with self.subTest(name=name):
                request = self.fixture.request(name)
                first = migration.plan(self.fixture.root, request)
                second = migration.plan(self.fixture.root, copy.deepcopy(request))
                self.assertEqual(
                    inspector.canonical_document(first),
                    inspector.canonical_document(second),
                )
                self.assertEqual(plan_hash, first["plan_sha256"])
                self.assertEqual(binding_hash, first["expected"]["binding"]["sha256"])

    def test_plan_and_dry_run_are_read_only(self):
        marker = self.fixture.root / ".agent-workflow" / "runs" / "issue-115" / "state.json"
        marker.parent.mkdir(parents=True)
        marker.write_bytes(b"frozen-115\n")
        before = self.fixture.snapshot()
        plan = migration.plan(self.fixture.root, self.fixture.request())
        migration.dry_run(self.fixture.root, plan)
        self.assertEqual(before, self.fixture.snapshot())
        self.assertEqual(b"frozen-115\n", marker.read_bytes())

    def test_repeated_and_concurrent_apply_converge_to_one_binding(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        first = migration.apply(self.fixture.root, plan)
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
            results = list(
                executor.map(lambda _index: migration.apply(self.fixture.root, plan), range(12))
            )
        hashes = {first["binding"]["sha256"]}
        hashes.update(item["binding"]["sha256"] for item in results)
        self.assertEqual({plan["expected"]["binding"]["sha256"]}, hashes)
        self.assertEqual("resolved", migration.verify(self.fixture.root, plan)["outcome"]["status"])

    def test_source_objects_remain_byte_exact(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        migration.apply(self.fixture.root, plan)
        store = inspector.resolve_store(self.fixture.root)
        for record in plan["source_objects"]:
            path = inspector.object_path(store, record["sha256"])
            self.assertEqual(base64.b64decode(record["bytes_base64"]), path.read_bytes())

    def test_base64_and_typed_wrappers_decode_without_rewriting_source(self):
        payload = b"wrapped payload\n"
        wrappers = (
            ("plan-change-manifest", "base64", base64.b64encode(payload)),
            (
                "plan",
                "typed-base64",
                inspector.canonical_bytes(
                    {
                        "kind": "typed-evidence",
                        "object_kind": "plan",
                        "issue": 133,
                        "correction": None,
                        "logical_path": "evidence.txt",
                        "payload_base64": base64.b64encode(payload).decode(),
                    }
                ),
            ),
        )
        for kind, encoding, wrapper in wrappers:
            with self.subTest(encoding=encoding):
                request = self.fixture.request()
                record = request["source"]["records"][-1]
                record.update(
                    {
                        "kind": kind,
                        "sha256": inspector.sha256(wrapper),
                        "size": len(wrapper),
                        "bytes_base64": base64.b64encode(wrapper).decode(),
                    }
                )
                request["source"]["selection"][0]["encoding"] = encoding
                plan = migration.plan(self.fixture.root, request)
                self.assertEqual(
                    payload,
                    base64.b64decode(plan["publication"]["payloads"][0]["bytes_base64"]),
                )
                migration.apply(self.fixture.root, plan)
                source_path = inspector.object_path(
                    inspector.resolve_store(self.fixture.root), record["sha256"]
                )
                self.assertEqual(wrapper, source_path.read_bytes())

        oversized_wrapper = self.fixture.request()
        record = oversized_wrapper["source"]["records"][-1]
        wrapper = wrappers[1][2]
        record.update(
            kind="plan",
            sha256=inspector.sha256(wrapper),
            size=len(wrapper),
            bytes_base64=base64.b64encode(wrapper).decode(),
        )
        oversized_wrapper["source"]["selection"][0]["encoding"] = "typed-base64"
        with mock.patch.object(evidence, "STRUCTURED_OBJECT_LIMIT", len(wrapper) - 1):
            self.assert_failure(
                "unsupported",
                "structured-object-too-large",
                lambda: migration.plan(self.fixture.root, oversized_wrapper),
            )

        malformed_raw_wrapper = self.fixture.request()
        record = malformed_raw_wrapper["source"]["records"][-1]
        wrapper = b"not-json"
        record.update(
            kind="plan",
            sha256=inspector.sha256(wrapper),
            size=len(wrapper),
            bytes_base64=base64.b64encode(wrapper).decode(),
        )
        self.assert_failure(
            "corrupt",
            "invalid-source-json",
            lambda: migration.plan(self.fixture.root, malformed_raw_wrapper),
        )

    def test_verify_requires_preserved_source_lineage(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        migration.apply(self.fixture.root, plan)
        record = plan["source_objects"][0]
        inspector.object_path(
            inspector.resolve_store(self.fixture.root), record["sha256"]
        ).unlink()
        self.assert_failure(
            "missing", "object-missing",
            lambda: migration.verify(self.fixture.root, plan),
        )

    def test_interruption_before_binding_has_no_success_commit_point(self):
        phases = (
            ("source", "legacy-raw-evidence"),
            ("source", "migration-source-manifest"),
            ("evidence", "evidence-payload"),
            ("evidence", "evidence-manifest"),
            ("evidence", "evidence-provenance"),
            ("evidence", "evidence-binding"),
        )
        for publisher, kind in phases:
            fixture = MigrationFixture()
            self.addCleanup(fixture.close)
            plan = migration.plan(fixture.root, fixture.request())
            binding_path = inspector.object_path(
                inspector.resolve_store(fixture.root),
                plan["expected"]["binding"]["sha256"],
            )
            target = migration if publisher == "source" else evidence
            name = "_publish_object" if publisher == "source" else "_publish"
            real_publish = getattr(target, name)

            def interrupt(store, reference, data, expected=kind):
                if reference["kind"] == expected:
                    raise KeyboardInterrupt
                return real_publish(store, reference, data)

            with self.subTest(kind=kind), mock.patch.object(
                target, name, side_effect=interrupt
            ):
                with self.assertRaises(KeyboardInterrupt):
                    migration.apply(fixture.root, plan)
            self.assertFalse(binding_path.exists())
            self.assertEqual(
                plan["expected"]["binding"],
                migration.apply(fixture.root, plan)["binding"],
            )

    def test_source_collision_fails_ambiguous_without_binding(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        store = inspector.resolve_store(self.fixture.root)
        source = plan["source_objects"][0]
        source_path = inspector.object_path(store, source["sha256"])
        source_path.parent.mkdir(parents=True)
        source_path.write_bytes(b"conflicting immutable bytes")
        binding_path = inspector.object_path(
            store, plan["expected"]["binding"]["sha256"]
        )
        self.assert_failure(
            "ambiguous",
            "immutable-object-collision",
            lambda: migration.apply(self.fixture.root, plan),
        )
        self.assertFalse(binding_path.exists())

    def test_already_canonical_is_verified_without_writes(self):
        original = migration.plan(self.fixture.root, self.fixture.request())
        binding = migration.apply(self.fixture.root, original)["binding"]
        request = {
            "format": migration.REQUEST_FORMAT,
            "source": {"variant": "canonical-binding", "binding": binding},
            "decision": None,
            "lineage": None,
        }
        no_op = migration.plan(self.fixture.root, request)
        before = self.fixture.snapshot()
        result = migration.apply(self.fixture.root, no_op)
        self.assertEqual("already-canonical", result["outcome"]["code"])
        self.assertEqual(binding, result["binding"])
        self.assertEqual([], result["objects"])
        self.assertEqual(before, self.fixture.snapshot())

    def test_absent_null_and_recorded_migration_are_distinct(self):
        expected = ("not-recorded", "none", "recorded")
        for index, value in enumerate(("absent", None, {"source": "legacy"})):
            request = self.fixture.request("v3.json")
            state_record = request["source"]["records"][0]
            state = json.loads(base64.b64decode(state_record["bytes_base64"]))
            if value != "absent":
                state["migration"] = value
            data = migration._canonical_state_bytes(state)
            state_record.update(
                {
                    "bytes_base64": base64.b64encode(data).decode(),
                    "sha256": inspector.sha256(data),
                    "size": len(data),
                }
            )
            plan = migration.plan(self.fixture.root, request)
            self.assertEqual(
                expected[index],
                plan["source_manifest"]["migration_metadata"]["status"],
            )

    def test_malformed_unsupported_and_conflicting_inputs_fail_typed(self):
        corrupt = self.fixture.request()
        corrupt["source"]["records"][0]["sha256"] = "0" * 64
        self.assert_failure(
            "corrupt", "source-record-mismatch",
            lambda: migration.plan(self.fixture.root, corrupt),
        )
        unsupported = self.fixture.request()
        unsupported["source"]["variant"] = "projection-v9"
        self.assert_failure(
            "unsupported", "unsupported-source-variant",
            lambda: migration.plan(self.fixture.root, unsupported),
        )
        duplicate = self.fixture.request()
        duplicate["source"]["selection"].append(copy.deepcopy(duplicate["source"]["selection"][0]))
        self.assert_failure(
            "ambiguous", "duplicate-selection",
            lambda: migration.plan(self.fixture.root, duplicate),
        )
        incomplete = self.fixture.request()
        transaction = copy.deepcopy(incomplete["source"]["records"][-1])
        transaction["logical_path"] = "adoption-transaction.json"
        incomplete["source"]["records"].append(transaction)
        self.assert_failure(
            "unsupported", "incomplete-legacy-transaction",
            lambda: migration.plan(self.fixture.root, incomplete),
        )
        malformed_variant = self.fixture.request()
        malformed_variant["source"]["variant"] = []
        self.assert_failure(
            "unsupported",
            "unsupported-source-variant",
            lambda: migration.plan(self.fixture.root, malformed_variant),
        )
        malformed_lineage = self.fixture.request()
        malformed_lineage["lineage"]["status"] = []
        self.assert_failure(
            "unsupported",
            "unsupported-lineage-status",
            lambda: migration.plan(self.fixture.root, malformed_lineage),
        )
        malformed_selection = self.fixture.request()
        malformed_selection["source"]["selection"][0]["entry_kind"] = []
        self.assert_failure(
            "unsupported",
            "unsupported-entry-kind",
            lambda: migration.plan(self.fixture.root, malformed_selection),
        )

    def test_legacy_authority_structure_lifecycle_and_integrity_are_strict(self):
        invalid_structure = self.fixture.request()
        state_record = invalid_structure["source"]["records"][0]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state.pop("artifacts")
        data = migration._canonical_state_bytes(state)
        state_record.update(
            bytes_base64=base64.b64encode(data).decode(),
            sha256=inspector.sha256(data),
            size=len(data),
        )
        self.assert_failure(
            "corrupt",
            "invalid-v4-envelope",
            lambda: migration.plan(self.fixture.root, invalid_structure),
        )

        unknown_event = self.fixture.request()
        state_record = unknown_event["source"]["records"][0]
        history_record = unknown_event["source"]["records"][1]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["history"][0]["event"] = "UNKNOWN_EVENT"
        event = state["history"][0]
        state_data = migration._canonical_state_bytes(state)
        history_data = (json.dumps(event, sort_keys=True) + "\n").encode()
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        history_record.update(
            bytes_base64=base64.b64encode(history_data).decode(),
            sha256=inspector.sha256(history_data),
            size=len(history_data),
        )
        self.assert_failure(
            "unsupported",
            "unsupported-legacy-lifecycle",
            lambda: migration.plan(self.fixture.root, unknown_event),
        )

        impossible_event = self.fixture.request()
        state_record = impossible_event["source"]["records"][0]
        history_record = impossible_event["source"]["records"][1]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["history"][0]["event"] = "PR_HUMAN_APPROVED"
        event = state["history"][0]
        state_data = migration._canonical_state_bytes(state)
        history_data = (json.dumps(event, sort_keys=True) + "\n").encode()
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        history_record.update(
            bytes_base64=base64.b64encode(history_data).decode(),
            sha256=inspector.sha256(history_data),
            size=len(history_data),
        )
        self.assert_failure(
            "unsupported",
            "unsupported-legacy-lifecycle",
            lambda: migration.plan(self.fixture.root, impossible_event),
        )

        unexpected_integrity = self.fixture.request()
        extra = copy.deepcopy(unexpected_integrity["source"]["records"][-1])
        extra["logical_path"] = "integrity.json"
        unexpected_integrity["source"]["records"].append(extra)
        self.assert_failure(
            "unsupported",
            "unexpected-integrity-record",
            lambda: migration.plan(self.fixture.root, unexpected_integrity),
        )

        type_mismatch = self.fixture.request()
        state_record = type_mismatch["source"]["records"][0]
        history_record = type_mismatch["source"]["records"][1]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["history"][0]["details"] = {"value": True}
        event = copy.deepcopy(state["history"][0])
        event["details"]["value"] = 1
        state_data = migration._canonical_state_bytes(state)
        history_data = (json.dumps(event, sort_keys=True) + "\n").encode()
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        history_record.update(
            bytes_base64=base64.b64encode(history_data).decode(),
            sha256=inspector.sha256(history_data),
            size=len(history_data),
        )
        self.assert_failure(
            "corrupt",
            "state-history-mismatch",
            lambda: migration.plan(self.fixture.root, type_mismatch),
        )

        nested = self.fixture.request()
        state_record = nested["source"]["records"][0]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["test_paths"] = [1]
        state_data = migration._canonical_state_bytes(state)
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        self.assert_failure(
            "corrupt",
            "invalid-test-path",
            lambda: migration.plan(self.fixture.root, nested),
        )

        relabeled = self.fixture.request()
        relabeled["source"]["records"][0]["kind"] = "plan"
        self.assert_failure(
            "unsupported",
            "unsupported-structural-record-kind",
            lambda: migration.plan(self.fixture.root, relabeled),
        )

        nonstandard_number = self.fixture.request()
        state_record = nonstandard_number["source"]["records"][0]
        history_record = nonstandard_number["source"]["records"][1]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["history"][0]["details"] = {"value": float("nan")}
        event = state["history"][0]
        state_data = (
            json.dumps(state, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
        ).encode()
        history_data = (json.dumps(event, sort_keys=True) + "\n").encode()
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        history_record.update(
            bytes_base64=base64.b64encode(history_data).decode(),
            sha256=inspector.sha256(history_data),
            size=len(history_data),
        )
        self.assert_failure(
            "corrupt",
            "invalid-source-json",
            lambda: migration.plan(self.fixture.root, nonstandard_number),
        )

    def test_declared_source_limit_and_surrogate_paths_fail_before_expansion(self):
        oversized = self.fixture.request()
        oversized["source"]["records"][0]["size"] = evidence.PAYLOAD_LIMIT + 1
        with mock.patch.object(
            base64,
            "b64decode",
            side_effect=AssertionError("decoded oversized source"),
        ):
            self.assert_failure(
                "unsupported",
                "source-object-too-large",
                lambda: migration.plan(self.fixture.root, oversized),
            )

        surrogate = self.fixture.request()
        surrogate["source"]["selection"][0]["path"] = "\ud800"
        self.assert_failure(
            "unsupported",
            "invalid-path",
            lambda: migration.plan(self.fixture.root, surrogate),
        )

        nested_history = ("[" * 2000 + "0" + "]" * 2000 + "\n").encode()
        self.assert_failure(
            "corrupt",
            "invalid-history",
            lambda: migration._parse_history(nested_history),
        )

        overflowing_number = (
            b'{"actor":"fixture","details":{"value":1e400},'
            b'"event":"WORKFLOW_INITIALIZED","sequence":1,'
            b'"state":"PLANNING","timestamp":"2026-01-01T00:00:00Z"}\n'
        )
        self.assert_failure(
            "unsupported",
            "floating-point-json",
            lambda: migration._parse_history(overflowing_number),
        )

    def test_plan_validation_binds_projection_request_records(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        plan["request"]["source"]["records"] = None
        unsigned = dict(plan)
        unsigned.pop("plan_sha256")
        plan["plan_sha256"] = inspector.sha256(inspector.canonical_bytes(unsigned))
        self.assert_failure(
            "corrupt",
            "invalid-source-collections",
            lambda: migration.dry_run(self.fixture.root, plan),
        )

    def test_source_manifest_object_limit_is_enforced_during_planning(self):
        with (
            mock.patch.object(evidence, "MANIFEST_ENTRY_LIMIT", 2),
            mock.patch.object(
                migration,
                "_decode_record",
                side_effect=AssertionError("decoded before count limit"),
            ),
        ):
            self.assert_failure(
                "unsupported",
                "manifest-entry-limit",
                lambda: migration.plan(
                    self.fixture.root, self.fixture.request()
                ),
            )

    def test_plan_tampering_is_corrupt(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        plan["publication"]["decision"]["id"] = "tampered"
        self.assert_failure(
            "corrupt", "plan-digest-mismatch",
            lambda: migration.apply(self.fixture.root, plan),
        )

    def test_inherited_correction_requires_exact_parent_and_unchanged_semantics(self):
        parent_request = self.fixture.request("settled-adoption.json")
        parent_plan = migration.plan(self.fixture.root, parent_request)
        parent_ref = migration.apply(self.fixture.root, parent_plan)["binding"]
        request = self.fixture.request()
        parent_evidence = next(
            record
            for record in parent_request["source"]["records"]
            if record["logical_path"] == "evidence.txt"
        )
        request["source"]["records"][-1] = copy.deepcopy(parent_evidence)
        request["source"]["correction"] = 1
        state_record = request["source"]["records"][0]
        history_record = request["source"]["records"][1]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        parent_objects = {
            item["logical_path"]: item["object"]
            for item in parent_plan["source_manifest"]["objects"]
        }
        state["correction"] = {
            "number": 1,
            "classification": "architecture",
            "reason": "deterministic correction fixture",
            "requested_by": "fixture-reviewer",
            "created_at": "2026-01-01T00:00:00+00:00",
            "inherited": [],
            "invalidated": [
                "artifact:plan",
                "artifact:plan_review",
                "artifact:test_report",
                "artifact:test_review",
                "artifact:implementation_report",
                "artifact:final_review",
                "approval:plan",
                "approval:tests",
                "approval:pr",
                "evidence:validation",
                "evidence:validated_fingerprint",
                "evidence:validated_head",
                "evidence:validated_base",
                "evidence:validated_test_fingerprint",
                "evidence:validation_evidence",
                "evidence:final_review",
                "evidence:draft_pr",
            ],
        }
        state["parent_run"] = {
            "issue": 133,
            "correction": None,
            "state": "WAITING_FOR_PR_HUMAN_APPROVAL",
            "validated_head": None,
            "validated_base": None,
            "state_sha256": parent_objects["state.json"]["sha256"],
            "history_sha256": parent_objects["history.jsonl"]["sha256"],
        }
        child_event = {
            "actor": "fixture-reviewer",
            "details": {
                "number": 1,
                "classification": "architecture",
                "reason": "deterministic correction fixture",
                "parent_correction": None,
            },
            "event": "CORRECTION_CREATED",
            "sequence": 1,
            "state": "PLANNING",
            "timestamp": "2026-01-01T00:00:00+00:00",
        }
        state["history"] = [child_event]
        state_data = migration._canonical_state_bytes(state)
        history_data = (json.dumps(child_event, sort_keys=True) + "\n").encode()
        state_record.update(
            {
                "bytes_base64": base64.b64encode(state_data).decode(),
                "sha256": inspector.sha256(state_data),
                "size": len(state_data),
            }
        )
        history_record.update(
            bytes_base64=base64.b64encode(history_data).decode(),
            sha256=inspector.sha256(history_data),
            size=len(history_data),
        )
        request["lineage"].update({"status": "inherited", "parent_binding": parent_ref})
        plan = migration.plan(self.fixture.root, request)
        child = migration.apply(self.fixture.root, plan)["binding"]
        parent_projection = evidence.project(self.fixture.root, parent_ref)
        child_projection = evidence.project(self.fixture.root, child)
        self.assertEqual(parent_projection["authority"]["manifest"], child_projection["authority"]["manifest"])
        self.assertEqual(parent_projection["subject"], child_projection["subject"])
        self.assertEqual(
            parent_projection["identity"]["family_run_id"],
            child_projection["identity"]["family_run_id"],
        )
        changed = copy.deepcopy(request)
        changed["source"]["selection"][0]["path"] = "artifacts/changed.txt"
        self.assert_failure(
            "stale",
            "inherited-evidence-changed",
            lambda: migration.plan(self.fixture.root, changed),
        )
        changed_subject = copy.deepcopy(request)
        changed_subject["lineage"]["subject"] = parent_ref
        self.assert_failure(
            "stale",
            "inherited-evidence-changed",
            lambda: migration.plan(self.fixture.root, changed_subject),
        )
        wrong_parent = copy.deepcopy(request)
        state_record = wrong_parent["source"]["records"][0]
        state = json.loads(base64.b64decode(state_record["bytes_base64"]))
        state["parent_run"]["state_sha256"] = "0" * 64
        state_data = migration._canonical_state_bytes(state)
        state_record.update(
            bytes_base64=base64.b64encode(state_data).decode(),
            sha256=inspector.sha256(state_data),
            size=len(state_data),
        )
        self.assert_failure(
            "stale",
            "parent-run-facts-mismatch",
            lambda: migration.plan(self.fixture.root, wrong_parent),
        )

    def test_v4_uses_complete_committed_envelope_validation(self):
        request = self.fixture.request("v4.json")
        record = next(
            item
            for item in request["source"]["records"]
            if item["logical_path"] == "integrity.json"
        )
        envelope = json.loads(base64.b64decode(record["bytes_base64"]))
        envelope["state"]["scope"] = []
        data = migration._canonical_state_bytes(envelope)
        record.update(
            {
                "bytes_base64": base64.b64encode(data).decode(),
                "sha256": inspector.sha256(data),
                "size": len(data),
            }
        )
        self.assert_failure(
            "corrupt",
            "invalid-v4-envelope",
            lambda: migration.plan(self.fixture.root, request),
        )

        boolean_sequence = self.fixture.request("v4.json")
        record = next(
            item
            for item in boolean_sequence["source"]["records"]
            if item["logical_path"] == "integrity.json"
        )
        envelope = json.loads(base64.b64decode(record["bytes_base64"]))
        envelope["sequence"] = True
        data = migration._canonical_state_bytes(envelope)
        record.update(
            {
                "bytes_base64": base64.b64encode(data).decode(),
                "sha256": inspector.sha256(data),
                "size": len(data),
            }
        )
        self.assert_failure(
            "corrupt",
            "invalid-integrity-record",
            lambda: migration.plan(self.fixture.root, boolean_sequence),
        )

        uninitialized = self.fixture.request("v4.json")
        records = {
            item["logical_path"]: item
            for item in uninitialized["source"]["records"]
        }
        state = json.loads(base64.b64decode(records["state.json"]["bytes_base64"]))
        event = copy.deepcopy(state["history"][0])
        event.update(
            event="PR_HUMAN_APPROVAL_REQUESTED",
            state="WAITING_FOR_PR_HUMAN_APPROVAL",
        )
        state["state"] = event["state"]
        state["history"] = [event]
        state_data = migration._canonical_state_bytes(state)
        history_data = (json.dumps(event, sort_keys=True) + "\n").encode()
        envelope = json.loads(
            base64.b64decode(records["integrity.json"]["bytes_base64"])
        )
        envelope.update(
            state=state,
            history=[event],
            state_sha256=inspector.sha256(state_data),
            history_sha256=inspector.sha256(history_data),
        )
        envelope_data = migration._canonical_state_bytes(envelope)
        for record, data in (
            (records["state.json"], state_data),
            (records["history.jsonl"], history_data),
            (records["integrity.json"], envelope_data),
        ):
            record.update(
                bytes_base64=base64.b64encode(data).decode(),
                sha256=inspector.sha256(data),
                size=len(data),
            )
        self.assert_failure(
            "unsupported",
            "unsupported-legacy-lifecycle",
            lambda: migration.plan(self.fixture.root, uninitialized),
        )

    def test_settled_adoption_requires_exact_trust_metadata(self):
        request = self.fixture.request("settled-adoption.json")
        record = next(
            item
            for item in request["source"]["records"]
            if item["logical_path"] == "integrity.json"
        )
        envelope = json.loads(base64.b64decode(record["bytes_base64"]))
        envelope["adopted_by"] = ""
        data = migration._canonical_state_bytes(envelope)
        record.update(
            {
                "bytes_base64": base64.b64encode(data).decode(),
                "sha256": inspector.sha256(data),
                "size": len(data),
            }
        )
        self.assert_failure(
            "corrupt",
            "invalid-integrity-record",
            lambda: migration.plan(self.fixture.root, request),
        )

    def test_typed_wrapper_rejects_boolean_issue_identity(self):
        request = self.fixture.request()
        record = request["source"]["records"][-1]
        wrapper = inspector.canonical_bytes(
            {
                "kind": "typed-evidence",
                "object_kind": "plan",
                "issue": True,
                "correction": None,
                "logical_path": "evidence.txt",
                "payload_base64": base64.b64encode(b"payload\n").decode(),
            }
        )
        record.update(
            {
                "kind": "plan",
                "sha256": inspector.sha256(wrapper),
                "size": len(wrapper),
                "bytes_base64": base64.b64encode(wrapper).decode(),
            }
        )
        request["source"]["selection"][0]["encoding"] = "typed-base64"
        self.assert_failure(
            "corrupt",
            "invalid-typed-payload",
            lambda: migration.plan(self.fixture.root, request),
        )
        item = {
            "logical_path": "evidence.txt",
            "object": {
                "kind": "plan",
                "sha256": inspector.sha256(wrapper),
                "size": len(wrapper),
                "encoding": "typed-base64",
            },
            "encoding": "typed-base64",
            "payload_sha256": inspector.sha256(b"payload\n"),
            "payload_size": len(b"payload\n"),
        }
        with self.assertRaises(evidence.EvidenceFailure) as raised:
            evidence._decode_migration_source_payload(
                wrapper,
                item,
                {"issue": 1, "correction": None},
            )
        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("invalid-typed-payload", raised.exception.code)

    def test_migration_capture_requires_deterministic_metadata(self):
        request = self.fixture.request()
        plan = migration.plan(self.fixture.root, request)
        capture = copy.deepcopy(plan["publication"]["captures"][0])
        capture["capture_method"] = "manual"
        capture["captured_at"] = "2026-01-01T00:00:00Z"
        with self.assertRaises(evidence.EvidenceFailure) as raised:
            evidence._validate_capture(
                capture,
                {capture["entry_sha256"]},
            )
        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("invalid-capture-metadata", raised.exception.code)

    def test_migration_provenance_requires_exact_source_reference(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        migration.apply(self.fixture.root, plan)
        publication = copy.deepcopy(plan["publication"])
        publication["captures"][0]["source"]["object"]["size"] += 1
        with self.assertRaises(evidence.EvidenceFailure) as raised:
            evidence.publish(self.fixture.root, publication)
        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual(
            "migration-source-reference-mismatch",
            raised.exception.code,
        )

    def test_migration_evidence_enums_reject_container_values(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())
        source_manifest = copy.deepcopy(plan["source_manifest"])
        source_manifest["migration_metadata"]["status"] = []
        with self.assertRaises(evidence.EvidenceFailure) as metadata_failure:
            evidence._validate_migration_source_manifest(source_manifest)
        self.assertEqual("corrupt", metadata_failure.exception.status)
        self.assertEqual(
            "invalid-migration-metadata",
            metadata_failure.exception.code,
        )

        migration_record = copy.deepcopy(plan["publication"]["migration"])
        migration_record["adapter"] = []
        with self.assertRaises(evidence.EvidenceFailure) as adapter_failure:
            evidence._validate_migration(migration_record)
        self.assertEqual("unsupported", adapter_failure.exception.status)
        self.assertEqual(
            "unsupported-migration-adapter",
            adapter_failure.exception.code,
        )
        with self.assertRaises(evidence.EvidenceFailure) as source_failure:
            evidence._validate_source({"type": []})
        self.assertEqual("unsupported", source_failure.exception.status)
        self.assertEqual(
            "unsupported-provenance-source",
            source_failure.exception.code,
        )
        with self.assertRaises(evidence.EvidenceFailure) as path_failure:
            evidence._validate_path("\ud800")
        self.assertEqual("unsupported", path_failure.exception.status)
        self.assertEqual("invalid-evidence-path", path_failure.exception.code)
        with self.assertRaises(inspector.InspectionFailure) as reference_failure:
            inspector.validate_reference(
                {"kind": [], "sha256": "0" * 64, "size": 0}
            )
        self.assertEqual("unsupported", reference_failure.exception.status)
        self.assertEqual(
            "unsupported-object-kind",
            reference_failure.exception.code,
        )

    def test_migration_source_manifest_rejects_empty_unknown_and_oversized(self):
        plan = migration.plan(self.fixture.root, self.fixture.request())

        empty = copy.deepcopy(plan["source_manifest"])
        empty["objects"] = []
        with self.assertRaises(evidence.EvidenceFailure) as empty_failure:
            evidence._validate_migration_source_manifest(empty)
        self.assertEqual("missing", empty_failure.exception.status)
        self.assertEqual("migration-source-empty", empty_failure.exception.code)

        unknown = copy.deepcopy(plan["source_manifest"])
        unknown["variant"] = "unsupported-variant"
        with self.assertRaises(evidence.EvidenceFailure) as variant_failure:
            evidence._validate_migration_source_manifest(unknown)
        self.assertEqual("unsupported", variant_failure.exception.status)
        self.assertEqual(
            "unsupported-migration-source-variant",
            variant_failure.exception.code,
        )

        oversized = copy.deepcopy(plan["source_manifest"])
        oversized["objects"][0]["object"]["size"] = evidence.PAYLOAD_LIMIT + 1
        with self.assertRaises(evidence.EvidenceFailure) as size_failure:
            evidence._validate_migration_source_manifest(oversized)
        self.assertEqual("unsupported", size_failure.exception.status)
        self.assertEqual("source-object-too-large", size_failure.exception.code)

        payload_size = copy.deepcopy(plan["source_manifest"])
        selected = next(
            item for item in payload_size["objects"] if item["encoding"] is not None
        )
        selected["payload_size"] = evidence.PAYLOAD_LIMIT + 1
        with self.assertRaises(evidence.EvidenceFailure) as payload_size_failure:
            evidence._validate_migration_source_manifest(payload_size)
        self.assertEqual("unsupported", payload_size_failure.exception.status)
        self.assertEqual(
            "payload-too-large",
            payload_size_failure.exception.code,
        )

        migration_record = copy.deepcopy(plan["publication"]["migration"])
        migration_record["source"]["size"] = evidence.STRUCTURED_OBJECT_LIMIT + 1
        with self.assertRaises(evidence.EvidenceFailure) as manifest_size_failure:
            evidence._validate_migration(migration_record)
        self.assertEqual("unsupported", manifest_size_failure.exception.status)
        self.assertEqual(
            "structured-object-too-large",
            manifest_size_failure.exception.code,
        )

        total = copy.deepcopy(plan["source_manifest"])
        with (
            mock.patch.object(evidence, "PUBLICATION_PAYLOAD_LIMIT", 1),
            self.assertRaises(evidence.EvidenceFailure) as total_failure,
        ):
            evidence._validate_migration_source_manifest(total)
        self.assertEqual("unsupported", total_failure.exception.status)
        self.assertEqual(
            "publication-payload-limit",
            total_failure.exception.code,
        )


class DurableV4MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        self.fixture = InspectorFixture(self.root)
        self.payload = b"durable payload\n"
        reference = self.fixture.install("legacy-raw-evidence", self.payload, raw=True)
        self.fixture.rewrite_state(
            lambda state: state.update({"migration_evidence": {"object": reference}})
        )
        self.reference = reference

    def tearDown(self):
        self.temporary.cleanup()

    def request(self):
        checkpoint = inspector.checkpoint_document(
            inspector.inspect(self.root, self.fixture.issue)
        )
        return {
            "format": migration.REQUEST_FORMAT,
            "source": {
                "variant": "durable-v4",
                "checkpoint": checkpoint,
                "selection": [
                    {
                        "logical_path": "legacy/evidence.txt",
                        "path": "artifacts/evidence.txt",
                        "entry_kind": "regular",
                        "mode": "100644",
                        "encoding": "raw",
                        "object": self.reference,
                    }
                ],
            },
            "decision": {"type": "legacy-migration", "id": "durable-v4"},
            "lineage": {"status": "original", "parent_binding": None, "subject": None},
        }

    def test_explicit_reachable_selection_preserves_v4_identity(self):
        plan = migration.plan(self.root, self.request())
        identity = plan["publication"]["identity"]
        authority = plan["precondition"]["authority"]
        self.assertEqual(authority["run_id"], identity["run_id"])
        self.assertEqual(authority["family_run_id"], identity["family_run_id"])
        self.assertEqual(authority["event_tip"], identity["event_tip"])
        self.assertEqual(self.payload, base64.b64decode(plan["source_objects"][0]["bytes_base64"]))
        self.assertEqual(plan["expected"]["binding"], migration.apply(self.root, plan)["binding"])

    def test_checkpoint_precondition_uses_one_verified_snapshot(self):
        with mock.patch.object(
            evidence,
            "adapt_v4",
            side_effect=AssertionError("split authority read"),
        ):
            plan = migration.plan(self.root, self.request())
            self.assertEqual(
                plan["expected"]["binding"],
                migration.apply(self.root, plan)["binding"],
            )

    def test_malformed_durable_plan_fields_fail_with_typed_outcomes(self):
        plan = migration.plan(self.root, self.request())

        checkpoint = copy.deepcopy(plan)
        checkpoint["request"]["source"]["checkpoint"] = []
        unsigned = dict(checkpoint)
        unsigned.pop("plan_sha256")
        checkpoint["plan_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with self.assertRaises(migration.MigrationFailure) as checkpoint_failure:
            migration.dry_run(self.root, checkpoint)
        self.assertEqual("corrupt", checkpoint_failure.exception.status)
        self.assertEqual("invalid-checkpoint", checkpoint_failure.exception.code)

        source_manifest = copy.deepcopy(plan)
        source_manifest["source_manifest"] = []
        unsigned = dict(source_manifest)
        unsigned.pop("plan_sha256")
        source_manifest["plan_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned)
        )
        with self.assertRaises(migration.MigrationFailure) as manifest_failure:
            migration.dry_run(self.root, source_manifest)
        self.assertEqual("corrupt", manifest_failure.exception.status)
        self.assertEqual(
            "invalid-source-manifest",
            manifest_failure.exception.code,
        )

        floating = copy.deepcopy(plan)
        floating["unexpected"] = 1.5
        with self.assertRaises(migration.MigrationFailure) as floating_failure:
            migration.dry_run(self.root, floating)
        self.assertEqual("unsupported", floating_failure.exception.status)
        self.assertEqual("floating-point-json", floating_failure.exception.code)

        authority = self.request()
        authority["source"]["checkpoint"]["authority"] = []
        with self.assertRaises(migration.MigrationFailure) as authority_failure:
            migration.plan(self.root, authority)
        self.assertEqual("corrupt", authority_failure.exception.status)
        self.assertEqual("invalid-checkpoint", authority_failure.exception.code)

    def test_durable_plan_cannot_inject_noncheckpoint_source_objects(self):
        plan = migration.plan(self.root, self.request())
        injected_data = b"not reachable from checkpoint"
        records = copy.deepcopy(plan["source_objects"])
        records.append(
            {
                "logical_path": "injected.txt",
                "kind": "legacy-raw-evidence",
                "sha256": inspector.sha256(injected_data),
                "size": len(injected_data),
                "bytes_base64": base64.b64encode(injected_data).decode(),
            }
        )
        forged = migration._assemble(
            self.root,
            copy.deepcopy(plan["request"]),
            supplied_records=records,
            supplied_migration=plan["source_manifest"]["migration_metadata"],
        )
        with self.assertRaises(migration.MigrationFailure) as raised:
            migration.dry_run(self.root, forged)
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("selected-sources-stale", raised.exception.code)

    def test_durable_parent_cannot_rewrite_checkpoint_family_identity(self):
        plan = migration.plan(self.root, self.request())
        migration.apply(self.root, plan)
        unrelated = copy.deepcopy(plan["publication"])
        unrelated["identity"]["run_id"] = "a" * 32
        unrelated["identity"]["family_run_id"] = "b" * 32
        unrelated["decision"]["id"] = "unrelated-parent"
        parent = evidence.publish(self.root, unrelated)["binding"]

        request = self.request()
        request["lineage"].update(
            {"status": "replacement", "parent_binding": parent}
        )
        with self.assertRaises(migration.MigrationFailure) as raised:
            migration.plan(self.root, request)
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("lineage-family-mismatch", raised.exception.code)

    def test_changed_checkpoint_is_stale_before_binding(self):
        plan = migration.plan(self.root, self.request())
        self.fixture.rewrite_state(lambda state: state.update({"migration": None}))
        with self.assertRaises(migration.MigrationFailure) as raised:
            migration.apply(self.root, plan)
        self.assertEqual("stale", raised.exception.status)
        binding_path = inspector.object_path(
            inspector.resolve_store(self.root), plan["expected"]["binding"]["sha256"]
        )
        self.assertFalse(binding_path.exists())

    def test_unreachable_or_duplicate_selection_fails_closed(self):
        request = self.request()
        request["source"]["selection"][0]["object"] = {
            "kind": "legacy-raw-evidence",
            "sha256": "0" * 64,
            "size": 1,
        }
        with self.assertRaises(migration.MigrationFailure) as raised:
            migration.plan(self.root, request)
        self.assertEqual("stale", raised.exception.status)

        duplicate = self.request()
        duplicate["source"]["selection"].append(
            {
                **duplicate["source"]["selection"][0],
                "logical_path": "legacy/duplicate.txt",
                "path": "artifacts/duplicate.txt",
            }
        )
        with self.assertRaises(migration.MigrationFailure) as raised:
            migration.plan(self.root, duplicate)
        self.assertEqual("ambiguous", raised.exception.status)
        self.assertEqual("conflicting-selection", raised.exception.code)

    def test_multiple_historical_candidates_never_override_explicit_selection(self):
        other = self.fixture.install(
            "legacy-raw-evidence", b"other historical payload\n", raw=True
        )
        self.fixture.rewrite_state(
            lambda state: state.update(
                {"historical_evidence": [self.reference, other]}
            )
        )
        request = self.request()
        plan = migration.plan(self.root, request)
        self.assertEqual(1, len(plan["source_objects"]))
        self.assertEqual(
            self.reference["sha256"],
            plan["source_objects"][0]["sha256"],
        )


class FrozenIssue115MigrationTest(unittest.TestCase):
    def test_plan_and_dry_run_leave_available_frozen_authority_unchanged(self):
        try:
            inspected = inspector.inspect(REPOSITORY, 115)
        except inspector.InspectionFailure as failure:
            self.skipTest("frozen issue #115 authority unavailable: %s" % failure.code)
        checkpoint = inspector.checkpoint_document(inspected)
        raw = next(
            (
                item
                for item in inspected["authority"]["verified_objects"]
                if "legacy-raw-evidence" in item["kinds"]
            ),
            None,
        )
        if raw is None:
            self.skipTest("frozen issue #115 has no reachable raw evidence")
        reference = {
            "kind": "legacy-raw-evidence",
            "sha256": raw["sha256"],
            "size": raw["size"],
        }
        request = {
            "format": migration.REQUEST_FORMAT,
            "source": {
                "variant": "durable-v4",
                "checkpoint": checkpoint,
                "selection": [
                    {
                        "logical_path": "legacy/frozen-object",
                        "path": "artifacts/frozen-object",
                        "entry_kind": "regular",
                        "mode": "100644",
                        "encoding": "raw",
                        "object": reference,
                    }
                ],
            },
            "decision": {"type": "legacy-migration", "id": "frozen-115-read-only"},
            "lineage": {"status": "original", "parent_binding": None, "subject": None},
        }
        store = inspector.resolve_store(REPOSITORY)

        def signature():
            return sorted(
                (
                    path.relative_to(store.store_dir).as_posix(),
                    path.stat().st_size,
                    path.stat().st_mtime_ns,
                )
                for path in store.store_dir.rglob("*")
                if path.is_file()
            )

        before = signature()
        status_before = subprocess.run(
            ["git", "status", "--porcelain=v1", "-z"],
            cwd=REPOSITORY,
            check=True,
            capture_output=True,
        ).stdout
        plan = migration.plan(REPOSITORY, request)
        migration.dry_run(REPOSITORY, plan)
        self.assertEqual(before, signature())
        status_after = subprocess.run(
            ["git", "status", "--porcelain=v1", "-z"],
            cwd=REPOSITORY,
            check=True,
            capture_output=True,
        ).stdout
        self.assertEqual(status_before, status_after)


if __name__ == "__main__":
    unittest.main()
