#!/usr/bin/env python3
"""Deterministic, read-only workflow invalidation and convergence policy."""

import argparse
import json
import pathlib
import re
import sys

try:
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_migration as migration
except ModuleNotFoundError:
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_migration as migration


POLICY_VERSION = "1.0.0"
REQUEST_FORMAT = "chess-echo-workflow-policy-request-v1"
STATE_FORMAT = "chess-echo-workflow-policy-state-v1"
RESULT_FORMAT = "chess-echo-workflow-policy-result-v1"
CORRECTION_AUTHORIZATION_FORMAT = "chess-echo-correction-authorization-v1"
MAX_REOPENS = 2
MAX_RETRIES_PER_STAGE = 3
SHA256_RE = re.compile(r"[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[0-9a-f]{32}")
OUTCOME_EXIT_CODES = {
    "resolved": 0,
    "missing": 3,
    "unsupported": 4,
    "corrupt": 5,
    "ambiguous": 6,
    "stale": 7,
}

DEPENDENCIES = {
    "plan-approval": (),
    "implementation-a": (),
    "test-manifest": ("plan-approval", "implementation-a"),
    "test-approval": ("test-manifest",),
    "implementation-submission": (
        "plan-approval",
        "implementation-a",
        "test-approval",
    ),
    "validation": ("implementation-submission",),
    "final-review": ("validation",),
    "pr-metadata": ("final-review",),
    "pr-approval": ("final-review", "pr-metadata"),
}
NODE_ORDER = tuple(DEPENDENCIES)
NODE_DECISIONS = {node: node for node in NODE_ORDER}
REOPEN_ROOTS = {
    "plan": ("plan-approval",),
    "tests": ("test-manifest",),
}
CORRECTION_ROOTS = {
    "metadata-only": ("pr-metadata",),
    "implementation-only": ("implementation-submission",),
    "test-contract": ("test-manifest",),
    "architecture": ("plan-approval", "implementation-a"),
}
CONVERGENCE_STATES = (
    "UNKNOWN",
    "CAUSE_ESTABLISHED",
    "FIX_IDENTIFIED",
    "FIX_APPLIED",
    "TARGETED_VERIFIED",
    "CLOSED",
)
CONVERGENCE_DECISIONS = {
    "CAUSE_ESTABLISHED": "cause-establishment",
    "FIX_IDENTIFIED": "fix-identification",
    "FIX_APPLIED": "fix-application",
    "TARGETED_VERIFIED": "targeted-verification",
    "CLOSED": "closure",
}


class PolicyFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.subject = subject

    def document(self):
        outcome = {
            "status": self.status,
            "code": self.code,
            "message": self.message,
        }
        if self.subject is not None:
            outcome["subject"] = self.subject
        return {"format": RESULT_FORMAT, "outcome": outcome}


def _fail(status, code, message, subject=None):
    raise PolicyFailure(status, code, message, subject)


def _exact_int(value):
    return type(value) is int


def _exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("corrupt", "invalid-%s-schema" % label, "%s schema is invalid" % label)


def _reference(value, expected_kind="evidence-binding"):
    try:
        return inspector.validate_reference(value, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)


def _canonical_bytes(value):
    try:
        return inspector.canonical_bytes(value)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except (TypeError, ValueError) as failure:
        _fail(
            "corrupt",
            "invalid-canonical-json",
            "Policy input cannot be represented as canonical JSON: %s" % failure,
        )


def _resolve_root(root):
    try:
        return pathlib.Path(root).resolve()
    except (TypeError, ValueError) as failure:
        _fail(
            "corrupt",
            "invalid-policy-root",
            "Policy root is invalid: %s" % failure,
        )
    except (OSError, RuntimeError) as failure:
        _fail(
            "missing",
            "policy-root-unreadable",
            "Policy root cannot be resolved: %s" % failure,
        )


def _digest(value):
    return inspector.sha256(_canonical_bytes(value))


def _state_digest(state):
    unsigned = dict(state)
    unsigned.pop("state_sha256", None)
    return _digest(unsigned)


def _result_digest(result):
    unsigned = dict(result)
    unsigned.pop("result_sha256", None)
    return _digest(unsigned)


def _node_sort(item):
    return NODE_ORDER.index(item["node"])


def _validate_binding_record(record):
    _exact_keys(record, {"binding", "migration_plan"}, "binding-input")
    return _reference(record["binding"]), record["migration_plan"]


def _verify_binding_inputs(root, records, issue, family_run_id):
    if not isinstance(records, list):
        _fail("corrupt", "invalid-binding-inputs", "Binding inputs must be a list")
    verified = {}
    for record in records:
        reference, migration_plan = _validate_binding_record(record)
        digest = reference["sha256"]
        if digest in verified:
            _fail("ambiguous", "duplicate-binding-input", "Binding input is duplicated", digest)
        try:
            projection = evidence.project(root, reference)
        except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
            _fail(failure.status, failure.code, failure.message, failure.subject)
        except OSError as failure:
            _fail(
                "missing",
                "policy-root-unreadable",
                "Policy evidence root cannot be read: %s" % failure,
            )
        identity = projection["identity"]
        if identity["issue"] != issue or identity["family_run_id"] != family_run_id:
            _fail(
                "stale",
                "binding-lineage-mismatch",
                "Binding belongs to another issue or family",
                digest,
            )
        migrated = (
            projection["migration"] is not None
            and projection["migration"]["adapter"] == evidence.MIGRATION_ADAPTER_FORMAT
        )
        if migrated:
            if not isinstance(migration_plan, dict):
                _fail(
                    "missing",
                    "migration-plan-required",
                    "Migrated binding requires its complete migration plan",
                    digest,
                )
            expected = migration_plan.get("expected")
            if (
                migration_plan.get("operation") != "publish"
                or not isinstance(expected, dict)
                or expected.get("binding") != reference
                or expected.get("source_manifest") != projection["migration"]["source"]
            ):
                _fail(
                    "stale",
                    "migration-plan-not-authoritative",
                    "Migrated binding requires its original publishing plan",
                    digest,
                )
            try:
                migration_result = migration.verify(root, migration_plan)
            except (inspector.InspectionFailure, migration.MigrationFailure) as failure:
                _fail(failure.status, failure.code, failure.message, failure.subject)
            except OSError as failure:
                _fail(
                    "missing",
                    "policy-root-unreadable",
                    "Policy migration root cannot be read: %s" % failure,
                )
            if migration_result["binding"] != reference:
                _fail(
                    "stale",
                    "migration-binding-mismatch",
                    "Migration plan resolves to another binding",
                    digest,
                )
        elif migration_plan is not None:
            _fail(
                "ambiguous",
                "unexpected-migration-plan",
                "Canonical binding cannot carry a migration plan",
                digest,
            )
        verified[digest] = {
            "reference": reference,
            "decision": projection["decision"],
            "identity": identity,
            "lineage": projection["lineage"],
            "parent_binding": projection["authority"]["parent_binding"],
            "migration": projection["migration"],
            "entries": projection["entries"],
        }
    return verified


def _validate_dependencies(value, node):
    if not isinstance(value, list):
        _fail("corrupt", "invalid-node-dependencies", "Node dependencies must be a list")
    expected = DEPENDENCIES[node]
    if len(value) != len(expected):
        _fail("corrupt", "invalid-node-dependencies", "Node dependency set is incomplete")
    result = {}
    for item in value:
        _exact_keys(item, {"node", "binding"}, "node-dependency")
        parent = item["node"]
        if not isinstance(parent, str):
            _fail(
                "corrupt",
                "invalid-node-dependency-identifier",
                "Node dependency identifier must be a string",
            )
        if parent in result:
            _fail("ambiguous", "duplicate-node-dependency", "Node dependency is duplicated")
        result[parent] = _reference(item["binding"])
    if set(result) != set(expected):
        _fail("corrupt", "invalid-node-dependencies", "Node dependency set is invalid")
    return result


def _validate_active(value):
    if not isinstance(value, list):
        _fail("corrupt", "invalid-active-nodes", "Active nodes must be a list")
    active = {}
    for item in value:
        _exact_keys(item, {"node", "binding", "dependencies"}, "active-node")
        node = item["node"]
        if not isinstance(node, str):
            _fail(
                "corrupt",
                "invalid-policy-node-identifier",
                "Policy node identifier must be a string",
            )
        if node not in DEPENDENCIES:
            _fail("unsupported", "unsupported-policy-node", "Policy node is unsupported")
        if node in active:
            _fail("ambiguous", "duplicate-active-node", "Active node is duplicated", node)
        active[node] = {
            "node": node,
            "binding": _reference(item["binding"]),
            "dependencies": _validate_dependencies(item["dependencies"], node),
        }
    for node, item in active.items():
        for parent, binding in item["dependencies"].items():
            if parent not in active:
                _fail(
                    "stale",
                    "active-dependency-missing",
                    "Active node depends on an inactive node",
                    node,
                )
            if binding != active[parent]["binding"]:
                _fail(
                    "stale",
                    "active-dependency-stale",
                    "Active node dependency binding is stale",
                    node,
                )
    return active


def _validate_history(value):
    if not isinstance(value, list):
        _fail("corrupt", "invalid-binding-history", "Binding history must be a list")
    history = []
    seen = set()
    for item in value:
        _exact_keys(item, {"node", "binding", "status", "transition_id"}, "binding-history")
        if not isinstance(item["node"], str):
            _fail(
                "corrupt",
                "invalid-policy-node-identifier",
                "Historical node identifier must be a string",
            )
        if item["node"] not in DEPENDENCIES:
            _fail("unsupported", "unsupported-policy-node", "Historical node is unsupported")
        binding = _reference(item["binding"])
        if not isinstance(item["status"], str):
            _fail(
                "corrupt",
                "invalid-history-status",
                "Binding history status must be a string",
            )
        if item["status"] not in {"active", "invalidated"}:
            _fail("corrupt", "invalid-history-status", "Binding history status is invalid")
        transition_id = item["transition_id"]
        if transition_id is not None and (
            not isinstance(transition_id, str) or SHA256_RE.fullmatch(transition_id) is None
        ):
            _fail("corrupt", "invalid-history-transition", "History transition is invalid")
        key = (item["node"], binding["sha256"])
        if key in seen:
            _fail("ambiguous", "duplicate-binding-history", "Binding history is duplicated")
        seen.add(key)
        history.append(
            {
                "node": item["node"],
                "binding": binding,
                "status": item["status"],
                "transition_id": transition_id,
            }
        )
    return history


def _validate_budgets(value):
    _exact_keys(value, {"reopens", "retries"}, "policy-budgets")
    if not _exact_int(value["reopens"]) or value["reopens"] < 0:
        _fail("corrupt", "invalid-reopen-budget", "Reopen usage must be a nonnegative integer")
    if value["reopens"] > MAX_REOPENS:
        _fail("corrupt", "reopen-budget-overflow", "Reopen usage exceeds the policy limit")
    retries = value["retries"]
    if not isinstance(retries, dict) or set(retries) != set(CONVERGENCE_STATES):
        _fail("corrupt", "invalid-retry-budgets", "Retry usage must name every convergence state")
    if any(not _exact_int(count) or count < 0 for count in retries.values()):
        _fail("corrupt", "invalid-retry-budget", "Retry usage must be nonnegative integers")
    if any(count > MAX_RETRIES_PER_STAGE for count in retries.values()):
        _fail("corrupt", "retry-budget-overflow", "Retry usage exceeds the policy limit")
    return {"reopens": value["reopens"], "retries": dict(retries)}


def _validate_convergence(value):
    _exact_keys(value, {"episode", "state", "evidence"}, "convergence")
    if not _exact_int(value["episode"]) or value["episode"] < 0:
        _fail("corrupt", "invalid-convergence-episode", "Convergence episode is invalid")
    if not isinstance(value["state"], str):
        _fail(
            "corrupt",
            "invalid-convergence-state-identifier",
            "Convergence state identifier must be a string",
        )
    if value["state"] not in CONVERGENCE_STATES:
        _fail("unsupported", "unsupported-convergence-state", "Convergence state is unsupported")
    if not isinstance(value["evidence"], list):
        _fail("corrupt", "invalid-convergence-evidence", "Convergence evidence must be a list")
    entries = []
    for item in value["evidence"]:
        _exact_keys(
            item,
            {"episode", "state", "source_tip", "active_sha256", "binding"},
            "convergence-evidence",
        )
        if not isinstance(item["state"], str):
            _fail(
                "corrupt",
                "invalid-convergence-state-identifier",
                "Evidence state identifier must be a string",
            )
        if item["state"] not in CONVERGENCE_STATES:
            _fail("unsupported", "unsupported-convergence-state", "Evidence state is unsupported")
        if (
            not _exact_int(item["episode"])
            or item["episode"] < 0
            or (
                item["source_tip"] is not None
                and (
                    not isinstance(item["source_tip"], str)
                    or SHA256_RE.fullmatch(item["source_tip"]) is None
                )
            )
            or not isinstance(item["active_sha256"], str)
            or SHA256_RE.fullmatch(item["active_sha256"]) is None
        ):
            _fail(
                "corrupt",
                "invalid-convergence-evidence-context",
                "Convergence evidence context is invalid",
            )
        entries.append(
            {
                "episode": item["episode"],
                "state": item["state"],
                "source_tip": item["source_tip"],
                "active_sha256": item["active_sha256"],
                "binding": _reference(item["binding"]),
            }
        )
    return {"episode": value["episode"], "state": value["state"], "evidence": entries}


def _validate_state(value, issue, family_run_id, expected_digest):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "generation",
            "transition_tip",
            "transition",
            "active",
            "history",
            "budgets",
            "convergence",
            "state_sha256",
        },
        "policy-state",
    )
    if value["format"] != STATE_FORMAT:
        _fail("unsupported", "unsupported-state-format", "Policy state format is unsupported")
    if (
        not _exact_int(value["issue"])
        or value["issue"] < 1
        or value["issue"] != issue
        or not isinstance(value["family_run_id"], str)
        or RUN_ID_RE.fullmatch(value["family_run_id"]) is None
        or value["family_run_id"] != family_run_id
        or not _exact_int(value["generation"])
        or value["generation"] < 0
    ):
        _fail("stale", "policy-state-identity-mismatch", "Policy state identity is stale")
    tip = value["transition_tip"]
    if tip is not None and (not isinstance(tip, str) or SHA256_RE.fullmatch(tip) is None):
        _fail("corrupt", "invalid-transition-tip", "Transition tip is invalid")
    transition = value["transition"]
    if value["generation"] == 0:
        if transition is not None or tip is not None:
            _fail(
                "corrupt",
                "invalid-genesis-transition",
                "Generation zero cannot name a transition",
            )
    elif not isinstance(transition, dict) or tip is None:
        _fail(
            "corrupt",
            "missing-policy-transition",
            "Non-genesis policy state requires its transition",
        )
    else:
        transition = _validate_recorded_operation(transition)
    if not isinstance(value["state_sha256"], str) or SHA256_RE.fullmatch(
        value["state_sha256"]
    ) is None:
        _fail("corrupt", "invalid-state-digest", "Policy state digest is invalid")
    active = _validate_active(value["active"])
    history = _validate_history(value["history"])
    if value["active"] != _active_document(active) or value["history"] != _history_document(
        history
    ):
        _fail(
            "corrupt",
            "noncanonical-policy-state-order",
            "Policy state node and history ordering is noncanonical",
        )
    budgets = _validate_budgets(value["budgets"])
    convergence = _validate_convergence(value["convergence"])
    history_active = {
        (item["node"], item["binding"]["sha256"])
        for item in history
        if item["status"] == "active"
    }
    active_pairs = {(node, item["binding"]["sha256"]) for node, item in active.items()}
    if history_active != active_pairs:
        _fail(
            "stale",
            "active-history-mismatch",
            "Active nodes and binding history disagree",
        )
    normalized = {
        "format": STATE_FORMAT,
        "issue": issue,
        "family_run_id": family_run_id,
        "generation": value["generation"],
        "transition_tip": tip,
        "transition": transition,
        "active": active,
        "history": history,
        "budgets": budgets,
        "convergence": convergence,
    }
    canonical = _state_document(normalized)
    supplied_unsigned = dict(value)
    supplied_unsigned.pop("state_sha256")
    canonical_unsigned = dict(canonical)
    canonical_unsigned.pop("state_sha256")
    if _canonical_bytes(supplied_unsigned) != _canonical_bytes(
        canonical_unsigned
    ):
        _fail(
            "corrupt",
            "noncanonical-policy-state",
            "Policy state is not canonical policy output",
        )
    calculated = canonical["state_sha256"]
    if value["state_sha256"] != calculated or expected_digest != calculated:
        _fail("stale", "policy-state-stale", "Policy state digest is stale")
    normalized["state_sha256"] = calculated
    return normalized


def _state_binding_digests(state):
    digests = {
        item["binding"]["sha256"] for item in state["active"].values()
    } | {item["binding"]["sha256"] for item in state["history"]}
    digests.update(item["binding"]["sha256"] for item in state["convergence"]["evidence"])
    return digests


def _operation_binding_digests(operation):
    digests = set()
    operation_type = operation.get("type") if isinstance(operation, dict) else None
    if operation_type in {"reopen", "correction"}:
        replacements = operation.get("replacements")
        if isinstance(replacements, list):
            for item in replacements:
                if isinstance(item, dict) and isinstance(item.get("binding"), dict):
                    digest = item["binding"].get("sha256")
                    if isinstance(digest, str):
                        digests.add(digest)
    if operation_type == "correction" and isinstance(operation.get("parent_binding"), dict):
        digest = operation["parent_binding"].get("sha256")
        if isinstance(digest, str):
            digests.add(digest)
        authorization = operation.get("authorization")
        if isinstance(authorization, dict) and isinstance(
            authorization.get("binding"), dict
        ):
            digest = authorization["binding"].get("sha256")
            if isinstance(digest, str):
                digests.add(digest)
    if operation_type == "convergence" and isinstance(operation.get("evidence"), dict):
        digest = operation["evidence"].get("sha256")
        if isinstance(digest, str):
            digests.add(digest)
    return digests


def _required_binding_digests(state, operation, authority_chain):
    digests = _state_binding_digests(state)
    for item in authority_chain:
        digests.add(item["binding"]["sha256"])
        chain_state = _validate_state(
            item["state"],
            state["issue"],
            state["family_run_id"],
            item["state"]["state_sha256"],
        )
        digests.update(_state_binding_digests(chain_state))
        digests.update(_operation_binding_digests(chain_state["transition"]))
    digests.update(_operation_binding_digests(operation))
    return digests


def _verify_node_bindings(state, operation, verified):
    def require_exact(reference, subject):
        digest = reference["sha256"]
        if digest not in verified:
            _fail("missing", "binding-input-missing", "%s binding input is missing" % subject)
        if verified[digest]["reference"] != reference:
            _fail(
                "stale",
                "binding-reference-mismatch",
                "%s binding reference differs from verified evidence" % subject,
            )

    for node, item in state["active"].items():
        require_exact(item["binding"], "Active node")
        for reference in item["dependencies"].values():
            require_exact(reference, "Active dependency")
    for item in state["history"]:
        require_exact(item["binding"], "Historical")
        digest = item["binding"]["sha256"]
        if verified[digest]["decision"]["type"] != NODE_DECISIONS[item["node"]]:
            _fail(
                "stale",
                "node-decision-mismatch",
                "Evidence decision does not match its policy node",
                item["node"],
            )
    for item in state["convergence"]["evidence"]:
        require_exact(item["binding"], "Convergence history")
        expected_id = _convergence_decision_id(
            item["episode"], item["source_tip"], item["active_sha256"]
        )
        if verified[item["binding"]["sha256"]]["decision"]["id"] != expected_id:
            _fail(
                "stale",
                "convergence-evidence-context-mismatch",
                "Historical convergence evidence is bound to another context",
            )
    if operation["type"] in {"reopen", "correction"}:
        for node, reference in operation["replacements"].items():
            require_exact(reference, "Replacement")
            if verified[reference["sha256"]]["decision"]["type"] != NODE_DECISIONS[node]:
                _fail(
                    "stale",
                    "node-decision-mismatch",
                    "Replacement evidence decision does not match its policy node",
                    node,
                )
    if operation["type"] == "correction":
        require_exact(operation["parent_binding"], "Correction parent")
    if operation["type"] == "convergence":
        require_exact(operation["evidence"], "Convergence operation")


def _validate_reason(value):
    if not isinstance(value, str) or not value.strip():
        _fail("corrupt", "invalid-policy-reason", "Policy reason must be nonempty")
    return value


def _validate_replacements(value, expected_roots):
    if not isinstance(value, list) or len(value) != len(expected_roots):
        _fail("corrupt", "invalid-replacements", "Replacement roots are incomplete")
    replacements = {}
    for item in value:
        _exact_keys(item, {"node", "binding"}, "replacement")
        node = item["node"]
        if not isinstance(node, str):
            _fail(
                "corrupt",
                "invalid-policy-node-identifier",
                "Replacement node identifier must be a string",
            )
        if node in replacements:
            _fail("ambiguous", "duplicate-replacement", "Replacement root is duplicated")
        replacements[node] = _reference(item["binding"])
    if set(replacements) != set(expected_roots):
        _fail("corrupt", "invalid-replacements", "Replacement roots do not match policy")
    return replacements


def _validate_child_identity(value):
    _exact_keys(
        value,
        {
            "issue",
            "run_id",
            "family_run_id",
            "correction",
            "run_generation",
            "sequence",
            "event_tip",
        },
        "correction-child-identity",
    )
    if (
        not _exact_int(value["issue"])
        or value["issue"] < 1
        or not isinstance(value["run_id"], str)
        or RUN_ID_RE.fullmatch(value["run_id"]) is None
        or not isinstance(value["family_run_id"], str)
        or RUN_ID_RE.fullmatch(value["family_run_id"]) is None
        or not _exact_int(value["correction"])
        or value["correction"] < 1
        or not _exact_int(value["run_generation"])
        or value["run_generation"] < 0
        or not _exact_int(value["sequence"])
        or value["sequence"] < 1
        or not isinstance(value["event_tip"], str)
        or SHA256_RE.fullmatch(value["event_tip"]) is None
    ):
        _fail(
            "corrupt",
            "invalid-correction-child-identity",
            "Correction child identity is invalid",
        )
    return dict(value)


def _validate_correction_authorization(value):
    _exact_keys(value, {"binding", "document"}, "correction-authorization")
    document = value["document"]
    _exact_keys(
        document,
        {
            "format",
            "issue",
            "family_run_id",
            "parent_binding",
            "child_identity",
            "classification",
            "roots",
        },
        "correction-authorization-document",
    )
    if document["format"] != CORRECTION_AUTHORIZATION_FORMAT:
        _fail(
            "unsupported",
            "unsupported-correction-authorization-format",
            "Correction authorization format is unsupported",
        )
    if (
        not _exact_int(document["issue"])
        or document["issue"] < 1
        or not isinstance(document["family_run_id"], str)
        or RUN_ID_RE.fullmatch(document["family_run_id"]) is None
        or not isinstance(document["classification"], str)
        or document["classification"] not in CORRECTION_ROOTS
        or not isinstance(document["roots"], list)
        or any(not isinstance(root, str) for root in document["roots"])
        or document["roots"] != list(CORRECTION_ROOTS[document["classification"]])
    ):
        _fail(
            "corrupt",
            "invalid-correction-authorization",
            "Correction authorization is invalid",
        )
    return {
        "binding": _reference(value["binding"]),
        "document": {
            "format": CORRECTION_AUTHORIZATION_FORMAT,
            "issue": document["issue"],
            "family_run_id": document["family_run_id"],
            "parent_binding": _reference(document["parent_binding"]),
            "child_identity": _validate_child_identity(document["child_identity"]),
            "classification": document["classification"],
            "roots": list(document["roots"]),
        },
    }


def _validate_operation(value):
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-operation", "Policy operation must be an object")
    operation_type = value.get("type")
    if operation_type == "reopen":
        _exact_keys(value, {"type", "target", "reason", "replacements"}, "reopen-operation")
        if not isinstance(value["target"], str):
            _fail(
                "corrupt",
                "invalid-reopen-target-identifier",
                "Reopen target identifier must be a string",
            )
        if value["target"] not in REOPEN_ROOTS:
            _fail("unsupported", "unsupported-reopen-target", "Reopen target is unsupported")
        roots = REOPEN_ROOTS[value["target"]]
        return {
            "type": operation_type,
            "target": value["target"],
            "reason": _validate_reason(value["reason"]),
            "roots": roots,
            "replacements": _validate_replacements(value["replacements"], roots),
        }
    if operation_type == "correction":
        _exact_keys(
            value,
            {
                "type",
                "classification",
                "reason",
                "parent_binding",
                "child_identity",
                "authorization",
                "replacements",
            },
            "correction-operation",
        )
        if not isinstance(value["classification"], str):
            _fail(
                "corrupt",
                "invalid-correction-classification-identifier",
                "Correction classification identifier must be a string",
            )
        if value["classification"] not in CORRECTION_ROOTS:
            _fail(
                "unsupported",
                "unsupported-correction-classification",
                "Correction classification is unsupported",
            )
        roots = CORRECTION_ROOTS[value["classification"]]
        return {
            "type": operation_type,
            "classification": value["classification"],
            "reason": _validate_reason(value["reason"]),
            "parent_binding": _reference(value["parent_binding"]),
            "child_identity": _validate_child_identity(value["child_identity"]),
            "authorization": _validate_correction_authorization(
                value["authorization"]
            ),
            "roots": roots,
            "replacements": _validate_replacements(value["replacements"], roots),
        }
    if operation_type == "convergence":
        _exact_keys(
            value,
            {"type", "from", "to", "evidence", "retry"},
            "convergence-operation",
        )
        if not isinstance(value["from"], str) or not isinstance(value["to"], str):
            _fail(
                "corrupt",
                "invalid-convergence-state-identifier",
                "Convergence state identifiers must be strings",
            )
        if value["from"] not in CONVERGENCE_STATES or value["to"] not in CONVERGENCE_STATES:
            _fail("unsupported", "unsupported-convergence-state", "Convergence state is unsupported")
        if type(value["retry"]) is not bool:
            _fail("corrupt", "invalid-retry-flag", "Retry flag must be a boolean")
        return {
            "type": operation_type,
            "from": value["from"],
            "to": value["to"],
            "evidence": _reference(value["evidence"]),
            "retry": value["retry"],
        }
    _fail("unsupported", "unsupported-policy-operation", "Policy operation is unsupported")


def _validate_recorded_operation(value):
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-recorded-transition", "Recorded transition is invalid")
    operation_type = value.get("type")
    if not isinstance(operation_type, str):
        _fail(
            "corrupt",
            "invalid-policy-operation-identifier",
            "Recorded policy operation identifier must be a string",
        )
    if operation_type in {"reopen", "correction"}:
        replacements = value.get("replacements")
        if not isinstance(replacements, dict):
            _fail(
                "corrupt",
                "invalid-recorded-transition",
                "Recorded replacements must be an object",
            )
        public = {
            key: item
            for key, item in value.items()
            if key not in {"roots", "replacements"}
        }
        public["replacements"] = [
            {"node": node, "binding": reference}
            for node, reference in replacements.items()
        ]
        normalized = _validate_operation(public)
    else:
        normalized = _validate_operation(value)
    if _canonical_bytes(normalized) != _canonical_bytes(value):
        _fail(
            "corrupt",
            "recorded-transition-mismatch",
            "Recorded transition is not canonical policy output",
        )
    return normalized


def _manifest_binds_document(entry, path, document, label):
    document_bytes = _canonical_bytes(document)
    manifest_entries = entry["entries"]
    if len(manifest_entries) != 1:
        _fail(
            "stale",
            "%s-manifest-mismatch" % label,
            "%s binding must contain exactly one entry" % label.replace("-", " ").title(),
        )
    manifest_entry = manifest_entries[0]
    if (
        manifest_entry["path"] != path
        or manifest_entry["kind"] != "regular"
        or manifest_entry["mode"] != "100644"
        or manifest_entry["content_sha256"] != inspector.sha256(document_bytes)
        or manifest_entry["size"] != len(document_bytes)
    ):
        _fail(
            "stale",
            "%s-manifest-mismatch" % label,
            "%s binding does not bind the supplied document"
            % label.replace("-", " ").title(),
        )


def _state_binding_matches(entry, state):
    if entry["decision"] != {
        "type": "policy-state",
        "id": "generation-%d" % state["generation"],
    }:
        _fail(
            "stale",
            "policy-state-decision-mismatch",
            "Policy state binding has the wrong decision identity",
        )
    if entry["migration"] is not None:
        _fail(
            "stale",
            "policy-state-migration-unsupported",
            "Policy state authority must be canonically published",
        )
    _manifest_binds_document(
        entry,
        "workflow-policy/state.json",
        _state_document(state),
        "policy-state",
    )


def _validate_correction_lineage(
    state, operation, verified, trusted_correction_binding
):
    if operation["type"] != "correction":
        if trusted_correction_binding is not None:
            _fail(
                "ambiguous",
                "unexpected-correction-authority",
                "Non-correction operation cannot name correction authority",
            )
        return
    if (
        not isinstance(trusted_correction_binding, str)
        or SHA256_RE.fullmatch(trusted_correction_binding) is None
    ):
        _fail(
            "missing",
            "trusted-correction-binding-required",
            "Correction requires an independently trusted authorization binding",
        )
    if "pr-approval" not in state["active"]:
        _fail(
            "stale",
            "correction-parent-inactive",
            "Correction requires the active PR approval as its exact parent",
        )
    parent = operation["parent_binding"]
    if parent != state["active"]["pr-approval"]["binding"]:
        _fail(
            "stale",
            "correction-parent-mismatch",
            "Correction parent does not match the active PR approval",
        )
    authorization = operation["authorization"]
    authorization_reference = authorization["binding"]
    if authorization_reference["sha256"] != trusted_correction_binding:
        _fail(
            "stale",
            "trusted-correction-binding-mismatch",
            "Correction authorization does not match the trusted binding",
        )
    if authorization_reference["sha256"] not in verified:
        _fail(
            "missing",
            "binding-input-missing",
            "Correction authorization binding input is missing",
        )
    authorization_entry = verified[authorization_reference["sha256"]]
    if authorization_entry["reference"] != authorization_reference:
        _fail(
            "stale",
            "binding-reference-mismatch",
            "Correction authorization reference differs from verified evidence",
        )
    authorization_document = authorization["document"]
    if authorization_document != {
        "format": CORRECTION_AUTHORIZATION_FORMAT,
        "issue": state["issue"],
        "family_run_id": state["family_run_id"],
        "parent_binding": parent,
        "child_identity": operation["child_identity"],
        "classification": operation["classification"],
        "roots": list(operation["roots"]),
    }:
        _fail(
            "stale",
            "correction-authorization-mismatch",
            "Correction operation differs from its authorization",
        )
    if (
        authorization_entry["decision"]
        != {
            "type": "correction-authorization",
            "id": "child-%s" % operation["child_identity"]["run_id"],
        }
        or authorization_entry["identity"] != operation["child_identity"]
        or authorization_entry["lineage"]["status"] != "replacement"
        or authorization_entry["parent_binding"] != parent
        or authorization_entry["migration"] is not None
    ):
        _fail(
            "stale",
            "correction-authorization-lineage-mismatch",
            "Correction authorization has incompatible identity or lineage",
        )
    _manifest_binds_document(
        authorization_entry,
        "workflow-policy/correction-authorization.json",
        authorization_document,
        "correction-authorization",
    )
    child_identity = None
    for node, reference in operation["replacements"].items():
        candidate = verified[reference["sha256"]]
        lineage = candidate["lineage"]
        if lineage["status"] != "replacement":
            _fail(
                "stale",
                "correction-replacement-not-child",
                "Correction replacement must be replacement-lineage evidence",
                node,
            )
        if candidate["parent_binding"] != parent:
            _fail(
                "stale",
                "correction-parent-mismatch",
                "Correction replacement names another parent",
                node,
            )
        identity = candidate["identity"]
        if identity != operation["child_identity"]:
            _fail(
                "stale",
                "correction-child-identity-mismatch",
                "Correction replacement belongs to another child identity",
                node,
            )
        if child_identity is None:
            child_identity = identity
        elif identity != child_identity:
            _fail(
                "ambiguous",
                "correction-child-identity-mismatch",
                "Correction replacements do not belong to one child identity",
            )


def _evaluate_recorded_transition(state, operation, verified):
    _verify_node_bindings(state, operation, verified)
    trusted_correction = (
        operation["authorization"]["binding"]["sha256"]
        if operation["type"] == "correction"
        else None
    )
    _validate_correction_lineage(
        state, operation, verified, trusted_correction
    )
    transition_id = _transition_id(state, operation)
    if operation["type"] == "convergence":
        result = _evaluate_convergence(state, operation, verified, transition_id)
    else:
        result = _evaluate_invalidation(state, operation, transition_id)
    if result["outcome"]["code"] != "evaluated":
        _fail(
            "corrupt",
            "invalid-authority-transition",
            "Escalation cannot create a new authoritative state",
        )
    return result["next_state"]


def _validate_authority_chain(value, current_state, verified, issue, family_run_id):
    if not isinstance(value, list) or not value:
        _fail(
            "missing",
            "policy-authority-chain-required",
            "Policy authority chain must be nonempty",
        )
    chain = []
    previous_reference = None
    previous_state = None
    for index, item in enumerate(value):
        _exact_keys(item, {"binding", "state"}, "policy-authority-entry")
        reference = _reference(item["binding"])
        if reference["sha256"] not in verified:
            _fail(
                "missing",
                "binding-input-missing",
                "Policy authority binding input is missing",
            )
        authority = verified[reference["sha256"]]
        if authority["reference"] != reference:
            _fail(
                "stale",
                "binding-reference-mismatch",
                "Policy authority reference differs from verified evidence",
            )
        state = _validate_state(
            item["state"],
            issue,
            family_run_id,
            item["state"].get("state_sha256")
            if isinstance(item["state"], dict)
            else None,
        )
        state_document = _state_document(state)
        if _canonical_bytes(state_document) != _canonical_bytes(
            item["state"]
        ):
            _fail(
                "corrupt",
                "noncanonical-policy-state",
                "Policy authority state is not canonical",
            )
        _state_binding_matches(authority, state)
        lineage = authority["lineage"]
        if index == 0:
            if (
                state["generation"] != 0
                or lineage["status"] != "original"
                or lineage["parent_binding"] is not None
                or state["budgets"]["reopens"] != 0
                or any(state["budgets"]["retries"].values())
                or state["convergence"] != {
                    "episode": 0,
                    "state": "UNKNOWN",
                    "evidence": [],
                }
            ):
                _fail(
                    "stale",
                    "invalid-policy-genesis",
                    "Policy authority chain has an invalid genesis",
                )
        else:
            if (
                state["generation"] != previous_state["generation"] + 1
                or lineage["status"] != "replacement"
                or lineage["parent_binding"] != previous_reference
            ):
                _fail(
                    "stale",
                    "policy-authority-lineage-mismatch",
                    "Policy authority chain lineage is not contiguous",
                )
            operation = _validate_recorded_operation(state["transition"])
            expected = _evaluate_recorded_transition(previous_state, operation, verified)
            if expected != state_document:
                _fail(
                    "stale",
                    "policy-authority-transition-mismatch",
                    "Policy state does not follow its authoritative predecessor",
                )
        chain.append({"binding": reference, "state": state_document})
        previous_reference = reference
        previous_state = state
    if _canonical_bytes(chain[-1]["state"]) != _canonical_bytes(
        _state_document(current_state)
    ):
        _fail(
            "stale",
            "policy-authority-current-mismatch",
            "Requested state is not the authoritative chain tip",
        )
    return chain


def _descendants(roots):
    closure = set(roots)
    changed = True
    while changed:
        changed = False
        for node, parents in DEPENDENCIES.items():
            if node not in closure and closure.intersection(parents):
                closure.add(node)
                changed = True
    return closure


def _active_document(active):
    return [
        {
            "node": node,
            "binding": active[node]["binding"],
            "dependencies": [
                {"node": parent, "binding": active[node]["dependencies"][parent]}
                for parent in DEPENDENCIES[node]
            ],
        }
        for node in NODE_ORDER
        if node in active
    ]


def _active_sha256(active):
    return _digest(_active_document(active))


def _convergence_decision_id(episode, source_tip, active_sha256):
    return "episode-%d-tip-%s-active-%s" % (
        episode,
        source_tip if source_tip is not None else "genesis",
        active_sha256,
    )


def _history_document(history):
    return sorted(
        history,
        key=lambda item: (
            NODE_ORDER.index(item["node"]),
            item["binding"]["sha256"],
        ),
    )


def _state_document(state):
    result = {
        "format": STATE_FORMAT,
        "issue": state["issue"],
        "family_run_id": state["family_run_id"],
        "generation": state["generation"],
        "transition_tip": state["transition_tip"],
        "transition": state["transition"],
        "active": _active_document(state["active"]),
        "history": _history_document(state["history"]),
        "budgets": state["budgets"],
        "convergence": state["convergence"],
    }
    result["state_sha256"] = _state_digest(result)
    return result


def _transition_id(state, operation):
    return _digest(
        {
            "format": "chess-echo-workflow-policy-transition-v1",
            "input_state_sha256": state["state_sha256"],
            "operation": operation,
        }
    )


def _escalated_result(state, operation, transition_id, escalation):
    next_state = _state_document(state)
    result = {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "escalated"},
        "input_state_sha256": state["state_sha256"],
        "transition_id": transition_id,
        "operation": operation,
        "changed_roots": [],
        "invalidated": [],
        "preserved": _active_document(state["active"]),
        "next_state": next_state,
        "escalation": escalation,
    }
    result["result_sha256"] = _result_digest(result)
    return result


def _evaluate_invalidation(state, operation, transition_id):
    roots = operation["roots"]
    for root in roots:
        if root not in state["active"]:
            _fail("stale", "replacement-root-inactive", "Replacement root is not active", root)
        if operation["replacements"][root] == state["active"][root]["binding"]:
            _fail("stale", "replacement-unchanged", "Replacement binding is unchanged", root)
        if any(
            item["node"] == root
            and item["binding"] == operation["replacements"][root]
            for item in state["history"]
        ):
            _fail(
                "stale",
                "replacement-already-historical",
                "Replacement binding is already in this node's history",
                root,
            )
    if state["budgets"]["reopens"] >= MAX_REOPENS:
        return _escalated_result(
            state,
            operation,
            transition_id,
            {
                "type": "decomposition-required",
                "limit": MAX_REOPENS,
                "used": state["budgets"]["reopens"],
            },
        )
    closure = _descendants(roots)
    invalidated_nodes = [node for node in NODE_ORDER if node in closure and node in state["active"]]
    preserved_nodes = [
        node for node in NODE_ORDER if node in state["active"] and node not in closure
    ]
    active = {node: dict(item) for node, item in state["active"].items() if node not in closure}
    invalidated = []
    history = []
    for item in state["history"]:
        if item["status"] == "active" and item["node"] in closure:
            changed = dict(item)
            changed["status"] = "invalidated"
            changed["transition_id"] = transition_id
            history.append(changed)
            invalidated.append({"node": item["node"], "binding": item["binding"]})
        else:
            history.append(dict(item))
    for root in roots:
        dependencies = {}
        for parent in DEPENDENCIES[root]:
            if parent not in active:
                _fail(
                    "stale",
                    "replacement-dependency-inactive",
                    "Replacement depends on an invalidated node",
                    root,
                )
            dependencies[parent] = active[parent]["binding"]
        active[root] = {
            "node": root,
            "binding": operation["replacements"][root],
            "dependencies": dependencies,
        }
        history.append(
            {
                "node": root,
                "binding": operation["replacements"][root],
                "status": "active",
                "transition_id": transition_id,
            }
        )
    next_state = {
        "format": STATE_FORMAT,
        "issue": state["issue"],
        "family_run_id": state["family_run_id"],
        "generation": state["generation"] + 1,
        "transition_tip": transition_id,
        "transition": operation,
        "active": active,
        "history": history,
        "budgets": {
            "reopens": state["budgets"]["reopens"] + 1,
            "retries": {stage: 0 for stage in CONVERGENCE_STATES},
        },
        "convergence": {
            "episode": state["convergence"]["episode"] + 1,
            "state": "UNKNOWN",
            "evidence": list(state["convergence"]["evidence"]),
        },
    }
    next_document = _state_document(next_state)
    result = {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "evaluated"},
        "input_state_sha256": state["state_sha256"],
        "transition_id": transition_id,
        "operation": operation,
        "changed_roots": list(roots),
        "invalidated": invalidated,
        "preserved": _active_document(
            {node: state["active"][node] for node in preserved_nodes}
        ),
        "next_state": next_document,
        "escalation": None,
    }
    result["result_sha256"] = _result_digest(result)
    return result


def _evaluate_convergence(state, operation, verified, transition_id):
    current = state["convergence"]["state"]
    if operation["from"] != current:
        _fail("stale", "convergence-state-stale", "Convergence source state is stale")
    evidence_digest = operation["evidence"]["sha256"]
    if evidence_digest not in verified:
        _fail("missing", "binding-input-missing", "Convergence evidence input is missing")
    expected_decision_id = _convergence_decision_id(
        state["convergence"]["episode"],
        state["transition_tip"],
        _active_sha256(state["active"]),
    )
    if verified[evidence_digest]["decision"]["id"] != expected_decision_id:
        _fail(
            "stale",
            "convergence-evidence-context-mismatch",
            "Convergence evidence is bound to another episode, tip, or active set",
        )
    if operation["retry"]:
        if operation["to"] != current or current == "CLOSED":
            _fail("corrupt", "invalid-convergence-retry", "Retry must remain in an open state")
        used = state["budgets"]["retries"][current]
        if used >= MAX_RETRIES_PER_STAGE:
            return _escalated_result(
                state,
                operation,
                transition_id,
                {
                    "type": "human-recovery-required",
                    "state": current,
                    "limit": MAX_RETRIES_PER_STAGE,
                    "used": used,
                },
            )
        next_state_name = current
    else:
        current_index = CONVERGENCE_STATES.index(current)
        if current_index + 1 >= len(CONVERGENCE_STATES) or (
            operation["to"] != CONVERGENCE_STATES[current_index + 1]
        ):
            _fail(
                "corrupt",
                "invalid-convergence-transition",
                "Convergence transitions cannot skip or reverse states",
            )
        expected_decision = CONVERGENCE_DECISIONS[operation["to"]]
        if verified[evidence_digest]["decision"]["type"] != expected_decision:
            _fail(
                "stale",
                "convergence-evidence-mismatch",
                "Evidence decision does not support the requested convergence state",
            )
        next_state_name = operation["to"]
    retries = dict(state["budgets"]["retries"])
    if operation["retry"]:
        retries[current] += 1
    else:
        retries[next_state_name] = 0
    next_state = {
        "format": STATE_FORMAT,
        "issue": state["issue"],
        "family_run_id": state["family_run_id"],
        "generation": state["generation"] + 1,
        "transition_tip": transition_id,
        "transition": operation,
        "active": state["active"],
        "history": state["history"],
        "budgets": {"reopens": state["budgets"]["reopens"], "retries": retries},
        "convergence": {
            "episode": state["convergence"]["episode"],
            "state": next_state_name,
            "evidence": state["convergence"]["evidence"]
            + [
                {
                    "episode": state["convergence"]["episode"],
                    "state": next_state_name,
                    "source_tip": state["transition_tip"],
                    "active_sha256": _active_sha256(state["active"]),
                    "binding": operation["evidence"],
                }
            ],
        },
    }
    next_document = _state_document(next_state)
    result = {
        "format": RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "evaluated"},
        "input_state_sha256": state["state_sha256"],
        "transition_id": transition_id,
        "operation": operation,
        "changed_roots": [],
        "invalidated": [],
        "preserved": _active_document(state["active"]),
        "next_state": next_document,
        "escalation": None,
    }
    result["result_sha256"] = _result_digest(result)
    return result


def evaluate(
    root,
    request,
    trusted_state_binding,
    trusted_correction_binding=None,
):
    root = _resolve_root(root)
    if (
        not isinstance(trusted_state_binding, str)
        or SHA256_RE.fullmatch(trusted_state_binding) is None
    ):
        _fail(
            "corrupt",
            "invalid-trusted-state-binding",
            "Trusted state binding must be 64 lowercase hex",
        )
    _exact_keys(
        request,
        {
            "format",
            "issue",
            "family_run_id",
            "state",
            "expected_state_sha256",
            "bindings",
            "authority_chain",
            "operation",
        },
        "policy-request",
    )
    if request["format"] != REQUEST_FORMAT:
        _fail("unsupported", "unsupported-request-format", "Policy request format is unsupported")
    if (
        not _exact_int(request["issue"])
        or request["issue"] < 1
        or not isinstance(request["family_run_id"], str)
        or RUN_ID_RE.fullmatch(request["family_run_id"]) is None
        or not isinstance(request["expected_state_sha256"], str)
        or SHA256_RE.fullmatch(request["expected_state_sha256"]) is None
    ):
        _fail("corrupt", "invalid-request-identity", "Policy request identity is invalid")
    state = _validate_state(
        request["state"],
        request["issue"],
        request["family_run_id"],
        request["expected_state_sha256"],
    )
    operation = _validate_operation(request["operation"])
    verified = _verify_binding_inputs(
        root,
        request["bindings"],
        request["issue"],
        request["family_run_id"],
    )
    authority_chain = _validate_authority_chain(
        request["authority_chain"],
        state,
        verified,
        request["issue"],
        request["family_run_id"],
    )
    if authority_chain[-1]["binding"]["sha256"] != trusted_state_binding:
        _fail(
            "stale",
            "trusted-state-binding-mismatch",
            "Policy authority chain does not end at the trusted state binding",
        )
    required = _required_binding_digests(
        state, request["operation"], authority_chain
    )
    if set(verified) != required:
        status = "missing" if required - set(verified) else "ambiguous"
        code = "binding-input-missing" if status == "missing" else "unreferenced-binding-input"
        _fail(status, code, "Binding inputs do not exactly cover policy evidence")
    _verify_node_bindings(state, operation, verified)
    _validate_correction_lineage(
        state, operation, verified, trusted_correction_binding
    )
    transition_id = _transition_id(state, operation)
    if operation["type"] == "convergence":
        return _evaluate_convergence(state, operation, verified, transition_id)
    return _evaluate_invalidation(state, operation, transition_id)


def _load_json(path):
    try:
        value = json.loads(pathlib.Path(path).read_bytes())
    except FileNotFoundError:
        _fail("missing", "request-missing", "Policy request file is missing")
    except (OSError, UnicodeError) as error:
        _fail("missing", "request-unreadable", "Cannot read policy request: %s" % error)
    except (json.JSONDecodeError, ValueError, RecursionError) as error:
        _fail("corrupt", "invalid-request-json", "Policy request is invalid JSON: %s" % error)
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-request-type", "Policy request must be a JSON object")
    return value


class PolicyArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = PolicyArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    evaluate_parser = commands.add_parser("evaluate")
    evaluate_parser.add_argument("--root", default=".")
    evaluate_parser.add_argument("--request", required=True)
    evaluate_parser.add_argument("--trusted-state-binding", required=True)
    evaluate_parser.add_argument("--trusted-correction-binding")
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        document = evaluate(
            args.root,
            _load_json(args.request),
            args.trusted_state_binding,
            args.trusted_correction_binding,
        )
        exit_code = 0
    except PolicyFailure as failure:
        document = failure.document()
        exit_code = OUTCOME_EXIT_CODES[failure.status]
    except (
        evidence.EvidenceFailure,
        inspector.InspectionFailure,
        migration.MigrationFailure,
    ) as failure:
        wrapped = PolicyFailure(
            failure.status, failure.code, failure.message, failure.subject
        )
        document = wrapped.document()
        exit_code = OUTCOME_EXIT_CODES[wrapped.status]
    try:
        output = inspector.canonical_document(document)
    except (inspector.InspectionFailure, TypeError, ValueError) as failure:
        wrapped = PolicyFailure(
            "corrupt",
            "invalid-canonical-json",
            "Policy outcome cannot be represented as canonical JSON: %s" % failure,
        )
        output = inspector.canonical_document(wrapped.document())
        exit_code = OUTCOME_EXIT_CODES[wrapped.status]
    sys.stdout.buffer.write(output)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
