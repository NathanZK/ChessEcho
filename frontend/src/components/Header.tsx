'use client';

import React, { useState } from 'react';
import { Compass, Swords, Target, Download, User, Clock3 } from 'lucide-react';
import type { AccountSummary } from '../services/api';
import { ExplorePositionModal, type PositionExplorationStart } from './ExplorePositionModal';
import { AccountMenu } from './AccountMenu';

export type TabType = 'puzzles' | 'weaknesses' | 'long-decisions' | 'similar-positions' | 'import';

export type HeaderSessionStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error';
export type HeaderAccountStatus = 'loading' | 'connected' | 'unconnected' | 'error';

interface HeaderProps {
  activeTab: TabType;
  setActiveTab: (tab: TabType) => void;
  username?: string;
  connectedAccount?: AccountSummary;
  weaknessCount?: number;
  onDisconnect?: () => void;
  sessionStatus?: HeaderSessionStatus;
  accountStatus: HeaderAccountStatus;
  onExplorePosition?: (position: PositionExplorationStart) => void;
  isSourceNeutralExploration?: boolean;
}

export const Header: React.FC<HeaderProps> = ({
  activeTab,
  setActiveTab,
  username,
  connectedAccount,
  weaknessCount = 0,
  onDisconnect,
  sessionStatus,
  accountStatus,
  onExplorePosition,
  isSourceNeutralExploration = false,
}) => {
  const [isExplorePositionOpen, setIsExplorePositionOpen] = useState(false);
  const showConnected = sessionStatus === 'authenticated' && !!connectedAccount;
  const showGuestSelection = sessionStatus === 'unauthenticated' && !!username;
  const showAccount = showConnected || showGuestSelection;
  const accountConnectionLabel =
    accountStatus === 'unconnected'
      ? 'No Chess.com account connected'
      : accountStatus === 'loading'
        ? 'Checking Chess.com connection'
        : accountStatus === 'error'
          ? 'Unable to load Chess.com connection'
          : 'Connected Chess.com account unavailable';
  const accountUsername = showConnected ? connectedAccount?.username : username;

  return (
    <header className="w-full h-[60px] px-6 md:px-10 border-b border-outline-variant bg-surface-container-lowest sticky top-0 z-50 flex items-center justify-between">
      <div className="w-full flex items-center justify-between h-full flex-nowrap">
        {/* Left: Brand Logo & Navigation */}
        <div className="flex items-center space-x-8 h-full">
          {/* Exact Prototype ChessProbe Brand Logo */}
          <div
            className="flex items-center space-x-2.5 font-bold tracking-tight text-primary text-lg cursor-pointer shrink-0"
            onClick={() => setActiveTab('puzzles')}
          >
            <span className="w-8 h-8 rounded bg-surface-container-high border border-outline-variant flex items-center justify-center text-primary shadow-inner shrink-0">
              <svg className="w-4 h-4 fill-current text-primary" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M19 22H5v-2h14v2m-1-4H6c-.5 0-.9-.3-1-.7-.4-1.7-.8-4.5 1-6.3 1.1-1.1 2.3-1.6 3-3.5.7-1.9.4-3.5 0-4.5-.1-.3 0-.6.2-.8.2-.2.5-.2.8-.1 1.7.7 3.5 2 4.5 4.4.4.9 1 1.5 1.5 2.5.5.9.8 2 .8 3.5 0 1.2-.5 2.1-1 2.8.5.5.8 1.1.9 1.7.2 1.3-.7 2.4-1.7 3M9.5 9c-.8 0-1.5.7-1.5 1.5s.7 1.5 1.5 1.5 1.5-.7 1.5-1.5S10.3 9 9.5 9z" />
              </svg>
            </span>
            <h1 className="font-sans font-extrabold tracking-wide text-primary text-lg">ChessProbe</h1>
          </div>

          {/* Primary Navigation Tabs */}
          <nav className="hidden md:flex space-x-6 h-full items-center text-xs font-mono">
            <button
              type="button"
              onClick={() => setActiveTab('puzzles')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'puzzles'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Swords className="w-3.5 h-3.5 shrink-0" />
              <span>{isSourceNeutralExploration ? 'Line Exploration' : 'Practice Puzzles'}</span>
            </button>

            <button
              type="button"
              aria-label="Position Weaknesses (Weaknesses Library)"
              onClick={() => setActiveTab('weaknesses')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'weaknesses'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Target className="w-3.5 h-3.5 shrink-0" />
              <span>Position Weaknesses</span>
              {weaknessCount > 0 && (
                <span className="px-1.5 py-0.2 rounded-full text-[10px] font-bold bg-primary/20 text-primary border border-primary/30">
                  {weaknessCount}
                </span>
              )}
            </button>

            <button
              type="button"
              aria-label="Decision Timing (Long Decisions)"
              onClick={() => setActiveTab('long-decisions')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'long-decisions'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Clock3 className="w-3.5 h-3.5 shrink-0" />
              <span>Decision Timing</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('similar-positions')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'similar-positions'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Compass className="w-3.5 h-3.5 shrink-0" />
              <span>Similar Positions</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('import')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'import'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Download className="w-3.5 h-3.5 shrink-0" />
              <span>Import Games</span>
            </button>
          </nav>
        </div>

        {/* Right: Actions & Account Status */}
        <div className="flex items-center space-x-3 shrink-0">
          <button
            type="button"
            onClick={() => setIsExplorePositionOpen(true)}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg border border-outline-variant hover:border-primary/40 text-xs font-mono text-on-surface-variant hover:text-primary transition shrink-0 cursor-pointer"
          >
            <Compass className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Explore a position</span>
          </button>

          {sessionStatus === 'authenticated' && (
            <div className="md:hidden">
              <AccountMenu
                username={username}
                connectedAccount={connectedAccount}
                sessionStatus={sessionStatus}
                accountStatus={accountStatus}
                onDisconnect={onDisconnect}
              />
            </div>
          )}

          {showAccount ? (
            <div
              className={`items-center space-x-2 ${
                sessionStatus === 'authenticated' ? 'hidden md:flex' : 'flex'
              }`}
            >
              <div className="flex items-center space-x-2 px-3 py-1 bg-surface-container border border-outline-variant rounded text-xs font-mono text-on-surface-variant">
                <div className="flex items-center space-x-2">
                  <span className="w-2 h-2 rounded-full bg-secondary"></span>
                  <span className="text-on-surface font-semibold">{accountUsername}</span>
                </div>
                <span className="text-outline hidden sm:inline">·</span>
                <span className="text-secondary text-[11px]">{showConnected ? 'Chess.com Connected' : 'Guest Mode'}</span>
              </div>
              {sessionStatus === 'authenticated' && (
                <div className="hidden xl:flex items-center px-2.5 py-1 bg-surface-container-high border border-outline-variant rounded text-xs font-mono text-on-surface">
                  <User className="w-3.5 h-3.5 mr-1 text-primary" />
                  <span>Signed in to ChessProbe</span>
                </div>
              )}
              {onDisconnect && (
                <button
                  type="button"
                  onClick={onDisconnect}
                  title={sessionStatus === 'authenticated' ? 'Sign out' : 'Disconnect Account'}
                  className="px-2.5 py-1 bg-surface-container-high hover:bg-surface-bright text-xs font-mono rounded text-on-surface border border-outline-variant transition cursor-pointer"
                >
                  {sessionStatus === 'authenticated' ? 'Sign out' : 'Disconnect'}
                </button>
              )}
              {showGuestSelection && (
                <a
                  href="/login"
                  className="px-3 py-1 bg-primary-container hover:bg-primary text-on-primary-container text-xs font-mono font-bold rounded transition cursor-pointer text-center"
                >
                  Sign In
                </a>
              )}
            </div>
          ) : (
            <div
              className={`items-center space-x-2 ${
                sessionStatus === 'authenticated' ? 'hidden md:flex' : 'flex'
              }`}
            >
              {sessionStatus === 'authenticated' ? (
                <div className="flex items-center space-x-2 px-3 py-1 bg-surface-container border border-outline-variant rounded text-xs font-mono text-on-surface-variant">
                  <span className="text-on-surface font-semibold">Signed in to ChessProbe</span>
                  <span className="text-outline">·</span>
                  <span className="text-outline">{accountConnectionLabel}</span>
                </div>
              ) : (
                <span className="text-outline text-xs font-mono hidden sm:inline">
                  Not Connected
                </span>
              )}
              {sessionStatus === 'authenticated' && onDisconnect && (
                <button
                  type="button"
                  onClick={onDisconnect}
                  title="Sign out"
                  className="px-2.5 py-1 bg-surface-container-high hover:bg-surface-bright text-xs font-mono rounded text-on-surface border border-outline-variant transition cursor-pointer"
                >
                  Sign out
                </button>
              )}
              {sessionStatus === 'unauthenticated' && (
                <div className="flex items-center space-x-2">
                  <a
                    href="/login"
                    className="px-3 py-1 bg-primary-container hover:bg-primary text-on-primary-container text-xs font-mono font-bold rounded transition cursor-pointer text-center"
                  >
                    Sign In
                  </a>
                  <a
                    href="/register"
                    className="px-2.5 py-1 text-primary hover:text-white text-xs font-mono transition text-center"
                  >
                    Register
                  </a>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {isExplorePositionOpen && (
        <ExplorePositionModal
          onClose={() => setIsExplorePositionOpen(false)}
          onSubmit={(position) => {
            onExplorePosition?.(position);
            setIsExplorePositionOpen(false);
          }}
        />
      )}
    </header>
  );
};
