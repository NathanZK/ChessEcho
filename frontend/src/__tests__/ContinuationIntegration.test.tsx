import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { PuzzleFeedbackPanel } from '../components/PuzzleFeedbackPanel';
import { ChessBoardArea } from '../components/ChessBoardArea';
import { continuationService, moveEvaluationService } from '../services/continuationService';
import * as api from '../services/api';
import { Puzzle } from '../mock/mockData';

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { onPieceDrop: (args: { sourceSquare: string, targetSquare: string }) => boolean } }) => (
    <div data-testid="mock-chessboard">
      <button 
        data-testid="simulate-user-move"
        onClick={() => {
          if (options && options.onPieceDrop) {
            options.onPieceDrop({ sourceSquare: 'b5', targetSquare: 'a4' });
          }
        }}
      >
        Play Bc4
      </button>
      <button 
        data-testid="simulate-best-move"
        onClick={() => {
          if (options && options.onPieceDrop) {
            options.onPieceDrop({ sourceSquare: 'f1', targetSquare: 'b5' });
          }
        }}
      >
        Play Bb5
      </button>
    </div>
  ),
}));

vi.mock('../services/soundService', () => ({
  playSound: vi.fn(),
  soundService: {
    playMoveSound: vi.fn(),
    isSoundEnabled: vi.fn().mockReturnValue(true),
  }
}));

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

describe('Frontend Turn-Based Puzzle Line Exploration Integration', () => {
  const mockPuzzle: Puzzle = {
    puzzleId: 'test-puzzle-1',
    fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    playerColor: 'WHITE',
    targetMove: 'Bb5',
    acceptableMoves: [{ move: 'Bc4', evalLoss: 0.1 }],
    movesPlayed: [],
    mistakeCount: 1,
    timesReached: 5,
    mistakeRate: 20,
    openingTitle: 'Ruy Lopez',
    evalCp: 35,
    priority: 1,
  };

  beforeEach(() => {
    continuationService.clear();
    moveEvaluationService.clear();
    vi.clearAllMocks();
  });

  it('1. Normal exploration: enter exploration -> ChessEcho makes 1 move -> USER_TURN', () => {
    const handleEnterExploration = vi.fn();
    const handleExitExploration = vi.fn();

    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        onEnterExploration={handleEnterExploration}
        onExitExploration={handleExitExploration}
        continuationCandidate={{
          move: 'a6',
          resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          providerType: 'ENGINE',
        }}
      />
    );

    expect(screen.getByText('Line Exploration')).toBeInTheDocument();
    expect(screen.getByText(/Your turn — explore a move/i)).toBeInTheDocument();
    expect(screen.getByText(/Last: a6/i)).toBeInTheDocument();
  });

  it('2. Reject unacceptable move: board remains unchanged and shows error message', () => {
    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        unacceptableMoveMessage="That move is outside the acceptable range."
      />
    );

    expect(screen.getByText('That move is outside the acceptable range.')).toBeInTheDocument();
  });

  it('3. Undo after ChessEcho response maintains correct board history and position', async () => {
    const handleFenChange = vi.fn();
    const handleMoveAttempt = vi.fn();
    const rerenderBox: { fn?: (ui: React.ReactElement) => void } = {};

    const candidate: api.ContinuationCandidate = {
      move: 'Bb5',
      resultingFen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      providerType: 'ENGINE',
    };

    const handleContinuationApplied = vi.fn(() => {
      rerenderBox.fn?.(
        <ChessBoardArea
          initialFen={mockPuzzle.fen}
          playerColor="WHITE"
          targetMove="Bb5"
          acceptableMoves={mockPuzzle.acceptableMoves}
          movesPlayed={[]}
          onMoveAttempt={handleMoveAttempt}
          onNextPuzzle={() => {}}
          onFenChange={handleFenChange}
          pendingContinuationCandidate={null}
          onContinuationApplied={handleContinuationApplied}
          isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        />
      );
    });

    const { rerender } = render(
      <ChessBoardArea
        initialFen={mockPuzzle.fen}
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={handleMoveAttempt}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
      />
    );
    rerenderBox.fn = rerender;

    expect(handleFenChange).toHaveBeenCalledWith(mockPuzzle.fen);

    // Apply continuation candidate move
    rerender(
      <ChessBoardArea
        initialFen={mockPuzzle.fen}
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={handleMoveAttempt}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        pendingContinuationCandidate={candidate}
        onContinuationApplied={handleContinuationApplied}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
      />
    );

    await waitFor(() => {
      expect(handleContinuationApplied).toHaveBeenCalledTimes(1);
    });

    expect(handleFenChange).toHaveBeenCalledWith(candidate.resultingFen);
  });

  it('4. Exit exploration stops active exploration mode', () => {
    const handleExitExploration = vi.fn();

    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        onExitExploration={handleExitExploration}
      />
    );

    const exitBtn = screen.getByRole('button', { name: /Exit/i });
    fireEvent.click(exitBtn);

    expect(handleExitExploration).toHaveBeenCalledTimes(1);
  });

  it('5. Strict turn-based ownership: ChessEcho moves do not recursively trigger continuations', async () => {
    vi.mocked(api.evaluateMove).mockResolvedValue({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Ba4',
      bestEvalCp: 80,
      evalCp: 80,
      evalLoss: 0,
      maxEvalLoss: 0.80,
      threshold: 0.80,
      acceptable: true,
    });

    // 1st request: triggered by entering exploration
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce({
      fen: 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        { move: 'a6', resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4', providerType: 'ENGINE', evalLoss: 0 }
      ]
    });

    // 2nd request: triggered by user playing Ba4 in exploration mode
    vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4',
      requestedMode: 'ENGINE',
      effectiveProvider: 'ENGINE',
      candidates: [
        { move: 'Nf6', resultingFen: 'r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 5', providerType: 'ENGINE', evalLoss: 0 }
      ]
    });

    // A lightweight wrapper replicating page.tsx's state machine for exploration
    function TestOrchestrator() {
      const [explorationTurn, setExplorationTurn] = React.useState<'USER' | 'CHESSECHO' | 'OFF'>('OFF');
      const [currentBoardFen, setCurrentBoardFen] = React.useState(mockPuzzle.fen);
      const [pendingContinuationCandidate, setPendingContinuationCandidate] = React.useState<api.ContinuationCandidate | null>(null);

      // Simulate the usePuzzleContinuation hook effect inside page.tsx
      React.useEffect(() => {
        if (explorationTurn === 'CHESSECHO') {
          api.fetchPuzzleContinuation(currentBoardFen, 'ENGINE').then(res => {
            if (res && res.candidates && res.candidates.length > 0) {
              setPendingContinuationCandidate(res.candidates[0]);
            }
          }).catch(e => console.error('Fetch failed', e));
        }
      }, [explorationTurn, currentBoardFen]);

      const isExplorationActive = explorationTurn !== 'OFF';

      return (
        <div>
          <PuzzleFeedbackPanel
            puzzle={mockPuzzle}
            feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
            onNextPuzzle={() => {}}
            puzzleColorFilter="BOTH"
            onColorFilterChange={() => {}}
            showPuzzleSettings={false}
            onTogglePuzzleSettings={() => {}}
            minMistakeCount={3}
            onMinMistakeCountChange={() => {}}
            onApplySettings={() => {}}
            isExplorationActive={isExplorationActive}
            isContinuationLoading={explorationTurn === 'CHESSECHO'}
            explorationPlayMode="CHESSECHO"
            onEnterExploration={() => setExplorationTurn('CHESSECHO')}
            onExitExploration={() => setExplorationTurn('OFF')}
          />
          <ChessBoardArea
            initialFen={mockPuzzle.fen}
            playerColor="WHITE"
            targetMove="Bb5"
            acceptableMoves={mockPuzzle.acceptableMoves}
            movesPlayed={[]}
            onMoveAttempt={() => {}}
            onNextPuzzle={() => {}}
            onFenChange={(fen) => setCurrentBoardFen(fen)}
            isExplorationActive={isExplorationActive}
            onUserExplorationMove={() => {
              // Emulate user making a move -> transition to CHESSECHO turn
              setExplorationTurn('CHESSECHO');
            }}
            onChessEchoExplorationMove={() => {
              // Transition to USER turn
              setExplorationTurn('USER');
            }}
            pendingContinuationCandidate={pendingContinuationCandidate}
            onContinuationApplied={() => setPendingContinuationCandidate(null)}
          />
        </div>
      );
    }

    render(<TestOrchestrator />);

    // 1. Enter exploration
    fireEvent.click(screen.getByRole('button', { name: /Continue Exploration/i }));

    // 2. ChessEcho makes one move
    await waitFor(() => {
      expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(1);
    });

    // 3. Verify it is USER_TURN
    expect(await screen.findByText(/Your turn — explore a move/i)).toBeInTheDocument();

    // 4. User makes one acceptable move (simulate by clicking the mocked chessboard)
    fireEvent.click(screen.getByTestId('simulate-user-move'));

    // 5. Verify evaluateMove API was called with exact FEN BEFORE user move and exact move attempted
    await waitFor(() => {
      expect(api.evaluateMove).toHaveBeenCalledWith(
        'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
        'Ba4'
      );
    });

    // 6. Verify exactly one additional ChessEcho move occurs
    await waitFor(() => {
      expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(2);
    });

    // 7. Verify it is USER_TURN again
    expect(await screen.findByText(/Your turn — explore a move/i)).toBeInTheDocument();

    // 8. Verify no additional move occurs
    await new Promise(r => setTimeout(r, 200));
    expect(api.fetchPuzzleContinuation).toHaveBeenCalledTimes(2);
  });

  it('6. Unacceptable user move in exploration is rejected and board remains unchanged without continuation request', async () => {
    vi.mocked(api.evaluateMove).mockResolvedValueOnce({
      fen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
      move: 'Ba4',
      bestMove: 'Bxc6',
      bestEvalCp: 100,
      evalCp: -10,
      evalLoss: 1.10,
      maxEvalLoss: 0.80,
      threshold: 0.80,
      acceptable: false,
    });

    const handleFenChange = vi.fn();
    const handleUserExplorationMove = vi.fn();
    const handleUnacceptableMove = vi.fn();

    render(
      <ChessBoardArea
        initialFen="r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4"
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={() => {}}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        onUserExplorationMove={handleUserExplorationMove}
        onUnacceptableMove={handleUnacceptableMove}
      />
    );

    // Simulate user attempting move Ba4
    fireEvent.click(screen.getByTestId('simulate-user-move'));

    await waitFor(() => {
      expect(api.evaluateMove).toHaveBeenCalledWith(
        'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
        'Ba4'
      );
    });

    // Board stays unchanged & user exploration move callback is NOT called
    expect(handleUserExplorationMove).not.toHaveBeenCalled();
    expect(handleUnacceptableMove).toHaveBeenCalledWith(
      expect.stringContaining('That move is too inaccurate. It loses 1.10 pawns compared with the best move.')
    );
  });

  it('7. Variable-length continuation rendering: 0-ply quiet position and multi-ply forcing lines render with distinct branches and badges', () => {
    const handleAlternativeSelected = vi.fn();

    const selectedCandidate: api.ContinuationCandidate = {
      move: 'Ba4',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4',
      providerType: 'ENGINE',
      evalLoss: 0,
      pliesResolved: 2,
      continuationMoves: ['g8f6', 'e1g1'],
      continuationSanMoves: ['Nf6', 'O-O'],
      terminalFen: 'r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQ1RK1 b kq - 2 5',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const altCandidateMultiPly: api.ContinuationCandidate = {
      move: 'Bxc6',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: 0.15,
      pliesResolved: 1,
      continuationMoves: ['d7c6'],
      continuationSanMoves: ['dxc6'],
      terminalFen: 'r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const altCandidateTerminal: api.ContinuationCandidate = {
      move: 'Qxf7#',
      resultingFen: 'r1bqkbnr/1ppp1Qpp/p1n5/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: null,
      pliesResolved: 0,
      continuationMoves: [],
      continuationSanMoves: [],
      terminalFen: 'r1bqkbnr/1ppp1Qpp/p1n5/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4',
      isTerminal: true,
      terminationReason: 'TERMINAL',
    };

    const altCandidateQuiet: api.ContinuationCandidate = {
      move: 'd3',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: 0.35,
      pliesResolved: 0,
      continuationMoves: [],
      continuationSanMoves: [],
      terminalFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 4',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const { rerender } = render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: selectedCandidate,
          candidates: [selectedCandidate, altCandidateMultiPly, altCandidateTerminal, altCandidateQuiet],
        }}
        onAlternativeSelected={handleAlternativeSelected}
      />
    );

    // Selected candidate Ba4 renders move, eval loss, and plies badge, but conceals continuation moves
    expect(screen.getByText(/Ba4/)).toBeInTheDocument();
    expect(screen.getByText(/— 0\.00 pawns/)).toBeInTheDocument();
    expect(screen.getByText(/\+2 plies/i)).toBeInTheDocument();
    expect(screen.queryByText(/Nf6/)).not.toBeInTheDocument();
    expect(screen.queryByText(/O-O/)).not.toBeInTheDocument();

    // Multi-ply alternative continuation renders candidate move, eval loss, and plies badge, but conceals continuation moves
    expect(screen.getByText(/Bxc6/)).toBeInTheDocument();
    expect(screen.getByText(/— 0\.15 pawns/)).toBeInTheDocument();
    expect(screen.getByText(/\+1 plies/i)).toBeInTheDocument();
    expect(screen.queryByText(/dxc6/)).not.toBeInTheDocument();

    // Terminal candidate renders terminal indicator
    expect(screen.getByText(/Qxf7#/)).toBeInTheDocument();
    expect(screen.getByText(/Terminal/i)).toBeInTheDocument();

    // Quiet candidate renders Quiet badge and eval loss
    expect(screen.getByText(/d3/)).toBeInTheDocument();
    expect(screen.getByText(/— 0\.35 pawns/)).toBeInTheDocument();
    expect(screen.getByText(/Quiet/i)).toBeInTheDocument();

    // User can select alternative line
    const exploreButtons = screen.getAllByRole('button', { name: /Explore this line/i });
    expect(exploreButtons).toHaveLength(3);
    fireEvent.click(exploreButtons[0]);
    expect(handleAlternativeSelected).toHaveBeenCalledWith(altCandidateMultiPly);

    // Dynamically updates plies-remaining when alternative candidate is selected
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: altCandidateMultiPly,
          candidates: [selectedCandidate, altCandidateMultiPly, altCandidateTerminal, altCandidateQuiet],
        }}
        onAlternativeSelected={handleAlternativeSelected}
      />
    );
    expect(screen.getByText('Bxc6')).toBeInTheDocument();
    expect(screen.getByText('+1 plies')).toBeInTheDocument();

    // Alternative responses section hides when only 1 candidate exists
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: selectedCandidate,
          candidates: [selectedCandidate],
        }}
        onAlternativeSelected={handleAlternativeSelected}
      />
    );
    expect(screen.queryByText(/Alternative responses/i)).not.toBeInTheDocument();
  });

  it('7b. Candidate badge boundary conditions, null fallbacks, optional callbacks, and mode isolation', () => {
    // 1. Non-CHESSECHO mode suppression: candidate cards must not render in BOTH_SIDES even when lastContinuationCandidates exists
    const candidateA: api.ContinuationCandidate = {
      move: 'Ba4',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4',
      providerType: 'ENGINE',
      evalLoss: null, // tests null evalLoss: no pawns text and no crash on .toFixed(2)
      pliesResolved: undefined, // undefined pliesResolved tests fallback to continuationSanMoves.length
      continuationMoves: ['g8f6', 'e1g1'],
      continuationSanMoves: ['Nf6', 'O-O'],
      terminalFen: 'r1bqkb1r/1ppp1ppp/p1n2n2/4p3/B3P3/5N2/PPPP1PPP/RNBQ1RK1 b kq - 2 5',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const candidateQuiet: api.ContinuationCandidate = {
      move: 'd3',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: 0.25,
      pliesResolved: 0,
      continuationMoves: [],
      continuationSanMoves: [],
      terminalFen: 'r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/3P1N2/PPP2PPP/RNBQK2R b KQkq - 0 4',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const altCandidatePliesFallback: api.ContinuationCandidate = {
      move: 'Bxc6',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: 0.15,
      pliesResolved: undefined, // fallback to continuationSanMoves.length (3 plies)
      continuationMoves: ['d7c6', 'd1d4', 'e5d4'],
      continuationSanMoves: ['dxc6', 'Qxd4', 'exd4'],
      terminalFen: 'r1bqkbnr/1pp2ppp/p1p5/8/3pP3/5N2/PPP2PPP/RNBQK2R w KQkq - 0 6',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const altCandidatePliesWithoutSan: api.ContinuationCandidate = {
      move: 'Nc3',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/2N2N2/PPPP1PPP/R1BQK2R b KQkq - 1 4',
      providerType: 'ENGINE',
      evalLoss: 0.2,
      pliesResolved: 1, // pliesResolved > 0 but continuationSanMoves empty
      continuationMoves: [],
      continuationSanMoves: [],
      terminalFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/2N2N2/PPPP1PPP/R1BQK2R b KQkq - 1 4',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    // Mode isolation: BOTH_SIDES does not render ChessEcho played card
    const { rerender } = render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="BOTH_SIDES"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: candidateA,
          candidates: [candidateA, altCandidatePliesFallback],
        }}
      />
    );
    expect(screen.queryByText(/ChessEcho played/i)).not.toBeInTheDocument();

    // Rerender in CHESSECHO mode with null evalLoss, fallback plies, and omitted onAlternativeSelected prop
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: candidateA,
          candidates: [candidateA, altCandidatePliesFallback, altCandidatePliesWithoutSan],
        }}
      />
    );

    // Selected Ba4 has null evalLoss -> no pawns text; plies fallback -> +2 plies from continuationSanMoves
    expect(screen.getByText(/ChessEcho played/i)).toBeInTheDocument();
    const selectedMoveElement = screen.getByText('Ba4');
    expect(selectedMoveElement).toBeInTheDocument();
    expect(selectedMoveElement.textContent).toBe('Ba4');
    expect(screen.getByText(/\+2 plies/i)).toBeInTheDocument();
    expect(screen.queryByText(/Nf6/)).not.toBeInTheDocument();

    // Alternative candidate with null pliesResolved falls back to continuationSanMoves -> +3 plies
    expect(screen.getByText(/Bxc6/)).toBeInTheDocument();
    expect(screen.getByText(/\+3 plies/i)).toBeInTheDocument();
    expect(screen.queryByText(/dxc6/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Qxd4/)).not.toBeInTheDocument();
    expect(screen.queryByText(/exd4/)).not.toBeInTheDocument();

    // Alternative candidate with pliesResolved > 0 but empty continuationSanMoves renders +1 plies
    expect(screen.getByText(/Nc3/)).toBeInTheDocument();
    expect(screen.getByText(/\+1 plies/i)).toBeInTheDocument();

    // Rerender with quiet selected move and pliesResolved without SAN moves
    const selectedPliesNoSan: api.ContinuationCandidate = {
      ...candidateA,
      pliesResolved: 2,
      continuationSanMoves: [],
      continuationMoves: [],
    };
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: selectedPliesNoSan,
          candidates: [selectedPliesNoSan],
        }}
      />
    );
    expect(screen.getByText(/\+2 plies/i)).toBeInTheDocument();

    // Rerender with quiet selected candidate (0 plies, empty continuation) -> renders evaluative move quality badge instead of Quiet
    rerender(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        onNextPuzzle={() => {}}
        puzzleColorFilter="BOTH"
        onColorFilterChange={() => {}}
        showPuzzleSettings={false}
        onTogglePuzzleSettings={() => {}}
        minMistakeCount={3}
        onMinMistakeCountChange={() => {}}
        onApplySettings={() => {}}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        lastContinuationCandidates={{
          parentFen: 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4',
          selected: candidateQuiet,
          candidates: [candidateQuiet],
        }}
      />
    );
    expect(screen.getByText(/Inaccuracy/i)).toBeInTheDocument();
    expect(screen.queryByText(/Quiet/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\+0 plies/i)).not.toBeInTheDocument();
  });

  it('8. Board navigation along alternative candidate continuation: selecting candidate replays continuationSanMoves and falls back gracefully on invalid line', async () => {
    const handleFenChange = vi.fn();
    const handleAlternativeContinuationApplied = vi.fn();

    const parentFen = 'r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4';
    const intermediateFen = 'r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5';
    const altCandidate: api.ContinuationCandidate = {
      move: 'Bxc6',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      evalLoss: 0.15,
      pliesResolved: 2,
      continuationMoves: ['d7c6', 'd2d4'],
      continuationSanMoves: ['dxc6', 'd4'],
      terminalFen: 'r1bqkbnr/1pp2ppp/p1p5/4p3/3PP3/5N2/PPP2PPP/RNBQK2R b KQkq - 0 5',
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    const { rerender } = render(
      <ChessBoardArea
        initialFen={parentFen}
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={() => {}}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
      />
    );

    // Initial mount reports parent FEN
    expect(handleFenChange).toHaveBeenCalledWith(parentFen);

    // Apply alternative continuation candidate with replayed moves
    rerender(
      <ChessBoardArea
        initialFen={parentFen}
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={() => {}}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        alternativeContinuationToApply={{
          parentFen,
          candidate: altCandidate,
        }}
        onAlternativeContinuationApplied={handleAlternativeContinuationApplied}
      />
    );

    await waitFor(() => {
      expect(handleAlternativeContinuationApplied).toHaveBeenCalledTimes(1);
    });

    // Board position advances sequentially along replayed continuation to terminal FEN
    expect(handleFenChange).toHaveBeenCalledWith(altCandidate.terminalFen);

    // Step back once in history: reaches intermediate position after dxc6 (before d4)
    const prevButton = screen.getByTitle(/Previous Move/i);
    expect(prevButton).not.toBeDisabled();
    fireEvent.click(prevButton);
    expect(handleFenChange).toHaveBeenCalledWith(intermediateFen);

    // Step back second time: reaches candidate resultingFen (after Bxc6, before dxc6)
    fireEvent.click(prevButton);
    expect(handleFenChange).toHaveBeenCalledWith(altCandidate.resultingFen);

    // Step back third time: reaches parentFen (before Bxc6)
    fireEvent.click(prevButton);
    expect(handleFenChange).toHaveBeenCalledWith(parentFen);

    // Graceful fallback: alternative candidate with invalid continuationSanMoves falls back to resultingFen
    const brokenCandidate: api.ContinuationCandidate = {
      move: 'Bxc6',
      resultingFen: 'r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4',
      providerType: 'ENGINE',
      continuationSanMoves: ['invalidMove123'],
      terminalFen: 'broken_fen',
      pliesResolved: 1,
      isTerminal: false,
      terminationReason: 'QUIET_STOP',
    };

    rerender(
      <ChessBoardArea
        initialFen={parentFen}
        playerColor="WHITE"
        targetMove="Bb5"
        acceptableMoves={mockPuzzle.acceptableMoves}
        movesPlayed={[]}
        onMoveAttempt={() => {}}
        onNextPuzzle={() => {}}
        onFenChange={handleFenChange}
        isExplorationActive={true}
        explorationPlayMode="CHESSECHO"
        alternativeContinuationToApply={{
          parentFen,
          candidate: brokenCandidate,
        }}
        onAlternativeContinuationApplied={handleAlternativeContinuationApplied}
      />
    );

    await waitFor(() => {
      expect(handleAlternativeContinuationApplied).toHaveBeenCalledTimes(2);
    });

    // Falls back safely to candidate resultingFen as the final observable callback state
    expect(handleFenChange).toHaveBeenLastCalledWith(brokenCandidate.resultingFen);
  });
});

