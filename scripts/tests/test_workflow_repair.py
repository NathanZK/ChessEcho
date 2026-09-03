import base64
import concurrent.futures
import contextlib
import importlib.util
import json
import os
import pathlib
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPTS = pathlib.Path(__file__).parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


inspector = load("workflow_inspector", SCRIPTS / "workflow_inspector.py")
sys.modules["workflow_inspector"] = inspector
fixture_module = load(
    "workflow_inspector_tests", SCRIPTS / "tests" / "test_workflow_inspector.py"
)
repair = load("workflow_repair", SCRIPTS / "workflow_repair.py")
InspectorFixture = fixture_module.InspectorFixture


def latest_run_update(fixture):
    pointer = fixture.pointer()
    index = json.loads(fixture.object_path(pointer["index"]).read_text())
    return index["run_update"]


def object_record(kind, value):
    data = inspector.canonical_bytes(value)
    return {
        "kind": kind,
        "sha256": inspector.sha256(data),
        "size": len(data),
        "bytes_base64": base64.b64encode(data).decode("ascii"),
    }


def reseal_material(fixture, checkpoint, source_failure=None):
    pointer = fixture.pointer()
    old_envelope_ref = checkpoint["authority"]["envelope"]
    old_envelope = json.loads(fixture.object_path(old_envelope_ref).read_text())
    new_envelope = {
        **old_envelope,
        "integrity_reseal": {
            "checkpoint_sha256": checkpoint["checkpoint_sha256"],
            "source_envelope_sha256": old_envelope_ref["sha256"],
        },
    }
    envelope_data = inspector.canonical_bytes(new_envelope)
    envelope_ref = {
        "kind": "run-envelope",
        "sha256": inspector.sha256(envelope_data),
        "size": len(envelope_data),
    }
    source_index = json.loads(fixture.object_path(pointer["index"]).read_text())
    target_index = dict(source_index)
    target_index["run_update"] = {
        **source_index["run_update"],
        "envelope": envelope_ref,
    }
    index_data = inspector.canonical_bytes(target_index)
    index_ref = {
        "kind": "issue-index",
        "sha256": inspector.sha256(index_data),
        "size": len(index_data),
    }
    target_pointer = dict(pointer)
    target_pointer["index"] = index_ref
    target_pointer["selection"] = {
        **pointer["selection"],
        "run": {**pointer["selection"]["run"], "envelope": envelope_ref},
    }
    pointer_data = inspector.canonical_bytes(target_pointer)
    objects = [
        {
            **envelope_ref,
            "bytes_base64": base64.b64encode(envelope_data).decode("ascii"),
        },
        {
            **index_ref,
            "bytes_base64": base64.b64encode(index_data).decode("ascii"),
        },
    ]
    operation = {
        "type": "integrity-reseal",
        "target_pointer": {
            "bytes_base64": base64.b64encode(pointer_data).decode("ascii"),
            "sha256": inspector.sha256(pointer_data),
            "size": len(pointer_data),
        },
        "source_inspection_failure": source_failure,
    }
    return operation, objects


class WorkflowRepairTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.fixture = InspectorFixture(self.root)
        self.checkpoint_path = self.root / ".git" / "checkpoint.json"
        self.request_path = self.root / ".git" / "request.json"
        self.bundle_path = self.root / ".git" / "bundle.json"
        self.checkpoint = inspector.checkpoint_document(
            inspector.inspect(self.root, self.fixture.issue)
        )
        self.checkpoint_path.write_bytes(inspector.canonical_document(self.checkpoint))

    def tearDown(self):
        self.temporary.cleanup()

    def request(self, operation=None, objects=None, confirmation=None, selector=None):
        operation = operation or {
            "type": "pointer-binding",
            "binding": latest_run_update(self.fixture),
        }
        operation_type = operation["type"]
        value = {
            "format": repair.REQUEST_FORMAT,
            "issue": self.fixture.issue,
            "selector": selector or {"current": True},
            "operation": operation,
            "objects": objects or [],
            "operator": "test-operator",
            "reason": "repair the exact verified binding",
            "confirmation": confirmation or (
                repair.POINTER_BINDING_CONFIRMATION
                if operation_type == "pointer-binding"
                else repair.INTEGRITY_RESEAL_CONFIRMATION
            ).format(issue=self.fixture.issue),
        }
        self.request_path.write_bytes(inspector.canonical_document(value))
        return value

    def prepare(self):
        self.request()
        bundle = repair.prepare(
            self.root,
            self.fixture.issue,
            self.checkpoint_path,
            self.request_path,
        )
        self.bundle_path.write_bytes(inspector.canonical_document(bundle))
        return bundle

    def snapshot(self):
        excluded = {self.checkpoint_path, self.request_path, self.bundle_path}
        return {
            path.relative_to(self.root).as_posix(): path.read_bytes()
            for path in self.root.rglob("*")
            if path.is_file() and path not in excluded
        }

    def new_object(self, bundle):
        source_hashes = {
            item["sha256"] for item in self.checkpoint["authority"]["verified_objects"]
        }
        return next(item for item in bundle["objects"] if item["sha256"] not in source_hashes)

    def reseal_request(self):
        operation, objects = reseal_material(self.fixture, self.checkpoint)
        self.request(operation, objects)
        return repair.prepare(
            self.root,
            self.fixture.issue,
            self.checkpoint_path,
            self.request_path,
        )

    def test_prepare_bundle_is_canonical_deterministic_complete_and_path_free(self):
        self.request()
        first = repair.prepare(
            self.root, self.fixture.issue, self.checkpoint_path, self.request_path
        )
        second = repair.prepare(
            self.root, self.fixture.issue, self.checkpoint_path, self.request_path
        )
        self.assertEqual(first, second)
        self.assertEqual(repair.BUNDLE_FORMAT, first["format"])
        digest = first["bundle_sha256"]
        unhashed = dict(first)
        unhashed.pop("bundle_sha256")
        self.assertEqual(inspector.sha256(inspector.canonical_bytes(unhashed)), digest)
        self.assertEqual(self.checkpoint, first["checkpoint"])
        self.assertEqual(
            base64.b64decode(first["source"]["pointer"]["bytes_base64"]),
            self.fixture.pointer_path.read_bytes(),
        )
        text = inspector.canonical_document(first).decode()
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("timestamp", text)
        self.assertNotIn("created_at", text)

    def test_checkpoint_schema_is_exact_and_accepts_documented_optional_forms(self):
        recorded = json.loads(json.dumps(self.checkpoint))
        recorded["observations"]["latest_validation"] = {
            "status": "PASS",
            "attempt_id": "validation-1",
            "checks": [
                {
                    "name": "tests",
                    "status": "PASS",
                    "result": recorded["authority"]["event"],
                }
            ],
        }
        recorded["checkpoint_sha256"] = self.redigest(
            recorded, "checkpoint_sha256"
        )
        repair.validate_checkpoint(recorded)

        mutations = (
            lambda value: value.__setitem__("extra", True),
            lambda value: value.pop("observations"),
            lambda value: value["authority"].__setitem__("issue", True),
            lambda value: value["authority"]["index"].__setitem__("extra", True),
            lambda value: value["repository"]["worktree"].__setitem__("clean", 1),
            lambda value: value["observations"]["latest_validation"].__setitem__(
                "extra", True
            ),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                malformed = json.loads(json.dumps(recorded))
                mutate(malformed)
                malformed["checkpoint_sha256"] = self.redigest(
                    malformed, "checkpoint_sha256"
                )
                with self.assertRaises(repair.RepairFailure) as raised:
                    repair.validate_checkpoint(malformed)
                self.assertEqual("invalid-checkpoint", raised.exception.code)

    def test_prepare_and_dry_run_use_no_filesystem_write_api(self):
        self.request()
        before = self.snapshot()
        with mock.patch.object(repair, "_publish_immutable", side_effect=AssertionError), \
             mock.patch.object(repair, "_publish_singleton", side_effect=AssertionError), \
             mock.patch.object(repair, "_replace_pointer", side_effect=AssertionError), \
             mock.patch.object(pathlib.Path, "write_bytes", side_effect=AssertionError):
            bundle = repair.prepare(
                self.root, self.fixture.issue, self.checkpoint_path, self.request_path
            )
            result = repair.dry_run(self.root, bundle)
        self.assertEqual("dry-run", result["outcome"]["status"])
        self.assertEqual("source-applicable", result["outcome"]["code"])
        self.assertEqual(before, self.snapshot())

    def test_dry_run_classifies_target_and_advanced_pointer_without_writes(self):
        bundle = self.prepare()
        repair.apply_bundle(self.root, bundle)
        before_target = self.snapshot()
        target = repair.dry_run(self.root, bundle)
        self.assertEqual("target-already-applied", target["outcome"]["code"])
        self.assertEqual(before_target, self.snapshot())

        advanced = json.loads(
            base64.b64decode(bundle["target"]["pointer"]["bytes_base64"])
        )
        advanced["generation"] += 1
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(advanced))
        before_advanced = self.snapshot()
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.dry_run(self.root, bundle)
        self.assertEqual("stale", raised.exception.status)
        self.assertEqual("source-preconditions-stale", raised.exception.code)
        self.assertEqual(before_advanced, self.snapshot())

    def test_pointer_binding_dry_run_apply_plan_and_idempotence(self):
        bundle = self.prepare()
        before_objects = {
            path.relative_to(self.fixture.store).as_posix()
            for path in self.fixture.store.rglob("*")
            if path.is_file()
        }
        dry = repair.dry_run(self.root, bundle)
        applied = repair.apply_bundle(self.root, bundle)
        repeated = repair.apply_bundle(self.root, bundle)
        self.assertEqual(dry["plan"], applied["plan"])
        self.assertEqual(applied, repeated)
        self.assertEqual(
            bundle["target"]["checkpoint"],
            inspector.checkpoint_document(inspector.inspect(self.root, self.fixture.issue)),
        )
        after_objects = {
            path.relative_to(self.fixture.store).as_posix()
            for path in self.fixture.store.rglob("*")
            if path.is_file()
        }
        self.assertTrue(before_objects <= after_objects)
        self.assertFalse(
            (self.root / ".agent-workflow").exists(),
            "repair must not create worktree projections",
        )

    def test_pointer_binding_denies_synthesized_authority_and_logical_mutation(self):
        binding = latest_run_update(self.fixture)
        pointer = self.fixture.pointer()
        envelope = json.loads(
            self.fixture.object_path(binding["envelope"]).read_text()
        )
        state = json.loads(self.fixture.object_path(envelope["state"]).read_text())
        state["state"] = "IMPLEMENTING"
        state_record = object_record("run-state", state)
        envelope["state"] = {
            key: state_record[key] for key in ("kind", "sha256", "size")
        }
        envelope_record = object_record("run-envelope", envelope)
        forged = {
            **binding,
            "envelope": {
                key: envelope_record[key] for key in ("kind", "sha256", "size")
            },
        }
        self.request(
            {"type": "pointer-binding", "binding": forged},
            [state_record, envelope_record],
        )
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root, self.fixture.issue, self.checkpoint_path, self.request_path
            )
        self.assertEqual("binding-source-mismatch", raised.exception.code)

        for field, value in (
            ("sequence", binding["sequence"] + 1),
            ("event_tip", "f" * 64),
        ):
            with self.subTest(field=field):
                self.request(
                    {"type": "pointer-binding", "binding": {**binding, field: value}}
                )
                with self.assertRaises(repair.RepairFailure) as raised:
                    repair.prepare(
                        self.root,
                        self.fixture.issue,
                        self.checkpoint_path,
                        self.request_path,
                    )
                self.assertEqual("binding-source-mismatch", raised.exception.code)

        pointer_binding = {**binding, "state": pointer["selection"]["run"]["state"]}
        self.request({"type": "pointer-binding", "binding": pointer_binding})
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root, self.fixture.issue, self.checkpoint_path, self.request_path
            )
        self.assertEqual("invalid-target-binding", raised.exception.code)

    def test_pointer_binding_bundle_revalidation_requires_exact_source_binding(self):
        bundle = self.prepare()
        index_item = next(
            item for item in bundle["objects"] if item["kind"] == "issue-index"
        )
        index = json.loads(base64.b64decode(index_item["bytes_base64"]))
        index["run_update"]["sequence"] += 1
        replacement = object_record("issue-index", index)
        bundle["objects"] = [
            replacement if item is index_item else item for item in bundle["objects"]
        ]
        pointer = json.loads(base64.b64decode(bundle["target"]["pointer"]["bytes_base64"]))
        pointer["index"] = {
            key: replacement[key] for key in ("kind", "sha256", "size")
        }
        pointer_data = inspector.canonical_bytes(pointer)
        bundle["target"]["pointer"] = {
            "bytes_base64": base64.b64encode(pointer_data).decode("ascii"),
            "sha256": inspector.sha256(pointer_data),
            "size": len(pointer_data),
        }
        bundle["plan"] = repair._build_plan(
            bundle["issue"],
            bundle["operation"]["type"],
            bundle["source"]["pointer"],
            bundle["target"]["pointer"],
            bundle["objects"],
            bundle["target"]["checkpoint"],
        )
        bundle["bundle_sha256"] = self.redigest(bundle, "bundle_sha256")
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.validate_bundle(bundle, self.root)
        self.assertEqual("pointer-binding-run-update", raised.exception.code)

    def test_authorized_integrity_reseal_and_declared_missing_or_corrupt_source(self):
        reseal = self.reseal_request()
        self.assertEqual("integrity-reseal", reseal["operation"]["type"])
        repair.apply_bundle(self.root, reseal)

        for status, code, damage in (
            ("missing", "object-missing", lambda path: path.unlink()),
            ("corrupt", "object-hash-or-size-mismatch", lambda path: path.write_bytes(b"{")),
        ):
            with self.subTest(status=status):
                other = WorkflowRepairTestFixture(self)
                try:
                    operation, objects = reseal_material(
                        other.fixture,
                        other.checkpoint,
                        {"status": status, "code": code},
                    )
                    envelope = other.fixture.object_path(
                        other.fixture.pointer()["selection"]["run"]["envelope"]
                    )
                    damage(envelope)
                    other.request(operation, objects)
                    reseal = repair.prepare(
                        other.root,
                        other.fixture.issue,
                        other.checkpoint_path,
                        other.request_path,
                    )
                    repair.apply_bundle(other.root, reseal)
                    self.assertEqual(
                        reseal["target"]["checkpoint"],
                        inspector.checkpoint_document(
                            inspector.inspect(other.root, other.fixture.issue)
                        ),
                    )
                finally:
                    other.close()

    def test_declared_failure_reseal_reconstructs_source_independently(self):
        forged = json.loads(json.dumps(self.checkpoint))
        forged["authority"]["pointer_sha256"] = "0" * 64
        forged["checkpoint_sha256"] = self.redigest(
            forged, "checkpoint_sha256"
        )
        operation, objects = reseal_material(
            self.fixture,
            forged,
            {"status": "missing", "code": "object-missing"},
        )
        envelope = self.fixture.object_path(
            self.fixture.pointer()["selection"]["run"]["envelope"]
        )
        envelope.unlink()
        self.checkpoint_path.write_bytes(inspector.canonical_document(forged))
        self.request(operation, objects)

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root,
                self.fixture.issue,
                self.checkpoint_path,
                self.request_path,
            )

        self.assertEqual("denied", raised.exception.status)
        self.assertEqual("source-checkpoint-mismatch", raised.exception.code)

    def test_denies_unreferenced_bundled_object(self):
        extra = object_record("issue-index", {"unreferenced": True})
        self.request(objects=[extra])
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root, self.fixture.issue, self.checkpoint_path, self.request_path
            )
        self.assertEqual("unreferenced-bundled-object", raised.exception.code)

    def test_integrity_reseal_cannot_republish_state_objects(self):
        operation, objects = reseal_material(self.fixture, self.checkpoint)
        state_reference = self.checkpoint["authority"]["state"]
        state_data = self.fixture.object_path(state_reference).read_bytes()
        objects.append({
            **state_reference,
            "bytes_base64": base64.b64encode(state_data).decode("ascii"),
        })
        self.request(operation, objects)

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root,
                self.fixture.issue,
                self.checkpoint_path,
                self.request_path,
            )

        self.assertEqual("denied", raised.exception.status)
        self.assertEqual("unsupported-reseal-scope", raised.exception.code)

    def test_denies_wrong_confirmation_and_malformed_documents(self):
        self.request(confirmation="yes")
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.prepare(
                self.root, self.fixture.issue, self.checkpoint_path, self.request_path
            )
        self.assertEqual("confirmation-mismatch", raised.exception.code)

        bundle = self.prepare()
        bundle["objects"][0]["size"] += 1
        bundle["bundle_sha256"] = self.redigest(bundle, "bundle_sha256")
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.validate_bundle(bundle)
        self.assertEqual("byte-record-mismatch", raised.exception.code)

    def test_stale_pointer_and_repository_facts_write_no_authority_or_projection(self):
        bundle = self.prepare()
        pointer_before = self.fixture.pointer_path.read_bytes()
        projection = self.root / ".agent-workflow"
        self.fixture.pointer_path.write_bytes(pointer_before + b"\n")
        stale_pointer = self.fixture.pointer_path.read_bytes()
        with self.assertRaises(repair.RepairFailure):
            repair.apply_bundle(self.root, bundle)
        self.assertEqual(stale_pointer, self.fixture.pointer_path.read_bytes())
        self.assertFalse(projection.exists())

        self.fixture.pointer_path.write_bytes(pointer_before)
        (self.root / "tracked.txt").write_text("changed\n")
        with self.assertRaises(repair.RepairFailure):
            repair.apply_bundle(self.root, bundle)
        self.assertEqual(pointer_before, self.fixture.pointer_path.read_bytes())
        self.assertFalse(projection.exists())

    def test_validated_stale_attempt_publishes_linked_failure_audit(self):
        bundle = self.prepare()
        self.fixture.pointer_path.write_bytes(b"{}\n")

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.apply_bundle(self.root, bundle)

        failure = raised.exception
        self.assertEqual("stale", failure.status)
        self.assertEqual(bundle["bundle_sha256"], failure.bundle_sha256)
        self.assertIsNotNone(failure.audit)
        store = inspector.resolve_store(self.root)
        audit_path = (
            store.store_dir
            / "repair-audits"
            / "sha256"
            / failure.audit["sha256"][:2]
            / failure.audit["sha256"][2:]
        )
        audit = json.loads(audit_path.read_text())
        self.assertEqual(
            {"status": "stale", "code": "source-preconditions-stale"},
            audit["outcome"],
        )
        self.assertEqual(bundle["bundle_sha256"], audit["bundle_sha256"])

    def test_existing_exact_object_collision_and_symlink_fail_closed(self):
        for mode in ("exact", "collision", "symlink"):
            with self.subTest(mode=mode):
                other = WorkflowRepairTestFixture(self)
                try:
                    bundle = other.prepare_pointer()
                    item = other.new_object(bundle)
                    path = inspector.object_path(
                        inspector.resolve_store(other.root), item["sha256"]
                    )
                    path.parent.mkdir(parents=True, exist_ok=True)
                    if mode == "exact":
                        path.write_bytes(base64.b64decode(item["bytes_base64"]))
                        result = repair.apply_bundle(other.root, bundle)
                        self.assertEqual("applied", result["outcome"]["status"])
                    elif mode == "collision":
                        path.write_bytes(b"conflict")
                        with self.assertRaises(repair.RepairFailure) as raised:
                            repair.apply_bundle(other.root, bundle)
                        self.assertEqual("immutable-object-collision", raised.exception.code)
                    else:
                        path.symlink_to(other.root / "tracked.txt")
                        with self.assertRaises(repair.RepairFailure) as raised:
                            repair.apply_bundle(other.root, bundle)
                        self.assertEqual(
                            "immutable-destination-not-regular", raised.exception.code
                        )
                finally:
                    other.close()

    def test_existing_object_replacement_or_symlink_race_fails_closed(self):
        path = self.root / ".git" / "race-object"
        expected = b"verified bytes"
        path.write_bytes(expected)
        replacement_target = self.root / "tracked.txt"
        real_read = os.read
        replaced = []

        def replacing_read(descriptor, size):
            chunk = real_read(descriptor, size)
            if chunk and not replaced:
                replaced.append(True)
                path.unlink()
                path.symlink_to(replacement_target)
            return chunk

        with mock.patch.object(repair.os, "read", side_effect=replacing_read):
            with self.assertRaises(repair.RepairFailure) as raised:
                repair._verify_existing(path, expected)
        self.assertEqual("immutable-destination-changed", raised.exception.code)

    def test_interrupted_object_temp_write_never_exposes_final_object(self):
        bundle = self.prepare()
        item = self.new_object(bundle)
        data = base64.b64decode(item["bytes_base64"])
        path = inspector.object_path(inspector.resolve_store(self.root), item["sha256"])
        real_write = os.write

        def interrupted_write(descriptor, chunk):
            real_write(descriptor, bytes(chunk[:1]))
            raise OSError("injected short object write")

        with mock.patch.object(repair.os, "write", side_effect=interrupted_write):
            with self.assertRaises(OSError):
                repair._publish_immutable(path, data)
        self.assertFalse(path.exists())
        self.assertFalse(any(path.parent.glob(".*.repair-*")))

    def test_recovery_at_every_boundary(self):
        for stage in repair.PHASES:
            with self.subTest(stage=stage):
                other = WorkflowRepairTestFixture(self)
                try:
                    bundle = other.prepare_pointer()
                    fired = []

                    def interrupt(current):
                        if current == stage and not fired:
                            fired.append(current)
                            raise InterruptedError(stage)

                    with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
                        with self.assertRaises(InterruptedError):
                            repair.apply_bundle(other.root, bundle)
                    recovered = repair.recover(other.root, other.fixture.issue)
                    if stage == "before-journal-publication":
                        self.assertEqual("clean", recovered["outcome"]["status"])
                        repair.apply_bundle(other.root, bundle)
                    elif stage == "after-journal-removal":
                        self.assertEqual("clean", recovered["outcome"]["status"])
                    else:
                        self.assertEqual("applied", recovered["outcome"]["status"])
                    self.assertEqual(
                        bundle["target"]["pointer"]["sha256"],
                        inspector.sha256(other.fixture.pointer_path.read_bytes()),
                    )
                finally:
                    other.close()

    def test_pointer_race_at_final_recheck_retains_journal(self):
        bundle = self.prepare()
        advanced = json.loads(self.fixture.pointer_path.read_text())
        advanced["generation"] += 2
        advanced_data = inspector.canonical_bytes(advanced)

        def advance_pointer(stage):
            if stage == "before-pointer-publication":
                self.fixture.pointer_path.write_bytes(advanced_data)

        with mock.patch.object(repair, "phase_hook", side_effect=advance_pointer):
            with self.assertRaises(repair.RepairFailure) as raised:
                repair.apply_bundle(self.root, bundle)

        self.assertEqual("pointer-conflict", raised.exception.code)
        self.assertEqual(advanced_data, self.fixture.pointer_path.read_bytes())
        journal = (
            inspector.resolve_store(self.root).store_dir
            / "issues"
            / str(self.fixture.issue)
            / "repair-journal.json"
        )
        self.assertTrue(journal.is_file())

    def test_multi_object_reseal_recovers_after_first_object(self):
        bundle = self.reseal_request()
        store = inspector.resolve_store(self.root)
        object_paths = [
            inspector.object_path(store, item["sha256"]) for item in bundle["objects"]
        ]
        fired = []

        def interrupt(stage):
            if stage == "after-object-publication" and not fired:
                fired.append(stage)
                raise InterruptedError(stage)

        with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
            with self.assertRaises(InterruptedError):
                repair.apply_bundle(self.root, bundle)
        self.assertEqual(1, sum(path.exists() for path in object_paths))
        result = repair.recover(self.root, self.fixture.issue)
        self.assertEqual("applied", result["outcome"]["status"])
        self.assertTrue(all(path.is_file() for path in object_paths))

    def test_postpublication_checkpoint_mismatch_is_conflict(self):
        bundle = self.prepare()
        real_inspect = inspector.inspect

        def drifting_inspect(root, issue, **selector):
            subject = real_inspect(root, issue, **selector)
            if self.fixture.pointer_path.read_bytes() == base64.b64decode(
                bundle["target"]["pointer"]["bytes_base64"]
            ):
                subject["repository"] = {
                    **subject["repository"],
                    "head": "f" * 40,
                }
            return subject

        with mock.patch.object(repair.inspector, "inspect", side_effect=drifting_inspect):
            with self.assertRaises(repair.RepairFailure) as raised:
                repair.apply_bundle(self.root, bundle)
        self.assertEqual("conflict", raised.exception.status)
        self.assertEqual("postcondition-mismatch", raised.exception.code)

    def test_postpublication_inspection_failure_is_conflict(self):
        bundle = self.prepare()
        real_inspect = inspector.inspect
        target_data = base64.b64decode(bundle["target"]["pointer"]["bytes_base64"])

        def failing_inspect(root, issue, **selector):
            if self.fixture.pointer_path.read_bytes() == target_data:
                raise inspector.InspectionFailure(
                    "corrupt", "injected-postcondition", "injected postcondition failure"
                )
            return real_inspect(root, issue, **selector)

        with mock.patch.object(repair.inspector, "inspect", side_effect=failing_inspect):
            with self.assertRaises(repair.RepairFailure) as raised:
                repair.apply_bundle(self.root, bundle)
        self.assertEqual("conflict", raised.exception.status)
        self.assertEqual("postcondition-injected-postcondition", raised.exception.code)

    def test_recovery_maps_target_validation_failure_to_audited_conflict(self):
        bundle = self.prepare()
        fired = []

        def interrupt(stage):
            if stage == "after-pointer-publication" and not fired:
                fired.append(stage)
                raise InterruptedError(stage)

        with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
            with self.assertRaises(InterruptedError):
                repair.apply_bundle(self.root, bundle)
        (self.root / "tracked.txt").write_text("repository drift\n")

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.recover(self.root, self.fixture.issue)

        failure = raised.exception
        self.assertEqual("conflict", failure.status)
        self.assertEqual(
            "postcondition-target-checkpoint-mismatch",
            failure.code,
        )
        self.assertEqual(bundle["bundle_sha256"], failure.bundle_sha256)
        self.assertIsNotNone(failure.audit)

    def test_reapply_committed_corrupt_target_is_audited_conflict(self):
        bundle = self.prepare()
        repair.apply_bundle(self.root, bundle)
        item = self.new_object(bundle)
        path = inspector.object_path(
            inspector.resolve_store(self.root), item["sha256"]
        )
        path.write_bytes(b"corrupt")
        pointer_before = self.fixture.pointer_path.read_bytes()

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.apply_bundle(self.root, bundle)

        self.assertEqual("conflict", raised.exception.status)
        self.assertTrue(raised.exception.code.startswith("postcondition-"))
        self.assertIsNotNone(raised.exception.audit)
        self.assertEqual(pointer_before, self.fixture.pointer_path.read_bytes())
        self.assertFalse(
            (
                inspector.resolve_store(self.root).store_dir
                / "issues"
                / str(self.fixture.issue)
                / "repair-journal.json"
            ).exists()
        )

    def test_target_recovery_translates_inspector_failure_to_audited_conflict(self):
        bundle = self.prepare()
        fired = []

        def interrupt(stage):
            if stage == "after-pointer-publication" and not fired:
                fired.append(stage)
                raise InterruptedError(stage)

        with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
            with self.assertRaises(InterruptedError):
                repair.apply_bundle(self.root, bundle)
        source_index = self.fixture.object_path(bundle["source"]["index"])
        source_index.unlink()

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.recover(self.root, self.fixture.issue)

        failure = raised.exception
        self.assertEqual("conflict", failure.status)
        self.assertEqual("postcondition-target-object-missing", failure.code)
        self.assertEqual(bundle["bundle_sha256"], failure.bundle_sha256)
        self.assertIsNotNone(failure.audit)

    def test_existing_journal_failure_is_audited_against_its_bundle(self):
        first = self.prepare()
        request = self.request()
        request["reason"] = "a distinct subsequent repair"
        self.request_path.write_bytes(inspector.canonical_document(request))
        second = repair.prepare(
            self.root,
            self.fixture.issue,
            self.checkpoint_path,
            self.request_path,
        )
        self.assertNotEqual(first["bundle_sha256"], second["bundle_sha256"])
        store = inspector.resolve_store(self.root)
        journal = (
            store.store_dir
            / "issues"
            / str(self.fixture.issue)
            / "repair-journal.json"
        )
        journal.write_bytes(
            inspector.canonical_bytes(repair._journal_document(first))
        )
        self.fixture.pointer_path.write_bytes(b"{}\n")

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.apply_bundle(self.root, second)

        self.assertEqual(first["bundle_sha256"], raised.exception.bundle_sha256)
        self.assertNotEqual(second["bundle_sha256"], raised.exception.bundle_sha256)
        self.assertIsNotNone(raised.exception.audit)

    def test_existing_journal_recovery_precedes_incoming_bundle_validation(self):
        first = self.prepare()
        request = self.request()
        request["reason"] = "a distinct subsequent repair"
        self.request_path.write_bytes(inspector.canonical_document(request))
        second = repair.prepare(
            self.root,
            self.fixture.issue,
            self.checkpoint_path,
            self.request_path,
        )
        fired = []

        def interrupt(stage):
            if stage == "after-journal-publication" and not fired:
                fired.append(stage)
                raise InterruptedError(stage)

        with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
            with self.assertRaises(InterruptedError):
                repair.apply_bundle(self.root, first)
        (self.root / "tracked.txt").write_text("repository drift\n")

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.apply_bundle(self.root, second)

        self.assertEqual(first["bundle_sha256"], raised.exception.bundle_sha256)
        self.assertNotEqual(second["bundle_sha256"], raised.exception.bundle_sha256)
        self.assertIsNotNone(raised.exception.audit)

    def test_apply_rejects_journal_owned_by_another_issue(self):
        bundle = self.prepare()
        foreign = json.loads(json.dumps(bundle))
        foreign["issue"] += 1
        store = inspector.resolve_store(self.root)
        journal = (
            store.store_dir
            / "issues"
            / str(self.fixture.issue)
            / "repair-journal.json"
        )
        journal.write_bytes(b"placeholder")

        with mock.patch.object(repair, "_validate_journal", return_value=foreign):
            with self.assertRaises(repair.RepairFailure) as raised:
                repair.apply_bundle(self.root, bundle)

        self.assertEqual("conflict", raised.exception.status)
        self.assertEqual("journal-issue-mismatch", raised.exception.code)
        self.assertTrue(journal.exists())

    def test_journal_symlink_is_rejected_by_apply_and_recover(self):
        for operation in ("apply", "recover"):
            with self.subTest(operation=operation):
                other = WorkflowRepairTestFixture(self)
                try:
                    bundle = other.prepare_pointer()
                    store = inspector.resolve_store(other.root)
                    journal = (
                        store.store_dir
                        / "issues"
                        / str(other.fixture.issue)
                        / "repair-journal.json"
                    )
                    journal.symlink_to(other.root / "tracked.txt")
                    with self.assertRaises(repair.RepairFailure) as raised:
                        if operation == "apply":
                            repair.apply_bundle(other.root, bundle)
                        else:
                            repair.recover(other.root, other.fixture.issue)
                    self.assertEqual("journal-not-regular", raised.exception.code)
                    self.assertTrue(journal.is_symlink())
                finally:
                    other.close()

    def test_journal_fifo_is_rejected_without_blocking(self):
        bundle = self.prepare()
        store = inspector.resolve_store(self.root)
        journal = (
            store.store_dir
            / "issues"
            / str(self.fixture.issue)
            / "repair-journal.json"
        )
        os.mkfifo(journal)

        with self.assertRaises(repair.RepairFailure) as raised:
            repair.apply_bundle(self.root, bundle)

        self.assertEqual("journal-not-regular", raised.exception.code)
        self.assertTrue(stat.S_ISFIFO(journal.lstat().st_mode))

    def test_valid_journal_missing_or_unreadable_pointer_is_conflict(self):
        for mode in ("missing", "unreadable"):
            with self.subTest(mode=mode):
                other = WorkflowRepairTestFixture(self)
                try:
                    bundle = other.prepare_pointer()
                    store = inspector.resolve_store(other.root)
                    journal = (
                        store.store_dir
                        / "issues"
                        / str(other.fixture.issue)
                        / "repair-journal.json"
                    )
                    journal.write_bytes(
                        inspector.canonical_bytes(repair._journal_document(bundle))
                    )
                    if mode == "missing":
                        other.fixture.pointer_path.unlink()
                        context = contextlib.nullcontext()
                    else:
                        real_read = pathlib.Path.read_bytes

                        def read_bytes(path):
                            if path == other.fixture.pointer_path:
                                raise PermissionError("injected unreadable pointer")
                            return real_read(path)

                        context = mock.patch.object(
                            pathlib.Path, "read_bytes", new=read_bytes
                        )
                    with context:
                        with self.assertRaises(repair.RepairFailure) as raised:
                            repair.recover(other.root, other.fixture.issue)
                    self.assertEqual("conflict", raised.exception.status)
                    self.assertEqual("pointer-conflict", raised.exception.code)
                    self.assertTrue(journal.is_file())
                finally:
                    other.close()

    def test_recovery_source_target_conflict_and_malformed_journal(self):
        bundle = self.prepare()
        store = inspector.resolve_store(self.root)
        issue_dir = store.store_dir / "issues" / str(self.fixture.issue)
        journal = issue_dir / "repair-journal.json"
        journal.write_bytes(inspector.canonical_bytes(repair._journal_document(bundle)))
        source = repair.recover(self.root, self.fixture.issue)
        self.assertEqual("applied", source["outcome"]["status"])

        journal.write_bytes(inspector.canonical_bytes(repair._journal_document(bundle)))
        target = repair.recover(self.root, self.fixture.issue)
        self.assertEqual("applied", target["outcome"]["status"])

        journal.write_bytes(inspector.canonical_bytes(repair._journal_document(bundle)))
        self.fixture.pointer_path.write_bytes(b"{}")
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.recover(self.root, self.fixture.issue)
        self.assertEqual("pointer-conflict", raised.exception.code)
        self.assertTrue(journal.exists())

        journal.write_bytes(b"{}")
        with self.assertRaises(repair.RepairFailure) as raised:
            repair.recover(self.root, self.fixture.issue)
        self.assertEqual("malformed-journal", raised.exception.code)
        self.assertTrue(journal.exists())

    def test_concurrent_apply_serializes(self):
        bundle = self.prepare()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(
                executor.map(lambda _unused: repair.apply_bundle(self.root, bundle), range(2))
            )
        self.assertEqual(results[0], results[1])
        self.assertEqual(
            bundle["target"]["pointer"]["sha256"],
            inspector.sha256(self.fixture.pointer_path.read_bytes()),
        )

    def test_run_id_and_correction_selectors(self):
        run_id = self.fixture.run_id
        self.request(selector={"run_id": run_id})
        bundle = repair.prepare(
            self.root, self.fixture.issue, self.checkpoint_path, self.request_path
        )
        self.assertEqual({"run_id": run_id}, bundle["selector"])

        other = WorkflowRepairTestFixture(self)
        try:
            other.fixture.add_correction(1)
            other.checkpoint = inspector.checkpoint_document(
                inspector.inspect(other.root, other.fixture.issue, correction=1)
            )
            other.checkpoint_path.write_bytes(
                inspector.canonical_document(other.checkpoint)
            )
            other.request(
                {
                    "type": "pointer-binding",
                    "binding": latest_run_update(other.fixture),
                },
                [],
                selector={"correction": 1},
            )
            correction_bundle = repair.prepare(
                other.root,
                other.fixture.issue,
                other.checkpoint_path,
                other.request_path,
            )
            result = repair.apply_bundle(other.root, correction_bundle)
            self.assertEqual("applied", result["outcome"]["status"])
            self.assertEqual(1, result["target"]["authority"]["correction"])
        finally:
            other.close()

    def test_cli_consumes_public_checkpoint_and_emits_canonical_json(self):
        self.request()
        command = [
            "python3",
            str(SCRIPTS / "workflow_repair.py"),
            "prepare",
            str(self.fixture.issue),
            "--root",
            str(self.root),
            "--checkpoint",
            str(self.checkpoint_path),
            "--request",
            str(self.request_path),
        ]
        result = subprocess.run(command, check=True, stdout=subprocess.PIPE)
        value = json.loads(result.stdout)
        self.assertEqual(repair.BUNDLE_FORMAT, value["format"])
        self.assertEqual(inspector.canonical_document(value), result.stdout)

    def test_real_frozen_115_integration_is_read_only_when_available(self):
        repository = SCRIPTS.parent
        store = inspector.resolve_store(repository)
        pointer = store.store_dir / "issues" / "115" / "index-integrity.json"
        if not pointer.is_file():
            self.skipTest("frozen #115 durable pointer is not available")
        before = pointer.read_bytes()
        try:
            checkpoint = inspector.checkpoint_document(inspector.inspect(repository, 115))
        except inspector.InspectionFailure as failure:
            self.assertIn(failure.status, inspector.OUTCOME_EXIT_CODES)
        else:
            repair.validate_checkpoint(checkpoint)
        self.assertEqual(before, pointer.read_bytes())

    @staticmethod
    def redigest(value, field):
        unhashed = dict(value)
        unhashed.pop(field, None)
        return inspector.sha256(inspector.canonical_bytes(unhashed))


class WorkflowRepairTestFixture:
    def __init__(self, test):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.fixture = InspectorFixture(self.root)
        self.checkpoint = inspector.checkpoint_document(
            inspector.inspect(self.root, self.fixture.issue)
        )
        self.checkpoint_path = self.root / ".git" / "checkpoint.json"
        self.request_path = self.root / ".git" / "request.json"
        self.checkpoint_path.write_bytes(inspector.canonical_document(self.checkpoint))
        self.test = test

    def request(self, operation, objects, selector=None):
        value = {
            "format": repair.REQUEST_FORMAT,
            "issue": self.fixture.issue,
            "selector": selector or {"current": True},
            "operation": operation,
            "objects": objects,
            "operator": "test-operator",
            "reason": "recover exact authority",
            "confirmation": (
                repair.POINTER_BINDING_CONFIRMATION
                if operation["type"] == "pointer-binding"
                else repair.INTEGRITY_RESEAL_CONFIRMATION
            ).format(issue=self.fixture.issue),
        }
        self.request_path.write_bytes(inspector.canonical_document(value))

    def prepare_pointer(self):
        self.request(
            {"type": "pointer-binding", "binding": latest_run_update(self.fixture)}, []
        )
        return repair.prepare(
            self.root,
            self.fixture.issue,
            self.checkpoint_path,
            self.request_path,
        )

    def new_object(self, bundle):
        source_hashes = {
            item["sha256"] for item in self.checkpoint["authority"]["verified_objects"]
        }
        return next(item for item in bundle["objects"] if item["sha256"] not in source_hashes)

    def close(self):
        self.temporary.cleanup()


if __name__ == "__main__":
    unittest.main()
