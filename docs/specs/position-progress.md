# Position Progress

contract:
  - `GET /api/positions/{positionId}/progress?playerColor={WHITE|BLACK}&minEvalLoss={value}` requires an authenticated session and returns progress for the resolved account, exact position, player color, and required mistake threshold.
  - `minEvalLoss` must be a finite positive number. Missing, malformed, zero, negative, NaN, or infinite values return HTTP 400 with the existing structured `VALIDATION_ERROR` body; invalid requests never fall back to a fixed threshold.
  - `positionId` and point `checkpointId` are UUIDs; `playerColor` is `WHITE` or `BLACK`.
  - `baseline` is null or has an ISO-8601 `occurredAt`, finite 0–100 rates, and positive `sourceEncounterCount`.
  - Points have ISO-8601 `occurredAt`, finite 0–100 rates, positive `attempts`, and boolean `open`.
  - `currentIntervalState` uses the three documented lifecycle values; `excludedUndatedEncounters` is a non-negative integer.
  - Change values are finite numbers or null; `assessment` is a string supplied by the backend.
  - Only a persisted `SOLVED` scheduling event for the requesting user, resolved account, position, and color starts a training interval. It represents a correct initial puzzle decision; continuation completion is not required.
  - `PRESENTED`, `STARTED`, `FAILED`, `SKIPPED`, game-scheduling events, and timed-attempt outcomes do not create checkpoints.
  - The baseline contains all dated scoped occurrences before the first checkpoint; without checkpoints, all dated occurrences remain baseline. A baseline exists only when it has at least one dated encounter.
  - Checkpoints are ordered by `(occurredAt, id)`. A checkpoint starts a half-open interval through, but not including, its successor. At tied checkpoint times, only the last event in ID order receives encounters at that timestamp.
  - Each non-empty interval produces one observation identified by its checkpoint UUID. The final interval is open; prior intervals are closed. Empty intervals produce no observation or synthetic rate.
  - `baseline` is null or an object with `occurredAt`, `mistakeRate`, `winRate`, and positive `sourceEncounterCount`. Its timestamp is the latest included baseline game's `playedAt`.
  - Each point has `checkpointId`, `occurredAt`, `mistakeRate`, `winRate`, positive `attempts`, and boolean `open`. Its timestamp is the latest included game's `playedAt`, not its checkpoint timestamp.
  - `currentIntervalState` is `NO_CHECKPOINT`, `OPEN_AWAITING_EVIDENCE`, or `MEASURED_OPEN`. It describes the latest interval; earlier measured closed points can coexist with an empty current interval. Empty older checkpoints are not listed.
  - `excludedUndatedEncounters` is the number of scoped source occurrences whose `Game.playedAt` is null. It is always reported, may be zero, and excludes no unrelated account, position, color, or user data.
  - Mistake classification uses the `minEvalLoss` value from the successful Weaknesses request that produced the selected row. The frontend keeps that value with the accepted result set, snapshots it with the selected position and color, and does not substitute a later global filter value.
  - `mistakeRateChange` and `winRateChange` compare the baseline with the latest measured interval by checkpoint order, including a closed observation if newer intervals are empty. The relative formula is `(latestRate - baselineRate) / baselineRate * 100`.
  - Return null changes when either comparison value is unavailable or its baseline rate is zero.
  - Use the insufficient-evidence assessment when the baseline or measured observation is absent.
  - Otherwise, describe both mistake-rate and win-rate direction relative to the baseline.
  - Compare rates even when a relative change is null because its baseline rate is zero.
  - Name unchanged rates explicitly. Use “and” for opposing movement or any unchanged rate; use “but” for same-direction movement.

invariant:
  - `Game.playedAt` is the game-completion-time proxy and solely determines baseline or interval membership. Never substitute import, creation, persistence, or event time for an undated game.
  - Baseline and intervals independently count every dated source occurrence as one attempt. Reuse `GameOutcomeNormalizer`; wins count as wins, draws and unknown outcomes are attempts but not wins.
  - Mistake loss calculation and fallback evaluation follow Weaknesses; an encounter is a mistake when its loss is greater than or equal to the request's `minEvalLoss`. A dated encounter with no usable direct or fallback evaluation remains an attempt but is not a mistake. Aggregate counts before calculating rates; do not average per-game percentages.
  - Source occurrence identity remains `(game, position, ply, playerColor)`. Re-import, re-analysis, and repeated reads do not multiply attempts; repeated plies remain distinct encounters.
  - Late imports are assigned by `playedAt` to their historical baseline or interval. They may update closed intervals; they never create checkpoints or change checkpoint identity. A point's `occurredAt` moves only when a later qualifying included game is added.
  - Relative changes and assessments compare the baseline with the last measured observation, not an empty latest interval, prior interval totals, or a synthetic zero.
  - The endpoint does not persist derived progress state or introduce a schema migration.

failure:
  - Guest progress requests remain rejected by the existing authentication boundary.
  - Missing or invalid `minEvalLoss` values return HTTP 400 with `error: VALIDATION_ERROR` and details; they do not use a hardcoded threshold.
  - No dated baseline yields `baseline: null`; no dated post-checkpoint games yield an empty interval and no point.
  - Undated occurrences are excluded from rates and attempts and counted in `excludedUndatedEncounters`; never fabricate chronology.
  - Account, position, color, or user mismatches contribute neither checkpoints nor excluded-undated counts.

convention:
  - Keep interval construction deterministic and in memory over existing personal event and occurrence reads.
  - The frontend displays baseline as separate historical context and charts every measured interval.
  - Chronological labels identify each interval's number, encounter count, and date.
  - Omit open/closed wording from chart labels and tooltips; show status in the latest summary.
  - Do not show a separate `MEASURED_OPEN` message. Retain no-checkpoint and awaiting-evidence states.
  - Use the backend-provided assessment and relative changes without recalculating them in the client.
  - The Weaknesses Library opens the exact position's progress detail and returns to the library; color follows the position's side to move.
  - Check session status before requesting progress; do not fetch for unauthenticated or unverifiable sessions.
  - Distinguish session checking, sign-in, session verification, request loading, retryable request failure, and empty interval states.
  - Use an accessible dependency-free SVG chart; keep the historical baseline separate from measured interval observations.
