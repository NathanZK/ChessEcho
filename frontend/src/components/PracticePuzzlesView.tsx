'use client';

import React, { useState, useSyncExternalStore } from 'react';
import { Chess } from 'chess.js';
import type { TabType } from '@/components/Header';
import type { PositionExplorationStart } from '@/components/ExplorePositionModal';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea, type ChessBoardHistorySnapshot } from '@/components/ChessBoardArea';
import { BlindfoldBoardWrapper } from '@/components/BlindfoldBoardWrapper';
import { NotationExchangeUI } from '@/components/NotationExchangeUI';
import { useBlindfoldSession } from '@/hooks/useBlindfoldSession';
import { usePuzzleAttemptCount } from '@/hooks/usePuzzleAttemptCount';
import { PuzzleFeedbackPanel } from '@/components/PuzzleFeedbackPanel';
import { Puzzle } from '@/types/puzzle';
import {
  fetchPuzzles,
  fetchPuzzleContinuation,
  submitTrainingAttempt,
  evaluateMove,
  type SessionState,
} from '@/services/api';
import { soundService } from '@/services/soundService';
import { useLineExploration, type PuzzleFeedbackState } from '@/hooks/useLineExploration';
import { StopwatchTimer, CountdownTimer } from '@/utils/timedTraining';
import { puzzleSettingsStore } from '@/utils/browserStores';

type TimerAttempt = {
  puzzle: Puzzle;
  mode: 'STOPWATCH' | 'COUNTDOWN';
  allowedMs: number;
  timer: StopwatchTimer | CountdownTimer;
  hasExpired: boolean;
};

export type LongDecisionReturnSnapshot = {
  originatingTab?: TabType;
  activePuzzle: Puzzle | null;
  currentPuzzleIndex: number;
  initialMoveHistorySan: string[];
  initialMoveHistoryStartFen: string | undefined;
  currentBoardFen: string;
  boardHistory: ChessBoardHistorySnapshot | null;
  currentEvalCp: number;
  isEvalUnknown: boolean;
  evalHistory: number[];
  feedbackHistory: PuzzleFeedbackState[];
  historyIndex: number;
  moveHistory: string[];
  hintSquare: string | undefined;
  feedback: PuzzleFeedbackState;
  showPuzzleSettings: boolean;
  isBoardFlipped: boolean;
  timerMode: 'STOPWATCH' | 'COUNTDOWN' | null;
  timerElapsedMs: number;
  timerAllowedMs: number;
  timerExpired: boolean;
  timerSubmissionError: string | null;
  timerAttempt: TimerAttempt | null;
  timerWasRunning: boolean;
};

export type PracticePuzzlesViewHandle = {
  explorePosition: (position: PositionExplorationStart, preserveReturnState?: boolean, originatingTab?: TabType) => void;
  exploreLongDecision: (fen: string) => boolean;
  exploreSimilarPositions: (position: PositionExplorationStart) => boolean;
  selectPracticeFromLibrary: (puzzle: Puzzle, fullList?: Puzzle[]) => void;
  clearAccountUiState: () => void;
  invalidatePuzzleRequests: () => void;
};

export type PracticePuzzlesViewProps = {
  hidden?: boolean;
  actionsRef?: React.RefObject<PracticePuzzlesViewHandle | null>;
  sessionStatus: SessionState['status'];
  sessionUserId?: string;
  activeAccountId?: string;
  activeUsername?: string;
  sessionGatePromise: Promise<boolean>;
  sessionGateOpen: boolean;
  onNavigateTab: (tab: TabType) => void;
  onWeaknessCountChange?: (count: number) => void;
  onSourceNeutralExplorationChange?: (isExploring: boolean) => void;
  onReturnToTab?: (tab: TabType) => void;
  hasLongDecisionReturnContext?: boolean;
  hasSimilarPositionsReturnContext?: boolean;
};

export function PracticePuzzlesView({
  hidden = false,
  actionsRef,
  sessionStatus,
  sessionUserId,
  activeAccountId,
  activeUsername,
  sessionGatePromise,
  sessionGateOpen,
  onNavigateTab,
  onWeaknessCountChange,
  onSourceNeutralExplorationChange,
  onReturnToTab,
  hasLongDecisionReturnContext = false,
  hasSimilarPositionsReturnContext = false,
}: PracticePuzzlesViewProps) {
  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>([]);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle | null>(null);
  const [initialMoveHistorySan, setInitialMoveHistorySan] = useState<string[]>([]);
  const [initialMoveHistoryStartFen, setInitialMoveHistoryStartFen] = useState<string | undefined>();
  const [boardHistorySnapshot, setBoardHistorySnapshot] = useState<ChessBoardHistorySnapshot | null>(null);
  const boardHistorySnapshotRef = React.useRef<ChessBoardHistorySnapshot | null>(null);
  const longDecisionReturnSnapshotRef = React.useRef<LongDecisionReturnSnapshot | null>(null);
  const suppliedPositionCounterRef = React.useRef(0);
  const [loadedPuzzleContext, setLoadedPuzzleContext] = useState('');
  const [isLoadingPuzzles, setIsLoadingPuzzles] = useState<boolean>(true);
  const [puzzleLoadError, setPuzzleLoadError] = useState<boolean>(false);
  const [puzzleReloadToken, setPuzzleReloadToken] = useState<number>(0);
  const puzzleLoadSeqRef = React.useRef<number>(0);

  const handleBoardHistoryChange = React.useCallback((snapshot: ChessBoardHistorySnapshot) => {
    boardHistorySnapshotRef.current = snapshot;
    setBoardHistorySnapshot(snapshot);
  }, []);

  const [puzzlePage, setPuzzlePage] = useState<number>(0);
  const [hasMorePuzzles, setHasMorePuzzles] = useState<boolean>(true);
  const [isFetchingMorePuzzles, setIsFetchingMorePuzzles] = useState<boolean>(false);

  // Filter settings
  const [isSettingsInitialized, setIsSettingsInitialized] = useState<boolean>(false);
  const [minEvalLoss, setMinEvalLoss] = useState<number>(0.8);
  const [minMistakeCount, setMinMistakeCount] = useState<number>(3);
  const [puzzleColorFilter, setPuzzleColorFilter] = useState<'BOTH' | 'WHITE' | 'BLACK'>('BOTH');
  const [showPuzzleSettings, setShowPuzzleSettings] = useState<boolean>(false);
  const [isBoardFlipped, setIsBoardFlipped] = useState<boolean>(false);
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);

  // History stacks
  const [evalHistory, setEvalHistory] = useState<number[]>([35]);
  const [feedbackHistory, setFeedbackHistory] = useState<PuzzleFeedbackState[]>([{ status: 'IDLE' }]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);

  const [currentEvalCp, setCurrentEvalCp] = useState<number>(35);
  const [isEvalUnknown, setIsEvalUnknown] = useState<boolean>(false);
  const [moveHistory, setMoveHistory] = useState<string[]>([]);
  const [hintSquare, setHintSquare] = useState<string | undefined>(undefined);
  const [currentBoardFen, setCurrentBoardFen] = useState<string>('');

  const [feedback, setFeedback] = useState<PuzzleFeedbackState>({ status: 'IDLE' });

  // Timed training state
  const timerRef = React.useRef<StopwatchTimer | CountdownTimer | null>(null);
  const timerAttemptRef = React.useRef<TimerAttempt | null>(null);
  const [timerMode, setTimerMode] = useState<'STOPWATCH' | 'COUNTDOWN' | null>(null);
  const [timerElapsedMs, setTimerElapsedMs] = useState<number>(0);
  const [timerAllowedMs, setTimerAllowedMs] = useState<number>(30000);
  const [timerExpired, setTimerExpired] = useState<boolean>(false);
  const [timerSubmissionError, setTimerSubmissionError] = useState<string | null>(null);

  const puzzleContextKey = JSON.stringify([sessionUserId, activeAccountId, activeUsername]);
  const puzzleContextReady =
    loadedPuzzleContext === puzzleContextKey &&
    !isLoadingPuzzles &&
    (sessionStatus === 'unauthenticated' ||
      sessionStatus === 'error' ||
      (sessionStatus === 'authenticated' && sessionGateOpen));

  const attemptContext = React.useMemo(() => {
    return puzzleContextReady &&
      sessionStatus === 'authenticated' &&
      sessionUserId &&
      activeAccountId &&
      activePuzzle &&
      activePuzzle.source !== 'supplied'
      ? {
          userId: sessionUserId,
          accountId: activeAccountId,
          puzzleId: activePuzzle.puzzleId,
          playerColor: activePuzzle.playerColor,
        }
      : null;
  }, [puzzleContextReady, sessionStatus, sessionUserId, activeAccountId, activePuzzle]);

  const puzzleAttempts = usePuzzleAttemptCount(attemptContext, sessionUserId);

  const invalidatePuzzleRequests = React.useCallback(() => {
    puzzleLoadSeqRef.current++;
    setIsFetchingMorePuzzles(false);
  }, []);

  const submitTimerAttempt = React.useCallback(
    async (outcome: 'SUBMITTED' | 'EXPIRED') => {
      const timer = timerRef.current;
      const mode = timerMode;
      if (!timer || !mode || !activePuzzle) return;
      const attemptId = timer.getCurrentAttemptId();
      if (!attemptId) return;
      if (sessionStatus === 'authenticated' && !activeAccountId) {
        setTimerSubmissionError('Select a connected Chess.com account before recording this attempt.');
        return;
      }

      let elapsedMs: number;
      let allowedMs: number | undefined;
      if (timer instanceof StopwatchTimer) {
        elapsedMs = timer.stop();
      } else {
        allowedMs = timerAllowedMs;
        elapsedMs = timerAllowedMs - timer.getRemaining();
      }

      try {
        await submitTrainingAttempt({
          attemptId,
          puzzleId: activePuzzle.puzzleId,
          mode,
          elapsedMs,
          allowedMs,
          outcome,
          ...(sessionStatus === 'authenticated' ? { accountId: activeAccountId } : {}),
        });
        setTimerSubmissionError(null);
      } catch {
        setTimerSubmissionError('Failed to record timing for this attempt.');
      }
    },
    [timerMode, timerAllowedMs, activePuzzle, activeAccountId, sessionStatus]
  );

  const submitExpiredTimerAttempt = React.useEffectEvent(() => {
    void submitTimerAttempt('EXPIRED');
  });

  React.useEffect(() => {
    if (hasLongDecisionReturnContext || hasSimilarPositionsReturnContext) return;
    if (!activePuzzle || !timerMode) {
      timerRef.current = null;
      timerAttemptRef.current = null;
      return;
    }

    let attempt = timerAttemptRef.current;
    if (
      attempt?.puzzle !== activePuzzle ||
      attempt.mode !== timerMode ||
      attempt.allowedMs !== timerAllowedMs
    ) {
      attempt = null;
      timerRef.current = null;
    }

    const tick = () => {
      if (!attempt) return;
      const current = attempt.timer;
      if (timerMode === 'STOPWATCH' && current instanceof StopwatchTimer) {
        setTimerElapsedMs(current.getElapsed());
      } else if (timerMode === 'COUNTDOWN' && current instanceof CountdownTimer) {
        const remaining = current.getRemaining();
        setTimerElapsedMs(timerAllowedMs - remaining);
        if (remaining === 0 && !attempt.hasExpired) {
          attempt.hasExpired = true;
          setTimerExpired(true);
          soundService.playSound('completion');
          submitExpiredTimerAttempt();
        }
      }
    };

    const initId = setTimeout(() => {
      if (!attempt) {
        const timer =
          timerMode === 'STOPWATCH' ? new StopwatchTimer() : new CountdownTimer(timerAllowedMs);
        timer.startNewAttempt();
        timer.start();
        attempt = {
          puzzle: activePuzzle,
          mode: timerMode,
          allowedMs: timerAllowedMs,
          timer,
          hasExpired: false,
        };
        timerAttemptRef.current = attempt;
        timerRef.current = timer;
        setTimerElapsedMs(0);
        setTimerExpired(false);
        setTimerSubmissionError(null);
        soundService.playSound('move');
      }
      timerRef.current = attempt.timer;
      tick();
    }, 0);
    const intervalId = setInterval(tick, 100);

    return () => {
      clearTimeout(initId);
      clearInterval(intervalId);
    };
  }, [
    activePuzzle,
    timerMode,
    timerAllowedMs,
    hasLongDecisionReturnContext,
    hasSimilarPositionsReturnContext,
  ]);

  const requestBlindfoldChessEchoMove = React.useCallback(async (fen: string) => {
    const response = await fetchPuzzleContinuation(fen, 'ENGINE');
    return response?.candidates[0]?.move ?? null;
  }, []);

  const blindfold = useBlindfoldSession(requestBlindfoldChessEchoMove);
  const isBlindfoldActive = blindfold.state !== null;
  const isBlindfoldActiveRef = React.useRef(isBlindfoldActive);
  const exitBlindfoldRef = React.useRef(blindfold.exit);
  React.useLayoutEffect(() => {
    isBlindfoldActiveRef.current = isBlindfoldActive;
    exitBlindfoldRef.current = blindfold.exit;
  });

  // Flip board keyboard shortcut
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (
        tag === 'INPUT' ||
        tag === 'TEXTAREA' ||
        tag === 'SELECT' ||
        (e.target as HTMLElement)?.isContentEditable
      )
        return;
      if (isBlindfoldActiveRef.current) return;
      if (e.key === 'x' || e.key === 'X') {
        setIsBoardFlipped((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // Browser settings restore
  const settings = useSyncExternalStore(
    puzzleSettingsStore.subscribe,
    puzzleSettingsStore.getSnapshot,
    puzzleSettingsStore.getServerSnapshot
  );
  const [appliedSettings, setAppliedSettings] = useState(puzzleSettingsStore.getServerSnapshot);

  if (appliedSettings !== settings) {
    setAppliedSettings(settings);
    setPuzzleColorFilter(settings.colorFilter);
    setMinEvalLoss(settings.minEvalLoss);
    setMinMistakeCount(settings.minMistakeCount);
    setSoundEnabled(settings.soundEnabled);
    setIsSettingsInitialized(true);
  }

  const handleMinMistakeCountChange = (val: number) => {
    invalidatePuzzleRequests();
    setMinMistakeCount(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_mistake_count', String(val));
    }
  };

  const handleColorFilterChange = (color: 'BOTH' | 'WHITE' | 'BLACK') => {
    invalidatePuzzleRequests();
    setPuzzleColorFilter(color);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_puzzle_color_filter', color);
      const weaknessColor = color === 'BOTH' ? 'ALL' : color;
      localStorage.setItem('chessecho_weakness_color_filter', weaknessColor);
    }
  };

  const handleToggleSound = () => {
    const next = soundService.toggleSound();
    setSoundEnabled(next);
  };

  const captureExplorationReturnSnapshot = React.useCallback((tab?: TabType): LongDecisionReturnSnapshot => {
    const timerAttempt = timerAttemptRef.current;
    const timerWasRunning = timerAttempt?.timer.isRunning() ?? false;
    let timerShouldResume = timerWasRunning;
    let elapsedMs = timerElapsedMs;
    if (timerWasRunning && timerAttempt) {
      if (timerAttempt.timer instanceof StopwatchTimer) {
        elapsedMs = timerAttempt.timer.pause();
      } else {
        const remainingMs = timerAttempt.timer.pause();
        elapsedMs = timerAttempt.allowedMs - remainingMs;
        if (remainingMs === 0 && !timerAttempt.hasExpired) {
          timerAttempt.hasExpired = true;
          timerShouldResume = false;
          setTimerExpired(true);
          soundService.playSound('completion');
          void submitTimerAttempt('EXPIRED');
        }
      }
      setTimerElapsedMs(elapsedMs);
    }

    return {
      originatingTab: tab,
      activePuzzle,
      currentPuzzleIndex,
      initialMoveHistorySan,
      initialMoveHistoryStartFen,
      currentBoardFen,
      boardHistory: boardHistorySnapshotRef.current ?? boardHistorySnapshot,
      currentEvalCp,
      isEvalUnknown,
      evalHistory,
      feedbackHistory,
      historyIndex,
      moveHistory,
      hintSquare,
      feedback,
      showPuzzleSettings,
      isBoardFlipped,
      timerMode,
      timerElapsedMs: elapsedMs,
      timerAllowedMs,
      timerExpired: timerExpired || (timerAttempt?.hasExpired ?? false),
      timerSubmissionError,
      timerAttempt,
      timerWasRunning: timerShouldResume,
    };
  }, [
    activePuzzle,
    boardHistorySnapshot,
    currentBoardFen,
    currentEvalCp,
    currentPuzzleIndex,
    evalHistory,
    feedback,
    feedbackHistory,
    hintSquare,
    historyIndex,
    initialMoveHistorySan,
    initialMoveHistoryStartFen,
    isBoardFlipped,
    isEvalUnknown,
    moveHistory,
    showPuzzleSettings,
    submitTimerAttempt,
    timerAllowedMs,
    timerElapsedMs,
    timerExpired,
    timerMode,
    timerSubmissionError,
  ]);

  const restoreExplorationReturnSnapshot = React.useCallback((returnSnapshot: LongDecisionReturnSnapshot) => {
    setActivePuzzle(returnSnapshot.activePuzzle);
    setCurrentPuzzleIndex(returnSnapshot.currentPuzzleIndex);
    setInitialMoveHistorySan(returnSnapshot.initialMoveHistorySan);
    setInitialMoveHistoryStartFen(returnSnapshot.initialMoveHistoryStartFen);
    setCurrentBoardFen(returnSnapshot.currentBoardFen);
    boardHistorySnapshotRef.current = returnSnapshot.boardHistory;
    setBoardHistorySnapshot(returnSnapshot.boardHistory);
    setCurrentEvalCp(returnSnapshot.currentEvalCp);
    setIsEvalUnknown(returnSnapshot.isEvalUnknown);
    setEvalHistory(returnSnapshot.evalHistory);
    setFeedbackHistory(returnSnapshot.feedbackHistory);
    setHistoryIndex(returnSnapshot.historyIndex);
    setMoveHistory(returnSnapshot.moveHistory);
    setHintSquare(returnSnapshot.hintSquare);
    setFeedback(returnSnapshot.feedback);
    setShowPuzzleSettings(returnSnapshot.showPuzzleSettings);
    setIsBoardFlipped(returnSnapshot.isBoardFlipped);
    setTimerMode(returnSnapshot.timerMode);
    setTimerElapsedMs(returnSnapshot.timerElapsedMs);
    setTimerAllowedMs(returnSnapshot.timerAllowedMs);
    setTimerExpired(returnSnapshot.timerExpired);
    setTimerSubmissionError(returnSnapshot.timerSubmissionError);
    timerAttemptRef.current = returnSnapshot.timerAttempt;
    timerRef.current = returnSnapshot.timerAttempt?.timer ?? null;
    if (returnSnapshot.timerWasRunning) {
      returnSnapshot.timerAttempt?.timer.resume();
    }
  }, []);

  const handleUpdateEval = React.useCallback((evalCp: number, isUnknown: boolean) => {
    setCurrentEvalCp(evalCp);
    setIsEvalUnknown(isUnknown);
  }, []);

  const handleFeedbackChange = React.useCallback((nextFeedback: PuzzleFeedbackState) => {
    setFeedback(nextFeedback);
  }, []);

  const lineExplorationRef = React.useRef<ReturnType<typeof useLineExploration> | null>(null);

  const resetPuzzleInteractionState = React.useCallback((puzzle: Puzzle) => {
    const initialEval = puzzle.evalCp ?? 35;
    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setFeedback({ status: 'IDLE' });
    setHistoryIndex(0);
    setMoveHistory([]);
    boardHistorySnapshotRef.current = null;
    setBoardHistorySnapshot(null);
    setHintSquare(undefined);
    setIsEvalUnknown(false);
    setCurrentBoardFen(puzzle.fen);
    exitBlindfoldRef.current();
    lineExplorationRef.current?.resetExploration();
    setTimerElapsedMs(0);
    setTimerExpired(false);
    setTimerSubmissionError(null);
    if (timerRef.current) {
      if (timerRef.current instanceof StopwatchTimer) {
        timerRef.current.reset();
      } else if (timerRef.current instanceof CountdownTimer) {
        timerRef.current.reset();
      }
    }
  }, []);

  const resetPuzzleInteractionStateRef = React.useRef(resetPuzzleInteractionState);
  React.useLayoutEffect(() => {
    resetPuzzleInteractionStateRef.current = resetPuzzleInteractionState;
  });

  const onWeaknessCountChangeRef = React.useRef(onWeaknessCountChange);
  React.useLayoutEffect(() => {
    onWeaknessCountChangeRef.current = onWeaknessCountChange;
  });

  const handleExitExplorationInternal = React.useCallback(
    (decisionMove: string | null) => {
      if (hasSimilarPositionsReturnContext) {
        const returnSnapshot = longDecisionReturnSnapshotRef.current;
        if (returnSnapshot) {
          restoreExplorationReturnSnapshot(returnSnapshot);
          longDecisionReturnSnapshotRef.current = null;
        }
        onReturnToTab?.('similar-positions');
        return;
      }
      if (hasLongDecisionReturnContext) {
        const returnSnapshot = longDecisionReturnSnapshotRef.current;
        if (returnSnapshot) {
          restoreExplorationReturnSnapshot(returnSnapshot);
          longDecisionReturnSnapshotRef.current = null;
        }
        onReturnToTab?.('long-decisions');
        return;
      }
      const genericSnapshot = longDecisionReturnSnapshotRef.current;
      if (genericSnapshot) {
        restoreExplorationReturnSnapshot(genericSnapshot);
        longDecisionReturnSnapshotRef.current = null;
        if (genericSnapshot.originatingTab) {
          onReturnToTab?.(genericSnapshot.originatingTab);
        }
        return;
      }
      if (activePuzzle?.source === 'supplied') {
        setActivePuzzle(null);
        setInitialMoveHistorySan([]);
        setInitialMoveHistoryStartFen(undefined);
        setCurrentBoardFen('');
        setFeedback({ status: 'IDLE' });
        return;
      }
      if (decisionMove) {
        setFeedback({ status: 'IDLE' });
      } else {
        setFeedback({ status: 'CORRECT', lastMove: activePuzzle?.targetMove });
      }
    },
    [
      activePuzzle,
      hasLongDecisionReturnContext,
      hasSimilarPositionsReturnContext,
      onReturnToTab,
      restoreExplorationReturnSnapshot,
    ]
  );

  const lineExploration = useLineExploration({
    activePuzzle,
    currentBoardFen,
    currentEvalCp,
    isEvalUnknown,
    onUpdateEval: handleUpdateEval,
    onFeedbackChange: handleFeedbackChange,
    onExitExploration: handleExitExplorationInternal,
  });

  React.useLayoutEffect(() => {
    lineExplorationRef.current = lineExploration;
  });

  const isSourceNeutral = lineExploration.isExplorationActive && activePuzzle?.source === 'supplied';
  React.useEffect(() => {
    onSourceNeutralExplorationChange?.(isSourceNeutral);
  }, [isSourceNeutral, onSourceNeutralExplorationChange]);

  const handleExplorePosition = React.useCallback(
    (position: PositionExplorationStart, preserveReturnState = false, originatingTab?: TabType) => {
      if (!preserveReturnState && !hasLongDecisionReturnContext && !hasSimilarPositionsReturnContext) {
        longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot(originatingTab);
      }
      suppliedPositionCounterRef.current += 1;
      blindfold.exit();
      if (!preserveReturnState) {
        setTimerMode(null);
        setTimerElapsedMs(0);
        setTimerExpired(false);
        setTimerSubmissionError(null);
      }
      const side = position.fen.trim().split(/\s+/)[1];
      setActivePuzzle({
        puzzleId: `supplied-position-${suppliedPositionCounterRef.current}`,
        source: 'supplied',
        fen: position.fen,
        playerColor: side === 'b' ? 'BLACK' : 'WHITE',
        targetMove: '',
        openingTitle: '',
        acceptableMoves: [],
        movesPlayed: [],
        priority: 0,
        timesReached: 0,
        mistakeCount: 0,
        mistakeRate: 0,
      });
      setInitialMoveHistorySan(position.sanHistory);
      setInitialMoveHistoryStartFen(position.historyStartFen);
      setCurrentBoardFen(position.fen);
      boardHistorySnapshotRef.current = null;
      setBoardHistorySnapshot(null);
      setCurrentEvalCp(35);
      setFeedback({ status: 'IDLE' });
      setIsEvalUnknown(true);
      setHintSquare(undefined);
      onNavigateTab('puzzles');
    },
    [
      blindfold,
      captureExplorationReturnSnapshot,
      hasLongDecisionReturnContext,
      hasSimilarPositionsReturnContext,
      onNavigateTab,
    ]
  );

  const handleLongDecisionExplore = React.useCallback(
    (fen: string): boolean => {
      try {
        new Chess(fen);
      } catch {
        return false;
      }
      longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot('long-decisions');
      handleExplorePosition({ fen, sanHistory: [] }, true);
      return true;
    },
    [captureExplorationReturnSnapshot, handleExplorePosition]
  );

  const handleSimilarPositionsExplore = React.useCallback(
    (position: PositionExplorationStart): boolean => {
      try {
        new Chess(position.fen);
      } catch {
        return false;
      }
      longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot('similar-positions');
      handleExplorePosition(position, true);
      return true;
    },
    [captureExplorationReturnSnapshot, handleExplorePosition]
  );

  const handleSelectPractice = React.useCallback(
    (puzzle: Puzzle, fullList?: Puzzle[]) => {
      let targetList = puzzlesList;
      let targetIdx = -1;

      if (fullList && fullList.length > 0) {
        targetList = fullList;
        setPuzzlesList(fullList);
        targetIdx = fullList.findIndex((p) => p.puzzleId === puzzle.puzzleId);
      } else {
        targetIdx = puzzlesList.findIndex((p) => p.puzzleId === puzzle.puzzleId);
        if (targetIdx === -1) {
          targetList = [...puzzlesList, puzzle];
          setPuzzlesList(targetList);
          targetIdx = targetList.length - 1;
        }
      }

      const selectedPuzzle =
        targetIdx !== -1
          ? {
              ...targetList[targetIdx],
              gameUrls: puzzle.gameUrls || targetList[targetIdx].gameUrls || [],
            }
          : puzzle;
      const finalIndex = Math.max(0, targetIdx);

      setCurrentPuzzleIndex(finalIndex);
      setActivePuzzle(selectedPuzzle);
      setLoadedPuzzleContext(puzzleContextKey);
      resetPuzzleInteractionState(selectedPuzzle);
      lineExploration.resetExploration();

      if (typeof window !== 'undefined' && selectedPuzzle) {
        localStorage.setItem('chessecho_puzzle_id', selectedPuzzle.puzzleId);
      }
      onNavigateTab('puzzles');
    },
    [lineExploration, onNavigateTab, puzzleContextKey, puzzlesList, resetPuzzleInteractionState]
  );

  const clearAccountUiState = React.useCallback(() => {
    longDecisionReturnSnapshotRef.current = null;
    boardHistorySnapshotRef.current = null;
    setBoardHistorySnapshot(null);
    setPuzzlesList([]);
    setCurrentPuzzleIndex(0);
    setActivePuzzle(null);
    setPuzzleLoadError(false);
    setIsLoadingPuzzles(false);
    setPuzzlePage(0);
    setHasMorePuzzles(false);
    setIsFetchingMorePuzzles(false);
    onWeaknessCountChange?.(0);
  }, [onWeaknessCountChange]);

  React.useLayoutEffect(() => {
    if (actionsRef) {
      actionsRef.current = {
        explorePosition: handleExplorePosition,
        exploreLongDecision: handleLongDecisionExplore,
        exploreSimilarPositions: handleSimilarPositionsExplore,
        selectPracticeFromLibrary: handleSelectPractice,
        clearAccountUiState,
        invalidatePuzzleRequests,
      };
    }
  }, [
    actionsRef,
    clearAccountUiState,
    handleExplorePosition,
    handleLongDecisionExplore,
    handleSelectPractice,
    handleSimilarPositionsExplore,
    invalidatePuzzleRequests,
  ]);

  // Puzzle query tracking
  const [trackedPuzzleQuery, setTrackedPuzzleQuery] = useState<{
    isSettingsInitialized: boolean;
    activeUsername?: string;
    puzzleColorFilter: 'BOTH' | 'WHITE' | 'BLACK';
    minEvalLoss: number;
    minMistakeCount: number;
  } | null>(null);

  if (
    !trackedPuzzleQuery ||
    trackedPuzzleQuery.isSettingsInitialized !== isSettingsInitialized ||
    trackedPuzzleQuery.activeUsername !== activeUsername ||
    trackedPuzzleQuery.puzzleColorFilter !== puzzleColorFilter ||
    trackedPuzzleQuery.minEvalLoss !== minEvalLoss ||
    trackedPuzzleQuery.minMistakeCount !== minMistakeCount
  ) {
    setTrackedPuzzleQuery({
      isSettingsInitialized,
      activeUsername,
      puzzleColorFilter,
      minEvalLoss,
      minMistakeCount,
    });
    if (!isSettingsInitialized || !activeUsername) {
      if (!activeUsername && activePuzzle?.source !== 'supplied') {
        setPuzzlesList([]);
        setActivePuzzle(null);
        setPuzzleLoadError(false);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        setIsLoadingPuzzles(false);
        setPuzzlePage(0);
        setHasMorePuzzles(false);
        setIsFetchingMorePuzzles(false);
      }
    }
  }

  React.useEffect(() => {
    if (!isSettingsInitialized || !activeUsername) {
      return;
    }
    const requestSequence = puzzleLoadSeqRef;

    async function loadData() {
      const seq = ++puzzleLoadSeqRef.current;
      await sessionGatePromise;
      if (seq !== puzzleLoadSeqRef.current) return;
      setIsLoadingPuzzles(true);
      setPuzzleLoadError(false);
      setPuzzlePage(0);
      setHasMorePuzzles(true);
      setIsFetchingMorePuzzles(false);
      try {
        const data = await fetchPuzzles(
          activeAccountId || activeUsername!,
          'CHESS_COM',
          puzzleColorFilter,
          minEvalLoss,
          minMistakeCount,
          10,
          0
        );

        if (seq !== puzzleLoadSeqRef.current) return;

        if (data && data.length > 0) {
          setPuzzlesList(data);
          const savedId =
            typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null;
          const foundIdx = savedId ? data.findIndex((p) => p.puzzleId === savedId) : -1;
          const targetIdx = foundIdx !== -1 ? foundIdx : 0;
          const targetPuzzle = data[targetIdx];

          setCurrentPuzzleIndex(targetIdx);
          setActivePuzzle(targetPuzzle);
          setLoadedPuzzleContext(puzzleContextKey);
          resetPuzzleInteractionStateRef.current?.(targetPuzzle);
          onWeaknessCountChangeRef.current?.(data.length);
        } else {
          setPuzzlesList([]);
          setActivePuzzle(null);
          setCurrentPuzzleIndex(0);
          onWeaknessCountChangeRef.current?.(0);
        }
      } catch (err) {
        if (seq !== puzzleLoadSeqRef.current) return;
        console.error('Failed to load puzzles from backend API:', err);
        setPuzzleLoadError(true);
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        setHasMorePuzzles(false);
      } finally {
        if (seq === puzzleLoadSeqRef.current) {
          setIsLoadingPuzzles(false);
        }
      }
    }
    loadData();
    return () => {
      requestSequence.current++;
    };
  }, [
    isSettingsInitialized,
    activeUsername,
    activeAccountId,
    puzzleColorFilter,
    minEvalLoss,
    minMistakeCount,
    puzzleReloadToken,
    puzzleContextKey,
    sessionGatePromise,
  ]);

  const handleRetryPuzzleLoad = () => {
    invalidatePuzzleRequests();
    setPuzzleReloadToken((token) => token + 1);
  };

  const handleApplyPuzzleSettings = async () => {
    if (!activeUsername) return;
    const seq = ++puzzleLoadSeqRef.current;
    setIsLoadingPuzzles(true);
    setPuzzleLoadError(false);
    setPuzzlePage(0);
    setHasMorePuzzles(true);
    setIsFetchingMorePuzzles(false);
    try {
      const data = await fetchPuzzles(
        activeAccountId || activeUsername,
        'CHESS_COM',
        puzzleColorFilter,
        minEvalLoss,
        minMistakeCount,
        10,
        0
      );

      if (seq !== puzzleLoadSeqRef.current) return;

      if (data && data.length > 0) {
        setPuzzlesList(data);
        const currentId =
          activePuzzle?.puzzleId ||
          (typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null);
        const foundIdx = currentId ? data.findIndex((p) => p.puzzleId === currentId) : -1;
        const targetIdx = foundIdx !== -1 ? foundIdx : 0;
        const targetPuzzle = data[targetIdx];

        setCurrentPuzzleIndex(targetIdx);
        setActivePuzzle(targetPuzzle);
        setLoadedPuzzleContext(puzzleContextKey);
        resetPuzzleInteractionState(targetPuzzle);
        lineExploration.resetExploration();
        onWeaknessCountChange?.(data.length);
      } else {
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        setHasMorePuzzles(false);
        if (typeof window !== 'undefined') {
          localStorage.removeItem('chessecho_puzzle_id');
        }
      }
    } catch (err) {
      if (seq !== puzzleLoadSeqRef.current) return;
      console.error('Failed to apply puzzle settings:', err);
      setPuzzleLoadError(true);
      setPuzzlesList([]);
      setActivePuzzle(null);
      setFeedback({ status: 'IDLE' });
      setMoveHistory([]);
      setHintSquare(undefined);
      setHasMorePuzzles(false);
    } finally {
      if (seq === puzzleLoadSeqRef.current) {
        setIsLoadingPuzzles(false);
      }
    }
  };

  const handlePreviousPuzzle = () => {
    if (puzzlesList.length === 0) return;
    const prevIndex = (currentPuzzleIndex - 1 + puzzlesList.length) % puzzlesList.length;
    const prevPuzzle = puzzlesList[prevIndex];

    setCurrentPuzzleIndex(prevIndex);
    setActivePuzzle(prevPuzzle);
    resetPuzzleInteractionState(prevPuzzle);
    lineExploration.resetExploration();
    if (typeof window !== 'undefined' && prevPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', prevPuzzle.puzzleId);
    }
  };

  const handleNextPuzzle = async () => {
    if (puzzlesList.length === 0) return;
    const nextIndex = (currentPuzzleIndex + 1) % puzzlesList.length;
    const nextPuzzle = puzzlesList[nextIndex];

    setCurrentPuzzleIndex(nextIndex);
    setActivePuzzle(nextPuzzle);
    resetPuzzleInteractionState(nextPuzzle);
    lineExploration.resetExploration();
    if (typeof window !== 'undefined' && nextPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', nextPuzzle.puzzleId);
    }

    if (
      activeUsername &&
      hasMorePuzzles &&
      !isFetchingMorePuzzles &&
      nextIndex >= puzzlesList.length - 2
    ) {
      const seq = puzzleLoadSeqRef.current;
      setIsFetchingMorePuzzles(true);
      const nextPage = puzzlePage + 1;
      try {
        const data = await fetchPuzzles(
          activeAccountId || activeUsername,
          'CHESS_COM',
          puzzleColorFilter,
          minEvalLoss,
          minMistakeCount,
          10,
          nextPage
        );

        if (seq !== puzzleLoadSeqRef.current) return;

        if (data && data.length > 0) {
          setPuzzlesList((prev) => {
            const existingIds = new Set(prev.map((p) => p.puzzleId));
            const newItems = data.filter((p) => !existingIds.has(p.puzzleId));
            return [...prev, ...newItems];
          });
          setPuzzlePage(nextPage);
          if (data.length < 10) {
            setHasMorePuzzles(false);
          }
        } else {
          setHasMorePuzzles(false);
        }
      } catch (err) {
        if (seq !== puzzleLoadSeqRef.current) return;
        console.error('Failed to prefetch next puzzle page:', err);
      } finally {
        if (seq === puzzleLoadSeqRef.current) {
          setIsFetchingMorePuzzles(false);
        }
      }
    }
  };

  const handleBoardUndo = () => {
    lineExploration.handleUndo();
    if (lineExploration.isExplorationActive) return;

    const prevIndex = Math.max(0, historyIndex - 1);
    setHistoryIndex(prevIndex);
    setCurrentEvalCp(evalHistory[prevIndex] ?? (activePuzzle?.evalCp ?? 35));
    const prevFeedback = feedbackHistory[prevIndex];
    setFeedback(prevFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleBoardRedo = () => {
    lineExploration.handleRedo();
    if (lineExploration.isExplorationActive) return;

    if (historyIndex < evalHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      setHistoryIndex(nextIndex);
      setCurrentEvalCp(evalHistory[nextIndex]);
      const nextFeedback = feedbackHistory[nextIndex];
      setFeedback(nextFeedback ?? { status: 'IDLE' });
    }
  };

  const handleBoardReset = () => {
    lineExploration.resetExploration();
    setHistoryIndex(0);
    setCurrentEvalCp(evalHistory[0] ?? (activePuzzle?.evalCp ?? 35));
    const firstFeedback = feedbackHistory[0];
    setFeedback(firstFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleMoveAttempt = (
    moveSan: string,
    isCorrect: boolean,
    isHistoricalMistake: boolean,
    historicalInfo?: { timesPlayed: number; averageLoss: number },
    isInitialDecision: boolean = true
  ) => {
    if (!activePuzzle) return;
    if (isInitialDecision && activePuzzle.source !== 'supplied') {
      if (!puzzleContextReady) return;
      void puzzleAttempts.submit(moveSan, isCorrect);
    }
    setMoveHistory((prev) => [...prev, moveSan]);
    setHintSquare(undefined);

    const startCp = activePuzzle.evalCp ?? 35;

    if (isInitialDecision) {
      if (activePuzzle.source === 'supplied' || !activePuzzle.targetMove) {
        void evaluateMove(activePuzzle.fen, moveSan).then((evalResult) => {
          if (!evalResult) return;
          const acceptable = evalResult.acceptable;
          const evalCp = evalResult.evalCp ?? startCp;
          const nextFeedback: PuzzleFeedbackState = {
            status: acceptable ? 'CORRECT' : 'INCORRECT',
            lastMove: moveSan,
          };
          setFeedback(nextFeedback);
          setCurrentEvalCp(evalCp);
          setIsEvalUnknown(false);
          setEvalHistory((prev) => [...prev.slice(0, historyIndex + 1), evalCp]);
          setFeedbackHistory((prev) => [...prev.slice(0, historyIndex + 1), nextFeedback]);
          setHistoryIndex((prev) => prev + 1);
          if (acceptable) {
            soundService.playSound('completion');
          } else {
            soundService.playSound('incorrect');
          }
        });
        return;
      }

      let calculatedEval = startCp;
      let moveHasEngineData = true;
      const acceptableMatch = activePuzzle.acceptableMoves.find((m) => m.move === moveSan);

      if (moveSan === activePuzzle.targetMove) {
        calculatedEval = startCp;
      } else if (acceptableMatch) {
        const lossCp = Math.round(acceptableMatch.evalLoss * 100);
        calculatedEval =
          activePuzzle.playerColor === 'BLACK' ? startCp + lossCp : startCp - lossCp;
      } else if (isHistoricalMistake && historicalInfo) {
        const lossCp = Math.round(historicalInfo.averageLoss * 100);
        calculatedEval =
          activePuzzle.playerColor === 'BLACK' ? startCp + lossCp : startCp - lossCp;
      } else {
        moveHasEngineData = false;
        calculatedEval = currentEvalCp;
      }

      let newFeedbackState: PuzzleFeedbackState = { status: 'IDLE' };

      if (isCorrect) {
        newFeedbackState = { status: 'CORRECT', lastMove: moveSan };
        if (timerMode && !timerExpired) {
          void submitTimerAttempt('SUBMITTED');
        }
      } else if (isHistoricalMistake) {
        newFeedbackState = { status: 'HISTORICAL_MISTAKE', lastMove: moveSan, historicalInfo };
      } else {
        newFeedbackState = { status: 'INCORRECT', lastMove: moveSan };
      }

      const trimmedEvalHist = evalHistory.slice(0, historyIndex + 1);
      const trimmedFeedHist = feedbackHistory.slice(0, historyIndex + 1);

      trimmedEvalHist.push(calculatedEval);
      trimmedFeedHist.push(newFeedbackState);

      setEvalHistory(trimmedEvalHist);
      setFeedbackHistory(trimmedFeedHist);
      setHistoryIndex(trimmedEvalHist.length - 1);

      setCurrentEvalCp(calculatedEval);
      setIsEvalUnknown(!moveHasEngineData);
      setFeedback(newFeedbackState);
    } else {
      const trimmedEvalHist = evalHistory.slice(0, historyIndex + 1);
      const trimmedFeedHist = feedbackHistory.slice(0, historyIndex + 1);

      trimmedEvalHist.push(currentEvalCp);
      trimmedFeedHist.push(feedback);

      setEvalHistory(trimmedEvalHist);
      setFeedbackHistory(trimmedFeedHist);
      setHistoryIndex(trimmedEvalHist.length - 1);
    }
  };

  const puzzleBoardOrientation: 'white' | 'black' =
    (activePuzzle?.playerColor === 'WHITE') !== isBoardFlipped ? 'white' : 'black';

  const isPuzzleBoardUnsettled =
    lineExploration.continuation.loading ||
    !!lineExploration.requestedContinuationFen ||
    !!lineExploration.pendingContinuationCandidate ||
    !!lineExploration.alternativeContinuationToApply ||
    lineExploration.isMoveEvaluationPending;

  const handleEnterBlindfold = () => {
    if (!activePuzzle || activePuzzle.source === 'supplied' || isPuzzleBoardUnsettled) return;
    blindfold.start(currentBoardFen || activePuzzle.fen, activePuzzle.playerColor);
  };

  return (
    <div
      hidden={hidden}
      className={`max-w-[1536px] w-full mx-auto px-4 lg:px-8 2xl:max-w-none 2xl:px-4 flex-1 flex flex-col justify-center min-h-0 ${
        hidden ? 'hidden' : ''
      }`}
    >
      <div className="flex-1 flex flex-col justify-center min-h-0">
        {isLoadingPuzzles ? (
          <div className="flex-1 flex items-center justify-center py-16">
            <div className="flex items-center space-x-3 text-primary bg-surface-container-low px-5 py-3 rounded-2xl border border-outline-variant shadow-lg">
              <div className="w-5 h-5 border-2 border-primary border-t-transparent rounded-full animate-spin" />
              <span className="text-xs font-bold text-on-surface-variant font-mono">
                Loading Practice Puzzles...
              </span>
            </div>
          </div>
        ) : puzzleLoadError ? (
          <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-error/30 shadow-xl">
            <div className="w-14 h-14 rounded-2xl bg-error-container/20 border border-error/40 flex items-center justify-center mx-auto text-error">
              <span className="text-3xl font-bold font-mono">!</span>
            </div>
            <div>
              <h3 className="text-lg font-bold text-on-surface">Couldn&apos;t Load Puzzles</h3>
              <p className="text-xs text-error mt-1 font-mono">
                We couldn&apos;t load your puzzles. Please try again.
              </p>
            </div>
            <button
              onClick={handleRetryPuzzleLoad}
              className="px-5 py-2.5 bg-surface-container-high hover:bg-surface-bright text-on-surface font-bold text-xs font-mono rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
            >
              <span>Retry</span>
            </button>
          </div>
        ) : !activePuzzle ? (
          <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
            <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-primary">
              <span className="text-3xl font-bold">♟</span>
            </div>
            <div>
              <h3 className="text-lg font-bold text-on-surface">No Practice Puzzles Available</h3>
              <p className="text-xs text-on-surface-variant mt-1">
                {!activeUsername
                  ? 'Import your games using the Import Games tab to detect your opening habits and build your custom puzzle set.'
                  : `No weakness positions detected yet for ${activeUsername}. Import more games or adjust search criteria.`}
              </p>
            </div>
            <button
              onClick={() => onNavigateTab('import')}
              className="px-5 py-2.5 bg-primary-container hover:bg-primary text-on-primary-container font-bold text-xs font-mono rounded-xl transition shadow-lg cursor-pointer"
            >
              Import Your Games Now
            </button>
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center gap-4 lg:flex-row lg:flex-wrap lg:items-start 2xl:flex-nowrap">
            {/* Left Stockfish Eval Bar */}
            <div className="hidden sm:flex flex-col items-center pt-1">
              <span className="text-[10px] font-bold text-slate-400 mb-1.5 uppercase tracking-wider">
                Eval
              </span>
              <EvalBar
                evalCp={currentEvalCp}
                isExploring={!lineExploration.isExplorationActive && historyIndex > 1}
                isUnknown={isEvalUnknown}
              />
            </div>

            {/* Center Interactive Chessboard & Controls */}
            <div className="w-full max-w-[640px] shrink-0 2xl:max-w-[760px] 2xl:w-auto 2xl:min-w-0 2xl:basis-[760px] 2xl:grow 2xl:shrink">
              <ChessBoardArea
                key={activePuzzle.source === 'supplied' ? activePuzzle.puzzleId : 'puzzle-board'}
                initialFen={activePuzzle.fen}
                initialBoardHistory={
                  activePuzzle.source === 'supplied' ? null : boardHistorySnapshot
                }
                onBoardHistoryChange={handleBoardHistoryChange}
                initialMoveHistorySan={
                  activePuzzle.source === 'supplied' ? initialMoveHistorySan : undefined
                }
                initialMoveHistoryStartFen={
                  activePuzzle.source === 'supplied' ? initialMoveHistoryStartFen : undefined
                }
                showPuzzleControls={activePuzzle.source !== 'supplied'}
                playerColor={activePuzzle.playerColor}
                blindfoldMode={isBlindfoldActive}
                boardOrientation={puzzleBoardOrientation}
                targetMove={activePuzzle.targetMove}
                acceptableMoves={activePuzzle.acceptableMoves}
                movesPlayed={activePuzzle.movesPlayed}
                onMoveAttempt={handleMoveAttempt}
                initialDecisionEnabled={activePuzzle.source === 'supplied' || puzzleContextReady}
                onPreviousPuzzle={handlePreviousPuzzle}
                onNextPuzzle={handleNextPuzzle}
                onUndo={handleBoardUndo}
                onRedo={handleBoardRedo}
                onReset={handleBoardReset}
                hintSquare={hintSquare}
                canHint={
                  feedback.status !== 'CORRECT' &&
                  (!lineExploration.isExplorationActive ||
                    !!lineExploration.pendingContinuationCandidate?.move)
                }
                onFlipBoard={() => setIsBoardFlipped((prev) => !prev)}
                soundEnabled={soundEnabled}
                onToggleSound={handleToggleSound}
                onFenChange={setCurrentBoardFen}
                pendingContinuationCandidate={lineExploration.pendingContinuationCandidate}
                onContinuationApplied={lineExploration.handleContinuationApplied}
                isExplorationActive={lineExploration.isExplorationActive}
                onUnacceptableMove={lineExploration.handleUnacceptableMove}
                onUserExplorationMove={lineExploration.handleUserExplorationMove}
                onChessEchoExplorationMove={lineExploration.handleChessEchoExplorationMove}
                alternativeContinuationToApply={lineExploration.alternativeContinuationToApply}
                onAlternativeContinuationApplied={() =>
                  lineExploration.setAlternativeContinuationToApply(null)
                }
                explorationPlayMode={lineExploration.explorationPlayMode}
                isChallengeComplete={lineExploration.challengeSubmission?.isComplete ?? false}
                onMoveEvaluationPendingChange={lineExploration.setIsMoveEvaluationPending}
              />
              {blindfold.state && (
                <BlindfoldBoardWrapper
                  fen={blindfold.state.currentFen}
                  isVisible={blindfold.state.isVisible}
                  onReveal={blindfold.reveal}
                  onHide={blindfold.hide}
                  onToggleVisibility={blindfold.toggleVisibility}
                  boardOrientation={puzzleBoardOrientation}
                />
              )}
            </div>

            {/* Right Feedback & Settings Panel */}
            <div className="w-full max-w-[480px] shrink-0 2xl:max-w-[360px] 2xl:w-auto 2xl:min-w-0 2xl:basis-[360px] 2xl:shrink">
              {blindfold.state ? (
                <NotationExchangeUI
                  state={blindfold.state}
                  onPlayerMove={blindfold.submitPlayerMove}
                  onReveal={blindfold.reveal}
                  onHide={blindfold.hide}
                  onToggleVisibility={blindfold.toggleVisibility}
                  onReset={blindfold.reset}
                  onExit={blindfold.exit}
                />
              ) : (
                <PuzzleFeedbackPanel
                  puzzle={activePuzzle}
                  feedback={feedback}
                  onPreviousPuzzle={handlePreviousPuzzle}
                  onNextPuzzle={handleNextPuzzle}
                  puzzleColorFilter={puzzleColorFilter}
                  onColorFilterChange={handleColorFilterChange}
                  showPuzzleSettings={showPuzzleSettings}
                  onTogglePuzzleSettings={() => setShowPuzzleSettings((v) => !v)}
                  minMistakeCount={minMistakeCount}
                  onMinMistakeCountChange={handleMinMistakeCountChange}
                  onApplySettings={handleApplyPuzzleSettings}
                  username={activeUsername}
                  isExplorationActive={lineExploration.isExplorationActive}
                  explorationDecisionMove={lineExploration.explorationDecisionMove}
                  onEnterExploration={lineExploration.handleEnterExploration}
                  onExitExploration={lineExploration.handleExitExploration}
                  onEnterBlindfold={
                    activePuzzle.source === 'supplied' ? undefined : handleEnterBlindfold
                  }
                  isBlindfoldEntryDisabled={isPuzzleBoardUnsettled}
                  continuationMode={lineExploration.continuationMode}
                  onContinuationModeChange={lineExploration.setContinuationMode}
                  opponentRatingBand={lineExploration.opponentRatingBand}
                  onOpponentRatingBandChange={lineExploration.setOpponentRatingBand}
                  explorationPlayMode={lineExploration.explorationPlayMode}
                  onExplorationPlayModeChange={lineExploration.handleExplorationPlayModeChange}
                  sideToMove={lineExploration.sideToMove}
                  continuationCandidate={lineExploration.continuation.selectedCandidate}
                  isContinuationLoading={
                    lineExploration.continuation.loading ||
                    !!lineExploration.requestedContinuationFen
                  }
                  unacceptableMoveMessage={lineExploration.unacceptableMoveMessage}
                  explorationFeedback={lineExploration.explorationFeedback}
                  lastContinuationCandidates={lineExploration.lastContinuationCandidates}
                  onAlternativeSelected={lineExploration.handleAlternativeSelected}
                  challengeCandidates={lineExploration.challengeCandidates}
                  challengeSubmission={lineExploration.challengeSubmission}
                  challengeInput={lineExploration.challengeInput}
                  onChallengeInputChange={lineExploration.onChallengeInputChange}
                  onChallengeSubmit={lineExploration.onChallengeSubmit}
                  challengeFeedback={lineExploration.challengeFeedback}
                  isChallengeLoading={lineExploration.isChallengeLoading}
                  onChallengeCandidateSelect={lineExploration.onChallengeCandidateSelect}
                  activeChallengeCandidate={lineExploration.activeChallengeCandidate}
                  challengeBranches={lineExploration.challengeBranches}
                  onFinishChallenge={lineExploration.handleExitExploration}
                  calculationInput={lineExploration.calculationInput}
                  onCalculationInputChange={lineExploration.onCalculationInputChange}
                  onCalculationSubmit={lineExploration.onCalculationSubmit}
                  calculationFeedback={lineExploration.calculationFeedback}
                  isCalculationLoading={lineExploration.isCalculationLoading}
                  onCalculationBack={lineExploration.onCalculationBack}
                  onBackToCandidates={lineExploration.onBackToCandidates}
                  timerMode={timerMode}
                  timerElapsedMs={timerElapsedMs}
                  timerAllowedMs={timerAllowedMs}
                  timerExpired={timerExpired}
                  timerSubmissionError={timerSubmissionError}
                  attemptCountState={puzzleAttempts.state}
                  attemptRecording={puzzleAttempts.recording}
                  pendingAttempts={puzzleAttempts.pending}
                  onRetryAttempt={puzzleAttempts.retry}
                  onReloadAttemptCount={puzzleAttempts.reload}
                  onTimerModeChange={setTimerMode}
                  onTimerAllowedMsChange={setTimerAllowedMs}
                />
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
