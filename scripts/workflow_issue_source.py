#!/usr/bin/env python3
"""Trusted publication of exact pre-genesis GitHub issue source bytes."""

import argparse
import copy
import pathlib
import re
import stat
import sys

if __package__:
    from scripts import workflow_cas
    from scripts import workflow_inspector as inspector
    from scripts import workflow_runtime as runtime
else:  # pragma: no cover - direct script execution
    import workflow_cas
    import workflow_inspector as inspector
    import workflow_runtime as runtime


VERSION = "1.1.0"
PUBLICATION_FORMAT = "chess-echo-trusted-issue-source-publication-v1"
FAILURE_FORMAT = "chess-echo-trusted-issue-source-failure-v1"
MAX_SOURCE_BYTES = 512 * 1024
FROZEN_ISSUES = frozenset({115})
SHA256_RE = re.compile(r"[0-9a-f]{64}")
OID_RE = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")
REPOSITORY_RE = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
RFC3339_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})"
)
OUTCOME_EXIT_CODES = {
    "missing": 20,
    "unsupported": 21,
    "corrupt": 22,
    "ambiguous": 23,
    "stale": 24,
    "denied": 25,
    "conflict": 26,
    "uncertain": 27,
}


class IssueSourceFailure(Exception):
    def __init__(self, status, code, message, subject=None):
        super().__init__(message)
        self.status, self.code, self.message, self.subject = (
            status,
            code,
            message,
            subject,
        )

    def document(self):
        outcome = {"status": self.status, "code": self.code, "message": self.message}
        if self.subject is not None:
            outcome["subject"] = self.subject
        return {"format": FAILURE_FORMAT, "outcome": outcome}


def _fail(status, code, message, subject=None):
    raise IssueSourceFailure(status, code, message, subject)


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
    except (TypeError, ValueError, inspector.InspectionFailure) as error:
        _fail("corrupt", "invalid-canonical-json", str(error))


def _with_digest(value, field):
    result = copy.deepcopy(value)
    result[field] = inspector.sha256(_canonical(result))
    return result


def _issue(issue):
    _require(
        type(issue) is int and issue > 0,
        "corrupt",
        "invalid-issue",
        "Issue must be a positive integer",
    )
    _require(
        issue not in FROZEN_ISSUES,
        "denied",
        "issue-frozen",
        "Issue is frozen before runtime or CAS access",
        str(issue),
    )
    return issue


def _repository(repository):
    _require(
        isinstance(repository, str) and REPOSITORY_RE.fullmatch(repository) is not None,
        "corrupt",
        "invalid-repository",
        "Repository must be an owner/name slug",
    )
    return repository


def _module_identity(module, name, version):
    try:
        path = pathlib.Path(module.__file__).resolve(strict=True)
        before = path.stat()
        data = path.read_bytes()
        after = path.stat()
    except (OSError, RuntimeError, ValueError) as error:
        _fail("missing", "%s-source-unavailable" % name, "%s source is unavailable: %s" % (name, error))
    _require(
        stat.S_ISREG(before.st_mode)
        and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
        "stale",
        "%s-source-moved" % name,
        "%s source changed during identity capture" % name,
    )
    return {"name": name, "version": version, "source_sha256": inspector.sha256(data)}


def _runtime_identity():
    return _module_identity(runtime, "workflow-runtime", runtime.RUNTIME_VERSION)


def _publisher_identity():
    return _module_identity(sys.modules[__name__], "workflow-issue-source", VERSION)


def _bootstrap(value, repository):
    try:
        validated = runtime.validate_bootstrap_document(value)
    except runtime.RuntimeFailure as error:
        _fail(error.status, error.code, error.message, error.subject)
    _require(
        validated["repository"] == repository,
        "stale",
        "runtime-bootstrap-identity",
        "Runtime bootstrap identity differs from the intake request",
    )
    return validated


def _observation(repository, issue, snapshot, raw):
    _require(
        type(raw) is bytes and 0 < len(raw) <= MAX_SOURCE_BYTES,
        "corrupt",
        "invalid-issue-source-bytes",
        "Issue source bytes are empty, unbounded, or not bytes",
    )
    try:
        value = inspector.parse_json_object(raw, "GitHub issue response")
    except inspector.InspectionFailure as error:
        _fail(error.status, error.code, error.message, error.subject)
    api_url = "https://api.github.com/repos/%s/issues/%d" % (repository, issue)
    html_url = "https://github.com/%s/issues/%d" % (repository, issue)
    _require(
        type(value.get("number")) is int
        and value.get("number") == issue
        and value.get("url") == api_url
        and value.get("html_url") == html_url,
        "stale",
        "issue-identity-mismatch",
        "GitHub API and HTML issue identity differ from the intake request",
    )
    _require(
        "pull_request" not in value,
        "denied",
        "issue-is-pull-request",
        "Pre-genesis intake accepts issues, not pull requests",
    )
    _require(
        value.get("state") == "open",
        "denied",
        "issue-not-open",
        "Pre-genesis intake requires an open issue",
    )
    title, body, raw_labels = value.get("title"), value.get("body"), value.get("labels")
    _require(
        isinstance(title, str)
        and 0 < len(title.encode("utf-8")) <= 1024
        and (body is None or isinstance(body, str))
        and len((body or "").encode("utf-8")) <= 1024 * 1024
        and isinstance(raw_labels, list),
        "corrupt",
        "invalid-issue-response",
        "GitHub issue response fields are malformed",
    )
    labels = []
    for item in raw_labels:
        _require(
            isinstance(item, dict)
            and isinstance(item.get("name"), str)
            and item["name"]
            and len(item["name"].encode("utf-8")) <= 1024,
            "corrupt",
            "invalid-issue-label",
            "GitHub issue labels are malformed",
        )
        labels.append(item["name"])
    _require(
        len(labels) == len(set(labels)),
        "ambiguous",
        "duplicate-issue-label",
        "GitHub issue response contains duplicate labels",
    )
    _require(isinstance(snapshot, dict), "corrupt", "invalid-issue-snapshot", "Runtime issue snapshot is not an object")
    captured_at = snapshot.get("captured_at")
    _require(
        isinstance(captured_at, str) and RFC3339_RE.fullmatch(captured_at) is not None,
        "corrupt",
        "invalid-issue-capture-time",
        "Runtime issue snapshot capture time is invalid",
    )
    source = {
        "kind": "issue-snapshot",
        "sha256": inspector.sha256(raw),
        "size": len(raw),
    }
    expected = _with_digest(
        {
            "format": runtime.ISSUE_SNAPSHOT_FORMAT,
            "repository": repository,
            "issue": issue,
            "title": title,
            "url": html_url,
            "body": body or "",
            "labels": sorted(labels),
            "source": source,
            "captured_at": captured_at,
        },
        "snapshot_sha256",
    )
    _require(
        snapshot == expected,
        "corrupt",
        "issue-snapshot-relationship",
        "Runtime snapshot does not canonically describe the exact raw issue bytes",
    )
    return source


def _validate_publication(publication, repository, issue):
    _exact(
        publication,
        {
            "format",
            "outcome",
            "repository",
            "issue",
            "source",
            "runtime",
            "bootstrap",
            "publisher",
            "publication_sha256",
        },
        "issue-source-publication",
    )
    _require(
        publication["format"] == PUBLICATION_FORMAT
        and publication["outcome"] == {"status": "resolved", "code": "published"}
        and publication["repository"] == repository
        and publication["issue"] == issue,
        "stale",
        "issue-source-publication-identity",
        "Trusted issue source publication identity differs from initialization",
    )
    unsigned = copy.deepcopy(publication)
    digest = unsigned.pop("publication_sha256")
    _require(
        isinstance(digest, str)
        and SHA256_RE.fullmatch(digest) is not None
        and inspector.sha256(_canonical(unsigned)) == digest,
        "corrupt",
        "issue-source-publication-digest",
        "Trusted issue source publication digest is invalid",
    )
    return publication


def validate_for_initialization(publication, bootstrap, snapshot, raw, repository, issue):
    issue, repository = _issue(issue), _repository(repository)
    publication = _validate_publication(publication, repository, issue)
    current_bootstrap = _bootstrap(bootstrap, repository)
    _require(
        publication["bootstrap"] == current_bootstrap,
        "stale",
        "issue-source-bootstrap-changed",
        "Runtime bootstrap, config, or executable identity changed after intake",
    )
    _require(
        publication["runtime"] == _runtime_identity()
        and publication["publisher"] == _publisher_identity(),
        "stale",
        "issue-source-tool-changed",
        "Trusted intake or runtime source identity changed after intake",
    )
    current_source = _observation(repository, issue, snapshot, raw)
    _require(
        publication["source"] == current_source,
        "stale",
        "issue-source-edited",
        "Issue bytes changed after trusted intake; publish a fresh source explicitly",
    )
    return copy.deepcopy(current_source)


def _cas_fail(status, code, message):
    mapped = "conflict" if code in {"immutable-object-collision", "immutable-destination-changed"} else "corrupt"
    _fail(mapped, code, message)


def publish(root, repository, issue, git_executable, gh_executable, github_token):
    issue, repository = _issue(issue), _repository(repository)
    identities_before = (_runtime_identity(), _publisher_identity())
    try:
        adapter = runtime.bootstrap(root, repository, git_executable, gh_executable, github_token)
        bootstrap = _bootstrap(adapter.bootstrap_document(), repository)
        snapshot, raw = adapter.observe_issue(issue)
    except runtime.RuntimeFailure as error:
        _fail(error.status, error.code, error.message, error.subject)
    source = _observation(repository, issue, snapshot, raw)
    identities_after = (_runtime_identity(), _publisher_identity())
    _require(
        identities_before == identities_after,
        "stale",
        "issue-source-tool-moved",
        "Trusted intake or runtime source changed during observation",
    )
    publication = _with_digest(
        {
            "format": PUBLICATION_FORMAT,
            "outcome": {"status": "resolved", "code": "published"},
            "repository": repository,
            "issue": issue,
            "source": source,
            "runtime": identities_after[0],
            "bootstrap": bootstrap,
            "publisher": identities_after[1],
        },
        "publication_sha256",
    )
    try:
        store = inspector.resolve_store(root)
        workflow_cas.publish_immutable(
            inspector.object_path(store, source["sha256"]),
            raw,
            _cas_fail,
            temporary_label="issue-source",
        )
        published = inspector.AuthorityReader(store, issue).read_bytes(source, "issue-snapshot")
    except inspector.InspectionFailure as error:
        _fail(error.status, error.code, error.message, error.subject)
    _require(
        published == raw,
        "corrupt",
        "published-issue-source-mismatch",
        "Published issue source differs from the observed bytes",
    )
    return publication


class IssueSourceArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        _fail("corrupt", "invalid-arguments", message)


def build_parser():
    parser = IssueSourceArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("publish")
    command.add_argument("issue", type=int)
    command.add_argument("--root", required=True)
    command.add_argument("--repository", required=True)
    command.add_argument("--git-executable", required=True)
    command.add_argument("--gh-executable", required=True)
    return parser


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
        token = sys.stdin.readline().rstrip("\n")
        document = publish(
            args.root,
            args.repository,
            args.issue,
            args.git_executable,
            args.gh_executable,
            token,
        )
        sys.stdout.buffer.write(inspector.canonical_document(document))
        return 0
    except IssueSourceFailure as failure:
        sys.stdout.buffer.write(inspector.canonical_document(failure.document()))
        return OUTCOME_EXIT_CODES[failure.status]


if __name__ == "__main__":
    raise SystemExit(main())
