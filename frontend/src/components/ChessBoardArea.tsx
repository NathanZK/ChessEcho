'use client';

import React, { useState, useEffect, useLayoutEffect, useRef } from 'react';
import { Chess } from 'chess.js';
import { Chessboard } from 'react-chessboard';
import { BoardControls } from './BoardControls';
import { playSound } from '@/services/soundService';
import { moveEvaluationService } from '@/services/continuationService';
import { ContinuationCandidate, ExplorationPlayMode } from '@/services/api';

export const CHALLENGE_MAX_EVAL_LOSS = 0.20;

interface ChessBoardAreaProps {
  initialFen: string;
  playerColor: 'WHITE' | 'BLACK';
  boardOrientation?: 'white' | 'black';
  targetMove: string;
  acceptableMoves: Array<{ move: string; evalLoss: number }>;
  movesPlayed: Array<{ move: string; timesPlayed: number; averageLoss: number }>;
  onMoveAttempt: (
    moveSan: string,
    isCorrect: boolean,
    isHistoricalMistake: boolean,
    historicalInfo?: { timesPlayed: number; averageLoss: number },
    isInitialDecision?: boolean
  ) => void;
  onPreviousPuzzle?: () => void;
  onNextPuzzle: () => void;
  onUndo?: () => void;
  onRedo?: () => void;
  hintSquare?: string;
  canHint?: boolean;
  onFlipBoard?: () => void;
  soundEnabled?: boolean;
  onToggleSound?: () => void;
  onFenChange?: (fen: string) => void;
  pendingContinuationCandidate?: ContinuationCandidate | null;
  onContinuationApplied?: () => void;
  isExplorationActive?: boolean;
  onUnacceptableMove?: (message?: string | null) => void;
  onUserExplorationMove?: (
    moveSan: string,
    nextFen: string,
    feedback?: { isBest: boolean; loss: number; evalCp?: number | null; fromFen?: string; bestMove?: string }
  ) => void;
  onChessEchoExplorationMove?: (moveSan: string) => void;
  onReset?: () => void;
  alternativeContinuationToApply?: { parentFen: string; candidate: ContinuationCandidate } | null;
  onAlternativeContinuationApplied?: () => void;
  explorationPlayMode?: ExplorationPlayMode;
  isChallengeComplete?: boolean;
}

export const ChessBoardArea: React.FC<ChessBoardAreaProps> = ({
  initialFen,
  playerColor,
  boardOrientation,
  targetMove,
  acceptableMoves,
  movesPlayed,
  onMoveAttempt,
  onPreviousPuzzle,
  onNextPuzzle,
  onUndo,
  onRedo,
  hintSquare,
  canHint = true,
  onFlipBoard,
  soundEnabled,
  onToggleSound,
  onFenChange,
  pendingContinuationCandidate,
  onContinuationApplied,
  isExplorationActive = false,
  onUnacceptableMove,
  onUserExplorationMove,
  onChessEchoExplorationMove,
  onReset,
  alternativeContinuationToApply,
  onAlternativeContinuationApplied,
  explorationPlayMode = 'CHESSECHO',
  isChallengeComplete = false,
}) => {
  const [game, setGame] = useState<Chess>(new Chess(initialFen));
  const currentBoardFenRef = useRef<string>(initialFen);
  const evaluationGenerationRef = useRef(0);
  const [fenHistory, setFenHistory] = useState<string[]>([initialFen]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);
  const [customSquareStyles, setCustomSquareStyles] = useState<Record<string, React.CSSProperties>>({});

  // Commit-phase mirror of the board position used by the stale-evaluation guard
  useEffect(() => {
    currentBoardFenRef.current = game.fen();
  }, [game]);

  useLayoutEffect(() => {
    const evaluationGeneration = evaluationGenerationRef;
    evaluationGeneration.current++;
    return () => {
      evaluationGeneration.current++;
    };
  }, [initialFen]);

  // Latest-callback refs, declared before every consumer
  const onFenChangeRef = useRef(onFenChange);
  const onChessEchoExplorationMoveRef = useRef(onChessEchoExplorationMove);
  const onContinuationAppliedRef = useRef(onContinuationApplied);
  const onAlternativeContinuationAppliedRef = useRef(onAlternativeContinuationApplied);
  useLayoutEffect(() => {
    onFenChangeRef.current = onFenChange;
    onChessEchoExplorationMoveRef.current = onChessEchoExplorationMove;
    onContinuationAppliedRef.current = onContinuationApplied;
    onAlternativeContinuationAppliedRef.current = onAlternativeContinuationApplied;
  });

  // Reset board state whenever initialFen changes
  const [fenNotification, setFenNotification] = useState<{ fen: string }>({ fen: initialFen });
  const [trackedInitialFen, setTrackedInitialFen] = useState(initialFen);
  if (trackedInitialFen !== initialFen) {
    setTrackedInitialFen(initialFen);
    setGame(new Chess(initialFen));
    setFenHistory([initialFen]);
    setHistoryIndex(0);
    setCustomSquareStyles({});
    setFenNotification({ fen: initialFen });
  }

  // Delivered in the same commit as the render that produced the position, so a
  // deferred notification can never overwrite a newer position with a stale one.
  useLayoutEffect(() => {
    onFenChangeRef.current?.(fenNotification.fen);
  }, [fenNotification]);

  // Apply hint square highlight if hint is triggered
  const [trackedHint, setTrackedHint] = useState<{ value: typeof hintSquare } | null>(null);
  if (!trackedHint || trackedHint.value !== hintSquare) {
    setTrackedHint({ value: hintSquare });
    if (hintSquare) {
      setCustomSquareStyles({
        [hintSquare]: {
          backgroundColor: 'rgba(245, 158, 11, 0.5)',
          borderRadius: '50%',
        },
      });
    } else {
      setCustomSquareStyles({});
    }
  }

  type BoardEffectuation =
    | { kind: 'applied'; fen: string; move?: string; source: 'pending' | 'alternative' }
    | { kind: 'failed'; error: unknown; source: 'pending' | 'alternative' };

  const [effectuation, setEffectuation] = useState<BoardEffectuation | null>(null);

  // Apply continuation candidate move when pendingContinuationCandidate changes
  const [trackedPending, setTrackedPending] = useState<{ value: typeof pendingContinuationCandidate } | null>(null);
  if (!trackedPending || trackedPending.value !== pendingContinuationCandidate) {
    setTrackedPending({ value: pendingContinuationCandidate });
    if (isExplorationActive && pendingContinuationCandidate?.resultingFen) {
      const nextFen = pendingContinuationCandidate.resultingFen;
      try {
        setGame(new Chess(nextFen));

        setFenHistory((prev) => {
          const newHistory = prev.slice(0, historyIndex + 1);
          newHistory.push(nextFen);
          return newHistory;
        });
        setHistoryIndex((prev) => prev + 1);

        setEffectuation({
          kind: 'applied',
          fen: nextFen,
          move: pendingContinuationCandidate.move,
          source: 'pending',
        });
      } catch (e) {
        setEffectuation({ kind: 'failed', error: e, source: 'pending' });
      }
    }
  }

  // Apply alternative continuation candidate (replaces the current ChessEcho move)
  const [trackedAlternative, setTrackedAlternative] = useState<{ value: typeof alternativeContinuationToApply } | null>(null);
  if (!trackedAlternative || trackedAlternative.value !== alternativeContinuationToApply) {
    setTrackedAlternative({ value: alternativeContinuationToApply });
    if (isExplorationActive && alternativeContinuationToApply) {
      const { parentFen, candidate } = alternativeContinuationToApply;

      // Find parentFen strictly in fenHistory using exact match
      const parentIndex = fenHistory.findIndex(f => f === parentFen);
      if (parentIndex !== -1) {
        try {
          const nextFen = candidate.resultingFen;
          setGame(new Chess(nextFen));

          setFenHistory((prev) => {
            const newHistory = prev.slice(0, parentIndex + 1);
            newHistory.push(nextFen);
            return newHistory;
          });
          setHistoryIndex(parentIndex + 1);

          setEffectuation({
            kind: 'applied',
            fen: nextFen,
            move: candidate.move,
            source: 'alternative',
          });
        } catch (e) {
          setEffectuation({ kind: 'failed', error: e, source: 'alternative' });
        }
      }
    }
  }

  // Replay the side effects of an applied (or failed) candidate after it is committed
  useEffect(() => {
    if (!effectuation) return;
    const isPending = effectuation.source === 'pending';
    const message = isPending
      ? 'Failed to apply continuation candidate resultingFen:'
      : 'Failed to apply alternative candidate:';
    try {
      if (effectuation.kind === 'failed') {
        console.error(message, effectuation.error);
        return;
      }

      playSound('move');
      onFenChangeRef.current?.(effectuation.fen);

      if (effectuation.move && onChessEchoExplorationMoveRef.current) {
        onChessEchoExplorationMoveRef.current(effectuation.move);
      }
    } catch (e) {
      console.error(message, e);
    } finally {
      if (isPending) {
        onContinuationAppliedRef.current?.();
      } else {
        onAlternativeContinuationAppliedRef.current?.();
      }
    }
  }, [effectuation]);



  const handlePieceDrop = (sourceSquare: string, targetSquare: string): boolean => {
    try {
      const gameCopy = new Chess(game.fen());
      const move = gameCopy.move({
        from: sourceSquare,
        to: targetSquare,
        promotion: 'q',
      });

      if (!move) return false; // Illegal chess move

      const moveSan = move.san;

      const isInitialDecision = historyIndex === 0;

      // Active Exploration User Move Evaluation
      if (isExplorationActive) {
        if (explorationPlayMode === 'CHALLENGE' && !isChallengeComplete) {
          return false;
        }

        const currentFen = game.fen();
        const nextFen = gameCopy.fen();

        setCustomSquareStyles({});
        currentBoardFenRef.current = nextFen;
        setGame(gameCopy);
        const evaluationGeneration = ++evaluationGenerationRef.current;

        moveEvaluationService.evaluateMove(currentFen, moveSan).then((res) => {
          if (
            evaluationGenerationRef.current !== evaluationGeneration ||
            currentBoardFenRef.current !== nextFen
          ) {
            return;
          }

          const isWhiteTurn = currentFen.split(' ')[1] === 'w';
          const isUserWhite = playerColor === 'WHITE';
          const isOpponentTurn = isWhiteTurn !== isUserWhite;
          const shouldUseStrictThreshold = isOpponentTurn && explorationPlayMode === 'BOTH_SIDES';

          if (res) {
            let acceptable = res.acceptable;
            const lossCp = res.evalLoss ?? 0;
            const evalCp = res.evalCp ?? null;
            let errorMessage: string | null = null;

            if (shouldUseStrictThreshold) {
              const OPPONENT_MOVE_MAX_EVAL_LOSS = 0.20;
              if (lossCp <= OPPONENT_MOVE_MAX_EVAL_LOSS) {
                acceptable = true;
              } else {
                acceptable = false;
                if (res.acceptable) {
                  errorMessage = `Good response, but there's a stronger move. This move loses ${lossCp.toFixed(2)} pawns. Keep looking.`;
                } else {
                  errorMessage = `That move is too inaccurate. It loses ${lossCp.toFixed(2)} pawns compared with the best move.`;
                }
              }
            } else {
              if (!acceptable) {
                errorMessage = `That move is too inaccurate. It loses ${lossCp.toFixed(2)} pawns compared with the best move.`;
              }
            }

            if (!acceptable) {
              playSound('incorrect');
              setGame(new Chess(currentFen)); // Revert board to position before move
              onUnacceptableMove?.(errorMessage);
            } else {
              if (lossCp === 0) {
                playSound('completion');
                onUserExplorationMove?.(moveSan, nextFen, { isBest: true, loss: 0, evalCp, fromFen: currentFen, bestMove: res.bestMove });
              } else {
                playSound('correct');
                onUserExplorationMove?.(moveSan, nextFen, { isBest: false, loss: lossCp, evalCp, fromFen: currentFen, bestMove: res.bestMove });
              }
              onUnacceptableMove?.(null);
              const newHistory = fenHistory.slice(0, historyIndex + 1);
              newHistory.push(nextFen);
              setFenHistory(newHistory);
              setHistoryIndex(newHistory.length - 1);
              onFenChange?.(nextFen);
            }
          } else {
            // Fallback if res is null
            playSound('incorrect');
            setGame(new Chess(currentFen)); // Revert board to position before move
            onUnacceptableMove?.("Evaluation failed. Please try again.");
          }
        }).catch(() => {
          if (
            evaluationGenerationRef.current !== evaluationGeneration ||
            currentBoardFenRef.current !== nextFen
          ) {
            return;
          }
          // Fallback if network/evaluation fails: accept move
          playSound('move');
          onUnacceptableMove?.(null);
          const newHistory = fenHistory.slice(0, historyIndex + 1);
          newHistory.push(nextFen);
          setFenHistory(newHistory);
          setHistoryIndex(newHistory.length - 1);
          onFenChange?.(nextFen);
          onUserExplorationMove?.(moveSan, nextFen, { isBest: false, loss: 0, evalCp: null, fromFen: currentFen });
        });

        return true;
      }

      setCustomSquareStyles({});
      setGame(gameCopy);

      // Truncate future history if making a move after undo
      const newHistory = fenHistory.slice(0, historyIndex + 1);
      newHistory.push(gameCopy.fen());
      setFenHistory(newHistory);
      setHistoryIndex(newHistory.length - 1);
      onFenChange?.(gameCopy.fen());

      if (isInitialDecision) {
        // Check if move matches best move or acceptable moves
        const isBest = moveSan === targetMove;
        const isAcceptable = acceptableMoves.some((m) => m.move === moveSan);
        const isCorrect = isBest || isAcceptable;

        // Check if move matches a historical mistake played by the user
        const historicalMistake = movesPlayed.find((m) => m.move === moveSan);

        // Sound feedback for initial puzzle decision
        if (isBest) {
          playSound('completion');
        } else if (isAcceptable) {
          playSound('correct');
        } else {
          playSound('incorrect');
        }

        onMoveAttempt(
          moveSan,
          isCorrect,
          !!historicalMistake,
          historicalMistake
            ? {
                timesPlayed: historicalMistake.timesPlayed,
                averageLoss: historicalMistake.averageLoss,
              }
            : undefined,
          true
        );
      } else {
        // Opponent move / line continuation: do not evaluate against initial targetMove
        playSound('move');
        onMoveAttempt(moveSan, false, false, undefined, false);
      }

      return true;
    } catch {
      return false;
    }
  };

  const handleUndo = () => {
    setCustomSquareStyles({});
    if (historyIndex > 0) {
      const prevIndex = historyIndex - 1;
      const prevFen = fenHistory[prevIndex];
      currentBoardFenRef.current = prevFen;
      setGame(new Chess(prevFen));
      setHistoryIndex(prevIndex);
      onFenChange?.(prevFen);
      onUndo?.();
    }
  };

  const handleRedo = () => {
    setCustomSquareStyles({});
    if (historyIndex < fenHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      const nextFen = fenHistory[nextIndex];
      currentBoardFenRef.current = nextFen;
      setGame(new Chess(nextFen));
      setHistoryIndex(nextIndex);
      onFenChange?.(nextFen);
      onRedo?.();
    }
  };

  // Laptop arrow key navigation (ArrowLeft = <, ArrowRight = >)
  const handleUndoRef = useRef(handleUndo);
  const handleRedoRef = useRef(handleRedo);
  useEffect(() => {
    handleUndoRef.current = handleUndo;
    handleRedoRef.current = handleRedo;
  });

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't trigger if user is typing in an input field
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;

      if (e.key === 'ArrowLeft') {
        handleUndoRef.current();
      } else if (e.key === 'ArrowRight') {
        handleRedoRef.current();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const handleReset = () => {
    evaluationGenerationRef.current++;
    const resetGame = new Chess(initialFen);
    currentBoardFenRef.current = initialFen;
    setGame(resetGame);
    setFenHistory([initialFen]);
    setHistoryIndex(0);
    setCustomSquareStyles({});
    onFenChange?.(initialFen);
    onReset?.();
  };

  const handleHint = () => {
    // Highlight square of piece that should make the best move
    try {
      const tempGame = new Chess(initialFen);
      const moves = tempGame.moves({ verbose: true });
      const targetMoveVerbose = moves.find((m) => m.san === targetMove);
      if (targetMoveVerbose) {
        setCustomSquareStyles({
          [targetMoveVerbose.from]: {
            backgroundColor: 'rgba(245, 158, 11, 0.65)',
            boxShadow: 'inset 0 0 10px rgba(245, 158, 11, 0.8)',
          },
        });
      }
    } catch {
      // Fallback
    }
  };

  const orientation = boardOrientation ?? (playerColor === 'BLACK' ? 'black' : 'white');

  return (
    <div className="flex flex-col space-y-2.5 w-full max-w-[640px] 2xl:max-w-[680px] mx-auto">
      {/* Chessboard Container with Chess.com Green Theme */}
      <div className="rounded-2xl overflow-hidden shadow-2xl border border-slate-800 bg-slate-900 p-2">
        <Chessboard
          options={{
            position: game.fen() === new Chess(initialFen).fen() ? initialFen : game.fen(),
            boardOrientation: orientation,
            darkSquareStyle: { backgroundColor: '#769656' },
            lightSquareStyle: { backgroundColor: '#eeeed2' },
            squareStyles: customSquareStyles,
            animationDurationInMs: 200,
            allowDragging: true,
            onPieceDrop: ({ sourceSquare, targetSquare }) => {
              if (!targetSquare) return false;
              return handlePieceDrop(sourceSquare, targetSquare);
            },
          }}
        />
      </div>

      {/* Board Navigation Controls */}
      <BoardControls
        onUndo={handleUndo}
        onRedo={handleRedo}
        onReset={handleReset}
        onHint={handleHint}
        onPreviousPuzzle={onPreviousPuzzle || (() => {})}
        onNextPuzzle={onNextPuzzle}
        canUndo={historyIndex > 0}
        canRedo={historyIndex < fenHistory.length - 1}
        canHint={canHint}
        onFlipBoard={onFlipBoard}
        soundEnabled={soundEnabled}
        onToggleSound={onToggleSound}
      />
    </div>
  );
};
