#!/usr/bin/python3
"""Deterministic agent used only by the orchestration integration tests."""

import json
import pathlib
import subprocess
import sys
import time


ROOT = pathlib.Path.cwd()
BIN = pathlib.Path(__file__).resolve().parent
STATE = BIN / "agent-state.json"


def _state():
    try:
        return json.loads(STATE.read_text())
    except FileNotFoundError:
        return {}


def _save(value):
    STATE.write_text(json.dumps(value, sort_keys=True))


def _git(*arguments):
    subprocess.run(
        ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", *arguments],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _plan(revision):
    text = "Implement feature safely.\nAdd tests.\n" if revision else "Implement feature.\nAdd tests.\n"
    value = {
        "format": "chess-echo-orchestrator-agent-candidate-v1",
        "kind": "plan",
        "plan": text,
        "units": [
            {
                "id": "change",
                "title": "Implement feature",
                "start_line": 1,
                "end_line": 1,
                "review_class": "ordinary",
                "dependencies": [],
            },
            {
                "id": "tests",
                "title": "Add tests",
                "start_line": 2,
                "end_line": 2,
                "review_class": "ordinary",
                "dependencies": ["change"],
            },
        ],
        "revision": None,
    }
    if revision:
        value["revision"] = {
            "diff": (
                "--- a/plan.md\n+++ b/plan.md\n"
                "@@ -1,2 +1,2 @@\n-Implement feature.\n"
                "+Implement feature safely.\n Add tests.\n"
            ),
            "changes": [
                {
                    "unit_id": "change",
                    "impact": "local",
                    "reason": "Address the review finding.",
                }
            ],
        }
    return value


def _review(needs_revision):
    value = {
        "format": "chess-echo-orchestrator-agent-candidate-v1",
        "kind": "review",
        "verdict": "needs-revision" if needs_revision else "accepted",
        "findings": [],
    }
    if needs_revision:
        value["findings"] = [
            {
                "unit_ids": ["change"],
                "category": "implementation",
                "detail": "Make the implementation step explicit.",
            }
        ]
    value["pr"] = {
        "head_ref": "issue-144",
        "title": "Implement workflow feature",
        "body": "## What\nAdd the workflow feature.\n\n## Why\nIssue #144.\n\n## Testing\nfixture-check\n",
    }
    return value


def _implement():
    test_path = ROOT / "scripts" / "tests" / "generated_test.py"
    if not test_path.exists():
        test_path.parent.mkdir(parents=True, exist_ok=True)
        test_path.write_text("def test_generated():\n    assert True\n")
        _git("add", "scripts/tests/generated_test.py")
        _git("commit", "-m", "test: add generated coverage")
        report = "Added focused test coverage."
    else:
        (ROOT / "scripts" / "implementation.py").write_text("VALUE = 'implemented'\n")
        _git("add", "scripts/implementation.py")
        _git("commit", "--amend", "--no-edit")
        report = "Implemented the approved change."
    return {
        "format": "chess-echo-orchestrator-agent-candidate-v1",
        "kind": "implementer",
        "report": report,
    }


def main(argv):
    if len(argv) == 2 and argv[0] == "--request-binding":
        sys.stdout.write(argv[1])
        return 0
    if len(argv) != 3 or argv[1] != "--request-binding":
        return 2
    role = argv[0]
    state = _state()
    state[role] = state.get(role, 0) + 1
    _save(state)
    mode = (BIN / "agent-mode").read_text().strip() if (BIN / "agent-mode").exists() else ""
    if mode == "malformed":
        sys.stdout.write("{}")
        return 0
    if mode == "sleep":
        (BIN / "agent-started").write_text(role)
        time.sleep(4)
    if mode == "read-drift" and role in {"planner", "reviewer"}:
        (ROOT / "scripts" / "read-drift.py").write_text("DIRTY = True\n")
    if role == "planner":
        value = _plan(mode == "revision" and state[role] > 1)
    elif role == "reviewer":
        value = _review((mode == "revision" and state[role] == 1) or mode == "reject-review")
        if mode == "empty-pr":
            value["pr"]["body"] = "## What\n\n## Why\nReason.\n\n## Testing\nChecked.\n"
    elif role == "implementer":
        value = _implement()
        if mode == "test-drift" and state[role] == 1:
            (ROOT / "scripts" / "not-a-test.py").write_text("DIRTY = True\n")
        if mode == "rewrite-tests" and state[role] > 1:
            (ROOT / "scripts" / "tests" / "generated_test.py").write_text(
                "def test_generated():\n    assert False\n"
            )
            _git("add", "scripts/tests/generated_test.py")
            _git("commit", "--amend", "--no-edit")
    else:
        return 3
    sys.stdout.write(json.dumps(value, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
