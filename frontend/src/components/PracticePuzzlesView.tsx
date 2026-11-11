'use client';

import React, { useState, useSyncExternalStore } from 'react';
import { Chess } from 'chess.js';
import type { TabType } from '@/components/Header';
import type { PositionExplorationStart } from '@/components/ExplorePositionModal';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea, type ChessBoardHistorySnapshot } from '@/components/ChessBoardArea';
import { BlindfoldBoardWrapper } from '@/components/BlindfoldBoardWrapper';
import { NotationExchangeUI } from '@/components/NotationExchangeUI';
import { PositionCard } from '@/components/PositionCard';
import { ChessBoardView } from '@/components/ChessBoardView';
import {
  Swords,
  Filter,
  SlidersHorizontal,
  Repeat,
  LayoutGrid,
  List,
  ArrowLeft,
} from 'lucide-react';
import { useBlindfoldSession } from '@/hooks/useBlindfoldSession';
import { usePuzzleAttemptCount } from '@/hooks/usePuzzleAttemptCount';
import { PuzzleFeedbackPanel } from '@/components/PuzzleFeedbackPanel';
import {
  Puzzle,
  type LongDecisionContext,
  type SimilarPositionContext,
  type PuzzleSource,
  isWeaknessBacked,
} from '@/types/puzzle';
import {
  fetchPuzzles,
  fetchPuzzleContinuation,
  submitTrainingAttempt,
  evaluateMove,
  type SessionState,
  type LongDecisionOccurrence,
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

function getAuthoritativeTimerElapsed(
  timer: StopwatchTimer | CountdownTimer | null,
  allowedMs: number,
  fallbackMs: number,
): number {
  if (!timer) return fallbackMs;
  if (timer.isRunning()) {
    return timer instanceof StopwatchTimer ? timer.stop() : allowedMs - timer.pause();
  }
  return timer instanceof StopwatchTimer ? timer.getElapsed() : allowedMs - timer.getRemaining();
}

export type PracticePuzzlesViewHandle = {
  explorePosition: (
    position: PositionExplorationStart,
    preserveReturnState?: boolean,
    originatingTab?: TabType,
    source?: PuzzleSource,
    longDecisionContext?: LongDecisionContext,
    similarContext?: SimilarPositionContext
  ) => boolean;
  exploreLongDecision: (occurrenceOrFen: LongDecisionOccurrence | string) => boolean;
  exploreSimilarPositions: (position: PositionExplorationStart, metadata?: SimilarPositionContext) => boolean;
  selectPracticeFromLibrary: (puzzle: Puzzle, fullList?: Puzzle[], originatingTab?: TabType) => void;
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
  hasGameAnalysisReturnContext?: boolean;
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
  hasGameAnalysisReturnContext = false,
}: PracticePuzzlesViewProps) {
  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>([]);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle | null>(null);
  const [viewMode, setViewMode] = useState<'practice' | 'discovery'>('practice');
  const [catalogViewMode, setCatalogViewMode] = useState<'grid' | 'list'>('grid');
  const [initialMoveHistorySan, setInitialMoveHistorySan] = useState<string[]>([]);
  const [initialMoveHistoryStartFen, setInitialMoveHistoryStartFen] = useState<string | undefined>();
  const [boardHistorySnapshot, setBoardHistorySnapshot] = useState<ChessBoardHistorySnapshot | null>(null);
  const boardHistorySnapshotRef = React.useRef<ChessBoardHistorySnapshot | null>(null);
  const [boardResetTrigger, setBoardResetTrigger] = useState<number>(0);
  const longDecisionReturnSnapshotRef = React.useRef<LongDecisionReturnSnapshot | null>(null);
  const [activeOriginatingTab, setActiveOriginatingTab] = useState<TabType | null>(null);
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
  const [isEvaluatingMove, setIsEvaluatingMove] = useState<boolean>(false);
  const evaluationRequestIdRef = React.useRef<number>(0);
  const activePuzzleRef = React.useRef<Puzzle | null>(null);
  React.useEffect(() => {
    activePuzzleRef.current = activePuzzle;
  }, [activePuzzle]);

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
      isWeaknessBacked(activePuzzle)
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
      if (!isWeaknessBacked(activePuzzle)) return;
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
    if (hasSimilarPositionsReturnContext) return;
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

  const handleMinEvalLossChange = (val: number) => {
    invalidatePuzzleRequests();
    setMinEvalLoss(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_eval_loss', String(val));
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
      const returnSnapshot = longDecisionReturnSnapshotRef.current;
      if (returnSnapshot) {
        restoreExplorationReturnSnapshot(returnSnapshot);
        longDecisionReturnSnapshotRef.current = null;
        setActiveOriginatingTab(null);
        if (returnSnapshot.originatingTab) {
          onReturnToTab?.(returnSnapshot.originatingTab);
        }
        return;
      }
      if (hasSimilarPositionsReturnContext) {
        onReturnToTab?.('similar-positions');
        return;
      }
      if (hasLongDecisionReturnContext) {
        onReturnToTab?.('long-decisions');
        return;
      }
      if (hasGameAnalysisReturnContext) {
        onReturnToTab?.('game-analysis');
        return;
      }
      if (
        activePuzzle?.source === 'supplied' ||
        activePuzzle?.source === 'long-decision' ||
        activePuzzle?.source === 'similar'
      ) {
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
      hasGameAnalysisReturnContext,
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

  const weaknessBacked = isWeaknessBacked(activePuzzle);
  const isWeaknessPlaylist =
    activePuzzle?.source === 'weakness' || (!activePuzzle?.source && weaknessBacked);
  const isSourceNeutral = lineExploration.isExplorationActive && !weaknessBacked;
  React.useEffect(() => {
    onSourceNeutralExplorationChange?.(isSourceNeutral);
  }, [isSourceNeutral, onSourceNeutralExplorationChange]);

  const handleExplorePosition = React.useCallback(
    (
      position: PositionExplorationStart,
      preserveReturnState = false,
      originatingTab?: TabType,
      source: PuzzleSource = 'supplied',
      longDecisionContext?: LongDecisionContext,
      similarContext?: SimilarPositionContext
    ): boolean => {
      try {
        new Chess(position.fen);
      } catch {
        return false;
      }
      evaluationRequestIdRef.current += 1;
      setIsEvaluatingMove(false);
      if (!preserveReturnState && !hasLongDecisionReturnContext && !hasSimilarPositionsReturnContext) {
        longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot(originatingTab);
        setActiveOriginatingTab(originatingTab ?? null);
      }
      suppliedPositionCounterRef.current += 1;
      blindfold.exit();
      if (source === 'long-decision') {
        setTimerMode('STOPWATCH');
        setTimerElapsedMs(0);
        setTimerExpired(false);
        setTimerSubmissionError(null);
        timerRef.current = null;
        timerAttemptRef.current = null;
      } else if (!preserveReturnState) {
        setTimerMode(null);
        setTimerElapsedMs(0);
        setTimerExpired(false);
        setTimerSubmissionError(null);
      }
      const side = position.fen.trim().split(/\s+/)[1];
      setActivePuzzle({
        puzzleId: `supplied-position-${suppliedPositionCounterRef.current}`,
        source,
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
        longDecisionContext,
        similarContext,
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
      setViewMode('practice');
      onNavigateTab('puzzles');
      return true;
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
    (occurrenceOrFen: LongDecisionOccurrence | string): boolean => {
      const isOccurrence = typeof occurrenceOrFen !== 'string';
      const fen = isOccurrence ? occurrenceOrFen.fen : occurrenceOrFen;
      try {
        new Chess(fen);
      } catch {
        return false;
      }
      const longDecisionContext: LongDecisionContext | undefined = isOccurrence
        ? {
            timeControl: occurrenceOrFen.timeControl,
            decisionTimeMs: occurrenceOrFen.decisionTimeMs,
            movePlayed: occurrenceOrFen.movePlayed,
            severity: occurrenceOrFen.severity,
            gameUrl: occurrenceOrFen.chessComUrl,
            platformGameId: occurrenceOrFen.platformGameId,
            plyNumber: occurrenceOrFen.plyNumber,
          }
        : undefined;

      longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot('long-decisions');
      setActiveOriginatingTab('long-decisions');
      return handleExplorePosition(
        { fen, sanHistory: [] },
        true,
        'long-decisions',
        'long-decision',
        longDecisionContext
      );
    },
    [captureExplorationReturnSnapshot, handleExplorePosition]
  );

  const handleSimilarPositionsExplore = React.useCallback(
    (position: PositionExplorationStart, metadata?: SimilarPositionContext): boolean => {
      try {
        new Chess(position.fen);
      } catch {
        return false;
      }
      longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot('similar-positions');
      setActiveOriginatingTab('similar-positions');
      return handleExplorePosition(
        position,
        true,
        'similar-positions',
        'similar',
        undefined,
        metadata
      );
    },
    [captureExplorationReturnSnapshot, handleExplorePosition]
  );

  const handleSelectPractice = React.useCallback(
    (puzzle: Puzzle, fullList?: Puzzle[], originatingTab?: TabType) => {
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
              source: 'weakness' as const,
              gameUrls: puzzle.gameUrls || targetList[targetIdx].gameUrls || [],
            }
          : {
              ...puzzle,
              source: 'weakness' as const,
            };
      const finalIndex = Math.max(0, targetIdx);

      setCurrentPuzzleIndex(finalIndex);
      evaluationRequestIdRef.current += 1;
      setIsEvaluatingMove(false);
      setActivePuzzle(selectedPuzzle);
      setLoadedPuzzleContext(puzzleContextKey);
      resetPuzzleInteractionState(selectedPuzzle);
      lineExploration.resetExploration();

      if (typeof window !== 'undefined' && selectedPuzzle) {
        localStorage.setItem('chessecho_puzzle_id', selectedPuzzle.puzzleId);
      }
      if (originatingTab) {
        longDecisionReturnSnapshotRef.current = captureExplorationReturnSnapshot(originatingTab);
        setActiveOriginatingTab(originatingTab);
      }
      setViewMode('practice');
      onNavigateTab('puzzles');
    },
    [
      captureExplorationReturnSnapshot,
      lineExploration,
      onNavigateTab,
      puzzleContextKey,
      puzzlesList,
      resetPuzzleInteractionState,
    ]
  );

  const handleStartPractice = React.useCallback(
    (puzzle?: Puzzle, index?: number) => {
      if (puzzle && index !== undefined) {
        setCurrentPuzzleIndex(index);
        setActivePuzzle(puzzle);
        resetPuzzleInteractionState(puzzle);
      } else if (puzzlesList.length > 0) {
        const idx =
          currentPuzzleIndex >= 0 && currentPuzzleIndex < puzzlesList.length
            ? currentPuzzleIndex
            : 0;
        setCurrentPuzzleIndex(idx);
        setActivePuzzle(puzzlesList[idx]);
        resetPuzzleInteractionState(puzzlesList[idx]);
      }
      setViewMode('practice');
    },
    [currentPuzzleIndex, puzzlesList, resetPuzzleInteractionState]
  );

  const handleExitPractice = React.useCallback(() => {
    const returnSnapshot = longDecisionReturnSnapshotRef.current;
    if (returnSnapshot?.originatingTab && returnSnapshot.originatingTab !== 'puzzles') {
      restoreExplorationReturnSnapshot(returnSnapshot);
      longDecisionReturnSnapshotRef.current = null;
      setActiveOriginatingTab(null);
      onReturnToTab?.(returnSnapshot.originatingTab);
      return;
    }
    if (hasSimilarPositionsReturnContext) {
      onReturnToTab?.('similar-positions');
      return;
    }
    if (hasLongDecisionReturnContext) {
      onReturnToTab?.('long-decisions');
      return;
    }
    if (hasGameAnalysisReturnContext) {
      onReturnToTab?.('game-analysis');
      return;
    }
    setActiveOriginatingTab(null);
    setViewMode('discovery');
  }, [
    hasGameAnalysisReturnContext,
    hasLongDecisionReturnContext,
    hasSimilarPositionsReturnContext,
    onReturnToTab,
    restoreExplorationReturnSnapshot,
  ]);

  const clearAccountUiState = React.useCallback(() => {
    longDecisionReturnSnapshotRef.current = null;
    setActiveOriginatingTab(null);
    boardHistorySnapshotRef.current = null;
    setBoardHistorySnapshot(null);
    setPuzzlesList([]);
    setCurrentPuzzleIndex(0);
    setActivePuzzle(null);
    setViewMode('discovery');
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
    evaluationRequestIdRef.current += 1;
    setIsEvaluatingMove(false);
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
    evaluationRequestIdRef.current += 1;
    setIsEvaluatingMove(false);
    lineExploration.resetExploration();
    setHistoryIndex(0);
    setCurrentEvalCp(evalHistory[0] ?? (activePuzzle?.evalCp ?? 35));
    const firstFeedback = feedbackHistory[0];
    setFeedback(firstFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
    if (activePuzzle?.source === 'long-decision' && timerMode === 'STOPWATCH') {
      if (timerRef.current instanceof StopwatchTimer) {
        timerRef.current.reset();
        timerRef.current.startNewAttempt();
        timerRef.current.start();
      }
      setTimerElapsedMs(0);
      setTimerExpired(false);
    }
  };

  const handleMoveAttempt = (
    moveSan: string,
    isCorrect: boolean,
    isHistoricalMistake: boolean,
    historicalInfo?: { timesPlayed: number; averageLoss: number },
    isInitialDecision: boolean = true
  ) => {
    if (!activePuzzle) return;
    if (isInitialDecision && weaknessBacked && Boolean(activePuzzle.targetMove)) {
      if (!puzzleContextReady) return;
      void puzzleAttempts.submit(moveSan, isCorrect);
    }
    setMoveHistory((prev) => [...prev, moveSan]);
    setHintSquare(undefined);

    const startCp = activePuzzle.evalCp ?? 35;

    if (isInitialDecision) {
      if (!activePuzzle.targetMove) {
        const preFen = activePuzzle.fen;
        const attemptElapsed = getAuthoritativeTimerElapsed(
          timerRef.current,
          timerAllowedMs,
          timerElapsedMs
        );
        setTimerElapsedMs(attemptElapsed);
        evaluationRequestIdRef.current += 1;
        const reqId = evaluationRequestIdRef.current;

        const runEvaluation = () => {
          setIsEvaluatingMove(true);
          setFeedback({
            status: 'EVALUATING',
            lastMove: moveSan,
            attemptElapsedMs: attemptElapsed,
          });

          void evaluateMove(preFen, moveSan)
            .then((evalResult) => {
              if (
                reqId !== evaluationRequestIdRef.current ||
                !activePuzzleRef.current ||
                activePuzzleRef.current.fen !== preFen
              ) {
                return;
              }
              setIsEvaluatingMove(false);
              if (!evalResult) {
                setFeedback({
                  status: 'EVALUATION_ERROR',
                  lastMove: moveSan,
                  errorMessage: 'Evaluation service returned an empty response.',
                  attemptElapsedMs: attemptElapsed,
                  onRetry: runEvaluation,
                });
                return;
              }

              const acceptable = evalResult.acceptable;
              const isBest = moveSan === evalResult.bestMove;
              const evalCp = evalResult.evalCp ?? startCp;

              if (isInitialDecision && weaknessBacked && puzzleContextReady) {
                void puzzleAttempts.submit(moveSan, acceptable);
              }

              if (acceptable) {
                setActivePuzzle((prev) => {
                  if (!prev) return null;
                  const newAcceptable = prev.acceptableMoves.filter((m) => m.move !== moveSan);
                  if (!isBest) {
                    newAcceptable.push({ move: moveSan, evalLoss: evalResult.evalLoss });
                  }
                  return {
                    ...prev,
                    targetMove: evalResult.bestMove,
                    acceptableMoves: newAcceptable,
                  };
                });
                const nextFeedback: PuzzleFeedbackState = {
                  status: 'CORRECT',
                  lastMove: moveSan,
                  evalLoss: evalResult.evalLoss,
                  attemptElapsedMs: attemptElapsed,
                };
                setFeedback(nextFeedback);
                setCurrentEvalCp(evalCp);
                setIsEvalUnknown(false);
                setEvalHistory((prev) => [...prev.slice(0, historyIndex + 1), evalCp]);
                setFeedbackHistory((prev) => [...prev.slice(0, historyIndex + 1), nextFeedback]);
                setHistoryIndex((prev) => prev + 1);
                if (isBest) {
                  soundService.playSound('completion');
                } else {
                  soundService.playSound('correct');
                }
              } else {
                // Roll back board to preMoveFen and remove move from history
                setBoardResetTrigger((prev) => prev + 1);
                setMoveHistory((prev) => prev.slice(0, -1));
                const nextFeedback: PuzzleFeedbackState = {
                  status: 'INCORRECT',
                  lastMove: moveSan,
                  evalLoss: evalResult.evalLoss,
                  historicalInfo: { timesPlayed: 1, averageLoss: evalResult.evalLoss },
                  attemptElapsedMs: attemptElapsed,
                };
                setFeedback(nextFeedback);
                soundService.playSound('incorrect');
              }
            })
            .catch((err: unknown) => {
              if (
                reqId !== evaluationRequestIdRef.current ||
                !activePuzzleRef.current ||
                activePuzzleRef.current.fen !== preFen
              ) {
                return;
              }
              setIsEvaluatingMove(false);
              const errorMsg =
                err instanceof Error ? err.message : 'Evaluation service network error';
              setFeedback({
                status: 'EVALUATION_ERROR',
                lastMove: moveSan,
                errorMessage: errorMsg,
                attemptElapsedMs: attemptElapsed,
                onRetry: runEvaluation,
              });
            });
        };

        runEvaluation();
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
        newFeedbackState = {
          status: 'CORRECT',
          lastMove: moveSan,
          attemptElapsedMs: timerElapsedMs,
        };
        if (timerMode && !timerExpired && weaknessBacked) {
          void submitTimerAttempt('SUBMITTED');
        }
      } else if (isHistoricalMistake) {
        newFeedbackState = {
          status: 'HISTORICAL_MISTAKE',
          lastMove: moveSan,
          historicalInfo,
          attemptElapsedMs: timerElapsedMs,
        };
      } else {
        newFeedbackState = {
          status: 'INCORRECT',
          lastMove: moveSan,
          attemptElapsedMs: timerElapsedMs,
        };
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
      className={`max-w-[1536px] w-full mx-auto px-4 lg:px-8 2xl:max-w-none 2xl:px-4 flex-1 flex flex-col justify-start min-h-0 ${
        hidden ? 'hidden' : ''
      }`}
    >
      <div className="flex-1 flex flex-col justify-start min-h-0">
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
        ) : !activePuzzle && puzzlesList.length === 0 ? (
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
        ) : viewMode === 'discovery' ? (
          <div className="space-y-4 max-w-[1536px] w-full mx-auto py-2 pb-8">
            {/* Discovery Header */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-outline-variant pb-4">
              <div className="space-y-1">
                <h2 className="text-xl md:text-2xl font-bold text-on-surface tracking-tight flex items-center gap-2">
                  <Swords className="w-6 h-6 text-primary" />
                  <span>Practice Puzzles</span>
                </h2>
                <p className="text-xs text-on-surface-variant max-w-2xl leading-relaxed">
                  Strengthen your play by solving recurring mistake positions detected from your imported games.
                </p>
              </div>

              {/* Actions & Loaded Counter & View Switcher */}
              <div className="flex items-center space-x-3 shrink-0 self-end sm:self-center">
                {puzzlesList.length > 0 && (
                  <button
                    type="button"
                    onClick={() => handleStartPractice()}
                    className="px-3.5 py-1.5 bg-primary-container hover:bg-primary text-on-primary-container font-bold text-xs font-mono rounded-lg transition shadow-md flex items-center gap-1.5 cursor-pointer"
                  >
                    <Swords className="w-3.5 h-3.5" />
                    <span>Start Practice</span>
                  </button>
                )}
                <div className="px-2.5 py-1 rounded bg-surface-container border border-outline-variant text-xs font-mono">
                  <span className="text-outline">Loaded: </span>
                  <strong className="text-primary">{puzzlesList.length}</strong>
                  <span className="text-outline text-[10px]"> puzzles</span>
                </div>

                <div className="flex items-center bg-surface-container-lowest p-0.5 rounded border border-outline-variant">
                  <button
                    type="button"
                    onClick={() => setCatalogViewMode('grid')}
                    className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                      catalogViewMode === 'grid'
                        ? 'bg-surface-container text-primary font-semibold'
                        : 'text-outline hover:text-on-surface'
                    }`}
                  >
                    <LayoutGrid className="w-3.5 h-3.5" />
                    <span>Grid</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setCatalogViewMode('list')}
                    className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                      catalogViewMode === 'list'
                        ? 'bg-surface-container text-primary font-semibold'
                        : 'text-outline hover:text-on-surface'
                    }`}
                  >
                    <List className="w-3.5 h-3.5" />
                    <span>List</span>
                  </button>
                </div>
              </div>
            </div>

            {/* Inline Filter Toolbar */}
            <div className="bg-surface-container-low border border-outline-variant rounded-xl p-3.5 space-y-2.5 shadow-sm">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-2.5">
                  <div className="flex items-center space-x-1.5 text-xs font-mono font-semibold text-outline mr-1">
                    <Filter className="w-3.5 h-3.5" />
                    <span>Filters:</span>
                  </div>

                  {/* Color Filter Segment */}
                  <div className="flex bg-surface-container-lowest p-0.5 rounded-lg border border-outline-variant text-xs font-mono">
                    <button
                      type="button"
                      onClick={() => handleColorFilterChange('BOTH')}
                      className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                        puzzleColorFilter === 'BOTH'
                          ? 'bg-surface-container text-primary border border-outline-variant'
                          : 'text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      Both Sides
                    </button>
                    <button
                      type="button"
                      onClick={() => handleColorFilterChange('WHITE')}
                      className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                        puzzleColorFilter === 'WHITE'
                          ? 'bg-surface-container text-primary border border-outline-variant'
                          : 'text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      White
                    </button>
                    <button
                      type="button"
                      onClick={() => handleColorFilterChange('BLACK')}
                      className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                        puzzleColorFilter === 'BLACK'
                          ? 'bg-surface-container text-primary border border-outline-variant'
                          : 'text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      Black
                    </button>
                  </div>

                  {/* Shared Mistake Threshold Filter */}
                  <div className="relative">
                    <select
                      aria-label="Filter by Threshold"
                      value={minEvalLoss}
                      onChange={(e) => handleMinEvalLossChange(Number(e.target.value))}
                      className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
                    >
                      <option value={0.3}>Threshold: 0.3 pawns (Strict)</option>
                      <option value={0.5}>Threshold: 0.5 pawns (Moderate)</option>
                      <option value={0.8}>Threshold: 0.8 pawns (Standard)</option>
                      <option value={1.2}>Threshold: 1.2 pawns (Severe)</option>
                      <option value={2.0}>Threshold: 2.0 pawns (Critical)</option>
                    </select>
                    <SlidersHorizontal className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
                  </div>

                  {/* Min Mistakes Filter */}
                  <div className="relative">
                    <select
                      aria-label="Filter by Min Mistakes"
                      value={minMistakeCount}
                      onChange={(e) => handleMinMistakeCountChange(Number(e.target.value))}
                      className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
                    >
                      <option value={1}>Min Mistakes: 1 (All)</option>
                      <option value={3}>Min Mistakes: 3 (Habits only)</option>
                      <option value={5}>Min Mistakes: 5+ (Frequent)</option>
                    </select>
                    <Repeat className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
                  </div>
                </div>
              </div>
            </div>

            {/* Puzzle Cards Grid / List */}
            <div className={catalogViewMode === 'grid' ? 'grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4 pt-2' : 'space-y-4 pt-2'}>
              {puzzlesList.map((puzzle, idx) => (
                <PositionCard
                  key={puzzle.puzzleId}
                  board={
                    <ChessBoardView
                      fen={puzzle.fen}
                      boardOrientation={puzzle.playerColor === 'WHITE' ? 'white' : 'black'}
                    />
                  }
                  title={
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-bold text-on-surface text-sm truncate">
                        {puzzle.openingTitle || `Puzzle #${idx + 1}`}
                      </span>
                      <span className={`px-2 py-0.5 rounded text-[10px] font-mono font-bold uppercase tracking-wider shrink-0 ${
                        puzzle.playerColor === 'WHITE'
                          ? 'bg-surface-container-high text-on-surface border border-outline-variant'
                          : 'bg-surface-container-highest text-primary border border-outline-variant'
                      }`}>
                        {puzzle.playerColor}
                      </span>
                    </div>
                  }
                  metadata={
                    <div className="flex flex-wrap items-center gap-2 text-xs font-mono">
                      {isWeaknessBacked(puzzle) && puzzle.mistakeCount != null && (
                        <span className="px-2 py-0.5 rounded bg-surface-container text-on-surface-variant border border-outline-variant text-[11px]">
                          {puzzle.mistakeCount} {puzzle.mistakeCount === 1 ? 'mistake' : 'mistakes'}
                        </span>
                      )}
                      {isWeaknessBacked(puzzle) && puzzle.timesReached != null && (
                        <span className="px-2 py-0.5 rounded bg-surface-container text-on-surface-variant border border-outline-variant text-[11px]">
                          {puzzle.timesReached} {puzzle.timesReached === 1 ? 'time' : 'times'} reached
                        </span>
                      )}
                    </div>
                  }
                  actions={
                    <div className="flex items-center justify-between w-full">
                      <button
                        type="button"
                        onClick={() => handleStartPractice(puzzle, idx)}
                        className="px-3 py-1.5 bg-primary-container hover:bg-primary text-on-primary-container font-bold text-xs font-mono rounded-lg transition shadow-md flex items-center gap-1.5 cursor-pointer"
                      >
                        <Swords className="w-3.5 h-3.5" />
                        <span>Practice Puzzle</span>
                      </button>
                      {puzzle.gameUrls && puzzle.gameUrls.length > 0 && (
                        <span className="text-[11px] font-mono text-outline">
                          {puzzle.gameUrls.length} {puzzle.gameUrls.length === 1 ? 'game' : 'games'}
                        </span>
                      )}
                    </div>
                  }
                />
              ))}
            </div>
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
          <div className="flex flex-col w-full">
            <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-outline-variant w-full mb-4">
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={handleExitPractice}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-outline-variant bg-surface-container hover:bg-surface-container-high text-xs font-mono text-on-surface transition cursor-pointer"
                >
                  <ArrowLeft className="w-3.5 h-3.5 text-primary" />
                  <span>
                    {hasLongDecisionReturnContext ||
                    (activeOriginatingTab && activeOriginatingTab !== 'puzzles')
                      ? `Back to ${
                          activeOriginatingTab === 'weaknesses'
                            ? 'Weaknesses'
                            : activeOriginatingTab === 'similar-positions'
                            ? 'Similar Positions'
                            : activeOriginatingTab === 'game-analysis'
                            ? 'Game Analysis'
                            : 'Decision Timing'
                        }`
                      : hasSimilarPositionsReturnContext
                      ? 'Back to Similar Positions'
                      : hasGameAnalysisReturnContext
                      ? 'Back to Game Analysis'
                      : 'Back to Puzzles'}
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => setViewMode('discovery')}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-outline-variant bg-surface-container-low hover:bg-surface-container text-xs font-mono text-outline hover:text-on-surface transition cursor-pointer"
                >
                  <List className="w-3.5 h-3.5 text-primary" />
                  <span>Browse Puzzles</span>
                </button>
                <span className="hidden sm:inline text-outline-variant">|</span>
                <div className="text-xs font-mono text-on-surface-variant flex items-center gap-1.5">
                  <span>Training Workspace</span>
                  <span>/</span>
                  <span className="text-primary font-semibold">Weakness Drill</span>
                </div>
              </div>
              <div className="flex items-center gap-3 text-xs font-mono text-outline">
                <span className="hidden md:inline text-on-surface-variant/70">
                  Live Archive &bull; Stockfish 16.1 depth 22
                </span>
                <span className="px-2.5 py-0.5 rounded-full bg-surface-container border border-outline-variant text-primary font-bold">
                  {`Puzzle ${currentPuzzleIndex + 1} of ${puzzlesList.length}`}
                </span>
              </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 w-full max-w-[1720px] mx-auto items-start">
              {/* Left Stockfish Eval Bar & Center Interactive Chessboard */}
              <div className="lg:col-span-8 2xl:col-span-9 flex flex-col items-center">
                <div className="flex items-start justify-center gap-3 w-full">
                  <div className="hidden sm:flex flex-col items-center pt-1 shrink-0">
                    <span className="text-[10px] font-bold text-on-surface-variant mb-1.5 uppercase tracking-wider font-mono">
                      Eval
                    </span>
                    <EvalBar
                      evalCp={currentEvalCp}
                      isExploring={!lineExploration.isExplorationActive && historyIndex > 1}
                      isUnknown={isEvalUnknown}
                    />
                  </div>

                  <div data-testid="practice-board-area" className="w-full max-w-[680px] shrink-0 flex flex-col space-y-2.5">
                    <ChessBoardArea
                      key={!isWeaknessPlaylist ? activePuzzle.puzzleId : 'puzzle-board'}
                      initialFen={activePuzzle.fen}
                      initialBoardHistory={
                        isWeaknessPlaylist ? boardHistorySnapshot : null
                      }
                      onBoardHistoryChange={handleBoardHistoryChange}
                      initialMoveHistorySan={
                        !isWeaknessPlaylist ? initialMoveHistorySan : undefined
                      }
                      initialMoveHistoryStartFen={
                        !isWeaknessPlaylist ? initialMoveHistoryStartFen : undefined
                      }
                      showPuzzleControls={isWeaknessPlaylist}
                      playerColor={activePuzzle.playerColor}
                      blindfoldMode={isBlindfoldActive}
                      boardOrientation={puzzleBoardOrientation}
                      targetMove={activePuzzle.targetMove}
                      acceptableMoves={activePuzzle.acceptableMoves}
                      movesPlayed={activePuzzle.movesPlayed}
                      onMoveAttempt={handleMoveAttempt}
                      initialDecisionEnabled={
                        !isEvaluatingMove && (!isWeaknessPlaylist || puzzleContextReady)
                      }
                      isMoveEvaluationPending={isEvaluatingMove || feedback.status === 'EVALUATION_ERROR'}
                      onPreviousPuzzle={handlePreviousPuzzle}
                      onNextPuzzle={handleNextPuzzle}
                      onUndo={handleBoardUndo}
                      onRedo={handleBoardRedo}
                      onReset={handleBoardReset}
                      resetTrigger={boardResetTrigger}
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
                      onChessProbeExplorationMove={lineExploration.handleChessEchoExplorationMove}
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

                    {/* Keyboard Shortcuts Micro-Bar */}
                    <div className="flex flex-wrap items-center justify-between text-[11px] font-mono text-on-surface-variant/70 border-t border-outline-variant/60 pt-2 px-1">
                      <span>&larr; Prev Move &bull; &rarr; Next Move &bull; X Flip &bull; &crarr; Next Puzzle</span>
                      <span className="hidden sm:inline text-primary/80">&bull; Interactive analysis mode active</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Right Feedback & Settings Panel */}
              <div className="lg:col-span-4 2xl:col-span-3 w-full space-y-4">
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
                    isWeaknessPlaylist ? handleEnterBlindfold : undefined
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
        </div>
        )}
      </div>
    </div>
  );
}
