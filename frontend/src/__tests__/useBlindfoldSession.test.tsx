import { describe, it, expect, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useBlindfoldSession } from '../hooks/useBlindfoldSession';

const WHITE_TO_MOVE = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe('useBlindfoldSession', () => {
  it('starts with no session', () => {
    const { result } = renderHook(() => useBlindfoldSession(vi.fn()));
    expect(result.current.state).toBeNull();
    expect(result.current.entryFen).toBeNull();
  });

  it('starts on the player turn without requesting ChessEcho when the player is to move', () => {
    const request = vi.fn();
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(WHITE_TO_MOVE, 'WHITE'));
    expect(result.current.state?.currentFen).toBe(WHITE_TO_MOVE);
    expect(result.current.state?.currentTurn).toBe('PLAYER');
    expect(result.current.entryFen).toBe(WHITE_TO_MOVE);
    expect(request).not.toHaveBeenCalled();
  });

  it('requests ChessEcho first when the other side is to move and applies its move', async () => {
    const request = vi.fn().mockResolvedValue('e5');
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    expect(request).toHaveBeenCalledWith(AFTER_E4);
    expect(result.current.state?.isLoading).toBe(true);
    await waitFor(() => expect(result.current.state?.currentTurn).toBe('PLAYER'));
    expect(result.current.state?.lastChessEchoMove?.san).toBe('e5');
    expect(result.current.state?.isLoading).toBe(false);
  });

  it('requests ChessEcho again from the entry position on reset', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce('e5')
      .mockResolvedValueOnce('Nc6')
      .mockResolvedValue('e5');
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    await waitFor(() => expect(result.current.state?.currentTurn).toBe('PLAYER'));
    act(() => result.current.submitPlayerMove('Nf3'));
    await waitFor(() => expect(result.current.state?.moveCount).toBe(3));

    act(() => result.current.reset());
    expect(result.current.state?.currentFen).toBe(AFTER_E4);
    expect(request).toHaveBeenLastCalledWith(AFTER_E4);
    await waitFor(() => expect(result.current.state?.moveCount).toBe(1));
  });

  it('applies a player move and requests the reply from the resulting position', async () => {
    const request = vi.fn().mockResolvedValue('e5');
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(WHITE_TO_MOVE, 'WHITE'));
    act(() => result.current.submitPlayerMove('e4'));
    expect(request).toHaveBeenCalledWith(AFTER_E4);
    await waitFor(() => expect(result.current.state?.lastChessEchoMove?.san).toBe('e5'));
  });

  it('reports illegal player notation without changing the position', () => {
    const request = vi.fn();
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(WHITE_TO_MOVE, 'WHITE'));
    act(() => result.current.submitPlayerMove('Ke5'));
    expect(result.current.state?.notationError).toMatch(/Illegal move/);
    expect(result.current.state?.currentFen).toBe(WHITE_TO_MOVE);
    expect(request).not.toHaveBeenCalled();
  });

  it('ignores player moves on ChessEcho turn', () => {
    const pending = deferred<string | null>();
    const request = vi.fn().mockReturnValue(pending.promise);
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    act(() => result.current.submitPlayerMove('e5'));
    expect(result.current.state?.moveCount).toBe(0);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['no move', () => Promise.resolve(null), /could not provide a move/],
    ['a rejected request', () => Promise.reject(new Error('network down')), /network down/],
    ['an illegal move', () => Promise.resolve('Ke4'), /Illegal move/],
  ])('fails the opening ChessEcho turn on %s', async (_label, impl, message) => {
    const request = vi.fn().mockImplementation(impl);
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    await waitFor(() => expect(result.current.state?.isFailed).toBe(true));
    expect(result.current.state?.notationError).toMatch(message);
    expect(result.current.state?.isLoading).toBe(false);
    expect(result.current.state?.currentFen).toBe(AFTER_E4);
  });

  it('reveals the board', () => {
    const { result } = renderHook(() => useBlindfoldSession(vi.fn()));
    act(() => result.current.start(WHITE_TO_MOVE, 'WHITE'));
    act(() => result.current.reveal());
    expect(result.current.state?.isVisible).toBe(true);
  });

  it('keeps the board revealed when a ChessEcho reply arrives after reveal', async () => {
    const pending = deferred<string | null>();
    const { result } = renderHook(() => useBlindfoldSession(vi.fn().mockReturnValue(pending.promise)));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    act(() => result.current.reveal());
    await act(async () => {
      pending.resolve('e5');
      await pending.promise;
    });
    expect(result.current.state?.lastChessEchoMove?.san).toBe('e5');
    expect(result.current.state?.isVisible).toBe(true);
  });

  it.each([
    ['exit', (s: ReturnType<typeof useBlindfoldSession>) => s.exit()],
    ['reset', (s: ReturnType<typeof useBlindfoldSession>) => s.reset()],
    ['restart', (s: ReturnType<typeof useBlindfoldSession>) => s.start(WHITE_TO_MOVE, 'WHITE')],
  ])('drops a ChessEcho response that arrives after %s', async (_label, interrupt) => {
    const first = deferred<string | null>();
    const request = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() => useBlindfoldSession(request));
    act(() => result.current.start(AFTER_E4, 'WHITE'));
    act(() => interrupt(result.current));
    const before = result.current.state;

    await act(async () => {
      first.resolve('e5');
      await first.promise;
    });

    expect(result.current.state).toEqual(before);
    expect(result.current.state?.lastChessEchoMove ?? null).toBeNull();
  });
});
