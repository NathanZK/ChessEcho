import { toCanonicalMove, areMovesEqual, type CanonicalMove } from './chessMove';
import { formatTimerMs } from '../components/PuzzleFeedbackPanel';
import type { AcceptableMove, LongDecisionContext } from '../types/puzzle';

export type DecisionQuality = 'OPTIMAL' | 'ACCEPTABLE' | 'INFERIOR';

export interface DecisionTimingComparisonResult {
  title: string;
  message: string;
  durationComparison?: string;
  isIndeterminate: boolean;
  originalQuality?: DecisionQuality;
  userQuality?: DecisionQuality;
  isSameMove?: boolean;
}

function determineOriginalQuality(
  preMoveFen: string,
  origCanonical: CanonicalMove,
  bestCanonical: CanonicalMove | null,
  acceptableMoves: AcceptableMove[],
): DecisionQuality {
  if (bestCanonical && areMovesEqual(origCanonical, bestCanonical)) {
    return 'OPTIMAL';
  }
  for (const m of acceptableMoves) {
    const canon = toCanonicalMove(preMoveFen, m.move);
    if (canon && areMovesEqual(origCanonical, canon)) {
      return (m.evalLoss ?? 0) === 0 ? 'OPTIMAL' : 'ACCEPTABLE';
    }
  }
  return 'INFERIOR';
}

function determineUserQuality(
  userCanonical: CanonicalMove | null,
  bestCanonical: CanonicalMove | null,
  isCorrect: boolean,
): DecisionQuality {
  if (bestCanonical && areMovesEqual(userCanonical, bestCanonical)) {
    return 'OPTIMAL';
  }
  return isCorrect ? 'ACCEPTABLE' : 'INFERIOR';
}

function getOptimalFeedback(
  userQuality: DecisionQuality,
  isSameMove: boolean,
  userMove: string,
  movePlayed: string,
) {
  if (userQuality === 'OPTIMAL') {
    if (isSameMove) {
      return {
        title: 'Repeated Strong Game Decision 🎯',
        message: 'You found the best move, repeating your strong game decision!',
      };
    }
    return {
      title: 'Found Alternative Optimal Move ✨',
      message: `You found an alternative optimal move (${userMove})! Your original game decision (${movePlayed}) was also optimal.`,
    };
  }
  if (userQuality === 'ACCEPTABLE') {
    return {
      title: 'Acceptable Alternative',
      message: `You found an acceptable alternative (${userMove}), though your original game decision (${movePlayed}) was the best move.`,
    };
  }
  return {
    title: 'Game Decision Was Better ⚠️',
    message: `That move is inaccurate. In your original game you found the best move (${movePlayed})!`,
  };
}

function getAcceptableFeedback(
  userQuality: DecisionQuality,
  isSameMove: boolean,
  userMove: string,
  movePlayed: string,
) {
  if (userQuality === 'OPTIMAL') {
    return {
      title: 'Decision Improved 🚀',
      message: `You improved upon your game decision! In the game you played ${movePlayed}, but here you found the best move ${userMove}.`,
    };
  }
  if (userQuality === 'ACCEPTABLE') {
    if (isSameMove) {
      return {
        title: 'Replicated Acceptable Move 👍',
        message: `You repeated your acceptable game move (${movePlayed}).`,
      };
    }
    return {
      title: 'Found Alternative Move 👍',
      message: `You found an alternative acceptable move (${userMove}). In the game you played ${movePlayed}.`,
    };
  }
  return {
    title: 'Inaccurate Move ❌',
    message: `You tried a different move (${userMove}), but it is inaccurate. In the game you played ${movePlayed}.`,
  };
}

function getInferiorFeedback(params: {
  userQuality: DecisionQuality;
  isSameMove: boolean;
  userMove: string;
  movePlayed: string;
  decisionTimeMs: number;
  targetMove?: string | null;
}) {
  const { userQuality, isSameMove, userMove, movePlayed, decisionTimeMs, targetMove } = params;
  if (userQuality === 'OPTIMAL') {
    const hasValidDecisionTime =
      typeof decisionTimeMs === 'number' && Number.isFinite(decisionTimeMs) && decisionTimeMs > 0;
    const timeSnippet = hasValidDecisionTime ? ` (${formatTimerMs(decisionTimeMs)})` : '';
    return {
      title: 'Decision Improved 🚀',
      message: `You improved upon your game decision! In the game you played ${movePlayed}${timeSnippet}, but in practice you found the best move ${userMove}.`,
    };
  }
  if (userQuality === 'ACCEPTABLE') {
    return {
      title: 'Better Alternative Found 👍',
      message: `You found an acceptable alternative (${userMove}), improving upon your game mistake (${movePlayed}).`,
    };
  }
  if (isSameMove) {
    return {
      title: 'Game Blunder Repeated ⚠️',
      message: `You repeated your game mistake (${movePlayed}). The best move was ${targetMove || 'a stronger continuation'}.`,
    };
  }
  return {
    title: 'Inaccurate Move ❌',
    message: `You tried a different move (${userMove}), but it is also inaccurate. In the game you played ${movePlayed}; the best move was ${targetMove || 'a stronger continuation'}.`,
  };
}

function getComparisonFeedback(params: {
  originalQuality: DecisionQuality;
  userQuality: DecisionQuality;
  isSameMove: boolean;
  userMove: string;
  movePlayed: string;
  decisionTimeMs: number;
  targetMove?: string | null;
}) {
  switch (params.originalQuality) {
    case 'OPTIMAL':
      return getOptimalFeedback(params.userQuality, params.isSameMove, params.userMove, params.movePlayed);
    case 'ACCEPTABLE':
      return getAcceptableFeedback(params.userQuality, params.isSameMove, params.userMove, params.movePlayed);
    case 'INFERIOR':
      return getInferiorFeedback(params);
  }
}

function computeDurationComparison(
  attemptElapsedMs?: number | null,
  decisionTimeMs?: number | null,
): string | undefined {
  const hasValidAttemptElapsed =
    typeof attemptElapsedMs === 'number' && Number.isFinite(attemptElapsedMs) && attemptElapsedMs > 0;
  const hasValidDecisionTime =
    typeof decisionTimeMs === 'number' && Number.isFinite(decisionTimeMs) && decisionTimeMs > 0;

  if (hasValidAttemptElapsed && hasValidDecisionTime) {
    return `Solved in ${(attemptElapsedMs / 1000).toFixed(1)}s vs. original game thinking time of ${formatTimerMs(decisionTimeMs)}.`;
  }
  return undefined;
}

/**
 * Evaluates Decision Timing post-attempt feedback across 3 independent orthogonal dimensions:
 * 1. Original move quality (OPTIMAL, ACCEPTABLE, INFERIOR)
 * 2. User move quality (OPTIMAL, ACCEPTABLE, INFERIOR)
 * 3. Move identity (Same canonical move vs Different canonical move)
 *
 * All moves are normalized against the immutable reference preMoveFen.
 */
export function evaluateDecisionTimingComparison(options: {
  preMoveFen: string;
  targetMove?: string | null;
  acceptableMoves?: AcceptableMove[];
  context?: LongDecisionContext | null;
  userMove?: string | null;
  isCorrect: boolean;
  attemptElapsedMs?: number | null;
}): DecisionTimingComparisonResult | null {
  const {
    preMoveFen,
    targetMove,
    acceptableMoves = [],
    context,
    userMove,
    isCorrect,
    attemptElapsedMs,
  } = options;

  if (!context || !userMove) {
    return null;
  }

  const durationComparison = computeDurationComparison(attemptElapsedMs, context.decisionTimeMs);

  const userCanonical = toCanonicalMove(preMoveFen, userMove);
  const bestCanonical = targetMove ? toCanonicalMove(preMoveFen, targetMove) : null;
  const origCanonical = toCanonicalMove(preMoveFen, context.movePlayed);

  if (!origCanonical) {
    return {
      title: 'Comparison Indeterminate',
      message: `Original game move (${context.movePlayed}) could not be verified on this position.`,
      durationComparison,
      isIndeterminate: true,
    };
  }

  const originalQuality = determineOriginalQuality(preMoveFen, origCanonical, bestCanonical, acceptableMoves);
  const userQuality = determineUserQuality(userCanonical, bestCanonical, isCorrect);
  const isSameMove = areMovesEqual(userCanonical, origCanonical);

  const feedback = getComparisonFeedback({
    originalQuality,
    userQuality,
    isSameMove,
    userMove,
    movePlayed: context.movePlayed,
    decisionTimeMs: context.decisionTimeMs,
    targetMove,
  });

  return {
    title: feedback.title,
    message: feedback.message,
    durationComparison,
    isIndeterminate: false,
    originalQuality,
    userQuality,
    isSameMove,
  };
}
