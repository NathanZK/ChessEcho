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

## Engine Continuation Quality Threshold (`max-eval-loss: 0.50`)

To prevent weak or non-plausible MultiPV candidate moves from being included as continuations, `EngineMoveProvider` applies a continuation quality filter based on evaluation loss relative to rank 1:
- **Configuration**:
  ```yaml
  engine:
    continuation:
      multi-pv: 5
      max-eval-loss: 0.50
  ```

### Architectural Rationale
- **Plausible Continuation vs User Mistake Threshold**: This is a continuation-specific threshold, distinct from the weakness or acceptable-move threshold used to judge user mistakes.
- **Purpose**: The goal is to provide the frontend/puzzle logic with a useful set of reasonable continuations rather than restricting candidates strictly to near-equal engine moves.
- **Default Value**: `0.50` pawns is the current default and is intentionally looser than thresholds used for judging user moves.
- **Backend Configuration**: The threshold remains backend configuration rather than a REST request parameter, ensuring clients cannot manipulate engine-analysis policy per request.
- **MultiPV Top-N & Eval-Loss Synergy**: MultiPV top-N (5) and evaluation-loss filtering (`0.50`) work in tandem. Stockfish first generates the top N candidates, and the `max-eval-loss` filter subsequently strips out candidates that fall too far behind rank 1.

### Filtering Rules
- Rank 1 candidate sets the baseline score (`bestCp`).
- Candidate evaluation loss is calculated: `evalLoss = max(0.0, (bestCp - candidateCp) / 100.0)`.
- Candidates are included if `evalLoss <= max-eval-loss` (using `<=` comparison). Candidates with `evalLoss` exactly at `0.50` are included.
- Rank 1 has `evalLoss = 0.00` and is always included.
- Ranking order from Stockfish is preserved.

---

## Component Responsibilities & Separation of Concerns

- **`ContinuationService`**: Orchestrates continuation request handling, tracks `requestedMode` vs `effectiveProvider`, executes `HUMAN` $\to$ `ENGINE` fallback policy, and preserves candidate collections.
- **`MoveProvider`**: Interface contract defining `providerType` and `getContinuationCandidates(fen: String): List<ContinuationCandidate>`.
- **`EngineMoveProvider`**: Implements `MoveProvider`. Performs MultiPV search and filters candidates using `max-eval-loss: 0.50`.
- **`HumanMoveProvider`**: Implements `MoveProvider`. Performs SHA-256 hash lookup of FEN and returns historical moves from `PositionOccurrence` with play counts. Returns an empty list when no historical occurrences exist.
- **Frontend & API (`ContinuationMode`)**: Exposes domain enum `ContinuationMode` (`ENGINE`, `HUMAN`), `requestedMode`, `effectiveProvider`, and candidate collections via REST endpoints (`GET /api/puzzles/continuation`).

---

## Explicitly Out of Scope
- **Opponent-Response Mechanic**: Interactive opponent response features will be built on top of this architecture in a future feature.
- **PR #49 Acceptable-Move / MultiPV Behavior**: The existing engine weakness analysis pipeline and MultiPV candidate scoring remain untouched.
