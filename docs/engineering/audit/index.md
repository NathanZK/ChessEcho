# Mutation Testing Audit Index

This document serves as the durable evidence repository for opt-in mutation testing audits across ChessEcho's backend Kotlin services (via PIT, [mutation-testing.md](../mutation-testing.md)) and frontend TypeScript utilities (via StrykerJS, [frontend-mutation-testing.md](../frontend-mutation-testing.md)), in accordance with parent QA strategy issue [#547](https://github.com/NathanZK/ChessEcho/issues/547), backend tracker issue [#550](https://github.com/NathanZK/ChessEcho/issues/550), and frontend integration issue [#635](https://github.com/NathanZK/ChessEcho/issues/635).

---

## 1. Summary of Completed Audits

| Target Class / Module | Test Suite | Scope | Mutants Generated | Killed | Survived | No Cov | Test Strength | Audit Date |
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
| `MoveEvaluationService` | `MoveEvaluationServiceTest` | Service Unit | 30 | 29 (97%) | 1 (3%) | 0 (0%) | 97% | 2026-10-18 |
| `HumanMoveBfsTraversal` | `HumanMoveBfsServiceTest` | Service Unit | 136 | 122 (90%) | 14 (10%) | 0 (0%) | 90% | 2026-10-18 |
| `EvaluationEvidenceSnapshotService` | `EvaluationEvidenceSnapshotTest` | Service Unit | 346 | 126 (36%) | 23 (7%) | 197 (57%) | 85% | 2026-10-18 |
| `EngineMoveProvider` | `EngineMoveProviderTest` | Service Unit | 29 | 29 (100%) | 0 (0%) | 0 (0%) | 100% | 2026-10-18 |
| `HumanMoveProvider` | `HumanMoveProviderTest` | Service Unit | 32 | 24 (75%) | 8 (25%) | 0 (0%) | 75% | 2026-10-18 |
| `ReferenceCoverageAnalysisService` | `ReferenceCoverageAnalysisServiceTest` | Service Unit | 61 | 60 (98%) | 1 (2%) | 0 (0%) | 98% | 2026-10-18 |
| `PositionSimilarityScorer` | `PositionSimilarityScorerTest` | Domain Unit (#91) | 38 | 26 (68%) | 11 (29%) | 1 (3%) | 70% | 2026-10-26 |
| [`Frontend: urlUtils.ts`](#218-frontend-mutation-testing-baseline-core-calculation-utilities-issue-635) | `urlUtils.test.ts` | Frontend Utility ([#635](https://github.com/NathanZK/ChessEcho/issues/635)) | 118 | 98 (83%) | 17 (14%) | 3 (3%) | 85% | 2026-10-27 |
| [`Frontend: chessGame.ts`](#218-frontend-mutation-testing-baseline-core-calculation-utilities-issue-635) | `ChessGameHistory.test.ts` | Frontend Domain ([#635](https://github.com/NathanZK/ChessEcho/issues/635)) | 27 | 16 (59%) | 6 (22%) [4 TO] | 1 (4%) | 77% | 2026-10-27 |
| [`Frontend: positionImport.ts`](#218-frontend-mutation-testing-baseline-core-calculation-utilities-issue-635) | `positionImport.test.ts` | Frontend Domain ([#635](https://github.com/NathanZK/ChessEcho/issues/635)) | 241 | 143 (59%) | 56 (23%) [15 TO] | 27 (11%) | 74% | 2026-10-27 |
| [`Frontend: PuzzleFeedbackPanel.tsx`](#219-frontend-line-exploration-continuation-secrecy-issue-629) | `ContinuationIntegration.test.tsx` | Frontend Presentation ([#629](https://github.com/NathanZK/ChessEcho/issues/629)) | 63 | 58 (92%) | 5 (8%) | 0 (0%) | 92% | 2026-10-28 |

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

### 2.11 `com.chessecho.service.MoveEvaluationService` (PR [#558](https://github.com/NathanZK/ChessEcho/pull/558))

- **Issue / PR**: Issue [#557](https://github.com/NathanZK/ChessEcho/issues/557), PR [#558](https://github.com/NathanZK/ChessEcho/pull/558)
- **Test Suite**: `com.chessecho.service.MoveEvaluationServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.MoveEvaluationService*" \
    -Ppitest.targetTests="com.chessecho.service.MoveEvaluationServiceTest*"
  ```
- **Results**: 30 generated, 29 killed (97%), 1 survived (3%), 0 no coverage (0%); 97% test strength.
- **Meaningful Gaps Killed**:
  - *Winning forced-mate distance & arithmetic*: Line 98 mate arithmetic mutators (`* 10` and `-`) killed by asserting faster mate-in-1 (`bestEvalCp = 9990`) scores higher than slower mate-in-2 (`evalCp = 9980`) and candidate within tolerance has `evalLoss = 0.10` and is `acceptable = true`.
  - *Winning-mate blunder rejection*: Line 98 condition negations and centipawn blunder mutators killed by asserting blunder into centipawns (`cp = 200`, `evalLoss = 97.90`) or opponent mate (`mate = -1`, `evalLoss = 199.80`) is marked `acceptable = false`.
  - *Losing forced-mate defense prolongation*: Line 98 negative mate arithmetic mutators (`-10000 - mate * 10`) killed by asserting prolonged defense mate-in-(-2) (`bestEvalCp = -9980`) scores higher than immediate checkmate mate-in-(-1) (`evalCp = -9990`) with `evalLoss = 0.10` and `acceptable = true` per threshold comparison.
  - *Mate-to-centipawn blunder concealment*: Line 98 `EmptyObjectReturnValsMutator` (substituting return with 0) killed by asserting blunder from neutral draw (`cp = 0`) into opponent mate (`mate = -1`) produces `bestEvalCp = 0`, `evalCp = -9990`, `evalLoss = 99.90`, and `acceptable = false`.
  - *Single-move forced-mate evaluation*: Line 66 single analysis integration verified when candidate is outside MultiPV=5 and produces mate score.
  - *Null evaluation branches*: Line 95 `score == null` and line 100 `EvalScore(cp = null, mate = null)` `EmptyObjectReturnValsMutator` killed by asserting `evalCp = null`, `evalLoss = 0.0`, and `acceptable = true` against non-zero baseline (`bestCp = 100`).
  - *Error boundaries*: Line 30 invalid FEN handling (`IllegalArgumentException`) and line 53 empty Stockfish candidate handling (`IllegalStateException`) verified with descriptive message assertions; line 46 unparseable move message verified.
- **Accepted Surviving Mutants**:
  - 1 boundary condition mutation on Line 98 (`ConditionalsBoundaryMutator` changing `score.mate > 0` to `>= 0`) accepted as an equivalent mutant on non-zero domain values (Taxonomy Category 5), since chess engine mate distances are non-zero integers (+1, +2, -1, -2).
- **Uncovered Branches**: None (0 no coverage).

### 2.12 `com.chessecho.service.HumanMoveBfsTraversal` (PR [#567](https://github.com/NathanZK/ChessEcho/pull/567))

- **Issue / PR**: Issue [#563](https://github.com/NathanZK/ChessEcho/issues/563), PR [#567](https://github.com/NathanZK/ChessEcho/pull/567)
- **Test Suite**: `com.chessecho.service.HumanMoveBfsServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.HumanMoveBfsTraversal*" \
    -Ppitest.targetTests="com.chessecho.service.HumanMoveBfsServiceTest*"
  ```
- **Results**: 136 generated, 122 killed (90%), 14 survived (10%), 0 no coverage (0%); 90% test strength (96.8% excluding compiler null-checks).
- **Meaningful Gaps Killed**:
  - *Rating band qualification*: All boundary comparisons in `isRatingInBand` (`min - 1`, `min`, `max`, `max + 1` for range bands, `min - 1`, `min`, `min + 300` for plus bands) and malformed band handling killed all 10 mutators on lines 362–369.
  - *Sink failure notification*: VoidMethodCallMutator on lines 129 (`sink.onArchiveListFailure`) and 152 (`sink.onMonthlyGamesFailure`) verified via explicit Mockito verifications and continuation assertions.
  - *Quota and boundary termination*: Boundary mutators on lines 112, 144, 145, 169 killed by asserting exact termination and resource bounding for `maxGamesPerPlayer`, `maxQualifyingGames`, and `maxPlayers`.
  - *Variant chess rule filtering*: NegateConditionalsMutators on lines 175 and 176 killed by asserting `chess960` games are skipped while standard `chess` and `null` rules qualify.
  - *PGN parse outcomes and metrics*: PGN parse error handling (`HumanMoveBfsPgnOutcome.PARSE_FAILED`), non-standard start detection (`NON_STANDARD_START`), valid move numbering (`preMovePly = index + 1`), and `result.uniqueGamesProcessed` metric accuracy verified.
- **Accepted Surviving Mutants**:
  - 10 synthetic Kotlin compiler null-checks (`Intrinsics.checkNotNull*`) on stdlib and library return values (Taxonomy Category 3).
  - 3 local logging increment mutators (`playerGamesInspected`, `newOpponentsDiscovered`, `playerQualifyingGames`) that only feed `log.info` parameters.
  - 1 redundant `chesslibGame.loadMoveText()` call where `PgnHolder.loadPgn()` already pre-loads move text.
- **Uncovered Branches**: None (0 no coverage; line coverage 99%).

### 2.13 `com.chessecho.service.EvaluationEvidenceSnapshotService` (PR [#568](https://github.com/NathanZK/ChessEcho/pull/568))

- **Issue / PR**: Issue [#562](https://github.com/NathanZK/ChessEcho/issues/562), PR [#568](https://github.com/NathanZK/ChessEcho/pull/568)
- **Test Suite**: `com.chessecho.service.EvaluationEvidenceSnapshotTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.EvaluationEvidenceSnapshotService*" \
    -Ppitest.targetTests="com.chessecho.service.EvaluationEvidenceSnapshotTest*"
  ```
- **Results**: 346 generated, 126 killed (36%), 23 survived (7%), 197 no coverage (57%); 85% test strength.
- **Meaningful Gaps Killed**:
  1. *Zero and Unit Boundaries*: Configuration (`minMistakeCount = 0`, `minTimesReached = 0`), reference population (`coveredPrefix = 50, prefixN = 50`, `prefixN = 1`, `minObservations = 0`, `distributionSha256 = null`), and row boundaries (`preMovePly = 1`, `loss = 0.0`, `engineDepth = 1`) verified along with rejection of sub-boundary negative/zero values (ConditionalsBoundaryMutator on lines 108–109, 115–117, 128, 132–133).
  2. *Referential & Digest Integrity*: 64-character hex requirements for `contentDigest` and `distributionSha256`, rejection of duplicate player identities and blank identities, unknown row player references, and `practicalEligible` without `practicalCandidate` enforced (NegateConditionalsMutator on lines 118–119, 122, 127, 147).
  3. *Entry Gate Enforcement*: Verification that `bind` and `reconstruct` unconditionally invoke `validate(snapshot)` before downstream logic (VoidMethodCallMutator on lines 158 and 166).
  4. *Practical Evidence Aggregation Math*: Multi-game evidence counting distinguishing candidate, eligible, ineligible, and excluded games, accumulation of wins, draws, and losses, and exact threshold boundary evaluations (MathMutator and ConditionalsBoundaryMutator on lines 342, 350, 351).
- **Accepted Surviving Mutants**:
  - 13 synthetic compiler bytecode null-checks (`Intrinsics.checkNotNull*`) on non-null references across lines 146, 342, 349–351, and compiler-generated lambda returns in `canonicalize` (line 399) (Taxonomy Category 3).
  - 10 synthetic Kotlin stdlib inlined collection checks (`isEmpty` / integer overflow `throwCountOverflow` in `all`, `any`, and `count`) on non-empty collections (Taxonomy Category 3).
- **Uncovered Branches**:
  - 197 mutants in database persistence methods (`persist`, `readVerifiedPersisted`, `reconstructPersisted`, `loadRows`, etc.) requiring PostgreSQL Testcontainers are deferred to `EvaluationEvidenceSnapshotPostgresIntegrationTest`.

### 2.14 `com.chessecho.service.continuation.EngineMoveProvider` (PR [#564](https://github.com/NathanZK/ChessEcho/pull/564))

- **Issue / PR**: Issue [#559](https://github.com/NathanZK/ChessEcho/issues/559), PR [#564](https://github.com/NathanZK/ChessEcho/pull/564)
- **Test Suite**: `com.chessecho.service.continuation.EngineMoveProviderTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.continuation.EngineMoveProvider*" \
    -Ppitest.targetTests="com.chessecho.service.continuation.EngineMoveProviderTest*"
  ```
- **Results**: 29 generated, 29 killed (100%), 0 survived (0%), 0 no coverage (0%); 100% test strength.
- **Meaningful Gaps Killed**:
  1. *Forced-mate null centipawns*: Line 105 empty return mutator killed by asserting `evalLoss` is `null` when candidate has a winning or losing mate score with `cp = null`.
  2. *Invalid UCI engine move filtering*: Line 127 `isValidEngineMove` negated conditional and empty string check killed by asserting invalid moves (`"(none)"`, `""`, `"   "`) are filtered from candidates.
  3. *Resulting FEN en-passant/halfmove token preservation*: Lines 113 and 115 empty return and board setup mutators killed by asserting the resulting FEN after `e5` from `rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1` strictly equals `rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2`.
  4. *Reflection fallback test robustness*: Injected reflection tests verify provider contract invariants.
- **Accepted Surviving Mutants**: None (0 survived).
- **Uncovered Branches**: None (0 no coverage; line coverage 100%).

### 2.15 `com.chessecho.service.continuation.HumanMoveProvider` (PR [#565](https://github.com/NathanZK/ChessEcho/pull/565))

- **Issue / PR**: Issue [#560](https://github.com/NathanZK/ChessEcho/issues/560), PR [#565](https://github.com/NathanZK/ChessEcho/pull/565)
- **Test Suite**: `com.chessecho.service.continuation.HumanMoveProviderTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.continuation.HumanMoveProvider*" \
    -Ppitest.targetTests="com.chessecho.service.continuation.HumanMoveProviderTest*"
  ```
- **Results**: 32 generated, 24 killed (75%), 8 survived (25%), 0 no coverage (0%); 75% test strength.
- **Meaningful Gaps Killed**:
  1. *Genuine SHA-256 FEN hashing*: Line 137 hashing mutators and line 130 4-part FEN truncation killed by replacing mock matcher with `eq(expectedHash)` using the genuine SHA-256 hash `7c53dea8af10e728bbf57edf7ebc8275f0a29735bb0c9c852ee96533064271dc` of normalized 4-part FEN.
  2. *Observation count threshold boundary*: Line 76 boundary condition mutator (`totalObservations >= minObservations`) killed by asserting candidates with exact threshold `totalObservations = 10` are included and `< 10` are excluded.
  3. *Resulting FEN continuation normalization*: Lines 110 and 112 board state setup and FEN generation verified with trailing field assertions (`... w KQkq c6 0 2`).
  4. *Provider metadata*: Line 22 provider type mutator killed by asserting `providerType == "HUMAN"`.
  5. *Invalid/null rating band validation*: Line 32 and 41 early exits verified with `verifyNoInteractions(humanMoveRepository)`.
- **Accepted Surviving Mutants**:
  - 4 synthetic Kotlin compiler bytecode null-checks (`Intrinsics.checkNotNull*`) on stdlib returns (Taxonomy Category 3).
  - 3 equivalent `EmptyObjectReturnValsMutator` substitutions on early returns (`emptyList()`) when conditions are unmet (Taxonomy Category 5).
  - 1 equivalent boundary mutation on line 130 (`parts.size >= 4` to `> 4`) where 4-part FEN string rejoining produces an identical string to the input (Taxonomy Category 5).
- **Uncovered Branches**: None (0 no coverage; line coverage 96%).

### 2.16 `com.chessecho.service.ReferenceCoverageAnalysisService` (PR [#566](https://github.com/NathanZK/ChessEcho/pull/566))

- **Issue / PR**: Issue [#561](https://github.com/NathanZK/ChessEcho/issues/561), PR [#566](https://github.com/NathanZK/ChessEcho/pull/566)
- **Test Suite**: `com.chessecho.service.ReferenceCoverageAnalysisServiceTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.service.ReferenceCoverageAnalysisService*" \
    -Ppitest.targetTests="com.chessecho.service.ReferenceCoverageAnalysisServiceTest*"
  ```
- **Results**: 61 generated, 60 killed (98%), 1 survived (2%), 0 no coverage (0%); 98% test strength.
- **Meaningful Gaps Killed**:
  1. *Zero-loss acceptance at positive threshold*: Line 96 condition negation and zero check killed by verifying that moves with `loss = 0.0` are accepted without triggering weakness threshold qualifications.
  2. *Exact threshold boundary qualification*: Line 127 boundary condition mutator in `sharedPositions` (`loss >= threshold` to `> threshold`) killed by asserting that move with `loss == 0.50` is included at threshold `0.50` and excluded at `0.80`.
  3. *Player map identity validation*: Lines 88–91 condition negations killed by asserting rejection of input where player map keys differ from `player.id` or contain blank player IDs.
- **Accepted Surviving Mutants**:
  - 1 synthetic Kotlin compiler bytecode iterator conditional on line 211 (Taxonomy Category 3).
- **Uncovered Branches**: None (0 no coverage; line coverage 99%).

### 2.17 `com.chessecho.similarity.PositionSimilarityScorer` (Issue [#91](https://github.com/NathanZK/ChessEcho/issues/91))

- **Issue**: Issue [#91](https://github.com/NathanZK/ChessEcho/issues/91)
- **Detailed Audit Report**: [2026-10-26-issue-91-mutation-audit.md](./2026-10-26-issue-91-mutation-audit.md)
- **Test Suite**: `com.chessecho.similarity.PositionSimilarityScorerTest`
- **Execution Command**:
  ```bash
  ./gradlew pitest \
    -Ppitest.targetClasses="com.chessecho.similarity.PositionSimilarityScorer*" \
    -Ppitest.targetTests="com.chessecho.similarity.PositionSimilarityScorerTest*"
  ```
- **Results**: 38 generated, 26 killed (68%), 11 survived (29%), 1 no coverage (3%); 70% test strength. Full-stack targeted audit evaluated 13 failure-mode mutants with 12 killed (92%) and 1 unresolved (8%).
- **Meaningful Gaps Killed via Regression Tests**:
  1. *Document length normalization ($b=0.75$)*: Removing length factor killed by adding regression test penalizing longer document length when matching tokens are identical.
  2. *Deterministic tie-breaking*: Ascending canonical FEN tie-break inversion killed by unconditionally asserting canonical FEN ordering on identical scores.
  3. *Illegal piece character FEN rejection*: Rejection of non-orthodox piece characters killed by asserting `IllegalArgumentException` on invalid pieces.
  4. *Search button disabled state*: Empty FEN input disabled state verified via explicit `toBeDisabled()` assertion.
- **Unresolved / Accepted Gaps**:
  - `PositionSimilarityCache`: Rebuild commit generation check remains unresolved due to absence of internal cache map assertions.

### 2.18 Frontend Mutation Testing Baseline: Core Calculation Utilities (Issue [#635](https://github.com/NathanZK/ChessEcho/issues/635))

- **Issue**: Issue [#635](https://github.com/NathanZK/ChessEcho/issues/635)
- **Detailed Audit Report**: [frontend-mutation-audit.md](./frontend-mutation-audit.md)
- **Operational Guide**: [frontend-mutation-testing.md](../frontend-mutation-testing.md)
- **Framework**: StrykerJS 10.1.1 (`@stryker-mutator/core`, `@stryker-mutator/vitest-runner`)
- **Scope**: `src/utils/urlUtils.ts`, `src/utils/chessGame.ts`, `src/utils/positionImport.ts`
- **Test Suites**: `src/__tests__/urlUtils.test.ts`, `src/__tests__/ChessGameHistory.test.ts`, `src/__tests__/positionImport.test.ts`
- **Execution Command**:
  ```bash
  cd frontend && npm run test:mutation
  ```
- **Aggregate Results**: 386 mutants generated; 257 killed (66.6%), 19 timed out (4.9%), 79 survived (20.5%), 31 no coverage (8.0%); 71.50% mutation score (276/386), 77.75% test strength (276/355).
- **Per-Target Breakdown**:
  - `urlUtils.ts`: 118 generated; 98 killed, 0 timed out, 17 survived, 3 no coverage; 83.05% mutation score, 85.22% test strength.
  - `chessGame.ts`: 27 generated; 16 killed, 4 timed out, 6 survived, 1 no coverage; 74.07% mutation score, 76.92% test strength.
  - `positionImport.ts`: 241 generated; 143 killed, 15 timed out, 56 survived, 27 no coverage; 65.56% mutation score, 73.83% test strength.
- **Actionable Findings Deferred to Remediation Issues**:
  1. *`chessGame.ts`*: Fallback move application doesn't verify history preservation when moves succeed.
  2. *`urlUtils.ts`*: `buildExactPlyChessComUrl` trailing non-digit validation boundaries; `/game/computer/` path test coverage.
  3. *`positionImport.ts`*: PGN escaped header tags (`\"`), scan comment-state combinations, and exact character move text verification.

### 2.19 `Frontend: PuzzleFeedbackPanel.tsx` (Line Exploration Continuation Secrecy)

- **Issue**: Issue [#629](https://github.com/NathanZK/ChessEcho/issues/629)
- **Operational Guide**: [frontend-mutation-testing.md](../frontend-mutation-testing.md)
- **Framework**: StrykerJS 10.1.1 (`@stryker-mutator/core`, `@stryker-mutator/vitest-runner`), Vitest 4.1.12
- **Scope**: `src/components/PuzzleFeedbackPanel.tsx` (Line Exploration continuation rendering block: lines 1000–1080)
- **Test Suite**: `src/__tests__/ContinuationIntegration.test.tsx` (and full Vitest coverage across 220 tests during dry-run)
- **Execution Command**:
  ```bash
  cd frontend && npm run test:mutation -- --mutate="src/components/PuzzleFeedbackPanel.tsx:1000:1-1080:1"
  ```
- **Targeted Scope Results (`lines 1000–1080`)**:
  - **Mutants Instrumented**: 63
  - **Killed**: 58 (92.06%)
  - **Timed Out**: 0 (0.00%)
  - **Survived**: 5 (7.94%)
  - **No Coverage**: 0 (0.00% — 100% eliminated via Test 7b)
  - **Compile / Runtime Errors**: 0 (0.00%)
  - **Mutation Score**: **92.06%** (Covered: **92.06%**)
  - **Execution Duration**: 32 seconds (9 parallel workers)
- **Component-Wide Baseline Comparison**:
  - Across the entire component (`PuzzleFeedbackPanel.tsx`, 1,152 lines): 739 mutants instrumented, 367 killed (49.7%), 27 timed out (3.7%), 316 survived (42.8%), 28 no coverage (3.8%), 1 error (53.39% score). The remaining 316 survivors reside outside the continuation rendering scope (in blindfold mode, timed training, hint panels, historical mistake banners) and remain tracked under persistent tracker [#550](https://github.com/NathanZK/ChessEcho/issues/550).
- **Surviving Mutants Audit & Qualitative Disposition (5 Mutants)**:
  1. *Mutant 15 (Line 1025:28, Category 5 Equivalent)*: `selected.pliesResolved != null` replaced with `true` in `(selected.pliesResolved != null && selected.pliesResolved > 0)`. Under ECMAScript relational comparison semantics, `null > 0 === false` and `undefined > 0 === false`. Therefore, `(x != null && x > 0)` and `(true && x > 0)` produce identical truth values for all JavaScript values.
  2. *Mutant 49 (Line 1057:36, Category 5 Equivalent)*: `alt.pliesResolved != null` replaced with `true` in `(alt.pliesResolved != null && alt.pliesResolved > 0)`. Symmetrical redundant null guard before `alt.pliesResolved > 0`.
  3. *Mutant 26 (Line 1027:82, Category 5 Equivalent)*: `selected.continuationSanMoves?.length` replaced with `.continuationSanMoves.length`. Line 1027 is enclosed by the ternary guard on line 1025, which on the fallback path requires `(selected.continuationSanMoves && selected.continuationSanMoves.length > 0)`. Control flow guarantees `continuationSanMoves` is non-null and defined whenever line 1027 evaluates.
  4. *Mutant 60 (Line 1059:58, Category 5 Equivalent)*: `alt.continuationSanMoves?.length` replaced with `.continuationSanMoves.length`. Symmetrical control-flow guarantee for alternative candidate rows, dominated by line 1057 `(alt.continuationSanMoves && alt.continuationSanMoves.length > 0)`.
  5. *Mutant 62 (Line 1069:46, Category 4 Defensive Safeguard)*: `onClick={() => onAlternativeSelected?.(alt)}` replaced with `onClick={() => onAlternativeSelected(alt)}`. In `PuzzleFeedbackPanelProps` (line 118), `onAlternativeSelected` is an optional prop callback. In interactive exploratory page flows (`app/page.tsx:2192`), `handleAlternativeSelected` is always supplied. The optional chaining `?.` is a defensive TypeScript safeguard against an unpassed optional prop when omitted.
- **Secrecy and Replay Protection**:
  - Trailing continuation move leakage eliminated from selected card and alternative response rows.
  - Verified empirically in Vitest: Test 7 `not.toBeInTheDocument()` secrecy assertions fail if trailing continuation SAN tokens (`Nf6`, `O-O`, `dxc6`) are rendered, and Test 8 verifies that underlying continuation data continues to drive board replay upon selecting "Explore this line".

---

## 3. Candidate Classes Not Yet Assessed

In accordance with [mutation-testing.md](../mutation-testing.md) and QA Strategy [#547](https://github.com/NathanZK/ChessEcho/issues/547), the following pure domain calculation units and integration suites remain candidates for future focused audits:

- [x] `com.chessecho.service.MoveEvaluationService` (`MoveEvaluationServiceTest`) — Interactive move evaluation, forced-mate centipawn score conversion, and exploration tolerance. (Audited in [#557](https://github.com/NathanZK/ChessEcho/issues/557) / PR [#558](https://github.com/NathanZK/ChessEcho/pull/558))
- [x] `com.chessecho.service.WeaknessCalculationService` (`WeaknessCalculationServiceTest`) — Account-scoped weakness prioritization and statistical ranking formulas. (Audited in [#553](https://github.com/NathanZK/ChessEcho/issues/553) / PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))
- [x] `com.chessecho.service.PracticalEvidenceService` (`PracticalEvidenceServiceTest`) — Practical weakness score calculations and confidence thresholds. (Audited in [#553](https://github.com/NathanZK/ChessEcho/issues/553) / PR [#555](https://github.com/NathanZK/ChessEcho/pull/555))
- [x] `com.chessecho.service.HumanMoveBfsTraversal` (`HumanMoveBfsServiceTest`) — Breadth-first traversal, rating band boundaries, and quota bounds. (Audited in [#563](https://github.com/NathanZK/ChessEcho/issues/563) / PR [#567](https://github.com/NathanZK/ChessEcho/pull/567))
- [x] `com.chessecho.service.EvaluationEvidenceSnapshotService` (`EvaluationEvidenceSnapshotTest`) — In-memory validation, boundary conditions, entry gates, and aggregation math. (Audited in [#562](https://github.com/NathanZK/ChessEcho/issues/562) / PR [#568](https://github.com/NathanZK/ChessEcho/pull/568))
- [x] `com.chessecho.service.continuation.EngineMoveProvider` (`EngineMoveProviderTest`) — Candidate filtering, forced-mate loss, resulting FEN generation. (Audited in [#559](https://github.com/NathanZK/ChessEcho/issues/559) / PR [#564](https://github.com/NathanZK/ChessEcho/pull/564))
- [x] `com.chessecho.service.continuation.HumanMoveProvider` (`HumanMoveProviderTest`) — Real SHA-256 hash lookup, observation boundaries, FEN normalization. (Audited in [#560](https://github.com/NathanZK/ChessEcho/issues/560) / PR [#565](https://github.com/NathanZK/ChessEcho/pull/565))
- [x] `com.chessecho.service.ReferenceCoverageAnalysisService` (`ReferenceCoverageAnalysisServiceTest`) — Zero-loss acceptance, shared positions threshold boundaries, player map validation. (Audited in [#561](https://github.com/NathanZK/ChessEcho/issues/561) / PR [#566](https://github.com/NathanZK/ChessEcho/pull/566))
- [ ] `com.chessecho.service.ProgressService` (`ProgressServiceTest`) — Historical interval baseline construction and progress delta calculations.
- [ ] `com.chessecho.service.EvaluationEvidenceProducerService` (`EvaluationEvidenceProducerServiceTest`) — Deterministic evaluation snapshot producer and verification rules.
- [ ] `com.chessecho.service.EvaluationEvidenceSnapshotService` (`EvaluationEvidenceSnapshotPostgresIntegrationTest`) — Integration mutation testing for 197 deferred database persistence and reconstruction mutants.


