'use client';

import React, { useState, useCallback, useEffect } from 'react';
import { fetchSimilarPositions } from '../services/api';
import type {
  AnalyzedPly,
  GameAnalysisResponse,
  SimilarPositionResult,
} from '../services/api';

export async function querySimilarPositions(
  fen: string,
  mode: 'STRUCTURAL' | 'DYNAMIC',
  gameId: string,
  accountId?: string,
  guestUsername?: string
): Promise<SimilarPositionResult[]> {
  const res = await fetchSimilarPositions({
    fen,
    mode,
    limit: 5,
    excludeGameId: gameId,
    accountId,
    username: guestUsername,
  });
  return res.results;
}

export function buildSimilarityIdentityKey(
  gameId: string,
  accountId?: string,
  guestUsername?: string,
  fen?: string
): string {
  return `${gameId}:${accountId ?? ''}:${guestUsername ?? ''}:${fen ?? ''}`;
}

function useSimilaritySectionState(initialSections?: { structural?: boolean; dynamic?: boolean }) {
  const [isStructuralExpanded, setIsStructuralExpanded] = useState(initialSections?.structural ?? false);
  const [isDynamicExpanded, setIsDynamicExpanded] = useState(initialSections?.dynamic ?? false);
  const [structuralResults, setStructuralResults] = useState<SimilarPositionResult[] | null>(null);
  const [dynamicResults, setDynamicResults] = useState<SimilarPositionResult[] | null>(null);
  const [isSearchingStructural, setIsSearchingStructural] = useState(false);
  const [isSearchingDynamic, setIsSearchingDynamic] = useState(false);
  const [structuralError, setStructuralError] = useState<string | null>(null);
  const [dynamicError, setDynamicError] = useState<string | null>(null);

  const resetAll = useCallback(() => {
    setIsStructuralExpanded(false);
    setIsDynamicExpanded(false);
    setStructuralResults(null);
    setDynamicResults(null);
    setStructuralError(null);
    setDynamicError(null);
  }, []);

  return {
    isStructuralExpanded, setIsStructuralExpanded,
    isDynamicExpanded, setIsDynamicExpanded,
    structuralResults, setStructuralResults,
    dynamicResults, setDynamicResults,
    isSearchingStructural, setIsSearchingStructural,
    isSearchingDynamic, setIsSearchingDynamic,
    structuralError, setStructuralError,
    dynamicError, setDynamicError,
    resetAll,
  };
}

function useInitialExpandedFetch(
  preMoveFen: string | undefined,
  analysisId: string,
  accountId: string | undefined,
  guestUsername: string | undefined,
  initialSections: { structural?: boolean; dynamic?: boolean } | undefined,
  currentKeyRef: React.RefObject<string>,
  state: ReturnType<typeof useSimilaritySectionState>
) {
  useEffect(() => {
    let isMounted = true;
    if (!preMoveFen) return () => { isMounted = false; };
    const requestKey = buildSimilarityIdentityKey(analysisId, accountId, guestUsername, preMoveFen);
    const run = (mode: 'STRUCTURAL' | 'DYNAMIC') => {
      querySimilarPositions(preMoveFen, mode, analysisId, accountId, guestUsername)
        .then((res) => {
          if (!isMounted || currentKeyRef.current !== requestKey) return;
          if (mode === 'STRUCTURAL') state.setStructuralResults(res);
          else state.setDynamicResults(res);
        })
        .catch((err) => {
          if (!isMounted || currentKeyRef.current !== requestKey) return;
          const msg = err instanceof Error ? err.message : 'Failed to load positions';
          if (mode === 'STRUCTURAL') state.setStructuralError(msg);
          else state.setDynamicError(msg);
        });
    };

    if (initialSections?.structural) run('STRUCTURAL');
    if (initialSections?.dynamic) run('DYNAMIC');
    return () => { isMounted = false; };
  }, [initialSections?.structural, initialSections?.dynamic, preMoveFen, analysisId, accountId, guestUsername, currentKeyRef, state]);
}

function useSimilarityLoader(
  gameId: string,
  accountId: string | undefined,
  guestUsername: string | undefined,
  currentKeyRef: React.RefObject<string>,
  state: ReturnType<typeof useSimilaritySectionState>
) {
  return useCallback(async (mode: 'STRUCTURAL' | 'DYNAMIC', fen: string) => {
    const isStruct = mode === 'STRUCTURAL';
    const requestKey = buildSimilarityIdentityKey(gameId, accountId, guestUsername, fen);
    if (isStruct) { state.setIsSearchingStructural(true); state.setStructuralError(null); }
    else { state.setIsSearchingDynamic(true); state.setDynamicError(null); }
    try {
      const results = await querySimilarPositions(fen, mode, gameId, accountId, guestUsername);
      if (currentKeyRef.current !== requestKey) return;
      if (isStruct) state.setStructuralResults(results);
      else state.setDynamicResults(results);
    } catch (err) {
      if (currentKeyRef.current !== requestKey) return;
      const msg = err instanceof Error ? err.message : `Failed to load ${mode.toLowerCase()} similar positions`;
      if (isStruct) state.setStructuralError(msg);
      else state.setDynamicError(msg);
    } finally {
      if (currentKeyRef.current === requestKey) {
        if (isStruct) state.setIsSearchingStructural(false);
        else state.setIsSearchingDynamic(false);
      }
    }
  }, [gameId, accountId, guestUsername, currentKeyRef, state]);
}

export function useMistakeSimilarity(
  activePly: AnalyzedPly | null,
  analysis: GameAnalysisResponse,
  initialSections?: { structural?: boolean; dynamic?: boolean },
  onExpandedChange?: (sections: { structural?: boolean; dynamic?: boolean }) => void,
  accountId?: string,
  guestUsername?: string
) {
  const preMoveFen = activePly?.preMoveFen;
  const identityKey = buildSimilarityIdentityKey(analysis.gameId, accountId, guestUsername, preMoveFen);
  const currentKeyRef = React.useRef(identityKey);
  useEffect(() => { currentKeyRef.current = identityKey; }, [identityKey]);

  const [activeKey, setActiveKey] = useState(identityKey);
  const state = useSimilaritySectionState(initialSections);

  if (identityKey !== activeKey) {
    setActiveKey(identityKey);
    state.resetAll();
  }

  const loadMode = useSimilarityLoader(analysis.gameId, accountId, guestUsername, currentKeyRef, state);
  useInitialExpandedFetch(preMoveFen, analysis.gameId, accountId, guestUsername, initialSections, currentKeyRef, state);

  const toggleSection = (mode: 'STRUCTURAL' | 'DYNAMIC') => {
    const isStruct = mode === 'STRUCTURAL';
    const next = isStruct ? !state.isStructuralExpanded : !state.isDynamicExpanded;
    if (isStruct) state.setIsStructuralExpanded(next);
    else state.setIsDynamicExpanded(next);
    onExpandedChange?.({
      structural: isStruct ? next : state.isStructuralExpanded,
      dynamic: isStruct ? state.isDynamicExpanded : next,
    });
    const results = isStruct ? state.structuralResults : state.dynamicResults;
    const isSearching = isStruct ? state.isSearchingStructural : state.isSearchingDynamic;
    if (next && !results && !isSearching && activePly) {
      loadMode(mode, activePly.preMoveFen);
    }
  };

  return {
    ...state,
    handleToggleStructural: () => toggleSection('STRUCTURAL'),
    handleToggleDynamic: () => toggleSection('DYNAMIC'),
    retryStructural: () => activePly && loadMode('STRUCTURAL', activePly.preMoveFen),
    retryDynamic: () => activePly && loadMode('DYNAMIC', activePly.preMoveFen),
  };
}
