---
name: Product issue
about: Minimal, reusable structure for a ChessEcho product change
title: ""
labels: []
assignees: []
---

## Problem

What is broken, missing, or friction-causing today? Keep this to the
observable symptom, not a proposed solution.

## Goal

What should be true once this issue is done? Describe the desired outcome,
not the implementation.

## Relevant failure modes

Where relevant, briefly describe each material, plausible failure mode: the
affected behavior, data, or invariant; the condition or mechanism that could
cause it; and the resulting failure or regression. Include the user-visible
consequence when one exists. A category label alone (such as "concurrency" or
"data integrity") is not a failure mode. For example: "Two workers can process
the same import ID, both observe that no record exists, and create duplicates."
If none are identified, write "None identified"; if an important mode is
uncertain, state what is uncertain rather than guessing. Do not invent
speculative risks or enumerate irrelevant categories. Any examples or cues
(such as concurrency, persistence, compatibility, account state, or user-visible
behavior) are optional prompts, not a checklist. Do not use this section for
acceptance criteria, validation/testing, mitigations, or implementation steps.

## Repository constraints

- Deployment state: <!-- Default: pre-deployment. Only add a line here if
  ChessEcho (or the relevant environment) is already deployed; see
  docs/engineering/repository-conventions.md for what this changes. -->
- Any other repository-specific constraints this issue must respect (for
  example, an existing architectural boundary that must not be redesigned).

Include assumptions, non-scope, dependencies, reproduction details, or exact
implementation paths only when materially relevant; do not prescribe a design
or file ownership merely to fill out the issue.

## Acceptance criteria

- For each criterion, state the observable outcome, appropriate verification
  method, and evidence an independent reviewer can inspect or reproduce.
  Identify what is checked, the relevant setup/state, and the expected result.
  Methods may differ or combine: programmatic checks, rendered UI/UX inspection,
  repository/documentation inspection, or empirical analysis. Use only those
  needed to establish the criterion, not every evidence type.

Evidence must be attributable to the reviewed implementation. Where relevant,
identify the reviewed revision, setup/state, how the state was reached, and
mocks, fixtures, manual state changes, or other limitations affecting what the
evidence proves. The relationship between criterion, method, setup/state, and
evidence must be inspectable or reproducible. Multiple evidence items must
establish the same claimed behavior/state, not merely look convincing separately
while describing unrelated revisions, constructed states, or execution paths.

Changed rendered UI appearance/state requires reviewable visual evidence from
the running application; where screenshots are the available mechanism, include
a screenshot of the relevant state with enough surrounding UI to judge the
claimed property. A screenshot proves appearance only under its captured
conditions, not interaction or reachability. Interactive criteria also require
reproducible interaction evidence; interaction tests do not establish visual
correctness. For user-flow criteria, establish that the rendered state is reached
through the specified flow, not just an independently constructed component/state.
Backend-only and non-rendered criteria need appropriate non-visual evidence, not
screenshots. This does not mandate end-to-end tests, a real backend for every
screenshot, or a universal browser/device matrix.

An implementer's "passed" or "manually verified" report is not itself evidence,
even if it references a test or screenshot. Inspect the supporting evidence:
tests must exercise the intended behavior rather than merely assert the
implementation's own output or mock away the behavior being changed.

## Validation

List applicable validation separately from criterion-specific evidence. Record
exact commands and results, or manual checks and observed outcomes; a passing
lint/typecheck/build or test suite does not by itself establish every criterion.

- Backend: `./gradlew ktlintCheck` and `./gradlew test` (or narrower,
  targeted commands if this issue does not touch the backend).
- Frontend: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and
  `npm run build` from `frontend/` (or narrower, targeted commands if this
  issue does not touch the frontend).

<!--
See docs/engineering/repository-conventions.md for the default pre-deployment
assumption and the baseline-first migration convention.
-->
