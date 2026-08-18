import {
  fetchPuzzleContinuation,
  evaluateMove,
  ContinuationMode,
  ContinuationResponse,
  ContinuationCandidate,
  MoveEvaluationResponse,
} from './api';

export interface SelectionResult {
  candidate: ContinuationCandidate | null;
  response: ContinuationResponse | null;
  fromCache: boolean;
}

export type CandidateSelectionPolicy = (
  candidates: ContinuationCandidate[]
) => ContinuationCandidate | null;

/**
 * Default candidate selection policy: picks the first candidate in the list (highest rank).
 * This policy can easily be replaced without modifying the continuation fetching or caching layer.
 */
export const defaultSelectionPolicy: CandidateSelectionPolicy = (candidates) => {
  return candidates.length > 0 ? candidates[0] : null;
};

export class ContinuationCacheService {
  private cache: Map<string, ContinuationResponse> = new Map();

  private getCacheKey(fen: string, mode: ContinuationMode): string {
    return `${fen.trim()}:${mode}`;
  }

  /**
   * Retrieves continuation response from cache if available.
   */
  get(fen: string, mode: ContinuationMode): ContinuationResponse | null {
    const key = this.getCacheKey(fen, mode);
    return this.cache.get(key) || null;
  }

  /**
   * Stores continuation response in cache.
   */
  set(fen: string, mode: ContinuationMode, response: ContinuationResponse): void {
    const key = this.getCacheKey(fen, mode);
    this.cache.set(key, response);
  }

  /**
   * Clears the continuation cache.
   */
  clear(): void {
    this.cache.clear();
  }

  /**
   * Fetches continuation candidates for a given FEN and mode, utilizing cache if present,
   * and applies the provided candidate selection policy.
   */
  async getContinuation(
    fen: string,
    mode: ContinuationMode = 'ENGINE',
    selectionPolicy: CandidateSelectionPolicy = defaultSelectionPolicy
  ): Promise<SelectionResult> {
    if (!fen) {
      return { candidate: null, response: null, fromCache: false };
    }

    const cachedResponse = this.get(fen, mode);
    if (cachedResponse) {
      const selected = selectionPolicy(cachedResponse.candidates);
      return {
        candidate: selected,
        response: cachedResponse,
        fromCache: true,
      };
    }

    const response = await fetchPuzzleContinuation(fen, mode);
    if (response) {
      this.set(fen, mode, response);
      const selected = selectionPolicy(response.candidates);
      return {
        candidate: selected,
        response,
        fromCache: false,
      };
    }

    return { candidate: null, response: null, fromCache: false };
  }
}

export const continuationService = new ContinuationCacheService();

export class MoveEvaluationCacheService {
  private cache: Map<string, MoveEvaluationResponse> = new Map();

  private getCacheKey(fen: string, move: string): string {
    return `${fen.trim()}:${move.trim()}`;
  }

  get(fen: string, move: string): MoveEvaluationResponse | null {
    return this.cache.get(this.getCacheKey(fen, move)) || null;
  }

  set(fen: string, move: string, response: MoveEvaluationResponse): void {
    this.cache.set(this.getCacheKey(fen, move), response);
  }

  clear(): void {
    this.cache.clear();
  }

  async evaluateMove(fen: string, move: string): Promise<MoveEvaluationResponse | null> {
    const cached = this.get(fen, move);
    if (cached) return cached;

    const response = await evaluateMove(fen, move);
    if (response) {
      this.set(fen, move, response);
    }
    return response;
  }
}

export const moveEvaluationService = new MoveEvaluationCacheService();


