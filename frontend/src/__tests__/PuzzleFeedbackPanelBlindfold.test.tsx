import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { PuzzleFeedbackPanel } from '../components/PuzzleFeedbackPanel';
import type { Puzzle } from '../mock/mockData';
import type { ExplorationPlayMode } from '../services/api';

const puzzle: Puzzle = {
  puzzleId: 'p1',
  fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1',
  playerColor: 'BLACK',
  targetMove: 'e5',
  openingTitle: "King's Pawn Opening",
  acceptableMoves: [],
  movesPlayed: [{ move: 'a6', timesPlayed: 2, averageLoss: 0.8 }],
  priority: 1,
  timesReached: 10,
  mistakeCount: 2,
  mistakeRate: 20,
};

type Status = 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';

const renderPanel = (opts: {
  status: Status;
  isExplorationActive?: boolean;
  explorationPlayMode?: ExplorationPlayMode;
  onEnterBlindfold?: () => void;
  isBlindfoldEntryDisabled?: boolean;
}) =>
  render(
    <PuzzleFeedbackPanel
      puzzle={puzzle}
      feedback={{
        status: opts.status,
        lastMove: opts.status === 'IDLE' || opts.status === 'CORRECT' ? undefined : 'a6',
        historicalInfo: opts.status === 'HISTORICAL_MISTAKE' ? { timesPlayed: 2, averageLoss: 0.8 } : undefined,
      }}
      onNextPuzzle={() => {}}
      onPreviousPuzzle={() => {}}
      puzzleColorFilter="BOTH"
      onColorFilterChange={() => {}}
      showPuzzleSettings={false}
      onTogglePuzzleSettings={() => {}}
      minMistakeCount={1}
      onMinMistakeCountChange={() => {}}
      onApplySettings={() => {}}
      onEnterExploration={() => {}}
      onExitExploration={() => {}}
      isExplorationActive={opts.isExplorationActive ?? false}
      explorationPlayMode={opts.explorationPlayMode}
      onEnterBlindfold={'onEnterBlindfold' in opts ? opts.onEnterBlindfold : () => {}}
      isBlindfoldEntryDisabled={opts.isBlindfoldEntryDisabled}
    />,
  );

const blindfoldButtons = () => screen.queryAllByRole('button', { name: /train blindfold/i });

const groupOf = (el: HTMLElement) => el.closest('[data-testid="exploration-actions"]');

describe('PuzzleFeedbackPanel blindfold entry', () => {
  it('does not offer blindfold on an idle puzzle', () => {
    renderPanel({ status: 'IDLE' });
    expect(blindfoldButtons()).toHaveLength(0);
  });

  it('does not offer blindfold when no entry handler is provided', () => {
    renderPanel({ status: 'CORRECT', onEnterBlindfold: undefined });
    expect(blindfoldButtons()).toHaveLength(0);
  });

  it('groups blindfold with Continue Exploration on the solved card, separate from puzzle navigation', () => {
    renderPanel({ status: 'CORRECT' });
    const [button] = blindfoldButtons();
    expect(blindfoldButtons()).toHaveLength(1);
    const group = groupOf(button);
    expect(group).not.toBeNull();
    expect(group).toContainElement(screen.getByRole('button', { name: /continue exploration/i }));
    expect(group).not.toContainElement(screen.getByRole('button', { name: /next puzzle/i }));
    expect(group).not.toContainElement(screen.getByRole('button', { name: /prev puzzle/i }));
  });

  it.each(['INCORRECT', 'HISTORICAL_MISTAKE'] as const)(
    'groups blindfold with Explore this decision on the %s card',
    (status) => {
      renderPanel({ status });
      const [button] = blindfoldButtons();
      expect(blindfoldButtons()).toHaveLength(1);
      expect(groupOf(button)).toContainElement(screen.getByRole('button', { name: /explore this decision/i }));
    },
  );

  it.each([
    ['mode selection', undefined],
    ['an active mode', 'CHESSECHO' as const],
  ])('places blindfold beside Exit in the Line Exploration header during %s', (_label, mode) => {
    renderPanel({ status: 'CORRECT', isExplorationActive: true, explorationPlayMode: mode });
    const buttons = blindfoldButtons();
    expect(buttons).toHaveLength(1);
    expect(groupOf(buttons[0])).toContainElement(screen.getByRole('button', { name: /^exit$/i }));
  });

  it('calls the entry handler', () => {
    const onEnterBlindfold = vi.fn();
    renderPanel({ status: 'CORRECT', onEnterBlindfold });
    fireEvent.click(blindfoldButtons()[0]);
    expect(onEnterBlindfold).toHaveBeenCalledTimes(1);
  });

  it('disables entry when requested', () => {
    const onEnterBlindfold = vi.fn();
    renderPanel({ status: 'CORRECT', isExplorationActive: true, explorationPlayMode: 'CHESSECHO', onEnterBlindfold, isBlindfoldEntryDisabled: true });
    const [button] = blindfoldButtons();
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onEnterBlindfold).not.toHaveBeenCalled();
  });
});
