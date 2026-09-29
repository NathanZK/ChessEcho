# Selected-population verification boundary

scope: Issue #450 only. Add a callable read-only verification boundary for a selected retained population before a producer persists evaluation evidence. Do not implement or modify the #445 producer.

## Decisions

contract: A caller supplies an `EvaluationReferencePopulation` and the occurrence IDs it intends to use. The boundary resolves exactly one `HumanMoveCorpusProjection` by `(contentDigest, sourceRunId, prefixN, ratingBand, minObservations, calculationVersion)`. `distributionSha256` and `coveredPrefix` are not projection lookup keys.

invariant: The selected population must have a non-null distribution digest, a positive `prefixN`, and `coveredPrefix >= prefixN`. The resolved projection must match all six lookup fields, be finalized and verified, and have a non-null stored digest equal to the caller-supplied digest.

test: Unit coverage rejects `prefixN <= 0` and `coveredPrefix < prefixN`. PostgreSQL integration coverage rejects an absent or mismatched projection, an unfinalized or unverified projection, a missing digest, and a caller digest mismatch. Ambiguous identity fails closed; the database's unique six-field projection constraint prevents duplicate identities.

contract: Recompute the resolved projection's digest using `HumanMoveCorpusProjectionFinalizationService.digestProjection(...)` and require it to equal the stored digest. Do not call `finalize(...)` or introduce another digest algorithm.

invariant: Projection identity, stored digest, recomputed digest, artifact binding, and requested occurrence resolution are checked in one read-only repeatable-read transaction.

test: Integration coverage rejects a stored digest that does not match the current projection rows and verifies the boundary makes no database writes.

contract: Call `HumanMoveCorpusOccurrenceService.verifyRequiredBinding(sourceRunId, contentDigest, coveredPrefix)` for artifact, source contribution, occurrence evidence, and binding verification. Do not duplicate this authority.

invariant: Artifact `coveredPrefix` proves artifact-level completeness only. It never substitutes for the selected projection's narrower `prefixN`.

test: Integration coverage rejects a binding/artifact mismatch and separately verifies that an occurrence with `prefixN < qualifyingOrdinal <= coveredPrefix` is not returned as resolved.

contract: Expose `verify(selectedPopulation: EvaluationReferencePopulation, occurrenceIds: Set<UUID>): VerifiedSelectedPopulationContext`. The result contains `population: EvaluationReferencePopulation`, `projection: HumanMoveCorpusProjection`, `binding: HumanMoveCorpusOccurrenceBinding`, and `occurrences: Map<UUID, E6ReferenceOccurrence>`. Resolve supplied IDs only when their rows match the selected source run and artifact metadata and satisfy `qualifying_ordinal <= selectedPopulation.prefixN`. Reject if any requested ID is absent or outside scope; never return a partial result as success.

invariant: Every occurrence returned to the producer has `qualifyingOrdinal <= prefixN`, even if the artifact binding covers a larger prefix. An empty requested ID set may resolve to an empty map, but does not skip population, digest, or binding checks.

test: Integration coverage proves a valid request returns all and only requested in-prefix occurrences, an occurrence beyond `prefixN` is rejected even when within `coveredPrefix`, and an empty request still verifies the selected population.

contract: Every failed verification raises an integrity failure before returning a verified context. The boundary itself performs no persistence, finalization, or other mutation.

invariant: #450 supplies a callable pre-persistence boundary; #445 remains responsible for calling it before `EvaluationEvidenceSnapshotService.persist(...)` and for transactional rollback guarantees.

test: Boundary integration tests assert rejection does not create or alter #430 snapshot or row records. Producer ordering and zero-persistence behavior remain #445 integration acceptance.

contract: Transaction semantics: The verifier participates in the caller's outer transaction (propagation = REQUIRED, not REQUIRES_NEW). The caller (#445) provides the transaction and isolation level. The verifier uses `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, propagation=Propagation.REQUIRED)`, matching repository conventions for read-only verification within a workflow (see `E6AnalysisEvidenceService.loadAndAdmit`, `EvaluationEvidenceSnapshotService.readVerifiedPersisted`).

invariant:
- Visibility: The verifier observes the caller's uncommitted reads and writes within the outer transaction's repeatable-read snapshot. This is correct: #450 and #445 are steps within the same workflow and must see each other's in-transaction state.
- Database snapshot: Verification and subsequent persistence both execute against the same repeatable-read snapshot established by the outer transaction. This prevents time-of-check/time-of-use gaps.
- Atomicity: Successful verification does not commit; instead, control returns to #445, which decides whether to persist or rollback. Verification failures raise exceptions and trigger #445's rollback semantics.
- Rollback responsibility: All transactional boundaries and rollback decisions belong to #445. The verifier is a read-only step that reports success or raises an integrity exception.

test: Caller-owned transaction semantics are verified by the fact that all integration tests pass within a single outer transaction scope. Tests verify that verification and no-mutation assertions are consistent within that scope. Producer-integration tests (with #445) will establish that the shared transaction does not cause visibility anomalies.

convention: Kotlin/Spring Boot service in the existing backend service layer; use the projection repository's six-field finder, existing integrity exceptions and binding service, read-only transaction conventions, and PostgreSQL integration-test patterns. No schema change. Validation is `./gradlew ktlintCheck` and `./gradlew test`.

deferred: #445 producer invocation and transaction wiring, #429/#440 admission behavior, position/move membership against retained projection rows, all schema changes, and any changes to projection, occurrence, artifact, BFS, or Fidenaut behavior.

## Working notes

The issue provides enough behavioral detail to design the boundary. The selected implementation approach is a new small service that composes the repository lookup, existing projection digest and occurrence-binding authorities, and a narrowly scoped occurrence-ID query.

Alternatives considered:

- Extending `E6AnalysisEvidenceService.loadAndAdmit(...)` would leave the proof after #430 persistence and risk changing #429 admission.
- Adding this proof inside `EvaluationEvidenceSnapshotService.persist(...)` would expand #450 into #430 persistence behavior and couple the boundary to storage.
- Comparing only caller and stored digests is permitted by the issue, but recomputing with the existing `digestProjection(...)` provides the same defense-in-depth as current E6 admission without a new algorithm.

No #445 producer call site exists in this repository state. #450 therefore defines and verifies the reusable boundary; its caller-ordering and rollback behavior must be accepted with #445.
