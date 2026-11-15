import React from 'react';
import {
  Sparkles,
  Trophy,
  AlertTriangle,
  EyeOff,
  LogOut,
} from 'lucide-react';
import { STRICT_OPPONENT_EVAL_LOSS_THRESHOLD } from '@/utils/practiceConstants';
import { formatPawnLoss } from '@/utils/formatters';
import { ContinuationCandidate, ExplorationPlayMode } from '@/services/api';

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

export function formatCalculationLine(line: { san: string; isWhite: boolean }[]): string {
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

export interface LineExplorationWorkbenchProps {
  explorationDecisionMove?: string | null;
  onExitExploration?: () => void;
  onEnterBlindfold?: () => void;
  isBlindfoldEntryDisabled?: boolean;
  explorationPlayMode?: ExplorationPlayMode;
  onExplorationPlayModeChange?: (mode: ExplorationPlayMode) => void;
  activeColorToMove: 'White' | 'Black';
  playerColor?: 'WHITE' | 'BLACK';
  unacceptableMoveMessage?: string | null;
  isContinuationLoading?: boolean;
  explorationFeedback?: { message: string; type: 'best' | 'good' } | null;
  continuationCandidate?: ContinuationCandidate | null;
  activeChallengeCandidate?: string | null;
  challengeSubmission?: ChallengeSubmissionResult;
  challengeCandidates?: ContinuationCandidate[];
  challengeBranches?: Record<string, { san: string; fenAfter: string; isWhite: boolean }[]>;
  onChallengeCandidateSelect?: (san: string) => void;
  onFinishChallenge?: () => void;
  challengeFeedback?: { message: string; type: 'success' | 'error' | 'info' } | null;
  challengeInput?: string;
  onChallengeInputChange?: (val: string) => void;
  onChallengeSubmit?: (e: React.FormEvent) => void;
  isChallengeLoading?: boolean;
  calculationFeedback?: { type: 'success' | 'error'; message: string } | null;
  calculationInput?: string;
  onCalculationInputChange?: (val: string) => void;
  onCalculationSubmit?: (e: React.FormEvent) => void;
  isCalculationLoading?: boolean;
  onBackToCandidates?: () => void;
  onCalculationBack?: () => void;
  lastContinuationCandidates?: {
    parentFen: string;
    candidates: ContinuationCandidate[];
    selected: ContinuationCandidate;
  } | null;
  onAlternativeSelected?: (candidate: ContinuationCandidate) => void;
}

export const LineExplorationWorkbench: React.FC<LineExplorationWorkbenchProps> = ({
  explorationDecisionMove,
  onExitExploration,
  onEnterBlindfold,
  isBlindfoldEntryDisabled,
  explorationPlayMode,
  onExplorationPlayModeChange,
  activeColorToMove,
  playerColor,
  unacceptableMoveMessage,
  isContinuationLoading,
  explorationFeedback,
  continuationCandidate,
  activeChallengeCandidate,
  challengeSubmission,
  challengeCandidates,
  challengeBranches,
  onChallengeCandidateSelect,
  onFinishChallenge,
  challengeFeedback,
  challengeInput = '',
  onChallengeInputChange,
  onChallengeSubmit,
  isChallengeLoading = false,
  calculationFeedback,
  calculationInput = '',
  onCalculationInputChange,
  onCalculationSubmit,
  isCalculationLoading = false,
  onBackToCandidates,
  onCalculationBack,
  lastContinuationCandidates,
  onAlternativeSelected,
}) => {
  return (
    <div className="bg-slate-950 p-4 rounded-2xl border border-emerald-500/30 space-y-3.5 text-xs shadow-lg shadow-slate-950/80 animate-in fade-in duration-200">
      <div className="flex flex-col space-y-2.5">
        <div className="flex items-center justify-between border-b border-slate-800/80 pb-2.5">
          <div className="flex items-center gap-2 font-bold text-emerald-300">
            <Sparkles className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-bold text-white tracking-wider uppercase">
              Line Exploration
              {explorationDecisionMove && (
                <span className="text-amber-300 normal-case font-normal ml-1">
                  (from {explorationDecisionMove})
                </span>
              )}
            </span>
          </div>

          <div data-testid="exploration-actions" className="flex items-center gap-1.5">
            {onEnterBlindfold && (
              <button
                type="button"
                onClick={onEnterBlindfold}
                disabled={isBlindfoldEntryDisabled}
                title={isBlindfoldEntryDisabled ? 'Available once the board settles' : 'Continue this position blindfolded'}
                className="flex items-center gap-1.5 px-2.5 py-1 bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-slate-200 font-bold text-[11px] rounded-lg transition border border-slate-800 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <EyeOff className="w-3 h-3 text-slate-400" />
                <span>Train Blindfold</span>
              </button>
            )}
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
                onClick={() => onExplorationPlayModeChange?.('CHESSPROBE')}
                className="flex flex-col items-start p-3 bg-slate-900 hover:bg-slate-850 hover:border-emerald-500/50 rounded-xl border border-slate-800 transition text-left group cursor-pointer space-y-1"
              >
                <div className="flex items-center justify-between w-full">
                  <span className="font-bold text-xs text-slate-100 group-hover:text-emerald-400 transition flex items-center gap-1.5">
                    <span>🤖</span> vs ChessProbe
                  </span>
                  <span className="text-[10px] font-bold px-2 py-0.5 bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 rounded-md group-hover:bg-emerald-500/20 transition">
                    Select →
                  </span>
                </div>
                <p className="text-[11px] text-slate-400 leading-normal">
                  ChessProbe responds to your moves.
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
                  Find a strong candidate move. ChessProbe won&apos;t respond.
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
                    onClick={() => onExplorationPlayModeChange('CHESSPROBE')}
                    className={`px-2 py-0.5 text-[10px] font-bold rounded transition cursor-pointer ${
                      explorationPlayMode === 'CHESSPROBE' ? 'bg-emerald-600 text-white shadow-sm' : 'text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    vs ChessProbe
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
              ) : isContinuationLoading && explorationPlayMode === 'CHESSPROBE' ? (
                <div className="flex flex-col space-y-2">
                  {explorationFeedback && (
                    <div className={`flex items-start gap-2 p-2.5 rounded-xl text-xs border ${explorationFeedback.type === 'best' ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' : 'bg-blue-500/10 border-blue-500/30 text-blue-300'}`}>
                      {explorationFeedback.type === 'best' ? <Trophy className="w-4 h-4 shrink-0 text-emerald-400 mt-0.5" /> : <Sparkles className="w-4 h-4 shrink-0 text-blue-400 mt-0.5" />}
                      <span className="font-medium leading-relaxed">{explorationFeedback.message}</span>
                    </div>
                  )}
                  <div className="flex items-center space-x-2 text-slate-400 py-2 justify-center bg-slate-900/60 rounded-xl border border-slate-800">
                    <div className="w-4 h-4 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                    <span className="font-medium text-xs">ChessProbe is choosing a response...</span>
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
                            ? ((playerColor && activeColorToMove.toUpperCase() !== playerColor)
                              ? `Opponent's turn — think like ${activeColorToMove}. Find their strongest move.`
                              : `Your turn — find the best move for ${activeColorToMove}.`)
                            : 'Your turn — explore a move.'}
                      </span>
                    </div>
                    {explorationPlayMode === 'CHESSPROBE' && continuationCandidate?.move && (
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
                                 `Find up to ${Math.min((challengeCandidates || []).length, 3)} strong candidate moves.`
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

                          {!(challengeSubmission?.isComplete) && (challengeCandidates || []).length > 0 && (
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
                                disabled={!challengeInput?.trim() || isChallengeLoading || (challengeCandidates || []).length === 0}
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

                                  Don&apos;t move the pieces. Visualize the {currentBranchLine.length === 1 ? 'resulting position' : 'position'}.
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
                                    disabled={isCalculationLoading || !calculationInput?.trim()}
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

                  {explorationPlayMode === 'CHESSPROBE' && lastContinuationCandidates && (
                    <div className="flex flex-col space-y-2 bg-slate-950/50 p-2.5 rounded-xl border border-slate-800">
                      <div className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">ChessProbe played</div>
                      <div className="flex items-center justify-between bg-emerald-900/20 px-3 py-2 rounded-lg border border-emerald-500/30">
                        <div className="flex items-center gap-1.5 flex-wrap">
                          <span className="font-mono text-emerald-300 font-bold text-sm">
                            {lastContinuationCandidates.selected.move}
                            {lastContinuationCandidates.selected.evalLoss != null && (
                              <span className="ml-1.5 text-[11px] text-emerald-400/80 font-sans font-normal">
                                — {lastContinuationCandidates.selected.evalLoss.toFixed(2)} pawns
                              </span>
                            )}
                          </span>
                          {lastContinuationCandidates.selected.isTerminal ? (
                            <span className="text-[10px] text-rose-300 bg-rose-950/60 border border-rose-700/50 px-1.5 py-0.5 rounded font-sans font-semibold">
                              Terminal
                            </span>
                          ) : (lastContinuationCandidates.selected.pliesResolved != null && lastContinuationCandidates.selected.pliesResolved > 0) || (lastContinuationCandidates.selected.continuationSanMoves && lastContinuationCandidates.selected.continuationSanMoves.length > 0) ? (
                            <span className="text-[10px] text-emerald-300/80 bg-emerald-950/60 border border-emerald-700/50 px-1.5 py-0.5 rounded font-mono">
                              +{lastContinuationCandidates.selected.pliesResolved ?? lastContinuationCandidates.selected.continuationSanMoves?.length} plies
                            </span>
                          ) : (() => {
                            const loss = lastContinuationCandidates.selected.evalLoss ?? 0;
                            if (loss === 0) {
                              return (
                                <span className="text-[10px] text-emerald-300 bg-emerald-950/60 border border-emerald-700/50 px-1.5 py-0.5 rounded font-sans font-semibold">
                                  Best move
                                </span>
                              );
                            } else if (loss <= STRICT_OPPONENT_EVAL_LOSS_THRESHOLD) {
                              return (
                                <span className="text-[10px] text-emerald-400/80 bg-emerald-950/60 border border-emerald-700/50 px-1.5 py-0.5 rounded font-sans">
                                  Acceptable move (+{formatPawnLoss(loss)} pawns)
                                </span>
                              );
                            } else {
                              return (
                                <span className="text-[10px] text-amber-300 bg-amber-950/60 border border-amber-700/50 px-1.5 py-0.5 rounded font-sans font-semibold">
                                  Inaccuracy
                                </span>
                              );
                            }
                          })()}
                        </div>
                        <span className="text-[10px] text-emerald-400/70 font-semibold bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/20">Selected</span>
                      </div>

                      {lastContinuationCandidates.candidates.length > 1 && (
                        <div className="pt-1 space-y-1.5">
                          <div className="text-[10px] font-semibold text-slate-500 uppercase tracking-wider">Alternative responses</div>
                          <div className="flex flex-col space-y-1.5">
                            {lastContinuationCandidates.candidates.filter(c => c.move !== lastContinuationCandidates.selected.move).map(alt => (
                              <div key={alt.move} className="flex items-center justify-between bg-slate-900 px-3 py-1.5 rounded-lg border border-slate-800 hover:border-slate-600 transition group">
                                <div className="flex items-center gap-1.5 flex-wrap">
                                  <span className="font-mono text-slate-300 text-xs font-semibold">
                                    {alt.move}
                                    {alt.evalLoss != null && (
                                      <span className="ml-1.5 text-[10px] text-slate-400 font-sans font-normal">
                                        — {alt.evalLoss.toFixed(2)} pawns
                                      </span>
                                    )}
                                  </span>
                                  {alt.isTerminal ? (
                                    <span className="text-[9px] text-rose-300 bg-rose-950/60 border border-rose-700/50 px-1.5 py-0.5 rounded font-sans font-semibold">
                                      Terminal
                                    </span>
                                  ) : (alt.pliesResolved != null && alt.pliesResolved > 0) || (alt.continuationSanMoves && alt.continuationSanMoves.length > 0) ? (
                                    <span className="text-[9px] text-indigo-300 bg-indigo-950/60 border border-indigo-700/50 px-1.5 py-0.5 rounded font-mono">
                                      +{alt.pliesResolved ?? alt.continuationSanMoves?.length} plies
                                    </span>
                                  ) : (
                                    <span className="text-[9px] text-slate-400 bg-slate-800/60 border border-slate-700 px-1.5 py-0.5 rounded font-sans">
                                      Quiet
                                    </span>
                                  )}
                                </div>
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
  );
};
