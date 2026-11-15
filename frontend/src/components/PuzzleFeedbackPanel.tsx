'use client';

import React, { useEffect } from 'react';
import {
  AlertTriangle,
  XCircle,
  ExternalLink,
  HelpCircle,
  Flame,
  Trophy,
  ChevronLeft,
  ChevronRight,
  Settings2,
  Sparkles,
  LogOut,
  Timer,
  EyeOff,
  Clock,
  RotateCcw,
  AlertCircle,
  Loader2,
} from 'lucide-react';
import { Puzzle, isWeaknessBacked } from '@/types/puzzle';
import { HistoricalGamesModal } from './HistoricalGamesModal';
import {
  LineExplorationWorkbench,
  formatCalculationLine,
  type ChallengeSubmissionResult,
} from './LineExplorationWorkbench';
import { isEvaluatedFeedback } from '@/hooks/useLineExploration';
import { ContinuationMode, ContinuationCandidate, ExplorationPlayMode } from '../services/api';
import type { PuzzleAttemptCountState } from '../hooks/usePuzzleAttemptCount';
import {
  formatDecimal,
  formatPawnLoss,
  formatTimerMs,
} from '@/utils/formatters';
import { evaluateDecisionTimingComparison } from '@/utils/decisionTimingComparison';

export {
  formatDecimal,
  formatTimerMs,
  formatCalculationLine,
  type ChallengeSubmissionResult,
};

export interface FeedbackState {
  status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING' | 'EVALUATING' | 'EVALUATION_ERROR';
  lastMove?: string;
  historicalInfo?: { timesPlayed: number; averageLoss: number };
  evalLoss?: number;
  errorMessage?: string;
  onRetry?: () => void;
  attemptElapsedMs?: number;
}

interface PuzzleFeedbackPanelProps {
  attemptCountState?: PuzzleAttemptCountState;
  attemptRecording?: boolean;
  pendingAttempts?: Array<{ id: string; retryable: boolean }>;
  onRetryAttempt?: (id: string) => void;
  onReloadAttemptCount?: () => void;
  puzzle: Puzzle;
  feedback: FeedbackState;
  onPreviousPuzzle?: () => void;
  onNextPuzzle: () => void;
  // Settings props
  puzzleColorFilter: 'BOTH' | 'WHITE' | 'BLACK';
  onColorFilterChange: (color: 'BOTH' | 'WHITE' | 'BLACK') => void;
  showPuzzleSettings: boolean;
  onTogglePuzzleSettings: () => void;
  minMistakeCount: number;
  onMinMistakeCountChange: (count: number) => void;
  onApplySettings: () => void;
  username?: string;
  // Continuation & Exploration props
  isExplorationActive?: boolean;
  explorationDecisionMove?: string | null;
  onEnterExploration?: (initialMode?: ExplorationPlayMode, decisionMove?: string) => void;
  onExitExploration?: () => void;
  onEnterBlindfold?: () => void;
  isBlindfoldEntryDisabled?: boolean;
  continuationMode?: ContinuationMode;
  onContinuationModeChange?: (mode: ContinuationMode) => void;
  opponentRatingBand?: string;
  onOpponentRatingBandChange?: (band: string) => void;
  explorationPlayMode?: ExplorationPlayMode;
  onExplorationPlayModeChange?: (mode: ExplorationPlayMode) => void;
  continuationCandidate?: ContinuationCandidate | null;
  isContinuationLoading?: boolean;
  sideToMove?: 'White' | 'Black';
  unacceptableMoveMessage?: string | null;
  explorationFeedback?: { message: string, type: 'best' | 'good' } | null;
  lastContinuationCandidates?: {
    parentFen: string;
    candidates: ContinuationCandidate[];
    selected: ContinuationCandidate;
  } | null;
  onAlternativeSelected?: (candidate: ContinuationCandidate) => void;
  challengeCandidates?: ContinuationCandidate[];
  challengeSubmission?: ChallengeSubmissionResult;
  challengeInput?: string;
  onChallengeInputChange?: (val: string) => void;
  onChallengeSubmit?: (e: React.FormEvent) => void;
  onChallengeCandidateSelect?: (san: string) => void;
  challengeFeedback?: { message: string, type: 'success' | 'error' | 'info' } | null;
  isChallengeLoading?: boolean;
  activeChallengeCandidate?: string | null;
  challengeBranches?: Record<string, { san: string, fenAfter: string, isWhite: boolean }[]>;
  onBackToCandidates?: () => void;
  calculationInput?: string;
  calculationFeedback?: { type: 'success' | 'error', message: string } | null;
  isCalculationLoading?: boolean;
  puzzleComplete?: boolean;
  hasMorePuzzles?: boolean;
  onCalculationInputChange?: (val: string) => void;
  onCalculationSubmit?: (e: React.FormEvent) => void;
  onCalculationBack?: () => void;
  onFinishChallenge?: () => void;
  // Timed training props
  timerMode?: 'STOPWATCH' | 'COUNTDOWN' | null;
  timerElapsedMs?: number;
  timerAllowedMs?: number;
  timerExpired?: boolean;
  timerSubmissionError?: string | null;
  onTimerModeChange?: (mode: 'STOPWATCH' | 'COUNTDOWN' | null) => void;
  onTimerAllowedMsChange?: (ms: number) => void;
}

export const PuzzleFeedbackPanel: React.FC<PuzzleFeedbackPanelProps> = ({
  attemptCountState = { status: 'hidden' },
  attemptRecording = false,
  pendingAttempts = [],
  onRetryAttempt,
  onReloadAttemptCount,
  puzzle,
  feedback,
  onPreviousPuzzle,
  onNextPuzzle,
  puzzleColorFilter,
  onColorFilterChange,
  showPuzzleSettings,
  onTogglePuzzleSettings,
  minMistakeCount,
  onMinMistakeCountChange,
  onApplySettings,
  username,
  isExplorationActive = false,
  explorationDecisionMove,
  onEnterExploration,
  onExitExploration,
  onEnterBlindfold,
  isBlindfoldEntryDisabled = false,
  explorationPlayMode,
  onExplorationPlayModeChange,
  sideToMove,
  continuationCandidate,
  isContinuationLoading = false,
  unacceptableMoveMessage,
  explorationFeedback,
  lastContinuationCandidates,
  onAlternativeSelected,
  challengeCandidates = [],
  challengeSubmission,
  challengeInput = '',
  onChallengeInputChange,
  onChallengeSubmit,
  onChallengeCandidateSelect,
  challengeFeedback,
  isChallengeLoading = false,
  activeChallengeCandidate = null,
  challengeBranches = {},
  onBackToCandidates,
  onFinishChallenge,
  calculationInput = '',
  onCalculationInputChange,
  onCalculationSubmit,
  calculationFeedback,
  isCalculationLoading = false,
  onCalculationBack,
  timerMode = null,
  timerElapsedMs = 0,
  timerAllowedMs = 30000,
  timerExpired = false,
  timerSubmissionError = null,
  onTimerModeChange,
  onTimerAllowedMsChange,
}) => {
  const [showGameModal, setShowGameModal] = React.useState<boolean>(false);
  const weaknessBacked = isWeaknessBacked(puzzle);
  const isWeaknessPlaylist = puzzle.source === 'weakness' || (!puzzle.source && weaknessBacked);
  const isLongDecision = puzzle.source === 'long-decision';
  const isSimilarPosition = puzzle.source === 'similar';
  const isSuppliedPosition = puzzle.source === 'supplied';

  const promptColor = puzzle.playerColor === 'BLACK' ? "Black's" : "White's";
  const activeColorToMove = sideToMove || (puzzle.playerColor === 'BLACK' ? 'Black' : 'White');

  const renderBlindfoldEntry = (className: string, iconClassName: string) => onEnterBlindfold && (
    <button
      type="button"
      onClick={onEnterBlindfold}
      disabled={isBlindfoldEntryDisabled}
      title={isBlindfoldEntryDisabled ? 'Available once the board settles' : 'Continue this position blindfolded'}
      className={`${className} disabled:opacity-50 disabled:cursor-not-allowed`}
    >
      <EyeOff className={iconClassName} />
      <span>Train Blindfold</span>
    </button>
  );

  // Listen for Enter key when puzzle is solved to advance to next puzzle
  useEffect(() => {
    if (feedback.status !== 'CORRECT') return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        onNextPuzzle();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [feedback.status, onNextPuzzle]);

  return (
    <div
      data-testid="puzzle-feedback-panel"
      data-feedback-status={feedback.status}
      data-interactive={String(feedback.status !== 'EVALUATING')}
      className="flex flex-col space-y-3 bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl text-slate-200"
    >
      {/* Collapsible Puzzle Settings Section (Universal across all position sources) */}
      <div className="border-b border-slate-800 pb-3">
          <button
            type="button"
            onClick={onTogglePuzzleSettings}
            className="w-full flex items-center justify-between text-xs font-bold text-slate-400 hover:text-emerald-400 transition cursor-pointer"
            aria-expanded={showPuzzleSettings}
          >
            <div className="flex items-center gap-1.5">
              <Settings2 className="w-3.5 h-3.5" />
              <span>Puzzle Settings</span>
            </div>
            <span className="text-[10px] font-normal text-slate-500">
              {showPuzzleSettings ? 'Hide ▲' : 'Show ▼'}
            </span>
          </button>

          {showPuzzleSettings && (
            <div className="mt-3 space-y-3">
              {/* Color Filter */}
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">
                  Color
                </span>
                <div className="flex bg-slate-950 p-0.5 rounded-xl border border-slate-800">
                  {(['BOTH', 'WHITE', 'BLACK'] as const).map((color) => (
                    <button
                      key={color}
                      type="button"
                      onClick={() => onColorFilterChange(color)}
                      className={`px-2.5 py-0.5 text-[11px] font-bold rounded-lg transition cursor-pointer ${
                        puzzleColorFilter === color
                          ? 'bg-emerald-600 text-white shadow-sm'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      {color === 'BOTH' ? 'Both' : color === 'WHITE' ? 'White' : 'Black'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Min Mistakes + Apply */}
              <div className="flex items-center gap-2 bg-slate-950 px-3 py-2 rounded-xl border border-slate-800">
                <label className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-300 flex-1">
                  <span>Min Mistakes:</span>
                  <input
                    type="number"
                    step="1"
                    min="1"
                    value={minMistakeCount}
                    onChange={(e) => onMinMistakeCountChange(Number(e.target.value))}
                    aria-label="Min Mistakes"
                    className="w-14 bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px] outline-none focus:border-emerald-500"
                  />
                </label>
                <button
                  type="button"
                  onClick={onApplySettings}
                  className="px-2.5 py-1 bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-bold rounded-md transition cursor-pointer"
                >
                  Apply
                </button>
              </div>

              {/* Timed Training */}
              <div className="flex flex-col gap-2 bg-slate-950 px-3 py-2 rounded-xl border border-slate-800">
                <div className="flex items-center justify-between">
                  <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">
                    Timed Training
                  </span>
                  <div className="flex bg-slate-900 p-0.5 rounded-xl border border-slate-800">
                    {(
                      [
                        { key: null, label: 'Off' },
                        { key: 'STOPWATCH', label: 'Stopwatch' },
                        { key: 'COUNTDOWN', label: 'Countdown' },
                      ] as const
                    ).map((option) => (
                      <button
                        key={option.label}
                        type="button"
                        onClick={() => onTimerModeChange?.(option.key)}
                        className={`px-2 py-0.5 text-[11px] font-bold rounded-lg transition cursor-pointer ${
                          timerMode === option.key
                            ? 'bg-emerald-600 text-white shadow-sm'
                            : 'text-slate-400 hover:text-slate-200'
                        }`}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                </div>
                {timerMode === 'COUNTDOWN' && (
                  <label className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-300">
                    <span>Allowed time:</span>
                    <select
                      value={timerAllowedMs}
                      onChange={(e) => onTimerAllowedMsChange?.(Number(e.target.value))}
                      aria-label="Countdown allowed time"
                      className="bg-slate-900 border border-slate-800 rounded px-1.5 py-0.5 text-emerald-400 font-mono text-[11px] outline-none focus:border-emerald-500"
                    >
                      <option value={15000}>15s</option>
                      <option value={30000}>30s</option>
                      <option value={60000}>60s</option>
                      <option value={120000}>120s</option>
                    </select>
                  </label>
                )}
                {timerSubmissionError && (
                  <span className="text-[11px] font-semibold text-red-400">
                    {timerSubmissionError}
                  </span>
                )}
              </div>
            </div>
          )}
        </div>

      {/* Context-Aware Header */}
      {isLongDecision ? (
        <div className="border-b border-slate-800 pb-2.5 flex items-center justify-between gap-2">
          <div className="space-y-1">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-xs font-bold text-amber-400 uppercase tracking-wider">
                Decision Timing
              </span>
              {puzzle.longDecisionContext?.timeControl && (
                <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 border border-slate-700">
                  {puzzle.longDecisionContext.timeControl}
                </span>
              )}
              {puzzle.longDecisionContext?.severity && (
                <span
                  className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${
                    puzzle.longDecisionContext.severity === 'CRITICAL'
                      ? 'bg-rose-950/70 border-rose-800 text-rose-300'
                      : puzzle.longDecisionContext.severity === 'SIGNIFICANT'
                      ? 'bg-amber-950/70 border-amber-800 text-amber-300'
                      : 'bg-emerald-950/70 border-emerald-800 text-emerald-300'
                  }`}
                >
                  {puzzle.longDecisionContext.severity}
                </span>
              )}
            </div>
            {isEvaluatedFeedback(feedback) && puzzle.longDecisionContext ? (
              <div className="text-sm font-semibold text-white flex items-center gap-2 flex-wrap">
                <span>
                  Played:{' '}
                  <span className="font-bold text-emerald-300 font-mono">
                    {puzzle.longDecisionContext?.movePlayed || '—'}
                  </span>
                </span>
                {puzzle.longDecisionContext?.decisionTimeMs != null && (
                  <span className="text-xs text-slate-400 font-normal">
                    ({formatTimerMs(puzzle.longDecisionContext.decisionTimeMs)})
                  </span>
                )}
              </div>
            ) : (
              <p className="text-xs text-slate-400">
                Critical game moment — find the best continuation under clock pressure.
              </p>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {puzzle.longDecisionContext?.gameUrl && (
              <a
                href={puzzle.longDecisionContext.gameUrl}
                target="_blank"
                rel="noreferrer"
                className="text-xs font-semibold text-slate-400 hover:text-emerald-400 flex items-center gap-1.5 transition cursor-pointer shrink-0"
              >
                <span>Open game</span>
                <ExternalLink className="w-3.5 h-3.5" />
              </a>
            )}
            {!isExplorationActive && onExitExploration && (
              <button
                type="button"
                onClick={onExitExploration}
                title="Exit Exploration"
                className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer shrink-0"
              >
                <LogOut className="w-3.5 h-3.5 text-slate-400" />
                <span>Exit</span>
              </button>
            )}
          </div>
        </div>
      ) : isSimilarPosition ? (
        <div className="border-b border-slate-800 pb-2.5 flex items-center justify-between gap-2">
          <div>
            <span className="text-xs font-bold text-indigo-400 uppercase tracking-wider">
              Similar Position Exploration
            </span>
            <h2 className="text-base font-bold text-white mt-0.5">
              Matching Structure Practice
            </h2>
          </div>
          {!isExplorationActive && onExitExploration && (
            <button
              type="button"
              onClick={onExitExploration}
              title="Exit Exploration"
              className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer shrink-0"
            >
              <LogOut className="w-3.5 h-3.5 text-slate-400" />
              <span>Exit</span>
            </button>
          )}
        </div>
      ) : isSuppliedPosition ? (
        <div className="border-b border-slate-800 pb-2.5 flex items-center justify-between gap-2">
          <div>
            <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">
              Supplied Position Exploration
            </span>
            <h2 className="text-base font-bold text-white mt-0.5">
              Custom Position
            </h2>
          </div>
          {!isExplorationActive && onExitExploration && (
            <button
              type="button"
              onClick={onExitExploration}
              title="Exit Exploration"
              className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer shrink-0"
            >
              <LogOut className="w-3.5 h-3.5 text-slate-400" />
              <span>Exit</span>
            </button>
          )}
        </div>
      ) : (
        <div className="border-b border-slate-800 pb-2.5 flex items-center justify-between gap-2">
          <div>
            <span className="text-xs font-bold text-emerald-400 uppercase tracking-wider">
              Target Opening Weakness
            </span>
            <h2 className="text-base font-bold text-white mt-0.5">
              {puzzle.openingTitle}
            </h2>
          </div>
          {puzzle.gameUrls && puzzle.gameUrls.length > 0 && (
            <button
              type="button"
              onClick={() => setShowGameModal(true)}
              className="text-xs font-semibold text-slate-400 hover:text-emerald-400 flex items-center gap-1.5 transition cursor-pointer shrink-0"
            >
              <span>View Games ({puzzle.gameUrls.length})</span>
              <ExternalLink className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      )}

      {/* Timed Training live badge */}
      {Boolean(timerMode) && (
        <div
          className={`flex items-center justify-center gap-1.5 py-1.5 rounded-xl border text-sm font-mono font-bold ${
            timerExpired
              ? 'bg-red-950/60 border-red-800 text-red-400'
              : 'bg-slate-950 border-slate-800 text-emerald-400'
          }`}
        >
          <Timer className="w-3.5 h-3.5" />
          <span>{formatTimerMs(timerElapsedMs)}</span>
          {timerMode === 'COUNTDOWN' && (
            <span className="text-slate-500 font-sans font-normal text-[11px]">
              / {formatTimerMs(timerAllowedMs)}
            </span>
          )}
          {timerExpired && <span className="ml-1 text-[11px]">Time&apos;s up</span>}
        </div>
      )}

      {/* Dynamic Feedback / Success Card */}
      {!isExplorationActive && (
        feedback.status === 'EVALUATING' ? (
          <div className="p-3.5 bg-slate-950/80 border border-indigo-500/40 rounded-2xl space-y-2 text-xs text-indigo-300 animate-pulse">
            <div className="flex items-center space-x-2 font-bold text-indigo-200">
              <Loader2 className="w-4 h-4 animate-spin text-indigo-400" />
              <span>Evaluating move...</span>
            </div>
            <p className="text-slate-400">
              Analyzing move against engine evaluations. Board interactions are temporarily paused.
            </p>
          </div>
        ) : feedback.status === 'EVALUATION_ERROR' ? (
          <div className="p-3.5 bg-rose-950/40 border border-rose-500/50 rounded-2xl space-y-2 text-xs text-rose-300">
            <div className="flex items-center space-x-2 font-bold text-rose-200">
              <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
              <span>Move Evaluation Failed</span>
            </div>
            <p className="text-slate-300">
              {feedback.errorMessage || 'Unable to evaluate move with the engine. Check your connection.'}
            </p>
            {feedback.onRetry && (
              <div className="pt-1">
                <button
                  type="button"
                  onClick={feedback.onRetry}
                  className="px-3 py-1.5 bg-rose-600 hover:bg-rose-500 text-white font-bold rounded-lg transition cursor-pointer flex items-center gap-1.5"
                >
                  <RotateCcw className="w-3.5 h-3.5" />
                  <span>Retry evaluation</span>
                </button>
              </div>
            )}
          </div>
        ) : feedback.status === 'CORRECT' ? (
        <div className="p-3.5 bg-gradient-to-br from-emerald-950/80 to-slate-900 border-2 border-emerald-500/50 rounded-2xl space-y-2.5 shadow-lg shadow-emerald-950/40 animate-in fade-in duration-200">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-emerald-500/20 border border-emerald-500/40 flex items-center justify-center shrink-0">
              <Trophy className="w-4 h-4 text-emerald-400 animate-bounce" />
            </div>
            <div>
              <h4 className="font-bold text-sm text-emerald-200">
                {feedback.lastMove === puzzle.targetMove ? 'Puzzle Solved! 🎉' : 'Good Move! 👍'}
              </h4>
              <p className="text-xs text-emerald-300/90">
                {feedback.lastMove === puzzle.targetMove ? (
                  <>
                    <span className="font-bold text-white">{feedback.lastMove}</span> is the best move!
                  </>
                ) : (
                  <>
                    <span className="font-bold text-white">{feedback.lastMove}</span> is an acceptable alternative! ({puzzle.targetMove ? <><span className="font-bold text-white">{puzzle.targetMove}</span> is #1 best</> : 'stronger move available'})
                  </>
                )}
              </p>
            </div>
          </div>

          {/* Decision Timing Post-Attempt Evaluation and Comparison */}
          {isLongDecision && puzzle.longDecisionContext && (() => {
            const comparison = evaluateDecisionTimingComparison({
              preMoveFen: puzzle.fen,
              targetMove: puzzle.targetMove,
              acceptableMoves: puzzle.acceptableMoves,
              context: puzzle.longDecisionContext,
              userMove: feedback.lastMove,
              isCorrect: true,
              attemptElapsedMs: feedback.attemptElapsedMs ?? timerElapsedMs,
            });
            if (!comparison) return null;
            return (
              <div className="bg-slate-950/70 border border-amber-500/30 rounded-xl p-3 space-y-2 text-xs text-amber-300">
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-2 font-bold text-amber-200">
                    <Clock className="w-4 h-4 text-amber-400 shrink-0" />
                    <span>{comparison.title}</span>
                  </div>
                  {comparison.originalQuality && (
                    <span className="text-[10px] font-semibold bg-amber-500/20 text-amber-300 px-2 py-0.5 rounded-full border border-amber-500/20">
                      Game: {puzzle.longDecisionContext.movePlayed} ({comparison.originalQuality.toLowerCase()})
                    </span>
                  )}
                </div>
                <p className="text-amber-200/90 leading-relaxed font-medium">
                  {comparison.message}
                </p>
                {comparison.durationComparison && (
                  <p className="text-slate-400 text-[11px] font-mono border-t border-slate-800/80 pt-1.5">
                    {comparison.durationComparison}
                  </p>
                )}
              </div>
            );
          })()}

          {/* Historical Mistakes List Displayed Even on Correct Move */}
          {puzzle.movesPlayed && puzzle.movesPlayed.length > 0 && (
            <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl p-3 space-y-2 text-xs text-amber-300">
              <div className="flex items-center justify-between">
                <div className="flex items-center space-x-2 font-bold text-amber-200">
                  <AlertTriangle className="w-4 h-4 text-amber-400 shrink-0" />
                  <span>Your Decisions in Source Games</span>
                </div>
                <span className="text-[10px] font-semibold bg-amber-500/20 text-amber-300 px-2 py-0.5 rounded-full border border-amber-500/20">
                  {puzzle.mistakeCount} total errors
                </span>
              </div>

              <p className="text-amber-300/80 leading-relaxed">
                In your source games, you played these sub-optimal decisions in this position:
              </p>

              <div className="grid grid-cols-1 gap-1.5 pt-0.5">
                {puzzle.movesPlayed.map((mistake, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between bg-slate-950/50 hover:bg-slate-950/80 px-2.5 py-1 rounded-lg border border-amber-500/20 transition"
                  >
                    <div className="flex items-center space-x-2 font-mono">
                      <span className="font-bold text-rose-400 bg-rose-500/10 px-1.5 py-0.5 rounded border border-rose-500/20">
                        {mistake.move}
                      </span>
                      <span className="text-slate-400 text-[11px]">
                        ({mistake.timesPlayed} {mistake.timesPlayed === 1 ? 'game' : 'games'})
                      </span>
                    </div>
                    <div className="text-amber-400/90 font-mono text-[11px] font-semibold">
                      -{formatPawnLoss(mistake.averageLoss)} pawns
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {!isExplorationActive && (onEnterExploration || onEnterBlindfold) && (
            <div data-testid="exploration-actions" className="flex items-center space-x-2 pt-1">
              {onEnterExploration && (
                <button
                  type="button"
                  onClick={() => onEnterExploration()}
                  className="flex-1 py-2.5 bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold text-xs rounded-xl transition border border-amber-500/30 flex items-center justify-center space-x-1.5 cursor-pointer shadow-sm"
                >
                  <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                  <span>Continue Exploration →</span>
                </button>
              )}
              {renderBlindfoldEntry(
                'flex-1 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer',
                'w-3.5 h-3.5 text-slate-300',
              )}
            </div>
          )}
          {isWeaknessPlaylist ? (
            <div className="flex items-center space-x-2">
              {onPreviousPuzzle && (
                <button
                  onClick={onPreviousPuzzle}
                  className="flex-1 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer"
                >
                  <ChevronLeft className="w-4 h-4" />
                  <span>Prev Puzzle</span>
                </button>
              )}
              <button
                onClick={onNextPuzzle}
                className="flex-1 py-2.5 bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs rounded-xl transition shadow-lg shadow-emerald-900/50 flex items-center justify-center space-x-1.5 group cursor-pointer"
              >
                <span>Next Puzzle</span>
                <span className="text-[10px] font-normal text-emerald-200 bg-emerald-700/60 px-1.5 py-0.5 rounded">
                  Enter ↵
                </span>
                <ChevronRight className="w-4 h-4 group-hover:translate-x-0.5 transition" />
              </button>
            </div>
          ) : (
            onExitExploration && (
              <div className="flex items-center space-x-2">
                <button
                  type="button"
                  onClick={onExitExploration}
                  title="Exit Exploration"
                  className="w-full py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer"
                >
                  <LogOut className="w-3.5 h-3.5 text-slate-400" />
                  <span>Exit</span>
                </button>
              </div>
            )
          )}
        </div>
      ) : feedback.status === 'HISTORICAL_MISTAKE' ? (
        <>
          <div className="flex items-start space-x-3 p-3.5 bg-amber-500/15 border border-amber-500/40 rounded-xl text-amber-300 animate-in fade-in duration-200">
            <AlertTriangle className="w-5 h-5 text-amber-400 shrink-0 mt-0.5" />
            <div>
              <h4 className="font-bold text-sm text-amber-200">Recurring Weakness Detected!</h4>
              <p className="text-xs mt-0.5 text-amber-300/90">
                You played <span className="font-bold text-white">{feedback.lastMove}</span> in{' '}
                <span className="font-bold text-white">
                  {feedback.historicalInfo?.timesPlayed}{' '}
                  {feedback.historicalInfo?.timesPlayed === 1 ? 'game' : 'games'}
                </span>{' '}
                —{' '}
                <span className="font-bold text-white">
                  {formatPawnLoss(feedback.historicalInfo?.averageLoss)} pawns worse
                </span>{' '}
                than the best move. Try{' '}
                <span className="font-bold text-emerald-300">{puzzle.targetMove}</span> instead!
              </p>
            </div>
          </div>
          {!isExplorationActive && ((onEnterExploration && feedback.lastMove) || onEnterBlindfold) && (
            <div data-testid="exploration-actions" className="flex items-center space-x-2 mt-2">
              {onEnterExploration && feedback.lastMove && (
                <button
                  type="button"
                  onClick={() => onEnterExploration(undefined, feedback.lastMove)}
                  className="flex-1 py-2 bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold text-xs rounded-xl transition border border-amber-500/30 flex items-center justify-center space-x-1.5 cursor-pointer"
                >
                  <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                  <span>Explore this decision</span>
                </button>
              )}
              {renderBlindfoldEntry(
                'flex-1 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer',
                'w-3.5 h-3.5 text-slate-300',
              )}
            </div>
          )}
        </>
      ) : feedback.status === 'INCORRECT' ? (
        <>
          <div className="flex items-start space-x-3 p-3.5 bg-rose-500/15 border border-rose-500/40 rounded-xl text-rose-300 animate-in fade-in duration-200">
            <XCircle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
            <div>
              <h4 className="font-bold text-sm text-rose-200">Not the Recommended Move</h4>
              <p className="text-xs mt-0.5 text-rose-300/90">
                <span className="font-bold text-white">{feedback.lastMove}</span> is not the recommended move.
              </p>
            </div>
          </div>

          {/* Decision Timing Post-Attempt Evaluation and Comparison */}
          {isLongDecision && puzzle.longDecisionContext && (() => {
            const comparison = evaluateDecisionTimingComparison({
              preMoveFen: puzzle.fen,
              targetMove: puzzle.targetMove,
              acceptableMoves: puzzle.acceptableMoves,
              context: puzzle.longDecisionContext,
              userMove: feedback.lastMove,
              isCorrect: false,
              attemptElapsedMs: feedback.attemptElapsedMs ?? timerElapsedMs,
            });
            if (!comparison) return null;
            return (
              <div className="bg-slate-950/70 border border-amber-500/30 rounded-xl p-3 space-y-2 text-xs text-amber-300 mt-2">
                <div className="flex items-center justify-between">
                  <div className="flex items-center space-x-2 font-bold text-amber-200">
                    <Clock className="w-4 h-4 text-amber-400 shrink-0" />
                    <span>{comparison.title}</span>
                  </div>
                  {comparison.originalQuality && (
                    <span className="text-[10px] font-semibold bg-amber-500/20 text-amber-300 px-2 py-0.5 rounded-full border border-amber-500/20">
                      Game: {puzzle.longDecisionContext.movePlayed} ({comparison.originalQuality.toLowerCase()})
                    </span>
                  )}
                </div>
                <p className="text-amber-200/90 leading-relaxed font-medium">
                  {comparison.message}
                </p>
                {comparison.durationComparison && (
                  <p className="text-slate-400 text-[11px] font-mono border-t border-slate-800/80 pt-1.5">
                    {comparison.durationComparison}
                  </p>
                )}
              </div>
            );
          })()}

          {!isExplorationActive &&
            ((onEnterExploration && feedback.lastMove) ||
              onEnterBlindfold ||
              (!isWeaknessPlaylist && onExitExploration)) && (
              <div data-testid="exploration-actions" className="flex items-center space-x-2 mt-2">
                {onEnterExploration && feedback.lastMove && (
                  <button
                    type="button"
                    onClick={() => onEnterExploration(undefined, feedback.lastMove)}
                    className="flex-1 py-2 bg-rose-500/20 hover:bg-rose-500/30 text-rose-300 font-bold text-xs rounded-xl transition border border-rose-500/30 flex items-center justify-center space-x-1.5 cursor-pointer"
                  >
                    <Sparkles className="w-3.5 h-3.5 text-rose-400" />
                    <span>Explore this decision</span>
                  </button>
                )}
                {!isWeaknessPlaylist && onExitExploration && (
                  <button
                    type="button"
                    onClick={onExitExploration}
                    title="Exit Exploration"
                    className="px-3 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer"
                  >
                    <LogOut className="w-3.5 h-3.5 text-slate-400" />
                    <span>Exit</span>
                  </button>
                )}
                {renderBlindfoldEntry(
                  'flex-1 py-2 bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold text-xs rounded-xl transition border border-slate-700/60 flex items-center justify-center space-x-1.5 cursor-pointer',
                  'w-3.5 h-3.5 text-slate-300'
                )}
              </div>
            )}
        </>
      ) : (
        /* IDLE State: Unified Puzzle-Solving Prompt across all five entry points */
        <div className="flex items-center space-x-3 p-3 bg-slate-950/60 border border-slate-800/80 rounded-xl text-slate-400">
          <HelpCircle className="w-4 h-4 text-slate-400 shrink-0" />
          <p className="text-xs">
            {isLongDecision
              ? `Find ${promptColor} best move. Can you improve upon your game decision under timing pressure?`
              : isSimilarPosition
              ? `Find ${promptColor} best move or an acceptable alternative from this matching pawn structure.`
              : isSuppliedPosition
              ? `Find ${promptColor} best move in this position.`
              : `Find ${promptColor} best move or an acceptable alternative to fix your opening habit.`}
          </p>
        </div>
      ))}

      {/* Active Line Exploration Card (Renders ONLY when isExplorationActive is true) */}
      {isExplorationActive && (
        <LineExplorationWorkbench
          explorationDecisionMove={explorationDecisionMove}
          onExitExploration={onExitExploration}
          onEnterBlindfold={onEnterBlindfold}
          isBlindfoldEntryDisabled={isBlindfoldEntryDisabled}
          explorationPlayMode={explorationPlayMode}
          onExplorationPlayModeChange={onExplorationPlayModeChange}
          activeColorToMove={activeColorToMove}
          playerColor={puzzle.playerColor}
          unacceptableMoveMessage={unacceptableMoveMessage}
          isContinuationLoading={isContinuationLoading}
          explorationFeedback={explorationFeedback}
          continuationCandidate={continuationCandidate}
          activeChallengeCandidate={activeChallengeCandidate}
          challengeSubmission={challengeSubmission}
          challengeCandidates={challengeCandidates}
          challengeBranches={challengeBranches}
          onChallengeCandidateSelect={onChallengeCandidateSelect}
          onFinishChallenge={onFinishChallenge}
          challengeFeedback={challengeFeedback}
          challengeInput={challengeInput}
          onChallengeInputChange={onChallengeInputChange}
          onChallengeSubmit={onChallengeSubmit}
          isChallengeLoading={isChallengeLoading}
          calculationFeedback={calculationFeedback}
          calculationInput={calculationInput}
          onCalculationInputChange={onCalculationInputChange}
          onCalculationSubmit={onCalculationSubmit}
          isCalculationLoading={isCalculationLoading}
          onBackToCandidates={onBackToCandidates}
          onCalculationBack={onCalculationBack}
          lastContinuationCandidates={lastContinuationCandidates}
          onAlternativeSelected={onAlternativeSelected}
        />
      )}

      {attemptCountState.status !== 'hidden' && weaknessBacked && (
        <section aria-label="Your puzzle attempts" aria-live="polite" className="rounded-xl border border-slate-700 bg-slate-950 p-3 text-sm">
          {attemptCountState.status === 'loaded' ? (
            <div className="flex flex-wrap gap-x-6 gap-y-1 font-semibold">
              <p className="text-amber-300">Failed: {attemptCountState.failedCount}</p>
              <p className="text-emerald-300">Solved: {attemptCountState.solvedCount}</p>
            </div>
          ) : attemptCountState.status === 'loading' ? (
            <p className="text-slate-400">Loading your attempt counts...</p>
          ) : (
            <div className="flex items-center justify-between gap-2">
              <p className="text-amber-300">Your attempt counts are unavailable.</p>
              <button type="button" onClick={onReloadAttemptCount} className="text-xs font-semibold text-emerald-300 underline">
                Retry counts
              </button>
            </div>
          )}
          <p className="mt-1 text-xs text-slate-500">Your submitted answers to this puzzle.</p>
          {attemptRecording && <p className="mt-2 text-xs text-slate-400">Recording your answer...</p>}
          {pendingAttempts.map((attempt, index) => (
            <div key={attempt.id} className="mt-2 flex items-center justify-between gap-2 text-xs">
              <p className="text-amber-300">{attempt.retryable
                ? 'Could not confirm this answer was recorded.'
                : 'This answer could not be recorded. Reload to check your saved counts.'}</p>
              {attempt.retryable && (
                <button type="button" onClick={() => onRetryAttempt?.(attempt.id)}
                  aria-label={`Retry recording answer ${index + 1}`} className="shrink-0 font-semibold text-emerald-300 underline">
                  Retry answer
                </button>
              )}
            </div>
          ))}
        </section>
      )}

      {/* Stats Pill Badges */}
      {weaknessBacked && <div className="grid grid-cols-2 gap-2 py-0.5">
        <div className="bg-slate-950 p-2 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Mistake Rate</div>
          <div className="text-xs font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
            <Flame className="w-3.5 h-3.5 text-amber-500" />
            {formatDecimal(puzzle.mistakeRate, 1)}%
          </div>
        </div>

        <div className="bg-slate-950 p-2 rounded-xl border border-slate-800 text-center">
          <div className="text-[11px] font-semibold text-slate-400">Times Reached</div>
          <div className="text-xs font-bold text-slate-200 mt-0.5">
            {puzzle.timesReached} games
          </div>
        </div>
      </div>}

      {showGameModal && puzzle.gameUrls && puzzle.gameUrls.length > 0 && (
        <HistoricalGamesModal
          urls={puzzle.gameUrls}
          onClose={() => setShowGameModal(false)}
          username={username}
        />
      )}
    </div>
  );
};
