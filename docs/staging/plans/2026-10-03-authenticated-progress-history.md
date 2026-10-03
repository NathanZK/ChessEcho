# Authenticated Progress History Plan

goal: Add a session-authenticated exact-position progress-history view to the Weaknesses Library.
spec: docs/staging/specs/2026-10-03-authenticated-progress-history.md

- [x] T1: Adding the progress API client contract
  goal: Implement and verify the typed authenticated progress request and response validation.
  files: `frontend/src/services/api.ts`, `frontend/src/__tests__/ProgressApiContract.test.ts`
  acceptance: `cd frontend && npx vitest run src/__tests__/ProgressApiContract.test.ts`
  spec: `docs/staging/specs/2026-10-03-authenticated-progress-history.md#contract`

- [x] T2: Building the progress history view
  goal: Render authenticated progress data, accessible chart, metrics, and all loading, empty, limited-history, error, and session states.
  files: `frontend/src/components/PositionProgressView.tsx`, `frontend/src/__tests__/PositionProgressView.test.tsx`
  acceptance: `cd frontend && npx vitest run src/__tests__/PositionProgressView.test.tsx`
  spec: `docs/staging/specs/2026-10-03-authenticated-progress-history.md#contract`

- [x] T3: Connecting progress history to weakness navigation
  goal: Make progress accessible for each exact weakness position and returnable to the library.
  files: `frontend/src/components/WeaknessesList.tsx`, `frontend/src/app/page.tsx`, `frontend/src/__tests__/WeaknessProgressNavigation.test.tsx`
  acceptance: `cd frontend && npx vitest run src/__tests__/WeaknessProgressNavigation.test.tsx`
  spec: `docs/staging/specs/2026-10-03-authenticated-progress-history.md#acceptance`

- [x] T4: Validating the frontend change
  goal: Confirm the integrated change passes frontend lint, typecheck, test suite, and production build.
  files: `frontend/src/services/api.ts`, `frontend/src/components/PositionProgressView.tsx`, `frontend/src/components/WeaknessesList.tsx`, `frontend/src/app/page.tsx`, and related frontend tests.
  acceptance: From `frontend/`, `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` all pass.
  spec: `docs/staging/specs/2026-10-03-authenticated-progress-history.md#acceptance`
