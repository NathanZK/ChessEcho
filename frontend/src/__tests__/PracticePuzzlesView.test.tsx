import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { PracticePuzzlesView, type PracticePuzzlesViewHandle } from '@/components/PracticePuzzlesView';
import * as api from '@/services/api';

vi.mock('@/services/api', async () => {
  const actual = await vi.importActual<typeof import('@/services/api')>('@/services/api');
  return {
    ...actual,
    fetchPuzzles: vi.fn(),
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

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
      <button
        data-testid="play-c5-drop"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'c7', targetSquare: 'c5' })}
      >
        Play c5
      </button>
      <button
        data-testid="play-e5-drop"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'e7', targetSquare: 'e5' })}
      >
        Play e5
      </button>
      <button
        data-testid="play-d4-drop"
        onClick={() => options?.onPieceDrop?.({ sourceSquare: 'd2', targetSquare: 'd4' })}
      >
        Play d4
      </button>
    </div>
  ),
}));

describe('PracticePuzzlesView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders loading state initially while puzzles are loading', () => {
    vi.mocked(api.fetchPuzzles).mockReturnValue(new Promise(() => {}));

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    expect(screen.getByText(/Loading Practice Puzzles/i)).toBeInTheDocument();
  });

  it('renders empty puzzle state when no puzzles are returned', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText(/No Practice Puzzles Available/i)).toBeInTheDocument();
    });
  });

  it('renders puzzle board and controls when puzzles are available, and toggles discovery catalog', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([
      {
        puzzleId: 'puzzle-1',
        fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        playerColor: 'WHITE',
        targetMove: 'e4',
        openingTitle: 'King Pawn Game',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 35,
      },
    ]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    // Initial practice mode
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Browse Puzzles/i })).toBeInTheDocument();
    });

    // Toggle to discovery catalog
    screen.getByRole('button', { name: /Browse Puzzles/i }).click();

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Practice Puzzles/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Start Practice/i })).toBeInTheDocument();
      expect(screen.getByText('King Pawn Game')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Practice Puzzle/i })).toBeInTheDocument();
    });
  });

  it('transitions to practice shell when clicking Start Practice and returns to discovery on back', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([
      {
        puzzleId: 'puzzle-1',
        fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        playerColor: 'WHITE',
        targetMove: 'e4',
        openingTitle: 'King Pawn Game',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 35,
      },
    ]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    // Toggle to discovery catalog
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Browse Puzzles/i })).toBeInTheDocument();
    });
    screen.getByRole('button', { name: /Browse Puzzles/i }).click();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Start Practice/i })).toBeInTheDocument();
    });

    // Enter practice
    screen.getByRole('button', { name: /Start Practice/i }).click();

    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Back to Puzzles/i })).toBeInTheDocument();
    });

    // Exit practice back to discovery
    screen.getByRole('button', { name: /Back to Puzzles/i }).click();

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /Practice Puzzles/i })).toBeInTheDocument();
      expect(screen.getByText('King Pawn Game')).toBeInTheDocument();
    });
  });

  it('transitions to practice shell when clicking Practice Puzzle on a catalog card', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([
      {
        puzzleId: 'puzzle-1',
        fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
        playerColor: 'WHITE',
        targetMove: 'e4',
        openingTitle: 'Sicilian Defense',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 35,
      },
    ]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    // Toggle to discovery catalog
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Browse Puzzles/i })).toBeInTheDocument();
    });
    screen.getByRole('button', { name: /Browse Puzzles/i }).click();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Practice Puzzle/i })).toBeInTheDocument();
    });

    screen.getByRole('button', { name: /Practice Puzzle/i }).click();

    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /Back to Puzzles/i })).toBeInTheDocument();
    });
  });

  it('bypasses discovery catalog and mounts practice shell directly when selectPracticeFromLibrary is called', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);
    const actionsRef = React.createRef<PracticePuzzlesViewHandle | null>();

    render(
      <PracticePuzzlesView
        actionsRef={actionsRef}
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(actionsRef.current).not.toBeNull();
    });

    actionsRef.current!.selectPracticeFromLibrary({
      puzzleId: 'lib-puzzle-1',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
      playerColor: 'BLACK',
      targetMove: 'c5',
      openingTitle: 'Sicilian Defense',
      acceptableMoves: [],
      movesPlayed: [],
      priority: 1,
      timesReached: 2,
      mistakeCount: 1,
      mistakeRate: 0.5,
      evalCp: 35,
    });

    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /Practice Puzzles/i })).not.toBeInTheDocument();
    });
  });

  it('populates activePuzzle with source long-decision and longDecisionContext when exploring long decision', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);
    const actionsRef = React.createRef<PracticePuzzlesViewHandle | null>();

    render(
      <PracticePuzzlesView
        actionsRef={actionsRef}
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(actionsRef.current).not.toBeNull();
    });

    const testOccurrence: api.LongDecisionOccurrence = {
      id: 'occ-123',
      positionId: 'pos-123',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
      playerColor: 'BLACK',
      plyNumber: 2,
      movePlayed: 'e5',
      decisionTimeMs: 45_000,
      timeControl: 'RAPID',
      platformGameId: 'game-123',
      playedAt: '2026-03-01T12:00:00Z',
      opponentUsername: 'opponent',
      chessComUrl: 'https://www.chess.com/game/live/123?move=2',
      severity: 'SIGNIFICANT',
    };

    let success = false;
    await waitFor(() => {
      success = actionsRef.current!.exploreLongDecision(testOccurrence);
    });

    expect(success).toBe(true);
    // Decision Timing metadata should be rendered
    expect(screen.getByText('Decision Timing')).toBeInTheDocument();
    expect(screen.getByText('RAPID')).toBeInTheDocument();
    // Pre-attempt secrecy: original move must strictly NOT be disclosed
    expect(screen.queryByText('e5')).not.toBeInTheDocument();
    expect(screen.getByText(/Find Black's best move\. Can you improve upon your game decision/i)).toBeInTheDocument();
    expect(screen.queryByText(/Target Opening Weakness/i)).not.toBeInTheDocument();
    // Statistics badges must NOT be rendered
    expect(screen.queryByText(/Mistake Rate/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Times Reached/i)).not.toBeInTheDocument();
  });

  it('restores captured originating tab when exiting exploration', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);
    const actionsRef = React.createRef<PracticePuzzlesViewHandle | null>();
    const onReturnToTab = vi.fn();

    render(
      <PracticePuzzlesView
        actionsRef={actionsRef}
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
        onReturnToTab={onReturnToTab}
      />
    );

    await waitFor(() => {
      expect(actionsRef.current).not.toBeNull();
    });

    // Explore position opened from 'import' tab
    actionsRef.current!.explorePosition(
      {
        fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
        sanHistory: [],
      },
      false,
      'import'
    );

    const exitBtn = await screen.findByRole('button', { name: /Exit/i });
    expect(exitBtn).toBeInTheDocument();
    exitBtn.click();

    expect(onReturnToTab).toHaveBeenCalledWith('import');
  });

  it('executes legal move on Decision Timing position, captures running practice stopwatch, and renders dual duration comparison', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);
    const actionsRef = React.createRef<PracticePuzzlesViewHandle | null>();
    const onReturnToTab = vi.fn();

    render(
      <PracticePuzzlesView
        actionsRef={actionsRef}
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
        onReturnToTab={onReturnToTab}
      />
    );

    await waitFor(() => {
      expect(actionsRef.current).not.toBeNull();
    });

    const testOccurrence: api.LongDecisionOccurrence = {
      id: 'occ-123',
      positionId: 'pos-123',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
      playerColor: 'BLACK',
      plyNumber: 2,
      movePlayed: 'e5',
      decisionTimeMs: 45_000,
      timeControl: 'RAPID',
      platformGameId: 'game-123',
      playedAt: '2026-03-01T12:00:00Z',
      opponentUsername: 'opponent',
      chessComUrl: 'https://www.chess.com/game/live/123?move=2',
      severity: 'SIGNIFICANT',
    };

    await waitFor(() => {
      expect(actionsRef.current!.exploreLongDecision(testOccurrence)).toBe(true);
    });

    // 1. Verify expected position is loaded on the board
    const board = screen.getByTestId('mock-chessboard');
    expect(board).toHaveAttribute('data-position', testOccurrence.fen);

    // 2. Verify running practice stopwatch is visibly rendered before the first move
    expect(await screen.findByText(/0:0[0-9]/)).toBeInTheDocument();

    // 3. Verify pre-attempt secrecy: original move 'e5' and original duration '45s' are concealed
    expect(screen.queryByText('Played:')).not.toBeInTheDocument();
    expect(screen.queryByText('(0:45)')).not.toBeInTheDocument();

    // 4. Mock evaluation response
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: testOccurrence.fen,
      move: 'c5',
      bestMove: 'c5',
      bestEvalCp: 20,
      evalCp: 20,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    // 5. Submit move 'c5' through the rendered chessboard interaction mechanism
    const playBtn = screen.getByTestId('play-c5-drop');
    playBtn.click();

    // 6. Verify evaluation receives the correct pre-move FEN and submitted move SAN
    await waitFor(() => {
      expect(api.evaluateMove).toHaveBeenCalledWith(testOccurrence.fen, 'c5');
    });

    // 7. Verify post-evaluation feedback and dual duration comparison are rendered
    expect(await screen.findByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    expect(screen.getByText('Decision Improved 🚀')).toBeInTheDocument();
    expect(screen.getByText('Played:')).toBeInTheDocument();
    expect(screen.getByText('e5')).toBeInTheDocument();
    expect(screen.getByText('(0:45)')).toBeInTheDocument();
    expect(screen.getByText(/Solved in [0-9.]+s vs\. original game thinking time of 0:45\./)).toBeInTheDocument();

    // 8. Verify return context remains intact and can exit back to long-decisions
    const exitBtn = screen.getAllByRole('button', { name: /Exit/i })[0];
    exitBtn.click();
    expect(onReturnToTab).toHaveBeenCalledWith('long-decisions');
  });

  it('preserves captured duration across evaluation failure and subsequent retry', async () => {
    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([]);
    const actionsRef = React.createRef<PracticePuzzlesViewHandle | null>();

    render(
      <PracticePuzzlesView
        actionsRef={actionsRef}
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(actionsRef.current).not.toBeNull();
    });

    const testOccurrence: api.LongDecisionOccurrence = {
      id: 'occ-123',
      positionId: 'pos-123',
      fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
      playerColor: 'BLACK',
      plyNumber: 2,
      movePlayed: 'e5',
      decisionTimeMs: 45_000,
      timeControl: 'RAPID',
      platformGameId: 'game-123',
      playedAt: '2026-03-01T12:00:00Z',
      opponentUsername: 'opponent',
      severity: 'SIGNIFICANT',
    };

    await waitFor(() => {
      expect(actionsRef.current!.exploreLongDecision(testOccurrence)).toBe(true);
    });

    // Initial evaluation returns null (failure)
    vi.mocked(api.evaluateMove).mockResolvedValueOnce(null);

    // Wait for running stopwatch before submitting move
    expect(await screen.findByText(/0:0[0-9]/)).toBeInTheDocument();

    // Submit move
    const playBtn = screen.getByTestId('play-c5-drop');
    playBtn.click();

    // Assert evaluation error state is rendered with retry button
    expect(await screen.findByText('Move Evaluation Failed')).toBeInTheDocument();
    const retryBtn = screen.getByRole('button', { name: /Retry evaluation/i });
    expect(retryBtn).toBeInTheDocument();

    // On retry, evaluation succeeds
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: testOccurrence.fen,
      move: 'c5',
      bestMove: 'c5',
      bestEvalCp: 20,
      evalCp: 20,
      evalLoss: 0,
      maxEvalLoss: 0.8,
      threshold: 0.8,
      acceptable: true,
    });

    retryBtn.click();

    // Assert evaluation resolved successfully and preserved original captured duration in comparison
    expect(await screen.findByText('Puzzle Solved! 🎉')).toBeInTheDocument();
    expect(screen.getByText(/Solved in [0-9.]+s vs\. original game thinking time of 0:45\./)).toBeInTheDocument();
  });

  it('resets board position and clears move history when navigating between puzzles sharing identical initialFen in weakness playlist', async () => {
    const SAME_FEN = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
    const POST_D4_FEN = 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3';

    vi.mocked(api.fetchPuzzles).mockResolvedValueOnce([
      {
        puzzleId: 'puzzle-1',
        fen: SAME_FEN,
        playerColor: 'WHITE',
        targetMove: 'd4',
        openingTitle: 'Ruy Lopez / Italian Game',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 1,
        timesReached: 1,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 35,
      },
      {
        puzzleId: 'puzzle-weakness-1',
        fen: SAME_FEN,
        playerColor: 'WHITE',
        targetMove: 'd4',
        openingTitle: 'Weakness Drill / Scotch Game',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 2,
        timesReached: 2,
        mistakeCount: 1,
        mistakeRate: 1,
        evalCp: 40,
      },
    ]);

    render(
      <PracticePuzzlesView
        sessionStatus="authenticated"
        sessionUserId="user-1"
        activeAccountId="acc-1"
        activeUsername="test_user"
        sessionGatePromise={Promise.resolve(true)}
        sessionGateOpen={true}
        onNavigateTab={vi.fn()}
      />
    );

    // Wait for puzzle-1 to load
    await waitFor(() => {
      expect(screen.getByText('Puzzle 1 of 2')).toBeInTheDocument();
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', SAME_FEN);
    });

    // Solve or make move d4 on puzzle-1
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: SAME_FEN,
      move: 'd4',
      bestMove: 'd4',
      bestEvalCp: 40,
      evalCp: 40,
      evalLoss: 0,
      maxEvalLoss: 0.5,
      threshold: 0.5,
      acceptable: true,
    });

    screen.getByTestId('play-d4-drop').click();

    // Verify move is applied on board
    await waitFor(() => {
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', POST_D4_FEN);
      expect(screen.getByTitle(/Previous Move/i)).not.toBeDisabled();
    });

    // Click Next Puzzle to navigate to puzzle-weakness-1 (which has identical SAME_FEN)
    const nextBtn = screen.getAllByRole('button', { name: /Next Puzzle/i })[0];
    nextBtn.click();

    // Verify board resets to initial SAME_FEN, history index is 0, and Previous Move is disabled
    await waitFor(() => {
      expect(screen.getByText('Puzzle 2 of 2')).toBeInTheDocument();
      expect(screen.getByText('Weakness Drill / Scotch Game')).toBeInTheDocument();
      expect(screen.getByTestId('mock-chessboard')).toHaveAttribute('data-position', SAME_FEN);
      expect(screen.getByTitle(/Previous Move/i)).toBeDisabled();
    });
  });
});
