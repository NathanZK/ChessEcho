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
import { Puzzle } from '@/types/puzzle';
import { JobStatusResponse } from '@/services/api';
import { useAccountSession } from '@/hooks/useAccountSession';
import {
  activeTabStore,
  activeJobStore,
  puzzleSettingsStore,
} from '@/utils/browserStores';

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
  } | null>(null);

  const [hasLongDecisionReturnContext, setHasLongDecisionReturnContext] = useState(false);
  const [hasSimilarPositionsReturnContext, setHasSimilarPositionsReturnContext] = useState(false);
  const [similarPositionsSnapshot, setSimilarPositionsSnapshot] = useState<SimilarPositionsReturnSnapshot | null>(null);
  const [isSourceNeutralExploration, setIsSourceNeutralExploration] = useState(false);

  const onClearAccountState = React.useCallback(() => {
    clearLiveJobState();
    practiceActionsRef.current?.clearAccountUiState();
    activeJobStore.set(null);
    setWeaknessCount(0);
    setSelectedProgress(null);
    setHasLongDecisionReturnContext(false);
    setHasSimilarPositionsReturnContext(false);
    setSimilarPositionsSnapshot(null);
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

  const handleColorFilterChange = (color: 'BOTH' | 'WHITE' | 'BLACK') => {
    practiceActionsRef.current?.invalidatePuzzleRequests();
    setPuzzleColorFilter(color);
    if (typeof window !== 'undefined') {
      localStorage.setItem('chessecho_puzzle_color_filter', color);
      const weaknessColor = color === 'BOTH' ? 'ALL' : color;
      localStorage.setItem('chessecho_weakness_color_filter', weaknessColor);
    }
  };

  const handleExplorePosition = (position: PositionExplorationStart) => {
    changeTab('puzzles');
    practiceActionsRef.current?.explorePosition(position, false, activeTab);
  };

  const handleLongDecisionExplorePosition = (fen: string): boolean => {
    const success = practiceActionsRef.current?.exploreLongDecision(fen) ?? false;
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

  const handleSelectPracticeFromLibrary = (puzzle: Puzzle, fullList?: Puzzle[]) => {
    changeTab('puzzles');
    practiceActionsRef.current?.selectPracticeFromLibrary(puzzle, fullList);
  };

  const handleSimilarPositionsSnapshotChange = React.useCallback(
    (snapshot: SimilarPositionsReturnSnapshot | null) => {
      setSimilarPositionsSnapshot(snapshot);
    },
    []
  );

  const showingPositionProgress = activeTab === 'weaknesses' && selectedProgress !== null;

  return (
    <div
      className={`${showingPositionProgress ? 'h-dvh' : 'h-screen'} bg-surface text-on-surface flex flex-col font-sans selection:bg-primary-container selection:text-on-primary-container overflow-hidden ${
        activeTab === 'puzzles' ? '2xl:flex-row' : ''
      }`}
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
        />

        {/* TAB 2: WEAKNESSES LIBRARY */}
        {activeTab === 'weaknesses' &&
          (selectedProgress ? (
            <PositionProgressView
              positionId={selectedProgress.positionId}
              playerColor={selectedProgress.playerColor}
              minEvalLoss={selectedProgress.minEvalLoss}
              accountId={selectedProgress.accountId}
              sessionStatus={session.sessionStatus}
              onBack={() => setSelectedProgress(null)}
            />
          ) : (
            <WeaknessesList
              accountId={session.sessionGateOpen ? session.connectedAccount?.id : undefined}
              username={session.sessionGateOpen ? session.weaknessDisplayUsername : undefined}
              minEvalLoss={minEvalLoss}
              onMinEvalLossChange={handleMinEvalLossChange}
              minMistakeCount={minMistakeCount}
              onMinMistakeCountChange={handleMinMistakeCountChange}
              onSelectPractice={handleSelectPracticeFromLibrary}
              onViewProgress={
                session.sessionStatus === 'unauthenticated' || session.connectedAccount?.id
                  ? (positionId, playerColor, minEvalLossVal) =>
                      setSelectedProgress({
                        positionId,
                        playerColor,
                        minEvalLoss: minEvalLossVal,
                        accountId: session.connectedAccount?.id,
                      })
                  : undefined
              }
              onWeaknessCountChange={setWeaknessCount}
              activeColorFilter={puzzleColorFilter === 'BOTH' ? 'ALL' : puzzleColorFilter}
              onColorFilterChange={(c) => handleColorFilterChange(c === 'ALL' ? 'BOTH' : c)}
              isAnalysisActive={
                !!session.activeUsername && activeJobStatus?.analysisStatus === 'ANALYZING'
              }
              refreshKey={weaknessRefreshKey}
            />
          ))}

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

        {/* TAB 3: IMPORT GAMES */}
        {activeTab === 'import' && (
          <ImportGamesView
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
