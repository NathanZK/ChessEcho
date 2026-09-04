#!/usr/bin/env python3
"""Inactive, read-only incremental plan-revision policy (issue #125).

This module verifies native #132 evidence bindings for a plan snapshot, an
optional prior technical review, a revised plan snapshot, an exact unified
diff, finding dispositions, and an optional current technical review. It
deterministically selects the minimum permitted review mode (full or
incremental), verifies required review coverage, and emits a derived policy
result.

It never publishes evidence, selects a trust anchor, authenticates an actor,
preserves human approval, mutates #134 state, transitions a lifecycle,
invokes an agent, or activates any behavior. Future #144 owns those
orchestration and activation responsibilities; see
docs/engineering/workflow-plan-revisions.md for the full contract.
"""

import argparse
import collections
import copy
import difflib
import functools
import json
import pathlib
import re
import sys

try:
    import workflow_evidence as evidence
    import workflow_inspector as inspector
except ModuleNotFoundError:
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector


POLICY_VERSION = "1.0.0"

FAILURE_FORMAT = "chess-echo-plan-revision-policy-failure-v1"
SNAPSHOT_FORMAT = "chess-echo-plan-snapshot-v1"
REVIEW_FORMAT = "chess-echo-plan-review-v1"
REVISION_FORMAT = "chess-echo-plan-revision-v1"
DIFF_FORMAT = "chess-echo-unified-plan-diff-v1"
BASELINE_REQUEST_FORMAT = "chess-echo-plan-baseline-policy-request-v1"
REVISION_REQUEST_FORMAT = "chess-echo-plan-revision-policy-request-v1"
RESULT_FORMAT = "chess-echo-plan-revision-policy-result-v1"

PLAN_PATH = "workflow-plan-revision/plan.md"
SNAPSHOT_PATH = "workflow-plan-revision/snapshot.json"
REVIEW_PATH = "workflow-plan-revision/review.json"
REVISION_PATH = "workflow-plan-revision/revision.json"
DIFF_PATH = "workflow-plan-revision/plan.diff"

OUTCOME_EXIT_CODES = {
    "resolved": 0,
    "missing": 3,
    "unsupported": 4,
    "corrupt": 5,
    "ambiguous": 6,
    "stale": 7,
    "denied": 8,
}

SHA256_RE = re.compile(r"[0-9a-f]{64}")
RUN_ID_RE = re.compile(r"[0-9a-f]{32}")
SLUG_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
FINDING_ID_RE = re.compile(r"finding-[0-9a-f]{64}")

MAX_REQUEST_BYTES = 8 * 1024 * 1024
MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
MAX_PLAN_BYTES = 1024 * 1024
MAX_PLAN_LINES = 5_000
MAX_DIFF_BYTES = 4 * 1024 * 1024
MAX_DIFF_COST = 5_000_000
MAX_UNITS = 1_000
MAX_FINDINGS = 1_000
MAX_DISPOSITIONS = 1_000
MAX_COVERAGE = 1_000
MAX_PRESERVED_SOURCES = 32
MAX_DEPENDENCIES = 10
MAX_TITLE_BYTES = 4_096
MAX_DETAIL_BYTES = 64 * 1024
MAX_ACTOR_BYTES = 256

REVIEW_CLASSES = (
    "ordinary",
    "scope",
    "acceptance-criteria",
    "architecture",
    "source-baseline",
)
SENSITIVE_REVIEW_CLASSES = frozenset(REVIEW_CLASSES) - {"ordinary"}
SEVERITIES = ("info", "warning", "blocking")
SEVERITY_ORDER = {value: index for index, value in enumerate(SEVERITIES)}
REVIEW_MODES = ("full", "incremental")
COVERAGE_METHODS = ("full", "incremental", "preserved")
DEPENDENCY_STATUSES = ("complete", "bounded", "unbounded")
VERDICTS = ("accepted", "needs-revision", "full-review-required")
PRIOR_OUTCOME_STATUSES = ("resolved", "remains", "superseded")
DISPOSITION_STATUSES = ("addressed", "disputed", "deferred")
IMPACTS = ("local", "full-review-required")

ESCALATION_REASONS = frozenset(
    {
        "accepted-prior-review",
        "changed-context-binding",
        "dependency-graph-changed",
        "diff-unit-mapping-mismatch",
        "planner-declared-full-review",
        "preservation-fanout-exceeded",
        "prior-review-escalated",
        "sensitive-unit-changed",
        "unit-metadata-changed",
        "unit-order-changed",
        "unit-set-changed",
    }
)

ACTIVATION_UNVERIFIED = (
    "actor-authentication",
    "human-approval",
    "latest-tip",
    "revocation",
    "replay-prevention",
    "temporal-freshness",
    "authoritative-publication",
    "lifecycle-transition",
)

# Golden vectors that must reproduce byte-for-byte on every runtime the CI
# matrix supports. The self-check is lazy and cached per process so an
# incompatible runtime reports a typed failure instead of failing at import.
_DIFF_GOLDEN_VECTORS = (
    (
        ["a\n", "b\n", "c\n"],
        ["a\n", "x\n", "c\n"],
        b"--- a/plan.md\n"
        b"+++ b/plan.md\n"
        b"@@ -1,3 +1,3 @@\n"
        b" a\n"
        b"-b\n"
        b"+x\n"
        b" c\n",
    ),
    (
        ["a\n", "b\n"],
        ["a\n", "b\n", "c\n"],
        b"--- a/plan.md\n"
        b"+++ b/plan.md\n"
        b"@@ -1,2 +1,3 @@\n"
        b" a\n"
        b" b\n"
        b"+c\n",
    ),
    (
        ["a\n", "b\n", "c\n"],
        ["a\n", "c\n"],
        b"--- a/plan.md\n"
        b"+++ b/plan.md\n"
        b"@@ -1,3 +1,2 @@\n"
        b" a\n"
        b"-b\n"
        b" c\n",
    ),
)
_diff_self_check_done = False


class PlanRevisionPolicyFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.subject = subject

    def document(self):
        return {
            "format": FAILURE_FORMAT,
            "outcome": {
                "status": self.status,
                "code": self.code,
                "message": self.message,
                "subject": self.subject,
            },
        }


def _fail(status, code, message, subject=None):
    raise PlanRevisionPolicyFailure(status, code, message, subject)


def _translate_input_errors(function):
    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except PlanRevisionPolicyFailure:
            raise
        except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
            _fail(failure.status, failure.code, failure.message, failure.subject)
        except (AttributeError, IndexError, KeyError, TypeError) as failure:
            _fail(
                "corrupt",
                "invalid-policy-input-type",
                "Policy input has an invalid nested type: %s" % type(failure).__name__,
            )

    return wrapped


# ---------------------------------------------------------------------------
# Generic canonical/schema helpers
# ---------------------------------------------------------------------------


def _exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("corrupt", "invalid-%s-schema" % label, "%s schema is invalid" % label)


def _exact_int(value):
    return type(value) is int


def _canonical_bytes(value, limit=MAX_DOCUMENT_BYTES, code="structured-document-too-large"):
    try:
        data = inspector.canonical_bytes(value)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except (TypeError, ValueError) as failure:
        _fail("corrupt", "invalid-canonical-json", str(failure))
    if len(data) > limit:
        _fail("unsupported", code, "Document exceeds its size limit")
    return data


def _digest(value, limit=MAX_DOCUMENT_BYTES, code="structured-document-too-large"):
    return inspector.sha256(_canonical_bytes(value, limit, code))


def _verify_digest(value, field, label, limit=MAX_DOCUMENT_BYTES, code=None):
    digest = value.get(field) if isinstance(value, dict) else None
    if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
        _fail("corrupt", "invalid-%s-digest" % label, "%s digest is invalid" % label)
    unsigned = dict(value)
    unsigned.pop(field, None)
    if _digest(unsigned, limit, code or ("%s-too-large" % label)) != digest:
        _fail("corrupt", "digest-mismatch", "%s digest is stale" % label)
    return digest


def _text(value, label, minimum=1, maximum=MAX_DETAIL_BYTES, trimmed=True):
    if not isinstance(value, str) or "\0" in value:
        _fail("corrupt", "invalid-%s" % label, "%s must be UTF-8 text" % label)
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        _fail("corrupt", "invalid-%s" % label, "%s is not valid UTF-8" % label)
    if size < minimum or size > maximum or (trimmed and value != value.strip()):
        _fail("corrupt", "invalid-%s" % label, "%s is outside its limits" % label)
    if any(ord(character) < 32 and character not in "\n\r\t" for character in value):
        _fail("corrupt", "invalid-%s" % label, "%s contains control characters" % label)
    return value


def _slug(value, label="slug"):
    if not isinstance(value, str) or SLUG_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-%s" % label, "%s is not a safe slug" % label)
    return value


def _enum(value, choices, label, status="unsupported"):
    if not isinstance(value, str):
        _fail("corrupt", "invalid-%s" % label, "%s must be a string" % label)
    if value not in choices:
        _fail(status, "%s-%s" % (status, label), "%s is unsupported" % label)
    return value


def _sha256(value, label="sha256"):
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-%s" % label, "%s is not 64 lowercase hex" % label)
    return value


def _run_id(value, label="family-run-id"):
    if not isinstance(value, str) or RUN_ID_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-%s" % label, "%s is invalid" % label)
    return value


def _issue(value):
    if not _exact_int(value) or value < 1 or value > 2 ** 63 - 1:
        _fail("corrupt", "invalid-issue", "Issue must be a positive exact integer")
    return value


def _uint(value, label, minimum=0, maximum=2 ** 63 - 1):
    if not _exact_int(value) or value < minimum or value > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s must be a bounded exact integer" % label)
    return value


def _reference(value, expected_kind=None):
    try:
        normalized = inspector.validate_reference(value, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if set(value) != set(normalized):
        _fail("corrupt", "invalid-object-reference-schema", "Object reference has extra fields")
    return normalized


def _reference_or_none(value, expected_kind=None):
    if value is None:
        return None
    return _reference(value, expected_kind)


def _ordered_unique(values, key, label, maximum, sort=True):
    if not isinstance(values, list) or len(values) > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s is not a bounded list" % label)
    keys = [key(item) for item in values]
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "duplicate-%s" % label, "%s contains duplicates" % label)
    if sort and keys != sorted(keys):
        _fail("corrupt", "noncanonical-%s-order" % label, "%s is not canonically ordered" % label)
    return values


def _slug_list(values, label, maximum):
    if not isinstance(values, list) or not values or len(values) > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s must be a nonempty bounded list" % label)
    for item in values:
        _slug(item, label)
    if len(set(values)) != len(values) or list(values) != sorted(values):
        _fail("corrupt", "invalid-%s-order" % label, "%s must be UTF-8 sorted and unique" % label)
    return values


def _plan_ordered_subset(values, order_index, label, maximum, allow_empty=True):
    if not isinstance(values, list) or len(values) > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s must be a bounded list" % label)
    if not values and not allow_empty:
        _fail("corrupt", "invalid-%s" % label, "%s must be nonempty" % label)
    if len(set(values)) != len(values):
        _fail("ambiguous", "duplicate-%s" % label, "%s contains duplicates" % label)
    positions = []
    for item in values:
        if item not in order_index:
            _fail("corrupt", "invalid-%s" % label, "%s references an unknown unit" % label)
        positions.append(order_index[item])
    if positions != sorted(positions):
        _fail("corrupt", "noncanonical-%s-order" % label, "%s is not in plan-unit order" % label)
    return values


# ---------------------------------------------------------------------------
# Root resolution and request loading
# ---------------------------------------------------------------------------


def _resolve_root(root):
    try:
        resolved = pathlib.Path(root).resolve()
    except (TypeError, ValueError) as failure:
        _fail("corrupt", "invalid-policy-root", "Policy root is invalid: %s" % failure)
    except (OSError, RuntimeError) as failure:
        _fail("missing", "policy-root-unreadable", "Policy root cannot be resolved: %s" % failure)
    if not resolved.is_dir():
        _fail("missing", "policy-root-unreadable", "Policy root is not a directory")
    try:
        store = inspector.resolve_store(resolved)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if not store.store_dir.is_dir():
        _fail("missing", "evidence-store-missing", "Workflow evidence store does not exist")
    return resolved


def _reject_duplicate_keys(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            _fail("ambiguous", "duplicate-json-key", "JSON object contains duplicate keys", key)
        value[key] = item
    return value


def _load_json(path):
    request_path = pathlib.Path(path)
    try:
        if request_path.stat().st_size > MAX_REQUEST_BYTES:
            _fail("unsupported", "request-too-large", "Request exceeds 8 MiB")
        data = bytearray()
        with request_path.open("rb") as stream:
            while len(data) <= MAX_REQUEST_BYTES:
                chunk = stream.read(min(64 * 1024, MAX_REQUEST_BYTES + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
    except FileNotFoundError:
        _fail("missing", "request-missing", "Request file is missing")
    except OSError as failure:
        _fail("missing", "request-unreadable", "Cannot read request: %s" % failure)
    if len(data) > MAX_REQUEST_BYTES:
        _fail("unsupported", "request-too-large", "Request exceeds 8 MiB")
    try:
        value = json.loads(bytes(data), object_pairs_hook=_reject_duplicate_keys)
    except PlanRevisionPolicyFailure:
        raise
    except (UnicodeError, json.JSONDecodeError, ValueError, RecursionError) as failure:
        _fail("corrupt", "invalid-request-json", "Request is invalid JSON: %s" % failure)
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-request-type", "Request must be a JSON object")
    return value


# ---------------------------------------------------------------------------
# Plan payload and unit-map validation
# ---------------------------------------------------------------------------


def _validate_plan_bytes(data):
    if len(data) > MAX_PLAN_BYTES:
        _fail("unsupported", "plan-too-large", "Plan payload exceeds 1 MiB")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        _fail("unsupported", "unsupported-plan-encoding", "Plan payload is not valid UTF-8")
    if "\0" in text or "\r" in text:
        _fail("unsupported", "unsupported-plan-encoding", "Plan payload must use LF-only text")
    if not text.endswith("\n") or text.endswith("\n\n"):
        _fail("unsupported", "unsupported-plan-encoding", "Plan payload must end in exactly one LF")
    lines = text[:-1].split("\n")
    if len(lines) > MAX_PLAN_LINES:
        _fail("unsupported", "plan-too-many-lines", "Plan payload exceeds 5,000 lines")
    return [line + "\n" for line in lines]


def _validate_units_schema(units_raw):
    if not isinstance(units_raw, list) or not units_raw or len(units_raw) > MAX_UNITS:
        _fail("corrupt", "invalid-snapshot-schema", "Units must be a nonempty bounded list")
    ids = []
    parsed = []
    for unit in units_raw:
        _exact_keys(
            unit,
            {"id", "title", "start_line", "end_line", "content_sha256", "review_class", "dependencies"},
            "plan-unit",
        )
        unit_id = _slug(unit["id"], "unit-id")
        ids.append(unit_id)
        _text(unit["title"], "unit-title", maximum=MAX_TITLE_BYTES)
        _uint(unit["start_line"], "unit-start-line", minimum=1)
        _uint(unit["end_line"], "unit-end-line", minimum=1)
        if unit["end_line"] < unit["start_line"]:
            _fail("corrupt", "invalid-snapshot-schema", "Unit end line precedes its start line")
        _sha256(unit["content_sha256"], "unit-content-hash")
        _enum(unit["review_class"], REVIEW_CLASSES, "review-class")
        deps = unit["dependencies"]
        if not isinstance(deps, list) or len(deps) > MAX_DEPENDENCIES:
            _fail("corrupt", "invalid-snapshot-schema", "Unit dependencies must be a bounded list")
        for dependency in deps:
            _slug(dependency, "unit-dependency")
        if len(set(deps)) != len(deps) or list(deps) != sorted(deps):
            _fail("corrupt", "invalid-snapshot-schema", "Unit dependencies must be sorted and unique")
        if unit_id in deps:
            _fail("corrupt", "invalid-snapshot-schema", "Unit cannot depend on itself")
        parsed.append(dict(unit))
    if len(set(ids)) != len(ids):
        _fail("ambiguous", "duplicate-unit", "Plan units contain duplicate unit IDs")
    id_set = set(ids)
    for unit in parsed:
        for dependency in unit["dependencies"]:
            if dependency not in id_set:
                _fail("corrupt", "invalid-snapshot-schema", "Unit dependency references an unknown unit")
    # Contiguous, nonoverlapping, exhaustive tiling in array order.
    previous_end = 0
    for unit in parsed:
        if unit["start_line"] <= previous_end:
            _fail("ambiguous", "overlapping-unit", "Plan units overlap")
        if unit["start_line"] > previous_end + 1:
            _fail("corrupt", "invalid-snapshot-schema", "Plan units leave a coverage gap")
        previous_end = unit["end_line"]
    # Acyclic dependency graph without recursion so the 1,000-unit limit is safe.
    graph = {unit["id"]: unit["dependencies"] for unit in parsed}
    remaining = {unit_id: len(graph[unit_id]) for unit_id in ids}
    dependents = {unit_id: [] for unit_id in ids}
    for unit_id, dependencies in graph.items():
        for dependency in dependencies:
            dependents[dependency].append(unit_id)
    ready = collections.deque(unit_id for unit_id in ids if remaining[unit_id] == 0)
    visited = 0
    while ready:
        unit_id = ready.popleft()
        visited += 1
        for dependent in dependents[unit_id]:
            remaining[dependent] -= 1
            if remaining[dependent] == 0:
                ready.append(dependent)
    if visited != len(ids):
        _fail("ambiguous", "dependency-cycle", "Unit dependency graph contains a cycle")
    return parsed, ids, previous_end


def _verify_units_against_plan(units, total_lines, plan_lines):
    if total_lines != len(plan_lines):
        _fail("stale", "unit-coverage-mismatch", "Plan units do not cover every plan line exactly once")
    for unit in units:
        content = "".join(plan_lines[unit["start_line"] - 1 : unit["end_line"]])
        if inspector.sha256(content.encode("utf-8")) != unit["content_sha256"]:
            _fail("stale", "unit-content-hash-mismatch", "Unit content hash does not match the plan bytes")


class UnitMap(object):
    """Convenience view over a validated snapshot's unit tiling."""

    def __init__(self, units):
        self.units = units
        self.order = [unit["id"] for unit in units]
        self.index = {unit_id: position for position, unit_id in enumerate(self.order)}
        self.by_id = {unit["id"]: unit for unit in units}
        self.hash_by_id = {unit["id"]: unit["content_sha256"] for unit in units}
        self.title_by_id = {unit["id"]: unit["title"] for unit in units}
        self.class_by_id = {unit["id"]: unit["review_class"] for unit in units}
        self.dependencies_by_id = {unit["id"]: tuple(unit["dependencies"]) for unit in units}
        self.dependents_by_id = {unit_id: set() for unit_id in self.order}
        for unit in units:
            for dependency in unit["dependencies"]:
                self.dependents_by_id[dependency].add(unit["id"])

    def line_owner(self, plan_lines):
        owners = [None] * len(plan_lines)
        for unit in self.units:
            for line_index in range(unit["start_line"] - 1, unit["end_line"]):
                owners[line_index] = unit["id"]
        if any(owner is None for owner in owners):
            _fail("ambiguous", "diff-unit-ambiguity", "Plan lines are not fully tiled by units")
        return owners


# ---------------------------------------------------------------------------
# Native #132 evidence binding verification
# ---------------------------------------------------------------------------


def _trusted_digest(reference, digest, label):
    _sha256(digest, label)
    if reference["sha256"] != digest:
        _fail("stale", "designated-binding-mismatch", "Request binding differs from the designated digest")


def _project(root, reference):
    try:
        return evidence.project(root, reference)
    except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)


def _check_identity(projection, expected_issue, expected_family=None):
    identity = projection["identity"]
    if identity["issue"] != expected_issue or identity["correction"] is not None:
        _fail("stale", "binding-identity-mismatch", "Evidence belongs to another issue or correction run")
    if expected_family is not None and identity["family_run_id"] != expected_family:
        _fail("stale", "binding-family-mismatch", "Evidence belongs to another family run")
    if projection["lineage"] != {"status": "original", "parent_binding": None}:
        _fail("stale", "binding-lineage-mismatch", "V1 evidence must have original lineage")
    if projection["migration"] is not None:
        _fail("unsupported", "migration-unsupported", "Migrated V1 plan-revision evidence is unsupported")
    return identity


def _single_entry(projection, expected_path):
    entries = projection["entries"]
    matches = [entry for entry in entries if entry["path"] == expected_path]
    if len(matches) != 1 or len(entries) != 1:
        _fail("ambiguous", "unexpected-document-entry", "Evidence contains unrelated or duplicated entries")
    entry = matches[0]
    if entry["kind"] != "regular" or entry["mode"] != "100644":
        _fail("stale", "document-record-entry-mismatch", "Evidence record entry is not a regular file")
    return entry


def _named_entries(projection, expected_paths):
    entries = projection["entries"]
    if len(entries) != len(expected_paths):
        _fail("ambiguous", "unexpected-document-entry", "Evidence contains unrelated or duplicated entries")
    by_path = {}
    for entry in entries:
        if entry["path"] not in expected_paths or entry["path"] in by_path:
            _fail("ambiguous", "unexpected-document-entry", "Evidence contains unrelated or duplicated entries")
        if entry["kind"] != "regular" or entry["mode"] != "100644":
            _fail("stale", "document-record-entry-mismatch", "Evidence record entry is not a regular file")
        by_path[entry["path"]] = entry
    return by_path


def _read_payload(root, expected_issue, entry, expected_data=None):
    try:
        reader = inspector.AuthorityReader(inspector.resolve_store(root), expected_issue)
        data = reader.read_bytes(entry["payload"], "evidence-payload")
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if entry["content_sha256"] != inspector.sha256(data) or entry["size"] != len(data):
        _fail("stale", "document-record-entry-mismatch", "Evidence record does not bind its stored payload")
    if expected_data is not None and data != expected_data:
        _fail("corrupt", "inline-document-payload-mismatch", "Inline document differs from the stored payload")
    return data


def _verify_binding_shape(root, reference, expected_decision_type, expected_subject, expected_issue, expected_family):
    """Light generic #132 identity/decision/subject check used for the #116 context chain."""
    projection = _project(root, reference)
    _check_identity(projection, expected_issue, expected_family)
    if projection["decision"]["type"] != expected_decision_type:
        _fail("stale", "binding-decision-mismatch", "Evidence decision type is incompatible")
    if expected_subject is not None and projection["subject"] != expected_subject:
        _fail("stale", "binding-subject-mismatch", "Evidence subject is incompatible")
    return projection


def _verify_context_chain(root, context, expected_issue, expected_family):
    """Verify the generic #132 issue-snapshot -> baseline -> triage subject chain
    without importing #116 or interpreting its classification semantics."""
    _exact_keys(context, {"issue_snapshot_binding", "baseline_binding", "triage_binding"}, "snapshot-context")
    issue_snapshot_ref = _reference(context["issue_snapshot_binding"], "evidence-binding")
    baseline_ref = _reference(context["baseline_binding"], "evidence-binding")
    triage_ref = _reference(context["triage_binding"], "evidence-binding")
    _verify_binding_shape(root, issue_snapshot_ref, "work-type-issue-snapshot", None, expected_issue, expected_family)
    _verify_binding_shape(root, baseline_ref, "work-type-baseline", issue_snapshot_ref, expected_issue, expected_family)
    _verify_binding_shape(root, triage_ref, "work-type-triage", baseline_ref, expected_issue, expected_family)
    return {
        "issue_snapshot_binding": issue_snapshot_ref,
        "baseline_binding": baseline_ref,
        "triage_binding": triage_ref,
    }


def _validate_predecessor_shape(value):
    if value is None:
        return None
    _exact_keys(value, {"plan_binding", "review_binding"}, "predecessor")
    return {
        "plan_binding": _reference(value["plan_binding"], "evidence-binding"),
        "review_binding": _reference(value["review_binding"], "evidence-binding"),
    }


def _validate_snapshot_schema(document):
    _exact_keys(
        document,
        {"format", "issue", "family_run_id", "revision", "context", "predecessor", "plan", "units", "snapshot_sha256"},
        "plan-snapshot",
    )
    if document["format"] != SNAPSHOT_FORMAT:
        _fail("unsupported", "unsupported-plan-snapshot-format", "Plan snapshot format is unsupported")
    _issue(document["issue"])
    _run_id(document["family_run_id"])
    _uint(document["revision"], "revision", minimum=1)
    context = document["context"]
    _exact_keys(context, {"issue_snapshot_binding", "baseline_binding", "triage_binding"}, "snapshot-context")
    for key in context:
        _reference(context[key], "evidence-binding")
    predecessor = _validate_predecessor_shape(document["predecessor"])
    if document["revision"] == 1 and predecessor is not None:
        _fail("corrupt", "invalid-snapshot-schema", "Revision 1 must not declare a predecessor")
    if document["revision"] > 1 and predecessor is None:
        _fail("corrupt", "invalid-snapshot-schema", "Revision greater than 1 requires a predecessor")
    plan_ref = document["plan"]
    _exact_keys(plan_ref, {"path", "content_sha256", "size"}, "snapshot-plan-reference")
    if plan_ref["path"] != PLAN_PATH:
        _fail("corrupt", "invalid-snapshot-schema", "Snapshot plan path is not the fixed plan path")
    _sha256(plan_ref["content_sha256"], "plan-content-hash")
    _uint(plan_ref["size"], "plan-size")
    units, ids, total_lines = _validate_units_schema(document["units"])
    _verify_digest(document, "snapshot_sha256", "snapshot", MAX_DOCUMENT_BYTES, "plan-snapshot-too-large")
    return document, UnitMap(units), total_lines


def _project_snapshot(root, envelope, expected_designated_digest, label):
    _exact_keys(envelope, {"binding", "document"}, "%s-envelope" % label)
    document, unit_map, total_lines = _validate_snapshot_schema(envelope["document"])
    reference = _reference(envelope["binding"], "evidence-binding")
    if expected_designated_digest is not None:
        _trusted_digest(reference, expected_designated_digest, label)
    projection = _project(root, reference)
    identity = _check_identity(projection, document["issue"], document["family_run_id"])
    if projection["decision"] != {"type": "plan-snapshot", "id": "snapshot-%s" % document["snapshot_sha256"]}:
        _fail("stale", "binding-decision-mismatch", "Plan snapshot decision is incompatible")
    context = _verify_context_chain(root, document["context"], document["issue"], document["family_run_id"])
    if projection["subject"] != context["triage_binding"]:
        _fail("stale", "binding-subject-mismatch", "Plan snapshot subject must equal the exact triage binding")
    entries = _named_entries(projection, {PLAN_PATH, SNAPSHOT_PATH})
    document_data = _canonical_bytes(document, MAX_DOCUMENT_BYTES, "plan-snapshot-too-large")
    snapshot_entry = entries[SNAPSHOT_PATH]
    if snapshot_entry["content_sha256"] != inspector.sha256(document_data) or snapshot_entry["size"] != len(document_data):
        _fail("stale", "document-record-entry-mismatch", "Snapshot entry does not bind the snapshot document")
    _read_payload(root, document["issue"], snapshot_entry, document_data)
    plan_entry = entries[PLAN_PATH]
    if (
        plan_entry["content_sha256"] != document["plan"]["content_sha256"]
        or plan_entry["size"] != document["plan"]["size"]
    ):
        _fail("stale", "document-record-entry-mismatch", "Plan entry does not match the declared plan reference")
    plan_data = _read_payload(root, document["issue"], plan_entry)
    plan_lines = _validate_plan_bytes(plan_data)
    _verify_units_against_plan(unit_map.units, total_lines, plan_lines)
    unit_map.reference_binding = reference
    return {
        "reference": reference,
        "document": document,
        "identity": identity,
        "units": unit_map,
        "plan_lines": plan_lines,
    }


# ---------------------------------------------------------------------------
# Plan review schema
# ---------------------------------------------------------------------------


def _finding_row_identity(row):
    return {
        "introduced_plan_binding": row["introduced_plan_binding"],
        "severity": row["severity"],
        "category": row["category"],
        "unit_ids": row["unit_ids"],
        "detail": row["detail"],
    }


def _validate_finding(value, unit_ids):
    _exact_keys(
        value,
        {"id", "introduced_plan_binding", "severity", "category", "unit_ids", "detail"},
        "finding",
    )
    if not isinstance(value["id"], str) or FINDING_ID_RE.fullmatch(value["id"]) is None:
        _fail("corrupt", "invalid-finding-id", "Finding ID is not the expected format")
    row = dict(value)
    row["introduced_plan_binding"] = _reference(value["introduced_plan_binding"], "evidence-binding")
    _enum(row["severity"], SEVERITIES, "finding-severity")
    _slug(row["category"], "finding-category")
    _slug_list(row["unit_ids"], "finding-unit-ids", MAX_UNITS)
    for unit_id in row["unit_ids"]:
        if unit_id not in unit_ids:
            _fail("corrupt", "invalid-review-schema", "Finding cites an unknown plan unit")
    _text(row["detail"], "finding-detail", maximum=MAX_DETAIL_BYTES)
    expected_id = "finding-%s" % _digest(_finding_row_identity(row))
    if row["id"] != expected_id:
        _fail("corrupt", "finding-id-mismatch", "Finding ID does not match its identity fields")
    return row


def _validate_findings(values, unit_ids):
    if not isinstance(values, list) or len(values) > MAX_FINDINGS:
        _fail("corrupt", "invalid-review-schema", "Findings must be a bounded list")
    rows = [_validate_finding(item, unit_ids) for item in values]
    _ordered_unique(rows, lambda row: row["id"], "finding", MAX_FINDINGS)
    return rows


def _validate_prior_finding_outcome(value):
    _exact_keys(value, {"finding_id", "status", "replacement_finding_id", "reason"}, "prior-finding-outcome")
    if not isinstance(value["finding_id"], str) or FINDING_ID_RE.fullmatch(value["finding_id"]) is None:
        _fail("corrupt", "invalid-review-schema", "Prior finding outcome ID is malformed")
    _enum(value["status"], PRIOR_OUTCOME_STATUSES, "prior-finding-outcome-status")
    replacement = value["replacement_finding_id"]
    if replacement is not None and (
        not isinstance(replacement, str) or FINDING_ID_RE.fullmatch(replacement) is None
    ):
        _fail("corrupt", "invalid-review-schema", "Replacement finding ID is malformed")
    if value["status"] in ("resolved", "remains") and replacement is not None:
        _fail("corrupt", "invalid-review-schema", "Only a superseded outcome names a replacement finding")
    if value["status"] == "superseded" and replacement is None:
        _fail("corrupt", "invalid-review-schema", "A superseded outcome must name a replacement finding")
    _text(value["reason"], "prior-finding-outcome-reason", maximum=MAX_DETAIL_BYTES)
    return dict(value)


def _validate_prior_finding_outcomes(values):
    if not isinstance(values, list) or len(values) > MAX_FINDINGS:
        _fail("corrupt", "invalid-review-schema", "Prior finding outcomes must be a bounded list")
    rows = [_validate_prior_finding_outcome(item) for item in values]
    _ordered_unique(rows, lambda row: row["finding_id"], "prior-finding-outcome", MAX_FINDINGS)
    return rows


def _validate_coverage_row(value):
    _exact_keys(value, {"unit_id", "content_sha256", "method", "source_review_binding"}, "coverage-row")
    row = dict(value)
    _slug(row["unit_id"], "coverage-unit-id")
    _sha256(row["content_sha256"], "coverage-content-hash")
    _enum(row["method"], COVERAGE_METHODS, "coverage-method")
    source = row["source_review_binding"]
    if row["method"] == "preserved":
        row["source_review_binding"] = _reference(source, "evidence-binding")
    elif source is not None:
        _fail("corrupt", "invalid-review-schema", "Only a preserved coverage row names a source review")
    return row


def _validate_coverage(values, unit_map):
    if not isinstance(values, list) or len(values) > MAX_COVERAGE:
        _fail("corrupt", "invalid-review-schema", "Coverage must be a bounded list")
    rows = [_validate_coverage_row(item) for item in values]
    for row in rows:
        if row["unit_id"] not in unit_map.hash_by_id:
            _fail("corrupt", "invalid-review-schema", "Coverage row references an unknown plan unit")
        if row["content_sha256"] != unit_map.hash_by_id[row["unit_id"]]:
            _fail("corrupt", "invalid-review-schema", "Coverage row hash is stale or foreign")
    _ordered_unique(rows, lambda row: unit_map.index[row["unit_id"]], "coverage-row", MAX_COVERAGE, sort=True)
    by_id = {}
    for row in rows:
        by_id[row["unit_id"]] = row
    return rows, by_id


def _validate_dependency_assessment(value, unit_map):
    _exact_keys(value, {"status", "reviewed_units", "reason"}, "dependency-assessment")
    _enum(value["status"], DEPENDENCY_STATUSES, "dependency-assessment-status")
    reviewed = _plan_ordered_subset(
        value["reviewed_units"], unit_map.index, "dependency-assessment-reviewed-units", MAX_UNITS,
        allow_empty=(value["status"] != "unbounded"),
    )
    _text(value["reason"], "dependency-assessment-reason", maximum=MAX_DETAIL_BYTES)
    return {"status": value["status"], "reviewed_units": list(reviewed), "reason": value["reason"]}


def _validate_review_schema(document, unit_map):
    review_keys = {
        "format", "issue", "family_run_id", "plan_binding", "revision_binding", "mode", "reviewer",
        "coverage", "prior_finding_outcomes", "findings", "dependency_assessment", "verdict",
        "full_review_reason", "review_sha256",
    }
    if not isinstance(document, dict) or set(document) != review_keys:
        _fail("corrupt", "invalid-review-schema", "Plan review schema is invalid")
    if document["format"] != REVIEW_FORMAT:
        _fail("unsupported", "unsupported-plan-review-format", "Plan review format is unsupported")
    _issue(document["issue"])
    _run_id(document["family_run_id"])
    plan_binding = _reference(document["plan_binding"], "evidence-binding")
    revision_binding = _reference_or_none(document["revision_binding"], "evidence-binding")
    _enum(document["mode"], REVIEW_MODES, "review-mode")
    reviewer = document["reviewer"]
    _exact_keys(reviewer, {"role", "actor"}, "reviewer")
    if reviewer["role"] != "independent-reviewer":
        _fail("unsupported", "unsupported-reviewer-role", "Reviewer role is unsupported")
    _text(reviewer["actor"], "reviewer-actor", maximum=MAX_ACTOR_BYTES)
    coverage_rows, coverage_by_id = _validate_coverage(document["coverage"], unit_map)
    prior_outcomes = _validate_prior_finding_outcomes(document["prior_finding_outcomes"])
    findings = _validate_findings(document["findings"], set(unit_map.order))
    dependency_assessment = _validate_dependency_assessment(document["dependency_assessment"], unit_map)
    _enum(document["verdict"], VERDICTS, "review-verdict")
    full_review_reason = document["full_review_reason"]
    unbounded = dependency_assessment["status"] == "unbounded"
    escalated_verdict = document["verdict"] == "full-review-required"
    if unbounded != escalated_verdict:
        _fail("corrupt", "invalid-review-schema", "Escalation status and verdict must agree")
    if escalated_verdict:
        _text(full_review_reason, "full-review-reason", maximum=MAX_DETAIL_BYTES)
        if set(dependency_assessment["reviewed_units"]) != {row["unit_id"] for row in coverage_rows}:
            _fail("corrupt", "invalid-review-schema", "Escalated coverage must equal the inspected units")
        for row in coverage_rows:
            if row["method"] != "incremental" or row["source_review_binding"] is not None:
                _fail("corrupt", "invalid-review-schema", "Escalated coverage rows must be method incremental")
    else:
        if full_review_reason is not None:
            _fail("corrupt", "invalid-review-schema", "Only an escalated review names a full-review reason")
    if document["mode"] == "full":
        if dependency_assessment["status"] != "complete":
            _fail("corrupt", "invalid-review-schema", "A full review requires a complete dependency assessment")
        if set(coverage_by_id) != set(unit_map.order) or any(
            row["method"] != "full" or row["source_review_binding"] is not None for row in coverage_rows
        ):
            _fail("corrupt", "invalid-review-schema", "A full review requires complete method-full coverage")
        if dependency_assessment["reviewed_units"] != unit_map.order:
            _fail("corrupt", "invalid-review-schema", "A full review must review every plan unit")
    else:
        if dependency_assessment["status"] not in ("bounded", "unbounded"):
            _fail("corrupt", "invalid-review-schema", "An incremental review cannot claim complete status")
        if dependency_assessment["status"] == "bounded":
            if set(coverage_by_id) != set(unit_map.order):
                _fail("corrupt", "invalid-review-schema", "A bounded incremental review requires complete coverage")
            if any(row["method"] == "full" for row in coverage_rows):
                _fail("corrupt", "invalid-review-schema", "Incremental review coverage cannot use method full")
            incremental_units = [row["unit_id"] for row in coverage_rows if row["method"] == "incremental"]
            if dependency_assessment["reviewed_units"] != [
                unit_id for unit_id in unit_map.order if unit_id in set(incremental_units)
            ]:
                _fail("corrupt", "invalid-review-schema", "reviewed_units must equal the incremental coverage rows")
    if revision_binding is None:
        if document["mode"] != "full" or prior_outcomes:
            _fail("corrupt", "invalid-review-schema", "A baseline review must be full with no prior outcomes")
    blocking = any(row["severity"] == "blocking" for row in findings)
    if document["verdict"] == "accepted" and blocking:
        _fail("denied", "accepted-review-has-blocking-finding", "Accepted review has blocking findings")
    if document["verdict"] == "needs-revision" and not blocking:
        _fail("denied", "needs-revision-without-blocking-finding", "Needs-revision review has no blocking finding")
    _verify_digest(document, "review_sha256", "review", MAX_DOCUMENT_BYTES, "plan-review-too-large")
    return {
        "plan_binding": plan_binding,
        "revision_binding": revision_binding,
        "mode": document["mode"],
        "verdict": document["verdict"],
        "full_review_reason": full_review_reason,
        "coverage_rows": coverage_rows,
        "coverage_by_id": coverage_by_id,
        "findings": findings,
        "findings_by_id": {row["id"]: row for row in findings},
        "prior_finding_outcomes": prior_outcomes,
        "dependency_assessment": dependency_assessment,
    }


def _project_review(root, envelope_or_reference, unit_map, expected_subject, expected_issue, expected_family,
                     label, expected_designated_digest=None, check_subject=True,
                     subject_mismatch_code="binding-subject-mismatch"):
    _exact_keys(
        envelope_or_reference,
        {"binding", "document"},
        "%s-envelope" % label,
    )
    document = envelope_or_reference["document"]
    reference = _reference(envelope_or_reference["binding"], "evidence-binding")
    if expected_designated_digest is not None:
        _trusted_digest(reference, expected_designated_digest, label)
    projection = _project(root, reference)
    parsed_schema = _validate_review_schema(document, unit_map)
    identity = _check_identity(projection, expected_issue, expected_family)
    if document["issue"] != expected_issue or document["family_run_id"] != expected_family:
        _fail("stale", "binding-identity-mismatch", "Plan review document belongs to another identity")
    if projection["decision"] != {"type": "plan-review", "id": "review-%s" % document["review_sha256"]}:
        _fail("stale", "binding-decision-mismatch", "Plan review decision is incompatible")
    if parsed_schema["plan_binding"] != unit_map.reference_binding:
        _fail("stale", "binding-subject-mismatch", "Plan review does not reference the expected plan snapshot")
    document_subject = (
        parsed_schema["plan_binding"]
        if parsed_schema["revision_binding"] is None
        else parsed_schema["revision_binding"]
    )
    if check_subject and (
        projection["subject"] != document_subject
        or (expected_subject is not None and projection["subject"] != expected_subject)
    ):
        _fail("stale", subject_mismatch_code, "Plan review subject is incompatible")
    entry = _single_entry(projection, REVIEW_PATH)
    document_data = _canonical_bytes(document, MAX_DOCUMENT_BYTES, "plan-review-too-large")
    if entry["content_sha256"] != inspector.sha256(document_data) or entry["size"] != len(document_data):
        _fail("stale", "document-record-entry-mismatch", "Review entry does not bind the review document")
    _read_payload(root, expected_issue, entry, document_data)
    return {
        "reference": reference,
        "document": document,
        "identity": identity,
        "schema": parsed_schema,
    }


def _verify_review_revision_link(root, review, expected_plan):
    """Check a non-baseline review's one-hop revision-to-plan link."""
    revision_ref = review["schema"]["revision_binding"]
    if revision_ref is None:
        return
    projection = _project(root, revision_ref)
    _check_identity(projection, review["identity"]["issue"], review["identity"]["family_run_id"])
    entry = _named_entries(projection, {REVISION_PATH, DIFF_PATH})[REVISION_PATH]
    data = _read_payload(root, review["identity"]["issue"], entry)
    document = inspector.parse_json_object(data, "linked plan revision")
    _exact_keys(
        document,
        {
            "format", "issue", "family_run_id", "prior_plan_binding", "prior_review_binding",
            "current_plan_binding", "diff", "changes", "dispositions", "revision_sha256",
        },
        "plan-revision",
    )
    _verify_digest(document, "revision_sha256", "revision", MAX_DOCUMENT_BYTES, "plan-revision-too-large")
    if _canonical_bytes(document, MAX_DOCUMENT_BYTES, "plan-revision-too-large") != data:
        _fail("corrupt", "noncanonical-document", "Linked revision document is not canonical")
    prior_plan = _reference(document["prior_plan_binding"], "evidence-binding")
    prior_review = _reference(document["prior_review_binding"], "evidence-binding")
    current_plan = _reference(document["current_plan_binding"], "evidence-binding")
    if (
        document["format"] != REVISION_FORMAT
        or document["issue"] != review["identity"]["issue"]
        or document["family_run_id"] != review["identity"]["family_run_id"]
        or current_plan != expected_plan
        or projection["decision"] != {"type": "plan-revision", "id": "revision-%s" % document["revision_sha256"]}
        or projection["subject"] != prior_review
    ):
        _fail("stale", "current-review-subject-mismatch", "Review revision does not bind its reviewed plan")


# ---------------------------------------------------------------------------
# Revision, diff, and preservation validation
# ---------------------------------------------------------------------------


def _unified_diff(old_lines, new_lines):
    matcher = difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False)
    output = []
    for group in matcher.get_grouped_opcodes(3):
        first = group[0]
        last = group[-1]
        old_start, old_end = first[1], last[2]
        new_start, new_end = first[3], last[4]
        output.append(
            "@@ -%d,%d +%d,%d @@\n"
            % (
                old_start + 1 if old_end != old_start else old_start,
                old_end - old_start,
                new_start + 1 if new_end != new_start else new_start,
                new_end - new_start,
            )
        )
        for tag, i1, i2, j1, j2 in group:
            if tag == "equal":
                output.extend(" " + line for line in old_lines[i1:i2])
            elif tag == "delete":
                output.extend("-" + line for line in old_lines[i1:i2])
            elif tag == "insert":
                output.extend("+" + line for line in new_lines[j1:j2])
            else:
                output.extend("-" + line for line in old_lines[i1:i2])
                output.extend("+" + line for line in new_lines[j1:j2])
    return ("--- a/plan.md\n+++ b/plan.md\n" + "".join(output)).encode("utf-8") if output else b""


def _diff_cost(old_lines, new_lines):
    old_counts = collections.Counter(old_lines)
    new_counts = collections.Counter(new_lines)
    return sum(old_counts[line] * count for line, count in new_counts.items())


def _self_check_diff():
    global _diff_self_check_done
    if _diff_self_check_done:
        return
    for old_lines, new_lines, expected in _DIFF_GOLDEN_VECTORS:
        if _unified_diff(old_lines, new_lines) != expected:
            _fail("unsupported", "diff-algorithm-runtime", "Runtime does not reproduce the V1 diff vectors")
    _diff_self_check_done = True


def _diff_touched_units(old_lines, new_lines, prior_map, current_map):
    if _diff_cost(old_lines, new_lines) > MAX_DIFF_COST:
        _fail("unsupported", "diff-cost-exceeded", "Plan diff repetition cost exceeds its limit")
    old_owner = prior_map.line_owner(old_lines)
    new_owner = current_map.line_owner(new_lines)
    touched = set()
    matcher = difflib.SequenceMatcher(None, old_lines, new_lines, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        touched.update(old_owner[i1:i2])
        touched.update(new_owner[j1:j2])
    return touched


def _validate_change(value, valid_ids):
    _exact_keys(value, {"unit_id", "impact", "reason"}, "revision-change")
    _slug(value["unit_id"], "change-unit-id")
    if value["unit_id"] not in valid_ids:
        _fail("corrupt", "invalid-revision-schema", "Change references an unknown unit")
    _enum(value["impact"], IMPACTS, "change-impact")
    _text(value["reason"], "change-reason", maximum=MAX_DETAIL_BYTES)
    return dict(value)


def _validate_disposition(value, valid_ids):
    _exact_keys(value, {"finding_id", "status", "unit_ids", "reason"}, "finding-disposition")
    if not isinstance(value["finding_id"], str) or FINDING_ID_RE.fullmatch(value["finding_id"]) is None:
        _fail("corrupt", "invalid-revision-schema", "Disposition finding ID is malformed")
    _enum(value["status"], DISPOSITION_STATUSES, "disposition-status")
    _slug_list(value["unit_ids"], "disposition-unit-ids", MAX_UNITS)
    if any(unit_id not in valid_ids for unit_id in value["unit_ids"]):
        _fail("corrupt", "invalid-revision-schema", "Disposition references an unknown unit")
    _text(value["reason"], "disposition-reason", maximum=MAX_DETAIL_BYTES)
    return dict(value)


def _validate_revision_schema(document, prior, current):
    _exact_keys(
        document,
        {
            "format", "issue", "family_run_id", "prior_plan_binding", "prior_review_binding",
            "current_plan_binding", "diff", "changes", "dispositions", "revision_sha256",
        },
        "plan-revision",
    )
    if document["format"] != REVISION_FORMAT:
        _fail("unsupported", "unsupported-plan-revision-format", "Plan revision format is unsupported")
    _issue(document["issue"])
    _run_id(document["family_run_id"])
    for name in ("prior_plan_binding", "prior_review_binding", "current_plan_binding"):
        _reference(document[name], "evidence-binding")
    diff = document["diff"]
    _exact_keys(diff, {"format", "path", "algorithm", "old_label", "new_label", "context_lines", "content_sha256", "size"}, "revision-diff")
    if diff["format"] != DIFF_FORMAT or diff["path"] != DIFF_PATH:
        _fail("unsupported", "unsupported-plan-diff-format", "Plan diff format or path is unsupported")
    if diff["algorithm"] != "sequence-matcher-unified-3-autojunk-false-v1" or diff["old_label"] != "a/plan.md" or diff["new_label"] != "b/plan.md" or diff["context_lines"] != 3:
        _fail("corrupt", "invalid-revision-schema", "Plan diff metadata is not the V1 contract")
    _sha256(diff["content_sha256"], "diff-content-hash")
    _uint(diff["size"], "diff-size")
    if diff["size"] > MAX_DIFF_BYTES:
        _fail("unsupported", "diff-too-large", "Plan diff exceeds 4 MiB")
    valid_ids = set(prior["units"].order) | set(current["units"].order)
    changes = [_validate_change(item, valid_ids) for item in document["changes"]] if isinstance(document["changes"], list) and len(document["changes"]) <= MAX_UNITS else _fail("corrupt", "invalid-revision-schema", "Changes must be bounded")
    if len({row["unit_id"] for row in changes}) != len(changes):
        _fail("corrupt", "invalid-revision-schema", "Revision changes contain duplicate unit IDs")
    _ordered_unique(changes, lambda row: row["unit_id"], "revision-change", MAX_UNITS)
    dispositions = [_validate_disposition(item, valid_ids) for item in document["dispositions"]] if isinstance(document["dispositions"], list) and len(document["dispositions"]) <= MAX_DISPOSITIONS else _fail("corrupt", "invalid-revision-schema", "Dispositions must be bounded")
    if len({row["finding_id"] for row in dispositions}) != len(dispositions):
        _fail("corrupt", "invalid-revision-schema", "Revision dispositions contain duplicate finding IDs")
    _ordered_unique(dispositions, lambda row: row["finding_id"], "finding-disposition", MAX_DISPOSITIONS)
    _verify_digest(document, "revision_sha256", "revision", MAX_DOCUMENT_BYTES, "plan-revision-too-large")
    return {"changes": changes, "dispositions": dispositions}


def _project_revision(root, envelope, prior, current, designated_digest):
    _exact_keys(envelope, {"binding", "document"}, "revision-envelope")
    reference = _reference(envelope["binding"], "evidence-binding")
    _trusted_digest(reference, designated_digest, "revision")
    schema = _validate_revision_schema(envelope["document"], prior, current)
    document = envelope["document"]
    if document["issue"] != prior["document"]["issue"] or document["family_run_id"] != prior["document"]["family_run_id"]:
        _fail("stale", "binding-identity-mismatch", "Revision identity does not match its plan chain")
    projection = _project(root, reference)
    _check_identity(projection, document["issue"], document["family_run_id"])
    if projection["decision"] != {"type": "plan-revision", "id": "revision-%s" % document["revision_sha256"]} or projection["subject"] != document["prior_review_binding"]:
        _fail("stale", "binding-subject-mismatch", "Revision binding does not bind its prior review")
    entries = _named_entries(projection, {REVISION_PATH, DIFF_PATH})
    data = _canonical_bytes(document, MAX_DOCUMENT_BYTES, "plan-revision-too-large")
    if entries[REVISION_PATH]["content_sha256"] != inspector.sha256(data) or entries[REVISION_PATH]["size"] != len(data):
        _fail("stale", "document-record-entry-mismatch", "Revision entry does not bind the revision document")
    _read_payload(root, document["issue"], entries[REVISION_PATH], data)
    diff_data = _read_payload(root, document["issue"], entries[DIFF_PATH])
    if len(diff_data) > MAX_DIFF_BYTES or inspector.sha256(diff_data) != document["diff"]["content_sha256"] or len(diff_data) != document["diff"]["size"]:
        _fail("stale", "document-record-entry-mismatch", "Revision entry does not bind the declared diff")
    _self_check_diff()
    if _diff_cost(prior["plan_lines"], current["plan_lines"]) > MAX_DIFF_COST:
        _fail("unsupported", "diff-cost-exceeded", "Plan diff repetition cost exceeds its limit")
    generated = _unified_diff(prior["plan_lines"], current["plan_lines"])
    if not generated:
        _fail("stale", "revision-without-change", "A revision must change plan bytes")
    if diff_data != generated:
        _fail("corrupt", "diff-content-mismatch", "Stored diff differs from deterministic regeneration")
    return {"reference": reference, "document": document, "schema": schema}


def _source_review(root, reference, issue, family, unit_id, content_hash, cache=None):
    """Bounded one-level anchor check; it deliberately never follows preserved rows."""
    digest = reference["sha256"]
    cached = None if cache is None else cache.get(digest)
    if cached is None:
        projection = _project(root, reference)
        _check_identity(projection, issue, family)
        entry = _single_entry(projection, REVIEW_PATH)
        data = _read_payload(root, issue, entry)
        document = inspector.parse_json_object(data, "preservation review")
        _exact_keys(document, {"format", "issue", "family_run_id", "plan_binding", "revision_binding", "mode", "reviewer", "coverage", "prior_finding_outcomes", "findings", "dependency_assessment", "verdict", "full_review_reason", "review_sha256"}, "plan-review")
        if document["format"] != REVIEW_FORMAT or document["issue"] != issue or document["family_run_id"] != family:
            _fail("denied", "unanchored-preserved-coverage", "Preservation source is not a matching plan review")
        _verify_digest(document, "review_sha256", "review", MAX_DOCUMENT_BYTES, "plan-review-too-large")
        if _canonical_bytes(document, MAX_DOCUMENT_BYTES, "plan-review-too-large") != data:
            _fail("denied", "unanchored-preserved-coverage", "Preservation source review is not canonical")
        plan_binding = _reference(document["plan_binding"], "evidence-binding")
        revision_binding = _reference_or_none(document["revision_binding"], "evidence-binding")
        mode = _enum(document["mode"], REVIEW_MODES, "review-mode")
        _exact_keys(document["reviewer"], {"role", "actor"}, "reviewer")
        if document["reviewer"]["role"] != "independent-reviewer":
            _fail("denied", "unanchored-preserved-coverage", "Preservation source reviewer role is invalid")
        _text(document["reviewer"]["actor"], "reviewer-actor", maximum=MAX_ACTOR_BYTES)
        _exact_keys(document["dependency_assessment"], {"status", "reviewed_units", "reason"}, "dependency-assessment")
        dependency_status = _enum(
            document["dependency_assessment"]["status"],
            DEPENDENCY_STATUSES,
            "dependency-assessment-status",
        )
        _enum(document["verdict"], VERDICTS, "review-verdict")
        if dependency_status == "unbounded" or document["verdict"] == "full-review-required":
            _fail("denied", "unanchored-preserved-coverage", "Escalated reviews cannot anchor preservation")
        if (mode == "full" and dependency_status != "complete") or (
            mode == "incremental" and dependency_status != "bounded"
        ):
            _fail("denied", "unanchored-preserved-coverage", "Preservation source review status is inconsistent")
        if projection["decision"] != {"type": "plan-review", "id": "review-%s" % document["review_sha256"]}:
            _fail("denied", "unanchored-preserved-coverage", "Preservation source decision is invalid")
        expected_subject = plan_binding if revision_binding is None else revision_binding
        if projection["subject"] != expected_subject:
            _fail("denied", "unanchored-preserved-coverage", "Preservation source subject is invalid")
        if not isinstance(document["coverage"], list) or len(document["coverage"]) > MAX_COVERAGE:
            _fail("denied", "unanchored-preserved-coverage", "Preservation source coverage is malformed")
        direct = {}
        for row in document["coverage"]:
            if not isinstance(row, dict):
                _fail("denied", "unanchored-preserved-coverage", "Preservation source coverage is malformed")
            _exact_keys(row, {"unit_id", "content_sha256", "method", "source_review_binding"}, "coverage-row")
            row_id = _slug(row["unit_id"], "coverage-unit-id")
            row_hash = _sha256(row["content_sha256"], "coverage-content-hash")
            row_method = _enum(row["method"], COVERAGE_METHODS, "coverage-method")
            if row_id in direct:
                _fail("denied", "unanchored-preserved-coverage", "Preservation source coverage is duplicated")
            if row_method == "preserved":
                _reference(row["source_review_binding"], "evidence-binding")
            elif row["source_review_binding"] is not None:
                _fail("denied", "unanchored-preserved-coverage", "Direct source coverage cannot name another review")
            direct[row_id] = (row_hash, row_method)
        cached = (mode, direct)
        if cache is not None:
            cache[digest] = cached
    mode, direct = cached
    expected_method = "full" if mode == "full" else "incremental"
    if direct.get(unit_id) == (content_hash, expected_method):
        return
    _fail("denied", "unanchored-preserved-coverage", "Preservation source did not directly review the unit")


def _required_dispositions(prior_review, revision):
    expected = [row["id"] for row in prior_review["schema"]["findings"]]
    actual = [row["finding_id"] for row in revision["schema"]["dispositions"]]
    if actual != expected:
        _fail("corrupt", "invalid-revision-schema", "Revision dispositions must exactly cover prior findings")


def _changes_and_escalations(prior, current, prior_review, revision):
    prior_map, current_map = prior["units"], current["units"]
    old_ids, new_ids = set(prior_map.order), set(current_map.order)
    retained = old_ids & new_ids
    changed_hashes = {unit_id for unit_id in retained if prior_map.hash_by_id[unit_id] != current_map.hash_by_id[unit_id]}
    structural = (old_ids ^ new_ids) | changed_hashes
    touched = _diff_touched_units(prior["plan_lines"], current["plan_lines"], prior_map, current_map)
    changes = {row["unit_id"]: row for row in revision["schema"]["changes"]}
    expected_changes = structural | touched
    if set(changes) != expected_changes:
        _fail("corrupt", "invalid-revision-schema", "Changes must exactly cover structural and diff-touched units")
    reasons = set()
    if prior_review["schema"]["verdict"] == "accepted":
        reasons.add("accepted-prior-review")
    if prior_review["schema"]["verdict"] == "full-review-required":
        reasons.add("prior-review-escalated")
    if prior["document"]["context"] != current["document"]["context"]:
        reasons.add("changed-context-binding")
    if old_ids != new_ids:
        reasons.add("unit-set-changed")
    if [item for item in prior_map.order if item in retained] != [item for item in current_map.order if item in retained]:
        reasons.add("unit-order-changed")
    if any(prior_map.title_by_id[x] != current_map.title_by_id[x] or prior_map.class_by_id[x] != current_map.class_by_id[x] for x in retained):
        reasons.add("unit-metadata-changed")
    if any(prior_map.dependencies_by_id[x] != current_map.dependencies_by_id[x] for x in retained):
        reasons.add("dependency-graph-changed")
    if changed_hashes != touched:
        reasons.add("diff-unit-mapping-mismatch")
    if any(row["impact"] == "full-review-required" for row in changes.values()):
        reasons.add("planner-declared-full-review")
    for unit_id in structural:
        review_class = current_map.class_by_id.get(unit_id, prior_map.class_by_id.get(unit_id))
        if review_class != "ordinary":
            reasons.add("sensitive-unit-changed")
    return expected_changes, reasons


def _validate_review_adjudication(current_review, prior_review, current_reference):
    prior_findings = prior_review["schema"]["findings_by_id"]
    outcomes = current_review["schema"]["prior_finding_outcomes"]
    if [row["finding_id"] for row in outcomes] != sorted(prior_findings):
        _fail("corrupt", "invalid-review-schema", "Review outcomes must exactly cover prior findings")
    current_findings = current_review["schema"]["findings_by_id"]
    for outcome in outcomes:
        finding_id = outcome["finding_id"]
        prior = prior_findings[finding_id]
        if outcome["status"] == "remains":
            if current_findings.get(finding_id) != prior:
                _fail("corrupt", "carried-finding-missing", "A remaining finding must be carried verbatim")
        elif outcome["status"] == "resolved":
            if finding_id in current_findings:
                _fail("corrupt", "invalid-review-schema", "A resolved finding cannot remain open")
        else:
            replacement = current_findings.get(outcome["replacement_finding_id"])
            if replacement is None or replacement["introduced_plan_binding"] != current_reference or finding_id in current_findings or SEVERITY_ORDER[replacement["severity"]] < SEVERITY_ORDER[prior["severity"]]:
                _fail("corrupt", "invalid-review-schema", "Superseded finding requires an equal-or-higher new finding")
    for finding in current_review["schema"]["findings"]:
        if finding["id"] not in prior_findings and finding["introduced_plan_binding"] != current_reference:
            _fail("corrupt", "invalid-review-schema", "New findings must be introduced against the current plan")


def _derive_preservation(root, prior, current, prior_review, required):
    rows = prior_review["schema"]["coverage_by_id"]
    eligible = []
    for unit_id in current["units"].order:
        if unit_id in required or unit_id not in rows:
            continue
        row = rows[unit_id]
        if prior["units"].hash_by_id.get(unit_id) != current["units"].hash_by_id[unit_id]:
            continue
        if row["method"] in ("full", "incremental"):
            source = prior_review["reference"]
        else:
            source = row["source_review_binding"]
        eligible.append({"unit_id": unit_id, "content_sha256": row["content_sha256"], "source_review_binding": source})
    sources = {item["source_review_binding"]["sha256"]: item["source_review_binding"] for item in eligible}
    if len(sources) > MAX_PRESERVED_SOURCES:
        return [], {"preservation-fanout-exceeded"}
    source_cache = {}
    for item in eligible:
        _source_review(root, item["source_review_binding"], current["document"]["issue"], current["document"]["family_run_id"], item["unit_id"], item["content_sha256"], source_cache)
    return sorted(eligible, key=lambda item: item["unit_id"]), set()


def _validate_current_review(root, current_review, prior_review, revision, current, minimum, required, eligible):
    schema = current_review["schema"]
    if schema["revision_binding"] is None:
        _fail("stale", "baseline-review-in-revision-evaluation", "Revision evaluation requires a revision review")
    if schema["revision_binding"] != revision["reference"]:
        _fail("stale", "current-review-subject-mismatch", "Current review does not name this revision")
    if minimum == "full" and schema["mode"] != "full":
        _fail("denied", "insufficient-review-mode", "An incremental review cannot satisfy a full requirement")
    _validate_review_adjudication(current_review, prior_review, current["reference"])
    if schema["mode"] == "incremental" and schema["verdict"] == "full-review-required":
        return "technical-review-escalated", []
    if schema["verdict"] == "full-review-required":
        _fail("corrupt", "invalid-review-schema", "Only incremental reviews can escalate")
    if schema["mode"] == "incremental":
        coverage = schema["coverage_by_id"]
        sources = {
            row["source_review_binding"]["sha256"]
            for row in coverage.values()
            if row["method"] == "preserved"
        }
        if len(sources) > MAX_PRESERVED_SOURCES:
            _fail("unsupported", "preserved-source-fanout", "Review preservation source fanout exceeds its limit")
        if not set(required).issubset({unit_id for unit_id, row in coverage.items() if row["method"] == "incremental"}):
            _fail("denied", "incomplete-review-coverage", "Incremental review omitted required units")
        verified = []
        eligible_by_id = {item["unit_id"]: item for item in eligible}
        for unit_id, row in coverage.items():
            if row["method"] == "preserved":
                expected = eligible_by_id.get(unit_id)
                if expected is None or row["content_sha256"] != expected["content_sha256"] or row["source_review_binding"] != expected["source_review_binding"]:
                    _fail("denied", "unanchored-preserved-coverage", "Preserved coverage is not policy eligible")
                verified.append(expected)
        verified.sort(key=lambda item: item["unit_id"])
    else:
        verified = []
    blocking = any(row["severity"] == "blocking" for row in schema["findings"])
    if schema["verdict"] == "accepted" and blocking:
        _fail("denied", "accepted-review-has-blocking-finding", "Accepted review has blocking findings")
    if schema["verdict"] == "needs-revision" and not blocking:
        _fail("denied", "needs-revision-without-blocking-finding", "Needs-revision review has no blocking finding")
    return ("technical-review-accepted" if schema["verdict"] == "accepted" else "technical-review-needs-revision"), verified


def _result(value):
    value["result_sha256"] = _digest(value, MAX_DOCUMENT_BYTES, "policy-result-too-large")
    return value


def _base_result(issue, family, request_digest, current, prior=None, review=None, revision=None, mode="full", reasons=(), changed=(), required=(), eligible=(), verified=(), disposition="not-applicable", verdict=None, code="review-required"):
    return _result(
        {
            "format": RESULT_FORMAT,
            "outcome": {"status": "resolved", "code": code},
            "authority": "inactive-derived-policy",
            "issue": issue,
            "family_run_id": family,
            "prior_plan_binding": None if prior is None else prior["reference"],
            "prior_review_binding": None if review is None else review["reference"],
            "current_plan_binding": current["reference"],
            "revision_binding": None if revision is None else revision["reference"],
            "current_review_binding": None,
            "review_mode": mode,
            "escalation_reasons": sorted(reasons),
            "changed_units": sorted(changed),
            "required_review_units": list(required),
            "eligible_preserved_units": eligible,
            "verified_preserved_units": verified,
            "disposition_status": disposition,
            "technical_verdict": verdict,
            "activation": {"status": "inactive", "owner": "thin-replacement-orchestrator", "unverified": list(ACTIVATION_UNVERIFIED)},
            "request_sha256": request_digest,
        }
    )


def _baseline_request(request):
    _exact_keys(request, {"format", "plan", "current_review", "request_sha256"}, "baseline-policy-request")
    if request["format"] != BASELINE_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-baseline-request-format", "Baseline request format is unsupported")
    _verify_digest(request, "request_sha256", "request", MAX_REQUEST_BYTES, "request-too-large")


def _revision_request(request):
    _exact_keys(request, {"format", "prior_plan", "prior_review", "current_plan", "revision", "current_review", "request_sha256"}, "revision-policy-request")
    if request["format"] != REVISION_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-revision-request-format", "Revision request format is unsupported")
    _verify_digest(request, "request_sha256", "request", MAX_REQUEST_BYTES, "request-too-large")


@_translate_input_errors
def evaluate_baseline(root, request, designated_plan_binding, designated_review_binding=None):
    root = _resolve_root(root)
    _baseline_request(request)
    plan = _project_snapshot(root, request["plan"], designated_plan_binding, "plan")
    if plan["document"]["revision"] != 1 or plan["document"]["predecessor"] is not None:
        _fail("stale", "revision-sequence-stale", "Baseline must be revision 1")
    review_envelope = request["current_review"]
    if review_envelope is None:
        if designated_review_binding is not None:
            _fail("stale", "designated-binding-mismatch", "Review designation requires a review envelope")
        return _base_result(plan["document"]["issue"], plan["document"]["family_run_id"], request["request_sha256"], plan, required=plan["units"].order)
    if designated_review_binding is None:
        _fail("stale", "designated-binding-mismatch", "Review envelope requires a review designation")
    review = _project_review(root, review_envelope, plan["units"], plan["reference"], plan["document"]["issue"], plan["document"]["family_run_id"], "current-review", designated_review_binding)
    if review["schema"]["revision_binding"] is not None:
        _fail("stale", "baseline-review-in-revision-evaluation", "Baseline review must have no revision binding")
    if any(
        finding["introduced_plan_binding"] != plan["reference"]
        for finding in review["schema"]["findings"]
    ):
        _fail("corrupt", "invalid-review-schema", "Baseline findings must be introduced against the baseline plan")
    code = "technical-review-accepted" if review["schema"]["verdict"] == "accepted" else "technical-review-needs-revision"
    result = _base_result(plan["document"]["issue"], plan["document"]["family_run_id"], request["request_sha256"], plan, required=plan["units"].order, verdict=review["schema"]["verdict"], code=code)
    result["current_review_binding"] = review["reference"]
    result.pop("result_sha256")
    return _result(result)


@_translate_input_errors
def evaluate_revision(root, request, trusted_prior_plan_binding, trusted_prior_review_binding, designated_current_plan_binding, designated_revision_binding, designated_current_review_binding=None):
    root = _resolve_root(root)
    _revision_request(request)
    prior = _project_snapshot(root, request["prior_plan"], trusted_prior_plan_binding, "prior-plan")
    current = _project_snapshot(root, request["current_plan"], designated_current_plan_binding, "current-plan")
    if prior["document"]["issue"] != current["document"]["issue"] or prior["document"]["family_run_id"] != current["document"]["family_run_id"]:
        _fail("stale", "binding-identity-mismatch", "Plan snapshots are from different identities")
    prior_review = _project_review(root, request["prior_review"], prior["units"], None, prior["document"]["issue"], prior["document"]["family_run_id"], "prior-review", trusted_prior_review_binding)
    _verify_review_revision_link(root, prior_review, prior["reference"])
    revision = _project_revision(root, request["revision"], prior, current, designated_revision_binding)
    document = revision["document"]
    if document["prior_plan_binding"] != prior["reference"] or document["prior_review_binding"] != prior_review["reference"] or document["current_plan_binding"] != current["reference"]:
        _fail("stale", "predecessor-mismatch", "Revision links do not match the designated chain")
    predecessor = current["document"]["predecessor"]
    if current["document"]["revision"] != prior["document"]["revision"] + 1 or predecessor != {"plan_binding": prior["reference"], "review_binding": prior_review["reference"]}:
        _fail("stale", "revision-sequence-stale", "Current snapshot does not directly follow the prior review")
    _required_dispositions(prior_review, revision)
    changed, reasons = _changes_and_escalations(prior, current, prior_review, revision)
    required = set(changed)
    for unit_id in changed:
        required.update(current["units"].dependencies_by_id.get(unit_id, ()))
        required.update(current["units"].dependents_by_id.get(unit_id, ()))
    for finding in prior_review["schema"]["findings"]:
        required.update(unit_id for unit_id in finding["unit_ids"] if unit_id in current["units"].index)
    for disposition in revision["schema"]["dispositions"]:
        required.update(unit_id for unit_id in disposition["unit_ids"] if unit_id in current["units"].index)
    required_order = [unit_id for unit_id in current["units"].order if unit_id in required]
    eligible = []
    if not reasons:
        if prior_review["schema"]["dependency_assessment"]["status"] == "unbounded":
            reasons.add("prior-review-escalated")
        else:
            eligible, fanout = _derive_preservation(root, prior, current, prior_review, set(required_order))
            reasons.update(fanout)
    minimum = "full" if reasons else "incremental"
    if minimum == "full":
        required_order, eligible = current["units"].order, []
    current_review_envelope = request["current_review"]
    if current_review_envelope is None:
        if designated_current_review_binding is not None:
            _fail("stale", "designated-binding-mismatch", "Review designation requires a review envelope")
        return _base_result(current["document"]["issue"], current["document"]["family_run_id"], request["request_sha256"], current, prior, prior_review, revision, minimum, reasons, changed, required_order, eligible, [], "complete")
    if designated_current_review_binding is None:
        _fail("stale", "designated-binding-mismatch", "Review envelope requires a review designation")
    if (
        isinstance(current_review_envelope, dict)
        and isinstance(current_review_envelope.get("document"), dict)
        and "revision_binding" in current_review_envelope["document"]
        and current_review_envelope["document"]["revision_binding"] is None
    ):
        _fail(
            "stale",
            "baseline-review-in-revision-evaluation",
            "Revision evaluation requires a revision review",
        )
    current_review = _project_review(
        root,
        current_review_envelope,
        current["units"],
        revision["reference"],
        current["document"]["issue"],
        current["document"]["family_run_id"],
        "current-review",
        designated_current_review_binding,
        subject_mismatch_code="current-review-subject-mismatch",
    )
    code, verified = _validate_current_review(root, current_review, prior_review, revision, current, minimum, set(required_order), eligible)
    result = _base_result(current["document"]["issue"], current["document"]["family_run_id"], request["request_sha256"], current, prior, prior_review, revision, minimum, reasons, changed, required_order, eligible, verified, "complete", current_review["schema"]["verdict"], code)
    result["current_review_binding"] = current_review["reference"]
    result.pop("result_sha256")
    return _result(result)


class PolicyArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)


def build_parser():
    parser = PolicyArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    baseline = commands.add_parser("evaluate-baseline")
    baseline.add_argument("--root", default=".")
    baseline.add_argument("--request", required=True)
    baseline.add_argument("--designated-plan-binding", required=True)
    baseline.add_argument("--designated-review-binding")
    revision = commands.add_parser("evaluate-revision")
    revision.add_argument("--root", default=".")
    revision.add_argument("--request", required=True)
    revision.add_argument("--trusted-prior-plan-binding", required=True)
    revision.add_argument("--trusted-prior-review-binding", required=True)
    revision.add_argument("--designated-current-plan-binding", required=True)
    revision.add_argument("--designated-revision-binding", required=True)
    revision.add_argument("--designated-current-review-binding")
    return parser


def _dispatch_evaluate_baseline(args):
    return evaluate_baseline(
        args.root, _load_json(args.request), args.designated_plan_binding, args.designated_review_binding
    )


def _dispatch_evaluate_revision(args):
    return evaluate_revision(
        args.root,
        _load_json(args.request),
        args.trusted_prior_plan_binding,
        args.trusted_prior_review_binding,
        args.designated_current_plan_binding,
        args.designated_revision_binding,
        args.designated_current_review_binding,
    )


COMMAND_HANDLERS = {
    "evaluate-baseline": _dispatch_evaluate_baseline,
    "evaluate-revision": _dispatch_evaluate_revision,
}


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        handler = COMMAND_HANDLERS.get(args.command)
        if handler is None:
            _fail("unsupported", "unsupported-command", "Command is unsupported")
        document = handler(args)
        exit_code = 0
    except PlanRevisionPolicyFailure as failure:
        document, exit_code = failure.document(), OUTCOME_EXIT_CODES[failure.status]
    except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
        wrapped = PlanRevisionPolicyFailure(failure.status, failure.code, failure.message, failure.subject)
        document, exit_code = wrapped.document(), OUTCOME_EXIT_CODES[wrapped.status]
    try:
        output = inspector.canonical_document(document)
    except (inspector.InspectionFailure, TypeError, ValueError):
        document = PlanRevisionPolicyFailure("corrupt", "invalid-canonical-output", "Policy output is not canonical").document()
        output, exit_code = inspector.canonical_document(document), OUTCOME_EXIT_CODES["corrupt"]
    sys.stdout.buffer.write(output)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
