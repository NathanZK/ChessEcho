import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { continuationService, defaultSelectionPolicy, ContinuationCacheService, moveEvaluationService } from '../services/continuationService';
import { usePuzzleContinuation } from '../utils/usePuzzleContinuation';
import * as api from '../services/api';

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api');
  return {
    ...actual,
    fetchPuzzleContinuation: vi.fn(),
    evaluateMove: vi.fn(),
  };
});

describe('Frontend Puzzle Continuation Architecture & Candidate Selection', () => {
  const fen1 = 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3';
  const fen2 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1';

  beforeEach(() => {
    continuationService.clear();
    moveEvaluationService.clear();
    vi.clearAllMocks();
  });

  describe('continuationService & Candidate Caching', () => {
    it('consumes candidate collection and applies candidate selection on the frontend', async () => {
      const mockResponse: api.ContinuationResponse = {
        fen: fen1,
        requestedMode: 'ENGINE',
        effectiveProvider: 'ENGINE',
        candidates: [
          { move: 'Bb5', resultingFen: 'fen_bb5', providerType: 'ENGINE' },
          { move: 'Bc4', resultingFen: 'fen_bc4', providerType: 'ENGINE' },
        ],
      };

      vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(mockResponse);

      const customPolicy = (candidates: api.ContinuationCandidate[]) => candidates[1] || null;

      const result = await continuationService.getContinuation(fen1, 'ENGINE', customPolicy);

      expect(result.candidate?.move).toBe('Bc4');
    });
  });

  describe('moveEvaluationService & Move Evaluation Caching', () => {
    it('caches and reuses move evaluation results for same FEN + move', async () => {
      const mockEval: api.MoveEvaluationResponse = {
        fen: fen1,
        move: 'Bc4',
        bestMove: 'Bb5',
        bestEvalCp: 80,
        evalCp: 65,
        evalLoss: 0.15,
        maxEvalLoss: 0.80,
        threshold: 0.80,
        acceptable: true,
      };

      vi.mocked(api.evaluateMove).mockResolvedValueOnce(mockEval);

      // First call (cache miss)
      const res1 = await moveEvaluationService.evaluateMove(fen1, 'Bc4');
      expect(res1).toEqual(mockEval);
      expect(api.evaluateMove).toHaveBeenCalledTimes(1);

      // Second call (cache hit)
      const res2 = await moveEvaluationService.evaluateMove(fen1, 'Bc4');
      expect(res2).toEqual(mockEval);
      expect(api.evaluateMove).toHaveBeenCalledTimes(1);
    });
  });

  describe('usePuzzleContinuation Hook', () => {
    it('returns continuation candidate and provider state when FEN is provided', async () => {
      const mockResponse: api.ContinuationResponse = {
        fen: fen1,
        requestedMode: 'ENGINE',
        effectiveProvider: 'ENGINE',
        candidates: [{ move: 'Bb5', resultingFen: 'fen_bb5', providerType: 'ENGINE' }],
      };

      vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(mockResponse);

      const { result } = renderHook(() => usePuzzleContinuation(fen1, 'ENGINE'));

      await act(async () => {});

      expect(result.current.loading).toBe(false);
      expect(result.current.effectiveProvider).toBe('ENGINE');
      expect(result.current.isFallback).toBe(false);
      expect(result.current.selectedCandidate?.move).toBe('Bb5');
    });

    it('indicates isFallback when requestedMode HUMAN falls back to effectiveProvider ENGINE', async () => {
      const mockResponse: api.ContinuationResponse = {
        fen: fen1,
        requestedMode: 'HUMAN',
        effectiveProvider: 'ENGINE',
        candidates: [{ move: 'Bb5', resultingFen: 'fen_bb5', providerType: 'ENGINE' }],
      };

      vi.mocked(api.fetchPuzzleContinuation).mockResolvedValueOnce(mockResponse);

      const { result } = renderHook(() => usePuzzleContinuation(fen1, 'HUMAN'));

      await act(async () => {});

      expect(result.current.loading).toBe(false);
      expect(result.current.effectiveProvider).toBe('ENGINE');
      expect(result.current.isFallback).toBe(true);
      expect(result.current.selectedCandidate?.move).toBe('Bb5');
    });

    it('prevents stale responses when FEN position changes in flight', async () => {
      let resolveFirstFetch: (val: api.ContinuationResponse | null) => void;
      const firstPromise = new Promise<api.ContinuationResponse | null>((resolve) => {
        resolveFirstFetch = resolve;
      });

      const secondResponse: api.ContinuationResponse = {
        fen: fen2,
        requestedMode: 'ENGINE',
        effectiveProvider: 'ENGINE',
        candidates: [{ move: 'e5', resultingFen: 'fen_e5', providerType: 'ENGINE' }],
      };

      vi.mocked(api.fetchPuzzleContinuation)
        .mockImplementationOnce(() => firstPromise)
        .mockResolvedValueOnce(secondResponse);

      const { result } = renderHook(() => usePuzzleContinuation());

      // Trigger first fetch
      act(() => {
        result.current.fetchContinuation(fen1, 'ENGINE');
      });

      // Trigger second fetch before first completes
      act(() => {
        result.current.fetchContinuation(fen2, 'ENGINE');
      });

      await act(async () => {});

      // Now resolve the first fetch (stale request)
      await act(async () => {
        resolveFirstFetch!({
          fen: fen1,
          requestedMode: 'ENGINE',
          effectiveProvider: 'ENGINE',
          candidates: [{ move: 'Bb5', resultingFen: 'fen_bb5', providerType: 'ENGINE' }],
        });
      });

      // State should reflect fen2, not stale fen1
      expect(result.current.selectedCandidate?.move).toBe('e5');
      expect(result.current.response?.fen).toBe(fen2);
    });
  });
});
