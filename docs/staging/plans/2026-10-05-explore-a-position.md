# Explore a position (#90)

spec: [2026-10-06-explore-a-position.md](../specs/2026-10-06-explore-a-position.md)

- [x] T1: Parsing FEN and PGN positions
  goal: Parse and validate a FEN or single-game PGN main line into exact selectable positions with preserved SAN history.
  files: `frontend/src/utils/positionImport.ts`, `frontend/src/__tests__/positionImport.test.ts`
  acceptance: `cd frontend && npm run test -- src/__tests__/positionImport.test.ts` — parser tests pass for valid/invalid FEN, valid/invalid/empty PGN, ply labels and SAN history (including a PGN with a custom `SetUp`/`FEN` start position), and explicit rejection of multi-game and variation PGNs.

- [x] T2: Adding the shared Explore a position entry flow
  goal: Add a responsive shared-header action and modal for FEN submission or PGN main-line position selection.
  files: `frontend/src/components/Header.tsx`, `frontend/src/components/ExplorePositionModal.tsx`, `frontend/src/__tests__/ExplorePositionModal.test.tsx`
  acceptance: `cd frontend && npm run test -- src/__tests__/ExplorePositionModal.test.tsx` — interaction tests open the action from the shared header, submit a validated FEN, choose a labeled PGN ply, display actionable parse errors, and close without submitting; verify button remains reachable at narrow and desktop viewport widths.

- [x] T3: Connecting supplied positions to Line Exploration
  goal: Initialize the existing Line Exploration flow from a supplied position and preserve PGN move history in the board's chess state.
  files: `frontend/src/app/page.tsx`, `frontend/src/mock/mockData.ts`, `frontend/src/components/ChessBoardArea.tsx`, `frontend/src/utils/chessGame.ts`, `frontend/src/__tests__/SuppliedPositionExploration.test.tsx`, `frontend/src/__tests__/ChessGameHistory.test.ts`
  acceptance: `cd frontend && npm run test -- src/__tests__/SuppliedPositionExploration.test.tsx src/__tests__/ChessGameHistory.test.ts` — FEN and selected PGN ply land on their exact positions; PGN SAN history seeds and remains in the board from the correct standard or custom PGN start position; the session enters the existing exploration state and invokes shared continuation/evaluation mechanisms without requiring a pre-existing puzzle; invalid input and failed history replay do not land on a different position.

- [x] T4: Removing puzzle framing from supplied sessions
  goal: Suppress puzzle-only controls and outcomes only for supplied-position sessions while preserving existing puzzle/weakness exploration behavior.
  files: `frontend/src/components/Header.tsx`, `frontend/src/components/PuzzleFeedbackPanel.tsx`, `frontend/src/components/BoardControls.tsx`, `frontend/src/components/ChessBoardArea.tsx`, `frontend/src/app/page.tsx`, `frontend/src/__tests__/SuppliedPositionExploration.test.tsx`, `frontend/src/__tests__/ExploreDecisionAfterWrongMove.test.tsx`, `frontend/src/__tests__/WeaknessProgressNavigation.test.tsx`
  acceptance: `cd frontend && npm run test -- src/__tests__/SuppliedPositionExploration.test.tsx src/__tests__/ExploreDecisionAfterWrongMove.test.tsx src/__tests__/WeaknessProgressNavigation.test.tsx` — supplied sessions show "Line Exploration" as the selected workspace label and hide puzzle feedback/navigation/hints/settings/timers/stats, never record puzzle outcomes, and exit without a false solved state; existing puzzle and weakness entry points still function. Verified with those suites plus Play Both Sides and timer/blindfold regression suites (85 tests passed); `npm run lint` and `npx tsc --noEmit` passed.

- [x] T5: Documenting and verifying issue 90
  goal: Document the source-neutral entry and handoff, run frontend quality checks, and capture the requested reviewable screenshots.
  files: `docs/architecture/frontend.md`, `docs/staging/specs/2026-10-06-explore-a-position.md`
  acceptance: `npm run lint`, `npx tsc --noEmit`, and `npm run build` pass. `npm run test` reports 546 passed (the board remounts only for supplied-position sessions, so puzzle navigation keeps the existing mounted board); the directly relevant supplied-position, header, puzzle, weakness, timer, blindfold, and Play Both Sides suites pass. Screenshots in `screenshots/issue-90/` show the actual QGD FEN entry and PGN main-line ply `2... e6` selection (plus `1. d4` selection in `07-pgn-select-first-move-1-d4.png`), each handed into Line Exploration and continued with `cxd5`. Captured at 1440x1050 with a fresh Next.js frontend and the actual Spring backend, PostgreSQL, and Stockfish running; no API routes were mocked. Both move-evaluation requests returned HTTP 200 for `cxd5`, best move `Nc3`, evaluation loss 0.04 pawns, and `acceptable: true`; the UI accepted the moves and advanced to Black to move. Header component tests exercise 375px and 1280px widths. T5 acceptance is complete.
