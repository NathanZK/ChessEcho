'use client';

import React, { useState } from 'react';
import { Target, Flame, Swords, ExternalLink, Filter } from 'lucide-react';
import { MOCK_PUZZLES, Puzzle } from '../mock/mockData';

interface WeaknessesListProps {
  username?: string;
  onSelectPractice: (puzzle: Puzzle) => void;
}

export const WeaknessesList: React.FC<WeaknessesListProps> = ({ onSelectPractice }) => {
  const [colorFilter, setColorFilter] = useState<'ALL' | 'WHITE' | 'BLACK'>('ALL');
  const [minMistakeCountFilter, setMinMistakeCountFilter] = useState<number>(3);

  const filteredPuzzles = MOCK_PUZZLES.filter((p) => {
    if (colorFilter !== 'ALL' && p.playerColor !== colorFilter) return false;
    if (p.mistakeCount < minMistakeCountFilter) return false;
    return true;
  }).sort((a, b) => b.priority - a.priority);

  return (
    <div className="max-w-6xl mx-auto space-y-4 px-4 py-2 text-slate-200">
      {/* Header & Filter Controls */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-lg">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Target className="w-5 h-5 text-emerald-400" />
            Recurring Opening Weaknesses Library
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Positions where you repeatedly make sub-optimal moves across games.
          </p>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center space-x-2 text-xs font-semibold text-slate-400">
            <Filter className="w-3.5 h-3.5" />
            <span>Filters:</span>
          </div>

          {/* Color Filter */}
          <div className="flex bg-slate-950 p-1 rounded-xl border border-slate-800">
            {(['ALL', 'WHITE', 'BLACK'] as const).map((color) => (
              <button
                key={color}
                onClick={() => setColorFilter(color)}
                className={`px-3 py-1 text-xs font-bold rounded-lg transition ${
                  colorFilter === color
                    ? 'bg-emerald-600 text-white shadow-sm'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {color}
              </button>
            ))}
          </div>

          {/* Min Mistake Count Filter */}
          <div className="flex items-center space-x-2 bg-slate-950 px-3 py-1.5 rounded-xl border border-slate-800 text-xs font-semibold">
            <span className="text-slate-400">Min Mistakes:</span>
            <select
              value={minMistakeCountFilter}
              onChange={(e) => setMinMistakeCountFilter(Number(e.target.value))}
              className="bg-slate-900 text-emerald-400 font-bold border border-slate-800 rounded px-2 py-0.5 outline-none"
            >
              <option value={1}>1 (All)</option>
              <option value={3}>3 (Habits only)</option>
              <option value={5}>5+ (High frequency)</option>
            </select>
          </div>
        </div>
      </div>

      {/* Weakness Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {filteredPuzzles.map((item) => (
          <div
            key={item.puzzleId}
            className="bg-slate-900 rounded-2xl p-5 border border-slate-800 hover:border-slate-700 shadow-xl transition flex flex-col justify-between space-y-4"
          >
            <div>
              <div className="flex items-start justify-between">
                <div>
                  <h3 className="text-base font-bold text-white">
                    {item.openingTitle}
                  </h3>
                </div>
                <span
                  className={`text-xs font-bold px-2 py-1 rounded-md ${
                    item.playerColor === 'WHITE'
                      ? 'bg-slate-200 text-slate-900'
                      : 'bg-slate-800 text-slate-200 border border-slate-700'
                  }`}
                >
                  As {item.playerColor}
                </span>
              </div>

              {/* Stats Summary */}
              <div className="grid grid-cols-3 gap-2 mt-4 text-center">
                <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                  <div className="text-[10px] text-slate-400 font-medium">Mistake Rate</div>
                  <div className="text-xs font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
                    <Flame className="w-3.5 h-3.5 text-amber-500" />
                    {item.mistakeRate.toFixed(1)}%
                  </div>
                </div>

                <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                  <div className="text-[10px] text-slate-400 font-medium">Times Reached</div>
                  <div className="text-xs font-bold text-slate-200 mt-0.5">
                    {item.timesReached}
                  </div>
                </div>

                <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                  <div className="text-[10px] text-slate-400 font-medium">Best Move</div>
                  <div className="text-xs font-bold text-emerald-400 mt-0.5">
                    {item.targetMove}
                  </div>
                </div>
              </div>

              {/* Played Mistakes List */}
              <div className="mt-3">
                <div className="text-[11px] font-semibold text-slate-400 mb-1">
                  Your Common Mistakes:
                </div>
                <div className="flex flex-wrap gap-1.5">
                  {item.movesPlayed.map((m, idx) => (
                    <span
                      key={idx}
                      className="px-2 py-0.5 bg-rose-500/10 border border-rose-500/20 text-rose-300 rounded text-xs font-mono font-semibold"
                    >
                      {m.move} ({m.timesPlayed}x, -{m.averageLoss.toFixed(2)})
                    </span>
                  ))}
                </div>
              </div>
            </div>

            {/* Action Buttons */}
            <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between">
              <a
                href={item.gameUrls?.[0]}
                target="_blank"
                rel="noreferrer"
                className="text-xs font-semibold text-slate-400 hover:text-emerald-400 flex items-center gap-1 transition"
              >
                <span>View Game</span>
                <ExternalLink className="w-3 h-3" />
              </a>

              <button
                onClick={() => onSelectPractice(item)}
                className="flex items-center space-x-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold rounded-xl transition shadow-md shadow-emerald-900/30"
              >
                <Swords className="w-3.5 h-3.5" />
                <span>Practice Position</span>
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
