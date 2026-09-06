import ast
import concurrent.futures
import copy
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import workflow_authority as authority
from scripts import workflow_authority_repair as repair
from scripts import workflow_inspector as inspector
from scripts.tests.test_workflow_authority import AuthorityFixture


SCRIPTS = pathlib.Path(__file__).parents[1]
REPOSITORY = SCRIPTS.parent


class RepairFixture:
    def __init__(self, keep_legacy=False):
        self.authority = AuthorityFixture()
        self.root = self.authority.root
        self.issue = self.authority.issue
        if not keep_legacy:
            self.authority.inspector.pointer_path.unlink()
        self.binding, self.state, _bundle = self.authority.install_genesis()
        self.checkpoint = authority.checkpoint(self.root, self.issue)
        self.target_data = inspector.canonical_bytes(self.checkpoint["pointer"])
        self.checkpoint_path = self.root / ".git" / "orchestration-checkpoint.json"
        self.request_path = self.root / ".git" / "orchestration-repair-request.json"
        self.bundle_path = self.root / ".git" / "orchestration-repair-bundle.json"
        self.write_checkpoint(self.checkpoint)

    @property
    def pointer_path(self):
        return self.authority.pointer_path

    @property
    def directory(self):
        return self.pointer_path.parent

    @property
    def journal_path(self):
        return self.directory / "repair-journal.json"

    def close(self):
        self.authority.close()

    def write_checkpoint(self, checkpoint):
        self.checkpoint_path.write_bytes(inspector.canonical_document(checkpoint))

    def source(self):
        return repair._source_observation(self.root, self.issue, self.target_data)

    def context(self):
        verified = authority.verify_checkpoint(self.root, self.checkpoint)
        return repair._authority_context(self.checkpoint, verified["state"])

    def request(self, **changes):
        value = {
            "format": repair.REQUEST_FORMAT,
            "issue": self.issue,
            "operation": {"type": repair.OPERATION},
            "operator": "test-operator",
            "reason": "restore independently captured pointer bytes",
            "confirmation": repair.CONFIRMATION.format(issue=self.issue),
            "selection": repair.SELECTION,
            "quiescence": repair.QUIESCENCE,
            "source": self.source(),
            "target_pointer": repair._record(self.target_data),
            "authority_context": self.context(),
        }
        value.update(changes)
        self.request_path.write_bytes(inspector.canonical_document(value))
        return value

    def prepare(self, **request_changes):
        self.request(**request_changes)
        bundle = repair.prepare(
            self.root, self.issue, self.checkpoint_path, self.request_path)
        self.bundle_path.write_bytes(inspector.canonical_document(bundle))
        return bundle

    def object_snapshot(self):
        root = self.authority.inspector.store / "objects"
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in root.rglob("*") if path.is_file()
        }


class WorkflowAuthorityRepairTest(unittest.TestCase):
    def setUp(self):
        self.fixture = RepairFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, action):
        with self.assertRaises(repair.OrchestrationRepairFailure) as raised:
            action()
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def rehash_checkpoint(self, checkpoint):
        unsigned = dict(checkpoint)
        unsigned.pop("checkpoint_sha256", None)
        checkpoint["checkpoint_sha256"] = inspector.sha256(
            inspector.canonical_bytes(unsigned))
        return checkpoint

    def test_missing_checkpoint_restore_preserves_immutable_state(self):
        before = self.fixture.object_snapshot()
        self.fixture.pointer_path.unlink()
        bundle = self.fixture.prepare()

        dry_run = repair.dry_run(self.fixture.root, bundle)
        result = repair.apply_bundle(self.fixture.root, bundle)

        self.assertEqual("source-applicable", dry_run["outcome"]["code"])
        self.assertEqual("target-verified", result["outcome"]["code"])
        self.assertEqual(self.fixture.target_data, self.fixture.pointer_path.read_bytes())
        self.assertEqual(self.fixture.checkpoint,
                         authority.checkpoint(self.fixture.root, self.fixture.issue))
        self.assertEqual(before, self.fixture.object_snapshot())
        self.assertFalse(self.fixture.journal_path.exists())
        receipt = result["receipt"]
        receipt_path = (
            self.fixture.authority.inspector.store
            / "orchestration-repair-receipts" / "sha256"
            / receipt["sha256"][:2] / receipt["sha256"][2:]
        )
        self.assertEqual(receipt["size"], len(receipt_path.read_bytes()))

    def test_corrupt_restore_and_existing_target_are_idempotent(self):
        self.fixture.pointer_path.write_bytes(b'{"broken":true}')
        bundle = self.fixture.prepare()
        first = repair.apply_bundle(self.fixture.root, bundle)
        second = repair.apply_bundle(self.fixture.root, bundle)

        self.assertEqual(first["receipt"], second["receipt"])
        self.assertEqual(self.fixture.target_data, self.fixture.pointer_path.read_bytes())
        self.assertEqual("target-already-applied",
                         repair.dry_run(self.fixture.root, bundle)["outcome"]["code"])

    def test_public_checkpoint_verification_ignores_live_pointer_but_not_chain(self):
        self.fixture.pointer_path.write_bytes(b"corrupt")
        verified = authority.verify_checkpoint(
            self.fixture.root, self.fixture.checkpoint)
        self.assertEqual(self.fixture.state, verified["state"])

        self.fixture.request()
        binding = self.fixture.checkpoint["chain"][0]["binding"]
        inspector.object_path(
            inspector.resolve_store(self.fixture.root), binding["sha256"]).unlink()
        self.assert_failure(
            "invalid",
            "target-authority-binding-invalid",
            lambda: repair.prepare(
                self.fixture.root,
                self.fixture.issue,
                self.fixture.checkpoint_path,
                self.fixture.request_path,
            ),
        )
        self.assertEqual(b"corrupt", self.fixture.pointer_path.read_bytes())

    def test_malformed_untrusted_and_wrong_identity_targets_fail_closed(self):
        self.fixture.pointer_path.unlink()
        original = self.fixture.object_snapshot()

        wrong_summary = copy.deepcopy(self.fixture.checkpoint)
        wrong_summary["state_sha256"] = "f" * 64
        wrong_summary["chain"][-1]["state_sha256"] = "f" * 64
        self.rehash_checkpoint(wrong_summary)
        self.fixture.write_checkpoint(wrong_summary)
        self.assert_failure(
            "invalid", "target-authority-checkpoint-mismatch",
            lambda: self.fixture.prepare())

        wrong_issue = copy.deepcopy(self.fixture.checkpoint)
        wrong_issue["issue"] = self.fixture.issue + 1
        wrong_issue["pointer"]["issue"] = self.fixture.issue + 1
        pointer_data = inspector.canonical_bytes(wrong_issue["pointer"])
        wrong_issue["pointer_sha256"] = inspector.sha256(pointer_data)
        self.rehash_checkpoint(wrong_issue)
        self.fixture.write_checkpoint(wrong_issue)
        with self.assertRaises(repair.OrchestrationRepairFailure):
            self.fixture.prepare()

        self.fixture.write_checkpoint(self.fixture.checkpoint)
        unauthorized = repair._record(b'{"not":"checkpoint"}')
        self.assert_failure(
            "denied", "target-authorization-mismatch",
            lambda: self.fixture.prepare(target_pointer=unauthorized))
        self.assertFalse(self.fixture.pointer_path.exists())
        self.assertEqual(original, self.fixture.object_snapshot())

    def test_valid_newer_pointer_denies_stale_checkpoint_rollback(self):
        first = self.fixture.binding
        second, _state = self.fixture.authority.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        authority.commit(self.fixture.root, bundle)
        current = self.fixture.pointer_path.read_bytes()

        self.assert_failure(
            "denied", "unsafe-rollback", lambda: self.fixture.prepare())
        self.assertEqual(current, self.fixture.pointer_path.read_bytes())

    def test_future_pointer_format_is_not_treated_as_corruption(self):
        future = {
            **self.fixture.checkpoint["pointer"],
            "format": "chess-echo-orchestration-pointer-v2",
        }
        data = inspector.canonical_bytes(future)
        self.fixture.pointer_path.write_bytes(data)

        self.assert_failure(
            "denied", "source-not-repairable",
            lambda: self.fixture.prepare())
        self.assertEqual(data, self.fixture.pointer_path.read_bytes())

    def test_authorization_text_and_journal_envelope_are_bounded(self):
        self.fixture.pointer_path.unlink()
        self.assert_failure(
            "denied", "authorization-too-large",
            lambda: self.fixture.prepare(
                reason="x" * (repair.AUTHORIZATION_TEXT_LIMIT + 1)))

        bundle = self.fixture.prepare()
        journal = repair._journal_document(bundle)
        self.assertLessEqual(
            len(inspector.canonical_bytes(journal)), repair.JOURNAL_LIMIT)

    def test_third_value_and_concurrent_writer_races_conflict(self):
        self.fixture.pointer_path.unlink()
        bundle = self.fixture.prepare()
        third = b'{"third":"value"}'
        self.fixture.pointer_path.write_bytes(third)
        self.assert_failure(
            "stale", "source-preconditions-stale",
            lambda: repair.apply_bundle(self.fixture.root, bundle))
        self.assertEqual(third, self.fixture.pointer_path.read_bytes())

        self.fixture.pointer_path.unlink()

        def race(stage):
            if stage == "before-pointer-publication":
                self.fixture.pointer_path.write_bytes(third)

        with mock.patch.object(repair, "phase_hook", side_effect=race):
            self.assert_failure(
                "conflict", "pointer-source-mismatch",
                lambda: repair.apply_bundle(self.fixture.root, bundle))
        self.assertEqual(third, self.fixture.pointer_path.read_bytes())
        self.assertTrue(self.fixture.journal_path.exists())

    def test_concurrent_repairs_serialize_and_share_one_receipt(self):
        self.fixture.pointer_path.unlink()
        bundle = self.fixture.prepare()

        def apply():
            return repair.apply_bundle(self.fixture.root, bundle)

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda _unused: apply(), range(2)))

        self.assertEqual(results[0]["receipt"], results[1]["receipt"])
        self.assertEqual(self.fixture.target_data, self.fixture.pointer_path.read_bytes())

    def test_pointer_symlink_fifo_and_nonregular_ancestor_fail_closed(self):
        target = self.fixture.directory / "redirect-target"
        target.write_bytes(b"unchanged")
        self.fixture.pointer_path.unlink()
        self.fixture.pointer_path.symlink_to(target)
        self.assert_failure(
            "conflict", "pointer-not-regular", lambda: self.fixture.prepare())
        self.assertEqual(b"unchanged", target.read_bytes())

        self.fixture.pointer_path.unlink()
        os.mkfifo(self.fixture.pointer_path)
        self.assert_failure(
            "conflict", "pointer-not-regular", lambda: self.fixture.prepare())
        self.fixture.pointer_path.unlink()

        orchestration = self.fixture.authority.inspector.store / "orchestration"
        for path in sorted(orchestration.rglob("*"), reverse=True):
            if path.is_file():
                path.unlink()
            elif path.is_dir():
                path.rmdir()
        orchestration.rmdir()
        redirected = self.fixture.root / "redirected"
        redirected.mkdir()
        orchestration.symlink_to(redirected, target_is_directory=True)
        self.assert_failure(
            "conflict", "authority-directory-conflict",
            lambda: self.fixture.prepare())
        self.assertEqual([], list(redirected.iterdir()))

    def test_lock_journal_and_temporary_attacks_fail_closed(self):
        self.fixture.pointer_path.unlink()
        bundle = self.fixture.prepare()
        lock = self.fixture.directory / "authority.lock"
        lock.unlink()
        target = self.fixture.directory / "lock-target"
        target.write_bytes(b"")
        lock.symlink_to(target)
        self.assert_failure(
            "conflict", "authority-lock-conflict",
            lambda: repair.apply_bundle(self.fixture.root, bundle))
        lock.unlink()

        for kind in ("symlink", "fifo"):
            with self.subTest(kind=kind):
                if self.fixture.journal_path.exists() or self.fixture.journal_path.is_symlink():
                    self.fixture.journal_path.unlink()
                if kind == "symlink":
                    self.fixture.journal_path.symlink_to(target)
                else:
                    os.mkfifo(self.fixture.journal_path)
                self.assert_failure(
                    "conflict", "journal-not-regular",
                    lambda: repair.apply_bundle(self.fixture.root, bundle))
                self.fixture.journal_path.unlink()

        interrupted = self.fixture.directory / (
            authority.TEMPORARY_PREFIX + "interrupted")
        interrupted.write_bytes(b"pending")
        self.assert_failure(
            "conflict", "interrupted-authority-commit",
            lambda: repair.apply_bundle(self.fixture.root, bundle))
        interrupted.unlink()

        unsafe = self.fixture.directory / (
            repair.REPAIR_TEMPORARY_PREFIX + "unsafe")
        unsafe.mkdir()
        self.assert_failure(
            "conflict", "repair-temporary-conflict",
            lambda: repair.apply_bundle(self.fixture.root, bundle))

    def test_recovery_covers_every_durable_boundary(self):
        phases = (
            "before-journal-publication",
            "after-journal-publication",
            "before-target-verification",
            "after-target-verification",
            "before-pointer-publication",
            "after-pointer-publication",
            "before-postcommit-verification",
            "after-postcommit-verification",
            "before-receipt-publication",
            "after-receipt-publication",
            "before-journal-removal",
            "after-journal-removal",
        )
        for phase in phases:
            with self.subTest(phase=phase):
                fixture = RepairFixture()
                try:
                    fixture.pointer_path.unlink()
                    bundle = fixture.prepare()

                    def interrupt(actual):
                        if actual == phase:
                            raise KeyboardInterrupt()

                    with mock.patch.object(
                            repair, "phase_hook", side_effect=interrupt):
                        with self.assertRaises(KeyboardInterrupt):
                            repair.apply_bundle(fixture.root, bundle)
                    recovered = repair.recover(fixture.root, fixture.issue)
                    if phase == "before-journal-publication":
                        self.assertEqual("no-journal", recovered["outcome"]["code"])
                        self.assertFalse(fixture.pointer_path.exists())
                    else:
                        if phase == "after-journal-removal":
                            self.assertEqual("no-journal", recovered["outcome"]["code"])
                        else:
                            self.assertEqual(
                                "target-verified", recovered["outcome"]["code"])
                        self.assertEqual(
                            fixture.target_data, fixture.pointer_path.read_bytes())
                        self.assertFalse(fixture.journal_path.exists())
                finally:
                    fixture.close()

    def test_recovery_rejects_missing_or_third_pointer_for_active_journal(self):
        self.fixture.pointer_path.unlink()
        bundle = self.fixture.prepare()

        def interrupt(stage):
            if stage == "after-journal-publication":
                raise KeyboardInterrupt()

        with mock.patch.object(repair, "phase_hook", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                repair.apply_bundle(self.fixture.root, bundle)
        self.fixture.pointer_path.write_bytes(b"third")
        self.assert_failure(
            "conflict", "pointer-conflict",
            lambda: repair.recover(self.fixture.root, self.fixture.issue))
        self.assertTrue(self.fixture.journal_path.exists())

    def test_legacy_dual_migrated_and_frozen_authority_are_denied(self):
        legacy = RepairFixture(keep_legacy=True)
        try:
            legacy.pointer_path.write_bytes(b"corrupt")
            self.assert_failure(
                "denied", "legacy-authority-owned", lambda: legacy.prepare())
            legacy.pointer_path.write_bytes(legacy.target_data)
            self.assert_failure(
                "denied", "legacy-authority-owned", lambda: legacy.prepare())
        finally:
            legacy.close()

        migrated_state = self.fixture.authority.make_state(
            cutover={
                "mode": "migrated-v4",
                "legacy_checkpoint_sha256": "f" * 64,
                "migration_binding": self.fixture.binding,
            })
        migrated = self.fixture.authority.publish_state(migrated_state)
        checkpoint = copy.deepcopy(self.fixture.checkpoint)
        checkpoint["pointer"]["authority"] = migrated
        checkpoint["authority"] = migrated
        checkpoint["state_sha256"] = migrated_state["state_sha256"]
        checkpoint["chain"][0] = {
            "generation": 0,
            "binding": migrated,
            "state_sha256": migrated_state["state_sha256"],
        }
        pointer_data = inspector.canonical_bytes(checkpoint["pointer"])
        checkpoint["pointer_sha256"] = inspector.sha256(pointer_data)
        self.rehash_checkpoint(checkpoint)
        self.fixture.write_checkpoint(checkpoint)
        self.fixture.pointer_path.unlink()
        with self.assertRaises(repair.OrchestrationRepairFailure) as migrated_failure:
            self.fixture.prepare()
        self.assertIn("unsupported-cutover-not-activated",
                      migrated_failure.exception.code)

        with mock.patch.object(
                repair, "_load_canonical",
                side_effect=AssertionError("must reject before lookup")):
            self.assert_failure(
                "denied", "issue-frozen",
                lambda: repair.prepare(
                    self.fixture.root, 115,
                    self.fixture.checkpoint_path, self.fixture.request_path))

    def test_fixed_surface_has_no_cas_selection_scan(self):
        tree = ast.parse((SCRIPTS / "workflow_authority_repair.py").read_text())
        calls = {
            node.func.attr
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Attribute)
        }
        self.assertTrue({"glob", "rglob", "walk"}.isdisjoint(calls))
        parser = repair.build_parser()
        subparsers = next(
            action for action in parser._actions
            if isinstance(action, __import__("argparse")._SubParsersAction))
        self.assertEqual(
            {"prepare", "dry-run", "apply", "recover"},
            set(subparsers.choices))

    def test_cli_supports_script_and_package_execution(self):
        for command in (
            [sys.executable, str(SCRIPTS / "workflow_authority_repair.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_authority_repair", "--help"],
        ):
            result = subprocess.run(
                command, cwd=str(REPOSITORY), text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
