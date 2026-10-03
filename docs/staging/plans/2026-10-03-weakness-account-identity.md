goal: Separate the authenticated weakness selector from the visible Chess.com username.

- [x] T1: Separating account selection from weakness display identity
  files: `frontend/src/app/page.tsx`, `frontend/src/components/WeaknessesList.tsx`, `frontend/src/__tests__/WeaknessesList.test.tsx`, `frontend/src/__tests__/ConnectedAccountFlow.test.tsx`
  acceptance: From `frontend/`, targeted tests `npm run test -- src/__tests__/WeaknessesList.test.tsx src/__tests__/ConnectedAccountFlow.test.tsx` fail before implementation and pass after; then `npm run lint`, `npx tsc --noEmit`, `npm run test`, and `npm run build` pass.
  spec: `docs/staging/specs/2026-10-03-weakness-account-identity.md`
