'use client';

import React from 'react';
import { ChevronDown, ChevronRight, Loader2, Swords } from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { useMistakeSimilarity } from '../hooks/useMistakeSimilarity';
import type {
  AnalyzedPly,
  GameAnalysisResponse,
  SimilarPositionResult,
} from '../services/api';
import { formatEvaluation } from '../utils/gameAnalysisFilters';

export type SimilarPositionMatch = SimilarPositionResult;

export interface GameAnalysisMoveDetailProps {
  activePly: AnalyzedPly | null;
  analysis: GameAnalysisResponse;
  evalLossThreshold?: number;
  accountId?: string;
  guestUsername?: string;
  onPracticeMistake?: (ply: AnalyzedPly, analysis: GameAnalysisResponse) => void;
  onPracticeSimilarPosition?: (
    match: SimilarPositionResult,
    analysis: GameAnalysisResponse,
    activePly: AnalyzedPly
  ) => void;
  initialExpandedSections?: { structural?: boolean; dynamic?: boolean };
  onExpandedSectionsChange?: (sections: { structural?: boolean; dynamic?: boolean }) => void;
}

interface SimilarPositionCardProps {
  match: SimilarPositionResult;
  activeColor: string;
  isAlsoOtherMode?: boolean;
  onPractice: (match: SimilarPositionResult) => void;
}

const SimilarPositionCard: React.FC<SimilarPositionCardProps> = ({
  match,
  activeColor,
  isAlsoOtherMode,
  onPractice,
}) => {
  const occ = match.occurrences[0];
  const moveNumber = occ?.plyNumber ? Math.ceil(occ.plyNumber / 2) : null;
  const boardOrientation = occ?.playerColor || activeColor;

  return (
    <div className="p-3 bg-surface-container rounded-lg border border-outline-variant flex flex-col sm:flex-row items-center gap-3">
      <div className="w-28 h-28 shrink-0 rounded overflow-hidden border border-outline-variant">
        <ChessBoardView fen={match.fen} boardOrientation={boardOrientation} />
      </div>
      <div className="flex-1 min-w-0 text-xs">
        <div className="flex items-center justify-between gap-2 mb-1">
          <span className="font-bold text-on-surface truncate">
            {occ?.opponentUsername ? `vs. ${occ.opponentUsername}` : 'Historical Position'}
          </span>
          <span className="font-semibold text-primary">Score: {match.score.toFixed(2)}</span>
        </div>
        <div className="text-[11px] text-on-surface-variant space-y-0.5">
          {moveNumber !== null && <div>Move {moveNumber}</div>}
          {occ && (
            <div>
              {occ.gameResult ? `${occ.gameResult.toUpperCase()} • ` : ''}
              {occ.timeControl ? `${occ.timeControl} • ` : ''}
              {occ.playedAt ? new Date(occ.playedAt).toLocaleDateString() : ''}
            </div>
          )}
        </div>
        {isAlsoOtherMode && (
          <span className="inline-block mt-1 px-1.5 py-0.5 text-[10px] bg-secondary-container text-on-secondary-container rounded font-medium">
            Structural & Dynamic
          </span>
        )}
      </div>
      <div className="shrink-0 self-end sm:self-center">
        <button
          type="button"
          onClick={() => onPractice(match)}
          className="px-3 py-1.5 bg-primary text-on-primary font-semibold rounded text-xs hover:bg-primary/90 transition cursor-pointer"
        >
          Practice
        </button>
      </div>
    </div>
  );
};

interface SimilaritySectionBodyProps {
  mode: 'STRUCTURAL' | 'DYNAMIC';
  isLoading: boolean;
  error: string | null;
  results: SimilarPositionResult[] | null;
  otherResults: SimilarPositionResult[] | null;
  activeColor: string;
  onRetry: () => void;
  onPractice: (match: SimilarPositionResult) => void;
}

const SimilaritySectionBody: React.FC<SimilaritySectionBodyProps> = ({
  mode,
  isLoading,
  error,
  results,
  otherResults,
  activeColor,
  onRetry,
  onPractice,
}) => {
  if (isLoading) {
    return (
      <div className="py-4 text-center text-xs text-on-surface-variant flex items-center justify-center gap-2">
        <Loader2 className="w-4 h-4 animate-spin text-primary" />
        <span>Searching {mode.toLowerCase()} similar positions...</span>
      </div>
    );
  }
  if (error) {
    return (
      <div className="py-2 text-center text-xs text-error">
        <span>{error}</span>
        <button
          type="button"
          onClick={onRetry}
          className="ml-2 underline font-semibold cursor-pointer"
        >
          Retry
        </button>
      </div>
    );
  }
  if (results && results.length === 0) {
    return (
      <div className="py-3 text-center text-xs text-on-surface-variant">
        No {mode.toLowerCase()} similar positions found in your other games.
      </div>
    );
  }
  const otherIds = new Set(otherResults?.map((r) => r.positionId) || []);
  return (
    <div className="space-y-2">
      {results?.map((match) => (
        <SimilarPositionCard
          key={match.positionId}
          match={match}
          activeColor={activeColor}
          isAlsoOtherMode={otherIds.has(match.positionId)}
          onPractice={onPractice}
        />
      ))}
    </div>
  );
};

interface SimilaritySectionProps {
  mode: 'STRUCTURAL' | 'DYNAMIC';
  title: string;
  isExpanded: boolean;
  isLoading: boolean;
  error: string | null;
  results: SimilarPositionResult[] | null;
  otherResults: SimilarPositionResult[] | null;
  activeColor: string;
  onToggle: () => void;
  onRetry: () => void;
  onPractice: (match: SimilarPositionResult) => void;
}

const SimilaritySection: React.FC<SimilaritySectionProps> = ({
  mode,
  title,
  isExpanded,
  isLoading,
  error,
  results,
  otherResults,
  activeColor,
  onToggle,
  onRetry,
  onPractice,
}) => {
  const countLabel = results ? ` (${results.length})` : '';
  const searchLabel =
    mode === 'STRUCTURAL' ? 'Search Structural Similarity' : 'Search Dynamic Similarity';

  return (
    <div className="border border-outline-variant rounded-lg overflow-hidden mb-3">
      <button
        type="button"
        aria-label={`${title} / ${searchLabel}${countLabel}`}
        onClick={onToggle}
        className="w-full flex items-center justify-between p-3 bg-surface-container hover:bg-surface-container-high transition text-xs font-semibold text-on-surface cursor-pointer"
      >
        <span className="flex items-center gap-2">
          {isExpanded ? (
            <ChevronDown className="w-4 h-4 text-primary" />
          ) : (
            <ChevronRight className="w-4 h-4 text-on-surface-variant" />
          )}
          <span>{title}</span>
        </span>
        {results && (
          <span className="px-2 py-0.5 rounded-full bg-primary/20 text-primary font-bold text-[11px]">
            {results.length}
          </span>
        )}
      </button>

      {isExpanded && (
        <div className="p-3 border-t border-outline-variant bg-surface-container-low space-y-3">
          <SimilaritySectionBody
            mode={mode}
            isLoading={isLoading}
            error={error}
            results={results}
            otherResults={otherResults}
            activeColor={activeColor}
            onRetry={onRetry}
            onPractice={onPractice}
          />
        </div>
      )}
    </div>
  );
};

const MoveHeaderAndMetrics: React.FC<{ activePly: AnalyzedPly; evalLossThreshold?: number }> = ({
  activePly,
  evalLossThreshold = 0.8,
}) => {
  const isMistake =
    activePly.evalLoss !== null &&
    activePly.evalLoss !== undefined &&
    activePly.evalLoss >= evalLossThreshold;

  return (
    <>
      <div className="flex items-start justify-between pb-3 border-b border-outline-variant mb-4">
        <div>
          <h3 className="text-base font-bold text-on-surface flex items-center gap-2">
            <span>Ply {activePly.plyNumber}:</span>
            <span className="text-primary">{activePly.movePlayed}</span>
          </h3>
          <span className="text-xs text-on-surface-variant">Played by {activePly.playerColor}</span>
        </div>

        <div className="text-right">
          <span className="text-xs text-on-surface-variant block">
            {isMistake ? 'Eval Loss:' : 'Move Note:'}
          </span>
          <span className={`text-sm font-bold ${isMistake ? 'text-error' : 'text-primary'}`}>
            {isMistake
              ? `${activePly.evalLoss !== null && activePly.evalLoss !== undefined ? activePly.evalLoss.toFixed(2) : '0.00'} pawns`
              : activePly.decisionSeverity
                ? `Timing Observation (${activePly.decisionSeverity})`
                : 'Good Move'}
          </span>
        </div>
      </div>

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

      <div className="flex flex-col items-center justify-center p-3 mb-4 bg-surface-container rounded-lg border border-outline-variant">
        <span className="text-[10px] text-on-surface-variant uppercase font-semibold mb-2 self-start">
          Pre-Move Position
        </span>
        <div className="w-56 h-56 sm:w-64 sm:h-64">
          <ChessBoardView fen={activePly.preMoveFen} boardOrientation={activePly.playerColor} />
        </div>
      </div>
    </>
  );
};

export const GameAnalysisMoveDetail: React.FC<GameAnalysisMoveDetailProps> = ({
  activePly,
  analysis,
  evalLossThreshold = 0.8,
  accountId,
  guestUsername,
  onPracticeMistake,
  onPracticeSimilarPosition,
  initialExpandedSections,
  onExpandedSectionsChange,
}) => {
  const similarity = useMistakeSimilarity(
    activePly,
    analysis,
    initialExpandedSections,
    onExpandedSectionsChange,
    accountId,
    guestUsername
  );

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

  const isMistake =
    activePly.evalLoss !== null &&
    activePly.evalLoss !== undefined &&
    activePly.evalLoss >= evalLossThreshold;

  return (
    <div className="lg:col-span-7 bg-surface-container-high border border-outline-variant rounded-lg p-5 flex flex-col justify-between">
      <div>
        <MoveHeaderAndMetrics activePly={activePly} evalLossThreshold={evalLossThreshold} />

        <div className="mt-4 pt-3 border-t border-outline-variant">
          <SimilaritySection
            mode="STRUCTURAL"
            title="Search Structural Similarities"
            isExpanded={similarity.isStructuralExpanded}
            isLoading={similarity.isSearchingStructural}
            error={similarity.structuralError}
            results={similarity.structuralResults}
            otherResults={similarity.dynamicResults}
            activeColor={activePly.playerColor}
            onToggle={similarity.handleToggleStructural}
            onRetry={similarity.retryStructural}
            onPractice={(match) => onPracticeSimilarPosition?.(match, analysis, activePly)}
          />

          <SimilaritySection
            mode="DYNAMIC"
            title="Search Dynamic Similarities"
            isExpanded={similarity.isDynamicExpanded}
            isLoading={similarity.isSearchingDynamic}
            error={similarity.dynamicError}
            results={similarity.dynamicResults}
            otherResults={similarity.structuralResults}
            activeColor={activePly.playerColor}
            onToggle={similarity.handleToggleDynamic}
            onRetry={similarity.retryDynamic}
            onPractice={(match) => onPracticeSimilarPosition?.(match, analysis, activePly)}
          />
        </div>
      </div>

      {onPracticeMistake && (
        <div className="mt-6 pt-4 border-t border-outline-variant flex justify-end">
          <button
            type="button"
            onClick={() => onPracticeMistake(activePly, analysis)}
            className="px-4 py-2 bg-primary text-on-primary font-bold rounded hover:bg-primary/90 transition flex items-center gap-2 cursor-pointer shadow-sm text-xs"
          >
            <Swords className="w-4 h-4" />
            <span>{isMistake ? 'Practice this mistake' : 'Practice this position'}</span>
          </button>
        </div>
      )}
    </div>
  );
};
