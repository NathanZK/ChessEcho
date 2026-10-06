import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CountdownTimer,
  StopwatchTimer,
  submitTrainingAttempt,
} from '../utils/timedTraining';
import { submitTrainingAttempt as submitApiTrainingAttempt } from '../services/api';

describe('timed training timers', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it.each([
    { mode: 'STOPWATCH', timer: () => new StopwatchTimer(), allowedMs: undefined },
    { mode: 'COUNTDOWN', timer: () => new CountdownTimer(5_000), allowedMs: 5_000 },
  ])('sends the real $mode attempt UUID through the production client', async ({ mode, timer, allowedMs }) => {
    const activeTimer = timer();
    const attemptId = activeTimer.startNewAttempt();
    activeTimer.start(1_000);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    });
    vi.stubGlobal('fetch', fetchMock);

    await submitApiTrainingAttempt({
      attemptId,
      puzzleId: 'puzzle-1',
      mode,
      elapsedMs: 1_250,
      allowedMs,
      outcome: 'SUBMITTED',
    });

    const [url, request] = fetchMock.mock.calls[0];
    const body = JSON.parse(request.body);
    expect(url).toMatch(/\/api\/puzzles\/attempt$/);
    expect(body.attemptId).toBe(attemptId);
    expect(activeTimer.getCurrentAttemptId()).toBe(attemptId);
    expect(body.attemptId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });

  it('measures stopwatch duration at the decision boundary', () => {
    const timer = new StopwatchTimer();
    timer.startNewAttempt();
    timer.start(1_000);

    expect(timer.getElapsed(4_250)).toBe(3_250);
    expect(timer.stop(5_000)).toBe(4_000);
    expect(timer.isRunning()).toBe(false);
  });

  it('pauses and resumes a stopwatch without changing its attempt or elapsed time', () => {
    const timer = new StopwatchTimer();
    const attemptId = timer.startNewAttempt();
    timer.start(1_000);

    timer.pause(1_750);
    expect(timer.getElapsed(2_000)).toBe(750);
    expect(timer.isRunning()).toBe(false);

    timer.resume(5_000);
    expect(timer.getElapsed(5_250)).toBe(1_000);
    expect(timer.getCurrentAttemptId()).toBe(attemptId);
    expect(timer.isRunning()).toBe(true);
  });

  it('isolates attempts when a puzzle is reset', () => {
    const timer = new StopwatchTimer();
    const firstAttempt = timer.startNewAttempt();
    timer.start(1_000);
    timer.reset();
    const secondAttempt = timer.startNewAttempt();

    expect(secondAttempt).not.toBe(firstAttempt);
    expect(timer.getCurrentAttemptId()).toBe(secondAttempt);
    expect(timer.getElapsed(5_000)).toBe(0);
  });

  it('latches countdown expiry and rejects a late submission', () => {
    const timer = new CountdownTimer(5_000);
    timer.startNewAttempt();
    timer.start(1_000);

    expect(timer.getRemaining(3_500)).toBe(2_500);
    expect(timer.isExpired(6_000)).toBe(true);
    expect(timer.getOutcome()).toBe('EXPIRED');
    expect(timer.canSubmit()).toBe(false);
  });

  it('pauses and resumes a countdown with the same remaining time and attempt', () => {
    const timer = new CountdownTimer(5_000);
    const attemptId = timer.startNewAttempt();
    timer.start(1_000);

    timer.pause(2_500);
    expect(timer.getRemaining(3_000)).toBe(3_500);

    timer.resume(5_000);
    expect(timer.getRemaining(5_500)).toBe(3_000);
    expect(timer.getCurrentAttemptId()).toBe(attemptId);
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
