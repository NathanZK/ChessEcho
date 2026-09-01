import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Chess } from 'chess.js';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService } from '../services/continuationService';

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchWeaknesses: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
  };
});

describe('weakness decision exploration', () => {
  const sourceFen =
    'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 2 3';
  const afterDecision = new Chess(sourceFen);
  afterDecision.move('Bc5');

  const weakness: api.WeaknessResponse = {
    positionId: 'decision-position',
    fen: sourceFen,
    timesReached: 8,
    mistakeCount: 4,
    mistakeRate: 50,
    averageLoss: 1.2,
    priority: 5,
    bestMove: 'Nf6',
    acceptableMoves: [{ move: 'Nf6', evalLoss: 0 }],
    movesPlayed: [{ move: 'Bc5', timesPlayed: 4, averageLoss: 1.2 }],
    gameUrls: [],
    evalCp: 20,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    localStorage.clear();
    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weaknesses';
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([weakness]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue(null);
  });

  it('starts existing line exploration after the historical move and requests the opponent continuation', async () => {
    await act(async () => {
      render(<Home />);
    });

    const action = await screen.findByRole('button', { name: 'Explore this decision' });
    fireEvent.click(action);

    expect(window.location.hash).toBe('#puzzles');
    expect(await screen.findByText('Exploring your historical decision')).toBeInTheDocument();
    expect(screen.getByText(/The board starts after/)).toHaveTextContent('Bc5');
    expect(screen.getByText('Line Exploration')).toBeInTheDocument();

    await waitFor(() => {
      expect(api.fetchPuzzleContinuation).toHaveBeenCalledWith(
        afterDecision.fen(),
        'ENGINE',
        '1200-1400'
      );
    });

    fireEvent.click(screen.getByTitle('Reset Position'));
    expect(screen.getByText('Exploring your historical decision')).toBeInTheDocument();
    expect(screen.getByText('Line Exploration')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Exit' }));
    expect(window.location.hash).toBe('#weaknesses');
    expect(await screen.findByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
  });
});
