'use client';

import React from 'react';
import { User } from 'lucide-react';
import type { AccountSummary } from '../services/api';
import type { HeaderAccountStatus as HeaderAccountStatusType, HeaderSessionStatus } from './Header';

export interface HeaderAccountStatusProps {
  sessionStatus?: HeaderSessionStatus;
  accountStatus: HeaderAccountStatusType;
  connectedAccount?: AccountSummary;
  username?: string;
  onDisconnect?: () => void;
}

interface ConnectedViewProps {
  sessionStatus?: HeaderSessionStatus;
  accountUsername?: string;
  showConnected: boolean;
  showGuestSelection: boolean;
  onDisconnect?: () => void;
}

const ConnectedView: React.FC<ConnectedViewProps> = ({
  sessionStatus,
  accountUsername,
  showConnected,
  showGuestSelection,
  onDisconnect,
}) => (
  <div
    className={`items-center space-x-2 ${
      sessionStatus === 'authenticated' ? 'hidden md:flex' : 'flex'
    }`}
  >
    <div className="flex items-center space-x-2 px-3 py-1 bg-surface-container border border-outline-variant rounded text-xs font-mono text-on-surface-variant">
      <div className="flex items-center space-x-2">
        <span className="w-2 h-2 rounded-full bg-secondary" />
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
);

interface UnconnectedViewProps {
  sessionStatus?: HeaderSessionStatus;
  accountConnectionLabel: string;
  onDisconnect?: () => void;
}

const UnconnectedView: React.FC<UnconnectedViewProps> = ({
  sessionStatus,
  accountConnectionLabel,
  onDisconnect,
}) => (
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
    ) : sessionStatus === 'unauthenticated' ? (
      <span className="text-outline text-xs font-mono hidden sm:inline">
        Not Connected
      </span>
    ) : null}
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
);

function getAccountConnectionLabel(status: HeaderAccountStatusType): string {
  switch (status) {
    case 'unconnected':
      return 'No Chess.com account connected';
    case 'loading':
      return 'Checking Chess.com connection';
    case 'error':
      return 'Unable to load Chess.com connection';
    default:
      return 'Connected Chess.com account unavailable';
  }
}

export const HeaderAccountStatus: React.FC<HeaderAccountStatusProps> = ({
  sessionStatus,
  accountStatus,
  connectedAccount,
  username,
  onDisconnect,
}) => {
  const showConnected = sessionStatus === 'authenticated' && !!connectedAccount;
  const showGuestSelection = sessionStatus === 'unauthenticated' && !!username;
  const showAccount = showConnected || showGuestSelection;

  if (showAccount) {
    return (
      <ConnectedView
        sessionStatus={sessionStatus}
        accountUsername={showConnected ? connectedAccount?.username : username}
        showConnected={showConnected}
        showGuestSelection={showGuestSelection}
        onDisconnect={onDisconnect}
      />
    );
  }

  return (
    <UnconnectedView
      sessionStatus={sessionStatus}
      accountConnectionLabel={getAccountConnectionLabel(accountStatus)}
      onDisconnect={onDisconnect}
    />
  );
};
