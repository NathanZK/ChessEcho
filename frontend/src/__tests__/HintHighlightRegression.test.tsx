import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { ChessBoardArea } from '../components/ChessBoardArea';

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

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playSound: vi.fn(),
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

describe('Task 2 — Hint Highlight State Regression Test', () => {
  beforeEach(() => {
    capturedOptions = emptyMockOptions;
    vi.restoreAllMocks();
  });

  it('verifies hint highlight appears on hint click, disappears on move, and stays absent after undo', async () => {
    const mockOnMoveAttempt = vi.fn();
    const mockOnUndo = vi.fn();

    // Initial position where black queen is on d8 and best move target is Qh4
    const initialFen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';

    await act(async () => {
      render(
        <ChessBoardArea
          initialFen={initialFen}
          playerColor="BLACK"
          targetMove="e5"
          acceptableMoves={[]}
          movesPlayed={[]}
          onMoveAttempt={mockOnMoveAttempt}
          onNextPuzzle={() => {}}
          onUndo={mockOnUndo}
        />
      );
    });

    // 1 & 2. Click Hint -> verify highlight appears on e7 square
    const hintButton = screen.getByRole('button', { name: /Hint/i });
    await act(async () => {
      fireEvent.click(hintButton);
    });

    expect(capturedOptions.squareStyles).toBeDefined();
    expect(Object.keys(capturedOptions.squareStyles)).toContain('e7');
    expect(capturedOptions.squareStyles['e7'].backgroundColor).toBeDefined();

    // 3 & 4. Make a move -> verify highlight disappears
    await act(async () => {
      const dropSuccess = capturedOptions.onPieceDrop({
        sourceSquare: 'e7',
        targetSquare: 'e5',
      });
      expect(dropSuccess).toBe(true);
    });

    expect(capturedOptions.squareStyles).toEqual({});

    // 5 & 6. Click Undo -> verify highlight remains absent
    const undoButton = screen.getByTitle(/Previous Move/i);
    await act(async () => {
      fireEvent.click(undoButton);
    });

    expect(mockOnUndo).toHaveBeenCalled();
    expect(capturedOptions.squareStyles).toEqual({});
  });

  it('highlights the source square for current board FEN during Line Exploration and clears on move', async () => {
    // Current exploration branch position where White is to move: 1. e4 e5
    const explorationFen = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2';

    await act(async () => {
      render(
        <ChessBoardArea
          initialFen={explorationFen}
          playerColor="WHITE"
          targetMove="Nf3"
          acceptableMoves={[]}
          movesPlayed={[]}
          isExplorationActive={true}
          onMoveAttempt={() => {}}
          onNextPuzzle={() => {}}
        />
      );
    });

    // In exploration at this position, click Hint
    const hintButton = screen.getByRole('button', { name: /Hint/i });
    await act(async () => {
      fireEvent.click(hintButton);
    });

    // In exploration, candidate move Nf3 originates from g1
    expect(capturedOptions.squareStyles).toBeDefined();
    expect(Object.keys(capturedOptions.squareStyles)).toContain('g1');
    expect(capturedOptions.squareStyles['g1'].backgroundColor).toBeDefined();

    // After playing legal move g1 -> f3, highlight is cleared
    await act(async () => {
      const dropSuccess = capturedOptions.onPieceDrop({ sourceSquare: 'g1', targetSquare: 'f3' });
      expect(dropSuccess).toBe(true);
    });
    expect(capturedOptions.squareStyles).toEqual({});
  });
});
