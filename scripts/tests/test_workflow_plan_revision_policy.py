import ast
import base64
import copy
import os
import pathlib
import json
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

from scripts import workflow_cas
from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_plan_revision_policy as policy


REPOSITORY = pathlib.Path(__file__).parents[2]


class RevisionFixture(object):
    """Native #132 evidence fixture for one plan revision hop.

    ``extra_units`` appends additional untouched ordinary units after the
    default ``change``/``tests``/``docs`` trio so preservation-fanout tests
    can exercise more than one byte-identical preserved unit without
    disturbing the default three-unit scenario every other test depends on.
    """

    def __init__(self, impact="local", prior_verdict="needs-revision", extra_units=0):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.store = inspector.resolve_store(self.root)
        self.store.store_dir.mkdir(parents=True)
        self.issue = 125
        self.family = "0123456789abcdef0123456789abcdef"
        self.sequence = 0
        self.extra_units = extra_units
        self.context = self._context()
        self._prior_text = self._plan_text("fix issue\n", extra_units)
        self._current_text = self._plan_text("fix issue carefully\n", extra_units)
        self.prior_plan = self.snapshot(1, self._prior_text.encode(), None)
        self.prior_review = self.review(
            self.prior_plan, None, "full", ["full"] * (3 + extra_units), prior_verdict,
            findings=(prior_verdict != "accepted"),
        )
        self.current_plan = self.snapshot(
            2,
            self._current_text.encode(),
            {"plan_binding": self.prior_plan["binding"], "review_binding": self.prior_review["binding"]},
        )
        self.revision = self.revision_record(impact)

    def close(self):
        self.temporary.cleanup()

    def digest(self, value, field):
        result = copy.deepcopy(value)
        result[field] = inspector.sha256(inspector.canonical_bytes(result))
        return result

    def raw(self, kind, data):
        reference = {"kind": kind, "sha256": inspector.sha256(data), "size": len(data)}
        workflow_cas.publish_immutable(
            inspector.object_path(self.store, reference["sha256"]), data,
            lambda status, code, message: self.fail("%s: %s" % (code, message)),
            temporary_label="revision-policy-test",
        )
        return reference

    def publish(self, decision, subject, documents, migration=None):
        self.sequence += 1
        entries, payloads = [], []
        for path, data in documents:
            payload = self.raw("evidence-payload", data)
            entry = {
                "path": path, "kind": "regular", "mode": "100644",
                "content_sha256": payload["sha256"], "size": payload["size"], "payload": payload,
            }
            entries.append(entry)
            payloads.append({
                "sha256": payload["sha256"], "size": payload["size"],
                "bytes_base64": base64.b64encode(data).decode("ascii"),
            })
        entries.sort(key=lambda entry: entry["path"])
        publication = {
            "format": evidence.PUBLICATION_FORMAT,
            "identity": {
                "issue": self.issue, "run_id": "%032x" % self.sequence,
                "family_run_id": self.family, "correction": None, "run_generation": 0,
                "sequence": self.sequence, "event_tip": "%064x" % self.sequence,
            },
            "decision": decision, "subject": subject,
            "lineage": {"status": "original", "parent_binding": None}, "migration": migration,
            "entries": entries,
            "captures": [{
                "entry_sha256": evidence._entry_digest(entry), "capture_method": "fixture",
                "captured_at": "2026-09-04T00:00:00Z",
                "source": {"type": "workspace", "path": entry["path"]},
                "tool": {"name": "test", "version": "1"},
            } for entry in entries],
            "payloads": payloads,
        }
        return {"binding": evidence.publish(self.root, publication)["binding"], "document": None}

    def _context(self):
        source = self.raw("evidence-payload", b"issue source")
        issue = self.publish({"type": "work-type-issue-snapshot", "id": "issue"}, source, [("context/issue.json", b"{}")])
        baseline = self.publish({"type": "work-type-baseline", "id": "baseline"}, issue["binding"], [("context/baseline.json", b"{}")])
        triage = self.publish({"type": "work-type-triage", "id": "triage"}, baseline["binding"], [("context/triage.json", b"{}")])
        return {"issue_snapshot_binding": issue["binding"], "baseline_binding": baseline["binding"], "triage_binding": triage["binding"]}

    @staticmethod
    def _plan_text(first_line, extra_units=0):
        lines = [first_line, "keep tests\n", "preserve docs\n"]
        lines.extend("extra line %d\n" % index for index in range(extra_units))
        return "".join(lines)

    def units(self, data):
        lines = data.decode("utf-8").splitlines(True)
        base = [
            {
                "id": "change", "title": "Change", "start_line": 1, "end_line": 1,
                "content_sha256": inspector.sha256(lines[0].encode("utf-8")),
                "review_class": "ordinary", "dependencies": [],
            },
            {
                "id": "tests", "title": "Tests", "start_line": 2, "end_line": 2,
                "content_sha256": inspector.sha256(lines[1].encode("utf-8")),
                "review_class": "ordinary", "dependencies": ["change"],
            },
            {
                "id": "docs", "title": "Docs", "start_line": 3, "end_line": 3,
                "content_sha256": inspector.sha256(lines[2].encode("utf-8")),
                "review_class": "ordinary", "dependencies": [],
            },
        ]
        for index in range(self.extra_units):
            line_number = 4 + index
            base.append({
                "id": "extra%d" % index, "title": "Extra %d" % index,
                "start_line": line_number, "end_line": line_number,
                "content_sha256": inspector.sha256(lines[line_number - 1].encode("utf-8")),
                "review_class": "ordinary", "dependencies": [],
            })
        return base

    def snapshot(self, revision, plan_data, predecessor, context=None, migration=None, document_newline=False):
        context = context or self.context
        document = self.digest({
            "format": policy.SNAPSHOT_FORMAT, "issue": self.issue, "family_run_id": self.family,
            "revision": revision, "context": context, "predecessor": predecessor,
            "plan": {"path": policy.PLAN_PATH, "content_sha256": inspector.sha256(plan_data), "size": len(plan_data)},
            "units": self.units(plan_data),
        }, "snapshot_sha256")
        record = self.publish(
            {"type": "plan-snapshot", "id": "snapshot-%s" % document["snapshot_sha256"]},
            context["triage_binding"],
            [
                (policy.PLAN_PATH, plan_data),
                (
                    policy.SNAPSHOT_PATH,
                    inspector.canonical_document(document)
                    if document_newline
                    else inspector.canonical_bytes(document),
                ),
            ],
            migration=migration,
        )
        record["document"] = document
        return record

    def custom_finding(self, plan, unit_ids, severity, category="implementation", detail="Custom finding detail."):
        finding = {
            "introduced_plan_binding": plan["binding"], "severity": severity,
            "category": category, "unit_ids": unit_ids, "detail": detail,
        }
        finding["id"] = "finding-" + inspector.sha256(inspector.canonical_bytes(policy._finding_row_identity(finding)))
        return finding

    def finding(self, plan):
        return self.custom_finding(plan, ["change"], "blocking", "implementation", "Make the change explicit.")

    def review(
        self, plan, revision, mode, methods, verdict, findings=False, outcomes=None,
        preserved_sources=None, subject=None, dependency_status=None, full_review_reason=None,
        covered_unit_ids=None, tamper_coverage_hash=None,
    ):
        all_units = plan["document"]["units"]
        pairs = list(zip(all_units, methods))
        if covered_unit_ids is not None:
            pairs = [(unit, method) for unit, method in pairs if unit["id"] in covered_unit_ids]
        coverage = [
            {
                "unit_id": unit["id"], "content_sha256": unit["content_sha256"], "method": method,
                "source_review_binding": None,
            }
            for unit, method in pairs
        ]
        if mode == "incremental":
            for row in coverage:
                if row["method"] == "preserved":
                    row["source_review_binding"] = (preserved_sources or {}).get(
                        row["unit_id"], self.prior_review["binding"]
                    )
        if tamper_coverage_hash:
            for row in coverage:
                if row["unit_id"] in tamper_coverage_hash:
                    row["content_sha256"] = tamper_coverage_hash[row["unit_id"]]
        if dependency_status is None:
            dependency_status = "complete" if mode == "full" else "bounded"
        incremental_ids = {row["unit_id"] for row in coverage if row["method"] == "incremental"}
        if dependency_status == "complete":
            reviewed_units = [unit["id"] for unit in all_units]
        elif dependency_status == "bounded":
            reviewed_units = [unit["id"] for unit in all_units if unit["id"] in incremental_ids]
        else:
            reviewed_units = [row["unit_id"] for row in coverage]
        if findings is True:
            finding_rows = [self.finding(plan)]
        elif findings:
            finding_rows = list(findings)
        else:
            finding_rows = []
        document = self.digest({
            "format": policy.REVIEW_FORMAT, "issue": self.issue, "family_run_id": self.family,
            "plan_binding": plan["binding"], "revision_binding": revision,
            "mode": mode, "reviewer": {"role": "independent-reviewer", "actor": "reviewer"},
            "coverage": coverage, "prior_finding_outcomes": outcomes or [],
            "findings": finding_rows,
            "dependency_assessment": {
                "status": dependency_status,
                "reviewed_units": reviewed_units,
                "reason": "Reviewed the required scope." if dependency_status != "unbounded" else "Bounded verification is unsafe here.",
            },
            "verdict": verdict, "full_review_reason": full_review_reason,
        }, "review_sha256")
        default_subject = plan["binding"] if revision is None else revision
        record = self.publish(
            {"type": "plan-review", "id": "review-%s" % document["review_sha256"]},
            subject if subject is not None else default_subject,
            [(policy.REVIEW_PATH, inspector.canonical_bytes(document))],
        )
        record["document"] = document
        return record

    def revision_record(self, impact, changes=None, dispositions=None):
        diff = policy._unified_diff(self.prior_plan_data(), self.current_plan_data())
        changes = changes if changes is not None else [
            {"unit_id": "change", "impact": impact, "reason": "Address review finding."}
        ]
        dispositions = dispositions if dispositions is not None else [
            {"finding_id": finding["id"], "status": "addressed", "unit_ids": ["change"], "reason": "Updated the text."}
            for finding in self.prior_review["document"]["findings"]
        ]
        document = self.digest({
            "format": policy.REVISION_FORMAT, "issue": self.issue, "family_run_id": self.family,
            "prior_plan_binding": self.prior_plan["binding"], "prior_review_binding": self.prior_review["binding"],
            "current_plan_binding": self.current_plan["binding"],
            "diff": {
                "format": policy.DIFF_FORMAT, "path": policy.DIFF_PATH,
                "algorithm": "sequence-matcher-unified-3-autojunk-false-v1",
                "old_label": "a/plan.md", "new_label": "b/plan.md", "context_lines": 3,
                "content_sha256": inspector.sha256(diff), "size": len(diff),
            },
            "changes": changes,
            "dispositions": dispositions,
        }, "revision_sha256")
        record = self.publish(
            {"type": "plan-revision", "id": "revision-%s" % document["revision_sha256"]},
            self.prior_review["binding"],
            [(policy.REVISION_PATH, inspector.canonical_bytes(document)), (policy.DIFF_PATH, diff)],
        )
        record["document"] = document
        return record

    def prior_plan_data(self):
        return policy._validate_plan_bytes(self._prior_text.encode())

    def current_plan_data(self):
        return policy._validate_plan_bytes(self._current_text.encode())

    def request(self, review=None, revision=None):
        value = {
            "format": policy.REVISION_REQUEST_FORMAT, "prior_plan": self.prior_plan,
            "prior_review": self.prior_review, "current_plan": self.current_plan,
            "revision": revision or self.revision, "current_review": review,
        }
        return self.digest(value, "request_sha256")


def _unit(unit_id, start, end, review_class="ordinary", content_hash="0" * 64, dependencies=()):
    return {
        "id": unit_id, "title": unit_id, "start_line": start, "end_line": end,
        "content_sha256": content_hash, "review_class": review_class, "dependencies": list(dependencies),
    }


def _fake_snapshot(units, plan_lines, context):
    return {"units": policy.UnitMap(units), "plan_lines": plan_lines, "document": {"context": context}}


class _BoundedProbeStream(object):
    """Context-manager byte source that can outlive ``MAX_REQUEST_BYTES``.

    Used to simulate a stat()/read() TOCTOU race: ``_load_json`` must still
    stop chunked reading at ``MAX_REQUEST_BYTES + 1`` bytes even when the
    underlying stream is willing to keep yielding data forever.
    """

    def __init__(self, total_size):
        self._remaining = total_size
        self.delivered = 0

    def read(self, size=-1):
        if size is None or size < 0:
            size = self._remaining
        take = min(size, self._remaining)
        self._remaining -= take
        self.delivered += take
        return b"x" * take

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False


class PlanRevisionPolicyTest(unittest.TestCase):
    def setUp(self):
        self.fixture = RevisionFixture()

    def tearDown(self):
        self.fixture.close()

    def evaluate(self, request, review=None):
        return policy.evaluate_revision(
            self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
            self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
            self.fixture.revision["binding"]["sha256"],
            None if review is None else review["binding"]["sha256"],
        )

    # ------------------------------------------------------------------
    # Baseline contract
    # ------------------------------------------------------------------

    def test_baseline_no_review_is_review_required(self):
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan, "current_review": None,
        }, "request_sha256")
        result = policy.evaluate_baseline(
            self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
        )
        self.assertEqual("review-required", result["outcome"]["code"])
        self.assertEqual("full", result["review_mode"])
        self.assertEqual(["change", "tests", "docs"], result["required_review_units"])
        self.assertIsNone(result["current_review_binding"])
        self.assertEqual("not-applicable", result["disposition_status"])

    def test_baseline_full_review_accepted_and_needs_revision(self):
        with self.subTest(case="needs-revision"):
            request = self.fixture.digest({
                "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan,
                "current_review": self.fixture.prior_review,
            }, "request_sha256")
            result = policy.evaluate_baseline(
                self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                self.fixture.prior_review["binding"]["sha256"],
            )
            self.assertEqual("technical-review-needs-revision", result["outcome"]["code"])
            self.assertEqual("needs-revision", result["technical_verdict"])

        with self.subTest(case="accepted"):
            accepted_review = self.fixture.review(
                self.fixture.prior_plan, None, "full", ["full", "full", "full"], "accepted", findings=False,
            )
            request = self.fixture.digest({
                "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan,
                "current_review": accepted_review,
            }, "request_sha256")
            result = policy.evaluate_baseline(
                self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                accepted_review["binding"]["sha256"],
            )
            self.assertEqual("technical-review-accepted", result["outcome"]["code"])
            self.assertEqual("accepted", result["technical_verdict"])

    def test_baseline_finding_must_be_introduced_against_exact_plan(self):
        foreign_finding = self.fixture.custom_finding(
            self.fixture.current_plan, ["change"], "blocking", "implementation", "Wrong plan binding.",
        )
        review = self.fixture.review(
            self.fixture.prior_plan, None, "full", ["full", "full", "full"], "needs-revision",
            findings=[foreign_finding],
        )
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan, "current_review": review,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(
                self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"], review["binding"]["sha256"],
            )
        self.assertEqual("invalid-review-schema", error.exception.code)
        self.assertEqual("corrupt", error.exception.status)

    def test_review_document_identity_must_match_its_binding(self):
        document = copy.deepcopy(self.fixture.prior_review["document"])
        document["issue"] += 1
        document.pop("review_sha256")
        document = self.fixture.digest(document, "review_sha256")
        review = self.fixture.publish(
            {"type": "plan-review", "id": "review-%s" % document["review_sha256"]},
            self.fixture.prior_plan["binding"],
            [(policy.REVIEW_PATH, inspector.canonical_bytes(document))],
        )
        review["document"] = document
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": self.fixture.prior_plan,
            "current_review": review,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(
                self.fixture.root,
                request,
                self.fixture.prior_plan["binding"]["sha256"],
                review["binding"]["sha256"],
            )
        self.assertEqual("binding-identity-mismatch", error.exception.code)
        self.assertEqual("stale", error.exception.status)

    def test_unknown_coverage_rows_always_fail_invalid_review_schema(self):
        known = {
            "unit_id": "change",
            "content_sha256": self.fixture.current_plan["document"]["units"][0]["content_sha256"],
            "method": "incremental",
            "source_review_binding": None,
        }
        unknown_a = {
            "unit_id": "unknown-a",
            "content_sha256": "0" * 64,
            "method": "incremental",
            "source_review_binding": None,
        }
        unknown_b = dict(unknown_a, unit_id="unknown-b", content_sha256="1" * 64)
        for label, rows in (
            ("unknown-first", [unknown_a, known]),
            ("unknown-last", [known, unknown_a]),
            ("two-unknown", [unknown_a, unknown_b]),
        ):
            with self.subTest(label=label):
                with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                    policy._validate_coverage(
                        rows,
                        policy.UnitMap(self.fixture.current_plan["document"]["units"]),
                    )
                self.assertEqual("corrupt", error.exception.status)
                self.assertEqual("invalid-review-schema", error.exception.code)

    def test_load_json_rejects_oversize_before_open_and_bounds_growth_race(self):
        path = self.fixture.root / "oversize-request.json"
        path.write_bytes(b"x" * (policy.MAX_REQUEST_BYTES + 1))
        with mock.patch.object(pathlib.Path, "open", side_effect=AssertionError("oversize file was opened")):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy._load_json(path)
        self.assertEqual("unsupported", error.exception.status)
        self.assertEqual("request-too-large", error.exception.code)

        class GrowingStream(object):
            def __init__(self):
                self.remaining = policy.MAX_REQUEST_BYTES + 1
                self.read_sizes = []

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, size):
                self.read_sizes.append(size)
                count = min(size, self.remaining)
                self.remaining -= count
                return b"x" * count

        stream = GrowingStream()
        with mock.patch.object(pathlib.Path, "stat", return_value=mock.Mock(st_size=1)), mock.patch.object(
            pathlib.Path, "open", return_value=stream
        ):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy._load_json(path)
        self.assertEqual("unsupported", error.exception.status)
        self.assertEqual("request-too-large", error.exception.code)
        self.assertLessEqual(max(stream.read_sizes), 64 * 1024)
        self.assertEqual(policy.MAX_REQUEST_BYTES + 1, sum(stream.read_sizes))

    # ------------------------------------------------------------------
    # Plan-size, diff-cost, and diff-determinism contracts
    # ------------------------------------------------------------------

    def test_plan_line_bound_5000_accepted_5001_rejected(self):
        exact = ("x\n" * 5000).encode()
        lines = policy._validate_plan_bytes(exact)
        self.assertEqual(5000, len(lines))
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy._validate_plan_bytes(("x\n" * 5001).encode())
        self.assertEqual("plan-too-many-lines", error.exception.code)
        self.assertEqual("unsupported", error.exception.status)

    def test_public_baseline_line_bound_5000_accepted_5001_rejected(self):
        def published_plan(line_count):
            plan_data = b"x\n" * line_count
            document = self.fixture.digest({
                "format": policy.SNAPSHOT_FORMAT,
                "issue": self.fixture.issue,
                "family_run_id": self.fixture.family,
                "revision": 1,
                "context": self.fixture.context,
                "predecessor": None,
                "plan": {
                    "path": policy.PLAN_PATH,
                    "content_sha256": inspector.sha256(plan_data),
                    "size": len(plan_data),
                },
                "units": [{
                    "id": "all",
                    "title": "All",
                    "start_line": 1,
                    "end_line": line_count,
                    "content_sha256": inspector.sha256(plan_data),
                    "review_class": "ordinary",
                    "dependencies": [],
                }],
            }, "snapshot_sha256")
            record = self.fixture.publish(
                {"type": "plan-snapshot", "id": "snapshot-%s" % document["snapshot_sha256"]},
                self.fixture.context["triage_binding"],
                [
                    (policy.PLAN_PATH, plan_data),
                    (policy.SNAPSHOT_PATH, inspector.canonical_bytes(document)),
                ],
            )
            record["document"] = document
            return record

        exact = published_plan(5000)
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": exact,
            "current_review": None,
        }, "request_sha256")
        result = policy.evaluate_baseline(
            self.fixture.root, request, exact["binding"]["sha256"]
        )
        self.assertEqual("review-required", result["outcome"]["code"])

        over = published_plan(5001)
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": over,
            "current_review": None,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(
                self.fixture.root, request, over["binding"]["sha256"]
            )
        self.assertEqual("unsupported", error.exception.status)
        self.assertEqual("plan-too-many-lines", error.exception.code)

    def test_diff_cost_exceeded_before_constructing_sequence_matcher(self):
        lines = ["same\n"] * 5000
        with mock.patch.object(policy.difflib, "SequenceMatcher") as matcher:
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy._diff_touched_units(lines, lines, None, None)
        self.assertEqual("diff-cost-exceeded", error.exception.code)
        self.assertEqual("unsupported", error.exception.status)
        matcher.assert_not_called()

    def test_golden_diff_vectors_match_public_regeneration(self):
        for old_lines, new_lines, expected in policy._DIFF_GOLDEN_VECTORS:
            self.assertEqual(expected, policy._unified_diff(old_lines, new_lines))
        self.assertEqual(b"", policy._unified_diff(["same\n"], ["same\n"]))

    def test_independent_diff_hunk_and_repeated_line_fixtures(self):
        cases = (
            (
                ["old-a\n"] + ["keep-%d\n" % index for index in range(6)] + ["old-b\n"],
                ["new-a\n"] + ["keep-%d\n" % index for index in range(6)] + ["new-b\n"],
                b"--- a/plan.md\n+++ b/plan.md\n"
                b"@@ -1,8 +1,8 @@\n-old-a\n+new-a\n"
                b" keep-0\n keep-1\n keep-2\n keep-3\n keep-4\n keep-5\n"
                b"-old-b\n+new-b\n",
            ),
            (
                ["old-a\n"] + ["keep-%d\n" % index for index in range(7)] + ["old-b\n"],
                ["new-a\n"] + ["keep-%d\n" % index for index in range(7)] + ["new-b\n"],
                b"--- a/plan.md\n+++ b/plan.md\n"
                b"@@ -1,4 +1,4 @@\n-old-a\n+new-a\n keep-0\n keep-1\n keep-2\n"
                b"@@ -6,4 +6,4 @@\n keep-4\n keep-5\n keep-6\n-old-b\n+new-b\n",
            ),
            (
                ["same\n", "same\n", "old\n", "same\n"],
                ["same\n", "same\n", "new\n", "same\n"],
                b"--- a/plan.md\n+++ b/plan.md\n"
                b"@@ -1,4 +1,4 @@\n same\n same\n-old\n+new\n same\n",
            ),
        )
        for old_lines, new_lines, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(expected, policy._unified_diff(old_lines, new_lines))

    def test_self_consistent_tampered_diff_fails_public_regeneration(self):
        tampered = b"--- a/plan.md\n+++ b/plan.md\n@@ -1,1 +1,1 @@\n-wrong\n+also-wrong\n"
        document = copy.deepcopy(self.fixture.revision["document"])
        document["diff"]["content_sha256"] = inspector.sha256(tampered)
        document["diff"]["size"] = len(tampered)
        document.pop("revision_sha256")
        document = self.fixture.digest(document, "revision_sha256")
        revision = self.fixture.publish(
            {"type": "plan-revision", "id": "revision-%s" % document["revision_sha256"]},
            self.fixture.prior_review["binding"],
            [
                (policy.REVISION_PATH, inspector.canonical_bytes(document)),
                (policy.DIFF_PATH, tampered),
            ],
        )
        revision["document"] = document
        request = self.fixture.request(revision=revision)
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_revision(
                self.fixture.root,
                request,
                self.fixture.prior_plan["binding"]["sha256"],
                self.fixture.prior_review["binding"]["sha256"],
                self.fixture.current_plan["binding"]["sha256"],
                revision["binding"]["sha256"],
            )
        self.assertEqual("corrupt", error.exception.status)
        self.assertEqual("diff-content-mismatch", error.exception.code)

    def test_public_evaluator_reports_runtime_diff_self_check_failure(self):
        previous = policy._diff_self_check_done
        policy._diff_self_check_done = False
        try:
            with mock.patch.object(policy, "_unified_diff", return_value=b"wrong"):
                with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                    self.evaluate(self.fixture.request())
            self.assertEqual("unsupported", error.exception.status)
            self.assertEqual("diff-algorithm-runtime", error.exception.code)
        finally:
            policy._diff_self_check_done = previous

    def test_tampered_diff_and_designation_fail_closed(self):
        request = self.fixture.request()
        request["revision"]["document"]["diff"]["size"] += 1
        request = self.fixture.digest({key: value for key, value in request.items() if key != "request_sha256"}, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            self.evaluate(request)
        self.assertEqual("digest-mismatch", error.exception.code)
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_revision(self.fixture.root, self.fixture.request(), "0" * 64, self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"], self.fixture.revision["binding"]["sha256"])
        self.assertEqual("designated-binding-mismatch", error.exception.code)

    # ------------------------------------------------------------------
    # Changes/dispositions schema completeness
    # ------------------------------------------------------------------

    def test_missing_and_extra_changes_rejected(self):
        with self.subTest(case="missing-change-row"):
            revision = self.fixture.revision_record("local", changes=[])
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("invalid-revision-schema", error.exception.code)

        with self.subTest(case="duplicate-change-row"):
            duplicate = {
                "unit_id": "change",
                "impact": "local",
                "reason": "Duplicate row.",
            }
            revision = self.fixture.revision_record(
                "local", changes=[duplicate, dict(duplicate)]
            )
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root,
                    request,
                    self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"],
                    self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("corrupt", error.exception.status)
            self.assertEqual("invalid-revision-schema", error.exception.code)

        with self.subTest(case="extra-change-row"):
            revision = self.fixture.revision_record("local", changes=[
                {"unit_id": "change", "impact": "local", "reason": "Address review finding."},
                {"unit_id": "docs", "impact": "local", "reason": "Unrelated extra row."},
            ])
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("invalid-revision-schema", error.exception.code)

    def test_missing_and_extra_dispositions_rejected(self):
        original_disposition = {
            "finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "addressed",
            "unit_ids": ["change"], "reason": "Updated the text.",
        }
        with self.subTest(case="missing-disposition-row"):
            revision = self.fixture.revision_record("local", dispositions=[])
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("invalid-revision-schema", error.exception.code)

        with self.subTest(case="duplicate-disposition-row"):
            revision = self.fixture.revision_record(
                "local",
                dispositions=[original_disposition, dict(original_disposition)],
            )
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root,
                    request,
                    self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"],
                    self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("corrupt", error.exception.status)
            self.assertEqual("invalid-revision-schema", error.exception.code)

        with self.subTest(case="extra-disposition-row"):
            extra = {"finding_id": "finding-" + "a" * 64, "status": "addressed", "unit_ids": ["change"], "reason": "Bogus extra disposition."}
            dispositions = sorted([original_disposition, extra], key=lambda row: row["finding_id"])
            revision = self.fixture.revision_record("local", dispositions=dispositions)
            request = self.fixture.request(revision=revision)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    revision["binding"]["sha256"],
                )
            self.assertEqual("invalid-revision-schema", error.exception.code)

    # ------------------------------------------------------------------
    # Stale designated digests per envelope category
    # ------------------------------------------------------------------

    def test_stale_designated_digest_for_each_envelope_category(self):
        wrong = "0" * 64
        with self.subTest(category="trusted-prior-plan-binding"):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, self.fixture.request(), wrong,
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    self.fixture.revision["binding"]["sha256"],
                )
            self.assertEqual("designated-binding-mismatch", error.exception.code)
            self.assertEqual("stale", error.exception.status)

        with self.subTest(category="trusted-prior-review-binding"):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, self.fixture.request(), self.fixture.prior_plan["binding"]["sha256"],
                    wrong, self.fixture.current_plan["binding"]["sha256"], self.fixture.revision["binding"]["sha256"],
                )
            self.assertEqual("designated-binding-mismatch", error.exception.code)

        with self.subTest(category="designated-current-plan-binding"):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, self.fixture.request(), self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], wrong, self.fixture.revision["binding"]["sha256"],
                )
            self.assertEqual("designated-binding-mismatch", error.exception.code)

        with self.subTest(category="designated-revision-binding"):
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, self.fixture.request(), self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"], wrong,
                )
            self.assertEqual("designated-binding-mismatch", error.exception.code)

        with self.subTest(category="designated-current-review-binding"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_revision(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"],
                    self.fixture.prior_review["binding"]["sha256"], self.fixture.current_plan["binding"]["sha256"],
                    self.fixture.revision["binding"]["sha256"], wrong,
                )
            self.assertEqual("designated-binding-mismatch", error.exception.code)

    # ------------------------------------------------------------------
    # Full-mode selection and preservation short-circuiting
    # ------------------------------------------------------------------

    def test_accepted_predecessor_forces_full_mode_without_preservation_derivation(self):
        accepted = RevisionFixture(prior_verdict="accepted")
        try:
            with mock.patch.object(policy, "_derive_preservation") as derive:
                result = policy.evaluate_revision(
                    accepted.root, accepted.request(), accepted.prior_plan["binding"]["sha256"],
                    accepted.prior_review["binding"]["sha256"], accepted.current_plan["binding"]["sha256"],
                    accepted.revision["binding"]["sha256"],
                )
            derive.assert_not_called()
            self.assertEqual("full", result["review_mode"])
            self.assertIn("accepted-prior-review", result["escalation_reasons"])
            self.assertEqual(["change", "tests", "docs"], result["required_review_units"])
            self.assertEqual([], result["eligible_preserved_units"])
            self.assertEqual([], result["verified_preserved_units"])
        finally:
            accepted.close()

    def test_full_review_required_impact_escalates(self):
        escalated = RevisionFixture(impact="full-review-required")
        try:
            result = policy.evaluate_revision(
                escalated.root, escalated.request(), escalated.prior_plan["binding"]["sha256"],
                escalated.prior_review["binding"]["sha256"], escalated.current_plan["binding"]["sha256"],
                escalated.revision["binding"]["sha256"],
            )
            self.assertEqual("full", result["review_mode"])
            self.assertEqual(["planner-declared-full-review"], result["escalation_reasons"])
            self.assertEqual(["change", "tests", "docs"], result["required_review_units"])
        finally:
            escalated.close()

    def test_sensitive_unit_change_uses_prior_class_for_removed_and_current_class_for_added_or_retained(self):
        context = {"a": 1}
        with self.subTest(case="removed-unit-uses-prior-class"):
            prior = _fake_snapshot(
                [_unit("keep", 1, 1, content_hash="1" * 64), _unit("scope-unit", 2, 2, review_class="scope", content_hash="2" * 64)],
                ["a\n", "b\n"], context,
            )
            current = _fake_snapshot([_unit("keep", 1, 1, content_hash="1" * 64)], ["a\n"], context)
            prior_review = {"schema": {"verdict": "needs-revision"}}
            revision = {"schema": {"changes": [{"unit_id": "scope-unit", "impact": "local"}]}}
            _changed, reasons = policy._changes_and_escalations(prior, current, prior_review, revision)
            self.assertIn("sensitive-unit-changed", reasons)
            self.assertIn("unit-set-changed", reasons)
            self.assertIn("diff-unit-mapping-mismatch", reasons)

        with self.subTest(case="added-unit-uses-current-class"):
            prior = _fake_snapshot([_unit("keep", 1, 1, content_hash="1" * 64)], ["a\n"], context)
            current = _fake_snapshot(
                [_unit("keep", 1, 1, content_hash="1" * 64), _unit("scope-unit", 2, 2, review_class="architecture", content_hash="2" * 64)],
                ["a\n", "b\n"], context,
            )
            prior_review = {"schema": {"verdict": "needs-revision"}}
            revision = {"schema": {"changes": [{"unit_id": "scope-unit", "impact": "local"}]}}
            _changed, reasons = policy._changes_and_escalations(prior, current, prior_review, revision)
            self.assertIn("sensitive-unit-changed", reasons)
            self.assertIn("unit-set-changed", reasons)
            self.assertIn("diff-unit-mapping-mismatch", reasons)

        with self.subTest(case="retained-unit-uses-current-class"):
            prior = _fake_snapshot([_unit("keep", 1, 1, review_class="ordinary", content_hash="1" * 64)], ["a\n"], context)
            current = _fake_snapshot([_unit("keep", 1, 1, review_class="architecture", content_hash="2" * 64)], ["x\n"], context)
            prior_review = {"schema": {"verdict": "needs-revision"}}
            revision = {"schema": {"changes": [{"unit_id": "keep", "impact": "local"}]}}
            _changed, reasons = policy._changes_and_escalations(prior, current, prior_review, revision)
            self.assertIn("sensitive-unit-changed", reasons)
            self.assertNotIn("diff-unit-mapping-mismatch", reasons)

    # ------------------------------------------------------------------
    # #116 context chain and binding subject links
    # ------------------------------------------------------------------

    def test_context_chain_mismatch_is_typed_stale(self):
        # Build a genuinely broken, freshly published #116 context chain: the
        # triage record's actual evidence subject names the issue snapshot
        # instead of the baseline, so the generic #132 subject chain #125
        # verifies is inconsistent even though every individual binding is a
        # well-formed, self-consistent evidence-binding reference.
        source = self.fixture.raw("evidence-payload", b"issue source 2")
        issue2 = self.fixture.publish({"type": "work-type-issue-snapshot", "id": "issue-2"}, source, [("context/issue2.json", b"{}")])
        baseline2 = self.fixture.publish({"type": "work-type-baseline", "id": "baseline-2"}, issue2["binding"], [("context/baseline2.json", b"{}")])
        triage2 = self.fixture.publish({"type": "work-type-triage", "id": "triage-2"}, issue2["binding"], [("context/triage2.json", b"{}")])
        broken_context = {
            "issue_snapshot_binding": issue2["binding"], "baseline_binding": baseline2["binding"],
            "triage_binding": triage2["binding"],
        }
        broken_plan = self.fixture.snapshot(
            1, b"broken context chain\nkeep tests\npreserve docs\n", None, context=broken_context,
        )
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT, "plan": broken_plan, "current_review": None,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(self.fixture.root, request, broken_plan["binding"]["sha256"])
        self.assertEqual("stale", error.exception.status)
        self.assertEqual("binding-subject-mismatch", error.exception.code)

    def test_current_review_subject_mismatch_is_stale(self):
        with self.subTest(case="baseline-review-subject"):
            review = self.fixture.review(
                self.fixture.prior_plan, None, "full", ["full", "full", "full"], "needs-revision",
                findings=True, subject=self.fixture.current_plan["binding"],
            )
            request = self.fixture.digest({
                "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan, "current_review": review,
            }, "request_sha256")
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                policy.evaluate_baseline(
                    self.fixture.root, request, self.fixture.prior_plan["binding"]["sha256"], review["binding"]["sha256"],
                )
            self.assertEqual("binding-subject-mismatch", error.exception.code)

        with self.subTest(case="revision-review-subject"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
                subject=self.fixture.prior_review["binding"],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("current-review-subject-mismatch", error.exception.code)

        with self.subTest(case="null-revision-binding-is-baseline-review"):
            review = self.fixture.review(
                self.fixture.current_plan,
                None,
                "full",
                ["full", "full", "full"],
                "accepted",
                findings=[],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("stale", error.exception.status)
            self.assertEqual(
                "baseline-review-in-revision-evaluation",
                error.exception.code,
            )

        with self.subTest(case="missing-revision-binding-is-corrupt-schema"):
            document = copy.deepcopy(review["document"])
            document.pop("revision_binding")
            document.pop("review_sha256")
            document = self.fixture.digest(document, "review_sha256")
            missing_key_review = self.fixture.publish(
                {
                    "type": "plan-review",
                    "id": "review-%s" % document["review_sha256"],
                },
                self.fixture.revision["binding"],
                [(policy.REVIEW_PATH, inspector.canonical_bytes(document))],
            )
            missing_key_review["document"] = document
            request = self.fixture.request(missing_key_review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, missing_key_review)
            self.assertEqual("corrupt", error.exception.status)
            self.assertEqual("invalid-review-schema", error.exception.code)

    def test_public_review_inputs_require_inline_envelopes(self):
        baseline = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": self.fixture.prior_plan,
            "current_review": self.fixture.prior_review["binding"],
        }, "request_sha256")
        with self.subTest(case="baseline-current-review"), self.assertRaises(
            policy.PlanRevisionPolicyFailure
        ) as error:
            policy.evaluate_baseline(
                self.fixture.root,
                baseline,
                self.fixture.prior_plan["binding"]["sha256"],
                self.fixture.prior_review["binding"]["sha256"],
            )
        self.assertEqual("corrupt", error.exception.status)
        self.assertEqual(
            "invalid-current-review-envelope-schema", error.exception.code
        )

        prior_request = self.fixture.request()
        prior_request["prior_review"] = self.fixture.prior_review["binding"]
        prior_request.pop("request_sha256")
        prior_request = self.fixture.digest(prior_request, "request_sha256")
        with self.subTest(case="prior-review"), self.assertRaises(
            policy.PlanRevisionPolicyFailure
        ) as error:
            self.evaluate(prior_request)
        self.assertEqual("corrupt", error.exception.status)
        self.assertEqual(
            "invalid-prior-review-envelope-schema", error.exception.code
        )

        current_review = self.fixture.review(
            self.fixture.current_plan,
            self.fixture.revision["binding"],
            "incremental",
            ["incremental", "incremental", "preserved"],
            "accepted",
            findings=[],
            outcomes=[{
                "finding_id": self.fixture.finding(self.fixture.prior_plan)["id"],
                "status": "resolved",
                "replacement_finding_id": None,
                "reason": "Fixed.",
            }],
        )
        current_request = self.fixture.request()
        current_request["current_review"] = current_review["binding"]
        current_request.pop("request_sha256")
        current_request = self.fixture.digest(current_request, "request_sha256")
        with self.subTest(case="current-review"), self.assertRaises(
            policy.PlanRevisionPolicyFailure
        ) as error:
            policy.evaluate_revision(
                self.fixture.root,
                current_request,
                self.fixture.prior_plan["binding"]["sha256"],
                self.fixture.prior_review["binding"]["sha256"],
                self.fixture.current_plan["binding"]["sha256"],
                self.fixture.revision["binding"]["sha256"],
                current_review["binding"]["sha256"],
            )
        self.assertEqual("corrupt", error.exception.status)
        self.assertEqual(
            "invalid-current-review-envelope-schema", error.exception.code
        )

    # ------------------------------------------------------------------
    # Incremental coverage completeness
    # ------------------------------------------------------------------

    def test_incremental_coverage_required_units_and_stale_hash_rejected(self):
        with self.subTest(case="missing-required-unit-marked-preserved"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "preserved", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("incomplete-review-coverage", error.exception.code)
            self.assertEqual("denied", error.exception.status)

        with self.subTest(case="incremental-review-cannot-use-method-full"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["full", "incremental", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("invalid-review-schema", error.exception.code)

        with self.subTest(case="stale-or-foreign-coverage-hash-rejected"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
                tamper_coverage_hash={"docs": "0" * 64},
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("invalid-review-schema", error.exception.code)

    def test_escalated_review_still_requires_full_prior_finding_adjudication(self):
        review = self.fixture.review(
            self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
            ["incremental"], "full-review-required",
            dependency_status="unbounded", full_review_reason="Cannot safely bound this change.",
            covered_unit_ids=["change"],
        )
        request = self.fixture.request(review)
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            self.evaluate(request, review)
        self.assertEqual("invalid-review-schema", error.exception.code)
        self.assertEqual("corrupt", error.exception.status)

    # ------------------------------------------------------------------
    # Prior-finding adjudication: remains/resolved/superseded
    # ------------------------------------------------------------------

    def test_prior_finding_adjudication_remains_resolved_superseded_rules(self):
        prior_finding = self.fixture.finding(self.fixture.prior_plan)

        with self.subTest(case="remains-without-verbatim-carry-is-rejected"):
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": prior_finding["id"], "status": "remains", "replacement_finding_id": None, "reason": "Still open."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("carried-finding-missing", error.exception.code)

        with self.subTest(case="resolved-recurrence-is-rejected"):
            recurring = dict(prior_finding)
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "needs-revision",
                findings=[recurring],
                outcomes=[{"finding_id": prior_finding["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Believed fixed."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("invalid-review-schema", error.exception.code)

        with self.subTest(case="superseded-with-lower-severity-is-rejected"):
            filler = self.fixture.custom_finding(self.fixture.current_plan, ["tests"], "blocking", "implementation", "Unrelated blocking concern.")
            replacement = self.fixture.custom_finding(self.fixture.current_plan, ["change"], "info", "implementation", "Lesser follow-up concern.")
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "needs-revision",
                findings=[filler, replacement],
                outcomes=[{"finding_id": prior_finding["id"], "status": "superseded", "replacement_finding_id": replacement["id"], "reason": "Superseded by a narrower concern."}],
            )
            request = self.fixture.request(review)
            with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
                self.evaluate(request, review)
            self.assertEqual("invalid-review-schema", error.exception.code)

        with self.subTest(case="superseded-with-equal-or-higher-severity-is-accepted"):
            replacement = self.fixture.custom_finding(self.fixture.current_plan, ["change"], "blocking", "implementation", "Equal-severity follow-up concern.")
            review = self.fixture.review(
                self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved"], "needs-revision",
                findings=[replacement],
                outcomes=[{"finding_id": prior_finding["id"], "status": "superseded", "replacement_finding_id": replacement["id"], "reason": "Superseded by an equal-severity concern."}],
            )
            request = self.fixture.request(review)
            result = self.evaluate(request, review)
            self.assertEqual("technical-review-needs-revision", result["outcome"]["code"])

    # ------------------------------------------------------------------
    # Preservation anchoring, sibling rejection, caching, and fan-out
    # ------------------------------------------------------------------

    def test_preservation_anchor_exact_triple_and_sibling_rejection(self):
        sibling_review = self.fixture.review(
            self.fixture.prior_plan, None, "full", ["full", "full", "full"], "accepted", findings=False,
        )
        review = self.fixture.review(
            self.fixture.current_plan, self.fixture.revision["binding"], "incremental",
            ["incremental", "incremental", "preserved"], "accepted", findings=[],
            outcomes=[{"finding_id": self.fixture.finding(self.fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
            preserved_sources={"docs": sibling_review["binding"]},
        )
        request = self.fixture.request(review)
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            self.evaluate(request, review)
        self.assertEqual("unanchored-preserved-coverage", error.exception.code)
        self.assertEqual("denied", error.exception.status)

    def test_preservation_source_loaded_once_for_duplicate_coverage_rows(self):
        fixture = RevisionFixture(extra_units=1)
        try:
            review = fixture.review(
                fixture.current_plan, fixture.revision["binding"], "incremental",
                ["incremental", "incremental", "preserved", "preserved"], "accepted", findings=[],
                outcomes=[{"finding_id": fixture.finding(fixture.prior_plan)["id"], "status": "resolved", "replacement_finding_id": None, "reason": "Fixed."}],
            )
            request = fixture.request(review)
            digest = fixture.prior_review["binding"]["sha256"]
            matching_calls = []
            original_project = policy._project

            def counting_project(root, reference):
                if reference.get("sha256") == digest:
                    matching_calls.append(reference)
                return original_project(root, reference)

            with mock.patch.object(policy, "_project", side_effect=counting_project):
                result = policy.evaluate_revision(
                    fixture.root, request, fixture.prior_plan["binding"]["sha256"],
                    fixture.prior_review["binding"]["sha256"], fixture.current_plan["binding"]["sha256"],
                    fixture.revision["binding"]["sha256"], review["binding"]["sha256"],
                )
            # One projection when the prior review itself is verified, plus at
            # most one more the first time a preserved row resolves that same
            # source; the second duplicate row must hit the cache rather than
            # re-project, so total calls stay well below the two preserved rows.
            self.assertEqual(2, len(matching_calls))
            self.assertEqual(
                ["docs", "extra0"], [row["unit_id"] for row in result["verified_preserved_units"]],
            )
        finally:
            fixture.close()

    def test_preservation_fanout_over_32_forces_full_without_projection(self):
        unit_ids = ["unit-%02d" % index for index in range(40)]
        units = [
            _unit(unit_id, index + 1, index + 1, content_hash=inspector.sha256(("line %d\n" % index).encode()))
            for index, unit_id in enumerate(unit_ids)
        ]
        unit_map = policy.UnitMap(units)
        current = {"units": unit_map, "document": {"issue": self.fixture.issue, "family_run_id": self.fixture.family}}
        prior = {"units": unit_map}
        coverage_by_id = {
            unit_id: {
                "unit_id": unit_id, "content_sha256": unit_map.hash_by_id[unit_id], "method": "preserved",
                "source_review_binding": {"kind": "evidence-binding", "sha256": "%064x" % index, "size": 1},
            }
            for index, unit_id in enumerate(unit_ids)
        }
        prior_review = {"schema": {"coverage_by_id": coverage_by_id}, "reference": self.fixture.prior_review["binding"]}
        with mock.patch.object(policy, "_source_review") as source_review:
            eligible, reasons = policy._derive_preservation(self.fixture.root, prior, current, prior_review, required=set())
        self.assertEqual([], eligible)
        self.assertEqual({"preservation-fanout-exceeded"}, reasons)
        source_review.assert_not_called()

    def test_supplied_incremental_review_with_overfanout_sources_is_unsupported(self):
        revision_reference = self.fixture.revision["binding"]
        coverage_by_id = {
            "unit-%02d" % index: {
                "unit_id": "unit-%02d" % index, "content_sha256": "0" * 64, "method": "preserved",
                "source_review_binding": {"kind": "evidence-binding", "sha256": "%064x" % index, "size": 1},
            }
            for index in range(33)
        }
        current_review = {
            "schema": {
                "revision_binding": revision_reference, "mode": "incremental", "verdict": "needs-revision",
                "findings": [], "findings_by_id": {}, "prior_finding_outcomes": [],
                "coverage_by_id": coverage_by_id,
            }
        }
        prior_review = {"schema": {"findings_by_id": {}}}
        revision = {"reference": revision_reference}
        current = {"reference": self.fixture.current_plan["binding"]}
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy._validate_current_review(
                self.fixture.root, current_review, prior_review, revision, current, "incremental", set(), []
            )
        self.assertEqual("preserved-source-fanout", error.exception.code)
        self.assertEqual("unsupported", error.exception.status)

    # ------------------------------------------------------------------
    # Dependency graph: deep acyclic chain and cycle detection
    # ------------------------------------------------------------------

    def test_dependency_chain_of_1000_units_does_not_recurse(self):
        unit_ids = ["unit-%04d" % index for index in range(1000)]
        units_raw = []
        for index, unit_id in enumerate(unit_ids):
            dependencies = [unit_ids[index - 1]] if index else []
            units_raw.append(_unit(
                unit_id, index + 1, index + 1,
                content_hash=inspector.sha256(("line %d\n" % index).encode()), dependencies=dependencies,
            ))
        started = time.monotonic()
        parsed, ids, total_lines = policy._validate_units_schema(units_raw)
        elapsed = time.monotonic() - started
        self.assertEqual(1000, len(parsed))
        self.assertEqual(1000, len(ids))
        self.assertEqual(1000, total_lines)
        self.assertLess(elapsed, 5.0)

    def test_dependency_cycle_is_typed_ambiguous_failure(self):
        units_raw = [
            _unit("a", 1, 1, content_hash=inspector.sha256(b"a\n"), dependencies=["b"]),
            _unit("b", 2, 2, content_hash=inspector.sha256(b"b\n"), dependencies=["a"]),
        ]
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy._validate_units_schema(units_raw)
        self.assertEqual("dependency-cycle", error.exception.code)
        self.assertEqual("ambiguous", error.exception.status)

    # ------------------------------------------------------------------
    # Evidence compatibility, CAS failures, replay, and escalation
    # ------------------------------------------------------------------

    def test_migrated_snapshot_is_unsupported(self):
        migrated = self.fixture.snapshot(
            1,
            b"migrated plan\nkeep tests\npreserve docs\n",
            None,
            migration={
                "adapter": evidence.V4_ADAPTER_FORMAT,
                "source": self.fixture.context["triage_binding"],
            },
        )
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": migrated,
            "current_review": None,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(
                self.fixture.root, request, migrated["binding"]["sha256"]
            )
        self.assertEqual("unsupported", error.exception.status)
        self.assertEqual("migration-unsupported", error.exception.code)

    def test_missing_and_tampered_cas_payloads_fail_closed(self):
        for label, mutation, expected_status in (
            ("missing", "unlink", "missing"),
            ("tampered", "tamper", "corrupt"),
        ):
            fixture = RevisionFixture()
            try:
                projection = evidence.project(
                    fixture.root, fixture.prior_plan["binding"]
                )
                plan_entry = next(
                    entry
                    for entry in projection["entries"]
                    if entry["path"] == policy.PLAN_PATH
                )
                payload_path = inspector.object_path(
                    fixture.store, plan_entry["payload"]["sha256"]
                )
                if mutation == "unlink":
                    payload_path.unlink()
                else:
                    payload_path.write_bytes(b"tampered")
                request = fixture.digest({
                    "format": policy.BASELINE_REQUEST_FORMAT,
                    "plan": fixture.prior_plan,
                    "current_review": None,
                }, "request_sha256")
                with self.subTest(label=label), self.assertRaises(
                    policy.PlanRevisionPolicyFailure
                ) as error:
                    policy.evaluate_baseline(
                        fixture.root,
                        request,
                        fixture.prior_plan["binding"]["sha256"],
                    )
                self.assertEqual(expected_status, error.exception.status)
            finally:
                fixture.close()

    def test_structured_payload_with_trailing_newline_is_rejected(self):
        snapshot = self.fixture.snapshot(
            1,
            b"newline record\nkeep tests\npreserve docs\n",
            None,
            document_newline=True,
        )
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT,
            "plan": snapshot,
            "current_review": None,
        }, "request_sha256")
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy.evaluate_baseline(
                self.fixture.root, request, snapshot["binding"]["sha256"]
            )
        self.assertIn(
            error.exception.code,
            {"document-record-entry-mismatch", "inline-document-payload-mismatch"},
        )

    def test_repeated_evaluation_is_byte_identical(self):
        request = self.fixture.request()
        first = self.evaluate(request)
        second = self.evaluate(copy.deepcopy(request))
        self.assertEqual(
            inspector.canonical_bytes(first),
            inspector.canonical_bytes(second),
        )

    def test_preserved_to_preserved_source_is_rejected(self):
        prior_finding = self.fixture.finding(self.fixture.prior_plan)
        review = self.fixture.review(
            self.fixture.current_plan,
            self.fixture.revision["binding"],
            "incremental",
            ["incremental", "incremental", "preserved"],
            "accepted",
            findings=[],
            outcomes=[{
                "finding_id": prior_finding["id"],
                "status": "resolved",
                "replacement_finding_id": None,
                "reason": "Fixed.",
            }],
        )
        docs_hash = self.fixture.current_plan["document"]["units"][2][
            "content_sha256"
        ]
        with self.assertRaises(policy.PlanRevisionPolicyFailure) as error:
            policy._source_review(
                self.fixture.root,
                review["binding"],
                self.fixture.issue,
                self.fixture.family,
                "docs",
                docs_hash,
            )
        self.assertEqual("denied", error.exception.status)
        self.assertEqual("unanchored-preserved-coverage", error.exception.code)

    def test_successful_technical_review_escalation_adjudicates_findings(self):
        prior_finding = self.fixture.finding(self.fixture.prior_plan)
        review = self.fixture.review(
            self.fixture.current_plan,
            self.fixture.revision["binding"],
            "incremental",
            ["incremental"],
            "full-review-required",
            findings=[],
            outcomes=[{
                "finding_id": prior_finding["id"],
                "status": "resolved",
                "replacement_finding_id": None,
                "reason": "The local concern is resolved.",
            }],
            dependency_status="unbounded",
            full_review_reason="The wider dependency boundary is ambiguous.",
            covered_unit_ids=["change"],
        )
        result = self.evaluate(self.fixture.request(review), review)
        self.assertEqual("technical-review-escalated", result["outcome"]["code"])
        self.assertEqual("full-review-required", result["technical_verdict"])

    # ------------------------------------------------------------------
    # CLI parity and typed failure hygiene
    # ------------------------------------------------------------------

    def test_direct_and_package_cli_match(self):
        request = self.fixture.digest({
            "format": policy.BASELINE_REQUEST_FORMAT, "plan": self.fixture.prior_plan,
            "current_review": None,
        }, "request_sha256")
        path = self.fixture.root / "baseline-request.json"
        path.write_bytes(inspector.canonical_document(request))
        arguments = [
            "evaluate-baseline", "--root", str(self.fixture.root), "--request", str(path),
            "--designated-plan-binding", self.fixture.prior_plan["binding"]["sha256"],
        ]
        commands = (
            [sys.executable, str(REPOSITORY / "scripts" / "workflow_plan_revision_policy.py")] + arguments,
            [sys.executable, "-m", "scripts.workflow_plan_revision_policy"] + arguments,
        )
        results = [subprocess.run(command, cwd=str(REPOSITORY), capture_output=True, check=True) for command in commands]
        self.assertEqual(results[0].stdout, results[1].stdout)
        self.assertEqual("review-required", json.loads(results[0].stdout)["outcome"]["code"])

        revision_request = self.fixture.request()
        revision_path = self.fixture.root / "revision-request.json"
        revision_path.write_bytes(inspector.canonical_document(revision_request))
        revision_arguments = [
            "evaluate-revision", "--root", str(self.fixture.root), "--request", str(revision_path),
            "--trusted-prior-plan-binding", self.fixture.prior_plan["binding"]["sha256"],
            "--trusted-prior-review-binding", self.fixture.prior_review["binding"]["sha256"],
            "--designated-current-plan-binding", self.fixture.current_plan["binding"]["sha256"],
            "--designated-revision-binding", self.fixture.revision["binding"]["sha256"],
        ]
        revision_commands = (
            [sys.executable, str(REPOSITORY / "scripts" / "workflow_plan_revision_policy.py")] + revision_arguments,
            [sys.executable, "-m", "scripts.workflow_plan_revision_policy"] + revision_arguments,
        )
        revision_results = [subprocess.run(command, cwd=str(REPOSITORY), capture_output=True, check=True) for command in revision_commands]
        self.assertEqual(revision_results[0].stdout, revision_results[1].stdout)
        self.assertEqual("review-required", json.loads(revision_results[0].stdout)["outcome"]["code"])

    def test_cli_typed_failure_has_no_traceback_and_correct_exit_code(self):
        arguments = [
            "evaluate-baseline", "--root", str(self.fixture.root),
            "--request", str(self.fixture.root / "missing-request.json"),
            "--designated-plan-binding", "0" * 64,
        ]
        result = subprocess.run(
            [sys.executable, str(REPOSITORY / "scripts" / "workflow_plan_revision_policy.py")] + arguments,
            cwd=str(REPOSITORY), capture_output=True,
        )
        self.assertEqual(policy.OUTCOME_EXIT_CODES["missing"], result.returncode)
        self.assertEqual(b"", result.stderr)
        self.assertNotIn(b"Traceback", result.stdout)
        document = json.loads(result.stdout)
        self.assertEqual("chess-echo-plan-revision-policy-failure-v1", document["format"])
        self.assertEqual("request-missing", document["outcome"]["code"])
        self.assertNotIn(str(REPOSITORY), json.dumps(document))

    # ------------------------------------------------------------------
    # Read-only and dependency-boundary hygiene
    # ------------------------------------------------------------------

    def test_evaluate_revision_performs_no_filesystem_mutation(self):
        forbidden = AssertionError("policy attempted filesystem mutation")
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
            result = self.evaluate(self.fixture.request())
        self.assertEqual("review-required", result["outcome"]["code"])

    def test_module_source_uses_no_forbidden_imports(self):
        tree = ast.parse((REPOSITORY / "scripts" / "workflow_plan_revision_policy.py").read_text())
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported.add(node.module.split(".")[0])
        forbidden = {"subprocess", "socket", "http", "urllib", "requests", "shutil", "tempfile"}
        self.assertEqual(set(), imported & forbidden)
        self.assertEqual(
            {
                "argparse", "collections", "copy", "difflib", "functools", "json", "pathlib", "re", "sys",
                "workflow_evidence", "workflow_inspector", "scripts",
            },
            imported,
        )


if __name__ == "__main__":
    unittest.main()
