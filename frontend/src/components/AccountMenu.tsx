'use client';

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { User, LogOut } from 'lucide-react';
import type { AccountSummary } from '../services/api';
import type { HeaderAccountStatus, HeaderSessionStatus } from './Header';

export interface AccountMenuProps {
  username?: string;
  connectedAccount?: AccountSummary;
  sessionStatus?: HeaderSessionStatus;
  accountStatus: HeaderAccountStatus;
  onDisconnect?: () => void;
}

export const AccountMenu: React.FC<AccountMenuProps> = ({
  username,
  connectedAccount,
  sessionStatus,
  accountStatus,
  onDisconnect,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const closeMenu = useCallback(() => {
    setIsOpen(false);
  }, []);

  const handleTriggerClick = () => {
    setIsOpen((prev) => !prev);
  };

  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      closeMenu();
      triggerRef.current?.focus();
    } else if ((event.key === 'Enter' || event.key === ' ') && event.target === triggerRef.current) {
      event.preventDefault();
      setIsOpen((prev) => !prev);
    }
  };

  useEffect(() => {
    const handleOutsideClick = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        closeMenu();
      }
    };

    document.addEventListener('mousedown', handleOutsideClick);
    return () => {
      document.removeEventListener('mousedown', handleOutsideClick);
    };
  }, [closeMenu]);

  const accountUsername = connectedAccount?.username ?? username;
  const isConnected = accountStatus === 'connected' && !!connectedAccount;

  const connectionLabel =
    accountStatus === 'unconnected'
      ? 'No Chess.com account connected'
      : accountStatus === 'loading'
        ? 'Checking Chess.com connection'
        : accountStatus === 'error'
          ? 'Unable to load Chess.com connection'
          : isConnected
            ? `Connected: ${accountUsername}`
            : 'Connected Chess.com account unavailable';

  const indicatorClass =
    accountStatus === 'connected'
      ? 'bg-secondary'
      : accountStatus === 'loading'
        ? 'bg-outline animate-pulse'
        : accountStatus === 'error'
          ? 'bg-error'
          : 'bg-outline';

  return (
    <div ref={containerRef} className="relative inline-block text-left" onKeyDown={handleKeyDown}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-label="Account menu"
        onClick={handleTriggerClick}
        className="w-9 h-9 rounded-lg border border-outline-variant bg-surface-container hover:bg-surface-container-high flex items-center justify-center text-on-surface transition cursor-pointer relative shrink-0"
      >
        <User className="w-4 h-4 text-primary" />
        <span className={`absolute top-1 right-1 w-2 h-2 rounded-full ${indicatorClass}`} />
      </button>

      {isOpen && (
        <div
          role="menu"
          aria-orientation="vertical"
          tabIndex={-1}
          className="absolute right-0 top-full mt-2 w-64 bg-surface-container-high border border-outline-variant rounded-lg shadow-xl p-3 z-50 flex flex-col space-y-2.5 text-xs font-mono"
        >
          <div className="flex flex-col space-y-1 pb-2 border-b border-outline-variant">
            <span className="text-on-surface font-semibold">
              {sessionStatus === 'error' ? 'Session error' : 'Signed in to ChessProbe'}
            </span>
            <div className="flex items-center space-x-1.5 text-on-surface-variant">
              <span className={`w-2 h-2 rounded-full shrink-0 ${indicatorClass}`} />
              <span className="truncate">{connectionLabel}</span>
            </div>
          </div>

          {onDisconnect && (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                closeMenu();
                onDisconnect();
              }}
              className="w-full flex items-center justify-between px-2.5 py-1.5 bg-surface-container hover:bg-surface-bright text-on-surface rounded border border-outline-variant transition cursor-pointer"
            >
              <span>Sign out</span>
              <LogOut className="w-3.5 h-3.5 text-outline" />
            </button>
          )}
        </div>
      )}
    </div>
  );
};
