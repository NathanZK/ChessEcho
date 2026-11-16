'use client';

import React, { useState, useEffect, useSyncExternalStore, useRef } from 'react';
import { ArrowLeft, AlertTriangle } from 'lucide-react';
import {
  type GameSummary,
  type GameAnalysisResponse,
  type AnalyzedPly,
  type SimilarPositionResult,
  fetchGames,
  analyzeGame,
} from '../services/api';
import { activeAccountStore } from '../utils/browserStores';
import {
  DEFAULT_EVAL_LOSS_THRESHOLD,
  DEFAULT_TIMING_FILTERS,
  type DecisionSeverityCategory,
  filterQualifyingPlies,
  toggleTimingCategory,
} from '../utils/gameAnalysisFilters';
import { GameAnalysisFilterBar } from './GameAnalysisFilterBar';
import { GameAnalysisPliesList } from './GameAnalysisPliesList';
import { GameAnalysisMoveDetail } from './GameAnalysisMoveDetail';
import { GameCard } from './GameCard';
import { AccountConnectionPromptModal } from './AccountConnectionPromptModal';
import {
  filterGamesByTimeControl,
  getImportPresetForCategory,
  TIME_CONTROL_CHIPS,
  type GameTimeControlCategory,
} from '../utils/timeControl';

export interface GameAnalysisViewProps {
  initialGameId?: string | null;
  initialPlyNumber?: number | null;
  initialThreshold?: number;
  initialTimingFilters?: DecisionSeverityCategory[];
  initialExpandedSections?: { structural?: boolean; dynamic?: boolean };
  accountId?: string;
  guestUsername?: string;
  sessionStatus?: 'loading' | 'authenticated' | 'unauthenticated' | 'error';
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
  onPracticeSimilarPosition?: (match: SimilarPositionResult, analysis: GameAnalysisResponse, activePly?: AnalyzedPly) => void;
  onNavigateImport?: (timeControls?: string[]) => void;
  onExpandedSectionsChange?: (sections: { structural?: boolean; dynamic?: boolean }) => void;
  onThresholdChange?: (threshold: number) => void;
  onTimingFiltersChange?: (filters: DecisionSeverityCategory[]) => void;
}

const GamesListView: React.FC<{
  games: GameSummary[];
  isLoading: boolean;
  selectedCategory: GameTimeControlCategory;
  onSelectCategory: (category: GameTimeControlCategory) => void;
  onSelectGame: (gameId: string) => void;
  onNavigateImport?: (timeControls?: string[]) => void;
}> = ({
  games,
  isLoading,
  selectedCategory,
  onSelectCategory,
  onSelectGame,
  onNavigateImport,
}) => {
  const filteredGames = filterGamesByTimeControl(games, selectedCategory);
  const categoryLabel =
    TIME_CONTROL_CHIPS.find((c) => c.category === selectedCategory)?.label ||
    selectedCategory;

  const handleHeaderImport = () => onNavigateImport?.(getImportPresetForCategory(selectedCategory));
  const handleEmptyImport = () => onNavigateImport?.(['BULLET', 'BLITZ', 'RAPID', 'CLASSICAL']);
  const handleFilterEmptyImport = () => onNavigateImport?.(getImportPresetForCategory(selectedCategory));

  return (
    <div className="w-full max-w-6xl mx-auto px-4 py-8 font-mono">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 mb-6">
        <div>
          <h2 className="text-xl font-bold text-on-surface">My Games</h2>
          <p className="text-xs text-on-surface-variant mt-1">
            Select a game to analyze with Stockfish and explore mistake patterns.
          </p>
        </div>
        {onNavigateImport && games.length > 0 && (
          <button
            type="button"
            onClick={handleHeaderImport}
            className="self-start sm:self-auto inline-flex items-center gap-1.5 px-3 py-1.5 bg-primary text-on-primary font-semibold rounded text-xs hover:bg-primary/90 transition cursor-pointer"
          >
            <span>Import Games</span>
          </button>
        )}
      </div>

      {/* Filter Chips */}
      <div className="flex flex-wrap items-center gap-2 mb-6" role="tablist" aria-label="Time-control filters">
        {TIME_CONTROL_CHIPS.map(({ category, label }) => {
          const isSelected = selectedCategory === category;
          return (
            <button
              key={category}
              type="button"
              role="button"
              onClick={() => onSelectCategory(category)}
              className={`px-3 py-1.5 rounded-full text-xs font-semibold transition cursor-pointer border ${
                isSelected
                  ? 'bg-primary text-on-primary border-primary'
                  : 'bg-surface-container-high text-on-surface-variant border-outline-variant hover:text-on-surface hover:bg-surface-container'
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>

      {isLoading ? (
        <div className="p-8 text-center text-on-surface-variant text-sm">Loading games...</div>
      ) : games.length === 0 ? (
        <div className="p-8 text-center text-on-surface-variant text-sm bg-surface-container rounded-lg border border-outline-variant space-y-4">
          <p>No games found. Please import some games to begin analysis.</p>
          <button
            type="button"
            onClick={handleEmptyImport}
            className="inline-flex items-center gap-1.5 px-4 py-2 bg-primary text-on-primary font-semibold rounded text-xs hover:bg-primary/90 transition cursor-pointer"
          >
            <span>Import Games</span>
          </button>
        </div>
      ) : filteredGames.length === 0 ? (
        <div className="p-8 text-center text-on-surface-variant text-sm bg-surface-container rounded-lg border border-outline-variant space-y-4">
          <p>No {categoryLabel} games found under active filter.</p>
          <div className="flex items-center justify-center gap-3">
            <button
              type="button"
              onClick={handleFilterEmptyImport}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-primary text-on-primary font-semibold rounded text-xs hover:bg-primary/90 transition cursor-pointer"
            >
              <span>Import {categoryLabel} Games</span>
            </button>
            <button
              type="button"
              onClick={() => onSelectCategory('ALL')}
              className="px-3 py-1.5 bg-surface-container-high border border-outline-variant text-on-surface-variant hover:text-on-surface rounded text-xs font-semibold transition cursor-pointer"
            >
              Clear filter
            </button>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {filteredGames.map((g) => (
            <GameCard key={g.id} game={g} onSelectGame={onSelectGame} />
          ))}
        </div>
      )}
    </div>
  );
};

interface GameAnalysisDashboardProps {
  analysis: GameAnalysisResponse;
  error: string | null;
  evalLossThreshold: number;
  timingFilters: DecisionSeverityCategory[];
  qualifyingPlies: AnalyzedPly[];
  activePly: AnalyzedPly | null;
  initialExpandedSections?: { structural?: boolean; dynamic?: boolean };
  accountId?: string;
  guestUsername?: string;
  onBackToGames: () => void;
  onEvalLossThresholdChange: (value: number) => void;
  onToggleTimingFilter: (category: DecisionSeverityCategory) => void;
  onSelectPly: (plyNumber: number) => void;
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
  onPracticeSimilarPosition?: (
    match: SimilarPositionResult,
    analysis: GameAnalysisResponse,
    activePly?: AnalyzedPly
  ) => void;
  onExpandedSectionsChange?: (sections: { structural?: boolean; dynamic?: boolean }) => void;
}

const GameAnalysisDashboard: React.FC<GameAnalysisDashboardProps> = ({
  analysis,
  error,
  evalLossThreshold,
  timingFilters,
  qualifyingPlies,
  activePly,
  initialExpandedSections,
  accountId,
  guestUsername,
  onBackToGames,
  onEvalLossThresholdChange,
  onToggleTimingFilter,
  onSelectPly,
  onPracticeMistake,
  onPracticeSimilarPosition,
  onExpandedSectionsChange,
}) => (
  <div className="w-full max-w-6xl mx-auto px-4 py-8 font-mono">
    {error && <div className="mb-4 text-xs text-error">{error}</div>}
    <div className="flex items-center justify-between mb-6 pb-4 border-b border-outline-variant">
      <button
        type="button"
        onClick={onBackToGames}
        className="flex items-center gap-2 text-xs font-semibold text-primary hover:text-primary/80 transition cursor-pointer"
      >
        <ArrowLeft className="w-4 h-4" />
        <span>Back to My Games</span>
      </button>
      <div className="text-right">
        <span className="text-sm font-bold text-on-surface">Game Analysis</span>
        <span className="text-xs text-on-surface-variant block">
          {analysis.whiteUsername} vs {analysis.blackUsername} ({analysis.timeControl || 'Standard'})
        </span>
      </div>
    </div>

    {analysis.analysisStatus === 'CAPACITY_EXCEEDED' && (
      <div className="p-3 mb-4 bg-amber-500/10 border border-amber-500/30 rounded text-amber-400 text-xs flex items-center gap-2">
        <AlertTriangle className="w-4 h-4 shrink-0" />
        <span>Stockfish analysis capacity reached; displaying available move evaluations.</span>
      </div>
    )}

    <GameAnalysisFilterBar
      evalLossThreshold={evalLossThreshold}
      onEvalLossThresholdChange={onEvalLossThresholdChange}
      timingFilters={timingFilters}
      onToggleTimingFilter={onToggleTimingFilter}
    />

    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
      <GameAnalysisPliesList
        totalPliesCount={analysis.plies.length}
        qualifyingPlies={qualifyingPlies}
        selectedPlyNumber={activePly?.plyNumber ?? null}
        onSelectPly={onSelectPly}
        evalLossThreshold={evalLossThreshold}
      />
      <GameAnalysisMoveDetail
        activePly={activePly}
        analysis={analysis}
        evalLossThreshold={evalLossThreshold}
        accountId={accountId}
        guestUsername={guestUsername}
        initialExpandedSections={initialExpandedSections}
        onExpandedSectionsChange={onExpandedSectionsChange}
        onPracticeMistake={onPracticeMistake}
        onPracticeSimilarPosition={onPracticeSimilarPosition}
      />
    </div>
  </div>
);

function useGameList(
  initialGameId: string | null,
  activeAccountId: string | undefined,
  sessionStatus: 'loading' | 'authenticated' | 'unauthenticated' | 'error' | undefined,
  setError: (err: string | null) => void
) {
  const [games, setGames] = useState<GameSummary[]>([]);
  const [isLoadingGames, setIsLoadingGames] = useState(false);

  useEffect(() => {
    if (initialGameId) return;

    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error') {
      queueMicrotask(() => {
        setGames([]);
        setIsLoadingGames(false);
      });
      return;
    }

    if (sessionStatus === 'loading') {
      queueMicrotask(() => {
        setIsLoadingGames(true);
      });
      return;
    }

    if (!activeAccountId) {
      queueMicrotask(() => {
        setGames([]);
        setIsLoadingGames(false);
      });
      return;
    }

    let isCurrent = true;
    queueMicrotask(() => {
      if (isCurrent) {
        setIsLoadingGames(true);
        setError(null);
      }
    });

    fetchGames({ page: 0, size: 50, accountId: activeAccountId })
      .then((res) => {
        if (isCurrent) setGames(res.content);
      })
      .catch((err: unknown) => {
        if (isCurrent) setError(err instanceof Error ? err.message : 'Failed to load games');
      })
      .finally(() => {
        if (isCurrent) setIsLoadingGames(false);
      });

    return () => {
      isCurrent = false;
    };
  }, [initialGameId, activeAccountId, sessionStatus, setError]);

  return { games, isLoadingGames };
}

function useGameAnalysis(
  selectedGameId: string | null,
  initialPlyNumber: number | null,
  sessionStatus: 'loading' | 'authenticated' | 'unauthenticated' | 'error' | undefined,
  setIsAccountPromptOpen: (open: boolean) => void,
  setError: (err: string | null) => void
) {
  const [analysis, setAnalysis] = useState<GameAnalysisResponse | null>(null);
  const [selectedPlyNumber, setSelectedPlyNumber] = useState<number | null>(initialPlyNumber);

  useEffect(() => {
    if (!selectedGameId) return;
    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error') {
      queueMicrotask(() => {
        setIsAccountPromptOpen(true);
      });
      return;
    }
    let isMounted = true;
    analyzeGame(selectedGameId)
      .then((res) => {
        if (isMounted) {
          setAnalysis(res);
          if (initialPlyNumber) setSelectedPlyNumber(initialPlyNumber);
        }
      })
      .catch((err: unknown) => {
        if (isMounted) setError(err instanceof Error ? err.message : 'Analysis failed');
      });
    return () => {
      isMounted = false;
    };
  }, [selectedGameId, initialPlyNumber, sessionStatus, setIsAccountPromptOpen, setError]);

  return { analysis, setAnalysis, selectedPlyNumber, setSelectedPlyNumber };
}

export const GameAnalysisView: React.FC<GameAnalysisViewProps> = ({
  initialGameId = null,
  initialPlyNumber = null,
  initialThreshold = DEFAULT_EVAL_LOSS_THRESHOLD,
  initialTimingFilters = DEFAULT_TIMING_FILTERS,
  initialExpandedSections,
  accountId,
  guestUsername,
  onPracticeMistake,
  onPracticeSimilarPosition,
  onNavigateImport,
  onExpandedSectionsChange,
  onThresholdChange,
  onTimingFiltersChange,
  sessionStatus,
}) => {
  const activeAccount = useSyncExternalStore(
    activeAccountStore.subscribe,
    activeAccountStore.getSnapshot,
    activeAccountStore.getServerSnapshot
  );
  const activeAccountId = activeAccount?.id || accountId;
  const [isAccountPromptOpen, setIsAccountPromptOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { games, isLoadingGames } = useGameList(
    initialGameId,
    activeAccountId,
    sessionStatus,
    setError
  );
  const [selectedCategory, setSelectedCategory] = useState<GameTimeControlCategory>('ALL');
  const [selectedGameId, setSelectedGameId] = useState<string | null>(initialGameId);
  const { analysis, setAnalysis, selectedPlyNumber, setSelectedPlyNumber } =
    useGameAnalysis(selectedGameId, initialPlyNumber, sessionStatus, setIsAccountPromptOpen, setError);
  const [evalLossThreshold, setEvalLossThreshold] = useState<number>(initialThreshold);
  const [timingFilters, setTimingFilters] =
    useState<DecisionSeverityCategory[]>(initialTimingFilters);

  const prevAccountIdRef = useRef(activeAccountId);
  useEffect(() => {
    if (prevAccountIdRef.current !== activeAccountId) {
      prevAccountIdRef.current = activeAccountId;
      if (!initialGameId) {
        queueMicrotask(() => {
          setSelectedGameId(null);
          setAnalysis(null);
          setSelectedPlyNumber(null);
          setError(null);
        });
      }
    }
  }, [activeAccountId, initialGameId, setAnalysis, setSelectedPlyNumber]);

  const handleSelectGame = (id: string) => {
    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error') {
      setIsAccountPromptOpen(true);
      return;
    }
    setError(null);
    setSelectedGameId(id);
  };

  const handleBackToGames = () => {
    setSelectedGameId(null);
    setAnalysis(null);
    setSelectedPlyNumber(null);
    setError(null);
  };

  if (!selectedGameId || !analysis) {
    return (
      <>
        {error && <div className="max-w-6xl mx-auto px-4 mt-4 text-xs text-error font-mono">{error}</div>}
        <GamesListView
          games={games}
          isLoading={isLoadingGames}
          selectedCategory={selectedCategory}
          onSelectCategory={setSelectedCategory}
          onSelectGame={handleSelectGame}
          onNavigateImport={onNavigateImport}
        />
        <AccountConnectionPromptModal
          isOpen={isAccountPromptOpen}
          onDismiss={() => setIsAccountPromptOpen(false)}
        />
      </>
    );
  }

  const qualifyingPlies = filterQualifyingPlies(analysis.plies, evalLossThreshold, timingFilters);
  const activePly =
    qualifyingPlies.find((p) => p.plyNumber === selectedPlyNumber) || qualifyingPlies[0] || null;

  const handleEvalLossThresholdChange = (newThreshold: number) => {
    setEvalLossThreshold(newThreshold);
    onThresholdChange?.(newThreshold);
  };

  const handleToggleTimingFilter = (cat: DecisionSeverityCategory) => {
    setTimingFilters((prev) => {
      const next = toggleTimingCategory(prev, cat);
      onTimingFiltersChange?.(next);
      return next;
    });
  };

  return (
    <GameAnalysisDashboard
      analysis={analysis}
      error={error}
      evalLossThreshold={evalLossThreshold}
      timingFilters={timingFilters}
      qualifyingPlies={qualifyingPlies}
      activePly={activePly}
      initialExpandedSections={initialExpandedSections}
      accountId={accountId}
      guestUsername={guestUsername}
      onBackToGames={handleBackToGames}
      onEvalLossThresholdChange={handleEvalLossThresholdChange}
      onToggleTimingFilter={handleToggleTimingFilter}
      onSelectPly={setSelectedPlyNumber}
      onPracticeMistake={onPracticeMistake}
      onPracticeSimilarPosition={onPracticeSimilarPosition}
      onExpandedSectionsChange={onExpandedSectionsChange}
    />
  );
};

export default GameAnalysisView;
