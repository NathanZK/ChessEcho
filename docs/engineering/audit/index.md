# Backend Mutation Testing Audit Index

This document serves as the durable evidence repository for opt-in PIT mutation testing audits across ChessEcho's backend services, in accordance with [mutation-testing.md](../mutation-testing.md), parent QA strategy issue [#547](https://github.com/NathanZK/ChessEcho/issues/547), and tracker issue [#550](https://github.com/NathanZK/ChessEcho/issues/550).

---

## 1. Summary of Completed Audits

| Target Class | Test Suite | Scope | Mutants Generated | Killed | Survived | No Cov | Test Strength | Audit Date |
|---|---|---|---|---|---|---|---|---|
| `AdaptivePuzzleSchedulingPolicy` | `AdaptivePuzzleSchedulingPolicyTest` | Unit | 41 | 25 (61%) | 13 (32%) | 3 (7%) | 66% | 2026-10-17 |
| `GameImportController` | `GameImportControllerTest` | WebMvc Slice | 52 | 43 (83%) | 4 (8%) | 5 (9%) | 91% | 2026-10-17 |
| `ImportGamesRequest` | `GameImportControllerTest` | DTO / Validation | 41 | 18 (44%) | 2 (5%) | 21 (51%) | 90% | 2026-10-17 |
| `GameParserService` | `GameParserServiceTest` | Unit | 171 | 47 (27%) | 35 (20%) | 89 (52%) | 57% | 2026-10-17 |
| `GameImportService` | `GameImportServiceAttributionIntegrationTest` | Service Integration | 323 | 117 (36%) | 119 (37%) | 87 (27%) | 50% | 2026-10-17 |
| `AccountOwnershipService` | `AccountOwnershipServiceTest` | Service Unit/Integration | 74 | 47 (64%) | 8 (11%) | 19 (26%) | 85% | 2026-10-17 |
| `WeaknessPriorityPolicy` | `WeaknessPriorityPolicyTest` | Service Unit | 57 | 51 (89%) | 3 (5%) | 3 (5%) | 94% | 2026-10-17 |
| `GameOutcomeNormalizer` | `GameOutcomeNormalizerTest` | Service Unit | 46 | 45 (98%) | 1 (2%) | 0 (0%) | 98% | 2026-10-17 |
| `PgnHeaderTagReader` | `PgnHeaderTagReaderTest` | Service Unit | 102 | 85 (83%) | 15 (15%) | 2 (2%) | 85% | 2026-10-17 |
| `PracticalEvidenceService` | `PracticalEvidenceServiceTest` | Service Unit | 41 | 35 (85%) | 5 (12%) | 1 (2%) | 88% | 2026-10-17 |
| `WeaknessCalculationService` | `WeaknessCalculationServiceTest` | Service Unit | 245 | 179 (73%) | 51 (21%) | 15 (6%) | 78% | 2026-10-17 |

---

## 2. Detailed Class Audits

### 2.1 `com.chessecho.controller.GameImportController`

- **Test Suite**: `com.chessecho.controller.GameImportControllerTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.controller.GameImportController*" \
    -Ppitest.targetTests="com.chessecho.controller.GameImportControllerTest*"
  ```
- **Results**: 52 generated, 43 killed (83%), 4 survived, 5 no coverage; 91% test strength.
- **Meaningful Gaps Killed**:
  1. *Side-effect execution*: Line 59 (`VoidMethodCallMutator` in `importGames()`) removed `gameImportService.executeImportJob(job.id)`. Tested via explicit Mockito `verify(gameImportService).executeImportJob(job.id)`.
  2. *Configuration state mapping*: Line 126 (`NegateConditionalsMutator` in `toImportJobResponse()`) mutated `configurationState.takeIf { chessAccount != null }`. Verified absent for guest imports and exposed when `chessAccount` is attached.
  3. *Current-month boundary clamping*: Line 164 (`ConditionalsBoundaryMutator` in `derivedStatusFor()`) mutated `< currentMonth` to `<= currentMonth`. Killed by adding a stub archive matching `currentMonth` to verify boundary exclusion.
- **Accepted Surviving Mutants**:
  - 4 surviving mutants across lines 62, 107, and 158: synthetic Kotlin compiler bytecode null-checks (`Intrinsics.checkNotNull*`) on stdlib return values (`YearMonth.toString()`, `ResponseEntity.body()`) that cannot be null at runtime (Taxonomy Category 3).
- **Uncovered Branches**:
  - Line 155 (`EmptyObjectReturnValsMutator`): optional fallback when repository is null.
  - Lines 80, 84, 87 (x4) in `getJobStatus()`: optional `accountOwnershipService != null` block left null in WebMvc slice test.

### 2.2 `com.chessecho.dto.ImportGamesRequest`

- **Test Suite**: `com.chessecho.controller.GameImportControllerTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.dto.ImportGamesRequest*" \
    -Ppitest.targetTests="com.chessecho.controller.GameImportControllerTest*"
  ```
- **Results**: 41 generated, 18 killed (44%), 2 survived, 21 no coverage; 90% test strength.
- **Meaningful Gaps Killed**:
  - Date range ordering validation: In `hasOrderedDateRange()`, boolean return mutators (`BooleanTrueReturnValsMutator`: 3/3 killed; `BooleanFalseReturnValsMutator`: 2/2 killed) and condition negations (`NegateConditionalsMutator`: 12/12 covered mutants killed). Verified with `fromDate > toDate` returning HTTP 400 Bad Request and equal range `fromDate == toDate` returning HTTP 202 Accepted.
- **Accepted Surviving Mutants**:
  - 1 boundary condition mutation on `YearMonth.parse(fromDate) <= YearMonth.parse(toDate)` (`<=` to `<`) when tested through controller slice.
- **Uncovered Branches**:
  - Normalization helper methods (`canonicalTimeControls`, `normalizedPlatform`, `normalizedUsername`) unreferenced directly within slice test scope.

### 2.3 `com.chessecho.service.GameParserService`

- **Test Suite**: `com.chessecho.service.GameParserServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.GameParserService*" \
    -Ppitest.targetTests="com.chessecho.service.GameParserServiceTest*"
  ```
- **Results**: 171 generated, 47 killed (27%), 35 survived, 89 no coverage; 57% test strength.
- **Meaningful Gaps Killed**:
  - Return value of `parseAndSavePositionsInternal` at line 244 (`EmptyObjectReturnValsMutator`): mutation replacing returned position IDs with `emptySet()` killed by asserting exact set of returned UUIDs against saved position IDs across single-game, transposition, and castling-rights test cases.
  - Line 45 empty games check verified returning `emptySet()`.
- **Accepted Surviving Mutants**:
  - Inlined Kotlin standard library bytecode comparator branches (`Comparisons.kt`) in deterministic tie-breakers.
  - Secondary comparison branches in heuristic time parsing (`parseClockMs`).

### 2.4 `com.chessecho.service.GameImportService`

- **Test Suite**: `com.chessecho.integration.service.GameImportServiceAttributionIntegrationTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.GameImportService*" \
    -Ppitest.targetTests="com.chessecho.integration.service.GameImportServiceAttributionIntegrationTest*"
  ```
- **Results**: 323 generated, 117 killed (36%), 119 survived, 87 no coverage; 50% test strength.
- **Meaningful Gaps Killed**:
  - `analysisStatus` transitions: line 241 `setAnalysisStatus("ANALYZING")` intermediate transition killed; line 261 terminal `"COMPLETED"` / `"FAILED"` status update killed; line 271 exception handler `"FAILED"` update killed.
  - Position ID forwarding: verified that accumulated parser-derived position IDs are forwarded to `EngineAnalysisOrchestrator.analyzeAffectedPositions`.
  - Progress counters: `gamesImported`, `gamesSkipped`, `gamesProcessed`, `eligibleGamesSelected` asserted on final job and intermediate in-flight state.
- **Accepted Surviving Mutants**:
  - Redundant finalization field assignments (e.g. lines 237-240 re-setting counters that were already persisted inside the import loop by `persistImportProgress` in the single-archive test fixture).
  - Loop and worker lease timeouts (16 mutations timed out and counted as killed).
  - Unexercised crash recovery sweep branches (`sweepStaleImportJobs`).

### 2.5 `com.chessecho.service.AccountOwnershipService`

- **Test Suite**: `com.chessecho.service.AccountOwnershipServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.AccountOwnershipService*" \
    -Ppitest.targetTests="com.chessecho.service.AccountOwnershipServiceTest*"
  ```
- **Results**: 74 generated, 47 killed (64%), 8 survived, 19 no coverage; 85% test strength.
- **Meaningful Gaps Killed**:
  - Snapshot validation in `verifySnapshots()`: line 270 negated conditional (killed), line 272 platform mismatch check (killed), line 275 username mismatch check (killed).
  - Independently asserted: conflicting `platform` throws `AccountSelectionMismatchException`, conflicting `username` throws `AccountSelectionMismatchException`, and matching snapshots succeed without exception.
- **Accepted Surviving Mutants**:
  - Void method calls (e.g. `connectionRepository.flush()`) without observable return differences.

### 2.6 `com.chessecho.service.WeaknessPriorityPolicy` (PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))

- **Issue / PR**: Issue [#553](https://github.com/NathanZK/ChessEcho/issues/553), PR [#555](https://github.com/NathanZK/ChessEcho/pull/555)
- **Test Suite**: `com.chessecho.service.WeaknessPriorityPolicyTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.WeaknessPriorityPolicy*" \
    -Ppitest.targetTests="com.chessecho.service.WeaknessPriorityPolicyTest*"
  ```
- **Results**: 57 generated, 51 killed (89%), 3 survived (5%), 3 no coverage (5%); 94% test strength.
- **Meaningful Gaps Killed**:
  - *Constructor parameter validation*: Line 69 `properties.validate()` invocation removal (`VoidMethodCallMutator`) killed by asserting that invalid `PracticalEvidenceProperties` throws `IllegalArgumentException`.
  - Penalty multiplier and frequency attenuation mutators killed.
- **Accepted Surviving Mutants**:
  - 3 boundary condition mutations in damping/non-linear weighting formulas where evaluation at the boundary produces invariant outcomes.
- **Uncovered Branches**:
  - Defensive fallback return paths in internal getters.

### 2.7 `com.chessecho.service.GameOutcomeNormalizer` (PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))

- **Issue / PR**: Issue [#553](https://github.com/NathanZK/ChessEcho/issues/553), PR [#555](https://github.com/NathanZK/ChessEcho/pull/555)
- **Test Suite**: `com.chessecho.service.GameOutcomeNormalizerTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.GameOutcomeNormalizer*" \
    -Ppitest.targetTests="com.chessecho.service.GameOutcomeNormalizerTest*"
  ```
- **Results**: 46 generated, 45 killed (98%), 1 survived (2%), 0 no coverage (0%); 98% test strength.
- **Meaningful Gaps Killed**:
  - *White-side corroboration*: Line 136 condition negation (`authoritativeColor == "WHITE"`) killed by asserting White-side outcome corroboration against the authoritative result.
  - Normalization lookups and result enum mapping mutators killed.
- **Accepted Surviving Mutants**:
  - 1 diagnostic logging output mutant.
- **Uncovered Branches**: None.

### 2.8 `com.chessecho.service.PgnHeaderTagReader` (PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))

- **Issue / PR**: Issue [#553](https://github.com/NathanZK/ChessEcho/issues/553), PR [#555](https://github.com/NathanZK/ChessEcho/pull/555)
- **Test Suite**: `com.chessecho.service.PgnHeaderTagReaderTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.PgnHeaderTagReader*" \
    -Ppitest.targetTests="com.chessecho.service.PgnHeaderTagReaderTest*"
  ```
- **Results**: 102 generated, 85 killed (83%), 15 survived (15%), 2 no coverage (2%); 85% test strength.
- **Meaningful Gaps Killed**:
  - *Minimal tag boundary*: Line 108 boundary mutant killed by asserting that minimal 6-character tag pair `[A ""]` is parsed with status `OK`.
  - Header tag key/value tokenization and escape handling mutators killed.
- **Accepted Surviving Mutants**:
  - Internal string parsing buffer sizing and capacity increment mutators.
- **Uncovered Branches**:
  - Auxiliary stream reset error paths.

### 2.9 `com.chessecho.service.PracticalEvidenceService` (PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))

- **Issue / PR**: Issue [#553](https://github.com/NathanZK/ChessEcho/issues/553), PR [#555](https://github.com/NathanZK/ChessEcho/pull/555)
- **Test Suite**: `com.chessecho.service.PracticalEvidenceServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.PracticalEvidenceService*" \
    -Ppitest.targetTests="com.chessecho.service.PracticalEvidenceServiceTest*"
  ```
- **Results**: 41 generated, 35 killed (85%), 5 survived (12%), 1 no coverage (2%); 88% test strength.
- **Meaningful Gaps Killed**:
  - *Multi-draw score rate arithmetic*: Line 132 `(wins + 0.5 * draws)` multi-draw score rate calculation mutators killed by asserting exact fractional values across multiple draws (`draws = 2`, `draws = 3`).
  - Minimum occurrence threshold and confidence scaling mutators killed.
- **Accepted Surviving Mutants**:
  - Defensive fallback returns on empty datasets and unreached diagnostic log paths.
- **Uncovered Branches**:
  - Null-safety checks on optional parameters.

### 2.10 `com.chessecho.service.WeaknessCalculationService` (including `WeaknessEvaluationLoss`) (PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))

- **Issue / PR**: Issue [#553](https://github.com/NathanZK/ChessEcho/issues/553), PR [#555](https://github.com/NathanZK/ChessEcho/pull/555)
- **Test Suite**: `com.chessecho.service.WeaknessCalculationServiceTest`, `com.chessecho.service.WeaknessEvaluationLossKtTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.WeaknessCalculationService*,com.chessecho.service.WeaknessEvaluationLoss*" \
    -Ppitest.targetTests="com.chessecho.service.WeaknessCalculationServiceTest*,com.chessecho.service.WeaknessEvaluationLossKtTest*"
  ```
- **Results**: 245 generated, 179 killed (73%), 51 survived (21%), 15 no coverage (6%); 78% test strength.
- **Meaningful Gaps Killed**:
  - *Average loss & rate arithmetic*: Line 188/269 `averageLoss` arithmetic mutators killed by asserting concrete non-zero multi-occurrence average; Line 230 `rawRate < 1.0` objective priority arithmetic mutators killed.
  - *Evaluation loss handling*: Precomputed loss, fallback math, null centipawns, equal centipawns (zero loss), and clamped negative loss branches in `WeaknessEvaluationLoss.kt` verified.
  - *Phase classification*: 4-rule precedence (corrupt FEN -> OPENING, `mmCount <= 6` -> ENDGAME, middlegame conditions -> MIDDLEGAME, default -> OPENING) verified across full 8-criteria matrix and corrupt FEN fallback with concrete `PhaseStats` calculations.
  - *Authenticated adaptive scheduling*: Scoping `(principal.appUserId, account.id, positionIds, color)`, event mapping, delegation, sort order (`scheduledScore DESC`, `positionId.toString() ASC`), zero-history parity, and null fallbacks verified.
  - *Opening context consensus*: Consensus matching tags -> populated, mismatching tags -> null, missing tags -> null verified.
- **Accepted Surviving Mutants**:
  - Defensive null-checks on optional collaborators and logging branches.
  - Forced-mate evaluation loss calculations (explicitly isolated and deferred to Issue #554).
- **Uncovered Branches**:
  - Optional fallback paths when auxiliary repositories are null.

---

## 3. Candidate Classes Not Yet Assessed

In accordance with [mutation-testing.md](../mutation-testing.md) and QA Strategy [#547](https://github.com/NathanZK/ChessEcho/issues/547), the following pure domain calculation units remain candidates for future focused audits:

- [x] `com.chessecho.service.WeaknessCalculationService` (`WeaknessCalculationServiceTest`) — Account-scoped weakness prioritization and statistical ranking formulas. (Audited in [#553](https://github.com/NathanZK/ChessEcho/issues/553) / PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))
- [ ] `com.chessecho.service.ProgressService` (`ProgressServiceTest`) — Historical interval baseline construction and progress delta calculations.
- [ ] `com.chessecho.service.EvaluationEvidenceProducerService` (`EvaluationEvidenceProducerServiceTest`) — Deterministic evaluation snapshot producer and verification rules.
- [x] `com.chessecho.service.PracticalEvidenceService` (`PracticalEvidenceServiceTest`) — Practical weakness score calculations and confidence thresholds. (Audited in [#553](https://github.com/NathanZK/ChessEcho/issues/553) / PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))
