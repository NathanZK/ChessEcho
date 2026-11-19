# Testing Capability Map

This capability map maps each of the ten canonical user journeys to existing test evidence across four explicit evidence tiers:
- **Tier 1 (Unit / Component)**: Isolated unit tests verifying functions or single components in JSDOM / JVM.
- **Tier 2 (Frontend Journey / Mocked)**: Home-mounted Vitest suites and Playwright browser journeys driving multi-step user workflows against mocked HTTP API boundaries.
- **Tier 3 (Backend Integration)**: JUnit 5 integration tests against real Testcontainers PostgreSQL, Flyway, and subprocesses.
- **Tier 4 (Full-Stack)**: Automated end-to-end tests exercising real browser against real backend/database (currently 0 tests).

| Journey ID | Journey Name | Tier 1 (Unit / Component) | Tier 2 (Frontend Journey / Mocked) | Tier 3 (Backend Integration / Testcontainers) | Tier 4 (Full-Stack) |
|---|---|---|---|---|---|
| **J01** | Shared-practice from Weaknesses Library | `PositionCard.test.tsx`, `ObjectiveWeaknessAnalyzerTest.kt` | `WeaknessesTab.test.tsx`, `WeaknessesList.test.tsx`, `WeaknessLoadStates.test.tsx`, `frontend/journeys/j01-weaknesses-practice.spec.ts` (Playwright) | `WeaknessControllerIntegrationTest.kt`, `PracticalWeaknessControllerIntegrationTest.kt`, `RecurringWeaknessDiscoveryConcurrencyTest.kt` | None (0 tests) |
| **J02** | Shared-practice from Decision Review / Timing | `decisionTimingComparison.test.ts`, `DecisionTimingPolicyTest.kt`, `domainProperties.test.ts` (fast-check) | `LongDecisionsView.test.tsx`, `ExploreDecisionAfterWrongMove.test.tsx`, `frontend/journeys/j02-decision-timing-practice.spec.ts` (Playwright) | `LongDecisionControllerIntegrationTest.kt` | None (0 tests) |
| **J03** | Shared-practice from Similar Positions | `PositionSimilarityServiceTest.kt`, `FenPositionValidatorTest.kt`, `DynamicTacticalScorerTest.kt`, `domainProperties.test.ts` (fast-check) | `SimilarPositionsView.test.tsx`, `SimilarPositionsNavigation.test.tsx`, `frontend/journeys/j03-similar-positions-practice.spec.ts` (Playwright) | `SimilarPositionsControllerIntegrationTest.kt`, `SimilarityConcurrencyManagerTest.kt`, `SimilarityConcurrencyManagerConcurrencyTest.kt`, `PositionSimilarityServiceDynamicTest.kt` | None (0 tests) |
| **J04** | Shared-practice from Explore Position | `positionImport.test.ts`, `MoveEvaluationServiceTest.kt`, `domainProperties.test.ts` (fast-check) | `ExplorePositionModal.test.tsx`, `SuppliedPositionExploration.test.tsx`, `frontend/journeys/j04-explore-position-practice.spec.ts` (Playwright) | `PuzzleControllerIntegrationTest.kt` | None (0 tests) |
| **J05** | Shared-practice from Practice Puzzles direct tab | `PuzzleFeedbackPanel.test.tsx`, `AdaptivePuzzleSchedulingPolicyTest.kt`, `domainProperties.test.ts` (fast-check) | `PracticePuzzlesView.test.tsx`, `PuzzlesFeaturesAndFixes.test.tsx`, `PuzzleAttemptCountFlow.test.tsx`, `frontend/journeys/j05-practice-puzzles.spec.ts` (Playwright) | `PuzzleControllerIntegrationTest.kt`, `PuzzleSubmissionPostgresIntegrationTest.kt` | None (0 tests) |
| **J06** | Guest game import and analysis pipeline | `ImportGamesRequestTest.kt`, `GameParserServiceAttributionTest.kt` | `ImportGamesViewPresentation.test.tsx`, `ImportJobStore.test.tsx`, `frontend/journeys/j06-game-import.spec.ts` (Playwright) | `GameImportServiceBaseIntegrationTest.kt`, `GameImportServiceAttributionIntegrationTest.kt`, `GuestSchedulingClaimReplayPostgresIntegrationTest.kt`, `ParallelGameImportConcurrencyTest.kt` | None (0 tests) |
| **J07** | Authenticated game import and analysis pipeline | `AccountOwnershipServiceTest.kt`, `AsyncJobConfigurationInvariantTest.kt` | `ConnectedAccountFlow.test.tsx`, `AccountContextAndImport.test.tsx`, `frontend/journeys/j06-game-import.spec.ts` (Playwright) | `OwnerScopedAccessIntegrationTest.kt`, `GameImportServiceAccountStateIntegrationTest.kt`, `AsyncJobConcurrencyIntegrationTest.kt`, `ParallelGameImportConcurrencyTest.kt` | None (0 tests) |
| **J08** | Session initialization and guest bootstrap | `SessionAuthenticationFilterTest.kt`, `SessionCookiePropertiesTest.kt` | `SessionBootstrap.test.tsx`, `useAccountSession.test.ts`, `HeaderResponsiveAccount.test.tsx`, `frontend/journeys/j08-j10-session-auth.spec.ts` (Playwright) | `SessionCookieAuthIntegrationTest.kt`, `DevSessionAbsenceTest.kt` | None (0 tests) |
| **J09** | User registration and initial credential creation | `LocalAuthApi.test.ts`, `RegisterPasswordVisibilityToggle.test.tsx` | `RegisterPasswordVisibilityToggle.test.tsx`, `frontend/journeys/j08-j10-session-auth.spec.ts` (Playwright) | `LocalAuthIntegrationTest.kt`, `LocalCredentialRepositoryTest.kt` | None (0 tests) |
| **J10** | User login, session persistence, and logout lifecycle | `LoginPasswordVisibilityToggle.test.tsx`, `SessionCredentialsCsrf.test.ts` | `SessionLogoutExpiry.test.tsx`, `frontend/journeys/j08-j10-session-auth.spec.ts` (Playwright) | `LocalAuthIntegrationTest.kt`, `OwnerScopedCsrfIntegrationTest.kt`, `CookieTransportSecurityIntegrationTest.kt` | None (0 tests) |
| **J11** | Individual game analysis and mistake drilldown | `GameAnalysisServiceTest.kt`, `EvaluationLossPolicyTest.kt`, `EvaluationLossPolicyPropertyTest.kt` (jqwik) | `GameAnalysisView.test.tsx`, `frontend/journeys/individual-game-analysis.spec.ts` (Playwright) | `GameAnalysisControllerTest.kt`, `StockfishServiceTest.kt` | None (0 tests) |
| **J12** | Recurring weakness discovery and thematic cluster practice | `PositionClusteringServiceTest.kt`, `DynamicInvertedIndexTest.kt`, `RecurringWeaknessDiscoveryServiceTest.kt` | `ClusterEvidenceModal.test.tsx`, `frontend/journeys/recurring-weakness-discovery.spec.ts` (Playwright) | `RecurringWeaknessControllerTest.kt`, `RecurringWeaknessDiscoveryHardeningTest.kt` | None (0 tests) |

## Deterministic Retirements and Known Deviations

When model-based BFS and monkey infrastructure was retired (#831), each unique behaviour
was ported to a deterministic test rather than dropped. Two notes apply to the J05 row:

- **Issue #779 / exploration exit.** `frontend/journeys/regression/issue-779-exploration-exit.spec.ts`
  drives solve -> enter exploration -> exit exploration -> attempt and asserts the post-exit
  move is freshly evaluated (rejection banner, failure counter, `INCORRECT` feedback) and that
  no stale evaluation request is replayed. It also asserts exactly one network
  `/api/puzzles/evaluate-move` request, which belongs to the pre-exit dynamic-evaluation puzzle.
- **AC2 deviation (requires maintainer approval).** The original #831 acceptance criterion asked
  for a request-level assertion that the post-exit attempt issues a new network evaluation.
  For target-move puzzles the application evaluates moves client-side in `ChessBoardArea`, so a
  correct fresh evaluation intentionally issues no request; adding one would distort behaviour.
  The regression therefore proves the AC2 intent through observable evaluation outcomes instead
  of request counting. This is a test-design deviation, not a coverage loss, and it is disclosed
  here and in the spec for maintainer sign-off before publication.
- **Exploration re-entry (investigated, no defect).** Exiting exploration remounts the board at
  the puzzle entry FEN with an empty history (undo/redo disabled), and `handleEnterExploration`
  rebuilds the exploration eval map, feedback (`EXPLORING`), candidates, and challenge state.
  Driving solve -> explore -> exit -> re-enter showed no stale history or feedback: re-entry
  starts at the entry FEN with no prior banner and disabled history, and a subsequent
  mistake-then-undo returns to the same intended `CORRECT` exit state. No additional regression
  was added because these states are derived from the reset sources rather than persisted, and a
  test would duplicate the #779 coverage without exercising a distinct failure mode.
