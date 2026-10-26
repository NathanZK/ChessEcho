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

/**
 * Constructs an exact-ply Chess.com game URL with ?move={plyNumber}.
 * Returns null if the identifier/URL is unverified, malformed, or unsupported.
 *
 * @param platformGameId - Stored platform game ID (absolute URL or raw numeric ID)
 * @param plyNumber - Exact ply number in the game
 * @param timeControl - Optional time control string to distinguish daily from live games
 */
export function buildExactPlyChessComUrl(
  platformGameId: string,
  plyNumber: number,
  timeControl?: string | null
): string | null {
  if (!platformGameId || typeof platformGameId !== 'string') {
    return null;
  }
  const trimmed = platformGameId.trim();
  if (!trimmed) {
    return null;
  }
  const ply = Math.max(0, plyNumber);

  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    try {
      const url = new URL(trimmed);
      const host = url.hostname.toLowerCase();
      if (host !== 'chess.com' && host !== 'www.chess.com') {
        return null;
      }
      if (
        !url.pathname.startsWith('/game/live/') &&
        !url.pathname.startsWith('/game/daily/') &&
        !url.pathname.startsWith('/game/computer/')
      ) {
        return null;
      }
      url.protocol = 'https:';
      url.searchParams.set('move', ply.toString());
      return url.toString();
    } catch {
      return null;
    }
  }

  if (/^\d+$/.test(trimmed)) {
    const isDaily = timeControl ? timeControl.toLowerCase().includes('daily') : false;
    const type = isDaily ? 'daily' : 'live';
    return `https://www.chess.com/game/${type}/${trimmed}?move=${ply}`;
  }

  return null;
}
