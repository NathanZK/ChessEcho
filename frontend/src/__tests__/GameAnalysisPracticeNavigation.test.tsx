import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeTabStore, gameAnalysisReturnStore } from '../utils/browserStores';

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options: {
      position: string;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div data-testid="mock-board" data-position={options.position}>
      {options.position}
    </div>
  ),
}));

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playSound: vi.fn(),
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchCurrentSession: vi.fn(),
    fetchAccounts: vi.fn(),
    fetchGames: vi.fn(),
    analyzeGame: vi.fn(),
    fetchSimilarPositions: vi.fn(),
    fetchRecurringWeaknesses: vi.fn().mockResolvedValue([]),
    fetchWeaknesses: vi.fn().mockResolvedValue([]),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

const mockAccount: api.AccountSummary = {
  id: '550e8400-e29b-41d4-a716-446655440010',
  platform: 'CHESS_COM',
  username: 'HeroPlayer',
};

const mockGamesResponse: api.GamePageResponse = {
  content: [
    {
      id: 'game-123',
      platformGameId: 'chess-com-1',
      timeControl: 'blitz',
      playedAt: '2026-11-09T18:00:00Z',
      result: 'win',
      whiteUsername: 'HeroPlayer',
      blackUsername: 'RivalPlayer',
      pgn: '1. e4 e5',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
};

const preMoveFen = 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4';

const mockAnalysisResponse: api.GameAnalysisResponse = {
  gameId: 'game-123',
  whiteUsername: 'HeroPlayer',
  blackUsername: 'RivalPlayer',
  timeControl: 'blitz',
  playedAt: '2026-11-09T18:00:00Z',
  result: 'win',
  analysisStatus: 'COMPLETE',
  plies: [
    {
      plyNumber: 1,
      movePlayed: 'e4',
      playerColor: 'WHITE',
      preMoveFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      decisionTimeMs: 2500,
      decisionSeverity: 'NO_CONCERN',
      bestMove: 'e4',
      bestEvalCp: 20,
      evalCp: 20,
      evalLoss: 0.0,
      weaknessSeverity: 'NO_CONCERN',
    },
    {
      plyNumber: 12,
      movePlayed: 'Nf6',
      playerColor: 'BLACK',
      preMoveFen,
      decisionTimeMs: 14000,
      decisionSeverity: 'SIGNIFICANT',
      bestMove: 'Nc6',
      bestEvalCp: -10,
      evalCp: 145,
      evalLoss: 1.55,
      weaknessSeverity: 'SIGNIFICANT',
    },
  ],
};

describe('Game Analysis Navigation and Practice Bridging', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.location.hash = '';
    activeTabStore.set('puzzles');
    gameAnalysisReturnStore.set(null);

    vi.mocked(api.fetchCurrentSession).mockResolvedValue({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.mocked(api.fetchAccounts).mockResolvedValue([mockAccount]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([]);
    vi.mocked(api.fetchGames).mockResolvedValue(mockGamesResponse);
    vi.mocked(api.analyzeGame).mockResolvedValue(mockAnalysisResponse);
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue({
      queryFen: preMoveFen,
      candidatesEvaluated: 10,
      shortlistedCandidatesCount: 2,
      engineCompletedCount: 2,
      dynamicSimilarityApplied: false,
      fallbackReason: null,
      queryStatus: 'SUCCESS',
      queryAchievedDepth: 16,
      queryPrincipalVariations: [],
      results: [],
    });
    vi.mocked(api.fetchRecurringWeaknesses).mockResolvedValue([]);
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  it('switches to Game Analysis tab via Tools menu and renders games list', async () => {
    render(<Home />);

    const toolsBtn = await screen.findByRole('button', { name: /Explore \/ Tools/i });
    fireEvent.click(toolsBtn);

    const gameAnalysisTabBtn = await screen.findByRole('menuitem', { name: /Game Analysis/i });
    fireEvent.click(gameAnalysisTabBtn);

    expect(activeTabStore.getSnapshot()).toBe('game-analysis');
    expect(await screen.findByRole('heading', { name: /My Games/i })).toBeInTheDocument();
    expect(await screen.findByText('RivalPlayer')).toBeInTheDocument();
  });

  it('analyzes game, practices mistake with concealed blunder move, and returns with context preserved', async () => {
    render(<Home />);

    // 1. Navigate to Game Analysis tab
    const toolsBtn = await screen.findByRole('button', { name: /Explore \/ Tools/i });
    fireEvent.click(toolsBtn);
    const gameAnalysisTabBtn = await screen.findByRole('menuitem', { name: /Game Analysis/i });
    fireEvent.click(gameAnalysisTabBtn);

    // 2. Select game to analyze
    const analyzeBtn = await screen.findByRole('button', { name: /Analyze/i });
    fireEvent.click(analyzeBtn);

    await waitFor(() => {
      expect(api.analyzeGame).toHaveBeenCalledWith('game-123');
    });

    // 3. Verify qualifying mistake move (ply 12) is displayed
    await screen.findByText(/Qualifying Mistake Moves/i);
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);

    // 4. Click "Practice this mistake"
    const practiceBtn = screen.getByRole('button', { name: /Practice this mistake/i });
    fireEvent.click(practiceBtn);

    // 5. Verify transition to practice puzzle shell
    expect(activeTabStore.getSnapshot()).toBe('puzzles');

    // Verify pre-move position is loaded on board
    const board = await screen.findByTestId('mock-board');
    expect(board).toHaveAttribute('data-position', preMoveFen);

    // Verify back navigation button indicates return to Game Analysis
    const backBtn = screen.getByRole('button', { name: /Back to Game Analysis/i });
    expect(backBtn).toBeInTheDocument();

    // 6. Click Back to Game Analysis and verify return context is restored
    fireEvent.click(backBtn);

    expect(activeTabStore.getSnapshot()).toBe('game-analysis');
    expect(await screen.findByText(/Qualifying Mistake Moves/i)).toBeInTheDocument();
  });
});
