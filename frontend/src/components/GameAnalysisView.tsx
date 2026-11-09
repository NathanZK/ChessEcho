'use client';

import React, { useState, useEffect } from 'react';
import { ArrowLeft, AlertTriangle } from 'lucide-react';
import {
  type GameSummary,
  type GameAnalysisResponse,
  type AnalyzedPly,
  type SimilarPositionsResponse,
  fetchGames,
  analyzeGame,
  fetchSimilarPositions,
} from '../services/api';
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

export const GameAnalysisView: React.FC<GameAnalysisViewProps> = ({
  initialGameId = null,
  initialPlyNumber = null,
  initialThreshold = DEFAULT_EVAL_LOSS_THRESHOLD,
  initialTimingFilters = DEFAULT_TIMING_FILTERS,
  onPracticeMistake,
}) => {
  const [games, setGames] = useState<GameSummary[]>([]);
  const [selectedGameId, setSelectedGameId] = useState<string | null>(initialGameId);
  const [analysis, setAnalysis] = useState<GameAnalysisResponse | null>(null);
  const [selectedPlyNumber, setSelectedPlyNumber] = useState<number | null>(initialPlyNumber);
  const [evalLossThreshold, setEvalLossThreshold] = useState<number>(initialThreshold);
  const [timingFilters, setTimingFilters] =
    useState<DecisionSeverityCategory[]>(initialTimingFilters);
  const [similarityResults, setSimilarityResults] =
    useState<SimilarPositionsResponse | null>(null);
  const [similarityMode, setSimilarityMode] = useState<'STRUCTURAL' | 'DYNAMIC'>('STRUCTURAL');
  const [isSearchingSimilarity, setIsSearchingSimilarity] = useState(false);
  const [isLoadingGames, setIsLoadingGames] = useState(!initialGameId);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let isMounted = true;
    if (!initialGameId) {
      fetchGames({ page: 0, size: 50 })
        .then((res) => {
          if (isMounted) setGames(res.content);
        })
        .catch((err: unknown) => {
          if (isMounted) setError(err instanceof Error ? err.message : 'Failed to load games');
        })
        .finally(() => {
          if (isMounted) setIsLoadingGames(false);
        });
    }
    return () => {
      isMounted = false;
    };
  }, [initialGameId]);

  useEffect(() => {
    if (!selectedGameId) return;
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
  }, [selectedGameId, initialPlyNumber]);

  const handleSearchSimilarity = async (mode: 'STRUCTURAL' | 'DYNAMIC', fen: string) => {
    setIsSearchingSimilarity(true);
    setSimilarityMode(mode);
    try {
      const results = await fetchSimilarPositions({ fen, mode, limit: 5 });
      setSimilarityResults(results);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Similarity search failed');
    } finally {
      setIsSearchingSimilarity(false);
    }
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
          onSelectGame={(id) => {
            setError(null);
            setSelectedGameId(id);
          }}
        />
      </>
    );
  }

  const qualifyingPlies = filterQualifyingPlies(analysis.plies, evalLossThreshold, timingFilters);
  const activePly =
    qualifyingPlies.find((p) => p.plyNumber === selectedPlyNumber) ||
    qualifyingPlies[0] ||
    null;

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
      onBackToGames={handleBackToGames}
      onEvalLossThresholdChange={setEvalLossThreshold}
      onToggleTimingFilter={(cat) => setTimingFilters((prev) => toggleTimingCategory(prev, cat))}
      onSelectPly={setSelectedPlyNumber}
      onSearchSimilarity={(mode) => activePly && handleSearchSimilarity(mode, activePly.preMoveFen)}
      onPracticeMistake={onPracticeMistake}
    />
  );
};

export default GameAnalysisView;
