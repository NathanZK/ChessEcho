'use client';

import React, { useState, useEffect, useRef } from 'react';
import { Compass, Swords, Target, Crosshair, Download, Clock3, ChevronDown, Menu, Search } from 'lucide-react';
import type { AccountSummary } from '../services/api';
import { ExplorePositionModal, type PositionExplorationStart } from './ExplorePositionModal';
import { AccountMenu } from './AccountMenu';
import { HeaderAccountStatus } from './HeaderAccountStatus';

export type TabType =
  | 'puzzles'
  | 'weaknesses'
  | 'weakness-analysis'
  | 'long-decisions'
  | 'similar-positions'
  | 'import'
  | 'game-analysis';

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
  const [isToolsMenuOpen, setIsToolsMenuOpen] = useState(false);
  const [isMobileToolsOpen, setIsMobileToolsOpen] = useState(false);
  const toolsDropdownRef = useRef<HTMLDivElement | null>(null);
  const mobileToolsDropdownRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (toolsDropdownRef.current && !toolsDropdownRef.current.contains(target)) setIsToolsMenuOpen(false);
      if (mobileToolsDropdownRef.current && !mobileToolsDropdownRef.current.contains(target)) setIsMobileToolsOpen(false);
    };

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsToolsMenuOpen(false);
        setIsMobileToolsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  const isWeaknessActive = activeTab === 'weaknesses';
  const isSecondaryActive =
    activeTab === 'long-decisions' ||
    activeTab === 'similar-positions' ||
    activeTab === 'weakness-analysis';

  return (
    <header className="w-full h-[60px] px-6 md:px-10 border-b border-outline-variant bg-surface-container-lowest sticky top-0 z-50 flex items-center justify-between">
      <div className="w-full flex items-center justify-between h-full flex-nowrap">
        {/* Left: Brand Logo & Navigation */}
        <div className="flex items-center space-x-8 h-full">
          {/* Exact Prototype ChessProbe Brand Logo */}
          <div
            className="flex items-center space-x-2.5 font-bold tracking-tight text-primary text-lg cursor-pointer shrink-0"
            onClick={() => setActiveTab('weaknesses')}
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
            {/* Primary Tab 1: Weakness */}
            <button
              type="button"
              aria-label="Weakness"
              onClick={() => setActiveTab('weaknesses')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-1.5 pb-1 ${
                isWeaknessActive
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Target className="w-3.5 h-3.5 shrink-0" />
              <span>Weakness</span>
              {weaknessCount > 0 && (
                <span className="px-1.5 py-0.2 rounded-full text-[10px] font-bold bg-primary/20 text-primary border border-primary/30">
                  {weaknessCount}
                </span>
              )}
            </button>

            {/* Primary Tab 2: Practice Puzzles */}
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

            {/* Primary Tab 3: My Games */}
            <button
              type="button"
              onClick={() => setActiveTab('game-analysis')}
              className={`transition cursor-pointer whitespace-nowrap flex items-center gap-2 pb-1 ${
                activeTab === 'game-analysis'
                  ? 'border-b-2 border-primary text-primary font-semibold'
                  : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              <Search className="w-3.5 h-3.5 shrink-0" />
              <span>My Games</span>
            </button>

            {/* Primary Tab 4: Import Games (placed immediately right of My Games) */}
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

            {/* Secondary Tools Dropdown */}
            <div className="relative h-full flex items-center" ref={toolsDropdownRef}>
              <button
                type="button"
                aria-haspopup="true"
                aria-expanded={isToolsMenuOpen}
                onClick={() => setIsToolsMenuOpen((prev) => !prev)}
                className={`transition cursor-pointer whitespace-nowrap flex items-center gap-1.5 pb-1 ${
                  isSecondaryActive
                    ? 'border-b-2 border-primary text-primary font-semibold'
                    : 'text-on-surface-variant hover:text-on-surface'
                }`}
              >
                <Compass className="w-3.5 h-3.5 shrink-0" />
                <span>Explore / Tools</span>
                <ChevronDown className={`w-3 h-3 transition-transform ${isToolsMenuOpen ? 'rotate-180' : ''}`} />
              </button>

              {isToolsMenuOpen && (
                <div
                  role="menu"
                  aria-label="Explore and tools"
                  className="absolute left-0 top-[48px] w-48 py-1 bg-surface-container-high border border-outline-variant rounded-md shadow-lg z-50 text-xs font-mono"
                >
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setIsToolsMenuOpen(false);
                      setIsExplorePositionOpen(true);
                    }}
                    className="w-full text-left px-3 py-2 flex items-center gap-2 text-on-surface-variant hover:text-on-surface hover:bg-surface-bright transition cursor-pointer"
                  >
                    <Compass className="w-3.5 h-3.5 shrink-0 text-primary" />
                    <span>Explore a position</span>
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setIsToolsMenuOpen(false);
                      setActiveTab('weakness-analysis');
                    }}
                    className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                      activeTab === 'weakness-analysis'
                        ? 'text-primary font-semibold bg-surface-container'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <Crosshair className="w-3.5 h-3.5 shrink-0 text-primary" />
                    <span>Exact-Position Analysis</span>
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setIsToolsMenuOpen(false);
                      setActiveTab('long-decisions');
                    }}
                    className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                      activeTab === 'long-decisions'
                        ? 'text-primary font-semibold bg-surface-container'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <Clock3 className="w-3.5 h-3.5 shrink-0 text-primary" />
                    <span>Decision Timing</span>
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setIsToolsMenuOpen(false);
                      setActiveTab('similar-positions');
                    }}
                    className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                      activeTab === 'similar-positions'
                        ? 'text-primary font-semibold bg-surface-container'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    <Compass className="w-3.5 h-3.5 shrink-0 text-primary" />
                    <span>Similar Positions</span>
                  </button>
                </div>
              )}
            </div>
          </nav>
        </div>

        {/* Right: Actions & Account Status */}
        <div className="flex items-center space-x-3 shrink-0">
          {/* Mobile Navigation Menu Trigger */}
          <div className="relative md:hidden" ref={mobileToolsDropdownRef}>
            <button
              type="button"
              aria-label="Mobile navigation menu"
              aria-haspopup="true"
              aria-expanded={isMobileToolsOpen}
              onClick={() => setIsMobileToolsOpen((prev) => !prev)}
              className={`flex items-center justify-center w-9 h-9 rounded-lg border border-outline-variant hover:border-primary/40 text-on-surface-variant hover:text-primary transition shrink-0 cursor-pointer ${
                isMobileToolsOpen || isSecondaryActive || isWeaknessActive ? 'border-primary text-primary' : ''
              }`}
            >
              <Menu className="w-4 h-4" />
            </button>

            {isMobileToolsOpen && (
              <div
                role="menu"
                className="absolute right-0 top-[42px] w-56 py-1 bg-surface-container-high border border-outline-variant rounded-md shadow-lg z-50 text-xs font-mono"
              >
                {/* Primary Core Tabs */}
                <button
                  type="button"
                  role="menuitem"
                  aria-label="Recurring Weaknesses - Weakness Library"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('weaknesses');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center justify-between hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'weaknesses'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <span className="flex items-center gap-2">
                    <Target className="w-3.5 h-3.5 shrink-0 text-primary" />
                    <span>Recurring Weaknesses</span>
                  </span>
                  {weaknessCount > 0 && (
                    <span className="px-1.5 py-0.2 rounded-full text-[10px] font-bold bg-primary/20 text-primary border border-primary/30">
                      {weaknessCount}
                    </span>
                  )}
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('puzzles');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'puzzles'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Swords className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>{isSourceNeutralExploration ? 'Line Exploration' : 'Practice Puzzles'}</span>
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('game-analysis');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'game-analysis'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Search className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>My Games</span>
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('import');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'import'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Download className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>Import Games</span>
                </button>

                <div className="my-1 border-t border-outline-variant/50" role="separator" />

                {/* Secondary Tools */}
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setIsExplorePositionOpen(true);
                  }}
                  className="w-full text-left px-3 py-2 flex items-center gap-2 text-on-surface-variant hover:text-on-surface hover:bg-surface-bright transition cursor-pointer"
                >
                  <Compass className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>Explore a position</span>
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('weakness-analysis');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'weakness-analysis'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Crosshair className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>Exact-Position Analysis</span>
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('long-decisions');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'long-decisions'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Clock3 className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>Decision Timing</span>
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setIsMobileToolsOpen(false);
                    setActiveTab('similar-positions');
                  }}
                  className={`w-full text-left px-3 py-2 flex items-center gap-2 hover:bg-surface-bright transition cursor-pointer ${
                    activeTab === 'similar-positions'
                      ? 'text-primary font-semibold bg-surface-container'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  <Compass className="w-3.5 h-3.5 shrink-0 text-primary" />
                  <span>Similar Positions</span>
                </button>
              </div>
            )}
          </div>

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

          <HeaderAccountStatus
            sessionStatus={sessionStatus}
            accountStatus={accountStatus}
            connectedAccount={connectedAccount}
            username={username}
            onDisconnect={onDisconnect}
          />
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
