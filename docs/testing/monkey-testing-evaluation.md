# Monkey Testing Architecture Evaluation: Gremlins.js vs. State-Constrained Playwright Runner

**Phase**: Phase 4 (Issue #715)  
**Parent Roadmap**: Phased Testing Architecture Roadmap (#710)  
**Status**: Adopted (Custom State-Constrained Playwright Runner); Rejected (Gremlins.js as Primary Dependency)

---

## 1. Context & Objective

As part of Phase 4 of the ChessProbe testing architecture roadmap (#710, #715), this evaluation investigates tools and architectures for executing automated monkey testing against ChessProbe's interactive practice interfaces. The goal is to discover edge-case bugs emerging from unexpected interaction orderings (rapid clicks, mid-animation transitions, reset/retry interleaving) while ensuring strict reproducibility and zero noise.

---

## 2. Technical Evaluation of Gremlins.js

Gremlins.js is an established library for in-browser chaos testing that injects horde-based "gremlins" (clickers, touchers, form-fillers, scrollers) directly into the page.

### 2.1 Advantages
- **High throughput**: Dispatches hundreds of DOM events per second directly in the browser runtime without Playwright IPC overhead.
- **Surface fuzzing**: Rapidly exercises arbitrary DOM nodes to detect unhandled event bubble crashes.

### 2.2 Impediments & Failure Modes in ChessProbe
1. **Unconstrained Noise**:
   - Gremlins.js selects target DOM elements purely at random without awareness of application state.
   - In a chess application, clicking random coordinates hits SVG piece layers, coordinate borders, or canvas backgrounds. The vast majority of random clicks are completely inert, wasting test execution time without advancing application state.
2. **Inability to Formulate Domain Actions**:
   - Playing a chess move requires an ordered pair of clicks: legal origin square (`from`) followed by legal destination square (`to`). Unconstrained random clickers virtually never produce legal moves, leaving the application permanently stranded in initial idle states.
3. **Flaky & Non-Deterministic Reproduction**:
   - While Gremlins.js supports seeds, real-world browser event loops, requestAnimationFrame timing, and async React state updates mean that re-running the same seed frequently diverges into different interaction timings.
4. **Heavyweight Bundle Overhead**:
   - Integrating Gremlins.js introduces extra client-side build or runtime dependencies for capabilities that Playwright's native automation handles more cleanly.

---

## 3. Custom State-Constrained Playwright Runner Architecture

To avoid unconstrained noise and guarantee strict reproducibility, Phase 4 implemented a custom, state-aware monkey runner (`frontend/journeys/model/monkeyRunner.ts`) leveraging the Phase 2 abstract state machine (`frontend/journeys/model/practiceModel.ts`).

### 3.1 Architectural Pillars
1. **State-Model Action Sampling**:
   - Rather than selecting random pixels or DOM nodes, the monkey runner queries `PRACTICE_TRANSITIONS` for the application's current abstract state (e.g. `DISCOVERY`, `PRACTICE_IDLE`, `CORRECT`, `INCORRECT`, `EXPLORING`).
   - Only permitted user actions with satisfied guards are eligible for selection at any step.
2. **Deterministic Mulberry32 PRNG**:
   - Uses a minimal, zero-dependency 32-bit Mulberry32 algorithm (`frontend/journeys/model/prng.ts`).
   - Given an identical seed integer (e.g. `seed = 42`), the runner produces the exact same action sequence across independent test runs.
3. **Multi-Layered Invariant Oracles**:
   - **Oracle 1 (Error Silence)**: Intercepts `page.on('pageerror')` and console errors to catch unhandled JavaScript runtime exceptions.
   - **Oracle 2 (Layout Integrity)**: Asserts the root container (`main`) remains mounted and visible (guarding against white-screen unhandled error boundaries).
   - **Oracle 3 (State Consistency)**: Verifies that expected action controls and feedback banners match the current abstract state.
4. **Action Logging & Regression Conversion**:
   - Every step records `{ step, fromState, action, toState, timestamp }`.
   - On invariant failure, the runner captures full-page screenshots, dumps the formatted sequence trace, and provides immediate guidance for converting the failing seed into a permanent Playwright regression test (`frontend/journeys/regression/`).

---

## 4. Architectural Decision & Conclusion

| Criterion | Gremlins.js | Custom State-Constrained Runner |
|---|---|---|
| **Action Validity** | Random DOM / unconstrained | Strictly constrained by state model |
| **Domain Meaningfulness** | Low (random clicks on inert SVG) | High (executes real legal chess workflows) |
| **Determinism & Replay** | Fragile across browser ticks | 100% deterministic via Mulberry32 |
| **Regression Conversion** | Difficult to isolate minimal steps | Direct conversion from action log |
| **Toolchain Dependencies** | External package | Native Playwright + internal state model |

**Decision**:
- **Adopt** the custom state-constrained Playwright monkey runner (`frontend/journeys/model/monkeyRunner.ts`).
- **Reject** Gremlins.js as a primary dependency for ChessProbe.

---

## 5. Scope Boundaries & Intentionally Deferred Capabilities (#716 Handoff)

To prevent scope creep and maintain strict architectural boundaries in Phase 4 (#715), the following capabilities are explicitly documented and intentionally deferred to subsequent roadmap phases:

### 5.1 Transient `EVALUATING` and `EVALUATION_ERROR` States (Deferred to Phase 5 / #716)
- **Current State**: `EVALUATING` and `EVALUATION_ERROR` are formally defined in the abstract state enum and registered in `STATE_ORACLES` with executable DOM assertions. However, Phase 4 monkey execution models sequential user actions where HTTP mock responses settle before subsequent actions are dispatched.
- **Phase 5 (#716) Handoff**: Interleaving user clicks during mid-flight evaluations (concurrent actions while `isEvaluatingMove === true`) and injecting network aborts/timeouts to trigger `EVALUATION_ERROR` will be addressed under Phase 5's race condition and concurrency testing scope.

### 5.2 Arbitrary Move Sequences in `EXPLORING` State (Deferred to Phase 5 / #716)
- **Current State**: Phase 4 validates entering (`CONTINUE_EXPLORATION`), verifying the `EXPLORING` invariant oracle (Line Exploration header, Exit button visibility), and cleanly exiting (`EXIT_EXPLORATION`).
- **Phase 5 (#716) Handoff**: Generating arbitrary multi-ply move sequences from arbitrary board FENs requires integrating dynamic legal move enumeration and engine evaluation continuations. This is deferred to Phase 5 and Phase 6 full-stack testing, keeping Phase 4 focused on state-constrained lifecycle monkey exploration.

