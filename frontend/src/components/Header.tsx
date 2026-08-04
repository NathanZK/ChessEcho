'use client';

import React from 'react';
import { Swords, Target, Download, User } from 'lucide-react';

export type TabType = 'puzzles' | 'weaknesses' | 'import';

interface HeaderProps {
  activeTab: TabType;
  setActiveTab: (tab: TabType) => void;
  username?: string;
  weaknessCount?: number;
}

export const Header: React.FC<HeaderProps> = ({
  activeTab,
  setActiveTab,
  username = 'NathanZele',
  weaknessCount = 4,
}) => {
  return (
    <header className="bg-slate-900 border-b border-slate-800 sticky top-0 z-50 px-4 lg:px-8 py-3">
      <div className="max-w-7xl mx-auto flex items-center justify-between">
        {/* Brand Logo */}
        <div className="flex items-center space-x-3 cursor-pointer" onClick={() => setActiveTab('puzzles')}>
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-700 flex items-center justify-center shadow-lg shadow-emerald-900/30">
            <span className="text-2xl font-bold text-white">♟</span>
          </div>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-white flex items-center gap-1.5">
              Chess<span className="text-emerald-400">Echo</span>
            </h1>
            <p className="text-xs text-slate-400 font-medium">Pattern-Based Opening Training</p>
          </div>
        </div>

        {/* Center 3-Tab Navigation */}
        <nav className="flex items-center space-x-1 bg-slate-950 p-1.5 rounded-xl border border-slate-800">
          <button
            onClick={() => setActiveTab('puzzles')}
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 ${
              activeTab === 'puzzles'
                ? 'bg-emerald-600 text-white shadow-md shadow-emerald-900/40'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <Swords className="w-4 h-4" />
            <span>Practice Puzzles</span>
          </button>

          <button
            onClick={() => setActiveTab('weaknesses')}
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 ${
              activeTab === 'weaknesses'
                ? 'bg-emerald-600 text-white shadow-md shadow-emerald-900/40'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <Target className="w-4 h-4" />
            <span>Weaknesses Library</span>
            {weaknessCount > 0 && (
              <span className="ml-1.5 px-2 py-0.5 text-xs font-bold bg-emerald-500/20 text-emerald-300 rounded-full border border-emerald-500/30">
                {weaknessCount}
              </span>
            )}
          </button>

          <button
            onClick={() => setActiveTab('import')}
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 ${
              activeTab === 'import'
                ? 'bg-emerald-600 text-white shadow-md shadow-emerald-900/40'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
            }`}
          >
            <Download className="w-4 h-4" />
            <span>Import Games</span>
          </button>
        </nav>

        {/* User Profile Badge */}
        <div className="flex items-center space-x-3 bg-slate-800/80 px-3.5 py-1.5 rounded-xl border border-slate-700/60">
          <div className="w-8 h-8 rounded-full bg-slate-700 flex items-center justify-center text-slate-300 border border-slate-600">
            <User className="w-4 h-4" />
          </div>
          <div className="text-left hidden sm:block">
            <div className="text-sm font-semibold text-slate-200">{username}</div>
            <div className="text-[11px] text-emerald-400 font-medium flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
              Chess.com Connected
            </div>
          </div>
        </div>
      </div>
    </header>
  );
};
