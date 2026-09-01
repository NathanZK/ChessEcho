'use client';

import React from 'react';
import { Swords, Target, Download, User } from 'lucide-react';

export type TabType = 'puzzles' | 'weaknesses' | 'import';

interface HeaderProps {
  activeTab: TabType;
  setActiveTab: (tab: TabType) => void;
  username?: string;
  weaknessCount?: number;
  onDisconnect?: () => void;
  sideNavigation?: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  activeTab,
  setActiveTab,
  username,
  weaknessCount = 0,
  onDisconnect,
  sideNavigation = false,
}) => {
  return (
    <header
      data-testid="app-navigation"
      className={`bg-slate-900 border-b border-slate-800 sticky top-0 z-50 px-4 lg:px-8 py-3 ${
        sideNavigation
          ? '2xl:w-52 2xl:h-full 2xl:shrink-0 2xl:border-r 2xl:border-b-0 2xl:px-3 2xl:py-5'
          : ''
      }`}
    >
      <div className={`max-w-7xl mx-auto flex items-center justify-between ${
        sideNavigation ? '2xl:h-full 2xl:flex-col 2xl:items-stretch' : ''
      }`}>
        {/* Brand Logo */}
        <button
          type="button"
          aria-label="ChessEcho home"
          className="flex items-center space-x-3 cursor-pointer text-left"
          onClick={() => setActiveTab('puzzles')}
        >
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-700 flex items-center justify-center shadow-lg shadow-emerald-900/30">
            <span className="text-2xl font-bold text-white">♟</span>
          </div>
          <div>
            <h1 className="text-xl font-bold tracking-tight text-white flex items-center gap-1.5">
              Chess<span className="text-emerald-400">Echo</span>
            </h1>
            <p className={`text-xs text-slate-400 font-medium ${sideNavigation ? '2xl:hidden' : ''}`}>
              Pattern-Based Opening Training
            </p>
          </div>
        </button>

        {/* Center 3-Tab Navigation */}
        <nav
          aria-label="Primary navigation"
          className={`flex items-center space-x-1 bg-slate-950 p-1.5 rounded-xl border border-slate-800 ${
            sideNavigation
              ? '2xl:w-full 2xl:flex-col 2xl:items-stretch 2xl:space-x-0 2xl:space-y-1.5'
              : ''
          }`}
        >
          <button
            onClick={() => setActiveTab('puzzles')}
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 cursor-pointer ${
              sideNavigation ? '2xl:w-full' : ''
            } ${
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
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 cursor-pointer ${
              sideNavigation ? '2xl:w-full' : ''
            } ${
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
            className={`flex items-center space-x-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 cursor-pointer ${
              sideNavigation ? '2xl:w-full' : ''
            } ${
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
        {username ? (
          <div className={`flex items-center space-x-3 bg-slate-800/80 px-3.5 py-1.5 rounded-xl border border-slate-700/60 ${
            sideNavigation ? '2xl:w-full 2xl:flex-wrap 2xl:gap-y-2 2xl:space-x-2' : ''
          }`}>
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
            {onDisconnect && (
              <button
                onClick={onDisconnect}
                title="Disconnect Account"
                className={`ml-2 px-2.5 py-1 bg-slate-700 hover:bg-rose-600 text-slate-300 hover:text-white text-[11px] font-bold rounded-lg transition border border-slate-600 hover:border-rose-500 cursor-pointer ${
                  sideNavigation ? '2xl:ml-0 2xl:w-full' : ''
                }`}
              >
                Disconnect
              </button>
            )}
          </div>
        ) : (
          <div className={`flex items-center space-x-2 bg-slate-950 px-3.5 py-1.5 rounded-xl border border-slate-800 text-xs font-semibold text-slate-400 ${
            sideNavigation ? '2xl:w-full' : ''
          }`}>
            <User className="w-4 h-4 text-slate-500" />
            <span>Not Connected</span>
          </div>
        )}
      </div>
    </header>
  );
};
