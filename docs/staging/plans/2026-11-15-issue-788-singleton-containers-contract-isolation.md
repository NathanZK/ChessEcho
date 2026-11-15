# Plan: Singleton Testcontainers Base Class, Contract Verification, Dual-Tenant Isolation & Persistence Harness (#788)

Spec: [docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md](../specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md)

## Milestone Tasks

- [x] T1: Implement AbstractIntegrationTest singleton Testcontainers base class, scoped worker drain, and 18-table tenant cleanup
  goal: Establish a reusable JVM-singleton PostgreSQL 16 Testcontainers base class with tenant-scoped async worker quiescence draining, transaction-wrapped 18-table topological cleanup with full identity derivation covering all 5 configurations (user-only, account-only, linked, unlinked, guest), and zero-residual quarantine assertions.
  files: backend/src/test/kotlin/com/chessprobe/AbstractIntegrationTest.kt, backend/src/test/kotlin/com/chessprobe/AbstractIntegrationTestLifecycleTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.AbstractIntegrationTestLifecycleTest"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#1-singleton-postgresql-testcontainers-base-class-scoped-worker-draining--tenant-cleanup-harness

- [x] T2: Migrate GameImportService integration suite and concurrency tests away from H2 to PostgreSQL Testcontainers
  goal: Migrate GameImportServiceBaseIntegrationTest to inherit from AbstractIntegrationTest (migrating its 6 subclasses), re-base ParallelGameImportConcurrencyTest removing the artificial start-position workaround, and migrate standalone async concurrency tests to enforce the PostgreSQL integration invariant.
  files: backend/src/test/kotlin/com/chessprobe/integration/service/GameImportServiceBaseIntegrationTest.kt, backend/src/test/kotlin/com/chessprobe/concurrency/ParallelGameImportConcurrencyTest.kt, backend/src/test/kotlin/com/chessprobe/integration/service/AsyncJobConcurrencyIntegrationTest.kt, backend/src/test/kotlin/com/chessprobe/integration/service/DerivedBatchBoundaryIntegrationTest.kt, backend/src/test/kotlin/com/chessprobe/integration/service/Issue417AnalysisTerminalStateIntegrationTest.kt, backend/src/test/kotlin/com/chessprobe/integration/controller/AccountAssociationConcurrencyIntegrationTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.concurrency.ParallelGameImportConcurrencyTest" --tests "com.chessprobe.integration.service.*"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#2-database-inventory-audit--test-migration-to-postgresql

- [x] T3: Implement Backend REST API Contract Verification Tests
  goal: Implement contract verification tests for active REST controllers (PositionSimilarityController, WeaknessController, RecurringWeaknessController, LongDecisionController) asserting query parameter parsing, HTTP 400 boundary rejection, and JSON response schema structure.
  files: backend/src/test/kotlin/com/chessprobe/contract/ControllerContractVerificationTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.contract.*"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#3-backend-rest-api-contract-verification

- [x] T4: Implement Dual-Tenant Repository and Cache Isolation Tests
  goal: Implement PostgreSQL dual-tenant tests across all 10 user-scoped repositories and in-memory caches asserting zero cross-tenant data leakage and HTTP 403 Forbidden enforcement.
  files: backend/src/test/kotlin/com/chessprobe/isolation/DualTenantRepositoryIsolationTest.kt, backend/src/test/kotlin/com/chessprobe/isolation/DualTenantCacheAndSecurityIsolationTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.isolation.*"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#4-dual-tenant-postgresql--cache-isolation-harness

- [x] T5: Implement Deterministic Repository Total-Ordering Slice Tests
  goal: Implement slice tests for PositionOccurrenceRepository (po.id ASC tie-breaker) and EngineAnalysisRepository (e.id ASC ordering key) verifying deterministic total ordering.
  files: backend/src/test/kotlin/com/chessprobe/repository/RepositoryDeterministicTotalOrderingSliceTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.repository.RepositoryDeterministicTotalOrderingSliceTest"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#5-deterministic-repository-total-ordering-slice-tests

- [x] T6: Implement RecurringWeaknessDiscovery Fault-Injection Negative State Harness
  goal: Implement fault-injection tests asserting that injected clustering exceptions transition jobs to FAILED status with error details and never COMPLETED.
  files: backend/src/test/kotlin/com/chessprobe/resilience/RecurringWeaknessDiscoveryFaultInjectionTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.resilience.*"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#6-fault-injection-negative-state-harness

- [x] T7: Implement UOBS-20 Opening Query Correctness and Cross-Game Diversity
  goal: Update PositionSimilarityScorer and PositionSimilarityService to ensure authentic Sicilian positions outrank orthodox start and enforce maxPositionsPerGame diversity cap.
  files: backend/src/main/kotlin/com/chessprobe/similarity/PositionSimilarityScorer.kt, backend/src/main/kotlin/com/chessprobe/service/similarity/PositionSimilarityService.kt, backend/src/test/kotlin/com/chessprobe/service/similarity/PositionSimilarityServiceTest.kt
  acceptance: cd backend && ./gradlew test --tests "com.chessprobe.service.similarity.PositionSimilarityServiceTest"
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#7-uobs-20-similarity-retrieval-hardening--regressions

- [x] T8: Full Backend Validation, Runtime Measurement, and Knowledge Graph Update
  goal: Execute complete backend validation (ktlint, detekt, test), measure separate cold-start and warm execution times against the 2m30s target, and update the local Graphify knowledge graph.
  files: backend/, graphify-out/
  acceptance: cd backend && ./gradlew ktlintCheck detektMain detektTest test && cd .. && graphify update .
  spec: docs/staging/specs/2026-11-15-issue-788-singleton-containers-contract-isolation.md#runtime-budget--performance-measurement
