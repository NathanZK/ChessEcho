# Combined Model-Based BFS Exploration with Constrained Monkey Testing

**Phase**: Phase 5 (Issue #716)  
**Parent Roadmap**: Phased Testing Architecture Roadmap (#710)  
**Status**: Adopted  

---

## 1. Overview & Objective

Phase 5 unifies model-based breadth-first search (BFS) state exploration (Phase 2, #713) with constrained stochastic monkey testing (Phase 4, #715). While pure BFS systematically traverses shallow structural transitions from `DISCOVERY` and pure monkey testing explores from initial idle states, real-world state corruption often occurs deep within lifecycle flows (e.g., retrying moves after mistakes, rapid actions during engine evaluation, or mode-switching into line continuation).

The combined runner (`frontend/journeys/model/combinedRunner.ts`) computes the shortest valid transition path to reach a high-risk abstract state using BFS, mounts and validates that state, and then hands off execution to a state-constrained PRNG monkey runner with accumulated context.

---

## 2. High-Risk Lifecycle Scenarios & Mappings

The system defines four canonical high-risk scenarios mapped to formal abstract states:

| Scenario Key | Target State | BFS Preamble Path | Risk Profile & Invariants Verified |
|---|---|---|---|
| `retry-after-wrong-move` | `INCORRECT` | `SELECT_PUZZLE` -> `SUBMIT_MISTAKE` | Verifies board rollback, piece selection clearing, and attempt counter incrementing across repeated retry cycles. |
| `continuation-loading` | `EXPLORING` | `SELECT_PUZZLE` -> `SUBMIT_OPTIMAL` -> `CONTINUE_EXPLORATION` | Exercises post-solve mode transition into interactive line exploration with custom branch moves. |
| `evaluation-pending` | `EVALUATING` | `SELECT_PUZZLE` -> `SUBMIT_OPTIMAL` (under deferred network gate) | Intercepts `/api/puzzles/evaluate-move*` to hold application in evaluation pending state without UI crashes. |
| `navigation-during-evaluation` | `PRACTICE_IDLE` | `SELECT_PUZZLE` -> `SUBMIT_OPTIMAL` (held) -> `BACK_TO_PUZZLES` | Tests race conditions when user navigates away while async engine evaluation is in flight. |

---

## 3. Architecture & Execution Contracts

```
[DISCOVERY] --(BFS Shortest Path)--> [Target State] --(Context Handoff)--> [Constrained Monkey Fuzzing]
                                           |                                           |
                                  Assert Invariants                           Assert Invariants
                                  (Every Step)                                (Every Step)
```

1. **Deterministic Path Resolution**: `findBfsPathToState` evaluates the state model's transition graph to produce the minimal deterministic preamble without hardcoded UI flows.
2. **Accumulated Context Handoff**: The context resulting from BFS execution (`puzzleLoaded`, `attemptCount`, mode) is passed directly to `runConstrainedMonkey`, maintaining monotonic step indexing (`stepOffset`).
3. **Continuous Invariant Oracles**: Rather than evaluating invariants only at terminal states, `assertInvariantOracles` runs after every individual action across both phases:
   - Oracle 1: Error silence (0 unhandled exceptions or console errors).
   - Oracle 2: Layout integrity (`<main>` container mounted).
   - Oracle 3: State consistency (feedback banners, active board controls, attempt counters).
4. **Structured Failure Diagnostics & Artifact Capture**: If an invariant is violated, the runner outputs a structured diagnostic record, full-page screenshot (`.png`), and Playwright trace archive (`.zip`).

---

## 4. Artifact & Regression Conversion

Failing combined runs yield actionable reproduction logs formatted as:
`[Step N] <fromState> --(<action>)--> <toState>`

These traces are converted directly into deterministic Playwright regression tests in `frontend/journeys/regression/combined-reproduction.spec.ts`, securing regression boundaries without ongoing stochastic fuzzer flakiness.
