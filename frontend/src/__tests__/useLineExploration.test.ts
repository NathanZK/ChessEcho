import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useLineExploration } from '@/hooks/useLineExploration';
import { Puzzle } from '@/types/puzzle';

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<typeof import('@/services/api')>('@/services/api');
  return {
    ...actual,
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

const mockPuzzle: Puzzle = {
  puzzleId: 'puzzle-1',
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  playerColor: 'WHITE',
  targetMove: 'e4',
  openingTitle: 'Test Opening',
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1,
  timesReached: 1,
  mistakeCount: 1,
  mistakeRate: 1,
  evalCp: 35,
};

describe('useLineExploration', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('starts inactive and enters exploration mode', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();
    const onExitExploration = vi.fn();

    const { result } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: mockPuzzle.fen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
        onExitExploration,
      })
    );

    expect(result.current.isExplorationActive).toBe(false);

    act(() => {
      result.current.handleEnterExploration('CHESSECHO');
    });

    expect(result.current.isExplorationActive).toBe(true);
    expect(result.current.explorationPlayMode).toBe('CHESSECHO');
    expect(onFeedbackChange).toHaveBeenCalledWith({ status: 'EXPLORING' });
  });

  it('updates evaluation when a user exploration move is performed', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();
    const onExitExploration = vi.fn();

    let boardFen = mockPuzzle.fen;
    const { result, rerender } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: boardFen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
        onExitExploration,
      })
    );

    act(() => {
      result.current.handleEnterExploration('BOTH_SIDES');
    });

    const nextFen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';
    act(() => {
      result.current.handleUserExplorationMove('e4', nextFen, {
        isBest: true,
        loss: 0,
        evalCp: 40,
        fromFen: mockPuzzle.fen,
      });
      boardFen = nextFen;
    });
    rerender();

    expect(onUpdateEval).toHaveBeenCalledWith(40, false);
    expect(result.current.explorationFeedback?.type).toBe('best');
  });

  it('exits exploration cleanly and notifies onExitExploration', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();
    const onExitExploration = vi.fn();

    const { result } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: mockPuzzle.fen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
        onExitExploration,
      })
    );

    act(() => {
      result.current.handleEnterExploration('CHESSECHO');
    });
    expect(result.current.isExplorationActive).toBe(true);

    act(() => {
      result.current.handleExitExploration();
    });

    expect(result.current.isExplorationActive).toBe(false);
    expect(result.current.explorationPlayMode).toBeUndefined();
    expect(onExitExploration).toHaveBeenCalled();
  });

  it('formats acceptable alternative feedback naming best move', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();

    let boardFen = mockPuzzle.fen;
    const { result, rerender } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: boardFen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
      })
    );

    act(() => {
      result.current.handleEnterExploration('BOTH_SIDES');
    });

    const nextFen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';
    act(() => {
      result.current.handleUserExplorationMove('e4', nextFen, {
        isBest: false,
        loss: 0.25,
        evalCp: 30,
        fromFen: mockPuzzle.fen,
        bestMove: 'd4',
      });
      boardFen = nextFen;
    });
    rerender();

    expect(result.current.explorationFeedback?.type).toBe('good');
    expect(result.current.explorationFeedback?.message).toBe(
      'Good move. It loses 0.25 pawns compared with the best move (d4).'
    );
  });

  it('formats acceptable alternative feedback without best move when omitted', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();

    let boardFen = mockPuzzle.fen;
    const { result, rerender } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: boardFen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
      })
    );

    act(() => {
      result.current.handleEnterExploration('BOTH_SIDES');
    });

    const nextFen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';
    act(() => {
      result.current.handleUserExplorationMove('e4', nextFen, {
        isBest: false,
        loss: 0.15,
        evalCp: 30,
        fromFen: mockPuzzle.fen,
      });
      boardFen = nextFen;
    });
    rerender();

    expect(result.current.explorationFeedback?.type).toBe('good');
    expect(result.current.explorationFeedback?.message).toBe(
      'Good move. It loses 0.15 pawns compared with the best move.'
    );
  });

  it('clears exploration feedback when resetExploration is invoked', () => {
    const onUpdateEval = vi.fn();
    const onFeedbackChange = vi.fn();

    let boardFen = mockPuzzle.fen;
    const { result, rerender } = renderHook(() =>
      useLineExploration({
        activePuzzle: mockPuzzle,
        currentBoardFen: boardFen,
        currentEvalCp: 35,
        isEvalUnknown: false,
        onUpdateEval,
        onFeedbackChange,
      })
    );

    act(() => {
      result.current.handleEnterExploration('BOTH_SIDES');
    });

    const nextFen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';
    act(() => {
      result.current.handleUserExplorationMove('e4', nextFen, {
        isBest: true,
        loss: 0,
        evalCp: 40,
        fromFen: mockPuzzle.fen,
      });
      boardFen = nextFen;
    });
    rerender();
    expect(result.current.explorationFeedback?.type).toBe('best');

    act(() => {
      result.current.resetExploration();
      boardFen = mockPuzzle.fen;
    });
    rerender();
    expect(result.current.explorationFeedback).toBeNull();
  });
});
