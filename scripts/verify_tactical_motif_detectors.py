#!/usr/bin/env python3
"""Run and independently verify tactical motif detector test cases."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.tactical_motif_detectors import evaluate_fork_n, evaluate_back_rank_mate

TEST_CASES_PATH = ROOT / "src/test/resources/tactical-motif-detector-test-cases.json"


def main() -> int:
    if not TEST_CASES_PATH.exists():
        print(f"ERROR: Test cases file not found at {TEST_CASES_PATH}", file=sys.stderr)
        return 1

    with open(TEST_CASES_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Verify fork_N positives
    fork_pos = data.get("fork_N", {}).get("positives", [])
    if len(fork_pos) < 5:
        print(f"ERROR: fork_N has fewer than 5 positives ({len(fork_pos)})", file=sys.stderr)
        return 1
    for tc in fork_pos:
        result = evaluate_fork_n(tc["fen"], tc["moves"])
        if not result:
            print(f"FAIL: fork_N positive failed: {tc['id']}", file=sys.stderr)
            return 1

    # Verify fork_N negatives
    fork_neg = data.get("fork_N", {}).get("negatives", [])
    if len(fork_neg) < 5:
        print(f"ERROR: fork_N has fewer than 5 negatives ({len(fork_neg)})", file=sys.stderr)
        return 1
    for tc in fork_neg:
        result = evaluate_fork_n(tc["fen"], tc["moves"])
        if result:
            print(f"FAIL: fork_N negative produced false positive: {tc['id']}", file=sys.stderr)
            return 1

    # Verify back_rank_mate positives
    brm_pos = data.get("back_rank_mate", {}).get("positives", [])
    if len(brm_pos) < 5:
        print(f"ERROR: back_rank_mate has fewer than 5 positives ({len(brm_pos)})", file=sys.stderr)
        return 1
    for tc in brm_pos:
        result = evaluate_back_rank_mate(tc["fen"], tc["moves"])
        if not result:
            print(f"FAIL: back_rank_mate positive failed: {tc['id']}", file=sys.stderr)
            return 1

    # Verify back_rank_mate negatives
    brm_neg = data.get("back_rank_mate", {}).get("negatives", [])
    if len(brm_neg) < 5:
        print(f"ERROR: back_rank_mate has fewer than 5 negatives ({len(brm_neg)})", file=sys.stderr)
        return 1
    for tc in brm_neg:
        result = evaluate_back_rank_mate(tc["fen"], tc["moves"])
        if result:
            print(f"FAIL: back_rank_mate negative produced false positive: {tc['id']}", file=sys.stderr)
            return 1

    print(f"SUCCESS: Verified {len(fork_pos)} fork_N positives, {len(fork_neg)} fork_N negatives, "
          f"{len(brm_pos)} back_rank_mate positives, {len(brm_neg)} back_rank_mate negatives.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
