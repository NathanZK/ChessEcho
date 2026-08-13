/**
 * Converts a Chess.com game URL to a 6chess analysis URL.
 * Returns null if the URL is not a supported Chess.com game URL.
 * 
 * @param chessComUrl - The original Chess.com game URL
 * @param username - The Chess.com username to include in the query parameters
 * @returns The 6chess URL or null if conversion is not possible
 */
export function convertTo6chessUrl(chessComUrl: string, username: string): string | null {
  try {
    const url = new URL(chessComUrl);
    
    // Check if it's a Chess.com domain
    if (!url.hostname.includes('chess.com')) {
      return null;
    }
    
    // Check if it's a game URL (live or daily)
    const pathParts = url.pathname.split('/').filter(Boolean);
    if (pathParts.length < 2 || pathParts[0] !== 'game') {
      return null;
    }
    
    const gameType = pathParts[1]; // 'live' or 'daily'
    const gameId = pathParts[2]; // The game ID
    
    if (!gameId) {
      return null;
    }
    
    // Build the 6chess URL
    const sixChessUrl = new URL('https://www.6chess.com');
    sixChessUrl.pathname = `/game/${gameType}/${gameId}`;
    sixChessUrl.searchParams.set('username', username);
    sixChessUrl.searchParams.set('move', '0');
    
    return sixChessUrl.toString();
  } catch {
    // Invalid URL
    return null;
  }
}
