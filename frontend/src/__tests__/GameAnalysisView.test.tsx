import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import GameAnalysisView from '../components/GameAnalysisView';
import * as api from '../services/api';
import { activeAccountStore } from '../utils/browserStores';

vi.mock('../services/api', () => ({
  fetchGames: vi.fn(),
  analyzeGame: vi.fn(),
  fetchSimilarPositions: vi.fn(),
}));

vi.mock('../components/ChessBoardView', () => ({
  ChessBoardView: ({ fen }: { fen: string }) => (
    <div data-testid="mock-chessboard-view" data-fen={fen}>
      Board preview
    </div>
  ),
}));

describe('GameAnalysisView', () => {
  const mockGames: api.GamePageResponse = {
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
      {
        id: 'game-456',
        platformGameId: 'chess-com-2',
        timeControl: 'rapid',
        playedAt: '2026-11-10T14:00:00Z',
        result: 'loss',
        whiteUsername: 'RivalPlayer',
        blackUsername: 'HeroPlayer',
        pgn: '1. d4 d5',
      },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 20,
  };

  const mockAnalysis: api.GameAnalysisResponse = {
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
        decisionTimeMs: 3000,
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
        preMoveFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
        decisionTimeMs: 25000,
        decisionSeverity: 'SIGNIFICANT',
        bestMove: 'Bc5',
        bestEvalCp: 35,
        evalCp: -120,
        evalLoss: 1.55,
        weaknessSeverity: 'SIGNIFICANT',
      },
      {
        plyNumber: 20,
        movePlayed: 'f3??',
        playerColor: 'WHITE',
        preMoveFen: 'r1bqkb1r/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
        decisionTimeMs: 2000,
        decisionSeverity: 'NO_CONCERN',
        bestMove: 'd4',
        bestEvalCp: 40,
        evalCp: -200,
        evalLoss: 2.4,
        weaknessSeverity: 'CRITICAL',
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
    activeAccountStore.set(undefined);
    vi.mocked(api.fetchGames).mockResolvedValue(mockGames);
    vi.mocked(api.analyzeGame).mockResolvedValue(mockAnalysis);
    vi.mocked(api.fetchSimilarPositions).mockResolvedValue({
      queryFen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
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
  });

  it('renders My Games list with board previews and allows selecting a game to analyze', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    render(<GameAnalysisView sessionStatus="authenticated" />);

    expect((await screen.findAllByText('HeroPlayer'))[0]).toBeInTheDocument();
    expect((await screen.findAllByText('RivalPlayer'))[0]).toBeInTheDocument();
    expect(screen.getAllByTestId('mock-chessboard-view').length).toBeGreaterThan(0);

    const analyzeBtns = screen.getAllByRole('button', { name: /Analyze/i });
    fireEvent.click(analyzeBtns[0]);

    await waitFor(() => {
      expect(api.analyzeGame).toHaveBeenCalledWith('game-123');
    });

    expect(await screen.findByText(/Game Analysis/i)).toBeInTheDocument();
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
  });

  it('provides time-control filter chips and filters games immediately in client state', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    render(<GameAnalysisView sessionStatus="authenticated" />);

    expect((await screen.findAllByText('HeroPlayer'))[0]).toBeInTheDocument();
    expect(api.fetchGames).toHaveBeenCalledTimes(1);

    // Verify filter chips are present
    expect(screen.getByRole('button', { name: /^All$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Bullet$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Blitz$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Rapid$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Classical$/i })).toBeInTheDocument();

    // Both blitz and rapid games initially shown
    expect(screen.getByText('BLITZ')).toBeInTheDocument();
    expect(screen.getByText('RAPID')).toBeInTheDocument();

    // Click Blitz filter
    fireEvent.click(screen.getByRole('button', { name: /^Blitz$/i }));

    // Only Blitz game shown, Rapid filtered out
    expect(screen.getByText('BLITZ')).toBeInTheDocument();
    expect(screen.queryByText('RAPID')).not.toBeInTheDocument();
    // No extra fetchGames called
    expect(api.fetchGames).toHaveBeenCalledTimes(1);

    // Click Rapid filter
    fireEvent.click(screen.getByRole('button', { name: /^Rapid$/i }));

    expect(screen.getByText('RAPID')).toBeInTheDocument();
    expect(screen.queryByText('BLITZ')).not.toBeInTheDocument();
    expect(api.fetchGames).toHaveBeenCalledTimes(1);
  });

  it('renders actionable Import CTA in empty state and navigates with matching preset', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    vi.mocked(api.fetchGames).mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 20,
    });
    const onNavigateImport = vi.fn();

    render(<GameAnalysisView sessionStatus="authenticated" onNavigateImport={onNavigateImport} />);

    expect(await screen.findByText(/No games found/i)).toBeInTheDocument();
    const importBtn = screen.getByRole('button', { name: /Import Games/i });
    expect(importBtn).toBeInTheDocument();

    fireEvent.click(importBtn);

    expect(onNavigateImport).toHaveBeenCalledWith([
      'BULLET',
      'BLITZ',
      'RAPID',
      'CLASSICAL',
    ]);
  });

  it('renders filter-specific empty state with clear filter action', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    render(<GameAnalysisView sessionStatus="authenticated" />);

    expect((await screen.findAllByText('HeroPlayer'))[0]).toBeInTheDocument();

    // Click Bullet filter (no bullet games in mock)
    fireEvent.click(screen.getByRole('button', { name: /^Bullet$/i }));

    expect(screen.getByText(/No Bullet games found/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Import Bullet Games/i })).toBeInTheDocument();

    // Click Clear filter
    const clearBtn = screen.getByRole('button', { name: /Clear filter/i });
    fireEvent.click(clearBtn);

    // Filter reset to All
    expect(screen.getByText('BLITZ')).toBeInTheDocument();
    expect(screen.getByText('RAPID')).toBeInTheDocument();
  });

  it('invokes onNavigateImport with context-aware preset from header Import Games button', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    const onNavigateImport = vi.fn();
    render(<GameAnalysisView sessionStatus="authenticated" onNavigateImport={onNavigateImport} />);

    expect((await screen.findAllByText('HeroPlayer'))[0]).toBeInTheDocument();

    // Select Rapid filter
    fireEvent.click(screen.getByRole('button', { name: /^Rapid$/i }));

    // Click header Import Games button
    const headerImportBtn = screen.getByRole('button', { name: /Import Games/i });
    fireEvent.click(headerImportBtn);

    expect(onNavigateImport).toHaveBeenCalledWith(['RAPID']);
  });

  it('operates eval loss threshold and timing filters independently without mutual reset', async () => {
    render(<GameAnalysisView initialGameId="game-123" />);

    // Plies 12 (loss 1.55, SIGNIFICANT) and 20 (loss 2.40, NO_CONCERN) qualify by default threshold (0.8)
    await screen.findByText(/Qualifying Mistake Moves/i);
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/f3\?\?/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/^e4$/i)).not.toBeInTheDocument();

    // Select 'Significant' timing filter
    const significantBtn = screen.getByRole('button', { name: /^Significant$/i });
    fireEvent.click(significantBtn);

    // Only ply 12 should be displayed
    expect(screen.getAllByText(/Nf6/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/f3\?\?/i)).not.toBeInTheDocument();

    // Adjust threshold slider to 2.0 (ply 12 has 1.55, so it should now filter out)
    const slider = screen.getByLabelText(/Eval Loss Threshold/i);
    fireEvent.change(slider, { target: { value: '2.0' } });

    // Both should be filtered out
    expect(screen.queryByText(/Nf6/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/f3\?\?/i)).not.toBeInTheDocument();

    // Reset timing to All, ply 20 (loss 2.4) should show up while threshold 2.0 remains
    const allTimingBtn = screen.getByRole('button', { name: /^All$/i });
    fireEvent.click(allTimingBtn);

    expect(screen.getAllByText(/f3\?\?/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/Nf6/i)).not.toBeInTheDocument();
  });

  it('queries structural and dynamic historical similarity using pre-move FEN', async () => {
    render(<GameAnalysisView initialGameId="game-123" />);

    await screen.findByText(/Qualifying Mistake Moves/i);
    const ply12Item = screen.getAllByText(/Nf6/i)[0];
    fireEvent.click(ply12Item);

    const structuralBtn = screen.getByRole('button', { name: /Search Structural Similarity/i });
    fireEvent.click(structuralBtn);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
          mode: 'STRUCTURAL',
        })
      );
    });

    const dynamicBtn = screen.getByRole('button', { name: /Search Dynamic Similarity/i });
    fireEvent.click(dynamicBtn);

    await waitFor(() => {
      expect(api.fetchSimilarPositions).toHaveBeenCalledWith(
        expect.objectContaining({
          fen: 'r1bqkb1r/pppp1ppp/2n2n2/4p3/4P3/2N2N2/PPPP1PPP/R1BQKB1R w KQkq - 4 4',
          mode: 'DYNAMIC',
        })
      );
    });
  });

  it('triggers onPracticeMistake callback when Practice this mistake button is clicked', async () => {
    const onPracticeMistake = vi.fn();
    render(<GameAnalysisView initialGameId="game-123" onPracticeMistake={onPracticeMistake} />);

    await screen.findByText(/Qualifying Mistake Moves/i);
    const ply12Item = screen.getAllByText(/Nf6/i)[0];
    fireEvent.click(ply12Item);

    const practiceBtn = screen.getByRole('button', { name: /Practice this mistake/i });
    fireEvent.click(practiceBtn);

    expect(onPracticeMistake).toHaveBeenCalledTimes(1);
    expect(onPracticeMistake).toHaveBeenCalledWith(
      expect.objectContaining({ plyNumber: 12, movePlayed: 'Nf6' }),
      expect.objectContaining({ gameId: 'game-123' })
    );
  });

  it('subscribes to activeAccountStore and passes accountId to fetchGames', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-789', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });

    render(<GameAnalysisView />);

    await waitFor(() => {
      expect(api.fetchGames).toHaveBeenCalledWith(
        expect.objectContaining({
          page: 0,
          size: 50,
          accountId: 'acc-789',
        })
      );
    });
  });

  it('re-executes fetchGames when activeAccountId updates in activeAccountStore', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-1', username: 'PlayerOne', platform: 'CHESS_COM' });
    });

    render(<GameAnalysisView />);

    await waitFor(() => {
      expect(api.fetchGames).toHaveBeenCalledWith(
        expect.objectContaining({ accountId: 'acc-1' })
      );
    });

    act(() => {
      activeAccountStore.set({ id: 'acc-2', username: 'PlayerTwo', platform: 'CHESS_COM' });
    });

    await waitFor(() => {
      expect(api.fetchGames).toHaveBeenCalledWith(
        expect.objectContaining({ accountId: 'acc-2' })
      );
    });
  });

  it('does not dispatch fetchGames while account context is unhydrated or missing activeAccountId', async () => {
    act(() => {
      activeAccountStore.set(undefined);
    });
    render(<GameAnalysisView sessionStatus="authenticated" />);

    await new Promise((r) => setTimeout(r, 50));
    expect(api.fetchGames).not.toHaveBeenCalled();
    expect(screen.getByText(/No games found/i)).toBeInTheDocument();
  });

  it('does not dispatch fetchGames while sessionStatus is loading', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    render(<GameAnalysisView sessionStatus="loading" />);

    await new Promise((r) => setTimeout(r, 50));
    expect(api.fetchGames).not.toHaveBeenCalled();
  });

  it('prevents guest sessions from issuing game-list requests', async () => {
    act(() => {
      activeAccountStore.set(undefined);
    });
    render(<GameAnalysisView sessionStatus="unauthenticated" />);

    await new Promise((r) => setTimeout(r, 50));
    expect(api.fetchGames).not.toHaveBeenCalled();
    expect(screen.getByText(/No games found/i)).toBeInTheDocument();
  });

  it('dispatches fetchGames with accountId once hydrated and renders games', async () => {
    act(() => {
      activeAccountStore.set({ id: 'acc-123', username: 'HeroPlayer', platform: 'CHESS_COM' });
    });
    render(<GameAnalysisView sessionStatus="authenticated" />);

    await waitFor(() => {
      expect(api.fetchGames).toHaveBeenCalledWith(
        expect.objectContaining({ page: 0, size: 50, accountId: 'acc-123' })
      );
    });
    expect((await screen.findAllByText('HeroPlayer'))[0]).toBeInTheDocument();
  });

  it('discards stale in-flight responses when switching accounts and renders only active account games', async () => {
    let resolveAcc1!: (value: api.GamePageResponse) => void;
    const acc1Promise = new Promise<api.GamePageResponse>((res) => {
      resolveAcc1 = res;
    });

    const acc2Games: api.GamePageResponse = {
      ...mockGames,
      content: [
        {
          ...mockGames.content[0],
          id: 'game-acc2',
          whiteUsername: 'AccountTwoHero',
        },
      ],
    };

    vi.mocked(api.fetchGames).mockImplementation((params) => {
      if (params?.accountId === 'acc-1') return acc1Promise;
      if (params?.accountId === 'acc-2') return Promise.resolve(acc2Games);
      return Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
    });

    act(() => {
      activeAccountStore.set({ id: 'acc-1', username: 'User1', platform: 'CHESS_COM' });
    });

    render(<GameAnalysisView sessionStatus="authenticated" />);

    await waitFor(() => {
      expect(api.fetchGames).toHaveBeenCalledWith(expect.objectContaining({ accountId: 'acc-1' }));
    });

    act(() => {
      activeAccountStore.set({ id: 'acc-2', username: 'User2', platform: 'CHESS_COM' });
    });

    expect(await screen.findByText('AccountTwoHero')).toBeInTheDocument();

    act(() => {
      resolveAcc1({
        ...mockGames,
        content: [
          {
            ...mockGames.content[0],
            id: 'game-acc1',
            whiteUsername: 'StaleAccountOneHero',
          },
        ],
      });
    });

    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText('StaleAccountOneHero')).not.toBeInTheDocument();
    expect(screen.getByText('AccountTwoHero')).toBeInTheDocument();
  });

  it('displays account connection modal when unauthenticated session mounts with initialGameId', async () => {
    render(<GameAnalysisView initialGameId="game-123" sessionStatus="unauthenticated" />);

    expect(api.analyzeGame).not.toHaveBeenCalled();
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Connect Account' })).toBeInTheDocument();
  });

  it('closes account connection modal when dismissed', async () => {
    render(<GameAnalysisView initialGameId="game-123" sessionStatus="unauthenticated" />);

    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    const dismissBtn = screen.getByRole('button', { name: /Dismiss/i });
    fireEvent.click(dismissBtn);

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });
});
