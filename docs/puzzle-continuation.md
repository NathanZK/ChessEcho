# ChessEcho Puzzle Continuation Architecture

## Purpose
ChessEcho allows continuing a puzzle position after the user executes a move or requests the continuation of a line.
Because continuation moves can come from multiple sources (engine calculation or actual historical human games), the continuation source architecture is decoupled and replaceable via a provider pattern.

## Architecture Diagram

```
                 ┌──────────────────┐
                 │ PuzzleController │
                 └────────┬─────────┘
                          │ (ContinuationMode)
                          ▼
              ┌──────────────────────┐
              │ ContinuationService  │ (Orchestrator & Fallback Owner)
              └──────────┬───────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │     MoveProvider     │ (Interface Contract)
              └──────────┬───────────┘
                         │
      ┌──────────────────┴──────────────────┐
      ▼                                     ▼
┌──────────────────────────┐   ┌──────────────────────────┐
│   EngineMoveProvider     │   │    HumanMoveProvider     │
│  (Stockfish MultiPV > 1) │   │ (Historical DB Lookups)  │
└──────────────────────────┘   └──────────────────────────┘
```

---

## Candidate Collection Architecture

A `MoveProvider` does **NOT** return a single move or force rank 1 selection. Instead, it returns a **collection of continuation candidates** (`List<ContinuationCandidate>`):
- **`EngineMoveProvider`**: Uses Stockfish MultiPV search (`MultiPV > 1`, default 5) and filters candidates using a continuation quality threshold (`max-eval-loss: 0.50`). Only moves within `0.50` pawns of rank 1 are returned.
- **`HumanMoveProvider`**: Returns multiple historically played moves for the position from `PositionOccurrence` data along with play frequencies (`timesPlayed`).
- **`ContinuationService`**: Orchestrates provider invocation and fallback without collapsing or arbitrarily reducing the candidate list to a single move. Downstream consumers/puzzles choose how to use candidate collections.

---

## Explicit Provider Tracking (`requestedMode` vs `effectiveProvider`)

The continuation response explicitly distinguishes between the mode requested by the caller and the provider that actually produced the candidate moves:
- **`requestedMode`**: The continuation mode requested (`ENGINE` or `HUMAN`).
- **`effectiveProvider`**: The provider that generated the returned candidate list (`"ENGINE"` or `"HUMAN"`).

### Mode Selection & Fallback Behavior
1. **ENGINE Mode**:
   - Caller requests `mode = ENGINE`.
   - `ContinuationService` delegates directly to `EngineMoveProvider`.
   - Response: `requestedMode: "ENGINE"`, `effectiveProvider: "ENGINE"`.

2. **HUMAN Mode (Historical Data Available)**:
   - Caller requests `mode = HUMAN`.
   - `ContinuationService` invokes `HumanMoveProvider`.
   - `HumanMoveProvider` finds historical occurrences in `PositionOccurrence`.
   - Response: `requestedMode: "HUMAN"`, `effectiveProvider: "HUMAN"`.

3. **HUMAN Mode Fallback (No Historical Data)**:
   - Caller requests `mode = HUMAN`.
   - `ContinuationService` invokes `HumanMoveProvider`, which returns an empty collection (`emptyList()`).
   - `ContinuationService` logs fallback and invokes `EngineMoveProvider`.
   - Response: `requestedMode: "HUMAN"`, `effectiveProvider: "ENGINE"`.

> **Important Invariant**: `HumanMoveProvider` NEVER directly invokes `EngineMoveProvider` or `StockfishService`. The fallback behavior and `effectiveProvider` determination are owned entirely by `ContinuationService`.

---

## Engine Configuration & Threshold Separation

ChessEcho explicitly separates candidate discovery for engine responses from user move evaluation during interactive line exploration. Both mechanisms use independent, configurable thresholds:

```yaml
engine:
  continuation:
    multi-pv: ${ENGINE_CONTINUATION_MULTI_PV:5}
    max-eval-loss: ${ENGINE_CONTINUATION_MAX_EVAL_LOSS:0.50}
  exploration:
    max-eval-loss: ${ENGINE_EXPLORATION_MAX_EVAL_LOSS:0.80}
```

### 1. Continuation Candidate Discovery (`continuation.max-eval-loss: 0.50`)
> **Question Answered**: *"Which moves may ChessEcho play?"*
- Uses Stockfish MultiPV search (default `multi-pv: 5`).
- Filters engine response candidate moves relative to rank 1 using `max-eval-loss: 0.50`.
- Returns a list of plausible moves for ChessEcho to respond with.

### 2. User Move Evaluation (`exploration.max-eval-loss: 0.80`)
> **Question Answered**: *"How much evaluation did the user's move lose, and is that loss acceptable?"*
- Evaluates the **exact** move attempted by the user from an exploration position FEN via `GET /api/puzzles/evaluate-move`.
- **Does NOT require the user's move to be present in MultiPV=5**. If the move is rank 6, 10, etc., Stockfish performs a targeted single-move evaluation for that exact move.
- Calculates eval loss relative to the best engine move for that position:
  $$\text{evalLoss} = \max\left(0.0, \frac{\text{bestEvalCp} - \text{userEvalCp}}{100.0}\right)$$
- If `evalLoss <= 0.80` (using `<=` comparison), the move is accepted.
- If `evalLoss > 0.80`, the move is rejected, the board remains at the pre-move position, and feedback is displayed. No continuation is requested.

### Threshold Independence & Server Configuration
- `continuation.max-eval-loss` (0.50) controls which candidate moves ChessEcho may play as an opponent.
- `exploration.max-eval-loss` (0.80) controls how much evaluation loss we allow from the user during interactive exploration.
- The user exploration threshold is server-configured (`engine.exploration.max-eval-loss`) and intentionally **not** accepted as a query parameter so clients cannot override evaluation policy.
- The evaluation response explicitly returns `maxEvalLoss` (the configured threshold applied) alongside `evalLoss` and `acceptable` for full client transparency.

---

## Component Responsibilities & Separation of Concerns

- **`ContinuationService`**: Orchestrates continuation request handling, tracks `requestedMode` vs `effectiveProvider`, executes `HUMAN` $\to$ `ENGINE` fallback policy, and preserves candidate collections.
- **`MoveEvaluationService`**: Evaluates arbitrary user moves against position FENs, computes evaluation loss relative to the engine baseline, and determines user move acceptability against `exploration.max-eval-loss`.
- **`EngineMoveProvider`**: Encapsulates engine-based MultiPV candidate discovery and applies continuation-specific quality filtering (`continuation.max-eval-loss`).
- **`HumanMoveProvider`**: Encapsulates historical human-move discovery from database occurrences.
- **`StockfishService`**: The single, unified Stockfish engine integration for both MultiPV candidate discovery and single-move analysis.
- **Frontend & API (`ContinuationMode`)**: Exposes domain enum `ContinuationMode` (`ENGINE`, `HUMAN`), `requestedMode`, `effectiveProvider`, and candidate collections via REST endpoints (`GET /api/puzzles/continuation`).

---

## Explicitly Out of Scope
- **Opponent-Response Mechanic**: Interactive opponent response features will be built on top of this architecture in a future feature.
- **PR #49 Acceptable-Move / MultiPV Behavior**: The existing engine weakness analysis pipeline and MultiPV candidate scoring remain untouched.
