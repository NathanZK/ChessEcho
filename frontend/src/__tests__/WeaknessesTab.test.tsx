import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { WeaknessesList, adaptWeaknessToPuzzle } from '../components/WeaknessesList';
import * as api from '../services/api';
import { WeaknessResponse } from '../services/api';

// Mock react-chessboard to prevent canvas / window rendering issues in jsdom environment
vi.mock('react-chessboard', () => ({
  Chessboard: () => <div data-testid="mock-chessboard">Mock Chessboard</div>,
}));

class MockIntersectionObserver {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}
Object.defineProperty(window, 'IntersectionObserver', { writable: true, configurable: true, value: MockIntersectionObserver });
Object.defineProperty(global, 'IntersectionObserver', { writable: true, configurable: true, value: MockIntersectionObserver });

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchWeaknesses: vi.fn(),
  };
});

const mockWeaknessItem: WeaknessResponse = {
  positionId: 'w-pos-123',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  timesReached: 15,
  mistakeCount: 5,
  mistakeRate: 33.3,
  averageLoss: 1.25,
  priority: 4.2,
  bestMove: 'Nc6',
  acceptableMoves: [{ move: 'Nf6', evalLoss: 0.1 }],
  movesPlayed: [
    { move: 'Bc5', timesPlayed: 4, averageLoss: 1.3 },
    { move: 'f5', timesPlayed: 1, averageLoss: 1.05 },
  ],
  gameUrls: [
    'https://www.chess.com/game/live/10001',
    'https://www.chess.com/game/live/10002',
  ],
  evalCp: 35,
};

describe('Weaknesses Tab MVP', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([mockWeaknessItem]);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('1. API Service Serialization', () => {
    it('serializes fetchWeaknesses params with uppercase platform, playerColor, page, and size', async () => {
      const actualApi = await vi.importActual<typeof import('../services/api')>('../services/api');

      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
        ok: true,
        json: async () => [mockWeaknessItem],
      }));

      const res = await actualApi.fetchWeaknesses('hikaru', 'chess_com', 'both', 0.8, 3, 0, 20);

      expect(global.fetch).toHaveBeenCalledTimes(1);
      const url = vi.mocked(global.fetch).mock.calls[0][0] as string;

      expect(url).toContain('platform=CHESS_COM');
      expect(url).toContain('username=hikaru');
      expect(url).toContain('playerColor=BOTH');
      expect(url).toContain('minEvalLoss=0.8');
      expect(url).toContain('minMistakeCount=3');
      expect(url).toContain('page=0');
      expect(url).toContain('size=20');
      expect(res).toEqual([mockWeaknessItem]);
    });
  });

  describe('2. WeaknessResponse to Puzzle Adapter', () => {
    it('correctly adapts WeaknessResponse into Puzzle model with turn derived from FEN and targetMove derived from bestMove', () => {
      const puzzle = adaptWeaknessToPuzzle(mockWeaknessItem);

      expect(puzzle.puzzleId).toBe('w-pos-123');
      expect(puzzle.fen).toBe(mockWeaknessItem.fen);
      expect(puzzle.playerColor).toBe('BLACK'); // derived from 'b' in FEN
      expect(puzzle.targetMove).toBe('Nc6');
      expect(puzzle.timesReached).toBe(15);
      expect(puzzle.mistakeCount).toBe(5);
      expect(puzzle.mistakeRate).toBe(33.3);
      expect(puzzle.gameUrls).toEqual(mockWeaknessItem.gameUrls);
    });

    it('handles white FEN turn correctly', () => {
      const whiteItem: WeaknessResponse = {
        ...mockWeaknessItem,
        fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        bestMove: 'Bb5',
      };
      const puzzle = adaptWeaknessToPuzzle(whiteItem);
      expect(puzzle.playerColor).toBe('WHITE');
      expect(puzzle.targetMove).toBe('Bb5');
    });
  });

  describe('3. WeaknessesList UX States & Interactions', () => {
    it('renders disconnected state when no username is provided', () => {
      render(<WeaknessesList username={undefined} onSelectPractice={vi.fn()} />);

      expect(screen.getByText('No Connected Account')).toBeInTheDocument();
      expect(screen.getByText(/Connect your Chess\.com account in the Import Games tab/i)).toBeInTheDocument();
      expect(api.fetchWeaknesses).not.toHaveBeenCalled();
    });

    it('renders loading spinner while fetching data', async () => {
      let resolveFn: (data: WeaknessResponse[]) => void;
      const pendingPromise = new Promise<WeaknessResponse[]>((resolve) => {
        resolveFn = resolve;
      });
      vi.mocked(api.fetchWeaknesses).mockReturnValue(pendingPromise);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      expect(screen.getByText('Loading Recurring Weaknesses...')).toBeInTheDocument();

      resolveFn!([mockWeaknessItem]);

      await waitFor(() => {
        expect(screen.getByText('33.3% (5x)')).toBeInTheDocument();
      });
    });

    it('renders empty response state when API returns no weaknesses', async () => {
      vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      await waitFor(() => {
        expect(screen.getByText('No Recurring Weaknesses Found')).toBeInTheDocument();
        expect(screen.getByText(/No positions met your weakness filter criteria/i)).toBeInTheDocument();
      });
    });

    it('renders error state when API call fails', async () => {
      vi.mocked(api.fetchWeaknesses).mockRejectedValue(new Error('Network error'));

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      await waitFor(() => {
        expect(screen.getByText('Failed to Load Weaknesses')).toBeInTheDocument();
      });
    });

    it('renders real weakness card with evidence metrics without displaying raw priority value', async () => {
      const onWeaknessCountChange = vi.fn();
      render(
        <WeaknessesList
          username="hikaru"
          onSelectPractice={vi.fn()}
          onWeaknessCountChange={onWeaknessCountChange}
        />
      );

      await waitFor(() => {
        expect(screen.getByText('As BLACK')).toBeInTheDocument();
        expect(screen.getByText('33.3% (5x)')).toBeInTheDocument();
        expect(screen.getByText('15')).toBeInTheDocument();
        expect(screen.getByText('-1.25 pawns')).toBeInTheDocument();
        expect(screen.queryByText('4.20')).not.toBeInTheDocument();
        expect(screen.getByText(/Your Historical Decisions:/i)).toBeInTheDocument();
        expect(screen.getByText(/Bc5 \(4x, -1\.30 pawns\)/i)).toBeInTheDocument();
      });

      await waitFor(() => {
        expect(onWeaknessCountChange).toHaveBeenCalledWith(1);
      });
    });

    it('refetches and resets page 0 when color filter, min mistake count, or minEvalLoss threshold changes', async () => {
      const onMinEvalLossChange = vi.fn();
      render(
        <WeaknessesList
          username="hikaru"
          minEvalLoss={0.8}
          onMinEvalLossChange={onMinEvalLossChange}
          onSelectPractice={vi.fn()}
        />
      );

      await waitFor(() => {
        expect(api.fetchWeaknesses).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 0, 20);
      });

      vi.clearAllMocks();

      // Change Color Filter to White
      const whiteFilterBtn = screen.getByRole('button', { name: /^WHITE$/i });
      fireEvent.click(whiteFilterBtn);

      await waitFor(() => {
        expect(api.fetchWeaknesses).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'WHITE', 0.8, 3, 0, 20);
      });

      vi.clearAllMocks();

      // Change Mistake Threshold selector
      const selects = screen.getAllByRole('combobox');
      const thresholdSelect = selects[0];
      fireEvent.change(thresholdSelect, { target: { value: '0.5' } });

      expect(onMinEvalLossChange).toHaveBeenCalledWith(0.5);
    });

    it('opens historical games modal when View Games is clicked', async () => {
      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      await waitFor(() => {
        expect(screen.getByText(/View Games \(2\)/i)).toBeInTheDocument();
      });

      const viewGamesBtn = screen.getByText(/View Games \(2\)/i);
      fireEvent.click(viewGamesBtn);

      expect(screen.getByText('Historical Games')).toBeInTheDocument();
      expect(screen.getByText(/Game #1: https:\/\/www\.chess\.com\/game\/live\/10001/i)).toBeInTheDocument();
      expect(screen.getByText(/Game #2: https:\/\/www\.chess\.com\/game\/live\/10002/i)).toBeInTheDocument();

      // Close modal
      const closeBtn = screen.getByRole('button', { name: /^Close$/i });
      fireEvent.click(closeBtn);

      expect(screen.queryByText('Historical Games')).not.toBeInTheDocument();
    });

    it('invokes onSelectPractice with adapted Puzzle when Practice Position is clicked', async () => {
      const onSelectPractice = vi.fn();
      render(<WeaknessesList username="hikaru" onSelectPractice={onSelectPractice} />);

      await waitFor(() => {
        expect(screen.getByRole('button', { name: /Practice Position/i })).toBeInTheDocument();
      });

      const practiceBtn = screen.getByRole('button', { name: /Practice Position/i });
      fireEvent.click(practiceBtn);

      expect(onSelectPractice).toHaveBeenCalledTimes(1);
      const adaptedPuzzle = onSelectPractice.mock.calls[0][0];

      expect(adaptedPuzzle.puzzleId).toBe('w-pos-123');
      expect(adaptedPuzzle.targetMove).toBe('Nc6');
      expect(adaptedPuzzle.playerColor).toBe('BLACK');
    });

    it('maintains persistent sentinel element in DOM and appends page 1', async () => {
      const page0Items: WeaknessResponse[] = Array.from({ length: 20 }, (_, idx) => ({
        ...mockWeaknessItem,
        positionId: `pos-p0-${idx}`,
      }));

      const page1Items: WeaknessResponse[] = Array.from({ length: 5 }, (_, idx) => ({
        ...mockWeaknessItem,
        positionId: `pos-p1-${idx}`,
      }));

      vi.mocked(api.fetchWeaknesses)
        .mockResolvedValueOnce(page0Items)
        .mockResolvedValueOnce(page1Items);

      render(<WeaknessesList username="hikaru" onSelectPractice={vi.fn()} />);

      // Verify persistent sentinel div is mounted in DOM
      const sentinel = await screen.findByTestId('weaknesses-sentinel');
      expect(sentinel).toBeInTheDocument();

      const loadMoreBtn = await screen.findByRole('button', { name: /Load More Weaknesses/i });
      fireEvent.click(loadMoreBtn);

      await waitFor(() => {
        expect(api.fetchWeaknesses).toHaveBeenCalledWith('hikaru', 'CHESS_COM', 'BOTH', 0.8, 3, 1, 20);
      });

      // After loading page 1 (5 items < 20), sentinel remains mounted but loadMore button is no longer shown
      await waitFor(() => {
        expect(screen.queryByRole('button', { name: /Load More Weaknesses/i })).not.toBeInTheDocument();
        expect(screen.getByTestId('weaknesses-sentinel')).toBeInTheDocument();
      });
    });
  });
});
