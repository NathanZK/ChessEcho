import { useEffect, useReducer, useRef, useCallback, useState } from 'react';
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

interface ContinuationRequest {
  fen: string;
  mode: ContinuationMode;
  ratingBand?: string;
  policy: CandidateSelectionPolicy;
}

interface ContinuationState {
  loading: boolean;
  error: boolean;
  response: ContinuationResponse | null;
  selectedCandidate: ContinuationCandidate | null;
  pendingRequest: ContinuationRequest | null;
}

const INITIAL_STATE: ContinuationState = {
  loading: false,
  error: false,
  response: null,
  selectedCandidate: null,
  pendingRequest: null,
};

type Action =
  | { type: 'REQUEST_STARTED' }
  | { type: 'REQUEST_ENQUEUED'; request: ContinuationRequest }
  | { type: 'REQUEST_SUCCEEDED'; response: ContinuationResponse | null; candidate: ContinuationCandidate | null }
  | { type: 'REQUEST_FAILED' }
  | { type: 'REQUEST_SETTLED' }
  | { type: 'CANDIDATE_SELECTED'; candidate: ContinuationCandidate | null }
  | { type: 'CLEARED' };

function reducer(state: ContinuationState, action: Action): ContinuationState {
  switch (action.type) {
    case 'REQUEST_STARTED':
      return { ...state, loading: true, error: false };
    case 'REQUEST_ENQUEUED':
      return { ...state, loading: true, error: false, pendingRequest: action.request };
    case 'REQUEST_SUCCEEDED':
      return {
        ...state,
        response: action.response,
        selectedCandidate: action.candidate,
        error: !action.response,
        loading: false,
        pendingRequest: null,
      };
    case 'REQUEST_FAILED':
      return {
        ...state,
        error: true,
        response: null,
        selectedCandidate: null,
        loading: false,
        pendingRequest: null,
      };
    case 'REQUEST_SETTLED':
      return state.pendingRequest ? { ...state, pendingRequest: null } : state;
    case 'CANDIDATE_SELECTED':
      return { ...state, selectedCandidate: action.candidate };
    case 'CLEARED':
      return state.response === null && state.selectedCandidate === null
        && !state.loading && !state.error && state.pendingRequest === null
        ? state
        : { ...INITIAL_STATE };
  }
}

export function usePuzzleContinuation(
  initialFen?: string,
  initialMode: ContinuationMode = 'ENGINE',
  policy: CandidateSelectionPolicy = defaultSelectionPolicy,
  initialRatingBand?: string
): UseContinuationResult {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);
  const lastRequestedFenRef = useRef<string | null>(null);

  // The single request owner: the only caller of continuationService.getContinuation
  const runRequest = useCallback(async (request: ContinuationRequest) => {
    lastRequestedFenRef.current = request.fen;

    try {
      const result = await continuationService.getContinuation(
        request.fen,
        request.mode,
        request.policy,
        request.ratingBand
      );

      // Stale request guard: ensure position hasn't changed while async fetch was in flight
      if (lastRequestedFenRef.current !== request.fen) {
        dispatch({ type: 'REQUEST_SETTLED' });
        return;
      }

      dispatch({ type: 'REQUEST_SUCCEEDED', response: result.response, candidate: result.candidate });
    } catch {
      if (lastRequestedFenRef.current === request.fen) {
        dispatch({ type: 'REQUEST_FAILED' });
      } else {
        dispatch({ type: 'REQUEST_SETTLED' });
      }
    }
  }, []);

  const fetchContinuation = useCallback(
    async (fen: string, mode: ContinuationMode = initialMode, ratingBand: string | undefined = initialRatingBand) => {
      if (!fen) return;
      dispatch({ type: 'REQUEST_STARTED' });
      await runRequest({ fen, mode, ratingBand, policy });
    },
    [initialMode, policy, initialRatingBand, runRequest]
  );

  const selectCandidate = useCallback(
    (customPolicy: CandidateSelectionPolicy = policy): ContinuationCandidate | null => {
      if (!state.response || state.response.candidates.length === 0) {
        dispatch({ type: 'CANDIDATE_SELECTED', candidate: null });
        return null;
      }
      const candidate = customPolicy(state.response.candidates);
      dispatch({ type: 'CANDIDATE_SELECTED', candidate });
      return candidate;
    },
    [state.response, policy]
  );

  // Declarative trigger: the state transition happens during the render that observes it
  const [trackedTrigger, setTrackedTrigger] = useState<{
    fen?: string;
    mode: ContinuationMode;
    ratingBand?: string;
    policy: CandidateSelectionPolicy;
  } | null>(null);

  if (
    !trackedTrigger ||
    trackedTrigger.fen !== initialFen ||
    trackedTrigger.mode !== initialMode ||
    trackedTrigger.ratingBand !== initialRatingBand ||
    trackedTrigger.policy !== policy
  ) {
    setTrackedTrigger({ fen: initialFen, mode: initialMode, ratingBand: initialRatingBand, policy });
    if (initialFen) {
      dispatch({
        type: 'REQUEST_ENQUEUED',
        request: { fen: initialFen, mode: initialMode, ratingBand: initialRatingBand, policy },
      });
    } else {
      dispatch({ type: 'CLEARED' });
    }
  }

  useEffect(() => {
    const request = state.pendingRequest;
    if (!request) return;
    void runRequest(request);
  }, [state.pendingRequest, runRequest]);

  const isFallback = state.response
    ? state.response.requestedMode === 'HUMAN' && state.response.effectiveProvider === 'ENGINE'
    : false;

  return {
    loading: state.loading,
    error: state.error,
    response: state.response,
    candidates: state.response?.candidates || [],
    selectedCandidate: state.selectedCandidate,
    effectiveProvider: state.response?.effectiveProvider || null,
    isFallback,
    fetchContinuation,
    selectCandidate,
  };
}
