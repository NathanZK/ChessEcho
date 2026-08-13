import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { ChessBoardArea } from '../components/ChessBoardArea';

let capturedOptions: any = {};

vi.mock('react-chessboard', () => ({
  Chessboard: (props: any) => {
    capturedOptions = props.options || {};
    return <div data-testid="mock-chessboard" />;
  },
}));

describe('Task 2 — Hint Highlight State Regression Test', () => {
  beforeEach(() => {
    capturedOptions = {};
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
});
