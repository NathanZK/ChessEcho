'use client';

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { fetchPuzzleAttemptCount, recordPuzzleEvent, PuzzleSubmissionError, type PuzzleEventRequest, type PuzzleAttemptCounts } from '../services/api';

export interface PuzzleAttemptContext {
  userId: string;
  accountId: string;
  puzzleId: string;
  playerColor: 'WHITE' | 'BLACK';
}

export type PuzzleAttemptCountState =
  | { status: 'hidden' | 'loading' | 'error' }
  | ({ status: 'loaded' } & PuzzleAttemptCounts);

interface PendingAnswer {
  id: string;
  key: string;
  userId: string;
  request: PuzzleEventRequest;
  status: 'recording' | 'failed';
  retryable: boolean;
}

function contextKey(context: PuzzleAttemptContext | null): string {
  return context ? JSON.stringify([context.userId, context.accountId, context.puzzleId, context.playerColor]) : '';
}

export function usePuzzleAttemptCount(context: PuzzleAttemptContext | null, userId?: string) {
  const key = contextKey(context);
  const current = useRef({ key, userId, mounted: true });
  const readVersion = useRef(0);
  const writing = useRef(new Set<string>());
  const [loaded, setLoaded] = useState<{ key: string; state: PuzzleAttemptCountState }>({
    key: '', state: { status: 'hidden' },
  });
  const [answers, setAnswers] = useState<PendingAnswer[]>([]);
  const [answerOwner, setAnswerOwner] = useState(userId);
  if (answerOwner !== userId) {
    setAnswerOwner(userId);
    setAnswers([]);
    setLoaded({ key: '', state: { status: 'hidden' } });
  }

  useLayoutEffect(() => {
    const versions = readVersion;
    current.current = { key, userId, mounted: true };
    return () => {
      current.current.mounted = false;
      versions.current++;
    };
  }, [key, userId]);

  const load = useCallback(() => {
    if (!context) return Promise.resolve();
    const version = ++readVersion.current;
    const isCurrent = () => current.current.mounted && current.current.key === key && readVersion.current === version;
    return Promise.resolve()
      .then(() => fetchPuzzleAttemptCount(context.puzzleId, context.accountId, context.playerColor))
      .then((counts) => {
        if (isCurrent()) setLoaded({ key, state: { status: 'loaded', ...counts } });
      }, () => {
        if (isCurrent()) setLoaded({ key, state: { status: 'error' } });
      });
  }, [context, key]);

  useEffect(() => { void load(); }, [load]);

  const reload = useCallback(async () => {
    if (!context) return;
    setLoaded({ key, state: { status: 'loading' } });
    await load();
  }, [context, key, load]);

  const record = useCallback(async (answer: PendingAnswer) => {
    if (writing.current.has(answer.id) || current.current.userId !== answer.userId) return;
    writing.current.add(answer.id);
    setAnswers((previous) => [...previous.filter((item) => item.id !== answer.id), { ...answer, status: 'recording' }]);
    try {
      await recordPuzzleEvent(answer.request);
      if (current.current.mounted && current.current.userId === answer.userId) {
        setAnswers((previous) => previous.filter((item) => item.id !== answer.id));
        if (current.current.key === answer.key) await reload();
      }
    } catch (error) {
      if (current.current.mounted && current.current.userId === answer.userId) {
        const retryable = !(error instanceof PuzzleSubmissionError) || error.status >= 500;
        setAnswers((previous) => previous.map((item) => item.id === answer.id
          ? { ...item, status: 'failed', retryable } : item));
      }
    } finally {
      writing.current.delete(answer.id);
    }
  }, [reload]);

  const submit = useCallback(async (submittedMove: string, correct: boolean) => {
    if (!context || current.current.key !== key) return;
    const id = crypto.randomUUID();
    await record({
      id, key, userId: context.userId, status: 'recording', retryable: true,
      request: {
        positionId: context.puzzleId, playerColor: context.playerColor, accountId: context.accountId,
        eventType: correct ? 'SOLVED' : 'FAILED', submissionId: id, submittedMove,
      },
    });
  }, [context, key, record]);

  const retry = useCallback(async (id: string) => {
    const answer = answers.find((item) => item.id === id && item.key === key && item.userId === userId && item.retryable);
    if (answer) await record(answer);
  }, [answers, key, userId, record]);

  const state: PuzzleAttemptCountState = !context ? { status: 'hidden' }
    : loaded.key === key ? loaded.state : { status: 'loading' };
  return {
    state,
    pending: answers.filter((answer) => answer.key === key && answer.userId === userId && answer.status === 'failed'),
    recording: answers.some((answer) => answer.key === key && answer.userId === userId && answer.status === 'recording'),
    submit, retry, reload,
  };
}
