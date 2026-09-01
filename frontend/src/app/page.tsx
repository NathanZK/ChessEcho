'use client';

import React, { useState, useSyncExternalStore } from 'react';
import { Header, TabType } from '@/components/Header';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea } from '@/components/ChessBoardArea';
import { PuzzleFeedbackPanel, type ChallengeSubmissionResult } from '@/components/PuzzleFeedbackPanel';
import { WeaknessesList } from '@/components/WeaknessesList';
import { ImportGamesView } from '@/components/ImportGamesView';
import { Puzzle } from '@/mock/mockData';
import { fetchPuzzles, JobStatusResponse, ContinuationMode, ContinuationCandidate, ExplorationPlayMode, toWhitePerspective, fetchPuzzleContinuation } from '@/services/api';
import { soundService } from '@/services/soundService';
import { usePuzzleContinuation } from '@/utils/usePuzzleContinuation';
import { activeTabStore, activeUsernameStore, puzzleSettingsStore } from '@/utils/browserStores';

export const EXPLORATION_STEP_DELAY_MS = 800;

type PuzzleFeedbackState = {
  status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
  lastMove?: string;
  historicalInfo?: { timesPlayed: number; averageLoss: number };
};

function errorMessageOf(e: unknown): string | undefined {
  if (e instanceof Error) return e.message;
  if (typeof e === 'object' && e !== null && 'message' in e) {
    const message = (e as { message?: unknown }).message;
    return typeof message === 'string' ? message : undefined;
  }
  return undefined;
}

export default function Home() {
  const activeTab = useSyncExternalStore(
    activeTabStore.subscribe,
    activeTabStore.getSnapshot,
    activeTabStore.getServerSnapshot
  );
  const activeUsername = useSyncExternalStore(
    activeUsernameStore.subscribe,
    activeUsernameStore.getSnapshot,
    activeUsernameStore.getServerSnapshot
  );
  const [activeJobStatus, setActiveJobStatus] = useState<JobStatusResponse | null>(null);
  const [weaknessRefreshKey, setWeaknessRefreshKey] = useState<number>(0);

  const handleJobStatusUpdate = (job: JobStatusResponse | null) => {
    setActiveJobStatus(job);
    if (job?.status === 'COMPLETED') {
      setWeaknessRefreshKey((k) => k + 1);
    }
  };

  const changeTab = (tab: TabType) => {
    activeTabStore.set(tab);
    if (typeof window !== 'undefined') {
      window.history.pushState(null, '', `#${tab}`);
    }
  };

  const [weaknessCount, setWeaknessCount] = useState<number>(0);

  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>([]);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle | null>(null);
  const [isLoadingPuzzles, setIsLoadingPuzzles] = useState<boolean>(true);
  const [puzzleLoadError, setPuzzleLoadError] = useState<boolean>(false);
  const [puzzleReloadToken, setPuzzleReloadToken] = useState<number>(0);
  // Monotonic puzzle-load generation. A completion applies its effects (data/error
  // setters, the loading flag, and the prefetch lock) only while it still owns the
  // current generation, so a stale/superseded load can never mutate the live UI.
  const puzzleLoadSeqRef = React.useRef<number>(0);

  const invalidatePuzzleRequests = () => {
    puzzleLoadSeqRef.current++;
    setIsFetchingMorePuzzles(false);
  };

  const handleSetUsername = (user: string | undefined) => {
    if (user !== activeUsername) {
      invalidatePuzzleRequests();
    }
    activeUsernameStore.set(user);
  };

  const handleDisconnect = () => {
    handleSetUsername(undefined);
    setPuzzlesList([]);
    setCurrentPuzzleIndex(0);
    setActivePuzzle(null);
    setPuzzleLoadError(false);
    setIsLoadingPuzzles(false);
    setPuzzlePage(0);
    setHasMorePuzzles(false);
    setIsFetchingMorePuzzles(false);
    setWeaknessCount(0);
  };

  // Explicit client initialization gate to prevent hydration mismatch and double-fetch
  const [isSettingsInitialized, setIsSettingsInitialized] = useState<boolean>(false);
  const [minEvalLoss, setMinEvalLoss] = useState<number>(0.8);
  const [minMistakeCount, setMinMistakeCount] = useState<number>(3);
  const [puzzleColorFilter, setPuzzleColorFilter] = useState<'BOTH' | 'WHITE' | 'BLACK'>('BOTH');
  const [showPuzzleSettings, setShowPuzzleSettings] = useState<boolean>(false);
  const [isBoardFlipped, setIsBoardFlipped] = useState<boolean>(false);
  const [soundEnabled, setSoundEnabled] = useState<boolean>(true);

  // Flip board keyboard shortcut (x / X)
  React.useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (e.target as HTMLElement)?.isContentEditable) return;
      if (e.key === 'x' || e.key === 'X') {
        setIsBoardFlipped((prev) => !prev);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // Restore puzzle filter settings from the browser store during render (no mount effect)
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

  const handleMinEvalLossChange = (val: number) => {
    invalidatePuzzleRequests();
    setMinEvalLoss(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_eval_loss', String(val));
    }
  };

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

  // History stacks for EvalBar and feedback state matching board undo/redo index
  const [evalHistory, setEvalHistory] = useState<number[]>([35]);
  const [feedbackHistory, setFeedbackHistory] = useState<PuzzleFeedbackState[]>([{ status: 'IDLE' }]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);

  const [currentEvalCp, setCurrentEvalCp] = useState<number>(35);
  const [isEvalUnknown, setIsEvalUnknown] = useState<boolean>(false);
  const [, setMoveHistory] = useState<string[]>([]);
  const [hintSquare, setHintSquare] = useState<string | undefined>(undefined);

  const [feedback, setFeedback] = useState<{
    status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
    lastMove?: string;
    historicalInfo?: { timesPlayed: number; averageLoss: number };
  }>({ status: 'IDLE' });

  // Continuation & Line Exploration turn-based state machine
  const [isExplorationActive, setIsExplorationActive] = useState<boolean>(false);
  const [explorationPlayMode, setExplorationPlayMode] = useState<ExplorationPlayMode | undefined>(undefined);
  const [explorationDecisionMove, setExplorationDecisionMove] = useState<string | null>(null);
  const [unacceptableMoveMessage, setUnacceptableMoveMessage] = useState<string | null>(null);
  const [currentBoardFen, setCurrentBoardFen] = useState<string>('');

  // Challenge Mode State
  const [challengeCandidatesByFen, setChallengeCandidatesByFen] = useState<Record<string, ContinuationCandidate[]>>({});
  const [challengeSubmissionByFen, setChallengeSubmissionByFen] = useState<Record<string, ChallengeSubmissionResult>>({});
  const [challengeInputByFen, setChallengeInputByFen] = useState<Record<string, string>>({});
  const [challengeFeedbackByFen, setChallengeFeedbackByFen] = useState<Record<string, { message: string, type: 'success' | 'error' | 'info' } | null>>({});
  const [isChallengeLoading, setIsChallengeLoading] = useState<boolean>(false);
  const [continuationMode, setContinuationMode] = useState<ContinuationMode>('ENGINE');
  const [opponentRatingBand, setOpponentRatingBand] = useState<string>('1200-1400');
  const [pendingContinuationCandidate, setPendingContinuationCandidate] = useState<ContinuationCandidate | null>(null);
  const [requestedContinuationFen, setRequestedContinuationFen] = useState<string | undefined>(undefined);

  const [explorationFeedbackByFen, setExplorationFeedbackByFen] = useState<Record<string, { message: string, type: 'best' | 'good' } | null>>({});
  const [lastContinuationCandidates, setLastContinuationCandidates] = useState<{
    parentFen: string;
    candidates: ContinuationCandidate[];
    selected: ContinuationCandidate;
  } | null>(null);
  const [alternativeContinuationToApply, setAlternativeContinuationToApply] = useState<{ parentFen: string, candidate: ContinuationCandidate } | null>(null);
  const [explorationEvalMap, setExplorationEvalMap] = useState<Record<string, { evalCp: number; isUnknown: boolean }>>({});

  // Challenge Calculation State
  const [challengeBranchesByFen, setChallengeBranchesByFen] = useState<Record<string, Record<string, { san: string, fenAfter: string, isWhite: boolean }[]>>>({});
  const [challengeActiveCandidateByFen, setChallengeActiveCandidateByFen] = useState<Record<string, string | null>>({});
  const [calculationInput, setCalculationInput] = useState<string>('');
  const [calculationFeedback, setCalculationFeedback] = useState<{ type: 'success' | 'error', message: string } | null>(null);
  const [isCalculationLoading, setIsCalculationLoading] = useState<boolean>(false);

  const challengeActiveFen = React.useMemo(() => {
    if (explorationPlayMode !== 'CHALLENGE') return currentBoardFen;
    const activeCandidate = challengeActiveCandidateByFen[currentBoardFen];
    if (activeCandidate) {
      const branchLine = challengeBranchesByFen[currentBoardFen]?.[activeCandidate];
      if (branchLine && branchLine.length > 0) {
        return branchLine[branchLine.length - 1].fenAfter;
      }
    }
    return currentBoardFen;
  }, [explorationPlayMode, challengeBranchesByFen, challengeActiveCandidateByFen, currentBoardFen]);

  const sideToMove: 'White' | 'Black' = React.useMemo(() => {
    if (!currentBoardFen) {
      return activePuzzle?.playerColor === 'BLACK' ? 'Black' : 'White';
    }
    return currentBoardFen.split(' ')[1] === 'w' ? 'White' : 'Black';
  }, [currentBoardFen, activePuzzle]);

  const continuation = usePuzzleContinuation(requestedContinuationFen, continuationMode, undefined, opponentRatingBand);

  // When ChessEcho turn is active and a candidate is selected, stage it for the board (only in CHESSECHO mode)
  const [trackedStaging, setTrackedStaging] = useState({
    isExplorationActive,
    explorationPlayMode,
    selectedCandidate: continuation.selectedCandidate,
    loading: continuation.loading,
    response: continuation.response,
    requestedContinuationFen,
    currentBoardFen,
    currentEvalCp,
  });

  if (
    trackedStaging.isExplorationActive !== isExplorationActive ||
    trackedStaging.explorationPlayMode !== explorationPlayMode ||
    trackedStaging.selectedCandidate !== continuation.selectedCandidate ||
    trackedStaging.loading !== continuation.loading ||
    trackedStaging.response !== continuation.response ||
    trackedStaging.requestedContinuationFen !== requestedContinuationFen ||
    trackedStaging.currentBoardFen !== currentBoardFen ||
    trackedStaging.currentEvalCp !== currentEvalCp
  ) {
    setTrackedStaging({
      isExplorationActive,
      explorationPlayMode,
      selectedCandidate: continuation.selectedCandidate,
      loading: continuation.loading,
      response: continuation.response,
      requestedContinuationFen,
      currentBoardFen,
      currentEvalCp,
    });

    if (isExplorationActive && explorationPlayMode === 'CHESSECHO' && !continuation.loading) {
      if (
        continuation.response?.fen === requestedContinuationFen &&
        currentBoardFen === requestedContinuationFen &&
        continuation.selectedCandidate
      ) {
        const candidate = continuation.selectedCandidate;
        setPendingContinuationCandidate(candidate);
        setLastContinuationCandidates({
          parentFen: requestedContinuationFen,
          candidates: continuation.candidates,
          selected: candidate
        });

        if (candidate.evalCp != null) {
          const whiteEval = toWhitePerspective(candidate.evalCp, requestedContinuationFen);
          setCurrentEvalCp(whiteEval);
          setIsEvalUnknown(false);
          setExplorationEvalMap((prev) => ({
            ...prev,
            [candidate.resultingFen]: { evalCp: whiteEval, isUnknown: false },
          }));
        } else {
          setIsEvalUnknown(true);
          setExplorationEvalMap((prev) => ({
            ...prev,
            [candidate.resultingFen]: { evalCp: currentEvalCp, isUnknown: true },
          }));
        }
      }
    }
  }

  // Keep lastContinuationCandidates synchronized with board history
  const [trackedHistorySync, setTrackedHistorySync] = useState({
    currentBoardFen,
    lastContinuationCandidates,
  });

  if (
    trackedHistorySync.currentBoardFen !== currentBoardFen ||
    trackedHistorySync.lastContinuationCandidates !== lastContinuationCandidates
  ) {
    setTrackedHistorySync({ currentBoardFen, lastContinuationCandidates });
    if (lastContinuationCandidates) {
      // If we are still at parentFen, it means the move is pending application by ChessBoardArea
      if (currentBoardFen !== lastContinuationCandidates.parentFen) {
        const actualSelected = lastContinuationCandidates.candidates.find(c => c.resultingFen === currentBoardFen);
        if (!actualSelected) {
          setLastContinuationCandidates(null);
        } else if (actualSelected.move !== lastContinuationCandidates.selected.move) {
          setLastContinuationCandidates(prev => prev ? { ...prev, selected: actualSelected } : null);
        }
      }
    }
  }

  // Synchronize EvalBar with the board's active position during exploration
  const evalEntry = isExplorationActive && currentBoardFen
    ? explorationEvalMap[currentBoardFen]
    : undefined;
  const [trackedEvalSync, setTrackedEvalSync] = useState({
    currentBoardFen,
    isExplorationActive,
    evalCp: evalEntry?.evalCp,
    isUnknown: evalEntry?.isUnknown,
  });

  if (
    trackedEvalSync.currentBoardFen !== currentBoardFen ||
    trackedEvalSync.isExplorationActive !== isExplorationActive ||
    trackedEvalSync.evalCp !== evalEntry?.evalCp ||
    trackedEvalSync.isUnknown !== evalEntry?.isUnknown
  ) {
    setTrackedEvalSync({
      currentBoardFen,
      isExplorationActive,
      evalCp: evalEntry?.evalCp,
      isUnknown: evalEntry?.isUnknown,
    });
    if (evalEntry) {
      setCurrentEvalCp(evalEntry.evalCp);
      setIsEvalUnknown(evalEntry.isUnknown);
    }
  }

  const handleExplorationPlayModeChange = (mode: ExplorationPlayMode) => {
    setExplorationPlayMode(mode);
    setPendingContinuationCandidate(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});

    if (mode === 'BOTH_SIDES') {
      setRequestedContinuationFen(undefined);
    } else {
      if (activePuzzle && currentBoardFen) {
        const fenColor = currentBoardFen.split(' ')[1];
        const isWhiteTurn = fenColor === 'w';
        const isUserWhite = activePuzzle.playerColor === 'WHITE';
        const isOpponentTurn = isWhiteTurn !== isUserWhite;
        if (isOpponentTurn) {
          setRequestedContinuationFen(currentBoardFen);
        } else {
          setRequestedContinuationFen(undefined);
        }
      }
    }
  };

  const handleAlternativeSelected = (candidate: ContinuationCandidate) => {
    if (!lastContinuationCandidates) return;
    const parentFen = lastContinuationCandidates.parentFen;

    if (candidate.evalCp != null) {
      const whiteEval = toWhitePerspective(candidate.evalCp, parentFen);
      setCurrentEvalCp(whiteEval);
      setIsEvalUnknown(false);
      setExplorationEvalMap((prev) => ({
        ...prev,
        [candidate.resultingFen]: { evalCp: whiteEval, isUnknown: false },
      }));
    } else {
      setIsEvalUnknown(true);
      setExplorationEvalMap((prev) => ({
        ...prev,
        [candidate.resultingFen]: { evalCp: currentEvalCp, isUnknown: true },
      }));
    }

    setAlternativeContinuationToApply({
      parentFen,
      candidate
    });
  };

  const handleContinuationApplied = () => {
    setPendingContinuationCandidate(null);
  };

  const handleUserExplorationMove = (
    moveSan: string,
    nextFen: string,
    feedback?: { isBest: boolean; loss: number; evalCp?: number | null; fromFen?: string }
  ) => {
    // User made a successful, acceptable move.
    if (explorationPlayMode === 'CHESSECHO') {
      setRequestedContinuationFen(nextFen);
      setLastContinuationCandidates(null);
    } else {
      setRequestedContinuationFen(undefined);
      setPendingContinuationCandidate(null);
      setLastContinuationCandidates(null);
    }

    if (feedback) {
      if (explorationPlayMode !== 'CHALLENGE') {
        if (feedback.isBest) {
          setExplorationFeedbackByFen(prev => ({ ...prev, [nextFen]: { message: 'Best move. No evaluation loss.', type: 'best' } }));
        } else {
          setExplorationFeedbackByFen(prev => ({ ...prev, [nextFen]: { message: `Good move. It loses ${feedback.loss.toFixed(2)} pawns compared with the best move.`, type: 'good' } }));
        }
      }

      if (feedback.evalCp != null && feedback.fromFen) {
        const whiteEval = toWhitePerspective(feedback.evalCp, feedback.fromFen);
        setCurrentEvalCp(whiteEval);
        setIsEvalUnknown(false);
        setExplorationEvalMap((prev) => ({
          ...prev,
          [nextFen]: { evalCp: whiteEval, isUnknown: false },
        }));
      } else if (feedback.evalCp === null) {
        setIsEvalUnknown(true);
        setExplorationEvalMap((prev) => ({
          ...prev,
          [nextFen]: { evalCp: currentEvalCp, isUnknown: true },
        }));
      }
    } else {
      setExplorationFeedbackByFen(prev => ({ ...prev, [nextFen]: null }));
    }
  };

  const handleChessEchoExplorationMove = () => {
    // ChessEcho made its move. Clear request.
    setRequestedContinuationFen(undefined);
  };

  const handleEnterExploration = (
    initialMode?: ExplorationPlayMode,
    decisionMove?: string
  ) => {
    setExplorationDecisionMove(decisionMove ?? null);
    setIsExplorationActive(true);
    setExplorationPlayMode(initialMode);
    const baselineFen = currentBoardFen || activePuzzle?.fen || '';
    const baselineEval = currentEvalCp;
    const baselineUnknown = isEvalUnknown;

    const initialMap: Record<string, { evalCp: number; isUnknown: boolean }> = {};
    if (activePuzzle?.fen) {
      initialMap[activePuzzle.fen] = { evalCp: activePuzzle.evalCp ?? 35, isUnknown: false };
    }
    if (baselineFen) {
      initialMap[baselineFen] = { evalCp: baselineEval, isUnknown: baselineUnknown };
    }
    setExplorationEvalMap(initialMap);

    if (initialMode === 'CHESSECHO') {
      setRequestedContinuationFen(baselineFen);
    } else {
      setRequestedContinuationFen(undefined);
      setPendingContinuationCandidate(null);
    }

    setFeedback({ status: 'EXPLORING' });
    setUnacceptableMoveMessage(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});
  };

  const handleExitExploration = () => {
    setIsExplorationActive(false);
    setExplorationPlayMode(undefined);
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    setUnacceptableMoveMessage(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});
    setExplorationDecisionMove(null);
    if (explorationDecisionMove) {
      setFeedback({ status: 'IDLE' });
    } else {
      setFeedback({ status: 'CORRECT', lastMove: activePuzzle?.targetMove });
    }
  };

  const handleUnacceptableMove = (message?: string | null) => {
    if (!message) {
      setUnacceptableMoveMessage(null);
      return;
    }
    setUnacceptableMoveMessage(message);
    setTimeout(() => {
      setUnacceptableMoveMessage(null);
    }, 3500);
  };

  const resetPuzzleInteractionState = (puzzle: Puzzle) => {
    const initialEval = puzzle.evalCp ?? 35;
    setCurrentEvalCp(initialEval);
    setEvalHistory([initialEval]);
    setFeedbackHistory([{ status: 'IDLE' }]);
    setFeedback({ status: 'IDLE' });
    setHistoryIndex(0);
    setMoveHistory([]);
    setHintSquare(undefined);
    setIsEvalUnknown(false);
    setCurrentBoardFen(puzzle.fen);
    setPendingContinuationCandidate(null);
    setRequestedContinuationFen(undefined);
    setIsExplorationActive(false);
    setExplorationDecisionMove(null);
    setUnacceptableMoveMessage(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});
  };

  const [puzzlePage, setPuzzlePage] = useState<number>(0);
  const [hasMorePuzzles, setHasMorePuzzles] = useState<boolean>(true);
  const [isFetchingMorePuzzles, setIsFetchingMorePuzzles] = useState<boolean>(false);

  // Fetch live puzzles whenever activeUsername, puzzleColorFilter, minEvalLoss, or minMistakeCount changes, guarded by isSettingsInitialized gate
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
    setTrackedPuzzleQuery({ isSettingsInitialized, activeUsername, puzzleColorFilter, minEvalLoss, minMistakeCount });
    if (!isSettingsInitialized || !activeUsername) {
      if (!activeUsername) {
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
      setIsLoadingPuzzles(true);
      setPuzzleLoadError(false);
      setPuzzlePage(0);
      setHasMorePuzzles(true);
      setIsFetchingMorePuzzles(false);
      try {
        const data = await fetchPuzzles(
          activeUsername!,
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
          const savedId = typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null;
          const foundIdx = savedId ? data.findIndex((p) => p.puzzleId === savedId) : -1;
          const targetIdx = foundIdx !== -1 ? foundIdx : 0;
          const targetPuzzle = data[targetIdx];

          setCurrentPuzzleIndex(targetIdx);
          setActivePuzzle(targetPuzzle);
          resetPuzzleInteractionState(targetPuzzle);
          setWeaknessCount(data.length);
        } else {
          setPuzzlesList([]);
          setActivePuzzle(null);
          setCurrentPuzzleIndex(0);
          setWeaknessCount(0);
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
  }, [isSettingsInitialized, activeUsername, puzzleColorFilter, minEvalLoss, minMistakeCount, puzzleReloadToken]);

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
        activeUsername,
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
        const currentId = activePuzzle?.puzzleId || (typeof window !== 'undefined' ? localStorage.getItem('chessecho_puzzle_id') : null);
        const foundIdx = currentId ? data.findIndex((p) => p.puzzleId === currentId) : -1;
        const targetIdx = foundIdx !== -1 ? foundIdx : 0;
        const targetPuzzle = data[targetIdx];

        setCurrentPuzzleIndex(targetIdx);
        setActivePuzzle(targetPuzzle);
        resetPuzzleInteractionState(targetPuzzle);
        setWeaknessCount(data.length);
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
    if (typeof window !== 'undefined' && nextPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', nextPuzzle.puzzleId);
    }

    // Prefetch next page if approaching the end of current puzzle list
    if (
      activeUsername &&
      hasMorePuzzles &&
      !isFetchingMorePuzzles &&
      nextIndex >= puzzlesList.length - 2
    ) {
      // Capture (do not bump) the current generation: a prefetch belongs to the
      // active load, so a superseding load (which bumps the generation) makes this
      // prefetch stale and a total no-op on completion, including the lock release.
      const seq = puzzleLoadSeqRef.current;
      setIsFetchingMorePuzzles(true);
      const nextPage = puzzlePage + 1;
      try {
        const data = await fetchPuzzles(
          activeUsername,
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
        // Background prefetch failure must stay retryable: do NOT mark the list as
        // terminally exhausted and do not discard already-rendered puzzles, so the
        // next navigation can re-attempt the same page.
        console.error('Failed to prefetch next puzzle page:', err);
      } finally {
        if (seq === puzzleLoadSeqRef.current) {
          setIsFetchingMorePuzzles(false);
        }
      }
    }
  };

  const handleSelectPracticeFromLibrary = (puzzle: Puzzle, fullList?: Puzzle[]) => {
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
    resetPuzzleInteractionState(selectedPuzzle);

    if (typeof window !== 'undefined' && selectedPuzzle) {
      localStorage.setItem('chessecho_puzzle_id', selectedPuzzle.puzzleId);
    }
    changeTab('puzzles');
  };

  const handleBoardUndo = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);

    if (isExplorationActive) {
      setLastContinuationCandidates(null);
      return;
    }

    const prevIndex = Math.max(0, historyIndex - 1);
    setHistoryIndex(prevIndex);
    setCurrentEvalCp(evalHistory[prevIndex] ?? (activePuzzle?.evalCp ?? 35));
    const prevFeedback = feedbackHistory[prevIndex];
    setFeedback(prevFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleBoardRedo = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);

    if (isExplorationActive) {
      setLastContinuationCandidates(null);
      return;
    }

    if (historyIndex < evalHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      setHistoryIndex(nextIndex);
      setCurrentEvalCp(evalHistory[nextIndex]);
      const nextFeedback = feedbackHistory[nextIndex];
      setFeedback(nextFeedback ?? { status: 'IDLE' });
    }
  };

  const handleBoardReset = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    setIsExplorationActive(false);
    setExplorationDecisionMove(null);
    setUnacceptableMoveMessage(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});

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
    setMoveHistory((prev) => [...prev, moveSan]);
    setHintSquare(undefined);

    const startCp = activePuzzle.evalCp ?? 35;

    if (isInitialDecision) {
      // Calculate evaluation update based on move played for initial decision
      let calculatedEval = startCp;
      let moveHasEngineData = true;
      const acceptableMatch = activePuzzle.acceptableMoves.find((m) => m.move === moveSan);

      if (moveSan === activePuzzle.targetMove) {
        // Best move: no loss — eval stays at starting position
        calculatedEval = startCp;
      } else if (acceptableMatch) {
        // Acceptable alternative: apply its specific eval loss from engine data
        const lossCp = Math.round(acceptableMatch.evalLoss * 100);
        calculatedEval = activePuzzle.playerColor === 'BLACK'
          ? startCp + lossCp
          : startCp - lossCp;
      } else if (isHistoricalMistake && historicalInfo) {
        // Historical mistake: use average loss from actual game data
        const lossCp = Math.round(historicalInfo.averageLoss * 100);
        calculatedEval = activePuzzle.playerColor === 'BLACK'
          ? startCp + lossCp
          : startCp - lossCp;
      } else {
        // No engine data for this move — freeze eval and show ? badge
        moveHasEngineData = false;
        calculatedEval = currentEvalCp; // keep current value unchanged
      }

      let newFeedbackState: {
        status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
        lastMove?: string;
        historicalInfo?: { timesPlayed: number; averageLoss: number };
      } = { status: 'IDLE' };

      if (isCorrect) {
        newFeedbackState = { status: 'CORRECT', lastMove: moveSan };
      } else if (isHistoricalMistake) {
        newFeedbackState = { status: 'HISTORICAL_MISTAKE', lastMove: moveSan, historicalInfo };
      } else {
        newFeedbackState = { status: 'INCORRECT', lastMove: moveSan };
      }

      // Truncate future stacks if making a move after undoing
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
      // Continuation / Opponent move
      const trimmedEvalHist = evalHistory.slice(0, historyIndex + 1);
      const trimmedFeedHist = feedbackHistory.slice(0, historyIndex + 1);

      trimmedEvalHist.push(currentEvalCp);
      trimmedFeedHist.push(feedback);

      setEvalHistory(trimmedEvalHist);
      setFeedbackHistory(trimmedFeedHist);
      setHistoryIndex(trimmedEvalHist.length - 1);
    }
  };

  const handleChallengeCandidateSelect = async (san: string) => {
    const chessjs = await import('chess.js');
    try {
      setChallengeActiveCandidateByFen(prev => ({ ...prev, [currentBoardFen]: san }));

      setChallengeBranchesByFen(prev => {
        const fenBranches = prev[currentBoardFen] || {};
        if (!fenBranches[san]) {
          const tempGame = new chessjs.Chess(currentBoardFen);
          const moveObj = tempGame.move(san);
          if (moveObj) {
            return {
              ...prev,
              [currentBoardFen]: {
                ...fenBranches,
                [san]: [{ san, fenAfter: tempGame.fen(), isWhite: moveObj.color === 'w' }]
              }
            };
          }
        }
        return prev;
      });
      setCalculationFeedback(null);
      setCalculationInput('');
    } catch {
      // Ignore invalid candidates
    }
  };

  const handleBackToCandidates = () => {
    setChallengeActiveCandidateByFen(prev => ({ ...prev, [currentBoardFen]: null }));
    setCalculationFeedback(null);
    setCalculationInput('');
  };

  const challengeSubmission = challengeSubmissionByFen[currentBoardFen];
  const [trackedChallengeQuery, setTrackedChallengeQuery] = useState<{
    currentBoardFen: string;
    explorationPlayMode: ExplorationPlayMode | undefined;
    submission: ChallengeSubmissionResult | undefined;
  } | null>(null);

  if (
    !trackedChallengeQuery ||
    trackedChallengeQuery.currentBoardFen !== currentBoardFen ||
    trackedChallengeQuery.explorationPlayMode !== explorationPlayMode ||
    trackedChallengeQuery.submission !== challengeSubmission
  ) {
    setTrackedChallengeQuery({ currentBoardFen, explorationPlayMode, submission: challengeSubmission });
    if (explorationPlayMode === 'CHALLENGE' && currentBoardFen && !challengeSubmission) {
      setChallengeFeedbackByFen(prev => ({ ...prev, [currentBoardFen]: null }));
      setChallengeInputByFen(prev => ({ ...prev, [currentBoardFen]: '' }));
    }
  }

  React.useEffect(() => {
    if (explorationPlayMode !== 'CHALLENGE' || !currentBoardFen) return;
    if (challengeSubmissionByFen[currentBoardFen]) return;

    let isMounted = true;

    const fetchCandidates = async () => {
      setIsChallengeLoading(true);
      try {
        const result = await fetchPuzzleContinuation(currentBoardFen, 'ENGINE');
        if (isMounted && result && result.fen === currentBoardFen) {
          const strongCandidates = result.candidates.filter(c => (c.evalLoss ?? 0) <= 0.20);
          setChallengeCandidatesByFen(prev => ({ ...prev, [currentBoardFen]: strongCandidates }));
        }
      } catch {
        if (isMounted) setChallengeFeedbackByFen(prev => ({ ...prev, [currentBoardFen]: { type: 'error', message: 'Failed to load candidates.' } }));
      } finally {
        if (isMounted) setIsChallengeLoading(false);
      }
    };

    fetchCandidates();
    return () => { isMounted = false; };
  }, [currentBoardFen, explorationPlayMode, challengeSubmissionByFen]);

  const handleChallengeSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!currentBoardFen) return;

    const currentChallengeInput = challengeInputByFen[currentBoardFen] || '';
    if (!currentChallengeInput.trim() || isChallengeLoading) return;

    const tokens = currentChallengeInput.split(/[\s,]+/).filter(t => t.trim().length > 0);
    if (tokens.length === 0) return;

    setIsChallengeLoading(true);
    setChallengeFeedbackByFen(prev => ({ ...prev, [currentBoardFen]: null }));

    const currentCandidates = challengeCandidatesByFen[currentBoardFen] || [];

    const chessjs = await import('chess.js');
    const uniqueMoves = new Map<string, string>(); // canonical -> raw
    const invalidTokens: string[] = [];

    for (const token of tokens) {
      try {
        const tempGame = new chessjs.Chess(challengeActiveFen);
        const moveObj = tempGame.move(token);
        if (moveObj) {
          if (!uniqueMoves.has(moveObj.san)) {
            uniqueMoves.set(moveObj.san, token);
          }
        } else {
          invalidTokens.push(token);
        }
      } catch {
        invalidTokens.push(token);
      }
    }

    if (invalidTokens.length > 0) {
      setChallengeFeedbackByFen(prev => ({ ...prev, [currentBoardFen]: { type: 'error', message: `"${invalidTokens[0]}" isn't valid SAN. Check the move and try again.` } }));
      setIsChallengeLoading(false);
      return;
    }

    const api = await import('@/services/api');
    const moveResults: ChallengeSubmissionResult['moves'] = [];
    let strongCount = 0;

    for (const canonicalSan of uniqueMoves.keys()) {
      try {
        const res = await api.evaluateMove(currentBoardFen, canonicalSan);
        if (res && res.evalLoss !== undefined && res.evalLoss !== null) {
          if (res.evalLoss <= 0.20) {
            strongCount++;
            moveResults.push({ san: canonicalSan, status: 'strong' });
          } else {
            moveResults.push({ san: canonicalSan, status: 'weak' });
          }
        } else {
          moveResults.push({ san: canonicalSan, status: 'weak', errorMsg: 'Failed to evaluate.' });
        }
      } catch (e: unknown) {
         moveResults.push({ san: canonicalSan, status: 'weak', errorMsg: errorMessageOf(e) });
      }
    }

    const targetCount = Math.min(currentCandidates.length, 3);
    const isComplete = strongCount >= targetCount;

    setChallengeSubmissionByFen(prev => ({
      ...prev,
      [currentBoardFen]: {
        foundCount: strongCount,
        targetCount,
        isComplete,
        moves: moveResults
      }
    }));

    setIsChallengeLoading(false);
    if (isComplete) {
      setChallengeInputByFen(prev => ({ ...prev, [currentBoardFen]: '' }));
    }
  };

  const handleCalculationSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!calculationInput.trim() || isCalculationLoading) return;
    setIsCalculationLoading(true);
    setCalculationFeedback(null);

    const chessjs = await import('chess.js');
    let moveObj;
    let afterFen = '';
    try {
      const tempGame = new chessjs.Chess(challengeActiveFen);
      moveObj = tempGame.move(calculationInput.trim());
      if (moveObj) {
        afterFen = tempGame.fen();
      }
    } catch {
      // Caught below
    }

    if (!moveObj) {
      setCalculationFeedback({ type: 'error', message: 'Illegal move. Check your visualization.' });
      setIsCalculationLoading(false);
      return;
    }

    const api = await import('@/services/api');
    try {
      const res = await api.evaluateMove(challengeActiveFen, moveObj.san);
      if (res && res.evalLoss !== undefined && res.evalLoss !== null && res.evalLoss <= 0.20) {
        setCalculationFeedback({ type: 'success', message: `Good calculation. ${moveObj.san} is a strong continuation.` });
        setChallengeBranchesByFen(prev => {
          const activeCandidate = challengeActiveCandidateByFen[currentBoardFen];
          if (!activeCandidate) return prev;

          const fenBranches = prev[currentBoardFen] || {};
          const branchLine = fenBranches[activeCandidate] || [];
          return {
            ...prev,
            [currentBoardFen]: {
              ...fenBranches,
              [activeCandidate]: [
                ...branchLine,
                { san: moveObj.san, fenAfter: afterFen, isWhite: moveObj.color === 'w' }
              ]
            }
          };
        });
        setCalculationInput('');
      } else {
        setCalculationFeedback({ type: 'error', message: `Not strong enough (loses ${(res?.evalLoss || 0).toFixed(2)}). Try again.` });
      }
    } catch (err: unknown) {
      setCalculationFeedback({ type: 'error', message: errorMessageOf(err) || 'Evaluation failed.' });
    } finally {
      setIsCalculationLoading(false);
    }
  };

  const handleCalculationBack = () => {
    setChallengeBranchesByFen(prev => {
      const activeCandidate = challengeActiveCandidateByFen[currentBoardFen];
      if (!activeCandidate) return prev;

      const fenBranches = prev[currentBoardFen] || {};
      const branchLine = fenBranches[activeCandidate] || [];
      if (branchLine.length <= 1) return prev;

      return {
        ...prev,
        [currentBoardFen]: {
          ...fenBranches,
          [activeCandidate]: branchLine.slice(0, branchLine.length - 1)
        }
      };
    });
    setCalculationFeedback(null);
    setCalculationInput('');
  };

  return (
    <div
      className={`h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-white overflow-hidden ${
        activeTab === 'puzzles' ? '2xl:flex-row' : ''
      }`}
    >
      {/* Top Header */}
      <Header
        activeTab={activeTab}
        setActiveTab={changeTab}
        username={activeUsername}
        weaknessCount={weaknessCount}
        onDisconnect={handleDisconnect}
      />

      {/* Main Content Area */}
      <main className={`flex-1 min-w-0 flex flex-col ${activeTab === 'import' ? 'justify-center overflow-hidden py-2' : 'justify-start overflow-y-auto py-2'}`}>
        {/* TAB 1: PRACTICE PUZZLES */}
        {activeTab === 'puzzles' && (
          <div className="max-w-[1536px] w-full mx-auto px-4 lg:px-8 2xl:max-w-none 2xl:px-4 flex-1 flex flex-col justify-center min-h-0">

            <div className="flex-1 flex flex-col justify-center min-h-0">
              {isLoadingPuzzles ? (
                <div className="flex-1 flex items-center justify-center py-16">
                  <div className="flex items-center space-x-3 text-emerald-400 bg-slate-900/80 px-5 py-3 rounded-2xl border border-slate-800 shadow-lg">
                    <div className="w-5 h-5 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                    <span className="text-xs font-bold text-slate-300">Loading Practice Puzzles...</span>
                  </div>
                </div>
              ) : puzzleLoadError ? (
                <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-rose-900/50 shadow-xl">
                  <div className="w-14 h-14 rounded-2xl bg-rose-950/60 border border-rose-800 flex items-center justify-center mx-auto text-rose-400">
                    <span className="text-3xl font-bold">!</span>
                  </div>
                  <div>
                    <h3 className="text-lg font-bold text-white">Couldn&apos;t Load Puzzles</h3>
                    <p className="text-xs text-rose-300 mt-1">
                      We couldn&apos;t load your puzzles. Please try again.
                    </p>
                  </div>
                  <button
                    onClick={handleRetryPuzzleLoad}
                    className="px-5 py-2.5 bg-slate-800 hover:bg-slate-700 text-white font-bold text-xs rounded-xl transition inline-flex items-center gap-1.5 cursor-pointer"
                  >
                    <span>Retry</span>
                  </button>
                </div>
              ) : !activePuzzle ? (
                <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl">
                  <div className="w-14 h-14 rounded-2xl bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-emerald-400">
                    <span className="text-3xl font-bold">♟</span>
                  </div>
                  <div>
                    <h3 className="text-lg font-bold text-white">No Practice Puzzles Available</h3>
                    <p className="text-xs text-slate-400 mt-1">
                      {!activeUsername
                        ? 'Import your games using the Import Games tab to detect your opening habits and build your custom puzzle set.'
                        : `No weakness positions detected yet for ${activeUsername}. Import more games or adjust search criteria.`}
                    </p>
                  </div>
                  <button
                    onClick={() => changeTab('import')}
                    className="px-5 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/30 cursor-pointer"
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
                    isExploring={!isExplorationActive && historyIndex > 1}
                    isUnknown={isEvalUnknown}
                  />
                </div>

                {/* Center Interactive Chessboard & Controls */}
                <div className="w-full max-w-[640px] shrink-0 2xl:max-w-[760px] 2xl:w-auto 2xl:min-w-0 2xl:basis-[760px] 2xl:grow 2xl:shrink">
                  <ChessBoardArea
                    initialFen={activePuzzle.fen}
                    playerColor={activePuzzle.playerColor}
                    boardOrientation={isBoardFlipped ? (activePuzzle.playerColor === 'WHITE' ? 'black' : 'white') : (activePuzzle.playerColor === 'WHITE' ? 'white' : 'black')}
                    targetMove={activePuzzle.targetMove}
                    acceptableMoves={activePuzzle.acceptableMoves}
                    movesPlayed={activePuzzle.movesPlayed}
                    onMoveAttempt={handleMoveAttempt}
                    onPreviousPuzzle={handlePreviousPuzzle}
                    onNextPuzzle={handleNextPuzzle}
                    onUndo={handleBoardUndo}
                    onRedo={handleBoardRedo}
                    onReset={handleBoardReset}
                    hintSquare={hintSquare}
                    canHint={!(feedback.status === 'CORRECT' || feedback.status === 'EXPLORING')}
                    onFlipBoard={() => setIsBoardFlipped((prev) => !prev)}
                    soundEnabled={soundEnabled}
                    onToggleSound={handleToggleSound}
                    onFenChange={setCurrentBoardFen}
                    pendingContinuationCandidate={pendingContinuationCandidate}
                    onContinuationApplied={handleContinuationApplied}
                    isExplorationActive={isExplorationActive}
                    onUnacceptableMove={handleUnacceptableMove}
                    onUserExplorationMove={handleUserExplorationMove}
                    onChessEchoExplorationMove={handleChessEchoExplorationMove}
                    alternativeContinuationToApply={alternativeContinuationToApply}
                    onAlternativeContinuationApplied={() => setAlternativeContinuationToApply(null)}

                    explorationPlayMode={explorationPlayMode}
                    isChallengeComplete={challengeSubmissionByFen[currentBoardFen]?.isComplete ?? false}
                  />
                </div>

                {/* Right Feedback & Settings Panel */}
                <div className="w-full max-w-[480px] shrink-0 2xl:max-w-[360px] 2xl:w-auto 2xl:min-w-0 2xl:basis-[360px] 2xl:shrink">
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
                    isExplorationActive={isExplorationActive}
                    explorationDecisionMove={explorationDecisionMove}
                    onEnterExploration={handleEnterExploration}
                    onExitExploration={handleExitExploration}
                    continuationMode={continuationMode}
                    onContinuationModeChange={setContinuationMode}
                    opponentRatingBand={opponentRatingBand}
                    onOpponentRatingBandChange={setOpponentRatingBand}
                    explorationPlayMode={explorationPlayMode}
                    onExplorationPlayModeChange={handleExplorationPlayModeChange}
                    sideToMove={sideToMove}
                    continuationCandidate={continuation.selectedCandidate}
                    isContinuationLoading={continuation.loading || !!requestedContinuationFen}
                    unacceptableMoveMessage={unacceptableMoveMessage}
                    explorationFeedback={explorationFeedbackByFen[currentBoardFen] || null}
                    lastContinuationCandidates={lastContinuationCandidates}
                    onAlternativeSelected={handleAlternativeSelected}
                    challengeCandidates={challengeCandidatesByFen[currentBoardFen] || []}
                    challengeSubmission={challengeSubmissionByFen[currentBoardFen]}
                    challengeInput={challengeInputByFen[currentBoardFen] || ''}
                    onChallengeInputChange={(v) => setChallengeInputByFen(prev => ({ ...prev, [currentBoardFen]: v }))}
                    onChallengeSubmit={handleChallengeSubmit}
                    challengeFeedback={challengeFeedbackByFen[currentBoardFen]}
                    isChallengeLoading={isChallengeLoading}
                    onChallengeCandidateSelect={handleChallengeCandidateSelect}
                    activeChallengeCandidate={challengeActiveCandidateByFen[currentBoardFen] || null}
                    challengeBranches={challengeBranchesByFen[currentBoardFen] || {}}
                    onFinishChallenge={handleExitExploration}
                    calculationInput={calculationInput}
                    onCalculationInputChange={setCalculationInput}
                    onCalculationSubmit={handleCalculationSubmit}
                    calculationFeedback={calculationFeedback}
                    isCalculationLoading={isCalculationLoading}
                    onCalculationBack={handleCalculationBack}
                    onBackToCandidates={handleBackToCandidates}
                  />
                </div>
              </div>
            )}
          </div>
          </div>
        )}

        {/* TAB 2: WEAKNESSES LIBRARY */}
        {activeTab === 'weaknesses' && (
          <WeaknessesList
            username={activeUsername}
            minEvalLoss={minEvalLoss}
            onMinEvalLossChange={handleMinEvalLossChange}
            minMistakeCount={minMistakeCount}
            onMinMistakeCountChange={handleMinMistakeCountChange}
            onSelectPractice={handleSelectPracticeFromLibrary}
            onWeaknessCountChange={setWeaknessCount}
            activeColorFilter={puzzleColorFilter === 'BOTH' ? 'ALL' : puzzleColorFilter}
            onColorFilterChange={(c) => handleColorFilterChange(c === 'ALL' ? 'BOTH' : c)}
            isAnalysisActive={!!activeUsername && (activeJobStatus?.status === 'QUEUED' || activeJobStatus?.status === 'PROCESSING')}
            refreshKey={weaknessRefreshKey}
          />
        )}

        {/* TAB 3: IMPORT GAMES */}
        {activeTab === 'import' && (
          <ImportGamesView
            connectedUsername={activeUsername}
            onDisconnect={handleDisconnect}
            onImportStarted={(user) => handleSetUsername(user)}
            onNavigateTab={(tab) => changeTab(tab)}
            onJobStatusUpdate={handleJobStatusUpdate}
          />
        )}




      </main>
    </div>
  );
}
