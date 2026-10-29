'use client';

import React, { useState } from 'react';
import { Compass, Swords, Target, Download, User, Clock3 } from 'lucide-react';
import type { AccountSummary } from '../services/api';
import { ExplorePositionModal, type PositionExplorationStart } from './ExplorePositionModal';

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
  const isPuzzlesLayout = activeTab === 'puzzles';
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
    <header
      className={`border-outline-variant bg-surface-container-lowest sticky top-0 z-50 transition-all ${
        isPuzzlesLayout
          ? 'w-full shrink-0 2xl:w-[280px] 2xl:h-screen 2xl:border-r 2xl:border-b-0 2xl:left-0 2xl:px-4 2xl:py-5 2xl:overflow-y-auto border-b px-4 md:px-8 py-3'
          : 'w-full h-[60px] px-6 md:px-10 border-b flex items-center justify-between'
      }`}
    >
      <div
        className={`w-full flex items-center justify-between ${
          isPuzzlesLayout
            ? 'flex-wrap gap-3 2xl:max-w-none 2xl:mx-0 2xl:h-full 2xl:flex-col 2xl:flex-nowrap 2xl:items-stretch 2xl:justify-start'
            : 'h-full flex-nowrap'
        }`}
      >
        {/* Left / Top: Brand Logo & Navigation */}
        <div
          className={`flex items-center ${
            isPuzzlesLayout
              ? 'order-1 min-w-0 max-w-full 2xl:w-full 2xl:shrink-0 2xl:flex-col 2xl:items-stretch 2xl:space-y-4'
              : 'space-x-8 h-full'
          }`}
        >
          {/* Exact Prototype ChessProbe Brand Logo */}
          <div
            className={`flex items-center space-x-2.5 font-bold tracking-tight text-primary text-lg cursor-pointer shrink-0 ${
              isPuzzlesLayout ? '2xl:pb-3 2xl:border-b 2xl:border-outline-variant' : ''
            }`}
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
          <nav
            className={`flex items-center text-xs font-mono ${
              isPuzzlesLayout
                ? 'gap-1 order-3 w-full min-w-0 overflow-x-auto 2xl:order-2 2xl:flex-col 2xl:items-stretch 2xl:overflow-visible 2xl:space-y-1'
                : 'hidden md:flex space-x-6 h-full items-center'
            }`}
          >
            <button
              type="button"
              onClick={() => setActiveTab('puzzles')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 ${
                isPuzzlesLayout
                  ? 'px-3 py-2.5 rounded-lg 2xl:w-full 2xl:justify-start font-medium text-xs bg-primary-container text-on-primary-container font-bold shadow-md shadow-primary/20'
                  : 'pb-1 text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Swords className="w-3.5 h-3.5 shrink-0" />
              <span>{isSourceNeutralExploration ? 'Line Exploration' : 'Practice Puzzles'}</span>
            </button>

            <button
              type="button"
              aria-label="Weaknesses Library"
              onClick={() => setActiveTab('weaknesses')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 ${
                isPuzzlesLayout
                  ? 'px-3 py-2.5 rounded-lg 2xl:w-full 2xl:justify-start font-medium text-xs text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                  : `pb-1 ${
                      activeTab === 'weaknesses'
                        ? 'border-b-2 border-primary text-primary font-semibold'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`
              }`}
            >
              <Target className="w-3.5 h-3.5 shrink-0" />
              <span>Weakness Library</span>
              {weaknessCount > 0 && (
                <span
                  className={`px-1.5 py-0.2 rounded-full text-[10px] font-bold ${
                    isPuzzlesLayout
                      ? 'ml-auto bg-primary/20 text-primary border border-primary/30'
                      : 'bg-primary/20 text-primary border border-primary/30'
                  }`}
                >
                  {weaknessCount}
                </span>
              )}
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('long-decisions')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 ${
                isPuzzlesLayout
                  ? 'px-3 py-2.5 rounded-lg 2xl:w-full 2xl:justify-start font-medium text-xs text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                  : `pb-1 ${
                      activeTab === 'long-decisions'
                        ? 'border-b-2 border-primary text-primary font-semibold'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`
              }`}
            >
              <Clock3 className="w-3.5 h-3.5 shrink-0" />
              <span>Long Decisions</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('similar-positions')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 ${
                isPuzzlesLayout
                  ? 'px-3 py-2.5 rounded-lg 2xl:w-full 2xl:justify-start font-medium text-xs text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                  : `pb-1 ${
                      activeTab === 'similar-positions'
                        ? 'border-b-2 border-primary text-primary font-semibold'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`
              }`}
            >
              <Compass className="w-3.5 h-3.5 shrink-0" />
              <span>Similar Positions</span>
            </button>

            <button
              type="button"
              onClick={() => setActiveTab('import')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 ${
                isPuzzlesLayout
                  ? 'px-3 py-2.5 rounded-lg 2xl:w-full 2xl:justify-start font-medium text-xs text-on-surface-variant hover:text-on-surface hover:bg-surface-container'
                  : `pb-1 ${
                      activeTab === 'import'
                        ? 'border-b-2 border-primary text-primary font-semibold'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`
              }`}
            >
              <Download className="w-3.5 h-3.5 shrink-0" />
              <span>Import Games</span>
            </button>
          </nav>
        </div>

        {/* Explore a position Button (In 2xl sidebar or top right) */}
        {isPuzzlesLayout && (
          <button
            type="button"
            onClick={() => setIsExplorePositionOpen(true)}
            className="hidden 2xl:flex items-center justify-center gap-2 px-3 py-2.5 rounded-lg border border-outline-variant bg-surface-container hover:bg-surface-container-high text-xs font-mono font-medium text-primary transition cursor-pointer 2xl:w-full 2xl:mt-3"
          >
            <Compass className="w-4 h-4 shrink-0" />
            <span>Explore a position</span>
          </button>
        )}

        {/* Right / Bottom: Actions & Account Status */}
        <div
          className={`flex items-center space-x-3 shrink-0 ${
            isPuzzlesLayout
              ? 'order-2 ml-auto max-w-full min-w-0 flex-wrap gap-2 2xl:order-3 2xl:mt-auto 2xl:ml-0 2xl:w-full 2xl:shrink-0 2xl:flex-col 2xl:items-stretch 2xl:pt-4 2xl:border-t 2xl:border-outline-variant'
              : ''
          }`}
        >
          {!isPuzzlesLayout && (
            <button
              type="button"
              onClick={() => setIsExplorePositionOpen(true)}
              className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg border border-outline-variant hover:border-primary/40 text-xs font-mono text-on-surface-variant hover:text-primary transition shrink-0 cursor-pointer"
            >
              <Compass className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Explore a position</span>
            </button>
          )}

          {showAccount ? (
            <div
              className={`flex items-center space-x-2 ${
                isPuzzlesLayout ? '2xl:flex-col 2xl:items-stretch 2xl:space-x-0 2xl:space-y-2 2xl:w-full' : ''
              }`}
            >
              <div
                className={`flex items-center space-x-2 px-3 py-1 bg-surface-container border border-outline-variant rounded text-xs font-mono text-on-surface-variant ${
                  isPuzzlesLayout ? '2xl:w-full 2xl:justify-between' : ''
                }`}
              >
                <div className="flex items-center space-x-2">
                  <span className="w-2 h-2 rounded-full bg-secondary"></span>
                  <span className="text-on-surface font-semibold">{accountUsername}</span>
                </div>
                <span className="text-outline hidden sm:inline">·</span>
                <span className="text-secondary text-[11px]">{showConnected ? 'Chess.com Connected' : 'Guest Mode'}</span>
              </div>
              {sessionStatus === 'authenticated' && !isPuzzlesLayout && (
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
                  className={`px-2.5 py-1 bg-surface-container-high hover:bg-surface-bright text-xs font-mono rounded text-on-surface border border-outline-variant transition cursor-pointer ${
                    isPuzzlesLayout ? '2xl:w-full 2xl:py-1.5' : ''
                  }`}
                >
                  {sessionStatus === 'authenticated' ? 'Sign out' : 'Disconnect'}
                </button>
              )}
              {showGuestSelection && (
                <a
                  href="/login"
                  className={`px-3 py-1 bg-primary-container hover:bg-primary text-on-primary-container text-xs font-mono font-bold rounded transition cursor-pointer text-center ${
                    isPuzzlesLayout ? '2xl:w-full 2xl:py-1.5' : ''
                  }`}
                >
                  Sign In
                </a>
              )}
            </div>
          ) : (
            <div
              className={`flex items-center space-x-2 ${
                isPuzzlesLayout ? '2xl:flex-col 2xl:items-stretch 2xl:space-x-0 2xl:space-y-2 2xl:w-full' : ''
              }`}
            >
              {sessionStatus === 'authenticated' ? (
                <div
                  className={`flex items-center space-x-2 px-3 py-1 bg-surface-container border border-outline-variant rounded text-xs font-mono text-on-surface-variant ${
                    isPuzzlesLayout ? '2xl:w-full' : ''
                  }`}
                >
                  <span className="text-on-surface font-semibold">Signed in to ChessProbe</span>
                  <span className="text-outline">·</span>
                  <span className="text-outline">{accountConnectionLabel}</span>
                </div>
              ) : (
                <span className={`text-outline text-xs font-mono ${isPuzzlesLayout ? '2xl:block text-center py-1' : 'hidden sm:inline'}`}>
                  Not Connected
                </span>
              )}
              {sessionStatus === 'authenticated' && onDisconnect && (
                <button
                  type="button"
                  onClick={onDisconnect}
                  title="Sign out"
                  className={`px-2.5 py-1 bg-surface-container-high hover:bg-surface-bright text-xs font-mono rounded text-on-surface border border-outline-variant transition cursor-pointer ${
                    isPuzzlesLayout ? '2xl:w-full 2xl:py-1.5' : ''
                  }`}
                >
                  Sign out
                </button>
              )}
              {sessionStatus === 'unauthenticated' && (
                <div className={`flex items-center space-x-2 ${isPuzzlesLayout ? '2xl:w-full' : ''}`}>
                  <a
                    href="/login"
                    className={`px-3 py-1 bg-primary-container hover:bg-primary text-on-primary-container text-xs font-mono font-bold rounded transition cursor-pointer text-center ${
                      isPuzzlesLayout ? 'flex-1' : ''
                    }`}
                  >
                    Sign In
                  </a>
                  <a
                    href="/register"
                    className={`px-2.5 py-1 text-primary hover:text-white text-xs font-mono transition text-center ${
                      isPuzzlesLayout ? 'flex-1' : ''
                    }`}
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
