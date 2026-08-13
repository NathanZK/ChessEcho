'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Target, Flame, Swords, ExternalLink, Filter, AlertCircle, RefreshCw, X } from 'lucide-react';
import { Chessboard } from 'react-chessboard';
import { fetchWeaknesses, WeaknessResponse } from '../services/api';
import { Puzzle } from '../mock/mockData';

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
  const diffMs = now.getTime() - date.getTime();
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

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
  onWeaknessCountChange?: (count: number) => void;
  activeColorFilter?: 'ALL' | 'WHITE' | 'BLACK';
  onColorFilterChange?: (color: 'ALL' | 'WHITE' | 'BLACK') => void;
}

const PAGE_SIZE = 20;

export const WeaknessesList: React.FC<WeaknessesListProps> = ({
  username,
  minEvalLoss = 0.8,
  onMinEvalLossChange,
  minMistakeCount = 3,
  onMinMistakeCountChange,
  onSelectPractice,
  onWeaknessCountChange,
  activeColorFilter,
  onColorFilterChange,
}) => {
  const [colorFilter, setColorFilter] = useState<'ALL' | 'WHITE' | 'BLACK'>(
    activeColorFilter || 'ALL'
  );

  const handleColorChange = (newColor: 'ALL' | 'WHITE' | 'BLACK') => {
    setColorFilter(newColor);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_weakness_color_filter', newColor);
    }
    onColorFilterChange?.(newColor);
  };

  useEffect(() => {
    if (activeColorFilter && activeColorFilter !== colorFilter) {
      setColorFilter(activeColorFilter);
    }
  }, [activeColorFilter, colorFilter]);

  const [weaknesses, setWeaknesses] = useState<WeaknessResponse[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isLoadingMore, setIsLoadingMore] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const [page, setPage] = useState<number>(0);
  const [hasMore, setHasMore] = useState<boolean>(true);

  const [activeGameModalUrls, setActiveGameModalUrls] = useState<string[] | null>(null);
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const isFetchingRef = useRef<boolean>(false);

  // Notify parent of total weakness count in a side-effect safe useEffect
  useEffect(() => {
    onWeaknessCountChange?.(weaknesses.length);
  }, [weaknesses.length, onWeaknessCountChange]);

  // Initial load or filter change reset
  useEffect(() => {
    if (!username) {
      setWeaknesses([]);
      setIsLoading(false);
      setIsLoadingMore(false);
      setError(null);
      setPage(0);
      setHasMore(false);
      return;
    }

    async function loadInitialWeaknesses() {
      setIsLoading(true);
      setError(null);
      setPage(0);
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
        setWeaknesses(data);
        setHasMore(data.length === PAGE_SIZE);
      } catch (err) {
        console.error('Failed to fetch weaknesses:', err);
        setError('Failed to load weakness data from backend API');
        setWeaknesses([]);
        setHasMore(false);
      } finally {
        setIsLoading(false);
        isFetchingRef.current = false;
      }
    }

    loadInitialWeaknesses();
  }, [username, colorFilter, minMistakeCount, minEvalLoss]);

  // Load next page function
  const loadNextPage = useCallback(async () => {
    if (!username || isLoading || isLoadingMore || !hasMore || isFetchingRef.current) return;

    isFetchingRef.current = true;
    setIsLoadingMore(true);
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
      console.error('Failed to load more weaknesses:', err);
      setHasMore(false);
    } finally {
      setIsLoadingMore(false);
      isFetchingRef.current = false;
    }
  }, [username, isLoading, isLoadingMore, hasMore, page, colorFilter, minMistakeCount, minEvalLoss]);

  // IntersectionObserver for infinite scroll sentinel
  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore || isLoading || isLoadingMore) return;
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasMore && !isLoading && !isLoadingMore && !isFetchingRef.current) {
          loadNextPage();
        }
      },
      { threshold: 0.1 }
    );

    observer.observe(target);
    return () => {
      observer.disconnect();
    };
  }, [sentinelRef, hasMore, isLoading, isLoadingMore, loadNextPage]);

  return (
    <div className="max-w-6xl mx-auto space-y-4 px-4 py-2 text-slate-200">
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
              onChange={(e) => onMinEvalLossChange?.(Number(e.target.value))}
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
              onChange={(e) => onMinMistakeCountChange?.(Number(e.target.value))}
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
            onClick={() => {
              setColorFilter((prev) => prev);
            }}
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
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
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
                          Your Historical Decisions:
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                          {item.movesPlayed.map((m, idx) => (
                            <span
                              key={idx}
                              className="px-2 py-1 bg-rose-500/10 border border-rose-500/20 text-rose-300 rounded-lg text-xs font-mono font-semibold"
                            >
                              {m.move} ({m.timesPlayed}x, -{m.averageLoss.toFixed(2)} pawns)
                            </span>
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
            {!isLoadingMore && hasMore && weaknesses.length >= PAGE_SIZE && (
              <button
                type="button"
                onClick={loadNextPage}
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
        <div className="fixed inset-0 bg-slate-950/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 max-w-md w-full shadow-2xl space-y-4">
            <div className="flex items-center justify-between border-b border-slate-800 pb-3">
              <h3 className="text-base font-bold text-white flex items-center gap-2">
                <ExternalLink className="w-4 h-4 text-emerald-400" />
                Historical Games
              </h3>
              <button
                type="button"
                onClick={() => setActiveGameModalUrls(null)}
                className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <p className="text-xs text-slate-400">
              The following historical games contain this recurring position:
            </p>

            <div className="max-h-60 overflow-y-auto space-y-2 pr-1">
              {activeGameModalUrls.map((url, idx) => (
                <a
                  key={idx}
                  href={url}
                  target="_blank"
                  rel="noreferrer"
                  className="flex items-center justify-between p-3 rounded-xl bg-slate-950 hover:bg-slate-800 border border-slate-800 text-xs font-semibold text-slate-300 hover:text-emerald-400 transition"
                >
                  <span className="truncate max-w-[280px]">Game #{idx + 1}: {url}</span>
                  <ExternalLink className="w-3.5 h-3.5 shrink-0 ml-2 text-slate-400" />
                </a>
              ))}
            </div>

            <div className="pt-2 flex justify-end">
              <button
                type="button"
                onClick={() => setActiveGameModalUrls(null)}
                className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold rounded-xl transition cursor-pointer"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
