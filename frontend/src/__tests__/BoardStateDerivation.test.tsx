import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { ChessBoardArea } from '../components/ChessBoardArea';
import { ContinuationCandidate } from '../services/api';

type MockChessboardOptions = {
  position: string;
  squareStyles: Record<string, React.CSSProperties>;
  onPieceDrop: (args: { sourceSquare: string; targetSquare: string }) => boolean;
  allowDragging: boolean;
};

type MockChessboardProps = { options?: MockChessboardOptions };

const emptyMockOptions: MockChessboardOptions = {
  position: '',
  squareStyles: {},
  onPieceDrop: () => false,
  allowDragging: false,
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
});
