'use client';

import React, { useState } from 'react';
import { Header, TabType } from '@/components/Header';
import { EvalBar } from '@/components/EvalBar';
import { ChessBoardArea } from '@/components/ChessBoardArea';
import { PuzzleFeedbackPanel } from '@/components/PuzzleFeedbackPanel';
import { WeaknessesList } from '@/components/WeaknessesList';
import { ImportGamesView } from '@/components/ImportGamesView';
import { Puzzle } from '@/mock/mockData';
import { fetchPuzzles, JobStatusResponse, ContinuationMode, ContinuationCandidate, toWhitePerspective } from '@/services/api';
import { soundService } from '@/services/soundService';
import { usePuzzleContinuation } from '@/utils/usePuzzleContinuation';

export const EXPLORATION_STEP_DELAY_MS = 800;

export default function Home() {
  const [activeTab, setActiveTab] = useState<TabType>('puzzles');
  const [activeUsername, setActiveUsername] = useState<string | undefined>(undefined);
  const [activeJobStatus, setActiveJobStatus] = useState<JobStatusResponse | null>(null);
  const [weaknessRefreshKey, setWeaknessRefreshKey] = useState<number>(0);

  const handleJobStatusUpdate = (job: JobStatusResponse | null) => {
    setActiveJobStatus(job);
    if (job?.status === 'COMPLETED') {
      setWeaknessRefreshKey((k) => k + 1);
    }
  };

  // Sync state from localStorage & window hash after client mount to prevent SSR hydration mismatch
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const hash = window.location.hash.replace('#', '');
      if (hash === 'weaknesses' || hash === 'import' || hash === 'puzzles') {
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setActiveTab(hash as TabType);
      } else {
        const savedTab = localStorage.getItem('chessecho_active_tab');
        if (savedTab === 'weaknesses' || savedTab === 'import' || savedTab === 'puzzles') {
          setActiveTab(savedTab as TabType);
        }
      }

      const savedUser = localStorage.getItem('chessecho_username');
      if (savedUser) {
        setActiveUsername(savedUser);
      }
    }
  }, []);

  // Listen to hashchange / popstate for browser back & forward navigation
  React.useEffect(() => {
    const syncHash = () => {
      if (typeof window !== 'undefined') {
        const hash = window.location.hash.replace('#', '');
        if (hash === 'weaknesses' || hash === 'import' || hash === 'puzzles') {
          setActiveTab(hash as TabType);
          localStorage.setItem('chessecho_active_tab', hash);
        }
      }
    };
    window.addEventListener('hashchange', syncHash);
    window.addEventListener('popstate', syncHash);
    return () => {
      window.removeEventListener('hashchange', syncHash);
      window.removeEventListener('popstate', syncHash);
    };
  }, []);

  const changeTab = (tab: TabType) => {
    setActiveTab(tab);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_active_tab', tab);
      window.history.pushState(null, '', `#${tab}`);
    }
  };

  const handleSetUsername = (user: string | undefined) => {
    setActiveUsername(user);
    if (typeof window !== 'undefined') {
      if (user) {
        localStorage.setItem('chessecho_username', user);
      } else {
        localStorage.removeItem('chessecho_username');
      }
    }
  };

  const [weaknessCount, setWeaknessCount] = useState<number>(0);

  const handleDisconnect = () => {
    handleSetUsername(undefined);
    setPuzzlesList([]);
    setActivePuzzle(null);
    setWeaknessCount(0);
  };


  const [puzzlesList, setPuzzlesList] = useState<Puzzle[]>([]);
  const [currentPuzzleIndex, setCurrentPuzzleIndex] = useState<number>(0);
  const [activePuzzle, setActivePuzzle] = useState<Puzzle | null>(null);
  const [isLoadingPuzzles, setIsLoadingPuzzles] = useState<boolean>(true);

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

  // Restore puzzle filter settings from localStorage ONCE after client mount
  React.useEffect(() => {
    if (typeof window !== 'undefined') {
      const savedColor = localStorage.getItem('chessecho_puzzle_color_filter');
      if (savedColor === 'BOTH' || savedColor === 'WHITE' || savedColor === 'BLACK') {
        setPuzzleColorFilter(savedColor as 'BOTH' | 'WHITE' | 'BLACK');
      }
      const savedEvalLoss = localStorage.getItem('chessecho_min_eval_loss');
      if (savedEvalLoss && !isNaN(Number(savedEvalLoss))) {
        setMinEvalLoss(Number(savedEvalLoss));
      }
      const savedMistakes = localStorage.getItem('chessecho_min_mistake_count');
      if (savedMistakes && !isNaN(Number(savedMistakes))) {
        setMinMistakeCount(Number(savedMistakes));
      }
      setSoundEnabled(soundService.isSoundEnabled());
      setIsSettingsInitialized(true);
    }
  }, []);

  const handleMinEvalLossChange = (val: number) => {
    setMinEvalLoss(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_eval_loss', String(val));
    }
  };

  const handleMinMistakeCountChange = (val: number) => {
    setMinMistakeCount(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_mistake_count', String(val));
    }
  };

  const handleColorFilterChange = (color: 'BOTH' | 'WHITE' | 'BLACK') => {
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
  const [feedbackHistory, setFeedbackHistory] = useState<unknown[]>([{ status: 'IDLE' }]);
  const [historyIndex, setHistoryIndex] = useState<number>(0);

  const [currentEvalCp, setCurrentEvalCp] = useState<number>(35);
  const [isEvalUnknown, setIsEvalUnknown] = useState<boolean>(false);
  const [moveHistory, setMoveHistory] = useState<string[]>([]);
  const [hintSquare, setHintSquare] = useState<string | undefined>(undefined);

  const [feedback, setFeedback] = useState<{
    status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
    lastMove?: string;
    historicalInfo?: { timesPlayed: number; averageLoss: number };
  }>({ status: 'IDLE' });

  // Continuation & Line Exploration turn-based state machine
  const [isExplorationActive, setIsExplorationActive] = useState<boolean>(false);
  const [unacceptableMoveMessage, setUnacceptableMoveMessage] = useState<string | null>(null);
  const [currentBoardFen, setCurrentBoardFen] = useState<string>('');
  const [continuationMode, setContinuationMode] = useState<ContinuationMode>('ENGINE');
  const [pendingContinuationCandidate, setPendingContinuationCandidate] = useState<ContinuationCandidate | null>(null);
  const [requestedContinuationFen, setRequestedContinuationFen] = useState<string | undefined>(undefined);

  const [explorationFeedback, setExplorationFeedback] = useState<{ message: string, type: 'best' | 'good' } | null>(null);
  const [lastContinuationCandidates, setLastContinuationCandidates] = useState<{
    parentFen: string;
    candidates: ContinuationCandidate[];
    selected: ContinuationCandidate;
  } | null>(null);
  const [alternativeContinuationToApply, setAlternativeContinuationToApply] = useState<{ parentFen: string, candidate: ContinuationCandidate } | null>(null);
  const [explorationEvalMap, setExplorationEvalMap] = useState<Record<string, { evalCp: number; isUnknown: boolean }>>({});

  const explorationTurn = React.useMemo(() => {
    if (!activePuzzle || !currentBoardFen) return 'USER';
    const fenColor = currentBoardFen.split(' ')[1]; // 'w' or 'b'
    const isWhiteTurn = fenColor === 'w';
    const isUserWhite = activePuzzle.playerColor === 'WHITE';
    return isWhiteTurn === isUserWhite ? 'USER' : 'CHESSECHO';
  }, [currentBoardFen, activePuzzle]);

  const continuation = usePuzzleContinuation(requestedContinuationFen, continuationMode);

  // When ChessEcho turn is active and a candidate is selected, stage it for the board
  React.useEffect(() => {
    if (!isExplorationActive) return;
    if (continuation.loading) return;

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
  }, [isExplorationActive, continuation.selectedCandidate, continuation.loading, continuation.response, requestedContinuationFen, currentBoardFen, currentEvalCp]);

  // Keep lastContinuationCandidates synchronized with board history
  React.useEffect(() => {
    if (lastContinuationCandidates) {
      // If we are still at parentFen, it means the move is pending application by ChessBoardArea
      if (currentBoardFen === lastContinuationCandidates.parentFen) return;
      
      const actualSelected = lastContinuationCandidates.candidates.find(c => c.resultingFen === currentBoardFen);
      if (!actualSelected) {
        setLastContinuationCandidates(null);
      } else if (actualSelected.move !== lastContinuationCandidates.selected.move) {
        setLastContinuationCandidates(prev => prev ? { ...prev, selected: actualSelected } : null);
      }
    }
  }, [currentBoardFen, lastContinuationCandidates]);

  // Synchronize EvalBar with the board's active position during exploration
  React.useEffect(() => {
    if (!isExplorationActive || !currentBoardFen) return;
    const entry = explorationEvalMap[currentBoardFen];
    if (entry) {
      setCurrentEvalCp(entry.evalCp);
      setIsEvalUnknown(entry.isUnknown);
    }
  }, [currentBoardFen, isExplorationActive, explorationEvalMap]);

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
    // User made a successful, acceptable move. Request a continuation.
    setRequestedContinuationFen(nextFen);
    setLastContinuationCandidates(null);
    if (feedback) {
      if (feedback.isBest) {
        setExplorationFeedback({ message: 'Best move. No evaluation loss.', type: 'best' });
      } else {
        setExplorationFeedback({ message: `Good move. It loses ${feedback.loss.toFixed(2)} pawns compared with the best move.`, type: 'good' });
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
      setExplorationFeedback(null);
    }
  };

  const handleChessEchoExplorationMove = (moveSan: string) => {
    // ChessEcho made its move. Clear request.
    setRequestedContinuationFen(undefined);
  };

  const handleEnterExploration = () => {
    setIsExplorationActive(true);
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

    setRequestedContinuationFen(baselineFen);
    setFeedback({ status: 'EXPLORING' });
    setUnacceptableMoveMessage(null);
    setExplorationFeedback(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
  };

  const handleExitExploration = () => {
    setIsExplorationActive(false);
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    setUnacceptableMoveMessage(null);
    setExplorationFeedback(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
    setFeedback({ status: 'CORRECT', lastMove: activePuzzle?.targetMove });
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
    setUnacceptableMoveMessage(null);
    setExplorationFeedback(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
  };

  const [puzzlePage, setPuzzlePage] = useState<number>(0);
  const [hasMorePuzzles, setHasMorePuzzles] = useState<boolean>(true);
  const [isFetchingMorePuzzles, setIsFetchingMorePuzzles] = useState<boolean>(false);

  // Fetch live puzzles whenever activeUsername, puzzleColorFilter, minEvalLoss, or minMistakeCount changes, guarded by isSettingsInitialized gate
  React.useEffect(() => {
    if (!isSettingsInitialized || !activeUsername) {
      if (!activeUsername) {
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        setIsLoadingPuzzles(false);
        setPuzzlePage(0);
        setHasMorePuzzles(false);
      }
      return;
    }

    async function loadData() {
      setIsLoadingPuzzles(true);
      setPuzzlePage(0);
      setHasMorePuzzles(true);
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
        console.error('Failed to load puzzles from backend API:', err);
        setPuzzlesList([]);
        setActivePuzzle(null);
        setFeedback({ status: 'IDLE' });
        setMoveHistory([]);
        setHintSquare(undefined);
        setHasMorePuzzles(false);
      } finally {
        setIsLoadingPuzzles(false);
      }
    }
    loadData();
  }, [isSettingsInitialized, activeUsername, puzzleColorFilter, minEvalLoss, minMistakeCount]);

  const handleApplyPuzzleSettings = async () => {
    if (!activeUsername) return;
    setIsLoadingPuzzles(true);
    setPuzzlePage(0);
    setHasMorePuzzles(true);
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
      console.error('Failed to apply puzzle settings:', err);
      setPuzzlesList([]);
      setActivePuzzle(null);
      setFeedback({ status: 'IDLE' });
      setMoveHistory([]);
      setHintSquare(undefined);
      setHasMorePuzzles(false);
    } finally {
      setIsLoadingPuzzles(false);
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
        console.error('Failed to prefetch next puzzle page:', err);
        setHasMorePuzzles(false);
      } finally {
        setIsFetchingMorePuzzles(false);
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
      setExplorationFeedback(null);
      setLastContinuationCandidates(null);
      return;
    }

    const prevIndex = Math.max(0, historyIndex - 1);
    setHistoryIndex(prevIndex);
    setCurrentEvalCp(evalHistory[prevIndex] ?? (activePuzzle?.evalCp ?? 35));
    const prevFeedback = feedbackHistory[prevIndex] as any;
    setFeedback(prevFeedback ?? { status: 'IDLE' });
    setIsEvalUnknown(false);
  };

  const handleBoardRedo = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);

    if (isExplorationActive) {
      setExplorationFeedback(null);
      setLastContinuationCandidates(null);
      return;
    }

    if (historyIndex < evalHistory.length - 1) {
      const nextIndex = historyIndex + 1;
      setHistoryIndex(nextIndex);
      setCurrentEvalCp(evalHistory[nextIndex]);
      const nextFeedback = feedbackHistory[nextIndex] as any;
      setFeedback(nextFeedback ?? { status: 'IDLE' });
    }
  };

  const handleBoardReset = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    setIsExplorationActive(false);
    setUnacceptableMoveMessage(null);
    setExplorationFeedback(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setExplorationEvalMap({});
    
    setHistoryIndex(0);
    setCurrentEvalCp(evalHistory[0] ?? (activePuzzle?.evalCp ?? 35));
    const firstFeedback = feedbackHistory[0] as any;
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
    const isAlreadySolved = feedback.status === 'CORRECT' || feedback.status === 'EXPLORING';

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

  return (
    <div className="h-screen bg-slate-950 text-slate-100 flex flex-col font-sans selection:bg-emerald-500 selection:text-white overflow-hidden">
      {/* Top Header */}
      <Header
        activeTab={activeTab}
        setActiveTab={changeTab}
        username={activeUsername}
        weaknessCount={weaknessCount}
        onDisconnect={handleDisconnect}
      />
      
      {/* Main Content Area */}
      <main className={`flex-1 flex flex-col ${activeTab === 'import' ? 'justify-center overflow-hidden py-2' : 'justify-start overflow-y-auto py-2'}`}>
        {/* TAB 1: PRACTICE PUZZLES */}
        {activeTab === 'puzzles' && (
          <div className="max-w-[1536px] w-full mx-auto px-4 lg:px-8 flex-1 flex flex-col justify-center min-h-0">

            <div className="flex-1 flex flex-col justify-center min-h-0">
              {isLoadingPuzzles ? (
                <div className="flex-1 flex items-center justify-center py-16">
                  <div className="flex items-center space-x-3 text-emerald-400 bg-slate-900/80 px-5 py-3 rounded-2xl border border-slate-800 shadow-lg">
                    <div className="w-5 h-5 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                    <span className="text-xs font-bold text-slate-300">Loading Practice Puzzles...</span>
                  </div>
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
              <div className="flex flex-col lg:flex-row items-center lg:items-start justify-center gap-5 xl:gap-8">
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
                <div className="w-full max-w-[640px] 2xl:max-w-[680px] shrink-0">
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
                  />
                </div>

                {/* Right Feedback & Settings Panel */}
                <div className="w-full max-w-[480px] shrink-0">
                  <PuzzleFeedbackPanel
                    puzzle={activePuzzle}
                    feedback={feedback}
                    moveHistory={moveHistory}
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
                    explorationTurn={explorationTurn}
                    onEnterExploration={handleEnterExploration}
                    onExitExploration={handleExitExploration}
                    continuationMode={continuationMode}
                    onContinuationModeChange={setContinuationMode}
                    continuationCandidate={continuation.selectedCandidate}
                    effectiveProvider={continuation.effectiveProvider}
                    isContinuationFallback={continuation.isFallback}
                    isContinuationLoading={continuation.loading || !!requestedContinuationFen}
                    unacceptableMoveMessage={unacceptableMoveMessage}
                    explorationFeedback={explorationFeedback}
                    lastContinuationCandidates={lastContinuationCandidates}
                    onAlternativeSelected={handleAlternativeSelected}
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
