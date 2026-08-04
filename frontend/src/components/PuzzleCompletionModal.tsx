'use client';

import React, { useEffect } from 'react';
import { CheckCircle2, ChevronRight, RotateCcw, Trophy, Flame } from 'lucide-react';
import { Puzzle } from '../mock/mockData';

interface PuzzleCompletionModalProps {
  isOpen: boolean;
  puzzle: Puzzle;
  onNextPuzzle: () => void;
  onPracticeAgain: () => void;
}

export const PuzzleCompletionModal: React.FC<PuzzleCompletionModalProps> = ({
  isOpen,
  puzzle,
  onNextPuzzle,
  onPracticeAgain,
}) => {
  // Listen for Enter or Space keys to quickly advance to next puzzle
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        onNextPuzzle();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onNextPuzzle]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 backdrop-blur-sm p-4 animate-in fade-in duration-200">
      <div className="bg-slate-900 border border-slate-800 rounded-3xl p-6 sm:p-8 max-w-md w-full shadow-2xl space-y-6 text-center text-slate-100">
        {/* Animated Trophy / Check Icon */}
        <div className="relative mx-auto w-16 h-16 rounded-2xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shadow-lg shadow-emerald-950/50">
          <Trophy className="w-8 h-8 text-emerald-400 animate-bounce" />
          <div className="absolute -top-1 -right-1 bg-emerald-500 rounded-full p-0.5">
            <CheckCircle2 className="w-4 h-4 text-slate-950 fill-white" />
          </div>
        </div>

        {/* Title & Description */}
        <div className="space-y-1">
          <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">
            Puzzle Solved!
          </span>
          <h3 className="text-xl font-bold text-white">
            {puzzle.openingTitle}
          </h3>
          <p className="text-xs text-slate-400 pt-1">
            You successfully played <span className="font-bold text-emerald-300">{puzzle.targetMove}</span> and fixed your opening habit!
          </p>
        </div>

        {/* Puzzle Summary Badges */}
        <div className="grid grid-cols-2 gap-3 bg-slate-950 p-3 rounded-2xl border border-slate-800">
          <div className="text-center">
            <span className="text-[10px] text-slate-400 font-semibold">Historical Mistake Rate</span>
            <div className="text-sm font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
              <Flame className="w-3.5 h-3.5 text-amber-500" />
              {puzzle.mistakeRate.toFixed(1)}%
            </div>
          </div>

          <div className="text-center border-l border-slate-800">
            <span className="text-[10px] text-slate-400 font-semibold">Times Reached</span>
            <div className="text-sm font-bold text-slate-200 mt-0.5">
              {puzzle.timesReached} games
            </div>
          </div>
        </div>

        {/* Action Buttons */}
        <div className="space-y-2.5 pt-2">
          <button
            onClick={onNextPuzzle}
            className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-sm rounded-xl transition shadow-lg shadow-emerald-950/60 flex items-center justify-center space-x-2 group"
          >
            <span>Next Puzzle</span>
            <span className="text-[11px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
              Enter ↵
            </span>
            <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
          </button>

          <button
            onClick={onPracticeAgain}
            className="w-full py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-300 font-semibold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>Practice Position Again</span>
          </button>
        </div>
      </div>
    </div>
  );
};
