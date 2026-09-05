import copy
import itertools
import unittest

from scripts import workflow_inspector as inspector
from scripts import workflow_supervision_policy as supervision


ISSUE = 165
FAMILY = "1" * 32


def reference(character):
    return {"kind": "evidence-binding", "sha256": character * 64, "size": 1}


def configuration(modes=None):
    modes = modes or {}
    return {
        "format": supervision.CONFIG_FORMAT,
        "gates": [
            {"gate": gate, "mode": modes.get(gate, "supervised")}
            for gate in supervision.GATES
        ],
    }


def authorization(challenge_binding, challenge):
    document = {
        "format": supervision.AUTHORIZATION_FORMAT,
        "challenge_binding": challenge_binding,
        "decision": "approve",
        "actor": {"provider": "github", "account_id": 42, "login": "owner"},
        "source": {
            "repository": "NathanZK/ChessEcho",
            "kind": "issue-comment",
            "id": 1,
        },
        "confirmation": challenge["confirmation"],
    }
    document["authorization_sha256"] = inspector.sha256(
        inspector.canonical_bytes(document)
    )
    return document


class SupervisionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.baseline = reference("a")
        self.policy_binding = reference("b")
        self.authority = reference("c")
        self.subject = reference("d")
        self.repository = reference("e")
        self.policy = supervision.initialize(
            ISSUE, FAMILY, self.baseline, configuration()
        )

    def assert_failure(self, code, action):
        with self.assertRaises(supervision.SupervisionPolicyFailure) as raised:
            action()
        self.assertEqual(code, raised.exception.code)

    def challenge(self, gate="plan", policy=None, policy_binding=None, authority=None):
        return supervision.build_gate_challenge(
            policy_binding or self.policy_binding,
            policy or self.policy,
            authority or self.authority,
            gate,
            [{"slot": "subject", "binding": self.subject}],
            self.repository,
        )

    def test_all_four_gate_mode_permutations_are_independent(self):
        for values in itertools.product(("automatic", "supervised"), repeat=4):
            with self.subTest(values=values):
                modes = dict(zip(supervision.GATES, values))
                document = supervision.initialize(
                    ISSUE, FAMILY, self.baseline, configuration(modes)
                )
                self.assertEqual(
                    modes,
                    {row["gate"]: row["mode"] for row in document["gates"]},
                )
                supervision.validate_policy(document, ISSUE, FAMILY)

    def test_unknown_missing_duplicate_unsorted_and_mandatory_values_fail_closed(self):
        cases = []
        unknown_gate = configuration()
        unknown_gate["gates"][0]["gate"] = "reviewer"
        cases.append(("unknown-supervision-gate", unknown_gate))
        unknown_mode = configuration()
        unknown_mode["gates"][0]["mode"] = "agent"
        cases.append(("unknown-supervision-mode", unknown_mode))
        missing = configuration()
        missing["gates"].pop()
        cases.append(("noncanonical-supervision-gates", missing))
        duplicate = configuration()
        duplicate["gates"][-1]["gate"] = "plan"
        cases.append(("noncanonical-supervision-gates", duplicate))
        unsorted = configuration()
        unsorted["gates"].reverse()
        cases.append(("noncanonical-supervision-gates", unsorted))
        mandatory = configuration()
        mandatory["gates"][0]["gate"] = "recovery"
        cases.append(("mandatory-human-gate-not-configurable", mandatory))
        extra = configuration()
        extra["unexpected"] = True
        cases.append(("invalid-supervision-config-schema", extra))
        extra_row = configuration()
        extra_row["gates"][0]["unexpected"] = True
        cases.append(("invalid-supervision-gate-schema", extra_row))
        nonstring = configuration()
        nonstring["gates"][0]["mode"] = 1
        cases.append(("invalid-supervision-gate", nonstring))
        for code, value in cases:
            with self.subTest(code=code):
                self.assert_failure(
                    code, lambda value=value: supervision.validate_configuration(value)
                )

    def test_mandatory_human_operations_are_outside_configurable_gates(self):
        self.assertEqual(
            {
                "irreversible-authority-transfer",
                "irreversible-recovery-repair",
                "legacy-replacement-authority-cutover",
                "production-activation",
                "production-credential-provider-authorization",
                "recovery",
            },
            set(supervision.MANDATORY_HUMAN_GATES),
        )
        self.assertTrue(set(supervision.GATES).isdisjoint(supervision.MANDATORY_HUMAN_GATES))

    def test_challenge_binds_policy_authority_subjects_and_mode(self):
        challenge = self.challenge()
        self.assertEqual(self.policy_binding, challenge["supervision_policy_binding"])
        self.assertEqual(self.authority, challenge["authority_binding"])
        self.assertEqual(self.repository, challenge["repository_observation_binding"])
        self.assertEqual("supervised", challenge["mode"])
        changed = copy.deepcopy(challenge)
        changed["authority_binding"] = reference("f")
        self.assert_failure(
            "gate-challenge-stale",
            lambda: supervision.validate_gate_challenge(
                changed,
                self.policy_binding,
                self.policy,
                self.authority,
                "plan",
            ),
        )
        changed_policy = copy.deepcopy(self.policy)
        changed_policy["gates"][1]["mode"] = "automatic"
        changed_policy["policy_sha256"] = inspector.sha256(
            inspector.canonical_bytes(
                {key: value for key, value in changed_policy.items() if key != "policy_sha256"}
            )
        )
        self.assert_failure(
            "gate-challenge-stale",
            lambda: supervision.validate_gate_challenge(
                challenge,
                self.policy_binding,
                changed_policy,
                self.authority,
                "plan",
            ),
        )

    def test_automatic_decision_cannot_select_agent_or_supervised_gate(self):
        challenge = self.challenge()
        self.assert_failure(
            "automatic-satisfaction-not-configured",
            lambda: supervision.automatic_decision(
                self.policy_binding,
                self.policy,
                reference("f"),
                challenge,
                self.authority,
            ),
        )
        automatic_policy = supervision.initialize(
            ISSUE,
            FAMILY,
            self.baseline,
            configuration({"plan": "automatic"}),
        )
        automatic = self.challenge("plan", automatic_policy)
        decision = supervision.automatic_decision(
            self.policy_binding,
            automatic_policy,
            reference("f"),
            automatic,
            self.authority,
        )
        self.assertEqual(
            ("satisfy", "configured-automatic-v1"),
            (decision["decision"], decision["rule"]),
        )
        self.assertNotIn("actor", decision)
        self.assertNotIn("role", decision)

    def test_satisfaction_requires_exactly_the_configured_mechanism(self):
        challenge_binding = reference("f")
        challenge = self.challenge()
        human = authorization(challenge_binding, challenge)
        human_binding = reference("0")
        satisfied = supervision.gate_satisfaction(
            self.policy_binding,
            self.policy,
            self.authority,
            challenge_binding,
            challenge,
            human_binding,
            human,
            self.repository,
        )
        self.assertEqual(human_binding, satisfied["human_authorization_binding"])
        self.assertIsNone(satisfied["automatic_decision_binding"])
        automatic_policy = supervision.initialize(
            ISSUE, FAMILY, self.baseline, configuration({"plan": "automatic"})
        )
        automatic_challenge = self.challenge("plan", automatic_policy)
        self.assert_failure(
            "automatic-gate-decision-stale",
            lambda: supervision.gate_satisfaction(
                self.policy_binding,
                automatic_policy,
                self.authority,
                challenge_binding,
                automatic_challenge,
                human_binding,
                human,
                self.repository,
            ),
        )
        replay = copy.deepcopy(satisfied)
        replay["challenge_binding"] = reference("1")
        self.assert_failure(
            "gate-satisfaction-stale",
            lambda: supervision.validate_satisfaction(
                replay,
                self.policy_binding,
                self.policy,
                self.authority,
                challenge_binding,
                challenge,
                human_binding,
                human,
            ),
        )

    def test_policy_change_is_human_authorized_and_affects_future_challenges(self):
        proposed = configuration({"tests": "automatic"})
        change_binding = reference("f")
        change = supervision.build_change_challenge(
            self.policy_binding,
            self.policy,
            self.authority,
            proposed,
            "IMPLEMENTATION",
        )
        human_binding = reference("0")
        human = authorization(change_binding, change)
        revised = supervision.revise(
            self.policy_binding,
            self.policy,
            change_binding,
            change,
            human_binding,
            human,
            self.authority,
            "IMPLEMENTATION",
        )
        self.assertEqual(1, revised["revision"])
        self.assertEqual(self.policy_binding, revised["previous_policy_binding"])
        self.assertEqual(human_binding, revised["authorization_binding"])
        self.assertEqual("supervised", self.challenge("tests")["mode"])
        self.assertEqual(
            "automatic",
            self.challenge("tests", revised, reference("1"))["mode"],
        )
        self.assert_failure(
            "supervision-policy-change-stale",
            lambda: supervision.revise(
                self.policy_binding,
                self.policy,
                change_binding,
                change,
                human_binding,
                human,
                self.authority,
                "VALIDATION",
            ),
        )


if __name__ == "__main__":
    unittest.main()
