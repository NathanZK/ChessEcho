# Practice State Model & Bounded BFS Exploration

This document formalizes the test-side abstract state model, bounded BFS exploration, and state invariant oracles introduced in Phase 2 (#713) for ChessProbe's Core Practice Puzzle Lifecycle.

---

### 1. Abstract States & Actions

The state model represents the core practice puzzle lifecycle without importing or coupling to internal React component state:

| Abstract State | Description | Invariant Oracle (DOM Assertion) |
|---|---|---|
| `DISCOVERY` | Puzzle catalog view | `Start Practice` button and catalog description visible |
| `PRACTICE_IDLE` | Interactive board baseline ready | Baseline square interactive (e.g. `d2`), `Back to Puzzles` button visible |
| `EVALUATING` | Engine move evaluation pending | Evaluating indicator visible; board input locked |
| `CORRECT` | Target move solved optimally | `Continue Exploration →` button visible; `Solved: 1` count verified |
| `INCORRECT` | Inaccurate move submitted | `Not the Recommended Move` banner; board rolled back to baseline FEN |
| `HISTORICAL_MISTAKE`| Matches user's historical blunder | Historical blunder coaching banner displayed; rollback to baseline FEN |
| `EVALUATION_ERROR` | Subprocess engine error / timeout | Evaluation error banner displayed; resubmission unlocked |
| `EXPLORING` | Free line exploration mode | Line Exploration header visible; `Exit` exploration button interactive |

**Actions**: `SELECT_PUZZLE`, `SUBMIT_OPTIMAL`, `SUBMIT_MISTAKE`, `SUBMIT_BLUNDER`, `SUBMIT_FAULT`, `RETRY`, `RESET`, `CONTINUE_EXPLORATION`, `EXIT_EXPLORATION`, `BACK_TO_PUZZLES`.

---

### 2. Transition Matrix & Guards

| From State | Action | Guard / Precondition | To State | Effect on Context |
|---|---|---|---|---|
| `DISCOVERY` | `SELECT_PUZZLE` | None | `PRACTICE_IDLE` | `puzzleLoaded = true, attemptCount = 0` |
| `PRACTICE_IDLE` | `SUBMIT_OPTIMAL` | `puzzleLoaded` | `CORRECT` | `attemptCount += 1` |
| `PRACTICE_IDLE` | `SUBMIT_MISTAKE` | `puzzleLoaded` | `INCORRECT` | `attemptCount += 1` |
| `PRACTICE_IDLE` | `SUBMIT_BLUNDER` | `puzzleLoaded` | `HISTORICAL_MISTAKE` | `attemptCount += 1` |
| `PRACTICE_IDLE` | `SUBMIT_FAULT` | `puzzleLoaded && faultInjectionEnabled` | `EVALUATION_ERROR` | Error banner displayed |
| `PRACTICE_IDLE` | `RESET` | `puzzleLoaded` | `PRACTICE_IDLE` | Baseline FEN restored |
| `PRACTICE_IDLE` | `BACK_TO_PUZZLES` | None | `DISCOVERY` | `puzzleLoaded = false` |
| `INCORRECT` | `RETRY` | `puzzleLoaded` | `PRACTICE_IDLE` | Baseline FEN restored |
| `HISTORICAL_MISTAKE` | `RETRY` | `puzzleLoaded` | `PRACTICE_IDLE` | Baseline FEN restored |
| `EVALUATION_ERROR` | `RETRY` | `puzzleLoaded` | `PRACTICE_IDLE` | Board unlocked for resubmission |
| `EVALUATION_ERROR` | `BACK_TO_PUZZLES` | None | `DISCOVERY` | `puzzleLoaded = false, inExploration = false` |
| `CORRECT` | `CONTINUE_EXPLORATION` | `puzzleLoaded` | `EXPLORING` | `inExploration = true` |
| `CORRECT` | `BACK_TO_PUZZLES` | None | `DISCOVERY` | `puzzleLoaded = false` |
| `EXPLORING` | `EXIT_EXPLORATION` | `inExploration` | `PRACTICE_IDLE` | `inExploration = false` |
| `EXPLORING` | `BACK_TO_PUZZLES` | None | `DISCOVERY` | `puzzleLoaded = false, inExploration = false` |

Forbidden transitions (e.g. `DISCOVERY` $\rightarrow$ `RETRY`, `PRACTICE_IDLE` $\rightarrow$ `CONTINUE_EXPLORATION`) are strictly rejected by `isValidTransition()`.

---

### 3. Bounded BFS Explorer & Path Selection

The bounded Breadth-First Search explorer (`bfsExplorer.ts`) traverses valid transitions from `DISCOVERY` up to a bounded depth (default depth 4). To prevent state space explosion and cyclic loops, branches track state-context signatures (`state:puzzleLoaded:inExploration`).

The heuristic path selector (`pathSelector.ts`) extracts three high-risk paths for browser execution:
1. **Evaluation Mistake & Retry (`path-01-eval-retry`)**: Probes mistake handling, automated rollback to baseline FEN, and board recovery.
2. **Mode Boundary Crossing (`path-02-mode-boundary-exploration`)**: Probes solving, entry into line exploration, continuation controls, and clean exit back to the puzzle baseline.
3. **Pending Navigation Concurrency (`path-03-pending-navigation`)**: Employs a deferred Playwright network gate (`page.route()`) holding `/api/puzzles/evaluate-move*` in-flight while the user navigates back to discovery, validating that `evaluationRequestIdRef` prevents state pollution or unhandled errors.

---

### 4. Out-of-Model Scope (Phase 2 Limitations)

The following capabilities are deliberately excluded from the Phase 2 abstract state model to prevent combinatorial state space explosion:
- **Blindfold Sessions**: Toggling board visibility (`useBlindfoldSession`) is modeled separately as a sub-mode; deferred to combined exploration in Phase 5 (#716).
- **Challenge Mode Branches**: Deep calculation tree branching and candidate sub-variations; deferred to Phase 4/5.
- **Timed Training Timers**: Countdown and stopwatch interval ticks; verified via isolated unit tests (`TimedTraining.test.tsx`).
- **Concrete State Space**: BFS explores an abstract discrete model; it does not claim exhaustive verification of all concrete React runtime memory states.

---

### 5. Architectural Traceability

- **Parent Tracking**: Phase 2 of 7 in testing architecture roadmap (#710).
- **Journey Registry**: Directly covers canonical journeys **J01** (Weaknesses Practice) and **J05** (Practice Puzzles).
- **Invariants**: Validates **INV-03** (Practice Mode State Isolation & Answer Secrecy) by asserting that exploration controls remain locked prior to solving and clean state separation is maintained on exit.
