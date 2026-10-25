#!/usr/bin/env python3
"""Run and independently verify tactical motif detectors and frozen candidate pool integrity."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import chess
from scripts.tactical_motif_detectors import evaluate_fork_n, evaluate_back_rank_mate

TEST_CASES_PATH = ROOT / "src/test/resources/tactical-motif-detector-test-cases.json"
FROZEN_POOLS_PATH = ROOT / "src/test/resources/tactical-motif-frozen-pools.json"
DIGEST_PATH = ROOT / "docs/experiments/tactical-motif-frozen-pools.sha256"
INDEP_POOLS_PATH = ROOT / "src/test/resources/tactical-motif-independent-pools.json"
INDEP_DIGEST_PATH = ROOT / "docs/experiments/tactical-motif-independent-pools.sha256"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_detectors() -> None:
    if not TEST_CASES_PATH.exists():
        raise AssertionError(f"Test cases file not found at {TEST_CASES_PATH}")

    with open(TEST_CASES_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Verify fork_N positives
    fork_pos = data.get("fork_N", {}).get("positives", [])
    if len(fork_pos) < 5:
        raise AssertionError(f"fork_N has fewer than 5 positives ({len(fork_pos)})")
    for tc in fork_pos:
        result = evaluate_fork_n(tc["fen"], tc["moves"])
        if not result:
            raise AssertionError(f"fork_N positive failed: {tc['id']}")

    # Verify fork_N negatives
    fork_neg = data.get("fork_N", {}).get("negatives", [])
    if len(fork_neg) < 5:
        raise AssertionError(f"fork_N has fewer than 5 negatives ({len(fork_neg)})")
    for tc in fork_neg:
        result = evaluate_fork_n(tc["fen"], tc["moves"])
        if result:
            raise AssertionError(f"fork_N negative produced false positive: {tc['id']}")

    # Verify back_rank_mate positives
    brm_pos = data.get("back_rank_mate", {}).get("positives", [])
    if len(brm_pos) < 5:
        raise AssertionError(f"back_rank_mate has fewer than 5 positives ({len(brm_pos)})")
    for tc in brm_pos:
        result = evaluate_back_rank_mate(tc["fen"], tc["moves"])
        if not result:
            raise AssertionError(f"back_rank_mate positive failed: {tc['id']}")

    # Verify back_rank_mate negatives
    brm_neg = data.get("back_rank_mate", {}).get("negatives", [])
    if len(brm_neg) < 5:
        raise AssertionError(f"back_rank_mate has fewer than 5 negatives ({len(brm_neg)})")
    for tc in brm_neg:
        result = evaluate_back_rank_mate(tc["fen"], tc["moves"])
        if result:
            raise AssertionError(f"back_rank_mate negative produced false positive: {tc['id']}")

    print(f"SUCCESS: Verified {len(fork_pos)} fork_N positives, {len(fork_neg)} fork_N negatives, "
          f"{len(brm_pos)} back_rank_mate positives, {len(brm_neg)} back_rank_mate negatives.")


def verify_frozen_pools() -> None:
    if not FROZEN_POOLS_PATH.exists():
        raise AssertionError(f"Frozen pools file not found at {FROZEN_POOLS_PATH}")
    if not DIGEST_PATH.exists():
        raise AssertionError(f"Digest file not found at {DIGEST_PATH}")

    expected_digest = DIGEST_PATH.read_text(encoding="utf-8").strip()
    actual_digest = sha256_file(FROZEN_POOLS_PATH)
    if actual_digest != expected_digest:
        raise AssertionError(f"Corpus SHA-256 mismatch: expected {expected_digest}, got {actual_digest}")

    with open(FROZEN_POOLS_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    queries = data.get("queries", [])
    if len(queries) < 30:
        raise AssertionError(f"Expected >= 30 queries, found {len(queries)}")

    fork_queries = [q for q in queries if q["motif"] == "fork_N"]
    brm_queries = [q for q in queries if q["motif"] == "back_rank_mate"]

    if len(fork_queries) < 15 or len(brm_queries) < 15:
        raise AssertionError(f"Unbalanced queries: {len(fork_queries)} fork_N, {len(brm_queries)} back_rank_mate")

    for q in queries:
        qid = q["queryId"]
        q_fen = q["canonicalFen"]
        q_url = q.get("gameUrl", "")
        motif = q["motif"]
        candidates = q.get("candidates", [])
        target_positives = set(q.get("targetPositives", []))

        if len(candidates) < 200:
            raise AssertionError(f"Query {qid} has fewer than 200 candidates ({len(candidates)})")
        if len(target_positives) < 1:
            raise AssertionError(f"Query {qid} has zero target positives")

        cand_fens = set()
        positive_count = 0

        for c in candidates:
            c_fen = c["canonicalFen"]
            c_url = c.get("gameUrl", "")
            c_pid = c["puzzleId"]
            c_label = c.get("label")

            # Leakage check: Same GameUrl
            if q_url and c_url and q_url == c_url:
                raise AssertionError(f"Leakage detected in {qid}: candidate {c_pid} shares gameUrl {q_url}")

            # Leakage check: Canonical FEN match with query
            if c_fen == q_fen:
                raise AssertionError(f"Leakage detected in {qid}: candidate {c_pid} matches query canonical FEN {q_fen}")

            # Deduplication check: unique canonical FEN in candidate pool
            if c_fen in cand_fens:
                raise AssertionError(f"Duplicate canonical FEN in {qid}: {c_fen}")
            cand_fens.add(c_fen)

            if c_pid in target_positives:
                if c_label != motif:
                    raise AssertionError(f"Target positive {c_pid} has incorrect label {c_label} (expected {motif})")
                positive_count += 1

        if positive_count != len(target_positives):
            raise AssertionError(f"Query {qid} positive count mismatch: expected {len(target_positives)}, found {positive_count}")

    print(f"SUCCESS: Verified frozen pools: {len(queries)} queries ({len(fork_queries)} fork_N, {len(brm_queries)} back_rank_mate), "
          f"all with >=200 unique candidates, zero leakage, and matching SHA-256 digest ({actual_digest[:16]}...).")


def verify_independent_pools() -> None:
    if not INDEP_POOLS_PATH.exists():
        raise AssertionError(f"Independent pools file not found at {INDEP_POOLS_PATH}")
    if not INDEP_DIGEST_PATH.exists():
        raise AssertionError(f"Independent digest file not found at {INDEP_DIGEST_PATH}")

    expected_digest = INDEP_DIGEST_PATH.read_text(encoding="utf-8").strip()
    actual_digest = sha256_file(INDEP_POOLS_PATH)
    if actual_digest != expected_digest:
        raise AssertionError(f"Independent corpus SHA-256 mismatch: expected {expected_digest}, got {actual_digest}")

    with open(INDEP_POOLS_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    queries = data.get("queries", [])
    if len(queries) < 30:
        raise AssertionError(f"Expected >= 30 independent queries, found {len(queries)}")

    fork_queries = [q for q in queries if q["motif"] == "fork_N"]
    brm_queries = [q for q in queries if q["motif"] == "back_rank_mate"]

    if len(fork_queries) < 15 or len(brm_queries) < 15:
        raise AssertionError(f"Unbalanced independent queries: {len(fork_queries)} fork_N, {len(brm_queries)} back_rank_mate")

    indep_pids = set()
    indep_fens = set()
    indep_games = set()

    for q in queries:
        qid = q["queryId"]
        q_fen = q["canonicalFen"]
        q_url = q.get("gameUrl", "")
        motif = q["motif"]
        candidates = q.get("candidates", [])
        target_positives = set(q.get("targetPositives", []))

        indep_pids.add(q["puzzleId"])
        indep_fens.add(q_fen)
        if q_url:
            indep_games.add(q_url)

        if len(candidates) < 200:
            raise AssertionError(f"Independent query {qid} has fewer than 200 candidates ({len(candidates)})")
        if len(target_positives) < 1:
            raise AssertionError(f"Independent query {qid} has zero target positives")

        cand_fens = set()
        positive_count = 0

        for c in candidates:
            c_fen = c["canonicalFen"]
            c_url = c.get("gameUrl", "")
            c_pid = c["puzzleId"]
            c_label = c.get("label")

            indep_pids.add(c_pid)
            indep_fens.add(c_fen)
            if c_url:
                indep_games.add(c_url)

            if q_url and c_url and q_url == c_url:
                raise AssertionError(f"Leakage detected in {qid}: candidate {c_pid} shares gameUrl {q_url}")
            if c_fen == q_fen:
                raise AssertionError(f"Leakage detected in {qid}: candidate {c_pid} matches query canonical FEN {q_fen}")
            if c_fen in cand_fens:
                raise AssertionError(f"Duplicate canonical FEN in {qid}: {c_fen}")
            cand_fens.add(c_fen)

            if c_pid in target_positives:
                if c_label != motif:
                    raise AssertionError(f"Target positive {c_pid} has incorrect label {c_label} (expected {motif})")
                positive_count += 1

        if positive_count != len(target_positives):
            raise AssertionError(f"Independent query {qid} positive count mismatch: expected {len(target_positives)}, found {positive_count}")

    # Cross-pool disjointness assertion against original frozen pools
    with open(FROZEN_POOLS_PATH, "r", encoding="utf-8") as f:
        orig_data = json.load(f)

    orig_pids = set()
    orig_fens = set()
    orig_games = set()
    for q in orig_data["queries"]:
        orig_pids.add(q["puzzleId"])
        orig_fens.add(q["canonicalFen"])
        if q.get("gameUrl"):
            orig_games.add(q["gameUrl"])
        for c in q["candidates"]:
            orig_pids.add(c["puzzleId"])
            orig_fens.add(c["canonicalFen"])
            if c.get("gameUrl"):
                orig_games.add(c["gameUrl"])

    pid_leak = indep_pids.intersection(orig_pids)
    if pid_leak:
        raise AssertionError(f"Cross-pool puzzle ID leakage: {pid_leak}")
    fen_leak = indep_fens.intersection(orig_fens)
    if fen_leak:
        raise AssertionError(f"Cross-pool canonical FEN leakage: {fen_leak}")
    game_leak = indep_games.intersection(orig_games)
    if game_leak:
        raise AssertionError(f"Cross-pool game URL leakage: {game_leak}")

    print(f"SUCCESS: Verified independent pools: {len(queries)} queries ({len(fork_queries)} fork_N, {len(brm_queries)} back_rank_mate), "
          f"all with >=200 unique candidates, zero internal leakage, zero cross-pool overlap ({len(indep_pids)} disjoint puzzles), "
          f"and matching SHA-256 digest ({actual_digest[:16]}...).")


def main() -> int:
    try:
        verify_detectors()
        verify_frozen_pools()
        verify_independent_pools()
        return 0
    except Exception as e:
        print(f"VERIFICATION FAILED: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
