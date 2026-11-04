# Cross-Cutting Invariants

This document formalizes the runtime invariants governing ChessProbe across all user journeys and interaction modes.

---

### INV-01: Session Isolation
- **Statement**: Guests and authenticated users operate under distinct authorization capabilities. Guest operations are keyed by username without an `AccountConnection`; authenticated operations require an active session and connection. Private training progress is strictly scoped to `(app_user_id, chess_account_id)`.
- **Scope & Applicability**: J06–J10, all authenticated API endpoints and Header components.
- **Failure Mode & Violation Impact**: Data leakage where private training history, personal Progress, or connected accounts are exposed across users or accessible without valid credentials.
- **Current Verification Method**: `OwnerScopedAccessIntegrationTest.kt`, `SessionAuthenticationFilterTest.kt`, `SessionCookieAuthIntegrationTest.kt`.

---

### INV-02: Evaluation Context Attribution
- **Statement**: Engine evaluations are shared across accounts keyed strictly by position identity (4-field FEN), but weakness ranking, mistake frequencies, and score rates are computed per account. Guest reads calculate statistics on the shared `ChessAccount` resolved by username.
- **Scope & Applicability**: J01, J06, J07, `EngineAnalysisOrchestrator`, `ObjectiveWeaknessAnalyzer`.
- **Failure Mode & Violation Impact**: Inverted mistake calculations or cross-account pollution where one player's blunder profile mutates another player's weakness ranking.
- **Current Verification Method**: `ObjectiveWeaknessAnalyzerTest.kt`, `WeaknessControllerIntegrationTest.kt`, `PracticalWeaknessControllerIntegrationTest.kt`.

---

### INV-03: Practice Mode State Isolation & Answer Secrecy
- **Statement**: Switching between Main Puzzle, Line Exploration (`CHESSECHO` / `BOTH_SIDES`), Challenge Mode, and Blindfold Mode must not leak event listeners, board orientation, or timers across modes. On puzzles with pre-defined target moves, engine continuation candidates and "Continue Exploration →" are strictly unavailable before the initial puzzle move is submitted (per #638 AC2). Supplied FEN/PGN positions (J04) have no pre-defined target and permit direct exploration.
- **Scope & Applicability**: J01–J05, `PracticePuzzlesView`, `PuzzleFeedbackPanel`, `ChessBoardArea`.
- **Failure Mode & Violation Impact**: Premature answer leakage exposing puzzle solutions before calculation; lingering keyboard listeners or timers causing unexpected board moves.
- **Current Verification Method**: `PuzzlesFeaturesAndFixes.test.tsx`, `SuppliedPositionExploration.test.tsx`, `BlindfoldContinuationFlow.test.tsx`.

---

### INV-04: Progress Scoping
- **Statement**: Personal Progress baseline intervals are derived from dated actual-game encounters and persisted `SOLVED` puzzle events scoped to the `(app_user_id, chess_account_id)` pair. Late game imports reconstruct historical intervals deterministically.
- **Scope & Applicability**: J01, J07, `ProgressController`, `ProgressService`.
- **Failure Mode & Violation Impact**: Progress chart corruption where training attempts from other users or unconnected accounts distort progress graphs.
- **Current Verification Method**: `ProgressControllerIntegrationTest.kt`, `ProgressServiceTest.kt`, `PositionProgressView.test.tsx`.

---

### INV-05: Double-Submit CSRF Enforcement
- **Statement**: State-changing endpoints (`POST`, `DELETE`) require `X-XSRF-TOKEN` matching `XSRF-TOKEN` cookie validated in constant time; safe reads (`GET`, `HEAD`) are exempt.
- **Scope & Applicability**: J06, J07, J09, J10, `CsrfEnforcementInterceptor`.
- **Failure Mode & Violation Impact**: Cross-site request forgery attacks triggering unauthorized game imports, account connections, or session terminations.
- **Current Verification Method**: `OwnerScopedCsrfIntegrationTest.kt`, `SessionCredentialsCsrf.test.ts`.

---

### INV-06: Dynamic Similarity Concurrency Semaphore
- **Statement**: Subprocess Stockfish sessions are bounded by a configurable semaphore (`chess.similarity.dynamic.max-concurrent-sessions` $\ge 1$) with fail-safe `try-finally` permit release preventing capacity lockouts.
- **Scope & Applicability**: J03, `PositionSimilarityService`, `SimilarityConcurrencyManager`.
- **Failure Mode & Violation Impact**: Host CPU starvation under concurrent requests, or permanent permit leaks hanging future similarity searches on engine anomalies.
- **Current Verification Method**: `SimilarityConcurrencyManagerTest.kt`, `SimilarPositionsControllerIntegrationTest.kt`.
