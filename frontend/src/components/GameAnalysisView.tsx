'use client';

import React, { useState, useEffect, useSyncExternalStore } from 'react';
import { ArrowLeft, AlertTriangle, Target } from 'lucide-react';
import {
  type GameSummary,
  type GameAnalysisResponse,
  type AnalyzedPly,
  type SimilarPositionsResponse,
  fetchGames,
  analyzeGame,
  fetchSimilarPositions,
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

export interface GameAnalysisViewProps {
  initialGameId?: string | null;
  initialPlyNumber?: number | null;
  initialThreshold?: number;
  initialTimingFilters?: DecisionSeverityCategory[];
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
  sessionStatus?: 'loading' | 'authenticated' | 'unauthenticated' | 'error';
}

const GamesListView: React.FC<{
  games: GameSummary[];
  isLoading: boolean;
  onSelectGame: (gameId: string) => void;
}> = ({ games, isLoading, onSelectGame }) => (
  <div className="w-full max-w-6xl mx-auto px-4 py-8 font-mono">
    <div className="mb-6">
      <h2 className="text-xl font-bold text-on-surface">My Games</h2>
      <p className="text-xs text-on-surface-variant mt-1">
        Select a game to analyze with Stockfish and explore mistake patterns.
      </p>
    </div>

    {isLoading ? (
      <div className="p-8 text-center text-on-surface-variant text-sm">Loading games...</div>
    ) : games.length === 0 ? (
      <div className="p-8 text-center text-on-surface-variant text-sm bg-surface-container rounded-lg border border-outline-variant">
        No games found. Please import some games to begin analysis.
      </div>
    ) : (
      <div className="bg-surface-container-high border border-outline-variant rounded-lg overflow-hidden">
        <table className="w-full text-left text-xs">
          <thead className="bg-surface-container text-on-surface-variant border-b border-outline-variant">
            <tr>
              <th className="py-2.5 px-4 font-semibold">Players</th>
              <th className="py-2.5 px-4 font-semibold">Result</th>
              <th className="py-2.5 px-4 font-semibold">Date</th>
              <th className="py-2.5 px-4 font-semibold text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-outline-variant/50">
            {games.map((g) => (
              <tr key={g.id} className="hover:bg-surface-container/50 transition">
                <td className="py-3 px-4">
                  <span className="font-bold text-on-surface">{g.whiteUsername}</span>
                  <span className="text-on-surface-variant mx-1.5">vs</span>
                  <span className="font-bold text-on-surface">{g.blackUsername}</span>
                </td>
                <td className="py-3 px-4 font-bold text-primary">{g.result}</td>
                <td className="py-3 px-4 text-on-surface-variant">{g.playedAt}</td>
                <td className="py-3 px-4 text-right">
                  <button
                    type="button"
                    onClick={() => onSelectGame(g.id)}
                    className="px-3 py-1.5 bg-primary text-on-primary font-semibold rounded hover:bg-primary/90 transition text-xs cursor-pointer"
                  >
                    Analyze
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}
  </div>
);

interface GameAnalysisDashboardProps {
  analysis: GameAnalysisResponse;
  error: string | null;
  evalLossThreshold: number;
  timingFilters: DecisionSeverityCategory[];
  qualifyingPlies: AnalyzedPly[];
  activePly: AnalyzedPly | null;
  similarityResults: SimilarPositionsResponse | null;
  similarityMode: 'STRUCTURAL' | 'DYNAMIC';
  isSearchingSimilarity: boolean;
  onBackToGames: () => void;
  onEvalLossThresholdChange: (value: number) => void;
  onToggleTimingFilter: (category: DecisionSeverityCategory) => void;
  onSelectPly: (plyNumber: number) => void;
  onSearchSimilarity: (mode: 'STRUCTURAL' | 'DYNAMIC') => void;
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
}

const GameAnalysisDashboard: React.FC<GameAnalysisDashboardProps> = ({
  analysis,
  error,
  evalLossThreshold,
  timingFilters,
  qualifyingPlies,
  activePly,
  similarityResults,
  similarityMode,
  isSearchingSimilarity,
  onBackToGames,
  onEvalLossThresholdChange,
  onToggleTimingFilter,
  onSelectPly,
  onSearchSimilarity,
  onPracticeMistake,
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
      />
      <GameAnalysisMoveDetail
        activePly={activePly}
        analysis={analysis}
        similarityResults={similarityResults}
        similarityMode={similarityMode}
        isSearchingSimilarity={isSearchingSimilarity}
        onSearchSimilarity={onSearchSimilarity}
        onPracticeMistake={onPracticeMistake}
      />
    </div>
  </div>
);

const AccountConnectionPromptModal: React.FC<{
  isOpen: boolean;
  onDismiss: () => void;
}> = ({ isOpen, onDismiss }) => {
  if (!isOpen) return null;
  return (
    <div
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4 backdrop-blur-sm font-mono"
    >
      <div className="bg-surface-container-high border border-outline-variant rounded-2xl w-full max-w-sm p-6 space-y-4 text-center shadow-2xl">
        <div className="w-12 h-12 rounded-xl bg-primary/20 text-primary flex items-center justify-center mx-auto">
          <Target className="w-6 h-6" />
        </div>
        <div>
          <h3 className="text-base font-bold text-on-surface">Connect Account</h3>
          <p className="text-xs text-on-surface-variant mt-1.5 leading-relaxed">
            Connect your Chess.com account in the Import Games tab to analyze your games.
          </p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          className="w-full py-2 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl hover:bg-primary/90 transition cursor-pointer"
        >
          Dismiss
        </button>
      </div>
    </div>
  );
};

const GamesListSection: React.FC<{
  error: string | null;
  games: GameSummary[];
  isLoadingGames: boolean;
  onSelectGame: (id: string) => void;
  isAccountPromptOpen: boolean;
  onDismissAccountPrompt: () => void;
}> = ({
  error,
  games,
  isLoadingGames,
  onSelectGame,
  isAccountPromptOpen,
  onDismissAccountPrompt,
}) => (
  <>
    {error && <div className="max-w-6xl mx-auto px-4 mt-4 text-xs text-error font-mono">{error}</div>}
    <GamesListView
      games={games}
      isLoading={isLoadingGames}
      onSelectGame={onSelectGame}
    />
    <AccountConnectionPromptModal
      isOpen={isAccountPromptOpen}
      onDismiss={onDismissAccountPrompt}
    />
  </>
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

function useSimilaritySearch(setError: (err: string | null) => void) {
  const [similarityResults, setSimilarityResults] =
    useState<SimilarPositionsResponse | null>(null);
  const [similarityMode, setSimilarityMode] = useState<'STRUCTURAL' | 'DYNAMIC'>('STRUCTURAL');
  const [isSearchingSimilarity, setIsSearchingSimilarity] = useState(false);

  const handleSearchSimilarity = (mode: 'STRUCTURAL' | 'DYNAMIC', fen: string) => {
    setIsSearchingSimilarity(true);
    setSimilarityMode(mode);
    fetchSimilarPositions({ fen, mode, limit: 5 })
      .then(setSimilarityResults)
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'Similarity search failed'))
      .finally(() => setIsSearchingSimilarity(false));
  };

  return {
    similarityResults,
    similarityMode,
    isSearchingSimilarity,
    handleSearchSimilarity,
  };
}

export const GameAnalysisView: React.FC<GameAnalysisViewProps> = ({
  initialGameId = null,
  initialPlyNumber = null,
  initialThreshold = DEFAULT_EVAL_LOSS_THRESHOLD,
  initialTimingFilters = DEFAULT_TIMING_FILTERS,
  onPracticeMistake,
  sessionStatus,
}) => {
  const activeAccount = useSyncExternalStore(
    activeAccountStore.subscribe,
    activeAccountStore.getSnapshot,
    activeAccountStore.getServerSnapshot
  );
  const activeAccountId = activeAccount?.id;
  const [isAccountPromptOpen, setIsAccountPromptOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { games, isLoadingGames } = useGameList(
    initialGameId,
    activeAccountId,
    sessionStatus,
    setError
  );
  const [selectedGameId, setSelectedGameId] = useState<string | null>(initialGameId);
  const [analysis, setAnalysis] = useState<GameAnalysisResponse | null>(null);
  const [selectedPlyNumber, setSelectedPlyNumber] = useState<number | null>(initialPlyNumber);
  const [evalLossThreshold, setEvalLossThreshold] = useState<number>(initialThreshold);
  const [timingFilters, setTimingFilters] =
    useState<DecisionSeverityCategory[]>(initialTimingFilters);

  const {
    similarityResults,
    similarityMode,
    isSearchingSimilarity,
    handleSearchSimilarity,
  } = useSimilaritySearch(setError);

  useEffect(() => {
    if (!initialGameId) {
      queueMicrotask(() => {
        setSelectedGameId(null);
        setAnalysis(null);
        setSelectedPlyNumber(null);
        setError(null);
      });
    }
  }, [activeAccountId, initialGameId]);

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
  }, [selectedGameId, initialPlyNumber, sessionStatus]);

  const handleSelectGame = (id: string) => {
    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error') {
      setIsAccountPromptOpen(true);
      return;
    }
    setError(null);
    setSelectedGameId(id);
  };

  if (!selectedGameId || !analysis) {
    return (
      <GamesListSection
        error={error}
        games={games}
        isLoadingGames={isLoadingGames}
        onSelectGame={handleSelectGame}
        isAccountPromptOpen={isAccountPromptOpen}
        onDismissAccountPrompt={() => setIsAccountPromptOpen(false)}
      />
    );
  }

  const qualifyingPlies = filterQualifyingPlies(analysis.plies, evalLossThreshold, timingFilters);
  const activePly =
    qualifyingPlies.find((p) => p.plyNumber === selectedPlyNumber) || qualifyingPlies[0] || null;

  return (
    <GameAnalysisDashboard
      analysis={analysis}
      error={error}
      evalLossThreshold={evalLossThreshold}
      timingFilters={timingFilters}
      qualifyingPlies={qualifyingPlies}
      activePly={activePly}
      similarityResults={similarityResults}
      similarityMode={similarityMode}
      isSearchingSimilarity={isSearchingSimilarity}
      onBackToGames={() => {
        setSelectedGameId(null);
        setAnalysis(null);
        setSelectedPlyNumber(null);
        setError(null);
      }}
      onEvalLossThresholdChange={setEvalLossThreshold}
      onToggleTimingFilter={(cat) => setTimingFilters((prev) => toggleTimingCategory(prev, cat))}
      onSelectPly={setSelectedPlyNumber}
      onSearchSimilarity={(mode) => activePly && handleSearchSimilarity(mode, activePly.preMoveFen)}
      onPracticeMistake={onPracticeMistake}
    />
  );
};

export default GameAnalysisView;
