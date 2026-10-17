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

---

## 3. Candidate Classes Not Yet Assessed

In accordance with [mutation-testing.md](../mutation-testing.md) and QA Strategy [#547](https://github.com/NathanZK/ChessEcho/issues/547), the following pure domain calculation units remain candidates for future focused audits:

- [ ] `com.chessecho.service.WeaknessCalculationService` (`WeaknessCalculationServiceTest`) — Account-scoped weakness prioritization and statistical ranking formulas.
- [ ] `com.chessecho.service.ProgressService` (`ProgressServiceTest`) — Historical interval baseline construction and progress delta calculations.
- [ ] `com.chessecho.service.EvaluationEvidenceProducerService` (`EvaluationEvidenceProducerServiceTest`) — Deterministic evaluation snapshot producer and verification rules.
- [ ] `com.chessecho.service.PracticalEvidenceService` (`PracticalEvidenceServiceTest`) — Practical weakness score calculations and confidence thresholds.
