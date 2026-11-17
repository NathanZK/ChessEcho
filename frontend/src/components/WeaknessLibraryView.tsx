'use client';

import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import {
  Target,
  Swords,
  List,
  RefreshCw,
  Sparkles,
  X,
  Flag,
  Layers,
  Zap,
} from 'lucide-react';
import { ChessBoardView } from './ChessBoardView';
import { StatusBanner } from './StatusBanner';
import { ClusterEvidenceModal } from './ClusterEvidenceModal';
import {
  fetchRecurringWeaknesses,
  triggerRecurringDiscovery,
  getRecurringDiscoveryJobStatus,
  cancelRecurringDiscoveryJob,
  getClusterPositions,
  type RecurringWeaknessResponse,
  type RecurringDiscoveryJobResponse,
  type ClusterMemberPosition,
} from '../services/api';
import { Puzzle } from '@/types/puzzle';

export interface WeaknessLibraryViewProps {
  accountId?: string;
  username?: string;
  isAnalysisActive?: boolean;
  sessionStatus?: 'loading' | 'authenticated' | 'unauthenticated' | 'error';
  onSelectPractice: (
    puzzle: Puzzle,
    fullList?: Puzzle[],
    context?: { clusterType?: 'STRUCTURAL' | 'DYNAMIC'; expandedClusterId?: string }
  ) => void;
  onWeaknessCountChange?: (count: number) => void;
  refreshKey?: number;
  initialClusterType?: 'STRUCTURAL' | 'DYNAMIC';
  initialExpandedClusterId?: string;
}

export const WeaknessLibraryView: React.FC<WeaknessLibraryViewProps> = ({
  accountId,
  username,
  isAnalysisActive,
  sessionStatus,
  onSelectPractice,
  onWeaknessCountChange,
  refreshKey,
  initialClusterType,
  initialExpandedClusterId,
}) => {
  const targetAccountId = accountId;
  const targetUsername = accountId ? undefined : username;
  const selector = accountId ?? username;

  const [selectedType, setSelectedType] = useState<'STRUCTURAL' | 'DYNAMIC' | null>(
    initialClusterType || null
  );

  const [structuralClusters, setStructuralClusters] = useState<RecurringWeaknessResponse[]>([]);
  const [dynamicClusters, setDynamicClusters] = useState<RecurringWeaknessResponse[]>([]);
  const [fetchedTypes, setFetchedTypes] = useState<Set<'STRUCTURAL' | 'DYNAMIC'>>(new Set());

  const [isLoadingClusters, setIsLoadingClusters] = useState(false);
  const [clusterError, setClusterError] = useState<string | null>(null);
  const [practiceError, setPracticeError] = useState<string | null>(null);
  const [loadingPracticeClusterId, setLoadingPracticeClusterId] = useState<string | null>(null);
  const [inspectingCluster, setInspectingCluster] = useState<RecurringWeaknessResponse | null>(null);

  // Recurring Weakness Discovery (#735)
  const [isDiscoveryModalOpen, setIsDiscoveryModalOpen] = useState(false);
  const [isAccountPromptOpen, setIsAccountPromptOpen] = useState(false);
  const [discoveryTimeControls, setDiscoveryTimeControls] = useState<string[]>(['RAPID', 'BLITZ', 'BULLET']);
  const [discoveryMinEvalLoss, setDiscoveryMinEvalLoss] = useState<number>(0.8);
  const [isSubmittingDiscovery, setIsSubmittingDiscovery] = useState(false);
  const [discoverySubmitError, setDiscoverySubmitError] = useState<string | null>(null);

  const [activeDiscoveryJob, setActiveDiscoveryJob] = useState<RecurringDiscoveryJobResponse | null>(null);
  const [isCancellingDiscovery, setIsCancellingDiscovery] = useState(false);
  const pollTimerRef = useRef<NodeJS.Timeout | null>(null);

  const clearPollTimer = useCallback(() => {
    if (pollTimerRef.current) {
      clearTimeout(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => clearPollTimer();
  }, [clearPollTimer]);

  useEffect(() => {
    if (!isDiscoveryModalOpen && !isAccountPromptOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (isDiscoveryModalOpen) setIsDiscoveryModalOpen(false);
        if (isAccountPromptOpen) setIsAccountPromptOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [isDiscoveryModalOpen, isAccountPromptOpen]);

  const activeClusters = useMemo(() => {
    if (selectedType === 'STRUCTURAL') return structuralClusters;
    if (selectedType === 'DYNAMIC') return dynamicClusters;
    return [];
  }, [selectedType, structuralClusters, dynamicClusters]);

  useEffect(() => {
    if (initialExpandedClusterId && activeClusters.length > 0) {
      const match = activeClusters.find((c) => c.clusterId === initialExpandedClusterId);
      if (match) {
        queueMicrotask(() => {
          setInspectingCluster(match);
        });
      }
    }
  }, [initialExpandedClusterId, activeClusters]);

  const loadClustersForType = useCallback(
    async (type: 'STRUCTURAL' | 'DYNAMIC') => {
      if (!selector) return;
      setIsLoadingClusters(true);
      setClusterError(null);
      try {
        const data = await fetchRecurringWeaknesses(targetAccountId, targetUsername, type);
        const clusters = data || [];
        if (type === 'STRUCTURAL') {
          setStructuralClusters(clusters);
        } else {
          setDynamicClusters(clusters);
        }
        setFetchedTypes((prev) => new Set(prev).add(type));
        onWeaknessCountChange?.(clusters.length);
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : 'Failed to load recurring weaknesses';
        if (!message.includes('ACCOUNT_SELECTION_REQUIRED')) {
          console.error('Failed to load recurring weaknesses:', err);
          setClusterError(message);
        }
      } finally {
        setIsLoadingClusters(false);
      }
    },
    [selector, targetAccountId, targetUsername, onWeaknessCountChange]
  );

  useEffect(() => {
    if (initialClusterType && !fetchedTypes.has(initialClusterType)) {
      queueMicrotask(() => {
        void loadClustersForType(initialClusterType);
      });
    }
  }, [initialClusterType, fetchedTypes, loadClustersForType]);

  const handleSelectClusterType = async (type: 'STRUCTURAL' | 'DYNAMIC') => {
    setSelectedType(type);
    const existing = type === 'STRUCTURAL' ? structuralClusters : dynamicClusters;
    if (existing.length > 0) {
      onWeaknessCountChange?.(existing.length);
      return;
    }

    if (accountId && sessionStatus !== 'unauthenticated') {
      try {
        const job = await triggerRecurringDiscovery({
          accountId: targetAccountId,
          username: targetUsername,
          clusterType: type,
          timeControls: discoveryTimeControls,
          minEvalLoss: discoveryMinEvalLoss,
        });
        if (job) {
          setActiveDiscoveryJob(job);
          if (job.status === 'COMPLETED') {
            await loadClustersForType(type);
          }
          return;
        }
      } catch (err: unknown) {
        console.warn('triggerRecurringDiscovery fallback to loadClustersForType:', err);
      }
    }

    void loadClustersForType(type);
  };

  const handleOpenDiscovery = () => {
    if (sessionStatus === 'loading') return;
    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error' || !accountId) {
      setIsAccountPromptOpen(true);
      return;
    }
    setDiscoverySubmitError(null);
    setIsDiscoveryModalOpen(true);
  };

  const handleToggleTimeControl = (tc: string) => {
    setDiscoveryTimeControls((prev) => {
      if (prev.includes(tc)) {
        if (prev.length === 1) return prev;
        return prev.filter((t) => t !== tc);
      } else {
        return [...prev, tc];
      }
    });
  };

  const handleStartDiscovery = async (overrideType?: 'STRUCTURAL' | 'DYNAMIC') => {
    if (sessionStatus === 'loading') return;
    if (sessionStatus === 'unauthenticated' || sessionStatus === 'error' || !accountId) {
      setIsAccountPromptOpen(true);
      setIsDiscoveryModalOpen(false);
      return;
    }
    setIsSubmittingDiscovery(true);
    setDiscoverySubmitError(null);
    try {
      const type = overrideType || selectedType || 'STRUCTURAL';
      setSelectedType(type);
      const job = await triggerRecurringDiscovery({
        accountId: targetAccountId,
        username: targetUsername,
        timeControls: discoveryTimeControls,
        minEvalLoss: discoveryMinEvalLoss,
        clusterType: type,
      });
      setActiveDiscoveryJob(job);
      setIsDiscoveryModalOpen(false);
      if (job && job.status === 'COMPLETED') {
        void loadClustersForType(type);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to trigger discovery';
      setDiscoverySubmitError(msg);
      setClusterError(msg);
    } finally {
      setIsSubmittingDiscovery(false);
    }
  };

  const handleCancelDiscovery = async () => {
    if (!activeDiscoveryJob) return;
    setIsCancellingDiscovery(true);
    try {
      const cancelled = await cancelRecurringDiscoveryJob(
        activeDiscoveryJob.jobId,
        targetAccountId,
        targetUsername
      );
      setActiveDiscoveryJob(cancelled);
      clearPollTimer();
    } catch (err) {
      console.error('Failed to cancel discovery job:', err);
    } finally {
      setIsCancellingDiscovery(false);
    }
  };

  useEffect(() => {
    if (!activeDiscoveryJob) {
      clearPollTimer();
      return;
    }

    if (activeDiscoveryJob.status === 'QUEUED' || activeDiscoveryJob.status === 'PROCESSING') {
      pollTimerRef.current = setTimeout(async () => {
        try {
          const updated = await getRecurringDiscoveryJobStatus(activeDiscoveryJob.jobId);
          setActiveDiscoveryJob(updated);
          if (updated.status === 'COMPLETED') {
            if (selectedType) {
              await loadClustersForType(selectedType);
            }
          } else if (updated.status === 'FAILED') {
            setClusterError(updated.errorMessage || 'Discovery job failed');
          }
        } catch (err) {
          console.error('Failed polling discovery job:', err);
        }
      }, 1500);
    } else {
      clearPollTimer();
    }

    return () => clearPollTimer();
  }, [activeDiscoveryJob, clearPollTimer, loadClustersForType, selectedType]);

  useEffect(() => {
    if (refreshKey && selectedType) {
      queueMicrotask(() => {
        void loadClustersForType(selectedType);
      });
    }
  }, [refreshKey, loadClustersForType, selectedType]);

  const handlePracticeCluster = async (cluster: RecurringWeaknessResponse) => {
    setPracticeError(null);
    setLoadingPracticeClusterId(cluster.clusterId);
    try {
      let currentPage = 0;
      let allPositions: ClusterMemberPosition[] = [];
      let hasNext = true;
      let expectedTotal: number | null = null;
      let expectedPages: number | null = null;
      while (hasNext) {
        const data = await getClusterPositions(cluster.clusterId, {
          playableOnly: true,
          size: 100,
          page: currentPage,
          accountId,
        });
        const batch = data.positions || [];
        if (data.hasNext && batch.length === 0) {
          throw new Error('Inconsistent pagination metadata: empty page received with hasNext=true');
        }
        if (typeof data.totalElements === 'number' && expectedTotal === null) {
          expectedTotal = data.totalElements;
        }
        if (typeof data.totalPages === 'number' && expectedPages === null) {
          expectedPages = data.totalPages;
        }
        allPositions = [...allPositions, ...batch];
        hasNext = Boolean(data.hasNext);
        currentPage++;
      }

      if (expectedTotal !== null && allPositions.length < expectedTotal) {
        throw new Error(`Incomplete playlist: retrieved ${allPositions.length} of ${expectedTotal} positions`);
      }
      if (expectedPages !== null && currentPage < expectedPages) {
        throw new Error(`Incomplete playlist: retrieved ${currentPage} of ${expectedPages} pages`);
      }

      const playablePositions = allPositions.filter(
        (pos) => typeof pos.targetMove === 'string' && pos.targetMove.trim().length > 0
      );
      const playablePuzzles: Puzzle[] = playablePositions.map((pos) => {
        const fenTurn = pos.fen ? pos.fen.split(' ')[1] : undefined;
        const playerColor: 'WHITE' | 'BLACK' =
          pos.playerColor === 'WHITE' || pos.playerColor === 'BLACK'
            ? pos.playerColor
            : (fenTurn === 'b' ? 'BLACK' : 'WHITE');

        return {
          puzzleId: pos.positionId,
          source: 'weakness',
          fen: pos.fen,
          playerColor,
          targetMove: pos.targetMove || '',
          acceptableMoves: pos.acceptableMoves || [],
          openingTitle: `Cluster Weakness: Move ${pos.moveNumber}`,
          movesPlayed: [
            {
              move: pos.movePlayed,
              timesPlayed: 1,
              averageLoss: pos.evalLoss ?? 1.0,
            },
          ],
          priority: Math.round((pos.evalLoss ?? 1.0) * 100),
          timesReached: 1,
          mistakeCount: 1,
          mistakeRate: 100,
        };
      });

      if (playablePuzzles.length === 0) {
        setPracticeError('No verified engine solutions available to practice for this cluster.');
        return;
      }

      onSelectPractice(playablePuzzles[0], playablePuzzles, {
        clusterType: selectedType ?? undefined,
      });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to launch practice for this cluster.';
      setPracticeError(msg);
    } finally {
      setLoadingPracticeClusterId(null);
    }
  };

  return (
    <div className="max-w-[1536px] w-full mx-auto px-4 lg:px-8 space-y-4 py-2">
      {/* Active Engine Analysis Status Banner */}
      {isAnalysisActive && username && (
        <div role="status" className="bg-primary-container/20 border border-primary/30 rounded-xl p-3 text-xs font-mono text-primary flex items-center space-x-2">
          <RefreshCw className="w-4 h-4 animate-spin shrink-0" />
          <span>Stockfish Engine Analysis Active: Evaluating positions for <strong>{username}</strong>. Detected recurring weaknesses update automatically as analysis completes.</span>
        </div>
      )}

      {/* Header */}
      <section className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-outline-variant pb-5">
        <div className="space-y-1.5">
          <h1 className="text-xl md:text-2xl font-bold text-on-surface tracking-tight flex items-center gap-2">
            <Flag className="w-6 h-6 text-primary" />
            <span>Recurring Weakness Library</span>
          </h1>
          <p className="text-xs text-on-surface-variant max-w-2xl leading-relaxed">
            Discover and practice recurring weakness clusters across your games. Select Structural clusters (based on pawn structure and piece formation) or Dynamic clusters (based on piece coordination and tactical tension).
          </p>
        </div>

        <button
          type="button"
          onClick={handleOpenDiscovery}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl shadow-sm hover:bg-primary/90 transition cursor-pointer self-start sm:self-auto shrink-0"
        >
          <Sparkles className="w-3.5 h-3.5" />
          <span>Find Recurring Weaknesses</span>
        </button>
      </section>

      {/* Async Discovery Progress Banner */}
      {activeDiscoveryJob && (activeDiscoveryJob.status === 'QUEUED' || activeDiscoveryJob.status === 'PROCESSING') && (
        <div className="p-4 bg-surface-container-high border border-primary/40 rounded-xl space-y-2 shadow-md">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <RefreshCw className="w-4 h-4 text-primary animate-spin" />
              <span className="text-xs font-mono font-bold text-on-surface">
                Discovering Recurring Weaknesses...
              </span>
              <span className="text-[11px] font-mono text-outline">
                (Stage: {activeDiscoveryJob.stage})
              </span>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-xs font-mono font-bold text-primary">
                {activeDiscoveryJob.progressPercent}%
              </span>
              <button
                type="button"
                onClick={handleCancelDiscovery}
                disabled={isCancellingDiscovery}
                className="px-2.5 py-1 text-xs font-mono text-error hover:bg-error-container/20 border border-error-container rounded-lg transition cursor-pointer"
              >
                {isCancellingDiscovery ? 'Cancelling...' : 'Cancel Discovery'}
              </button>
            </div>
          </div>
          <div className="w-full bg-surface-container-lowest h-1.5 rounded-full overflow-hidden">
            <div
              className="bg-primary h-full transition-all duration-300"
              style={{ width: `${Math.max(5, Math.min(100, activeDiscoveryJob.progressPercent))}%` }}
            />
          </div>
        </div>
      )}

      {/* Discovery Job Failure Banner */}
      {activeDiscoveryJob && activeDiscoveryJob.status === 'FAILED' && (
        <StatusBanner
          variant="error"
          title="Discovery Failed"
          message={activeDiscoveryJob.errorMessage || 'Discovery job failed.'}
          action={{
            label: 'Retry Discovery',
            onClick: () => {
              void handleStartDiscovery();
            },
          }}
          onDismiss={() => setActiveDiscoveryJob(null)}
        />
      )}

      {/* Discovery Partial Success Warning Banner */}
      {activeDiscoveryJob && activeDiscoveryJob.status === 'COMPLETED' && Boolean(activeDiscoveryJob.warningMessage) && (
        <StatusBanner
          variant="warning"
          title="Partial Discovery Results"
          message={activeDiscoveryJob.warningMessage || ''}
          onDismiss={() => {
            setActiveDiscoveryJob((prev) => (prev ? { ...prev, warningMessage: null } : null));
          }}
        />
      )}

      {/* Cluster Type Selector Actions */}
      <section className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <button
          type="button"
          onClick={() => handleSelectClusterType('STRUCTURAL')}
          className={`p-4 rounded-2xl border text-left transition cursor-pointer flex items-center justify-between shadow-sm ${
            selectedType === 'STRUCTURAL'
              ? 'bg-surface-container-high border-primary ring-1 ring-primary'
              : 'bg-surface-container-low border-outline-variant hover:border-primary/50'
          }`}
        >
          <div className="flex items-center gap-3.5">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
              selectedType === 'STRUCTURAL' ? 'bg-primary text-on-primary' : 'bg-surface-container text-primary'
            }`}>
              <Layers className="w-5 h-5" />
            </div>
            <div>
              <div className="text-sm font-bold font-mono text-on-surface">
                Structural Weakness Clusters
              </div>
              <p className="text-xs text-on-surface-variant">
                Pawn skeleton, king safety, and enduring pawn structure patterns
              </p>
            </div>
          </div>
          {fetchedTypes.has('STRUCTURAL') && (
            <span className="px-2 py-0.5 rounded-full text-xs font-mono font-bold bg-primary/20 text-primary border border-primary/30 shrink-0">
              {structuralClusters.length}
            </span>
          )}
        </button>

        <button
          type="button"
          onClick={() => handleSelectClusterType('DYNAMIC')}
          className={`p-4 rounded-2xl border text-left transition cursor-pointer flex items-center justify-between shadow-sm ${
            selectedType === 'DYNAMIC'
              ? 'bg-surface-container-high border-primary ring-1 ring-primary'
              : 'bg-surface-container-low border-outline-variant hover:border-primary/50'
          }`}
        >
          <div className="flex items-center gap-3.5">
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
              selectedType === 'DYNAMIC' ? 'bg-primary text-on-primary' : 'bg-surface-container text-primary'
            }`}>
              <Zap className="w-5 h-5" />
            </div>
            <div>
              <div className="text-sm font-bold font-mono text-on-surface">
                Dynamic Weakness Clusters
              </div>
              <p className="text-xs text-on-surface-variant">
                Piece coordination, tempo, initiative, and tactical calculation
              </p>
            </div>
          </div>
          {fetchedTypes.has('DYNAMIC') && (
            <span className="px-2 py-0.5 rounded-full text-xs font-mono font-bold bg-primary/20 text-primary border border-primary/30 shrink-0">
              {dynamicClusters.length}
            </span>
          )}
        </button>
      </section>

      {/* Cluster Load Error Banner */}
      {clusterError && (
        <StatusBanner
          variant="error"
          message={`Failed to load recurring weaknesses: ${clusterError}`}
          action={{
            label: 'Retry',
            onClick: () => {
              if (selectedType) void loadClustersForType(selectedType);
            },
          }}
        />
      )}

      {practiceError && (
        <div
          role="alert"
          className="p-4 rounded-xl border border-error/30 bg-error/10 text-error text-xs font-mono flex items-center justify-between shadow-sm"
        >
          <span>{practiceError}</span>
          <button
            type="button"
            onClick={() => setPracticeError(null)}
            className="text-error hover:text-error/80 cursor-pointer p-1"
            aria-label="Dismiss error"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {/* Content Area */}
      {!selectedType ? (
        <div className="w-full py-16 text-center space-y-4 bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-sm">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-primary">
            <Target className="w-7 h-7" />
          </div>
          <div className="space-y-1">
            <h3 className="text-lg font-bold text-on-surface">Select Cluster Discovery Mode</h3>
            <p className="text-xs text-on-surface-variant leading-relaxed">
              Choose Structural Weakness Clusters to inspect pawn formation habits, or Dynamic Weakness Clusters to explore active tactical and tempo motifs.
            </p>
          </div>
        </div>
      ) : isLoadingClusters ? (
        <StatusBanner
          variant="loading"
          message={`Loading ${selectedType === 'STRUCTURAL' ? 'Structural' : 'Dynamic'} Weakness Clusters...`}
        />
      ) : activeClusters.length === 0 ? (
        <div className="w-full py-16 text-center space-y-4 bg-surface-container-low p-8 rounded-2xl border border-outline-variant shadow-sm">
          <div className="w-14 h-14 rounded-2xl bg-surface-container-lowest border border-outline-variant flex items-center justify-center mx-auto text-outline">
            <Target className="w-7 h-7" />
          </div>
          <div className="space-y-1">
            <h3 className="text-lg font-bold text-on-surface">
              No {selectedType === 'STRUCTURAL' ? 'Structural' : 'Dynamic'} Clusters Found
            </h3>
            <p className="text-xs text-on-surface-variant leading-relaxed">
              Discover recurring mistake patterns grouped by {selectedType.toLowerCase()} criteria across your games.
            </p>
          </div>
          <button
            type="button"
            onClick={() => handleStartDiscovery(selectedType)}
            className="px-4 py-2 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl shadow-sm hover:bg-primary/90 transition inline-flex items-center gap-1.5 cursor-pointer"
          >
            <Sparkles className="w-3.5 h-3.5" />
            <span>Run Discovery Now</span>
          </button>
        </div>
      ) : (
        <div data-testid="weakness-cluster-container" className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
          {activeClusters.map((cluster) => {
            const activeColorInFen = cluster.representativeFen.split(' ')[1];
            const boardOrientation = activeColorInFen === 'b' ? 'black' : 'white';
            const mistakeFreqPercent = Math.round(cluster.mistakeFrequency * 100);
            const blunderRatePercent = Math.round(cluster.blunderRate * 100);

            return (
              <div
                key={cluster.clusterId}
                className="bg-surface-container-low border border-outline-variant hover:border-primary/40 transition-colors rounded-2xl overflow-hidden shadow-sm flex flex-col justify-between"
              >
                <div className="p-4 space-y-3">
                  <div className="flex items-center justify-between text-xs font-mono">
                    <span className="px-2 py-0.5 rounded bg-primary/10 text-primary font-bold border border-primary/20">
                      {cluster.memberPositionCount} variations
                    </span>
                    <span className="text-outline">
                      {cluster.totalOccurrences} occurrences ({cluster.distinctGamesCount} games)
                    </span>
                  </div>

                  <div className="w-full aspect-square max-w-[280px] mx-auto rounded-xl overflow-hidden border border-outline-variant bg-surface-container-lowest">
                    <ChessBoardView
                      fen={cluster.representativeFen}
                      boardOrientation={boardOrientation}
                    />
                  </div>

                  <div className="grid grid-cols-2 gap-2 text-xs font-mono pt-1">
                    <div className="bg-surface-container-high/60 p-2 rounded-lg border border-outline-variant/60">
                      <span className="text-[10px] text-outline block">Mistake Freq: {mistakeFreqPercent}%</span>
                      <span className="font-bold text-amber-accent">{cluster.mistakeCount} mistakes</span>
                    </div>
                    <div className="bg-surface-container-high/60 p-2 rounded-lg border border-outline-variant/60">
                      <span className="text-[10px] text-outline block">Blunder Rate: {blunderRatePercent}%</span>
                      <span className="font-bold text-error">Severe blunders</span>
                    </div>
                  </div>

                  {cluster.timingProfile && (
                    <div className="flex flex-wrap items-center gap-1.5 text-[11px] font-mono text-outline pt-1">
                      <span>Speed profile:</span>
                      {Object.entries(cluster.timingProfile).map(([speed, count]) => (
                        <span key={speed} className="px-1.5 py-0.5 bg-surface-container rounded border border-outline-variant text-on-surface">
                          {speed}: {count}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <div className="p-3 bg-surface-container-lowest border-t border-outline-variant flex items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => setInspectingCluster(cluster)}
                    className="w-1/2 py-2 bg-surface-container hover:bg-surface-container-high border border-outline-variant text-on-surface text-xs font-mono font-bold rounded-xl transition flex items-center justify-center gap-1.5 shadow-sm cursor-pointer"
                  >
                    <List className="w-3.5 h-3.5" />
                    <span>Inspect Evidence</span>
                  </button>
                  <button
                    type="button"
                    disabled={loadingPracticeClusterId === cluster.clusterId}
                    onClick={() => { void handlePracticeCluster(cluster); }}
                    className="w-1/2 py-2 bg-primary hover:bg-primary/90 disabled:opacity-50 text-on-primary text-xs font-mono font-bold rounded-xl transition flex items-center justify-center gap-1.5 shadow-sm cursor-pointer"
                  >
                    {loadingPracticeClusterId === cluster.clusterId ? (
                      <div className="w-3.5 h-3.5 border-2 border-on-primary border-t-transparent rounded-full animate-spin" />
                    ) : (
                      <Swords className="w-3.5 h-3.5" />
                    )}
                    <span>Practice This Weakness</span>
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Cluster Evidence Modal */}
      {inspectingCluster && (
        <ClusterEvidenceModal
          cluster={inspectingCluster}
          accountId={accountId}
          onClose={() => setInspectingCluster(null)}
          onSelectPractice={(puzzle, fullList) => {
            onSelectPractice(puzzle, fullList, {
              clusterType: inspectingCluster.clusterType,
              expandedClusterId: inspectingCluster.clusterId,
            });
          }}
        />
      )}

      {/* Discovery Modal */}
      {isDiscoveryModalOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="discovery-modal-title"
          className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4 backdrop-blur-sm"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setIsDiscoveryModalOpen(false);
          }}
        >
          <div className="bg-surface-container-high border border-outline-variant rounded-2xl w-full max-w-md p-6 space-y-5 shadow-2xl">
            <div className="flex items-center justify-between border-b border-outline-variant pb-3">
              <h3 id="discovery-modal-title" className="text-base font-bold text-on-surface flex items-center gap-2">
                <Sparkles className="w-4 h-4 text-primary" />
                <span>Discover Recurring Weaknesses</span>
              </h3>
              <button
                type="button"
                aria-label="Close"
                onClick={() => setIsDiscoveryModalOpen(false)}
                className="text-on-surface-variant hover:text-on-surface p-1 rounded-lg hover:bg-surface-bright transition cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {discoverySubmitError && (
              <div className="p-3 bg-error-container/20 border border-error-container rounded-xl text-xs text-error">
                {discoverySubmitError}
              </div>
            )}

            {/* Time Controls */}
            <div className="space-y-2">
              <label className="text-xs font-mono text-outline block">Time Controls</label>
              <div className="flex gap-2">
                {(['RAPID', 'BLITZ', 'BULLET'] as const).map((tc) => {
                  const label = tc.charAt(0) + tc.slice(1).toLowerCase();
                  const selected = discoveryTimeControls.includes(tc);
                  return (
                    <button
                      key={tc}
                      type="button"
                      onClick={() => handleToggleTimeControl(tc)}
                      className={`flex-1 py-1.5 text-xs font-mono font-semibold rounded-lg border transition cursor-pointer ${
                        selected
                          ? 'bg-primary-container text-on-primary-container border-primary'
                          : 'bg-surface-container border-outline-variant text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      {label}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Threshold Slider */}
            <div className="space-y-2">
              <div className="flex justify-between items-center text-xs font-mono">
                <label className="text-outline">Min Evaluation Loss</label>
                <span className="font-bold text-primary">{discoveryMinEvalLoss} pawns</span>
              </div>
              <input
                type="range"
                min={0.0}
                max={3.0}
                step={0.1}
                value={discoveryMinEvalLoss}
                onChange={(e) => setDiscoveryMinEvalLoss(parseFloat(e.target.value))}
                className="w-full accent-primary cursor-pointer"
              />
            </div>

            <div className="flex justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => setIsDiscoveryModalOpen(false)}
                className="px-4 py-2 text-xs font-mono text-on-surface-variant hover:bg-surface-bright rounded-xl transition cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleStartDiscovery()}
                disabled={isSubmittingDiscovery || discoveryTimeControls.length === 0}
                className="px-4 py-2 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl shadow-sm hover:bg-primary/90 transition disabled:opacity-50 cursor-pointer"
              >
                {isSubmittingDiscovery ? 'Starting...' : 'Start Discovery'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Account Connection Prompt Modal */}
      {isAccountPromptOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="account-prompt-title"
          className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4 backdrop-blur-sm"
          onMouseDown={(e) => {
            if (e.target === e.currentTarget) setIsAccountPromptOpen(false);
          }}
        >
          <div className="bg-surface-container-high border border-outline-variant rounded-2xl w-full max-w-sm p-6 space-y-4 text-center shadow-2xl">
            <div className="w-12 h-12 rounded-xl bg-primary/20 text-primary flex items-center justify-center mx-auto">
              <Target className="w-6 h-6" />
            </div>
            <div>
              <h3 id="account-prompt-title" className="text-base font-bold text-on-surface">Connect Account</h3>
              <p className="text-xs text-on-surface-variant mt-1.5 leading-relaxed">
                Connect your Chess.com account in the Import Games tab to analyze your recurring opening weaknesses.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setIsAccountPromptOpen(false)}
              className="w-full py-2 bg-primary text-on-primary font-mono font-bold text-xs rounded-xl hover:bg-primary/90 transition cursor-pointer"
            >
              Dismiss
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
