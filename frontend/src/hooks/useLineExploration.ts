import React, { useState } from 'react';
import {
  ContinuationMode,
  ContinuationCandidate,
  ExplorationPlayMode,
  toWhitePerspective,
  fetchPuzzleContinuation,
  evaluateMove,
} from '@/services/api';
import {
  createDeterministicSelectionPolicy,
  createStochasticSelectionPolicy,
} from '@/services/continuationService';
import { usePuzzleContinuation } from '@/hooks/usePuzzleContinuation';
import { STRICT_OPPONENT_EVAL_LOSS_THRESHOLD } from '@/utils/practiceConstants';
import { formatPawnLoss } from '@/utils/formatters';
import { Puzzle } from '@/types/puzzle';
import type { ChallengeSubmissionResult } from '@/components/PuzzleFeedbackPanel';

const chessEchoSelectionPolicy = createStochasticSelectionPolicy();
const deterministicSelectionPolicy = createDeterministicSelectionPolicy();

export type PuzzleFeedbackState = {
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

export type UseLineExplorationOptions = {
  activePuzzle: Puzzle | null;
  currentBoardFen: string;
  currentEvalCp: number;
  isEvalUnknown: boolean;
  onUpdateEval: (evalCp: number, isUnknown: boolean) => void;
  onFeedbackChange: (feedback: PuzzleFeedbackState) => void;
  onExitExploration?: (decisionMove: string | null) => void;
};

export function useLineExploration({
  activePuzzle,
  currentBoardFen,
  currentEvalCp,
  isEvalUnknown,
  onUpdateEval,
  onFeedbackChange,
  onExitExploration,
}: UseLineExplorationOptions) {
  const [isExplorationActive, setIsExplorationActive] = useState<boolean>(false);
  const [isMoveEvaluationPending, setIsMoveEvaluationPending] = useState<boolean>(false);
  const [explorationPlayMode, setExplorationPlayMode] = useState<ExplorationPlayMode | undefined>(undefined);
  const [explorationDecisionMove, setExplorationDecisionMove] = useState<string | null>(null);
  const [unacceptableMoveMessage, setUnacceptableMoveMessage] = useState<string | null>(null);

  // Challenge Mode State
  const [challengeCandidatesByFen, setChallengeCandidatesByFen] = useState<Record<string, ContinuationCandidate[]>>({});
  const [challengeSubmissionByFen, setChallengeSubmissionByFen] = useState<Record<string, ChallengeSubmissionResult>>({});
  const [challengeInputByFen, setChallengeInputByFen] = useState<Record<string, string>>({});
  const [challengeFeedbackByFen, setChallengeFeedbackByFen] = useState<Record<string, { message: string; type: 'success' | 'error' | 'info' } | null>>({});
  const [isChallengeLoading, setIsChallengeLoading] = useState<boolean>(false);
  const [continuationMode, setContinuationMode] = useState<ContinuationMode>('ENGINE');
  const [opponentRatingBand, setOpponentRatingBand] = useState<string>('1200-1400');
  const [pendingContinuationCandidate, setPendingContinuationCandidate] = useState<ContinuationCandidate | null>(null);
  const [requestedContinuationFen, setRequestedContinuationFen] = useState<string | undefined>(undefined);

  const [explorationFeedbackByFen, setExplorationFeedbackByFen] = useState<Record<string, { message: string; type: 'best' | 'good' } | null>>({});
  const [lastContinuationCandidates, setLastContinuationCandidates] = useState<{
    parentFen: string;
    candidates: ContinuationCandidate[];
    selected: ContinuationCandidate;
  } | null>(null);
  const [alternativeContinuationToApply, setAlternativeContinuationToApply] = useState<{ parentFen: string; candidate: ContinuationCandidate } | null>(null);
  const [explorationEvalMap, setExplorationEvalMap] = useState<Record<string, { evalCp: number; isUnknown: boolean }>>({});

  // Challenge Calculation State
  const [challengeBranchesByFen, setChallengeBranchesByFen] = useState<Record<string, Record<string, { san: string; fenAfter: string; isWhite: boolean }[]>>>({});
  const [challengeActiveCandidateByFen, setChallengeActiveCandidateByFen] = useState<Record<string, string | null>>({});
  const [calculationInput, setCalculationInput] = useState<string>('');
  const [calculationFeedback, setCalculationFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
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

  const continuationSelectionPolicy = explorationPlayMode === 'CHESSECHO'
    ? chessEchoSelectionPolicy
    : deterministicSelectionPolicy;
  const continuation = usePuzzleContinuation(
    requestedContinuationFen,
    continuationMode,
    continuationSelectionPolicy,
    opponentRatingBand
  );

  // Synchronize candidate staging when ChessEcho turn is active
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
          selected: candidate,
        });

        if (candidate.evalCp != null) {
          const whiteEval = toWhitePerspective(candidate.evalCp, requestedContinuationFen);
          onUpdateEval(whiteEval, false);
          setExplorationEvalMap((prev) => ({
            ...prev,
            [candidate.resultingFen]: { evalCp: whiteEval, isUnknown: false },
          }));
        } else {
          onUpdateEval(currentEvalCp, true);
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
      if (currentBoardFen !== lastContinuationCandidates.parentFen) {
        const actualSelected = lastContinuationCandidates.candidates.find(
          (c) => c.resultingFen === currentBoardFen
        );
        if (!actualSelected) {
          setLastContinuationCandidates(null);
        } else if (actualSelected.move !== lastContinuationCandidates.selected.move) {
          setLastContinuationCandidates((prev) =>
            prev ? { ...prev, selected: actualSelected } : null
          );
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
      onUpdateEval(evalEntry.evalCp, evalEntry.isUnknown);
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

    setLastContinuationCandidates((prev) =>
      prev ? { ...prev, selected: candidate } : null
    );

    if (candidate.evalCp != null) {
      const whiteEval = toWhitePerspective(candidate.evalCp, parentFen);
      onUpdateEval(whiteEval, false);
      setExplorationEvalMap((prev) => ({
        ...prev,
        [candidate.resultingFen]: { evalCp: whiteEval, isUnknown: false },
      }));
    } else {
      onUpdateEval(currentEvalCp, true);
      setExplorationEvalMap((prev) => ({
        ...prev,
        [candidate.resultingFen]: { evalCp: currentEvalCp, isUnknown: true },
      }));
    }

    setAlternativeContinuationToApply({
      parentFen,
      candidate,
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
          setExplorationFeedbackByFen((prev) => ({
            ...prev,
            [nextFen]: { message: 'Best move. No evaluation loss.', type: 'best' },
          }));
        } else {
          setExplorationFeedbackByFen((prev) => ({
            ...prev,
            [nextFen]: {
              message: `Good move. It loses ${feedback.loss.toFixed(2)} pawns compared with the best move.`,
              type: 'good',
            },
          }));
        }
      }

      if (feedback.evalCp != null && feedback.fromFen) {
        const whiteEval = toWhitePerspective(feedback.evalCp, feedback.fromFen);
        onUpdateEval(whiteEval, false);
        setExplorationEvalMap((prev) => ({
          ...prev,
          [nextFen]: { evalCp: whiteEval, isUnknown: false },
        }));
      } else if (feedback.evalCp === null) {
        onUpdateEval(currentEvalCp, true);
        setExplorationEvalMap((prev) => ({
          ...prev,
          [nextFen]: { evalCp: currentEvalCp, isUnknown: true },
        }));
      }
    } else {
      setExplorationFeedbackByFen((prev) => ({ ...prev, [nextFen]: null }));
    }
  };

  const handleChessEchoExplorationMove = () => {
    setRequestedContinuationFen(undefined);
  };

  const handleEnterExploration = (
    initialMode?: ExplorationPlayMode,
    decisionMove?: string,
    baselineFenOverride?: string,
    baselineEvalOverride?: number,
    baselineUnknownOverride?: boolean
  ) => {
    setExplorationDecisionMove(decisionMove ?? null);
    setIsExplorationActive(true);
    setExplorationPlayMode(initialMode);
    const baselineFen = baselineFenOverride || currentBoardFen || activePuzzle?.fen || '';
    const baselineEval = baselineEvalOverride ?? currentEvalCp;
    const baselineUnknown = baselineUnknownOverride ?? isEvalUnknown;

    const initialMap: Record<string, { evalCp: number; isUnknown: boolean }> = {};
    if (!baselineFenOverride && activePuzzle?.fen) {
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

    onFeedbackChange({ status: 'EXPLORING' });
    setUnacceptableMoveMessage(null);
    setLastContinuationCandidates(null);
    setAlternativeContinuationToApply(null);
    setChallengeBranchesByFen({});
    setChallengeActiveCandidateByFen({});
  };

  const handleExitExploration = () => {
    const decisionMove = explorationDecisionMove;
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
    onExitExploration?.(decisionMove);
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

  const resetExploration = () => {
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

  const handleUndo = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    if (isExplorationActive) {
      setLastContinuationCandidates(null);
    }
  };

  const handleRedo = () => {
    setRequestedContinuationFen(undefined);
    setPendingContinuationCandidate(null);
    if (isExplorationActive) {
      setLastContinuationCandidates(null);
    }
  };

  const handleChallengeCandidateSelect = async (san: string) => {
    const chessjs = await import('chess.js');
    try {
      setChallengeActiveCandidateByFen((prev) => ({ ...prev, [currentBoardFen]: san }));

      setChallengeBranchesByFen((prev) => {
        const fenBranches = prev[currentBoardFen] || {};
        if (!fenBranches[san]) {
          const tempGame = new chessjs.Chess(currentBoardFen);
          const moveObj = tempGame.move(san);
          if (moveObj) {
            return {
              ...prev,
              [currentBoardFen]: {
                ...fenBranches,
                [san]: [{ san, fenAfter: tempGame.fen(), isWhite: moveObj.color === 'w' }],
              },
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
    setChallengeActiveCandidateByFen((prev) => ({ ...prev, [currentBoardFen]: null }));
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
    setTrackedChallengeQuery({
      currentBoardFen,
      explorationPlayMode,
      submission: challengeSubmission,
    });
    if (explorationPlayMode === 'CHALLENGE' && currentBoardFen && !challengeSubmission) {
      setChallengeFeedbackByFen((prev) => ({ ...prev, [currentBoardFen]: null }));
      setChallengeInputByFen((prev) => ({ ...prev, [currentBoardFen]: '' }));
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
          const strongCandidates = result.candidates.filter(
            (c) => (c.evalLoss ?? 0) <= STRICT_OPPONENT_EVAL_LOSS_THRESHOLD
          );
          setChallengeCandidatesByFen((prev) => ({ ...prev, [currentBoardFen]: strongCandidates }));
        }
      } catch {
        if (isMounted) {
          setChallengeFeedbackByFen((prev) => ({
            ...prev,
            [currentBoardFen]: { type: 'error', message: 'Failed to load candidates.' },
          }));
        }
      } finally {
        if (isMounted) setIsChallengeLoading(false);
      }
    };

    fetchCandidates();
    return () => {
      isMounted = false;
    };
  }, [currentBoardFen, explorationPlayMode, challengeSubmissionByFen]);

  const handleChallengeSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!currentBoardFen) return;

    const currentChallengeInput = challengeInputByFen[currentBoardFen] || '';
    if (!currentChallengeInput.trim() || isChallengeLoading) return;

    const tokens = currentChallengeInput.split(/[\s,]+/).filter((t) => t.trim().length > 0);
    if (tokens.length === 0) return;

    setIsChallengeLoading(true);
    setChallengeFeedbackByFen((prev) => ({ ...prev, [currentBoardFen]: null }));

    const currentCandidates = challengeCandidatesByFen[currentBoardFen] || [];

    const chessjs = await import('chess.js');
    const uniqueMoves = new Map<string, string>();
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
      setChallengeFeedbackByFen((prev) => ({
        ...prev,
        [currentBoardFen]: {
          type: 'error',
          message: `"${invalidTokens[0]}" isn't valid SAN. Check the move and try again.`,
        },
      }));
      setIsChallengeLoading(false);
      return;
    }

    const moveResults: ChallengeSubmissionResult['moves'] = [];
    let strongCount = 0;

    for (const canonicalSan of uniqueMoves.keys()) {
      try {
        const res = await evaluateMove(currentBoardFen, canonicalSan);
        if (res && res.evalLoss !== undefined && res.evalLoss !== null) {
          if (res.evalLoss <= STRICT_OPPONENT_EVAL_LOSS_THRESHOLD) {
            strongCount++;
            moveResults.push({ san: canonicalSan, status: 'strong' });
          } else {
            moveResults.push({ san: canonicalSan, status: 'weak' });
          }
        } else {
          moveResults.push({ san: canonicalSan, status: 'weak', errorMsg: 'Failed to evaluate.' });
        }
      } catch (err: unknown) {
        moveResults.push({ san: canonicalSan, status: 'weak', errorMsg: errorMessageOf(err) });
      }
    }

    const targetCount = Math.min(currentCandidates.length, 3);
    const isComplete = strongCount >= targetCount;

    setChallengeSubmissionByFen((prev) => ({
      ...prev,
      [currentBoardFen]: {
        foundCount: strongCount,
        targetCount,
        isComplete,
        moves: moveResults,
      },
    }));

    setIsChallengeLoading(false);
    if (isComplete) {
      setChallengeInputByFen((prev) => ({ ...prev, [currentBoardFen]: '' }));
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

    try {
      const res = await evaluateMove(challengeActiveFen, moveObj.san);
      if (
        res &&
        res.evalLoss !== undefined &&
        res.evalLoss !== null &&
        res.evalLoss <= STRICT_OPPONENT_EVAL_LOSS_THRESHOLD
      ) {
        setCalculationFeedback({
          type: 'success',
          message: `Good calculation. ${moveObj.san} is a strong continuation.`,
        });
        setChallengeBranchesByFen((prev) => {
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
                { san: moveObj.san, fenAfter: afterFen, isWhite: moveObj.color === 'w' },
              ],
            },
          };
        });
        setCalculationInput('');
      } else {
        setCalculationFeedback({
          type: 'error',
          message: `Not strong enough (loses ${formatPawnLoss(res?.evalLoss)}). Try again.`,
        });
      }
    } catch (err: unknown) {
      setCalculationFeedback({
        type: 'error',
        message: errorMessageOf(err) || 'Evaluation failed.',
      });
    } finally {
      setIsCalculationLoading(false);
    }
  };

  const handleCalculationBack = () => {
    setChallengeBranchesByFen((prev) => {
      const activeCandidate = challengeActiveCandidateByFen[currentBoardFen];
      if (!activeCandidate) return prev;

      const fenBranches = prev[currentBoardFen] || {};
      const branchLine = fenBranches[activeCandidate] || [];
      if (branchLine.length <= 1) return prev;

      return {
        ...prev,
        [currentBoardFen]: {
          ...fenBranches,
          [activeCandidate]: branchLine.slice(0, branchLine.length - 1),
        },
      };
    });
    setCalculationFeedback(null);
    setCalculationInput('');
  };

  return {
    isExplorationActive,
    explorationPlayMode,
    explorationDecisionMove,
    unacceptableMoveMessage,
    continuationMode,
    setContinuationMode,
    opponentRatingBand,
    setOpponentRatingBand,
    pendingContinuationCandidate,
    requestedContinuationFen,
    lastContinuationCandidates,
    alternativeContinuationToApply,
    setAlternativeContinuationToApply,
    sideToMove,
    continuation,
    explorationFeedback: explorationFeedbackByFen[currentBoardFen] || null,
    isMoveEvaluationPending,
    setIsMoveEvaluationPending,
    handleExplorationPlayModeChange,
    handleAlternativeSelected,
    handleContinuationApplied,
    handleUserExplorationMove,
    handleChessEchoExplorationMove,
    handleEnterExploration,
    handleExitExploration,
    handleUnacceptableMove,
    resetExploration,
    handleUndo,
    handleRedo,
    // Challenge state & actions
    challengeCandidates: challengeCandidatesByFen[currentBoardFen] || [],
    challengeSubmission,
    challengeInput: challengeInputByFen[currentBoardFen] || '',
    onChallengeInputChange: (v: string) =>
      setChallengeInputByFen((prev) => ({ ...prev, [currentBoardFen]: v })),
    onChallengeSubmit: handleChallengeSubmit,
    challengeFeedback: challengeFeedbackByFen[currentBoardFen],
    isChallengeLoading,
    onChallengeCandidateSelect: handleChallengeCandidateSelect,
    activeChallengeCandidate: challengeActiveCandidateByFen[currentBoardFen] || null,
    challengeBranches: challengeBranchesByFen[currentBoardFen] || {},
    calculationInput,
    onCalculationInputChange: setCalculationInput,
    onCalculationSubmit: handleCalculationSubmit,
    calculationFeedback,
    isCalculationLoading,
    onCalculationBack: handleCalculationBack,
    onBackToCandidates: handleBackToCandidates,
  };
}
