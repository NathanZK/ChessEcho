# Spec: Singleton Testcontainers Base Class, Contract Verification, Dual-Tenant Isolation & Persistence Harness (#788)

```
issue:       #788
topology:    single
coordinator: (this repo)
scope:       standard
milestone:   M1 (Testing Foundation & Persistence Lattice)
convention:  docs/engineering/repository-conventions.md
deferred:    Phase 6 Playwright full-stack live-engine E2E (#717), Knip dead-code cleanup
```

## Approaches Considered

- **Approach A (Ad-hoc Testcontainers per slice)**: Each integration test class declares its own `@Container` PostgreSQL instance with per-test truncation scripts.
  - *Trade-off*: High execution time (>5m), port contention under concurrent Gradle test forks, and duplicated cleanup logic.
- **Approach B (Global Schema Re-creation per test)**: Drop and re-run Flyway migrations before every test run.
  - *Trade-off*: Severe latency (>15s per test class) and database connection thrashing.
- **Approach C (Singleton JVM Testcontainers with Scoped Draining, Complete Identity Cleanup & Decoupled Slices) [RECOMMENDED]**: Static JVM-singleton PostgreSQL 16 container started once per test worker process, with Flyway validation, tenant-scoped async worker quiescence draining, transaction-wrapped 18-table child-to-parent cleanup covering all five user/account identity configurations, and post-cleanup quarantine assertions. Decoupled slice tests for backend REST contract verification, dual-tenant isolation, deterministic repository ordering, and resilience fault-injection.
  - *Trade-off*: Requires rigorous topological ordering for cascading deletes, but delivers sub-2-minute warm execution, zero port collisions, and leak-free isolation.

---

## Decisions & Declarations

### 1. Singleton PostgreSQL Testcontainers Base Class, Scoped Worker Draining & Tenant Cleanup Harness
- **contract**: `backend/src/test/kotlin/com/chessprobe/AbstractIntegrationTest.kt`
  - Base class for all PostgreSQL Spring Boot integration tests.
  - Container Lifecycle:
    - Static JVM singleton `PostgreSQLContainer<Nothing>("postgres:16-alpine")` started once during class loading.
    - Dynamic properties binding `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`, `spring.flyway.enabled=true`, and `spring.jpa.hibernate.ddl-auto=validate`.
    - JVM shutdown hook ensuring clean container termination on worker shutdown.
  - Tenant-Scoped Worker Quiescence Draining:
    - `drainAsyncWorkerQuiescence(userId: UUID? = null, accountId: UUID? = null, jobIds: Collection<UUID> = emptyList(), timeoutMs: Long = 5000)`:
      - Derives tenant identity sets `(derivedUserIds, derivedAccountIds)` from inputs.
      - Scopes quiescence checks strictly to the target test tenant or explicitly provided `jobIds`. Unrelated jobs from other concurrent test executions or tenants do not block or fail the caller.
      - Lifecycle checks:
        1. Database `async_job`: Polls `asyncJobRepository` for jobs matching `(app_user_id IN (:derivedUserIds) OR chess_account_id IN (:derivedAccountIds) OR id IN (:jobIds))` where `status IN ('QUEUED', 'PROCESSING')`.
        2. In-Memory `RecurringWeaknessDiscoveryService`: If registered in Spring context, inspects `activeAccountJobs` for any `accountId IN (:derivedAccountIds)` and awaits `completionLatch` up to remaining timeout.
      - Polling interval: 50ms sleep up to `timeoutMs`.
      - Failure behavior: Throws `IllegalStateException("Worker quiescence timed out after ${timeoutMs}ms for scoped tenant: users=$derivedUserIds, accounts=$derivedAccountIds, jobs=$jobIds")`.
  - Complete Tenant Identity Derivation Algorithm:
    - Input: `cleanTenantData(userId: UUID? = null, accountId: UUID? = null)`
    - Requirement: `require(userId != null || accountId != null) { "At least one of userId or accountId must be provided" }`.
    - Identity derivation logic (executed before deletion):
      ```kotlin
      val derivedUserIds = mutableSetOf<UUID>()
      val derivedAccountIds = mutableSetOf<UUID>()
      if (userId != null) {
          derivedUserIds.add(userId)
          val linkedAccounts = jdbcTemplate.queryForList(
              "SELECT chess_account_id FROM account_connection WHERE app_user_id = ?",
              UUID::class.java, userId
          )
          derivedAccountIds.addAll(linkedAccounts)
      }
      if (accountId != null) {
          derivedAccountIds.add(accountId)
          val linkedUsers = jdbcTemplate.queryForList(
              "SELECT app_user_id FROM account_connection WHERE chess_account_id = ?",
              UUID::class.java, accountId
          )
          derivedUserIds.addAll(linkedUsers)
      }
      ```
    - Pre-records cascading subquery IDs before deletion:
      - `derivedSnapshotIds`: `SELECT id FROM position_cluster_snapshot WHERE account_id IN (:derivedAccountIds)` (empty list if `derivedAccountIds` is empty).
      - `derivedArchiveIds`: `SELECT id FROM imported_archive WHERE chess_account_id IN (:derivedAccountIds)` (empty list if `derivedAccountIds` is empty).
    - Identity Configuration Coverage (Explicitly verified in lifecycle tests):
      1. **User-only**: `derivedAccountIds` empty; cleans user-scoped auth and credential records.
      2. **Account-only**: `derivedUserIds` empty; cleans account-scoped games, occurrences, weaknesses, clusters, and archives.
      3. **Linked**: Both sets populated via `account_connection`; cleans both user and account graphs and the connection record.
      4. **Unlinked**: User and account exist without active connection; both sets cleaned concurrently without foreign key errors.
      5. **Guest-account**: Public guest jobs/attempts (`app_user_id` null, `chess_account_id` present) cleaned safely.
  - Strict 18-Table Child-to-Parent Deletion Order:
    - Wrapped inside an atomic `TransactionTemplate.execute` block for all-or-nothing rollback on failure.
    - Order verified against `V1__baseline.sql` foreign keys and triggers:
      1. `training_attempt` (WHERE `app_user_id IN (:derivedUserIds) OR chess_account_id IN (:derivedAccountIds)`)
      2. `puzzle_scheduling_event` (WHERE `app_user_id IN (:derivedUserIds) OR chess_account_id IN (:derivedAccountIds)`)
      3. `position_cluster_assignment` (WHERE `snapshot_id IN (:derivedSnapshotIds)`)
      4. `position_cluster` (WHERE `snapshot_id IN (:derivedSnapshotIds)`)
      5. `position_cluster_snapshot` (WHERE `account_id IN (:derivedAccountIds)`)
      6. `user_position_weakness` (WHERE `chess_account_id IN (:derivedAccountIds)`)
      7. `user_position_stats` (WHERE `chess_account_id IN (:derivedAccountIds)`)
      8. `position_occurrence` (WHERE `chess_account_id IN (:derivedAccountIds)`)
      9. `game` (WHERE `chess_account_id IN (:derivedAccountIds)`)
      10. `archive_derived_processing` (WHERE `imported_archive_id IN (:derivedArchiveIds)`)
      11. `imported_archive` (WHERE `chess_account_id IN (:derivedAccountIds)`)
      12. `async_job` (WHERE `app_user_id IN (:derivedUserIds) OR chess_account_id IN (:derivedAccountIds)`)
          *Note: Must be deleted BEFORE chess_account to prevent trigger `trg_chess_account_async_jobs_unresolved` mutation on account deletion, and BEFORE app_user due to `REFERENCES app_user (id) ON DELETE NO ACTION`.*
      13. `account_connection` (WHERE `app_user_id IN (:derivedUserIds) OR chess_account_id IN (:derivedAccountIds)`)
      14. `auth_session` (WHERE `app_user_id IN (:derivedUserIds)`)
      15. `auth_identity` (WHERE `app_user_id IN (:derivedUserIds)`)
      16. `local_credential` (WHERE `app_user_id IN (:derivedUserIds)`)
      17. `chess_account` (WHERE `id IN (:derivedAccountIds)`)
      18. `app_user` (WHERE `id IN (:derivedUserIds)`)
  - Post-Cleanup Quarantine Assertion:
    - `assertTenantQuarantined(derivedUserIds: Set<UUID>, derivedAccountIds: Set<UUID>, derivedSnapshotIds: List<UUID>, derivedArchiveIds: List<UUID>)`:
      - Queries all 18 tables using derived ID sets.
      - Throws explicit `AssertionError("Tenant quarantine assertion failed: residual rows detected in table <table> (count=<count>)")` if any row persists.
  - Reusable Base Class Test Helpers:
    - `createTestUser(email: String = "test-${UUID.randomUUID()}@example.com"): AppUser`
    - `createTestAccount(user: AppUser? = null, platform: Platform = Platform.CHESS_COM, username: String = "test-${UUID.randomUUID()}"): ChessAccount`
- **test**: `backend/src/test/kotlin/com/chessprobe/AbstractIntegrationTestLifecycleTest.kt`
  - Validates full lifecycle: startup, worker draining, and all five identity configurations under `cleanTenantData()` and `assertTenantQuarantined()`.

---

### 2. Database Inventory Audit & Test Migration to PostgreSQL
- **Database Inventory Audit & H2 Classification**:
  - **Category A: Intentionally Non-PostgreSQL / Transient Auth-Slice Tests (H2 Permitted)**:
    - `CookieTransportSecurityIntegrationTest.kt`
    - `SessionCookieAuthIntegrationTest.kt`
    - `DevSessionAbsenceTest.kt`
    - `LocalAuthIntegrationTest.kt`
    - `OwnerScopedCsrfIntegrationTest.kt`
    - *Classification Rationale*: These test Spring Security HTTP filter chain mechanics, CSRF tokens, and HTTP-only cookie transport headers. The database serves only as a transient credential backing store with no custom PostgreSQL functions, triggers, concurrency locks, or complex queries. In-memory H2 is intentionally suitable.
  - **Category B: Controller Web-Slice & Unit Tests (MockMvc / Pure Unit, No Database Needed)**:
    - Tests in `backend/src/test/kotlin/com/chessprobe/controller/` (`AccountControllerTest`, `GameControllerTest`, `PositionSimilarityControllerTest`, `WeaknessControllerTest`, `LongDecisionControllerTest`, `RecurringWeaknessControllerTest`, etc.): Pure `@WebMvcTest` slices with `@MockBean` services.
    - Domain, DTO, and Service Unit Tests (`backend/src/test/kotlin/com/chessprobe/service/`, `similarity/`, `domain/`, `dto/`): Pure Mockito/Kotlin unit tests.
  - **Category C: PostgreSQL Integration Tests (Mandatory PostgreSQL Testcontainers Invariant)**:
    - All tests exercising database transactions, concurrency locking (`FOR UPDATE SKIP LOCKED`), partial unique indexes, triggers, foreign keys, or complex analytical queries MUST run on PostgreSQL Testcontainers.
    - **Migrations Required**:
      1. `GameImportServiceBaseIntegrationTest.kt`:
         - Migrate base class from standalone `@SpringBootTest` with H2 `application-test.yml` to inherit from `AbstractIntegrationTest`.
         - Re-bases all 6 inheriting test suites to PostgreSQL:
           - `GameImportServiceAnalysisIntegrationTest.kt`
           - `GameImportServiceCoordinatorIntegrationTest.kt`
           - `GameImportServiceArchiveIntegrationTest.kt`
           - `GameImportServiceBatchIntegrationTest.kt`
           - `GameImportServiceAccountStateIntegrationTest.kt`
           - `ParallelGameImportConcurrencyTest.kt`
      2. `ParallelGameImportConcurrencyTest.kt`:
         - Inherits from `AbstractIntegrationTest` via `GameImportServiceBaseIntegrationTest`.
         - Removes the artificial root starting-position pre-seeding workaround (`positionRepository.save(Position(...))`) necessitated by H2.
         - Tests genuine PostgreSQL read-committed transaction isolation, foreign key constraints, and concurrent game import execution.
         - Uses `cleanTenantData(accountId = account.id)` in `@AfterEach`.
      3. Standalone Async Concurrency & Boundary Tests:
         - `AsyncJobConcurrencyIntegrationTest.kt`: Migrate to inherit from `AbstractIntegrationTest`.
         - `DerivedBatchBoundaryIntegrationTest.kt`: Migrate to inherit from `AbstractIntegrationTest`.
         - `Issue417AnalysisTerminalStateIntegrationTest.kt`: Migrate to inherit from `AbstractIntegrationTest`.
         - `AccountAssociationConcurrencyIntegrationTest.kt`: Migrate to inherit from `AbstractIntegrationTest`.
- **test**:
  - `backend/src/test/kotlin/com/chessprobe/concurrency/ParallelGameImportConcurrencyTest.kt`
  - `backend/src/test/kotlin/com/chessprobe/integration/service/AsyncJobConcurrencyIntegrationTest.kt`
  - `backend/src/test/kotlin/com/chessprobe/integration/service/DerivedBatchBoundaryIntegrationTest.kt`
  - `backend/src/test/kotlin/com/chessprobe/integration/service/Issue417AnalysisTerminalStateIntegrationTest.kt`
  - `backend/src/test/kotlin/com/chessprobe/integration/controller/AccountAssociationConcurrencyIntegrationTest.kt`

---

### 3. Backend REST API Contract Verification
- **contract**: `backend/src/test/kotlin/com/chessprobe/contract/ControllerContractVerificationTest.kt`
  - **Scope & Terminology Boundary**:
    - The scope is explicitly designated as **Backend REST API Contract Verification**.
    - *Rationale*: A JVM test runner running Kotlin tests cannot directly parse TypeScript interfaces at runtime without a bidirectional code generation bridge (e.g. OpenAPI/Zod schema generator). Rather than introducing speculative bridge dependencies, this suite verifies that backend REST controllers strictly enforce HTTP query parameter validation, path variable extraction, default values, constraint violations (HTTP 400), and JSON response payload structures conforming to the API contracts documented in `docs/api/` and `API_CONTRACT.md`.
  - Controllers Verified:
    1. `PositionSimilarityController` (`/api/positions/similar`)
       - Parameters: `fen` (orthodox FEN string), `accountId` (UUID?), `username` (String?), `minScore` (Double?, $\ge 0.0$), `limit` (Int, 1..100, default 20), `depth` (Int, 4..12, default 8), `multiPv` (Int, 1..5, default 3), `mode` (String, `STRUCTURAL` or `DYNAMIC`).
       - Validations: Negative `minScore` (< 0.0) returns HTTP 400. Unsupported `mode` returns HTTP 400. Malformed `fen` returns HTTP 400.
       - Response Contract: `SimilarPositionsResponse` containing `queryFen`, `candidatesEvaluated`, and `results` array with `rank`, `score`, `positionId`, `fen`, `occurrences`.
    2. `WeaknessController` (`/api/positions/weaknesses`)
       - Parameters: `playerColor` (PlayerColor enum), `minEvalLoss` (Double?, $\ge 0.0$), `minMistakeCount` (Int, $\ge 1$), `page` (Int, $\ge 0$), `size` (Int, 1..100).
       - Validations: Negative `minEvalLoss` (< 0.0) returns HTTP 400. Zero or negative `minMistakeCount` (< 1) returns HTTP 400.
       - Response Contract: Pageable list of `WeaknessResponse` containing `positionId`, `fen`, `playerColor`, `mistakeCount`, `averageLoss`.
    3. `RecurringWeaknessController` (`/api/weaknesses/recurring`)
       - Endpoints:
         - GET `/api/weaknesses/recurring`: Parameters `accountId` (UUID?), `username` (String?). At least one must be provided; omitting both returns HTTP 400.
         - GET `/api/weaknesses/recurring/clusters/{clusterId}/positions`: Parameters `clusterId` (UUID path variable), `page` (Int, $\ge 0$), `size` (Int, 1..100), `playableOnly` (Boolean, default false).
       - Response Contract: List of `RecurringWeaknessResponse` and `ClusterMemberPositionsResponse`.
    4. `LongDecisionController` (`/api/positions/long-decisions`)
       - Parameters: `accountId` (UUID), `timeControl` (TimeControl enum), `minDurationSeconds` (Int?), `thresholdSeconds` (Int?), `severity` (DecisionSeverity?).
       - Validations: Missing both `minDurationSeconds` and `thresholdSeconds` returns HTTP 400. Invalid `timeControl` string returns HTTP 400.
       - Response Contract: `LongDecisionPageResponse` containing `content` array with `positionId`, `fen`, `decisionTimeMs`, `timeControl`.
- **test**: `backend/src/test/kotlin/com/chessprobe/contract/ControllerContractVerificationTest.kt`
  - Test command: `./gradlew test --tests "com.chessprobe.contract.*"`

---

### 4. Dual-Tenant PostgreSQL & Cache Isolation Harness
- **contract**: `backend/src/test/kotlin/com/chessprobe/isolation/DualTenantRepositoryIsolationTest.kt`
  - Seeds distinct records for Tenant A and Tenant B across all 10 user/account-scoped repositories:
    1. `GameRepository`
    2. `PositionOccurrenceRepository`
    3. `UserPositionWeaknessRepository`
    4. `UserPositionStatsRepository`
    5. `TrainingAttemptRepository`
    6. `PuzzleSchedulingEventRepository`
    7. `AsyncJobRepository`
    8. `ImportedArchiveRepository`
    9. `ArchiveDerivedProcessingRepository`
    10. `AccountConnectionRepository`
  - Asserts Tenant A queries return 0 records belonging to Tenant B.
  - Asserts mutating or deleting Tenant A data never alters Tenant B state.
- **contract**: `backend/src/test/kotlin/com/chessprobe/isolation/DualTenantCacheAndSecurityIsolationTest.kt`
  - Validates `DynamicSimilarityCache` partitions cache entries strictly by `accountId` (UUID key), ensuring Tenant A documents in `DynamicInvertedIndex` cannot be queried by Tenant B.
  - Validates controller security: Requesting private tenant endpoints (`/api/accounts/{id}/connection`, `/api/games/import`, `/api/positions/similar` with explicit `accountId`) using Tenant A session authentication against Tenant B `accountId` returns HTTP 403 Forbidden.
- **test**:
  - `backend/src/test/kotlin/com/chessprobe/isolation/DualTenantRepositoryIsolationTest.kt`
  - `backend/src/test/kotlin/com/chessprobe/isolation/DualTenantCacheAndSecurityIsolationTest.kt`
  - Test command: `./gradlew test --tests "com.chessprobe.isolation.*"`

---

### 5. Deterministic Repository Total-Ordering Slice Tests
- **contract**: `backend/src/test/kotlin/com/chessprobe/repository/RepositoryDeterministicTotalOrderingSliceTest.kt`
  - Path reconciliation: Verified `PositionSimilarityScorer.kt` is located at `backend/src/main/kotlin/com/chessprobe/similarity/PositionSimilarityScorer.kt`.
  - Ordering key & SQL alias verification:
    - `PositionOccurrenceRepository.findLongDecisionOccurrences`:
      - SQL alias: `po`
      - Primary sort: `po.decisionTimeMs DESC`
      - Secondary sort: `CASE WHEN g.playedAt IS NULL THEN 1 ELSE 0 END ASC, g.playedAt DESC`
      - Tie-breaker: `po.id ASC`
      - Slice test inserts 10 occurrences with identical `decisionTimeMs` and identical `playedAt`, asserting returned page is strictly ordered by `po.id ASC`.
    - `EngineAnalysisRepository.findByPositionIdInWithMoveEvaluations`:
      - SQL alias: `e`
      - Query: `SELECT DISTINCT e FROM EngineAnalysis e LEFT JOIN FETCH e.moveEvaluations WHERE e.position.id IN :positionIds`
      - Ordering key: `e.id ASC` (independent from `po.id`)
      - Slice test inserts 10 engine analysis records and verifies deterministic batch retrieval ordered by `e.id ASC`.
- **test**: `backend/src/test/kotlin/com/chessprobe/repository/RepositoryDeterministicTotalOrderingSliceTest.kt`
  - Test command: `./gradlew test --tests "com.chessprobe.repository.RepositoryDeterministicTotalOrderingSliceTest"`

---

### 6. Fault-Injection Negative State Harness
- **contract**: `backend/src/test/kotlin/com/chessprobe/resilience/RecurringWeaknessDiscoveryFaultInjectionTest.kt`
  - Simulates clustering failures during recurring weakness discovery:
    - Injects `RuntimeException("Simulated cluster failure")` into clustering snapshot resolution.
    - Injects failure where `PositionClusteringService` returns failure diagnostics.
  - Asserts job transitions to `DiscoveryJobStatus.FAILED` with explicit `errorMessage` detailing the failure.
  - Asserts job never transitions to `COMPLETED` when clustering fails.
- **test**: `backend/src/test/kotlin/com/chessprobe/resilience/RecurringWeaknessDiscoveryFaultInjectionTest.kt`
  - Test command: `./gradlew test --tests "com.chessprobe.resilience.*"`

---

### 7. UOBS-20 Similarity Retrieval Hardening & Regressions
- **contract**: `backend/src/main/kotlin/com/chessprobe/similarity/PositionSimilarityScorer.kt` & `backend/src/main/kotlin/com/chessprobe/service/similarity/PositionSimilarityService.kt`
  - **Feature 1: Opening-Selection Query Correctness**:
    - In `PositionSimilarityScorer.kt`: When scoring candidates for early opening positions (e.g. Sicilian sample `rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2`), candidates matching central pawn structure tokens (`e4`, `c5`) are prioritized over the ply-0 standard starting position (`rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1`), ensuring authentic Sicilian positions outrank the un-diverged starting position.
  - **Feature 2: Cross-Game Result Diversity**:
    - Adds parameter `maxPositionsPerGame: Int = DEFAULT_MAX_POSITIONS_PER_GAME` (default 3) to `PositionSimilarityService.findSimilarPositions(...)`.
    - When shortlisting candidates in both structural (`findStructuralSimilarPositions`) and dynamic (`buildRerankedDynamicResult`) modes:
      - Tracks accepted position count per `game.id` from qualifying occurrences.
      - If candidate position occurrences only belong to games that have reached `maxPositionsPerGame`, caps inclusion while strictly preserving descending rank order across eligible games.
- **test**: `backend/src/test/kotlin/com/chessprobe/service/similarity/PositionSimilarityServiceTest.kt`
  - `opening query correctness: searching with Sicilian sample FEN does not rank starting position above authentic Sicilian positions`
  - `cross-game diversity: results cap positions per game at maxPositionsPerGame while preserving monotonic rank ordering`
- **test command**: `./gradlew test --tests "com.chessprobe.service.similarity.PositionSimilarityServiceTest"`

---

## Invariants

1. Real PostgreSQL database must be used for all integration tests; in-memory H2 is strictly forbidden for PostgreSQL integration tests.
2. Singleton Testcontainers container must start once per test worker JVM and be cleanly reused across test classes.
3. Tenant cleanup hook must execute strictly in child-to-parent order per verified foreign key constraints in `V1__baseline.sql`.
4. After `cleanTenantData()`, `assertTenantQuarantined()` must assert zero residual rows across all 18 tables.
5. Injected clustering failures in `RecurringWeaknessDiscoveryService` must transition jobs to `FAILED`, never `COMPLETED`.
6. Similarity shortlisting must never return more than `maxPositionsPerGame` positions from any single game when diverse candidates exist.

---

## Runtime Budget & Performance Measurement

- **Target Runtime**: 2m30s ($\le 150\text{s}$) for the entire backend test suite is a **measurement and performance target**, NOT a correctness constraint or failing test assertion.
- **Reporting Telemetry**:
  - The test harness and documentation will report:
    1. **Cold-start execution time**: Container image initialization, startup, and Flyway baseline migration execution.
    2. **Warm execution time**: Test class and slice execution with the container already active and connection pool warmed.
  - This distinguishes container provisioning latency from application test execution overhead.

---

## Working Notes & Reviewer Synthesis

- Reviewer triggers fired:
  - Trigger 1: `spec-compliance`
  - Trigger 3: `consistency`
  - Trigger 5: `safety`
- Reviewer synthesis summary:
  - Addressed complete tenant identity derivation algorithm covering all five cases (user-only, account-only, linked, unlinked, guest-account).
  - Derived target IDs (`derivedSnapshotIds`, `derivedArchiveIds`) before parent deletion to prevent quarantine false-greens.
  - Scoped worker quiescence draining to test tenant IDs or registered job IDs with explicit timeout failure policy.
  - Completed database inventory audit classifying H2 tests (unit vs. integration requiring PostgreSQL vs. transient auth slice).
  - Explicitly mapped required test migrations (`GameImportServiceBaseIntegrationTest`, `ParallelGameImportConcurrencyTest`, `AsyncJobConcurrencyIntegrationTest`, `DerivedBatchBoundaryIntegrationTest`, `Issue417AnalysisTerminalStateIntegrationTest`, `AccountAssociationConcurrencyIntegrationTest`).
  - Reconciled file paths (`backend/src/main/kotlin/com/chessprobe/similarity/PositionSimilarityScorer.kt`) and verified distinct SQL aliases and ordering keys (`po.id ASC` vs. `e.id ASC`).
  - Clarified scope boundary and terminology to Backend REST API Contract Verification.
  - Clarified 2m30s budget as a measurement target with separate cold-start and warm execution telemetry.
