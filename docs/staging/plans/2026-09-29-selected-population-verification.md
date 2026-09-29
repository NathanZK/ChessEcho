# Selected-population verification boundary

goal: Provide #445 a verified selected-population context before evidence persistence.
spec: docs/staging/specs/2026-09-29-selected-population-verification.md

- [x] T1: Implement the read-only verification boundary
  goal: Resolve and verify a selected population and its requested occurrences through the declared existing authorities.
  files: `src/main/kotlin/com/chessecho/service/SelectedPopulationVerificationService.kt`, `src/test/kotlin/com/chessecho/service/SelectedPopulationVerificationServiceTest.kt`
  acceptance: `./gradlew test --tests com.chessecho.service.SelectedPopulationVerificationServiceTest`; cover invalid prefix bounds and fail-closed behavior for missing required selection data.
  spec: `docs/staging/specs/2026-09-29-selected-population-verification.md#decisions`

- [x] T2: Verify projection and occurrence boundaries in PostgreSQL
  goal: Prove the verifier's digest, artifact-binding, prefix-scope, and read-only guarantees against persisted corpus data.
  files: `src/test/kotlin/com/chessecho/integration/corpus/SelectedPopulationVerificationPostgresIntegrationTest.kt`
  acceptance: `./gradlew test --tests com.chessecho.integration.corpus.SelectedPopulationVerificationPostgresIntegrationTest`; cover valid in-prefix resolution, unfinalized/unverified projections, missing or mismatched stored/caller/recomputed digests, missing or mismatched projection and binding, rejection beyond `prefixN` but within `coveredPrefix`, and empty-ID verification. Compare the relevant projection, projection-row, occurrence, and binding state before and after successful and rejected calls to prove the verifier itself performs no writes; do not involve #430 snapshot or row persistence.
  spec: `docs/staging/specs/2026-09-29-selected-population-verification.md#decisions`

- [x] T3: Run backend quality and regression checks
  goal: Confirm the completed boundary preserves the backend's declared quality bar.
  files: `src/main/kotlin/com/chessecho/service/SelectedPopulationVerificationService.kt`, `src/test/kotlin/com/chessecho/service/SelectedPopulationVerificationServiceTest.kt`, `src/test/kotlin/com/chessecho/integration/corpus/SelectedPopulationVerificationPostgresIntegrationTest.kt`
  acceptance: `./gradlew ktlintCheck && ./gradlew test`
  spec: `docs/staging/specs/2026-09-29-selected-population-verification.md#decisions`
