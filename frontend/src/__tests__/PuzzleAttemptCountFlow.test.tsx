import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Home from '../app/page';
import * as api from '../services/api';
import { activeAccountStore } from '../utils/browserStores';

vi.mock('../services/api', async () => ({
  ...await vi.importActual<typeof import('../services/api')>('../services/api'),
  fetchCurrentSession: vi.fn(), fetchAccounts: vi.fn(), fetchPuzzles: vi.fn(),
  fetchPuzzleAttemptCount: vi.fn(), recordPuzzleEvent: vi.fn(),
  submitTrainingAttempt: vi.fn(),
}));
vi.mock('../services/soundService', () => ({
  playSound: vi.fn(), soundService: { playSound: vi.fn(), isSoundEnabled: vi.fn(() => true) },
}));
vi.mock('react-chessboard', () => ({
  Chessboard: ({ options }: { options: { onPieceDrop: (move: { sourceSquare: string; targetSquare: string }) => boolean } }) => (
    <div>
      <button onClick={() => options.onPieceDrop({ sourceSquare: 'e2', targetSquare: 'e4' })}>Submit best answer</button>
      <button onClick={() => options.onPieceDrop({ sourceSquare: 'd2', targetSquare: 'd4' })}>Submit historical mistake</button>
      <button onClick={() => options.onPieceDrop({ sourceSquare: 'g1', targetSquare: 'f3' })}>Submit alternative</button>
      <button onClick={() => options.onPieceDrop({ sourceSquare: 'c2', targetSquare: 'c4' })}>Submit other incorrect answer</button>
      <button onClick={() => options.onPieceDrop({ sourceSquare: 'e2', targetSquare: 'e5' })}>Try illegal answer</button>
    </div>
  ),
}));

const puzzle = {
  puzzleId: 'puzzle-a', playerColor: 'WHITE' as const,
  fen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  targetMove: 'e4', acceptableMoves: [{ move: 'Nf3', evalLoss: 0.1 }],
  movesPlayed: [{ move: 'd4', timesPlayed: 2, averageLoss: 1.5 }],
  openingTitle: 'Count fixture', priority: 1, timesReached: 10, mistakeCount: 4, mistakeRate: 40,
};
let counts: { solvedCount: number; failedCount: number };
async function open() {
  window.location.hash = '#puzzles';
  render(<Home />);
  await screen.findByText('Count fixture', {}, { timeout: 1000 });
}
function count() { return screen.getByRole('region', { name: 'Your puzzle attempts' }); }
function expectCounts(solved: number, failed: number) {
  expect(count()).toHaveTextContent(`Solved: ${solved}`);
  expect(count()).toHaveTextContent(`Failed: ${failed}`);
  expect(count()).not.toHaveTextContent('Your attempts:');
}

describe('submitted-answer count in the production puzzle page', () => {
  beforeEach(() => {
    cleanup(); localStorage.clear(); window.location.hash = ''; vi.resetAllMocks(); counts = { solvedCount: 0, failedCount: 0 };
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'authenticated', userId: 'alice' });
    vi.mocked(api.fetchAccounts).mockResolvedValue([{ id: 'account-a', username: 'fixture', platform: 'CHESS_COM' }]);
    vi.mocked(api.fetchPuzzles).mockResolvedValue([puzzle]);
    vi.mocked(api.fetchPuzzleAttemptCount).mockImplementation(async () => ({ ...counts }));
    vi.mocked(api.recordPuzzleEvent).mockImplementation(async (request) => {
      if (request.eventType === 'SOLVED') counts.solvedCount++;
      else if (request.eventType === 'FAILED') counts.failedCount++;
    });
  });
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it('reaches failed-only then mixed outcome counts through incorrect, reset, correct, and remount', async () => {
    await open();
    await waitFor(() => expectCounts(0, 0));
    fireEvent.click(screen.getByRole('button', { name: 'Submit historical mistake' }));
    await waitFor(() => expectCounts(0, 1));
    fireEvent.click(screen.getByTitle('Reset Position'));
    expect(counts).toEqual({ solvedCount: 0, failedCount: 1 });
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    await waitFor(() => expectCounts(1, 1));
    cleanup(); await open();
    await waitFor(() => expectCounts(1, 1));
    expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(2);
  });

  it('counts acceptable alternatives and other incorrect answers but not illegal moves or resets', async () => {
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Try illegal answer' }));
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Submit alternative' }));
    await waitFor(() => expectCounts(1, 0));
    fireEvent.click(screen.getByTitle('Reset Position'));
    fireEvent.click(screen.getByRole('button', { name: 'Submit other incorrect answer' }));
    await waitFor(() => expectCounts(1, 1));
    expect(vi.mocked(api.recordPuzzleEvent).mock.calls.map(([request]) => request.eventType)).toEqual(['SOLVED', 'FAILED']);
  });

  it('does not fetch or display a personal count for a guest', async () => {
    vi.mocked(api.fetchCurrentSession).mockResolvedValue({ status: 'unauthenticated' });
    localStorage.setItem('chessecho_username', 'fixture');
    await open();
    expect(screen.queryByRole('region', { name: 'Your puzzle attempts' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    expect(api.fetchPuzzleAttemptCount).not.toHaveBeenCalled();
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();
  });

  it('counts identical answers separately after undo, not undo or redo themselves', async () => {
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    await waitFor(() => expectCounts(1, 0));
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    fireEvent.keyDown(window, { key: 'ArrowRight' });
    expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(window, { key: 'ArrowLeft' });
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    await waitFor(() => expectCounts(2, 0));
    const requests = vi.mocked(api.recordPuzzleEvent).mock.calls.map(([request]) => request);
    expect(requests[0].submittedMove).toBe(requests[1].submittedMove);
    expect(requests[0].submissionId).not.toBe(requests[1].submissionId);
  });

  it.each([false, true])('recovers a failed response with persistence=%s by replaying the same answer', async (persisted) => {
    const saved = new Set<string>();
    vi.mocked(api.recordPuzzleEvent).mockImplementation(async (request) => {
      if (!saved.has(request.submissionId!)) {
        saved.add(request.submissionId!);
        if (persisted) counts.solvedCount++;
        throw new TypeError('Response lost');
      }
      if (!persisted) counts.solvedCount++;
    });
    await open();
    await waitFor(() => expectCounts(0, 0));
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    await screen.findByText('Could not confirm this answer was recorded.');
    expectCounts(0, 0);
    fireEvent.click(screen.getByRole('button', { name: 'Retry recording answer 1' }));
    await waitFor(() => expectCounts(1, 0));
    const requests = vi.mocked(api.recordPuzzleEvent).mock.calls.map(([request]) => request);
    expect(requests[1]).toEqual(requests[0]);
  });

  it('does not submit the old puzzle under a replacement account while loading', async () => {
    await open();
    await waitFor(() => expectCounts(0, 0));
    vi.mocked(api.fetchPuzzles).mockReturnValue(new Promise(() => {}));
    act(() => { activeAccountStore.set({ id: 'account-b', username: 'fixture', platform: 'CHESS_COM' }); });
    const staleButton = screen.queryByRole('button', { name: 'Submit best answer' });
    if (staleButton) fireEvent.click(staleButton);
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();
    expect(screen.queryByRole('region', { name: 'Your puzzle attempts' })).not.toBeInTheDocument();
  });

  it('counts one timed answer but not later countdown expiry telemetry', async () => {
    await open();
    await waitFor(() => expectCounts(0, 0));
    fireEvent.click(screen.getByRole('button', { name: /puzzle settings/i }));
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'] });
    await act(async () => { vi.advanceTimersByTime(10); });
    fireEvent.click(screen.getByRole('button', { name: 'Countdown' }));
    await act(async () => { vi.advanceTimersByTime(1); });
    fireEvent.click(screen.getByRole('button', { name: 'Submit best answer' }));
    await act(async () => { vi.advanceTimersByTime(30_100); });
    expect(api.recordPuzzleEvent).toHaveBeenCalledTimes(1);
    expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(2);
    expect(counts).toEqual({ solvedCount: 1, failedCount: 0 });
  });

  it('does not count countdown expiry without an answer', async () => {
    await open();
    fireEvent.click(screen.getByRole('button', { name: /puzzle settings/i }));
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance'] });
    await act(async () => { vi.advanceTimersByTime(10); });
    fireEvent.click(screen.getByRole('button', { name: 'Countdown' }));
    await act(async () => { vi.advanceTimersByTime(1); });
    await act(async () => { vi.advanceTimersByTime(30_100); });
    expect(api.submitTrainingAttempt).toHaveBeenCalledTimes(1);
    expect(api.recordPuzzleEvent).not.toHaveBeenCalled();
    expect(counts).toEqual({ solvedCount: 0, failedCount: 0 });
  });
});
