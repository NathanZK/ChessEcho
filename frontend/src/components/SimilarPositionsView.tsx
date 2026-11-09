'use client';

import React, { useState, useEffect, useRef } from 'react';
import { StatusBanner } from './StatusBanner';
import { SectionHeader } from './SectionHeader';
import { SimilarPositionCard } from './SimilarPositionCard';
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
  limit?: number;
  mode?: 'STRUCTURAL' | 'DYNAMIC';
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

const SUPPORTED_LIMITS = [5, 10, 20, 30, 40, 50] as const;
const DEFAULT_LIMIT = 20;

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
  const [selectedOccurrenceId, setSelectedOccurrenceId] = useState<string | null>(
    initialSnapshot?.selectedOccurrenceId ?? null
  );
  const [limit, setLimit] = useState<number>(initialSnapshot?.limit ?? DEFAULT_LIMIT);
  const [retrievalMode, setRetrievalMode] = useState<'STRUCTURAL' | 'DYNAMIC'>(
    initialSnapshot?.mode ?? 'STRUCTURAL'
  );
  const [inputError, setInputError] = useState<string | null>(null);
  const [incompletePiecePlacement, setIncompletePiecePlacement] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [hasSearched, setHasSearched] = useState<boolean>(
    Boolean(initialSnapshot?.queryFen || initialSnapshot?.results?.length)
  );

  const activeAccountIdentifier = connectedAccount?.id ?? guestUsername ?? null;
  const requestVersionRef = useRef<number>(0);

  const onSnapshotChangeRef = useRef(onSnapshotChange);
  useEffect(() => {
    onSnapshotChangeRef.current = onSnapshotChange;
  }, [onSnapshotChange]);

  const latestSnapshotRef = useRef<SimilarPositionsReturnSnapshot>({
    queryFen: initialSnapshot?.queryFen ?? '',
    fenInput: initialSnapshot?.fenInput ?? '',
    results: initialSnapshot?.results ?? [],
    selectedOccurrenceId: initialSnapshot?.selectedOccurrenceId ?? null,
    activeAccountId: connectedAccount?.id ?? guestUsername ?? null,
    limit: initialSnapshot?.limit ?? DEFAULT_LIMIT,
  });

  useEffect(() => {
    latestSnapshotRef.current = {
      queryFen,
      fenInput,
      results,
      selectedOccurrenceId,
      activeAccountId: activeAccountIdentifier,
      limit,
    };
  }, [queryFen, fenInput, results, selectedOccurrenceId, activeAccountIdentifier, limit]);

  const handleFenChange = (value: string) => {
    setFenInput(value);
    if (inputError) setInputError(null);
    if (incompletePiecePlacement) setIncompletePiecePlacement(null);
  };

  const handleClearFen = () => {
    setFenInput('');
    setInputError(null);
    setIncompletePiecePlacement(null);
  };

  const handleSampleClick = (fen: string) => {
    setFenInput(fen);
    setInputError(null);
    setIncompletePiecePlacement(null);
  };

  const executeSearch = async (
    validFenString: string,
    searchLimit: number = limit,
    searchMode: 'STRUCTURAL' | 'DYNAMIC' = retrievalMode
  ) => {
    if (!connectedAccount && !guestUsername) {
      setSearchError('Connect a Chess.com account or specify a guest username to search.');
      return;
    }

    const currentVersion = ++requestVersionRef.current;
    setInputError(null);
    setSearchError(null);
    setIncompletePiecePlacement(null);
    setIsLoading(true);

    try {
      const data = await fetchSimilarPositions({
        fen: validFenString,
        accountId: connectedAccount?.id,
        username: !connectedAccount ? guestUsername : undefined,
        limit: searchLimit,
        mode: searchMode,
      });

      if (currentVersion !== requestVersionRef.current) return;

      setQueryFen(data.queryFen);
      setResults(data.results);
      setHasSearched(true);
      const selectedId =
        data.results.length > 0 && data.results[0].occurrences.length > 0
          ? data.results[0].occurrences[0].id
          : null;
      setSelectedOccurrenceId(selectedId);
      const newSnapshot: SimilarPositionsReturnSnapshot = {
        queryFen: data.queryFen,
        fenInput: validFenString,
        results: data.results,
        selectedOccurrenceId: selectedId,
        activeAccountId: activeAccountIdentifier,
        limit: searchLimit,
        mode: searchMode,
      };
      latestSnapshotRef.current = newSnapshot;
      onSnapshotChangeRef.current?.(newSnapshot);
    } catch (err: unknown) {
      if (currentVersion !== requestVersionRef.current) return;
      const message = err instanceof Error ? err.message : 'Failed to search similar positions';
      setSearchError(message);
    } finally {
      if (currentVersion === requestVersionRef.current) {
        setIsLoading(false);
      }
    }
  };

  const handleModeChange = (newMode: 'STRUCTURAL' | 'DYNAMIC') => {
    if (newMode === retrievalMode) return;
    setRetrievalMode(newMode);
    const updatedSnapshot: SimilarPositionsReturnSnapshot = {
      ...latestSnapshotRef.current,
      mode: newMode,
    };
    latestSnapshotRef.current = updatedSnapshot;
    onSnapshotChangeRef.current?.(updatedSnapshot);

    if (hasSearched && queryFen) {
      void executeSearch(queryFen, limit, newMode);
    }
  };

  const handleSearch = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();

    const parseResult = parseFenInput(fenInput);
    if (!parseResult.ok) {
      setInputError(parseResult.error);
      setIncompletePiecePlacement(parseResult.missingActiveColor ? parseResult.piecePlacement ?? null : null);
      return;
    }

    await executeSearch(parseResult.fen, limit);
  };

  const handleLimitChange = (newLimit: number) => {
    setLimit(newLimit);
    const updatedSnapshot: SimilarPositionsReturnSnapshot = {
      ...latestSnapshotRef.current,
      limit: newLimit,
    };
    latestSnapshotRef.current = updatedSnapshot;
    onSnapshotChangeRef.current?.(updatedSnapshot);

    if (hasSearched && queryFen) {
      void executeSearch(queryFen, newLimit);
    }
  };

  const handleSelectActiveColor = async (turn: 'w' | 'b') => {
    if (!incompletePiecePlacement) return;
    const completedFen = `${incompletePiecePlacement} ${turn} - - 0 1`;
    setFenInput(completedFen);
    await executeSearch(completedFen, limit);
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
    <div className="mx-auto w-full max-w-[1536px] p-4 sm:p-6 lg:p-8 space-y-6 text-on-surface">
      {/* Header & Overview */}
      <SectionHeader
        title="Find Similar Personal Positions"
        description="Enter an arbitrary FEN to retrieve positions from your personal games matching static pawn structures and piece relationship patterns."
        actions={
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="text-on-surface-variant font-mono">Target account:</span>
            {connectedAccount ? (
              <span className="rounded-md bg-primary/10 border border-primary/30 px-2.5 py-1 text-primary font-mono font-medium">
                Connected: {connectedAccount.username} ({connectedAccount.platform})
              </span>
            ) : guestUsername ? (
              <span className="rounded-md bg-surface-container border border-outline-variant px-2.5 py-1 text-on-surface font-mono font-medium">
                Guest: {guestUsername} (Chess.com)
              </span>
            ) : (
              <span className="rounded-md bg-amber-accent/15 border border-amber-accent/30 px-2.5 py-1 text-amber-accent font-mono font-medium">
                No account selected
              </span>
            )}
          </div>
        }
      />

      {/* Search Controls */}
      <form
        onSubmit={handleSearch}
        className="rounded-2xl border border-outline-variant bg-surface-container-low p-6 space-y-4 shadow-xl"
      >
        <div className="space-y-2">
          <label htmlFor="fen-input" className="block text-sm font-semibold font-mono text-on-surface flex items-center gap-2">
            <span className="material-symbols-outlined text-primary text-base" aria-hidden="true">hub</span>
            <span>FEN Position</span>
          </label>
          <div className="flex flex-col sm:flex-row gap-2.5">
            <div className="relative flex-1 flex items-center">
              <input
                id="fen-input"
                aria-label="FEN Position"
                type="text"
                value={fenInput}
                onChange={(e) => handleFenChange(e.target.value)}
                placeholder="e.g. r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5"
                className={`w-full rounded-xl border bg-surface-container-lowest px-4 py-2.5 pr-10 text-sm font-mono text-on-surface placeholder:text-on-surface-variant/60 focus:outline-none transition ${
                  inputError
                    ? 'border-error focus:border-error'
                    : 'border-outline-variant focus:border-primary'
                }`}
              />
              {fenInput && (
                <button
                  type="button"
                  onClick={handleClearFen}
                  aria-label="Clear FEN input"
                  className="absolute right-3 text-on-surface-variant hover:text-on-surface cursor-pointer"
                >
                  <span className="material-symbols-outlined text-sm">close</span>
                </button>
              )}
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <div
                className="flex items-center rounded-xl bg-surface-container border border-outline-variant p-0.5 text-xs font-mono"
                role="group"
                aria-label="Retrieval Mode"
              >
                <button
                  type="button"
                  aria-pressed={retrievalMode === 'STRUCTURAL'}
                  onClick={() => handleModeChange('STRUCTURAL')}
                  className={`px-3 py-1.5 rounded-lg font-medium transition cursor-pointer ${
                    retrievalMode === 'STRUCTURAL'
                      ? 'bg-surface-container-high text-primary font-semibold shadow-sm'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  Structural
                </button>
                <button
                  type="button"
                  aria-pressed={retrievalMode === 'DYNAMIC'}
                  onClick={() => handleModeChange('DYNAMIC')}
                  className={`px-3 py-1.5 rounded-lg font-medium transition cursor-pointer ${
                    retrievalMode === 'DYNAMIC'
                      ? 'bg-surface-container-high text-primary font-semibold shadow-sm'
                      : 'text-on-surface-variant hover:text-on-surface'
                  }`}
                >
                  Dynamic
                </button>
              </div>

              <label htmlFor="limit-select" className="sr-only">
                Results limit
              </label>
              <select
                id="limit-select"
                aria-label="Results limit"
                value={limit}
                onChange={(e) => handleLimitChange(Number(e.target.value))}
                disabled={isLoading}
                className="bg-surface-container border border-outline-variant rounded-xl text-xs font-mono px-3 py-2.5 text-on-surface focus:outline-none focus:border-primary cursor-pointer disabled:opacity-50"
              >
                {SUPPORTED_LIMITS.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>

              <button
                type="submit"
                disabled={isLoading || !fenInput.trim() || !isAccountAvailable}
                className="inline-flex items-center gap-2 rounded-xl bg-primary-container hover:bg-primary px-5 py-2.5 text-sm font-semibold font-mono text-on-primary-container shadow-sm disabled:opacity-50 disabled:cursor-not-allowed transition cursor-pointer"
              >
                {isLoading ? (
                  <>
                    <span className="material-symbols-outlined text-base animate-spin">sync</span>
                    <span>Searching...</span>
                  </>
                ) : (
                  <>
                    <span className="material-symbols-outlined text-base">search</span>
                    <span>Search</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {inputError && (
            <div className="space-y-2 mt-1">
              <p className="text-xs text-error font-mono flex items-center gap-1.5">
                <span className="material-symbols-outlined text-sm">warning</span>
                <span>{inputError}</span>
              </p>
              {incompletePiecePlacement && (
                <div className="flex items-center gap-2 pt-1">
                  <span className="text-xs font-mono text-on-surface-variant">Set active color:</span>
                  <button
                    type="button"
                    onClick={() => handleSelectActiveColor('w')}
                    className="rounded-lg border border-outline-variant bg-surface-container px-3 py-1 text-xs font-mono font-medium text-on-surface hover:border-primary transition cursor-pointer"
                  >
                    White to move
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSelectActiveColor('b')}
                    className="rounded-lg border border-outline-variant bg-surface-container px-3 py-1 text-xs font-mono font-medium text-on-surface hover:border-primary transition cursor-pointer"
                  >
                    Black to move
                  </button>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Starter Sample Triggers */}
        <div className="space-y-1.5 pt-1">
          <span className="text-xs font-mono text-on-surface-variant">Quick sample positions:</span>
          <div className="flex flex-wrap gap-2">
            {SAMPLE_POSITIONS.map((sample) => (
              <button
                key={sample.name}
                type="button"
                onClick={() => handleSampleClick(sample.fen)}
                className="rounded-lg border border-outline-variant bg-surface-container-lowest px-3 py-1.5 text-xs font-mono text-on-surface-variant hover:text-on-surface hover:border-primary/50 transition cursor-pointer"
              >
                {sample.name}
              </button>
            ))}
          </div>
        </div>
      </form>

      {/* Global Search Error */}
      {searchError && (
        <div role="alert" className="rounded-2xl border border-error-container bg-error-container/20 p-4 text-sm text-error flex items-start gap-3">
          <span className="material-symbols-outlined text-base text-error mt-0.5">error</span>
          <div>
            <p className="font-semibold font-mono">Retrieval Error</p>
            <p className="mt-1 text-xs font-mono">{searchError}</p>
          </div>
        </div>
      )}

      {/* Account Missing State */}
      {!isAccountAvailable && (
        <div className="rounded-2xl border border-outline-variant bg-surface-container-low p-8 text-center space-y-3">
          <p className="text-base font-semibold text-on-surface">No Target Account Selected</p>
          <p className="text-sm text-on-surface-variant max-w-md mx-auto">
            Connect your Chess.com account or specify a guest username to search for similar positions in
            your games.
          </p>
        </div>
      )}

      {/* Results Section */}
      {isAccountAvailable && (
        <div className="space-y-4">
          {hasSearched && (
            <div className="flex items-center justify-between px-1">
              <span className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-surface-container border border-outline-variant text-xs font-mono text-on-surface">
                <span className="w-1.5 h-1.5 rounded-full bg-primary" />
                <span>Found <strong className="text-primary font-bold">{results.length}</strong> matching position{results.length === 1 ? '' : 's'}</span>
              </span>
              <span className="text-xs font-mono text-on-surface-variant hidden sm:inline">
                Matches sorted by similarity to query FEN
              </span>
            </div>
          )}

          {hasSearched && results.length === 0 && !isLoading && (
            <StatusBanner
              variant="empty"
              title="No Similar Positions Found"
              message="No personal game positions met the similarity criteria for this FEN. Try searching a different position or opening line."
            />
          )}

          {results.length > 0 && (
            <div className="grid gap-5 grid-cols-1 xl:grid-cols-2">
              {results.map((result) => (
                <SimilarPositionCard
                  key={result.positionId}
                  result={result}
                  onExplore={handleExplore}
                />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
