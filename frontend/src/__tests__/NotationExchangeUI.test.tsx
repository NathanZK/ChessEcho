import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { NotationExchangeUI } from '../components/NotationExchangeUI';
import { createBlinfoldGameState } from '../utils/blindfoldGameState';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

describe('NotationExchangeUI', () => {
  it('renders "Reveal board" when board is hidden and calls onReveal or onToggle', () => {
    const state = createBlinfoldGameState(START_FEN, 'WHITE');
    const onReveal = vi.fn();
    const onHide = vi.fn();
    const onToggle = vi.fn();

    render(
      <NotationExchangeUI
        state={state}
        onPlayerMove={vi.fn()}
        onReveal={onReveal}
        onHide={onHide}
        onToggleVisibility={onToggle}
        onReset={vi.fn()}
      />,
    );

    const button = screen.getByRole('button', { name: 'Reveal board' });
    expect(button).toBeInTheDocument();
    fireEvent.click(button);
    expect(onReveal).toHaveBeenCalled();
  });

  it('renders "Hide board" when board is visible and calls onHide or onToggle', () => {
    const state = { ...createBlinfoldGameState(START_FEN, 'WHITE'), isVisible: true };
    const onReveal = vi.fn();
    const onHide = vi.fn();
    const onToggle = vi.fn();

    render(
      <NotationExchangeUI
        state={state}
        onPlayerMove={vi.fn()}
        onReveal={onReveal}
        onHide={onHide}
        onToggleVisibility={onToggle}
        onReset={vi.fn()}
      />,
    );

    const button = screen.getByRole('button', { name: 'Hide board' });
    expect(button).toBeInTheDocument();
    fireEvent.click(button);
    expect(onHide).toHaveBeenCalled();
  });

  it('renders move quality feedback when available on lastPlayerMove', () => {
    const state = {
      ...createBlinfoldGameState(START_FEN, 'WHITE'),
      lastPlayerMove: {
        san: 'e4',
        evalLoss: 0,
        acceptable: true,
        feedbackText: 'Best move!',
      },
    };

    render(
      <NotationExchangeUI
        state={state}
        onPlayerMove={vi.fn()}
        onReveal={vi.fn()}
        onReset={vi.fn()}
      />,
    );

    expect(screen.getByText('Best move!')).toBeInTheDocument();
    expect(screen.getByText(/e4:/)).toBeInTheDocument();
  });

  it('renders terminal banner and disables input when game is terminal', () => {
    const state = {
      ...createBlinfoldGameState(START_FEN, 'WHITE'),
      isTerminal: true,
      terminalReason: 'CHECKMATE' as const,
      winner: 'PLAYER' as const,
    };

    render(
      <NotationExchangeUI
        state={state}
        onPlayerMove={vi.fn()}
        onReveal={vi.fn()}
        onReset={vi.fn()}
      />,
    );

    expect(screen.getByText('Checkmate! You won.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Move notation' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('renders draw announcement on stalemate', () => {
    const state = {
      ...createBlinfoldGameState(START_FEN, 'WHITE'),
      isTerminal: true,
      terminalReason: 'STALEMATE' as const,
      winner: 'DRAW' as const,
    };

    render(
      <NotationExchangeUI
        state={state}
        onPlayerMove={vi.fn()}
        onReveal={vi.fn()}
        onReset={vi.fn()}
      />,
    );

    expect(screen.getByText('Draw by stalemate.')).toBeInTheDocument();
  });
});
