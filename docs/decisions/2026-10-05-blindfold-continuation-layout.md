# Blindfold Continuation Layout

context:
- Issue #81 defined blindfold continuation but left presentation and component structure open.
- Issue #514 required blindfold to continue the studied puzzle position without a separate layout, board resize, or restyled board.

choice:
- Keep the puzzle layout mounted and swap column contents while blindfold is active.
- Keep blindfold session state in `useBlindfoldSession`; `Home` starts it from the displayed board FEN.
- Render the blindfold board in the puzzle board column with shared `boardPresentation.ts` styling and the puzzle board orientation.
- Replace `PuzzleFeedbackPanel` with `NotationExchangeUI`; this also removes the solved-card `Enter` shortcut during notation entry.
- Disable entry while a move evaluation or continuation is pending, because the displayed position can lead `currentBoardFen`.

ruled-out:
- Rendering the blindfold board inside `ChessBoardArea`; it couples the puzzle board to blindfold session state.
- Embedding notation entry in `PuzzleFeedbackPanel`; it requires reworking feedback cards and guarding global shortcuts.
- A page-level blindfold toggle with a separate layout.
