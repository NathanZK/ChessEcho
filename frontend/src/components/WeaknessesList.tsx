'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Target, Flame, Swords, ExternalLink, Filter, AlertCircle, RefreshCw, Search } from 'lucide-react';
import { Chessboard } from 'react-chessboard';
import { fetchWeaknesses, WeaknessResponse } from '../services/api';
import { MoveBreakdown, Puzzle } from '../mock/mockData';
import { HistoricalGamesModal } from './HistoricalGamesModal';

export function adaptWeaknessToPuzzle(
  weakness: WeaknessResponse,
  fallbackColor: 'WHITE' | 'BLACK' = 'WHITE'
): Puzzle {
  const fenTurn = weakness.fen ? weakness.fen.split(' ')[1] : undefined;
  const playerColor: 'WHITE' | 'BLACK' =
    fenTurn === 'b' ? 'BLACK' : fenTurn === 'w' ? 'WHITE' : fallbackColor;

  return {
    puzzleId: weakness.positionId,
    fen: weakness.fen,
    playerColor,
    targetMove: weakness.bestMove || '',
    openingTitle: 'Weakness Position',
    acceptableMoves: weakness.acceptableMoves || [],
    movesPlayed: weakness.movesPlayed || [],
    priority: weakness.priority,
    timesReached: weakness.timesReached,
    mistakeCount: weakness.mistakeCount,
    mistakeRate: weakness.mistakeRate,
    gameUrls: weakness.gameUrls || [],
    evalCp: weakness.evalCp ?? 35,
  };
}

export function formatLastSeen(lastSeenAt?: string | null): string | null {
  if (!lastSeenAt) return null;
  const date = new Date(lastSeenAt);
  if (isNaN(date.getTime())) return null;

  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const startOfGameDay = new Date(date.getFullYear(), date.getMonth(), date.getDate());

  const diffMs = startOfToday.getTime() - startOfGameDay.getTime();
  const diffDays = Math.round(diffMs / (1000 * 60 * 60 * 24));

  if (diffDays <= 0) return 'Last seen today';
  if (diffDays === 1) return 'Last seen yesterday';
  if (diffDays < 30) return `Last seen ${diffDays} days ago`;
  const diffMonths = Math.floor(diffDays / 30);
  if (diffMonths === 1) return 'Last seen 1 month ago';
  if (diffMonths < 12) return `Last seen ${diffMonths} months ago`;
  const diffYears = Math.floor(diffDays / 365);
  if (diffYears === 1) return 'Last seen 1 year ago';
  return `Last seen ${diffYears} years ago`;
}

interface WeaknessesListProps {
  username?: string;
  minEvalLoss?: number;
  onMinEvalLossChange?: (val: number) => void;
  minMistakeCount?: number;
  onMinMistakeCountChange?: (val: number) => void;
  onSelectPractice: (puzzle: Puzzle, fullList?: Puzzle[]) => void;
  onExploreDecision?: (puzzle: Puzzle, decision: MoveBreakdown) => void;
  onWeaknessCountChange?: (count: number) => void;
  activeColorFilter?: 'ALL' | 'WHITE' | 'BLACK';
  onColorFilterChange?: (color: 'ALL' | 'WHITE' | 'BLACK') => void;
  isAnalysisActive?: boolean;
  refreshKey?: number;
}

const PAGE_SIZE = 20;

export const WeaknessesList: React.FC<WeaknessesListProps> = ({
  username,
  minEvalLoss = 0.8,
  onMinEvalLossChange,
  minMistakeCount = 3,
  onMinMistakeCountChange,
  onSelectPractice,
  onExploreDecision,
  onWeaknessCountChange,
  activeColorFilter,
  onColorFilterChange,
  isAnalysisActive,
  refreshKey,
}) => {
  const [colorFilter, setColorFilter] = useState<'ALL' | 'WHITE' | 'BLACK'>(
    activeColorFilter || 'ALL'
  );

  if (activeColorFilter && activeColorFilter !== colorFilter) {
    setColorFilter(activeColorFilter);
  }

  const [weaknesses, setWeaknesses] = useState<WeaknessResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isLoadingMore, setIsLoadingMore] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<boolean>(false);
  const [reloadToken, setReloadToken] = useState<number>(0);

  const [page, setPage] = useState<number>(0);
  const [hasMore, setHasMore] = useState<boolean>(true);

  const [activeGameModalUrls, setActiveGameModalUrls] = useState<string[] | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const isFetchingRef = useRef<boolean>(false);
  // Monotonic request generation. A completion only owns the current generation
  // when its captured seq still equals loadSeqRef.current; otherwise every one of
  // its effects (data/error setters, loading flags, and the fetch lock release) is
  // skipped, so a stale/superseded request can never mutate the current UI.
  const loadSeqRef = useRef<number>(0);

  const invalidateWeaknessRequests = () => {
    loadSeqRef.current++;
    isFetchingRef.current = false;
  };

  const handleColorChange = (newColor: 'ALL' | 'WHITE' | 'BLACK') => {
    invalidateWeaknessRequests();
    setColorFilter(newColor);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_weakness_color_filter', newColor);
    }
    onColorFilterChange?.(newColor);
  };

  const handleMinEvalLossChange = (value: number) => {
    invalidateWeaknessRequests();
    onMinEvalLossChange?.(value);
  };

  const handleMinMistakeCountChange = (value: number) => {
    invalidateWeaknessRequests();
    onMinMistakeCountChange?.(value);
  };

  const handleRetryInitialLoad = () => {
    invalidateWeaknessRequests();
    setReloadToken((token) => token + 1);
  };

  // Notify parent of total weakness count in a side-effect safe useEffect
  useEffect(() => {
    onWeaknessCountChange?.(weaknesses.length);
  }, [weaknesses.length, onWeaknessCountChange]);

  // Initial load or filter change reset
  const [trackedWeaknessQuery, setTrackedWeaknessQuery] = useState<{
    username?: string;
    colorFilter: 'ALL' | 'WHITE' | 'BLACK';
    minMistakeCount: number;
    minEvalLoss: number;
    refreshKey?: number;
  } | null>(null);

  if (
    !trackedWeaknessQuery ||
    trackedWeaknessQuery.username !== username ||
    trackedWeaknessQuery.colorFilter !== colorFilter ||
    trackedWeaknessQuery.minMistakeCount !== minMistakeCount ||
    trackedWeaknessQuery.minEvalLoss !== minEvalLoss ||
    trackedWeaknessQuery.refreshKey !== refreshKey
  ) {
    setTrackedWeaknessQuery({ username, colorFilter, minMistakeCount, minEvalLoss, refreshKey });
    if (!username) {
      setWeaknesses([]);
      setIsLoading(false);
      setIsLoadingMore(false);
      setError(null);
      setLoadMoreError(false);
      setPage(0);
      setHasMore(false);
    }
  }

  useEffect(() => {
    if (!username) {
      return;
    }
    const requestSequence = loadSeqRef;
    const fetchLock = isFetchingRef;

    async function loadInitialWeaknesses() {
      const seq = ++loadSeqRef.current;
      setIsLoading(true);
      setError(null);
      setLoadMoreError(false);
      setPage(0);
      setIsLoadingMore(false);
      isFetchingRef.current = true;
      try {
        const backendColor = colorFilter === 'ALL' ? 'BOTH' : colorFilter;
        const data = await fetchWeaknesses(
          username!,
          'CHESS_COM',
          backendColor,
          minEvalLoss,
          minMistakeCount,
          0,
          PAGE_SIZE
        );
        if (seq !== loadSeqRef.current) return;
        setWeaknesses(data);
        setHasMore(data.length === PAGE_SIZE);
      } catch (err) {
        if (seq !== loadSeqRef.current) return;
        console.error('Failed to fetch weaknesses:', err);
        setError("We couldn't load your weaknesses. Please try again.");
        setWeaknesses([]);
        setHasMore(false);
      } finally {
        if (seq === loadSeqRef.current) {
          setIsLoading(false);
          isFetchingRef.current = false;
        }
      }
    }

    loadInitialWeaknesses();
    return () => {
      requestSequence.current++;
      fetchLock.current = false;
    };
  }, [username, colorFilter, minMistakeCount, minEvalLoss, refreshKey, reloadToken]);

  // Load next page function
  const loadNextPage = useCallback(async (isRetry: boolean = false) => {
    if (!username || isLoading || isLoadingMore || !hasMore || isFetchingRef.current) return;
    // While a pagination error is unresolved, only an explicit Retry may proceed;
    // this suppresses the auto-loader so the error stays a stable manual state.
    if (loadMoreError && !isRetry) return;

    const seq = ++loadSeqRef.current;
    isFetchingRef.current = true;
    setIsLoadingMore(true);
    if (loadMoreError) setLoadMoreError(false);
    const nextPage = page + 1;
    try {
      const backendColor = colorFilter === 'ALL' ? 'BOTH' : colorFilter;
      const data = await fetchWeaknesses(
        username,
        'CHESS_COM',
        backendColor,
        minEvalLoss,
        minMistakeCount,
        nextPage,
        PAGE_SIZE
      );

      if (seq !== loadSeqRef.current) return;

      if (data && data.length > 0) {
        setWeaknesses((prev) => {
          const existingIds = new Set(prev.map((w) => w.positionId));
          const newItems = data.filter((w) => !existingIds.has(w.positionId));
          return [...prev, ...newItems];
        });
        setPage(nextPage);
        setHasMore(data.length === PAGE_SIZE);
      } else {
        setHasMore(false);
      }
    } catch (err) {
      if (seq !== loadSeqRef.current) return;
      console.error('Failed to load more weaknesses:', err);
      // A pagination failure is surfaced as a retryable inline error. It must NOT
      // collapse to a terminal "no more data" state, so hasMore is preserved.
      setLoadMoreError(true);
    } finally {
      if (seq === loadSeqRef.current) {
        setIsLoadingMore(false);
        isFetchingRef.current = false;
      }
    }
  }, [username, isLoading, isLoadingMore, hasMore, page, colorFilter, minMistakeCount, minEvalLoss, loadMoreError]);

  // IntersectionObserver for infinite scroll sentinel
  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore || isLoading || isLoadingMore || loadMoreError) return;
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !isLoading && !isLoadingMore && !loadMoreError && !isFetchingRef.current) {
          loadNextPage();
        }
      },
      { threshold: 0.1 }
    );

    observer.observe(target);
    return () => {
      observer.disconnect();
    };
  }, [sentinelRef, hasMore, isLoading, isLoadingMore, loadMoreError, loadNextPage]);

  return (
    <div className="max-w-[1536px] w-full mx-auto px-4 lg:px-8 space-y-4 py-2 text-slate-200">
      {/* Engine Analysis Status Banner */}
      {isAnalysisActive && (
        <div className="p-3.5 bg-amber-500/10 border border-amber-500/30 rounded-2xl flex items-center space-x-3 text-xs font-semibold text-amber-300 shadow-md animate-in fade-in duration-200">
          <div className="w-2.5 h-2.5 rounded-full bg-amber-400 animate-ping shrink-0" />
          <span>
            Stockfish Engine Analysis Active: Evaluating positions for <strong>{username}</strong>. Detected weaknesses will update automatically as analysis completes.
          </span>
        </div>
      )}

      {/* Header & Filter Controls */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-slate-900 p-4 rounded-2xl border border-slate-800 shadow-lg">
        <div>
          <h2 className="text-xl font-bold text-white flex items-center gap-2">
            <Target className="w-5 h-5 text-emerald-400" />
            Recurring Opening Weaknesses Library
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Positions where you repeatedly make sub-optimal moves across games.
          </p>
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center space-x-2 text-xs font-semibold text-slate-400">
            <Filter className="w-3.5 h-3.5" />
            <span>Filters:</span>
          </div>

          {/* Color Filter */}
          <div className="flex bg-slate-950 p-1 rounded-xl border border-slate-800">
            {(['ALL', 'WHITE', 'BLACK'] as const).map((color) => (
              <button
                key={color}
                onClick={() => handleColorChange(color)}
                className={`px-3 py-1 text-xs font-bold rounded-lg transition cursor-pointer ${
                  colorFilter === color
                    ? 'bg-emerald-600 text-white shadow-sm'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {color}
              </button>
            ))}
          </div>

          {/* Shared Mistake Threshold Filter */}
          <div className="flex items-center space-x-2 bg-slate-950 px-3 py-1.5 rounded-xl border border-slate-800 text-xs font-semibold">
            <span className="text-slate-400">Mistake Threshold:</span>
            <select
              value={minEvalLoss}
              onChange={(e) => handleMinEvalLossChange(Number(e.target.value))}
              className="bg-slate-900 text-emerald-400 font-bold border border-slate-800 rounded px-2 py-0.5 outline-none cursor-pointer text-xs"
            >
              <option value={0.3}>0.3 pawns (Strict)</option>
              <option value={0.5}>0.5 pawns (Moderate)</option>
              <option value={0.8}>0.8 pawns (Standard)</option>
              <option value={1.2}>1.2 pawns (Severe)</option>
              <option value={2.0}>2.0 pawns (Critical)</option>
            </select>
          </div>

          {/* Min Mistake Count Filter */}
          <div className="flex items-center space-x-2 bg-slate-950 px-3 py-1.5 rounded-xl border border-slate-800 text-xs font-semibold">
            <span className="text-slate-400">Min Mistakes:</span>
            <select
              value={minMistakeCount}
              onChange={(e) => handleMinMistakeCountChange(Number(e.target.value))}
              className="bg-slate-900 text-emerald-400 font-bold border border-slate-800 rounded px-2 py-0.5 outline-none cursor-pointer text-xs"
            >
              <option value={1}>1 (All)</option>
              <option value={3}>3 (Habits only)</option>
              <option value={5}>5+ (High frequency)</option>
            </select>
          </div>
        </div>
      </div>

      {/* Main Content Area: Loading / Disconnected / Error / Empty / Grid */}
      {isLoading ? (
        <div className="flex flex-col items-center justify-center py-20 bg-slate-900/60 rounded-2xl border border-slate-800 shadow-lg space-y-3">
          <div className="w-8 h-8 border-3 border-emerald-400 border-t-transparent rounded-full animate-spin" />
          <span className="text-xs font-bold text-slate-300">Loading Recurring Weaknesses...</span>
        </div>
      ) : !username ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-emerald-400">
            <Target className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-white">No Connected Account</h3>
            <p className="text-xs text-slate-400 mt-1">
              Connect your Chess.com account in the Import Games tab to analyze your recurring opening weaknesses.
            </p>
          </div>
        </div>
      ) : error ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-rose-900/50 shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-rose-950/60 border border-rose-800 flex items-center justify-center mx-auto text-rose-400">
            <AlertCircle className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-white">Failed to Load Weaknesses</h3>
            <p className="text-xs text-rose-300 mt-1">{error}</p>
          </div>
          <button
            onClick={handleRetryInitialLoad}
            className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold rounded-xl transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry</span>
          </button>
        </div>
      ) : weaknesses.length === 0 ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-slate-900/60 p-8 rounded-2xl border border-slate-800 shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-slate-950 border border-slate-800 flex items-center justify-center mx-auto text-emerald-400">
            <Target className="w-7 h-7 text-slate-500" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-white">No Recurring Weaknesses Found</h3>
            <p className="text-xs text-slate-400 mt-1">
              No positions met your weakness filter criteria for <span className="text-emerald-400 font-semibold">{username}</span>. Try lowering the minimum mistakes filter or importing more games.
            </p>
          </div>
        </div>
      ) : (
        /* Weakness Cards Grid */
        <div className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {weaknesses.map((item) => {
              const activeColorInFen = item.fen ? item.fen.split(' ')[1] : 'w';
              const playerColor: 'WHITE' | 'BLACK' =
                activeColorInFen === 'b' ? 'BLACK' : activeColorInFen === 'w' ? 'WHITE' : 'WHITE';

              return (
                <div
                  key={item.positionId}
                  className="bg-slate-900 rounded-2xl p-5 border border-slate-800 hover:border-slate-700 shadow-xl transition flex flex-col justify-between space-y-4"
                >
                  <div className="space-y-4">
                    {/* Top Header: Badge & FEN Board Visualization */}
                    <div className="flex flex-col sm:flex-row items-center sm:items-start gap-4">
                      {/* Visual Board Display */}
                      <div className="w-[180px] h-[180px] shrink-0 rounded-xl overflow-hidden border border-slate-800 shadow-md">
                        <Chessboard
                          options={{
                            position: item.fen,
                            boardOrientation: playerColor === 'BLACK' ? 'black' : 'white',
                            darkSquareStyle: { backgroundColor: '#769656' },
                            lightSquareStyle: { backgroundColor: '#eeeed2' },
                            allowDragging: false,
                          }}
                        />
                      </div>

                      {/* Stats & Metadata */}
                      <div className="flex-1 space-y-3 w-full">
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                              Weakness Position
                            </span>
                            {item.lastSeenAt && formatLastSeen(item.lastSeenAt) && (
                              <span className="text-[11px] font-medium text-slate-400">
                                • {formatLastSeen(item.lastSeenAt)}
                              </span>
                            )}
                          </div>
                          <span
                            className={`text-xs font-bold px-2 py-0.5 rounded-md ${
                              playerColor === 'WHITE'
                                ? 'bg-slate-200 text-slate-900'
                                : 'bg-slate-800 text-slate-200 border border-slate-700'
                            }`}
                          >
                            As {playerColor}
                          </span>
                        </div>

                        {/* Stats Grid: Evidence Metrics Only */}
                        <div className="grid grid-cols-3 gap-2 text-center">
                          <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                            <div className="text-[10px] text-slate-400 font-medium">Mistake Rate</div>
                            <div className="text-xs font-bold text-amber-400 mt-0.5 flex items-center justify-center gap-1">
                              <Flame className="w-3.5 h-3.5 text-amber-500" />
                              {item.mistakeRate.toFixed(1)}% ({item.mistakeCount}x)
                            </div>
                          </div>

                          <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                            <div className="text-[10px] text-slate-400 font-medium">Times Reached</div>
                            <div className="text-xs font-bold text-slate-200 mt-0.5">
                              {item.timesReached}
                            </div>
                          </div>

                          <div className="bg-slate-950/80 p-2 rounded-xl border border-slate-800">
                            <div className="text-[10px] text-slate-400 font-medium">Avg Eval Loss</div>
                            <div className="text-xs font-bold text-rose-400 mt-0.5">
                              -{item.averageLoss.toFixed(2)} pawns
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* Historical Moves Played */}
                    {item.movesPlayed && item.movesPlayed.length > 0 && (
                      <div className="bg-slate-950/50 p-3 rounded-xl border border-slate-800/80">
                        <div className="text-[11px] font-semibold text-slate-400 mb-1.5">
                          Your Decisions in Source Games:
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {item.movesPlayed.map((m, idx) => (
                            <div
                              key={idx}
                              className="flex items-center gap-1.5"
                            >
                              <span className="px-2 py-1 bg-rose-500/10 border border-rose-500/20 text-rose-300 rounded-lg text-xs font-mono font-semibold">
                                {m.move} ({m.timesPlayed}x, -{m.averageLoss.toFixed(2)} pawns)
                              </span>
                              {m.resultingFen && onExploreDecision && (
                                <button
                                  type="button"
                                  onClick={() => onExploreDecision(adaptWeaknessToPuzzle(item, playerColor), m)}
                                  className="px-2 py-1 bg-sky-500/10 border border-sky-500/30 text-sky-300 hover:bg-sky-500/20 rounded-lg text-xs font-semibold transition cursor-pointer flex items-center gap-1"
                                >
                                  <Search className="w-3 h-3" />
                                  <span>Explore this decision</span>
                                </button>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>

                  {/* Bottom Action Bar */}
                  <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between gap-2">
                    {item.gameUrls && item.gameUrls.length > 0 ? (
                      <button
                        type="button"
                        onClick={() => setActiveGameModalUrls(item.gameUrls)}
                        className="text-xs font-semibold text-slate-400 hover:text-emerald-400 flex items-center gap-1.5 transition cursor-pointer"
                      >
                        <span>View Games ({item.gameUrls.length})</span>
                        <ExternalLink className="w-3.5 h-3.5" />
                      </button>
                    ) : (
                      <div />
                    )}

                    <button
                      type="button"
                      onClick={() => {
                        const fullConvertedList = weaknesses.map((w) => {
                          const col: 'WHITE' | 'BLACK' = w.fen && w.fen.split(' ')[1] === 'b' ? 'BLACK' : 'WHITE';
                          return adaptWeaknessToPuzzle(w, col);
                        });
                        onSelectPractice(adaptWeaknessToPuzzle(item, playerColor), fullConvertedList);
                      }}
                      className="flex items-center space-x-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold rounded-xl transition shadow-md shadow-emerald-900/30 cursor-pointer"
                    >
                      <Swords className="w-3.5 h-3.5" />
                      <span>Practice Position</span>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Permanently Mounted Sentinel & Infinite Scroll Loading Indicator */}
          <div
            ref={sentinelRef}
            data-testid="weaknesses-sentinel"
            className="py-4 flex justify-center items-center min-h-[50px]"
          >
            {isLoadingMore && (
              <div className="flex items-center space-x-2 text-xs font-bold text-slate-400 bg-slate-900 px-4 py-2 rounded-xl border border-slate-800">
                <div className="w-4 h-4 border-2 border-emerald-400 border-t-transparent rounded-full animate-spin" />
                <span>Loading more weaknesses...</span>
              </div>
            )}
            {!isLoadingMore && loadMoreError && (
              <div
                data-testid="weaknesses-load-more-error"
                className="flex flex-col sm:flex-row items-center gap-3 bg-slate-900 px-4 py-3 rounded-xl border border-rose-900/50 text-xs"
              >
                <div className="flex items-center gap-2 text-rose-300 font-semibold">
                  <AlertCircle className="w-4 h-4 text-rose-400 shrink-0" />
                  <span>We couldn&apos;t load more weaknesses. Please try again.</span>
                </div>
                <button
                  type="button"
                  onClick={() => loadNextPage(true)}
                  className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white font-bold rounded-xl transition inline-flex items-center gap-1.5 cursor-pointer"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  <span>Retry</span>
                </button>
              </div>
            )}
            {!isLoadingMore && !loadMoreError && hasMore && weaknesses.length >= PAGE_SIZE && (
              <button
                type="button"
                onClick={() => loadNextPage()}
                className="px-4 py-2 bg-slate-900 hover:bg-slate-800 text-slate-300 hover:text-white text-xs font-bold rounded-xl border border-slate-800 transition cursor-pointer"
              >
                Load More Weaknesses
              </button>
            )}
          </div>
        </div>
      )}

      {/* Historical Games Modal */}
      {activeGameModalUrls && (
        <HistoricalGamesModal
          urls={activeGameModalUrls}
          onClose={() => setActiveGameModalUrls(null)}
          username={username}
        />
      )}
    </div>
  );
};
