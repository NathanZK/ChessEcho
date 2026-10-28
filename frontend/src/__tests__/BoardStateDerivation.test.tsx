import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { describe, it, expect, afterEach, vi } from 'vitest';
import * as chessGame from '../utils/chessGame';
import { ChessBoardArea } from '../components/ChessBoardArea';
import { ContinuationCandidate, MoveEvaluationResponse } from '../services/api';
import { playSound } from '../services/soundService';
import { moveEvaluationService } from '../services/continuationService';

const createMoveEvalResponse = (
  overrides: Partial<MoveEvaluationResponse> = {}
): MoveEvaluationResponse => ({
  fen: '',
  move: '',
  bestMove: '',
  bestEvalCp: null,
  evalCp: null,
  evalLoss: 0,
  maxEvalLoss: 0.5,
  threshold: 0.5,
  acceptable: true,
  ...overrides,
});

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('../services/continuationService', async () => {
  const actual = await vi.importActual<typeof import('../services/continuationService')>('../services/continuationService');
  return {
    ...actual,
    moveEvaluationService: {
      evaluateMove: vi.fn(),
      clear: vi.fn(),
    },
  };
});

type MockChessboardOptions = {
  position: string;
  squareStyles: Record<string, React.CSSProperties>;
  onPieceDrop: (args: { sourceSquare: string; targetSquare: string }) => boolean;
  allowDragging: boolean;
  boardOrientation?: string;
  animationDurationInMs?: number;
  onSquareClick?: (args: { piece?: unknown; square: string }) => void;
};

type MockChessboardProps = { options?: MockChessboardOptions };

const emptyMockOptions: MockChessboardOptions = {
  position: '',
  squareStyles: {},
  onPieceDrop: () => false,
  allowDragging: false,
  boardOrientation: undefined,
  animationDurationInMs: undefined,
  onSquareClick: undefined,
};

let capturedOptions: MockChessboardOptions = emptyMockOptions;

vi.mock('react-chessboard', () => ({
  Chessboard: (props: MockChessboardProps) => {
    capturedOptions = props.options || emptyMockOptions;
    return <div data-testid="mock-chessboard" />;
  },
}));

/**
 * Issue 98 — `ChessBoardArea` board-state derivation guards (plan §7.3, T16–T23).
 */
describe('Issue 98 — board state derivation', () => {
  const FEN_A = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
  const FEN_BB5 = 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3';
  const FEN_BC4 = 'r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3';
  const FEN_OTHER_PUZZLE = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';

  type BoardProps = React.ComponentProps<typeof ChessBoardArea>;

  const boardProps = (overrides: Partial<BoardProps> = {}): BoardProps => ({
    initialFen: FEN_A,
    playerColor: 'WHITE',
    targetMove: 'Bb5',
    acceptableMoves: [],
    movesPlayed: [],
    onMoveAttempt: vi.fn(),
    onNextPuzzle: vi.fn(),
    ...overrides,
  });

  const candidate = (move: string, resultingFen: string): ContinuationCandidate => ({
    move,
    resultingFen,
    providerType: 'ENGINE',
  });

  afterEach(() => {
    capturedOptions = emptyMockOptions;
    vi.restoreAllMocks();
    vi.mocked(moveEvaluationService.evaluateMove).mockReset();
  });

  it('T16 — mount notifies once with initialFen and an initialFen change resets the board', async () => {
    const onFenChange = vi.fn();
    const props = boardProps({ onFenChange });

    const { rerender } = render(<ChessBoardArea {...props} />);

    expect(onFenChange).toHaveBeenCalledTimes(1);
    expect(onFenChange).toHaveBeenCalledWith(FEN_A);
    expect(capturedOptions.position).toBe(FEN_A);

    // Paint styles and advance the history so the reset has something to undo.
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();

    await act(async () => {
      rerender(<ChessBoardArea {...props} initialFen={FEN_OTHER_PUZZLE} />);
    });

    expect(onFenChange).toHaveBeenLastCalledWith(FEN_OTHER_PUZZLE);
    expect(capturedOptions.position).toBe(FEN_OTHER_PUZZLE);
    expect(capturedOptions.squareStyles).toEqual({});
    expect(screen.getByTitle(/Previous Move/i)).toBeDisabled();
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();
  });

  it('T17 — the board already shows the applied candidate when onFenChange fires', async () => {
    const positionsAtNotification: string[] = [];
    const onFenChange = vi.fn<(fen: string) => void>(() => {
      positionsAtNotification.push(capturedOptions.position);
    });
    const onContinuationApplied = vi.fn();
    const props = boardProps({
      onFenChange,
      onContinuationApplied,
      isExplorationActive: true,
      pendingContinuationCandidate: null,
    });

    const { rerender } = render(<ChessBoardArea {...props} />);

    await act(async () => {
      rerender(
        <ChessBoardArea {...props} pendingContinuationCandidate={candidate('Bb5', FEN_BB5)} />
      );
    });

    expect(onContinuationApplied).toHaveBeenCalledTimes(1);

    const notificationIndex = onFenChange.mock.calls.findIndex((call) => call[0] === FEN_BB5);
    expect(notificationIndex).toBeGreaterThanOrEqual(0);
    expect(positionsAtNotification[notificationIndex]).toBe(FEN_BB5);
  });

  it('T18 — a candidate that arrives while exploration is inactive is consumed silently', async () => {
    const onFenChange = vi.fn();
    const onContinuationApplied = vi.fn();
    const onChessEchoExplorationMove = vi.fn();
    const props = boardProps({
      onFenChange,
      onContinuationApplied,
      onChessEchoExplorationMove,
      isExplorationActive: false,
      pendingContinuationCandidate: null,
    });
    const inactiveCandidate = candidate('Bb5', FEN_BB5);

    const { rerender } = render(<ChessBoardArea {...props} />);
    const fenCallsAfterMount = onFenChange.mock.calls.length;

    await act(async () => {
      rerender(<ChessBoardArea {...props} pendingContinuationCandidate={inactiveCandidate} />);
    });

    expect(capturedOptions.position).toBe(FEN_A);
    expect(onContinuationApplied).not.toHaveBeenCalled();
    expect(onChessEchoExplorationMove).not.toHaveBeenCalled();
    expect(onFenChange).toHaveBeenCalledTimes(fenCallsAfterMount);

    // Re-enabling exploration must not replay the already-consumed candidate.
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...props}
          isExplorationActive={true}
          pendingContinuationCandidate={inactiveCandidate}
        />
      );
    });

    expect(capturedOptions.position).toBe(FEN_A);
    expect(onContinuationApplied).not.toHaveBeenCalled();
    expect(onChessEchoExplorationMove).not.toHaveBeenCalled();
    expect(onFenChange).toHaveBeenCalledTimes(fenCallsAfterMount);
  });

  it('T19 — an unparseable candidate FEN logs and still completes', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const onContinuationApplied = vi.fn();
    const props = boardProps({
      onContinuationApplied,
      isExplorationActive: true,
      pendingContinuationCandidate: null,
    });

    const { rerender } = render(<ChessBoardArea {...props} />);

    await act(async () => {
      rerender(
        <ChessBoardArea
          {...props}
          pendingContinuationCandidate={candidate('??', 'not-a-valid-fen')}
        />
      );
    });

    expect(errorSpy).toHaveBeenCalledWith(
      'Failed to apply continuation candidate resultingFen:',
      expect.anything()
    );
    expect(onContinuationApplied).toHaveBeenCalledTimes(1);
    expect(capturedOptions.position).toBe(FEN_A);
  });

  it('T20 — an alternative whose parentFen is absent from history is a complete no-op', async () => {
    const onFenChange = vi.fn();
    const onAlternativeContinuationApplied = vi.fn();
    const props = boardProps({
      onFenChange,
      onAlternativeContinuationApplied,
      isExplorationActive: true,
      alternativeContinuationToApply: null,
    });

    const { rerender } = render(<ChessBoardArea {...props} />);
    const fenCallsAfterMount = onFenChange.mock.calls.length;

    await act(async () => {
      rerender(
        <ChessBoardArea
          {...props}
          alternativeContinuationToApply={{
            parentFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
            candidate: candidate('Bc4', FEN_BC4),
          }}
        />
      );
    });

    expect(capturedOptions.position).toBe(FEN_A);
    expect(onFenChange).toHaveBeenCalledTimes(fenCallsAfterMount);
    expect(onAlternativeContinuationApplied).not.toHaveBeenCalled();
  });

  it('T21 — an alternative truncates history at its parent and still completes on a parse failure', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const onAlternativeContinuationApplied = vi.fn();
    const props = boardProps({
      onAlternativeContinuationApplied,
      isExplorationActive: true,
      pendingContinuationCandidate: null,
      alternativeContinuationToApply: null,
    });

    const { rerender } = render(<ChessBoardArea {...props} />);

    // Build history [FEN_A, FEN_BB5] with historyIndex = 1.
    await act(async () => {
      rerender(
        <ChessBoardArea {...props} pendingContinuationCandidate={candidate('Bb5', FEN_BB5)} />
      );
    });
    expect(capturedOptions.position).toBe(FEN_BB5);

    // Replace the ChessEcho move: history becomes [FEN_A, FEN_BC4], historyIndex = 1.
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...props}
          alternativeContinuationToApply={{
            parentFen: FEN_A,
            candidate: candidate('Bc4', FEN_BC4),
          }}
        />
      );
    });

    expect(onAlternativeContinuationApplied).toHaveBeenCalledTimes(1);
    expect(capturedOptions.position).toBe(FEN_BC4);
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();

    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // A parse failure inside the parent-found branch still reports and still completes.
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...props}
          alternativeContinuationToApply={{
            parentFen: FEN_A,
            candidate: candidate('??', 'not-a-valid-fen'),
          }}
        />
      );
    });

    expect(errorSpy).toHaveBeenCalledWith(
      'Failed to apply alternative candidate:',
      expect.anything()
    );
    expect(onAlternativeContinuationApplied).toHaveBeenCalledTimes(2);
  });

  it('T22 — the keydown listener is registered once per mount and always drives the latest history', async () => {
    const addEventListenerSpy = vi.spyOn(window, 'addEventListener');
    const keydownRegistrations = () =>
      addEventListenerSpy.mock.calls.filter((call) => call[0] === 'keydown').length;

    render(<ChessBoardArea {...boardProps()} />);

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    const afterFirstMove = capturedOptions.position;

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
    const afterSecondMove = capturedOptions.position;

    expect(afterFirstMove).toBe(FEN_BB5);
    expect(afterSecondMove).not.toBe(afterFirstMove);
    expect(keydownRegistrations()).toBe(1);

    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowLeft' });
    });
    expect(capturedOptions.position).toBe(afterFirstMove);

    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowRight' });
    });
    expect(capturedOptions.position).toBe(afterSecondMove);

    expect(keydownRegistrations()).toBe(1);
  });

  it('T23 — a hint square supplied at mount paints, and clearing it clears the styles', async () => {
    const props = boardProps({ hintSquare: 'e4' });

    const { rerender } = render(<ChessBoardArea {...props} />);

    expect(capturedOptions.squareStyles).toEqual({
      e4: { backgroundColor: 'rgba(245, 158, 11, 0.5)', borderRadius: '50%' },
    });

    await act(async () => {
      rerender(<ChessBoardArea {...props} hintSquare={undefined} />);
    });

    expect(capturedOptions.squareStyles).toEqual({});
  });

  it('T24 — onSquareClick handles empty square, piece selection, deselection, legal move click, and illegal move reselection', async () => {
    const props = boardProps();
    render(<ChessBoardArea {...props} />);

    // 1. Clean board: click empty square when no piece is selected: leaves styles empty
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'e4' });
    });
    expect(capturedOptions.squareStyles).toEqual({});

    // Paint hint styles to verify clicking empty square does not clear them
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'e4' });
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    // Clear styles
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });

    // 2. Click square with piece: highlights square
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'e2' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      e2: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });

    // 3. Click same square: deselects and clears styles
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'e2' });
    });
    expect(capturedOptions.squareStyles).toEqual({});

    // 4. Click square with piece, then click another square with friendly piece (illegal move): reselects second piece
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'e2' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      e2: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'd2' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      d2: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });

    // 5. Click illegal square without piece: clears selection and styles
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'e5' });
    });
    expect(capturedOptions.squareStyles).toEqual({});

    // 6. Click square with piece, then click legal target square: executes move and clears styles
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wB', square: 'f1' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      f1: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'b5' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(capturedOptions.squareStyles).toEqual({});

    // 7. Verify moveFromSquare is reset to null after a successful square click move (Mutant 321):
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    cloneSpy.mockClear();
    // Clicking empty square e4 must not attempt a drop or call cloneChessGameWithHistory
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'e4' });
    });
    expect(cloneSpy).not.toHaveBeenCalled();
    expect(capturedOptions.squareStyles).toEqual({});

    // Clicking b5 (where bishop now sits) selects b5 rather than attempting another f1->b5 drop
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wB', square: 'b5' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      b5: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });
  });

  it('T25 — onPieceDrop resets moveFromSquare and rejects drops with empty or missing targetSquare', async () => {
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    const props = boardProps();
    render(<ChessBoardArea {...props} />);

    // Select a square via onSquareClick first
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'd2' });
    });
    expect(capturedOptions.squareStyles).toEqual({
      d2: { backgroundColor: 'rgba(245, 158, 11, 0.4)' },
    });

    // Drop with empty targetSquare: returns false, does not invoke cloneChessGameWithHistory, and resets moveFromSquare
    cloneSpy.mockClear();
    let dropResult = true;
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'e2', targetSquare: '' });
    });
    expect(dropResult).toBe(false);
    expect(cloneSpy).not.toHaveBeenCalled();

    // If moveFromSquare was not reset, clicking d4 would have moved d2-d4 from the prior selection.
    // Because it was reset, clicking d4 (empty square) does nothing and position remains FEN_A.
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'd4' });
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // Now select d2 again, drop a different piece via drag-and-drop, and verify moveFromSquare is cleared.
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: 'wP', square: 'd2' });
    });
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(dropResult).toBe(true);
    expect(capturedOptions.position).toBe(FEN_BB5);

    // Clicking d4 now must NOT move d2-d4
    await act(async () => {
      capturedOptions.onSquareClick?.({ piece: undefined, square: 'd4' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
  });

  it('T26 — Undo and Redo clear customSquareStyles even at boundary limits', async () => {
    const onUndo = vi.fn();
    const onRedo = vi.fn();
    const onFenChange = vi.fn();
    const props = boardProps({ onUndo, onRedo, onFenChange });
    render(<ChessBoardArea {...props} />);

    // Paint hint styles at historyIndex === 0
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    // ArrowLeft at historyIndex === 0: clears styles, does not invoke onUndo or onFenChange
    const fenChangeCalls = onFenChange.mock.calls.length;
    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowLeft' });
    });
    expect(capturedOptions.squareStyles).toEqual({});
    expect(onUndo).not.toHaveBeenCalled();
    expect(onFenChange).toHaveBeenCalledTimes(fenChangeCalls);

    // Advance 1 move
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);

    // Paint hint styles again at the end of history
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    // ArrowRight at latest history: clears styles, does not invoke onRedo or onFenChange
    const fenChangeCallsAfterMove = onFenChange.mock.calls.length;
    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowRight' });
    });
    expect(capturedOptions.squareStyles).toEqual({});
    expect(onRedo).not.toHaveBeenCalled();
    expect(onFenChange).toHaveBeenCalledTimes(fenChangeCallsAfterMove);

    // Undo when historyIndex > 0: invokes onUndo and onFenChange, clears styles
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(onUndo).toHaveBeenCalledTimes(1);
    expect(capturedOptions.position).toBe(FEN_A);
    expect(capturedOptions.squareStyles).toEqual({});

    // Redo when historyIndex < fenHistory.length - 1: invokes onRedo and onFenChange, clears styles
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(onRedo).toHaveBeenCalledTimes(1);
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(capturedOptions.squareStyles).toEqual({});
  });

  it('T27 — Arrow navigation ignores keydown within inputs/textareas, non-arrow keys, and handles null target', async () => {
    const props = boardProps();
    render(<ChessBoardArea {...props} />);

    // Make 1 move
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);

    // Dispatch ArrowLeft inside an <input> element: must NOT trigger undo
    const inputElement = document.createElement('input');
    document.body.appendChild(inputElement);
    await act(async () => {
      inputElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    document.body.removeChild(inputElement);

    // Dispatch ArrowLeft inside a <textarea> element: must NOT trigger undo
    const textareaElement = document.createElement('textarea');
    document.body.appendChild(textareaElement);
    await act(async () => {
      textareaElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    document.body.removeChild(textareaElement);

    // Undo back to FEN_A so historyIndex === 0
    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowLeft' });
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // Dispatch a non-arrow key (e.g. ArrowUp) on window: must NOT trigger redo
    await act(async () => {
      fireEvent.keyDown(window, { key: 'ArrowUp' });
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // Dispatch keydown with null target / without HTMLElement target: must not throw
    await act(async () => {
      const evt = new Event('keydown', { bubbles: true });
      Object.defineProperty(evt, 'key', { value: 'ArrowLeft' });
      Object.defineProperty(evt, 'target', { value: null });
      window.dispatchEvent(evt);
    });
    expect(capturedOptions.position).toBe(FEN_A);
  });

  it('T28 — Reset clears customSquareStyles, resets gameHistory/fenHistory, and works without callbacks', async () => {
    const onReset = vi.fn();
    const onFenChange = vi.fn();
    const props = boardProps({ onReset, onFenChange });
    const { rerender } = render(<ChessBoardArea {...props} />);

    // Advance 2 moves
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();

    // Paint hint styles
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    // Click Reset
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(capturedOptions.squareStyles).toEqual({});
    expect(screen.getByTitle(/Previous Move/i)).toBeDisabled();
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();
    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onFenChange).toHaveBeenLastCalledWith(FEN_A);

    // Advance 1 move after reset and verify Previous Move becomes enabled and undo works
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // Advance 1 move and reset without callbacks provided
    await act(async () => {
      rerender(<ChessBoardArea {...boardProps({ onReset: undefined, onFenChange: undefined })} />);
    });
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);
  });

  it('T29 — Hint highlights exact square with backgroundColor and boxShadow, and handles invalid targetMove', async () => {
    const { rerender } = render(<ChessBoardArea {...boardProps({ targetMove: 'InvalidMove' })} />);

    // Hint with invalid targetMove: leaves styles empty without throwing
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(capturedOptions.squareStyles).toEqual({});

    // Hint with valid targetMove 'Bb5': highlights f1 with exact styles
    await act(async () => {
      rerender(<ChessBoardArea {...boardProps({ targetMove: 'Bb5' })} />);
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(capturedOptions.squareStyles).toEqual({
      f1: {
        backgroundColor: 'rgba(245, 158, 11, 0.65)',
        boxShadow: 'inset 0 0 10px rgba(245, 158, 11, 0.8)',
      },
    });
  });

  it('T30 — Board orientation defaults by playerColor and respects boardOrientation override', async () => {
    // 1. playerColor === 'BLACK', no boardOrientation -> 'black'
    const { rerender } = render(<ChessBoardArea {...boardProps({ playerColor: 'BLACK' })} />);
    expect(capturedOptions.boardOrientation).toBe('black');

    // 2. playerColor === 'WHITE', no boardOrientation -> 'white'
    await act(async () => {
      rerender(<ChessBoardArea {...boardProps({ playerColor: 'WHITE' })} />);
    });
    expect(capturedOptions.boardOrientation).toBe('white');

    // 3. boardOrientation override 'white' with playerColor 'BLACK' -> 'white'
    await act(async () => {
      rerender(<ChessBoardArea {...boardProps({ playerColor: 'BLACK', boardOrientation: 'white' })} />);
    });
    expect(capturedOptions.boardOrientation).toBe('white');
  });

  it('T31 — Initial puzzle moves truncate future history after undo, handle non-initial moves, and reject illegal drops', async () => {
    const onMoveAttempt = vi.fn();
    render(<ChessBoardArea {...boardProps({ onMoveAttempt })} />);

    // 1. Initial move 'Bb5' (best move)
    let dropResult = false;
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(dropResult).toBe(true);
    expect(onMoveAttempt).toHaveBeenLastCalledWith('Bb5', true, false, undefined, true);

    // 2. Undo back to initial FEN
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3. Alternate move 'Bc4' after undo: truncates future history
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'c4' });
    });
    expect(dropResult).toBe(true);
    expect(capturedOptions.position).toBe(FEN_BC4);
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();

    // Undo after Bc4 must return to FEN_A, proving Bb5 future branch was truncated
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_BC4);

    // 4. Continuation non-initial move (historyIndex > 0)
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
    expect(dropResult).toBe(true);
    expect(onMoveAttempt).toHaveBeenLastCalledWith('Nf6', false, false, undefined, false);

    // 5. Illegal move drop throws in chess.js and returns false
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'e1', targetSquare: 'e8' });
    });
    expect(dropResult).toBe(false);
  });

  it('T32 — Initial puzzle move does not flag historical mistake when movesPlayed matches a different move', async () => {
    const onMoveAttempt = vi.fn();
    const props = boardProps({
      onMoveAttempt,
      targetMove: 'Bb5',
      movesPlayed: [{ move: 'Bc4', timesPlayed: 2, averageLoss: 1.5 }],
    });
    render(<ChessBoardArea {...props} />);

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(onMoveAttempt).toHaveBeenLastCalledWith('Bb5', true, false, undefined, true);
  });

  it('T33 — Undo and Redo pass preserved gameHistory instances to cloneChessGameWithHistory', async () => {
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    const props = boardProps({
      initialMoveHistorySan: ['e4', 'e5', 'Nf3', 'Nc6'],
      initialMoveHistoryStartFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    });
    render(<ChessBoardArea {...props} />);

    // Make 1 move
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });

    cloneSpy.mockClear();

    // Undo: cloneChessGameWithHistory must receive gameHistory[prevIndex] preserving 'e4' and 'Nc6'
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(cloneSpy).toHaveBeenCalledTimes(1);
    const undoPassedGame = cloneSpy.mock.calls[0][0];
    expect(undoPassedGame.history()).toContain('e4');
    expect(undoPassedGame.history()).toContain('Nc6');

    cloneSpy.mockClear();

    // Redo: cloneChessGameWithHistory must receive gameHistory[nextIndex] preserving 'Bb5'
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(cloneSpy).toHaveBeenCalledTimes(1);
    const redoPassedGame = cloneSpy.mock.calls[0][0];
    expect(redoPassedGame.history()).toContain('Bb5');
  });

  it('T34 — Exploration move drops: rejects in CHALLENGE mode, clears styles and returns true in CHESSECHO mode', async () => {
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValue(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0,
        evalCp: 20,
        bestMove: 'Bb5',
      })
    );

    // 1. In CHALLENGE mode: returns false immediately
    const challengeProps = boardProps({
      isExplorationActive: true,
      explorationPlayMode: 'CHALLENGE',
    });
    const { rerender } = render(<ChessBoardArea {...challengeProps} />);
    let dropResult = true;
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(dropResult).toBe(false);

    // 2. In CHESSECHO mode: clears custom styles, returns true, notifies onFenChange
    const onFenChange = vi.fn();
    const chessEchoProps = boardProps({
      isExplorationActive: true,
      explorationPlayMode: 'CHESSECHO',
      onFenChange,
    });
    await act(async () => {
      rerender(<ChessBoardArea {...chessEchoProps} />);
    });

    // Paint hint styles first
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Hint/i }));
    });
    expect(Object.keys(capturedOptions.squareStyles).length).toBeGreaterThan(0);

    // Drop piece
    await act(async () => {
      dropResult = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(dropResult).toBe(true);
    expect(capturedOptions.squareStyles).toEqual({});
    await act(async () => {});
    expect(onFenChange).toHaveBeenCalledWith(FEN_BB5);

    // 3. Drop with onFenChange undefined does not throw
    await act(async () => {
      rerender(<ChessBoardArea {...chessEchoProps} onFenChange={undefined} />);
    });
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
  });

  it('T35 — Pending move evaluation transitions notify onMoveEvaluationPendingChange at count boundaries', async () => {
    let resolve1: (val: MoveEvaluationResponse | null) => void = () => {};
    let resolve2: (val: MoveEvaluationResponse | null) => void = () => {};
    const promise1 = new Promise<MoveEvaluationResponse | null>((r) => {
      resolve1 = r;
    });
    const promise2 = new Promise<MoveEvaluationResponse | null>((r) => {
      resolve2 = r;
    });

    vi.mocked(moveEvaluationService.evaluateMove)
      .mockReturnValueOnce(promise1)
      .mockReturnValueOnce(promise2);

    const onPendingChange = vi.fn();
    render(
      <ChessBoardArea
        {...boardProps({
          isExplorationActive: true,
          explorationPlayMode: 'CHESSECHO',
          onMoveEvaluationPendingChange: onPendingChange,
        })}
      />
    );

    // First move: pending transitions 0 -> 1 (notifies true)
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    expect(onPendingChange).toHaveBeenCalledTimes(1);
    expect(onPendingChange).toHaveBeenLastCalledWith(true);

    // Second move: pending transitions 1 -> 2 (does not notify true again)
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
    expect(onPendingChange).toHaveBeenCalledTimes(1);

    // First promise resolves: pending transitions 2 -> 1 (does not notify false)
    await act(async () => {
      resolve1(
        createMoveEvalResponse({
          acceptable: true,
          evalLoss: 0,
          evalCp: 0,
          bestMove: 'Bb5',
        })
      );
    });
    expect(onPendingChange).toHaveBeenCalledTimes(1);

    // Second promise resolves: pending transitions 1 -> 0 (notifies false)
    await act(async () => {
      resolve2(
        createMoveEvalResponse({
          acceptable: true,
          evalLoss: 0,
          evalCp: 0,
          bestMove: 'Nf6',
        })
      );
    });
    expect(onPendingChange).toHaveBeenCalledTimes(2);
    expect(onPendingChange).toHaveBeenLastCalledWith(false);
  });

  it('T36 — Exploration evaluation handles user vs opponent turns, strict threshold boundary (0.20), and inaccurate moves', async () => {
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    const onUnacceptableMove = vi.fn();
    const props = boardProps({
      isExplorationActive: true,
      explorationPlayMode: 'BOTH_SIDES',
      playerColor: 'WHITE',
      onUnacceptableMove,
      initialMoveHistorySan: ['e4', 'e5', 'Nf3', 'Nc6'],
      initialMoveHistoryStartFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    });
    const { rerender } = render(<ChessBoardArea {...props} />);

    // 1. User turn in BOTH_SIDES mode (White turn): isOpponentTurn is false -> strict threshold is NOT used.
    // Move with evalLoss: 0.50 and acceptable: true is accepted.
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0.5,
        evalCp: 30,
        bestMove: 'Bb5',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(null);

    // 2. Opponent turn in BOTH_SIDES mode (Black turn): isOpponentTurn is true.
    // Exact strict threshold boundary: lossCp = 0.20 with acceptable: false.
    // lossCp <= 0.20 is true, so acceptable becomes true and move is accepted!
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: false,
        evalLoss: 0.2,
        evalCp: 10,
        bestMove: 'a6',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'a7', targetSquare: 'a6' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).not.toBe(FEN_BB5);
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(null);

    // 3. Inaccurate move in CHESSECHO mode: acceptable: false, evalLoss: 3.50.
    // Reset to FEN_A so board is clean and white bishop is on f1
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    await act(async () => {
      rerender(
        <ChessBoardArea
          {...boardProps({
            isExplorationActive: true,
            explorationPlayMode: 'CHESSECHO',
            onUnacceptableMove,
            initialMoveHistorySan: ['e4', 'e5', 'Nf3', 'Nc6'],
            initialMoveHistoryStartFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
          })}
        />
      );
    });

    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: false,
        evalLoss: 3.5,
        evalCp: -300,
        bestMove: 'Bb5',
      })
    );

    cloneSpy.mockClear();
    vi.mocked(playSound).mockClear();

    // In FEN_A, Be2 is a legal move but evaluated as inaccurate
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'e2' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_A); // rolled back
    expect(playSound).toHaveBeenCalledWith('incorrect');
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(
      'That move is too inaccurate. It loses 3.50 pawns compared with the best move.'
    );
    expect(cloneSpy).toHaveBeenCalled();
    const rollbackGame = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(rollbackGame.history()).toContain('e4');

    // 4. Mutant 31: playerColor is BLACK playing own turn in BOTH_SIDES mode (isOpponentTurn = false).
    // An acceptable move with evalLoss > 0.20 (e.g. 0.25) must NOT use strict threshold.
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...boardProps({
            playerColor: 'BLACK',
            initialFen: FEN_BB5,
            isExplorationActive: true,
            explorationPlayMode: 'BOTH_SIDES',
            onUnacceptableMove,
          })}
        />
      );
    });

    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0.25,
        evalCp: -20,
        bestMove: 'a6',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'a7', targetSquare: 'a6' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).not.toBe(FEN_BB5);
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(null);

    // 5. Mutant 41: opponent turn in CHESSECHO mode (NOT BOTH_SIDES mode).
    // strict threshold must only apply when explorationPlayMode === 'BOTH_SIDES'.
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...boardProps({
            playerColor: 'WHITE',
            initialFen: FEN_BB5,
            isExplorationActive: true,
            explorationPlayMode: 'CHESSECHO',
            onUnacceptableMove,
          })}
        />
      );
    });

    // Reset board so it is at initialFen FEN_BB5 with Black to move
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_BB5);

    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0.25,
        evalCp: -20,
        bestMove: 'a6',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'a7', targetSquare: 'a6' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).not.toBe(FEN_BB5);
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(null);
  });

  it('T37 — Sound effects and optional callbacks on exploration moves', async () => {
    vi.mocked(playSound).mockClear();
    const onUserExplorationMove = vi.fn();
    const props = boardProps({
      isExplorationActive: true,
      explorationPlayMode: 'CHESSECHO',
      onUserExplorationMove,
      onUnacceptableMove: undefined,
    });
    const { rerender } = render(<ChessBoardArea {...props} />);

    // 1. Best move (loss = 0): sound completion, onUserExplorationMove called with isBest: true
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0,
        evalCp: 50,
        bestMove: 'Bb5',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(playSound).toHaveBeenCalledWith('completion');
    expect(onUserExplorationMove).toHaveBeenLastCalledWith(
      'Bb5',
      FEN_BB5,
      expect.objectContaining({ isBest: true, loss: 0 })
    );

    // 2. Acceptable non-best move (loss > 0): sound correct, onUserExplorationMove called with isBest: false
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0.15,
        evalCp: 35,
        bestMove: 'a6',
      })
    );

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'g8', targetSquare: 'f6' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(playSound).toHaveBeenCalledWith('correct');
    expect(onUserExplorationMove).toHaveBeenLastCalledWith(
      'Nf6',
      expect.any(String),
      expect.objectContaining({ isBest: false, loss: 0.15 })
    );

    // 3. Optional chaining on callbacks: moves with onUserExplorationMove, onUnacceptableMove, and onFenChange undefined
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...boardProps({
            isExplorationActive: true,
            explorationPlayMode: 'CHESSECHO',
            onUserExplorationMove: undefined,
            onUnacceptableMove: undefined,
            onFenChange: undefined,
          })}
        />
      );
    });

    // Reset board back to FEN_A so pieces are at starting exploration position
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3a. Best move (loss = 0): tests onUserExplorationMove?.() (Mutant 88)
    vi.mocked(playSound).mockClear();
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0,
        evalCp: 0,
        bestMove: 'Bb5',
      })
    );
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(playSound).toHaveBeenLastCalledWith('completion');
    expect(playSound).not.toHaveBeenCalledWith('move');

    // Reset to FEN_A for next move
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3b. Non-best acceptable move (loss > 0): tests onUserExplorationMove?.() (Mutant 94)
    vi.mocked(playSound).mockClear();
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0.1,
        evalCp: 0,
        bestMove: 'Bb5',
      })
    );
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'c4' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(playSound).toHaveBeenLastCalledWith('correct');
    expect(playSound).not.toHaveBeenCalledWith('move');

    // Reset to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3c. Unacceptable move: tests onUnacceptableMove?.() (Mutant 80)
    vi.mocked(playSound).mockClear();
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(
      createMoveEvalResponse({
        acceptable: false,
        evalLoss: 2.0,
        evalCp: 0,
        bestMove: 'Bb5',
      })
    );
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'e2' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(playSound).toHaveBeenLastCalledWith('incorrect');
    expect(playSound).not.toHaveBeenCalledWith('move');

    // Reset to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3d. Null resolution fallback: tests onUnacceptableMove?.() (Mutant 115)
    vi.mocked(playSound).mockClear();
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(null);
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'e2' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(playSound).toHaveBeenLastCalledWith('incorrect');
    expect(playSound).not.toHaveBeenCalledWith('move');

    // Reset to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3e. Network error fallback in catch: tests onUnacceptableMove?.(), onFenChange?.(), onUserExplorationMove?.() (Mutants 124, 137)
    vi.mocked(playSound).mockClear();
    vi.mocked(moveEvaluationService.evaluateMove).mockRejectedValueOnce(new Error('Network error'));
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(playSound).toHaveBeenLastCalledWith('move');
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();

    // Undo returns to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3f. Network error when onFenChange is undefined but onUserExplorationMove is provided (Mutant 136)
    const onUserExplorationMoveFn = vi.fn();
    await act(async () => {
      rerender(
        <ChessBoardArea
          {...boardProps({
            isExplorationActive: true,
            explorationPlayMode: 'CHESSECHO',
            onUserExplorationMove: onUserExplorationMoveFn,
            onUnacceptableMove: undefined,
            onFenChange: undefined,
          })}
        />
      );
    });
    vi.mocked(moveEvaluationService.evaluateMove).mockRejectedValueOnce(new Error('Network error'));
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(onUserExplorationMoveFn).toHaveBeenCalledWith(
      'Bb5',
      FEN_BB5,
      expect.objectContaining({ isBest: false, loss: 0 })
    );
  });

  it('T38 — History truncation during exploration: undoing then playing alternate move truncates future history', async () => {
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValue(
      createMoveEvalResponse({
        acceptable: true,
        evalLoss: 0,
        evalCp: 0,
        bestMove: 'Bb5',
      })
    );

    render(
      <ChessBoardArea
        {...boardProps({
          isExplorationActive: true,
          explorationPlayMode: 'CHESSECHO',
        })}
      />
    );

    // 1. Play first exploration move Bb5
    let dropRes = false;
    await act(async () => {
      dropRes = capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(dropRes).toBe(true);
    expect(capturedOptions.position).toBe(FEN_BB5);

    // 2. Undo back to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(screen.getByTitle(/Next Move/i)).not.toBeDisabled();

    // Redo back to FEN_BB5: checks gameHistory preserves gameCopy with Bb5 history (Mutant 104)
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    cloneSpy.mockClear();
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    const redoGame = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(redoGame.history()).toContain('Bb5');

    // Undo back to FEN_A again
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // 3. Play alternative move Bc4: truncates future history, Next Move is disabled
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'c4' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_BC4);
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();

    // 4. Undo after Bc4 must return to FEN_A, proving Bb5 future branch was truncated
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    cloneSpy.mockClear();
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_BC4);
    const redoBc4Game = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(redoBc4Game.history()).toContain('Bc4');
  });

  it('T39 — Fallback handling when evaluateMove resolves to null or rejects with network error', async () => {
    const onUnacceptableMove = vi.fn();
    const onUserExplorationMove = vi.fn();
    const onFenChange = vi.fn();
    const props = boardProps({
      isExplorationActive: true,
      explorationPlayMode: 'CHESSECHO',
      initialMoveHistorySan: ['e4', 'e5', 'Nf3', 'Nc6'],
      initialMoveHistoryStartFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      onUnacceptableMove,
      onUserExplorationMove,
      onFenChange,
    });
    render(<ChessBoardArea {...props} />);

    // 1. evaluateMove resolves to null: rollback and failure message
    vi.mocked(moveEvaluationService.evaluateMove).mockResolvedValueOnce(null);
    vi.mocked(playSound).mockClear();
    const cloneSpy = vi.spyOn(chessGame, 'cloneChessGameWithHistory');
    cloneSpy.mockClear();

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(playSound).toHaveBeenCalledWith('incorrect');
    expect(onUnacceptableMove).toHaveBeenLastCalledWith('Evaluation failed. Please try again.');
    // Mutant 114: rollback game must use gameHistory[historyIndex], which preserves initial move history
    const rollbackGame = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(rollbackGame.history()).toContain('e4');

    // 2. evaluateMove rejects (network failure): fallback accepts move
    vi.mocked(moveEvaluationService.evaluateMove).mockRejectedValueOnce(new Error('Network error'));
    vi.mocked(playSound).mockClear();

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    expect(playSound).toHaveBeenCalledWith('move');
    expect(onUnacceptableMove).toHaveBeenLastCalledWith(null);
    expect(onUserExplorationMove).toHaveBeenLastCalledWith(
      'Bb5',
      FEN_BB5,
      expect.objectContaining({ isBest: false, loss: 0, evalCp: null })
    );

    // Navigation and history management after network fallback (Mutants 125, 126, 127, 128, 130, 131, 132, 133, 135)
    expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();

    // Undo returns to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(screen.getByTitle(/Next Move/i)).not.toBeDisabled();

    // Redo returns to FEN_BB5
    cloneSpy.mockClear();
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_BB5);
    const redoGame = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(redoGame.history()).toContain('Bb5');
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();

    // Undo back to FEN_A and play alternative move Bc4 with another network error
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    vi.mocked(moveEvaluationService.evaluateMove).mockRejectedValueOnce(new Error('Network error 2'));
    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'c4' });
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_BC4);
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();

    // Undo after Bc4 must return to FEN_A, proving Bb5 branch was truncated
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Previous Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_A);
    cloneSpy.mockClear();
    await act(async () => {
      fireEvent.click(screen.getByTitle(/Next Move/i));
    });
    expect(capturedOptions.position).toBe(FEN_BC4);
    const redoBc4 = cloneSpy.mock.calls[cloneSpy.mock.calls.length - 1][0];
    expect(redoBc4.history()).toContain('Bc4');

    // 3. Stale evaluation guard on reject: board changed before error caught (Mutants 119, 121)
    let rejectPromise: (err: unknown) => void = () => {};
    const deferredReject = new Promise<MoveEvaluationResponse | null>((_, rej) => {
      rejectPromise = rej;
    });
    vi.mocked(moveEvaluationService.evaluateMove).mockReturnValueOnce(deferredReject);

    // Reset board to FEN_A
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    await act(async () => {
      capturedOptions.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
    });
    // User resets board while request is pending
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Reset/i }));
    });
    expect(capturedOptions.position).toBe(FEN_A);

    // Clear all callback mocks before rejecting deferred promise
    onUserExplorationMove.mockClear();
    onFenChange.mockClear();
    vi.mocked(playSound).mockClear();
    onUnacceptableMove.mockClear();

    // Now reject the in-flight promise
    await act(async () => {
      rejectPromise(new Error('Delayed network error'));
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(capturedOptions.position).toBe(FEN_A);
    expect(onUserExplorationMove).not.toHaveBeenCalled();
    expect(onFenChange).not.toHaveBeenCalled();
    expect(vi.mocked(playSound)).not.toHaveBeenCalled();
    expect(onUnacceptableMove).not.toHaveBeenCalled();
  });
});
