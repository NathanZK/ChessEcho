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
} from 'lucide-react';
import { Puzzle } from '../mock/mockData';
import { HistoricalGamesModal } from './HistoricalGamesModal';
import { ContinuationMode, ContinuationCandidate, ExplorationPlayMode } from '../services/api';

export function formatDecimal(val: number, decimals: number = 2): string {
  return (val ?? 0).toFixed(decimals).replace(',', '.');
}

interface FeedbackState {
  status: 'IDLE' | 'CORRECT' | 'HISTORICAL_MISTAKE' | 'INCORRECT' | 'EXPLORING';
  lastMove?: string;
  historicalInfo?: { timesPlayed: number; averageLoss: number };
}

export function formatCalculationLine(line: { san: string, isWhite: boolean }[]): string {
  let result = '';
  let moveNum = 1;
  for (let i = 0; i < line.length; i++) {
    const move = line[i];
    if (i === 0) {
      if (move.isWhite) {
        result += `${moveNum}. ${move.san}`;
      } else {
        result += `${moveNum}... ${move.san}`;
      }
    } else {
      if (move.isWhite) {
        moveNum++;
        result += ` ${moveNum}. ${move.san}`;
      } else {
        result += ` ${move.san}`;
      }
    }
  }
  return result;
}

export interface ChallengeSubmissionResult {
  foundCount: number;
  targetCount: number;
  isComplete: boolean;
  moves: {
    san: string;
    status: 'strong' | 'weak' | 'invalid';
    errorMsg?: string;
  }[];
}

interface PuzzleFeedbackPanelProps {
  puzzle: Puzzle;
  feedback: FeedbackState;
  moveHistory: string[];
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
  explorationTurn?: 'USER' | 'CHESSECHO';
  onEnterExploration?: () => void;
  onExitExploration?: () => void;
  continuationMode?: ContinuationMode;
  onContinuationModeChange?: (mode: ContinuationMode) => void;
  opponentRatingBand?: string;
  onOpponentRatingBandChange?: (band: string) => void;
  explorationPlayMode?: ExplorationPlayMode;
  onExplorationPlayModeChange?: (mode: ExplorationPlayMode) => void;
  continuationCandidate?: ContinuationCandidate | null;
  isContinuationLoading?: boolean;
  sideToMove?: 'White' | 'Black';
  effectiveProvider?: string | null;
  isContinuationFallback?: boolean;
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
  onStartExploration?: () => void;
  onChangePlayMode?: (mode: any) => void;
  onCalculationInputChange?: (val: string) => void;
  onCalculationSubmit?: (e: React.FormEvent) => void;
  onCalculationBack?: () => void;
  onContinueMilestone?: () => void;
  onFinishChallenge?: () => void;
}

export const PuzzleFeedbackPanel: React.FC<PuzzleFeedbackPanelProps> = ({
  puzzle,
  feedback,
  moveHistory,
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
  explorationTurn = 'USER',
  onEnterExploration,
  onExitExploration,
  continuationMode = 'ENGINE',
  onContinuationModeChange,
  opponentRatingBand,
  onOpponentRatingBandChange,
  explorationPlayMode,
  onExplorationPlayModeChange,
  sideToMove,
  continuationCandidate,
  effectiveProvider,
  isContinuationFallback = false,
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
  onContinueMilestone,
  onFinishChallenge,
  calculationInput = '',
  onCalculationInputChange,
  onCalculationSubmit,
  calculationFeedback,
  isCalculationLoading = false,
  onCalculationBack,
}) => {
  const [showGameModal, setShowGameModal] = React.useState<boolean>(false);

  const activeColorToMove = sideToMove || (puzzle.playerColor === 'BLACK' ? 'Black' : 'White');

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
    <div className="flex flex-col space-y-3 bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-xl text-slate-200">
      {/* Collapsible Puzzle Settings Section */}
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
          <span className="text-[10px] font-normal text-slate-500">{showPuzzleSettings ? 'Hide ▲' : 'Show ▼'}</span>
        </button>

        {showPuzzleSettings && (
          <div className="mt-3 space-y-3">
            {/* Color Filter */}
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-bold text-slate-400 uppercase tracking-wider">Color</span>
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
          </div>
        )}
      </div>

      {/* Opening Header */}
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

      {/* Dynamic Feedback / Success Card */}
      {feedback.status === 'CORRECT' ? (
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
                    <span className="font-bold text-white">{feedback.lastMove}</span> is an acceptable alternative! (<span className="font-bold text-white">{puzzle.targetMove}</span> is #1 best)
                  </>
                )}
              </p>
            </div>
          </div>

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
                      -{formatDecimal(mistake.averageLoss, 2)} pawns
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="flex items-center space-x-2 pt-1">
            {!isExplorationActive && onEnterExploration && (
              <button
                type="button"
                onClick={() => onEnterExploration()}
                className="flex-1 py-2.5 bg-amber-500/20 hover:bg-amber-500/30 text-amber-300 font-bold text-xs rounded-xl transition border border-amber-500/30 flex items-center justify-center space-x-1.5 cursor-pointer shadow-sm"
              >
                <Sparkles className="w-3.5 h-3.5 text-amber-400" />
                <span>Continue Exploration →</span>
              </button>
            )}
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
        </div>
      ) : feedback.status === 'HISTORICAL_MISTAKE' ? (
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
                {formatDecimal(feedback.historicalInfo?.averageLoss ?? 0, 2)} pawns worse
              </span>{' '}
              than the best move. Try{' '}
              <span className="font-bold text-emerald-300">{puzzle.targetMove}</span> instead!
            </p>
          </div>
        </div>
      ) : feedback.status === 'INCORRECT' ? (
        <div className="flex items-start space-x-3 p-3.5 bg-rose-500/15 border border-rose-500/40 rounded-xl text-rose-300 animate-in fade-in duration-200">
          <XCircle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
          <div>
            <h4 className="font-bold text-sm text-rose-200">Not the Recommended Move</h4>
            <p className="text-xs mt-0.5 text-rose-300/90">
              <span className="font-bold text-white">{feedback.lastMove}</span> is not the recommended move.
            </p>
          </div>
        </div>
      ) : (
        <div className="flex items-center space-x-3 p-3 bg-slate-950/60 border border-slate-800/80 rounded-xl text-slate-400">
          <HelpCircle className="w-4 h-4 text-slate-400 shrink-0" />
          <p className="text-xs">
            Find {puzzle.playerColor === 'BLACK' ? "Black's" : "White's"} best move or an acceptable alternative to fix your opening habit.
          </p>
        </div>
      )}

      {/* Active Line Exploration Card (Renders ONLY when isExplorationActive is true) */}
      {isExplorationActive && (
        <div className="bg-slate-950 p-4 rounded-2xl border border-emerald-500/30 space-y-3.5 text-xs shadow-lg shadow-slate-950/80 animate-in fade-in duration-200">
          <div className="flex flex-col space-y-2.5">
            <div className="flex items-center justify-between border-b border-slate-800/80 pb-2.5">
              <div className="flex items-center gap-2 font-bold text-emerald-300">
                <Sparkles className="w-4 h-4 text-emerald-400" />
                <span className="text-xs font-bold text-white tracking-wider uppercase">Line Exploration</span>
              </div>

              {/* Exit Exploration Button */}
              {onExitExploration && (
                <button
                  type="button"
                  onClick={onExitExploration}
                  title="Exit Exploration"
                  className="flex items-center gap-1.5 px-2.5 py-1 bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-slate-200 font-bold text-[11px] rounded-lg transition border border-slate-800 cursor-pointer"
                >
                  <LogOut className="w-3 h-3 text-slate-400" />
                  <span>Exit</span>
                </button>
              )}
            </div>

            {!explorationPlayMode ? (
              /* Mode Selection Screen */
              <div className="space-y-3 py-1">
                <div>
                  <h3 className="text-xs font-bold text-slate-200 uppercase tracking-wider">Choose how you want to explore</h3>
                  <p className="text-[11px] text-slate-400 mt-0.5">Select a mode to begin exploring continuation lines.</p>
                </div>

                <div className="grid grid-cols-1 gap-2.5 pt-1">
                  <button
                    type="button"
                    onClick={() => onExplorationPlayModeChange?.('CHESSECHO')}
                    className="flex flex-col items-start p-3 bg-slate-900 hover:bg-slate-850 hover:border-emerald-500/50 rounded-xl border border-slate-800 transition text-left group cursor-pointer space-y-1"
                  >
                    <div className="flex items-center justify-between w-full">
                      <span className="font-bold text-xs text-slate-100 group-hover:text-emerald-400 transition flex items-center gap-1.5">
                        <span>🤖</span> vs ChessEcho
                      </span>
                      <span className="text-[10px] font-bold px-2 py-0.5 bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 rounded-md group-hover:bg-emerald-500/20 transition">
                        Select →
                      </span>
                    </div>
                    <p className="text-[11px] text-slate-400 leading-normal">
                      ChessEcho responds to your moves.
                    </p>
                  </button>

                  <button
                    type="button"
                    onClick={() => onExplorationPlayModeChange?.('BOTH_SIDES')}
                    className="flex flex-col items-start p-3 bg-slate-900 hover:bg-slate-850 hover:border-amber-500/50 rounded-xl border border-slate-800 transition text-left group cursor-pointer space-y-1"
                  >
                    <div className="flex items-center justify-between w-full">
                      <span className="font-bold text-xs text-slate-100 group-hover:text-amber-400 transition flex items-center gap-1.5">
                        <span>♟⇄♙</span> Play Both Sides
                      </span>
                      <span className="text-[10px] font-bold px-2 py-0.5 bg-amber-500/10 text-amber-400 border border-amber-500/20 rounded-md group-hover:bg-amber-500/20 transition">
                        Select →
                      </span>
                    </div>
                    <p className="text-[11px] text-slate-400 leading-normal">
                      You choose moves for both players.
                    </p>
                  </button>

                  <button
                    type="button"
                    onClick={() => onExplorationPlayModeChange?.('CHALLENGE')}
                    className="flex flex-col items-start p-3 bg-slate-900 hover:bg-slate-850 hover:border-purple-500/50 rounded-xl border border-slate-800 transition text-left group cursor-pointer space-y-1"
                  >
                    <div className="flex items-center justify-between w-full">
                      <span className="font-bold text-xs text-slate-100 group-hover:text-purple-400 transition flex items-center gap-1.5">
                        <span>🎯</span> Challenge Mode
                      </span>
                      <span className="text-[10px] font-bold px-2 py-0.5 bg-purple-500/10 text-purple-400 border border-purple-500/20 rounded-md group-hover:bg-purple-500/20 transition">
                        Select →
                      </span>
                    </div>
                    <p className="text-[11px] text-slate-400 leading-normal">
                      Find a strong candidate move. ChessEcho won't respond.
                    </p>
                  </button>
                </div>
              </div>
            ) : (
              /* Active Mode View Header Bar */
              <div className="flex flex-col space-y-2">
                <div className="flex items-center justify-between pt-0.5">
                  {/* Play Mode Selector: vs ChessEcho / Play Both Sides */}
                  {onExplorationPlayModeChange && (
                    <div className="flex bg-slate-900 p-0.5 rounded-lg border border-slate-800" role="group" aria-label="Exploration Mode">
                      <button
                        type="button"
                        onClick={() => onExplorationPlayModeChange('CHESSECHO')}
                        className={`px-2 py-0.5 text-[10px] font-bold rounded transition cursor-pointer ${
                          explorationPlayMode === 'CHESSECHO' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-400 hover:text-slate-200'
                        }`}
                      >
                        vs ChessEcho
                      </button>
                      <button
                        type="button"
                        onClick={() => onExplorationPlayModeChange('BOTH_SIDES')}
                        className={`px-2 py-0.5 text-[10px] font-bold rounded transition cursor-pointer ${
                          explorationPlayMode === 'BOTH_SIDES' ? 'bg-amber-600 text-white shadow-sm' : 'text-slate-400 hover:text-slate-200'
                        }`}
                      >
                        Play Both Sides
                      </button>
                      <button
                        type="button"
                        onClick={() => onExplorationPlayModeChange('CHALLENGE')}
                        className={`px-2 py-0.5 text-[10px] font-bold rounded transition cursor-pointer ${
                          explorationPlayMode === 'CHALLENGE' ? 'bg-purple-600 text-white shadow-sm' : 'text-slate-400 hover:text-slate-200'
                        }`}
                      >
                        Challenge Mode
                      </button>
                    </div>
                  )}

                  {/* Continuation Mode Selector (ENGINE / HUMAN) only visible in CHESSECHO mode */}
                  {explorationPlayMode === 'CHESSECHO' && onContinuationModeChange && (
                    <div className="flex gap-2">
                      <div className="flex bg-slate-900 p-0.5 rounded-lg border border-slate-800">
                        {(['ENGINE', 'HUMAN'] as const).map((m) => (
                          <button
                            key={m}
                            type="button"
                            onClick={() => onContinuationModeChange(m)}
                            className={`px-2 py-0.5 text-[10px] font-bold rounded transition cursor-pointer ${
                              continuationMode === m ? 'bg-blue-600 text-white shadow-sm' : 'text-slate-400 hover:text-slate-200'
                            }`}
                          >
                            {m}
                          </button>
                        ))}
                      </div>
                      
                      {continuationMode === 'HUMAN' && onOpponentRatingBandChange && (
                        <select 
                          className="bg-slate-900 text-slate-200 text-[10px] font-bold px-2 py-0.5 rounded-lg border border-slate-800 focus:outline-none focus:ring-1 focus:ring-blue-500"
                          value={opponentRatingBand}
                          onChange={(e) => onOpponentRatingBandChange(e.target.value)}
                        >
                          <option value="400-600">400-600</option>
                          <option value="600-800">600-800</option>
                          <option value="800-1000">800-1000</option>
                          <option value="1000-1200">1000-1200</option>
                          <option value="1200-1400">1200-1400</option>
                          <option value="1400-1600">1400-1600</option>
                          <option value="1600-1800">1600-1800</option>
                          <option value="1800-2000">1800-2000</option>
                          <option value="2000-2200">2000-2200</option>
                          <option value="2200+">2200+</option>
                        </select>
                      )}
                    </div>
                  )}
                </div>

                {/* Active Mode Body */}
                <div className="bg-slate-900 rounded-xl p-3 border border-slate-800/80 shadow-inner">
                  {/* When in Play Both Sides mode, show prominent indicator */}
                  {explorationPlayMode === 'BOTH_SIDES' && (
                    <div
                      data-testid="play-both-sides-badge"
                      className="flex items-center justify-between bg-slate-900/90 px-3 py-1.5 rounded-xl border border-amber-500/30 mb-2"
                    >
                      <span className="text-[10px] font-bold tracking-wider text-amber-400 uppercase flex items-center gap-1">
                        <span>♟⇄♙</span> Play Both Sides
                      </span>
                      <span className="text-xs font-semibold text-slate-200 flex items-center gap-1.5">
                        <span className={`w-2 h-2 rounded-full inline-block ${activeColorToMove === 'White' ? 'bg-white border border-slate-300' : 'bg-slate-900 border border-slate-500'}`} />
                        {activeColorToMove} to move
                      </span>
                    </div>
                  )}

          {unacceptableMoveMessage ? (
            <div className="flex items-center space-x-2 text-rose-300 py-2 px-3 bg-rose-500/10 rounded-xl border border-rose-500/30">
              <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
              <span className="font-semibold text-xs">{unacceptableMoveMessage}</span>
            </div>
          ) : isContinuationLoading && explorationPlayMode === 'CHESSECHO' ? (
            <div className="flex flex-col space-y-2">
              {explorationFeedback && (
                <div className={`flex items-start gap-2 p-2.5 rounded-xl text-xs border ${explorationFeedback.type === 'best' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' : 'bg-blue-500/10 border-blue-500/30 text-blue-300'}`}>
                  {explorationFeedback.type === 'best' ? <Trophy className="w-4 h-4 shrink-0 text-emerald-400 mt-0.5" /> : <Sparkles className="w-4 h-4 shrink-0 text-blue-400 mt-0.5" />}
                  <span className="font-medium leading-relaxed">{explorationFeedback.message}</span>
                </div>
              )}
              <div className="flex items-center space-x-2 text-slate-400 py-2 justify-center bg-slate-900/60 rounded-xl border border-slate-800">
                <div className="w-4 h-4 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                <span className="font-medium text-xs">ChessEcho is choosing a response...</span>
              </div>
            </div>
          ) : (
            <div className="flex flex-col space-y-2">
              {explorationFeedback && (
                <div className={`flex items-start gap-2 p-2.5 rounded-xl text-xs border ${explorationFeedback.type === 'best' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' : 'bg-blue-500/10 border-blue-500/30 text-blue-300'}`}>
                  {explorationFeedback.type === 'best' ? <Trophy className="w-4 h-4 shrink-0 text-emerald-400 mt-0.5" /> : <Sparkles className="w-4 h-4 shrink-0 text-blue-400 mt-0.5" />}
                  <span className="font-medium leading-relaxed">{explorationFeedback.message}</span>
                </div>
              )}

              <div className={`flex items-center justify-between bg-slate-900/60 px-3 py-2 rounded-xl border ${explorationPlayMode === 'CHALLENGE' ? 'border-purple-500/20' : 'border-emerald-500/20'}`}>
                <div className="flex items-center space-x-2">
                  <span className={`w-2 h-2 rounded-full ${explorationPlayMode === 'CHALLENGE' ? 'bg-purple-400' : 'bg-emerald-400'}`} />
                  <span className={`font-semibold text-xs ${explorationPlayMode === 'CHALLENGE' ? 'text-purple-300' : 'text-emerald-300'}`}>
                    {explorationPlayMode === 'CHALLENGE'
                      ? `Challenge Mode — find a strong candidate move for ${activeColorToMove}.`
                      : explorationPlayMode === 'BOTH_SIDES'
                        ? (activeColorToMove.toUpperCase() !== puzzle.playerColor
                          ? `Opponent's turn — think like ${activeColorToMove}. Find their strongest move.`
                          : `Your turn — find the best move for ${activeColorToMove}.`)
                        : 'Your turn — explore a move.'}
                  </span>
                </div>
                {explorationPlayMode === 'CHESSECHO' && continuationCandidate?.move && (
                  <span className="text-[11px] text-slate-400 font-mono">
                    (Last: {continuationCandidate.move})
                  </span>
                )}
              </div>

              {explorationPlayMode === 'CHALLENGE' && (
                <div className="pt-2 space-y-3">
                  {!activeChallengeCandidate ? (
                    // Candidate Discovery Phase
                    <>
                      <div className="flex items-center justify-between text-xs font-semibold text-purple-300">
                        <span>
                           {challengeSubmission ? (
                             challengeSubmission.isComplete
                               ? `Excellent. You found all ${challengeSubmission.targetCount} strong candidates.`
                               : `You found ${challengeSubmission.foundCount} / ${challengeSubmission.targetCount}. Keep looking.`
                           ) : (
                             `Find up to ${Math.min(challengeCandidates.length, 3)} strong candidate moves.`
                           )}
                        </span>
                      </div>

                      {challengeSubmission && challengeSubmission.moves.some(m => m.status === 'strong') && (
                        <div className="text-xs text-purple-200 font-bold mb-1">
                          Choose a candidate to calculate.
                        </div>
                      )}

                      {challengeSubmission && challengeSubmission.moves.length > 0 && (
                        <div className="flex flex-col space-y-1">
                          {challengeSubmission.moves.map((m, i) => {
                            const isExplored = m.status === 'strong' && challengeBranches && challengeBranches[m.san] && challengeBranches[m.san].length > 0;
                            return (
                              <button
                                key={i}
                                type="button"
                                onClick={() => {
                                  if (m.status === 'strong' && onChallengeCandidateSelect) {
                                    onChallengeCandidateSelect(m.san);
                                  }
                                }}
                                className={`flex items-center space-x-2 text-xs font-mono px-2 py-1 rounded border text-left ${
                                  m.status === 'strong'
                                    ? 'bg-emerald-900/20 border-emerald-500/20 text-emerald-200 cursor-pointer hover:bg-emerald-800/30 hover:border-emerald-500/40 transition-colors'
                                    : 'bg-red-900/20 border-red-500/20 text-red-200 cursor-default opacity-70'
                                }`}
                                disabled={m.status !== 'strong'}
                              >
                                <span>{m.status === 'strong' ? (isExplored ? '✓' : '○') : '✗'}</span>
                                <span>{m.san} — {m.status === 'strong' ? (isExplored ? 'explored' : 'not explored') : 'not strong enough'}</span>
                              </button>
                            );
                          })}
                        </div>
                      )}

                      {challengeSubmission && (
                        <div className="pt-2">
                          <button
                            onClick={onFinishChallenge}
                            className="w-full py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-lg transition"
                          >
                            Finish Challenge
                          </button>
                        </div>
                      )}

                      {challengeFeedback && (
                        <div className={`text-xs px-3 py-2 rounded-lg border ${
                          challengeFeedback.type === 'success' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' :
                          challengeFeedback.type === 'error' ? 'bg-rose-500/10 border-rose-500/30 text-rose-300' :
                          'bg-sky-500/10 border-sky-500/30 text-sky-300'
                        }`}>
                          {challengeFeedback.type === 'success' ? '✓ ' : ''}{challengeFeedback.message}
                        </div>
                      )}

                      {!(challengeSubmission?.isComplete) && challengeCandidates.length > 0 && (
                        <form onSubmit={onChallengeSubmit} className="flex flex-col space-y-2 mt-3 pt-3 border-t border-purple-500/20">
                          <textarea
                            value={challengeInput}
                            onChange={(e) => onChallengeInputChange?.(e.target.value)}
                            placeholder="Enter candidate moves (e.g. Bc4, Nf3, O-O)"
                            rows={3}
                            disabled={isChallengeLoading}
                            className="w-full bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-xs text-slate-200 focus:outline-none focus:border-purple-500 placeholder:text-slate-600"
                            autoComplete="off"
                          />
                          <button
                            type="submit"
                            disabled={!challengeInput.trim() || isChallengeLoading || challengeCandidates.length === 0}
                            className="w-full bg-purple-600 hover:bg-purple-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-bold py-2 rounded-lg transition"
                          >
                            {isChallengeLoading ? 'Submitting...' : 'Submit Candidates'}
                          </button>
                        </form>
                      )}

                      {challengeSubmission && challengeSubmission.foundCount === 0 && (
                        <div className="text-xs text-rose-300 bg-rose-900/20 border border-rose-500/20 p-2 rounded">
                          No strong candidates found for this position.
                        </div>
                      )}
                    </>
                  ) : (
                    // Calculation Phase
                    <div className="flex flex-col space-y-3">
                      {(() => {
                        const currentBranchLine = (challengeBranches && activeChallengeCandidate) ? challengeBranches[activeChallengeCandidate] || [] : [];
                        return (
                          <>
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-purple-300">Candidate: {activeChallengeCandidate}</span>
                            </div>

                            <div className="text-xs text-purple-200">
                              <div className="mb-3">
                                <span className="font-semibold text-purple-300">Your calculation</span>
                                <div className="mt-1.5 p-2 bg-slate-950/50 border border-purple-500/20 rounded font-mono text-slate-300 text-xs leading-relaxed">
                                  {currentBranchLine.length > 0 ? formatCalculationLine(currentBranchLine) : activeChallengeCandidate}
                                </div>
                              </div>

                              Don't move the pieces. Visualize the {currentBranchLine.length === 1 ? 'resulting position' : 'position'}.
                              <br /><br />
                              <span className="font-bold text-white">What is {currentBranchLine.length > 0 ? (currentBranchLine[currentBranchLine.length - 1].isWhite ? "Black's" : "White's") : "Unknown"} best continuation?</span>
                            </div>

                            {calculationFeedback && (
                              <div className={`text-xs px-3 py-2 rounded-lg border ${
                                calculationFeedback.type === 'success' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' :
                                'bg-rose-500/10 border-rose-500/30 text-rose-300'
                              }`}>
                                {calculationFeedback.type === 'success' ? '✓ ' : ''}{calculationFeedback.message}
                              </div>
                            )}

                            <form onSubmit={onCalculationSubmit} className="flex gap-2">
                              <input
                                type="text"
                                value={calculationInput}
                                onChange={(e) => onCalculationInputChange?.(e.target.value)}
                                placeholder="SAN input (e.g. a6)"
                                disabled={isCalculationLoading}
                                className="flex-1 bg-slate-950 border border-slate-700 rounded-lg px-3 py-2 text-xs text-slate-200 focus:outline-none focus:border-purple-500"
                                autoComplete="off"
                              />
                              <button
                                type="submit"
                                disabled={isCalculationLoading || !calculationInput.trim()}
                                className="bg-purple-600 hover:bg-purple-500 text-white px-4 py-2 rounded-lg text-xs font-bold"
                              >
                                Submit
                              </button>
                            </form>

                            <div className="flex flex-col gap-2 pt-2">
                              <button
                                onClick={onBackToCandidates}
                                className="w-full py-2 bg-purple-600/20 hover:bg-purple-600/40 border border-purple-500/30 text-purple-200 font-bold text-xs rounded-lg transition"
                              >
                                Back to candidates
                              </button>
                              <button
                                onClick={onCalculationBack}
                                disabled={currentBranchLine.length <= 1}
                                className="w-full py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 font-bold text-xs rounded-lg transition disabled:opacity-50 disabled:cursor-not-allowed"
                              >
                                Back
                              </button>
                            </div>
                          </>
                        );
                      })()}
                    </div>
                  )}
                </div>
              )}

              {explorationPlayMode === 'CHESSECHO' && lastContinuationCandidates && (
                <div className="flex flex-col space-y-2 bg-slate-950/50 p-2.5 rounded-xl border border-slate-800">
                  <div className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">ChessEcho played</div>
                  <div className="flex items-center justify-between bg-emerald-900/20 px-3 py-2 rounded-lg border border-emerald-500/30">
                    <span className="font-mono text-emerald-300 font-bold text-sm">
                      {lastContinuationCandidates.selected.move}
                      {lastContinuationCandidates.selected.evalLoss != null && (
                        <span className="ml-1.5 text-[11px] text-emerald-400/80 font-sans font-normal">
                          — {lastContinuationCandidates.selected.evalLoss.toFixed(2)} pawns
                        </span>
                      )}
                    </span>
                    <span className="text-[10px] text-emerald-400/70 font-semibold bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/20">Selected</span>
                  </div>

                  {lastContinuationCandidates.candidates.length > 1 && (
                    <div className="pt-1 space-y-1.5">
                      <div className="text-[10px] font-semibold text-slate-500 uppercase tracking-wider">Alternative responses</div>
                      <div className="flex flex-col space-y-1.5">
                        {lastContinuationCandidates.candidates.filter(c => c.move !== lastContinuationCandidates.selected.move).map(alt => (
                          <div key={alt.move} className="flex items-center justify-between bg-slate-900 px-3 py-1.5 rounded-lg border border-slate-800 hover:border-slate-600 transition group">
                            <span className="font-mono text-slate-300 text-xs font-semibold">
                              {alt.move}
                              {alt.evalLoss != null && (
                                <span className="ml-1.5 text-[10px] text-slate-400 font-sans font-normal">
                                  — {alt.evalLoss.toFixed(2)} pawns
                                </span>
                              )}
                            </span>
                            <button
                              type="button"
                              onClick={() => onAlternativeSelected?.(alt)}
                              className="px-2.5 py-1 bg-slate-800 group-hover:bg-slate-700 text-slate-300 text-[10px] font-bold rounded transition cursor-pointer border border-slate-700 group-hover:border-slate-500"
                            >
                              Explore this line
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
          </div>
          </div>
        )}
        </div>
        </div>
      )}

      {/* Stats Pill Badges */}
      <div className="grid grid-cols-2 gap-2 py-0.5">
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
      </div>

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
