#!/usr/bin/env python3
"""Run and independently verify the bounded #522 FEN-similarity fixture."""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import math
import os
import platform
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

import chess


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PATH = ROOT / "src/test/resources/fen-similarity-v1-fixtures.json"
REQUIREMENTS_PATH = ROOT / "scripts/requirements-fen-similarity-verifier.txt"
EXPECTED_CHESS_VERSION = "1.11.2"
K1 = 1.2
B = 0.75
RAY_DIRECTIONS = (
    (1, 0),
    (-1, 0),
    (0, 1),
    (0, -1),
    (1, 1),
    (1, -1),
    (-1, 1),
    (-1, -1),
)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def chess_distribution_sha256() -> str:
    distribution = importlib.metadata.distribution("chess")
    digest = hashlib.sha256()
    package_files = sorted(path for path in distribution.files or [] if str(path).startswith("chess/"))
    for package_file in package_files:
        path = distribution.locate_file(package_file)
        digest.update(str(package_file).encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def square_name(square: int) -> str:
    return chess.square_name(square)


def color_name(color: chess.Color) -> str:
    return "white" if color == chess.WHITE else "black"


def piece_name(piece: chess.Piece) -> str:
    return chess.piece_name(piece.piece_type)


def canonical_fen(board: chess.Board) -> str:
    return " ".join(board.fen(en_passant="fen").split()[:4])


def replay(start_fen: str, sequence: str) -> chess.Board:
    board = chess.Board(start_fen)
    if not board.is_valid():
        raise AssertionError(f"Invalid standard starting position: {start_fen}")
    for uci in sequence.split():
        move = chess.Move.from_uci(uci)
        if move not in board.legal_moves:
            raise AssertionError(f"Illegal fixture move {uci} in {sequence}")
        board.push(move)
        if not board.is_valid():
            raise AssertionError(f"Fixture sequence produced an invalid position after {uci}")
    return board


def feature_terms(board: chess.Board, include_reachability: bool) -> dict[str, float]:
    terms: defaultdict[str, float] = defaultdict(float)
    pieces = board.piece_map()

    for square, piece in pieces.items():
        terms[f"piece|{color_name(piece.color)}|{piece_name(piece)}|{square_name(square)}"] += 1.0

    if include_reachability:
        for color in (chess.WHITE, chess.BLACK):
            side_board = board.copy(stack=False)
            side_board.turn = color
            moves_by_destination: set[tuple[int, int]] = set()
            for move in side_board.generate_pseudo_legal_moves():
                if side_board.is_castling(move):
                    continue
                if side_board.piece_at(move.to_square) is not None:
                    continue
                moves_by_destination.add((move.from_square, move.to_square))

            for source, target in sorted(moves_by_destination):
                piece = pieces[source]
                distance = max(
                    abs(chess.square_file(source) - chess.square_file(target)),
                    abs(chess.square_rank(source) - chess.square_rank(target)),
                )
                weight = 1.0 - (7.0 * distance / 64.0)
                token = f"reach|{color_name(piece.color)}|{piece_name(piece)}|{square_name(target)}"
                terms[token] += weight

    for source, source_piece in pieces.items():
        attacked_squares = board.attacks(source)
        for target in attacked_squares:
            target_piece = pieces.get(target)
            if target_piece is None or target == source:
                continue
            family = "defense" if target_piece.color == source_piece.color else "attack"
            token = (
                f"{family}|{color_name(source_piece.color)}|{piece_name(source_piece)}|"
                f"{color_name(target_piece.color)}|{piece_name(target_piece)}|{square_name(target)}"
            )
            terms[token] += 1.0

    for source, slider in pieces.items():
        if slider.piece_type not in (chess.ROOK, chess.BISHOP, chess.QUEEN):
            continue
        directions = (
            RAY_DIRECTIONS[:4]
            if slider.piece_type == chess.ROOK
            else RAY_DIRECTIONS[4:]
            if slider.piece_type == chess.BISHOP
            else RAY_DIRECTIONS
        )
        source_file = chess.square_file(source)
        source_rank = chess.square_rank(source)
        for file_step, rank_step in directions:
            file_index = source_file + file_step
            rank_index = source_rank + rank_step
            while 0 <= file_index < 8 and 0 <= rank_index < 8:
                target = chess.square(file_index, rank_index)
                target_piece = pieces.get(target)
                if target_piece is not None:
                    if target_piece.color != slider.color:
                        token = (
                            f"ray|{color_name(slider.color)}|{piece_name(slider)}|"
                            f"{color_name(target_piece.color)}|{piece_name(target_piece)}|{square_name(target)}"
                        )
                        terms[token] += 1.0
                    break
                file_index += file_step
                rank_index += rank_step

    return dict(sorted(terms.items()))


def descriptors(board: chess.Board) -> dict[str, object]:
    fen_fields = board.fen(en_passant="fen").split()
    counts = {
        f"{color_name(color)}_{chess.piece_name(piece_type)}": len(board.pieces(piece_type, color))
        for color in (chess.WHITE, chess.BLACK)
        for piece_type in range(chess.PAWN, chess.KING + 1)
    }
    attacked_squares = {}
    for color in (chess.WHITE, chess.BLACK):
        squares = set()
        for source in board.pieces(chess.PAWN, color) | board.pieces(chess.KNIGHT, color) | \
                board.pieces(chess.BISHOP, color) | board.pieces(chess.ROOK, color) | \
                board.pieces(chess.QUEEN, color) | board.pieces(chess.KING, color):
            squares.update(board.attacks(source))
        attacked_squares[color_name(color)] = sorted(square_name(square) for square in squares)

    return {
        "pieceTerms": sorted(
            f"piece|{color_name(piece.color)}|{piece_name(piece)}|{square_name(square)}"
            for square, piece in board.piece_map().items()
        ),
        "pieceCounts": counts,
        "sideToMove": "WHITE" if board.turn == chess.WHITE else "BLACK",
        "castlingRights": "".join(sorted(board.castling_xfen().replace("-", ""))),
        "enPassantTarget": fen_fields[3],
        "legalUciMoves": sorted(move.uci() for move in board.legal_moves),
        "attackedSquares": attacked_squares,
    }


def term_digest(terms: dict[str, float]) -> str:
    contents = "\n".join(f"{token}\t{terms[token]}" for token in sorted(terms))
    return sha256_bytes(contents.encode("utf-8"))


def document_statistics(
    query_key: str,
    candidate_keys: list[str],
    terms_by_key: dict[str, dict[str, float]],
) -> tuple[dict[str, float], dict[str, int], dict[str, float], float]:
    document_keys = sorted(set(candidate_keys) - {query_key})
    document_terms = {key: terms_by_key[key] for key in document_keys}
    document_lengths = {
        key: sum(terms_by_key[key][term] for term in sorted(terms_by_key[key]))
        for key in document_keys
    }
    document_frequencies: Counter[str] = Counter()
    for terms in document_terms.values():
        document_frequencies.update(term for term, frequency in terms.items() if frequency > 0.0)
    average_length = sum(document_lengths[key] for key in document_keys) / len(document_keys) if document_keys else 0.0
    return document_lengths, dict(document_frequencies), document_terms, average_length


def score(
    query_terms: dict[str, float],
    document_terms: dict[str, float],
    document_frequencies: dict[str, int],
    document_count: int,
    document_length: float,
    average_document_length: float,
) -> float:
    if document_count == 0 or average_document_length == 0.0:
        return 0.0
    total = 0.0
    for term in sorted(query_terms):
        term_frequency = document_terms.get(term, 0.0)
        if term_frequency <= 0.0:
            continue
        document_frequency = document_frequencies.get(term, 0)
        inverse_document_frequency = math.log(
            1.0 + (document_count - document_frequency + 0.5) / (document_frequency + 0.5)
        )
        normalization = K1 * (1.0 - B + B * document_length / average_document_length)
        total += inverse_document_frequency * (term_frequency * (K1 + 1.0)) / (term_frequency + normalization)
    if not math.isfinite(total):
        raise AssertionError("Independent BM25 score is not finite")
    return total


def rank(
    query_key: str,
    query_terms: dict[str, float],
    candidate_keys: list[str],
    terms_by_key: dict[str, dict[str, float]],
) -> tuple[list[tuple[str, float]], dict[str, object]]:
    lengths, frequencies, document_terms, average_length = document_statistics(
        query_key, candidate_keys, terms_by_key
    )
    scored = [
        (
            key,
            score(
                query_terms,
                document_terms[key],
                frequencies,
                len(document_terms),
                lengths[key],
                average_length,
            ),
        )
        for key in sorted(document_terms)
    ]
    scored.sort(key=lambda row: (-row[1], row[0]))
    frequency_text = "\n".join(f"{term}\t{count}" for term, count in sorted(frequencies.items()))
    statistics = {
        "N": len(document_terms),
        "averageDocumentLength": repr(average_length),
        "documentFrequencyDigest": sha256_bytes(frequency_text.encode("utf-8")),
    }
    return scored, statistics


def assert_close(actual: object, expected: float, label: str) -> None:
    if not math.isclose(float(actual), expected, rel_tol=1e-12, abs_tol=1e-12):
        raise AssertionError(f"{label}: actual {actual!r}, expected {expected!r}")


def compare_rankings(
    report: dict[str, object],
    seeds: list[tuple[str, str]],
    documents: dict[str, str],
    query_terms_by_key: dict[str, dict[str, float]],
    terms_by_key: dict[str, dict[str, float]],
) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    keys = sorted(documents)
    expected_rankings = []
    full_rank_by_query: dict[str, list[tuple[str, float]]] = {}

    for fixture_id, query_fen in seeds:
        query_key = " ".join(query_fen.split()[:4])
        independent, statistics = rank(query_key, query_terms_by_key[query_key], keys, terms_by_key)
        full_rank_by_query[fixture_id] = independent
        kotlin_row = next(row for row in report["rankings"] if row["queryFixture"] == fixture_id)
        if kotlin_row["poolStatistics"]["N"] != statistics["N"]:
            raise AssertionError(f"Pool N differs for query {fixture_id}")
        if kotlin_row["poolStatistics"]["documentFrequencyDigest"] != statistics["documentFrequencyDigest"]:
            raise AssertionError(f"Recomputed document frequencies differ for query {fixture_id}")
        assert_close(
            kotlin_row["poolStatistics"]["averageDocumentLength"],
            float(statistics["averageDocumentLength"]),
            f"Average document length for query {fixture_id}",
        )
        actual_rows = kotlin_row["ranking"]
        if [row["canonicalFen"] for row in actual_rows] != [key for key, _ in independent]:
            raise AssertionError(f"Ranking keys/order differ for query {fixture_id}")
        if len(actual_rows) != len(independent):
            raise AssertionError(f"Ranking length differs for query {fixture_id}")
        for actual, (_, expected_score) in zip(actual_rows, independent):
            assert_close(actual["score"], expected_score, f"BM25 score for query {fixture_id}, rank {actual['rank']}")
        expected_rankings.append(
            {
                "queryFixture": fixture_id,
                "queryFen": query_fen,
                "poolStatistics": statistics,
                "ranking": actual_rows,
            }
        )

    expected_stability = []
    fixture_records = {record["id"]: record for record in report["fixtures"]}
    for fixture_id, query_fen in seeds:
        query_key = " ".join(query_fen.split()[:4])
        full_rank = full_rank_by_query[fixture_id]
        for omitted_fixture in ("A", "B", "C", "D"):
            omitted_record = fixture_records[omitted_fixture]
            omitted_keys = {omitted_record["canonicalKeyA"], omitted_record["successorKey"]}
            candidate_keys = [key for key in keys if key not in omitted_keys]
            perturbed, statistics = rank(
                query_key,
                query_terms_by_key[query_key],
                candidate_keys,
                terms_by_key,
            )
            common_keys = set(candidate_keys) - {query_key}
            baseline_top = [key for key, _ in full_rank if key in common_keys][:3]
            perturbed_top = [key for key, _ in perturbed][:3]
            union = set(baseline_top) | set(perturbed_top)
            jaccard = len(set(baseline_top) & set(perturbed_top)) / len(union) if union else 1.0

            actual = next(
                row
                for row in report["stability"]
                if row["queryFixture"] == fixture_id and row["omittedFixture"] == omitted_fixture
            )
            if actual["fullPoolCommonTop3"] != sorted(baseline_top):
                raise AssertionError(f"Baseline common top-three differs for {fixture_id}/{omitted_fixture}")
            if actual["perturbedTop3"] != sorted(perturbed_top):
                raise AssertionError(f"Perturbed top-three differs for {fixture_id}/{omitted_fixture}")
            if actual["candidateCount"] != len(candidate_keys):
                raise AssertionError(f"Candidate count differs for {fixture_id}/{omitted_fixture}")
            assert_close(actual["jaccard"], jaccard, f"Jaccard for {fixture_id}/{omitted_fixture}")
            if actual["poolStatistics"]["N"] != statistics["N"]:
                raise AssertionError(f"Perturbed N differs for {fixture_id}/{omitted_fixture}")
            if actual["poolStatistics"]["documentFrequencyDigest"] != statistics["documentFrequencyDigest"]:
                raise AssertionError(f"Perturbed document frequencies differ for {fixture_id}/{omitted_fixture}")
            assert_close(
                actual["poolStatistics"]["averageDocumentLength"],
                float(statistics["averageDocumentLength"]),
                f"Perturbed average length for {fixture_id}/{omitted_fixture}",
            )
            expected_stability.append(actual)

    return expected_rankings, expected_stability


def make_independent_states(fixture_spec: dict[str, object]) -> tuple[dict[str, object], dict[str, str], list[tuple[str, str]]]:
    fixtures = []
    documents: dict[str, str] = {}
    states: dict[str, object] = {}
    seeds = []

    for fixture in fixture_spec["fixtures"]:
        board_a = replay(fixture_spec["startFen"], fixture["sequenceA"])
        board_b = replay(fixture_spec["startFen"], fixture["sequenceB"])
        fen_a = board_a.fen(en_passant="fen")
        fen_b = board_b.fen(en_passant="fen")
        key_a = canonical_fen(board_a)
        key_b = canonical_fen(board_b)
        if key_a != key_b:
            raise AssertionError(f"Independent move orders do not transpose for fixture {fixture['id']}")
        if descriptors(board_a) != descriptors(board_b):
            raise AssertionError(f"Independent descriptors differ across transposition {fixture['id']}")
        if feature_terms(board_a, True) != feature_terms(board_b, True):
            raise AssertionError(f"Independent document terms differ across transposition {fixture['id']}")
        if feature_terms(board_a, False) != feature_terms(board_b, False):
            raise AssertionError(f"Independent query terms differ across transposition {fixture['id']}")

        successor_board = board_a.copy(stack=False)
        successor_move = min(move.uci() for move in successor_board.legal_moves)
        successor_board.push_uci(successor_move)
        successor_fen = successor_board.fen(en_passant="fen")
        successor_key = canonical_fen(successor_board)

        fixtures.append(
            {
                "id": fixture["id"],
                "sequenceA": fixture["sequenceA"],
                "sequenceB": fixture["sequenceB"],
                "fenA": fen_a,
                "fenB": fen_b,
                "canonicalKeyA": key_a,
                "canonicalKeyB": key_b,
                "successorMove": successor_move,
                "successorFen": successor_fen,
                "successorKey": successor_key,
            }
        )
        seeds.append((fixture["id"], fen_a))
        documents.setdefault(key_a, fen_a)
        documents.setdefault(successor_key, successor_fen)

        for key, board in ((key_a, board_a), (successor_key, successor_board)):
            states[key] = {
                "board": board,
                "descriptors": descriptors(board),
                "documentTerms": feature_terms(board, include_reachability=True),
                "queryTerms": feature_terms(board, include_reachability=False),
            }

    if len(documents) != 8:
        raise AssertionError(f"Expected eight independent fixture documents, got {len(documents)}")
    return {"fixtures": fixtures, "states": states}, documents, seeds


def verify_counter_variants(report: dict[str, object], states: dict[str, object]) -> None:
    for record in report["counterVariants"]:
        for check in (
            "keyUnchanged",
            "documentTermsUnchanged",
            "queryTermsUnchanged",
            "candidateScoreUnchanged",
            "queryRankingUnchanged",
        ):
            if record[check] is not True:
                raise AssertionError(f"Kotlin counter-variant check {check} failed for {record['positionKey']}")
        base = chess.Board(states[record["positionKey"]]["board"].fen(en_passant="fen"))
        variant = chess.Board(record["fen"])
        if canonical_fen(variant) != record["positionKey"]:
            raise AssertionError(f"Counter variant changed identity: {record['positionKey']}")
        state = states[record["positionKey"]]
        if feature_terms(variant, True) != state["documentTerms"]:
            raise AssertionError(f"Counter variant changed document features: {record['positionKey']}")
        if feature_terms(variant, False) != state["queryTerms"]:
            raise AssertionError(f"Counter variant changed query features: {record['positionKey']}")
        if descriptors(variant) != descriptors(base):
            raise AssertionError(f"Counter variant changed independent descriptors: {record['positionKey']}")


def main() -> int:
    if chess.__version__ != EXPECTED_CHESS_VERSION:
        raise RuntimeError(
            f"Expected chess {EXPECTED_CHESS_VERSION}, found {chess.__version__}; "
            "install the verifier-only pinned requirements."
        )
    fixture_bytes = FIXTURE_PATH.read_bytes()
    fixture_spec = json.loads(fixture_bytes)
    independent, documents, seeds = make_independent_states(fixture_spec)
    terms_by_key = {
        key: state["documentTerms"]
        for key, state in independent["states"].items()
    }
    query_terms_by_key = {
        key: state["queryTerms"]
        for key, state in independent["states"].items()
    }

    with tempfile.TemporaryDirectory(prefix="chessecho-fen-similarity-") as temporary_directory:
        report_path = Path(temporary_directory) / "kotlin-evaluation.json"
        environment = os.environ.copy()
        environment["FEN_SIMILARITY_REPORT_PATH"] = str(report_path)
        subprocess.run(
            [
                "./gradlew",
                "test",
                "--tests",
                "*FenSimilarityFixtureEvaluationTest",
                "--rerun-tasks",
            ],
            cwd=ROOT,
            env=environment,
            check=True,
        )
        report = json.loads(report_path.read_text(encoding="utf-8"))

    for expected in independent["fixtures"]:
        actual = next(record for record in report["fixtures"] if record["id"] == expected["id"])
        for key in ("sequenceA", "sequenceB", "fenA", "fenB", "canonicalKeyA", "canonicalKeyB", "successorMove", "successorFen", "successorKey"):
            if actual[key] != expected[key]:
                raise AssertionError(f"Fixture {expected['id']} {key} differs: {actual[key]!r} vs {expected[key]!r}")
    if report["documentPool"] != [
        {"canonicalKey": key, "fen": documents[key]} for key in sorted(documents)
    ]:
        raise AssertionError("Kotlin document pool differs from the independent fixture")

    for actual_state in report["states"]:
        key = actual_state["canonicalKey"]
        expected_state = independent["states"][key]
        for descriptor, expected_value in expected_state["descriptors"].items():
            actual_value = actual_state["descriptors"].get(descriptor)
            if actual_value != expected_value:
                raise AssertionError(
                    f"Independent descriptor {descriptor} differs for {key}: "
                    f"component={actual_value!r}; oracle={expected_value!r}"
                )
        if actual_state["documentTerms"].keys() != expected_state["documentTerms"].keys():
            raise AssertionError(f"Document feature term set differs for {key}")
        if actual_state["queryTerms"].keys() != expected_state["queryTerms"].keys():
            raise AssertionError(f"Query feature term set differs for {key}")
        for field in ("documentTerms", "queryTerms"):
            for token, expected_frequency in expected_state[field].items():
                assert_close(
                    actual_state[field][token],
                    expected_frequency,
                    f"{field} {token} in {key}",
                )
        if actual_state["documentTermCount"] != len(expected_state["documentTerms"]):
            raise AssertionError(f"Document term count differs for {key}")
        if actual_state["documentTermDigest"] != term_digest(expected_state["documentTerms"]):
            raise AssertionError(f"Document term digest differs for {key}")

    verify_counter_variants(report, independent["states"])
    expected_rankings, expected_stability = compare_rankings(
        report,
        seeds,
        documents,
        query_terms_by_key,
        terms_by_key,
    )
    mean_jaccard = sum(float(row["jaccard"]) for row in expected_stability) / len(expected_stability)
    if not math.isclose(float(report["meanJaccard"]), mean_jaccard, rel_tol=1e-12, abs_tol=1e-12):
        raise AssertionError("Mean Jaccard differs from independent calculation")
    if report["projectStabilityGate"] is not (mean_jaccard >= 0.60):
        raise AssertionError("Kotlin stability-gate result differs from the independent calculation")

    base_revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    source_paths = (
        "src/main/kotlin/com/chessecho/similarity/FenPositionSimilarity.kt",
        "src/test/kotlin/com/chessecho/similarity/FenPositionSimilarityTest.kt",
        "src/test/kotlin/com/chessecho/similarity/FenSimilarityFixtureEvaluationTest.kt",
        "src/test/resources/fen-similarity-v1-fixtures.json",
        "scripts/verify_fen_similarity_fixture.py",
        "scripts/requirements-fen-similarity-verifier.txt",
    )
    compact_states = [
        {
            "canonicalKey": state["canonicalKey"],
            "fen": state["fen"],
            "descriptors": state["descriptors"],
            "documentTermCount": state["documentTermCount"],
            "documentTermDigest": state["documentTermDigest"],
        }
        for state in report["states"]
    ]
    result = {
        "baseRevision": base_revision,
        "filesSha256": {path: sha256_file(ROOT / path) for path in source_paths},
        "fixtureInputSha256": sha256_bytes(fixture_bytes),
        "verifierDependency": {
            "distribution": "chess",
            "version": chess.__version__,
            "pythonVersion": platform.python_version(),
            "installedPackageSourceSha256": chess_distribution_sha256(),
            "requirementsSha256": sha256_file(REQUIREMENTS_PATH),
        },
        "verifierScriptSha256": sha256_file(Path(__file__).resolve()),
        "fixtureEndpointAndSuccessorRecords": report["fixtures"],
        "documentPool": report["documentPool"],
        "independentPositionFeatureVectors": compact_states,
        "counterVariantChecks": report["counterVariants"],
        "fullRankings": expected_rankings,
        "stabilityComparisons": expected_stability,
        "meanTopThreeJaccard": format(mean_jaccard, ".17g"),
        "stabilityThreshold": 0.60,
        "stage1V1Accepted": mean_jaccard >= 0.60,
        "limitations": (
            "Passes establish consistency with the declared feature contract and stability on this fixed fixture only; "
            "they do not establish universal ground-truth FEN similarity."
        ),
    }
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
