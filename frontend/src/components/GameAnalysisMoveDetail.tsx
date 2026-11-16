'use client';

import React from 'react';
import { Search, Swords } from 'lucide-react';
import type {
  AnalyzedPly,
  GameAnalysisResponse,
  SimilarPositionsResponse,
} from '../services/api';
import { formatEvaluation } from '../utils/gameAnalysisFilters';

export interface GameAnalysisMoveDetailProps {
  activePly: AnalyzedPly | null;
  analysis: GameAnalysisResponse;
  similarityResults: SimilarPositionsResponse | null;
  similarityMode: 'STRUCTURAL' | 'DYNAMIC';
  isSearchingSimilarity: boolean;
  onSearchSimilarity: (mode: 'STRUCTURAL' | 'DYNAMIC') => void;
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
}

const SimilarityPanel: React.FC<{
  results: SimilarPositionsResponse | null;
  mode: 'STRUCTURAL' | 'DYNAMIC';
  isSearching: boolean;
  onSearch: (mode: 'STRUCTURAL' | 'DYNAMIC') => void;
}> = ({ results, mode, isSearching, onSearch }) => (
  <div className="mt-4 pt-3 border-t border-outline-variant">
    <div className="flex items-center justify-between mb-2">
      <span className="font-semibold text-primary flex items-center gap-1.5">
        <Search className="w-3.5 h-3.5" />
        <span>Historical Similarity</span>
      </span>
    </div>

    <div className="flex gap-2 mb-3">
      <button
        type="button"
        onClick={() => onSearch('STRUCTURAL')}
        disabled={isSearching}
        className="flex-1 py-1.5 px-2 bg-surface-container border border-outline-variant hover:border-primary/50 text-on-surface-variant hover:text-on-surface rounded text-xs transition cursor-pointer disabled:opacity-50"
      >
        Search Structural Similarity
      </button>
      <button
        type="button"
        onClick={() => onSearch('DYNAMIC')}
        disabled={isSearching}
        className="flex-1 py-1.5 px-2 bg-surface-container border border-outline-variant hover:border-primary/50 text-on-surface-variant hover:text-on-surface rounded text-xs transition cursor-pointer disabled:opacity-50"
      >
        Search Dynamic Similarity
      </button>
    </div>

    {results && (
      <div className="bg-surface-container rounded p-3 border border-outline-variant text-xs mb-4">
        <div className="flex items-center justify-between mb-1.5">
          <span className="font-semibold text-primary">
            {mode} Results
          </span>
          <span className="text-[10px] text-on-surface-variant">
            Evaluated {results.candidatesEvaluated} candidates
          </span>
        </div>
        {results.results.length === 0 ? (
          <p className="text-on-surface-variant text-[11px]">
            No historical matches found for this pre-move position.
          </p>
        ) : (
          <div className="space-y-1">
            {results.results.map((m, idx) => (
              <div key={idx} className="flex items-center justify-between text-[11px]">
                <span>Match #{idx + 1}</span>
                <span className="font-semibold">Score: {m.score.toFixed(3)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    )}
  </div>
);

export const GameAnalysisMoveDetail: React.FC<GameAnalysisMoveDetailProps> = ({
  activePly,
  analysis,
  similarityResults,
  similarityMode,
  isSearchingSimilarity,
  onSearchSimilarity,
  onPracticeMistake,
}) => {
  if (!activePly) {
    return (
      <div className="lg:col-span-7 bg-surface-container-high border border-outline-variant rounded-lg p-5 flex flex-col justify-center items-center h-64 text-center text-on-surface-variant text-xs">
        <span>No qualifying move selected.</span>
        <span className="text-[11px] mt-1 text-on-surface-variant/70">
          Adjust the evaluation loss or timing filters to inspect moves.
        </span>
      </div>
    );
  }

  return (
    <div className="lg:col-span-7 bg-surface-container-high border border-outline-variant rounded-lg p-5 flex flex-col justify-between">
      <div>
        <div className="flex items-start justify-between pb-3 border-b border-outline-variant mb-4">
          <div>
            <h3 className="text-base font-bold text-on-surface flex items-center gap-2">
              <span>Ply {activePly.plyNumber}:</span>
              <span className="text-primary">{activePly.movePlayed}</span>
            </h3>
            <span className="text-xs text-on-surface-variant">
              Played by {activePly.playerColor}
            </span>
          </div>

          <div className="text-right">
            <span className="text-xs text-on-surface-variant block">Eval Loss:</span>
            <span className="text-sm font-bold text-error">
              {activePly.evalLoss !== null && activePly.evalLoss !== undefined
                ? `${activePly.evalLoss.toFixed(2)} pawns`
                : '-'}
            </span>
          </div>
        </div>

        {/* Engine Metrics Grid */}
        <div className="grid grid-cols-3 gap-3 mb-4">
          <div className="p-2.5 bg-surface-container rounded border border-outline-variant">
            <span className="text-[10px] text-on-surface-variant block uppercase">Position Eval</span>
            <span className="text-sm font-bold text-on-surface">
              {formatEvaluation(activePly.evalCp)}
            </span>
          </div>
          <div className="p-2.5 bg-surface-container rounded border border-outline-variant">
            <span className="text-[10px] text-on-surface-variant block uppercase">Best Engine Move</span>
            <span className="text-sm font-bold text-primary">
              {activePly.bestMove || '-'} ({formatEvaluation(activePly.bestEvalCp)})
            </span>
          </div>
          <div className="p-2.5 bg-surface-container rounded border border-outline-variant">
            <span className="text-[10px] text-on-surface-variant block uppercase">Decision Time</span>
            <span className="text-sm font-bold text-on-surface">
              {activePly.decisionTimeMs ? `${(activePly.decisionTimeMs / 1000).toFixed(1)}s` : '-'}
            </span>
          </div>
        </div>

        {/* Pre-move FEN */}
        <div className="mb-4">
          <span className="text-[10px] text-on-surface-variant block mb-1 uppercase font-semibold">
            Pre-Move FEN:
          </span>
          <div className="p-2 bg-surface-container rounded text-[11px] font-mono break-all border border-outline-variant text-on-surface-variant">
            {activePly.preMoveFen}
          </div>
        </div>

        <SimilarityPanel
          results={similarityResults}
          mode={similarityMode}
          isSearching={isSearchingSimilarity}
          onSearch={onSearchSimilarity}
        />
      </div>

      {onPracticeMistake && (
        <div className="mt-6 pt-4 border-t border-outline-variant flex justify-end">
          <button
            type="button"
            onClick={() => onPracticeMistake(activePly, analysis)}
            className="px-4 py-2 bg-primary text-on-primary font-bold rounded hover:bg-primary/90 transition flex items-center gap-2 cursor-pointer shadow-sm text-xs"
          >
            <Swords className="w-4 h-4" />
            <span>Practice this mistake</span>
          </button>
        </div>
      )}
    </div>
  );
};
