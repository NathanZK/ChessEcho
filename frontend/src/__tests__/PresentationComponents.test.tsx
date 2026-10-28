import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { PositionCard } from '../components/PositionCard';
import { ExternalGameLink } from '../components/ExternalGameLink';
import { StatusBanner } from '../components/StatusBanner';
import { SectionHeader } from '../components/SectionHeader';

describe('Presentation Components', () => {
  describe('PositionCard', () => {
    it('renders board, title, metadata, and actions', () => {
      render(
        <PositionCard
          board={<div data-testid="test-board">Board</div>}
          title={<h3>Test Position Title</h3>}
          metadata={<div data-testid="test-metadata">Metadata details</div>}
          actions={<button type="button">Practice</button>}
        />
      );

      expect(screen.getByTestId('test-board')).toBeInTheDocument();
      expect(screen.getByText('Test Position Title')).toBeInTheDocument();
      expect(screen.getByTestId('test-metadata')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Practice' })).toBeInTheDocument();
    });

    it('merges custom className on card container', () => {
      const { container } = render(
        <PositionCard
          board={<div>Board</div>}
          title={<h3>Title</h3>}
          className="custom-card-class"
        />
      );
      expect(container.querySelector('.custom-card-class')).toBeInTheDocument();
    });
  });

  describe('ExternalGameLink', () => {
    it('renders external link with target=_blank and rel=noopener noreferrer', () => {
      render(
        <ExternalGameLink
          url="https://www.chess.com/game/live/12345"
          label="View on Chess.com"
          platform="chesscom"
        />
      );

      const link = screen.getByRole('link', { name: /View on Chess.com/i });
      expect(link).toHaveAttribute('href', 'https://www.chess.com/game/live/12345');
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    });

    it('uses default label when label is not provided', () => {
      render(
        <ExternalGameLink
          url="https://lichess.org/abcd1234"
          platform="lichess"
        />
      );

      const link = screen.getByRole('link');
      expect(link).toHaveAttribute('target', '_blank');
      expect(link.textContent).toMatch(/Lichess/i);
    });
  });

  describe('StatusBanner', () => {
    it('renders loading variant with spinner and message', () => {
      render(<StatusBanner variant="loading" message="Loading positions..." />);
      expect(screen.getByText('Loading positions...')).toBeInTheDocument();
    });

    it('renders empty variant with placeholder and message', () => {
      render(
        <StatusBanner
          variant="empty"
          title="No positions found"
          message="Try adjusting your filters or import games."
        />
      );
      expect(screen.getByText('No positions found')).toBeInTheDocument();
      expect(screen.getByText('Try adjusting your filters or import games.')).toBeInTheDocument();
    });

    it('renders error variant with role=alert and optional retry action', () => {
      const onRetry = vi.fn();
      render(
        <StatusBanner
          variant="error"
          title="Failed to load"
          message="Network timeout error."
          action={{ label: 'Retry', onClick: onRetry }}
        />
      );

      const alert = screen.getByRole('alert');
      expect(alert).toBeInTheDocument();
      expect(screen.getByText('Failed to load')).toBeInTheDocument();
      expect(screen.getByText('Network timeout error.')).toBeInTheDocument();

      const retryBtn = screen.getByRole('button', { name: 'Retry' });
      fireEvent.click(retryBtn);
      expect(onRetry).toHaveBeenCalledTimes(1);
    });
  });

  describe('SectionHeader', () => {
    it('renders section title and description', () => {
      render(
        <SectionHeader
          title="Weakness Library"
          description="Catalog of recurrent mistakes identified from your games."
          badgeText="12 detected"
          actions={<button type="button">Refresh</button>}
        />
      );

      expect(screen.getByText('Weakness Library')).toBeInTheDocument();
      expect(screen.getByText('Catalog of recurrent mistakes identified from your games.')).toBeInTheDocument();
      expect(screen.getByText('12 detected')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
    });
  });
});
