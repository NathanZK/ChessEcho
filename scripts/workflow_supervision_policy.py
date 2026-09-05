#!/usr/bin/env python3
"""Deterministic supervision policy for existing replacement-workflow gates."""

import copy
import re

try:
    import workflow_inspector as inspector
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_inspector as inspector


POLICY_VERSION = "1.0.0"
CONFIG_FORMAT = "chess-echo-supervision-config-v1"
POLICY_FORMAT = "chess-echo-supervision-policy-v1"
CHALLENGE_FORMAT = "chess-echo-gate-challenge-v1"
CHANGE_FORMAT = "chess-echo-supervision-policy-change-v1"
AUTOMATIC_DECISION_FORMAT = "chess-echo-automatic-gate-decision-v1"
SATISFACTION_FORMAT = "chess-echo-gate-satisfaction-v1"
AUTHORIZATION_FORMAT = "chess-echo-human-authorization-v1"
POLICY_PATH = "workflow-supervision/policy.json"
CHALLENGE_PATH = "workflow-supervision/gate-challenge.json"
CHANGE_PATH = "workflow-supervision/policy-change.json"
AUTOMATIC_DECISION_PATH = "workflow-supervision/automatic-decision.json"
SATISFACTION_PATH = "workflow-supervision/gate-satisfaction.json"
GATES = ("final", "plan", "pr-publication", "tests")
MODES = frozenset(("automatic", "supervised"))
MANDATORY_HUMAN_GATES = frozenset(
    (
        "irreversible-authority-transfer",
        "irreversible-recovery-repair",
        "legacy-replacement-authority-cutover",
        "production-activation",
        "production-credential-provider-authorization",
        "recovery",
    )
)
SHA256_RE = re.compile(r"[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[0-9a-f]{32}")


class SupervisionPolicyFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status, self.code, self.message, self.subject = (
            status,
            code,
            message,
            subject,
        )


def _fail(status, code, message, subject=None):
    raise SupervisionPolicyFailure(status, code, message, subject)


def _require(condition, status, code, message, subject=None):
    if not condition:
        _fail(status, code, message, subject)


def _exact(value, keys, label):
    _require(
        isinstance(value, dict) and set(value) == set(keys),
        "corrupt",
        "invalid-%s-schema" % label,
        "%s schema is invalid" % label,
    )


def _canonical(value):
    try:
        return inspector.canonical_bytes(value)
    except (inspector.InspectionFailure, TypeError, ValueError) as error:
        _fail("corrupt", "invalid-canonical-json", str(error))


def _digest(value):
    return inspector.sha256(_canonical(value))


def _reference(value, label):
    try:
        return inspector.validate_reference(value, "evidence-binding")
    except inspector.InspectionFailure as error:
        _fail(error.status, "invalid-%s-binding" % label, error.message, error.subject)


def _identity(issue, family_run_id):
    _require(
        type(issue) is int
        and issue > 0
        and isinstance(family_run_id, str)
        and RUN_ID_RE.fullmatch(family_run_id) is not None,
        "corrupt",
        "invalid-supervision-identity",
        "Supervision identity is invalid",
    )


def validate_configuration(value):
    _exact(value, {"format", "gates"}, "supervision-config")
    _require(
        value["format"] == CONFIG_FORMAT,
        "unsupported",
        "unsupported-supervision-config-format",
        "Supervision configuration format is unsupported",
    )
    rows = value["gates"]
    _require(
        isinstance(rows, list),
        "corrupt",
        "invalid-supervision-gates",
        "Supervision gates must be a list",
    )
    normalized = []
    for row in rows:
        _exact(row, {"gate", "mode"}, "supervision-gate")
        gate, mode = row["gate"], row["mode"]
        _require(
            isinstance(gate, str) and isinstance(mode, str),
            "corrupt",
            "invalid-supervision-gate",
            "Supervision gate and mode must be strings",
        )
        if gate in MANDATORY_HUMAN_GATES:
            _fail(
                "denied",
                "mandatory-human-gate-not-configurable",
                "Mandatory-human gates cannot be configured",
                gate,
            )
        _require(
            gate in GATES,
            "unsupported",
            "unknown-supervision-gate",
            "Supervision gate is unsupported",
            gate,
        )
        _require(
            mode in MODES,
            "unsupported",
            "unknown-supervision-mode",
            "Supervision mode is unsupported",
            mode,
        )
        normalized.append({"gate": gate, "mode": mode})
    gates = [row["gate"] for row in normalized]
    _require(
        gates == list(GATES),
        "ambiguous",
        "noncanonical-supervision-gates",
        "Supervision gates must be complete, unique, and canonically ordered",
    )
    return {"format": CONFIG_FORMAT, "gates": normalized}


def _policy_document(
    issue,
    family_run_id,
    revision,
    baseline_binding,
    gates,
    previous_policy_binding,
    change_challenge_binding,
    authorization_binding,
):
    document = {
        "format": POLICY_FORMAT,
        "issue": issue,
        "family_run_id": family_run_id,
        "revision": revision,
        "baseline_binding": baseline_binding,
        "previous_policy_binding": previous_policy_binding,
        "change_challenge_binding": change_challenge_binding,
        "authorization_binding": authorization_binding,
        "gates": copy.deepcopy(gates),
    }
    document["policy_sha256"] = _digest(document)
    return document


def initialize(issue, family_run_id, baseline_binding, configuration):
    _identity(issue, family_run_id)
    baseline = _reference(baseline_binding, "baseline")
    config = validate_configuration(configuration)
    return _policy_document(
        issue,
        family_run_id,
        0,
        baseline,
        config["gates"],
        None,
        None,
        None,
    )


def validate_policy(value, issue=None, family_run_id=None):
    _exact(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "revision",
            "baseline_binding",
            "previous_policy_binding",
            "change_challenge_binding",
            "authorization_binding",
            "gates",
            "policy_sha256",
        },
        "supervision-policy",
    )
    _require(
        value["format"] == POLICY_FORMAT,
        "unsupported",
        "unsupported-supervision-policy-format",
        "Supervision policy format is unsupported",
    )
    _identity(value["issue"], value["family_run_id"])
    if issue is not None or family_run_id is not None:
        _require(
            value["issue"] == issue and value["family_run_id"] == family_run_id,
            "stale",
            "supervision-policy-identity-stale",
            "Supervision policy identity is stale",
        )
    _require(
        type(value["revision"]) is int and value["revision"] >= 0,
        "corrupt",
        "invalid-supervision-policy-revision",
        "Supervision policy revision is invalid",
    )
    baseline = _reference(value["baseline_binding"], "baseline")
    config = validate_configuration({"format": CONFIG_FORMAT, "gates": value["gates"]})
    previous = value["previous_policy_binding"]
    challenge = value["change_challenge_binding"]
    authorization = value["authorization_binding"]
    if value["revision"] == 0:
        _require(
            previous is None and challenge is None and authorization is None,
            "corrupt",
            "invalid-supervision-policy-origin",
            "Initial supervision policy cannot name change evidence",
        )
    else:
        previous = _reference(previous, "previous-policy")
        challenge = _reference(challenge, "change-challenge")
        authorization = _reference(authorization, "authorization")
    unsigned = copy.deepcopy(value)
    digest = unsigned.pop("policy_sha256")
    _require(
        isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None
        and digest == _digest(unsigned),
        "corrupt",
        "supervision-policy-digest-mismatch",
        "Supervision policy digest is invalid",
    )
    return {
        **copy.deepcopy(value),
        "baseline_binding": baseline,
        "previous_policy_binding": previous,
        "change_challenge_binding": challenge,
        "authorization_binding": authorization,
        "gates": config["gates"],
    }


def mode_for_gate(policy, gate):
    current = validate_policy(policy)
    _require(
        isinstance(gate, str),
        "corrupt",
        "invalid-supervision-gate",
        "Supervision gate must be a string",
    )
    if gate in MANDATORY_HUMAN_GATES:
        return "supervised"
    for row in current["gates"]:
        if row["gate"] == gate:
            return row["mode"]
    _fail(
        "unsupported",
        "unknown-supervision-gate",
        "Supervision gate is unsupported",
        gate,
    )


def _subjects(value):
    _require(
        isinstance(value, list),
        "corrupt",
        "invalid-gate-subjects",
        "Gate subjects must be a list",
    )
    rows = []
    for row in value:
        _exact(row, {"slot", "binding"}, "gate-subject")
        _require(
            isinstance(row["slot"], str) and row["slot"],
            "corrupt",
            "invalid-gate-subject",
            "Gate subject slot is invalid",
        )
        rows.append({"slot": row["slot"], "binding": _reference(row["binding"], "subject")})
    slots = [row["slot"] for row in rows]
    _require(
        slots == sorted(slots, key=lambda item: item.encode("utf-8"))
        and len(slots) == len(set(slots)),
        "ambiguous",
        "noncanonical-gate-subjects",
        "Gate subjects must be sorted and unique",
    )
    return rows


def build_gate_challenge(
    policy_binding,
    policy,
    authority_binding,
    gate,
    subjects,
    repository_observation_binding,
):
    current = validate_policy(policy)
    policy_reference = _reference(policy_binding, "supervision-policy")
    authority = _reference(authority_binding, "authority")
    repository = (
        None
        if repository_observation_binding is None
        else _reference(repository_observation_binding, "repository-observation")
    )
    core = {
        "format": CHALLENGE_FORMAT,
        "issue": current["issue"],
        "family_run_id": current["family_run_id"],
        "gate": gate,
        "decision": "approve",
        "mode": mode_for_gate(current, gate),
        "supervision_policy_binding": policy_reference,
        "authority_binding": authority,
        "subjects": _subjects(subjects),
        "repository_observation_binding": repository,
    }
    digest = _digest(core)
    return {
        **core,
        "confirmation": "approve %s %s" % (gate, digest),
        "challenge_sha256": digest,
    }


def validate_gate_challenge(
    value,
    policy_binding,
    policy,
    authority_binding,
    gate=None,
):
    _exact(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "gate",
            "decision",
            "mode",
            "supervision_policy_binding",
            "authority_binding",
            "subjects",
            "repository_observation_binding",
            "confirmation",
            "challenge_sha256",
        },
        "gate-challenge",
    )
    current = validate_policy(policy)
    expected_policy = _reference(policy_binding, "supervision-policy")
    expected_authority = _reference(authority_binding, "authority")
    repository = value["repository_observation_binding"]
    if repository is not None:
        repository = _reference(repository, "repository-observation")
    core = {
        "format": value["format"],
        "issue": value["issue"],
        "family_run_id": value["family_run_id"],
        "gate": value["gate"],
        "decision": value["decision"],
        "mode": value["mode"],
        "supervision_policy_binding": _reference(
            value["supervision_policy_binding"], "supervision-policy"
        ),
        "authority_binding": _reference(value["authority_binding"], "authority"),
        "subjects": _subjects(value["subjects"]),
        "repository_observation_binding": repository,
    }
    digest = _digest(core)
    valid = (
        core["format"] == CHALLENGE_FORMAT
        and core["issue"] == current["issue"]
        and core["family_run_id"] == current["family_run_id"]
        and core["decision"] == "approve"
        and core["supervision_policy_binding"] == expected_policy
        and core["authority_binding"] == expected_authority
        and core["mode"] == mode_for_gate(current, core["gate"])
        and (gate is None or core["gate"] == gate)
        and value["challenge_sha256"] == digest
        and value["confirmation"] == "approve %s %s" % (core["gate"], digest)
    )
    _require(
        valid,
        "stale",
        "gate-challenge-stale",
        "Gate challenge is not exact",
    )
    return copy.deepcopy(value)


def automatic_decision(
    policy_binding,
    policy,
    challenge_binding,
    challenge,
    authority_binding,
):
    current = validate_policy(policy)
    challenge_reference = _reference(challenge_binding, "gate-challenge")
    validated = validate_gate_challenge(
        challenge,
        policy_binding,
        current,
        authority_binding,
    )
    _require(
        validated["mode"] == "automatic",
        "denied",
        "automatic-satisfaction-not-configured",
        "Gate is not configured for automatic satisfaction",
        validated["gate"],
    )
    _require(
        validated["gate"] not in MANDATORY_HUMAN_GATES,
        "denied",
        "mandatory-human-gate",
        "Mandatory-human gate cannot be satisfied automatically",
        validated["gate"],
    )
    document = {
        "format": AUTOMATIC_DECISION_FORMAT,
        "issue": current["issue"],
        "family_run_id": current["family_run_id"],
        "gate": validated["gate"],
        "decision": "satisfy",
        "rule": "configured-automatic-v1",
        "authority_binding": _reference(authority_binding, "authority"),
        "supervision_policy_binding": _reference(
            policy_binding, "supervision-policy"
        ),
        "challenge_binding": challenge_reference,
        "challenge_sha256": validated["challenge_sha256"],
    }
    document["decision_sha256"] = _digest(document)
    return document


def _validate_human_authorization(value, challenge_binding, challenge):
    _exact(
        value,
        {
            "format",
            "challenge_binding",
            "decision",
            "actor",
            "source",
            "confirmation",
            "authorization_sha256",
        },
        "human-authorization",
    )
    unsigned = copy.deepcopy(value)
    digest = unsigned.pop("authorization_sha256")
    valid = (
        value["format"] == AUTHORIZATION_FORMAT
        and value["challenge_binding"] == challenge_binding
        and value["decision"] == "approve"
        and value["confirmation"] == challenge["confirmation"]
        and isinstance(value["actor"], dict)
        and isinstance(value["source"], dict)
        and isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None
        and digest == _digest(unsigned)
    )
    _require(
        valid,
        "stale",
        "human-authorization-stale",
        "Human authorization is not exact",
    )
    return copy.deepcopy(value)


def gate_satisfaction(
    policy_binding,
    policy,
    authority_binding,
    challenge_binding,
    challenge,
    mechanism_binding,
    mechanism,
    repository_observation_binding,
):
    current = validate_policy(policy)
    challenge_reference = _reference(challenge_binding, "gate-challenge")
    validated = validate_gate_challenge(
        challenge,
        policy_binding,
        current,
        authority_binding,
    )
    mechanism_reference = _reference(mechanism_binding, "satisfaction-mechanism")
    if validated["mode"] == "supervised":
        _validate_human_authorization(mechanism, challenge_reference, validated)
        human, automatic = mechanism_reference, None
    else:
        expected = automatic_decision(
            policy_binding,
            current,
            challenge_reference,
            validated,
            authority_binding,
        )
        _require(
            mechanism == expected,
            "stale",
            "automatic-gate-decision-stale",
            "Automatic gate decision is not exact",
        )
        human, automatic = None, mechanism_reference
    repository = (
        None
        if repository_observation_binding is None
        else _reference(repository_observation_binding, "repository-observation")
    )
    document = {
        "format": SATISFACTION_FORMAT,
        "issue": current["issue"],
        "family_run_id": current["family_run_id"],
        "gate": validated["gate"],
        "mode": validated["mode"],
        "authority_binding": _reference(authority_binding, "authority"),
        "supervision_policy_binding": _reference(
            policy_binding, "supervision-policy"
        ),
        "challenge_binding": challenge_reference,
        "human_authorization_binding": human,
        "automatic_decision_binding": automatic,
        "repository_observation_binding": repository,
    }
    document["satisfaction_sha256"] = _digest(document)
    return document


def validate_satisfaction(
    value,
    policy_binding,
    policy,
    authority_binding,
    challenge_binding,
    challenge,
    mechanism_binding,
    mechanism,
):
    _exact(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "gate",
            "mode",
            "authority_binding",
            "supervision_policy_binding",
            "challenge_binding",
            "human_authorization_binding",
            "automatic_decision_binding",
            "repository_observation_binding",
            "satisfaction_sha256",
        },
        "gate-satisfaction",
    )
    expected = gate_satisfaction(
        policy_binding,
        policy,
        authority_binding,
        challenge_binding,
        challenge,
        mechanism_binding,
        mechanism,
        value["repository_observation_binding"],
    )
    _require(
        value == expected,
        "stale",
        "gate-satisfaction-stale",
        "Gate satisfaction is not exact",
    )
    return copy.deepcopy(value)


def build_change_challenge(
    current_policy_binding,
    current_policy,
    authority_binding,
    proposed_configuration,
    phase,
):
    current = validate_policy(current_policy)
    proposal = validate_configuration(proposed_configuration)
    _require(
        proposal["gates"] != current["gates"],
        "conflict",
        "supervision-policy-unchanged",
        "Proposed supervision policy is unchanged",
    )
    _require(
        isinstance(phase, str) and phase,
        "corrupt",
        "invalid-supervision-change-phase",
        "Supervision change phase is invalid",
    )
    core = {
        "format": CHANGE_FORMAT,
        "issue": current["issue"],
        "family_run_id": current["family_run_id"],
        "gate": "supervision-policy-change",
        "decision": "approve",
        "authority_binding": _reference(authority_binding, "authority"),
        "current_policy_binding": _reference(
            current_policy_binding, "current-policy"
        ),
        "current_policy_sha256": current["policy_sha256"],
        "proposed_configuration": proposal,
        "phase": phase,
    }
    digest = _digest(core)
    return {
        **core,
        "confirmation": "approve supervision-policy-change %s" % digest,
        "challenge_sha256": digest,
    }


def validate_change_challenge(
    value,
    current_policy_binding,
    current_policy,
    authority_binding,
    phase,
):
    _exact(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "gate",
            "decision",
            "authority_binding",
            "current_policy_binding",
            "current_policy_sha256",
            "proposed_configuration",
            "phase",
            "confirmation",
            "challenge_sha256",
        },
        "supervision-policy-change",
    )
    current = validate_policy(current_policy)
    core = {
        key: copy.deepcopy(value[key])
        for key in (
            "format",
            "issue",
            "family_run_id",
            "gate",
            "decision",
            "authority_binding",
            "current_policy_binding",
            "current_policy_sha256",
            "proposed_configuration",
            "phase",
        )
    }
    core["authority_binding"] = _reference(core["authority_binding"], "authority")
    core["current_policy_binding"] = _reference(
        core["current_policy_binding"], "current-policy"
    )
    core["proposed_configuration"] = validate_configuration(
        core["proposed_configuration"]
    )
    digest = _digest(core)
    valid = (
        core["format"] == CHANGE_FORMAT
        and core["issue"] == current["issue"]
        and core["family_run_id"] == current["family_run_id"]
        and core["gate"] == "supervision-policy-change"
        and core["decision"] == "approve"
        and core["authority_binding"] == _reference(authority_binding, "authority")
        and core["current_policy_binding"]
        == _reference(current_policy_binding, "current-policy")
        and core["current_policy_sha256"] == current["policy_sha256"]
        and core["phase"] == phase
        and value["challenge_sha256"] == digest
        and value["confirmation"]
        == "approve supervision-policy-change %s" % digest
    )
    _require(
        valid,
        "stale",
        "supervision-policy-change-stale",
        "Supervision policy change challenge is not exact",
    )
    return copy.deepcopy(value)


def revise(
    current_policy_binding,
    current_policy,
    change_challenge_binding,
    change_challenge,
    authorization_binding,
    authorization,
    authority_binding,
    phase,
):
    current = validate_policy(current_policy)
    change_reference = _reference(change_challenge_binding, "change-challenge")
    change = validate_change_challenge(
        change_challenge,
        current_policy_binding,
        current,
        authority_binding,
        phase,
    )
    authorization_reference = _reference(authorization_binding, "authorization")
    try:
        _validate_human_authorization(authorization, change_reference, change)
    except SupervisionPolicyFailure as error:
        _fail(
            error.status,
            "supervision-change-authorization-stale",
            "Supervision change authorization is not exact",
            error.subject,
        )
    return _policy_document(
        current["issue"],
        current["family_run_id"],
        current["revision"] + 1,
        current["baseline_binding"],
        change["proposed_configuration"]["gates"],
        _reference(current_policy_binding, "current-policy"),
        change_reference,
        authorization_reference,
    )
