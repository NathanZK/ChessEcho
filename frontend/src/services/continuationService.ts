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

export const createDeterministicSelectionPolicy = (): CandidateSelectionPolicy => {
  return (candidates) => candidates[0] || null;
};

/**
 * Creates a selection policy that uses a provided random function.
 * - ENGINE mode: uniformly samples from engine candidates
 * - HUMAN mode: stochastic, weighted by timesPlayed
 */
export const createStochasticSelectionPolicy = (
  randomFn: () => number = Math.random
): CandidateSelectionPolicy => {
  return (candidates) => {
    if (candidates.length === 0) return null;

    // Mixed provider responses are invalid for stochastic selection, so use a safe fallback.
    const isHuman = candidates.every(c => c.providerType === 'HUMAN');
    const isEngine = candidates.every(c => c.providerType === 'ENGINE');
    if (!isHuman && !isEngine) {
      return candidates[0];
    }

    if (isEngine) {
      // Engine providers are expected to return only candidates within the
      // backend's strong-candidate threshold; keep malformed responses safe.
      if (candidates.some(c => c.evalLoss != null && c.evalLoss > 0.5)) {
        return candidates[0];
      }
      const index = Math.floor(randomFn() * candidates.length);
      return candidates[index] || candidates[0];
    }

    // Weighted random selection for HUMAN
    let totalWeight = 0;
    for (const c of candidates) {
      if (c.timesPlayed && c.timesPlayed > 0) {
        totalWeight += c.timesPlayed;
      }
    }

    if (totalWeight <= 0) {
      return candidates[0]; // Safe fallback if no positive weights
    }

    let random = randomFn() * totalWeight;
    for (const c of candidates) {
      if (c.timesPlayed && c.timesPlayed > 0) {
        random -= c.timesPlayed;
        if (random <= 0) return c;
      }
    }

    return candidates[0]; // Fallback
  };
};

/**
 * Default candidate selection policy:
 * ENGINE -> picks the first candidate.
 * HUMAN -> picks stochastically weighted by timesPlayed.
 */
export const defaultSelectionPolicy: CandidateSelectionPolicy = createDeterministicSelectionPolicy();

export class ContinuationCacheService {
  private cache: Map<string, ContinuationResponse> = new Map();

  private getCacheKey(fen: string, mode: ContinuationMode, ratingBand?: string): string {
    return `${fen.trim()}:${mode}${ratingBand ? `:${ratingBand}` : ''}`;
  }

  /**
   * Retrieves continuation response from cache if available.
   */
  get(fen: string, mode: ContinuationMode, ratingBand?: string): ContinuationResponse | null {
    const key = this.getCacheKey(fen, mode, ratingBand);
    return this.cache.get(key) || null;
  }

  /**
   * Stores continuation response in cache.
   */
  set(fen: string, mode: ContinuationMode, response: ContinuationResponse, ratingBand?: string): void {
    const key = this.getCacheKey(fen, mode, ratingBand);
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
    selectionPolicy: CandidateSelectionPolicy = defaultSelectionPolicy,
    ratingBand?: string
  ): Promise<SelectionResult> {
    if (!fen) {
      return { candidate: null, response: null, fromCache: false };
    }

    const cachedResponse = this.get(fen, mode, ratingBand);
    if (cachedResponse) {
      const selected = selectionPolicy(cachedResponse.candidates);
      return {
        candidate: selected,
        response: cachedResponse,
        fromCache: true,
      };
    }

    const response = await fetchPuzzleContinuation(fen, mode, ratingBand);
    if (response) {
      this.set(fen, mode, response, ratingBand);
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
