import { Puzzle } from '../mock/mockData';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

export interface ImportJobResponse {
  jobId: string;
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
}

export interface JobStatusResponse {
  jobId: string;
  status: 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  gamesImported: number;
  gamesSkipped: number;
  gamesProcessed?: number;
  gamesFilteredOut?: number;
  errorMessage?: string | null;
  analysisStatus?: 'NOT_STARTED' | 'ANALYZING' | 'COMPLETED' | 'FAILED';
}



export interface WeaknessResponse {
  positionId: string;
  fen: string;
  timesReached: number;
  mistakeCount: number;
  mistakeRate: number;
  averageLoss: number;
  priority: number;
  bestMove?: string;
  acceptableMoves: Array<{ move: string; evalLoss: number }>;
  movesPlayed: Array<{ move: string; timesPlayed: number; averageLoss: number }>;
  gameUrls: string[];
  evalCp?: number;
  lastSeenAt?: string;
}

export type ContinuationMode = 'ENGINE' | 'HUMAN';
export type ExplorationPlayMode = 'CHESSECHO' | 'BOTH_SIDES' | 'CHALLENGE';

export interface ContinuationCandidate {
  move: string;
  resultingFen: string;
  providerType: string;
  evalCp?: number | null;
  evalLoss?: number | null;
  timesPlayed?: number | null;
}

export interface ContinuationResponse {
  fen: string;
  requestedMode: ContinuationMode;
  effectiveProvider: string;
  candidates: ContinuationCandidate[];
}

export interface MoveEvaluationResponse {
  fen: string;
  move: string;
  bestMove: string;
  bestEvalCp: number | null;
  evalCp: number | null;
  evalLoss: number;
  maxEvalLoss: number;
  threshold: number;
  acceptable: boolean;
}

/**
 * Converts a side-to-move centipawn evaluation into absolute White-perspective centipawns.
 *
 * @param evalCp Centipawn evaluation relative to the player to move in [fen].
 * @param fen The chess position in FEN notation before the move was played.
 * @returns Absolute centipawns from White's perspective (+ = White advantage, - = Black advantage).
 */
export function toWhitePerspective(evalCp: number, fen: string): number {
  const isWhiteToMove = fen.split(' ')[1] === 'w';
  return isWhiteToMove ? evalCp : -evalCp;
}

export async function fetchPuzzleContinuation(
  fen: string,
  mode: ContinuationMode = 'ENGINE',
  ratingBand?: string
): Promise<ContinuationResponse | null> {
  if (!fen) return null;
  try {
    let url = `${API_BASE_URL}/puzzles/continuation?fen=${encodeURIComponent(fen)}&mode=${encodeURIComponent(mode)}`;
    if (ratingBand) {
      url += `&ratingBand=${encodeURIComponent(ratingBand)}`;
    }
    const response = await fetch(url);
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}

export async function evaluateMove(
  fen: string,
  move: string
): Promise<MoveEvaluationResponse | null> {
  if (!fen || !move) return null;
  try {
    const url = `${API_BASE_URL}/puzzles/evaluate-move?fen=${encodeURIComponent(fen)}&move=${encodeURIComponent(move)}`;
    const response = await fetch(url);
    if (!response.ok) return null;
    return await response.json();
  } catch {
    return null;
  }
}


/**
 * Fetches [url] and returns its body as a JSON array, throwing on any load
 * failure so callers can distinguish a genuine failure from an empty success.
 *
 * A load failure is a non-2xx response, a network error (the underlying fetch
 * rejects, which propagates unchanged), an unparseable body, or a 2xx body that
 * is not the expected JSON array. This mirrors the throwing convention of
 * `startImportJob`/`pollJobStatus`; a successful empty result still resolves `[]`.
 */
async function fetchJsonArray<T>(url: string, resource: string): Promise<T[]> {
  const response = await fetch(url);

  if (!response.ok) {
    throw new Error(`Failed to load ${resource}: ${response.status}`);
  }

  const data = await response.json();
  if (!Array.isArray(data)) {
    throw new Error(`Failed to load ${resource}: unexpected response body`);
  }

  return data as T[];
}

export async function fetchPuzzles(
  username: string,
  platform: string = 'CHESS_COM',
  playerColor: string = 'WHITE',
  minEvalLoss: number = 0.8,
  minMistakeCount: number = 3,
  limit: number = 5,
  page: number = 0
): Promise<Puzzle[]> {
  if (!username) return [];

  const formattedPlatform = platform.toUpperCase();
  const formattedColor = playerColor.toUpperCase();
  const url = `${API_BASE_URL}/puzzles?platform=${encodeURIComponent(formattedPlatform)}&username=${encodeURIComponent(username)}&playerColor=${encodeURIComponent(formattedColor)}&minEvalLoss=${minEvalLoss}&minMistakeCount=${minMistakeCount}&limit=${limit}&page=${page}`;

  const data = await fetchJsonArray<Puzzle>(url, 'puzzles');

  return data.map((item, idx) => {
    const fenTurn = item.fen ? item.fen.split(' ')[1] : undefined;
    const playerColor: 'WHITE' | 'BLACK' =
      fenTurn === 'b' ? 'BLACK' : fenTurn === 'w' ? 'WHITE' : (item.playerColor === 'BLACK' ? 'BLACK' : 'WHITE');

    return {
      ...item,
      playerColor,
      openingTitle: item.openingTitle || `Weakness Position #${idx + 1}`,
      evalCp: item.evalCp ?? 35,
      gameUrls: item.gameUrls || [],
    };
  });
}

export async function fetchWeaknesses(
  username: string,
  platform: string = 'CHESS_COM',
  playerColor: string = 'BOTH',
  minEvalLoss: number = 0.8,
  minMistakeCount: number = 3,
  page: number = 0,
  size: number = 20
): Promise<WeaknessResponse[]> {
  if (!username) return [];

  const formattedPlatform = platform.toUpperCase();
  const formattedColor = playerColor.toUpperCase();
  const url = `${API_BASE_URL}/positions/weaknesses?platform=${encodeURIComponent(formattedPlatform)}&username=${encodeURIComponent(username)}&playerColor=${encodeURIComponent(formattedColor)}&minEvalLoss=${minEvalLoss}&minMistakeCount=${minMistakeCount}&page=${page}&size=${size}`;

  return await fetchJsonArray<WeaknessResponse>(url, 'weaknesses');
}


export async function startImportJob(
  username: string,
  platform: string = 'CHESS_COM',
  timeControls: string[],
  playerColor: string,
  fromDate?: string,
  toDate?: string
): Promise<ImportJobResponse> {
  const response = await fetch(`${API_BASE_URL}/games/import`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      platform,
      timeControls: timeControls.map((tc) => tc.toUpperCase()),
      playerColor: playerColor.toUpperCase(),
      fromDate: fromDate?.trim() || undefined,
      toDate: toDate?.trim() || undefined,
    }),
  });

  if (!response.ok) {
    const errBody = await response.json().catch(() => ({}));
    let message = `Status ${response.status}`;
    if (Array.isArray(errBody.details) && errBody.details.length > 0) {
      message = errBody.details.join('\n');
    } else if (errBody.error) {
      message = errBody.error;
    }
    throw new Error(message);
  }

  return await response.json();
}

export async function pollJobStatus(jobId: string): Promise<JobStatusResponse> {
  const response = await fetch(`${API_BASE_URL}/jobs/${jobId}`);

  if (!response.ok) {
    throw new Error(`Failed to poll job status: ${response.status}`);
  }

  return await response.json();
}
