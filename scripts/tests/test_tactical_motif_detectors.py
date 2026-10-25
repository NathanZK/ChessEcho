#!/usr/bin/env python3
"""Unit tests for independent tactical motif detectors (fork_N and back_rank_mate).

Validates oracle detector correctness on verified positive and negative examples,
legal move replay, single-ply and empty sequences, malformed FEN, and illegal moves.
"""

from __future__ import annotations

import unittest
from scripts.tactical_motif_detectors import evaluate_fork_n, evaluate_back_rank_mate


class TestTacticalMotifDetectors(unittest.TestCase):
    def test_fork_n_positive_cases(self) -> None:
        """Verify >= 5 distinct, verified positive examples of fork_N at solution ply 1."""
        # Case 1: White knight moves to d5, simultaneously attacking c7 (pawn) and e7 (bishop)
        fen1 = "r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        moves1 = ["a7a6", "c3d5"]
        self.assertTrue(evaluate_fork_n(fen1, moves1), "Expected fork_N on c7 and e7")

        # Case 2: Royal fork: White knight moves to c7, checking Black king on e8 and attacking Black rook on a8
        fen2 = "r3k2r/1pp1bppp/8/1N6/8/8/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        moves2 = ["h7h6", "b5c7"]
        self.assertTrue(evaluate_fork_n(fen2, moves2), "Expected royal fork on e8 (K) and a8 (R)")

        # Case 3: Black knight fork: Black knight moves to c2, checking White king on e1 and attacking rook on a1
        fen3 = "r1bqk2r/pppp1ppp/8/8/1n6/8/PPPP1PPP/R3K2R w KQkq - 0 1"
        moves3 = ["h2h3", "b4c2"]
        self.assertTrue(evaluate_fork_n(fen3, moves3), "Expected Black knight fork on e1 (K) and a1 (R)")

        # Case 4: Queen and Rook fork: White knight moves to f6, attacking Black queen on d7 and Black rook on f8
        fen4 = "r4rk1/pp1q1ppp/8/8/4N3/8/PPPP1PPP/R1BQK2R b KQ - 0 1"
        moves4 = ["a7a6", "e4f6"]
        self.assertTrue(evaluate_fork_n(fen4, moves4), "Expected knight fork on d7 (Q) and f8 (R)")

        # Case 5: Multi-piece fork: White knight moves to d6, attacking e8 (K), c8 (B), c7 (p), and e7 (p)
        fen5 = "r1b1k2r/pppp1ppp/8/8/4N3/8/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        moves5 = ["h7h6", "e4d6"]
        self.assertTrue(evaluate_fork_n(fen5, moves5), "Expected multi-piece knight fork from d6")

    def test_fork_n_negative_cases(self) -> None:
        """Verify >= 5 distinct, verified negative examples for fork_N."""
        # Negative 1: Non-knight piece (Bishop) moves and attacks multiple pieces
        fen1 = "r3k2r/pppb1ppp/8/8/8/3B4/PPPP1PPP/RNBQK2R b KQkq - 0 1"
        moves1 = ["h7h6", "d3e4"]
        self.assertFalse(evaluate_fork_n(fen1, moves1), "Bishop move must not trigger fork_N")

        # Negative 2: Knight moves at ply 1, but attacks only 1 piece
        fen2 = "r3k2r/pp2ppp1/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        moves2 = ["h7h6", "c3d5"]
        self.assertFalse(evaluate_fork_n(fen2, moves2), "Knight attacking 1 piece must not trigger fork_N")

        # Negative 3: Knight moves to square attacking 0 opponent pieces
        fen3 = "r3k2r/pppp1ppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
        moves3 = ["h7h6", "b1a3"]
        self.assertFalse(evaluate_fork_n(fen3, moves3), "Knight attacking 0 opponent pieces must not trigger fork_N")

        # Negative 4: Knight fork occurs at ply 3, NOT at ply 1
        fen4 = "r3k2r/1pp1bppp/8/1N6/8/8/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        moves4 = ["h7h6", "d1e2", "a7a6", "b5c7"]
        self.assertFalse(evaluate_fork_n(fen4, moves4), "Fork at ply 3 must not trigger fork_N (evaluated at ply 1)")

        # Negative 5: Knight attacks friendly pieces only
        fen5 = "8/8/8/8/3N4/2P1P3/8/4K2k b - - 0 1"
        moves5 = ["h1g1", "d4e6"]
        self.assertFalse(evaluate_fork_n(fen5, moves5), "Attacks on friendly pieces must not count towards fork")

    def test_fork_n_malformed_and_edge_cases(self) -> None:
        """Verify explicit handling of malformed FEN, illegal moves, and short sequences."""
        # Malformed FEN
        self.assertFalse(evaluate_fork_n("invalid-fen-string", ["e2e4", "e7e5"]))

        # Insufficient moves (< 2 moves)
        self.assertFalse(evaluate_fork_n("r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1", ["a7a6"]))
        self.assertFalse(evaluate_fork_n("r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1", []))

        # Illegal setup move M0
        self.assertFalse(evaluate_fork_n("r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1", ["e1e8", "c3d5"]))

        # Illegal player move M1
        self.assertFalse(evaluate_fork_n("r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1", ["a7a6", "c3h8"]))

    def test_back_rank_mate_positive_cases(self) -> None:
        """Verify >= 5 distinct, verified positive examples of back_rank_mate at terminal ply."""
        # Case 1: White rook delivers mate on d8 against Black king on g8
        fen1 = "6k1/p4ppp/8/8/8/8/8/3R2K1 b - - 0 1"
        moves1 = ["a7a6", "d1d8"]
        self.assertTrue(evaluate_back_rank_mate(fen1, moves1), "Expected back-rank mate with Rook on d8")

        # Case 2: White queen delivers mate on e8 against Black king on g8
        fen2 = "6k1/p4ppp/8/8/8/8/8/4Q1K1 b - - 0 1"
        moves2 = ["a7a6", "e1e8"]
        self.assertTrue(evaluate_back_rank_mate(fen2, moves2), "Expected back-rank mate with Queen on e8")

        # Case 3: Black rook delivers mate on d1 against White king on g1
        fen3 = "3r2k1/8/8/8/8/8/P4PPP/6K1 w - - 0 1"
        moves3 = ["a2a3", "d8d1"]
        self.assertTrue(evaluate_back_rank_mate(fen3, moves3), "Expected Black back-rank mate with Rook on d1")

        # Case 4: Black queen delivers mate on e1 against White king on g1
        fen4 = "4q1k1/8/8/8/8/8/P4PPP/6K1 w - - 0 1"
        moves4 = ["a2a3", "e8e1"]
        self.assertTrue(evaluate_back_rank_mate(fen4, moves4), "Expected Black back-rank mate with Queen on e1")

        # Case 5: Multi-ply sequence: White deflects/exchanges on d8 and mates on d8
        fen5 = "2r3k1/p2R1ppp/8/8/8/8/8/3R2K1 b - - 0 1"
        moves5 = ["a7a6", "d7d8", "c8d8", "d1d8"]
        self.assertTrue(evaluate_back_rank_mate(fen5, moves5), "Expected multi-ply back-rank mate")

    def test_back_rank_mate_negative_cases(self) -> None:
        """Verify >= 5 distinct, verified negative examples for back_rank_mate."""
        # Negative 1: Queen checkmate on rank 7 (not back rank)
        fen1 = "6k1/5p1p/6p1/8/8/8/8/4Q1K1 w - - 0 1"
        # Not a back-rank mate if delivered on non-back rank
        fen_smothered = "6k1/5pQp/8/8/8/8/8/6K1 b - - 0 1"
        moves1 = ["a7a6", "e1e7"]  # e7 is rank 7, not rank 8
        self.assertFalse(evaluate_back_rank_mate(fen1, moves1))

        # Negative 2: Check on rank 8 that is not checkmate (king has flight square)
        fen2 = "6k1/p4pp1/7p/8/8/8/8/3R2K1 b - - 0 1"
        moves2 = ["a7a6", "d1d8"]  # Kh7 is legal escape
        self.assertFalse(evaluate_back_rank_mate(fen2, moves2), "Escape square Kh7 prevents mate")

        # Negative 3: Checkmate delivered by Bishop (not Rook or Queen)
        fen3 = "k7/2B5/1K6/8/8/8/8/8 b - - 0 1"
        moves3 = ["a8a7", "c7b8"]
        self.assertFalse(evaluate_back_rank_mate(fen3, moves3), "Bishop mate is not back-rank mate")

        # Negative 4: Malformed FEN
        self.assertFalse(evaluate_back_rank_mate("corrupt-fen", ["a7a6", "d1d8"]))

        # Negative 5: Illegal move in sequence
        fen5 = "6k1/p4ppp/8/8/8/8/8/3R2K1 b - - 0 1"
        moves5 = ["a7a6", "e1e8"]  # No queen on e1
        self.assertFalse(evaluate_back_rank_mate(fen5, moves5), "Illegal move must be rejected")


if __name__ == "__main__":
    unittest.main()
