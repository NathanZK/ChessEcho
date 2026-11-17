'use client';

import React, { useState, useSyncExternalStore } from 'react';
import { Header, TabType } from '@/components/Header';
import type { PositionExplorationStart } from '@/components/ExplorePositionModal';
import {
  PracticePuzzlesView,
  type PracticePuzzlesViewHandle,
} from '@/components/PracticePuzzlesView';
import { WeaknessesList } from '@/components/WeaknessesList';
import { LongDecisionsView } from '@/components/LongDecisionsView';
import {
  SimilarPositionsView,
  type SimilarPositionsReturnSnapshot,
} from '@/components/SimilarPositionsView';
import { PositionProgressView } from '@/components/PositionProgressView';
import { ImportGamesView } from '@/components/ImportGamesView';
import { GameAnalysisView } from '@/components/GameAnalysisView';
import { Puzzle, type ColorFilter } from '@/types/puzzle';
import {
  JobStatusResponse,
  type LongDecisionOccurrence,
  type GameAnalysisResponse,
  type AnalyzedPly,
  type SimilarPositionResult,
} from '@/services/api';
import { useAccountSession } from '@/hooks/useAccountSession';
import {
  activeTabStore,
  activeJobStore,
  puzzleSettingsStore,
  gameAnalysisReturnStore,
  type GameAnalysisReturnSnapshot,
} from '@/utils/browserStores';
import {
  buildMistakePracticePuzzle,
  buildSimilarPositionPracticePuzzle,
} from '@/utils/gameAnalysisPuzzle';
import type { DecisionSeverityCategory } from '@/utils/gameAnalysisFilters';

export default function Home() {
  const activeTab = useSyncExternalStore(
    activeTabStore.subscribe,
    activeTabStore.getSnapshot,
    activeTabStore.getServerSnapshot
  );

  const practiceActionsRef = React.useRef<PracticePuzzlesViewHandle | null>(null);

  const [activeJobStatus, setActiveJobStatus] = useState<JobStatusResponse | null>(null);
  const [weaknessRefreshKey, setWeaknessRefreshKey] = useState<number>(0);
  const prevJobRef = React.useRef<JobStatusResponse | null>(null);

  const clearLiveJobState = React.useCallback(() => {
    prevJobRef.current = null;
    setActiveJobStatus(null);
  }, []);

  const [weaknessCount, setWeaknessCount] = useState<number>(0);
  const [selectedProgress, setSelectedProgress] = useState<{
    positionId: string;
    playerColor: 'WHITE' | 'BLACK';
    minEvalLoss: number;
    accountId?: string;
    ecoCode?: string;
  } | null>(null);

  const [hasLongDecisionReturnContext, setHasLongDecisionReturnContext] = useState(false);
  const [hasSimilarPositionsReturnContext, setHasSimilarPositionsReturnContext] = useState(false);
  const [hasGameAnalysisReturnContext, setHasGameAnalysisReturnContext] = useState(false);
  const [similarPositionsSnapshot, setSimilarPositionsSnapshot] = useState<SimilarPositionsReturnSnapshot | null>(null);
  const [gameAnalysisSnapshot, setGameAnalysisSnapshot] = useState<GameAnalysisReturnSnapshot | null>(null);
  const [importPresetTimeControls, setImportPresetTimeControls] = useState<string[] | undefined>();
  const [isSourceNeutralExploration, setIsSourceNeutralExploration] = useState(false);

  const onClearAccountState = React.useCallback(() => {
    clearLiveJobState();
    practiceActionsRef.current?.clearAccountUiState();
    activeJobStore.set(null);
    setWeaknessCount(0);
    setSelectedProgress(null);
    setHasLongDecisionReturnContext(false);
    setHasSimilarPositionsReturnContext(false);
    setHasGameAnalysisReturnContext(false);
    setSimilarPositionsSnapshot(null);
    setGameAnalysisSnapshot(null);
    gameAnalysisReturnStore.set(null);
    setWeaknessRefreshKey((key) => key + 1);
  }, [clearLiveJobState]);

  const session = useAccountSession({ onClearAccountState });

  const previousAccountIdRef = React.useRef<string | undefined>(session.activeAccount?.id);
  React.useEffect(() => {
    if (
      previousAccountIdRef.current &&
      session.activeAccount?.id &&
      previousAccountIdRef.current !== session.activeAccount.id
    ) {
      activeJobStore.set(null);
    }
    previousAccountIdRef.current = session.activeAccount?.id;
  }, [session.activeAccount?.id]);

  const handleJobStatusUpdate = (job: JobStatusResponse | null) => {
    const previousJob = prevJobRef.current;
    const importCompleted =
      job?.status === 'COMPLETED' && previousJob?.status !== 'COMPLETED';
    const analysisCompleted =
      previousJob?.analysisStatus === 'ANALYZING' &&
      (job?.analysisStatus === 'COMPLETED' || job?.analysisStatus === 'FAILED');
    if (importCompleted || analysisCompleted) {
      setWeaknessRefreshKey((key) => key + 1);
    }
    prevJobRef.current = job;
    setActiveJobStatus(job);
  };

  const changeTab = React.useCallback((tab: TabType) => {
    activeTabStore.set(tab);
    if (typeof window !== 'undefined') {
      window.history.pushState(null, '', `#${tab}`);
    }
    if (tab !== 'puzzles') {
      setHasLongDecisionReturnContext(false);
      setHasSimilarPositionsReturnContext(false);
      setHasGameAnalysisReturnContext(false);
    }
  }, []);

  const handleSetUsername = (user: string | undefined) => {
    if (user !== session.activeUsername) {
      practiceActionsRef.current?.invalidatePuzzleRequests();
    }
    session.handleSetUsername(user);
  };

  // Browser filter settings restore
  const settings = useSyncExternalStore(
    puzzleSettingsStore.subscribe,
    puzzleSettingsStore.getSnapshot,
    puzzleSettingsStore.getServerSnapshot
  );
  const [appliedSettings, setAppliedSettings] = useState(puzzleSettingsStore.getServerSnapshot);
  const [minEvalLoss, setMinEvalLoss] = useState<number>(0.8);
  const [minMistakeCount, setMinMistakeCount] = useState<number>(3);
  const [puzzleColorFilter, setPuzzleColorFilter] = useState<'BOTH' | 'WHITE' | 'BLACK'>('BOTH');
  const [weaknessClusterSnapshot, setWeaknessClusterSnapshot] = useState<{
    clusterType?: 'STRUCTURAL' | 'DYNAMIC';
    expandedClusterId?: string;
  } | null>(null);

  if (appliedSettings !== settings) {
    setAppliedSettings(settings);
    setPuzzleColorFilter(settings.colorFilter);
    setMinEvalLoss(settings.minEvalLoss);
    setMinMistakeCount(settings.minMistakeCount);
  }

  const handleMinEvalLossChange = (val: number) => {
    practiceActionsRef.current?.invalidatePuzzleRequests();
    setMinEvalLoss(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_eval_loss', String(val));
    }
  };

  const handleMinMistakeCountChange = (val: number) => {
    practiceActionsRef.current?.invalidatePuzzleRequests();
    setMinMistakeCount(val);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_min_mistake_count', String(val));
    }
  };

  const handleColorFilterChange = (color: ColorFilter | 'ALL') => {
    const normalized: ColorFilter = color === 'ALL' ? 'BOTH' : color;
    practiceActionsRef.current?.invalidatePuzzleRequests();
    setPuzzleColorFilter(normalized);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_puzzle_color_filter', normalized);
      const weaknessColor = normalized === 'BOTH' ? 'ALL' : normalized;
      localStorage.setItem('chessecho_weakness_color_filter', weaknessColor);
    }
  };

  const handleExplorePosition = (position: PositionExplorationStart): boolean => {
    const success = practiceActionsRef.current?.explorePosition(position, false, activeTab) ?? false;
    if (success) {
      changeTab('puzzles');
    }
    return success;
  };

  const handleLongDecisionExplorePosition = (
    occurrenceOrFen: LongDecisionOccurrence | string
  ): boolean => {
    const success = practiceActionsRef.current?.exploreLongDecision(occurrenceOrFen) ?? false;
    if (success) {
      setHasLongDecisionReturnContext(true);
      changeTab('puzzles');
    }
    return success;
  };

  const handleSimilarPositionsExplorePosition = (position: PositionExplorationStart): boolean => {
    const success = practiceActionsRef.current?.exploreSimilarPositions(position) ?? false;
    if (success) {
      setHasSimilarPositionsReturnContext(true);
      changeTab('puzzles');
    }
    return success;
  };

  const handleSelectPracticeFromLibrary = (
    puzzle: Puzzle,
    fullList?: Puzzle[],
    context?: { clusterType?: 'STRUCTURAL' | 'DYNAMIC'; expandedClusterId?: string }
  ) => {
    if (context) {
      setWeaknessClusterSnapshot(context);
    }
    const returnTab = activeTab === 'weakness-analysis' ? 'weakness-analysis' : 'weaknesses';
    changeTab('puzzles');
    practiceActionsRef.current?.selectPracticeFromLibrary(puzzle, fullList, returnTab);
  };

  const handleSimilarPositionsSnapshotChange = React.useCallback(
    (snapshot: SimilarPositionsReturnSnapshot | null) => {
      setSimilarPositionsSnapshot(snapshot);
    },
    []
  );

  const handleGameAnalysisPracticeMistake = (
    ply: AnalyzedPly,
    analysis: GameAnalysisResponse
  ) => {
    const synthesizedPuzzle = buildMistakePracticePuzzle(ply, analysis);
    setHasGameAnalysisReturnContext(true);
    const returnSnapshot: GameAnalysisReturnSnapshot = {
      gameId: analysis.gameId,
      plyNumber: ply.plyNumber,
      evalLossThreshold: gameAnalysisSnapshot?.evalLossThreshold ?? 0.8,
      timingFilters: gameAnalysisSnapshot?.timingFilters ?? ['All'],
      expandedSections: gameAnalysisSnapshot?.expandedSections,
    };
    setGameAnalysisSnapshot(returnSnapshot);
    gameAnalysisReturnStore.set(returnSnapshot);

    changeTab('puzzles');
    practiceActionsRef.current?.selectPracticeFromLibrary(
      synthesizedPuzzle,
      [synthesizedPuzzle],
      'game-analysis'
    );
  };

  const handleGameAnalysisPracticeSimilarPosition = (
    match: SimilarPositionResult,
    analysis: GameAnalysisResponse,
    activePly?: AnalyzedPly
  ) => {
    const synthesizedPuzzle = buildSimilarPositionPracticePuzzle(match, analysis);
    setHasGameAnalysisReturnContext(true);
    const returnSnapshot: GameAnalysisReturnSnapshot = {
      gameId: analysis.gameId,
      plyNumber: activePly?.plyNumber ?? gameAnalysisSnapshot?.plyNumber ?? 1,
      evalLossThreshold: gameAnalysisSnapshot?.evalLossThreshold ?? 0.8,
      timingFilters: gameAnalysisSnapshot?.timingFilters ?? ['All'],
      expandedSections: gameAnalysisSnapshot?.expandedSections,
    };
    setGameAnalysisSnapshot(returnSnapshot);
    gameAnalysisReturnStore.set(returnSnapshot);

    changeTab('puzzles');
    practiceActionsRef.current?.selectPracticeFromLibrary(
      synthesizedPuzzle,
      [synthesizedPuzzle],
      'game-analysis'
    );
  };

  const handleGameAnalysisThresholdChange = React.useCallback((threshold: number) => {
    setGameAnalysisSnapshot((prev) =>
      prev
        ? { ...prev, evalLossThreshold: threshold }
        : {
            gameId: '',
            plyNumber: 1,
            evalLossThreshold: threshold,
            timingFilters: ['All'],
          }
    );
  }, []);

  const handleGameAnalysisTimingFiltersChange = React.useCallback((filters: DecisionSeverityCategory[]) => {
    setGameAnalysisSnapshot((prev) =>
      prev
        ? { ...prev, timingFilters: filters }
        : {
            gameId: '',
            plyNumber: 1,
            evalLossThreshold: 0.8,
            timingFilters: filters,
          }
    );
  }, []);

  const handleGameAnalysisExpandedSectionsChange = (sections: {
    structural?: boolean;
    dynamic?: boolean;
  }) => {
    setGameAnalysisSnapshot((prev) =>
      prev
        ? { ...prev, expandedSections: sections }
        : {
            gameId: '',
            plyNumber: 1,
            evalLossThreshold: 0.8,
            timingFilters: ['All'],
            expandedSections: sections,
          }
    );
  };

  const showingPositionProgress =
    (activeTab === 'weaknesses' || activeTab === 'weakness-analysis') &&
    selectedProgress !== null;

  return (
    <div
      className={`${showingPositionProgress ? 'h-dvh' : 'h-screen'} bg-surface text-on-surface flex flex-col font-sans selection:bg-primary-container selection:text-on-primary-container overflow-hidden`}
    >
      {/* Top Header */}
      <Header
        activeTab={activeTab}
        setActiveTab={changeTab}
        onExplorePosition={handleExplorePosition}
        username={session.displayedUsername}
        connectedAccount={session.connectedAccount}
        weaknessCount={weaknessCount}
        onDisconnect={session.handleLogout}
        sessionStatus={session.sessionStatus}
        accountStatus={session.accountStatus}
        isSourceNeutralExploration={isSourceNeutralExploration}
      />

      {/* Main Content Area */}
      <main
        className={`flex-1 min-w-0 flex flex-col ${
          showingPositionProgress ? 'min-h-0' : ''
        } ${
          activeTab === 'import'
            ? 'justify-center overflow-hidden py-2'
            : 'justify-start overflow-y-auto py-2'
        }`}
      >
        {/* TAB 1: PRACTICE PUZZLES */}
        <PracticePuzzlesView
          hidden={activeTab !== 'puzzles'}
          actionsRef={practiceActionsRef}
          sessionStatus={session.sessionStatus}
          sessionUserId={session.sessionUserId}
          activeAccountId={session.activeAccountId}
          activeUsername={session.activeUsername}
          sessionGatePromise={session.sessionGatePromise}
          sessionGateOpen={session.sessionGateOpen}
          onNavigateTab={changeTab}
          onWeaknessCountChange={setWeaknessCount}
          onSourceNeutralExplorationChange={setIsSourceNeutralExploration}
          onReturnToTab={changeTab}
          hasLongDecisionReturnContext={hasLongDecisionReturnContext}
          hasSimilarPositionsReturnContext={hasSimilarPositionsReturnContext}
          hasGameAnalysisReturnContext={hasGameAnalysisReturnContext}
        />

        {/* TAB 1: WEAKNESS LIBRARY */}
        {activeTab === 'weaknesses' && (
          <WeaknessesList
            mode="library"
            accountId={session.sessionGateOpen ? session.connectedAccount?.id : undefined}
            username={session.sessionGateOpen ? session.weaknessDisplayUsername : undefined}
            isAnalysisActive={
              !!session.activeUsername && activeJobStatus?.analysisStatus === 'ANALYZING'
            }
            sessionStatus={session.sessionStatus}
            onSelectPractice={handleSelectPracticeFromLibrary}
            onWeaknessCountChange={setWeaknessCount}
            refreshKey={weaknessRefreshKey}
            initialClusterType={weaknessClusterSnapshot?.clusterType}
            initialExpandedClusterId={weaknessClusterSnapshot?.expandedClusterId}
          />
        )}

        {/* TAB 1b: EXACT-POSITION ANALYSIS */}
        {activeTab === 'weakness-analysis' && (
          <>
            <WeaknessesList
              mode="analysis"
              accountId={session.sessionGateOpen ? session.connectedAccount?.id : undefined}
              username={session.sessionGateOpen ? session.weaknessDisplayUsername : undefined}
              sessionStatus={session.sessionStatus}
              minEvalLoss={minEvalLoss}
              onMinEvalLossChange={handleMinEvalLossChange}
              minMistakeCount={minMistakeCount}
              onMinMistakeCountChange={handleMinMistakeCountChange}
              onSelectPractice={handleSelectPracticeFromLibrary}
              onViewProgress={
                session.sessionStatus === 'unauthenticated' || session.connectedAccount?.id
                  ? (positionId, playerColor, minEvalLossVal, ecoCode) =>
                      setSelectedProgress({
                        positionId,
                        playerColor,
                        minEvalLoss: minEvalLossVal,
                        accountId: session.connectedAccount?.id,
                        ecoCode,
                      })
                  : undefined
              }
              onWeaknessCountChange={setWeaknessCount}
              activeColorFilter={puzzleColorFilter}
              onColorFilterChange={handleColorFilterChange}
              isAnalysisActive={
                !!session.activeUsername && activeJobStatus?.analysisStatus === 'ANALYZING'
              }
              refreshKey={weaknessRefreshKey}
            />

            {selectedProgress && (
              <PositionProgressView
                positionId={selectedProgress.positionId}
                playerColor={selectedProgress.playerColor}
                minEvalLoss={selectedProgress.minEvalLoss}
                accountId={selectedProgress.accountId}
                ecoCode={selectedProgress.ecoCode}
                sessionStatus={session.sessionStatus}
                onBack={() => setSelectedProgress(null)}
                onClose={() => setSelectedProgress(null)}
              />
            )}
          </>
        )}

        {(activeTab === 'long-decisions' || hasLongDecisionReturnContext) && (
          <div hidden={activeTab !== 'long-decisions'}>
            <LongDecisionsView
              accountId={session.connectedAccount?.id}
              sessionStatus={session.sessionStatus}
              accountStatus={session.accountStatus}
              onRetryAccountLoad={session.retryAccountLoad}
              onNavigateImport={() => changeTab('import')}
              onExplorePosition={handleLongDecisionExplorePosition}
            />
          </div>
        )}

        {(activeTab === 'similar-positions' || hasSimilarPositionsReturnContext) && (
          <div hidden={activeTab !== 'similar-positions'}>
            <SimilarPositionsView
              connectedAccount={session.connectedAccount}
              guestUsername={
                !session.connectedAccount
                  ? session.displayedUsername || session.activeUsername
                  : undefined
              }
              onExplorePosition={handleSimilarPositionsExplorePosition}
              initialSnapshot={similarPositionsSnapshot}
              onSnapshotChange={handleSimilarPositionsSnapshotChange}
            />
          </div>
        )}

        {(activeTab === 'game-analysis' || hasGameAnalysisReturnContext) && (
          <div hidden={activeTab !== 'game-analysis'}>
            <GameAnalysisView
              sessionStatus={session.sessionStatus}
              initialGameId={gameAnalysisSnapshot?.gameId || undefined}
              initialPlyNumber={gameAnalysisSnapshot?.plyNumber}
              initialThreshold={gameAnalysisSnapshot?.evalLossThreshold}
              initialTimingFilters={gameAnalysisSnapshot?.timingFilters}
              initialExpandedSections={gameAnalysisSnapshot?.expandedSections}
              accountId={session.connectedAccount?.id}
              guestUsername={
                !session.connectedAccount
                  ? session.displayedUsername || session.activeUsername
                  : undefined
              }
              onExpandedSectionsChange={handleGameAnalysisExpandedSectionsChange}
              onThresholdChange={handleGameAnalysisThresholdChange}
              onTimingFiltersChange={handleGameAnalysisTimingFiltersChange}
              onPracticeMistake={handleGameAnalysisPracticeMistake}
              onPracticeSimilarPosition={handleGameAnalysisPracticeSimilarPosition}
              onNavigateImport={(timeControls) => {
                setImportPresetTimeControls(timeControls);
                changeTab('import');
              }}
            />
          </div>
        )}

        {/* TAB 3: IMPORT GAMES */}
        {activeTab === 'import' && (
          <ImportGamesView
            initialTimeControls={importPresetTimeControls}
            connectedUsername={session.displayedUsername}
            connectedAccount={session.connectedAccount}
            connectedAccountId={session.connectedAccount?.id}
            sessionStatus={session.sessionStatus}
            accountStatus={session.accountStatus}
            accountError={session.accountError}
            onConnectAccount={async (username) => {
              await session.connectChessAccount(username);
            }}
            onRetryAccountLoad={session.retryAccountLoad}
            onDisconnect={session.handleDisconnect}
            disconnectError={session.disconnectError}
            isDisconnecting={session.isDisconnecting}
            onImportStarted={(user) => handleSetUsername(user)}
            onNavigateTab={(tab) => changeTab(tab)}
            onJobStatusUpdate={handleJobStatusUpdate}
          />
        )}
      </main>
    </div>
  );
}
