import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService } from '../services/continuationService';
import { activeTabStore, activeUsernameStore, puzzleSettingsStore } from '../utils/browserStores';
import type { Puzzle } from '../mock/mockData';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { position: string } }) => (
    <div data-testid="mock-chessboard" data-position={options.position} />
  ),
}));

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  },
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchWeaknesses: vi.fn(),
    fetchPuzzles: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

describe('Issue 72 — explore a weakness decision', () => {
  const sourceFen = 'rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2';
  const resultingFen = 'rnbqkbnr/pppp1ppp/8/4P3/8/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2';
  const newestCandidateFen = 'r1bqkbnr/pppp1ppp/2n5/4P3/8/8/PPP1PPPP/RNBQKBNR w KQkq - 1 3';

  const weakness: api.WeaknessResponse = {
    positionId: 'decision-weakness',
    fen: sourceFen,
    timesReached: 5,
    mistakeCount: 3,
    mistakeRate: 60,
    averageLoss: 2,
    priority: 10,
    bestMove: 'e4',
    acceptableMoves: [],
    movesPlayed: [
      { move: 'dxe5', timesPlayed: 3, averageLoss: 2, resultingFen },
      { move: 'Qh5', timesPlayed: 1, averageLoss: 1.1, resultingFen: null },
    ],
    gameUrls: [],
    evalCp: 50,
  };

  const replacementPuzzle: Puzzle = {
    puzzleId: 'replacement',
    openingTitle: 'Replacement opening',
    fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    playerColor: 'WHITE',
    targetMove: 'e4',
    acceptableMoves: [],
    movesPlayed: [],
    priority: 1,
    timesReached: 4,
    mistakeCount: 1,
    mistakeRate: 25,
    evalCp: 0,
  };

  const deferred = <T,>() => {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  };

  const decisionEvidenceIsVisible = () => {
    expect(screen.getByText('dxe5')).toBeInTheDocument();
    expect(screen.getByText(/3 games/i)).toBeInTheDocument();
    expect(screen.getByText(/2\.00 pawns/i)).toBeInTheDocument();
  };

  const enterDecision = async () => {
    const button = await screen.findByRole('button', { name: /Explore this decision/i });
    fireEvent.click(button);
    await waitFor(() => expect(window.location.hash).toBe('#puzzles'));
    expect(screen.getByText(/Choose how you want to explore/i)).toBeInTheDocument();
    expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', resultingFen);
    decisionEvidenceIsVisible();
  };

  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    localStorage.clear();
    window.location.hash = '#weaknesses';
    localStorage.setItem('chessecho_username', 'testuser');
    activeTabStore.invalidate();
    activeUsernameStore.invalidate();
    puzzleSettingsStore.invalidate();
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([weakness]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([replacementPuzzle]);
  });

  it('preserves the selected decision, starts after dxe5, and applies only the newest same-FEN continuation', async () => {
    const puzzleLoad = deferred<Puzzle[]>();
    const engine = deferred<api.ContinuationResponse | null>();
    const humanDefault = deferred<api.ContinuationResponse | null>();
    const humanNewBand = deferred<api.ContinuationResponse | null>();
    vi.mocked(api.fetchPuzzles).mockReturnValue(puzzleLoad.promise);
    vi.mocked(api.fetchPuzzleContinuation)
      .mockReturnValueOnce(engine.promise)
      .mockReturnValueOnce(humanDefault.promise)
      .mockReturnValueOnce(humanNewBand.promise);

    render(<Home />);
    await enterDecision();

    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    await waitFor(() => expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'HUMAN' }));
    await waitFor(() => expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(2));
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '1600-1800' } });
    await waitFor(() => expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(3));

    expect(vi.mocked(api.fetchPuzzleContinuation).mock.calls).toEqual([
      [resultingFen, 'ENGINE', '1200-1400'],
      [resultingFen, 'HUMAN', '1200-1400'],
      [resultingFen, 'HUMAN', '1600-1800'],
    ]);

    const newestResponse: api.ContinuationResponse = {
      fen: resultingFen,
      requestedMode: 'HUMAN',
      effectiveProvider: 'HUMAN',
      candidates: [{
        move: 'Nc6',
        resultingFen: newestCandidateFen,
        providerType: 'HUMAN',
        timesPlayed: 12,
      }],
    };
    await act(async () => {
      humanNewBand.resolve(newestResponse);
    });
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', newestCandidateFen);
    });
    decisionEvidenceIsVisible();

    await act(async () => {
      engine.resolve({
        fen: resultingFen,
        requestedMode: 'ENGINE',
        effectiveProvider: 'ENGINE',
        candidates: [{
          move: 'Nf6',
          resultingFen: `${resultingFen} stale-engine`,
          providerType: 'ENGINE',
        }],
      });
      humanDefault.reject(new Error('stale default-band request'));
    });
    expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', newestCandidateFen);
    decisionEvidenceIsVisible();

    await act(async () => {
      puzzleLoad.resolve([replacementPuzzle]);
    });
    expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', newestCandidateFen);
    decisionEvidenceIsVisible();
  });

  it('Reset and Exit restore the source position, clear evidence, and return to IDLE', async () => {
    render(<Home />);
    await enterDecision();

    fireEvent.click(screen.getByTitle('Reset Position'));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', sourceFen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
      expect(screen.queryByText('Line Exploration')).not.toBeInTheDocument();
      expect(screen.getByText(/Find White's best move/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    await enterDecision();
    fireEvent.click(screen.getByTitle('Exit Exploration'));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', sourceFen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
      expect(screen.queryByText('Line Exploration')).not.toBeInTheDocument();
      expect(screen.getByText(/Find White's best move/i)).toBeInTheDocument();
    });
  });

  it('clears decision context for Practice Position, navigation, settings replacement, and disconnect', async () => {
    render(<Home />);
    await enterDecision();

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    fireEvent.click(await screen.findByRole('button', { name: /Practice Position/i }));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', sourceFen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    await enterDecision();
    fireEvent.click(screen.getByTitle('Next Puzzle'));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', replacementPuzzle.fen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    await enterDecision();
    fireEvent.click(screen.getByTitle('Previous Puzzle'));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', replacementPuzzle.fen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    await enterDecision();
    fireEvent.click(screen.getByRole('button', { name: /Puzzle Settings/i }));
    fireEvent.click(screen.getByRole('button', { name: /^Apply$/i }));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', replacementPuzzle.fen);
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    await enterDecision();
    fireEvent.click(screen.getByRole('button', { name: /Disconnect/i }));
    await waitFor(() => {
      expect(screen.queryByText('dxe5')).not.toBeInTheDocument();
      expect(screen.queryByText('Line Exploration')).not.toBeInTheDocument();
      expect(screen.queryByTestId('mock-chessboard')).not.toBeInTheDocument();
    });
  });
});
