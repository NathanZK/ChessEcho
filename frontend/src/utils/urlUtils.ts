const ALLOWED_HOSTNAMES = new Set(['chess.com', 'www.chess.com']);
const ALLOWED_GAME_TYPES = new Set(['live', 'daily']);
const VALID_GAME_ID_REGEX = /^\w+$/;

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

    // Validate scheme
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      return null;
    }

    // Validate domain against explicit allowlist (case-insensitive)
    const hostname = url.hostname.toLowerCase();
    if (!ALLOWED_HOSTNAMES.has(hostname)) {
      return null;
    }

    // Check if it's a game URL (live or daily) with a valid game ID
    const pathParts = url.pathname.split('/').filter(Boolean);
    if (pathParts.length !== 3 || pathParts[0] !== 'game') {
      return null;
    }

    const gameType = pathParts[1];
    if (!ALLOWED_GAME_TYPES.has(gameType)) {
      return null;
    }

    const gameId = pathParts[2];
    if (!VALID_GAME_ID_REGEX.test(gameId)) {
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
