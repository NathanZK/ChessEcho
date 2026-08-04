'use client';

import React from 'react';

interface EvalBarProps {
  evalCp?: number;
  isExploring?: boolean;
  isUnknown?: boolean; // True when the played move has no engine data — eval is frozen
}

export const EvalBar: React.FC<EvalBarProps> = ({ evalCp = 0, isExploring = false, isUnknown = false }) => {
  // Convert centipawns to pawn advantage capped between -10.0 and +10.0
  const pawns = evalCp / 100.0;
  const clampedPawns = Math.max(-10, Math.min(10, pawns));
  
  // Calculate percentage height for White's portion of the bar (50% is equal 0.0)
  // +10 -> 100% white, 0 -> 50% white, -10 -> 0% white
  const whitePercent = 50 + (clampedPawns / 10.0) * 50;

  const displayEval =
    pawns >= 0 ? `+${pawns.toFixed(2)}` : `${pawns.toFixed(2)}`;

  return (
    <div className="flex flex-col items-center h-[500px] lg:h-[560px] xl:h-[600px] w-8 bg-slate-950 rounded-xl overflow-hidden border border-slate-800 shadow-inner relative select-none">
      {/* Line Exploration Lock Indicator Badge */}
      {isExploring && (
        <div className="absolute top-2 inset-x-0 flex justify-center z-10 pointer-events-none">
          <span className="text-[9px] font-bold px-1 py-0.5 rounded bg-emerald-950/90 text-emerald-400 border border-emerald-500/40 shadow text-center leading-none">
            🔒 EXPL
          </span>
        </div>
      )}

      {/* Unknown Eval Badge — move has no engine data, bar is frozen */}
      {isUnknown && (
        <div className="absolute top-2 inset-x-0 flex justify-center z-10 pointer-events-none">
          <span className="text-[9px] font-bold px-1 py-0.5 rounded bg-slate-800/90 text-slate-400 border border-slate-600/40 shadow text-center leading-none">
            ? N/A
          </span>
        </div>
      )}

      {/* Top Black Portion */}
      <div
        className="w-full bg-slate-900 transition-all duration-500 ease-out"
        style={{ height: `${100 - whitePercent}%` }}
      />

      {/* Bottom White Portion (Chess.com White/Light Slate) */}
      <div
        className="w-full bg-slate-100 transition-all duration-500 ease-out shadow-inner"
        style={{ height: `${whitePercent}%` }}
      />

      {/* Evaluation Text Pill Overlay */}
      <div className="absolute inset-x-0 bottom-2 flex justify-center pointer-events-none">
        <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded-md border shadow-md font-mono ${
          isUnknown
            ? 'bg-slate-800/90 text-slate-500 border-slate-600'
            : 'bg-slate-900/90 text-slate-100 border-slate-700'
        }`}>
          {isUnknown ? '?' : displayEval}
        </span>
      </div>
    </div>
  );
};
