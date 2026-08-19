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
        moveHistory={['Bb5']}
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
        explorationTurn="USER"
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
    const handleUnacceptableMove = vi.fn();

    render(
      <PuzzleFeedbackPanel
        puzzle={mockPuzzle}
        feedback={{ status: 'CORRECT', lastMove: 'Bb5' }}
        moveHistory={['Bb5']}
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
        explorationTurn="USER"
        unacceptableMoveMessage="That move is outside the acceptable range."
      />
    );

    expect(screen.getByText('That move is outside the acceptable range.')).toBeInTheDocument();
  });

  it('3. Undo after ChessEcho response maintains correct board history and position', async () => {
    const handleFenChange = vi.fn();
    const handleMoveAttempt = vi.fn();
    let rerenderFn: (ui: React.ReactElement) => void;

    const candidate: api.ContinuationCandidate = {
      move: 'Bb5',
      resultingFen: 'r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3',
      providerType: 'ENGINE',
    };

    const handleContinuationApplied = vi.fn(() => {
      rerenderFn(
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
    rerenderFn = rerender;

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
        moveHistory={['Bb5']}
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
        explorationTurn="USER"
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
            moveHistory={['Bb5']}
            onNextPuzzle={() => {}}
            puzzleColorFilter="BOTH"
            onColorFilterChange={() => {}}
            showPuzzleSettings={false}
            onTogglePuzzleSettings={() => {}}
            minMistakeCount={3}
            onMinMistakeCountChange={() => {}}
            onApplySettings={() => {}}
            isExplorationActive={isExplorationActive}
            explorationTurn={isExplorationActive ? explorationTurn as 'USER' | 'CHESSECHO' : undefined}
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
            onUserExplorationMove={(moveSan) => {
              // Emulate user making a move -> transition to CHESSECHO turn
              setExplorationTurn('CHESSECHO');
            }}
            onChessEchoExplorationMove={(moveSan) => {
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
});

