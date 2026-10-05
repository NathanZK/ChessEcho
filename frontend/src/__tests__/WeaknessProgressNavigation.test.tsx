import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import type { AccountSummary, PositionProgressResponse, WeaknessResponse } from '../services/api';
import { activeTabStore, puzzleSettingsStore } from '../utils/browserStores';

const mocks = vi.hoisted(() => ({
  fetchCurrentSession: vi.fn(),
  fetchAccounts: vi.fn(),
  fetchPuzzles: vi.fn(),
  fetchWeaknesses: vi.fn(),
  fetchPositionProgress: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchCurrentSession: mocks.fetchCurrentSession,
    fetchAccounts: mocks.fetchAccounts,
    fetchPuzzles: mocks.fetchPuzzles,
    fetchWeaknesses: mocks.fetchWeaknesses,
    fetchPositionProgress: mocks.fetchPositionProgress,
    logout: mocks.logout,
  };
});

vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard" />,
}));

const account: AccountSummary = {
  id: 'account-1',
  platform: 'CHESS_COM',
  username: 'hikaru',
};

const weakness: WeaknessResponse = {
  positionId: 'position-451',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  timesReached: 10,
  mistakeCount: 4,
  mistakeRate: 40,
  averageLoss: 1.2,
  priority: 3,
  bestMove: 'Nc6',
  acceptableMoves: [],
  movesPlayed: [],
  gameUrls: [],
};

const progress: PositionProgressResponse = {
  positionId: weakness.positionId,
  playerColor: 'BLACK',
  baseline: {
    occurredAt: '2025-12-01T00:00:00Z',
    mistakeRate: 50,
    winRate: 25,
    sourceEncounterCount: 4,
  },
  points: [
    {
      checkpointId: '00000000-0000-0000-0000-000000000001',
      occurredAt: '2026-01-01T00:00:00Z',
      mistakeRate: 50,
      winRate: 25,
      attempts: 4,
      open: false,
    },
    {
      checkpointId: '00000000-0000-0000-0000-000000000002',
      occurredAt: '2026-02-01T00:00:00Z',
      mistakeRate: 25,
      winRate: 50,
      attempts: 8,
      open: true,
    },
  ],
  currentIntervalState: 'MEASURED_OPEN',
  excludedUndatedEncounters: 0,
  mistakeRateChange: -50,
  winRateChange: 100,
  assessment: 'You are making fewer mistakes at this position.',
};

describe('Weakness progress navigation', () => {
  beforeEach(() => {
    localStorage.clear();
    activeTabStore.set('weaknesses');
    vi.clearAllMocks();
    mocks.fetchCurrentSession.mockResolvedValue({
      status: 'authenticated',
      userId: 'user-451',
    });
    mocks.fetchAccounts.mockResolvedValue([account]);
    mocks.fetchPuzzles.mockResolvedValue([]);
    mocks.fetchWeaknesses.mockResolvedValue([weakness]);
    mocks.fetchPositionProgress.mockResolvedValue(progress);
    mocks.logout.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('opens the selected weakness progress with its FEN color and returns to the library', async () => {
    localStorage.setItem('chessecho_min_eval_loss', '0.3');
    render(<Home />);

    await screen.findByText('Recurring Opening Weaknesses Library');
    fireEvent.click(await screen.findByRole('button', { name: /view progress/i }));

    expect(await screen.findByRole('heading', { name: 'Position Progress' })).toBeInTheDocument();
    await waitFor(() => {
      expect(mocks.fetchPositionProgress).toHaveBeenCalledWith('position-451', 'BLACK', 0.3);
    });
    expect(screen.getByText(progress.assessment)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /back to weaknesses/i }));
    expect(await screen.findByText('Recurring Opening Weaknesses Library')).toBeInTheDocument();
  });

  it('freezes the selected threshold across global filter changes and uses the next result threshold on reselection', async () => {
    localStorage.setItem('chessecho_min_eval_loss', '0.3');
    mocks.fetchPositionProgress.mockRejectedValueOnce(new Error('Unavailable'));
    render(<Home />);

    fireEvent.click(await screen.findByRole('button', { name: /view progress/i }));
    await screen.findByText(/couldn't load position progress/i);
    expect(mocks.fetchPositionProgress).toHaveBeenLastCalledWith(weakness.positionId, 'BLACK', 0.3);

    act(() => {
      puzzleSettingsStore.set({ ...puzzleSettingsStore.getSnapshot(), minEvalLoss: 1.2 });
    });
    expect(mocks.fetchPositionProgress).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    await screen.findByText(progress.assessment);
    expect(mocks.fetchPositionProgress).toHaveBeenLastCalledWith(weakness.positionId, 'BLACK', 0.3);

    fireEvent.click(screen.getByRole('button', { name: /back to weaknesses/i }));
    await screen.findByRole('button', { name: /view progress/i });
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '0.5' } });
    await waitFor(() => {
      expect(mocks.fetchWeaknesses).toHaveBeenLastCalledWith(account.id, 'CHESS_COM', 'BOTH', 0.5, 3, 0, 20);
    });
    fireEvent.click(await screen.findByRole('button', { name: /view progress/i }));
    await screen.findByText(progress.assessment);
    expect(mocks.fetchPositionProgress).toHaveBeenLastCalledWith(weakness.positionId, 'BLACK', 0.5);
  });
});
