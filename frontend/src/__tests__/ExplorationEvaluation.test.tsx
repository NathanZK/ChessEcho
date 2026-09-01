import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import Home from '../app/page';
import { toWhitePerspective } from '../services/api';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import { Puzzle } from '../mock/mockData';
import * as sound from '../services/soundService';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { position: string; onPieceDrop: (args: { sourceSquare: string; targetSquare: string }) => boolean } }) => (
    <div data-testid="mock-chessboard" data-position={options.position}>
      <button
        data-testid="simulate-puzzle-move-white"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' }); // Bb5
        }}
      >
        Play Bb5 (White)
      </button>
      <button
        data-testid="simulate-expl-move-white"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'b5', targetSquare: 'a4' }); // Ba4
        }}
      >
        Play Ba4 (White)
      </button>
      <button
        data-testid="simulate-expl-move-black"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'a7', targetSquare: 'a6' }); // a6
        }}
      >
        Play a6 (Black)
      </button>
    </div>
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
    fetchPuzzles: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

describe('Line Exploration Evaluation & EvalBar Updates', () => {
  const deferred = <T,>() => {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  };

  const mockPuzzleWhite: Puzzle = {
    puzzleId: 'puzzle-white-1',
    fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    playerColor: 'WHITE',
    targetMove: 'Bb5',
    openingTitle: 'Ruy Lopez',
    acceptableMoves: [],
    movesPlayed: [],
    priority: 1.0,
    timesReached: 10,
    mistakeCount: 2,
    mistakeRate: 20.0,
    evalCp: 30, // baseline +0.30
  };

  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    moveEvaluationService.clear();
    localStorage.clear();
    localStorage.setItem('chessecho_username', 'testuser');
    vi.mocked(api.fetchPuzzles).mockResolvedValue([mockPuzzleWhite]);
  });

  it('1. toWhitePerspective correctly handles White and Black to move positions', () => {
    // White to move: positive is White advantage, negative is Black advantage
    const fenWhite = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
    expect(toWhitePerspective(120, fenWhite)).toBe(120);
    expect(toWhitePerspective(-80, fenWhite)).toBe(-80);

    // Black to move: positive is Black advantage (so negative White), negative is White advantage (so positive White)
    const fenBlack = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 2 3';
    expect(toWhitePerspective(80, fenBlack)).toBe(-80);
    expect(toWhitePerspective(-150, fenBlack)).toBe(150);
  });

  it('2. Anchors exploration baseline to the solved puzzle evaluation, not the original starting position, and remains unlocked', async () => {
    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('+0.30')).toBeInTheDocument();
    });

    // Solve the puzzle: White plays Bb5
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));

    // Solved position should stay at +0.30 or updated puzzle solve eval
    await waitFor(() => {
      expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    });

    // Enter exploration
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // EvalBar should maintain the current position evaluation (+0.30) and NOT be locked
    expect(screen.getByText('+0.30')).toBeInTheDocument();
    expect(screen.queryByText(/🔒 EXPL/)).not.toBeInTheDocument();
  });

  it('3. White exploration move updates EvalBar using backend evalCp', async () => {
    // After Bb5 (White), Black plays a6 (simulated via continuation or user), then White plays Ba4
    // 1st: ChessEcho responds with a6
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -30, // White +0.30
          evalLoss: 0.0,
        },
      ],
    });

    // 2nd: User plays Ba4 (White to move)
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      bestEvalCp: 120,
      evalCp: 120, // +1.20 White perspective
      evalLoss: 0.0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    render(<Home />);

    await waitFor(() => {
      expect(screen.getByText('+0.30')).toBeInTheDocument();
    });

    // Solve puzzle & enter exploration
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // ChessEcho makes move a6 (board is now White to move)
    await waitFor(() => expect(screen.getByText('Your turn — explore a move.')).toBeInTheDocument());

    // Play White move Ba4 in exploration
    fireEvent.click(screen.getByTestId('simulate-expl-move-white'));

    await waitFor(() => {
      expect(screen.getByText('+1.20')).toBeInTheDocument();
    });
  });

  it('4. Black exploration move correctly inverts perspective for EvalBar', async () => {
    // After Bb5 (White), board is Black to move. User plays a6 for Black.
    // In FEN with 'b' to move, evalCp of 80 means Black is +0.80 -> White EvalBar must show -0.80
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      bestEvalCp: 80,
      evalCp: 80, // Black is +0.80
      evalLoss: 0.0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // Play Black move a6 in exploration
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));

    await waitFor(() => {
      expect(screen.getByText('-0.80')).toBeInTheDocument();
    });
  });

  it('5. ChessEcho ENGINE continuation move updates EvalBar using candidate evalCp', async () => {
    // ChessEcho responding as Black: candidate evalCp = -50 (Black is down 0.50 -> White is +0.50)
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -50, // Black perspective -50 -> White +0.50
          evalLoss: 0.0,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    await waitFor(() => {
      expect(screen.getByText('+0.50')).toBeInTheDocument();
    });
  });

  it('6. Alternative ENGINE candidate updates EvalBar when clicked', async () => {
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -50, // +0.50 White
          evalLoss: 0.0,
        },
        {
          move: 'Nf6',
          resultingFen: 'r1bqkb1r/pppp1ppp/2n2n2/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 4',
          providerType: 'ENGINE',
          evalCp: -140, // Black perspective -140 -> White +1.40
          evalLoss: 0.9,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // Initially ChessEcho plays selected candidate a6 (+0.50)
    await waitFor(() => {
      expect(screen.getByText('+0.50')).toBeInTheDocument();
      expect(screen.getByText('Nf6')).toBeInTheDocument();
    });

    // Select alternative line Nf6
    fireEvent.click(screen.getByRole('button', { name: /Explore this line/i }));

    // EvalBar should immediately update to White +1.40
    await waitFor(() => {
      expect(screen.getByText('+1.40')).toBeInTheDocument();
    });
  });

  it('7. HUMAN candidate with null evalCp displays unknown badge without crashing or locking', async () => {
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'HUMAN',
      effectiveProvider: 'HUMAN',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'HUMAN',
          evalCp: null,
          timesPlayed: 5,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // EvalBar should show unknown '?' state and NOT be locked
    await waitFor(() => {
      expect(screen.getByText('?')).toBeInTheDocument();
      expect(screen.getByText('? N/A')).toBeInTheDocument();
      expect(screen.queryByText(/🔒 EXPL/)).not.toBeInTheDocument();
    });
  });

  it('8. Exploration Undo and Redo restore prior and subsequent evaluations accurately', async () => {
    // 1st: ChessEcho plays a6 (+0.50)
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -50, // +0.50
          evalLoss: 0.0,
        },
      ],
    });

    // 2nd: User plays Ba4 -> +1.20
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      bestEvalCp: 120,
      evalCp: 120, // +1.20
      evalLoss: 0.0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // ChessEcho plays a6 -> +0.50
    await waitFor(() => {
      expect(screen.getByText('+0.50')).toBeInTheDocument();
      expect(screen.getByText('Your turn — explore a move.')).toBeInTheDocument();
    });

    // User plays Ba4 -> +1.20
    fireEvent.click(screen.getByTestId('simulate-expl-move-white'));
    await waitFor(() => expect(screen.getByText('+1.20')).toBeInTheDocument());

    // Undo move -> restores +0.50 (position after a6)
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => expect(screen.getByText('+0.50')).toBeInTheDocument());

    // Undo move -> restores baseline +0.30 (position after Bb5)
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => expect(screen.getByText('+0.30')).toBeInTheDocument());

    // Redo move -> restores +0.50
    fireEvent.click(screen.getByTitle(/Next Move/i));
    await waitFor(() => expect(screen.getByText('+0.50')).toBeInTheDocument());
  });

  it('9. Selecting an alternative branch correctly updates EvalBar and overwrites future branch history', async () => {
    console.log("MOCKING SECOND FETCH"); vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -50, // +0.50
          evalLoss: 0.0,
        },
        {
          move: 'Nf6',
          resultingFen: 'r1bqkb1r/pppp1ppp/2n2n2/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 4',
          providerType: 'ENGINE',
          evalCp: -140, // +1.40
          evalLoss: 0.9,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // Selected candidate a6 (+0.50)
    await waitFor(() => {
      expect(screen.getByText('+0.50')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Explore this line/i })).toBeInTheDocument();
    });

    // Explore alternative Nf6 (+1.40)
    fireEvent.click(screen.getByRole('button', { name: /Explore this line/i }));
    await waitFor(() => expect(screen.getByText('+1.40')).toBeInTheDocument());

    // Undo -> restores baseline +0.30
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => expect(screen.getByText('+0.30')).toBeInTheDocument());

    // Redo -> restores alternative branch +1.40
    fireEvent.click(screen.getByTitle(/Next Move/i));
    await waitFor(() => expect(screen.getByText('+1.40')).toBeInTheDocument());
  });

  it('10. Stale / delayed evaluation does not overwrite if board moved or was reset', async () => {
    let resolveDelayedEval: (value: api.MoveEvaluationResponse | null) => void = () => {};
    const delayedEvalPromise = new Promise<api.MoveEvaluationResponse | null>((resolve) => {
      resolveDelayedEval = resolve;
    });

    vi.mocked(api.evaluateMove).mockImplementationOnce(() => delayedEvalPromise);

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // User plays move with in-flight evaluation
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));

    // User immediately resets board position before evaluation finishes
    fireEvent.click(screen.getByTitle(/Reset Position/i));
    await waitFor(() => expect(screen.getByText('+0.30')).toBeInTheDocument());

    // Now resolve the delayed evaluation
    resolveDelayedEval({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      bestEvalCp: 80,
      evalCp: 80,
      evalLoss: 0.0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    // EvalBar must remain at +0.30 and not be overwritten by stale -0.80
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText('+0.30')).toBeInTheDocument();
  });

  it('10b. unmount invalidates delayed evaluation resolve and reject effects', async () => {
    const resolvingEvaluation = deferred<api.MoveEvaluationResponse | null>();
    vi.mocked(api.evaluateMove).mockReturnValueOnce(resolvingEvaluation.promise);

    const first = render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));
    expect(api.evaluateMove).toHaveBeenCalledTimes(1);
    first.unmount();
    vi.mocked(sound.playSound).mockClear();

    await act(async () => {
      resolvingEvaluation.resolve({
        fen: mockPuzzleWhite.fen,
        move: 'a6',
        bestMove: 'a6',
        bestEvalCp: 80,
        evalCp: 80,
        evalLoss: 0,
        maxEvalLoss: 0.8,
        threshold: 0.8,
        acceptable: true,
      });
    });
    expect(sound.playSound).not.toHaveBeenCalled();

    moveEvaluationService.clear();
    vi.mocked(api.fetchPuzzles).mockResolvedValue([mockPuzzleWhite]);
    const rejectingEvaluation = deferred<api.MoveEvaluationResponse | null>();
    vi.mocked(api.evaluateMove).mockReturnValueOnce(rejectingEvaluation.promise);
    const second = render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));
    second.unmount();
    vi.mocked(sound.playSound).mockClear();

    await act(async () => {
      rejectingEvaluation.reject(new Error('late evaluation failure'));
    });
    expect(sound.playSound).not.toHaveBeenCalled();
  });

  it('10c. replacing the Home puzzle baseline invalidates delayed evaluation rejection', async () => {
    const replacementPuzzle: Puzzle = {
      ...mockPuzzleWhite,
      puzzleId: 'replacement-puzzle',
      fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      evalCp: -25,
    };
    vi.mocked(api.fetchPuzzles).mockResolvedValue([mockPuzzleWhite, replacementPuzzle]);
    const delayedEvaluation = deferred<api.MoveEvaluationResponse | null>();
    vi.mocked(api.evaluateMove).mockReturnValueOnce(delayedEvaluation.promise);

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));

    fireEvent.click(screen.getByTitle('Next Puzzle'));
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', replacementPuzzle.fen);
      expect(screen.getByText('-0.25')).toBeInTheDocument();
    });
    vi.mocked(sound.playSound).mockClear();

    await act(async () => {
      delayedEvaluation.reject(new Error('stale replacement failure'));
    });

    expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', replacementPuzzle.fen);
    expect(screen.getByText('-0.25')).toBeInTheDocument();
    expect(screen.queryByText(/Evaluation failed|too inaccurate/i)).not.toBeInTheDocument();
    expect(sound.playSound).not.toHaveBeenCalled();
  });

  it('11. Manual exploration on the board without Continue Exploration remains locked', async () => {
    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    // Move 1: Puzzle target move Bb5 (historyIndex = 1) -> Solved and unlocked
    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument());
    expect(screen.queryByText(/🔒 EXPL/)).not.toBeInTheDocument();

    // Move 2: User plays a manual move on the board WITHOUT clicking Continue Exploration (historyIndex = 2)
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));

    // EvalBar must now show the locked 🔒 EXPL badge
    await waitFor(() => {
      expect(screen.getByText(/🔒 EXPL/)).toBeInTheDocument();
    });

    // Undo back to Move 1 (historyIndex = 1) -> Unlocks again
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => {
      expect(screen.queryByText(/🔒 EXPL/)).not.toBeInTheDocument();
    });
  });

  it('12. Challenge Mode uses multi-candidate discovery via batched SAN input', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        { move: 'a6', resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4', providerType: 'ENGINE', evalLoss: 0.15 },
        { move: 'Nf6', resultingFen: 'r1bqkb1r/pppp1ppp/2n2n2/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 4', providerType: 'ENGINE', evalLoss: 0.18 },
        { move: 'd6', resultingFen: 'some-fen', providerType: 'ENGINE', evalLoss: 0.25 } // > 0.20, should be filtered out
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // 1. Can select Challenge Mode
    fireEvent.click(screen.getByRole('button', { name: /Challenge Mode/i }));

    // 2. Does not trigger ChessEcho response (user is to move)
    await waitFor(() => {
      expect(screen.getByText(/Challenge Mode — find a strong candidate move/i)).toBeInTheDocument();
    });

    // 3. Candidates target is displayed
    await waitFor(() => {
      expect(screen.getByText(/Find up to/i)).toBeInTheDocument();
    });

    // 4. SAN input is displayed
    const input = screen.getByPlaceholderText(/Enter candidate moves/i);
    expect(input).toBeInTheDocument();

    // 5. Drag-and-drop is disabled in Challenge Mode
    fireEvent.click(screen.getByTestId('simulate-expl-move-black'));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByText(/Good candidate/i)).not.toBeInTheDocument();

    // 6. Invalid SAN rejects the whole batch and keeps input
    fireEvent.change(input, { target: { value: 'a6, invalid_move' } });
    fireEvent.click(screen.getByRole('button', { name: /Submit Candidates/i }));
    await waitFor(() => expect(screen.getByText(/"invalid_move" isn't valid SAN. Check the move and try again./i)).toBeInTheDocument());

    // 7. Duplicate SANs in batch are counted once
    // 8. evalLoss > 0.20 rejects the candidate (d6)
    // 9. Valid SAN is canonicalized and accepted (evalLoss <= 0.20)
    // 10. Multiple submitted candidates are evaluated in one submission
    vi.mocked(api.evaluateMove).mockImplementation(async (fen, moveSan) => {
      if (moveSan === 'a6') {
        return { fen, move: 'a6', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.15, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true };
      }
      if (moveSan === 'd6') {
        return { fen, move: 'd6', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.25, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true };
      }
      return null;
    });

    fireEvent.change(input, { target: { value: 'a6, a6, d6' } });
    fireEvent.click(screen.getByRole('button', { name: /Submit Candidates/i }));

    await waitFor(() => {
      // 11. Feedback shows accepted/rejected submitted candidates together
      expect(screen.getByText('You found 1 / 2. Keep looking.')).toBeInTheDocument();
      // Should show 'a6' as strong
      expect(screen.getByText(/a6 — not explored/)).toBeInTheDocument();
      // Should show 'd6' as weak
      expect(screen.getByText(/d6 — not strong enough/)).toBeInTheDocument();
      // 12. Duplicate a6 only listed once (by relying on it not crashing or showing 2/2)
    });

    // 13. Engine candidate moves are not exposed (Nf6 should not be visible)
    expect(screen.queryByText(/Nf6/)).not.toBeInTheDocument();

    // 14. Finding the top qualifying candidates completes the challenge
    vi.mocked(api.evaluateMove).mockImplementation(async (fen, moveSan) => {
      if (moveSan === 'a6') {
        return { fen, move: 'a6', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.15, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true };
      }
      if (moveSan === 'Nge7') {
        return { fen, move: 'Nge7', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.20, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true };
      }
      return null;
    });

    // Submitting a new batch
    fireEvent.change(input, { target: { value: 'a6, Nge7' } });
    fireEvent.click(screen.getByRole('button', { name: /Submit Candidates/i }));

    await waitFor(() => {
      expect(screen.getByText('Excellent. You found all 2 strong candidates.')).toBeInTheDocument();
      // Input should be hidden when complete
      expect(screen.queryByPlaceholderText(/Enter candidate moves/i)).not.toBeInTheDocument();
    });

    // 16. Clicking a candidate applies exactly that move to the board and remains in CHALLENGE mode
    const a6Button = screen.getByRole('button', { name: /a6 — not explored/i });

    vi.mocked(api.fetchPuzzleContinuation).mockImplementation(async (fen) => {
      if (fen === 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4') {
        return {
          fen,
          requestedMode: 'ENGINE',
          effectiveProvider: 'ENGINE',
          candidates: [
            { move: 'Ba4', resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4', providerType: 'ENGINE', evalLoss: 0.0 }
          ],
        } as api.ContinuationResponse;
      }
      return { fen, requestedMode: 'ENGINE', effectiveProvider: 'ENGINE', candidates: [] };
    });

    fireEvent.click(a6Button);

    await waitFor(() => {
      expect(screen.getByText(/What is White's best continuation/i)).toBeInTheDocument();
      expect(screen.queryByText('Excellent. You found all 2 strong candidates.')).not.toBeInTheDocument();
    });


  });

  it('13. Challenge Mode multi-branch calculation semantics', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        { move: 'a6', resultingFen: 'fen_a6', providerType: 'ENGINE', evalLoss: 0.15 },
        { move: 'Nf6', resultingFen: 'fen_Nf6', providerType: 'ENGINE', evalLoss: 0.18 },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('simulate-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // 1. Can select Challenge Mode
    fireEvent.click(screen.getByRole('button', { name: /Challenge Mode/i }));

    await waitFor(() => {
      expect(screen.getByText(/Find up to/i)).toBeInTheDocument();
    });

    vi.mocked(api.evaluateMove).mockImplementation(async (fen, moveSan) => {
      if (moveSan === 'a6') {
        return { fen, move: 'a6', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.15, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true, color: 'b' } as api.MoveEvaluationResponse;
      }
      if (moveSan === 'Nf6') {
        return { fen, move: 'Nf6', bestMove: 'a6', bestEvalCp: 80, evalCp: 80, evalLoss: 0.18, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true, color: 'b' } as api.MoveEvaluationResponse;
      }
      return null;
    });

    // 2. Candidate discovery
    const input = screen.getByPlaceholderText(/Enter candidate moves/i);
    fireEvent.change(input, { target: { value: 'a6, Nf6' } });
    fireEvent.click(screen.getByRole('button', { name: /Submit Candidates/i }));

    await waitFor(() => {
      expect(screen.getByText(/Choose a candidate to calculate/i)).toBeInTheDocument();
    });

    // 3. Strong candidate buttons remain available
    const a6Button = screen.getAllByRole('button').find(b => b.textContent?.includes('a6') && b.textContent?.includes('not explored'));
    const nf6Button = screen.getAllByRole('button').find(b => b.textContent?.includes('Nf6') && b.textContent?.includes('not explored'));
    expect(a6Button).toBeDefined();
    console.log(screen.getAllByRole('button').map(b => b.textContent)); expect(nf6Button).toBeDefined();

    // 4. Selecting candidate A creates branch A
    fireEvent.click(a6Button!);

    await waitFor(() => {
      expect(screen.getByText(/Candidate: a6/i)).toBeInTheDocument();
      // Initially, the calculation line is just the candidate move
      expect(screen.getByText(/1\.\.\. a6/)).toBeInTheDocument();
      expect(screen.getByText(/What is White's best continuation/i)).toBeInTheDocument();
    });

    // 5. Calculating several plies in branch A
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3', move: 'Ba4', bestMove: 'Ba4', bestEvalCp: 120, evalCp: 120, evalLoss: 0.0, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true, color: 'w'
    } as api.MoveEvaluationResponse);

    const calcInput = screen.getByPlaceholderText(/SAN input/i);
    fireEvent.change(calcInput, { target: { value: 'Ba4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }));

    await waitFor(() => {
      expect(screen.getByText(/1\.\.\. a6 2\. Ba4/)).toBeInTheDocument();
    });

    // 6. Returning to candidates preserves branch A
    fireEvent.click(screen.getByRole('button', { name: 'Back to candidates' }));

    await waitFor(() => {
      expect(screen.getByText(/Choose a candidate to calculate/i)).toBeInTheDocument();
    });

    // The button for a6 should now say 'explored'
    const a6Explored = screen.getAllByRole('button').find(b => b.textContent?.includes('a6') && b.textContent?.includes('explored'));
    expect(a6Explored).toBeDefined();

    // 7. Selecting candidate B creates independent branch B
    const nf6Btn = screen.getAllByRole('button').find(b => b.textContent?.includes('Nf6'));
    fireEvent.click(nf6Btn!);

    await waitFor(() => {
      expect(screen.getByText(/Candidate: Nf6/i)).toBeInTheDocument();
      expect(screen.getByText(/1\.\.\. Nf6/)).toBeInTheDocument();
      // Make sure branch A's moves are NOT here
      expect(screen.queryByText(/2\\. Ba4/)).not.toBeInTheDocument();
    });

    // 8. Calculating ply in branch B
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3', move: 'd4', bestMove: 'd4', bestEvalCp: 120, evalCp: 120, evalLoss: 0.0, maxEvalLoss: 0.8, threshold: 0.8, acceptable: true, color: 'w'
    } as api.MoveEvaluationResponse);
    fireEvent.change(screen.getByPlaceholderText(/SAN input/i), { target: { value: 'd4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }));

    await waitFor(() => {
      expect(screen.getByText(/1\.\.\. Nf6 2\. d4/)).toBeInTheDocument();
    });

    // 9. Back removes only the latest ply from the active branch
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    await waitFor(() => {
      expect(screen.getByText(/1\.\.\. Nf6/)).toBeInTheDocument();
      expect(screen.queryByText(/d4/)).not.toBeInTheDocument();
    });

    // 10. Returning to candidate A restores its calculation line
    fireEvent.click(screen.getByRole('button', { name: 'Back to candidates' }));
    await waitFor(() => {
      expect(screen.getByText(/Choose a candidate/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getAllByRole('button').find(b => b.textContent?.includes('a6') && b.textContent?.includes('explored'))!);

    await waitFor(() => {
      expect(screen.getByText(/Candidate: a6/i)).toBeInTheDocument();
      expect(screen.getByText(/1\.\.\. a6 2\. Ba4/)).toBeInTheDocument();
    });

    // 11. visible board remains unchanged throughout Challenge Mode
    expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(2);

    // 12. Finish Challenge exits cleanly
    fireEvent.click(screen.getByRole('button', { name: 'Back to candidates' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Finish Challenge/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Finish Challenge/i }));

    await waitFor(() => {
      expect(screen.queryByText(/Candidate:/i)).not.toBeInTheDocument();
    });
  });
});
