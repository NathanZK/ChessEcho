#!/usr/bin/env python3
"""Closed fake ``gh`` implementation for workflow-orchestrator tests."""

import json
import pathlib
import subprocess
import sys


HERE = pathlib.Path(__file__).resolve().parent
RESPONSES = HERE / "gh-responses.json"
CALLS = HERE / "gh-calls.jsonl"


def _load():
    return json.loads(RESPONSES.read_text())


def _save(value):
    RESPONSES.write_text(json.dumps(value, sort_keys=True))


def _record(argv):
    with CALLS.open("a") as stream:
        stream.write(json.dumps(argv) + "\n")


def _option(argv, name):
    return argv[argv.index(name) + 1] if name in argv else None


def _head(ref):
    return subprocess.check_output(["git", "rev-parse", ref], text=True).strip()


def _create(argv):
    data = _load()
    repository = _option(argv, "--repo")
    base_ref = _option(argv, "--base")
    head_ref = _option(argv, "--head")
    title = _option(argv, "--title")
    body = _option(argv, "--body")
    number = 1
    record = {
        "number": number,
        "html_url": "https://github.com/%s/pull/%d" % (repository, number),
        "state": "open",
        "draft": True,
        "base": {"ref": base_ref, "sha": _head("refs/remotes/origin/%s" % base_ref)},
        "head": {"ref": head_ref, "sha": _head("HEAD")},
        "title": title,
        "body": body,
    }
    data.setdefault("api", {})["repos/%s/pulls/%d" % (repository, number)] = record
    data["created_pr"] = record
    _save(data)
    if data.get("create_mode") == "uncertain-after-write":
        sys.stderr.write("connection lost after write\n")
        return 1
    sys.stdout.write(record["html_url"] + "\n")
    return 0


def _api(argv):
    rest = argv[1:]
    if rest[:1] == ["graphql"]:
        sys.stdout.write(json.dumps(_load().get("graphql", {}), separators=(",", ":")))
        return 0
    endpoint = next((item for item in rest if item.startswith("repos/")), None)
    data = _load()
    if endpoint and "/git/matching-refs/heads/" in endpoint:
        head_ref = endpoint.split("/git/matching-refs/heads/", 1)[1]
        value = [{"ref": "refs/heads/%s" % head_ref,
                  "object": {"type": "commit", "sha": _head("HEAD")}}]
        sys.stdout.write(json.dumps(value, separators=(",", ":")))
        return 0
    if endpoint and endpoint.startswith("repos/") and "/pulls?" in endpoint:
        value = data.get("created_pr")
        sys.stdout.write(json.dumps([[] if value is None else [value]], separators=(",", ":")))
        return 0
    value = data.get("api", {}).get(endpoint)
    if value is None:
        sys.stderr.write("no canned response for %r\n" % endpoint)
        return 4
    sys.stdout.write(json.dumps(value, separators=(",", ":")))
    return 0


def main(argv):
    _record(argv)
    if argv[:2] == ["pr", "create"]:
        return _create(argv)
    if argv[:1] == ["api"]:
        return _api(argv)
    sys.stderr.write("unsupported fake gh command\n")
    return 3


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
