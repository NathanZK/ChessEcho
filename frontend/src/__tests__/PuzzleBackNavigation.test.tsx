import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

const mockWeakness: api.WeaknessResponse = {
  positionId: 'pos-nav-1',
  fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  timesReached: 5,
  mistakeCount: 3,
  mistakeRate: 60.0,
  averageLoss: 0.9,
  priority: 8.5,
  bestMove: 'e4',
  acceptableMoves: [],
  movesPlayed: [{ move: 'd4', timesPlayed: 3, averageLoss: 0.9 }],
  gameUrls: [],
  evalCp: 35,
};

describe('Task 3 — Puzzle Back Navigation Regression Test', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('verifies selecting practice position pushes #puzzles to history and browser back returns to weaknesses', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([mockWeakness]);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#import';

    await act(async () => {
      render(<Home />);
    });

    // 1. Navigate from Import tab to Weaknesses tab via header button
    const weaknessesTabBtn = screen.getByRole('button', { name: /Weaknesses Library/i });
    await act(async () => {
      fireEvent.click(weaknessesTabBtn);
    });

    expect(window.location.hash).toBe('#weaknesses');

    // Wait for weakness card to render
    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });

    // 2. Click Practice Position on weakness card
    const practiceBtn = await screen.findByRole('button', { name: /Practice Position/i });
    await act(async () => {
      fireEvent.click(practiceBtn);
    });

    // Verify hash was updated to #puzzles by changeTab('puzzles')
    expect(window.location.hash).toBe('#puzzles');
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();

    // 3. Simulate browser Back button (popstate / hashchange to #weaknesses)
    await act(async () => {
      window.location.hash = '#weaknesses';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    // Verify app navigated back to Weaknesses tab, not Import tab
    await waitFor(() => {
      expect(screen.getByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
    });
  });
});
