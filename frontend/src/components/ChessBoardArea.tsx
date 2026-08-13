'use client';

import React, { useState, useEffect } from 'react';
import { Chess } from 'chess.js';
import { Chessboard } from 'react-chessboard';
import { BoardControls } from './BoardControls';

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
}) => {
  const [game, setGame] = useState<Chess>(new Chess(initialFen));
  const [fenHistory, setFenHistory] = useState<string[]>([initialFen]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);
  const [customSquareStyles, setCustomSquareStyles] = useState<Record<string, React.CSSProperties>>({});

  // Reset board state whenever initialFen changes
  useEffect(() => {
    const newGame = new Chess(initialFen);
    setGame(newGame);
    setFenHistory([initialFen]);
    setHistoryIndex(0);
    setCustomSquareStyles({});
  }, [initialFen]);

  // Apply hint square highlight if hint is triggered
  useEffect(() => {
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
  }, [hintSquare]);

  const handlePieceDrop = (sourceSquare: string, targetSquare: string): boolean => {
    try {
      const gameCopy = new Chess(game.fen());
      const move = gameCopy.move({
        from: sourceSquare,
        to: targetSquare,
        promotion: 'q',
      });

      if (!move) return false; // Illegal chess move

      setCustomSquareStyles({});
      const moveSan = move.san;
      setGame(gameCopy);

      const isInitialDecision = historyIndex === 0;

      // Truncate future history if making a move after undo
      const newHistory = fenHistory.slice(0, historyIndex + 1);
      newHistory.push(gameCopy.fen());
      setFenHistory(newHistory);
      setHistoryIndex(newHistory.length - 1);

      if (isInitialDecision) {
        // Check if move matches best move or acceptable moves
        const isBest = moveSan === targetMove;
        const isAcceptable = acceptableMoves.some((m) => m.move === moveSan);
        const isCorrect = isBest || isAcceptable;

        // Check if move matches a historical mistake played by the user
        const historicalMistake = movesPlayed.find((m) => m.move === moveSan);

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
      setGame(new Chess(prevFen));
      setHistoryIndex(prevIndex);
      onUndo?.();
    }
  };

  const handleRedo = () => {
    setCustomSquareStyles({});
    if (historyIndex < fenHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      const nextFen = fenHistory[nextIndex];
      setGame(new Chess(nextFen));
      setHistoryIndex(nextIndex);
      onRedo?.();
    }
  };

  // Laptop arrow key navigation (ArrowLeft = <, ArrowRight = >)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't trigger if user is typing in an input field
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;

      if (e.key === 'ArrowLeft') {
        handleUndo();
      } else if (e.key === 'ArrowRight') {
        handleRedo();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [historyIndex, fenHistory]);

  const handleReset = () => {
    const resetGame = new Chess(initialFen);
    setGame(resetGame);
    setFenHistory([initialFen]);
    setHistoryIndex(0);
    setCustomSquareStyles({});
    onUndo?.();
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
            position: game.fen(),
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
      />
    </div>
  );
};
