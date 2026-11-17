import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { GameCard } from '../components/GameCard';
import type { GameSummary } from '../services/api';

vi.mock('../components/ChessBoardView', () => ({
  ChessBoardView: ({ fen }: { fen: string }) => (
    <div data-testid="mock-chessboard-view" data-fen={fen}>
      Board preview
    </div>
  ),
}));

describe('GameCard', () => {
  const baseGame: GameSummary = {
    id: 'game-123',
    platformGameId: '172923974924',
    timeControl: 'RAPID',
    playedAt: '2026-11-01T12:00:00Z',
    result: 'win',
    whiteUsername: 'HeroPlayer',
    blackUsername: 'GrandmasterRival',
    pgn: '1. e4 e5 2. Nf3 Nc6',
  };

  it('renders players, result, date, and resolved time-control badge', () => {
    const onSelect = vi.fn();
    render(<GameCard game={baseGame} onSelectGame={onSelect} />);

    expect(screen.getByText('HeroPlayer')).toBeInTheDocument();
    expect(screen.getByText('GrandmasterRival')).toBeInTheDocument();
    expect(screen.getByText('win')).toBeInTheDocument();
    expect(screen.getByText('RAPID')).toBeInTheDocument();
    expect(screen.getByTestId('mock-chessboard-view')).toBeInTheDocument();
  });

  it('renders external Chess.com link when valid platformGameId exists', () => {
    const onSelect = vi.fn();
    render(<GameCard game={baseGame} onSelectGame={onSelect} />);

    const link = screen.getByRole('link', { name: /View on Chess\.com/i });
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', 'https://www.chess.com/game/live/172923974924?move=0');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('gracefully omits external link when platformGameId is not a valid URL or numeric ID', () => {
    const onSelect = vi.fn();
    const gameWithoutUrl: GameSummary = {
      ...baseGame,
      platformGameId: '',
    };
    render(<GameCard game={gameWithoutUrl} onSelectGame={onSelect} />);

    expect(screen.queryByRole('link', { name: /View on Chess\.com/i })).not.toBeInTheDocument();
  });

  it('triggers onSelectGame when Analyze button is clicked', () => {
    const onSelect = vi.fn();
    render(<GameCard game={baseGame} onSelectGame={onSelect} />);

    const analyzeBtn = screen.getByRole('button', { name: /Analyze/i });
    fireEvent.click(analyzeBtn);

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('game-123');
  });
});
