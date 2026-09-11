#!/usr/bin/env python3
"""Stateless restart/resume mechanics for the replacement workflow orchestrator.

This module owns the small set of documents needed to safely resume orchestration after a
process restart: pending-result query construction, discovered pending-result response
validation, agent candidate-output schema validation, and exact execution-handoff
construction/validation. It performs no Git/GitHub/process execution, publishes no evidence,
commits no authority, selects no lifecycle transition, and performs no GitHub mutation -- all
of that remains the exclusive responsibility of workflow_orchestrator.py.
"""
import base64, copy, json
try:
    import workflow_inspector as inspector
except ModuleNotFoundError:  # pragma: no cover - package execution
    from scripts import workflow_inspector as inspector

HANDOFF_FORMAT = "chess-echo-execution-handoff-v1"
PENDING_RESULT_QUERY_FORMAT = "chess-echo-pending-result-query-v1"
PENDING_RESULT_CANDIDATES_FORMAT = "chess-echo-pending-result-candidates-v1"
CANDIDATE_FORMAT = "chess-echo-orchestrator-agent-candidate-v1"
RECOVERY_CHALLENGE_FORMAT = "chess-echo-human-challenge-v1"


class ResumeFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status, self.code, self.message, self.subject = status, code, message, subject


def _fail(status, code, message, subject=None):
    raise ResumeFailure(status, code, message, subject)


def _require(condition, status, code, message, subject=None):
    if not condition:
        _fail(status, code, message, subject)


def _canonical(value):
    return inspector.canonical_bytes(value)


def _digest(value):
    return inspector.sha256(_canonical(value))


def _with_digest(value, field):
    value = copy.deepcopy(value)
    value[field] = _digest(value)
    return value


def pending_result_query(issue, family_run_id, authority_binding, pending):
    """Build the exact query document used to discover a pending attempt's published result."""
    if pending is None or pending["kind"] in {"human", "human-rejection", "policy"} or pending["status"] != "requested":
        return None
    document = {
        "format": PENDING_RESULT_QUERY_FORMAT,
        "issue": issue,
        "family_run_id": family_run_id,
        "authority_binding": authority_binding,
        "request_binding": pending["request_binding"],
        "attempt_id": pending["attempt_id"],
        "result_kind": "github-pr-observation" if pending["kind"] == "github-read" else "execution-result",
    }
    return _with_digest(document, "query_sha256")


def candidate_schema(value, expected):
    """Validate the exact shape of an agent's decoded candidate output."""
    _require(value.get("format") == CANDIDATE_FORMAT and value.get("kind") == expected, "corrupt", "candidate-output-invalid", "Agent output has the wrong candidate kind")
    if expected == "implementer":
        _require(set(value) == {"format", "kind", "report"} and isinstance(value["report"], str) and value["report"], "corrupt", "candidate-output-invalid", "Implementer candidate is invalid")
    if expected == "review":
        _require(set(value) == {"format", "kind", "verdict", "findings", "pr"} and isinstance(value["findings"], list) and isinstance(value["pr"], dict), "corrupt", "candidate-output-invalid", "Review candidate is invalid")
    return value


def valid_pr_body(body):
    headings = ("## What", "## Why", "## Testing")
    lines, positions = body.splitlines(), []
    for heading in headings:
        matches = [index for index, line in enumerate(lines) if line == heading]
        if len(matches) != 1: return False
        positions.append(matches[0])
    if positions != sorted(positions) or any(line.startswith("## ") and line not in headings for line in lines): return False
    bounds = positions[1:] + [len(lines)]
    return all(any(line.strip() for line in lines[start + 1:end]) for start, end in zip(positions, bounds))


def validate_pr_metadata(pr):
    """Validate a review candidate's draft-PR metadata: exact shape and body headings."""
    _require(isinstance(pr, dict) and set(pr) == {"head_ref", "title", "body"}, "corrupt", "pr-metadata-invalid", "Final review did not supply exact draft PR metadata")
    _require(all(isinstance(pr[key], str) and pr[key] for key in ("head_ref", "title", "body")), "corrupt", "pr-metadata-invalid", "Draft PR metadata is incomplete")
    _require(valid_pr_body(pr["body"]), "denied", "pr-body-headings", "Draft PR body must contain only nonempty What, Why, and Testing sections")
    return pr


def build_handoff(authority_binding, request_binding, result_binding=None, repository_after_binding=None, pr_observation_binding=None):
    """Construct the exact execution-handoff document shared by live execution and discovery."""
    return {
        "format": HANDOFF_FORMAT,
        "authority_binding": authority_binding,
        "request_binding": request_binding,
        "result_binding": result_binding,
        "repository_after_binding": repository_after_binding,
        "pr_observation_binding": pr_observation_binding,
    }


def validate_handoff_shape(supplied, authority_binding, request_binding):
    """Validate a caller-supplied handoff has the exact schema and identity for this resume."""
    keys = {"format", "authority_binding", "request_binding", "result_binding", "repository_after_binding", "pr_observation_binding"}
    _require(isinstance(supplied, dict) and set(supplied) == keys and supplied["format"] == HANDOFF_FORMAT and supplied["authority_binding"] == authority_binding and supplied["request_binding"] == request_binding, "busy", "attempt-in-flight", "Pending execution requires its exact result handoff")


def validate_discovery_response(discovery, query):
    """Validate a pending-result discovery response and return its single verified candidate."""
    _require(isinstance(discovery, dict) and set(discovery) == {"format", "query_sha256", "candidates"} and discovery["format"] == PENDING_RESULT_CANDIDATES_FORMAT and discovery["query_sha256"] == query["query_sha256"] and isinstance(discovery["candidates"], list) and len(discovery["candidates"]) <= 16, "corrupt", "pending-result-discovery-invalid", "Pending result discovery response is malformed or stale")
    candidates = discovery["candidates"]
    if not candidates:
        _fail("missing", "pending-result-not-found", "No published result exists for the exact pending request and attempt")
    if len(candidates) != 1:
        _fail("ambiguous", "pending-result-ambiguous", "Multiple published results claim the exact pending request and attempt")
    candidate = candidates[0]
    _require(isinstance(candidate, dict) and set(candidate) == {"kind", "binding"} and candidate["kind"] == query["result_kind"], "corrupt", "pending-result-candidate-invalid", "Pending result candidate has the wrong shape or kind")
    binding = candidate["binding"]
    _require(isinstance(binding, dict) and set(binding) == {"kind", "sha256", "size"} and binding["kind"] == "evidence-binding" and isinstance(binding["sha256"], str) and len(binding["sha256"]) == 64 and all(character in "0123456789abcdef" for character in binding["sha256"]) and type(binding["size"]) is int and binding["size"] > 0, "corrupt", "pending-result-candidate-invalid", "Pending result candidate binding is malformed")
    return candidate["kind"], binding


def verify_reconstruction_response(returned_document, context):
    """Verify a runtime provider's reconstruction echo matches the exact request context."""
    _require(returned_document == context, "stale", "runtime-reconstruction-unverified", "Runtime provider did not reconstruct the selected evidence")


def resolve_pr_number(result, supplied):
    """Resolve the exact pull request number for a github-write resume, preferring a caller-supplied value."""
    if isinstance(supplied, dict) and type(supplied.get("pr_number")) is int and supplied["pr_number"] > 0:
        return supplied["pr_number"]
    _require(result is not None, "uncertain", "pr-number-unknown", "A cancelled or uncertain PR write requires an explicit pull request number")
    external = result.get("reconciliation", {}).get("external_identity")
    try:
        raw = base64.b64decode(result["process_result"]["stdout"]["base64"]).decode().strip()
    except (KeyError, TypeError, ValueError, UnicodeError):
        raw = ""
    value = external or raw
    _require(value.startswith("https://github.com/") and "/pull/" in value, "uncertain", "pr-number-unknown", "PR write needs an exact reconciled pull request number")
    try:
        return int(value.rsplit("/", 1)[1])
    except ValueError:
        _fail("uncertain", "pr-number-unknown", "PR write returned an invalid pull request URL")


def _no_duplicates(pairs):
    value = {}
    for key, item in pairs:
        _require(key not in value, "ambiguous", "candidate-output-invalid", "Agent output repeats a JSON key", key)
        value[key] = item
    return value


def decode_candidate(result, expected):
    """Decode and validate an agent execution result's candidate output against its result-record digest."""
    record = result.get("candidate_output")
    _require(result.get("outcome") == "succeeded" and isinstance(record, dict) and set(record) == {"sha256", "size"}, "corrupt", "candidate-output-invalid", "Agent result has no successful candidate output")
    try:
        raw = base64.b64decode(result["process_result"]["stdout"]["base64"], validate=True)
        value = json.loads(raw.decode(), object_pairs_hook=_no_duplicates)
    except (KeyError, TypeError, ValueError, UnicodeError, json.JSONDecodeError, RecursionError) as error:
        _fail("corrupt", "candidate-output-invalid", "Agent output is invalid: %s" % error)
    _require(record == {"sha256": inspector.sha256(raw), "size": len(raw)} and isinstance(value, dict), "corrupt", "candidate-output-invalid", "Agent output does not match its result record")
    return candidate_schema(value, expected)


def validate_plan_candidate(candidate):
    """Validate a plan candidate's schema and return its plan lines with content-hashed units."""
    _require(set(candidate) == {"format", "kind", "plan", "units", "revision"}, "corrupt", "candidate-output-invalid", "Plan candidate has an invalid schema")
    _require(isinstance(candidate["plan"], str) and candidate["plan"].endswith("\n") and not candidate["plan"].endswith("\n\n"), "corrupt", "candidate-output-invalid", "Plan candidate must use one trailing LF")
    lines, units = candidate["plan"].splitlines(True), []
    _require(isinstance(candidate["units"], list) and candidate["units"], "corrupt", "candidate-output-invalid", "Plan candidate has no units")
    for raw in candidate["units"]:
        _require(isinstance(raw, dict) and set(raw) == {"id", "title", "start_line", "end_line", "review_class", "dependencies"}, "corrupt", "candidate-output-invalid", "Plan unit schema is invalid")
        start, end = raw["start_line"], raw["end_line"]
        _require(type(start) is int and type(end) is int and 1 <= start <= end <= len(lines), "corrupt", "candidate-output-invalid", "Plan unit range is invalid")
        unit = copy.deepcopy(raw)
        unit["content_sha256"] = inspector.sha256("".join(lines[start - 1:end]).encode())
        units.append(unit)
    return lines, units


def validate_review_candidate(candidate, snapshot_binding):
    """Validate a review candidate's schema and return its verdict with identified, sorted findings."""
    _require(set(candidate) == {"format", "kind", "verdict", "findings", "pr"}, "corrupt", "candidate-output-invalid", "Review candidate has an invalid schema")
    verdict = candidate["verdict"]
    _require(verdict in {"accepted", "needs-revision", "full-review-required"}, "corrupt", "candidate-output-invalid", "Review verdict is invalid")
    findings = []
    for raw in candidate["findings"]:
        _require(isinstance(raw, dict) and set(raw) == {"unit_ids", "category", "detail"}, "corrupt", "candidate-output-invalid", "Review finding schema is invalid")
        row = {"introduced_plan_binding": snapshot_binding, **raw}
        row["id"] = "finding-" + _digest({"introduced_plan_binding": row["introduced_plan_binding"], "severity": "blocking", "category": row["category"], "unit_ids": row["unit_ids"], "detail": row["detail"]})
        row["severity"] = "blocking"
        findings.append(row)
    findings.sort(key=lambda row: row["id"])
    return verdict, findings


def verify_recovery_challenge(challenge, predecessor, issue, family_run_id):
    """Validate a recovery human-challenge document has the exact schema and confirmation digest."""
    keys = {"format", "issue", "family_run_id", "gate", "decision", "authority_binding", "subjects", "repository_observation_binding", "confirmation", "challenge_sha256"}
    _require(isinstance(challenge, dict) and set(challenge) == keys and isinstance(challenge["subjects"], list), "corrupt", "challenge-stale", "Recovery challenge has an invalid schema")
    core = dict(challenge)
    confirmation, digest = core.pop("confirmation"), core.pop("challenge_sha256")
    _require(core["format"] == RECOVERY_CHALLENGE_FORMAT and core["issue"] == issue and core["family_run_id"] == family_run_id and core["gate"] == "recovery" and core["decision"] == "approve" and core["authority_binding"] == predecessor and core["repository_observation_binding"] is None and digest == _digest(core) and confirmation == "approve recovery %s" % digest, "stale", "challenge-stale", "Recovery challenge is not exact")
