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
  errorMessage?: string | null;
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
  try {
    const formattedPlatform = platform.toUpperCase();
    const formattedColor = playerColor.toUpperCase();
    const url = `${API_BASE_URL}/puzzles?platform=${encodeURIComponent(formattedPlatform)}&username=${encodeURIComponent(username)}&playerColor=${encodeURIComponent(formattedColor)}&minEvalLoss=${minEvalLoss}&minMistakeCount=${minMistakeCount}&limit=${limit}&page=${page}`;
    const response = await fetch(url);

    if (!response.ok) {
      return [];
    }

    const data: Puzzle[] = await response.json();
    if (!Array.isArray(data)) return [];

    return data.map((item, idx) => ({
      ...item,
      openingTitle: item.openingTitle || `Weakness Position #${idx + 1}`,
      evalCp: item.evalCp ?? 35,
    }));
  } catch {
    return [];
  }
}

export async function fetchWeaknesses(
  username: string,
  platform: string = 'CHESS_COM',
  playerColor: string = 'BOTH',
  minEvalLoss: number = 0.8,
  minMistakeCount: number = 3
): Promise<WeaknessResponse[]> {
  if (!username) return [];
  try {
    const formattedPlatform = platform.toUpperCase();
    const formattedColor = playerColor.toUpperCase();
    const url = `${API_BASE_URL}/positions/weaknesses?platform=${encodeURIComponent(formattedPlatform)}&username=${encodeURIComponent(username)}&playerColor=${encodeURIComponent(formattedColor)}&minEvalLoss=${minEvalLoss}&minMistakeCount=${minMistakeCount}`;
    const response = await fetch(url);

    if (!response.ok) {
      return [];
    }

    const data = await response.json();
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
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

