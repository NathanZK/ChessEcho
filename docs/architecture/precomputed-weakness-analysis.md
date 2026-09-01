# ChessEcho — Analysis & Weakness Data Architecture

## 1. Purpose

ChessEcho analyzes a player's historical chess games to identify recurring positions, evaluate the moves they played, and generate personalized puzzles.

The system should:

* Preserve raw game and position history as the source of truth.
* Analyze positions with Stockfish once and reuse that analysis across users.
* Store evaluations for historical moves and a range of alternative moves.
* Keep puzzle and weakness retrieval fast and paginated.
* Avoid recalculating hundreds of thousands of occurrences during API requests.
* Replace the current scheduled Stockfish polling with an explicit analysis step in the import pipeline.

---

# 2. Data Model

The data is divided into three layers:

```text
RAW USER DATA
    │
    ├── AppUser
    ├── ChessAccount
    ├── Game
    ├── Position
    └── PositionOccurrence
            │
            ▼
GLOBAL ENGINE DATA
    │
    ├── EngineAnalysis
    └── MoveEvaluation
            │
            ▼
READ-TIME WEAKNESS INTERPRETATION (MVP)
    │
    └── WeaknessCalculationService (JPQL Aggregation + Bounded Batch Reads)
            │
            ▼
FUTURE MATERIALIZED READ MODEL (RESERVED)
    │
    └── UserPositionWeakness
```

`UserPositionStats` is a separately import-maintained recurrence summary. It is
raw user data, but it is not part of the active weakness interpretation path
shown above.

## 2.1 Raw Data

### Game

A `Game` represents one imported chess game.

It must retain enough information to identify the players and determine which side the Chess.com/Lichess account played.

Relevant fields include:

* `id`
* `chessAccount`
* `platformGameId`
* `pgn`
* nullable `timeControl`
* nullable `playedAt`
* nullable `result`
* nullable `whiteUsername`
* nullable `blackUsername`
* `createdAt`

The associated `ChessAccount` identifies the imported user's account and owns
the platform. Player ratings are not persisted. `whiteUsername` and
`blackUsername` identify the players and allow the imported account's side to
be determined.

The current Chess.com import writes `white.result` directly into `Game.result`
regardless of whether the imported account played White or Black. Therefore
`result` is currently a white-side source value, not a normalized
account-perspective outcome.

---

## 2.2 Position

A `Position` represents a unique board state.

It contains:

* `id`
* `hash`
* `fen`

The position is global. The same position reached by different users or in different games refers to the same `Position`.

---

## 2.3 PositionOccurrence

A `PositionOccurrence` represents one specific time a user reached a position in a game.

It contains:

* `id`
* `positionId`
* `gameId`
* `chessAccountId`
* `playerColor`
* `plyNumber`
* `movePlayed`

For example:

```text
Position: P123
Game: G456
Account: Nathan's Chess.com account
Color: WHITE
Ply: 31
Move played: Nf3
```

This is the source-of-truth record used to determine how often a user reached a position and what they actually played.

---

# 3. Global Engine Analysis

Stockfish analysis is global because the same position has the same engine evaluation regardless of which user reached it.

## 3.1 EngineAnalysis

There is one `EngineAnalysis` record per analyzed position. The uniqueness
constraint covers `position_id` only; no analysis-configuration identity is
persisted.

It contains:

* `id`
* `position`
* `depth`
* nullable `baselineEvalCp`
* nullable `bestMove`
* nullable `bestMoveEvalCp`
* child `moveEvaluations`
* `analyzedAt`

`baselineEvalCp` represents the evaluation of the position before the player moves.

`bestMoveEvalCp` represents the evaluation after Stockfish's best move.

Both are retained because move loss/gain must be calculated relative to the best available continuation.

---

# 4. Move Evaluations

`MoveEvaluation` stores Stockfish's evaluation of moves from a position.

Move evaluations are global and are attached to `EngineAnalysis`.

Each record contains:

* `engineAnalysis`
* `move`
* nullable `evalCp`
* nullable `evalLossFromBest`

The `evalLossFromBest` represents how much worse the move is than Stockfish's best move from the player's perspective.

For example:

| Move | Evaluation | Loss from best |
| ---- | ---------: | -------------: |
| Nf3  |      +1.20 |           0.00 |
| Bb5  |      +1.05 |           0.15 |
| d4   |      +0.60 |           0.60 |
| a3   |      +0.10 |           1.10 |

The best move therefore has:

```text
evalLossFromBest = 0.0
```

---

# 5. Acceptable Moves

Acceptable moves are **not stored as a single fixed list** such as "acceptable at 0.3 pawns."

Instead, the system stores the underlying move evaluations.

This allows the user's acceptable threshold to be chosen later.

For example, suppose a position has:

```text
Best move: Nf3
Best evaluation: +1.20

Move evaluations:

Nf3    +1.20    loss 0.00
Bb5    +1.05    loss 0.15
d4     +0.60    loss 0.60
a3     +0.25    loss 0.95
```

A user with an acceptable threshold of `0.3` gets:

```text
Nf3
Bb5
```

A user with an acceptable threshold of `0.8` gets:

```text
Nf3
Bb5
d4
```

A beginner could therefore receive a more forgiving puzzle solution set without requiring another Stockfish analysis.

The acceptable threshold is a **request/user preference**, while the stored move evaluations are the reusable global data.

---

# 6. User Position Weakness (Future Materialized Read Model)

`UserPositionWeakness` stores the aggregated performance of one chess account in one position for one color.

The uniqueness constraint is:

```text
(chessAccountId, positionId, playerColor)
```

The aggregate contains:

* `id`
* `chessAccountId`
* `positionId`
* `playerColor`
* `mistakeCount`
* `mistakeRate`
* `averageLoss`
* `priority`
* `movesPlayed`
* `gameUrls`
* `updatedAt`

The moves and game URLs are serialized values. `UserPositionWeakness` exists in
the schema/codebase as an inactive, reserved future materialized read model, but
the active service does not read it.

---

# 7. User-Specific Calculations

## timesReached

Number of times the user reached the position while playing the specified color.

```text
timesReached = COUNT(PositionOccurrence.id)
```

The active `PositionOccurrenceRepository.findWeaknessAggregations` count is over
occurrences joined to matching engine/move evaluation data. Separately,
`UserPositionStats.timesReached` is an import-maintained all-occurrence summary
updated by `GameImportService.updateUserPositionStats`; it is not an input to
the active weakness read path.

## mistakeCount

Number of the user's moves whose evaluation loss exceeds the user's configured mistake threshold.

```text
mistake threshold = 0.8 pawns (default, configurable at request time)
```

## mistakeRate

```text
mistakeRate = mistakeCount / timesReached
```

## averageLoss

Average evaluation loss across the user's mistakes.

## priority and recommendationPriority

`priority` remains the objective-only value computed from engine-classified
mistakes, recency weighting, and raw mistake rate:

```text
priority = sum(evalLoss × weight) × (mistakeCount / timesReached)
```

[Practical Evidence in Weakness Prioritization](practical-weakness-prioritization.md)
is implemented as a separate, account-specific W/D/L evidence dimension derived
at read time from `PositionOccurrence` and `Game`. This slice adds no schema,
materialization, or backfill and does not activate `UserPositionWeakness`.

`recommendationPriority` is the shared weakness/puzzle ordering key. It equals
the objective `priority` whenever practical ranking is disabled, uncalibrated,
absent, insufficient, or inconclusive. Only explicitly enabled, calibrated, and
confidence-eligible practical evidence may apply the bounded adjustment. The
production defaults (`chess.weakness.practical.ranking-enabled=false` and
`policy-version=uncalibrated-v1`) therefore preserve objective-only ordering.

---

# 8. Resulting Architecture & MVP Performance Validation

### Current MVP Architecture

The active MVP read path calculates objective weakness and additive practical
evidence dynamically at query time:

```text
PositionOccurrence
+
EngineAnalysis
+
MoveEvaluation
        │
        ├── objective aggregation ────────────────┐
        │                                         │
PositionOccurrence + Game                        │
        │                                         │
        └── PracticalEvidenceService (W/D/L) ─────┤
                                                  ▼
                                  WeaknessCalculationService
                                                  +
                                  WeaknessPriorityPolicy
                                  (confidence-gated adjustment)
                                                  │
                                                  ▼
                           shared deterministic pre-pagination order
                    recommendationPriority, priority, position ID, color
                                                  │
                                                  ▼
                               WeaknessResponse / PuzzleResponse
```

`priority` stays objective-only. `recommendationPriority` changes ordering only
when practical ranking is explicitly configured, calibrated, and admitted by
the confidence gate; it is equal to `priority` under the default-off
configuration. Both weaknesses and puzzles consume this same total order before
pagination.

The read path performs no database writes and invokes no Stockfish analysis.
Practical evidence is derived from existing occurrence and game data rather
than materialized. `UserPositionWeakness` remains an inactive future escape
hatch and is **not** part of the active read path.

---

### Why Dynamic Query-Time Weakness is Currently Acceptable for MVP

Initial benchmarks showed the unoptimized dynamic implementation degrading to **~46.2 seconds** for large real-world accounts due to un-indexed occurrence join scans and $O(N)$ secondary query loops.

After introducing a composite index on `PositionOccurrence(chess_account_id, player_color, position_id)` and batching secondary entity lookups, real-world benchmark measurements improved to:

- **Original Request Time**: **~46.2 seconds** (46,249 ms)
- **Optimized Dynamic Request Time**: **~171 milliseconds** (171.11 ms)
- **Overall Speedup**: **$\approx 270\times$ faster endpoint execution**.

The optimized dynamic approach provides:
- **0 Stockfish calls** during weakness/puzzle reads.
- **0 database writes** during weakness/puzzle reads.
- **A fixed set of batched database reads** with no per-game query. The
  practical-evidence occurrence fetch is account-scoped but not row-capped.
- **Interactive query-time threshold adjustments** (`minEvalLoss = 0.3` vs `0.8`) with zero database recalculation writes or cache invalidation overhead.

Therefore, the dynamic query-time architecture is fully sufficient for the MVP.

---

### Benchmark Comparison

| Metric | Old Implementation Baseline | Optimized Dynamic (MVP) | Materialized Read Path (Future) |
| :--- | ---: | ---: | ---: |
| **Total `/api/puzzles` Latency** | ~46.2 s | **~171 ms** | ~24 ms |
| **Occurrence Data Loaded** | 432,497 rows | **5,000 candidate rows** | 0 (read from view) |
| **Position Processing** | 372,342 positions in JVM loop | **200 qualifying positions** | 200 precomputed rows |
| **Database Query Pattern** | $O(N)$ secondary queries (up to 1,003) | **4 bounded queries** | 1 query |
| **Stockfish Calls During Read** | 0 | **0** | 0 |
| **DB Writes During Read** | 0 | **0** | 0 |

---

### Future Escape Hatch

If user game history grows significantly larger (e.g. $> 10,000$ games) or latency SLAs require sub-50ms responses, `UserPositionWeakness` can be enabled as an asynchronous materialized read model populated post-import.

Because raw historical data (`PositionOccurrence`) and engine data (`EngineAnalysis`) remain the immutable source of truth, enabling `UserPositionWeakness` in the future will require **zero changes to the underlying raw data model**.
