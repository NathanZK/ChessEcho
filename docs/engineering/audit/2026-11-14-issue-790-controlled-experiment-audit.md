# Issue #790 Controlled Application-Level Fault-Detection Experiment Audit

**Date**: 2026-11-14  
**Author**: Antigravity  
**Target**: GitHub Issue #790  
**Classification**: Controlled Seeded-Fault Detection Evidence (Non-Historical)

---

## 1. Executive Summary

This audit records the controlled application-level fault-detection experiment performed on GitHub Issue #790 to verify that the general BFS explorer, live browser SUT adapter, DOM observation pipeline, and invariant evaluator possess independent defect-detection capability against the running production Next.js application without hardcoded test subsets, seeded action sequences, or mutation-specific invariant tailoring.

---

## 2. Pre-Mutation Inspection of State & Observation Pathways

1. **Reset Handler & React State Flow**: Located in `frontend/src/components/ChessBoardArea.tsx:611-626` (`handleReset`).
   In production baseline code, clicking Reset creates a fresh `Chess(initialFen)`, resets `fenHistory` to `[initialFen]`, sets `historyIndex = 0`, clears square selection and highlight states (`setMoveFromSquare(null)`, `setCustomSquareStyles({})`), updates the internal ref `currentBoardFenRef.current = initialFen`, and propagates upstream via `onFenChange?.(initialFen)` and `onReset?.()`. Upstream in `PracticePuzzlesView.tsx:1858`, `onFenChange` updates React state `currentBoardFen`.
2. **DOM Observation Path**: `PracticePuzzlesView.tsx:1813` renders `data-board-fen={currentBoardFen || activePuzzle?.fen}`.
   In `browserSutAdapter.ts:360`, `PlaywrightBrowserPracticeSutAdapter.observeState()` reads this live DOM attribute into `PracticeObservedState.boardFen`.
3. **BFS Generator**: `generalBfsExplorer.ts:16` explores valid model transitions from `INITIAL` up to depth 3, yielding 36 valid sequences (12 of which exercise `RESET`).
4. **Live Trace Minimizer**: `liveTraceMinimizer.ts:39` executes greedy 1-delta reduction directly against the live browser SUT (`adapter.reset()` -> execute candidate trace -> observe live DOM -> evaluate invariants).

---

## 3. The Controlled Application-Level Mutation

A state mutation was temporarily applied in `frontend/src/components/ChessBoardArea.tsx:611-626`:

```tsx
const handleReset = () => {
  // CONTROLLED MUTATION: Reset fails to restore initialFen; retains post-move board position in React state
  const movedFen = game.fen();
  currentBoardFenRef.current = movedFen;
  setGameHistory((prev) => [prev[0] ?? game]);
  setHistoryIndex(0);
  setMoveFromSquare(null);
  setCustomSquareStyles({});
  onFenChange?.(movedFen);
  onReset?.();
};
```

**Validity**:
- Mutates actual React component state and game engine state—not a DOM reflection hook or test flag.
- Because `movedFen` is a non-empty valid FEN string, the JSX `currentBoardFen || activePuzzle?.fen` does not fall back to `activePuzzle.fen`. The live board remains in the post-move state, directly reflecting the broken application state in both the UI and the DOM.

A clean production build was compiled:
```bash
npm --prefix frontend run build
```

---

## 4. Execution Against Mutated Application

The full deterministic BFS action suite was executed without any hardcoded test subsets or mutation-tailored assertions:

```bash
cd frontend && CI=true npx playwright test journeys/general-defect-detection.spec.ts
```

- **Sequences Generated**: 36 total BFS paths generated from FSM graph.
- **Sequences Executed**: 36 executed against live production server (`http://localhost:3000`).
- **Sequences Exercising `RESET`**: 12 paths exercised `RESET`.
- **Invariants Evaluated**: The 7 domain-general practice invariants (`GENERAL_PRACTICE_INVARIANTS`), including `G-INV-6: ReversibleReset`.

### Failure Evidence
The explorer uncovered **8 invariant violations**, all triggering `[G-INV-6: ReversibleReset]`:
1. `['MAKE_MOVE_CORRECT', 'RESET']` (length 2)
2. `['MAKE_MOVE_INCORRECT', 'RESET']` (length 2)
3. `['MAKE_MOVE_CORRECT', 'ENTER_LINE_EXPLORATION', 'RESET']` (length 3)
4. `['MAKE_MOVE_CORRECT', 'SOLVE', 'RESET']` (length 3)
5. `['MAKE_MOVE_INCORRECT', 'ENTER_LINE_EXPLORATION', 'RESET']` (length 3)
6. `['MAKE_MOVE_INCORRECT', 'RETRY', 'RESET']` (length 3)
7. `['RESET', 'MAKE_MOVE_CORRECT', 'RESET']` (length 3)
8. `['RESET', 'MAKE_MOVE_INCORRECT', 'RESET']` (length 3)

**Diagnostic Failure Output**:
```text
Invariant failed: [G-INV-6: ReversibleReset] Reset button must restore the board to the puzzle entry position
Expected FEN: r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3
Observed FEN: r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3
```

---

## 5. Trace Minimization & Deterministic Replay

- **Seed**: `42`
- **Original Longer Failing Trace (length 3)**:
  `['MAKE_MOVE_CORRECT', 'ENTER_LINE_EXPLORATION', 'RESET']`
- **Minimization Process**:
  `liveTraceMinimizer.minimizeTraceLive(adapter, trace, invariants, context)` sequentially dropped individual actions and re-executed against the live SUT.
  - When `ENTER_LINE_EXPLORATION` was removed, the candidate trace `['MAKE_MOVE_CORRECT', 'RESET']` reproduced the exact same `[G-INV-6: ReversibleReset]` invariant failure.
- **Minimized Failing Trace (length 2)**:
  `['MAKE_MOVE_CORRECT', 'RESET']`
- **Deterministic Replay Verification**:
  The minimized trace `['MAKE_MOVE_CORRECT', 'RESET']` was replayed directly against the mutated application:
  ```text
  1 passed (3.0s)
  Deterministic reproduction confirmed for [G-INV-6: ReversibleReset]
  ```

---

## 6. Clean Revert & Baseline Verification

1. **Revert**: The temporary mutation in `frontend/src/components/ChessBoardArea.tsx:611-626` was cleanly reverted.
2. **Production Rebuild**:
   ```bash
   npm --prefix frontend run build
   # Compiled successfully in 414ms
   ```
3. **Exploration Re-run**:
   The identical 36 BFS sequences were re-executed against the clean build:
   ```bash
   cd frontend && CI=true npx playwright test journeys/general-defect-detection.spec.ts
   ```
   **Output**:
   ```text
   Report result paths executed: 36
   Discovered defects: []
   ✓ 1 …ces against live application and verifies all general invariants (37.4s)
   1 passed (38.9s)
   ```
   The failure completely disappeared on clean baseline code.
4. **Test & Lint Suite Status**:
   - `npm --prefix frontend test`: 96 passed (96 test files), 1,121 passed (1,121 tests)
   - `npm --prefix frontend run lint`: 0 errors, 0 warnings
   - No temporary test files, hardcoded mutation scripts, or seed tests remain committed.

---

## 7. Explicit Classification

This result is strictly classified as:
> **Controlled seeded-fault detection evidence** — demonstrating that the general BFS explorer, live browser SUT adapter, DOM observation pipeline, and invariant evaluator can detect, isolate, and minimize an un-tailored application-level state fault in a running production build. This does **not** constitute historical defect discovery.
