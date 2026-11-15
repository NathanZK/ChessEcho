import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { LineExplorationWorkbench, formatCalculationLine } from '../components/LineExplorationWorkbench';

describe('LineExplorationWorkbench', () => {
  it('formats alternating calculation lines with standard move numbers', () => {
    const line = [
      { san: 'e4', isWhite: true },
      { san: 'e5', isWhite: false },
      { san: 'Nf3', isWhite: true },
    ];
    expect(formatCalculationLine(line)).toBe('1. e4 e5 2. Nf3');
  });

  it('formats black-first calculation lines with ellipsis', () => {
    const line = [
      { san: 'c5', isWhite: false },
      { san: 'Nf3', isWhite: true },
    ];
    expect(formatCalculationLine(line)).toBe('1... c5 2. Nf3');
  });

  it('renders mode selection view when explorationPlayMode is undefined', () => {
    const handleModeChange = vi.fn();
    render(
      <LineExplorationWorkbench
        activeColorToMove="White"
        onExplorationPlayModeChange={handleModeChange}
      />
    );

    expect(screen.getByText('Choose how you want to explore')).toBeInTheDocument();
    const vsEngineBtn = screen.getByRole('button', { name: /vs ChessProbe/i });
    fireEvent.click(vsEngineBtn);
    expect(handleModeChange).toHaveBeenCalledWith('CHESSPROBE');
  });

  it('renders active vs ChessProbe mode with exit action', () => {
    const handleExit = vi.fn();
    render(
      <LineExplorationWorkbench
        activeColorToMove="White"
        explorationPlayMode="CHESSPROBE"
        onExitExploration={handleExit}
      />
    );

    expect(screen.getByText(/Your turn — explore a move/i)).toBeInTheDocument();
    const exitBtn = screen.getByRole('button', { name: /exit/i });
    fireEvent.click(exitBtn);
    expect(handleExit).toHaveBeenCalledTimes(1);
  });

  it('renders Play Both Sides mode indicator correctly', () => {
    render(
      <LineExplorationWorkbench
        activeColorToMove="Black"
        explorationPlayMode="BOTH_SIDES"
        playerColor="WHITE"
      />
    );

    expect(screen.getByTestId('play-both-sides-badge')).toBeInTheDocument();
    expect(screen.getByText(/Black to move/i)).toBeInTheDocument();
    expect(screen.getByText(/Opponent's turn — think like Black/i)).toBeInTheDocument();
  });

  it('renders challenge mode candidates and triggers selection callback', () => {
    const handleSelect = vi.fn();
    render(
      <LineExplorationWorkbench
        activeColorToMove="White"
        explorationPlayMode="CHALLENGE"
        challengeSubmission={{
          foundCount: 1,
          targetCount: 2,
          isComplete: false,
          moves: [
            { san: 'Bc4', status: 'strong' },
            { san: 'h3', status: 'weak' },
          ],
        }}
        onChallengeCandidateSelect={handleSelect}
      />
    );

    const strongCandidate = screen.getByRole('button', { name: /Bc4/i });
    fireEvent.click(strongCandidate);
    expect(handleSelect).toHaveBeenCalledWith('Bc4');

    const weakCandidate = screen.getByRole('button', { name: /h3/i });
    expect(weakCandidate).toBeDisabled();
  });
});
