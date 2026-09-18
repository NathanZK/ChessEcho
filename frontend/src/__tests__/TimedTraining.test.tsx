import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CountdownTimer,
  StopwatchTimer,
  submitTrainingAttempt,
} from '../utils/timedTraining';

describe('timed training timers', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('measures stopwatch duration at the decision boundary', () => {
    const timer = new StopwatchTimer();
    timer.startNewAttempt('puzzle-1');
    timer.start(1_000);

    expect(timer.getElapsed(4_250)).toBe(3_250);
    expect(timer.stop(5_000)).toBe(4_000);
    expect(timer.isRunning()).toBe(false);
  });

  it('isolates attempts when a puzzle is reset', () => {
    const timer = new StopwatchTimer();
    const firstAttempt = timer.startNewAttempt('puzzle-1');
    timer.start(1_000);
    timer.reset();
    const secondAttempt = timer.startNewAttempt('puzzle-2');

    expect(secondAttempt).not.toBe(firstAttempt);
    expect(timer.getCurrentAttemptId()).toBe(secondAttempt);
    expect(timer.getElapsed(5_000)).toBe(0);
  });

  it('latches countdown expiry and rejects a late submission', () => {
    const timer = new CountdownTimer(5_000);
    timer.startNewAttempt('puzzle-3');
    timer.start(1_000);

    expect(timer.getRemaining(3_500)).toBe(2_500);
    expect(timer.isExpired(6_000)).toBe(true);
    expect(timer.getOutcome()).toBe('EXPIRED');
    expect(timer.canSubmit()).toBe(false);
  });

  it('submits the complete timing payload', async () => {
    const response = {
      attemptId: 'attempt-1',
      puzzleId: 'puzzle-1',
      mode: 'STOPWATCH',
      elapsedMs: 3_250,
      outcome: 'SUBMITTED',
      recordedAt: '2026-01-01T00:00:00Z',
    };
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => response,
      })),
    );

    await expect(
      submitTrainingAttempt({
        attemptId: response.attemptId,
        puzzleId: response.puzzleId,
        mode: response.mode,
        elapsedMs: response.elapsedMs,
        outcome: response.outcome,
      }),
    ).resolves.toEqual(response);
  });
});
