# Evaluation-evidence producer

`EvaluationEvidenceProducerService` creates immutable #430 snapshots from
persisted operational evaluation evidence and a caller-selected reference
population.

## Input and scope

- `EvaluationEvidenceProducerInput` contains a player roster, selected
  operational `PositionOccurrence` IDs, a selected reference population, and
  frozen snapshot configuration.
- The input copies its collections into unmodifiable sets and rejects duplicate
  operational occurrence IDs.
- The roster is non-empty and authoritative, including players with no
  evidence rows.
- Operational membership comes only from the supplied occurrence IDs.
- The producer never expands scope by account, game, job, request, date, or
  current database membership.
- Each selected occurrence must exist and belong to a roster player.
- Selected occurrences hydrate their persisted game, position, and account.
- Engine analysis and move evaluations are loaded for the selected positions.
- Each selected position must have one engine analysis and each selected move
  must have one matching move evaluation.

## Evidence construction

- Reference matches use exact `Position.hash` and pre-move ply.
- Matching is limited to the selected source run, content digest, covered
  prefix, and qualifying ordinals through `prefixN`.
- Every matching #431 occurrence produces a row for each selected operational
  game/decision; move equality is not required.
- Row identity is `(snapshotId, playerId, gameId, occurrenceId)`.
- Player, game, occurrence, position, ply, move, color, depth, and observed
  outcome come from the selected records and matching reference occurrence.
- Observed outcomes use `GameOutcomeNormalizer`; an unavailable outcome fails
  closed.
- `objectiveOutcome`, all five practical-evidence fields, `occurrenceEvidenceId`,
  `sourceRevision`, `engineIdentity`, and `parserIdentity` are absent.
- Objective classification, practical-evidence generation, and provenance
  fabrication are outside this producer.

## Loss and snapshot configuration

- Loss requires both persisted centipawn inputs used by the existing
  deterministic calculation.
- A persisted loss is accepted only when both inputs are present.
- A missing loss is derived only through `EngineAnalysisService.calculateEvalLoss`.
- Missing inputs, non-finite loss, or negative loss fail closed; missing evidence
  never becomes zero.
- Snapshot thresholds are `{0.30, 0.50, 0.80}` and platform is `CHESS_COM`.
- `minMistakeCount`, `minTimesReached`, and snapshot color are explicit frozen
  input values; counts are nonnegative and color is `WHITE`, `BLACK`, or `BOTH`.
- `observationWindowDays` is null.
- Snapshot configuration does not filter operational rows.
- The producer adds no aggregation-mode field.

## Verification and persistence

- The public producer method owns a read-write `REPEATABLE_READ` transaction.
- It reads selected evidence, constructs rows and the complete occurrence-ID
  set, then calls `SelectedPopulationVerificationService.verify`.
- The producer validates the returned population, exact occurrence-ID set,
  source binding, position hash, and pre-move ply against every proposed row.
- #450 verification completes before the first #430 write.
- #450 verification and #430 persistence join the producer transaction through
  `REQUIRED` propagation.
- The producer calls `EvaluationEvidenceSnapshotService.persist` once.
- Failures propagate; transaction rollback leaves no partial snapshot or rows.
- #430 retains responsibility for canonicalization, digest, immutability, and
  retry-conflict behavior.
