import { MOCK_PUZZLES, Puzzle } from '../mock/mockData';

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

export async function fetchPuzzles(
  username: string = 'NathanZele',
  platform: string = 'CHESS_COM',
  playerColor: string = 'black',
  limit: number = 5,
  page: number = 0
): Promise<Puzzle[]> {
  try {
    const url = `${API_BASE_URL}/puzzles?platform=${platform}&username=${username}&playerColor=${playerColor}&limit=${limit}&page=${page}`;
    const response = await fetch(url, { signal: AbortSignal.timeout(3000) });
    
    if (!response.ok) {
      throw new Error(`API error: ${response.status}`);
    }

    const data: Puzzle[] = await response.json();
    
    // Add default opening titles for display if missing
    return data.map((item, idx) => ({
      ...item,
      openingTitle: item.openingTitle || `Opening Position #${idx + 1}`,
      evalCp: item.evalCp ?? 35,
    }));
  } catch (error) {
    console.warn('Backend API offline or unreachable. Falling back to mock dataset.', error);
    return MOCK_PUZZLES;
  }
}

export async function startImportJob(
  username: string,
  platform: string = 'CHESS_COM',
  timeControls: string[],
  playerColor: string,
  fromDate?: string,
  toDate?: string
): Promise<{ jobId: string }> {
  try {
    const response = await fetch(`${API_BASE_URL}/games/import`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username,
        platform,
        timeControls,
        playerColor,
        fromDate,
        toDate,
      }),
    });

    if (!response.ok) {
      throw new Error(`Import failed with status ${response.status}`);
    }

    return await response.json();
  } catch (error) {
    console.warn('Backend API offline. Using simulated job runner.', error);
    return { jobId: 'job_' + Math.random().toString(36).substring(2, 9) };
  }
}
