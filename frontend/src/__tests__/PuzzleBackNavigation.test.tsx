import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeTabStore } from '../utils/browserStores';

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playSound: vi.fn(),
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options?: {
      position: string;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div data-testid="mock-chessboard" data-position={options?.position}>
      {options?.onPieceDrop && (
        <button
          data-testid="play-nc6-drop"
          onClick={() => options?.onPieceDrop?.({ sourceSquare: 'b8', targetSquare: 'c6' })}
        >
          Play Nc6
        </button>
      )}
    </div>
  ),
}));

const mockWeakness: api.WeaknessResponse = {
  positionId: 'pos-nav-1',
  playerColor: 'BLACK',
  fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
  timesReached: 5,
  mistakeCount: 3,
  mistakeRate: 60.0,
  averageLoss: 0.9,
  priority: 8.5,
  bestMove: 'Nc6',
  acceptableMoves: [],
  movesPlayed: [{ move: 'd4', timesPlayed: 3, averageLoss: 0.9 }],
  gameUrls: [],
  evalCp: 35,
};

describe('Task 3 — Puzzle Back Navigation Regression Test', () => {
  beforeEach(() => {
    localStorage.clear();
    activeTabStore.invalidate();
    vi.restoreAllMocks();
  });

  it('verifies selecting practice position pushes #puzzles to history and browser back returns to weaknesses', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([mockWeakness]);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);
    vi.spyOn(api, 'evaluateMove').mockResolvedValue({
      fen: mockWeakness.fen,
      move: 'Nc6',
      bestMove: 'Nc6',
      bestEvalCp: 35,
      evalCp: 35,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });
    vi.spyOn(api, 'fetchPuzzleContinuation').mockResolvedValue({
      fen: mockWeakness.fen,
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [],
    });

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#import';
    activeTabStore.set('import');

    await act(async () => {
      render(<Home />);
    });

    // 1. Navigate from Import tab to Exact-Position Analysis via Explore / Tools dropdown
    const toolsDropdownBtn = screen.getByRole('button', { name: /^Explore \/ Tools$/i });
    await act(async () => {
      fireEvent.click(toolsDropdownBtn);
    });

    const analysisItem = screen.getByRole('menuitem', { name: /^Exact-Position Analysis$/i });
    await act(async () => {
      fireEvent.click(analysisItem);
    });

    expect(window.location.hash).toBe('#weakness-analysis');

    // Wait for weakness card to render
    await waitFor(() => {
      expect(screen.getByText('Exact-Position Analysis')).toBeInTheDocument();
    });

    // 2. Click Practice Position on weakness card
    const practiceBtn = await screen.findByRole('button', { name: /Practice Position/i });
    await act(async () => {
      fireEvent.click(practiceBtn);
    });

    // Verify hash was updated to #puzzles by changeTab('puzzles')
    expect(window.location.hash).toBe('#puzzles');
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();

    // 3. Simulate browser Back button (popstate / hashchange to #weakness-analysis)
    await act(async () => {
      window.location.hash = '#weakness-analysis';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
      window.dispatchEvent(new PopStateEvent('popstate'));
    });

    // Verify app navigated back to Exact-Position Analysis tab
    await waitFor(() => {
      expect(screen.getByText('Exact-Position Analysis')).toBeInTheDocument();
    });
  });

  it('preserves practice puzzle context when exiting exploration rather than ejecting to weaknesses', async () => {
    vi.spyOn(api, 'fetchWeaknesses').mockResolvedValue([mockWeakness]);
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);
    vi.spyOn(api, 'evaluateMove').mockResolvedValue({
      fen: mockWeakness.fen,
      move: 'Nc6',
      bestMove: 'Nc6',
      bestEvalCp: 35,
      evalCp: 35,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });
    vi.spyOn(api, 'fetchPuzzleContinuation').mockResolvedValue({
      fen: mockWeakness.fen,
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [],
    });

    localStorage.setItem('chessecho_username', 'testuser');
    window.location.hash = '#weakness-analysis';
    activeTabStore.set('weakness-analysis');

    await act(async () => {
      render(<Home />);
    });

    // 1. Wait for weakness card to render
    await waitFor(() => {
      expect(screen.getByText('Exact-Position Analysis')).toBeInTheDocument();
    });

    // 2. Click Practice Position on weakness card
    const practiceBtn = await screen.findByRole('button', { name: /Practice Position/i });
    await act(async () => {
      fireEvent.click(practiceBtn);
    });

    expect(window.location.hash).toBe('#puzzles');
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();

    // 3. User plays correct move (Nc6) to solve puzzle and enters Line Exploration
    const playBtn = await screen.findByTestId('play-nc6-drop');
    await act(async () => {
      fireEvent.click(playBtn);
    });

    const continueExplorationBtn = await screen.findByRole('button', { name: /Continue Exploration →/i });
    await act(async () => {
      fireEvent.click(continueExplorationBtn);
    });

    // Verify exploration bar is visible with Exit Exploration button
    const exitExplorationBtn = await screen.findByTitle(/Exit Exploration/i);
    expect(exitExplorationBtn).toBeInTheDocument();

    // 4. Click Exit Exploration
    await act(async () => {
      fireEvent.click(exitExplorationBtn);
    });

    // 5. User should STILL be in practice (#puzzles), and target opening weakness still visible
    expect(window.location.hash).toBe('#puzzles');
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Back to Weaknesses/i })).toBeInTheDocument();

    // 6. Only when clicking explicit "Back to Weaknesses" do they return to #weakness-analysis
    const backBtn = screen.getByRole('button', { name: /Back to Weaknesses/i });
    await act(async () => {
      fireEvent.click(backBtn);
    });

    await waitFor(() => {
      expect(window.location.hash).toBe('#weakness-analysis');
      expect(screen.getByText('Exact-Position Analysis')).toBeInTheDocument();
    });
  });

  it('verifies that practicing an individual cluster member position returns to weaknesses with cluster expanded', async () => {
    const mockCluster: api.RecurringWeaknessResponse = {
      clusterId: 'cluster-ret-1',
      clusterType: 'STRUCTURAL',
      recommendationPriority: 0.5,
      representativeFen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      memberPositionCount: 1,
      totalOccurrences: 5,
      distinctGamesCount: 4,
      mistakeCount: 3,
      mistakeFrequency: 0.6,
      blunderRate: 0.2,
      evaluatedOccurrences: 5,
      unevaluatedOccurrences: 0,
      evalLossDistribution: { MINOR: 3, MODERATE: 2, SIGNIFICANT: 0, SEVERE: 0, UNEVALUATED: 0 },
      evalLossPercentages: { MINOR: 60, MODERATE: 40, SIGNIFICANT: 0, SEVERE: 0, UNEVALUATED: 0 },
      timingProfile: { FAST: 1, NORMAL: 3, SLOW: 1 },
      formatBreakdown: { RAPID: 5 },
    };

    const mockMemberPos: api.ClusterMemberPosition = {
      positionId: 'pos-cluster-mem-1',
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      playerColor: 'BLACK',
      movePlayed: 'd4',
      targetMove: 'Nc6',
      acceptableMoves: [{ move: 'Nc6', evalLoss: 0.0 }],
      evalLoss: 0.85,
      mistakeSeverity: 'MINOR',
      decisionTimeMs: 2500,
      decisionSeverity: 'NO_CONCERN',
      timeControl: 'RAPID',
      gameId: 'game-c1',
      platformGameId: 'chesscom_c1',
      plyNumber: 4,
      moveNumber: 2,
    };

    vi.spyOn(api, 'fetchCurrentSession').mockResolvedValue({
      status: 'authenticated',
      userId: 'user-1',
    });
    vi.spyOn(api, 'fetchAccounts').mockResolvedValue([{
      id: 'test-account-id',
      platform: 'CHESS_COM',
      username: 'testuser',
    }]);
    vi.spyOn(api, 'fetchRecurringWeaknesses').mockResolvedValue([mockCluster]);
    vi.spyOn(api, 'getClusterPositions').mockResolvedValue({
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      positions: [mockMemberPos],
    });
    vi.spyOn(api, 'fetchPuzzles').mockResolvedValue([]);

    localStorage.setItem('chessecho_username', 'testuser');
    localStorage.setItem('chessecho_session_user', 'user-1');
    window.location.hash = '#weaknesses';
    activeTabStore.set('weaknesses');

    await act(async () => {
      render(<Home />);
    });

    // 1. In Weakness Library, click Structural Weakness Clusters
    const structuralBtn = await screen.findByRole('button', { name: /Structural Weakness Clusters/i });
    await act(async () => {
      fireEvent.click(structuralBtn);
    });

    // 2. Click Inspect Evidence on the cluster card
    const inspectBtn = await screen.findByRole('button', { name: /Inspect Evidence/i });
    await act(async () => {
      fireEvent.click(inspectBtn);
    });

    // 3. Evidence modal opens; click "Practice Position" on member card
    const modal = await screen.findByRole('dialog');
    expect(modal).toBeInTheDocument();
    const practiceMemberBtn = await screen.findByRole('button', { name: /Practice Position/i });
    await act(async () => {
      fireEvent.click(practiceMemberBtn);
    });

    // 4. Navigates to practice
    expect(window.location.hash).toBe('#puzzles');
    expect(screen.getByText('Target Opening Weakness')).toBeInTheDocument();

    // 5. Click "Back to Weaknesses"
    const backBtn = await screen.findByRole('button', { name: /Back to Weaknesses/i });
    await act(async () => {
      fireEvent.click(backBtn);
    });

    // 6. Restores #weaknesses and re-expands cluster evidence modal
    await waitFor(() => {
      expect(window.location.hash).toBe('#weaknesses');
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: /Cluster Evidence Inspection/i })).toBeInTheDocument();
    });
  });
});
