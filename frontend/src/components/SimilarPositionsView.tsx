'use client';

import React, { useState, useEffect } from 'react';
import { ChessBoardView } from './ChessBoardView';
import { ExternalGameLink } from './ExternalGameLink';
import { PositionCard } from './PositionCard';
import { StatusBanner } from './StatusBanner';
import { SectionHeader } from './SectionHeader';
import { Search, AlertCircle, RefreshCw } from 'lucide-react';
import {
  type AccountSummary,
  type SimilarPositionResult,
  fetchSimilarPositions,
} from '../services/api';
import { parseFenInput } from '../utils/positionImport';
import type { PositionExplorationStart } from './ExplorePositionModal';

export interface SimilarPositionsReturnSnapshot {
  queryFen: string;
  fenInput: string;
  results: SimilarPositionResult[];
  selectedOccurrenceId: string | null;
  activeAccountId: string | null;
}

export interface SimilarPositionsViewProps {
  connectedAccount?: AccountSummary;
  guestUsername?: string;
  onExplorePosition: (position: PositionExplorationStart) => void;
  onSnapshotChange?: (snapshot: SimilarPositionsReturnSnapshot | null) => void;
  initialSnapshot?: SimilarPositionsReturnSnapshot | null;
}

const SAMPLE_POSITIONS = [
  {
    name: 'Italian Game',
    fen: 'r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4',
  },
  {
    name: 'Sicilian Defense',
    fen: 'rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2',
  },
  {
    name: 'French Defense',
    fen: 'rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
  },
  {
    name: "Queen's Gambit",
    fen: 'rnbqkbnr/ppp1pppp/8/3p4/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2',
  },
];

export const SimilarPositionsView: React.FC<SimilarPositionsViewProps> = ({
  connectedAccount,
  guestUsername,
  onExplorePosition,
  onSnapshotChange,
  initialSnapshot,
}) => {
  const [fenInput, setFenInput] = useState<string>(initialSnapshot?.fenInput ?? '');
  const [queryFen, setQueryFen] = useState<string>(initialSnapshot?.queryFen ?? '');
  const [results, setResults] = useState<SimilarPositionResult[]>(initialSnapshot?.results ?? []);
  const [candidatesEvaluated, setCandidatesEvaluated] = useState<number>(0);
  const [selectedOccurrenceId, setSelectedOccurrenceId] = useState<string | null>(
    initialSnapshot?.selectedOccurrenceId ?? null
  );
  const [minScore, setMinScore] = useState<string>('');
  const [limit, setLimit] = useState<number>(20);
  const [inputError, setInputError] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [hasSearched, setHasSearched] = useState<boolean>(Boolean(initialSnapshot?.results?.length));

  const activeAccountIdentifier = connectedAccount?.id ?? guestUsername ?? null;

  const onSnapshotChangeRef = React.useRef(onSnapshotChange);
  useEffect(() => {
    onSnapshotChangeRef.current = onSnapshotChange;
  }, [onSnapshotChange]);

  const latestSnapshotRef = React.useRef<SimilarPositionsReturnSnapshot>({
    queryFen: initialSnapshot?.queryFen ?? '',
    fenInput: initialSnapshot?.fenInput ?? '',
    results: initialSnapshot?.results ?? [],
    selectedOccurrenceId: initialSnapshot?.selectedOccurrenceId ?? null,
    activeAccountId: connectedAccount?.id ?? guestUsername ?? null,
  });

  useEffect(() => {
    latestSnapshotRef.current = {
      queryFen,
      fenInput,
      results,
      selectedOccurrenceId,
      activeAccountId: activeAccountIdentifier,
    };
  }, [queryFen, fenInput, results, selectedOccurrenceId, activeAccountIdentifier]);

  const handleFenChange = (value: string) => {
    setFenInput(value);
    if (inputError) {
      setInputError(null);
    }
  };

  const handleSampleClick = (fen: string) => {
    setFenInput(fen);
    setInputError(null);
  };

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();

    const parseResult = parseFenInput(fenInput);
    if (!parseResult.ok) {
      setInputError(parseResult.error);
      return;
    }

    if (!connectedAccount && !guestUsername) {
      setSearchError('Connect a Chess.com account or specify a guest username to search.');
      return;
    }

    setInputError(null);
    setSearchError(null);
    setIsLoading(true);

    try {
      const parsedMinScore = minScore ? parseFloat(minScore) : undefined;
      const data = await fetchSimilarPositions({
        fen: parseResult.fen,
        accountId: connectedAccount?.id,
        username: !connectedAccount ? guestUsername : undefined,
        minScore: parsedMinScore !== undefined && !isNaN(parsedMinScore) ? parsedMinScore : undefined,
        limit,
      });

      setQueryFen(data.queryFen);
      setCandidatesEvaluated(data.candidatesEvaluated);
      setResults(data.results);
      setHasSearched(true);
      const selectedId =
        data.results.length > 0 && data.results[0].occurrences.length > 0
          ? data.results[0].occurrences[0].id
          : null;
      setSelectedOccurrenceId(selectedId);
      const newSnapshot: SimilarPositionsReturnSnapshot = {
        queryFen: data.queryFen,
        fenInput,
        results: data.results,
        selectedOccurrenceId: selectedId,
        activeAccountId: activeAccountIdentifier,
      };
      latestSnapshotRef.current = newSnapshot;
      onSnapshotChangeRef.current?.(newSnapshot);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to search similar positions';
      setSearchError(message);
    } finally {
      setIsLoading(false);
    }
  };

  const handleExplore = (fen: string) => {
    const parseResult = parseFenInput(fen);
    if (!parseResult.ok) {
      setInputError(`Cannot explore invalid position: ${parseResult.error}`);
      return;
    }
    onSnapshotChangeRef.current?.(latestSnapshotRef.current);
    onExplorePosition({
      fen: parseResult.fen,
      sanHistory: [],
      historyStartFen: parseResult.fen,
    });
  };

  const isAccountAvailable = Boolean(connectedAccount || guestUsername);

  return (
    <div className="mx-auto w-full max-w-[1536px] p-4 sm:p-6 lg:p-8 space-y-6">
      {/* Header & Overview */}
      <SectionHeader
        title="Find Similar Personal Positions"
        description="Enter an arbitrary FEN to retrieve positions from your personal games matching static pawn structures and piece relationship patterns."
        actions={
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="text-slate-400">Target account:</span>
            {connectedAccount ? (
              <span className="rounded-md bg-emerald-950/60 border border-emerald-800/60 px-2.5 py-1 text-emerald-300 font-medium">
                Connected: {connectedAccount.username} ({connectedAccount.platform})
              </span>
            ) : guestUsername ? (
              <span className="rounded-md bg-sky-950/60 border border-sky-800/60 px-2.5 py-1 text-sky-300 font-medium">
                Guest: {guestUsername} (Chess.com)
              </span>
            ) : (
              <span className="rounded-md bg-amber-950/60 border border-amber-800/60 px-2.5 py-1 text-amber-300 font-medium">
                No account selected
              </span>
            )}
          </div>
        }
      />

      {/* Search Controls */}
      <form
        onSubmit={handleSearch}
        className="rounded-2xl border border-slate-800 bg-slate-900/60 p-6 space-y-4 backdrop-blur"
      >
        <div className="space-y-2">
          <label htmlFor="fen-input" className="block text-sm font-semibold text-slate-200">
            FEN Position
          </label>
          <div className="flex gap-2">
            <input
              id="fen-input"
              type="text"
              value={fenInput}
              onChange={(e) => handleFenChange(e.target.value)}
              placeholder="e.g. r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5"
              className={`flex-1 rounded-xl border bg-slate-950 px-4 py-2.5 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:ring-2 ${
                inputError
                  ? 'border-red-500 focus:border-red-500 focus:ring-red-500/20'
                  : 'border-slate-800 focus:border-emerald-500 focus:ring-emerald-500/20'
              }`}
            />
            <button
              type="submit"
              disabled={isLoading || !fenInput.trim() || !isAccountAvailable}
              className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed transition"
            >
              {isLoading ? (
                <>
                  <RefreshCw className="h-4 w-4 animate-spin" />
                  Searching...
                </>
              ) : (
                <>
                  <Search className="h-4 w-4" />
                  Search
                </>
              )}
            </button>
          </div>
          {inputError && (
            <p className="text-xs text-red-400 flex items-center gap-1 mt-1">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" />
              {inputError}
            </p>
          )}
        </div>

        {/* Starter Sample Triggers */}
        <div className="space-y-1.5">
          <span className="text-xs font-medium text-slate-400">Quick sample positions:</span>
          <div className="flex flex-wrap gap-2">
            {SAMPLE_POSITIONS.map((sample) => (
              <button
                key={sample.name}
                type="button"
                onClick={() => handleSampleClick(sample.fen)}
                className="rounded-lg border border-slate-800 bg-slate-950/80 px-2.5 py-1 text-xs text-slate-300 hover:border-slate-700 hover:text-white transition"
              >
                {sample.name}
              </button>
            ))}
          </div>
        </div>

        {/* Additional Filters */}
        <div className="flex flex-wrap gap-4 pt-2 border-t border-slate-800/80 text-xs text-slate-300">
          <div className="flex items-center gap-2">
            <label htmlFor="min-score">Min BM25 Score:</label>
            <input
              id="min-score"
              type="number"
              min="0"
              step="0.5"
              value={minScore}
              onChange={(e) => setMinScore(e.target.value)}
              placeholder="0.0"
              className="w-20 rounded-lg border border-slate-800 bg-slate-950 px-2 py-1 text-slate-100 placeholder-slate-600 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            />
          </div>
          <div className="flex items-center gap-2">
            <label htmlFor="limit-select">Results limit:</label>
            <select
              id="limit-select"
              value={limit}
              onChange={(e) => setLimit(parseInt(e.target.value, 10))}
              className="rounded-lg border border-slate-800 bg-slate-950 px-2 py-1 text-slate-100 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            >
              <option value="10">10</option>
              <option value="20">20</option>
              <option value="50">50</option>
              <option value="100">100</option>
            </select>
          </div>
        </div>
      </form>

      {/* Global Search Error */}
      {searchError && (
        <div className="rounded-2xl border border-red-900/50 bg-red-950/30 p-4 text-sm text-red-300 flex items-start gap-3">
          <AlertCircle className="h-5 w-5 shrink-0 text-red-400 mt-0.5" />
          <div>
            <p className="font-semibold text-red-200">Retrieval Error</p>
            <p className="mt-1 text-xs">{searchError}</p>
          </div>
        </div>
      )}

      {/* Account Missing State */}
      {!isAccountAvailable && (
        <div className="rounded-2xl border border-slate-800 bg-slate-900/40 p-8 text-center space-y-3">
          <p className="text-base font-semibold text-slate-200">No Target Account Selected</p>
          <p className="text-sm text-slate-400 max-w-md mx-auto">
            Connect your Chess.com account or specify a guest username to search for similar positions in
            your games.
          </p>
        </div>
      )}

      {/* Results Section */}
      {isAccountAvailable && (
        <div className="space-y-4">
          {hasSearched && (
            <div className="flex items-center justify-between text-xs text-slate-400 px-1">
              <span>
                Found <strong className="text-slate-200">{results.length}</strong> matching position
                {results.length === 1 ? '' : 's'}
              </span>
              {candidatesEvaluated > 0 && (
                <span>Evaluated {candidatesEvaluated} candidate positions</span>
              )}
            </div>
          )}

          {hasSearched && results.length === 0 && !isLoading && (
            <StatusBanner
              variant="empty"
              title="No Similar Positions Found"
              message="No personal game positions met the similarity criteria for this FEN. Try lowering the minimum score threshold or searching a different position."
            />
          )}

          {results.length > 0 && (
            <div className="grid gap-4 sm:grid-cols-1 md:grid-cols-2">
              {results.map((result) => {
                const primaryOccurrence = result.occurrences[0];
                return (
                  <PositionCard
                    key={result.positionId}
                    className="flex flex-col gap-4"
                  >
                    <div className="flex items-start justify-between border-b border-slate-800/80 pb-3">
                      <div>
                        <span className="text-xs font-semibold uppercase tracking-wider text-emerald-400">
                          Rank #{result.rank}
                        </span>
                        <h2 className="text-sm font-semibold text-white">
                          Score: {result.score.toFixed(2)}
                        </h2>
                      </div>
                      <button
                        type="button"
                        onClick={() => handleExplore(result.fen)}
                        className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600/20 border border-emerald-500/40 px-3 py-1.5 text-xs font-medium text-emerald-300 hover:bg-emerald-600/30 transition"
                      >
                        Explore this position
                      </button>
                    </div>

                    <div className="grid gap-4 sm:grid-cols-[160px_1fr]">
                      <div className="aspect-square w-full max-w-[160px] overflow-hidden rounded-xl border border-slate-700 mx-auto">
                        <ChessBoardView
                          fen={result.fen}
                          boardOrientation={primaryOccurrence?.playerColor ?? 'white'}
                          allowDragging={false}
                          containerClassName="w-full h-full"
                          frameClassName="w-full h-full"
                        />
                      </div>

                      <div className="flex flex-col justify-between text-xs space-y-2 min-w-0">
                        {primaryOccurrence ? (
                          <div className="space-y-1.5">
                            <p className="text-slate-300">
                              Played move:{' '}
                              <strong className="text-white font-mono text-sm">
                                {primaryOccurrence.playedMove}
                              </strong>{' '}
                              <span className="text-slate-400">
                                (ply {primaryOccurrence.plyNumber}, {primaryOccurrence.playerColor.toLowerCase()})
                              </span>
                            </p>
                            {primaryOccurrence.opponentUsername && (
                              <p className="text-slate-400 truncate">
                                Opponent: <span className="text-slate-200">{primaryOccurrence.opponentUsername}</span>
                              </p>
                            )}
                            {primaryOccurrence.timeControl && (
                              <p className="text-slate-400">
                                Time control: <span className="text-slate-200">{primaryOccurrence.timeControl}</span>
                              </p>
                            )}
                            {primaryOccurrence.gameResult && (
                              <p className="text-slate-400">
                                Result:{' '}
                                <span
                                  className={`font-medium ${
                                    primaryOccurrence.gameResult.toLowerCase() === 'win'
                                      ? 'text-emerald-400'
                                      : primaryOccurrence.gameResult.toLowerCase() === 'loss'
                                      ? 'text-rose-400'
                                      : 'text-amber-400'
                                  }`}
                                >
                                  {primaryOccurrence.gameResult}
                                </span>
                              </p>
                            )}
                            {primaryOccurrence.playedAt && (
                              <p className="text-slate-500">
                                Date: {new Date(primaryOccurrence.playedAt).toLocaleDateString()}
                              </p>
                            )}
                          </div>
                        ) : (
                          <p className="text-slate-500 italic">No occurrence details available</p>
                        )}

                        <div className="pt-2 border-t border-slate-800/80">
                          {primaryOccurrence?.chessComUrl ? (
                            <ExternalGameLink
                              url={primaryOccurrence.chessComUrl}
                              label="View move on Chess.com"
                              platform="chesscom"
                            />
                          ) : (
                            <span className="text-slate-500 italic">
                              Chess.com link unavailable
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  </PositionCard>
                );
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
