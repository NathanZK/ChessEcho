'use client';

import React, { useEffect } from 'react';
import { AlertTriangle, CheckCircle2, XCircle, ExternalLink, HelpCircle, Flame, Trophy, ChevronRight } from 'lucide-react';
import { Puzzle } from '../mock/mockData';

export function formatDecimal(val: number, decimals: number = 2): string {
  return (val ?? 0).toFixed(decimals).replace(',', '.');
}

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
    <div className="flex flex-col space-y-3 bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl text-slate-200">
      {/* Opening Header */}
      <div className="border-b border-slate-800 pb-2.5">
        <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">
          Target Opening Weakness
        </span>
        <h2 className="text-base font-bold text-white mt-0.5">
          {puzzle.openingTitle}
        </h2>
      </div>

      {/* Dynamic Feedback / Success Card */}
      {feedback.status === 'CORRECT' ? (
        <div className="p-3.5 bg-gradient-to-br from-emerald-950/80 to-slate-900 border-2 border-emerald-500/50 rounded-2xl space-y-2.5 shadow-lg shadow-emerald-950/40 animate-in fade-in duration-200">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Trophy className="w-4 h-4 text-emerald-400 animate-bounce" />
            </div>
            <div>
              <h4 className="font-bold text-sm text-emerald-200">
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

          {/* Historical Mistakes List Displayed Even on Correct Move */}
          {puzzle.movesPlayed && puzzle.movesPlayed.length > 0 && (
            <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl p-3 space-y-2 text-xs text-amber-300">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2 font-bold text-amber-200">
                  <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
                  <span>Your Past Decisions</span>
                </div>
                <span className="text-[10px] font-semibold bg-amber-500/20 text-amber-300 px-2 py-0.5 rounded-full border border-amber-500/20">
                  {puzzle.mistakeCount} total errors
                </span>
              </div>

              <p className="text-amber-300/80 leading-relaxed">
                In past games, you played these sub-optimal decisions in this position:
              </p>

              <div className="grid grid-cols-1 gap-1.5 pt-0.5">
                {puzzle.movesPlayed.map((mistake, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between bg-slate-950/50 hover:bg-slate-950/80 px-2.5 py-1 rounded-lg border border-amber-500/20 transition"
                  >
                    <div className="flex items-center space-x-2 font-mono">
                      <span className="font-bold text-rose-400 bg-rose-500/10 px-1.5 py-0.5 rounded border border-rose-500/20">
                        {mistake.move}
                      </span>
                      <span className="text-slate-400 text-[11px]">
                        ({mistake.timesPlayed} {mistake.timesPlayed === 1 ? 'game' : 'games'})
                      </span>
                    </div>
                    <div className="text-amber-400/90 font-mono text-[11px] font-semibold">
                      -{formatDecimal(mistake.averageLoss, 2)} pawns
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          <button
            onClick={onNextPuzzle}
            className="w-full py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/50 flex items-center justify-center space-x-2 group"
          >
            <span>Next Puzzle</span>
            <span className="text-[10px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
              Enter ↵
            </span>
            <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
          </button>
        </div>
      ) : feedback.status === 'EXPLORING' ? (
        <div className="p-3.5 bg-gradient-to-br from-emerald-950/80 to-slate-900 border-2 border-emerald-500/50 rounded-2xl space-y-2.5 shadow-lg shadow-emerald-950/40 animate-in fade-in duration-200">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Trophy className="w-4 h-4 text-emerald-400" />
            </div>
            <div>
              <h4 className="font-bold text-sm text-emerald-200">Line Exploration 🔍</h4>
              <p className="text-xs text-emerald-300/90">
                Played <span className="font-bold text-white">{feedback.lastMove}</span>. Examining follow-up moves on the board.
              </p>
            </div>
          </div>

          <button
            onClick={onNextPuzzle}
            className="w-full py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/50 flex items-center justify-center space-x-2 group"
          >
            <span>Next Puzzle</span>
            <span className="text-[10px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
              Enter ↵
            </span>
            <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
          </button>
        </div>
      ) : feedback.status === 'HISTORICAL_MISTAKE' ? (
        <div className="flex items-start space-x-3 p-3.5 bg-amber-500/15 border border-amber-500/40 rounded-xl text-amber-300 animate-in fade-in duration-200">
          <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="font-bold text-sm text-amber-200">Historical Mistake Detected!</h4>
            <p className="text-xs mt-0.5 text-amber-300/90">
              You played <span className="font-bold text-white">{feedback.lastMove}</span> in{' '}
              <span className="font-bold text-white">
                {feedback.historicalInfo?.timesPlayed}{' '}
                {feedback.historicalInfo?.timesPlayed === 1 ? 'past game' : 'past games'}
              </span>{' '}
              —{' '}
              <span className="font-bold text-white">
                {formatDecimal(feedback.historicalInfo?.averageLoss ?? 0, 2)} pawns worse
              </span>{' '}
              than the best move. Try{' '}
              <span className="font-bold text-emerald-300">{puzzle.targetMove}</span> instead!
            </p>
          </div>
        </div>
      ) : feedback.status === 'INCORRECT' ? (
        <div className="flex items-start space-x-3 p-3.5 bg-rose-500/15 border border-rose-500/40 rounded-xl text-rose-300 animate-in fade-in duration-200">
          <XCircle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="font-bold text-sm text-rose-200">Not the Recommended Move</h4>
            <p className="text-xs mt-0.5 text-rose-300/90">
              <span className="font-bold text-white">{feedback.lastMove}</span> is not the recommended move.
            </p>
          </div>
        </div>
      ) : (
        <div className="flex items-center space-x-3 p-3 bg-slate-950/60 border border-slate-800/80 rounded-xl text-slate-400">
          <HelpCircle className="w-4 h-4 text-slate-400 shrink-0" />
          <p className="text-xs">
            Find {puzzle.playerColor === 'BLACK' ? "Black's" : "White's"} best move or an acceptable alternative to fix your opening habit.
          </p>
        </div>
      )}

      {/* Stats Pill Badges */}
      <div className="grid grid-cols-2 gap-2 py-0.5">
        <div className="bg-slate-950 p-2 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Mistake Rate</div>
          <div className="text-xs font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
            <Flame className="w-3.5 h-3.5 text-amber-500" />
            {formatDecimal(puzzle.mistakeRate, 1)}%
          </div>
        </div>

        <div className="bg-slate-950 p-2 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Times Reached</div>
          <div className="text-xs font-bold text-slate-200 mt-0.5">
            {puzzle.timesReached} games
          </div>
        </div>
      </div>
    </div>
  );
};
