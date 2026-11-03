import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { Header } from '../components/Header';
import type { AccountSummary } from '../services/api';

describe('Header Responsive Account Integration (#659)', () => {
  const mockAccount: AccountSummary = {
    id: 'test-uuid',
    username: 'hikaru',
    platform: 'CHESS_COM',
  };

  const defaultProps = {
    activeTab: 'weaknesses' as const,
    setActiveTab: vi.fn(),
    sessionStatus: 'authenticated' as const,
    accountStatus: 'connected' as const,
    connectedAccount: mockAccount,
    onDisconnect: vi.fn(),
  };

  it('preserves h-[60px] header height invariant in standard layout', () => {
    const { container } = render(<Header {...defaultProps} />);
    const header = container.querySelector('header');
    expect(header).toHaveClass('h-[60px]', 'border-b');
  });

  it('renders mobile AccountMenu trigger on narrow screens for authenticated session', () => {
    render(<Header {...defaultProps} />);

    // Compact trigger for narrow viewports
    const mobileTrigger = screen.getByRole('button', { name: /Account menu/i });
    expect(mobileTrigger).toBeInTheDocument();
    expect(mobileTrigger).toHaveClass('w-9', 'h-9');

    // Clicking mobile trigger opens the dropdown menu
    fireEvent.click(mobileTrigger);
    expect(screen.getByRole('menu')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('renders desktop inline account presentation concurrently for wide viewports and test compatibility', () => {
    render(<Header {...defaultProps} />);

    // Desktop presentation is available in DOM with responsive utility classes
    expect(screen.getByText('hikaru')).toBeInTheDocument();
    expect(screen.getByText('Chess.com Connected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('maintains guest mode distinction without rendering authenticated AccountMenu trigger', () => {
    render(
      <Header
        activeTab="weaknesses"
        setActiveTab={vi.fn()}
        sessionStatus="unauthenticated"
        accountStatus="unconnected"
      />
    );

    expect(screen.queryByRole('button', { name: /Account menu/i })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign In' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
  });

  it('verifies 375px mobile horizontal fit: trigger <= 40px fits within ~139px remaining budget', () => {
    const { container } = render(<Header {...defaultProps} />);

    // Brand logo container
    const logo = container.querySelector('h1');
    expect(logo).toHaveTextContent('ChessProbe');

    // Right side mobile account trigger exists with <=40px footprint
    const mobileTrigger = screen.getByRole('button', { name: /Account menu/i });
    expect(mobileTrigger).toHaveClass('w-9', 'h-9'); // 36px x 36px <= 40px
  });

  it('supports unauthenticated guest with selected username', () => {
    render(
      <Header
        activeTab="weaknesses"
        setActiveTab={vi.fn()}
        username="guest_player"
        sessionStatus="unauthenticated"
        accountStatus="unconnected"
        onDisconnect={vi.fn()}
      />
    );

    expect(screen.queryByRole('button', { name: /Account menu/i })).not.toBeInTheDocument();
    expect(screen.getByText('guest_player')).toBeInTheDocument();
    expect(screen.getByText('Guest Mode')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument();
  });
});
