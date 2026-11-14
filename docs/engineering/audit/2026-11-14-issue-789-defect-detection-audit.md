# Defect-Detection Audit: Issue #789

**Audited Issue**: [#789 `test(infra): implement bounded state-machine BFS explorer with trace minimization, deterministic concurrency harness, and reproducible seeding`](https://github.com/NathanZK/ChessEcho/issues/789)  
**Date**: 2026-11-14  
**Audit Scope**: Post-implementation audit of newly discovered defects, historical defect detection veracity, harness capability boundaries, and unresolved defect taxonomy.  
**Execution Boundary**: In-process model testing (Vitest) and backend virtual-thread concurrency (JUnit 5). Browser DOM execution is scheduled for Milestone 3 (#790).

---

## 1. Newly Discovered Defects

During the implementation and testing of Issue #789, defects and failures were categorized across genuine application concurrency, test-harness configuration, model-minimizer logic, and environment workarounds:

### 1.1 Genuine Application Defect: Cancellation vs. Worker Completion Race & Concurrent Trigger Race (#765)
* **Classification**: **Genuine Application Defect** (Discovered and fixed during #789).
* **Location**: [`backend/src/main/kotlin/com/chessecho/service/weakness/RecurringWeaknessDiscoveryService.kt`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/backend/src/main/kotlin/com/chessecho/service/weakness/RecurringWeaknessDiscoveryService.kt)
* **Pre-Fix Failure**:
  1. A client initiates discovery via `triggerDiscovery(accountId, threshold)`.
  2. While the asynchronous worker computes weakness clusters and prepares to call `completeJob(jobId, weaknesses)`, the client submits a cancellation request via `cancelDiscovery(jobId)`.
  3. `cancelDiscovery` sets the status to `CANCELLED`. However, `completeJob` lacked mutual exclusion and terminal-state checks; it executed concurrently, overwriting `jobState` back to `COMPLETED`, setting `stage = COMPLETE`, and persisting `cachedWeaknesses[accountId] = results` via `weaknessRepository.saveAll(...)`.
  4. Secondary Race: Multiple concurrent calls to `triggerDiscovery` lacked mutual exclusion on the check-then-act transition (`currentJob == null || isTerminal()`), allowing duplicate background worker threads to launch for the same account.
* **Root Cause**: Non-synchronized mutable state transitions in `RecurringWeaknessDiscoveryService` and the absence of a terminal-status check in `completeJob`.
* **Synchronization Correction**:
  * Added `synchronized(jobState)` blocks around all status transitions (`completeJob`, `cancelDiscovery`, `failJob`, `transition`).
  * Enforced terminal state immutability in `completeJob`: `if (jobState.status.isTerminal()) return`. Once marked `CANCELLED` or `FAILED`, worker results are discarded and no database writes occur.
  * Added mutex synchronization in `triggerDiscovery` using `activeAccountJobs.putIfAbsent(account.id, job.jobId)` to enforce a single active discovery job per account.
* **Post-Fix Verification Evidence**:
  * Test File: [`backend/src/test/kotlin/com/chessecho/concurrency/RecurringWeaknessDiscoveryConcurrencyTest.kt`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/backend/src/test/kotlin/com/chessecho/concurrency/RecurringWeaknessDiscoveryConcurrencyTest.kt)
  * Tests:
    * `concurrent cancellation and completion must enforce terminal state immutability`: Asserts `assertEquals(DiscoveryJobStatus.CANCELLED, finalJob.status)` and `verify(weaknessRepository, never()).saveAll(any())`.
    * `concurrent trigger discovery requests should not execute duplicate concurrent jobs`: Asserts exactly one job starts and concurrent triggers reject with `IllegalStateException`.
  * Execution: `./gradlew test --tests "com.chessecho.concurrency.RecurringWeaknessDiscoveryConcurrencyTest"` -> 2/2 tests passed (BUILD SUCCESSFUL).

---

### 1.2 Test Harness / Tooling Defect: Trace Minimizer False Neutrality on Pruning
* **Classification**: **Test Harness / Tooling Defect** (Corrected during #789).
* **Location**: [`frontend/journeys/model/bfsExplorer.test.ts`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/frontend/journeys/model/bfsExplorer.test.ts)
* **Pre-Fix Failure**: In `bfsExplorer.test.ts`, the trace minimization test initialized `inMemoryAdapter` with `initialContext` pre-seeded with `puzzleId: 'puzzle-regress'`. During 1-delta reduction on trace `['SELECT_PUZZLE', 'ENTER_EXPLORATION', 'MAKE_EXPLORATION_MOVE', 'EXIT_EXPLORATION']`, pruning `SELECT_PUZZLE` still triggered the exit invariant regression because the context already had a non-null `puzzleId`. Consequently, `SELECT_PUZZLE` was pruned as neutral, causing `expect(minTrace.actions).toContain('SELECT_PUZZLE')` to fail.
* **Root Cause**: Flawed initial test fixture context bypassing the causal prerequisite for downstream invariant violation.
* **Correction**: Initialized `initialContext` with `puzzleId: null`, making `SELECT_PUZZLE` indispensable for reaching the regression condition.

---

### 1.3 Test Harness / Tooling Defect: Vitest / Playwright Journey Include Pattern Collision
* **Classification**: **Test Harness / Tooling Defect** (Corrected during #789).
* **Location**: [`frontend/vitest.config.mts`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/frontend/vitest.config.mts)
* **Pre-Fix Failure**: Running `npm run test` failed with `Error: Playwright Test did not expect test.describe() to be called here`.
* **Root Cause**: `vitest.config.mts` had an include pattern of `'journeys/**/*.{test,spec}.{ts,tsx}'`, causing Vitest to ingest Playwright browser journey specs (`*.spec.ts`).
* **Correction**: Restricted Vitest to `'journeys/**/*.test.{ts,tsx}'` and configured `playwright.config.ts` with `testMatch: /.*\.spec\.ts$/`.

---

### 1.4 Test-Environment Concurrency Quirk: In-Memory H2 Root Position Hash Collision
* **Classification**: **Test-Environment Workaround**.
* **Location**: [`backend/src/test/kotlin/com/chessecho/concurrency/ParallelGameImportConcurrencyTest.kt`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/backend/src/test/kotlin/com/chessecho/concurrency/ParallelGameImportConcurrencyTest.kt)
* **Pre-Fix Failure**: Parallel game imports across virtual threads failed on an unseeded in-memory H2 database with `org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation: public.CONSTRAINT_INDEX_2C ON public.position(hash)`.
* **Nature of Correction**: **Test-Environment Workaround**. The initial board position (`rnbqkbnr/...`) was pre-seeded in the test setup fixture before dispatching concurrent worker threads.
* **Evidence Availability Boundary**: While hypothesized that PostgreSQL dialect handles concurrent duplicate inserts differently, comparative PostgreSQL concurrency execution logs were not gathered in this session. Pre-seeding the initial root position is classified strictly as a **test-environment workaround** to eliminate index contention in H2.

---

### 1.5 Unresolved Application Defects Left for Milestone 3 (#790)

1. **UOBS-13 (Exploration Exit Context Loss)**:
   * **Location**: [`frontend/src/features/puzzles/PracticePuzzlesView.tsx:605-616`](file:///Users/nathankebede/AntigravityProfiles/third/.gemini/antigravity/worktrees/ChessEcho/implement_issue_789_praxis/frontend/src/features/puzzles/PracticePuzzlesView.tsx#L605-L616)
   * **Application Defect**: Exiting exploration or challenge mode invokes `setActivePuzzle(null)` and switches active view away from practice solving.
   * **Status**: **Unresolved in Application Code**. Issue #789 created the abstract model invariant and verified it against an in-memory mock adapter (`InMemoryAdapter`). Live application code in `PracticePuzzlesView.tsx` was not modified and remains defective.
2. **ADD-01 (Square Selection Persistence Across Resets)**:
   * **Status**: **Unresolved / Unverified Application Defect**. Requires real DOM rendering via Playwright to detect.
3. **UOBS-17 (Board History Desync on Undo/Redo)**:
   * **Status**: **Unresolved / Unverified Application Defect**. Requires browser-level interaction with React component state and `chess.js` history.
4. **#782 (Same-FEN Puzzle Re-render Failure)**:
   * **Status**: **Unresolved / Unverified Application Defect**. React reconciliation keying issue requiring live DOM mounting.

---

## 2. Historical Defect Detection Audit

| Historical Defect | Detection Classification | Supporting Evidence | Application Status | Remaining Work |
| :--- | :--- | :--- | :--- | :--- |
| **#765**<br>Discovery Race: Cancellation vs. Completion | **Genuine application defect discovered and fixed during #789** | `RecurringWeaknessDiscoveryConcurrencyTest.kt`: `concurrent cancellation and completion must enforce terminal state immutability`. Enforces `CANCELLED` status immutability. | **Fixed** in `RecurringWeaknessDiscoveryService.kt` via synchronized monitors and atomic checks. | None; verified green under concurrency harness. |
| **ADD-13**<br>Stockfish Semaphore Permit Leak Under Engine Timeout | **Verified by new regression coverage; historical discovery not established** | `SimilarityConcurrencyManagerConcurrencyTest.kt`: `permits are released in finally block under simulated engine timeouts (ADD-13)`. 16 concurrent virtual threads verify `availablePermits() == 3`. | **Verified Robust** in `SimilarityConcurrencyManager.kt` via existing `finally` release block. Pre-fix leak not demonstrated. | None; regression coverage active. |
| **UOBS-13**<br>Exploration / Challenge Exit Puzzle Context Loss | **Represented by model invariant; application defect remains unresolved and unverified in live UI** | `assertExplorationExitInvariant` in `invariantEvaluator.ts`; passing in `bfsExplorer.test.ts` via synthetic mutation `simulateExitRegression()` on `InMemoryAdapter`. | **Present / Unresolved** in `PracticePuzzlesView.tsx:605-616` (`activePuzzle = null`). | Implement Playwright `BrowserSutAdapter` in #790 and fix `PracticePuzzlesView.tsx`. |
| **ADD-01**<br>Square Selection Persistence Across Resets | **Unresolved / unverified application defect; requires browser SUT adapter** | None (in-process tests do not assert DOM class or SVG selection state). | **Present / Unresolved** in UI board component. | Implement Playwright `BrowserSutAdapter` in #790. |
| **UOBS-17**<br>Board History Desync on Move -> Undo -> Redo | **Unresolved / unverified application defect; requires browser SUT adapter** | `MOVE_TREE` transitions modeled in `modelFsm.ts`, but not run against React component state. | **Present / Unresolved** in UI component. | Wire `BrowserSutAdapter` to execute board moves in browser (#790). |
| **#782**<br>Same-FEN Puzzle Re-render Failure | **Unresolved / unverified application defect; requires browser SUT adapter** | None (React component reconciliation bug). | **Present / Unresolved** in UI component. | Add puzzle switching spec in Playwright browser harness (#790). |

---

## 3. Detection Capability and Limitations

### 3.1 Demonstrated Capabilities
* **State-Space BFS Permutation**: Explores non-linear action permutations within configured bounds ($d \le 6, N \le 100$) using canonical hashing for cycle detection.
* **Deterministic Trace Minimization**: 1-delta greedy reduction isolates 1-minimal failing sub-traces from complex failure sequences.
* **Deterministic Replay**: Seed logging (`--seed=<int>`) enables deterministic reproduction of randomized action walks.
* **Multi-Threaded Concurrency Verification**: `CountDownLatch`-coordinated harness over virtual threads reliably surfaces race conditions without arbitrary sleep delays.

### 3.2 Concrete Boundaries & Limitations
* **Absence of Live DOM Execution**: Vitest tests execute strictly against `InMemoryAdapter`. In-process model tests **cannot and do not claim** detection of DOM element persistence, CSS display anomalies, React reconciliation bugs, or browser URL routing changes.
* **Browser Adapter Stubbing**: `BrowserSutAdapter` in #789 serves as an interface specification and throws `Error('Not implemented: Requires Playwright Page')`. Real browser execution is intentionally deferred to Milestone 3 (#790).

---

## 4. Audit Conclusions

1. **Genuine New Application Defects Discovered**:
   * **1 Genuine Concurrency Defect (#765)**: Discovery cancellation vs worker completion race condition in `RecurringWeaknessDiscoveryService.kt`, successfully detected, reproduced, and fixed under synchronized monitors.
2. **Historical Defects and Detection Veracity**:
   * **ADD-13**: Reclassified as `Verified by new regression coverage; historical discovery not established`. Pre-fix execution evidence of an active permit leak is unavailable; the harness proves the invariant holds under 16 concurrent virtual threads against the current implementation.
   * **#765**: Genuine application defect discovered and fixed during #789.
3. **Historical Defects Confirmed Fixed in Application**:
   * **#765**: Confirmed fixed in `RecurringWeaknessDiscoveryService.kt`.
4. **Historical Defects Still Present or Unresolved**:
   * **UOBS-13, ADD-01, UOBS-17, #782**: All four remain unverified against the real UI and unresolved in application source code. Model-level coverage in `InMemoryAdapter` does not constitute application-level detection.
5. **Detection Gaps Carried into Milestone 3 (#790)**:
   * Concrete implementation of `BrowserSutAdapter` driving a real Playwright `Page`.
   * Execution of the BFS explorer against the running Next.js application to expose and remediate UOBS-13, ADD-01, UOBS-17, and #782 in the live frontend codebase.
