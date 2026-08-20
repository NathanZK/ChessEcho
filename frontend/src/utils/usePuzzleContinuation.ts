import { useState, useEffect, useRef, useCallback } from 'react';
import { ContinuationMode, ContinuationResponse, ContinuationCandidate } from '../services/api';
import { continuationService, CandidateSelectionPolicy, defaultSelectionPolicy } from '../services/continuationService';

export interface UseContinuationResult {
  loading: boolean;
  error: boolean;
  response: ContinuationResponse | null;
  candidates: ContinuationCandidate[];
  selectedCandidate: ContinuationCandidate | null;
  effectiveProvider: string | null;
  isFallback: boolean;
  fetchContinuation: (fen: string, mode?: ContinuationMode, ratingBand?: string) => Promise<void>;
  selectCandidate: (policy?: CandidateSelectionPolicy) => ContinuationCandidate | null;
}

export function usePuzzleContinuation(
  initialFen?: string,
  initialMode: ContinuationMode = 'ENGINE',
  policy: CandidateSelectionPolicy = defaultSelectionPolicy,
  initialRatingBand?: string
): UseContinuationResult {
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<boolean>(false);
  const [response, setResponse] = useState<ContinuationResponse | null>(null);
  const [selectedCandidate, setSelectedCandidate] = useState<ContinuationCandidate | null>(null);
  const lastRequestedFenRef = useRef<string | null>(null);

  const fetchContinuation = useCallback(
    async (fen: string, mode: ContinuationMode = initialMode, ratingBand: string | undefined = initialRatingBand) => {
      if (!fen) return;
      lastRequestedFenRef.current = fen;
      setLoading(true);
      setError(false);

      try {
        const result = await continuationService.getContinuation(fen, mode, policy, ratingBand);

        // Stale request guard: ensure position hasn't changed while async fetch was in flight
        if (lastRequestedFenRef.current !== fen) {
          return;
        }

        setResponse(result.response);
        setSelectedCandidate(result.candidate);
        if (!result.response) {
          setError(true);
        }
      } catch {
        if (lastRequestedFenRef.current === fen) {
          setError(true);
          setResponse(null);
          setSelectedCandidate(null);
        }
      } finally {
        if (lastRequestedFenRef.current === fen) {
          setLoading(false);
        }
      }
    },
    [initialMode, policy, initialRatingBand]
  );

  const selectCandidate = useCallback(
    (customPolicy: CandidateSelectionPolicy = policy): ContinuationCandidate | null => {
      if (!response || response.candidates.length === 0) {
        setSelectedCandidate(null);
        return null;
      }
      const candidate = customPolicy(response.candidates);
      setSelectedCandidate(candidate);
      return candidate;
    },
    [response, policy]
  );

  useEffect(() => {
    if (initialFen) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void fetchContinuation(initialFen, initialMode, initialRatingBand);
    } else {
      setResponse(null);
      setSelectedCandidate(null);
      setLoading(false);
      setError(false);
    }
  }, [initialFen, initialMode, initialRatingBand, fetchContinuation]);

  const isFallback = response
    ? response.requestedMode === 'HUMAN' && response.effectiveProvider === 'ENGINE'
    : false;

  return {
    loading,
    error,
    response,
    candidates: response?.candidates || [],
    selectedCandidate,
    effectiveProvider: response?.effectiveProvider || null,
    isFallback,
    fetchContinuation,
    selectCandidate,
  };
}
