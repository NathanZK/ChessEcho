'use client';

import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import {
  applyChessEchoMove,
  applyPlayerMove,
  createBlinfoldGameState,
  resetBlinfoldGame,
  type BlindfoldGameState,
  type BlindfoldPlayerColor,
} from '@/utils/blindfoldGameState';

export type RequestChessEchoMove = (fen: string) => Promise<string | null>;

export interface BlindfoldSession {
  state: BlindfoldGameState | null;
  entryFen: string | null;
  start: (entryFen: string, playerColor: BlindfoldPlayerColor) => void;
  submitPlayerMove: (san: string) => void;
  reveal: () => void;
  reset: () => void;
  exit: () => void;
}

export function useBlindfoldSession(requestChessEchoMove: RequestChessEchoMove): BlindfoldSession {
  const [state, setState] = useState<BlindfoldGameState | null>(null);
  const [entry, setEntry] = useState<{ fen: string; playerColor: BlindfoldPlayerColor } | null>(null);
  const stateRef = useRef<BlindfoldGameState | null>(null);
  const entryRef = useRef(entry);
  // Incremented on start/reset/exit so replies from an earlier session are dropped.
  const sessionTokenRef = useRef(0);
  const requestRef = useRef(requestChessEchoMove);

  useLayoutEffect(() => {
    requestRef.current = requestChessEchoMove;
  });

  const commit = useCallback((next: BlindfoldGameState | null) => {
    stateRef.current = next;
    setState(next);
  }, []);

  const requestReply = useCallback((from: BlindfoldGameState) => {
    const token = sessionTokenRef.current;
    commit({ ...from, isLoading: true });

    const isCurrent = () => sessionTokenRef.current === token && stateRef.current !== null;
    const fail = (message: string) => {
      if (!isCurrent()) return;
      commit({ ...stateRef.current!, isLoading: false, isFailed: true, notationError: message });
    };

    requestRef.current(from.currentFen).then(
      (reply) => {
        if (!isCurrent()) return;
        if (!reply) {
          fail('ChessEcho could not provide a move.');
          return;
        }
        const result = applyChessEchoMove(from, reply, from.currentFen);
        if ('error' in result) {
          fail(result.error);
          return;
        }
        commit({ ...result, isLoading: false, isVisible: stateRef.current!.isVisible });
      },
      (error: unknown) => fail(error instanceof Error ? error.message : 'ChessEcho request failed.'),
    );
  }, [commit]);

  const begin = useCallback((initial: BlindfoldGameState) => {
    sessionTokenRef.current += 1;
    if (initial.currentTurn === 'CHESSECHO') {
      requestReply(initial);
    } else {
      commit(initial);
    }
  }, [commit, requestReply]);

  const start = useCallback((entryFen: string, playerColor: BlindfoldPlayerColor) => {
    let initial: BlindfoldGameState;
    try {
      initial = createBlinfoldGameState(entryFen, playerColor);
    } catch (error) {
      console.error('Unable to start blindfold training:', error);
      return;
    }
    const nextEntry = { fen: entryFen, playerColor };
    entryRef.current = nextEntry;
    setEntry(nextEntry);
    begin(initial);
  }, [begin]);

  const reset = useCallback(() => {
    const current = stateRef.current;
    const currentEntry = entryRef.current;
    if (!current || !currentEntry) return;
    begin(resetBlinfoldGame(current, currentEntry.fen, currentEntry.playerColor));
  }, [begin]);

  const exit = useCallback(() => {
    sessionTokenRef.current += 1;
    entryRef.current = null;
    setEntry(null);
    commit(null);
  }, [commit]);

  const submitPlayerMove = useCallback((san: string) => {
    const current = stateRef.current;
    if (!current || current.currentTurn !== 'PLAYER') return;
    const result = applyPlayerMove(current, san, current.currentFen);
    if ('error' in result) {
      commit({ ...current, notationError: result.error });
      return;
    }
    requestReply(result);
  }, [commit, requestReply]);

  const reveal = useCallback(() => {
    const current = stateRef.current;
    if (!current) return;
    commit({ ...current, isVisible: true });
  }, [commit]);

  return { state, entryFen: entry?.fen ?? null, start, submitPlayerMove, reveal, reset, exit };
}
