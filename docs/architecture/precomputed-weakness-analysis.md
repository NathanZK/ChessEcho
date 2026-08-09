# ChessEcho — Analysis & Weakness Data Architecture

## 1. Purpose

ChessEcho analyzes a player's historical chess games to identify recurring positions, evaluate the moves they played, and generate personalized puzzles.

The system should:

* Preserve raw game and position history as the source of truth.
* Analyze positions with Stockfish once and reuse that analysis across users.
* Store evaluations for historical moves and a range of alternative moves.
* Precompute user-specific weakness statistics and priority.
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
USER-SPECIFIC DERIVED DATA
    │
    └── UserPositionWeakness
```

## 2.1 Raw Data

### Game

A `Game` represents one imported chess game.

It must retain enough information to identify the players and determine which side the Chess.com/Lichess account played.

Relevant fields include:

* `id`
* `chessAccountId`
* `platform`
* `platformGameId`
* `whitePlayer`
* `blackPlayer`
* `whitePlayerRating`
* `blackPlayerRating`
* `playedAt`
* `timeControl`
* `result`
* `pgn`

The `chessAccountId` identifies the imported user's account. `whitePlayer` and `blackPlayer` identify the actual players in the game.

This allows the system to determine whether the imported user played White or Black.

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

There is one `EngineAnalysis` record per analyzed position and analysis configuration.

It contains:

* `positionId`
* `depth`
* `baselineEvalCp`
* `baselineEvalMate`
* `bestMove`
* `bestMoveEvalCp`
* `bestMoveEvalMate`
* `analyzedAt`

`baselineEval*` represents the evaluation of the position before the player moves.

`bestMoveEval*` represents the evaluation after Stockfish's best move.

Both are retained because move loss/gain must be calculated relative to the best available continuation.

The mate fields are retained because a mate evaluation cannot be represented accurately by centipawns alone.

---

# 4. Move Evaluations

`MoveEvaluation` stores Stockfish's evaluation of moves from a position.

Move evaluations are global and are attached to `EngineAnalysis`.

Each record contains:

* `engineAnalysisId`
* `move`
* `evalCp`
* `evalMate`
* `evalLossFromBest`

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

## 4.1 Stored Evaluation Range

Stockfish should not evaluate every theoretically possible legal move from every position.

Instead, the analysis stores moves through a configurable maximum loss threshold.

The initial system threshold should be **1.0 pawn**.

For example, if the best move is evaluated at `+1.20`:

```text
Nf3    +1.20    loss 0.00
Bb5    +1.05    loss 0.15
d4     +0.60    loss 0.60
a3     +0.10    loss 1.10  ← not stored
```

This provides enough information to support different user preferences without storing an unnecessarily large number of moves.

The threshold is an engine-data retention threshold, not the user's definition of a mistake.

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

# 6. User Position Weakness

`UserPositionWeakness` stores the aggregated performance of one chess account in one position for one color.

The uniqueness constraint is:

```text
(chessAccountId, positionId, playerColor)
```

For example:

```text
Nathan
    │
    ├── Position A / WHITE
    ├── Position B / WHITE
    ├── Position C / BLACK
    └── Position D / BLACK
```

The aggregate contains:

* `id`
* `chessAccountId`
* `positionId`
* `playerColor`
* `timesReached`
* `mistakeCount`
* `mistakeRate`
* `averageLoss`
* `priority`
* `movesPlayed`
* `gameUrls`
* `updatedAt`

`timesReached` is stored directly in this table.

This makes the minimum-occurrence filter inexpensive and allows the table to be indexed for fast retrieval.

---

# 7. User-Specific Calculations

The aggregate stores the calculations that currently require scanning every `PositionOccurrence`.

## timesReached

Number of times the user reached the position while playing the specified color.

```text
timesReached = number of PositionOccurrence records
```

## mistakeCount

Number of the user's moves whose evaluation loss exceeds the user's configured mistake threshold.

The mistake threshold is **not hard-coded as a universal 0.8-pawn rule**.

Instead, the system should define a configurable analysis setting, initially with a default value such as:

```text
mistake threshold = 0.8 pawns
```

The important distinction is that `0.8` is a **default configuration**, not a fundamental definition of a mistake.

The threshold belongs to the weakness-analysis configuration and can be changed as the product's model of mistakes evolves.

Because changing the threshold requires rebuilding derived aggregates, the chosen threshold should be treated as an analysis-version/configuration rather than recalculated on every API request.

## mistakeRate

```text
mistakeRate = mistakeCount / timesReached
```

## averageLoss

Average evaluation loss across the user's mistakes.

## priority

Priority is precomputed and stored for each user-position-color combination.

The API should **not calculate priority while serving `/api/puzzles` or `/api/positions/weaknesses`**.

The priority calculation may incorporate:

* evaluation loss
* mistake frequency
* recency
* times reached

The exact formula is an implementation detail of the aggregation service and can be versioned if it changes later.

---

# 8. Why Priority Is Stored

The current implementation loads all occurrences into memory and calculates priority during every request.

For a user with hundreds of thousands of occurrences, this causes:

```text
GET /api/puzzles
        ↓
load 432,000+ occurrences
        ↓
group positions
        ↓
load engine analyses
        ↓
calculate mistakes
        ↓
calculate priority
        ↓
sort all weaknesses
        ↓
take 5
```

The new implementation should instead perform:

```text
GET /api/puzzles
        ↓
SELECT ...
FROM user_position_weakness
WHERE account = ?
  AND color = ?
  AND mistake_count >= ?
ORDER BY priority DESC
LIMIT 5
```

The database can therefore return the highest-priority weaknesses directly.

---

# 9. Puzzle and Weakness Retrieval

There should be one underlying weakness data model.

Puzzle generation and weakness browsing should not independently recalculate the same statistics.

The shared flow is:

```text
UserPositionWeakness
        │
        ├── Weakness view
        │
        └── Puzzle generation
              │
              └── EngineAnalysis
                    │
                    └── MoveEvaluation
```

A weakness identifies **where the player repeatedly struggles**.

A puzzle uses that weakness and the global engine data to determine **what the player should be asked to do**.

If the two endpoints eventually expose meaningfully different representations, they can remain separate API endpoints. They should nevertheless share the same underlying aggregation and analysis services.

---

# 10. Import and Analysis Pipeline

The current scheduled `EngineAnalysisJob` should be replaced with an explicit analysis step.

The import process already runs asynchronously, so Stockfish analysis does not need an independent polling scheduler.

The pipeline becomes:

```text
┌────────────────────────────┐
│ POST /api/games/import     │
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ Async Import Job           │
│                            │
│ Fetch games                │
│ Save Game records          │
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ GameParserService          │
│                            │
│ Parse PGNs                 │
│ Create Position records    │
│ Create PositionOccurrence  │
└─────────────┬──────────────┘
              │
              │ returns Set<UUID>
              │ of affected Position IDs
              ▼
┌────────────────────────────┐
│ EngineAnalysisOrchestrator │
│                            │
│ Find qualifying positions  │
│ Ensure global analysis     │
│ Evaluate missing moves     │
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ WeaknessAggregationService  │
│                            │
│ Aggregate occurrences      │
│ Calculate mistakes         │
│ Calculate priority         │
│ UPSERT UserPositionWeakness│
└─────────────┬──────────────┘
              │
              ▼
┌────────────────────────────┐
│ AsyncJob = COMPLETED       │
└────────────────────────────┘
```

The HTTP import request remains asynchronous and returns the job ID.

---

# 11. Parser Output

`GameParserService.parseAndSavePositions(games)` should return:

```kotlin
Set<UUID>
```

containing the IDs of all `Position` records affected by the newly imported games.

It does **not** return PGNs or FENs.

The PGNs already belong to `Game`.

The FENs already belong to `Position`.

The returned IDs are simply an efficient way for later stages to know which positions may need analysis or aggregation.

For example:

```text
Imported games
      ↓
PGN parsing
      ↓
Position P1
Position P2
Position P3
Position P1
Position P4
      ↓
Set<UUID>
{P1, P2, P3, P4}
```

---

# 12. Minimum Occurrence Analysis

Minimum occurrence is a criterion for deciding which positions deserve Stockfish analysis.

For example:

```text
minimum occurrences = 5
```

A position reached fewer than five times does not initially require personalized weakness analysis.

The occurrence count should be obtained from persisted occurrence/aggregate data rather than counting occurrences during the weakness API request.

A position can qualify later when a subsequent import increases its count from:

```text
4 → 5
```

or:

```text
5 → 10
```

The system therefore checks the **current total count in the database**, not merely the number of occurrences in the latest monthly import.

Monthly archive boundaries have no analytical significance.

---

# 13. Existing Engine Analysis

If a position already has an `EngineAnalysis` record, a later import must not blindly re-analyze it.

Instead, the orchestrator checks whether newly encountered historical moves are already represented by `MoveEvaluation`.

Example:

```text
Existing:
Position P
EngineAnalysis
    ├── Nf3
    └── Bb5

New import:
Position P
New historical move:
    └── d4
```

The orchestrator adds the missing `d4` evaluation rather than repeating the entire analysis unnecessarily.

The user's `UserPositionWeakness` aggregate is then recalculated for the affected position.

---

# 14. Failure and Recovery

Raw imported data remains the source of truth.

If Stockfish analysis fails:

* imported `Game` records remain persisted
* `Position` records remain persisted
* `PositionOccurrence` records remain persisted
* completed engine analyses remain persisted
* completed user aggregates remain persisted

The import job can be marked `FAILED` and retried.

A retry should identify:

* qualifying positions without engine analysis
* existing analyses missing newly encountered move evaluations
* affected user aggregates requiring recalculation

The process should be idempotent.

---

# 15. Rebuilding Derived Data

`PositionOccurrence` remains the authoritative historical record.

`EngineAnalysis` and `UserPositionWeakness` are derived data.

If the weakness formula changes, the derived user table can be rebuilt without re-importing games or rerunning Stockfish.

For example:

```text
PositionOccurrence
       +
EngineAnalysis
       │
       ▼
Rebuild UserPositionWeakness
```

This allows future changes to:

* priority formulas
* recency weighting
* mistake thresholds
* aggregation logic

without losing historical data.

---

# 16. Database Indexing

The primary retrieval index should support the common query:

```text
account + color + mistake threshold + priority
```

For example:

```sql
CREATE INDEX idx_user_position_weakness_query
ON user_position_weakness
    (chess_account_id, player_color, mistake_count, priority DESC);
```

The uniqueness constraint:

```text
(chess_account_id, position_id, player_color)
```

prevents duplicate aggregate records.

Additional indexes should be added based on actual query plans rather than preemptively indexing every column.

---

# 17. End-to-End Example

Nathan imports 500 new games.

### Step 1 — Import

The games are saved with:

```text
whitePlayer
blackPlayer
result
playedAt
timeControl
pgn
```

### Step 2 — Parse

The PGNs produce:

```text
Position P1
Position P2
Position P3
...
```

and corresponding `PositionOccurrence` records.

The parser returns:

```text
{P1, P2, P3, ...}
```

### Step 3 — Candidate detection

The system checks the current database totals.

```text
P1 → 17 occurrences
P2 → 6 occurrences
P3 → 2 occurrences
```

With a minimum occurrence threshold of 5:

```text
P1 → analyze
P2 → analyze
P3 → skip for now
```

### Step 4 — Global engine analysis

Suppose P1 produces:

```text
Best move: Nf3
Best evaluation: +1.20

Nf3    +1.20    loss 0.00
Bb5    +1.05    loss 0.15
d4     +0.60    loss 0.60
a3     +0.25    loss 0.95
```

These evaluations are stored globally.

### Step 5 — User aggregation

Nathan reached P1 as White 17 times.

Suppose:

```text
Mistakes: 6
Mistake rate: 35.3%
Average loss: 1.14
Priority: 8.72
```

These values are stored in:

```text
UserPositionWeakness
```

### Step 6 — Puzzle request

Nathan requests five puzzles.

The database can immediately return:

```text
ORDER BY priority DESC
LIMIT 5
```

The API does not need to scan the 17 occurrences or calculate priority again.

### Step 7 — Acceptable moves

Nathan has selected an acceptable threshold of `0.3`.

From the globally stored move evaluations:

```text
Nf3    loss 0.00
Bb5    loss 0.15
d4     loss 0.60
```

the puzzle considers:

```text
Nf3
Bb5
```

acceptable.

If Nathan later chooses `0.8`, the same engine data allows:

```text
Nf3
Bb5
d4
```

without another Stockfish analysis.

---

# 18. Resulting Architecture

The final architecture separates three responsibilities:

```text
RAW HISTORY
PositionOccurrence
        │
        │ "What actually happened?"
        ▼
ENGINE KNOWLEDGE
EngineAnalysis
MoveEvaluation
        │
        │ "What were the consequences of each move?"
        ▼
USER ANALYTICS
UserPositionWeakness
        │
        │ "Where does this player repeatedly struggle?"
        ▼
API
Puzzles / Weaknesses
```

The API becomes a read layer over precomputed analytical data rather than a place where large-scale chess analysis is performed.

Stockfish runs explicitly as part of the import/analysis pipeline rather than through a global scheduled polling job.
