#!/usr/bin/env python3
"""Generate reproducible, frozen tactical motif fixture pools from Lichess puzzles.

Enforces leakage controls:
- Same-GameUrl exclusion
- Canonical-FEN deduplication
- Self-exclusion of the query puzzle

Assigns oracle labels (fork_N and back_rank_mate) via independent detectors.
Serializes frozen candidate pools to JSON and records SHA-256 digest.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Set

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import chess
from scripts.tactical_motif_detectors import evaluate_fork_n, evaluate_back_rank_mate

DEFAULT_SOURCE = ROOT / "scripts/data/lichess_puzzle_sample.csv.zst"
DEFAULT_OUTPUT = ROOT / "src/test/resources/tactical-motif-frozen-pools.json"
DEFAULT_DIGEST = ROOT / "docs/experiments/tactical-motif-frozen-pools.sha256"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_fen(fen: str) -> str:
    return " ".join(fen.split()[:4])


def extract_puzzles(source_path: Path) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Extract valid puzzles from zst-compressed Lichess CSV."""
    cmd = ["zstd", "-dc", str(source_path)]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    if proc.stdout is None:
        raise RuntimeError(f"Failed to open pipe for {source_path}")

    reader = csv.DictReader(proc.stdout)
    fork_puzzles: List[Dict[str, Any]] = []
    brm_puzzles: List[Dict[str, Any]] = []
    distractors: List[Dict[str, Any]] = []
    seen_fens: Set[str] = set()

    for row in reader:
        pid = row.get("PuzzleId", "")
        fen = row.get("FEN", "")
        moves_str = row.get("Moves", "")
        game_url = row.get("GameUrl", "")

        moves = moves_str.split()
        if len(moves) < 2:
            continue

        try:
            b = chess.Board(fen)
            if not b.is_valid():
                continue
            setup_move = chess.Move.from_uci(moves[0])
            if setup_move not in b.legal_moves:
                continue
            b.push(setup_move)
            root_fen = b.fen()
            c_fen = canonical_fen(root_fen)
        except Exception:
            continue

        # Canonical-FEN deduplication across the whole extracted pool
        if c_fen in seen_fens:
            continue
        seen_fens.add(c_fen)

        item = {
            "puzzleId": pid,
            "fen": fen,
            "moves": moves,
            "rootFen": root_fen,
            "canonicalFen": c_fen,
            "gameUrl": game_url,
        }

        if evaluate_fork_n(fen, moves):
            item["label"] = "fork_N"
            fork_puzzles.append(item)
        elif evaluate_back_rank_mate(fen, moves):
            item["label"] = "back_rank_mate"
            brm_puzzles.append(item)
        else:
            item["label"] = None
            distractors.append(item)

    proc.stdout.close()
    proc.wait()
    return fork_puzzles, brm_puzzles, distractors


def build_queries(
    motif: str,
    query_sources: List[Dict[str, Any]],
    candidate_sources: List[Dict[str, Any]],
    distractor_pool: List[Dict[str, Any]],
    num_queries: int = 16,
    num_positives_per_query: int = 20,
    num_distractors_per_query: int = 180,
) -> List[Dict[str, Any]]:
    queries = []
    for q_item in query_sources[:num_queries]:
        q_url = q_item["gameUrl"]
        q_fen = q_item["canonicalFen"]
        q_id = q_item["puzzleId"]

        # Filter positives with leakage controls
        positives: List[Dict[str, Any]] = []
        pos_fens: Set[str] = set()
        for cand in candidate_sources:
            if cand["puzzleId"] == q_id:
                continue
            if cand["gameUrl"] and cand["gameUrl"] == q_url:
                continue
            if cand["canonicalFen"] == q_fen:
                continue
            if cand["canonicalFen"] in pos_fens:
                continue
            pos_fens.add(cand["canonicalFen"])
            positives.append(cand)
            if len(positives) == num_positives_per_query:
                break

        if len(positives) < num_positives_per_query:
            raise RuntimeError(f"Could not find {num_positives_per_query} positives for query {q_id}")

        # Filter distractors with leakage controls
        distractors: List[Dict[str, Any]] = []
        dist_fens: Set[str] = set()
        for d in distractor_pool:
            if d["gameUrl"] and d["gameUrl"] == q_url:
                continue
            if d["canonicalFen"] == q_fen:
                continue
            if d["canonicalFen"] in pos_fens:
                continue
            if d["canonicalFen"] in dist_fens:
                continue
            dist_fens.add(d["canonicalFen"])
            distractors.append(d)
            if len(distractors) == num_distractors_per_query:
                break

        if len(distractors) < num_distractors_per_query:
            raise RuntimeError(f"Could not find {num_distractors_per_query} distractors for query {q_id}")

        all_candidates = positives + distractors
        # Verify strict leakage controls and sizing
        assert len(all_candidates) == num_positives_per_query + num_distractors_per_query
        assert len({c["canonicalFen"] for c in all_candidates}) == len(all_candidates)
        assert q_fen not in {c["canonicalFen"] for c in all_candidates}
        assert not any(c["gameUrl"] and c["gameUrl"] == q_url for c in all_candidates)

        query_record = {
            "queryId": f"query_{motif}_{len(queries) + 1:02d}",
            "puzzleId": q_item["puzzleId"],
            "fen": q_item["fen"],
            "moves": q_item["moves"],
            "rootFen": q_item["rootFen"],
            "canonicalFen": q_item["canonicalFen"],
            "gameUrl": q_item["gameUrl"],
            "motif": motif,
            "targetPositives": [p["puzzleId"] for p in positives],
            "candidates": all_candidates,
        }
        queries.append(query_record)

    return queries


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate frozen tactical motif fixture pools.")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="Path to lichess zst csv")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Path to output json")
    parser.add_argument("--digest-output", type=Path, default=DEFAULT_DIGEST, help="Path to output sha256")
    args = parser.parse_args()

    if not args.source.exists():
        print(f"ERROR: Source file {args.source} not found.", file=sys.stderr)
        return 1

    print(f"Extracting and labeling puzzles from {args.source}...")
    fork_puzzles, brm_puzzles, distractors = extract_puzzles(args.source)
    print(f"Found {len(fork_puzzles)} fork_N, {len(brm_puzzles)} back_rank_mate, {len(distractors)} distractors.")

    if len(fork_puzzles) < 36 or len(brm_puzzles) < 36 or len(distractors) < 200:
        print("ERROR: Insufficient puzzles for fixture generation.", file=sys.stderr)
        return 1

    # Reserve first 16 of each for queries, remaining for positive candidates
    fork_queries = build_queries("fork_N", fork_puzzles[:16], fork_puzzles[16:], distractors, num_queries=16)
    brm_queries = build_queries("back_rank_mate", brm_puzzles[:16], brm_puzzles[16:], distractors, num_queries=16)

    all_queries = fork_queries + brm_queries
    print(f"Built {len(all_queries)} queries total ({len(fork_queries)} fork_N, {len(brm_queries)} back_rank_mate).")

    dataset = {
        "description": "Frozen candidate pools for Stage 1 & Stage 2 tactical motif evaluation (#607)",
        "sourceDataset": "Lichess Open Puzzles (CC0)",
        "totalQueries": len(all_queries),
        "candidatesPerQuery": 200,
        "queries": all_queries,
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(dataset, f, indent=2, sort_keys=True)

    digest = sha256_file(args.output)
    args.digest_output.parent.mkdir(parents=True, exist_ok=True)
    args.digest_output.write_text(f"{digest}\n", encoding="utf-8")

    print(f"Wrote frozen pools to {args.output}")
    print(f"Recorded SHA-256 digest to {args.digest_output}: {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
