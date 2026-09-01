# Practical Evidence in Weakness Prioritization

## Purpose and evidence boundaries

ChessEcho may use two separate evidence dimensions when deciding which recurring
weakness a player should train first:

- **Objective evidence** describes move quality relative to engine best play.
  The current implementation uses `evalLossFromBest`, mistake frequency, and
  recency. It answers: “How objectively inaccurate is this recurring
  decision?”
- **Practical evidence** describes the empirical win/draw/loss (W/D/L)
  distribution associated with a recurring position or decision in one chess
  account's games. It answers: “What outcomes were observed after this account
  reached this position or played this move?”

Practical evidence is account-specific and associative. It does not prove that a
position or move caused an outcome. It must not replace, mutate, or relabel
global engine evidence. **Weakness priority** is a recommendation order that may
eventually use both dimensions while continuing to expose them separately.

## Identity and sampling unit

A position summary is identified by:

1. chess account;
2. repository position identity: the SHA-256 hash of the first four FEN fields
   (piece placement, active color, castling rights, and en-passant target); and
3. the imported account's player color.

A decision summary additionally includes the historical SAN move. This identity
describes repository behavior; it does not claim that all statistically
relevant positions are equivalent.

The sampling unit is a **distinct eligible game**, not a raw
`PositionOccurrence`. A game contributes at most once to a position summary and
at most once to a given position/decision summary. Repetition of the same board
state in one game therefore cannot multiply that game's outcome or confidence.
Accounts and colors are never pooled implicitly.

## Practical evidence contract

Every practical summary must retain or derive:

- chess account, repository position identity, player color, and optional SAN
  decision identity;
- the observation window and eligible cohort policy, including the declared
  treatment of time control;
- distinct eligible-game count, wins, draws, losses, and excluded/unknown
  count;
- the descriptive empirical score rate:

  ```text
  score rate = (wins + 0.5 × draws) / distinct eligible games
  ```

- the declared comparator used to interpret the observed score rate;
- confidence state, confidence rule, sample-floor policy, and policy version;
- the separate objective metrics and engine-analysis availability considered
  for the same recommendation.

The score rate is a description of observations, not a priority weight. It is
not meaningful without its denominator, cohort, window, comparator, and
confidence context. A follow-up may compare decisions from the same position or
use an appropriately matched account baseline, but it must declare the selected
comparison rather than treating raw score rate as proof that a decision is
harmful.

## Eligibility, exclusions, and outcome normalization

Only completed, supported standard chess games whose result can be normalized
to the imported account's perspective contribute to W/D/L:

- a White-side terminal result is mapped directly to W/D/L when the account
  played White and mapped to the opposite player perspective when the account
  played Black;
- draws remain draws, never losses;
- unknown, missing, malformed, aborted, or unsupported outcomes are excluded
  from the score-rate denominator and reported in the excluded/unknown count;
- unsupported variants and games outside the declared cohort or observation
  window are ineligible.

The current `Game.result` is not yet a canonical account-perspective result:
Chess.com import stores `white.result` even when the imported account is Black.
Production work must therefore define and test normalization, legacy-row
handling, and platform mappings before practical evidence is enabled.

## Confidence and ranking eligibility

Practical evidence has three product states:

- **Insufficient**: fewer distinct eligible games than the configured sample
  floor. This is not zero, neutral, or evidence of no effect. Objective
  behavior remains authoritative and unchanged.
- **Inconclusive/display-only**: the sample floor is met, but uncertainty is too
  broad to distinguish the observation from the declared comparator. The
  evidence may be explained but cannot change ranking.
- **Ranking-eligible**: both the sample floor and the selected confidence rule
  are met. Only this state may influence recommendation order.

The sample floor, interval/confidence method, meaningful-difference rule, and
policy version must be configurable, visible in provenance, and calibrated
before use. This specification intentionally chooses no numeric thresholds.

## Objective/practical conflicts

The four quadrants below apply only when practical evidence is ranking-eligible:

| Objective evidence | Practical evidence | Product handling |
|---|---|---|
| Inaccurate | Poor associated outcomes | Strongest corroborated training case; explain each signal separately. |
| Inaccurate | Successful associated outcomes | Retain the objective weakness. Practical evidence may moderate ordering, but cannot erase or relabel the engine finding. |
| Reasonable | Poor associated outcomes | Label as a **practical concern** or investigation candidate, never as an engine mistake or proof that the move caused losses. |
| Reasonable | Successful associated outcomes | No corroborated weakness; keep low priority unless another declared signal applies. |

Insufficient or inconclusive practical evidence never changes ordering in any
quadrant. A practical-only recommendation must be labeled as such and must not
be described using “pawns lost” or other objective-mistake language. A later
design must decide how practical-only candidates enter retrieval because the
current query starts from recurring engine-classified mistakes.

Practical success may moderate priority only after the confidence gate; it
cannot suppress access to an objective weakness or turn an objectively
inaccurate move into an objectively good one.

## Explainability

An explanation may say:

- “You scored X from Y eligible games after reaching this position”;
- “You scored X from Y eligible games after playing this move”; or
- “This was higher/lower than the declared comparison; confidence is
  [state].”

It must show the eligible-game denominator and confidence state, and should
expose W/D/L, exclusions, cohort/window, comparator, and policy provenance. It
must not say that a move caused a result, that a win validates an objectively
poor move, or that a loss invalidates an objectively sound move. Opponent
strength, later play, opening selection, time control, and other confounders
remain possible. Raw internal `priority` is not itself user-facing evidence.

## Current consumers and related-work boundaries

The current objective-only `WeaknessCalculationService` feeds both
`GET /api/positions/weaknesses` and `GET /api/puzzles`; a future ordering change
therefore affects the weakness library and puzzle ordering. Existing response
fields and thresholds must keep their objective meanings. Additive practical
evidence must be explicitly named, and both consumers need compatible,
deterministic ordering behavior. `WeaknessesList` currently presents objective
evidence without displaying raw priority; practical explanations should follow
the same evidence-first convention.

- **Issue #72** owns interactive exploration beginning from the user's
  historical decision. This specification does not change that flow or decide
  whether the explored move was practically harmful.
- **Issue #76** owns authenticated longitudinal training/progress history,
  trends, and a future dashboard. This specification concerns cross-game
  evidence for the current recommendation order; it adds no progress events,
  trajectories, streaks, dashboard, or improvement claim.

## Decisions deliberately deferred

This specification does not choose:

- a final scoring or weighting formula;
- numeric sample, calibration, or confidence thresholds;
- comparator, interval method, cohort segmentation, or observation window;
- dynamic/read-time versus materialized storage, schema, migration, or backfill
  design;
- a production DTO, API, frontend, dashboard, or rollout design;
- practical-only candidate retrieval or pagination/snapshot mechanics; or
- any causal explanation.

Storage remains neutral. A follow-up must first inspect deployed Flyway history,
then choose either read-time derivation from `PositionOccurrence` and `Game` or
an additive, versioned materialization with explicit backfill, idempotency, and
rollback. It must not edit an applied migration or assume the inactive
`UserPositionWeakness` table is the correct destination.

## Test-first follow-up slices

Each slice requires independent approval and tests before production changes:

1. **Outcome normalization and data-quality audit.** Test imported accounts as
   White and Black, win/loss/draw terminal variants, unsupported and missing
   results, duplicate/re-import idempotency, and legacy-row handling. Then
   select a canonical normalization and deployment-compatible backfill or
   read-time policy.
2. **Practical evidence query/model.** Create repository integration fixtures
   for transpositions sharing the four-field-FEN identity, different SAN
   decisions, repeated positions in one game, separate accounts/colors, and
   excluded outcomes. Assert account/position/color/optional-move grouping,
   `COUNT(DISTINCT game.id)`, W/D/L, exclusions, and no account leakage.
3. **Calibration and combination policy.** Unit-test below-floor,
   exactly-at-floor, inconclusive, and ranking-eligible states; all four
   conflict quadrants; missing engine evidence; practical-only labels;
   deterministic ties; and objective-only fallback. Calibrate comparator,
   confidence, and versioned combination policy against representative
   fixtures before implementing a named recommendation component.
4. **Additive API and consumers.** Test both weakness and puzzle controller
   contracts, objective-field compatibility, exclusions, colors, ordering and
   pagination ties. Test frontend handling of additive/missing fields,
   sample/confidence display, conflict labels, non-causal copy, and stale or
   paginated responses before changing API or UI behavior.
5. **Validation and rollout observability.** Compare candidate combined ordering
   with objective-only ordering before enablement. Test/log policy version,
   eligible/excluded counts, and missing-result rates without sensitive game
   detail, and define rollback to objective-only ranking.
