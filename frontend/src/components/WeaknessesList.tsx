'use client';

import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  Target,
  Swords,
  ExternalLink,
  Filter,
  AlertCircle,
  RefreshCw,
  TrendingUp,
  LayoutGrid,
  List,
  ChevronDown,
  SlidersHorizontal,
  Repeat,
  Flag,
} from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { PositionCard } from './PositionCard';
import { StatusBanner } from './StatusBanner';
import { fetchWeaknesses, WeaknessResponse, TimeControlStats } from '../services/api';
import { Puzzle } from '@/types/puzzle';
import { HistoricalGamesModal } from './HistoricalGamesModal';
import {
  formatDecimal,
  formatPawnLoss,
  formatScoreRate,
} from '@/utils/formatters';

const PRACTICAL_TIME_CONTROLS = [
  { key: 'BULLET', label: 'Bullet' },
  { key: 'BLITZ', label: 'Blitz' },
  { key: 'RAPID', label: 'Rapid' },
] as const;

interface PracticalTcBreakdown {
  key: string;
  label: string;
  stats: TimeControlStats;
}

function getPracticalTcBreakdown(timeControlStats?: Record<string, TimeControlStats>): PracticalTcBreakdown[] {
  if (!timeControlStats) return [];
  const result: PracticalTcBreakdown[] = [];
  for (const { key, label } of PRACTICAL_TIME_CONTROLS) {
    const stats = timeControlStats[key];
    if (stats && stats.eligibleGames != null && stats.eligibleGames > 0) {
      result.push({ key, label, stats });
    }
  }
  return result;
}

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
  accountId?: string;
  username?: string;
  minEvalLoss?: number;
  onMinEvalLossChange?: (val: number) => void;
  minMistakeCount?: number;
  onMinMistakeCountChange?: (val: number) => void;
  onSelectPractice: (puzzle: Puzzle, fullList?: Puzzle[]) => void;
  onViewProgress?: (positionId: string, playerColor: 'WHITE' | 'BLACK', minEvalLoss: number) => void;
  onWeaknessCountChange?: (count: number) => void;
  activeColorFilter?: 'ALL' | 'WHITE' | 'BLACK';
  onColorFilterChange?: (color: 'ALL' | 'WHITE' | 'BLACK') => void;
  isAnalysisActive?: boolean;
  refreshKey?: number;
}

const PAGE_SIZE = 20;

export const WeaknessesList: React.FC<WeaknessesListProps> = ({
  accountId,
  username,
  minEvalLoss = 0.8,
  onMinEvalLossChange,
  minMistakeCount = 3,
  onMinMistakeCountChange,
  onSelectPractice,
  onViewProgress,
  onWeaknessCountChange,
  activeColorFilter,
  onColorFilterChange,
  isAnalysisActive,
  refreshKey,
}) => {
  const selector = accountId ?? username;
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [colorFilter, setColorFilter] = useState<'ALL' | 'WHITE' | 'BLACK'>(
    activeColorFilter || 'ALL'
  );

  if (activeColorFilter && activeColorFilter !== colorFilter) {
    setColorFilter(activeColorFilter);
  }

  const [timeControlFilter, setTimeControlFilter] = useState<'ALL' | 'BULLET' | 'BLITZ' | 'RAPID' | 'CLASSICAL'>('ALL');
  const [phaseFilter, setPhaseFilter] = useState<'ALL' | 'OPENING' | 'MIDDLEGAME' | 'ENDGAME'>('ALL');

  const [weaknessResult, setWeaknessResult] = useState<{
    rows: WeaknessResponse[];
    minEvalLoss: number;
  }>({ rows: [], minEvalLoss });
  const rawWeaknesses = weaknessResult.rows;
  const weaknesses = rawWeaknesses.filter(w => {
    const tcPass = timeControlFilter === 'ALL' || (w.timeControlStats && w.timeControlStats[timeControlFilter] && w.timeControlStats[timeControlFilter].timesReached > 0);
    const phasePass = phaseFilter === 'ALL' || (w.phaseStats && w.phaseStats[phaseFilter] && w.phaseStats[phaseFilter].timesReached > 0);
    return tcPass && phasePass;
  });

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
  const loadSeqRef = useRef<number>(0);

  const invalidateWeaknessRequests = () => {
    loadSeqRef.current++;
    isFetchingRef.current = false;
  };

  const handleColorChange = (newColor: 'ALL' | 'WHITE' | 'BLACK') => {
    invalidateWeaknessRequests();
    setColorFilter(newColor);
    onColorFilterChange?.(newColor);
  };

  const handleMinEvalLossChange = (newThreshold: number) => {
    invalidateWeaknessRequests();
    onMinEvalLossChange?.(newThreshold);
  };

  const handleMinMistakeCountChange = (newCount: number) => {
    invalidateWeaknessRequests();
    onMinMistakeCountChange?.(newCount);
  };

  const handleRetryInitialLoad = () => {
    invalidateWeaknessRequests();
    setReloadToken((prev) => prev + 1);
  };

  const loadNextPage = useCallback(
    async (isRetry = false) => {
      if (!selector || isFetchingRef.current || (!hasMore && !isRetry) || (loadMoreError && !isRetry)) return;
      const seq = ++loadSeqRef.current;
      const nextPage = isRetry ? page : page + 1;
      const requestMinEvalLoss = minEvalLoss;

      setIsLoadingMore(true);
      setLoadMoreError(false);
      isFetchingRef.current = true;

      try {
        const backendColor = colorFilter === 'ALL' ? 'BOTH' : colorFilter;
        const newRows = await fetchWeaknesses(
          selector,
          'CHESS_COM',
          backendColor,
          requestMinEvalLoss,
          minMistakeCount,
          nextPage,
          PAGE_SIZE
        );
        if (seq !== loadSeqRef.current) return;

        const safeNewRows = Array.isArray(newRows) ? newRows : [];
        setWeaknessResult((prev) => {
          const existingIds = new Set(prev.rows.map((r) => r.positionId));
          const filtered = safeNewRows.filter((r) => !existingIds.has(r.positionId));
          return { rows: [...prev.rows, ...filtered], minEvalLoss: requestMinEvalLoss };
        });
        setPage(nextPage);
        setHasMore(safeNewRows.length === PAGE_SIZE);
      } catch (err) {
        if (seq !== loadSeqRef.current) return;
        console.error('Failed to load more weaknesses:', err);
        setLoadMoreError(true);
      } finally {
        if (seq === loadSeqRef.current) {
          setIsLoadingMore(false);
          isFetchingRef.current = false;
        }
      }
    },
    [selector, hasMore, page, minEvalLoss, minMistakeCount, colorFilter, loadMoreError]
  );

  useEffect(() => {
    onWeaknessCountChange?.(weaknesses.length);
  }, [weaknesses.length, onWeaknessCountChange]);

  useEffect(() => {
    invalidateWeaknessRequests();
  }, [selector, minEvalLoss, minMistakeCount, colorFilter, refreshKey]);

  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore || isLoading || isLoadingMore || loadMoreError) return;
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasMore && !isLoading && !isLoadingMore && !loadMoreError && !isFetchingRef.current) {
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

  useEffect(() => {
    let active = true;
    const requestSelector = selector ?? '';
    if (!requestSelector) {
      loadSeqRef.current++;
      isFetchingRef.current = false;
      queueMicrotask(() => {
        if (!active) return;
        setWeaknessResult({ rows: [], minEvalLoss });
        setIsLoading(false);
        setIsLoadingMore(false);
        setError(null);
        setLoadMoreError(false);
        setPage(0);
        setHasMore(false);
      });
      return () => {
        active = false;
      };
    }

    async function loadInitialWeaknesses() {
      const seq = ++loadSeqRef.current;
      const requestMinEvalLoss = minEvalLoss;
      setIsLoading(true);
      setError(null);
      setLoadMoreError(false);
      setPage(0);
      setIsLoadingMore(false);
      isFetchingRef.current = true;
      try {
        const backendColor = colorFilter === 'ALL' ? 'BOTH' : colorFilter;
        const data = await fetchWeaknesses(
          requestSelector,
          'CHESS_COM',
          backendColor,
          requestMinEvalLoss,
          minMistakeCount,
          0,
          PAGE_SIZE
        );
        if (seq !== loadSeqRef.current) return;
        setWeaknessResult({ rows: data, minEvalLoss: requestMinEvalLoss });
        setHasMore(data.length === PAGE_SIZE);
      } catch (err) {
        if (seq !== loadSeqRef.current) return;
        console.error('Failed to fetch weaknesses:', err);
        setError("We couldn't load your weaknesses. Please try again.");
        setWeaknessResult({ rows: [], minEvalLoss: requestMinEvalLoss });
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
      invalidateWeaknessRequests();
    };
  }, [selector, minEvalLoss, minMistakeCount, colorFilter, reloadToken, refreshKey]);

  return (
    <div className="space-y-4 max-w-[1536px] mx-auto">
      {/* Active Engine Analysis Status Banner */}
      {isAnalysisActive && username && (
        <div className="p-3 bg-primary/10 border border-primary/30 rounded-xl flex items-center justify-between text-xs font-medium text-primary shadow-sm">
          <div className="flex items-center space-x-2.5">
            <div className="w-2 h-2 rounded-full bg-primary animate-ping shrink-0" />
            <span>Stockfish Engine Analysis Active: Evaluating positions for <strong>{username}</strong>. Detected recurring weaknesses update automatically as analysis completes.</span>
          </div>
        </div>
      )}

      {/* Section Header */}
      <section className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-outline-variant pb-4">
        <div className="space-y-1">
          <h1 className="text-xl md:text-2xl font-bold text-on-surface tracking-tight flex items-center gap-2">
            <Flag className="w-6 h-6 text-primary" />
            <span>Recurring Weakness Library</span>
          </h1>
          <p className="text-xs text-on-surface-variant max-w-2xl leading-relaxed">
            Positions where you repeatedly make sub-optimal moves across games. Ordered by default training priority queue (fusing engine evaluation loss, habit recurrence, and recency).
          </p>
        </div>

        {/* View Switcher & Paging Scope Indicator */}
        <div className="flex items-center space-x-3 shrink-0 self-end sm:self-center">
          <div className="px-2.5 py-1 rounded bg-surface-container border border-outline-variant text-xs font-mono">
            <span className="text-outline">Loaded Page: </span>
            <strong data-testid="weakness-count-indicator" className="text-primary">{weaknesses.length}</strong>
            <span className="text-outline text-[10px]"> positions</span>
          </div>

          {/* View Mode Segment */}
          <div className="flex items-center bg-surface-container-lowest p-0.5 rounded border border-outline-variant">
            <button
              type="button"
              onClick={() => setViewMode('grid')}
              className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                viewMode === 'grid'
                  ? 'bg-surface-container text-primary font-semibold'
                  : 'text-outline hover:text-on-surface'
              }`}
            >
              <LayoutGrid className="w-3.5 h-3.5" />
              <span>Grid</span>
            </button>
            <button
              type="button"
              onClick={() => setViewMode('list')}
              className={`flex items-center gap-1 px-2.5 py-1 rounded text-xs font-mono transition cursor-pointer ${
                viewMode === 'list'
                  ? 'bg-surface-container text-primary font-semibold'
                  : 'text-outline hover:text-on-surface'
              }`}
            >
              <List className="w-3.5 h-3.5" />
              <span>List</span>
            </button>
          </div>
        </div>
      </section>

      {/* Filters Bar */}
      <section className="bg-surface-container-low border border-outline-variant rounded-xl p-3.5 space-y-2.5 shadow-sm">
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
                onClick={() => handleColorChange('ALL')}
                className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                  colorFilter === 'ALL'
                    ? 'bg-surface-container text-primary border border-outline-variant'
                    : 'text-on-surface-variant hover:text-on-surface'
                }`}
              >
                Both Sides
              </button>
              <button
                type="button"
                onClick={() => handleColorChange('WHITE')}
                className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                  colorFilter === 'WHITE'
                    ? 'bg-surface-container text-primary border border-outline-variant'
                    : 'text-on-surface-variant hover:text-on-surface'
                }`}
              >
                White
              </button>
              <button
                type="button"
                onClick={() => handleColorChange('BLACK')}
                className={`px-2.5 py-1 rounded-md font-semibold transition cursor-pointer ${
                  colorFilter === 'BLACK'
                    ? 'bg-surface-container text-primary border border-outline-variant'
                    : 'text-on-surface-variant hover:text-on-surface'
                }`}
              >
                Black
              </button>
            </div>

            {/* Phase Filter Dropdown */}
            <div className="relative">
              <select
                aria-label="Filter by Phase"
                value={phaseFilter}
                onChange={(e) =>
                  setPhaseFilter(e.target.value as 'ALL' | 'OPENING' | 'MIDDLEGAME' | 'ENDGAME')
                }
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
              >
                <option value="ALL">Phase: All Phases</option>
                <option value="OPENING">Opening</option>
                <option value="MIDDLEGAME">Middlegame</option>
                <option value="ENDGAME">Endgame</option>
              </select>
              <ChevronDown className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
            </div>

            {/* Time Control / Speed Filter Dropdown */}
            <div className="relative">
              <select
                aria-label="Filter by Speed"
                value={timeControlFilter}
                onChange={(e) =>
                  setTimeControlFilter(
                    e.target.value as 'ALL' | 'BULLET' | 'BLITZ' | 'RAPID' | 'CLASSICAL'
                  )
                }
                className="appearance-none bg-surface-container hover:bg-surface-container-high border border-outline-variant rounded-lg pl-2.5 pr-7 py-1 text-xs font-mono text-on-surface cursor-pointer outline-none"
              >
                <option value="ALL">Speed: All Speeds</option>
                <option value="BLITZ">Blitz</option>
                <option value="RAPID">Rapid</option>
                <option value="BULLET">Bullet</option>
                <option value="CLASSICAL">Classical</option>
              </select>
              <ChevronDown className="pointer-events-none absolute right-1.5 top-1/2 -translate-y-1/2 text-outline w-3.5 h-3.5" />
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
      </section>

      {/* Main Content Area */}
      {isLoading ? (
        <StatusBanner
          variant="loading"
          message="Loading Recurring Weaknesses..."
        />
      ) : !selector ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-primary">
            <Target className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">No Connected Account</h3>
            <p className="text-xs text-on-surface-variant mt-1">
              Connect your Chess.com account in the Import Games tab to analyze your recurring opening weaknesses.
            </p>
          </div>
        </div>
      ) : error ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-error-container shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-error-container/20 border border-error-container flex items-center justify-center mx-auto text-error">
            <AlertCircle className="w-7 h-7" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">Failed to Load Weaknesses</h3>
            <p className="text-xs text-error mt-1">{error}</p>
          </div>
          <button
            type="button"
            onClick={handleRetryInitialLoad}
            className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface text-xs font-mono font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry</span>
          </button>
        </div>
      ) : weaknesses.length === 0 ? (
        <div className="py-16 text-center space-y-4 max-w-lg mx-auto bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-xl">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-outline">
            <Target className="w-7 h-7 text-outline" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-on-surface">No Recurring Weaknesses Found</h3>
            <p className="text-xs text-on-surface-variant mt-1">
              No positions met your weakness filter criteria{username && <> for <span className="text-primary font-semibold">{username}</span></>}. Try lowering the minimum mistakes filter or importing more games.
            </p>
          </div>
        </div>
      ) : (
        <div className="space-y-4">
          {viewMode === 'grid' ? (
            <div data-testid="weakness-grid-container" className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
              {weaknesses.map((item) => {
                const activeColorInFen = item.fen ? item.fen.split(' ')[1] : 'w';
                const playerColor: 'WHITE' | 'BLACK' =
                  activeColorInFen === 'b' ? 'BLACK' : activeColorInFen === 'w' ? 'WHITE' : 'WHITE';
                  
                const tcStats = timeControlFilter !== 'ALL' && item.timeControlStats ? item.timeControlStats[timeControlFilter] : null;
                const phStats = phaseFilter !== 'ALL' && item.phaseStats ? item.phaseStats[phaseFilter] : null;
                
                const displayMistakeRate = phStats ? phStats.mistakeRate * 100 : tcStats ? tcStats.mistakeRate * 100 : item.mistakeRate;
                const displayMistakeCount = phStats ? phStats.mistakeCount : tcStats ? tcStats.mistakeCount : item.mistakeCount;
                const displayTimesReached = phStats ? phStats.timesReached : tcStats ? tcStats.timesReached : item.timesReached;
                const displayAverageLoss = phStats ? phStats.averageLoss : tcStats ? tcStats.averageLoss : item.averageLoss;

                const colorBadge = playerColor === 'WHITE'
                  ? <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-white text-slate-900">As WHITE</span>
                  : <span className="px-2 py-0.5 rounded text-[10px] font-mono font-bold bg-surface-container-highest text-on-surface border border-outline-variant">As BLACK</span>;

                const openingText = item.openingContext ? (
                  <a
                    href={item.openingContext.ecoUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="hover:text-primary underline text-primary font-mono text-xs font-semibold"
                  >
                    Opening Context: {item.openingContext.eco}
                  </a>
                ) : (
                  <span className="text-outline font-mono text-xs">Weakness Position</span>
                );

                const prac = item.practicalEvidence;
                const tcBreakdown = getPracticalTcBreakdown(item.timeControlStats);

                return (
                  <PositionCard
                    key={item.positionId}
                    className="bg-surface-container-low border border-outline-variant hover:border-outline rounded-2xl overflow-hidden flex flex-col justify-between transition shadow-sm hover:shadow-md"
                  >
                    <div>
                      {/* Card Header */}
                      <div className="p-4 border-b border-outline-variant/60 flex items-start justify-between gap-2">
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            {colorBadge}
                            {openingText}
                          </div>
                          {item.lastSeenAt && formatLastSeen(item.lastSeenAt) && (
                            <div className="text-[11px] font-mono text-outline pt-0.5">
                              {formatLastSeen(item.lastSeenAt)}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Main Split Body */}
                      <div className="p-4 grid grid-cols-1 sm:grid-cols-2 gap-4 items-start">
                        {/* 1:1 Mini Chessboard */}
                        <div className="relative bg-surface-container-lowest border border-outline-variant rounded-xl p-1.5 aspect-square flex flex-col justify-between shadow-inner">
                          <div className="w-full h-full">
                            <ChessBoardView
                              fen={item.fen}
                              boardOrientation={playerColor}
                              allowDragging={false}
                              containerClassName="w-full h-full"
                              frameClassName="w-full h-full"
                            />
                          </div>
                          <div className="flex items-center justify-between text-[10px] font-mono text-outline px-1 pt-1">
                            <span>Turn: {playerColor}</span>
                            <span className="text-on-surface-variant">{displayTimesReached} Encounters</span>
                          </div>
                        </div>

                        {/* 3-Column Evidence Stats */}
                        <div className="space-y-2.5">
                          <div className="grid grid-cols-3 gap-1.5 text-center font-mono">
                            <div className="bg-surface-container-lowest p-2 rounded-lg border border-outline-variant">
                              <div className="text-[9px] text-outline font-sans uppercase">Mistake Rate</div>
                              <div className="text-xs font-bold text-amber-accent mt-0.5">
                                <span>{formatDecimal(displayMistakeRate, 1)}% ({displayMistakeCount}x)</span>
                              </div>
                            </div>

                            <div className="bg-surface-container-lowest p-2 rounded-lg border border-outline-variant">
                              <div className="text-[9px] text-outline font-sans uppercase">Reached</div>
                              <div className="text-xs font-bold text-on-surface mt-0.5">{displayTimesReached}</div>
                              <div className="text-[9px] text-outline">games</div>
                            </div>

                            <div className="bg-surface-container-lowest p-2 rounded-lg border border-outline-variant">
                              <div className="text-[9px] text-outline font-sans uppercase">Avg Mistake Loss</div>
                              <div className="text-xs font-bold text-error mt-0.5">-{formatPawnLoss(displayAverageLoss)} pawns</div>
                            </div>
                          </div>

                          {/* Historical Decisions in Source Games */}
                          {item.movesPlayed && item.movesPlayed.length > 0 && (
                            <div className="p-2.5 bg-surface-container-lowest rounded-lg border border-outline-variant space-y-1">
                              <div className="text-[10px] font-sans font-semibold text-outline">
                                Your Decisions in Source Games:
                              </div>
                              <div className="flex flex-wrap gap-1">
                                {item.movesPlayed.map((m, idx) => (
                                  <span
                                    key={idx}
                                    className="px-1.5 py-0.5 bg-error-container/20 border border-error/30 text-error rounded text-[11px] font-mono font-semibold"
                                  >
                                    {m.move} ({m.timesPlayed}x, -{formatPawnLoss(m.averageLoss)} pawns)
                                  </span>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      </div>


                      {/* Practical Evidence Block */}
                      {prac && (
                        <div className="mx-4 mb-4 p-3 bg-secondary-container/10 border border-secondary/20 rounded-xl space-y-1.5 text-xs font-mono">
                          <div className="text-[11px] font-sans font-semibold text-secondary flex items-center gap-1">
                            <Target className="w-3.5 h-3.5" />
                            Actual-game results at this exact position
                          </div>

                          {prac.eligibleGames > 0 && prac.scoreRate !== null ? (
                            <div className="space-y-0.5 text-[11px]">
                              <div className="text-on-surface">
                                {`Wins ${prac.wins} · Draws ${prac.draws} · Losses ${prac.losses}`}
                              </div>
                              <div className="text-outline">
                                <span>{`${formatScoreRate(prac.scoreRate)} score rate`}</span>
                                <span className="mx-1">·</span>
                                <span>{`${prac.eligibleGames} eligible games`}</span>
                              </div>
                            </div>
                          ) : (
                            <div className="text-[11px] text-outline">
                              {`No score available from actual games (${prac.eligibleGames} eligible games)`}
                            </div>
                          )}

                          {tcBreakdown.length > 0 && (
                            <div data-testid="time-control-breakdown" className="pt-2 mt-2 border-t border-secondary/20 space-y-1">
                              <div className="text-[10px] font-sans font-medium text-secondary/80">
                                Time Control Breakdown
                              </div>
                              <div className="space-y-1">
                                {tcBreakdown.map(({ key, label, stats }) => (
                                  <div key={key} className="flex flex-wrap items-center justify-between text-[11px] gap-1">
                                    <span className="px-1.5 py-0.5 rounded text-[10px] font-bold bg-surface-container-highest text-on-surface border border-outline-variant">
                                      {label}
                                    </span>
                                    <span className="text-on-surface">
                                      {`${stats.wins ?? 0}W · ${stats.draws ?? 0}D · ${stats.losses ?? 0}L`}
                                    </span>
                                    <span className="text-outline">
                                      {`${formatScoreRate(stats.scoreRate)} score rate (${stats.eligibleGames} games)`}
                                    </span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                    </div>

                    {/* Bottom Action Bar */}
                    <div className="p-3 bg-surface-container-lowest border-t border-outline-variant/60 flex items-center justify-between gap-2">
                      <div>
                        {item.gameUrls && item.gameUrls.length > 0 && (
                          <button
                            type="button"
                            onClick={() => setActiveGameModalUrls(item.gameUrls)}
                            className="px-2.5 py-1.5 text-xs font-mono text-outline hover:text-primary flex items-center gap-1 transition cursor-pointer"
                            title="View actual games where this occurred"
                          >
                            <ExternalLink className="w-3.5 h-3.5" />
                            <span>View Games ({item.gameUrls.length})</span>
                          </button>
                        )}
                      </div>

                      <div className="flex items-center space-x-2">
                        {onViewProgress && (
                          <button
                            type="button"
                            onClick={() => onViewProgress(item.positionId, playerColor, weaknessResult.minEvalLoss)}
                            className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border border-outline-variant hover:border-secondary/40 text-xs font-mono text-on-surface-variant hover:text-secondary transition cursor-pointer"
                            title="View progress"
                          >
                            <TrendingUp className="w-3.5 h-3.5" />
                            <span>View Progress</span>
                          </button>
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
                          className="flex items-center space-x-1 px-3 py-1.5 bg-primary-container hover:bg-primary text-on-primary-container rounded-lg font-mono text-xs font-bold transition shadow-sm cursor-pointer"
                        >
                          <Swords className="w-3.5 h-3.5" />
                          <span>Practice Position</span>
                        </button>
                      </div>
                    </div>
                  </PositionCard>
                );
              })}
            </div>
          ) : (
            <div data-testid="weakness-list-container" className="space-y-3">
              {weaknesses.map((item) => {
                const activeColorInFen = item.fen ? item.fen.split(' ')[1] : 'w';
                const playerColor: 'WHITE' | 'BLACK' =
                  activeColorInFen === 'b' ? 'BLACK' : activeColorInFen === 'w' ? 'WHITE' : 'WHITE';

                const tcStats = timeControlFilter !== 'ALL' && item.timeControlStats ? item.timeControlStats[timeControlFilter] : null;
                const phStats = phaseFilter !== 'ALL' && item.phaseStats ? item.phaseStats[phaseFilter] : null;

                const displayMistakeRate = phStats ? phStats.mistakeRate * 100 : tcStats ? tcStats.mistakeRate * 100 : item.mistakeRate;
                const displayMistakeCount = phStats ? phStats.mistakeCount : tcStats ? tcStats.mistakeCount : item.mistakeCount;
                const displayTimesReached = phStats ? phStats.timesReached : tcStats ? tcStats.timesReached : item.timesReached;
                const displayAverageLoss = phStats ? phStats.averageLoss : tcStats ? tcStats.averageLoss : item.averageLoss;

                const colorBadge = playerColor === 'WHITE'
                  ? <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-white text-slate-900">WHITE</span>
                  : <span className="px-1.5 py-0.5 rounded text-[9px] font-mono font-bold bg-surface-container-highest text-on-surface border border-outline-variant">BLACK</span>;

                const openingText = item.openingContext ? (
                  <a
                    href={item.openingContext.ecoUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-mono text-xs font-semibold text-primary underline"
                  >
                    {item.openingContext.eco}
                  </a>
                ) : (
                  <span className="text-outline font-mono text-xs">Position</span>
                );

                const prac = item.practicalEvidence;
                const tcBreakdown = getPracticalTcBreakdown(item.timeControlStats);

                return (
                  <div
                    key={item.positionId}
                    className="bg-surface-container-low border border-outline-variant hover:border-outline rounded-xl p-3 flex flex-col lg:flex-row items-stretch lg:items-center justify-between gap-4 transition shadow-sm"
                  >
                    {/* Column 1: Board Thumbnail (88x88) + Orientation + Opening */}
                    <div className="flex items-center space-x-3 shrink-0">
                      <div className="w-[88px] h-[88px] bg-surface-container-lowest border border-outline-variant rounded-lg p-1 shrink-0 overflow-hidden">
                        <ChessBoardView
                          fen={item.fen}
                          boardOrientation={playerColor}
                          allowDragging={false}
                          containerClassName="w-full h-full"
                          frameClassName="w-full h-full"
                        />
                      </div>
                      <div className="space-y-1 min-w-0">
                        <div className="flex items-center gap-1.5 flex-wrap">
                          {colorBadge}
                          {openingText}
                        </div>
                        {item.lastSeenAt && formatLastSeen(item.lastSeenAt) && (
                          <div className="text-[11px] font-mono text-outline">
                            {formatLastSeen(item.lastSeenAt)}
                          </div>
                        )}
                        <div className="text-[10px] font-mono text-outline">
                          {displayTimesReached} encounters
                        </div>
                      </div>
                    </div>

                    {/* Column 2: Historical Blunder Metrics */}
                    <div className="grid grid-cols-2 gap-3 text-xs font-mono py-1 px-3 bg-surface-container-lowest/60 rounded-lg border border-outline-variant/60">
                      <div>
                        <span className="text-[10px] text-outline font-sans block">Mistake Rate</span>
                        <span className="font-bold text-amber-accent">
                          {formatDecimal(displayMistakeRate, 1)}% ({displayMistakeCount}x)
                        </span>
                      </div>
                      <div>
                        <span className="text-[10px] text-outline font-sans block">Avg Mistake Loss</span>
                        <span className="font-bold text-error">
                          -{formatPawnLoss(displayAverageLoss)} pawns
                        </span>
                      </div>
                    </div>

                    {/* Column 3: Practical Game Outcomes */}
                    {prac && prac.eligibleGames > 0 && prac.scoreRate !== null ? (
                      <div className="grid grid-cols-2 gap-3 text-xs font-mono py-1 px-3 bg-surface-container-lowest/60 rounded-lg border border-outline-variant/60 flex-1">
                        <div>
                          <span className="text-[10px] text-outline font-sans block">
                            W / D / L ({prac.eligibleGames} games)
                          </span>
                          <span className="text-on-surface">
                            {prac.wins} / {prac.draws} / {prac.losses}
                          </span>
                        </div>
                        <div>
                          <span className="text-[10px] text-outline font-sans block">
                            Score Rate / Win Rate
                          </span>
                          <span className="text-secondary">
                            {formatScoreRate(prac.scoreRate)} / {formatScoreRate(
                              typeof prac.winRate === 'number' && !isNaN(prac.winRate)
                                ? prac.winRate
                                : prac.wins / prac.eligibleGames
                            )}
                          </span>
                        </div>
                        {tcBreakdown.length > 0 && (
                          <div data-testid="time-control-breakdown-list" className="col-span-2 pt-1 border-t border-outline-variant/40 flex flex-wrap gap-2 text-[10px]">
                            {tcBreakdown.map(({ key, label, stats }) => (
                              <span key={key} className="inline-flex items-center gap-1 text-outline">
                                <span className="font-semibold text-on-surface">{label}:</span>
                                <span>{`${stats.wins ?? 0}W·${stats.draws ?? 0}D·${stats.losses ?? 0}L`}</span>
                                <span className="text-secondary">{formatScoreRate(stats.scoreRate, '—')}</span>
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="flex-1" />
                    )}

                    {/* Column 4: Contextual Actions */}
                    <div className="flex items-center space-x-2 shrink-0 self-end lg:self-center">
                      {item.gameUrls && item.gameUrls.length > 0 && (
                        <button
                          type="button"
                          onClick={() => setActiveGameModalUrls(item.gameUrls)}
                          className="px-2.5 py-1.5 text-xs font-mono text-outline hover:text-on-surface rounded-lg border border-outline-variant transition cursor-pointer"
                          title="View actual games"
                        >
                          View Games ({item.gameUrls.length})
                        </button>
                      )}

                      {onViewProgress && (
                        <button
                          type="button"
                          onClick={() => onViewProgress(item.positionId, playerColor, weaknessResult.minEvalLoss)}
                          className="px-2.5 py-1.5 text-xs font-mono text-on-surface-variant hover:text-secondary rounded-lg border border-outline-variant transition cursor-pointer"
                          title="View progress"
                        >
                          View Progress
                        </button>
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
                        className="px-3 py-1.5 bg-primary-container hover:bg-primary text-on-primary-container rounded-lg font-mono text-xs font-bold transition shadow-sm cursor-pointer"
                      >
                        Practice Position
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}

          {/* Permanently Mounted Sentinel & Infinite Scroll Loading Indicator */}
          <div
            ref={sentinelRef}
            data-testid="weaknesses-sentinel"
            className="py-4 flex justify-center items-center min-h-[50px]"
          >
            {isLoadingMore && (
              <div className="flex items-center space-x-2 text-xs font-bold text-outline bg-surface-container-low px-4 py-2 rounded-xl border border-outline-variant">
                <div className="w-4 h-4 border-2 border-primary border-t-transparent rounded-full animate-spin" />
                <span>Loading more weaknesses...</span>
              </div>
            )}
            {!isLoadingMore && loadMoreError && (
              <div
                data-testid="weaknesses-load-more-error"
                className="flex flex-col sm:flex-row items-center gap-3 bg-surface-container-low px-4 py-3 rounded-xl border border-error-container text-xs"
              >
                <div className="flex items-center gap-2 text-error font-semibold">
                  <AlertCircle className="w-4 h-4 text-error shrink-0" />
                  <span>We couldn&apos;t load more weaknesses. Please try again.</span>
                </div>
                <button
                  type="button"
                  onClick={() => loadNextPage(true)}
                  className="px-4 py-2 bg-surface-container-high hover:bg-surface-bright text-on-surface font-bold rounded-xl border border-outline-variant transition inline-flex items-center gap-1.5 cursor-pointer"
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
                className="px-4 py-2 bg-surface-container-low hover:bg-surface-container text-on-surface-variant hover:text-on-surface text-xs font-bold rounded-xl border border-outline-variant transition cursor-pointer"
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
