# Dynamic FEN Similarity Architecture

## Overview

The dynamic FEN similarity subsystem extends arbitrary-FEN similarity retrieval (`GET /api/positions/similar`) by combining static relational BM25 retrieval with engine-generated tactical continuations.

## Two-Stage Retrieval Pipeline

1. **Stage 1: Inverted Index Retrieval & Pre-Qualification**
   - Retrieves candidates from the user's personal game corpus using `PositionInvertedIndex` and static BM25 scoring.
   - Filters candidate positions to ensure `playerColor == activeTurn`.
   - Requires verified personal occurrences in the user's games.
   - Clamps the pre-qualified shortlist to at most 100 candidates.

2. **Stage 2: Stockfish Batch Session & Dynamic BM25 Scoring**
   - Resolves candidates cache-first via `EngineAnalysisRepository.findByPositionIdInWithMoveEvaluations`; candidates already evaluated at depth $\ge 16$ with non-blank best move bypass engine batch analysis.
   - For remaining uncached candidates, acquires a concurrency permit via `SimilarityConcurrencyManager`.
   - Starts a bounded Stockfish batch session targeting Depth 16 MultiPV 1 (`DYNAMIC_TARGET_DEPTH = 16`).
   - Tracks MultiPV variations across iterative-deepening lines, recording `achievedDepth` per rank.
   - Evaluates continuations at ply 0 using `DynamicTacticalScorer` and BM25 token matching on rank 1 PV.
   - Reranks candidates deterministically: dynamic score descending, static rank ascending, `canonicalFen` ascending, and `positionId` ascending.
   - On batch timeout, engine failure, or capacity exhaustion, falls back cleanly to Stage-1 candidates directly.

## Concurrency Control

- `SimilarityConcurrencyManager` manages engine session capacity with an in-memory `Semaphore`.
- Configuration: `chess.similarity.dynamic.max-concurrent-sessions` (default 2, must be >= 1).
- Permit acquisition and release is managed strictly inside `try ... finally` in `withPermitOrFallback()`.
- On capacity saturation, requests immediately fall back to static similarity ranking.

## Watchdog & Recovery Lifecycle

- Search limit: depth-driven (`go depth <depth>`, range 4..12, default 8).
- Per-position watchdog: 500ms timeout per search.
- When watchdog triggers:
  1. Issues UCI `stop` to Stockfish.
  2. Waits up to 200ms to drain `bestmove`.
  3. If `bestmove` received, marks candidate as `INTERRUPTED` with dynamic score 0.0, retaining static rank.
  4. If drain times out or engine hangs, terminates subprocess and spawns at most 1 replacement process per batch.
  5. If replacement fails or query analysis fails/interrupts, gracefully falls back to static ranking.
- Subprocess is guaranteed destroyed upon batch completion in a `finally` block.

## API Contracts

- `GET /api/positions/similar`:
  - `depth`: integer in `4..12` (default 8).
  - `multiPv`: integer in `1..5` (default 1).
- Response exposes:
  - `candidatesEvaluated`, `shortlistedCandidatesCount`, `engineCompletedCount`.
  - `dynamicSimilarityApplied`, `fallbackReason`.
  - `queryStatus`, `queryAchievedDepth`, `queryPrincipalVariations`.
  - Per-position `status`, `achievedDepth`, `principalVariations`.
