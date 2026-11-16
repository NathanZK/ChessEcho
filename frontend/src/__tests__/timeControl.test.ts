import { describe, it, expect } from 'vitest';
import {
  resolveGameTimeControl,
  filterGamesByTimeControl,
  getImportPresetForCategory,
  buildExternalGameUrl,
} from '../utils/timeControl';
import type { GameSummary } from '../services/api';

describe('timeControl utilities', () => {
  describe('resolveGameTimeControl', () => {
    it('resolves explicit category names case-insensitively', () => {
      expect(resolveGameTimeControl('BULLET')).toBe('BULLET');
      expect(resolveGameTimeControl('bullet')).toBe('BULLET');
      expect(resolveGameTimeControl('BLITZ')).toBe('BLITZ');
      expect(resolveGameTimeControl('blitz')).toBe('BLITZ');
      expect(resolveGameTimeControl('RAPID')).toBe('RAPID');
      expect(resolveGameTimeControl('rapid')).toBe('RAPID');
      expect(resolveGameTimeControl('CLASSICAL')).toBe('CLASSICAL');
      expect(resolveGameTimeControl('classical')).toBe('CLASSICAL');
      expect(resolveGameTimeControl('standard')).toBe('CLASSICAL');
    });

    it('resolves seconds and increment formats', () => {
      // < 180s total -> Bullet
      expect(resolveGameTimeControl('60')).toBe('BULLET');
      expect(resolveGameTimeControl('60+1')).toBe('BULLET'); // 60 + 40*1 = 100 < 180 -> BULLET
      expect(resolveGameTimeControl('120+1')).toBe('BULLET'); // 120 + 40 = 160 < 180 -> BULLET

      // 180s <= total < 600s -> Blitz
      expect(resolveGameTimeControl('180')).toBe('BLITZ');
      expect(resolveGameTimeControl('180+2')).toBe('BLITZ'); // 180 + 80 = 260 < 600 -> BLITZ
      expect(resolveGameTimeControl('300')).toBe('BLITZ'); // 5 mins
      expect(resolveGameTimeControl('300+2')).toBe('BLITZ'); // 300 + 80 = 380 < 600 -> BLITZ

      // 600s <= total < 3600s -> Rapid
      expect(resolveGameTimeControl('600')).toBe('RAPID'); // 10 mins
      expect(resolveGameTimeControl('900+10')).toBe('RAPID'); // 15 | 10
      expect(resolveGameTimeControl('1800')).toBe('RAPID'); // 30 mins

      // >= 3600s or daily -> Classical
      expect(resolveGameTimeControl('3600')).toBe('CLASSICAL'); // 60 mins
      expect(resolveGameTimeControl('daily')).toBe('CLASSICAL');
      expect(resolveGameTimeControl('1/86400')).toBe('CLASSICAL');
    });

    it('defaults unknown or null timeControl to RAPID gracefully', () => {
      expect(resolveGameTimeControl(null)).toBe('RAPID');
      expect(resolveGameTimeControl(undefined)).toBe('RAPID');
      expect(resolveGameTimeControl('')).toBe('RAPID');
      expect(resolveGameTimeControl('unknown_format')).toBe('RAPID');
    });
  });

  describe('filterGamesByTimeControl', () => {
    const mockGames: GameSummary[] = [
      {
        id: '1',
        platformGameId: '101',
        timeControl: 'BULLET',
        whiteUsername: 'W1',
        blackUsername: 'B1',
      },
      {
        id: '2',
        platformGameId: '102',
        timeControl: '180+2', // Blitz
        whiteUsername: 'W2',
        blackUsername: 'B2',
      },
      {
        id: '3',
        platformGameId: '103',
        timeControl: '600', // Rapid
        whiteUsername: 'W3',
        blackUsername: 'B3',
      },
      {
        id: '4',
        platformGameId: '104',
        timeControl: 'CLASSICAL',
        whiteUsername: 'W4',
        blackUsername: 'B4',
      },
    ];

    it('returns all games when category is ALL', () => {
      expect(filterGamesByTimeControl(mockGames, 'ALL')).toHaveLength(4);
    });

    it('filters accurately by category', () => {
      expect(filterGamesByTimeControl(mockGames, 'BULLET')).toEqual([mockGames[0]]);
      expect(filterGamesByTimeControl(mockGames, 'BLITZ')).toEqual([mockGames[1]]);
      expect(filterGamesByTimeControl(mockGames, 'RAPID')).toEqual([mockGames[2]]);
      expect(filterGamesByTimeControl(mockGames, 'CLASSICAL')).toEqual([mockGames[3]]);
    });
  });

  describe('getImportPresetForCategory', () => {
    it('returns all 4 time controls for ALL', () => {
      expect(getImportPresetForCategory('ALL')).toEqual(['BULLET', 'BLITZ', 'RAPID', 'CLASSICAL']);
    });

    it('returns single time control array for specific categories', () => {
      expect(getImportPresetForCategory('BULLET')).toEqual(['BULLET']);
      expect(getImportPresetForCategory('BLITZ')).toEqual(['BLITZ']);
      expect(getImportPresetForCategory('RAPID')).toEqual(['RAPID']);
      expect(getImportPresetForCategory('CLASSICAL')).toEqual(['CLASSICAL']);
    });
  });

  describe('buildExternalGameUrl', () => {
    it('preserves complete HTTP/HTTPS URLs', () => {
      expect(buildExternalGameUrl('https://www.chess.com/game/live/12345')).toBe(
        'https://www.chess.com/game/live/12345'
      );
    });

    it('converts numeric ID into chess.com live game URL', () => {
      expect(buildExternalGameUrl('172923974924')).toBe(
        'https://www.chess.com/game/live/172923974924'
      );
    });

    it('returns null for empty, null, or non-URL identifiers', () => {
      expect(buildExternalGameUrl(null)).toBeNull();
      expect(buildExternalGameUrl(undefined)).toBeNull();
      expect(buildExternalGameUrl('')).toBeNull();
      expect(buildExternalGameUrl('not-a-numeric-or-url-id')).toBeNull();
    });
  });
});
