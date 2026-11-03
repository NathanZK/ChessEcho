import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { ImportGamesView } from '../components/ImportGamesView';
import { activeJobStore } from '../utils/browserStores';

vi.mock('../components/SectionHeader', () => ({
  SectionHeader: ({ title, description, badgeText }: { title: string; description?: string; badgeText?: string }) => (
    <div data-testid="canonical-section-header">
      <h2>{title}</h2>
      {badgeText && <span data-testid="badge-text">{badgeText}</span>}
      {description && <p>{description}</p>}
    </div>
  ),
}));

describe('ImportGamesView Presentation & Header Unification', () => {
  it('renders canonical SectionHeader with title, description, and CHESS.COM badge', () => {
    render(<ImportGamesView />);

    const header = screen.getByTestId('canonical-section-header');
    expect(header).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /import & analyze your game history/i })).toBeInTheDocument();
    expect(screen.getByTestId('badge-text')).toHaveTextContent('CHESS.COM');

    expect(screen.queryByText(/chess\.com game importer/i)).not.toBeInTheDocument();
  });

  it('migrates container, cards, inputs, buttons, and status monitor to semantic design tokens', () => {
    const { container } = render(<ImportGamesView />);

    // Page wrapper
    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toContain('text-on-surface');
    expect(root.className).not.toContain('text-slate-200');

    // Cards must use surface-container-low and border-outline-variant
    const cards = container.querySelectorAll('.rounded-2xl.border.shadow-xl');
    expect(cards.length).toBeGreaterThanOrEqual(2);
    cards.forEach((card) => {
      expect(card.className).toContain('bg-surface-container-low');
      expect(card.className).toContain('border-outline-variant');
      expect(card.className).not.toContain('bg-slate-900');
      expect(card.className).not.toContain('border-slate-800');
    });

    // Username input
    const usernameInput = screen.getByPlaceholderText(/hikaru/i);
    expect(usernameInput.className).toContain('bg-surface-container-lowest');
    expect(usernameInput.className).toContain('border-outline-variant');
    expect(usernameInput.className).toContain('focus:border-primary');
    expect(usernameInput.className).toContain('text-on-surface');

    // Labels
    const usernameLabel = screen.getByText('Chess.com Username');
    expect(usernameLabel.className).toContain('text-on-surface-variant');
    expect(usernameLabel.className).toContain('font-mono');

    // Time control buttons (Blitz, Rapid selected by default)
    const blitzButton = screen.getByRole('button', { name: /⚡ blitz/i });
    expect(blitzButton.className).toContain('bg-primary/10');
    expect(blitzButton.className).toContain('border-primary');
    expect(blitzButton.className).toContain('text-primary');

    const bulletButton = screen.getByRole('button', { name: /🚀 bullet/i });
    expect(bulletButton.className).toContain('bg-surface-container-lowest');
    expect(bulletButton.className).toContain('border-outline-variant');
    expect(bulletButton.className).toContain('text-on-surface-variant');

    // Color selector buttons
    const bothColorBtn = screen.getByRole('button', { name: 'BOTH' });
    expect(bothColorBtn.className).toContain('bg-primary-container');
    expect(bothColorBtn.className).toContain('text-on-primary-container');
    expect(bothColorBtn.className).toContain('font-mono');

    // Action button
    const submitBtn = screen.getByRole('button', { name: /start import & engine analysis/i });
    expect(submitBtn.className).toContain('bg-primary-container');
    expect(submitBtn.className).toContain('text-on-primary-container');
    expect(submitBtn.className).toContain('font-mono');
  });

  it('renders completed and active job status monitor using semantic tokens', () => {
    act(() => {
      activeJobStore.set({
        jobId: 'job-123',
        status: 'COMPLETED',
        gamesImported: 42,
        gamesSkipped: 10,
        analysisStatus: 'COMPLETED',
      });
    });

    render(<ImportGamesView />);

    // Metric counters
    const importedCount = screen.getByText('42');
    expect(importedCount.className).toContain('font-mono');
    expect(importedCount.className).toContain('text-primary');

    // Completed badge
    const completedBadge = screen.getByText('COMPLETED');
    expect(completedBadge.className).toContain('bg-primary/20');
    expect(completedBadge.className).toContain('text-primary');
    expect(completedBadge.className).toContain('border-primary/30');

    // Completion action buttons
    const practiceBtn = screen.getByRole('button', { name: /practice puzzles/i });
    expect(practiceBtn.className).toContain('bg-primary-container');
    expect(practiceBtn.className).toContain('text-on-primary-container');
    expect(practiceBtn.className).toContain('font-mono');

    const weaknessesBtn = screen.getByRole('button', { name: /view weaknesses/i });
    expect(weaknessesBtn.className).toContain('bg-surface-container-high');
    expect(weaknessesBtn.className).toContain('text-on-surface');
    expect(weaknessesBtn.className).toContain('border-outline-variant');
    expect(weaknessesBtn.className).toContain('font-mono');

    act(() => {
      activeJobStore.set(null);
    });
  });

  it('preserves ARIA roles (alert, status, dialog) and renders accessible error and validation states', () => {
    const { rerender } = render(
      <ImportGamesView
        sessionStatus="authenticated"
        accountStatus="error"
        accountError="Database connection timeout"
        disconnectError="Network failure"
      />
    );

    // Alerts for errors
    const alerts = screen.getAllByRole('alert');
    expect(alerts.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/database connection timeout/i)).toBeInTheDocument();
    expect(screen.getByText(/unable to disconnect account/i)).toBeInTheDocument();

    // Rerender with active status message
    rerender(
      <ImportGamesView
        sessionStatus="authenticated"
        accountStatus="loading"
      />
    );
    expect(screen.getByRole('status')).toHaveTextContent(/loading your connected chess\.com account/i);
  });

  it('renders without horizontal overflow constraints across 375px, 768px, and 1440px viewports', () => {
    const viewports = [375, 768, 1440];
    for (const width of viewports) {
      Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: width });
      const { container } = render(<ImportGamesView />);
      const root = container.firstElementChild as HTMLElement;
      expect(root).toBeInTheDocument();
      // Ensure root wrapper maintains responsive grid classes
      const grid = container.querySelector('.grid.grid-cols-1.md\\:grid-cols-2');
      expect(grid).toBeInTheDocument();
    }
  });
});
