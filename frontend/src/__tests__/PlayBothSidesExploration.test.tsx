import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import { Puzzle } from '../mock/mockData';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { onPieceDrop: (args: { sourceSquare: string; targetSquare: string }) => boolean } }) => (
    <div data-testid="mock-chessboard">
      <button
        data-testid="play-puzzle-move-white"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' }); // Bb5
        }}
      >
        Play Bb5 (White)
      </button>
      <button
        data-testid="play-move-black-a6"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'a7', targetSquare: 'a6' }); // a6
        }}
      >
        Play a6 (Black)
      </button>
      <button
        data-testid="play-move-white-ba4"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'b5', targetSquare: 'a4' }); // Ba4
        }}
      >
        Play Ba4 (White)
      </button>
      <button
        data-testid="play-move-white-h4"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'h2', targetSquare: 'h4' }); // h4 (White inaccurate)
        }}
      >
        Play h4 (White - inaccurate)
      </button>
      <button
        data-testid="play-move-white-e4"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'e2', targetSquare: 'e4' });
        }}
      >
        Play e4
      </button>
      <button
        data-testid="play-move-white-e4"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'e2', targetSquare: 'e4' });
        }}
      >
        Play e4
      </button>
      <button
        data-testid="play-move-black-e5"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'e7', targetSquare: 'e5' });
        }}
      >
        Play e5 (Black)
      </button>
      <button
        data-testid="play-move-white-nf3"
        onClick={() => {
          options?.onPieceDrop({ sourceSquare: 'g1', targetSquare: 'f3' });
        }}
      >
        Play Nf3
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

describe('Play Both Sides Mode in Line Exploration', () => {
  const mockPuzzle: Puzzle = {
    puzzleId: 'puzzle-pbs-1',
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
    vi.mocked(api.fetchPuzzles).mockResolvedValue([mockPuzzle]);
  });

  it('1. User can enter Play Both Sides mode from Line Exploration', async () => {
    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    // Solve puzzle
    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // By default, starts in vs ChessEcho
    expect(screen.getByRole('button', { name: /vs ChessEcho/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Play Both Sides/i })).toBeInTheDocument();

    // Click Play Both Sides
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Prominent badge and side indicator appear
    expect(screen.getByTestId('play-both-sides-badge')).toBeInTheDocument();
    expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    expect(screen.getByText(/Opponent's turn — think like Black\. Find their strongest move\./i)).toBeInTheDocument();
  });

  it('2. User plays Black move, turn switches to White, and user plays White move', async () => {
    // Evaluation for Black's move a6: Black perspective -30 -> White perspective +0.30 (evalLoss = 0 -> best)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      evalCp: -30,
      bestEvalCp: -30,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    // Evaluation for White's move Ba4: White perspective +45 -> White perspective +0.45 (evalLoss = 0 -> best)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      evalCp: 45,
      bestEvalCp: 45,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    // Solve puzzle & enter Play Both Sides
    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // User plays Black move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));

    // Move is evaluated and feedback is displayed
    await waitFor(() => {
      expect(screen.getByText('Best move. No evaluation loss.')).toBeInTheDocument();
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Your turn — find the best move for White\./i)).toBeInTheDocument();
    });

    // EvalBar updated to +0.30
    expect(screen.getByText('+0.30')).toBeInTheDocument();

    // User plays White move Ba4
    fireEvent.click(screen.getByTestId('play-move-white-ba4'));

    // Responsibility switches back to Black
    await waitFor(() => {
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Opponent's turn — think like Black\. Find their strongest move\./i)).toBeInTheDocument();
      expect(screen.getByText('+0.45')).toBeInTheDocument();
    });
  });

  it('3. ChessEcho does not automatically play a continuation move in Play Both Sides mode', async () => {
    // Setup a continuation response
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -30,
          evalLoss: 0,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    // Solve puzzle & enter Play Both Sides
    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Wait a bit to ensure ChessEcho does NOT play
    await new Promise((resolve) => setTimeout(resolve, 300));

    // Turn should still be Black's turn (user must play)
    expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    expect(screen.getByText(/Opponent's turn — think like Black\. Find their strongest move\./i)).toBeInTheDocument();
    // Candidate card should NOT be shown automatically
    expect(screen.queryByText(/ChessEcho played/i)).not.toBeInTheDocument();
  });

  it('4. Handles good-but-inferior move and inaccurate move in Play Both Sides', async () => {
    // Good move with eval loss: Black plays a6
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'Nf6',
      evalCp: -15, // Black to move: -15 -> +0.15 White
      bestEvalCp: -30,
      evalLoss: 0.15,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    // Next move: White plays inaccurate move h4
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'h4',
      bestMove: 'Ba4',
      evalCp: -120,
      bestEvalCp: 45,
      evalLoss: 1.65,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: false,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // User plays acceptable move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      expect(screen.getByText(/Good move\. It loses 0\.15 pawns compared with the best move\./i)).toBeInTheDocument();
      expect(screen.getByText('+0.15')).toBeInTheDocument();
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
    });

    // User plays inaccurate move h4 for White
    fireEvent.click(screen.getByTestId('play-move-white-h4'));
    await waitFor(() => {
      expect(screen.getByText(/That move is too inaccurate\. It loses 1\.65 pawns compared with the best move\./i)).toBeInTheDocument();
    });
  });

  it('5. Undo and Redo restore positions, side-to-move, and evaluations in Play Both Sides', async () => {
    // 1. Black plays a6 -> +0.30
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      evalCp: -30,
      bestEvalCp: -30,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    // 2. White plays Ba4 -> +0.45
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      evalCp: 45,
      bestEvalCp: 45,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play a6 -> White to move (+0.30)
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText('+0.30')).toBeInTheDocument();
    });

    // Play Ba4 -> Black to move (+0.45)
    fireEvent.click(screen.getByTestId('play-move-white-ba4'));
    await waitFor(() => {
      expect(screen.getByText('+0.45')).toBeInTheDocument();
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    });

    // Undo move (steps back to after a6)
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => {
      expect(screen.getByText('+0.30')).toBeInTheDocument();
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Your turn — find the best move for White\./i)).toBeInTheDocument();
    });

    // Undo again (steps back to baseline after Bb5)
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => {
      expect(screen.getByText('+0.30')).toBeInTheDocument();
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Opponent's turn — think like Black\. Find their strongest move\./i)).toBeInTheDocument();
    });

    // Redo (steps forward to after a6)
    fireEvent.click(screen.getByTitle(/Next Move/i));
    await waitFor(() => {
      expect(screen.getByText('+0.30')).toBeInTheDocument();
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
    });

    // Redo again (steps forward to after Ba4)
    fireEvent.click(screen.getByTitle(/Next Move/i));
    await waitFor(() => {
      expect(screen.getByText('+0.45')).toBeInTheDocument();
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    });
  });

  it('6. Branching from an earlier position overwrites the forward branch in Play Both Sides', async () => {
    // 1. Black plays a6 -> +0.30
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      evalCp: -30,
      bestEvalCp: -30,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    // 2. White plays Ba4 -> +0.45
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      evalCp: 45,
      bestEvalCp: 45,
      evalLoss: 0,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true,
    });

    // 3. Alternative: After undoing to after a6, White plays h4 -> -1.20
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'h4',
      bestMove: 'Ba4',
      evalCp: -120, // White perspective -1.20
      bestEvalCp: 45,
      evalLoss: 1.65,
      maxEvalLoss: 200,
      threshold: 200,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play a6 -> Ba4
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText('+0.30')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('play-move-white-ba4'));
    await waitFor(() => {
      expect(screen.getByText('+0.45')).toBeInTheDocument();
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    });

    // Undo once to position after a6 (White to move)
    fireEvent.click(screen.getByTitle(/Previous Move/i));
    await waitFor(() => {
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText('+0.30')).toBeInTheDocument();
    });

    // Play alternative branch: White plays h4
    fireEvent.click(screen.getByTestId('play-move-white-h4'));
    await waitFor(() => {
      expect(screen.getByText('-1.20')).toBeInTheDocument();
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    });

    // Redo button should now be disabled (forward history truncated)
    expect(screen.getByTitle(/Next Move/i)).toBeDisabled();
  });

  it('7. Switching between vs ChessEcho and Play Both Sides works smoothly', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -30,
          evalLoss: 0,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // Mode-selection screen appears, choose vs ChessEcho
    expect(screen.getByText(/Choose how you want to explore/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));

    // In vs ChessEcho mode, ChessEcho will play a6
    await waitFor(() => expect(screen.getByText('Your turn — explore a move.')).toBeInTheDocument());

    // Switch to Play Both Sides
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));
    expect(screen.getByTestId('play-both-sides-badge')).toBeInTheDocument();
    expect(screen.getByText(/White to move/i)).toBeInTheDocument();
    expect(screen.getByText(/Your turn — find the best move for White\./i)).toBeInTheDocument();

    // Switch back to vs ChessEcho
    fireEvent.click(screen.getByRole('button', { name: /vs ChessEcho/i }));
    expect(screen.queryByTestId('play-both-sides-badge')).not.toBeInTheDocument();
    expect(screen.getByText('Your turn — explore a move.')).toBeInTheDocument();
  });

  it('8. Opponent move with evalLoss <= 0.20 is accepted', async () => {
    // Evaluation for Black's move a6: evalLoss = 0.15 (<= 0.20)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'Nf6',
      evalCp: -15,
      bestEvalCp: -30,
      evalLoss: 0.15,
      maxEvalLoss: 30, // Normal tolerance is 30 pawns (huge for test)
      threshold: 30,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play Black's move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      // It is accepted, turn passes to White
      expect(screen.getByText(/White to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Good move\. It loses 0\.15 pawns/i)).toBeInTheDocument();
    });
  });

  it('9. Opponent move with evalLoss > 0.20 but <= normal maxEvalLoss is rejected with specific feedback', async () => {
    // Evaluation for Black's move a6: evalLoss = 0.25 (> 0.20, but <= 30)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'Nf6',
      evalCp: -5,
      bestEvalCp: -30,
      evalLoss: 0.25,
      maxEvalLoss: 30,
      threshold: 30,
      acceptable: true, // Backend says acceptable based on normal tolerance
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play Black's move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      // It is rejected, turn stays Black
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
      // Custom feedback is shown
      expect(screen.getByText(/Good response, but there's a stronger move\. This move loses 0\.25 pawns\. Keep looking\./i)).toBeInTheDocument();
    });
  });

  it('10. Opponent move with evalLoss > normal maxEvalLoss is rejected with standard feedback', async () => {
    // Evaluation for Black's move a6: evalLoss = 1.50
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'Nf6',
      evalCp: 120,
      bestEvalCp: -30,
      evalLoss: 1.50,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: false, // Backend says NOT acceptable
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play Black's move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => {
      // It is rejected, turn stays Black
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
      // Standard feedback is shown
      expect(screen.getByText(/That move is too inaccurate\. It loses 1\.50 pawns/i)).toBeInTheDocument();
    });
  });

  it('11. User own side move uses normal tolerance', async () => {
    // Fast forward to White's turn
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      move: 'a6',
      bestMove: 'a6',
      evalCp: -30,
      bestEvalCp: -30,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    // White plays Ba4 with evalLoss 0.50 (<= 0.8 maxEvalLoss, but > 0.20 opponent threshold)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Bxc6',
      evalCp: -20,
      bestEvalCp: 30,
      evalLoss: 0.50,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // Play Black's move a6
    fireEvent.click(screen.getByTestId('play-move-black-a6'));
    await waitFor(() => screen.getByText(/White to move/i));

    // Play White's move Ba4
    fireEvent.click(screen.getByTestId('play-move-white-ba4'));
    await waitFor(() => {
      // It is accepted because White is the user's side, even though evalLoss > 0.20
      expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
      expect(screen.getByText(/Good move\. It loses 0\.50 pawns/i)).toBeInTheDocument();
    });
  });
  it('12. User Black shows Opponent turn for White and Own turn for Black', async () => {
    // Puzzle where User is Black, and it's White's turn initially
    vi.mocked(api.fetchPuzzles).mockResolvedValue([{
      ...mockPuzzle,
      puzzleId: 'puzzle-pbs-black',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1', // Black to move
      playerColor: 'BLACK',
      targetMove: 'e5',
    }]);

    render(<Home />);
    // Wait for board to load
    await waitFor(() => expect(screen.getByTestId('play-move-black-e5')).toBeInTheDocument());

    // Solve the puzzle by playing e5 (user's turn as Black)
    fireEvent.click(screen.getByTestId('play-move-black-e5'));

    // Now Continue Exploration appears
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));

    // Switch to Play Both Sides
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Play Both Sides/i }));

    // User is Black, position is now White to move -> Opponent's turn
    await waitFor(() => {
      expect(screen.getByText(/Opponent's turn — think like White\. Find their strongest move\./i)).toBeInTheDocument();
    });

    // Play White move Nf3
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      move: 'Nf3',
      bestMove: 'Nf3',
      evalCp: 35,
      bestEvalCp: 35,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    fireEvent.click(screen.getByTestId('play-move-white-nf3'));

    await waitFor(() => {
      // User is Black, position is Black to move -> Own turn
      expect(screen.getByText(/Your turn — find the best move for Black\./i)).toBeInTheDocument();
    });
  });

  it('13. Initial mode selection state shows correctly, both options available, no auto-move', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValue({
      fen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        {
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
          evalCp: -30,
          evalLoss: 0,
        },
      ],
    });

    render(<Home />);
    await waitFor(() => screen.getByText('+0.30'));

    fireEvent.click(screen.getByTestId('play-puzzle-move-white'));
    await waitFor(() => screen.getByRole('button', { name: /Continue Exploration/i }));
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // Verify initial mode selection screen is unmistakably different
    expect(screen.getByText(/Choose how you want to explore/i)).toBeInTheDocument();

    // Neither mode appears pre-selected (ENGINE/HUMAN controls should be absent)
    expect(screen.queryByRole('button', { name: 'ENGINE' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'HUMAN' })).not.toBeInTheDocument();

    // Both options are equally available
    const vsEngineBtn = screen.getByRole('button', { name: /vs ChessEcho/i });
    const bothSidesBtn = screen.getByRole('button', { name: /Play Both Sides/i });
    expect(vsEngineBtn).toBeInTheDocument();
    expect(bothSidesBtn).toBeInTheDocument();

    // No continuation move has been played yet
    expect(screen.queryByText(/ChessEcho played/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Your turn — explore a move.')).not.toBeInTheDocument();

    // Now select vs ChessEcho and verify it transitions
    fireEvent.click(vsEngineBtn);

    // After selection, ENGINE/HUMAN controls should appear and it should wait for ChessEcho or user
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'ENGINE' })).toBeInTheDocument();
    });
  });
});
