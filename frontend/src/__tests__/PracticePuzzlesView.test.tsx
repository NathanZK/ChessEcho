import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { PracticePuzzlesView } from '@/components/PracticePuzzlesView';
import * as api from '@/services/api';

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<typeof import('@/services/api')>('@/services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

describe('PracticePuzzlesView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders loading state initially while puzzles are loading', () => {
    vi.mocked(api.fetchPuzzles).mockReturnValue(new Promise(() => {}));

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
  });

  it('renders empty puzzle state when no puzzles are returned', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });
  });

  it('renders puzzle board and controls when puzzles are available', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([
      {
        puzzleId: 'puzzle-1',
        fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        playerColor: 'WHITE',
        targetMove: 'e4',
        openingTitle: 'King Pawn Game',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 35,
      },
    ]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toBeInTheDocument();
    });
  });
});
