import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { Chess } from 'chess.js';
import Home from '../app/page';
import * as api from '../services/api';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import type { Puzzle } from '../mock/mockData';

const DROPS: Array<[string, string]> = [
  ['e7', 'e5'],
  ['h7', 'h6'],
  ['g1', 'f3'],
  ['b8', 'c6'],
  ['e2', 'e4'],
];

vi.mock('react-chessboard', () => ({
  Chessboard: ({
    options,
  }: {
    options: {
      position: string;
      boardOrientation?: string;
      darkSquareStyle?: React.CSSProperties;
      lightSquareStyle?: React.CSSProperties;
      onPieceDrop?: (args: { sourceSquare: string; targetSquare: string }) => boolean;
    };
  }) => (
    <div
      data-testid="mock-chessboard"
      data-position={options.position}
      data-orientation={options.boardOrientation ?? 'white'}
      data-dark={JSON.stringify(options.darkSquareStyle ?? null)}
      data-light={JSON.stringify(options.lightSquareStyle ?? null)}
    >
      {DROPS.map(([from, to]) => (
        <button
          key={`${from}${to}`}
          data-testid={`drop-${from}-${to}`}
          onClick={() => options.onPieceDrop?.({ sourceSquare: from, targetSquare: to })}
        />
      ))}
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
    fetchWeaknesses: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

const after = (fen: string, ...moves: string[]) => {
  const game = new Chess(fen);
  moves.forEach((m) => game.move(m));
  return game.fen();
};

const blackPuzzle: Puzzle = {
  puzzleId: 'blindfold-black',
  fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  playerColor: 'BLACK',
  targetMove: 'e5',
  openingTitle: "King's Pawn Opening",
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1,
  timesReached: 10,
  mistakeCount: 2,
  mistakeRate: 20,
  evalCp: 30,
};

const whitePuzzle: Puzzle = {
  ...blackPuzzle,
  puzzleId: 'blindfold-white',
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  playerColor: 'WHITE',
  targetMove: 'e4',
  openingTitle: 'Starting Position',
};

const SOLVED_FEN = after(blackPuzzle.fen, 'e5');
const WRONG_FEN = after(blackPuzzle.fen, 'h6');
const EXPLORED_FEN = after(SOLVED_FEN, 'Nf3');

const continuationReply = (fen: string, move: string): api.ContinuationResponse => ({
  fen,
  requestedMode: 'ENGINE',
  effectiveProvider: 'ENGINE',
  candidates: [{ move, resultingFen: after(fen, move), providerType: 'ENGINE', evalCp: 20 }],
});

const evaluation = (fen: string, move: string): api.MoveEvaluationResponse => ({
  fen,
  move,
  bestMove: move,
  bestEvalCp: 20,
  evalCp: 20,
  evalLoss: 0,
  maxEvalLoss: 0.5,
  threshold: 0.5,
  acceptable: true,
});

const board = () => screen.getByTestId('mock-chessboard');
const blindfoldEntry = () => screen.getByRole('button', { name: /train blindfold/i });
const notationInput = () => screen.getByRole('textbox', { name: 'Move notation' });

const loadPuzzles = async (puzzles: Puzzle[] = [blackPuzzle]) => {
  vi.mocked(api.fetchPuzzles).mockResolvedValue(puzzles);
  render(<Home />);
  await waitFor(() => expect(screen.getByText(puzzles[0].openingTitle)).toBeInTheDocument());
};

const solve = async () => {
  fireEvent.click(screen.getByTestId('drop-e7-e5'));
  await screen.findByText('Puzzle Solved! 🎉');
};

const enterBlindfold = () => {
  fireEvent.click(blindfoldEntry());
  return screen.findByText('Board hidden');
};

const revealBoard = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Reveal board' }));
  return board();
};

const exploreBothSides = async () => {
  fireEvent.click(screen.getByRole('button', { name: /continue exploration/i }));
  fireEvent.click(await screen.findByText('Play Both Sides'));
};

describe('Blindfold continuation flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    continuationService.clear();
    moveEvaluationService.clear();
    localStorage.clear();
    window.location.hash = '';
    localStorage.setItem('chessecho_username', 'testuser');
    vi.mocked(api.fetchPuzzleContinuation).mockImplementation(async (fen) => {
      if (fen === SOLVED_FEN || fen === WRONG_FEN) return continuationReply(fen, 'Nf3');
      return null;
    });
    vi.mocked(api.evaluateMove).mockImplementation(async (fen, move) => evaluation(fen, move));
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([]);
  });

  it('is not offered on a fresh puzzle and has no page-level toggle', async () => {
    await loadPuzzles();
    expect(screen.queryByRole('button', { name: /train blindfold/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /exit blindfold mode/i })).toBeNull();
  });

  it('continues from the solved position inside the existing puzzle columns', async () => {
    await loadPuzzles();
    await solve();
    const puzzleBoard = { dark: board().dataset.dark, light: board().dataset.light, orientation: board().dataset.orientation };
    await enterBlindfold();

    expect(screen.queryByText('Puzzle Solved! 🎉')).toBeNull();
    expect(screen.getByText('Blindfold continuation')).toBeInTheDocument();
    expect(screen.getByText('Eval')).toBeInTheDocument();

    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(SOLVED_FEN, 'ENGINE');
    await waitFor(() => expect(screen.getByText('Your turn — enter SAN notation')).toBeInTheDocument());

    const revealed = revealBoard();
    expect(revealed.dataset.position).toBe(after(SOLVED_FEN, 'Nf3'));
    expect(puzzleBoard.orientation).toBe('black');
    expect(revealed.dataset.orientation).toBe(puzzleBoard.orientation);
    expect(revealed.dataset.dark).toBe(puzzleBoard.dark);
    expect(revealed.dataset.light).toBe(puzzleBoard.light);

    vi.mocked(api.fetchPuzzleContinuation).mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(SOLVED_FEN, 'ENGINE');
  });

  it('shows the entry position when revealed before any blindfold move', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockReturnValue(new Promise(() => {}));
    await loadPuzzles();
    await solve();
    await enterBlindfold();
    expect(revealBoard().dataset.position).toBe(SOLVED_FEN);
  });

  it.each([
    ['the wrong-move card', false],
    ['Explore this decision', true],
  ])('continues from the wrong-move position via %s', async (_label, viaExploration) => {
    await loadPuzzles();
    fireEvent.click(screen.getByTestId('drop-h7-h6'));
    await screen.findByText('Not the Recommended Move');
    if (viaExploration) {
      fireEvent.click(screen.getByRole('button', { name: 'Explore this decision' }));
      await screen.findByText(/from h6/i);
    }

    await enterBlindfold();
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(WRONG_FEN, 'ENGINE');
    await screen.findByText('Your turn — enter SAN notation');
    vi.mocked(api.fetchPuzzleContinuation).mockClear();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(WRONG_FEN, 'ENGINE');
  });

  it('continues from a historical-mistake position', async () => {
    const historicalPuzzle = { ...blackPuzzle, movesPlayed: [{ move: 'Nc6', timesPlayed: 3, averageLoss: 0.6 }] };
    await loadPuzzles([historicalPuzzle]);
    fireEvent.click(screen.getByTestId('drop-b8-c6'));
    await screen.findByText('Recurring Weakness Detected!');

    vi.mocked(api.fetchPuzzleContinuation).mockReturnValue(new Promise(() => {}));
    await enterBlindfold();
    const historicalFen = after(blackPuzzle.fen, 'Nc6');
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(historicalFen, 'ENGINE');
    expect(revealBoard().dataset.position).toBe(historicalFen);
  });

  it('continues from an explored position with the player to move', async () => {
    await loadPuzzles();
    await solve();
    await exploreBothSides();
    fireEvent.click(screen.getByTestId('drop-g1-f3'));
    await waitFor(() => expect(board().dataset.position).toBe(EXPLORED_FEN));
    await waitFor(() => expect(blindfoldEntry()).toBeEnabled());

    vi.mocked(api.fetchPuzzleContinuation).mockClear();
    await enterBlindfold();
    expect(api.fetchPuzzleContinuation).not.toHaveBeenCalled();
    expect(screen.getByText('Your turn — enter SAN notation')).toBeInTheDocument();
    expect(revealBoard().dataset.position).toBe(EXPLORED_FEN);

    fireEvent.change(notationInput(), { target: { value: 'Nc6' } });
    fireEvent.submit(notationInput().closest('form')!);
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(after(EXPLORED_FEN, 'Nc6'), 'ENGINE');

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    expect(board().dataset.position).toBe(EXPLORED_FEN);
  });

  it('uses the navigated position after ArrowLeft in exploration', async () => {
    await loadPuzzles();
    await solve();
    await exploreBothSides();
    fireEvent.click(screen.getByTestId('drop-g1-f3'));
    await waitFor(() => expect(board().dataset.position).toBe(EXPLORED_FEN));
    await waitFor(() => expect(blindfoldEntry()).toBeEnabled());

    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    await waitFor(() => expect(board().dataset.position).toBe(SOLVED_FEN));

    await enterBlindfold();
    expect(api.fetchPuzzleContinuation).toHaveBeenLastCalledWith(SOLVED_FEN, 'ENGINE');
  });

  it('disables entry while an exploration move is being evaluated', async () => {
    let resolveEvaluation!: (value: api.MoveEvaluationResponse) => void;
    vi.mocked(api.evaluateMove).mockReturnValue(new Promise((resolve) => { resolveEvaluation = resolve; }));
    await loadPuzzles();
    await solve();
    await exploreBothSides();

    fireEvent.click(screen.getByTestId('drop-g1-f3'));
    await waitFor(() => expect(board().dataset.position).toBe(EXPLORED_FEN));
    expect(blindfoldEntry()).toBeDisabled();

    await act(async () => resolveEvaluation(evaluation(SOLVED_FEN, 'Nf3')));
    await waitFor(() => expect(blindfoldEntry()).toBeEnabled());
  });

  it('disables entry while a ChessEcho continuation is in flight', async () => {
    vi.mocked(api.fetchPuzzleContinuation).mockReturnValue(new Promise(() => {}));
    await loadPuzzles();
    await solve();
    fireEvent.click(screen.getByRole('button', { name: /continue exploration/i }));
    fireEvent.click(await screen.findByText('vs ChessEcho'));
    await waitFor(() => expect(blindfoldEntry()).toBeDisabled());
  });

  it('submits notation with Enter without advancing the puzzle', async () => {
    await loadPuzzles([blackPuzzle, whitePuzzle]);
    await solve();
    await enterBlindfold();
    await screen.findByText('Your turn — enter SAN notation');

    fireEvent.change(notationInput(), { target: { value: 'Nc6' } });
    const enter = { key: 'Enter', code: 'Enter' };
    fireEvent.keyDown(notationInput(), enter);
    fireEvent.submit(notationInput().closest('form')!);

    await waitFor(() => expect(screen.getByText('Nc6')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Exit' }));
    expect(await screen.findByText("King's Pawn Opening")).toBeInTheDocument();
    expect(screen.getByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    expect(screen.queryByText('Starting Position')).toBeNull();
  });

  it('ignores board shortcuts while blindfolded', async () => {
    await loadPuzzles();
    await solve();
    await enterBlindfold();
    await screen.findByText('Your turn — enter SAN notation');

    fireEvent.keyDown(window, { key: 'x' });
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    expect(revealBoard().dataset.orientation).toBe('black');

    fireEvent.click(screen.getByRole('button', { name: 'Exit' }));
    await screen.findByText('Puzzle Solved! 🎉');
    expect(board().dataset.position).toBe(SOLVED_FEN);
  });

  it('uses the flipped orientation chosen before entry', async () => {
    await loadPuzzles([whitePuzzle]);
    fireEvent.click(screen.getByTestId('drop-e2-e4'));
    await screen.findByText('Puzzle Solved! 🎉');
    fireEvent.keyDown(window, { key: 'x' });
    await waitFor(() => expect(board().dataset.orientation).toBe('black'));

    await enterBlindfold();
    expect(revealBoard().dataset.orientation).toBe('black');
  });

  it('restores the exploration card on exit', async () => {
    await loadPuzzles();
    await solve();
    await exploreBothSides();
    await enterBlindfold();

    fireEvent.click(screen.getByRole('button', { name: 'Exit' }));
    await screen.findByText('Line Exploration');
    expect(screen.queryByText('Blindfold continuation')).toBeNull();
    expect(board().dataset.position).toBe(SOLVED_FEN);
  });

  it('leaves blindfold when a different puzzle is selected from the library', async () => {
    vi.mocked(api.fetchWeaknesses).mockResolvedValue([{
      positionId: whitePuzzle.puzzleId,
      fen: whitePuzzle.fen,
      timesReached: 5,
      mistakeCount: 3,
      mistakeRate: 60,
      averageLoss: 0.9,
      priority: 8.5,
      bestMove: 'e4',
      acceptableMoves: [],
      movesPlayed: [],
      gameUrls: [],
      evalCp: 20,
    }]);
    await loadPuzzles([blackPuzzle, whitePuzzle]);
    await solve();
    await enterBlindfold();

    fireEvent.click(screen.getByRole('button', { name: /Weaknesses Library/i }));
    fireEvent.click(await screen.findByRole('button', { name: /Practice Position/i }));

    await waitFor(() => expect(board().dataset.position).toBe(whitePuzzle.fen));
    expect(screen.queryByText('Blindfold continuation')).toBeNull();
    expect(screen.queryByText('Board hidden')).toBeNull();
  });
});
