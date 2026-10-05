# Explore a position (#90)

milestone: n/a (single contained feature, not roadmap-tracked)

## Contract

- New shared-header action, **"Explore a position"**, rendered by `Header.tsx`, visible regardless of `activeTab` and regardless of `activePuzzle`/puzzle availability. Not a tab; no new `TabType` value; no new route.
- Opens a modal (`ExplorePositionModal`, new component under `frontend/src/components/`) with two inputs: FEN text field, PGN text field. PGN is parsed to its main line only (no variations/comments/NAGs required).
- On valid FEN submit: hands off directly to Line Exploration at that exact FEN, with no prior move history (per issue: "FEN starts without prior move history").
- On valid PGN submit: parses the main line into plies (initial position + one entry per main-line ply), each labeled unambiguously by move number + side (e.g. "Start", "1. e4", "1...e5", "2. Nf3"). User picks one ply. Handoff carries the SAN move history through the selected ply and the PGN's initial position (including a `SetUp`/`FEN` start position, when present) into the exploration session (not just the resulting FEN), so chess.js's internal history (repetition detection, etc.) is correct.
- Handoff destination: the existing Line Exploration experience inside the Practice Puzzles board workspace (`page.tsx` + `ChessBoardArea` + `PuzzleFeedbackPanel`'s exploration card). While a supplied session is active, the selected workspace tab is contextually labeled "Line Exploration" (not "Practice Puzzles"); it remains the same internal tab and returns to "Practice Puzzles" after exit. No new board/evaluation/continuation implementation or top-level tab.

## Invariant: what "no active puzzle required" means

"No active puzzle required" (issue acceptance criteria 1–2) means no pre-existing real puzzle/weakness selection is required before a user can open and complete the "Explore a position" flow. It does **not** mean `activePuzzle` is literally unset during a supplied session: internally, the handoff still populates `activePuzzle` with the synthetic `source: 'supplied'` record (see below), because the existing exploration/continuation/evaluation code in `page.tsx` is keyed off `activePuzzle`. Every session started from the Header action is marked `source: 'supplied'`, with no exception.

## Architecture: synthetic supplied-position session (Option A)

Rejected alternative: a fully separate session-state tree independent of `activePuzzle`, with its own panel. Rejected because it would duplicate continuation/evaluation wiring that's currently keyed off `activePuzzle` in `page.tsx` (baseline-FEN fallback, continuation requests, eval map seeding) — directly conflicting with the issue's "use existing mechanisms, no parallel system" constraint. Confirmed by reading `handleEnterExploration`/`handleExitExploration`/continuation-fetch effects in `page.tsx`: nearly all of them read `activePuzzle` as a fallback or key.

Chosen:
- Extend the `Puzzle` interface (`frontend/src/mock/mockData.ts`, the single declared source) with an optional discriminator: `source?: 'puzzle' | 'weakness' | 'supplied'`. Absent/undefined is treated as the existing puzzle/weakness behavior (no changes needed at existing call sites).
- Submitting the modal builds a synthetic `Puzzle`-shaped record using the interface's existing field types exactly as declared (`puzzleId: string`, `fen: string`, `playerColor: 'WHITE'|'BLACK'`, `targetMove: string`, `openingTitle: string`, `acceptableMoves: AcceptableMove[]`, `movesPlayed: MoveBreakdown[]`, `priority: number`, `timesReached: number`, `mistakeCount: number`, `mistakeRate: number`, `evalCp?: number` left undefined): `{ puzzleId: 'supplied-<uuid>', fen, playerColor: <derived from FEN side-to-move>, targetMove: '', openingTitle: '', acceptableMoves: [], movesPlayed: [], priority: 0, timesReached: 0, mistakeCount: 0, mistakeRate: 0, source: 'supplied' }`.
- `page.tsx` gets one new handler, e.g. `handleExplorePosition(fen: string, sanHistory: string[])`:
  - sets `activePuzzle` to the synthetic record,
  - switches to the `puzzles` tab (the only tab that renders `ChessBoardArea`),
  - calls the existing `handleEnterExploration()` immediately (no decision move, no initial mode) so the session starts already in `EXPLORING` state — this is what structurally bypasses the puzzle-outcome/initial-decision code path (confirmed: `ChessBoardArea.handlePieceDrop` only calls `onMoveAttempt`/`recordPuzzleEventBestEffort` when `!isExplorationActive`; once exploration is active from the first render, that path is never reached),
  - seeds the existing evaluation display as unknown (`? N/A`); no starting-position evaluation is inferred from a prior puzzle or a placeholder centipawn value,
  - stores `sanHistory` (empty for FEN, populated for PGN) in new state, passed to `ChessBoardArea` as a new optional prop.
- `ChessBoardArea.tsx` gets optional props `initialMoveHistorySan?: string[]` and `initialMoveHistoryStartFen?: string`. When SAN history is non-empty, the initial `Chess` instance is built from `initialMoveHistoryStartFen` (or standard chess initial position if absent) and replays those SAN moves, so chess.js's internal move history is correct for repetition/history-dependent rules. When history is empty/absent, behavior is unchanged (`new Chess(initialFen)`).
  - Failure mode: `sanHistory` is produced by `positionImport.ts` from the same PGN parse that yielded `fen`, so a replay mismatch should not occur in practice; defensively, if replaying `sanHistory` does not land on the expected `fen` (or throws), fall back to `new Chess(initialFen)` (discarding the history) rather than crashing the board. This is a silent degradation of repetition-history fidelity only, never a wrong position.
  - Continuation/evaluate-move request failures during a supplied session are handled by the existing unchanged mechanisms (`unacceptableMoveMessage`, `continuation.loading`/error states) already wired for puzzle/weakness sessions — no new error handling is introduced.
- `PuzzleFeedbackPanel.tsx` and `BoardControls.tsx`: every puzzle-only block/control is suppressed for `source === 'supplied'`: settings (color filter/min-mistakes), opening title + "View Games", timed-training controls/badge, Prev/Next puzzle buttons, hint, blindfold entry, outcome feedback cards, puzzle-specific idle instructions, and mistake-rate/timesReached stats. The exploration card (mode selection, continuation, challenge) and position controls (undo, redo, reset, flip, sound) remain. The selected navigation label is "Line Exploration" only for the supplied session; existing puzzle/weakness sessions retain "Practice Puzzles".
- Exit: for a supplied session, "Exit Exploration" clears `activePuzzle` back to `null` (ending the session) instead of restoring a puzzle-solved feedback state (`handleExitExploration` currently sets `feedback: {status:'CORRECT', lastMove: activePuzzle?.targetMove}` on exit — wrong for a supplied session with no target move). Returning to `null` surfaces the existing "No Practice Puzzles Available" fallback card, which is acceptable and not misleading (it does not claim the supplied session was a puzzle).
- Backend: none. `GET /api/puzzles/continuation` and `GET /api/puzzles/evaluate-move` are already FEN-driven (confirmed in `API_CONTRACT.md`), not `puzzleId`-driven. The issue's `full-stack` label reflects original uncertainty, not a real requirement; validation only needs the frontend commands.

## Data shape

- `PgnPly = { ply: number; moveNumber: number; color: 'w' | 'b'; san: string; sanHistory: string[]; fen: string; label: string }` — `sanHistory` is inclusive of this ply (empty array = initial position). `label` is the unambiguous display string (e.g. `"Start"`, `"1. e4"`, `"1... e5"`). The parser returns `{ ok: true; startFen: string; plies: PgnPly[] }`, where `startFen` is the PGN's `FEN` tag when `SetUp "1"` is used, otherwise the standard initial position.
- New util module `frontend/src/utils/positionImport.ts`:
  - `parseFenInput(input: string): { ok: true; fen: string } | { ok: false; error: string }` — trims, uses chess.js `validateFen`, returns its error message on failure.
  - `parsePgnMainLine(input: string): { ok: true; startFen: string; plies: PgnPly[] } | { ok: false; error: string }` — uses `new Chess().loadPgn(input)` (catch thrown error for malformed PGN), then `history({verbose:true})` to build `plies`, prefixed with a synthetic ply 0 (PGN start position, empty `sanHistory`).
  - Multiple games in one input (a second PGN header block, e.g. a second `[Event ...]` tag after moves have already been parsed) are treated as invalid PGN: same rejection path and error copy as malformed PGN ("only one game is supported — paste a single game's PGN"). Detected by scanning for a second tag-pair block before calling `loadPgn`, so the error is explicit rather than relying on `loadPgn`'s own (inconsistent) failure behavior on concatenated games.
  - Recursive variations (`(...)` / RAV) in the input are rejected the same way (explicit pre-scan for an unescaped `(`), with copy clarifying "variations aren't supported — paste just the main line." They are never silently stripped or silently followed, since doing so risks seating the user at a position other than the one they intended (the exact failure mode the issue calls out). Comments (`{...}`) and NAGs (`$n`) are safe to leave to chess.js's native `loadPgn` handling (ignored, main line unaffected).
- Modal → `page.tsx` handoff shape: `ExplorePositionModal` takes `onSubmit: (result: { fen: string; sanHistory: string[]; historyStartFen?: string }) => void` and `onClose: () => void`. The modal owns all FEN/PGN parsing and ply selection internally and only calls `onSubmit` once, with a single already-validated position (`sanHistory: []` and no `historyStartFen` for FEN; selected ply's SAN history and PGN `startFen` for PGN). `page.tsx` performs no parsing/validation itself — it only receives a trusted, final position.

## Failure modes

- Invalid FEN (`validateFen` fails, or empty input): modal shows the chess.js validation error text inline; no board change; focus stays on the input; nothing is submitted.
- Invalid/unparseable PGN (`loadPgn` throws): modal shows an actionable error ("Couldn't read that PGN — check the move text."); no ply list rendered; nothing submitted.
- Valid PGN with zero main-line plies (header-only / empty game): ply list still offers "Start" (the initial position) so the user isn't stuck.
- User closes modal without submitting: no state changes anywhere (no `activePuzzle` mutation). `onClose` only tears down modal-local state.
- A multi-game PGN or a PGN containing variations: rejected with the specific copy above; no ply list rendered; nothing submitted (same shape as other invalid-PGN handling).

## Test

- Unit tests for `positionImport.ts`: valid FEN, invalid FEN (malformed/garbage/wrong field count), valid PGN → correct ply list/labels/sanHistory, invalid PGN, empty-game PGN, multi-game PGN rejected, PGN with variations rejected.
- Component/interaction tests (Vitest + Testing Library in `frontend/package.json`) for:
  - Header renders and opens the "Explore a position" action at narrow and desktop layout, without requiring `activePuzzle`.
  - FEN submit → board reaches that FEN, no pre-existing puzzle needed, exploration controls present; assert the session enters `EXPLORING` immediately (tab switches to `puzzles`, `isExplorationActive` true) rather than through any puzzle-decision state.
  - PGN submit → select a ply by its label → board reaches that ply's FEN; assert the `initialMoveHistorySan` prop passed into `ChessBoardArea` matches the selected ply's `sanHistory`.
  - Invalid FEN/PGN (including multi-game and variation PGNs) → error shown, no board/activePuzzle change.
  - Closing the modal without submitting → no `activePuzzle`/tab/state change.
  - Supplied session hides puzzle-only controls (settings, timer, prev/next, hint, feedback cards, mistake stats) and never calls the puzzle-outcome recording path (assert `recordPuzzleEventBestEffort`/the events endpoint is not invoked during a supplied session's moves).
  - Supplied session still issues continuation/evaluate-move requests through the existing shared mechanism (assert the same request functions used by puzzle sessions are called with the supplied FEN).
  - Exiting a supplied session clears `activePuzzle` to `null` and returns to the existing "No Practice Puzzles Available" fallback, not a puzzle-solved feedback state.
  - Existing puzzle and weakness entry points (`onEnterExploration` from `PuzzleFeedbackPanel`, `WeaknessesList`'s "Practice Position") still work unchanged (regression).
- Screenshots (manual, for user-facing proof — not a substitute for the above): entry point narrow/desktop, FEN handoff result, PGN ply-selection handoff result, an invalid-input error state, and a supplied session showing the puzzle chrome is absent.

## Convention

- Match existing component/style conventions in `Header.tsx`/`PuzzleFeedbackPanel.tsx` (Tailwind utility classes, existing color/spacing tokens, existing modal patterns if one already exists in the codebase — check during `plan`/`tdd` before introducing a new modal pattern).
- Follow `frontend/AGENTS.md` for all frontend changes.
- No new dependencies — chess.js is already present and sufficient for FEN/PGN parsing.
- Update `docs/architecture/frontend.md` to document the new entry point, the `source` discriminator, and the suppressed-chrome behavior, per repo convention ("update directly related documentation").

## Out of scope (per issue's repository constraints)

- Similar-position research / "similar position → Line Exploration" (future entry point only; the `source: 'supplied'` discriminator is deliberately generic enough to extend later, but no second source is being built now).
- PGN variations, comments, NAGs, multi-game PGN files.
- Preserving/restoring a previously active puzzle session after exiting a supplied-position session.
- Any backend endpoint changes.

## Working notes

- Confirmed via `node -e` probe against the installed `chess.js` (`^1.4.0`): `validateFen()` returns `{ok, error}` without throwing; `new Chess(badFen)` throws `"Invalid FEN: ..."`; `loadPgn()` throws on malformed move text; `history({verbose:true})` returns `{san, before, after, color}` per ply — all directly usable.
- Confirmed: frontend tests use Vitest with Testing Library in jsdom (`frontend/vitest.config.mts`); browser screenshots will be captured separately from the running app.
- Need to confirm during `plan`: whether a modal-pattern component already exists elsewhere in the codebase (e.g. for the "View Games" link in `PuzzleFeedbackPanel.tsx`) to reuse instead of introducing a new one.
