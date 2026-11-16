import type { GameSummary } from '../services/api';

export type GameTimeControlCategory = 'ALL' | 'BULLET' | 'BLITZ' | 'RAPID' | 'CLASSICAL';

export const GAME_TIME_CONTROL_CATEGORIES: GameTimeControlCategory[] = [
  'ALL',
  'BULLET',
  'BLITZ',
  'RAPID',
  'CLASSICAL',
];

export const TIME_CONTROL_CHIPS: { category: GameTimeControlCategory; label: string }[] = [
  { category: 'ALL', label: 'All' },
  { category: 'BULLET', label: 'Bullet' },
  { category: 'BLITZ', label: 'Blitz' },
  { category: 'RAPID', label: 'Rapid' },
  { category: 'CLASSICAL', label: 'Classical' },
];

const DIRECT_CATEGORIES: Record<string, Exclude<GameTimeControlCategory, 'ALL'>> = {
  BULLET: 'BULLET',
  BLITZ: 'BLITZ',
  RAPID: 'RAPID',
  CLASSICAL: 'CLASSICAL',
  STANDARD: 'CLASSICAL',
  DAILY: 'CLASSICAL',
};

function resolveSecondsTimeControl(
  normalized: string
): Exclude<GameTimeControlCategory, 'ALL'> | null {
  const parts = normalized.split('+');
  const baseSeconds = parseInt(parts[0], 10);
  if (isNaN(baseSeconds) || baseSeconds <= 0) return null;
  const incSeconds = parts.length > 1 ? parseInt(parts[1], 10) || 0 : 0;
  const totalEstimatedSeconds = baseSeconds + (40 * incSeconds);
  if (totalEstimatedSeconds < 180) return 'BULLET';
  if (totalEstimatedSeconds < 600) return 'BLITZ';
  if (totalEstimatedSeconds < 3600) return 'RAPID';
  return 'CLASSICAL';
}

/**
 * Resolves raw time-control string into canonical time category: BULLET, BLITZ, RAPID, or CLASSICAL.
 * Supports standard category names and seconds-based formats (e.g. 180+2).
 */
export function resolveGameTimeControl(
  timeControl?: string | null
): Exclude<GameTimeControlCategory, 'ALL'> {
  if (!timeControl || !timeControl.trim()) {
    return 'RAPID';
  }

  const normalized = timeControl.trim().toUpperCase();
  if (DIRECT_CATEGORIES[normalized]) {
    return DIRECT_CATEGORIES[normalized];
  }
  if (normalized.includes('/')) {
    return 'CLASSICAL';
  }

  return resolveSecondsTimeControl(normalized) || 'RAPID';
}

/**
 * Filters game summaries by time-control category.
 */
export function filterGamesByTimeControl(
  games: GameSummary[],
  category: GameTimeControlCategory
): GameSummary[] {
  if (category === 'ALL') {
    return games;
  }
  return games.filter((game) => resolveGameTimeControl(game.timeControl) === category);
}

/**
 * Derives context-aware import time-control presets from the active category.
 */
export function getImportPresetForCategory(
  category: GameTimeControlCategory
): string[] {
  if (category === 'ALL') {
    return ['BULLET', 'BLITZ', 'RAPID', 'CLASSICAL'];
  }
  return [category];
}

/**
 * Constructs a working external game URL from a platformGameId or URL string.
 */
export function buildExternalGameUrl(platformGameId?: string | null): string | null {
  if (!platformGameId || !platformGameId.trim()) return null;
  const trimmed = platformGameId.trim();
  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    return trimmed;
  }
  if (/^\d+$/.test(trimmed)) {
    return `https://www.chess.com/game/live/${trimmed}`;
  }
  return null;
}
