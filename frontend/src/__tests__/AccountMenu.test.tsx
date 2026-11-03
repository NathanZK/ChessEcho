import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AccountMenu } from '../components/AccountMenu';
import type { AccountSummary } from '../services/api';

describe('AccountMenu Component', () => {
  const mockConnectedAccount: AccountSummary = {
    id: 'test-account-id',
    username: 'MagnusC',
    platform: 'CHESS_COM',
  };

  const defaultProps = {
    sessionStatus: 'authenticated' as const,
    accountStatus: 'connected' as const,
    connectedAccount: mockConnectedAccount,
    onDisconnect: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('Trigger Button & Sizing', () => {
    it('renders a compact trigger button with WAI-ARIA attributes', () => {
      render(<AccountMenu {...defaultProps} />);

      const trigger = screen.getByRole('button', { name: /Account menu/i });
      expect(trigger).toBeInTheDocument();
      expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
      // Verify compact styling classes: w-9 (36px) and h-9 (36px), strictly <= 40px
      expect(trigger).toHaveClass('w-9', 'h-9');
    });

    it('displays appropriate connection status indicator dot on trigger', () => {
      const { rerender, container } = render(
        <AccountMenu {...defaultProps} accountStatus="connected" />
      );
      expect(container.querySelector('.bg-secondary')).toBeInTheDocument();

      rerender(<AccountMenu {...defaultProps} accountStatus="unconnected" connectedAccount={undefined} />);
      expect(container.querySelector('.bg-outline')).toBeInTheDocument();

      rerender(<AccountMenu {...defaultProps} accountStatus="loading" />);
      expect(container.querySelector('.animate-pulse')).toBeInTheDocument();

      rerender(<AccountMenu {...defaultProps} accountStatus="error" />);
      expect(container.querySelector('.bg-error')).toBeInTheDocument();
    });
  });

  describe('Menu Panel Display & Content', () => {
    it('does not render the dropdown menu panel by default', () => {
      render(<AccountMenu {...defaultProps} />);
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    });

    it('toggles menu open and closed on trigger click', () => {
      render(<AccountMenu {...defaultProps} />);
      const trigger = screen.getByRole('button', { name: /Account menu/i });

      fireEvent.click(trigger);
      expect(trigger).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByRole('menu')).toBeInTheDocument();

      fireEvent.click(trigger);
      expect(trigger).toHaveAttribute('aria-expanded', 'false');
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    });

    it('displays session and connection details for connected account', () => {
      render(<AccountMenu {...defaultProps} />);
      fireEvent.click(screen.getByRole('button', { name: /Account menu/i }));

      expect(screen.getByText('Signed in to ChessProbe')).toBeInTheDocument();
      expect(screen.getByText('Connected: MagnusC')).toBeInTheDocument();
      const signOutButton = screen.getByRole('menuitem', { name: 'Sign out' });
      expect(signOutButton).toBeInTheDocument();

      fireEvent.click(signOutButton);
      expect(defaultProps.onDisconnect).toHaveBeenCalledTimes(1);
    });

    it('displays unconnected state when no account is connected', () => {
      render(
        <AccountMenu
          sessionStatus="authenticated"
          accountStatus="unconnected"
          connectedAccount={undefined}
          onDisconnect={vi.fn()}
        />
      );
      fireEvent.click(screen.getByRole('button', { name: /Account menu/i }));

      expect(screen.getByText('Signed in to ChessProbe')).toBeInTheDocument();
      expect(screen.getByText('No Chess.com account connected')).toBeInTheDocument();
    });

    it('displays loading and error connection messages', () => {
      const { rerender } = render(
        <AccountMenu
          sessionStatus="authenticated"
          accountStatus="loading"
          onDisconnect={vi.fn()}
        />
      );
      fireEvent.click(screen.getByRole('button', { name: /Account menu/i }));
      expect(screen.getByText('Checking Chess.com connection')).toBeInTheDocument();

      rerender(
        <AccountMenu
          sessionStatus="authenticated"
          accountStatus="error"
          onDisconnect={vi.fn()}
        />
      );
      expect(screen.getByText('Unable to load Chess.com connection')).toBeInTheDocument();
    });
  });

  describe('Keyboard Navigation & Accessibility', () => {
    it('opens menu with Enter and Space keys', () => {
      render(<AccountMenu {...defaultProps} />);
      const trigger = screen.getByRole('button', { name: /Account menu/i });

      fireEvent.keyDown(trigger, { key: 'Enter' });
      expect(screen.getByRole('menu')).toBeInTheDocument();

      fireEvent.keyDown(trigger, { key: 'Enter' });
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();

      fireEvent.keyDown(trigger, { key: ' ' });
      expect(screen.getByRole('menu')).toBeInTheDocument();
    });

    it('closes menu and restores focus to trigger on Escape key', () => {
      render(<AccountMenu {...defaultProps} />);
      const trigger = screen.getByRole('button', { name: /Account menu/i });
      trigger.focus();

      fireEvent.click(trigger);
      const menu = screen.getByRole('menu');
      expect(menu).toBeInTheDocument();

      fireEvent.keyDown(menu, { key: 'Escape' });
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
      expect(document.activeElement).toBe(trigger);
    });

    it('allows native keyboard activation on menu items without intercepting Enter or Space', () => {
      render(<AccountMenu {...defaultProps} />);
      fireEvent.click(screen.getByRole('button', { name: /Account menu/i }));

      const signOut = screen.getByRole('menuitem', { name: 'Sign out' });
      fireEvent.click(signOut);
      expect(defaultProps.onDisconnect).toHaveBeenCalledTimes(1);
    });
  });

  describe('Outside Click & Cleanup', () => {
    it('closes menu when clicking outside', () => {
      render(
        <div>
          <button type="button" data-testid="outside-element">Outside</button>
          <AccountMenu {...defaultProps} />
        </div>
      );

      fireEvent.click(screen.getByRole('button', { name: /Account menu/i }));
      expect(screen.getByRole('menu')).toBeInTheDocument();

      fireEvent.mouseDown(screen.getByTestId('outside-element'));
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    });

    it('cleans up outside click listener on unmount', () => {
      const removeEventListenerSpy = vi.spyOn(document, 'removeEventListener');
      const { unmount } = render(<AccountMenu {...defaultProps} />);

      unmount();
      expect(removeEventListenerSpy).toHaveBeenCalledWith('mousedown', expect.any(Function));
    });
  });
});
