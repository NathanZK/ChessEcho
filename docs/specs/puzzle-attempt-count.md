# Puzzle submitted-attempt counts

## Meaning and scope

- **Failed** and **Solved** count submitted legal initial puzzle answers separately by their persisted FAILED/SOLVED outcomes.
- Scope is the authenticated user, selected account, position ID, and player color.
- A fresh initial answer after reset or undo counts again, including the same move.
- Illegal moves, hints, navigation, reset/undo/redo alone, continuation moves, and timer telemetry do not count.
- Source-game `timesReached`, `mistakeCount`, and Position Progress encounters remain separate.
- Guests and unresolved identities/accounts omit both personal counts.

## Persistence and API

- One grouped `puzzle_scheduling_event` aggregate supplies both counts: caller-owned `SOLVED`/`FAILED` rows without source occurrences.
- Both values use one snapshot; an absent outcome group returns zero.
- Existing attributable rows count once per row. Missing or duplicated historical submissions cannot be reconstructed reliably.
- Identified answers store paired `submission_id` UUID and `submitted_move` canonical SAN.
- Database uniqueness on `(app_user_id, submission_id)` makes sequential and concurrent delivery replay-safe.
- Matching replay returns the original receipt; changed context, move, or outcome returns `409 PUZZLE_SUBMISSION_CONFLICT`.
- Atomic insertion and separate receipt lookup run in a READ COMMITTED transaction.
- Legacy writes without the paired fields retain their existing response and event semantics.
- `POST /api/puzzles/events` accepts the optional paired identity/move and returns `202 { submissionId }` for identified answers.
- `GET /api/puzzles/{positionId}/attempt-count?accountId=UUID&playerColor=WHITE|BLACK` returns `{ solvedCount, failedCount }`, not `attemptCount`.
- Backend values are nonnegative Long integers; frontend values must also be safe integers, or the pair is unavailable without rounding.
- The principal supplies user identity; existing shared-account resolution applies without introducing a connection requirement.
- Authentication, validation, unknown account, and missing occurrence errors follow the [API contract](../../API_CONTRACT.md).
- Schema follows the pre-deployment V1 baseline convention.

## Frontend lifecycle

- `usePuzzleAttemptCount` owns authoritative reads and immutable pending answers.
- Submission UUIDs are independent of timer attempt IDs.
- Successful writes reread both counts without refetching puzzle lists or resetting the board.
- Read generations prevent stale responses from replacing another context or a newer read.
- Puzzle provenance gates initial answers during replacement account/session loads.
- Loading, read failure, recording, and uncertain recording are explicit; failures never fabricate either zero.
- Manual network/5xx retries reuse the exact payload and identity; terminal 4xx responses do not offer write retry.
- Pending answers survive puzzle/account navigation in memory, remain hidden outside their context, and clear on logout/user change.
- Browser reload recovers recorded outcome counts, not unsaved pending answers.

## Verification boundaries

- PostgreSQL tests cover legacy 2/3 counts, zero/absent outcomes, isolation, exclusions, both-outcome replay/concurrency, and one grouped statement.
- HTTP tests cover authentication, binding, errors, receipt shape, and legacy compatibility.
- Frontend API, hook, and production-page tests cover 0/0 -> 0/1 -> 1/1 -> reload 1/1, repeated answers, retries, races, timers, and guests.
- Running-app screenshots establish appearance and board interaction; mocked API screenshots do not establish database persistence.
