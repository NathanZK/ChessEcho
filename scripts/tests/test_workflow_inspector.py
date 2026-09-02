import contextlib
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock


MODULE_PATH = pathlib.Path(__file__).parents[1] / "workflow_inspector.py"
SPEC = importlib.util.spec_from_file_location("workflow_inspector", MODULE_PATH)
inspector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inspector)


class InspectorFixture:
    def __init__(self, root):
        self.root = root
        self.issue = 42
        self.run_id = "0123456789abcdef0123456789abcdef"
        self._git("init", "-q")
        self._git("config", "user.email", "inspector@example.test")
        self._git("config", "user.name", "Inspector Test")
        (root / "tracked.txt").write_text("tracked\n")
        self._git("add", "tracked.txt")
        self._git("commit", "-qm", "fixture")
        self.head = self._git("rev-parse", "HEAD").stdout.strip()
        self._git("update-ref", "refs/remotes/origin/main", self.head)
        common = pathlib.Path(
            self._git(
                "rev-parse", "--path-format=absolute", "--git-common-dir"
            ).stdout.strip()
        )
        self.store = common / inspector.STORE_NAME
        self.pointer_path = (
            self.store / "issues" / str(self.issue) / "index-integrity.json"
        )
        self.refs = {}
        self._build()

    def _git(self, *arguments):
        return subprocess.run(
            ["git", *arguments],
            cwd=str(self.root),
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def install(self, kind, value, raw=False):
        data = value if raw else inspector.canonical_bytes(value)
        digest = hashlib.sha256(data).hexdigest()
        path = self.store / "objects" / "sha256" / digest[:2] / digest[2:]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        reference = {"kind": kind, "sha256": digest, "size": len(data)}
        self.refs[kind] = reference
        return reference

    def _build(self):
        issue_snapshot = self.install("issue-snapshot", b"Issue 42\n", raw=True)
        event_value = {
            "actor": "fixture",
            "at": "2026-01-01T00:00:00+00:00",
            "details": {"scope": "workflow-tooling"},
            "event": "WORKFLOW_INITIALIZED",
            "kind": "run-event",
            "previous_event": None,
            "run_id": self.run_id,
            "sequence": 1,
            "state": "PLANNING",
            "timestamp": "2026-01-01T00:00:00+00:00",
        }
        event_value["sha256"] = inspector.sha256(
            inspector.canonical_bytes(event_value)
        )
        event = self.install("run-event", event_value)
        history_event = {
            key: value for key, value in event_value.items()
            if key not in {"kind", "previous_event", "run_id", "sha256"}
        }
        history = self.install("run-history", {
            "kind": "run-history",
            "run_id": self.run_id,
            "generation": 0,
            "events": [history_event],
        })
        state = self.install("run-state", {
            "kind": "run-state",
            "run_id": self.run_id,
            "family_run_id": self.run_id,
            "generation": 0,
            "event_sequence": 1,
            "event_tip": event_value["sha256"],
            "state": "PLANNING",
            "issue": {"number": self.issue},
            "issue_snapshot": {"name": "issue.md", "object": issue_snapshot},
            "evidence_baseline": {
                "kind": "target-base",
                "ref": "origin/main",
                "sha": self.head,
            },
            "validation_attempts": [],
        })
        envelope = self.install("run-envelope", {
            "kind": "run-envelope",
            "issue": self.issue,
            "run_id": self.run_id,
            "generation": 0,
            "sequence": 1,
            "event_tip": event_value["sha256"],
            "state": state,
            "history": history,
            "event": event,
            "previous_envelope": None,
            "previous_history": None,
        })
        binding = {
            "run_id": self.run_id,
            "family_run_id": self.run_id,
            "number": None,
            "generation": 0,
            "sequence": 1,
            "event_tip": event_value["sha256"],
            "state": state,
            "history": history,
            "event": event,
            "envelope": envelope,
            "status": "current",
            "supersedes": None,
        }
        row = {
            "run_id": self.run_id,
            "family_run_id": self.run_id,
            "number": None,
            "status": "current",
            "supersedes": None,
        }
        index = self.install("issue-index", {
            "kind": "issue-index",
            "issue": self.issue,
            "generation": 0,
            "previous_index": None,
            "current_normal_run_id": self.run_id,
            "attempts": [row],
            "corrections": [],
            "supersession_edges": {},
            "compatibility_records": {},
            "reuse_manifests": {},
            "migration_checked": False,
            "run_update": {
                key: value for key, value in binding.items()
                if key not in {"state", "history", "event"}
            },
        })
        pointer = {
            "format": inspector.POINTER_FORMAT,
            "issue": self.issue,
            "generation": 0,
            "index": index,
            "selection": {
                "current_normal_run_id": self.run_id,
                "run": binding,
            },
        }
        self.pointer_path.parent.mkdir(parents=True, exist_ok=True)
        self.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

    def pointer(self):
        return json.loads(self.pointer_path.read_text())

    def object_path(self, reference):
        digest = reference["sha256"]
        return self.store / "objects" / "sha256" / digest[:2] / digest[2:]

    def rewrite_index(self, mutate):
        pointer = self.pointer()
        index = json.loads(self.object_path(pointer["index"]).read_text())
        mutate(index)
        reference = self.install("issue-index", index)
        pointer["index"] = reference
        self.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

    def rewrite_state(self, mutate):
        pointer = self.pointer()
        envelope = json.loads(
            self.object_path(pointer["selection"]["run"]["envelope"]).read_text()
        )
        state = json.loads(self.object_path(envelope["state"]).read_text())
        mutate(state)
        state_reference = self.install("run-state", state)
        envelope["state"] = state_reference
        envelope_reference = self.install("run-envelope", envelope)
        pointer["selection"]["run"]["state"] = state_reference
        pointer["selection"]["run"]["envelope"] = envelope_reference
        index = json.loads(self.object_path(pointer["index"]).read_text())
        index["run_update"]["envelope"] = envelope_reference
        pointer["index"] = self.install("issue-index", index)
        self.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

    def add_correction(self, number=1):
        pointer = self.pointer()
        normal_binding = pointer["selection"]["run"]
        correction_run_id = "fedcba9876543210fedcba9876543210"
        envelope = json.loads(
            self.object_path(normal_binding["envelope"]).read_text()
        )
        event = json.loads(self.object_path(envelope["event"]).read_text())
        event.pop("sha256")
        event["run_id"] = correction_run_id
        event["sha256"] = inspector.sha256(inspector.canonical_bytes(event))
        event_reference = self.install("run-event", event)
        history = json.loads(self.object_path(envelope["history"]).read_text())
        history["run_id"] = correction_run_id
        history_reference = self.install("run-history", history)
        state = json.loads(self.object_path(envelope["state"]).read_text())
        state.update(
            {
                "correction": {"number": number},
                "event_tip": event["sha256"],
                "family_run_id": normal_binding["run_id"],
                "run_id": correction_run_id,
            }
        )
        state_reference = self.install("run-state", state)
        envelope.update(
            {
                "event": event_reference,
                "event_tip": event["sha256"],
                "history": history_reference,
                "run_id": correction_run_id,
                "state": state_reference,
            }
        )
        envelope_reference = self.install("run-envelope", envelope)
        binding = {
            **normal_binding,
            "envelope": envelope_reference,
            "event_tip": event["sha256"],
            "family_run_id": normal_binding["run_id"],
            "number": number,
            "run_id": correction_run_id,
            "supersedes": normal_binding["run_id"],
        }
        index = json.loads(self.object_path(pointer["index"]).read_text())
        index["corrections"].append(
            {
                key: binding[key]
                for key in (
                    "run_id",
                    "family_run_id",
                    "number",
                    "status",
                    "supersedes",
                )
            }
        )
        index["run_update"] = {
            key: value for key, value in binding.items()
            if key not in {"state", "history", "event"}
        }
        pointer["index"] = self.install("issue-index", index)
        self.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

    def rewrite_correction_state(self, mutate):
        pointer = self.pointer()
        index = json.loads(self.object_path(pointer["index"]).read_text())
        binding = index["run_update"]
        envelope = json.loads(self.object_path(binding["envelope"]).read_text())
        state = json.loads(self.object_path(envelope["state"]).read_text())
        mutate(state)
        envelope["state"] = self.install("run-state", state)
        binding["envelope"] = self.install("run-envelope", envelope)
        index["run_update"] = binding
        pointer["index"] = self.install("issue-index", index)
        self.pointer_path.write_bytes(inspector.canonical_bytes(pointer))


class WorkflowInspectorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.fixture = InspectorFixture(self.root)

    def tearDown(self):
        self.temporary.cleanup()

    def test_resolves_v4_authority_and_minimal_repository_facts(self):
        result = inspector.inspect(self.root, self.fixture.issue)

        authority = result["authority"]
        self.assertEqual(self.fixture.run_id, authority["run_id"])
        self.assertEqual(0, authority["run_generation"])
        self.assertEqual(1, authority["sequence"])
        self.assertEqual("PLANNING", authority["state_name"])
        self.assertEqual("not-recorded", result["observations"]["latest_validation"]["status"])
        self.assertEqual(self.fixture.head, result["repository"]["head"]["commit"])
        self.assertEqual(
            self.fixture.head,
            result["repository"]["base"]["recorded_commit"],
        )
        self.assertTrue(result["repository"]["base"]["matches_recorded"])
        self.assertEqual(
            {
                "clean": True,
                "status_sha256": hashlib.sha256(b"").hexdigest(),
            },
            result["repository"]["worktree"],
        )

    def test_repository_baseline_requires_a_40_character_git_sha(self):
        state = {
            "evidence_baseline": {
                "kind": "target-base",
                "ref": "origin/main",
                "sha": self.fixture.head,
            }
        }

        repository = inspector.inspect_repository(self.root, state)
        self.assertEqual(
            self.fixture.head,
            repository["base"]["recorded_commit"],
        )

        for invalid_sha in ("not-a-git-sha", "a" * 64):
            with self.subTest(invalid_sha=invalid_sha):
                state["evidence_baseline"]["sha"] = invalid_sha
                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect_repository(self.root, state)
                self.assertEqual("unsupported", raised.exception.status)
                self.assertEqual(
                    "unsupported-repository-baseline",
                    raised.exception.code,
                )

    def test_checkpoint_schema_is_canonical_self_describing_and_deterministic(self):
        subject = inspector.inspect(self.root, self.fixture.issue)
        first = inspector.canonical_document(inspector.checkpoint_document(subject))
        second = inspector.canonical_document(
            inspector.checkpoint_document(
                inspector.inspect(self.root, self.fixture.issue)
            )
        )

        self.assertEqual(first, second)
        checkpoint = json.loads(first)
        self.assertEqual(
            {
                "authority",
                "canonicalization",
                "checkpoint_sha256",
                "format",
                "inspector",
                "observations",
                "repository",
            },
            set(checkpoint),
        )
        digest = checkpoint.pop("checkpoint_sha256")
        self.assertEqual(
            hashlib.sha256(inspector.canonical_bytes(checkpoint)).hexdigest(),
            digest,
        )
        self.assertEqual(inspector.CHECKPOINT_FORMAT, checkpoint["format"])
        self.assertEqual(inspector.CANONICALIZATION, checkpoint["canonicalization"])
        self.assertEqual(
            hashlib.sha256(MODULE_PATH.read_bytes()).hexdigest(),
            checkpoint["inspector"]["source_sha256"],
        )
        text = first.decode()
        self.assertNotIn(str(self.root), text)
        self.assertNotIn("payload_base64", text)
        self.assertNotIn("created_at", text)
        self.assertNotIn("generated_at", text)

    def test_tampered_object_is_corrupt(self):
        pointer = self.fixture.pointer()
        path = self.fixture.object_path(pointer["selection"]["run"]["envelope"])
        path.write_bytes(path.read_bytes() + b"x")

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("object-hash-or-size-mismatch", raised.exception.code)

    def test_missing_object_is_typed_missing(self):
        pointer = self.fixture.pointer()
        self.fixture.object_path(pointer["selection"]["run"]["envelope"]).unlink()

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("missing", raised.exception.status)
        self.assertEqual("object-missing", raised.exception.code)

    def test_unsupported_pointer_format_is_typed_unsupported(self):
        pointer = self.fixture.pointer()
        pointer["format"] = "future-pointer-v9"
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("unsupported", raised.exception.status)
        self.assertEqual("unsupported-pointer-format", raised.exception.code)

    def test_duplicate_run_mapping_is_typed_ambiguous(self):
        self.fixture.rewrite_index(
            lambda index: index["attempts"].append(dict(index["attempts"][0]))
        )

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("ambiguous", raised.exception.status)
        self.assertEqual("duplicate-run-id", raised.exception.code)

    def test_pointer_selection_must_match_index(self):
        pointer = self.fixture.pointer()
        pointer["selection"]["current_normal_run_id"] = "f" * 32
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("pointer-selection-mismatch", raised.exception.code)

    def test_pointer_run_identity_must_match_current_run(self):
        pointer = self.fixture.pointer()
        pointer["selection"]["run"]["run_id"] = "f" * 32
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("pointer-binding-mismatch", raised.exception.code)

    def test_pointer_summary_must_match_index_row(self):
        pointer = self.fixture.pointer()
        pointer["selection"]["run"]["family_run_id"] = "f" * 32
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("pointer-binding-mismatch", raised.exception.code)

    def test_pointer_object_binding_must_match_envelope(self):
        pointer = self.fixture.pointer()
        pointer["selection"]["run"]["state"] = pointer["selection"]["run"]["history"]
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("pointer-binding-mismatch", raised.exception.code)

    def test_exact_integer_correction_identity_is_accepted(self):
        self.fixture.add_correction(1)

        result = inspector.inspect(self.root, self.fixture.issue, correction=1)

        self.assertEqual(1, result["authority"]["correction"])

    def test_boolean_index_correction_identities_are_rejected(self):
        for number in (True, False):
            with self.subTest(number=number):
                temporary = tempfile.TemporaryDirectory()
                self.addCleanup(temporary.cleanup)
                fixture = InspectorFixture(pathlib.Path(temporary.name))
                fixture.add_correction(1)
                fixture.rewrite_index(
                    lambda index: index["corrections"][0].update({"number": number})
                )

                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect(fixture.root, fixture.issue, correction=1)

                self.assertEqual("corrupt", raised.exception.status)
                self.assertEqual("invalid-correction-row", raised.exception.code)

    def test_boolean_binding_correction_identities_are_rejected(self):
        for number in (True, False):
            with self.subTest(number=number):
                temporary = tempfile.TemporaryDirectory()
                self.addCleanup(temporary.cleanup)
                fixture = InspectorFixture(pathlib.Path(temporary.name))
                fixture.add_correction(1)
                fixture.rewrite_index(
                    lambda index: index["run_update"].update({"number": number})
                )

                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect(fixture.root, fixture.issue, correction=1)

                self.assertEqual("corrupt", raised.exception.status)
                self.assertEqual("invalid-run-binding", raised.exception.code)

    def test_boolean_state_correction_identities_are_rejected(self):
        for number in (True, False):
            with self.subTest(number=number):
                temporary = tempfile.TemporaryDirectory()
                self.addCleanup(temporary.cleanup)
                fixture = InspectorFixture(pathlib.Path(temporary.name))
                fixture.add_correction(1)
                fixture.rewrite_correction_state(
                    lambda state: state["correction"].update({"number": number})
                )

                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect(fixture.root, fixture.issue, correction=1)

                self.assertEqual("corrupt", raised.exception.status)
                self.assertEqual("state-identity-mismatch", raised.exception.code)

    def test_boolean_correction_selector_is_rejected(self):
        self.fixture.add_correction(1)

        for correction in (True, False):
            with self.subTest(correction=correction):
                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect(
                        self.root,
                        self.fixture.issue,
                        correction=correction,
                    )

                self.assertEqual("unsupported", raised.exception.status)
                self.assertEqual("invalid-correction", raised.exception.code)

    def test_state_must_match_final_history_lifecycle(self):
        self.fixture.rewrite_state(
            lambda state: state.update({"state": "IMPLEMENTATION"})
        )

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("state-history-mismatch", raised.exception.code)

    def test_out_of_range_tip_event_is_typed_corrupt(self):
        event = {
            "actor": "fixture",
            "at": "2026-01-01T00:00:00+00:00",
            "details": {},
            "event": "WORKFLOW_INITIALIZED",
            "kind": "run-event",
            "previous_event": None,
            "run_id": self.fixture.run_id,
            "sequence": 2,
            "state": "PLANNING",
            "timestamp": "2026-01-01T00:00:00+00:00",
        }
        event["sha256"] = inspector.sha256(inspector.canonical_bytes(event))
        reference = {
            "kind": "run-event",
            "sha256": hashlib.sha256(inspector.canonical_bytes(event)).hexdigest(),
            "size": len(inspector.canonical_bytes(event)),
        }

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.verify_event_chain(
                mock.Mock(),
                reference,
                event,
                [{"sequence": 1}],
                {
                    "run_id": self.fixture.run_id,
                    "sequence": 1,
                    "event_tip": event["sha256"],
                },
            )

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("event-binding-mismatch", raised.exception.code)

    def test_truncated_event_chain_is_typed_corrupt(self):
        event = {
            "actor": "fixture",
            "at": "2026-01-01T00:00:00+00:00",
            "details": {},
            "event": "SECOND",
            "kind": "run-event",
            "previous_event": None,
            "run_id": self.fixture.run_id,
            "sequence": 2,
            "state": "PLANNING",
            "timestamp": "2026-01-01T00:00:00+00:00",
        }
        event["sha256"] = inspector.sha256(inspector.canonical_bytes(event))
        reference = {
            "kind": "run-event",
            "sha256": hashlib.sha256(inspector.canonical_bytes(event)).hexdigest(),
            "size": len(inspector.canonical_bytes(event)),
        }
        projected = {
            key: value for key, value in event.items()
            if key not in {"kind", "previous_event", "run_id", "sha256"}
        }

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.verify_event_chain(
                mock.Mock(),
                reference,
                event,
                [{"sequence": 1}, projected],
                {
                    "run_id": self.fixture.run_id,
                    "sequence": 2,
                    "event_tip": event["sha256"],
                },
            )

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("event-chain-truncated", raised.exception.code)

    def test_projection_without_pointer_is_explicitly_unsupported(self):
        self.fixture.pointer_path.unlink()
        projection = (
            self.root / ".agent-workflow" / "runs"
            / ("issue-%s" % self.fixture.issue)
        )
        projection.mkdir(parents=True)

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("unsupported", raised.exception.status)
        self.assertEqual("projection-only-run", raised.exception.code)

    def test_inspection_uses_no_python_filesystem_mutation_api(self):
        forbidden = AssertionError("inspector attempted filesystem mutation")
        with mock.patch.object(
            pathlib.Path, "write_bytes", side_effect=forbidden
        ), mock.patch.object(
            pathlib.Path, "write_text", side_effect=forbidden
        ), mock.patch.object(
            pathlib.Path, "mkdir", side_effect=forbidden
        ), mock.patch.object(
            pathlib.Path, "unlink", side_effect=forbidden
        ), mock.patch.object(
            os, "replace", side_effect=forbidden
        ), mock.patch.object(
            os, "remove", side_effect=forbidden
        ):
            result = inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual(self.fixture.run_id, result["authority"]["run_id"])

    def test_inspection_changes_no_repository_or_git_bytes(self):
        before = self.snapshot_files(self.root)
        before_git = self.snapshot_files(self.root / ".git")

        inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual(before, self.snapshot_files(self.root))
        self.assertEqual(before_git, self.snapshot_files(self.root / ".git"))

    def test_git_runner_rejects_non_allowlisted_operation(self):
        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector._git(self.root, "fetch")
        self.assertEqual("unsupported-git-operation", raised.exception.code)

    def test_git_runner_disables_locks_and_lazy_fetch(self):
        completed = subprocess.CompletedProcess(
            ["git"], 0, b"0" * 40 + b"\n", b""
        )
        with mock.patch.object(
            inspector.subprocess, "run", return_value=completed
        ) as run:
            inspector._git(self.root, "head")

        environment = run.call_args.kwargs["env"]
        self.assertEqual("0", environment["GIT_OPTIONAL_LOCKS"])
        self.assertEqual("1", environment["GIT_NO_LAZY_FETCH"])
        self.assertEqual(
            ["git", "-c", "core.fsmonitor=false"],
            run.call_args.args[0][:3],
        )

    def test_git_runner_cannot_be_redirected_by_environment(self):
        with tempfile.TemporaryDirectory() as other_directory:
            other = InspectorFixture(pathlib.Path(other_directory))
            (other.root / "tracked.txt").write_text("other repository\n")
            other._git("commit", "-qam", "other repository")
            other_head = other._git("rev-parse", "HEAD").stdout.strip()
            self.assertNotEqual(self.fixture.head, other_head)

            with mock.patch.dict(os.environ, self.git_redirect_environment(other)):
                self.assertEqual(
                    self.fixture.head,
                    inspector._git(self.root, "head"),
                )

    def test_public_checkpoint_cannot_be_redirected_by_git_environment(self):
        with tempfile.TemporaryDirectory() as other_directory:
            other = InspectorFixture(pathlib.Path(other_directory))
            (other.root / "tracked.txt").write_text("other repository\n")
            other._git("commit", "-qam", "other repository")
            other_head = other._git("rev-parse", "HEAD").stdout.strip()
            stream = io.BytesIO()

            class BinaryStdout:
                buffer = stream

            with mock.patch.dict(
                os.environ,
                self.git_redirect_environment(other),
            ), mock.patch.object(inspector.sys, "stdout", BinaryStdout()):
                result = inspector.main([
                    "checkpoint",
                    str(self.fixture.issue),
                    "--root",
                    str(self.root),
                ])

        self.assertEqual(0, result)
        checkpoint = json.loads(stream.getvalue())
        self.assertEqual(
            self.fixture.head,
            checkpoint["repository"]["head"]["commit"],
        )
        self.assertNotEqual(
            other_head,
            checkpoint["repository"]["head"]["commit"],
        )

    def test_typed_evidence_requires_declared_encoding(self):
        reference = {
            "kind": "plan",
            "sha256": "a" * 64,
            "size": 1,
        }
        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.validate_reference(reference)
        self.assertEqual("missing-typed-payload-encoding", raised.exception.code)

    def test_incomplete_evidence_references_fail_closed(self):
        reference = self.fixture.refs["issue-snapshot"]
        for missing_field in ("kind", "sha256", "size"):
            with self.subTest(missing_field=missing_field):
                malformed = {
                    key: value for key, value in reference.items()
                    if key != missing_field
                }
                self.fixture.rewrite_state(
                    lambda state: state.__setitem__("malformed_reference", malformed)
                )

                with self.assertRaises(inspector.InspectionFailure) as raised:
                    inspector.inspect(self.root, self.fixture.issue)

                self.assertEqual("corrupt", raised.exception.status)
                self.assertEqual(
                    "invalid-object-reference-shape",
                    raised.exception.code,
                )

    def test_incomplete_nested_evidence_reference_fails_closed(self):
        reference = dict(self.fixture.refs["issue-snapshot"])
        del reference["size"]
        self.fixture.rewrite_state(
            lambda state: state.__setitem__(
                "nested_evidence",
                {"level": [{"object": reference}]},
            )
        )

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("invalid-object-reference-shape", raised.exception.code)

    def test_ordinary_non_reference_objects_remain_valid(self):
        self.fixture.rewrite_state(
            lambda state: state.__setitem__(
                "ordinary_metadata",
                {
                    "kind": "description",
                    "nested": [{"sha256": "not-an-object-reference"}],
                    "value": "ordinary",
                },
            )
        )

        result = inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual(self.fixture.run_id, result["authority"]["run_id"])

    def test_json_booleans_are_not_valid_integer_identities(self):
        pointer = self.fixture.pointer()
        pointer["generation"] = False
        self.fixture.pointer_path.write_bytes(inspector.canonical_bytes(pointer))

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.inspect(self.root, self.fixture.issue)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("invalid-pointer-schema", raised.exception.code)

    def test_deep_json_is_typed_corrupt(self):
        value = {}
        for _number in range(1200):
            value = {"nested": value}

        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.canonical_bytes(value)

        self.assertEqual("corrupt", raised.exception.status)
        self.assertEqual("json-too-deep", raised.exception.code)

    def test_inspector_identity_rejects_source_replacement(self):
        with mock.patch.object(
            inspector, "LOADED_SOURCE_SHA256", "0" * 64
        ):
            with self.assertRaises(inspector.InspectionFailure) as raised:
                inspector.inspector_identity()

        self.assertEqual("inspector-source-changed", raised.exception.code)

    def test_validation_checks_require_a_list(self):
        with self.assertRaises(inspector.InspectionFailure) as raised:
            inspector.latest_validation_observation({
                "validation_attempts": [{"status": "FAIL", "checks": None}]
            })
        self.assertEqual("invalid-validation-checks", raised.exception.code)

    def test_repository_ref_movement_fails_closed(self):
        calls = {"head": 0}

        def changing_git(_root, operation, *values):
            if operation == "head":
                calls["head"] += 1
                return ("a" if calls["head"] == 1 else "b") * 40
            if operation in {"head-tree", "base", "base-tree", "merge-base"}:
                return "c" * 40
            if operation == "ancestor":
                return True
            if operation == "commit-count":
                return "1"
            if operation == "status":
                return b""
            raise AssertionError(operation)

        state = {
            "evidence_baseline": {
                "kind": "target-base",
                "ref": "origin/main",
                "sha": "c" * 40,
            }
        }
        with mock.patch.object(inspector, "_git", side_effect=changing_git):
            with self.assertRaises(inspector.InspectionFailure) as raised:
                inspector.inspect_repository(self.root, state)

        self.assertEqual("repository-changed-during-read", raised.exception.code)

    def test_cli_emits_typed_json_failure(self):
        self.fixture.pointer_path.unlink()
        stream = io.BytesIO()

        class BinaryStdout:
            buffer = stream

        with mock.patch.object(inspector.sys, "stdout", BinaryStdout()):
            result = inspector.main([
                "inspect", str(self.fixture.issue), "--root", str(self.root)
            ])

        self.assertEqual(inspector.OUTCOME_EXIT_CODES["missing"], result)
        output = json.loads(stream.getvalue())
        self.assertEqual("missing", output["outcome"]["status"])

    def test_cli_parse_failure_is_typed_json(self):
        stream = io.BytesIO()

        class BinaryStdout:
            buffer = stream

        with mock.patch.object(inspector.sys, "stdout", BinaryStdout()):
            result = inspector.main(["inspect", "not-an-issue"])

        self.assertEqual(inspector.OUTCOME_EXIT_CODES["unsupported"], result)
        output = json.loads(stream.getvalue())
        self.assertEqual("unsupported", output["outcome"]["status"])
        self.assertEqual("invalid-cli", output["outcome"]["code"])

    @staticmethod
    def snapshot_files(root):
        snapshot = {}
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                snapshot[relative] = ("symlink", os.readlink(path))
            elif path.is_file():
                snapshot[relative] = (
                    "file",
                    path.stat().st_mode,
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                )
            elif path.is_dir():
                snapshot[relative] = ("directory", path.stat().st_mode)
        return snapshot

    @staticmethod
    def git_redirect_environment(other):
        git_dir = other.root / ".git"
        environment = {
            key: "poisoned" for key in inspector.GIT_REDIRECT_ENVIRONMENT
        }
        environment.update({
            "GIT_ALTERNATE_OBJECT_DIRECTORIES": str(git_dir / "objects"),
            "GIT_CEILING_DIRECTORIES": str(other.root),
            "GIT_COMMON_DIR": str(git_dir),
            "GIT_CONFIG_COUNT": "1",
            "GIT_CONFIG_KEY_0": "core.worktree",
            "GIT_CONFIG_VALUE_0": str(other.root),
            "GIT_DIR": str(git_dir),
            "GIT_DISCOVERY_ACROSS_FILESYSTEM": "1",
            "GIT_GRAFT_FILE": str(git_dir / "info" / "grafts"),
            "GIT_INDEX_FILE": str(git_dir / "index"),
            "GIT_NAMESPACE": "poisoned",
            "GIT_OBJECT_DIRECTORY": str(git_dir / "objects"),
            "GIT_REPLACE_REF_BASE": "refs/replace-poisoned/",
            "GIT_SHALLOW_FILE": str(git_dir / "shallow"),
            "GIT_WORK_TREE": str(other.root),
        })
        return environment


class FrozenIssue115IntegrationTest(unittest.TestCase):
    def test_frozen_issue_115_resolves_without_mutation_when_available(self):
        repository = pathlib.Path(__file__).parents[2]
        try:
            store = inspector.resolve_store(repository)
        except inspector.InspectionFailure:
            self.skipTest("Git common-dir store is unavailable")
        pointer = store.store_dir / "issues" / "115" / "index-integrity.json"
        if not pointer.is_file():
            self.skipTest("Frozen #115 authority is not installed")
        before_pointer = pointer.read_bytes()
        before_store = WorkflowInspectorTest.snapshot_files(store.store_dir)

        result = inspector.inspect(repository, 115)

        authority = result["authority"]
        self.assertEqual("3456dce810235e3e995ea867b03c37b8", authority["run_id"])
        self.assertEqual(54, authority["run_generation"])
        self.assertEqual(93, authority["sequence"])
        self.assertEqual("IMPLEMENTATION", authority["state_name"])
        self.assertEqual("FAIL", result["observations"]["latest_validation"]["status"])
        self.assertEqual(before_pointer, pointer.read_bytes())
        self.assertEqual(
            before_store,
            WorkflowInspectorTest.snapshot_files(store.store_dir),
        )


if __name__ == "__main__":
    unittest.main()
