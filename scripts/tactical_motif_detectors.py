#!/usr/bin/env python3
"""Independent oracle detectors for tactical motifs (fork_N and back_rank_mate).

These detectors are used strictly as an external evaluation oracle to label Lichess
puzzles. They are never encoded as features or tokens within the retrieval index.
"""

from __future__ import annotations

from typing import List, Optional
import chess


def evaluate_fork_n(fen: str, moves: List[str]) -> bool:
    """Evaluate whether a puzzle exhibits a knight fork (fork_N) at solution ply 1.

    A puzzle exhibits fork_N if:
    1. The move sequence has at least 2 plies: Moves[0] (opponent setup) and Moves[1] (player move).
    2. Moves[0] and Moves[1] are legal moves replayable from the initial FEN.
    3. The piece moved in Moves[1] is a Knight (chess.KNIGHT).
    4. From its destination square at ply 1, the Knight simultaneously attacks >= 2
       opponent pieces, each valued at least as much as a pawn (pawn, knight, bishop,
       rook, queen, king).

    Any malformed FEN, illegal move, or unparseable sequence returns False.
    """
    if not fen or not moves or len(moves) < 2:
        return False

    try:
        board = chess.Board(fen)
        if not board.is_valid():
            return False

        # Replay Moves[0] (opponent setup move)
        setup_move = chess.Move.from_uci(moves[0])
        if setup_move not in board.legal_moves:
            return False
        board.push(setup_move)

        # Replay Moves[1] (first player solution move)
        player_move = chess.Move.from_uci(moves[1])
        if player_move not in board.legal_moves:
            return False

        # Verify the piece moved is a Knight
        moved_piece = board.piece_at(player_move.from_square)
        if moved_piece is None or moved_piece.piece_type != chess.KNIGHT:
            return False
        player_color = moved_piece.color

        # Push the player move to reach position P1
        board.push(player_move)

        # In position P1, examine the knight at player_move.to_square
        dest_square = player_move.to_square
        dest_piece = board.piece_at(dest_square)
        if dest_piece is None or dest_piece.piece_type != chess.KNIGHT or dest_piece.color != player_color:
            return False

        # Compute squares attacked by the knight from dest_square
        attacked_squares = board.attacks(dest_square)
        opponent_pieces_attacked = 0

        for sq in attacked_squares:
            piece_on_sq = board.piece_at(sq)
            if piece_on_sq is not None and piece_on_sq.color != player_color:
                # All chess pieces (P, N, B, R, Q, K) have value >= pawn
                opponent_pieces_attacked += 1

        return opponent_pieces_attacked >= 2

    except Exception:
        return False


def evaluate_back_rank_mate(fen: str, moves: List[str]) -> bool:
    """Evaluate whether a puzzle exhibits a back-rank checkmate at the terminal solution ply.

    A puzzle exhibits back_rank_mate if:
    1. The move sequence has at least 2 plies (Moves[0] setup + solution moves).
    2. All moves are legal when replayed from the initial FEN.
    3. The final position is checkmate (board.is_checkmate() is True).
    4. The mating move is delivered by a Rook (chess.ROOK) or Queen (chess.QUEEN).
    5. The mating piece lands on the mated king's home back rank:
       - Rank 8 (0-indexed rank 7) if mating Black's king.
       - Rank 1 (0-indexed rank 0) if mating White's king.

    Any malformed FEN, illegal move, or unparseable sequence returns False.
    """
    if not fen or not moves or len(moves) < 2:
        return False

    try:
        board = chess.Board(fen)
        if not board.is_valid():
            return False

        last_move: Optional[chess.Move] = None
        for move_uci in moves:
            move = chess.Move.from_uci(move_uci)
            if move not in board.legal_moves:
                return False
            board.push(move)
            last_move = move

        if not board.is_checkmate() or last_move is None:
            return False

        mating_piece = board.piece_at(last_move.to_square)
        if mating_piece is None or mating_piece.piece_type not in (chess.ROOK, chess.QUEEN):
            return False

        mating_color = mating_piece.color
        mated_color = not mating_color
        dest_rank = chess.square_rank(last_move.to_square)

        # Mated king's home back rank: rank 7 (8th rank) for Black, rank 0 (1st rank) for White
        if mated_color == chess.BLACK:
            return dest_rank == 7
        else:
            return dest_rank == 0

    except Exception:
        return False
