'use client';

import React, { useEffect } from 'react';
import { AlertTriangle, CheckCircle2, XCircle, ExternalLink, HelpCircle, Flame, Trophy, ChevronRight } from 'lucide-react';
import { Puzzle } from '../mock/mockData';

interface FeedbackState {
  status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
  lastMove?: string;
  historicalInfo?: { timesPlayed: number; averageLoss: number };
}

interface PuzzleFeedbackPanelProps {
  puzzle: Puzzle;
  feedback: FeedbackState;
  moveHistory: string[];
  onNextPuzzle: () => void;
}

export const PuzzleFeedbackPanel: React.FC<PuzzleFeedbackPanelProps> = ({
  puzzle,
  feedback,
  moveHistory,
  onNextPuzzle,
}) => {
  // Listen for Enter key when puzzle is solved to advance to next puzzle
  useEffect(() => {
    if (feedback.status !== 'CORRECT') return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onNextPuzzle();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [feedback.status, onNextPuzzle]);

  return (
    <div className="flex flex-col space-y-4 bg-slate-900 p-5 rounded-2xl border border-slate-800 shadow-xl text-slate-200">
      {/* Opening Header */}
      <div className="border-b border-slate-800 pb-3">
        <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">
          Target Opening Weakness
        </span>
        <h2 className="text-lg font-bold text-white mt-0.5">
          {puzzle.openingTitle}
        </h2>
      </div>

      {/* Dynamic Feedback / Success Card (Lichess Style - Side Panel) */}
      {feedback.status === 'CORRECT' ? (
        <div className="p-4 bg-gradient-to-br from-emerald-950/80 to-slate-900 border-2 border-emerald-500/50 rounded-2xl space-y-3 shadow-lg shadow-emerald-950/40 animate-in fade-in duration-200">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Trophy className="w-5 h-5 text-emerald-400 animate-bounce" />
            </div>
            <div>
              <h4 className="font-bold text-base text-emerald-200">
                {feedback.lastMove === puzzle.targetMove ? 'Puzzle Solved! 🎉' : 'Good Move! 👍'}
              </h4>
              <p className="text-xs text-emerald-300/90">
                {feedback.lastMove === puzzle.targetMove ? (
                  <>
                    <span className="font-bold text-white">{feedback.lastMove}</span> is the best move!
                  </>
                ) : (
                  <>
                    <span className="font-bold text-white">{feedback.lastMove}</span> is an acceptable alternative! (<span className="font-bold text-white">{puzzle.targetMove}</span> is #1 best)
                  </>
                )}
              </p>
            </div>
          </div>

          <button
            onClick={onNextPuzzle}
            className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/50 flex items-center justify-center space-x-2 group"
          >
            <span>Next Puzzle</span>
            <span className="text-[10px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
              Enter ↵
            </span>
            <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
          </button>
        </div>
      ) : feedback.status === 'EXPLORING' ? (
        <div className="p-4 bg-gradient-to-br from-emerald-950/80 to-slate-900 border-2 border-emerald-500/50 rounded-2xl space-y-3 shadow-lg shadow-emerald-950/40 animate-in fade-in duration-200">
          <div className="flex items-center space-x-3">
            <div className="w-10 h-10 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Trophy className="w-5 h-5 text-emerald-400" />
            </div>
            <div>
              <h4 className="font-bold text-base text-emerald-200">Line Exploration 🔍</h4>
              <p className="text-xs text-emerald-300/90">
                Played <span className="font-bold text-white">{feedback.lastMove}</span>. Examining follow-up moves on the board.
              </p>
            </div>
          </div>

          <button
            onClick={onNextPuzzle}
            className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/50 flex items-center justify-center space-x-2 group"
          >
            <span>Next Puzzle</span>
            <span className="text-[10px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
              Enter ↵
            </span>
            <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
          </button>
        </div>
      ) : feedback.status === 'HISTORICAL_MISTAKE' ? (
        <div className="flex items-start space-x-3 p-4 bg-amber-500/15 border border-amber-500/40 rounded-xl text-amber-300 animate-in fade-in duration-200">
          <AlertTriangle className="w-6 h-6 text-amber-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="font-bold text-sm text-amber-200">Historical Mistake Detected!</h4>
            <p className="text-xs mt-0.5 text-amber-300/90">
              You played <span className="font-bold text-white">{feedback.lastMove}</span> in{' '}
              <span className="font-bold text-white">{feedback.historicalInfo?.timesPlayed} past games</span> (avg loss:{' '}
              <span className="font-bold text-white">{feedback.historicalInfo?.averageLoss.toFixed(2)} pawns</span>). Try{' '}
              <span className="font-bold text-emerald-300">{puzzle.targetMove}</span> instead!
            </p>
          </div>
        </div>
      ) : feedback.status === 'INCORRECT' ? (
        <div className="flex items-start space-x-3 p-4 bg-rose-500/15 border border-rose-500/40 rounded-xl text-rose-300 animate-in fade-in duration-200">
          <XCircle className="w-6 h-6 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="font-bold text-sm text-rose-200">Suboptimal Move</h4>
            <p className="text-xs mt-0.5 text-rose-300/90">
              <span className="font-bold text-white">{feedback.lastMove}</span> is not recommended. Look for control of central squares.
            </p>
          </div>
        </div>
      ) : (
        <div className="flex items-center space-x-3 p-3.5 bg-slate-950/60 border border-slate-800/80 rounded-xl text-slate-400">
          <HelpCircle className="w-5 h-5 text-slate-400 shrink-0" />
          <p className="text-xs">
            Find Black's best move or an acceptable alternative to fix your opening habit.
          </p>
        </div>
      )}

      {/* Stats Pill Badges */}
      <div className="grid grid-cols-2 gap-2 py-1">
        <div className="bg-slate-950 p-2.5 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Mistake Rate</div>
          <div className="text-sm font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
            <Flame className="w-3.5 h-3.5 text-amber-500" />
            {puzzle.mistakeRate.toFixed(1)}%
          </div>
        </div>

        <div className="bg-slate-950 p-2.5 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Times Reached</div>
          <div className="text-sm font-bold text-slate-200 mt-0.5">
            {puzzle.timesReached} games
          </div>
        </div>
      </div>

      {/* Move History Notation Log */}
      <div className="bg-slate-950 p-3 rounded-xl border border-slate-800">
        <div className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">
          Move Attempt Log
        </div>
        {moveHistory.length === 0 ? (
          <span className="text-xs text-slate-400 italic">No moves played yet</span>
        ) : (
          <div className="flex flex-wrap gap-1.5 text-xs font-mono text-slate-300">
            {moveHistory.map((m, idx) => (
              <span
                key={idx}
                className="px-2 py-0.5 bg-slate-900 border border-slate-800 rounded font-semibold text-emerald-400"
              >
                {idx + 1}. {m}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* Game URLs List */}
      <div className="pt-2">
        <div className="text-xs font-bold text-slate-400 mb-2 uppercase tracking-wider">
          Your Historical Games in This Position
        </div>
        <div className="space-y-1.5 max-h-28 overflow-y-auto pr-1">
          {puzzle.gameUrls.slice(0, 4).map((url, idx) => (
            <a
              key={idx}
              href={url}
              target="_blank"
              rel="noreferrer"
              className="flex items-center justify-between px-3 py-1.5 bg-slate-950 hover:bg-slate-800 text-slate-300 hover:text-emerald-400 rounded-lg text-xs font-medium border border-slate-800/80 transition"
            >
              <span>Chess.com Live Game #{url.split('/').pop()}</span>
              <ExternalLink className="w-3.5 h-3.5 text-slate-400" />
            </a>
          ))}
        </div>
      </div>
    </div>
  );
};
