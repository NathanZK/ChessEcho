'use client';

import React, { useState, useEffect, useLayoutEffect, useRef } from 'react';
import { Chess } from 'chess.js';
import { Chessboard } from 'react-chessboard';
import { BoardControls } from './BoardControls';
import {
  BOARD_CONTAINER_CLASS,
  BOARD_DARK_SQUARE_STYLE,
  BOARD_FRAME_CLASS,
  BOARD_LIGHT_SQUARE_STYLE,
} from './boardPresentation';
import { playSound } from '@/services/soundService';
import { moveEvaluationService } from '@/services/continuationService';
import { ContinuationCandidate, ExplorationPlayMode } from '@/services/api';
import {
  applyChessMoveOrUseFen,
  cloneChessGameWithHistory,
  createChessGameAtPosition,
} from '@/utils/chessGame';

export const CHALLENGE_MAX_EVAL_LOSS = 0.20;

interface ChessBoardAreaProps {
  initialDecisionEnabled?: boolean;
  blindfoldMode?: boolean;
  initialFen: string;
  initialMoveHistorySan?: string[];
  initialMoveHistoryStartFen?: string;
  showPuzzleControls?: boolean;
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
  onMoveEvaluationPendingChange?: (pending: boolean) => void;
}

export const ChessBoardArea: React.FC<ChessBoardAreaProps> = ({
  initialDecisionEnabled = true,
  blindfoldMode = false,
  initialFen,
  initialMoveHistorySan = [],
  initialMoveHistoryStartFen,
  showPuzzleControls = true,
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
  onMoveEvaluationPendingChange,
}) => {
  const [game, setGame] = useState<Chess>(() =>
    createChessGameAtPosition(initialFen, initialMoveHistorySan, initialMoveHistoryStartFen)
  );
  const [gameHistory, setGameHistory] = useState<Chess[]>(() => [
    createChessGameAtPosition(initialFen, initialMoveHistorySan, initialMoveHistoryStartFen),
  ]);
  const currentBoardFenRef = useRef<string>(initialFen);
  const [fenHistory, setFenHistory] = useState<string[]>([initialFen]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);
  const [customSquareStyles, setCustomSquareStyles] = useState<Record<string, React.CSSProperties>>({});
  const pendingEvaluationsRef = useRef(0);

  // Commit-phase mirror of the board position used by the stale-evaluation guard
  useEffect(() => {
    currentBoardFenRef.current = game.fen();
  }, [game]);

  // Latest-callback refs, declared before every consumer
  const onFenChangeRef = useRef(onFenChange);
  const onChessEchoExplorationMoveRef = useRef(onChessEchoExplorationMove);
  const onContinuationAppliedRef = useRef(onContinuationApplied);
  const onAlternativeContinuationAppliedRef = useRef(onAlternativeContinuationApplied);
  const onMoveEvaluationPendingChangeRef = useRef(onMoveEvaluationPendingChange);
  const blindfoldModeRef = useRef(blindfoldMode);
  useLayoutEffect(() => {
    onMoveEvaluationPendingChangeRef.current = onMoveEvaluationPendingChange;
    blindfoldModeRef.current = blindfoldMode;
    onFenChangeRef.current = onFenChange;
    onChessEchoExplorationMoveRef.current = onChessEchoExplorationMove;
    onContinuationAppliedRef.current = onContinuationApplied;
    onAlternativeContinuationAppliedRef.current = onAlternativeContinuationApplied;
  });

  // Reset board state whenever initialFen changes
  const [fenNotification, setFenNotification] = useState<{ fen: string }>({ fen: initialFen });
  const initialPositionKey = `${initialFen}\u0000${initialMoveHistoryStartFen ?? ''}\u0000${initialMoveHistorySan.join('\u0000')}`;
  const [trackedInitialPositionKey, setTrackedInitialPositionKey] = useState(initialPositionKey);
  if (trackedInitialPositionKey !== initialPositionKey) {
    const initialGame = createChessGameAtPosition(
      initialFen,
      initialMoveHistorySan,
      initialMoveHistoryStartFen,
    );
    setTrackedInitialPositionKey(initialPositionKey);
    setGame(initialGame);
    setGameHistory([initialGame]);
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
        const nextGame = applyChessMoveOrUseFen(game, pendingContinuationCandidate.move, nextFen);
        setGame(nextGame);

        setFenHistory((prev) => {
          const newHistory = prev.slice(0, historyIndex + 1);
          newHistory.push(nextFen);
          return newHistory;
        });
        setGameHistory((prev) => {
          const newHistory = prev.slice(0, historyIndex + 1);
          newHistory.push(nextGame);
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
          const parentGame = gameHistory[parentIndex] ?? game;
          const nextGame = applyChessMoveOrUseFen(parentGame, candidate.move, nextFen);
          setGame(nextGame);

          setFenHistory((prev) => {
            const newHistory = prev.slice(0, parentIndex + 1);
            newHistory.push(nextFen);
            return newHistory;
          });
          setGameHistory((prev) => {
            const newHistory = prev.slice(0, parentIndex + 1);
            newHistory.push(nextGame);
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
    if (historyIndex === 0 && !initialDecisionEnabled) return false;
    try {
      const gameCopy = cloneChessGameWithHistory(game);
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

        pendingEvaluationsRef.current += 1;
        if (pendingEvaluationsRef.current === 1) onMoveEvaluationPendingChangeRef.current?.(true);

        moveEvaluationService.evaluateMove(currentFen, moveSan).then((res) => {
          // Stale evaluation guard: verify board has not moved or reset while request was in flight
          if (currentBoardFenRef.current !== nextFen) {
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
              setGame(cloneChessGameWithHistory(gameHistory[historyIndex] ?? new Chess(currentFen)));
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
              setGameHistory((prev) => [...prev.slice(0, historyIndex + 1), gameCopy]);
              setHistoryIndex(newHistory.length - 1);
              onFenChange?.(nextFen);
            }
          } else {
            // Fallback if res is null
            playSound('incorrect');
            setGame(cloneChessGameWithHistory(gameHistory[historyIndex] ?? new Chess(currentFen)));
            onUnacceptableMove?.("Evaluation failed. Please try again.");
          }
        }).catch(() => {
          // Stale evaluation guard: verify board has not moved or reset
          if (currentBoardFenRef.current !== nextFen) {
            return;
          }
          // Fallback if network/evaluation fails: accept move
          playSound('move');
          onUnacceptableMove?.(null);
          const newHistory = fenHistory.slice(0, historyIndex + 1);
          newHistory.push(nextFen);
          setFenHistory(newHistory);
          setGameHistory((prev) => [...prev.slice(0, historyIndex + 1), gameCopy]);
          setHistoryIndex(newHistory.length - 1);
          onFenChange?.(nextFen);
          onUserExplorationMove?.(moveSan, nextFen, { isBest: false, loss: 0, evalCp: null, fromFen: currentFen });
        }).finally(() => {
          pendingEvaluationsRef.current -= 1;
          if (pendingEvaluationsRef.current === 0) onMoveEvaluationPendingChangeRef.current?.(false);
        });

        return true;
      }

      setCustomSquareStyles({});
      setGame(gameCopy);

      // Truncate future history if making a move after undo
      const newHistory = fenHistory.slice(0, historyIndex + 1);
      newHistory.push(gameCopy.fen());
      setFenHistory(newHistory);
      setGameHistory((prev) => [...prev.slice(0, historyIndex + 1), gameCopy]);
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
      setGame(cloneChessGameWithHistory(gameHistory[prevIndex] ?? new Chess(prevFen)));
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
      setGame(cloneChessGameWithHistory(gameHistory[nextIndex] ?? new Chess(nextFen)));
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
      if (blindfoldModeRef.current) return;

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
    const resetGame = createChessGameAtPosition(
      initialFen,
      initialMoveHistorySan,
      initialMoveHistoryStartFen,
    );
    currentBoardFenRef.current = initialFen;
    setGame(resetGame);
    setGameHistory([resetGame]);
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

  if (blindfoldMode) return null;

  return (
    <div className={BOARD_CONTAINER_CLASS}>
      <div className={BOARD_FRAME_CLASS}>
        <Chessboard
          options={{
            position: game.fen(),
            boardOrientation: orientation,
            darkSquareStyle: BOARD_DARK_SQUARE_STYLE,
            lightSquareStyle: BOARD_LIGHT_SQUARE_STYLE,
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
        showPuzzleControls={showPuzzleControls}
        onFlipBoard={onFlipBoard}
        soundEnabled={soundEnabled}
        onToggleSound={onToggleSound}
      />
    </div>
  );
};
