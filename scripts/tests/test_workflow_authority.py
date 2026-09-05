import base64
import concurrent.futures
import copy
import hashlib
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_authority as authority


SCRIPTS = pathlib.Path(__file__).parents[1]
REPOSITORY = SCRIPTS.parent
INSPECTOR_TEST = SCRIPTS / "tests" / "test_workflow_inspector.py"
SPEC = importlib.util.spec_from_file_location("authority_inspector_fixture", INSPECTOR_TEST)
FIXTURE_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE_MODULE)
InspectorFixture = FIXTURE_MODULE.InspectorFixture


class AuthorityFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        self.inspector = InspectorFixture(self.root)
        self.issue = self.inspector.issue
        self.family = self.inspector.run_id
        self.triage = self.publish_binding("triage", "work-type-triage")
        self.policy = self.publish_binding("policy", "policy-state")

    def close(self):
        self.temporary.cleanup()

    @property
    def pointer_path(self):
        return (
            self.inspector.store
            / "orchestration"
            / "issues"
            / str(self.issue)
            / "pointer.json"
        )

    def snapshot(self):
        result = {}
        for path in sorted(self.root.rglob("*")):
            relative = path.relative_to(self.root).as_posix()
            if path.is_symlink():
                result[relative] = ("symlink", os.readlink(path))
            elif path.is_file():
                result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
            elif path.is_dir():
                result[relative] = ("directory", path.stat().st_mode)
        return result

    def identity(self, index=0, family=None):
        return {
            "issue": self.issue,
            "run_id": "%032x" % (index + 1),
            "family_run_id": family or self.family,
            "correction": None,
            "run_generation": index,
            "sequence": index + 1,
            "event_tip": "%064x" % (index + 1),
        }

    def publish_binding(
        self,
        name,
        decision_type,
        *,
        decision_id=None,
        payload=None,
        path=None,
        identity=None,
        subject=None,
        lineage=None,
    ):
        payload = ("%s\n" % name).encode("utf-8") if payload is None else payload
        digest = inspector.sha256(payload)
        entry = {
            "path": path or "authority-fixture/%s.txt" % name,
            "kind": "regular",
            "mode": "100644",
            "content_sha256": digest,
            "size": len(payload),
            "payload": {
                "kind": "evidence-payload",
                "sha256": digest,
                "size": len(payload),
            },
        }
        publication = {
            "format": evidence.PUBLICATION_FORMAT,
            "identity": identity or self.identity(),
            "decision": {
                "type": decision_type,
                "id": decision_id or "fixture-%s" % name,
            },
            "subject": subject or self.inspector.refs["run-state"],
            "lineage": lineage
            or {
                "status": "original",
                "parent_binding": None,
            },
            "migration": None,
            "entries": [entry],
            "captures": [
                {
                    "entry_sha256": evidence._entry_digest(entry),
                    "capture_method": "fixture",
                    "captured_at": "2026-09-05T00:00:00Z",
                    "source": {"type": "workspace", "path": entry["path"]},
                    "tool": {"name": "test", "version": "1"},
                }
            ],
            "payloads": [
                {
                    "sha256": digest,
                    "size": len(payload),
                    "bytes_base64": base64.b64encode(payload).decode("ascii"),
                }
            ],
        }
        return evidence.publish(self.root, publication)["binding"]

    def make_state(
        self,
        generation=0,
        previous_authority=None,
        previous_pointer_sha256=None,
        **changes
    ):
        state = {
            "format": authority.STATE_FORMAT,
            "issue": self.issue,
            "family_run_id": self.family,
            "generation": generation,
            "previous_authority": previous_authority,
            "previous_pointer_sha256": previous_pointer_sha256,
            "route": "implementation",
            "phase": "PLANNING",
            "triage_binding": self.triage,
            "policy_state_binding": self.policy,
            "candidates": [],
            "pending": None,
            "cutover": {
                "mode": "new-run",
                "legacy_checkpoint_sha256": None,
                "migration_binding": None,
            },
            "transition": {
                "type": "initialize",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
        }
        state.update(changes)
        state["state_sha256"] = inspector.sha256(inspector.canonical_bytes(state))
        return state

    def publish_state(self, state, **changes):
        state = copy.deepcopy(state)
        state_bytes = inspector.canonical_bytes(state)
        generation = state["generation"]
        identity = {
            "issue": state["issue"],
            "run_id": hashlib.sha256(
                b"orchestration-state-v1\0" + state_bytes
            ).hexdigest()[:32],
            "family_run_id": state["family_run_id"],
            "correction": None,
            "run_generation": generation,
            "sequence": generation + 1,
            "event_tip": hashlib.sha256(
                b"orchestration-tip-v1\0" + state_bytes
            ).hexdigest(),
        }
        publication = {
            "decision_id": "generation-%d" % generation,
            "decision_type": "orchestration-state",
            "identity": identity,
            "lineage": {
                "status": "original" if generation == 0 else "replacement",
                "parent_binding": state["previous_authority"],
            },
            "path": "workflow-orchestration/state.json",
            "subject": (
                state["policy_state_binding"]
                if generation == 0
                else state["previous_authority"]
            ),
        }
        publication.update(changes)
        return self.publish_binding(
            "state-%d-%s" % (generation, state["state_sha256"][:12]),
            publication.pop("decision_type"),
            payload=state_bytes,
            **publication
        )

    def genesis(self, **state_changes):
        state = self.make_state(**state_changes)
        return self.publish_state(state), state

    def next_candidate(self, previous, **state_changes):
        pointer_data = self.pointer_path.read_bytes()
        previous_pointer_sha256 = state_changes.pop(
            "previous_pointer_sha256",
            inspector.sha256(pointer_data),
        )
        state = self.make_state(
            generation=1,
            previous_authority=previous,
            previous_pointer_sha256=previous_pointer_sha256,
            transition={
                "type": "plan-request",
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            },
            **state_changes
        )
        return self.publish_state(state), state

    def install_genesis(self):
        binding, state = self.genesis()
        bundle = authority.prepare(self.root, self.issue, binding)
        authority.commit(self.root, bundle)
        return binding, state, bundle


class WorkflowAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.fixture = AuthorityFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, action):
        with self.assertRaises(authority.AuthorityFailure) as raised:
            action()
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def test_limits_are_the_approved_contract(self):
        self.assertEqual(4 * 1024, authority.POINTER_LIMIT)
        self.assertEqual(2 * 1024 * 1024, authority.STATE_LIMIT)
        self.assertEqual(16 * 1024, authority.BUNDLE_LIMIT)
        self.assertEqual(4 * 1024 * 1024, authority.CHECKPOINT_LIMIT)
        self.assertEqual(10_000, authority.CHAIN_LIMIT)
        self.assertEqual(1_024, authority.STALE_TEMPORARY_LIMIT)

    def test_missing_pointer_is_typed_and_never_an_empty_checkpoint(self):
        for operation in (
            lambda: authority.status(self.fixture.root, self.fixture.issue),
            lambda: authority.checkpoint(self.fixture.root, self.fixture.issue),
        ):
            with self.subTest(operation=operation):
                self.assert_failure(
                    "missing",
                    "orchestration-pointer-missing",
                    operation,
                )

    def test_genesis_prepare_commit_status_and_checkpoint(self):
        binding, state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)

        self.assertEqual(authority.BUNDLE_FORMAT, bundle["format"])
        self.assertIsNone(bundle["source"])
        self.assertEqual(binding, bundle["candidate_binding"])
        operation = {
            "issue": self.fixture.issue,
            "source_sha256": None,
            "target_sha256": bundle["target"]["sha256"],
            "candidate_binding": binding,
        }
        self.assertEqual(
            inspector.sha256(inspector.canonical_bytes(operation)),
            bundle["operation_id"],
        )
        unsigned = dict(bundle)
        unsigned.pop("bundle_sha256")
        self.assertEqual(
            inspector.sha256(inspector.canonical_bytes(unsigned)),
            bundle["bundle_sha256"],
        )

        committed = authority.commit(self.fixture.root, bundle)
        self.assertEqual(
            {"status": "resolved", "code": "committed"},
            committed["outcome"],
        )
        status = authority.status(self.fixture.root, self.fixture.issue)
        self.assertEqual(authority.INSPECTION_FORMAT, status["format"])
        self.assertEqual(binding, status["authority"])
        self.assertEqual(state["state_sha256"], status["state_sha256"])
        self.assertEqual(1, status["chain_length"])
        self.assertEqual([0], [item["generation"] for item in status["chain"]])

        checkpoint = authority.checkpoint(self.fixture.root, self.fixture.issue)
        self.assertEqual(authority.CHECKPOINT_FORMAT, checkpoint["format"])
        unsigned = dict(checkpoint)
        digest = unsigned.pop("checkpoint_sha256")
        self.assertEqual(
            inspector.sha256(inspector.canonical_bytes(unsigned)),
            digest,
        )

    def test_next_generation_chain_is_genesis_to_tip(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, second_state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        authority.commit(self.fixture.root, bundle)

        checkpoint = authority.checkpoint(self.fixture.root, self.fixture.issue)
        self.assertEqual(2, checkpoint["chain_length"])
        self.assertEqual([0, 1], [item["generation"] for item in checkpoint["chain"]])
        self.assertEqual(
            [first, second],
            [item["binding"] for item in checkpoint["chain"]],
        )
        self.assertEqual(second_state["state_sha256"], checkpoint["state_sha256"])

    def test_status_checkpoint_and_prepare_are_read_only(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        before = self.fixture.snapshot()

        authority.status(self.fixture.root, self.fixture.issue)
        authority.checkpoint(self.fixture.root, self.fixture.issue)
        authority.prepare(self.fixture.root, self.fixture.issue, second)

        self.assertEqual(before, self.fixture.snapshot())

    def test_state_and_pointer_limits_accept_exact_and_reject_one_over(self):
        binding, state = self.fixture.genesis()
        state_size = len(inspector.canonical_bytes(state))
        with mock.patch.object(authority, "STATE_LIMIT", state_size):
            authority.prepare(self.fixture.root, self.fixture.issue, binding)
        with mock.patch.object(authority, "STATE_LIMIT", state_size - 1):
            self.assert_failure(
                "unsupported",
                "orchestration-state-too-large",
                lambda: authority.prepare(
                    self.fixture.root,
                    self.fixture.issue,
                    binding,
                ),
            )

        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        pointer_size = bundle["target"]["size"]
        with mock.patch.object(authority, "POINTER_LIMIT", pointer_size):
            authority.validate_bundle(bundle, self.fixture.root)
        with mock.patch.object(authority, "POINTER_LIMIT", pointer_size - 1):
            self.assert_failure(
                "unsupported",
                "orchestration-pointer-too-large",
                lambda: authority.validate_bundle(bundle, self.fixture.root),
            )

    def test_state_size_is_rejected_before_authority_payload_read(self):
        binding, state = self.fixture.genesis()
        with mock.patch.object(
            authority,
            "STATE_LIMIT",
            len(inspector.canonical_bytes(state)) - 1,
        ), mock.patch.object(
            authority.workflow_evidence,
            "verify",
            side_effect=AssertionError("full graph verification"),
        ):
            self.assert_failure(
                "unsupported",
                "orchestration-state-too-large",
                lambda: authority.prepare(
                    self.fixture.root,
                    self.fixture.issue,
                    binding,
                ),
            )

    def test_bundle_and_checkpoint_limits_accept_exact_and_reject_one_over(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        bundle_size = len(inspector.canonical_bytes(bundle))
        with mock.patch.object(authority, "BUNDLE_LIMIT", bundle_size):
            authority.validate_bundle(bundle, self.fixture.root)
        with mock.patch.object(authority, "BUNDLE_LIMIT", bundle_size - 1):
            self.assert_failure(
                "unsupported",
                "authority-bundle-too-large",
                lambda: authority.validate_bundle(bundle, self.fixture.root),
            )

        authority.commit(self.fixture.root, bundle)
        checkpoint = authority.checkpoint(self.fixture.root, self.fixture.issue)
        checkpoint_size = len(inspector.canonical_bytes(checkpoint))
        with mock.patch.object(authority, "CHECKPOINT_LIMIT", checkpoint_size):
            authority.checkpoint(self.fixture.root, self.fixture.issue)
        with mock.patch.object(authority, "CHECKPOINT_LIMIT", checkpoint_size - 1):
            self.assert_failure(
                "unsupported",
                "authority-checkpoint-too-large",
                lambda: authority.checkpoint(
                    self.fixture.root,
                    self.fixture.issue,
                ),
            )

    def test_route_is_not_activated_at_genesis_or_later(self):
        binding, _state = self.fixture.genesis(route="design")
        self.assert_failure(
            "unsupported",
            "unsupported-route-not-activated",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_migrated_cutover_is_not_activated(self):
        binding, _state = self.fixture.genesis(
            cutover={
                "mode": "migrated-v4",
                "legacy_checkpoint_sha256": "a" * 64,
                "migration_binding": self.fixture.triage,
            }
        )
        self.assert_failure(
            "unsupported",
            "unsupported-cutover-not-activated",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

        first, _state, _bundle = self.fixture.install_genesis()
        binding, _state = self.fixture.next_candidate(first, route="research")
        self.assert_failure(
            "unsupported",
            "unsupported-route-not-activated",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_state_binding_identity_subject_lineage_and_policy_are_enforced(self):
        cases = {
            "decision": (
                {"decision_type": "not-orchestration-state"},
                "authority-binding-invalid",
            ),
            "decision-id": (
                {"decision_id": "generation-8"},
                "authority-binding-invalid",
            ),
            "subject": (
                {"subject": self.fixture.triage},
                "authority-lineage-stale",
            ),
            "lineage": (
                {
                    "lineage": {
                        "status": "replacement",
                        "parent_binding": self.fixture.policy,
                    }
                },
                "authority-lineage-stale",
            ),
        }
        state = self.fixture.make_state()
        for name, (changes, code) in cases.items():
            with self.subTest(name=name):
                binding = self.fixture.publish_state(state, **changes)
                self.assert_failure(
                    "stale" if code == "authority-lineage-stale" else "corrupt",
                    code,
                    lambda binding=binding: authority.prepare(
                        self.fixture.root,
                        self.fixture.issue,
                        binding,
                    ),
                )

        wrong_policy = self.fixture.publish_binding(
            "wrong-policy",
            "not-policy-state",
        )
        binding, _state = self.fixture.genesis(policy_state_binding=wrong_policy)
        self.assert_failure(
            "corrupt",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_state_digest_run_id_event_tip_and_family_are_enforced(self):
        state = self.fixture.make_state()
        bad_digest = copy.deepcopy(state)
        bad_digest["state_sha256"] = "f" * 64
        binding = self.fixture.publish_state(bad_digest)
        self.assert_failure(
            "corrupt",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

        bad_run = self.fixture.publish_state(
            state,
            identity={**self.fixture.identity(), "run_id": "f" * 32},
        )
        self.assert_failure(
            "corrupt",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, bad_run),
        )

        bad_tip = self.fixture.publish_state(
            state,
            identity={**self.fixture.identity(), "event_tip": "f" * 64},
        )
        self.assert_failure(
            "corrupt",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, bad_tip),
        )

        other_policy = self.fixture.publish_binding(
            "other-family-policy",
            "policy-state",
            identity=self.fixture.identity(family="f" * 32),
        )
        binding, _state = self.fixture.genesis(policy_state_binding=other_policy)
        self.assert_failure(
            "stale",
            "authority-lineage-stale",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_candidates_are_sorted_unique_and_identity_bound(self):
        candidate = self.fixture.publish_binding("candidate", "plan-snapshot")
        another = self.fixture.publish_binding("another", "plan-review", identity=self.fixture.identity(2))
        for candidates in (
            [
                {"slot": "plan-snapshot", "binding": candidate},
                {"slot": "plan-review", "binding": another},
            ],
            [
                {"slot": "plan-snapshot", "binding": candidate},
                {"slot": "plan-snapshot", "binding": another},
            ],
        ):
            binding, _state = self.fixture.genesis(candidates=candidates)
            self.assert_failure(
                "ambiguous",
                "authority-binding-invalid",
                lambda binding=binding: authority.prepare(
                    self.fixture.root,
                    self.fixture.issue,
                    binding,
                ),
            )

    def test_pending_reference_kind_is_enforced(self):
        first, _state, _bundle = self.fixture.install_genesis()
        challenge = self.fixture.publish_binding(
            "challenge", "human-challenge", subject=first
        )
        request = self.fixture.publish_binding(
            "request", "execution-request", subject=first
        )
        pending = {
            "attempt_id": "a" * 64,
            "kind": "human",
            "request_binding": challenge,
            "status": "requested",
        }
        binding, _state = self.fixture.next_candidate(first, pending=pending)
        authority.prepare(self.fixture.root, self.fixture.issue, binding)

        pending["request_binding"] = request
        binding, _state = self.fixture.next_candidate(first, pending=pending)
        self.assert_failure(
            "stale",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

        pending["kind"] = "agent"
        binding, _state = self.fixture.next_candidate(first, pending=pending)
        authority.prepare(self.fixture.root, self.fixture.issue, binding)

    def test_cached_binding_cannot_hide_a_false_reference_size(self):
        first, _state, _bundle = self.fixture.install_genesis()
        challenge = self.fixture.publish_binding(
            "challenge", "human-challenge", subject=first
        )
        false_reference = {**challenge, "size": challenge["size"] + 1}
        binding, _state = self.fixture.next_candidate(
            first,
            candidates=[{"slot": "human-challenge", "binding": challenge}],
            pending={
                "attempt_id": "a" * 64,
                "kind": "human",
                "request_binding": false_reference,
                "status": "requested",
            },
        )
        self.assert_failure(
            "corrupt",
            "authority-binding-invalid",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_related_binding_generation_subject_and_lineage_are_bound(self):
        first, _state, _bundle = self.fixture.install_genesis()
        cases = (
            self.fixture.publish_binding(
                "future-request",
                "execution-request",
                identity=self.fixture.identity(99),
                subject=first,
            ),
            self.fixture.publish_binding(
                "unrelated-request",
                "execution-request",
                subject=self.fixture.triage,
            ),
            self.fixture.publish_binding(
                "replacement-request",
                "execution-request",
                identity=self.fixture.identity(1),
                subject=first,
                lineage={"status": "replacement", "parent_binding": self.fixture.policy},
            ),
        )
        for request in cases:
            pending = {
                "attempt_id": "a" * 64,
                "kind": "agent",
                "request_binding": request,
                "status": "requested",
            }
            binding, _state = self.fixture.next_candidate(first, pending=pending)
            self.assert_failure(
                "stale",
                "authority-lineage-stale",
                lambda binding=binding: authority.prepare(
                    self.fixture.root, self.fixture.issue, binding
                ),
            )

    def test_enum_values_fail_with_typed_outcomes(self):
        candidate = self.fixture.publish_binding("candidate", "plan-snapshot")
        request = self.fixture.publish_binding("request", "execution-request")
        cases = (
            ({"phase": []}, "unsupported"),
            ({"candidates": [{"slot": {}, "binding": candidate}]}, "unsupported"),
            ({"pending": {
                "attempt_id": "a" * 64,
                "kind": [],
                "request_binding": request,
                "status": "requested",
            }}, "corrupt"),
            ({"transition": {
                "type": {},
                "request_binding": None,
                "result_binding": None,
                "authorization_binding": None,
                "repository_observation_binding": None,
            }}, "unsupported"),
        )
        for changes, status in cases:
            with self.subTest(changes=changes):
                binding, _state = self.fixture.genesis(**changes)
                self.assert_failure(
                    status,
                    "authority-binding-invalid",
                    lambda binding=binding: authority.prepare(
                        self.fixture.root, self.fixture.issue, binding
                    ),
                )

    def test_generation_and_previous_pointer_are_enforced(self):
        first, _state, _bundle = self.fixture.install_genesis()
        wrong_generation = self.fixture.make_state(
            generation=2,
            previous_authority=first,
            previous_pointer_sha256=inspector.sha256(
                self.fixture.pointer_path.read_bytes()
            ),
        )
        binding = self.fixture.publish_state(wrong_generation)
        self.assert_failure(
            "stale",
            "authority-lineage-stale",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

        binding, _state = self.fixture.next_candidate(
            first,
            previous_pointer_sha256="f" * 64,
        )
        self.assert_failure(
            "stale",
            "authority-lineage-stale",
            lambda: authority.prepare(self.fixture.root, self.fixture.issue, binding),
        )

    def test_chain_limit_is_typed_and_never_partial(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        authority.commit(
            self.fixture.root,
            authority.prepare(self.fixture.root, self.fixture.issue, second),
        )
        with mock.patch.object(authority, "CHAIN_LIMIT", 1):
            self.assert_failure(
                "unsupported",
                "authority-chain-limit",
                lambda: authority.status(self.fixture.root, self.fixture.issue),
            )

    def test_real_10001_generation_bound_is_rejected(self):
        pointer = {
            "format": authority.POINTER_FORMAT,
            "issue": self.fixture.issue,
            "generation": authority.CHAIN_LIMIT,
            "authority": self.fixture.policy,
        }

        def state_for(_root, current, _cache):
            generation = current["generation"]
            return {
                "state_sha256": "%064x" % generation,
                "previous_authority": {
                    "kind": "evidence-binding",
                    "sha256": "%064x" % generation,
                    "size": 1,
                },
            }

        with mock.patch.object(
            authority,
            "_state_binding",
            side_effect=state_for,
        ) as verify:
            self.assert_failure(
                "unsupported",
                "authority-chain-limit",
                lambda: authority._chain(self.fixture.root, pointer),
            )
        self.assertEqual(authority.CHAIN_LIMIT, verify.call_count)

    def test_genesis_replay_and_pointer_appeared_race(self):
        first, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, first)
        authority.commit(self.fixture.root, bundle)
        replay = authority.commit(self.fixture.root, bundle)
        self.assertEqual("already-committed", replay["outcome"]["code"])

        other_fixture = AuthorityFixture()
        try:
            candidate, _state = other_fixture.genesis()
            stale_bundle = authority.prepare(
                other_fixture.root,
                other_fixture.issue,
                candidate,
            )
            other, _state = other_fixture.genesis(phase="PLAN_REVIEW")
            other_bundle = authority.prepare(
                other_fixture.root,
                other_fixture.issue,
                other,
            )
            authority.commit(other_fixture.root, other_bundle)
            self.assert_failure(
                "conflict",
                "pointer-target-conflict",
                lambda: authority.commit(other_fixture.root, stale_bundle),
            )
        finally:
            other_fixture.close()

    def test_stale_expected_tip_and_target_replay(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        second_bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        other, _state = self.fixture.next_candidate(first, phase="PLAN_REVIEW")
        other_bundle = authority.prepare(self.fixture.root, self.fixture.issue, other)
        authority.commit(self.fixture.root, other_bundle)

        self.assert_failure(
            "conflict",
            "pointer-source-mismatch",
            lambda: authority.commit(self.fixture.root, second_bundle),
        )
        replay = authority.commit(self.fixture.root, other_bundle)
        self.assertEqual("already-committed", replay["outcome"]["code"])

    def test_concurrent_contenders_select_exactly_one_target(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        third, _state = self.fixture.next_candidate(first, phase="PLAN_REVIEW")
        bundles = [
            authority.prepare(self.fixture.root, self.fixture.issue, second),
            authority.prepare(self.fixture.root, self.fixture.issue, third),
        ]

        def attempt(bundle):
            try:
                return authority.commit(self.fixture.root, bundle)["pointer"]["authority"]
            except authority.AuthorityFailure as failure:
                return failure.code

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(attempt, bundles))

        self.assertEqual(1, sum(item in (second, third) for item in results))
        self.assertEqual(1, results.count("pointer-source-mismatch"))
        self.assertIn(
            authority.status(self.fixture.root, self.fixture.issue)["authority"],
            (second, third),
        )

    def test_pointer_symlink_and_nonregular_values_fail_closed(self):
        self.fixture.pointer_path.parent.mkdir(parents=True)
        target = self.fixture.pointer_path.parent / "target"
        target.write_text("not authority")
        self.fixture.pointer_path.symlink_to(target)
        self.assert_failure(
            "conflict",
            "pointer-not-regular",
            lambda: authority.status(self.fixture.root, self.fixture.issue),
        )

    def test_authority_ancestor_symlink_cannot_redirect_commit(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        orchestration = self.fixture.inspector.store / "orchestration"
        redirected = self.fixture.root / "redirected-authority"
        redirected.mkdir()
        orchestration.symlink_to(redirected, target_is_directory=True)

        self.assert_failure(
            "conflict",
            "authority-directory-conflict",
            lambda: authority.commit(self.fixture.root, bundle),
        )
        self.assertEqual([], list(redirected.rglob("*")))

    def test_stale_temporary_cleanup_is_bounded_and_rejects_nonregulars(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        issue_dir = self.fixture.pointer_path.parent
        issue_dir.mkdir(parents=True)
        stale = issue_dir / ".pointer.json.authority-stale"
        stale.write_bytes(b"stale")
        authority.commit(self.fixture.root, bundle)
        self.assertFalse(stale.exists())

        first = authority.status(
            self.fixture.root,
            self.fixture.issue,
        )["authority"]
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        unsafe = issue_dir / ".pointer.json.authority-unsafe"
        unsafe.mkdir()
        self.assert_failure(
            "conflict",
            "authority-temporary-conflict",
            lambda: authority.commit(self.fixture.root, bundle),
        )

    def test_temporary_limit_fails_before_cleanup(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        issue_dir = self.fixture.pointer_path.parent
        issue_dir.mkdir(parents=True)
        stale = issue_dir / ".pointer.json.authority-stale"
        stale.write_bytes(b"stale")
        with mock.patch.object(authority, "STALE_TEMPORARY_LIMIT", 0):
            self.assert_failure(
                "conflict",
                "authority-temporary-conflict",
                lambda: authority.commit(self.fixture.root, bundle),
            )
        self.assertTrue(stale.exists())

    def test_interruption_before_replace_leaves_source_and_cleans_temporary(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        source = self.fixture.pointer_path.read_bytes()

        def interrupt(stage):
            if stage == "before-pointer-replace":
                raise KeyboardInterrupt()

        with mock.patch.object(authority, "phase_hook", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                authority.commit(self.fixture.root, bundle)

        self.assertEqual(source, self.fixture.pointer_path.read_bytes())
        self.assertEqual(
            [],
            list(self.fixture.pointer_path.parent.glob(".pointer.json.authority-*")),
        )

    def test_interruption_after_temporary_fsync_leaves_source(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        source = self.fixture.pointer_path.read_bytes()

        def interrupt(stage):
            if stage == "after-temporary-fsync":
                raise KeyboardInterrupt()

        with mock.patch.object(authority, "phase_hook", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                authority.commit(self.fixture.root, bundle)

        self.assertEqual(source, self.fixture.pointer_path.read_bytes())
        self.assertEqual(
            [],
            list(self.fixture.pointer_path.parent.glob(".pointer.json.authority-*")),
        )

    def test_interruption_after_replace_restarts_as_exact_target(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)

        def interrupt(stage):
            if stage == "after-pointer-replace":
                raise KeyboardInterrupt()

        with mock.patch.object(authority, "phase_hook", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                authority.commit(self.fixture.root, bundle)

        result = authority.commit(self.fixture.root, bundle)
        self.assertEqual("already-committed", result["outcome"]["code"])
        self.assertEqual(second, result["pointer"]["authority"])

    def test_interruption_after_directory_fsync_restarts_as_exact_target(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)

        def interrupt(stage):
            if stage == "after-directory-fsync":
                raise KeyboardInterrupt()

        with mock.patch.object(authority, "phase_hook", side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                authority.commit(self.fixture.root, bundle)

        result = authority.commit(self.fixture.root, bundle)
        self.assertEqual("already-committed", result["outcome"]["code"])

    def test_candidate_verification_interrupts_before_pointer_mutation(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)

        for stage in (
            "before-candidate-verification",
            "after-candidate-verification",
        ):
            with self.subTest(stage=stage), mock.patch.object(
                authority,
                "phase_hook",
                side_effect=lambda actual, stage=stage: (
                    (_ for _ in ()).throw(KeyboardInterrupt())
                    if actual == stage
                    else None
                ),
            ):
                with self.assertRaises(KeyboardInterrupt):
                    authority.commit(self.fixture.root, bundle)
                self.assertFalse(self.fixture.pointer_path.exists())

    def test_replacement_race_and_unsafe_lock_fail_closed(self):
        first, _state, _bundle = self.fixture.install_genesis()
        second, _state = self.fixture.next_candidate(first)
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, second)
        third = b'{"third":"value"}'

        def race(stage):
            if stage == "before-pointer-replace":
                self.fixture.pointer_path.write_bytes(third)

        with mock.patch.object(authority, "phase_hook", side_effect=race):
            self.assert_failure(
                "conflict",
                "pointer-source-mismatch",
                lambda: authority.commit(self.fixture.root, bundle),
            )
        self.assertEqual(third, self.fixture.pointer_path.read_bytes())

        self.fixture.pointer_path.write_bytes(
            authority._decode_record(bundle["source"], "source")
        )
        lock = self.fixture.pointer_path.parent / "authority.lock"
        lock.unlink()
        target = self.fixture.pointer_path.parent / "lock-target"
        target.write_bytes(b"")
        lock.symlink_to(target)
        self.assert_failure(
            "conflict",
            "authority-lock-conflict",
            lambda: authority.commit(self.fixture.root, bundle),
        )

    def test_lock_errors_are_typed_for_direct_commit_callers(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        with mock.patch.object(authority.fcntl, "flock", side_effect=OSError("lock")):
            self.assert_failure(
                "conflict",
                "authority-lock-conflict",
                lambda: authority.commit(self.fixture.root, bundle),
            )

    def test_bundle_mutation_and_noncanonical_pointer_fail_closed(self):
        binding, _state = self.fixture.genesis()
        bundle = authority.prepare(self.fixture.root, self.fixture.issue, binding)
        mutated = copy.deepcopy(bundle)
        mutated["operation_id"] = "f" * 64
        self.assert_failure(
            "corrupt",
            "authority-bundle-invalid",
            lambda: authority.commit(self.fixture.root, mutated),
        )

        self.fixture.pointer_path.parent.mkdir(parents=True)
        pointer = authority._decode_record(bundle["target"], "target")
        self.fixture.pointer_path.write_bytes(pointer + b"\n")
        self.assert_failure(
            "corrupt",
            "invalid-orchestration-pointer",
            lambda: authority.status(self.fixture.root, self.fixture.issue),
        )


class WorkflowAuthorityCliTest(unittest.TestCase):
    def test_cli_supports_script_and_package_execution(self):
        for command in (
            [sys.executable, str(SCRIPTS / "workflow_authority.py"), "--help"],
            [sys.executable, "-m", "scripts.workflow_authority", "--help"],
        ):
            with self.subTest(command=command):
                completed = subprocess.run(
                    command,
                    cwd=str(REPOSITORY),
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(0, completed.returncode, completed.stderr)

    def test_parser_has_exact_commands(self):
        parser = authority.build_parser()
        subparsers = next(
            action
            for action in parser._actions
            if isinstance(action, authority.argparse._SubParsersAction)
        )
        self.assertEqual(
            {"status", "checkpoint", "prepare", "commit"},
            set(subparsers.choices),
        )

    def test_bundle_file_is_bounded_before_json_parsing(self):
        with tempfile.NamedTemporaryFile() as bundle:
            bundle.write(b"{" + b" " * (authority.BUNDLE_LIMIT + 1))
            bundle.flush()
            with self.assertRaises(authority.AuthorityFailure) as raised:
                authority._load(bundle.name, "authority-bundle", authority.BUNDLE_LIMIT)
        self.assertEqual("authority-bundle-too-large", raised.exception.code)


if __name__ == "__main__":
    unittest.main()
