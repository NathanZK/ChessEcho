'use client';

import React from 'react';
import { ChevronLeft, ChevronRight, RotateCcw, Lightbulb } from 'lucide-react';

interface BoardControlsProps {
  onUndo: () => void;
  onRedo: () => void;
  onReset: () => void;
  onHint: () => void;
  onNextPuzzle: () => void;
  canUndo: boolean;
  canRedo: boolean;
  canHint?: boolean;
}

export const BoardControls: React.FC<BoardControlsProps> = ({
  onUndo,
  onRedo,
  onReset,
  onHint,
  onNextPuzzle,
  canUndo,
  canRedo,
  canHint = true,
}) => {
  return (
    <div className="flex items-center justify-between gap-2 p-2.5 bg-slate-900 rounded-xl border border-slate-800 shadow-md">
      {/* < and > Move Navigation with Keyboard Indicator */}
      <div className="flex items-center space-x-1.5">
        <button
          onClick={onUndo}
          disabled={!canUndo}
          title="Previous Move (Left Arrow ←)"
          className="flex items-center justify-center w-10 h-9 bg-slate-800 hover:bg-slate-700 disabled:opacity-30 text-slate-200 rounded-lg text-base font-bold transition border border-slate-700/60"
        >
          <ChevronLeft className="w-5 h-5" />
        </button>

        <button
          onClick={onRedo}
          disabled={!canRedo}
          title="Next Move (Right Arrow →)"
          className="flex items-center justify-center w-10 h-9 bg-slate-800 hover:bg-slate-700 disabled:opacity-30 text-slate-200 rounded-lg text-base font-bold transition border border-slate-700/60"
        >
          <ChevronRight className="w-5 h-5" />
        </button>

        <button
          onClick={onReset}
          title="Reset Position"
          className="flex items-center space-x-1 px-3 h-9 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg text-xs font-semibold transition border border-slate-700/60"
        >
          <RotateCcw className="w-3.5 h-3.5" />
          <span className="hidden sm:inline">Reset</span>
        </button>
      </div>

      {/* Action Controls: Hint & Next Puzzle */}
      <div className="flex items-center space-x-2">
        <button
          onClick={onHint}
          disabled={!canHint}
          title={canHint ? "Show Move Hint" : "Hint unavailable after puzzle is solved"}
          className="flex items-center space-x-1.5 px-3 h-9 bg-amber-500/20 hover:bg-amber-500/30 disabled:opacity-30 disabled:hover:bg-amber-500/20 text-amber-300 rounded-lg text-xs font-semibold transition border border-amber-500/30 cursor-pointer disabled:cursor-not-allowed"
        >
          <Lightbulb className="w-3.5 h-3.5 text-amber-400" />
          <span>Hint</span>
        </button>

        <button
          onClick={onNextPuzzle}
          className="flex items-center space-x-1.5 px-3.5 h-9 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-bold transition shadow-md shadow-emerald-900/40"
        >
          <span>Next Puzzle</span>
          <ChevronRight className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
};
