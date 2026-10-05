import React, { Activity, StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { soundService } from '../services/soundService';
import type { Puzzle } from '../mock/mockData';
import { activeAccountStore } from '../utils/browserStores';

vi.mock('../services/api', async () => ({
  ...await vi.importActual<typeof import('../services/api')>('../services/api'),
  fetchCurrentSession: vi.fn(),
  fetchPuzzles: vi.fn(),
  submitTrainingAttempt: vi.fn(),
}));

vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: {
    options: { onPieceDrop: (move: { sourceSquare: string; targetSquare: string }) => boolean };
  }) => (
    <button onClick={() => options.onPieceDrop({ sourceSquare: 'e2', targetSquare: 'e4' })}>
      Play fixture best move
    </button>
  ),
}));

const puzzles: Puzzle[] = [1, 2, 3, 4].map((number) => ({
  puzzleId: `timer-puzzle-${number}`,
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  playerColor: 'WHITE',
  targetMove: 'e4',
  openingTitle: `Timer fixture ${number}`,
  acceptableMoves: [],
  movesPlayed: [],
  priority: 1,
  timesReached: 10,
  mistakeCount: 4,
  mistakeRate: 40,
}));

let played: string[];
let rejectPlayback: boolean;

async function advance(ms: number) {
  await act(async () => { vi.advanceTimersByTime(ms); });
}

async function openTraining(muted = false) {
  localStorage.setItem('chessecho_username', 'timer-fixture');
  localStorage.setItem('chessecho_sound_enabled', String(!muted));
  soundService.reset();
  const view = render(<StrictMode><Activity mode="visible"><Home /></Activity></StrictMode>);
  await screen.findByText('Timer fixture 1');
  fireEvent.click(screen.getByRole('button', { name: /puzzle settings/i }));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'] });
  await advance(10);
  return view;
}

async function start(mode: 'Stopwatch' | 'Countdown') {
  fireEvent.click(screen.getByRole('button', { name: mode }));
  await advance(1);
}

describe('Timed Training sound transitions in the production page', () => {
  beforeEach(() => {
    localStorage.clear();
    window.location.hash = '';
    vi.clearAllMocks();
    played = [];
    rejectPlayback = false;
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'unauthenticated' });
    vi.mocked(api.fetchPuzzles).mockResolvedValue(puzzles);
    vi.mocked(api.submitTrainingAttempt).mockImplementation(async (payload) => ({
      ...payload,
      recordedAt: '2026-10-05T12:00:00Z',
    }));
    vi.stubGlobal('Audio', class {
      currentTime = 0;
      preload = '';
      constructor(private src: string) {}
      play() {
        played.push(this.src);
        return rejectPlayback ? Promise.reject(new Error('Playback blocked')) : Promise.resolve();
      }
    });
  });

  afterEach(() => {
    cleanup();
    soundService.reset();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it.each(['Stopwatch', 'Countdown'] as const)('plays one start cue for %s despite repeated selection and updates', async (mode) => {
    await openTraining();
    await start(mode);
    expect(played).toEqual(['/sounds/move.wav']);
    fireEvent.click(screen.getByRole('button', { name: mode }));
    fireEvent.click(screen.getByTitle('Flip Board (X)'));
    await advance(1_000);
    expect(played).toEqual(['/sounds/move.wav']);
    expect(screen.getByText('0:01')).toBeInTheDocument();
  });

  it('preserves an attempt across effect teardown and re-setup without replaying its start', async () => {
    const view = await openTraining();
    await start('Stopwatch');
    await advance(1_000);
    view.rerender(<StrictMode><Activity mode="hidden"><Home /></Activity></StrictMode>);
    view.rerender(<StrictMode><Activity mode="visible"><Home /></Activity></StrictMode>);
    await advance(1_000);
    expect(played).toEqual(['/sounds/move.wav']);
    expect(api.submitTrainingAttempt).not.toHaveBeenCalled();
  });

  it('does not restart countdown when the submission account changes before replacement puzzles load', async () => {
    await openTraining();
    await start('Countdown');
    await advance(10_000);
    vi.mocked(api.fetchPuzzles).mockReturnValue(new Promise(() => {}));
    act(() => {
      activeAccountStore.set({ id: 'replacement-account', username: 'timer-fixture', platform: 'CHESS_COM' });
    });
    await advance(20_100);
    expect(played).toEqual(['/sounds/move.wav', '/sounds/completion.wav']);
    expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(1);
  });

  it('plays new starts for mode, puzzle and countdown duration changes, but not board reset', async () => {
    await openTraining();
    await start('Stopwatch');
    await start('Countdown');
    fireEvent.change(screen.getByRole('combobox', { name: 'Countdown allowed time' }), { target: { value: '15000' } });
    await advance(1);
    fireEvent.click(screen.getAllByTitle('Next Puzzle')[0]);
    await advance(1);
    expect(screen.getByText('Timer fixture 2')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('Reset Position'));
    fireEvent.change(screen.getByRole('combobox', { name: 'Countdown allowed time' }), { target: { value: '15000' } });
    await advance(1);
    expect(played).toEqual(Array(4).fill('/sounds/move.wav'));
  });

  it('plays one end cue at zero and does not replay it after ticks or effect re-setup', async () => {
    const view = await openTraining();
    await start('Countdown');
    await advance(30_100);
    expect(played).toEqual(['/sounds/move.wav', '/sounds/completion.wav']);
    expect(screen.getByText("Time's up")).toBeInTheDocument();
    expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(1);
    expect(api.submitTrainingAttempt).toHaveBeenCalledWith(expect.objectContaining({ outcome: 'EXPIRED', allowedMs: 30_000 }));
    view.rerender(<StrictMode><Activity mode="hidden"><Home /></Activity></StrictMode>);
    view.rerender(<StrictMode><Activity mode="visible"><Home /></Activity></StrictMode>);
    await advance(2_000);
    expect(played).toEqual(['/sounds/move.wav', '/sounds/completion.wav']);
    expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(1);
  });

  it.each(['off', 'puzzle', 'duration', 'unmount'] as const)('does not emit an end cue for a canceled countdown: %s', async (action) => {
    const view = await openTraining();
    await start('Countdown');
    await advance(29_000);
    if (action === 'off') fireEvent.click(screen.getByRole('button', { name: 'Off' }));
    if (action === 'puzzle') fireEvent.click(screen.getAllByTitle('Next Puzzle')[0]);
    if (action === 'duration') fireEvent.change(screen.getByRole('combobox', { name: 'Countdown allowed time' }), { target: { value: '60000' } });
    if (action === 'unmount') view.unmount();
    await advance(2_000);
    expect(played).not.toContain('/sounds/completion.wav');
    expect(api.submitTrainingAttempt).not.toHaveBeenCalled();
  });

  it('only starts the final pending selection and cancels initialization on unmount', async () => {
    const view = await openTraining();
    fireEvent.click(screen.getByRole('button', { name: 'Countdown' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Countdown allowed time' }), { target: { value: '15000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    await advance(1);
    expect(played).toEqual(['/sounds/move.wav']);
    fireEvent.click(screen.getByRole('button', { name: 'Countdown' }));
    view.unmount();
    await advance(1);
    expect(played).toEqual(['/sounds/move.wav']);
  });

  it('retains stopwatch submission when a pending mode change is replaced by the active mode', async () => {
    await openTraining();
    await start('Stopwatch');
    fireEvent.click(screen.getByRole('button', { name: 'Countdown' }));
    fireEvent.click(screen.getByRole('button', { name: 'Stopwatch' }));
    await advance(1);
    expect(played).toEqual(['/sounds/move.wav']);
    fireEvent.click(screen.getByRole('button', { name: 'Play fixture best move' }));
    expect(api.submitTrainingAttempt).toHaveBeenCalledWith(expect.objectContaining({ mode: 'STOPWATCH', outcome: 'SUBMITTED' }));
  });

  it('consumes persistently muted start and expiry without replay when unmuted', async () => {
    await openTraining(true);
    await start('Countdown');
    await advance(30_100);
    expect(played).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: /enable sound/i }));
    await advance(1_000);
    expect(played).toEqual([]);
    await start('Stopwatch');
    expect(played).toEqual(['/sounds/move.wav']);
    await advance(60_000);
    expect(played).toEqual(['/sounds/move.wav']);
  });

  it('uses the current mute preference independently at start and expiry', async () => {
    await openTraining();
    await start('Countdown');
    fireEvent.click(screen.getByRole('button', { name: /mute sound/i }));
    await advance(30_100);
    expect(played).toEqual(['/sounds/move.wav']);
    await start('Stopwatch');
    await start('Countdown');
    fireEvent.click(screen.getByRole('button', { name: /enable sound/i }));
    await advance(30_100);
    expect(played).toEqual(['/sounds/move.wav', '/sounds/completion.wav']);
  });

  it('stops and submits the stopwatch at a correct decision without an end cue', async () => {
    await openTraining();
    await start('Stopwatch');
    await advance(2_000);
    fireEvent.click(screen.getByRole('button', { name: 'Play fixture best move' }));
    expect(api.submitTrainingAttempt).toHaveBeenCalledWith(expect.objectContaining({ mode: 'STOPWATCH', outcome: 'SUBMITTED', elapsedMs: 2_001 }));
    played.length = 0;
    await advance(35_000);
    expect(played).toEqual([]);
    expect(screen.getByText('0:02')).toBeInTheDocument();
  });

  it('keeps countdown expiry audio independent of earlier decision telemetry', async () => {
    await openTraining();
    await start('Countdown');
    fireEvent.click(screen.getByRole('button', { name: 'Play fixture best move' }));
    expect(api.submitTrainingAttempt).toHaveBeenCalledWith(expect.objectContaining({ mode: 'COUNTDOWN', outcome: 'SUBMITTED' }));
    played.length = 0;
    await advance(30_100);
    expect(played).toEqual(['/sounds/completion.wav']);
    expect(screen.getByText("Time's up")).toBeInTheDocument();
  });

  it('continues after rejected playback and retains timing submission errors', async () => {
    await openTraining();
    rejectPlayback = true;
    vi.mocked(api.submitTrainingAttempt).mockRejectedValue(new Error('Submission failed'));
    await start('Countdown');
    await advance(30_100);
    expect(played).toEqual(['/sounds/move.wav', '/sounds/completion.wav']);
    expect(screen.getByText("Time's up")).toBeInTheDocument();
    expect(screen.getByText('Failed to record timing for this attempt.')).toBeInTheDocument();
    await advance(1_000);
    expect(played).toHaveLength(2);
  });
});
