import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest';
import { HistoricalGamesModal } from '../components/HistoricalGamesModal';

describe('HistoricalGamesModal', () => {
  const mockUrls = [
    'https://www.chess.com/game/live/172923974924',
    'https://www.chess.com/game/daily/123456789',
  ];

  const mockOnClose = vi.fn();

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the modal with source games', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} />
    );

    expect(screen.getByText('Source Games')).toBeInTheDocument();
    expect(screen.getByText('Game #1: https://www.chess.com/game/live/172923974924')).toBeInTheDocument();
    expect(screen.getByText('Game #2: https://www.chess.com/game/daily/123456789')).toBeInTheDocument();
  });

  it('renders the Analyze on 6chess link when username is provided', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} username="testuser" />
    );

    const analyzeLinks = screen.getAllByText('Analyze on 6chess');
    expect(analyzeLinks).toHaveLength(2);
    
    // Check that the first analyze link has the correct href
    const firstAnalyzeLink = analyzeLinks[0].closest('a');
    expect(firstAnalyzeLink).toHaveAttribute('href', 'https://www.6chess.com/game/live/172923974924?username=testuser&move=0');
  });

  it('does not render Analyze on 6chess link when username is not provided', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} />
    );

    expect(screen.queryByText('Analyze on 6chess')).not.toBeInTheDocument();
  });

  it('does not render Analyze on 6chess link for non-Chess.com URLs', () => {
    const mixedUrls = [
      'https://www.chess.com/game/live/172923974924',
      'https://www.lichess.org/game/abc123',
    ];

    render(
      <HistoricalGamesModal urls={mixedUrls} onClose={mockOnClose} username="testuser" />
    );

    // Should only have one Analyze link for the Chess.com URL
    const analyzeLinks = screen.getAllByText('Analyze on 6chess');
    expect(analyzeLinks).toHaveLength(1);
  });

  it('opens the original Chess.com game in a new tab', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} />
    );

    const gameLink = screen.getByText('Game #1: https://www.chess.com/game/live/172923974924').closest('a');
    expect(gameLink).toHaveAttribute('href', 'https://www.chess.com/game/live/172923974924');
    expect(gameLink).toHaveAttribute('target', '_blank');
    expect(gameLink).toHaveAttribute('rel', 'noreferrer');
  });

  it('opens the 6chess analysis in a new tab', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} username="testuser" />
    );

    const analyzeLinks = screen.getAllByText('Analyze on 6chess');
    expect(analyzeLinks.length).toBeGreaterThan(0);
    const analyzeLink = analyzeLinks[0].closest('a');
    expect(analyzeLink).toHaveAttribute('target', '_blank');
    expect(analyzeLink).toHaveAttribute('rel', 'noreferrer');
  });

  it('calls onClose when Close button is clicked', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} />
    );

    const closeButton = screen.getByText('Close');
    fireEvent.click(closeButton);

    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when X button is clicked', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} />
    );

    const closeButton = screen.getByLabelText('Close modal');
    fireEvent.click(closeButton);

    expect(mockOnClose).toHaveBeenCalledTimes(1);
  });

  it('renders nothing when urls array is empty', () => {
    const { container } = render(
      <HistoricalGamesModal urls={[]} onClose={mockOnClose} />
    );

    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when urls is null', () => {
    const { container } = render(
      <HistoricalGamesModal urls={null as any} onClose={mockOnClose} />
    );

    expect(container.firstChild).toBeNull();
  });

  it('preserves original Open Game behavior', () => {
    render(
      <HistoricalGamesModal urls={mockUrls} onClose={mockOnClose} username="testuser" />
    );

    // Both original game links should still be present
    const gameLinks = screen.getAllByText(/Game #\d+:/);
    expect(gameLinks).toHaveLength(2);
    
    // First game link should still point to Chess.com
    const firstGameLink = gameLinks[0].closest('a');
    expect(firstGameLink).toHaveAttribute('href', 'https://www.chess.com/game/live/172923974924');
  });
});
