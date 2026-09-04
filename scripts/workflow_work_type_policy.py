#!/usr/bin/env python3
"""Inactive work-type classification and evidence-assessment policy."""

import argparse
import base64
import binascii
import copy
import functools
import hashlib
import json
import pathlib
import re
import sys

try:
    import workflow_evidence as evidence
    import workflow_inspector as inspector
    import workflow_supervisor as supervisor
except ModuleNotFoundError:
    from scripts import workflow_evidence as evidence
    from scripts import workflow_inspector as inspector
    from scripts import workflow_supervisor as supervisor


POLICY_VERSION = "1.0.0"
FAILURE_FORMAT = "chess-echo-work-type-policy-failure-v1"
ISSUE_SNAPSHOT_FORMAT = "chess-echo-work-type-issue-snapshot-v1"
BASELINE_FORMAT = "chess-echo-work-type-baseline-v1"
TRIAGE_REQUEST_FORMAT = "chess-echo-work-type-triage-request-v1"
TRIAGE_RESULT_FORMAT = "chess-echo-work-type-triage-result-v1"
ARTIFACT_FORMAT = "chess-echo-work-type-artifact-v1"
REVIEW_FORMAT = "chess-echo-work-type-independent-review-v1"
ACCEPTANCE_FORMAT = "chess-echo-work-type-human-acceptance-v1"
CONTENT_CHECK_FORMAT = "chess-echo-work-type-documentation-content-check-v1"
DIFF_CHECK_FORMAT = "chess-echo-work-type-documentation-diff-check-v1"
OBSERVATION_FORMAT = "chess-echo-work-type-diff-observation-v1"
TARGETED_REQUEST_FORMAT = "chess-echo-work-type-targeted-request-v1"
TARGETED_RESULT_FORMAT = "chess-echo-work-type-targeted-result-v1"
COMPLETION_REQUEST_FORMAT = "chess-echo-work-type-completion-request-v1"
COMPLETION_RESULT_FORMAT = "chess-echo-work-type-completion-result-v1"

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
GIT_OID_RE = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")
SLUG_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
REPOSITORY_RE = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
TARGET_BASE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
RFC3339_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})"
)

MAX_STRUCTURED_BYTES = 8 * 1024 * 1024
MAX_TEXT_BYTES = 1024 * 1024
MAX_CONFIG_BYTES = 1024 * 1024
MAX_PATH_BYTES = 4096
MAX_ENTRIES = 10_000
MAX_SCOPE = 1_000
MAX_FINDINGS = 1_000
MAX_LABELS = 100
MAX_PROFILES = 4
MAX_CHECKS = 128
MAX_TEST_PATHS = 1_000
MAX_TEMPLATES = 128
MAX_COMMAND_PARTS = 128
MAX_COMMAND_PART_BYTES = 4096
MAX_TARGETED_SELECTIONS = 4
MAX_TARGETED_SELECTORS = 32
MAX_TARGETED_METADATA_BYTES = 1024 * 1024
MAX_OUTPUT_LIMIT_BYTES = 512 * 1024
MAX_TIMEOUT_MS = 3_600_000
MAX_GRACE_MS = 60_000

REPOSITORY_PROFILES = (
    "backend",
    "frontend",
    "full-stack",
    "workflow-tooling",
)
NON_REPOSITORY_PROFILES = (
    "design-artifact",
    "research-artifact",
    "documentation-content-diff",
)
ALL_PROFILES = REPOSITORY_PROFILES + NON_REPOSITORY_PROFILES
WORK_TYPES = ("implementation", "design", "research", "documentation")
SURFACE_ORDER = (
    "backend",
    "frontend",
    "workflow-tooling",
    "documentation",
    "evidence-artifact",
)
PROFILE_LIMITS = {
    "timeout_ms": 3_600_000,
    "grace_ms": 2_000,
    "output_limit_bytes": 512 * 1024,
}
PROFILE_COVERAGE = {
    "backend": frozenset({"backend", "documentation"}),
    "frontend": frozenset({"frontend", "documentation"}),
    "full-stack": frozenset({"backend", "frontend", "documentation"}),
    "workflow-tooling": frozenset({"workflow-tooling", "documentation"}),
    "design-artifact": frozenset({"documentation", "evidence-artifact"}),
    "research-artifact": frozenset({"documentation", "evidence-artifact"}),
    "documentation-content-diff": frozenset({"documentation"}),
}

ROUTE_REQUIREMENTS = {
    "implementation": (
        "source-aligned-plan",
        "independent-plan-review",
        "explicit-human-plan-approval",
        "tests-before-production",
        "independent-test-review",
        "explicit-human-test-approval",
        "implementation",
        "legacy-fresh-comprehensive-final-validation",
        "independent-final-review",
        "explicit-human-pr-approval",
    ),
    "design": (
        "durable-design-artifact",
        "independent-artifact-review",
        "clean-final-scope-verification",
        "explicit-human-artifact-acceptance",
    ),
    "research": (
        "durable-research-artifact",
        "independent-artifact-review",
        "clean-final-scope-verification",
        "explicit-human-artifact-acceptance",
    ),
    "documentation": (
        "durable-documentation-change",
        "documentation-content-check",
        "documentation-diff-check",
        "independent-artifact-review",
        "clean-final-scope-verification",
        "explicit-human-artifact-acceptance",
    ),
}
ACTIVATION_UNSATISFIED = (
    "workflow-initialization-integration",
    "authoritative-classification-recording",
    "trusted-latest-tip-and-revocation",
    "temporal-final-validation",
    "lifecycle-completion",
)
COMPLETION_UNVERIFIED = (
    "actor-authentication",
    "latest-tip",
    "revocation",
    "replay-prevention",
    "temporal-freshness",
    "authoritative-recording",
    "lifecycle-completion",
)
TARGETED_LIMITATIONS = (
    "not-authoritative",
    "not-final-validation",
    "escaped-descendants-not-observable",
    "freshness-not-verified",
    "replay-not-prevented",
)

DOCUMENT_CONTRACTS = {
    "issue-snapshot": (
        "workflow-work-type/issue-snapshot.json",
        "work-type-issue-snapshot",
        "issue-{issue}-{digest}",
        "snapshot_sha256",
    ),
    "baseline": (
        "workflow-work-type/baseline.json",
        "work-type-baseline",
        "baseline-{digest}",
        "baseline_sha256",
    ),
    "triage": (
        "workflow-work-type/triage.json",
        "work-type-triage",
        "triage-{digest}",
        "result_sha256",
    ),
    "observation": (
        "workflow-work-type/diff-observation.json",
        "work-type-diff-observation",
        "observation-{digest}",
        "observation_sha256",
    ),
    "targeted": (
        "workflow-work-type/targeted-validation.json",
        "work-type-targeted-validation",
        "targeted-{digest}",
        "result_sha256",
    ),
    "artifact": (
        "workflow-work-type/artifact.json",
        "work-type-artifact",
        "artifact-{digest}",
        "artifact_sha256",
    ),
    "review": (
        "workflow-work-type/independent-review.json",
        "work-type-independent-review",
        "review-{digest}",
        "review_sha256",
    ),
    "acceptance": (
        "workflow-work-type/human-acceptance.json",
        "work-type-human-acceptance",
        "acceptance-{digest}",
        "acceptance_sha256",
    ),
    "content-check": (
        "workflow-work-type/documentation-content-check.json",
        "work-type-documentation-content-check",
        "content-check-{digest}",
        "check_sha256",
    ),
    "diff-check": (
        "workflow-work-type/documentation-diff-check.json",
        "work-type-documentation-diff-check",
        "diff-check-{digest}",
        "check_sha256",
    ),
}

WORKFLOW_DOCS = frozenset(
    {
        "docs/engineering/agent-workflow.md",
        "docs/engineering/workflow-boundaries.md",
        "docs/engineering/workflow-evidence.md",
        "docs/engineering/workflow-inspector.md",
        "docs/engineering/workflow-migration.md",
        "docs/engineering/workflow-policy.md",
        "docs/engineering/workflow-repair.md",
        "docs/engineering/workflow-supervisor.md",
        "docs/engineering/workflow-work-type-policy.md",
    }
)
WORKFLOW_FILES = frozenset(
    {
        ".github/agent-workflow.json",
        ".github/workflows/ci.yml",
        "Makefile",
    }
)
BACKEND_FILES = frozenset(
    {
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "Dockerfile",
        "docker-compose.yml",
    }
)
INERT_DOCUMENT_SUFFIXES = frozenset({".adoc", ".md", ".rst", ".txt"})
SHELL_NAMES = frozenset(
    {
        "bash",
        "cmd",
        "cmd.exe",
        "csh",
        "dash",
        "fish",
        "powershell",
        "pwsh",
        "sh",
        "tcsh",
        "zsh",
    }
)
DISPATCH_WRAPPERS = frozenset({"busybox", "command", "env", "find", "nohup", "xargs"})


class WorkTypePolicyFailure(Exception):
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
    raise WorkTypePolicyFailure(status, code, message, subject)


def _translate_input_errors(function):
    @functools.wraps(function)
    def wrapped(*args, **kwargs):
        try:
            return function(*args, **kwargs)
        except WorkTypePolicyFailure:
            raise
        except (AttributeError, IndexError, KeyError, TypeError) as failure:
            _fail(
                "corrupt",
                "invalid-policy-input-type",
                "Policy input has an invalid nested type: %s"
                % type(failure).__name__,
            )

    return wrapped


def _exact_keys(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        _fail("corrupt", "invalid-%s-schema" % label, "%s schema is invalid" % label)


def _exact_int(value):
    return type(value) is int


def _canonical_bytes(value):
    try:
        data = inspector.canonical_bytes(value)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    except (TypeError, ValueError) as failure:
        _fail("corrupt", "invalid-canonical-json", str(failure))
    if len(data) > MAX_STRUCTURED_BYTES:
        _fail("unsupported", "structured-document-too-large", "Document exceeds 8 MiB")
    return data


def _digest(value):
    return inspector.sha256(_canonical_bytes(value))


def _verify_digest(value, field, label):
    digest = value.get(field) if isinstance(value, dict) else None
    if not isinstance(digest, str) or SHA256_RE.fullmatch(digest) is None:
        _fail("corrupt", "invalid-%s-digest" % label, "%s digest is invalid" % label)
    unsigned = dict(value)
    unsigned.pop(field, None)
    if _digest(unsigned) != digest:
        _fail("corrupt", "%s-digest-mismatch" % label, "%s digest is stale" % label)
    return digest


def _text(value, label, minimum=1, maximum=MAX_TEXT_BYTES, trimmed=True):
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


def _enum(value, choices, label):
    if not isinstance(value, str):
        _fail("corrupt", "invalid-%s" % label, "%s must be a string" % label)
    if value not in choices:
        _fail("unsupported", "unsupported-%s" % label, "%s is unsupported" % label)
    return value


def _sha256(value, label="sha256"):
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-%s" % label, "%s is not 64 lowercase hex" % label)
    return value


def _run_id(value):
    if not isinstance(value, str) or RUN_ID_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-family-run-id", "Family run ID is invalid")
    return value


def _issue(value):
    if not _exact_int(value) or value < 1 or value > 2**63 - 1:
        _fail("corrupt", "invalid-issue", "Issue must be a positive exact integer")
    return value


def _uint(value, label, maximum=2**63 - 1):
    if not _exact_int(value) or value < 0 or value > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s must be a nonnegative exact integer" % label)
    return value


def _timestamp(value, label):
    if not isinstance(value, str) or RFC3339_RE.fullmatch(value) is None:
        _fail("corrupt", "invalid-%s" % label, "%s is not RFC 3339" % label)
    return value


def _path(value, label="path", allow_dot=False):
    if allow_dot and value == ".":
        return value
    try:
        encoded = value.encode("utf-8") if isinstance(value, str) else None
    except UnicodeEncodeError:
        encoded = None
    if not isinstance(value, str) or not value or encoded is None or len(encoded) > MAX_PATH_BYTES:
        _fail("unsupported", "invalid-%s" % label, "%s is not a supported path" % label)
    if "\0" in value or "\\" in value or value.startswith("/"):
        _fail("unsupported", "invalid-%s" % label, "%s is not a normalized path" % label)
    if any(part in {"", ".", ".."} for part in value.split("/")):
        _fail("unsupported", "invalid-%s" % label, "%s is not a normalized path" % label)
    return value


def _config_pattern(value):
    _text(value, "test-path", maximum=MAX_PATH_BYTES)
    if value.startswith("/") or "\\" in value or "\0" in value:
        _fail("corrupt", "invalid-test-path", "Configured test path is invalid")
    if any(part in {"", ".", ".."} for part in value.split("/")):
        _fail("corrupt", "invalid-test-path", "Configured test path is not normalized")
    return value


def _reference(value, expected_kind=None):
    try:
        normalized = inspector.validate_reference(value, expected_kind)
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if set(value) != set(normalized):
        _fail("corrupt", "invalid-object-reference-schema", "Object reference has extra fields")
    return normalized


def _ordered_unique(values, key, label, maximum):
    if not isinstance(values, list) or len(values) > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s is not a bounded list" % label)
    keys = [key(item) for item in values]
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "duplicate-%s" % label, "%s contains duplicates" % label)
    if keys != sorted(keys):
        _fail("corrupt", "noncanonical-%s-order" % label, "%s is not canonically ordered" % label)
    return values


def _git_oid(value, length, label, allow_zero=False):
    if not isinstance(value, str) or GIT_OID_RE.fullmatch(value) is None or len(value) != length:
        _fail("corrupt", "invalid-%s" % label, "%s is not a repository object ID" % label)
    if not allow_zero and value == _zero_oid(length):
        _fail("corrupt", "invalid-%s" % label, "%s cannot be the zero object ID" % label)
    return value


def _git_blob_oid(data, length):
    payload = b"blob " + str(len(data)).encode("ascii") + b"\0" + data
    if length == 40:
        return hashlib.sha1(payload).hexdigest()
    if length == 64:
        return hashlib.sha256(payload).hexdigest()
    _fail("unsupported", "unsupported-object-format", "Git object format is unsupported")


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


def _load_json(path):
    try:
        data = pathlib.Path(path).read_bytes()
    except FileNotFoundError:
        _fail("missing", "request-missing", "Request file is missing")
    except OSError as failure:
        _fail("missing", "request-unreadable", "Cannot read request: %s" % failure)
    if len(data) > MAX_STRUCTURED_BYTES:
        _fail("unsupported", "request-too-large", "Request exceeds 8 MiB")
    try:
        value = json.loads(data, object_pairs_hook=_reject_duplicate_keys)
    except WorkTypePolicyFailure:
        raise
    except (UnicodeError, json.JSONDecodeError, ValueError, RecursionError) as failure:
        _fail("corrupt", "invalid-request-json", "Request is invalid JSON: %s" % failure)
    if not isinstance(value, dict):
        _fail("corrupt", "invalid-request-type", "Request must be a JSON object")
    return value


def _reject_duplicate_keys(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            _fail(
                "ambiguous",
                "duplicate-json-key",
                "JSON object contains duplicate keys",
                key,
            )
        value[key] = item
    return value


def _validate_string_list(values, label, maximum, validator, preserve_order=False):
    if not isinstance(values, list) or len(values) > maximum:
        _fail("corrupt", "invalid-%s" % label, "%s must be a bounded list" % label)
    normalized = [validator(value) for value in values]
    if len(normalized) != len(set(normalized)):
        _fail("ambiguous", "duplicate-%s" % label, "%s contains duplicates" % label)
    if not preserve_order and normalized != sorted(normalized, key=lambda item: item.encode("utf-8")):
        _fail("corrupt", "noncanonical-%s-order" % label, "%s is not ordered" % label)
    return normalized


def _validate_issue_snapshot(value):
    _exact_keys(
        value,
        {
            "format",
            "repository",
            "issue",
            "title",
            "url",
            "body",
            "labels",
            "source",
            "captured_at",
            "snapshot_sha256",
        },
        "issue-snapshot",
    )
    if value["format"] != ISSUE_SNAPSHOT_FORMAT:
        _fail("unsupported", "unsupported-issue-snapshot-format", "Issue snapshot format is unsupported")
    if not isinstance(value["repository"], str) or REPOSITORY_RE.fullmatch(value["repository"]) is None:
        _fail("corrupt", "invalid-repository", "Repository identity is invalid")
    issue = _issue(value["issue"])
    _text(value["title"], "issue-title", maximum=1024)
    url = _text(value["url"], "issue-url", maximum=4096)
    if url != "https://github.com/%s/issues/%d" % (value["repository"], issue):
        _fail("stale", "issue-url-mismatch", "Issue URL does not match identity")
    _text(value["body"], "issue-body", minimum=0, trimmed=False)
    _validate_string_list(
        value["labels"],
        "issue-labels",
        MAX_LABELS,
        lambda item: _text(item, "issue-label", maximum=128),
    )
    _reference(value["source"], "issue-snapshot")
    _timestamp(value["captured_at"], "captured-at")
    _verify_digest(value, "snapshot_sha256", "issue-snapshot")
    return value


def _validate_check(check):
    _exact_keys(check, {"name", "command", "cwd"}, "profile-check")
    name = _slug(check["name"], "check-name")
    command = check["command"]
    if (
        not isinstance(command, list)
        or not command
        or len(command) > MAX_COMMAND_PARTS
    ):
        _fail("corrupt", "invalid-check-command", "Check command is invalid")
    for part in command:
        _text(part, "command-part", maximum=MAX_COMMAND_PART_BYTES)
    cwd = _path(check["cwd"], "check-cwd", allow_dot=True)
    return {"name": name, "command": list(command), "cwd": cwd}


def _normalized_config_profiles(config):
    profiles = config.get("validation_profiles")
    if not isinstance(config.get("target_base"), str) or not config["target_base"]:
        _fail("corrupt", "invalid-baseline-config", "Config target_base is invalid")
    if not isinstance(profiles, dict) or set(profiles) != set(REPOSITORY_PROFILES):
        _fail("corrupt", "invalid-baseline-config", "Config profiles are incomplete")
    normalized = []
    for profile_id in sorted(REPOSITORY_PROFILES):
        profile = profiles[profile_id]
        if not isinstance(profile, dict):
            _fail("corrupt", "invalid-baseline-profile", "Config profile is invalid")
        checks = profile.get("checks")
        test_paths = profile.get("test_paths")
        if (
            not isinstance(checks, list)
            or not checks
            or len(checks) > MAX_CHECKS
            or not isinstance(test_paths, list)
            or not test_paths
            or len(test_paths) > MAX_TEST_PATHS
        ):
            _fail("corrupt", "invalid-baseline-profile", "Config profile is incomplete")
        normalized_checks = []
        names = set()
        for raw_check in checks:
            if not isinstance(raw_check, dict):
                _fail("corrupt", "invalid-profile-check-schema", "Config check is invalid")
            if set(raw_check) not in ({"name", "command"}, {"name", "command", "cwd"}):
                _fail("corrupt", "invalid-profile-check-schema", "Config check schema is invalid")
            projected = {
                "name": raw_check.get("name"),
                "command": raw_check.get("command"),
                "cwd": raw_check.get("cwd", "."),
            }
            check = _validate_check(projected)
            if check["name"] in names:
                _fail("ambiguous", "duplicate-profile-check", "Config check name is duplicated")
            names.add(check["name"])
            normalized_checks.append(check)
        normalized_paths = _validate_string_list(
            test_paths,
            "test-paths",
            MAX_TEST_PATHS,
            _config_pattern,
            preserve_order=True,
        )
        normalized.append(
            {
                "id": profile_id,
                "checks": normalized_checks,
                "test_paths": normalized_paths,
            }
        )
    return config["target_base"], normalized


def _validate_profile_limits(value, profiles):
    if not isinstance(value, list):
        _fail("corrupt", "invalid-profile-check-limits", "Profile check limits must be a list")
    rows = []
    keys = []
    for row in value:
        _exact_keys(
            row,
            {"profile", "check", "timeout_ms", "grace_ms", "output_limit_bytes"},
            "profile-check-limit",
        )
        profile = row["profile"]
        check = _slug(row["check"], "check-name")
        if profile not in REPOSITORY_PROFILES:
            _fail("corrupt", "invalid-profile-check-limit", "Limit profile is invalid")
        for field, maximum in (
            ("timeout_ms", MAX_TIMEOUT_MS),
            ("grace_ms", MAX_GRACE_MS),
            ("output_limit_bytes", MAX_OUTPUT_LIMIT_BYTES),
        ):
            _uint(row[field], "profile-check-limit", maximum)
        key = (profile, check)
        keys.append(key)
        rows.append(row)
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "unreferenced-profile-check-limit", "Profile limit is duplicated")
    if keys != sorted(keys):
        _fail("corrupt", "noncanonical-profile-check-limit-order", "Profile limits are not ordered")
    expected = {
        (profile["id"], check["name"])
        for profile in profiles
        for check in profile["checks"]
    }
    actual = set(keys)
    if expected - actual:
        _fail("missing", "profile-check-limit-missing", "A configured check has no limit row")
    if actual - expected:
        _fail("ambiguous", "unreferenced-profile-check-limit", "A limit row names no configured check")
    for row in rows:
        if any(row[field] != expected_value for field, expected_value in PROFILE_LIMITS.items()):
            _fail("stale", "profile-check-limit-policy-mismatch", "Profile check limits differ from V1 policy")
    return rows


def _validate_template(value):
    _exact_keys(
        value,
        {
            "id",
            "profiles",
            "command_prefix",
            "cwd",
            "selector_kind",
            "max_selectors",
            "max_selector_bytes",
            "timeout_ms",
            "grace_ms",
            "output_limit_bytes",
        },
        "targeted-template",
    )
    template_id = _slug(value["id"], "template-id")
    profiles = _validate_string_list(
        value["profiles"],
        "template-profiles",
        MAX_PROFILES,
        lambda item: item
        if item in REPOSITORY_PROFILES
        else _fail("unsupported", "unsupported-template-profile", "Template profile is unsupported"),
    )
    if not profiles:
        _fail("missing", "template-profile-missing", "Targeted template has no profile")
    prefix = value["command_prefix"]
    if not isinstance(prefix, list) or not prefix or len(prefix) > MAX_COMMAND_PARTS:
        _fail("corrupt", "invalid-template-command", "Template command prefix is invalid")
    for part in prefix:
        _text(part, "template-command-part", maximum=MAX_COMMAND_PART_BYTES)
    prefix_names = {
        pathlib.PurePosixPath(part).name.lower()
        for part in prefix
        if not part.startswith("-")
    }
    if prefix_names.intersection(SHELL_NAMES | DISPATCH_WRAPPERS):
        _fail(
            "denied",
            "targeted-shell-prohibited",
            "Targeted templates cannot invoke a shell or command dispatcher",
        )
    _path(value["cwd"], "template-cwd", allow_dot=True)
    _enum(value["selector_kind"], {"relative-path", "test-id"}, "selector-kind")
    if value["selector_kind"] == "test-id":
        executable = pathlib.PurePosixPath(prefix[0]).name.lower()
        if (
            not executable.startswith("python")
            or len(prefix) != 3
            or prefix[1:] != ["-m", "unittest"]
        ):
            _fail(
                "denied",
                "targeted-test-id-runner-unsupported",
                "V1 test-ID templates must invoke Python unittest directly",
            )
    if not _exact_int(value["max_selectors"]) or not 1 <= value["max_selectors"] <= MAX_TARGETED_SELECTORS:
        _fail("corrupt", "invalid-template-selector-limit", "Template selector count is invalid")
    if not _exact_int(value["max_selector_bytes"]) or not 1 <= value["max_selector_bytes"] <= 512:
        _fail("corrupt", "invalid-template-selector-limit", "Template selector size is invalid")
    for field, maximum in (
        ("timeout_ms", MAX_TIMEOUT_MS),
        ("grace_ms", MAX_GRACE_MS),
        ("output_limit_bytes", MAX_OUTPUT_LIMIT_BYTES),
    ):
        _uint(value[field], "template-limit", maximum)
    if value["timeout_ms"] < 1 or value["output_limit_bytes"] < 1:
        _fail("corrupt", "invalid-template-limit", "Template limits must be positive")
    return value


def _validate_baseline(value):
    _exact_keys(
        value,
        {
            "format",
            "repository",
            "issue",
            "family_run_id",
            "issue_snapshot_binding",
            "target_base",
            "config",
            "profiles",
            "profile_check_limits",
            "targeted_templates",
            "baseline_sha256",
        },
        "baseline",
    )
    if value["format"] != BASELINE_FORMAT:
        _fail("unsupported", "unsupported-baseline-format", "Baseline format is unsupported")
    if not isinstance(value["repository"], str) or REPOSITORY_RE.fullmatch(value["repository"]) is None:
        _fail("corrupt", "invalid-repository", "Repository identity is invalid")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["issue_snapshot_binding"], "evidence-binding")
    target = value["target_base"]
    _exact_keys(target, {"name", "ref", "commit", "tree"}, "target-base")
    if not isinstance(target["name"], str) or TARGET_BASE_RE.fullmatch(target["name"]) is None:
        _fail("corrupt", "invalid-target-base", "Target base name is invalid")
    if target["ref"] != "refs/remotes/origin/%s" % target["name"]:
        _fail("stale", "target-base-ref-mismatch", "Target base ref differs from its name")
    oid_length = len(target["commit"]) if isinstance(target["commit"], str) else 0
    if oid_length not in {40, 64}:
        _fail("corrupt", "invalid-target-base-oid", "Target base object format is invalid")
    _git_oid(target["commit"], oid_length, "target-base-commit")
    _git_oid(target["tree"], oid_length, "target-base-tree")
    config_record = value["config"]
    _exact_keys(
        config_record,
        {"path", "blob_oid", "content_sha256", "size", "bytes_base64"},
        "baseline-config",
    )
    if config_record["path"] != ".github/agent-workflow.json":
        _fail("stale", "baseline-config-path-mismatch", "Baseline config path is invalid")
    _git_oid(config_record["blob_oid"], oid_length, "config-blob")
    _sha256(config_record["content_sha256"], "config-content-sha256")
    _uint(config_record["size"], "config-size", MAX_CONFIG_BYTES)
    if config_record["size"] < 1:
        _fail("corrupt", "invalid-config-size", "Baseline config must be nonempty")
    try:
        config_data = base64.b64decode(config_record["bytes_base64"], validate=True)
    except (TypeError, ValueError, binascii.Error):
        _fail("corrupt", "invalid-config-base64", "Baseline config is not strict base64")
    if (
        len(config_data) != config_record["size"]
        or inspector.sha256(config_data) != config_record["content_sha256"]
        or _git_blob_oid(config_data, oid_length) != config_record["blob_oid"]
    ):
        _fail("corrupt", "baseline-config-content-mismatch", "Baseline config facts conflict")
    try:
        config = json.loads(config_data, object_pairs_hook=_reject_duplicate_keys)
    except WorkTypePolicyFailure:
        raise
    except (UnicodeError, json.JSONDecodeError, ValueError, RecursionError):
        _fail("corrupt", "invalid-baseline-config", "Baseline config JSON is invalid")
    if not isinstance(config, dict):
        _fail("corrupt", "invalid-baseline-config", "Baseline config must be an object")
    config_target, projected = _normalized_config_profiles(config)
    if config_target != target["name"]:
        _fail("stale", "target-base-config-mismatch", "Target base differs from base config")
    if value["profiles"] != projected:
        _fail("stale", "baseline-profile-projection-mismatch", "Profiles differ from base config")
    _validate_profile_limits(value["profile_check_limits"], projected)
    templates = value["targeted_templates"]
    if not isinstance(templates, list) or len(templates) > MAX_TEMPLATES:
        _fail("corrupt", "invalid-targeted-templates", "Targeted templates must be bounded")
    template_ids = []
    for template in templates:
        _validate_template(template)
        template_ids.append(template["id"])
    if len(template_ids) != len(set(template_ids)):
        _fail("ambiguous", "duplicate-targeted-template", "Targeted template is duplicated")
    if template_ids != sorted(template_ids):
        _fail("corrupt", "noncanonical-targeted-template-order", "Targeted templates are not ordered")
    _verify_digest(value, "baseline_sha256", "baseline")
    return value


def _validate_scope(values, label="expected-scope", allow_empty=False):
    if not isinstance(values, list) or len(values) > MAX_SCOPE or (not allow_empty and not values):
        _fail("corrupt", "invalid-%s" % label, "%s must be a bounded list" % label)
    keys = []
    normalized = []
    for item in values:
        _exact_keys(item, {"kind", "path"}, "scope-entry")
        _enum(item["kind"], {"path", "subtree"}, "scope-kind")
        path = _path(item["path"], "scope-path")
        key = (path, item["kind"])
        keys.append(key)
        normalized.append({"kind": item["kind"], "path": path})
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "duplicate-%s" % label, "%s contains duplicates" % label)
    if keys != sorted(keys):
        _fail("corrupt", "noncanonical-%s-order" % label, "%s is not ordered" % label)
    for index, left in enumerate(normalized):
        for right in normalized[index + 1 :]:
            if _scope_entry_contains(left, right["path"]) or _scope_entry_contains(
                right, left["path"]
            ):
                _fail("ambiguous", "overlapping-%s" % label, "%s overlaps" % label)
    return normalized


def _scope_entry_contains(entry, path):
    if entry["kind"] == "path":
        return entry["path"] == path
    return path == entry["path"] or path.startswith(entry["path"] + "/")


def _scope_contains(scope, path):
    return any(_scope_entry_contains(entry, path) for entry in scope)


def _validate_classification(value, baseline):
    _exact_keys(
        value,
        {
            "work_type",
            "basis",
            "deliverable",
            "executable_change_expected",
            "expected_scope",
            "validation_profile",
            "unresolved_ambiguities",
        },
        "classification",
    )
    work_type = _enum(value["work_type"], WORK_TYPES, "work-type")
    _text(value["basis"], "classification-basis", maximum=16_384)
    deliverable = value["deliverable"]
    _exact_keys(deliverable, {"storage", "kind", "locations"}, "deliverable")
    _enum(deliverable["storage"], {"git", "evidence-cas"}, "deliverable-storage")
    expected_kind = {
        "implementation": "implementation-change",
        "design": "design-document",
        "research": "research-report",
        "documentation": "documentation-change",
    }[work_type]
    if deliverable["kind"] != expected_kind:
        _fail("denied", "deliverable-kind-mismatch", "Deliverable kind conflicts with work type")
    locations = _validate_string_list(
        deliverable["locations"],
        "deliverable-locations",
        MAX_SCOPE,
        lambda item: _path(item, "deliverable-location"),
    )
    if not locations:
        _fail("missing", "deliverable-location-missing", "Durable deliverable requires a location")
    if type(value["executable_change_expected"]) is not bool:
        _fail("corrupt", "invalid-executable-expectation", "Executable expectation must be boolean")
    scope = _validate_scope(
        value["expected_scope"],
        allow_empty=deliverable["storage"] == "evidence-cas",
    )
    ambiguities = value["unresolved_ambiguities"]
    if not isinstance(ambiguities, list) or len(ambiguities) > 32:
        _fail("corrupt", "invalid-ambiguities", "Ambiguities must be a bounded list")
    ambiguity_keys = []
    for ambiguity in ambiguities:
        _exact_keys(ambiguity, {"code", "detail"}, "ambiguity")
        key = (_slug(ambiguity["code"], "ambiguity-code"), _text(ambiguity["detail"], "ambiguity-detail"))
        ambiguity_keys.append(key)
    if len(ambiguity_keys) != len(set(ambiguity_keys)):
        _fail("ambiguous", "duplicate-ambiguity", "Ambiguity is duplicated")
    if ambiguity_keys != sorted(ambiguity_keys):
        _fail("corrupt", "noncanonical-ambiguity-order", "Ambiguities are not ordered")
    if ambiguities:
        _fail("ambiguous", "unresolved-classification-ambiguity", "Classification remains ambiguous")
    profile = _enum(value["validation_profile"], ALL_PROFILES, "validation-profile")
    if work_type == "implementation":
        if (
            deliverable["storage"] != "git"
            or value["executable_change_expected"] is not True
            or profile not in REPOSITORY_PROFILES
            or not scope
        ):
            _fail("denied", "implementation-classification-contradiction", "Implementation intake is contradictory")
        surfaces = set()
        for entry in scope:
            candidate = (
                entry["path"]
                if entry["kind"] == "path"
                else entry["path"] + "/__scope__"
            )
            surface = _classify_path(candidate)
            if surface is None:
                _fail(
                    "denied",
                    "uncovered-intake-scope",
                    "Expected scope has no policy surface",
                    entry["path"],
                )
            surfaces.add(surface)
        if not _repository_profile_matches(profile, frozenset(surfaces)):
            _fail(
                "denied",
                "profile-scope-mismatch",
                "Selected profile does not exactly cover expected scope",
            )
    else:
        expected_profile = {
            "design": "design-artifact",
            "research": "research-artifact",
            "documentation": "documentation-content-diff",
        }[work_type]
        if value["executable_change_expected"] is not False or profile != expected_profile:
            _fail("denied", "nonimplementation-classification-contradiction", "Non-implementation intake is contradictory")
        if work_type == "documentation" and deliverable["storage"] != "git":
            _fail("denied", "documentation-storage-mismatch", "Documentation must be Git-backed")
        if deliverable["storage"] == "evidence-cas" and scope:
            _fail("denied", "cas-repository-scope-mismatch", "CAS-only work requires empty repository scope")
        if deliverable["storage"] == "git" and not scope:
            _fail("missing", "repository-scope-missing", "Git-backed work requires repository scope")
        if deliverable["storage"] == "git":
            for location in locations:
                if _classify_path(location) != "documentation":
                    _fail(
                        "denied",
                        "non-content-deliverable-location",
                        "Non-implementation Git deliverables require inert documentation paths",
                        location,
                    )
            expected_artifact_scope = [
                {"kind": "path", "path": location} for location in locations
            ]
            if scope != expected_artifact_scope:
                _fail(
                    "denied",
                    "nonimplementation-scope-mismatch",
                    "Non-implementation Git scope must equal exact artifact locations",
                )
    if deliverable["storage"] == "git":
        for location in locations:
            if not _scope_contains(scope, location):
                _fail("denied", "deliverable-outside-scope", "Deliverable lies outside expected scope", location)
    return value


def _result_document(document):
    result = copy.deepcopy(document)
    result["result_sha256"] = _digest(result)
    return result


def _validate_triage_result(value, baseline):
    _exact_keys(
        value,
        {
            "format",
            "outcome",
            "issue",
            "family_run_id",
            "issue_snapshot_binding",
            "baseline_binding",
            "classification",
            "route",
            "activation",
            "request_sha256",
            "result_sha256",
        },
        "triage-result",
    )
    if value["format"] != TRIAGE_RESULT_FORMAT:
        _fail("unsupported", "unsupported-triage-result-format", "Triage result format is unsupported")
    if value["outcome"] != {"status": "resolved", "code": "classified"}:
        _fail("corrupt", "invalid-triage-outcome", "Triage outcome is invalid")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["issue_snapshot_binding"], "evidence-binding")
    _reference(value["baseline_binding"], "evidence-binding")
    classification = _validate_classification(value["classification"], baseline)
    expected_route = {
        "work_type": classification["work_type"],
        "requirements": list(ROUTE_REQUIREMENTS[classification["work_type"]]),
        "targeted_validation": "advisory-only",
        "operationally_active": False,
    }
    if value["route"] != expected_route:
        _fail("stale", "triage-route-mismatch", "Triage route differs from policy")
    expected_activation = {
        "status": "inactive",
        "owner": "issue-144",
        "unsatisfied": list(ACTIVATION_UNSATISFIED),
    }
    if value["activation"] != expected_activation:
        _fail("stale", "triage-activation-mismatch", "Triage activation boundary differs")
    _sha256(value["request_sha256"], "request-sha256")
    _verify_digest(value, "result_sha256", "triage-result")
    return value


def _decision_id(kind, document):
    _path_value, _decision_type, template, digest_field = DOCUMENT_CONTRACTS[kind]
    values = {"digest": document[digest_field]}
    if kind == "issue-snapshot":
        values["issue"] = document["issue"]
    return template.format(**values)


def _project_envelope(
    root,
    envelope,
    kind,
    validator,
    expected_subject,
    expected_issue,
    expected_family=None,
    artifact=False,
):
    _exact_keys(envelope, {"binding", "document"}, "%s-envelope" % kind)
    reference = _reference(envelope["binding"], "evidence-binding")
    document = validator(envelope["document"])
    try:
        projection = evidence.project(root, reference)
    except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    identity = projection["identity"]
    if identity["issue"] != expected_issue or identity["correction"] is not None:
        _fail("stale", "binding-identity-mismatch", "Evidence belongs to another issue")
    if expected_family is not None and identity["family_run_id"] != expected_family:
        _fail("stale", "binding-family-mismatch", "Evidence belongs to another family")
    if "issue" in document and document["issue"] != expected_issue:
        _fail("stale", "document-issue-mismatch", "Evidence document declares another issue")
    if (
        expected_family is not None
        and "family_run_id" in document
        and document["family_run_id"] != expected_family
    ):
        _fail("stale", "document-family-mismatch", "Evidence document declares another family")
    path, decision_type, _template, _field = DOCUMENT_CONTRACTS[kind]
    if projection["decision"] != {
        "type": decision_type,
        "id": _decision_id(kind, document),
    }:
        _fail("stale", "binding-decision-mismatch", "Evidence decision is incompatible")
    if projection["subject"] != expected_subject:
        _fail("stale", "binding-subject-mismatch", "Evidence subject is incompatible")
    if projection["lineage"] != {"status": "original", "parent_binding": None}:
        _fail("stale", "binding-lineage-mismatch", "V1 evidence must have original lineage")
    if projection["migration"] is not None:
        _fail("unsupported", "work-type-migration-unsupported", "Migrated V1 work-type evidence is unsupported")
    entries = projection["entries"]
    record_entries = [entry for entry in entries if entry["path"] == path]
    if len(record_entries) != 1:
        _fail("stale", "document-record-entry-mismatch", "Evidence record entry is missing or duplicated")
    record_entry = record_entries[0]
    document_data = _canonical_bytes(document)
    if (
        record_entry["kind"] != "regular"
        or record_entry["mode"] != "100644"
        or record_entry["content_sha256"] != inspector.sha256(document_data)
        or record_entry["size"] != len(document_data)
    ):
        _fail("stale", "document-record-entry-mismatch", "Evidence record does not bind the document")
    try:
        reader = inspector.AuthorityReader(inspector.resolve_store(root), expected_issue)
        stored = reader.read_bytes(record_entry["payload"], "evidence-payload")
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if stored != document_data:
        _fail("corrupt", "inline-document-payload-mismatch", "Inline document differs from stored payload")
    if not artifact and len(entries) != 1:
        _fail("ambiguous", "unexpected-document-entry", "Evidence contains unrelated entries")
    return {
        "reference": reference,
        "document": document,
        "projection": projection,
        "identity": identity,
    }


def _trusted_digest(reference, digest, label):
    _sha256(digest, label)
    if reference["sha256"] != digest:
        _fail("stale", "%s-mismatch" % label, "Request binding differs from designated digest")


def _load_issue_from_binding(root, reference, trusted_digest):
    reference = _reference(reference, "evidence-binding")
    _trusted_digest(reference, trusted_digest, "trusted-issue-snapshot-binding")
    try:
        projection = evidence.project(root, reference)
    except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    entries = projection["entries"]
    expected_path = DOCUMENT_CONTRACTS["issue-snapshot"][0]
    matches = [entry for entry in entries if entry["path"] == expected_path]
    if len(matches) != 1 or len(entries) != 1:
        _fail("stale", "issue-snapshot-entry-mismatch", "Issue snapshot entry is invalid")
    try:
        reader = inspector.AuthorityReader(inspector.resolve_store(root), projection["identity"]["issue"])
        data = reader.read_bytes(matches[0]["payload"], "evidence-payload")
        document = inspector.parse_json_object(data, "work-type issue snapshot")
    except inspector.InspectionFailure as failure:
        _fail(failure.status, failure.code, failure.message, failure.subject)
    if _canonical_bytes(document) != data:
        _fail("corrupt", "noncanonical-issue-snapshot", "Issue snapshot payload is not canonical")
    return _project_envelope(
        root,
        {"binding": reference, "document": document},
        "issue-snapshot",
        _validate_issue_snapshot,
        document["source"],
        document["issue"],
    )


def _validate_baseline_chain(root, baseline_envelope, trusted_issue, trusted_baseline):
    _exact_keys(baseline_envelope, {"binding", "document"}, "baseline-envelope")
    baseline_document = _validate_baseline(baseline_envelope["document"])
    baseline_reference = _reference(baseline_envelope["binding"], "evidence-binding")
    _trusted_digest(baseline_reference, trusted_baseline, "trusted-baseline-binding")
    issue_record = _load_issue_from_binding(
        root,
        baseline_document["issue_snapshot_binding"],
        trusted_issue,
    )
    baseline_record = _project_envelope(
        root,
        baseline_envelope,
        "baseline",
        _validate_baseline,
        issue_record["reference"],
        issue_record["document"]["issue"],
        baseline_document["family_run_id"],
    )
    if (
        baseline_document["repository"] != issue_record["document"]["repository"]
        or baseline_document["issue"] != issue_record["document"]["issue"]
        or baseline_document["issue_snapshot_binding"] != issue_record["reference"]
        or issue_record["identity"]["family_run_id"]
        != baseline_document["family_run_id"]
    ):
        _fail("stale", "baseline-issue-mismatch", "Baseline differs from issue snapshot")
    return issue_record, baseline_record


def _validate_triage_chain(
    root,
    baseline_envelope,
    triage_envelope,
    trusted_issue,
    trusted_baseline,
    trusted_triage,
):
    issue_record, baseline_record = _validate_baseline_chain(
        root, baseline_envelope, trusted_issue, trusted_baseline
    )
    _exact_keys(triage_envelope, {"binding", "document"}, "triage-envelope")
    triage_reference = _reference(triage_envelope["binding"], "evidence-binding")
    _trusted_digest(triage_reference, trusted_triage, "trusted-triage-binding")
    triage_record = _project_envelope(
        root,
        triage_envelope,
        "triage",
        lambda document: _validate_triage_result(document, baseline_record["document"]),
        baseline_record["reference"],
        baseline_record["document"]["issue"],
        baseline_record["document"]["family_run_id"],
    )
    triage = triage_record["document"]
    if (
        triage["issue_snapshot_binding"] != issue_record["reference"]
        or triage["baseline_binding"] != baseline_record["reference"]
        or triage["issue"] != baseline_record["document"]["issue"]
        or triage["family_run_id"] != baseline_record["document"]["family_run_id"]
    ):
        _fail("stale", "triage-chain-mismatch", "Triage evidence chain is inconsistent")
    return issue_record, baseline_record, triage_record


@_translate_input_errors
def classify(root, request, trusted_issue_snapshot_binding, trusted_baseline_binding):
    root = _resolve_root(root)
    _exact_keys(
        request,
        {"format", "issue_snapshot", "baseline", "classification", "request_sha256"},
        "triage-request",
    )
    if request["format"] != TRIAGE_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-triage-request-format", "Triage request format is unsupported")
    _verify_digest(request, "request_sha256", "request")
    issue_envelope = request["issue_snapshot"]
    _exact_keys(issue_envelope, {"binding", "document"}, "issue-snapshot-envelope")
    issue_document = _validate_issue_snapshot(issue_envelope["document"])
    issue_reference = _reference(issue_envelope["binding"], "evidence-binding")
    _trusted_digest(
        issue_reference,
        trusted_issue_snapshot_binding,
        "trusted-issue-snapshot-binding",
    )
    issue_record = _project_envelope(
        root,
        issue_envelope,
        "issue-snapshot",
        _validate_issue_snapshot,
        issue_document["source"],
        issue_document["issue"],
    )
    _exact_keys(request["baseline"], {"binding", "document"}, "baseline-envelope")
    baseline_document = _validate_baseline(request["baseline"]["document"])
    baseline_reference = _reference(request["baseline"]["binding"], "evidence-binding")
    _trusted_digest(baseline_reference, trusted_baseline_binding, "trusted-baseline-binding")
    baseline_record = _project_envelope(
        root,
        request["baseline"],
        "baseline",
        _validate_baseline,
        issue_reference,
        issue_document["issue"],
        baseline_document["family_run_id"],
    )
    if (
        baseline_document["repository"] != issue_document["repository"]
        or baseline_document["issue"] != issue_document["issue"]
        or baseline_document["issue_snapshot_binding"] != issue_reference
        or issue_record["identity"]["family_run_id"] != baseline_document["family_run_id"]
    ):
        _fail("stale", "baseline-issue-mismatch", "Baseline differs from issue snapshot")
    classification = _validate_classification(request["classification"], baseline_document)
    result = {
        "format": TRIAGE_RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "classified"},
        "issue": issue_document["issue"],
        "family_run_id": baseline_document["family_run_id"],
        "issue_snapshot_binding": issue_reference,
        "baseline_binding": baseline_reference,
        "classification": copy.deepcopy(classification),
        "route": {
            "work_type": classification["work_type"],
            "requirements": list(ROUTE_REQUIREMENTS[classification["work_type"]]),
            "targeted_validation": "advisory-only",
            "operationally_active": False,
        },
        "activation": {
            "status": "inactive",
            "owner": "issue-144",
            "unsatisfied": list(ACTIVATION_UNSATISFIED),
        },
        "request_sha256": request["request_sha256"],
    }
    return _result_document(result)


def _validate_selector(value, kind, maximum):
    _text(value, "targeted-selector", maximum=maximum)
    if value.startswith("-") or "\\" in value or value.startswith("/"):
        _fail("denied", "targeted-selector-option-injection", "Selector can alter command options")
    if kind == "relative-path":
        _path(value, "targeted-selector")
    elif not re.fullmatch(
        r"[A-Za-z0-9_$*?+\[\]-]+(?:\.[A-Za-z0-9_$*?+\[\]-]+)*",
        value,
    ):
        _fail("denied", "targeted-selector-grammar", "Test selector is outside its grammar")
    return value


def _scope_is_within(inner, outer):
    return all(
        any(
            parent["kind"] == "subtree"
            and (
                child["path"] == parent["path"]
                or child["path"].startswith(parent["path"] + "/")
            )
            or parent == child
            for parent in outer
        )
        for child in inner
    )


def _resolve_test_id(root, cwd, selector, declared_scope):
    parts = selector.split(".")
    for length in range(len(parts), 0, -1):
        relative = pathlib.Path(*parts[:length]).with_suffix(".py")
        try:
            effective = (cwd / relative).resolve()
        except (OSError, RuntimeError):
            _fail(
                "denied",
                "targeted-path-resolution-failed",
                "Test-ID module cannot be resolved safely",
                selector,
            )
        if not effective.is_file():
            continue
        try:
            repository_relative = effective.relative_to(root).as_posix()
            effective.relative_to(cwd)
        except ValueError:
            _fail(
                "denied",
                "targeted-test-id-outside-root",
                "Test-ID module escapes repository or cwd",
                selector,
            )
        if not _scope_contains(declared_scope, repository_relative):
            _fail(
                "denied",
                "targeted-test-id-outside-declared-scope",
                "Test-ID module lies outside declared scope",
                repository_relative,
            )
        return
    _fail(
        "denied",
        "targeted-test-id-unresolved",
        "Test-ID does not resolve to a repository Python module",
        selector,
    )


def _profile_by_id(baseline, profile_id):
    return next(profile for profile in baseline["profiles"] if profile["id"] == profile_id)


def _profile_limit(baseline, profile_id, check_name):
    return next(
        row
        for row in baseline["profile_check_limits"]
        if row["profile"] == profile_id and row["check"] == check_name
    )


def _validate_targeted_request(value, baseline, triage):
    _exact_keys(
        value,
        {"format", "baseline", "triage", "attempt_label", "selections", "request_sha256"},
        "targeted-request",
    )
    if value["format"] != TARGETED_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-targeted-request-format", "Targeted request format is unsupported")
    _slug(value["attempt_label"], "attempt-label")
    selections = value["selections"]
    if (
        not isinstance(selections, list)
        or not 1 <= len(selections) <= MAX_TARGETED_SELECTIONS
    ):
        _fail("corrupt", "invalid-targeted-selections", "Targeted selections must contain 1..4 entries")
    profile_id = triage["classification"]["validation_profile"]
    if profile_id in NON_REPOSITORY_PROFILES:
        _fail(
            "denied",
            "non-repository-profile-has-no-targeted-checks",
            "Non-repository profiles have no executable targeted checks",
        )
    profile = _profile_by_id(baseline, profile_id)
    templates = {template["id"]: template for template in baseline["targeted_templates"]}
    selection_keys = []
    resolved = []
    for selection in selections:
        _exact_keys(selection, {"kind", "id", "selectors", "declared_scope"}, "targeted-selection")
        _enum(
            selection["kind"],
            {"profile-check", "targeted-template"},
            "targeted-selection",
        )
        selection_id = _slug(selection["id"], "targeted-selection-id")
        declared_scope = _validate_scope(selection["declared_scope"], "declared-scope")
        if not _scope_is_within(declared_scope, triage["classification"]["expected_scope"]):
            _fail("denied", "targeted-scope-outside-intake", "Targeted scope exceeds triage scope")
        selectors = selection["selectors"]
        if not isinstance(selectors, list):
            _fail("corrupt", "invalid-targeted-selectors", "Targeted selectors must be a list")
        if selection["kind"] == "profile-check":
            if selectors:
                _fail("corrupt", "profile-check-selectors-prohibited", "Profile checks do not accept selectors")
            try:
                check = next(check for check in profile["checks"] if check["name"] == selection_id)
            except StopIteration:
                _fail("denied", "untrusted-profile-check", "Profile check is not frozen in the baseline")
            limits = _profile_limit(baseline, profile_id, selection_id)
            command = list(check["command"])
            cwd = check["cwd"]
        else:
            template = templates.get(selection_id)
            if template is None or profile_id not in template["profiles"]:
                _fail("denied", "untrusted-targeted-template", "Targeted template is not allowed")
            if not 1 <= len(selectors) <= template["max_selectors"]:
                _fail("corrupt", "invalid-targeted-selector-count", "Selector count is outside template limits")
            selectors = [
                _validate_selector(item, template["selector_kind"], template["max_selector_bytes"])
                for item in selectors
            ]
            if template["selector_kind"] == "relative-path":
                for selector in selectors:
                    repository_path = (
                        selector
                        if template["cwd"] == "."
                        else "%s/%s" % (template["cwd"], selector)
                    )
                    if not _scope_contains(declared_scope, repository_path):
                        _fail(
                            "denied",
                            "targeted-selector-outside-declared-scope",
                            "Targeted selector lies outside declared scope",
                            repository_path,
                        )
            command = list(template["command_prefix"]) + selectors
            if len(command) > MAX_COMMAND_PARTS:
                _fail("denied", "targeted-command-too-large", "Resolved command is too large")
            cwd = template["cwd"]
            limits = template
        key = (selection["kind"], selection_id, tuple(selectors))
        selection_keys.append(key)
        resolved.append(
            {
                "selection": copy.deepcopy(selection),
                "command": command,
                "cwd": cwd,
                "limits": {
                    "timeout_ms": limits["timeout_ms"],
                    "grace_ms": limits["grace_ms"],
                    "output_limit_bytes": limits["output_limit_bytes"],
                },
                "declared_scope": declared_scope,
                "selector_kind": (
                    None
                    if selection["kind"] == "profile-check"
                    else template["selector_kind"]
                ),
                "selector_count": len(selectors),
            }
        )
    if len(selection_keys) != len(set(selection_keys)):
        _fail("ambiguous", "duplicate-targeted-selection", "Targeted selection is duplicated")
    metadata = [
        {
            "selection": item["selection"],
            "command": item["command"],
            "cwd": item["cwd"],
            "declared_scope": item["declared_scope"],
        }
        for item in resolved
    ]
    if len(_canonical_bytes(metadata)) > MAX_TARGETED_METADATA_BYTES:
        _fail("denied", "targeted-result-metadata-limit", "Targeted result metadata exceeds 1 MiB")
    _verify_digest(value, "request_sha256", "request")
    return resolved


@_translate_input_errors
def run_targeted(
    root,
    request,
    trusted_issue_snapshot_binding,
    trusted_baseline_binding,
    trusted_triage_binding,
):
    root = _resolve_root(root)
    _exact_keys(
        request,
        {"format", "baseline", "triage", "attempt_label", "selections", "request_sha256"},
        "targeted-request",
    )
    if request["format"] != TARGETED_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-targeted-request-format", "Targeted request format is unsupported")
    _verify_digest(request, "request_sha256", "request")
    issue_record, baseline_record, triage_record = _validate_triage_chain(
        root,
        request["baseline"],
        request["triage"],
        trusted_issue_snapshot_binding,
        trusted_baseline_binding,
        trusted_triage_binding,
    )
    del issue_record
    resolved = _validate_targeted_request(
        request, baseline_record["document"], triage_record["document"]
    )
    executions = []
    all_success = True
    for item in resolved:
        try:
            cwd = (root / item["cwd"]).resolve() if item["cwd"] != "." else root
        except (OSError, RuntimeError):
            _fail(
                "denied",
                "targeted-path-resolution-failed",
                "Targeted cwd cannot be resolved safely",
                item["cwd"],
            )
        try:
            cwd.relative_to(root)
        except ValueError:
            _fail("denied", "targeted-cwd-outside-root", "Targeted cwd escapes repository")
        if not cwd.is_dir():
            _fail("missing", "targeted-cwd-missing", "Targeted cwd does not exist")
        command = list(item["command"])
        if item["selector_kind"] == "relative-path":
            safe_selectors = []
            selectors = command[-item["selector_count"] :]
            for selector in selectors:
                try:
                    effective = (cwd / selector).resolve()
                except (OSError, RuntimeError):
                    _fail(
                        "denied",
                        "targeted-path-resolution-failed",
                        "Targeted selector cannot be resolved safely",
                        selector,
                    )
                try:
                    repository_relative = effective.relative_to(root).as_posix()
                    safe_selector = effective.relative_to(cwd).as_posix()
                except ValueError:
                    _fail(
                        "denied",
                        "targeted-selector-outside-root",
                        "Resolved targeted selector escapes repository or cwd",
                        selector,
                    )
                if not _scope_contains(item["declared_scope"], repository_relative):
                    _fail(
                        "denied",
                        "targeted-selector-outside-declared-scope",
                        "Resolved targeted selector lies outside declared scope",
                        repository_relative,
                    )
                if safe_selector.startswith("-"):
                    _fail(
                        "denied",
                        "targeted-selector-option-injection",
                        "Resolved targeted selector can alter command options",
                        safe_selector,
                    )
                safe_selectors.append(safe_selector)
            command[-item["selector_count"] :] = safe_selectors
        elif item["selector_kind"] == "test-id":
            for selector in command[-item["selector_count"] :]:
                _resolve_test_id(
                    root,
                    cwd,
                    selector,
                    item["declared_scope"],
                )
        try:
            process_result = supervisor.supervise(
                command,
                timeout_ms=item["limits"]["timeout_ms"],
                grace_ms=item["limits"]["grace_ms"],
                output_limit_bytes=item["limits"]["output_limit_bytes"],
                cwd=str(cwd),
            )
        except ValueError as failure:
            _fail("corrupt", "invalid-supervisor-input", str(failure))
        success = process_result["outcome"] == "success"
        all_success = all_success and success
        executions.append(
            {
                "selection": item["selection"],
                "command": command,
                "cwd": item["cwd"],
                "process_result": process_result,
                "observed_status": "success" if success else "failure",
                "declared_scope": item["declared_scope"],
            }
        )
    result = {
        "format": TARGETED_RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": "advisory-observed"},
        "authority": "advisory-only",
        "issue": triage_record["document"]["issue"],
        "family_run_id": triage_record["document"]["family_run_id"],
        "triage_binding": triage_record["reference"],
        "attempt_label": request["attempt_label"],
        "executions": executions,
        "overall_status": "observed-success" if all_success else "observed-failure",
        "limitations": list(TARGETED_LIMITATIONS),
        "request_sha256": request["request_sha256"],
    }
    result = _result_document(result)
    if len(_canonical_bytes(result)) > MAX_STRUCTURED_BYTES:
        _fail("denied", "targeted-result-metadata-limit", "Targeted result exceeds 8 MiB")
    return result


def _validate_workspace_record(value):
    _exact_keys(value, {"code", "path", "original_path"}, "workspace-record")
    _enum(value["code"], {"A", "C", "D", "M", "R", "T", "U"}, "workspace-status")
    _path(value["path"], "workspace-path")
    if value["code"] in {"C", "R"}:
        _path(value["original_path"], "workspace-original-path")
    elif value["original_path"] is not None:
        _fail("corrupt", "unexpected-workspace-original-path", "Workspace original path is unexpected")
    return value


def _validate_workspace(value):
    _exact_keys(
        value,
        {
            "staged",
            "unstaged",
            "untracked_non_ignored",
            "assume_unchanged",
            "skip_worktree",
            "status_sha256",
        },
        "workspace",
    )
    for field in ("staged", "unstaged"):
        records = value[field]
        if not isinstance(records, list) or len(records) > MAX_ENTRIES:
            _fail("corrupt", "invalid-workspace-records", "Workspace records are invalid")
        keys = []
        for record in records:
            _validate_workspace_record(record)
            keys.append((record["path"], record["original_path"] or "", record["code"]))
        if len(keys) != len(set(keys)):
            _fail("ambiguous", "duplicate-workspace-record", "Workspace record is duplicated")
        if keys != sorted(keys):
            _fail("corrupt", "noncanonical-workspace-order", "Workspace records are not ordered")
    for field in ("untracked_non_ignored", "assume_unchanged", "skip_worktree"):
        _validate_string_list(
            value[field],
            field.replace("_", "-"),
            MAX_ENTRIES,
            lambda item: _path(item, field.replace("_", "-")),
        )
    _verify_digest(value, "status_sha256", "workspace-status")
    return value


def _zero_oid(length):
    return "0" * length


def _validate_change(value, oid_length):
    _exact_keys(
        value,
        {
            "status",
            "old_mode",
            "new_mode",
            "old_oid",
            "new_oid",
            "old_path",
            "new_path",
        },
        "diff-change",
    )
    status = _enum(value["status"], {"A", "D", "M", "T"}, "diff-status")
    modes = {"000000", "100644", "100755", "120000", "160000"}
    _enum(value["old_mode"], modes, "old-mode")
    _enum(value["new_mode"], modes, "new-mode")
    _git_oid(value["old_oid"], oid_length, "old-object", allow_zero=True)
    _git_oid(value["new_oid"], oid_length, "new-object", allow_zero=True)
    if status == "A":
        if (
            value["old_mode"] != "000000"
            or value["old_oid"] != _zero_oid(oid_length)
            or value["old_path"] is not None
            or value["new_mode"] == "000000"
            or value["new_oid"] == _zero_oid(oid_length)
        ):
            _fail("corrupt", "invalid-add-change", "Added change has invalid old/new facts")
        _path(value["new_path"], "new-path")
    elif status == "D":
        if (
            value["new_mode"] != "000000"
            or value["new_oid"] != _zero_oid(oid_length)
            or value["new_path"] is not None
            or value["old_mode"] == "000000"
            or value["old_oid"] == _zero_oid(oid_length)
        ):
            _fail("corrupt", "invalid-delete-change", "Deleted change has invalid old/new facts")
        _path(value["old_path"], "old-path")
    else:
        if (
            value["old_mode"] == "000000"
            or value["new_mode"] == "000000"
            or value["old_path"] is None
            or value["new_path"] is None
            or value["old_path"] != value["new_path"]
            or value["old_oid"] == _zero_oid(oid_length)
            or value["new_oid"] == _zero_oid(oid_length)
        ):
            _fail("corrupt", "invalid-modify-change", "Modified change has invalid facts")
        _path(value["old_path"], "old-path")
    return value


def _validate_observation(value):
    _exact_keys(
        value,
        {
            "format",
            "repository",
            "issue",
            "family_run_id",
            "triage_binding",
            "observer",
            "observed_at",
            "object_format",
            "base",
            "head",
            "ancestry",
            "changes",
            "workspace",
            "git_trust",
            "head_config",
            "raw_diff_sha256",
            "observation_sha256",
        },
        "diff-observation",
    )
    if value["format"] != OBSERVATION_FORMAT:
        _fail("unsupported", "unsupported-observation-format", "Diff observation format is unsupported")
    if not isinstance(value["repository"], str) or REPOSITORY_RE.fullmatch(value["repository"]) is None:
        _fail("corrupt", "invalid-repository", "Repository identity is invalid")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["triage_binding"], "evidence-binding")
    observer = value["observer"]
    _exact_keys(observer, {"name", "version", "source_sha256"}, "observer")
    _slug(observer["name"], "observer-name")
    _slug(observer["version"], "observer-version")
    _sha256(observer["source_sha256"], "observer-source")
    _timestamp(value["observed_at"], "observed-at")
    _enum(value["object_format"], {"sha1", "sha256"}, "object-format")
    oid_length = 40 if value["object_format"] == "sha1" else 64
    base = value["base"]
    _exact_keys(base, {"ref", "commit", "tree"}, "observation-base")
    _text(base["ref"], "base-ref", maximum=512)
    _git_oid(base["commit"], oid_length, "base-commit")
    _git_oid(base["tree"], oid_length, "base-tree")
    head = value["head"]
    _exact_keys(head, {"commit", "tree"}, "observation-head")
    _git_oid(head["commit"], oid_length, "head-commit")
    _git_oid(head["tree"], oid_length, "head-tree")
    ancestry = value["ancestry"]
    _exact_keys(ancestry, {"base_is_ancestor", "commit_count"}, "observation-ancestry")
    if type(ancestry["base_is_ancestor"]) is not bool:
        _fail("corrupt", "invalid-observation-ancestry", "Ancestry flag must be boolean")
    _uint(ancestry["commit_count"], "commit-count")
    changes = value["changes"]
    if not isinstance(changes, list) or len(changes) > MAX_ENTRIES:
        _fail("corrupt", "invalid-diff-changes", "Diff changes must be bounded")
    keys = []
    seen_paths = set()
    for change in changes:
        _validate_change(change, oid_length)
        keys.append(
            (
                change["old_path"] or "",
                change["new_path"] or "",
                change["status"],
                change["old_oid"],
                change["new_oid"],
            )
        )
        change_paths = set(_change_paths(change))
        if seen_paths.intersection(change_paths):
            _fail(
                "ambiguous",
                "conflicting-diff-path",
                "A diff path appears in more than one change record",
            )
        seen_paths.update(change_paths)
    if len(keys) != len(set(keys)):
        _fail("ambiguous", "duplicate-diff-change", "Diff change is duplicated")
    if keys != sorted(keys):
        _fail("corrupt", "noncanonical-diff-order", "Diff changes are not ordered")
    _validate_workspace(value["workspace"])
    trust = value["git_trust"]
    _exact_keys(
        trust,
        {
            "no_replace_objects",
            "replacement_refs",
            "git_replace_ref_base",
            "git_graft_file",
            "info_grafts_present",
            "environment_redirections",
            "alternate_object_directories",
        },
        "git-trust",
    )
    if type(trust["no_replace_objects"]) is not bool or type(trust["info_grafts_present"]) is not bool:
        _fail("corrupt", "invalid-git-trust-flags", "Git trust flags must be boolean")
    replacement_refs = trust["replacement_refs"]
    if not isinstance(replacement_refs, list) or len(replacement_refs) > MAX_ENTRIES:
        _fail("corrupt", "invalid-replacement-refs", "Replacement refs are invalid")
    ref_keys = []
    for ref in replacement_refs:
        _exact_keys(ref, {"name", "object_id"}, "replacement-ref")
        name = _text(ref["name"], "replacement-ref-name", maximum=512)
        oid = _git_oid(ref["object_id"], oid_length, "replacement-object")
        ref_keys.append((name, oid))
    if len(ref_keys) != len(set(ref_keys)) or ref_keys != sorted(ref_keys):
        _fail("ambiguous", "invalid-replacement-ref-set", "Replacement refs are ambiguous")
    for field in ("git_replace_ref_base", "git_graft_file"):
        if trust[field] is not None:
            _text(trust[field], field.replace("_", "-"), maximum=4096)
    for field in ("environment_redirections", "alternate_object_directories"):
        _validate_string_list(
            trust[field],
            field.replace("_", "-"),
            128,
            lambda item: _text(item, field.replace("_", "-"), maximum=4096),
        )
    config = value["head_config"]
    _exact_keys(config, {"path", "blob_oid", "content_sha256", "size"}, "head-config")
    if config["path"] != ".github/agent-workflow.json":
        _fail("stale", "head-config-path-mismatch", "HEAD config path is invalid")
    _git_oid(config["blob_oid"], oid_length, "head-config-blob")
    _sha256(config["content_sha256"], "head-config-content")
    _uint(config["size"], "head-config-size", MAX_CONFIG_BYTES)
    _sha256(value["raw_diff_sha256"], "raw-diff")
    same_commit = base["commit"] == head["commit"]
    same_tree = base["tree"] == head["tree"]
    no_changes = not changes
    if (
        (ancestry["commit_count"] == 0) != same_commit
        or (same_commit and not same_tree)
        or same_tree != no_changes
    ):
        _fail(
            "corrupt",
            "observation-repository-facts-mismatch",
            "Commit, tree, count, and change facts are contradictory",
        )
    if value["raw_diff_sha256"] != _digest(changes):
        _fail(
            "corrupt",
            "raw-diff-changes-mismatch",
            "Raw diff identity differs from normalized change records",
        )
    _verify_digest(value, "observation_sha256", "observation")
    return value


def _validate_artifact(value):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "triage_binding",
            "work_type",
            "storage",
            "kind",
            "locations",
            "entries",
            "artifact_sha256",
        },
        "artifact",
    )
    if value["format"] != ARTIFACT_FORMAT:
        _fail("unsupported", "unsupported-artifact-format", "Artifact format is unsupported")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["triage_binding"], "evidence-binding")
    _enum(
        value["work_type"],
        {"design", "research", "documentation"},
        "artifact-work-type",
    )
    expected = {
        "design": "design-document",
        "research": "research-report",
        "documentation": "documentation-change",
    }[value["work_type"]]
    _enum(value["storage"], {"git", "evidence-cas"}, "artifact-storage")
    if value["kind"] != expected:
        _fail("denied", "artifact-kind-mismatch", "Artifact kind/storage is incompatible")
    locations = _validate_string_list(
        value["locations"],
        "artifact-locations",
        MAX_ENTRIES,
        lambda item: _path(item, "artifact-location"),
    )
    entries = value["entries"]
    if not isinstance(entries, list) or not entries or len(entries) > MAX_ENTRIES:
        _fail("corrupt", "invalid-artifact-entries", "Artifact entries must be nonempty")
    paths = []
    for entry in entries:
        _exact_keys(
            entry,
            {"path", "kind", "mode", "content_sha256", "size", "payload"},
            "artifact-entry",
        )
        path = _path(entry["path"], "artifact-path")
        paths.append(path)
        if entry["kind"] != "regular" or entry["mode"] != "100644":
            _fail("denied", "non-content-artifact", "Artifacts must be regular non-executable files")
        _sha256(entry["content_sha256"], "artifact-content")
        _uint(entry["size"], "artifact-size", evidence.PAYLOAD_LIMIT)
        if entry["size"] < 1:
            _fail("corrupt", "empty-artifact", "Artifact content must be nonempty")
        payload = _reference(entry["payload"], "evidence-payload")
        if payload["sha256"] != entry["content_sha256"] or payload["size"] != entry["size"]:
            _fail("corrupt", "artifact-payload-mismatch", "Artifact payload facts conflict")
    if len(paths) != len(set(paths)) or paths != sorted(paths):
        _fail("ambiguous", "invalid-artifact-paths", "Artifact paths are duplicated or unordered")
    if locations != paths:
        _fail("stale", "artifact-location-mismatch", "Artifact locations differ from entries")
    _verify_digest(value, "artifact_sha256", "artifact")
    return value


def _validate_review(value):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "artifact_binding",
            "reviewer",
            "status",
            "findings",
            "reviewed_artifact_sha256",
            "reviewed_at",
            "review_sha256",
        },
        "independent-review",
    )
    if value["format"] != REVIEW_FORMAT:
        _fail("unsupported", "unsupported-review-format", "Review format is unsupported")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["artifact_binding"], "evidence-binding")
    reviewer = value["reviewer"]
    _exact_keys(reviewer, {"role", "actor"}, "reviewer")
    if reviewer["role"] != "independent-reviewer":
        _fail("denied", "reviewer-role-mismatch", "Review role is not independent-reviewer")
    _text(reviewer["actor"], "reviewer-actor", maximum=256)
    _enum(value["status"], {"accepted", "needs-revision"}, "review-status")
    findings = value["findings"]
    if not isinstance(findings, list) or len(findings) > MAX_FINDINGS:
        _fail("corrupt", "invalid-review-findings", "Review findings are invalid")
    keys = []
    for finding in findings:
        _exact_keys(finding, {"id", "severity", "detail"}, "review-finding")
        finding_id = _slug(finding["id"], "finding-id")
        _enum(
            finding["severity"],
            {"info", "warning", "blocking"},
            "finding-severity",
        )
        detail = _text(finding["detail"], "finding-detail")
        keys.append((finding_id, finding["severity"], detail))
    if len(keys) != len(set(keys)) or keys != sorted(keys):
        _fail("ambiguous", "invalid-review-findings", "Review findings are duplicated or unordered")
    if value["status"] == "accepted" and any(
        finding["severity"] == "blocking" for finding in findings
    ):
        _fail("denied", "accepted-review-has-blocking-finding", "Accepted review has a blocking finding")
    _sha256(value["reviewed_artifact_sha256"], "reviewed-artifact")
    _timestamp(value["reviewed_at"], "reviewed-at")
    _verify_digest(value, "review_sha256", "review")
    return value


def _validate_acceptance(value):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "artifact_binding",
            "review_binding",
            "actor",
            "confirmation",
            "accepted_at",
            "acceptance_sha256",
        },
        "human-acceptance",
    )
    if value["format"] != ACCEPTANCE_FORMAT:
        _fail("unsupported", "unsupported-acceptance-format", "Acceptance format is unsupported")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["artifact_binding"], "evidence-binding")
    _reference(value["review_binding"], "evidence-binding")
    _text(value["actor"], "acceptance-actor", maximum=256)
    if value["confirmation"] != "artifact_accepted":
        _fail("denied", "acceptance-confirmation-mismatch", "Acceptance confirmation is invalid")
    _timestamp(value["accepted_at"], "accepted-at")
    _verify_digest(value, "acceptance_sha256", "acceptance")
    return value


def _validate_content_check(value):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "artifact_binding",
            "observation_binding",
            "status",
            "checks",
            "check_sha256",
        },
        "documentation-content-check",
    )
    if value["format"] != CONTENT_CHECK_FORMAT:
        _fail("unsupported", "unsupported-content-check-format", "Content check format is unsupported")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["artifact_binding"], "evidence-binding")
    _reference(value["observation_binding"], "evidence-binding")
    _enum(
        value["status"],
        {"pass", "fail"},
        "documentation-check-status",
    )
    checks = value["checks"]
    if not isinstance(checks, list) or not checks or len(checks) > MAX_ENTRIES:
        _fail("corrupt", "invalid-content-checks", "Content checks must be nonempty")
    paths = []
    oid_length = None
    for check in checks:
        _exact_keys(
            check,
            {
                "path",
                "artifact_content_sha256",
                "observed_new_oid",
                "observed_content_sha256",
                "status",
            },
            "documentation-content-check-entry",
        )
        paths.append(_path(check["path"], "content-check-path"))
        _sha256(check["artifact_content_sha256"], "artifact-content")
        _sha256(check["observed_content_sha256"], "observed-content")
        candidate_length = len(check["observed_new_oid"]) if isinstance(check["observed_new_oid"], str) else 0
        if candidate_length not in {40, 64}:
            _fail("corrupt", "invalid-observed-new-object", "Observed object ID is invalid")
        if oid_length is None:
            oid_length = candidate_length
        _git_oid(check["observed_new_oid"], oid_length, "observed-new-object")
        _enum(
            check["status"],
            {"pass", "fail"},
            "documentation-check-status",
        )
    if len(paths) != len(set(paths)) or paths != sorted(paths):
        _fail("ambiguous", "invalid-content-check-paths", "Content check paths are duplicated or unordered")
    if (value["status"] == "pass") != all(check["status"] == "pass" for check in checks):
        _fail("corrupt", "documentation-content-status-mismatch", "Content check summary is inconsistent")
    _verify_digest(value, "check_sha256", "content-check")
    return value


def _validate_diff_check(value):
    _exact_keys(
        value,
        {
            "format",
            "issue",
            "family_run_id",
            "artifact_binding",
            "observation_binding",
            "declared_locations",
            "observed_changes_sha256",
            "status",
            "check_sha256",
        },
        "documentation-diff-check",
    )
    if value["format"] != DIFF_CHECK_FORMAT:
        _fail("unsupported", "unsupported-diff-check-format", "Diff check format is unsupported")
    _issue(value["issue"])
    _run_id(value["family_run_id"])
    _reference(value["artifact_binding"], "evidence-binding")
    _reference(value["observation_binding"], "evidence-binding")
    _validate_string_list(
        value["declared_locations"],
        "declared-locations",
        MAX_ENTRIES,
        lambda item: _path(item, "declared-location"),
    )
    _sha256(value["observed_changes_sha256"], "observed-changes")
    _enum(
        value["status"],
        {"pass", "fail"},
        "documentation-check-status",
    )
    _verify_digest(value, "check_sha256", "diff-check")
    return value


def _project_artifact(root, envelope, triage_reference, issue, family):
    record = _project_envelope(
        root,
        envelope,
        "artifact",
        _validate_artifact,
        triage_reference,
        issue,
        family,
        artifact=True,
    )
    document = record["document"]
    expected_entries = {
        (
            entry["path"],
            entry["kind"],
            entry["mode"],
            entry["content_sha256"],
            entry["size"],
            tuple(sorted(entry["payload"].items())),
        )
        for entry in document["entries"]
    }
    manifest_entries = {
        (
            entry["path"],
            entry["kind"],
            entry["mode"],
            entry["content_sha256"],
            entry["size"],
            tuple(sorted(entry["payload"].items())),
        )
        for entry in record["projection"]["entries"]
        if entry["path"] != DOCUMENT_CONTRACTS["artifact"][0]
    }
    if expected_entries != manifest_entries:
        _fail("stale", "artifact-manifest-mismatch", "Artifact manifest differs from artifact record")
    return record


def _classify_path(path):
    if path.startswith("scripts/") or path.startswith(".github/agents/"):
        return "workflow-tooling"
    if path in WORKFLOW_FILES or path in WORKFLOW_DOCS:
        return "workflow-tooling"
    suffix = pathlib.PurePosixPath(path).suffix.lower()
    name = pathlib.PurePosixPath(path).name.lower()
    if (
        path.startswith("docs/")
        and suffix in INERT_DOCUMENT_SUFFIXES
    ) or (
        name.startswith("readme")
        and suffix in INERT_DOCUMENT_SUFFIXES
    ):
        return "documentation"
    if path.startswith("frontend/"):
        return "frontend"
    if path.startswith("src/") or path.startswith("gradle/") or path in BACKEND_FILES:
        return "backend"
    if path.startswith("docs/"):
        return "workflow-tooling"
    return None


def _repository_profile_matches(profile, surfaces):
    if profile == "backend":
        return "backend" in surfaces and surfaces <= PROFILE_COVERAGE[profile]
    if profile == "frontend":
        return "frontend" in surfaces and surfaces <= PROFILE_COVERAGE[profile]
    if profile == "full-stack":
        return {"backend", "frontend"} <= surfaces <= PROFILE_COVERAGE[profile]
    if profile == "workflow-tooling":
        return "workflow-tooling" in surfaces and surfaces <= PROFILE_COVERAGE[profile]
    return False


def _change_paths(change):
    return [path for path in (change["old_path"], change["new_path"]) if path is not None]


def _fully_clean(observation):
    workspace = observation["workspace"]
    return not any(
        workspace[field]
        for field in (
            "staged",
            "unstaged",
            "untracked_non_ignored",
            "assume_unchanged",
            "skip_worktree",
        )
    )


def _git_trust_clean(observation):
    trust = observation["git_trust"]
    return (
        trust["no_replace_objects"] is True
        and not trust["replacement_refs"]
        and trust["git_replace_ref_base"] is None
        and trust["git_graft_file"] is None
        and trust["info_grafts_present"] is False
        and not trust["environment_redirections"]
        and not trust["alternate_object_directories"]
    )


def _assess_scope(baseline, triage, observation):
    classification = triage["classification"]
    if (
        observation["repository"] != baseline["repository"]
        or observation["issue"] != baseline["issue"]
        or observation["family_run_id"] != baseline["family_run_id"]
        or observation["triage_binding"] != triage["_binding"]
    ):
        _fail("stale", "observation-identity-mismatch", "Observation differs from triage identity")
    if observation["base"] != {
        "ref": baseline["target_base"]["ref"],
        "commit": baseline["target_base"]["commit"],
        "tree": baseline["target_base"]["tree"],
    }:
        _fail("stale", "observation-base-mismatch", "Observation base differs from trusted baseline")
    if observation["ancestry"]["base_is_ancestor"] is not True:
        _fail("denied", "base-not-ancestor", "Observed HEAD does not descend from base")
    config = baseline["config"]
    if observation["head_config"] != {
        "path": config["path"],
        "blob_oid": config["blob_oid"],
        "content_sha256": config["content_sha256"],
        "size": config["size"],
    }:
        _fail("stale", "baseline-config-mismatch", "HEAD config differs from trusted base config")
    if not _fully_clean(observation):
        _fail("denied", "final-workspace-not-clean", "Final observation includes workspace changes")
    if not _git_trust_clean(observation):
        _fail("denied", "git-trust-controls-unsatisfied", "Git replacement/graft controls are not clean")
    surfaces = set()
    executable = False
    scope = classification["expected_scope"]
    for change in observation["changes"]:
        for path in _change_paths(change):
            surface = _classify_path(path)
            if surface is None:
                _fail("denied", "uncovered-diff-path", "Changed path has no policy surface", path)
            surfaces.add(surface)
            if not _scope_contains(scope, path):
                _fail("denied", "diff-outside-declared-scope", "Changed path lies outside declared scope", path)
        if (
            any(_classify_path(path) != "documentation" for path in _change_paths(change))
            or change["old_mode"] in {"100755", "120000", "160000"}
            or change["new_mode"] in {"100755", "120000", "160000"}
            or change["status"] == "T"
        ):
            executable = True
    storage = classification["deliverable"]["storage"]
    work_type = classification["work_type"]
    if storage == "evidence-cas":
        if observation["changes"]:
            _fail("denied", "cas-repository-diff", "CAS-only work requires an empty Git diff")
        surfaces = {"evidence-artifact"}
    ordered_surfaces = [surface for surface in SURFACE_ORDER if surface in surfaces]
    profile = classification["validation_profile"]
    surface_set = frozenset(surfaces)
    valid = False
    if profile in REPOSITORY_PROFILES:
        valid = _repository_profile_matches(profile, surface_set)
    elif profile in {"design-artifact", "research-artifact"}:
        valid = surface_set == frozenset(
            {"evidence-artifact"} if storage == "evidence-cas" else {"documentation"}
        )
    elif profile == "documentation-content-diff":
        valid = surface_set == frozenset({"documentation"})
    if not valid:
        _fail("denied", "profile-scope-mismatch", "Selected profile does not exactly cover observed surfaces")
    if work_type != "implementation":
        if executable:
            _fail("denied", "scope-drift-requires-implementation", "Non-implementation diff contains executable changes")
        if storage == "git":
            locations = classification["deliverable"]["locations"]
            changed_paths = []
            for change in observation["changes"]:
                if (
                    change["status"] not in {"A", "M"}
                    or change["old_mode"] not in {"000000", "100644"}
                    or change["new_mode"] != "100644"
                ):
                    _fail("denied", "scope-drift-requires-implementation", "Non-implementation change is not content-only")
                changed_paths.extend(_change_paths(change))
            if sorted(set(changed_paths)) != locations:
                _fail("denied", "artifact-diff-location-mismatch", "Observed changes differ from deliverable locations")
    return {
        "status": "conforms",
        "surfaces": ordered_surfaces,
        "selected_profile": profile,
        "profile_covers_surfaces": True,
        "within_declared_scope": True,
        "fully_clean_workspace": True,
        "baseline_matches": True,
        "executable_change_present": executable,
    }


def _validate_completion_request(value):
    _exact_keys(
        value,
        {
            "format",
            "issue_snapshot",
            "baseline",
            "triage",
            "observation",
            "artifact",
            "review",
            "acceptance",
            "documentation_content_check",
            "documentation_diff_check",
            "request_sha256",
        },
        "completion-request",
    )
    if value["format"] != COMPLETION_REQUEST_FORMAT:
        _fail("unsupported", "unsupported-completion-request-format", "Completion request format is unsupported")
    _verify_digest(value, "request_sha256", "request")
    return value


def _reference_or_none(record):
    return None if record is None else record["reference"]


def _validate_nonimplementation(
    root,
    request,
    triage_record,
    observation_record,
    designated_review,
    designated_acceptance,
):
    triage = triage_record["document"]
    classification = triage["classification"]
    issue = triage["issue"]
    family = triage["family_run_id"]
    artifact_record = _project_artifact(
        root, request["artifact"], triage_record["reference"], issue, family
    )
    artifact = artifact_record["document"]
    if (
        artifact["work_type"] != classification["work_type"]
        or artifact["storage"] != classification["deliverable"]["storage"]
        or artifact["locations"] != classification["deliverable"]["locations"]
        or artifact["triage_binding"] != triage_record["reference"]
    ):
        _fail("stale", "artifact-triage-mismatch", "Artifact differs from triage deliverable")
    if artifact["storage"] == "git":
        observed_changes = {
            change["new_path"]: change
            for change in observation_record["document"]["changes"]
            if change["new_path"] is not None
        }
        artifact_entries = {entry["path"]: entry for entry in artifact["entries"]}
        if set(observed_changes) != set(artifact_entries):
            _fail("stale", "artifact-observation-coverage-mismatch", "Git artifact differs from observed paths")
        oid_length = (
            40 if observation_record["document"]["object_format"] == "sha1" else 64
        )
        reader = inspector.AuthorityReader(inspector.resolve_store(root), issue)
        for path, entry in artifact_entries.items():
            try:
                payload = reader.read_bytes(entry["payload"], "evidence-payload")
            except inspector.InspectionFailure as failure:
                _fail(failure.status, failure.code, failure.message, failure.subject)
            if _git_blob_oid(payload, oid_length) != observed_changes[path]["new_oid"]:
                _fail("stale", "artifact-observation-content-mismatch", "Git artifact content differs from observed object", path)
    review_reference = _reference(request["review"]["binding"], "evidence-binding")
    _trusted_digest(review_reference, designated_review, "designated-review-binding")
    review_record = _project_envelope(
        root,
        request["review"],
        "review",
        _validate_review,
        artifact_record["reference"],
        issue,
        family,
    )
    review = review_record["document"]
    if (
        review["artifact_binding"] != artifact_record["reference"]
        or review["reviewed_artifact_sha256"] != artifact["artifact_sha256"]
        or review["status"] != "accepted"
    ):
        _fail("denied", "artifact-review-not-accepted", "Artifact review is not accepted")
    acceptance_reference = _reference(request["acceptance"]["binding"], "evidence-binding")
    _trusted_digest(
        acceptance_reference,
        designated_acceptance,
        "designated-acceptance-binding",
    )
    acceptance_record = _project_envelope(
        root,
        request["acceptance"],
        "acceptance",
        _validate_acceptance,
        review_record["reference"],
        issue,
        family,
    )
    acceptance = acceptance_record["document"]
    if (
        acceptance["artifact_binding"] != artifact_record["reference"]
        or acceptance["review_binding"] != review_record["reference"]
    ):
        _fail("stale", "acceptance-chain-mismatch", "Acceptance differs from review chain")
    content_record = None
    diff_record = None
    if classification["work_type"] == "documentation":
        content_record = _project_envelope(
            root,
            request["documentation_content_check"],
            "content-check",
            _validate_content_check,
            artifact_record["reference"],
            issue,
            family,
        )
        diff_record = _project_envelope(
            root,
            request["documentation_diff_check"],
            "diff-check",
            _validate_diff_check,
            observation_record["reference"],
            issue,
            family,
        )
        content = content_record["document"]
        diff = diff_record["document"]
        if (
            content["artifact_binding"] != artifact_record["reference"]
            or content["observation_binding"] != observation_record["reference"]
            or diff["artifact_binding"] != artifact_record["reference"]
            or diff["observation_binding"] != observation_record["reference"]
        ):
            _fail("stale", "documentation-check-chain-mismatch", "Documentation checks name another chain")
        if content["status"] != "pass" or diff["status"] != "pass":
            _fail("denied", "documentation-validation-failed", "Documentation checks did not pass")
        if diff["declared_locations"] != artifact["locations"]:
            _fail("stale", "documentation-diff-location-mismatch", "Diff check locations differ")
        changes_digest = _digest(observation_record["document"]["changes"])
        if diff["observed_changes_sha256"] != changes_digest:
            _fail("stale", "documentation-diff-mismatch", "Diff check names another observation")
        artifact_entries = {entry["path"]: entry for entry in artifact["entries"]}
        observed_changes = {
            change["new_path"]: change
            for change in observation_record["document"]["changes"]
            if change["new_path"] is not None
        }
        if set(artifact_entries) != set(observed_changes) or [
            check["path"] for check in content["checks"]
        ] != sorted(artifact_entries):
            _fail("stale", "documentation-content-coverage-mismatch", "Content checks are incomplete")
        reader = inspector.AuthorityReader(inspector.resolve_store(root), issue)
        oid_length = 40 if observation_record["document"]["object_format"] == "sha1" else 64
        for check in content["checks"]:
            entry = artifact_entries[check["path"]]
            try:
                payload = reader.read_bytes(entry["payload"], "evidence-payload")
            except inspector.InspectionFailure as failure:
                _fail(failure.status, failure.code, failure.message, failure.subject)
            change = observed_changes[check["path"]]
            if (
                check["status"] != "pass"
                or check["artifact_content_sha256"] != entry["content_sha256"]
                or check["observed_content_sha256"] != inspector.sha256(payload)
                or check["observed_new_oid"] != change["new_oid"]
                or check["observed_new_oid"] != _git_blob_oid(payload, oid_length)
            ):
                _fail("stale", "documentation-content-mismatch", "Content check differs from artifact/diff")
    return {
        "artifact": artifact_record,
        "review": review_record,
        "acceptance": acceptance_record,
        "content": content_record,
        "diff": diff_record,
    }


@_translate_input_errors
def assess_completion(
    root,
    request,
    trusted_issue_snapshot_binding,
    trusted_baseline_binding,
    trusted_triage_binding,
    designated_observation_binding,
    designated_review_binding=None,
    designated_acceptance_binding=None,
):
    root = _resolve_root(root)
    _validate_completion_request(request)
    issue_envelope = request["issue_snapshot"]
    _exact_keys(issue_envelope, {"binding", "document"}, "issue-snapshot-envelope")
    issue_reference = _reference(issue_envelope["binding"], "evidence-binding")
    _trusted_digest(
        issue_reference,
        trusted_issue_snapshot_binding,
        "trusted-issue-snapshot-binding",
    )
    issue_document = _validate_issue_snapshot(issue_envelope["document"])
    issue_record = _project_envelope(
        root,
        issue_envelope,
        "issue-snapshot",
        _validate_issue_snapshot,
        issue_document["source"],
        issue_document["issue"],
    )
    chain_issue, baseline_record, triage_record = _validate_triage_chain(
        root,
        request["baseline"],
        request["triage"],
        trusted_issue_snapshot_binding,
        trusted_baseline_binding,
        trusted_triage_binding,
    )
    if chain_issue["reference"] != issue_record["reference"]:
        _fail("stale", "completion-issue-mismatch", "Completion issue snapshot differs")
    _exact_keys(request["observation"], {"binding", "document"}, "observation-envelope")
    observation_reference = _reference(request["observation"]["binding"], "evidence-binding")
    _trusted_digest(
        observation_reference,
        designated_observation_binding,
        "designated-observation-binding",
    )
    observation_record = _project_envelope(
        root,
        request["observation"],
        "observation",
        _validate_observation,
        triage_record["reference"],
        triage_record["document"]["issue"],
        triage_record["document"]["family_run_id"],
    )
    triage_for_scope = copy.deepcopy(triage_record["document"])
    triage_for_scope["_binding"] = triage_record["reference"]
    scope = _assess_scope(
        baseline_record["document"], triage_for_scope, observation_record["document"]
    )
    work_type = triage_record["document"]["classification"]["work_type"]
    optionals = (
        "artifact",
        "review",
        "acceptance",
        "documentation_content_check",
        "documentation_diff_check",
    )
    structural = {
        "artifact_binding": None,
        "review_binding": None,
        "acceptance_binding": None,
        "documentation_content_check_binding": None,
        "documentation_diff_check_binding": None,
    }
    if work_type == "implementation":
        if any(request[field] is not None for field in optionals):
            _fail("ambiguous", "unexpected-completion-evidence", "Implementation cannot supply completion evidence")
        if designated_review_binding is not None or designated_acceptance_binding is not None:
            _fail("ambiguous", "unexpected-designated-evidence", "Implementation cannot designate artifact approval")
        code = "implementation-route-conforms"
    else:
        if any(request[field] is None for field in ("artifact", "review", "acceptance")):
            _fail("missing", "nonimplementation-evidence-missing", "Artifact/review/acceptance is required")
        if designated_review_binding is None or designated_acceptance_binding is None:
            _fail("missing", "designated-evidence-missing", "Review/acceptance designation is required")
        if work_type == "documentation":
            if (
                request["documentation_content_check"] is None
                or request["documentation_diff_check"] is None
            ):
                _fail("missing", "documentation-check-missing", "Documentation checks are required")
        elif (
            request["documentation_content_check"] is not None
            or request["documentation_diff_check"] is not None
        ):
            _fail("ambiguous", "unexpected-documentation-check", "Design/research cannot supply documentation checks")
        records = _validate_nonimplementation(
            root,
            request,
            triage_record,
            observation_record,
            designated_review_binding,
            designated_acceptance_binding,
        )
        structural = {
            "artifact_binding": records["artifact"]["reference"],
            "review_binding": records["review"]["reference"],
            "acceptance_binding": records["acceptance"]["reference"],
            "documentation_content_check_binding": _reference_or_none(records["content"]),
            "documentation_diff_check_binding": _reference_or_none(records["diff"]),
        }
        code = "nonimplementation-structurally-satisfied"
    result = {
        "format": COMPLETION_RESULT_FORMAT,
        "outcome": {"status": "resolved", "code": code},
        "authority": "inactive-derived-policy",
        "issue": triage_record["document"]["issue"],
        "family_run_id": triage_record["document"]["family_run_id"],
        "work_type": work_type,
        "triage_binding": triage_record["reference"],
        "observation_binding": observation_record["reference"],
        "scope": scope,
        "structural_evidence": structural,
        "route_requirements": list(ROUTE_REQUIREMENTS[work_type]),
        "activation": {
            "status": "inactive",
            "operationally_activated": False,
            "owner": "issue-144",
            "unverified": list(COMPLETION_UNVERIFIED),
        },
        "request_sha256": request["request_sha256"],
    }
    return _result_document(result)


class PolicyArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("unsupported", "invalid-cli", "Invalid command line: %s" % message)


def _common_arguments(parser):
    parser.add_argument("--root", default=".")
    parser.add_argument("--request", required=True)
    parser.add_argument("--trusted-issue-snapshot-binding", required=True)
    parser.add_argument("--trusted-baseline-binding", required=True)


def build_parser():
    parser = PolicyArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    classify_parser = commands.add_parser("classify")
    _common_arguments(classify_parser)
    targeted_parser = commands.add_parser("run-targeted")
    _common_arguments(targeted_parser)
    targeted_parser.add_argument("--trusted-triage-binding", required=True)
    completion_parser = commands.add_parser("assess-completion")
    _common_arguments(completion_parser)
    completion_parser.add_argument("--trusted-triage-binding", required=True)
    completion_parser.add_argument("--designated-observation-binding", required=True)
    completion_parser.add_argument("--designated-review-binding")
    completion_parser.add_argument("--designated-acceptance-binding")
    return parser


def _dispatch_classify(args):
    return classify(
        args.root,
        _load_json(args.request),
        args.trusted_issue_snapshot_binding,
        args.trusted_baseline_binding,
    )


def _dispatch_run_targeted(args):
    return run_targeted(
        args.root,
        _load_json(args.request),
        args.trusted_issue_snapshot_binding,
        args.trusted_baseline_binding,
        args.trusted_triage_binding,
    )


def _dispatch_assess_completion(args):
    return assess_completion(
        args.root,
        _load_json(args.request),
        args.trusted_issue_snapshot_binding,
        args.trusted_baseline_binding,
        args.trusted_triage_binding,
        args.designated_observation_binding,
        args.designated_review_binding,
        args.designated_acceptance_binding,
    )


COMMAND_HANDLERS = {
    "classify": _dispatch_classify,
    "run-targeted": _dispatch_run_targeted,
    "assess-completion": _dispatch_assess_completion,
}


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        handler = COMMAND_HANDLERS.get(args.command)
        if handler is None:
            _fail("unsupported", "unsupported-command", "Command is unsupported")
        document = handler(args)
        exit_code = 0
    except WorkTypePolicyFailure as failure:
        document = failure.document()
        exit_code = OUTCOME_EXIT_CODES[failure.status]
    except (evidence.EvidenceFailure, inspector.InspectionFailure) as failure:
        wrapped = WorkTypePolicyFailure(
            failure.status, failure.code, failure.message, failure.subject
        )
        document = wrapped.document()
        exit_code = OUTCOME_EXIT_CODES[wrapped.status]
    try:
        output = inspector.canonical_document(document)
    except (inspector.InspectionFailure, TypeError, ValueError) as failure:
        wrapped = WorkTypePolicyFailure(
            "corrupt",
            "invalid-canonical-output",
            "Policy output is not canonical: %s" % failure,
        )
        output = inspector.canonical_document(wrapped.document())
        exit_code = OUTCOME_EXIT_CODES[wrapped.status]
    sys.stdout.buffer.write(output)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
