import base64
import copy
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import workflow_cas
from scripts import workflow_evidence as evidence
from scripts import workflow_inspector as inspector
from scripts import workflow_work_type_policy as policy


SCRIPTS = pathlib.Path(__file__).parents[1]
REPOSITORY = SCRIPTS.parent
CONFIG = REPOSITORY / ".github" / "agent-workflow.json"
ZERO = "0" * 40


class WorkTypeFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(dir=REPOSITORY)
        self.root = pathlib.Path(self.temporary.name)
        subprocess.run(
            ["git", "init", "-q", str(self.root)],
            check=True,
            capture_output=True,
        )
        self.store = inspector.resolve_store(self.root)
        self.store.store_dir.mkdir(parents=True)
        (self.root / "scripts").mkdir()
        (self.root / "scripts" / "tests").mkdir()
        (self.root / "scripts" / "tests" / "test_workflow_work_type_policy.py").write_text(
            "import unittest\n"
            "class WorkflowWorkTypePolicyTest(unittest.TestCase):\n"
            "    def test_all_four_classifications_have_exact_inactive_routes(self):\n"
            "        pass\n"
        )
        self.issue = 116
        self.family = "0123456789abcdef0123456789abcdef"
        self.sequence = 0
        source_data = b"trusted issue source\n"
        self.source = self.publish_raw("issue-snapshot", source_data)
        self.issue_document = self.with_digest(
            {
                "format": policy.ISSUE_SNAPSHOT_FORMAT,
                "repository": "NathanZK/ChessEcho",
                "issue": self.issue,
                "title": "Agent Workflow: Add Work-Type Triage",
                "url": "https://github.com/NathanZK/ChessEcho/issues/116",
                "body": "Explicitly classify implementation, design, research, or documentation.",
                "labels": ["P1", "enhancement"],
                "source": self.source,
                "captured_at": "2026-09-04T00:00:00Z",
            },
            "snapshot_sha256",
        )
        self.issue_envelope = self.publish_document(
            "issue-snapshot",
            self.issue_document,
            self.source,
        )
        self.baseline_document = self.make_baseline()
        self.baseline_envelope = self.publish_document(
            "baseline",
            self.baseline_document,
            self.issue_envelope["binding"],
        )

    def close(self):
        self.temporary.cleanup()

    @staticmethod
    def with_digest(value, field):
        result = copy.deepcopy(value)
        result[field] = inspector.sha256(inspector.canonical_bytes(result))
        return result

    def publish_raw(self, kind, data):
        reference = {
            "kind": kind,
            "sha256": inspector.sha256(data),
            "size": len(data),
        }

        def fail(_status, code, message):
            raise AssertionError("%s: %s" % (code, message))

        workflow_cas.publish_immutable(
            inspector.object_path(self.store, reference["sha256"]),
            data,
            fail,
            temporary_label="work-type-test",
        )
        return reference

    def entry(self, path, data):
        payload = self.publish_raw("evidence-payload", data)
        return {
            "path": path,
            "kind": "regular",
            "mode": "100644",
            "content_sha256": payload["sha256"],
            "size": payload["size"],
            "payload": payload,
        }

    def publish_document(self, kind, document, subject, extra=()):
        self.sequence += 1
        record_path, decision_type, _template, _digest_field = policy.DOCUMENT_CONTRACTS[
            kind
        ]
        document_data = inspector.canonical_bytes(document)
        entries = [self.entry(record_path, document_data)]
        payloads = [
            {
                "sha256": entries[0]["content_sha256"],
                "size": entries[0]["size"],
                "bytes_base64": base64.b64encode(document_data).decode("ascii"),
            }
        ]
        for path, data in extra:
            entry = self.entry(path, data)
            entries.append(entry)
            payloads.append(
                {
                    "sha256": entry["content_sha256"],
                    "size": entry["size"],
                    "bytes_base64": base64.b64encode(data).decode("ascii"),
                }
            )
        entries.sort(key=lambda item: item["path"].encode("utf-8"))
        captures = [
            {
                "entry_sha256": evidence._entry_digest(entry),
                "capture_method": "fixture",
                "captured_at": "2026-09-04T00:00:00Z",
                "source": {"type": "workspace", "path": entry["path"]},
                "tool": {"name": "work-type-test", "version": "1"},
            }
            for entry in entries
        ]
        publication = {
            "format": evidence.PUBLICATION_FORMAT,
            "identity": {
                "issue": self.issue,
                "run_id": self.family,
                "family_run_id": self.family,
                "correction": None,
                "run_generation": 0,
                "sequence": self.sequence,
                "event_tip": "%064x" % self.sequence,
            },
            "decision": {
                "type": decision_type,
                "id": policy._decision_id(kind, document),
            },
            "subject": subject,
            "lineage": {"status": "original", "parent_binding": None},
            "migration": None,
            "entries": entries,
            "captures": captures,
            "payloads": payloads,
        }
        reference = evidence.publish(self.root, publication)["binding"]
        return {"binding": reference, "document": copy.deepcopy(document)}

    def make_baseline(self):
        config_data = CONFIG.read_bytes()
        config = json.loads(config_data)
        target_name, profiles = policy._normalized_config_profiles(config)
        limits = [
            {
                "profile": profile["id"],
                "check": check["name"],
                **policy.PROFILE_LIMITS,
            }
            for profile in profiles
            for check in profile["checks"]
        ]
        limits.sort(key=lambda item: (item["profile"], item["check"]))
        templates = [
            {
                "id": "python-echo-test-id",
                "profiles": ["workflow-tooling"],
                "command_prefix": [sys.executable, "-m", "unittest"],
                "cwd": ".",
                "selector_kind": "test-id",
                "max_selectors": 1,
                "max_selector_bytes": 256,
                "timeout_ms": 5_000,
                "grace_ms": 100,
                "output_limit_bytes": 4_096,
            },
            {
                "id": "relative-path-test",
                "profiles": ["workflow-tooling"],
                "command_prefix": [sys.executable, "-m", "unittest"],
                "cwd": "scripts",
                "selector_kind": "relative-path",
                "max_selectors": 1,
                "max_selector_bytes": 128,
                "timeout_ms": 5_000,
                "grace_ms": 100,
                "output_limit_bytes": 4_096,
            },
        ]
        commit = "1" * 40
        tree = "2" * 40
        value = {
            "format": policy.BASELINE_FORMAT,
            "repository": "NathanZK/ChessEcho",
            "issue": self.issue,
            "family_run_id": self.family,
            "issue_snapshot_binding": self.issue_envelope["binding"],
            "target_base": {
                "name": target_name,
                "ref": "refs/remotes/origin/%s" % target_name,
                "commit": commit,
                "tree": tree,
            },
            "config": {
                "path": ".github/agent-workflow.json",
                "blob_oid": policy._git_blob_oid(config_data, 40),
                "content_sha256": inspector.sha256(config_data),
                "size": len(config_data),
                "bytes_base64": base64.b64encode(config_data).decode("ascii"),
            },
            "profiles": profiles,
            "profile_check_limits": limits,
            "targeted_templates": templates,
        }
        return self.with_digest(value, "baseline_sha256")

    def classification(self, work_type, storage=None):
        storage = storage or ("git" if work_type != "research" else "evidence-cas")
        kind = {
            "implementation": "implementation-change",
            "design": "design-document",
            "research": "research-report",
            "documentation": "documentation-change",
        }[work_type]
        profile = {
            "implementation": "workflow-tooling",
            "design": "design-artifact",
            "research": "research-artifact",
            "documentation": "documentation-content-diff",
        }[work_type]
        location = {
            "implementation": "scripts/workflow_work_type_policy.py",
            "design": "docs/design.md",
            "research": "evidence/research.json",
            "documentation": "docs/guide.md",
        }[work_type]
        if storage == "evidence-cas":
            scope = []
        elif work_type == "implementation":
            scope = [{"kind": "subtree", "path": "scripts"}]
        else:
            scope = [{"kind": "path", "path": location}]
        return {
            "work_type": work_type,
            "basis": "The issue explicitly requires this durable deliverable.",
            "deliverable": {
                "storage": storage,
                "kind": kind,
                "locations": [location],
            },
            "executable_change_expected": work_type == "implementation",
            "expected_scope": scope,
            "validation_profile": profile,
            "unresolved_ambiguities": [],
        }

    def triage_request(self, work_type, storage=None):
        request = {
            "format": policy.TRIAGE_REQUEST_FORMAT,
            "issue_snapshot": copy.deepcopy(self.issue_envelope),
            "baseline": copy.deepcopy(self.baseline_envelope),
            "classification": self.classification(work_type, storage),
        }
        return self.with_digest(request, "request_sha256")

    def classify(self, work_type, storage=None):
        request = self.triage_request(work_type, storage)
        result = policy.classify(
            self.root,
            request,
            self.issue_envelope["binding"]["sha256"],
            self.baseline_envelope["binding"]["sha256"],
        )
        envelope = self.publish_document(
            "triage",
            result,
            self.baseline_envelope["binding"],
        )
        return request, result, envelope

    def change(self, path, data=b"content\n", mode="100644", status="A"):
        oid = policy._git_blob_oid(data, 40)
        if status == "A":
            return {
                "status": "A",
                "old_mode": "000000",
                "new_mode": mode,
                "old_oid": ZERO,
                "new_oid": oid,
                "old_path": None,
                "new_path": path,
            }
        if status == "D":
            return {
                "status": "D",
                "old_mode": mode,
                "new_mode": "000000",
                "old_oid": oid,
                "new_oid": ZERO,
                "old_path": path,
                "new_path": None,
            }
        return {
            "status": status,
            "old_mode": mode,
            "new_mode": mode,
            "old_oid": "3" * 40,
            "new_oid": oid,
            "old_path": path,
            "new_path": path,
        }

    def observation(self, triage_envelope, changes):
        changes = sorted(
            changes,
            key=lambda item: (
                item["old_path"] or "",
                item["new_path"] or "",
                item["status"],
                item["old_oid"],
                item["new_oid"],
            ),
        )
        workspace = {
            "staged": [],
            "unstaged": [],
            "untracked_non_ignored": [],
            "assume_unchanged": [],
            "skip_worktree": [],
        }
        workspace["status_sha256"] = inspector.sha256(
            inspector.canonical_bytes(workspace)
        )
        value = {
            "format": policy.OBSERVATION_FORMAT,
            "repository": self.baseline_document["repository"],
            "issue": self.issue,
            "family_run_id": self.family,
            "triage_binding": triage_envelope["binding"],
            "observer": {
                "name": "external-observer",
                "version": "1.0.0",
                "source_sha256": "4" * 64,
            },
            "observed_at": "2026-09-04T00:10:00Z",
            "object_format": "sha1",
            "base": {
                "ref": self.baseline_document["target_base"]["ref"],
                "commit": self.baseline_document["target_base"]["commit"],
                "tree": self.baseline_document["target_base"]["tree"],
            },
            "head": {
                "commit": "5" * 40,
                "tree": (
                    "6" * 40
                    if changes
                    else self.baseline_document["target_base"]["tree"]
                ),
            },
            "ancestry": {"base_is_ancestor": True, "commit_count": 1},
            "changes": changes,
            "workspace": workspace,
            "git_trust": {
                "no_replace_objects": True,
                "replacement_refs": [],
                "git_replace_ref_base": None,
                "git_graft_file": None,
                "info_grafts_present": False,
                "environment_redirections": [],
                "alternate_object_directories": [],
            },
            "head_config": {
                key: self.baseline_document["config"][key]
                for key in ("path", "blob_oid", "content_sha256", "size")
            },
            "raw_diff_sha256": inspector.sha256(
                inspector.canonical_bytes(changes)
            ),
        }
        value = self.with_digest(value, "observation_sha256")
        return self.publish_document(
            "observation",
            value,
            triage_envelope["binding"],
        )

    def targeted_request(self, triage_envelope, selection):
        request = {
            "format": policy.TARGETED_REQUEST_FORMAT,
            "baseline": copy.deepcopy(self.baseline_envelope),
            "triage": copy.deepcopy(triage_envelope),
            "attempt_label": "attempt-1",
            "selections": [selection],
        }
        return self.with_digest(request, "request_sha256")

    def completion_request(
        self,
        triage_envelope,
        observation_envelope,
        artifact=None,
        review=None,
        acceptance=None,
        content=None,
        diff=None,
    ):
        request = {
            "format": policy.COMPLETION_REQUEST_FORMAT,
            "issue_snapshot": copy.deepcopy(self.issue_envelope),
            "baseline": copy.deepcopy(self.baseline_envelope),
            "triage": copy.deepcopy(triage_envelope),
            "observation": copy.deepcopy(observation_envelope),
            "artifact": artifact,
            "review": review,
            "acceptance": acceptance,
            "documentation_content_check": content,
            "documentation_diff_check": diff,
        }
        return self.with_digest(request, "request_sha256")

    def nonimplementation_evidence(self, triage_envelope, observation_envelope, data):
        triage = triage_envelope["document"]
        classification = triage["classification"]
        path = classification["deliverable"]["locations"][0]
        entry = self.entry(path, data)
        artifact = self.with_digest(
            {
                "format": policy.ARTIFACT_FORMAT,
                "issue": self.issue,
                "family_run_id": self.family,
                "triage_binding": triage_envelope["binding"],
                "work_type": classification["work_type"],
                "storage": classification["deliverable"]["storage"],
                "kind": classification["deliverable"]["kind"],
                "locations": [path],
                "entries": [entry],
            },
            "artifact_sha256",
        )
        artifact_envelope = self.publish_document(
            "artifact",
            artifact,
            triage_envelope["binding"],
            extra=[(path, data)],
        )
        review = self.with_digest(
            {
                "format": policy.REVIEW_FORMAT,
                "issue": self.issue,
                "family_run_id": self.family,
                "artifact_binding": artifact_envelope["binding"],
                "reviewer": {
                    "role": "independent-reviewer",
                    "actor": "reviewer",
                },
                "status": "accepted",
                "findings": [],
                "reviewed_artifact_sha256": artifact["artifact_sha256"],
                "reviewed_at": "2026-09-04T00:20:00Z",
            },
            "review_sha256",
        )
        review_envelope = self.publish_document(
            "review",
            review,
            artifact_envelope["binding"],
        )
        acceptance = self.with_digest(
            {
                "format": policy.ACCEPTANCE_FORMAT,
                "issue": self.issue,
                "family_run_id": self.family,
                "artifact_binding": artifact_envelope["binding"],
                "review_binding": review_envelope["binding"],
                "actor": "human",
                "confirmation": "artifact_accepted",
                "accepted_at": "2026-09-04T00:30:00Z",
            },
            "acceptance_sha256",
        )
        acceptance_envelope = self.publish_document(
            "acceptance",
            acceptance,
            review_envelope["binding"],
        )
        content_envelope = None
        diff_envelope = None
        if classification["work_type"] == "documentation":
            change = observation_envelope["document"]["changes"][0]
            content = self.with_digest(
                {
                    "format": policy.CONTENT_CHECK_FORMAT,
                    "issue": self.issue,
                    "family_run_id": self.family,
                    "artifact_binding": artifact_envelope["binding"],
                    "observation_binding": observation_envelope["binding"],
                    "status": "pass",
                    "checks": [
                        {
                            "path": path,
                            "artifact_content_sha256": inspector.sha256(data),
                            "observed_new_oid": change["new_oid"],
                            "observed_content_sha256": inspector.sha256(data),
                            "status": "pass",
                        }
                    ],
                },
                "check_sha256",
            )
            content_envelope = self.publish_document(
                "content-check",
                content,
                artifact_envelope["binding"],
            )
            diff = self.with_digest(
                {
                    "format": policy.DIFF_CHECK_FORMAT,
                    "issue": self.issue,
                    "family_run_id": self.family,
                    "artifact_binding": artifact_envelope["binding"],
                    "observation_binding": observation_envelope["binding"],
                    "declared_locations": [path],
                    "observed_changes_sha256": inspector.sha256(
                        inspector.canonical_bytes(
                            observation_envelope["document"]["changes"]
                        )
                    ),
                    "status": "pass",
                },
                "check_sha256",
            )
            diff_envelope = self.publish_document(
                "diff-check",
                diff,
                observation_envelope["binding"],
            )
        return (
            artifact_envelope,
            review_envelope,
            acceptance_envelope,
            content_envelope,
            diff_envelope,
        )


class WorkflowWorkTypePolicyTest(unittest.TestCase):
    def setUp(self):
        self.fixture = WorkTypeFixture()

    def tearDown(self):
        self.fixture.close()

    def assert_failure(self, status, code, callable_value, *args):
        with self.assertRaises(policy.WorkTypePolicyFailure) as raised:
            callable_value(*args)
        self.assertEqual(status, raised.exception.status)
        self.assertEqual(code, raised.exception.code)

    def classify(self, request):
        return policy.classify(
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
        )

    def assess(
        self,
        request,
        observation,
        review=None,
        acceptance=None,
    ):
        return policy.assess_completion(
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            request["triage"]["binding"]["sha256"],
            observation["binding"]["sha256"],
            review["binding"]["sha256"] if review else None,
            acceptance["binding"]["sha256"] if acceptance else None,
        )

    def test_all_four_classifications_have_exact_inactive_routes(self):
        for work_type in policy.WORK_TYPES:
            with self.subTest(work_type=work_type):
                request = self.fixture.triage_request(work_type)
                first = self.classify(request)
                second = self.classify(copy.deepcopy(request))
                self.assertEqual(first, second)
                self.assertEqual(
                    list(policy.ROUTE_REQUIREMENTS[work_type]),
                    first["route"]["requirements"],
                )
                self.assertFalse(first["route"]["operationally_active"])
                self.assertEqual(
                    list(policy.ACTIVATION_UNSATISFIED),
                    first["activation"]["unsatisfied"],
                )
                rendered = json.dumps(first).lower()
                self.assertNotIn('"completed"', rendered)
                self.assertNotIn('"validated"', rendered)

    def test_ambiguity_and_contradictory_intake_fail_closed(self):
        ambiguous = self.fixture.triage_request("implementation")
        ambiguous["classification"]["unresolved_ambiguities"] = [
            {"code": "scope", "detail": "Executable behavior is unclear."}
        ]
        ambiguous = self.fixture.with_digest(
            {key: value for key, value in ambiguous.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "ambiguous",
            "unresolved-classification-ambiguity",
            self.classify,
            ambiguous,
        )
        contradictory = self.fixture.triage_request("documentation")
        contradictory["classification"]["executable_change_expected"] = True
        contradictory = self.fixture.with_digest(
            {
                key: value
                for key, value in contradictory.items()
                if key != "request_sha256"
            },
            "request_sha256",
        )
        self.assert_failure(
            "denied",
            "nonimplementation-classification-contradiction",
            self.classify,
            contradictory,
        )

    def test_unbounded_overlap_and_missing_deliverable_are_rejected(self):
        overlap = self.fixture.triage_request("implementation")
        overlap["classification"]["expected_scope"] = [
            {"kind": "subtree", "path": "scripts"},
            {"kind": "path", "path": "scripts/example.py"},
        ]
        overlap = self.fixture.with_digest(
            {key: value for key, value in overlap.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "ambiguous", "overlapping-expected-scope", self.classify, overlap
        )
        missing = self.fixture.triage_request("design")
        missing["classification"]["deliverable"]["locations"] = []
        missing = self.fixture.with_digest(
            {key: value for key, value in missing.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "missing", "deliverable-location-missing", self.classify, missing
        )

    def test_baseline_projects_real_config_with_default_cwd_and_order(self):
        baseline = self.fixture.baseline_document
        policy._validate_baseline(copy.deepcopy(baseline))
        config = json.loads(CONFIG.read_bytes())
        for profile in baseline["profiles"]:
            raw = config["validation_profiles"][profile["id"]]
            self.assertEqual(raw["test_paths"], profile["test_paths"])
            self.assertEqual(
                [check.get("cwd", ".") for check in raw["checks"]],
                [check["cwd"] for check in profile["checks"]],
            )
        reordered = copy.deepcopy(baseline)
        paths = reordered["profiles"][1]["test_paths"]
        reordered["profiles"][1]["test_paths"] = list(reversed(paths))
        config_data = base64.b64decode(reordered["config"]["bytes_base64"])
        config_value = json.loads(config_data)
        config_value["validation_profiles"][reordered["profiles"][1]["id"]][
            "test_paths"
        ] = list(reversed(paths))
        config_data = json.dumps(config_value).encode()
        reordered["config"].update(
            {
                "blob_oid": policy._git_blob_oid(config_data, 40),
                "content_sha256": inspector.sha256(config_data),
                "size": len(config_data),
                "bytes_base64": base64.b64encode(config_data).decode(),
            }
        )
        reordered = self.fixture.with_digest(
            {
                key: value
                for key, value in reordered.items()
                if key != "baseline_sha256"
            },
            "baseline_sha256",
        )
        policy._validate_baseline(reordered)

    def test_profile_limit_rows_are_complete_exact_and_policy_owned(self):
        baseline = self.fixture.baseline_document
        expected = sum(len(profile["checks"]) for profile in baseline["profiles"])
        self.assertEqual(expected, len(baseline["profile_check_limits"]))
        missing = copy.deepcopy(baseline)
        missing["profile_check_limits"].pop()
        missing = self.fixture.with_digest(
            {key: value for key, value in missing.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "missing",
            "profile-check-limit-missing",
            policy._validate_baseline,
            missing,
        )
        extra = copy.deepcopy(baseline)
        extra["profile_check_limits"].append(
            {
                "profile": "workflow-tooling",
                "check": "unused",
                **policy.PROFILE_LIMITS,
            }
        )
        extra["profile_check_limits"].sort(
            key=lambda item: (item["profile"], item["check"])
        )
        extra = self.fixture.with_digest(
            {key: value for key, value in extra.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "ambiguous",
            "unreferenced-profile-check-limit",
            policy._validate_baseline,
            extra,
        )
        for value, status, code in (
            (-1, "corrupt", "invalid-profile-check-limit"),
            (policy.MAX_OUTPUT_LIMIT_BYTES + 1, "corrupt", "invalid-profile-check-limit"),
            (1024, "stale", "profile-check-limit-policy-mismatch"),
        ):
            changed = copy.deepcopy(baseline)
            changed["profile_check_limits"][0]["output_limit_bytes"] = value
            changed = self.fixture.with_digest(
                {
                    key: item
                    for key, item in changed.items()
                    if key != "baseline_sha256"
                },
                "baseline_sha256",
            )
            with self.subTest(value=value):
                self.assert_failure(
                    status, code, policy._validate_baseline, changed
                )

    def test_baseline_target_profile_and_template_drift_are_rejected(self):
        target = copy.deepcopy(self.fixture.baseline_document)
        target["target_base"]["name"] = "release"
        target["target_base"]["ref"] = "refs/remotes/origin/release"
        target = self.fixture.with_digest(
            {key: value for key, value in target.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "stale", "target-base-config-mismatch", policy._validate_baseline, target
        )
        profile = copy.deepcopy(self.fixture.baseline_document)
        profile["profiles"][0]["checks"][0]["command"].append("--changed")
        profile = self.fixture.with_digest(
            {key: value for key, value in profile.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "stale",
            "baseline-profile-projection-mismatch",
            policy._validate_baseline,
            profile,
        )
        template = copy.deepcopy(self.fixture.baseline_document)
        template["targeted_templates"][0]["output_limit_bytes"] += 1
        template["baseline_sha256"] = self.fixture.with_digest(
            {
                key: value
                for key, value in template.items()
                if key != "baseline_sha256"
            },
            "baseline_sha256",
        )["baseline_sha256"]
        request = self.fixture.triage_request("implementation")
        request["baseline"]["document"] = template
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "stale",
            "binding-decision-mismatch",
            self.classify,
            request,
        )

    def test_inline_document_substitution_cannot_replace_store_payload(self):
        substituted = self.fixture.triage_request("implementation")
        substituted["issue_snapshot"]["document"]["title"] = "Substituted"
        substituted["issue_snapshot"]["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in substituted["issue_snapshot"]["document"].items()
                if key != "snapshot_sha256"
            },
            "snapshot_sha256",
        )
        substituted = self.fixture.with_digest(
            {
                key: value
                for key, value in substituted.items()
                if key != "request_sha256"
            },
            "request_sha256",
        )
        self.assert_failure(
            "stale", "binding-decision-mismatch", self.classify, substituted
        )

    def test_baseline_chain_rejects_cross_family_issue_binding(self):
        original_family = self.fixture.family
        self.fixture.family = "fedcba9876543210fedcba9876543210"
        other_issue = self.fixture.publish_document(
            "issue-snapshot",
            self.fixture.issue_document,
            self.fixture.source,
        )
        self.fixture.family = original_family
        baseline = copy.deepcopy(self.fixture.baseline_document)
        baseline["issue_snapshot_binding"] = other_issue["binding"]
        baseline = self.fixture.with_digest(
            {key: value for key, value in baseline.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        baseline_envelope = self.fixture.publish_document(
            "baseline", baseline, other_issue["binding"]
        )
        self.assert_failure(
            "stale",
            "baseline-issue-mismatch",
            policy._validate_baseline_chain,
            self.fixture.root,
            baseline_envelope,
            other_issue["binding"]["sha256"],
            baseline_envelope["binding"]["sha256"],
        )

    def test_targeted_profile_check_uses_frozen_limits_and_is_advisory(self):
        _request, _result, triage = self.fixture.classify("implementation")
        selection = {
            "kind": "profile-check",
            "id": "agent-workflow-tests",
            "selectors": [],
            "declared_scope": [{"kind": "subtree", "path": "scripts"}],
        }
        request = self.fixture.targeted_request(triage, selection)
        process_result = {
            "format": "chess-echo-process-result-v1",
            "command_sha256": "a" * 64,
            "limits": {
                "timeout_ms": policy.PROFILE_LIMITS["timeout_ms"],
                "grace_ms": policy.PROFILE_LIMITS["grace_ms"],
                "output_bytes_per_stream": policy.PROFILE_LIMITS[
                    "output_limit_bytes"
                ],
            },
            "containment": {
                "kind": "posix-process-group",
                "cleanup_scope": "original-process-group",
                "escaped_descendants": "not-observable",
                "descendant_cleanup_verified": False,
            },
            "outcome": "success",
            "reason": "process-exited",
            "exit_code": 0,
            "terminating_signal": None,
            "forced_termination": False,
            "cleanup_verified": True,
            "stdout": {"bytes": 0, "base64": ""},
            "stderr": {"bytes": 0, "base64": ""},
            "supervisor_error": None,
        }
        with mock.patch.object(
            policy.supervisor, "supervise", return_value=process_result
        ) as supervise:
            result = policy.run_targeted(
                self.fixture.root,
                request,
                self.fixture.issue_envelope["binding"]["sha256"],
                self.fixture.baseline_envelope["binding"]["sha256"],
                triage["binding"]["sha256"],
            )
        self.assertEqual("advisory-only", result["authority"])
        self.assertEqual(list(policy.TARGETED_LIMITATIONS), result["limitations"])
        self.assertFalse(
            result["executions"][0]["process_result"]["containment"][
                "descendant_cleanup_verified"
            ]
        )
        self.assertEqual(
            policy.PROFILE_LIMITS,
            {
                key: supervise.call_args.kwargs[key]
                for key in policy.PROFILE_LIMITS
            },
        )
        self.assertEqual(str(self.fixture.root), supervise.call_args.kwargs["cwd"])

    def test_targeted_template_preserves_its_limits(self):
        _request, _result, triage = self.fixture.classify("implementation")
        selection = {
            "kind": "targeted-template",
            "id": "python-echo-test-id",
            "selectors": [
                "scripts.tests.test_workflow_work_type_policy."
                "WorkflowWorkTypePolicyTest."
                "test_all_four_classifications_have_exact_inactive_routes"
            ],
            "declared_scope": [{"kind": "subtree", "path": "scripts"}],
        }
        request = self.fixture.targeted_request(triage, selection)
        fake = {
            "format": "chess-echo-process-result-v1",
            "command_sha256": "b" * 64,
            "limits": {
                "timeout_ms": 5000,
                "grace_ms": 100,
                "output_bytes_per_stream": 4096,
            },
            "containment": {
                "kind": "posix-process-group",
                "cleanup_scope": "original-process-group",
                "escaped_descendants": "not-observable",
                "descendant_cleanup_verified": False,
            },
            "outcome": "nonzero-exit",
            "reason": "process-exited",
            "exit_code": 1,
            "terminating_signal": None,
            "forced_termination": False,
            "cleanup_verified": True,
            "stdout": {"bytes": 0, "base64": ""},
            "stderr": {"bytes": 0, "base64": ""},
            "supervisor_error": None,
        }
        with mock.patch.object(
            policy.supervisor, "supervise", return_value=fake
        ) as supervise:
            result = policy.run_targeted(
                self.fixture.root,
                request,
                self.fixture.issue_envelope["binding"]["sha256"],
                self.fixture.baseline_envelope["binding"]["sha256"],
                triage["binding"]["sha256"],
            )
        self.assertEqual("observed-failure", result["overall_status"])
        self.assertEqual(5000, supervise.call_args.kwargs["timeout_ms"])
        self.assertEqual(100, supervise.call_args.kwargs["grace_ms"])
        self.assertEqual(4096, supervise.call_args.kwargs["output_limit_bytes"])

    def test_targeted_limit_override_and_selector_injection_are_rejected(self):
        _request, _result, triage = self.fixture.classify("implementation")
        selection = {
            "kind": "targeted-template",
            "id": "python-echo-test-id",
            "selectors": ["-k"],
            "declared_scope": [{"kind": "subtree", "path": "scripts"}],
        }
        request = self.fixture.targeted_request(triage, selection)
        self.assert_failure(
            "denied",
            "targeted-selector-option-injection",
            policy.run_targeted,
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )
        request["timeout_ms"] = 1
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-targeted-request-schema",
            policy.run_targeted,
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )

    def test_targeted_selectors_cannot_escape_or_lie_about_scope(self):
        _request, _result, triage = self.fixture.classify("implementation")
        for selector, code in (
            ("../../outside.py", "invalid-targeted-selector"),
            ("/outside.py", "targeted-selector-option-injection"),
        ):
            request = self.fixture.targeted_request(
                triage,
                {
                    "kind": "targeted-template",
                    "id": "relative-path-test",
                    "selectors": [selector],
                    "declared_scope": [{"kind": "subtree", "path": "scripts"}],
                },
            )
            with self.subTest(selector=selector):
                with self.assertRaises(policy.WorkTypePolicyFailure) as raised:
                    policy.run_targeted(
                        self.fixture.root,
                        request,
                        self.fixture.issue_envelope["binding"]["sha256"],
                        self.fixture.baseline_envelope["binding"]["sha256"],
                        triage["binding"]["sha256"],
                    )
                self.assertIn(code, raised.exception.code)
        test_id = self.fixture.targeted_request(
            triage,
            {
                "kind": "targeted-template",
                "id": "python-echo-test-id",
                "selectors": ["../../outside.py"],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        self.assert_failure(
            "denied",
            "targeted-selector-grammar",
            policy.run_targeted,
            self.fixture.root,
            test_id,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )
        external_module = self.fixture.targeted_request(
            triage,
            {
                "kind": "targeted-template",
                "id": "python-echo-test-id",
                "selectors": ["os.getpid"],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        self.assert_failure(
            "denied",
            "targeted-test-id-unresolved",
            policy.run_targeted,
            self.fixture.root,
            external_module,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )

    def test_targeted_relative_selector_cannot_escape_through_symlink(self):
        _request, _result, triage = self.fixture.classify("implementation")
        scripts = self.fixture.root / "scripts"
        outside = self.fixture.root.parent / (
            self.fixture.root.name + "-outside-target"
        )
        outside.mkdir()
        self.addCleanup(
            lambda: outside.rmdir() if outside.exists() else None
        )
        (outside / "outside.py").write_text("print('outside')\n")
        self.addCleanup(
            lambda: (outside / "outside.py").unlink()
            if (outside / "outside.py").exists()
            else None
        )
        (scripts / "link").symlink_to(outside, target_is_directory=True)
        request = self.fixture.targeted_request(
            triage,
            {
                "kind": "targeted-template",
                "id": "relative-path-test",
                "selectors": ["link/outside.py"],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        self.assert_failure(
            "denied",
            "targeted-selector-outside-root",
            policy.run_targeted,
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )

    def test_resolved_selector_cannot_reintroduce_option_or_symlink_loop(self):
        _request, _result, triage = self.fixture.classify("implementation")
        scripts = self.fixture.root / "scripts"
        (scripts / "-k").write_text("option-like\n")
        (scripts / "safe").symlink_to("-k")
        option_request = self.fixture.targeted_request(
            triage,
            {
                "kind": "targeted-template",
                "id": "relative-path-test",
                "selectors": ["safe"],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        self.assert_failure(
            "denied",
            "targeted-selector-option-injection",
            policy.run_targeted,
            self.fixture.root,
            option_request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )
        (scripts / "loop").symlink_to("loop")
        loop_request = self.fixture.targeted_request(
            triage,
            {
                "kind": "targeted-template",
                "id": "relative-path-test",
                "selectors": ["loop"],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        self.assert_failure(
            "denied",
            "targeted-path-resolution-failed",
            policy.run_targeted,
            self.fixture.root,
            loop_request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )

    def test_targeted_templates_reject_shell_dispatch_wrappers(self):
        for prefix in (
            ["/usr/bin/env", "sh", "-c", "true"],
            ["busybox", "sh", "-c", "true"],
        ):
            baseline = copy.deepcopy(self.fixture.baseline_document)
            baseline["targeted_templates"][0]["command_prefix"] = prefix
            baseline = self.fixture.with_digest(
                {
                    key: value
                    for key, value in baseline.items()
                    if key != "baseline_sha256"
                },
                "baseline_sha256",
            )
            with self.subTest(prefix=prefix):
                self.assert_failure(
                    "denied",
                    "targeted-shell-prohibited",
                    policy._validate_baseline,
                    baseline,
                )
        baseline = copy.deepcopy(self.fixture.baseline_document)
        baseline["targeted_templates"][0]["command_prefix"] = [
            sys.executable,
            "-c",
            "print('bypass')",
            "-m",
            "unittest",
        ]
        baseline = self.fixture.with_digest(
            {
                key: value
                for key, value in baseline.items()
                if key != "baseline_sha256"
            },
            "baseline_sha256",
        )
        self.assert_failure(
            "denied",
            "targeted-test-id-runner-unsupported",
            policy._validate_baseline,
            baseline,
        )
    def test_non_repository_profiles_cannot_run_targeted_checks(self):
        for work_type in ("design", "research", "documentation"):
            _request, _result, triage = self.fixture.classify(work_type)
            selection = {
                "kind": "profile-check",
                "id": "agent-workflow-tests",
                "selectors": [],
                "declared_scope": (
                    [{"kind": "path", "path": "docs/guide.md"}]
                    if work_type == "documentation"
                    else [{"kind": "path", "path": "docs/design.md"}]
                ),
            }
            if work_type == "research":
                selection["declared_scope"] = [
                    {"kind": "path", "path": "evidence/research.json"}
                ]
            request = self.fixture.targeted_request(triage, selection)
            with self.subTest(work_type=work_type):
                self.assert_failure(
                    "denied",
                    "non-repository-profile-has-no-targeted-checks",
                    policy.run_targeted,
                    self.fixture.root,
                    request,
                    self.fixture.issue_envelope["binding"]["sha256"],
                    self.fixture.baseline_envelope["binding"]["sha256"],
                    triage["binding"]["sha256"],
                )

    def test_implementation_assessment_is_route_only_not_completion(self):
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        request = self.fixture.completion_request(triage, observation)
        result = self.assess(request, observation)
        self.assertEqual(
            "implementation-route-conforms", result["outcome"]["code"]
        )
        self.assertEqual(
            list(policy.COMPLETION_UNVERIFIED),
            result["activation"]["unverified"],
        )
        self.assertFalse(result["activation"]["operationally_activated"])
        self.assertTrue(result["scope"]["executable_change_present"])
        self.assertTrue(all(value is None for value in result["structural_evidence"].values()))

    def test_every_final_workspace_category_fails_closed(self):
        _request, _result, triage = self.fixture.classify("implementation")
        fields = (
            ("staged", [{"code": "M", "path": "scripts/a.py", "original_path": None}]),
            ("unstaged", [{"code": "M", "path": "scripts/a.py", "original_path": None}]),
            ("untracked_non_ignored", ["scripts/a.py"]),
            ("assume_unchanged", ["scripts/a.py"]),
            ("skip_worktree", ["scripts/a.py"]),
        )
        for field, records in fields:
            observation = self.fixture.observation(
                triage, [self.fixture.change("scripts/new_policy.py")]
            )
            workspace = observation["document"]["workspace"]
            workspace[field] = records
            workspace["status_sha256"] = inspector.sha256(
                inspector.canonical_bytes(
                    {
                        key: value
                        for key, value in workspace.items()
                        if key != "status_sha256"
                    }
                )
            )
            observation["document"] = self.fixture.with_digest(
                {
                    key: value
                    for key, value in observation["document"].items()
                    if key != "observation_sha256"
                },
                "observation_sha256",
            )
            observation = self.fixture.publish_document(
                "observation", observation["document"], triage["binding"]
            )
            request = self.fixture.completion_request(triage, observation)
            with self.subTest(field=field):
                self.assert_failure(
                    "denied",
                    "final-workspace-not-clean",
                    self.assess,
                    request,
                    observation,
                )

    def test_untracked_sensitive_surfaces_are_all_denied(self):
        paths = (
            "src/main/kotlin/A.kt",
            "src/test/kotlin/ATest.kt",
            "src/main/resources/db/migration/V3.sql",
            "build.gradle.kts",
            ".github/agent-workflow.json",
            "scripts/a.py",
        )
        for path in paths:
            _request, _result, triage = self.fixture.classify("implementation")
            observation = self.fixture.observation(
                triage, [self.fixture.change("scripts/new_policy.py")]
            )
            workspace = observation["document"]["workspace"]
            workspace["untracked_non_ignored"] = [path]
            workspace["status_sha256"] = inspector.sha256(
                inspector.canonical_bytes(
                    {
                        key: value
                        for key, value in workspace.items()
                        if key != "status_sha256"
                    }
                )
            )
            observation["document"] = self.fixture.with_digest(
                {
                    key: value
                    for key, value in observation["document"].items()
                    if key != "observation_sha256"
                },
                "observation_sha256",
            )
            observation = self.fixture.publish_document(
                "observation", observation["document"], triage["binding"]
            )
            request = self.fixture.completion_request(triage, observation)
            with self.subTest(path=path):
                self.assert_failure(
                    "denied",
                    "final-workspace-not-clean",
                    self.assess,
                    request,
                    observation,
                )

    def test_git_replacement_graft_and_redirection_controls_fail(self):
        cases = {
            "no_replace_objects": False,
            "replacement_refs": [{"name": "refs/replace/x", "object_id": "7" * 40}],
            "git_replace_ref_base": "refs/replace/",
            "git_graft_file": ".git/grafts",
            "info_grafts_present": True,
            "environment_redirections": ["GIT_OBJECT_DIRECTORY"],
            "alternate_object_directories": ["/tmp/objects"],
        }
        for field, value in cases.items():
            _request, _result, triage = self.fixture.classify("implementation")
            observation = self.fixture.observation(
                triage, [self.fixture.change("scripts/new_policy.py")]
            )
            observation["document"]["git_trust"][field] = value
            observation["document"] = self.fixture.with_digest(
                {
                    key: item
                    for key, item in observation["document"].items()
                    if key != "observation_sha256"
                },
                "observation_sha256",
            )
            observation = self.fixture.publish_document(
                "observation", observation["document"], triage["binding"]
            )
            request = self.fixture.completion_request(triage, observation)
            with self.subTest(field=field):
                self.assert_failure(
                    "denied",
                    "git-trust-controls-unsatisfied",
                    self.assess,
                    request,
                    observation,
                )

    def test_profile_mixed_scope_and_config_drift_are_rejected(self):
        request = self.fixture.triage_request("implementation")
        request["classification"]["validation_profile"] = "full-stack"
        request["classification"]["deliverable"]["locations"] = ["src/main/A.kt"]
        request["classification"]["expected_scope"] = [
            {"kind": "subtree", "path": "frontend"},
            {"kind": "subtree", "path": "src"},
        ]
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        result = self.classify(request)
        triage = self.fixture.publish_document(
            "triage", result, self.fixture.baseline_envelope["binding"]
        )
        observation = self.fixture.observation(
            triage, [self.fixture.change("src/main/A.kt")]
        )
        completion = self.fixture.completion_request(triage, observation)
        self.assert_failure(
            "denied", "profile-scope-mismatch", self.assess, completion, observation
        )
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        observation["document"]["head_config"]["content_sha256"] = "9" * 64
        observation["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in observation["document"].items()
                if key != "observation_sha256"
            },
            "observation_sha256",
        )
        observation = self.fixture.publish_document(
            "observation", observation["document"], triage["binding"]
        )
        completion = self.fixture.completion_request(triage, observation)
        self.assert_failure(
            "stale", "baseline-config-mismatch", self.assess, completion, observation
        )

    def test_classification_rejects_profile_that_cannot_cover_declared_scope(self):
        request = self.fixture.triage_request("implementation")
        request["classification"]["deliverable"]["locations"] = ["src/main/A.kt"]
        request["classification"]["expected_scope"] = [
            {"kind": "subtree", "path": "src"}
        ]
        request["classification"]["validation_profile"] = "workflow-tooling"
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "denied", "profile-scope-mismatch", self.classify, request
        )

    def test_conflicting_diff_records_and_present_zero_oids_are_rejected(self):
        _request, _result, triage = self.fixture.classify("documentation")
        first = self.fixture.change("docs/guide.md", data=b"first\n", status="M")
        second = self.fixture.change("docs/guide.md", data=b"second\n", status="M")
        observation = self.fixture.observation(triage, [first, second])
        request = self.fixture.completion_request(
            triage,
            observation,
            artifact={},
            review={},
            acceptance={},
            content={},
            diff={},
        )
        self.assert_failure(
            "ambiguous",
            "conflicting-diff-path",
            self.assess,
            request,
            observation,
        )
        zero = self.fixture.change("docs/guide.md")
        zero["new_oid"] = ZERO
        observation = self.fixture.observation(triage, [zero])
        request = self.fixture.completion_request(
            triage,
            observation,
            artifact={},
            review={},
            acceptance={},
            content={},
            diff={},
        )
        self.assert_failure(
            "corrupt", "invalid-add-change", self.assess, request, observation
        )

    def test_real_git_commits_trees_and_config_blobs_reject_zero_oids(self):
        baseline = copy.deepcopy(self.fixture.baseline_document)
        baseline["target_base"]["commit"] = ZERO
        baseline = self.fixture.with_digest(
            {key: value for key, value in baseline.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-target-base-commit",
            policy._validate_baseline,
            baseline,
        )
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        observation["document"]["head"]["commit"] = ZERO
        observation["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in observation["document"].items()
                if key != "observation_sha256"
            },
            "observation_sha256",
        )
        observation = self.fixture.publish_document(
            "observation", observation["document"], triage["binding"]
        )
        completion = self.fixture.completion_request(triage, observation)
        self.assert_failure(
            "corrupt",
            "invalid-head-commit",
            self.assess,
            completion,
            observation,
        )

    def test_observation_repository_facts_and_diff_digest_are_correlated(self):
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        impossible = copy.deepcopy(observation["document"])
        impossible["head"] = copy.deepcopy(impossible["base"])
        impossible["head"].pop("ref")
        impossible["ancestry"]["commit_count"] = 0
        impossible = self.fixture.with_digest(
            {
                key: value
                for key, value in impossible.items()
                if key != "observation_sha256"
            },
            "observation_sha256",
        )
        impossible_envelope = self.fixture.publish_document(
            "observation", impossible, triage["binding"]
        )
        completion = self.fixture.completion_request(triage, impossible_envelope)
        self.assert_failure(
            "corrupt",
            "observation-repository-facts-mismatch",
            self.assess,
            completion,
            impossible_envelope,
        )
        mismatched = copy.deepcopy(observation["document"])
        mismatched["raw_diff_sha256"] = "f" * 64
        mismatched = self.fixture.with_digest(
            {
                key: value
                for key, value in mismatched.items()
                if key != "observation_sha256"
            },
            "observation_sha256",
        )
        mismatched_envelope = self.fixture.publish_document(
            "observation", mismatched, triage["binding"]
        )
        completion = self.fixture.completion_request(triage, mismatched_envelope)
        self.assert_failure(
            "corrupt",
            "raw-diff-changes-mismatch",
            self.assess,
            completion,
            mismatched_envelope,
        )

    def test_nonimplementation_executable_and_scope_bypasses_fail(self):
        _request, _result, triage = self.fixture.classify("documentation")
        for change in (
            self.fixture.change("docs/guide.md", mode="100755"),
            self.fixture.change("docs/guide.md", mode="120000"),
            self.fixture.change("src/main/A.kt"),
            self.fixture.change("docs/guide.md", status="D"),
        ):
            observation = self.fixture.observation(triage, [change])
            completion = self.fixture.completion_request(
                triage,
                observation,
                artifact={},
                review={},
                acceptance={},
                content={},
                diff={},
            )
            with self.subTest(change=change):
                with self.assertRaises(policy.WorkTypePolicyFailure) as raised:
                    self.assess(completion, observation)
                self.assertIn(
                    raised.exception.code,
                    {
                        "scope-drift-requires-implementation",
                        "diff-outside-declared-scope",
                    },
                )

    def test_executable_configuration_under_docs_requires_implementation(self):
        request = self.fixture.triage_request("documentation")
        request["classification"]["deliverable"]["locations"] = ["docs/conf.py"]
        request["classification"]["expected_scope"] = [
            {"kind": "path", "path": "docs/conf.py"}
        ]
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "denied",
            "non-content-deliverable-location",
            self.classify,
            request,
        )
        self.assertEqual("workflow-tooling", policy._classify_path("docs/conf.py"))
        self.assertEqual("documentation", policy._classify_path("docs/guide.md"))

    def test_nonimplementation_scope_cannot_include_extra_executable_path(self):
        request = self.fixture.triage_request("documentation")
        request["classification"]["expected_scope"] = [
            {"kind": "path", "path": "docs/conf.py"},
            {"kind": "path", "path": "docs/guide.md"},
        ]
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "denied",
            "nonimplementation-scope-mismatch",
            self.classify,
            request,
        )

    def test_type_change_and_gitlink_cannot_hide_in_nonimplementation(self):
        _request, _result, triage = self.fixture.classify("documentation")
        cases = [
            {
                "status": "T",
                "old_mode": "100644",
                "new_mode": "120000",
                "old_oid": "3" * 40,
                "new_oid": "4" * 40,
                "old_path": "docs/guide.md",
                "new_path": "docs/guide.md",
            },
            self.fixture.change("docs/guide.md", mode="160000"),
        ]
        for change in cases:
            observation = self.fixture.observation(triage, [change])
            completion = self.fixture.completion_request(
                triage,
                observation,
                artifact={},
                review={},
                acceptance={},
                content={},
                diff={},
            )
            with self.subTest(change=change):
                self.assert_failure(
                    "denied",
                    "scope-drift-requires-implementation",
                    self.assess,
                    completion,
                    observation,
                )

    def test_documentation_structural_completion_checks_committed_content(self):
        data = b"# Durable documentation\n"
        _request, _result, triage = self.fixture.classify("documentation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("docs/guide.md", data=data)]
        )
        artifact, review, acceptance, content, diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        request = self.fixture.completion_request(
            triage, observation, artifact, review, acceptance, content, diff
        )
        result = self.assess(request, observation, review, acceptance)
        self.assertEqual(
            "nonimplementation-structurally-satisfied", result["outcome"]["code"]
        )
        self.assertEqual(artifact["binding"], result["structural_evidence"]["artifact_binding"])
        self.assertIn("actor-authentication", result["activation"]["unverified"])

    def test_documentation_artifact_substitution_and_check_failure_are_rejected(self):
        data = b"# Durable documentation\n"
        _request, _result, triage = self.fixture.classify("documentation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("docs/guide.md", data=data)]
        )
        artifact, review, acceptance, content, diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        substituted = copy.deepcopy(artifact)
        substituted["document"]["entries"][0]["content_sha256"] = "a" * 64
        substituted["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in substituted["document"].items()
                if key != "artifact_sha256"
            },
            "artifact_sha256",
        )
        request = self.fixture.completion_request(
            triage, observation, substituted, review, acceptance, content, diff
        )
        with self.assertRaises(policy.WorkTypePolicyFailure):
            self.assess(request, observation, review, acceptance)
        failing = copy.deepcopy(content)
        failing["document"]["status"] = "fail"
        failing["document"]["checks"][0]["status"] = "fail"
        failing["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in failing["document"].items()
                if key != "check_sha256"
            },
            "check_sha256",
        )
        failing = self.fixture.publish_document(
            "content-check", failing["document"], artifact["binding"]
        )
        request = self.fixture.completion_request(
            triage, observation, artifact, review, acceptance, failing, diff
        )
        self.assert_failure(
            "denied",
            "documentation-validation-failed",
            self.assess,
            request,
            observation,
            review,
            acceptance,
        )

    def test_embedded_evidence_identity_must_match_binding_and_chain(self):
        data = b"Design\n"
        _request, _result, triage = self.fixture.classify("design")
        observation = self.fixture.observation(
            triage, [self.fixture.change("docs/design.md", data=data)]
        )
        artifact, _review, _acceptance, _content, _diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        for field, value, code in (
            ("issue", 999, "document-issue-mismatch"),
            (
                "family_run_id",
                "fedcba9876543210fedcba9876543210",
                "document-family-mismatch",
            ),
        ):
            document = copy.deepcopy(artifact["document"])
            document[field] = value
            document = self.fixture.with_digest(
                {
                    key: item
                    for key, item in document.items()
                    if key != "artifact_sha256"
                },
                "artifact_sha256",
            )
            envelope = self.fixture.publish_document(
                "artifact",
                document,
                triage["binding"],
                extra=[("docs/design.md", data)],
            )
            with self.subTest(field=field):
                self.assert_failure(
                    "stale",
                    code,
                    policy._project_artifact,
                    self.fixture.root,
                    envelope,
                    triage["binding"],
                    self.fixture.issue,
                    self.fixture.family,
                )

    def test_review_and_acceptance_are_designated_but_not_authenticated(self):
        data = b"Design\n"
        _request, _result, triage = self.fixture.classify("design")
        observation = self.fixture.observation(
            triage, [self.fixture.change("docs/design.md", data=data)]
        )
        artifact, review, acceptance, _content, _diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        request = self.fixture.completion_request(
            triage, observation, artifact, review, acceptance
        )
        self.assert_failure(
            "stale",
            "designated-review-binding-mismatch",
            policy.assess_completion,
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
            observation["binding"]["sha256"],
            "f" * 64,
            acceptance["binding"]["sha256"],
        )
        result = self.assess(request, observation, review, acceptance)
        self.assertIn("actor-authentication", result["activation"]["unverified"])
        self.assertIn("replay-prevention", result["activation"]["unverified"])

    def test_git_design_artifact_bytes_must_match_observed_object(self):
        data = b"Design\n"
        _request, _result, triage = self.fixture.classify("design")
        observation = self.fixture.observation(
            triage, [self.fixture.change("docs/design.md", data=b"Different\n")]
        )
        artifact, review, acceptance, _content, _diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        request = self.fixture.completion_request(
            triage, observation, artifact, review, acceptance
        )
        self.assert_failure(
            "stale",
            "artifact-observation-content-mismatch",
            self.assess,
            request,
            observation,
            review,
            acceptance,
        )

    def test_cas_research_requires_empty_diff_and_is_structurally_assessed(self):
        data = b"Research result\n"
        _request, _result, triage = self.fixture.classify("research")
        observation = self.fixture.observation(triage, [])
        artifact, review, acceptance, _content, _diff = (
            self.fixture.nonimplementation_evidence(triage, observation, data)
        )
        request = self.fixture.completion_request(
            triage, observation, artifact, review, acceptance
        )
        result = self.assess(request, observation, review, acceptance)
        self.assertEqual(["evidence-artifact"], result["scope"]["surfaces"])
        self.assertEqual(
            "nonimplementation-structurally-satisfied", result["outcome"]["code"]
        )

    def test_designated_observation_is_static_not_freshness_proof(self):
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        request = self.fixture.completion_request(triage, observation)
        first = self.assess(request, observation)
        replay = self.assess(copy.deepcopy(request), observation)
        self.assertEqual(first, replay)
        self.assertEqual(
            [
                "actor-authentication",
                "latest-tip",
                "revocation",
                "replay-prevention",
                "temporal-freshness",
                "authoritative-recording",
                "lifecycle-completion",
            ],
            replay["activation"]["unverified"],
        )

    def test_malformed_nested_envelopes_are_typed_not_exception_leaks(self):
        _request, _result, triage = self.fixture.classify("implementation")
        request = self.fixture.targeted_request(
            triage,
            {
                "kind": "profile-check",
                "id": "agent-workflow-tests",
                "selectors": [],
                "declared_scope": [{"kind": "subtree", "path": "scripts"}],
            },
        )
        request["triage"] = []
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-triage-envelope-schema",
            policy.run_targeted,
            self.fixture.root,
            request,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
        )
        classify_request = self.fixture.triage_request("implementation")
        classify_request["baseline"] = []
        classify_request = self.fixture.with_digest(
            {
                key: value
                for key, value in classify_request.items()
                if key != "request_sha256"
            },
            "request_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-baseline-envelope-schema",
            self.classify,
            classify_request,
        )
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        completion = self.fixture.completion_request(triage, observation)
        completion["observation"] = []
        completion = self.fixture.with_digest(
            {
                key: value
                for key, value in completion.items()
                if key != "request_sha256"
            },
            "request_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-observation-envelope-schema",
            policy.assess_completion,
            self.fixture.root,
            completion,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
            observation["binding"]["sha256"],
        )

    def test_malformed_nested_enum_types_are_typed(self):
        request = self.fixture.triage_request("implementation")
        request["classification"]["expected_scope"][0]["kind"] = []
        request = self.fixture.with_digest(
            {key: value for key, value in request.items() if key != "request_sha256"},
            "request_sha256",
        )
        self.assert_failure(
            "corrupt", "invalid-scope-kind", self.classify, request
        )
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        observation["document"]["changes"][0]["status"] = {}
        observation["document"] = self.fixture.with_digest(
            {
                key: value
                for key, value in observation["document"].items()
                if key != "observation_sha256"
            },
            "observation_sha256",
        )
        observation = self.fixture.publish_document(
            "observation", observation["document"], triage["binding"]
        )
        completion = self.fixture.completion_request(triage, observation)
        self.assert_failure(
            "corrupt",
            "invalid-diff-status",
            self.assess,
            completion,
            observation,
        )

    def test_completion_rejects_comprehensive_or_unknown_evidence_fields(self):
        _request, _result, triage = self.fixture.classify("implementation")
        observation = self.fixture.observation(
            triage, [self.fixture.change("scripts/new_policy.py")]
        )
        completion = self.fixture.completion_request(triage, observation)
        completion["comprehensive_validation"] = {"binding": {}}
        completion = self.fixture.with_digest(
            {
                key: value
                for key, value in completion.items()
                if key != "request_sha256"
            },
            "request_sha256",
        )
        self.assert_failure(
            "corrupt",
            "invalid-completion-request-schema",
            policy.assess_completion,
            self.fixture.root,
            completion,
            self.fixture.issue_envelope["binding"]["sha256"],
            self.fixture.baseline_envelope["binding"]["sha256"],
            triage["binding"]["sha256"],
            observation["binding"]["sha256"],
        )

    def test_public_cli_is_canonical_and_read_only(self):
        request = self.fixture.triage_request("implementation")
        request_path = self.fixture.root / "request.json"
        request_path.write_text(json.dumps(request))
        marker = self.fixture.store.store_dir / "marker"
        marker.write_bytes(b"sentinel")
        before = {
            path.relative_to(self.fixture.store.store_dir): path.read_bytes()
            for path in self.fixture.store.store_dir.rglob("*")
            if path.is_file()
        }
        commands = (
            [sys.executable, str(SCRIPTS / "workflow_work_type_policy.py")],
            [sys.executable, "-m", "scripts.workflow_work_type_policy"],
        )
        outputs = []
        for prefix in commands:
            result = subprocess.run(
                prefix
                + [
                    "classify",
                    "--root",
                    str(self.fixture.root),
                    "--request",
                    str(request_path),
                    "--trusted-issue-snapshot-binding",
                    self.fixture.issue_envelope["binding"]["sha256"],
                    "--trusted-baseline-binding",
                    self.fixture.baseline_envelope["binding"]["sha256"],
                ],
                cwd=REPOSITORY,
                capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            outputs.append(result.stdout)
        self.assertEqual(outputs[0], outputs[1])
        self.assertEqual(
            inspector.canonical_document(json.loads(outputs[0])), outputs[0]
        )
        after = {
            path.relative_to(self.fixture.store.store_dir): path.read_bytes()
            for path in self.fixture.store.store_dir.rglob("*")
            if path.is_file()
        }
        self.assertEqual(before, after)

    def test_public_cli_translates_root_request_and_argument_failures(self):
        missing_request = subprocess.run(
            [
                sys.executable,
                str(SCRIPTS / "workflow_work_type_policy.py"),
                "classify",
                "--root",
                str(self.fixture.root),
                "--request",
                str(self.fixture.root / "missing.json"),
                "--trusted-issue-snapshot-binding",
                "a" * 64,
                "--trusted-baseline-binding",
                "b" * 64,
            ],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(3, missing_request.returncode)
        self.assertEqual(
            "request-missing", json.loads(missing_request.stdout)["outcome"]["code"]
        )
        valid_request = self.fixture.root / "valid-request.json"
        valid_request.write_text(json.dumps(self.fixture.triage_request("implementation")))
        bad_root = subprocess.run(
            [
                sys.executable,
                str(SCRIPTS / "workflow_work_type_policy.py"),
                "classify",
                "--root",
                str(self.fixture.root / "absent"),
                "--request",
                str(valid_request),
                "--trusted-issue-snapshot-binding",
                "a" * 64,
                "--trusted-baseline-binding",
                "b" * 64,
            ],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(3, bad_root.returncode)
        self.assertEqual(
            "policy-root-unreadable",
            json.loads(bad_root.stdout)["outcome"]["code"],
        )
        invalid_cli = subprocess.run(
            [sys.executable, str(SCRIPTS / "workflow_work_type_policy.py"), "classify"],
            cwd=REPOSITORY,
            capture_output=True,
        )
        self.assertEqual(4, invalid_cli.returncode)
        self.assertEqual("invalid-cli", json.loads(invalid_cli.stdout)["outcome"]["code"])

    def test_request_and_embedded_config_reject_duplicate_json_keys(self):
        duplicate = self.fixture.root / "duplicate.json"
        duplicate.write_text('{"format":"a","format":"b"}')
        self.assert_failure(
            "ambiguous", "duplicate-json-key", policy._load_json, duplicate
        )
        baseline = copy.deepcopy(self.fixture.baseline_document)
        config_data = b'{"target_base":"main","target_base":"release","validation_profiles":{}}'
        baseline["config"].update(
            {
                "blob_oid": policy._git_blob_oid(config_data, 40),
                "content_sha256": inspector.sha256(config_data),
                "size": len(config_data),
                "bytes_base64": base64.b64encode(config_data).decode(),
            }
        )
        baseline = self.fixture.with_digest(
            {key: value for key, value in baseline.items() if key != "baseline_sha256"},
            "baseline_sha256",
        )
        self.assert_failure(
            "ambiguous", "duplicate-json-key", policy._validate_baseline, baseline
        )


if __name__ == "__main__":
    unittest.main()
