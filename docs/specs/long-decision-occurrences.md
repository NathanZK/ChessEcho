# Long-decision occurrences

## Decision duration

- `PositionOccurrence.decisionTimeMs` is nullable and stores the account player's elapsed decision duration.
- The importer associates each post-move PGN `%clk` annotation with its main-line ply.
- Supported PGN `TimeControl` values contain one positive base time, optionally followed by a positive Fischer increment: `base` or `base+increment`.
- For the player's first move, duration is `base + increment - postMoveClock`.
- For later moves, duration is `previousOwnPostMoveClock + increment - currentPostMoveClock`.
- A missing increment contributes zero. Arithmetic is in milliseconds and does not imply greater precision than the source clock.
- Clock values accept non-negative `h:mm:ss` or `h:mm:ss.fraction`, with 1–3 fractional digits and minute/second components from 00 through 59.
- Every account-player move needs exactly one parseable clock snapshot. Missing, malformed, ambiguous, unsupported, or negative-derived required timing makes the entire game ineligible.
- An ineligible game stores null decision times for all of its occurrences; ordinary occurrence data remains unchanged.
- Opponent clock snapshots are not required when the main-line comment association remains unambiguous. Repeated snapshots are valid when they produce a non-negative duration.
- Reprocessing refreshes only `decision_time_ms` for existing occurrence identities, including clearing values when a replay is ineligible.

## Query and presentation

- `GET /api/positions/long-decisions` requires an authenticated user, an existing shared-data `accountId`, a broad `timeControl`, and a positive integer `thresholdSeconds`. The account need not be currently connected.
- `Game.timeControl` (Chess.com's `time_class`) selects games; raw PGN `TimeControl` is used only for deriving durations.
- An occurrence qualifies when `decision_time_ms >= thresholdSeconds * 1000`. Correctness, engine evaluation, and recurrence do not affect qualification.
- Results are individual occurrences, paginated by game date descending, then ply and occurrence ID. The API does not aggregate positions.
- The Long Decisions view displays the occurrence's board position, played move, duration, time-control class, opponent, date, ply, and source-game link.
- The feature does not classify move quality, group occurrences on the backend, normalize decision times, or launch training.
