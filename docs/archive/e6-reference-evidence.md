# Expanded E6 reference-evidence contract (Issue #428)

This document defines the minimum occurrence-level evidence that a retained
reference corpus must preserve for the expanded E6 analyses. It is a contract
for the lossless acquisition follow-up in #431; it does not change the
acquisition behavior from #423, the portable aggregate artifact from #426, or
the original experiment in #75.

## Ownership and evidence boundary

The reference evidence is immutable and scoped to one source run. A source run
is identified by its existing run identity and provenance. Within that run,
`(sourceRunId, qualifyingOrdinal)` identifies one source game. The
`qualifyingOrdinal` is the contiguous, one-based ordinal already enforced by
the corpus acquisition contract.

The semantic identity of one retained occurrence is:

```text
(sourceRunId, qualifyingOrdinal, preMovePly)
```

`sourceRunId` and `qualifyingOrdinal` establish the source-game boundary;
`preMovePly` identifies one decision point within that game. A source-local
game UUID may identify a row in the acquisition database, but it is not a
portable position identity and must not replace the source-run/ordinal
identity when evidence is exported or compared.

Reprocessing or retrying the same source occurrence must resolve to the same
semantic identity. Occurrences at different plies in one game remain distinct
even when their position hash and SAN move are equal.

## Required occurrence facts

For every retained occurrence, the evidence must preserve or deterministically
establish all of the following:

- **Pre-move ply:** `preMovePly` is one-based and identifies the position before
  the move is played. Odd plies are White decisions and even plies are Black
  decisions.
- **Move side:** the side must be unambiguous. It may be derived from the
  attributed game side and ply parity when that derivation is sufficient.
- **Full move number:** when needed for presentation or analysis,
  `fullMoveNumber = (preMovePly + 1) / 2`, using integer division.
- **SAN:** `movePlayed` is the SAN move made from the retained pre-move
  position.
- **Portable position identity:** the position hash is calculated from the
  first four normalized FEN fields used by the existing system: board, side to
  move, castling rights, and en passant target. Halfmove and fullmove
  counters are excluded.
- **Agreement invariant:** the hash, SAN, derived side, and ply must describe
  the position and move at the occurrence identity above. They must not be
  recomputed from a later aggregate row whose location is unknown.

No redundant `player_color`, FEN, move-number, or other persisted field is
required when the existing canonical position and game data deterministically
establish the invariant. An implementation may retain additional data only
when it is necessary to enforce or verify this contract.

## Counting semantics

The evidence must keep these quantities distinct:

- **Occurrence count:** the number of retained occurrence identities. Repeated
  visits to the same position, including visits at different plies, count
  separately.
- **Distinct-position count:** the number of unique portable position hashes
  in the selected source scope. Repeated occurrences of one position count
  once.
- **Unique-game count:** the number of unique
  `(sourceRunId, qualifyingOrdinal)` identities containing the selected
  position or occurrence set. A source-local UUID is only an implementation
  reference for this same source-game identity.
- **Evaluation-player count:** the number of distinct evaluation players
  represented by the selected evidence, using the attributed player identity
  already associated with the source occurrence. This is not interchangeable
  with unique games or occurrences.

Any analysis reporting more than one of these quantities must name the
quantity and its source scope. Grouping by `(positionHash, movePlayed)` may be
used to derive an aggregate distribution, but it must occur only after
occurrence evidence has been retained and must not be treated as the
occurrence identity.

## Repeated occurrences and determinism

Two occurrences with equal position hash and equal SAN are still different
occurrences when their `preMovePly` values differ. The implementation must
retain both under the same source game and must not collapse them into one
row merely because the aggregate key is equal. Conversely, repeated
occurrences do not create distinct positions: they contribute multiple
occurrences and one distinct position.

The same source run, qualifying ordinal, and pre-move ply must be idempotent
under retry and reprocessing. A different source run is a different immutable
evidence scope even if it contains the same source game or position. Source
boundaries must therefore be part of every counting and comparison scope.

## Compatibility and eligibility

The existing #423/#426 aggregate observation contract remains valid for the
analyses it originally supports. Its grouped
`(positionHash, movePlayed, observationCount)` rows do not prove occurrence
locations. An old aggregate-only row is therefore **ineligible** for an
expanded location-, repeated-occurrence-, or occurrence-count analysis unless
the required source run, qualifying ordinal, pre-move ply, SAN, side, and
canonical hash facts can be independently proven from immutable evidence.

This contract is a compatibility boundary, not a request to change #426:
the #426 artifact format, import/export APIs, materialization behavior, and
portable aggregate fields remain unchanged. #431 owns the lossless
reference-persistence implementation that supplies this contract. #428 does
not introduce an artifact format, portability API, evaluation evidence,
manifest, analysis version, or provenance framework.
