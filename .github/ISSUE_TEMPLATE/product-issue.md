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

## Acceptance criteria

- List the concrete, checkable conditions that mean this issue is done.

## Validation

- Backend: `./gradlew ktlintCheck` and `./gradlew test` (or narrower,
  targeted commands if this issue does not touch the backend).
- Frontend: `npm run lint`, `npx tsc --noEmit`, `npm run test`, and
  `npm run build` from `frontend/` (or narrower, targeted commands if this
  issue does not touch the frontend).

<!--
See docs/engineering/repository-conventions.md for the default pre-deployment
assumption and the baseline-first migration convention.
-->
