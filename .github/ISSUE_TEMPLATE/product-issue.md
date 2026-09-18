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
