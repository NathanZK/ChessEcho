'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  X,
  ChevronLeft,
  ChevronRight,
  RefreshCw,
  ExternalLink,
  Clock,
  AlertCircle,
  HelpCircle,
} from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import {
  getClusterPositions,
  type ClusterMemberPosition,
  type RecurringWeaknessResponse,
} from '../services/api';
import { buildExactPlyChessComUrl } from '@/utils/urlUtils';
import { formatPawnLoss } from '@/utils/formatters';
import { Puzzle } from '@/types/puzzle';

export interface ClusterEvidenceModalProps {
  cluster: RecurringWeaknessResponse;
  accountId?: string;
  onClose: () => void;
  onSelectPractice?: (puzzle: Puzzle, fullList?: Puzzle[]) => void;
}

const MISTAKE_SEVERITIES = ['MINOR', 'MODERATE', 'SIGNIFICANT', 'SEVERE', 'UNEVALUATED'] as const;

function getSeverityBadge(severity?: string | null) {
  if (!severity || severity === 'UNEVALUATED') {
    return {
      label: 'Unevaluated',
      className: 'bg-surface-container-high text-outline border border-outline-variant',
    };
  }
  switch (severity) {
    case 'MINOR':
      return {
        label: 'Minor',
        className: 'bg-amber-accent/15 text-amber-400 border border-amber-accent/30',
      };
    case 'MODERATE':
      return {
        label: 'Moderate',
        className: 'bg-amber-accent/25 text-amber-300 border border-amber-accent/40',
      };
    case 'SIGNIFICANT':
      return {
        label: 'Significant',
        className: 'bg-rose-500/15 text-rose-300 border border-rose-500/30',
      };
    case 'SEVERE':
      return {
        label: 'Severe',
        className: 'bg-error-container/30 text-rose-400 border border-error-container/50',
      };
    case 'NO_CONCERN':
      return {
        label: 'No Concern',
        className: 'bg-primary/15 text-emerald-400 border border-primary/30',
      };
    default:
      return {
        label: severity,
        className: 'bg-surface-container text-on-surface border border-outline-variant',
      };
  }
}

function getDecisionBadge(severity?: string | null) {
  if (!severity || severity === 'UNKNOWN' || severity === 'UNEVALUATED') {
    return {
      label: 'Unevaluated',
      className: 'bg-surface-container text-outline border border-outline-variant',
    };
  }
  switch (severity) {
    case 'NO_CONCERN':
      return {
        label: 'NO_CONCERN',
        className: 'bg-primary/15 text-emerald-400 border border-primary/30',
      };
    case 'MINOR':
      return {
        label: 'MINOR',
        className: 'bg-amber-accent/15 text-amber-400 border border-amber-accent/30',
      };
    case 'SIGNIFICANT':
      return {
        label: 'SIGNIFICANT',
        className: 'bg-rose-500/15 text-rose-300 border border-rose-500/30',
      };
    case 'CRITICAL':
      return {
        label: 'CRITICAL',
        className: 'bg-error-container/30 text-rose-400 border border-error-container/50',
      };
    default:
      return {
        label: severity,
        className: 'bg-surface-container text-on-surface border border-outline-variant',
      };
  }
}

const EvidenceHeader: React.FC<{ cluster: RecurringWeaknessResponse; onClose: () => void }> = ({
  cluster,
  onClose,
}) => {
  const mistakeFreqPercent = Math.round(cluster.mistakeFrequency * 100);
  const blunderRatePercent = Math.round(cluster.blunderRate * 100);

  return (
    <div className="p-4 sm:p-5 border-b border-outline-variant bg-surface-container-low flex items-start justify-between gap-4">
      <div className="space-y-1.5">
        <div className="flex items-center gap-2.5">
          <h2 id="cluster-evidence-title" className="text-lg font-bold font-mono text-on-surface">
            Cluster Evidence Inspection
          </h2>
          <span className="px-2 py-0.5 rounded text-xs font-mono font-bold bg-primary/10 text-primary border border-primary/20">
            {cluster.clusterType}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-3 text-xs font-mono text-outline">
          <span className="text-on-surface font-semibold">{cluster.memberPositionCount} variations</span>
          <span>•</span>
          <span>
            {cluster.totalOccurrences} occurrences ({cluster.distinctGamesCount} games)
          </span>
          <span>•</span>
          <span className="text-amber-accent font-semibold">Mistake Freq: {mistakeFreqPercent}%</span>
          <span>•</span>
          <span className="text-error font-semibold">Blunder Rate: {blunderRatePercent}%</span>
        </div>
      </div>
      <button
        type="button"
        onClick={onClose}
        aria-label="Close dialog"
        className="p-1.5 rounded-lg text-outline hover:text-on-surface hover:bg-surface-container transition cursor-pointer"
      >
        <X className="w-5 h-5" />
      </button>
    </div>
  );
};

const EvidenceDistributions: React.FC<{ cluster: RecurringWeaknessResponse }> = ({ cluster }) => {
  return (
    <div className="p-4 sm:p-5 border-b border-outline-variant bg-surface-container-low/50 space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div className="bg-surface-container-low p-3.5 rounded-xl border border-outline-variant/80 space-y-2.5">
          <div className="flex items-center justify-between text-xs font-mono">
            <span className="font-bold text-on-surface uppercase tracking-wider">
              Mistake Severity Distribution
            </span>
            <span className="text-outline text-[11px]">
              Evaluated: {cluster.evaluatedOccurrences} / Unevaluated: {cluster.unevaluatedOccurrences}
            </span>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {MISTAKE_SEVERITIES.map((severityKey) => {
              const count = cluster.evalLossDistribution?.[severityKey] ?? 0;
              const percentage =
                cluster.evalLossPercentages?.[severityKey] ??
                (cluster.totalOccurrences > 0 ? Math.round((count / cluster.totalOccurrences) * 100) : 0);
              const label =
                severityKey === 'MINOR'
                  ? 'Minor'
                  : severityKey === 'MODERATE'
                  ? 'Moderate'
                  : severityKey === 'SIGNIFICANT'
                  ? 'Significant'
                  : severityKey === 'SEVERE'
                  ? 'Severe'
                  : 'Unevaluated';

              return (
                <div
                  key={severityKey}
                  className="bg-surface-container-lowest p-2 rounded-lg border border-outline-variant text-xs font-mono"
                >
                  <span className="text-[11px] text-outline block">{label}</span>
                  <span className="font-bold text-on-surface">
                    {label}: {count} ({percentage}%)
                  </span>
                </div>
              );
            })}
          </div>
        </div>

        <div className="bg-surface-container-low p-3.5 rounded-xl border border-outline-variant/80 space-y-2.5">
          <span className="font-bold text-on-surface uppercase tracking-wider text-xs font-mono block">
            Time Control Breakdown
          </span>
          <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
            {cluster.formatBreakdown &&
              Object.entries(cluster.formatBreakdown).map(([tc, count]) => {
                const label = tc.charAt(0).toUpperCase() + tc.slice(1).toLowerCase();
                return (
                  <div
                    key={tc}
                    className="bg-surface-container-lowest p-2 rounded-lg border border-outline-variant text-xs font-mono"
                  >
                    <span className="text-[11px] text-outline block">{label}</span>
                    <span className="font-bold text-on-surface">
                      {label}: {count}
                    </span>
                  </div>
                );
              })}
          </div>
        </div>
      </div>
    </div>
  );
};

const EvidencePositionCard: React.FC<{ pos: ClusterMemberPosition }> = ({ pos }) => {
  const boardOrientation = pos.playerColor === 'BLACK' ? 'black' : 'white';
  const severityInfo = getSeverityBadge(pos.mistakeSeverity);
  const decisionInfo = getDecisionBadge(pos.decisionSeverity);
  const chessComUrl = buildExactPlyChessComUrl(pos.platformGameId, pos.plyNumber, pos.timeControl);
  const duration =
    pos.decisionTimeMs != null ? `${(pos.decisionTimeMs / 1000).toFixed(1)}s` : 'Unavailable';

  return (
    <div className="p-4 bg-surface-container-low border border-outline-variant rounded-xl flex flex-col md:flex-row gap-4 items-start md:items-center justify-between">
      <div className="flex items-center gap-4">
        <div className="w-24 h-24 sm:w-28 sm:h-28 shrink-0 rounded-lg overflow-hidden border border-outline-variant bg-surface-container-lowest">
          <ChessBoardView fen={pos.fen} boardOrientation={boardOrientation} />
        </div>
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2 font-mono text-xs">
            <span className="text-outline">Played:</span>
            <span className="font-bold px-2 py-0.5 rounded bg-surface-container-lowest border border-outline-variant text-on-surface">
              {pos.movePlayed}
            </span>
            <span className="text-outline ml-2">Stockfish:</span>
            <span className="font-bold px-2 py-0.5 rounded bg-primary/10 text-primary border border-primary/20">
              {pos.targetMove || 'Pending analysis'}
            </span>
            {pos.acceptableMoves && pos.acceptableMoves.length > 0 && (
              <span className="text-[11px] text-outline ml-1">
                (Also: {pos.acceptableMoves.map((m) => `${m.move} [${formatPawnLoss(m.evalLoss)}]`).join(', ')})
              </span>
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2 font-mono text-xs">
            <span className="text-outline">Loss:</span>
            <span className="font-bold text-on-surface">
              {pos.evalLoss != null ? `${formatPawnLoss(pos.evalLoss)} pawns` : 'Unevaluated'}
            </span>
            <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${severityInfo.className}`}>
              {severityInfo.label}
            </span>
            <span className="text-outline ml-2">Timing:</span>
            <span className="font-bold text-on-surface flex items-center gap-1">
              <Clock className="w-3 h-3 text-outline" />
              {duration}
            </span>
            <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${decisionInfo.className}`}>
              {decisionInfo.label}
            </span>
            {pos.timeControl && (
              <span className="px-1.5 py-0.5 rounded text-[10px] bg-surface-container border border-outline-variant text-outline">
                {pos.timeControl}
              </span>
            )}
          </div>
          <div className="flex items-center gap-2 font-mono text-[11px] text-outline">
            <span>
              Move {pos.moveNumber} (ply {pos.plyNumber})
            </span>
            {chessComUrl ? (
              <a
                href={chessComUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary hover:underline"
              >
                <span>View game</span>
                <ExternalLink className="w-3 h-3" />
              </a>
            ) : (
              <span>Game: {pos.platformGameId}</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

const EvidencePagination: React.FC<{
  page: number;
  totalPages: number;
  totalElements: number;
  hasNext: boolean;
  isLoading: boolean;
  onPageChange: (newPage: number) => void;
}> = ({ page, totalPages, totalElements, hasNext, isLoading, onPageChange }) => (
  <div className="p-3 sm:p-4 border-t border-outline-variant bg-surface-container-low flex items-center justify-between">
    <div className="text-xs font-mono text-outline">
      Page {page + 1} of {Math.max(1, totalPages)}
      {totalElements > 0 && ` (${totalElements} total positions)`}
    </div>
    <div className="flex items-center gap-2">
      <button
        type="button"
        aria-label="Previous page"
        disabled={page === 0 || isLoading}
        onClick={() => onPageChange(page - 1)}
        className="p-1.5 rounded-lg border border-outline-variant bg-surface-container hover:bg-surface-container-high disabled:opacity-40 disabled:cursor-not-allowed text-on-surface transition cursor-pointer"
      >
        <ChevronLeft className="w-4 h-4" />
      </button>
      <button
        type="button"
        aria-label="Next page"
        disabled={!hasNext || page >= totalPages - 1 || isLoading}
        onClick={() => onPageChange(page + 1)}
        className="p-1.5 rounded-lg border border-outline-variant bg-surface-container hover:bg-surface-container-high disabled:opacity-40 disabled:cursor-not-allowed text-on-surface transition cursor-pointer"
      >
        <ChevronRight className="w-4 h-4" />
      </button>
    </div>
  </div>
);

export const ClusterEvidenceModal: React.FC<ClusterEvidenceModalProps> = ({
  cluster,
  accountId,
  onClose,
  onSelectPractice: _onSelectPractice,
}) => {
  const [page, setPage] = useState(0);
  const [pageSize] = useState(10);
  const [positions, setPositions] = useState<ClusterMemberPosition[]>([]);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const backdropRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  const loadPositions = useCallback(
    async (targetPage: number) => {
      setIsLoading(true);
      setError(null);
      try {
        const data = await getClusterPositions(cluster.clusterId, {
          page: targetPage,
          size: pageSize,
          accountId,
        });
        setPositions(data.positions || []);
        setPage(data.page ?? targetPage);
        setTotalPages(data.totalPages || 1);
        setTotalElements(data.totalElements || 0);
        setHasNext(Boolean(data.hasNext));
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Failed to load evidence positions';
        setError(msg);
      } finally {
        setIsLoading(false);
      }
    },
    [cluster.clusterId, pageSize, accountId]
  );

  useEffect(() => {
    let active = true;
    queueMicrotask(() => {
      if (active) void loadPositions(page);
    });
    return () => {
      active = false;
    };
  }, [loadPositions, page]);

  return (
    <div
      ref={backdropRef}
      onClick={(e) => {
        if (e.target === backdropRef.current) onClose();
      }}
      className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-3 sm:p-5 overflow-y-auto"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="cluster-evidence-title"
        className="w-full max-w-5xl bg-surface-container-lowest border border-outline-variant rounded-2xl shadow-2xl overflow-hidden flex flex-col max-h-[90vh] my-auto animate-in fade-in zoom-in-95 duration-150"
      >
        <EvidenceHeader cluster={cluster} onClose={onClose} />
        <EvidenceDistributions cluster={cluster} />

        <div className="flex-1 overflow-y-auto p-4 sm:p-5 space-y-4">
          {error ? (
            <div role="alert" className="p-5 rounded-xl border border-error/40 bg-error/10 text-error space-y-3">
              <div className="flex items-center gap-2">
                <AlertCircle className="w-5 h-5 text-error" />
                <span className="text-sm font-bold font-mono">Error Loading Evidence</span>
              </div>
              <p className="text-xs font-mono">{error}</p>
              <button
                type="button"
                onClick={() => void loadPositions(page)}
                className="px-3 py-1.5 bg-error text-on-error font-mono font-bold text-xs rounded-lg hover:bg-error/90 transition inline-flex items-center gap-1.5 cursor-pointer"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                <span>Retry</span>
              </button>
            </div>
          ) : isLoading ? (
            <div className="py-20 text-center space-y-3">
              <div className="w-8 h-8 border-3 border-primary border-t-transparent rounded-full animate-spin mx-auto" />
              <p className="text-xs font-mono text-outline">Loading cluster member positions...</p>
            </div>
          ) : positions.length === 0 ? (
            <div className="py-16 text-center space-y-2 bg-surface-container-low p-6 rounded-xl border border-outline-variant">
              <HelpCircle className="w-8 h-8 text-outline mx-auto" />
              <p className="text-sm font-bold text-on-surface">No Positions Found</p>
              <p className="text-xs text-outline">No member positions were found for this cluster.</p>
            </div>
          ) : (
            <div className="space-y-4">
              {positions.map((pos) => (
                <EvidencePositionCard key={pos.positionId} pos={pos} />
              ))}
            </div>
          )}
        </div>

        <EvidencePagination
          page={page}
          totalPages={totalPages}
          totalElements={totalElements}
          hasNext={hasNext}
          isLoading={isLoading}
          onPageChange={(newPage) => setPage(newPage)}
        />
      </div>
    </div>
  );
};
