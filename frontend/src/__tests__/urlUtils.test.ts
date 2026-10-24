import { describe, it, expect } from "vitest";
import { convertTo6chessUrl } from '../utils/urlUtils';

describe('convertTo6chessUrl', () => {
  it('converts a normal Chess.com live game URL correctly', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const username = 'blackbeetle17';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=blackbeetle17&move=0');
  });

  it('converts a Chess.com daily game URL correctly', () => {
    const chessComUrl = 'https://www.chess.com/game/daily/123456789';
    const username = 'testuser';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBe('https://www.6chess.com/game/daily/123456789?username=testuser&move=0');
  });

  it('properly URL-encodes the username', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const username = 'user with spaces';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    // URLSearchParams uses + for spaces, which is valid URL encoding
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=user+with+spaces&move=0');
  });

  it('properly URL-encodes special characters in username', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const username = 'user@example.com';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=user%40example.com&move=0');
  });

  it('returns null for non-Chess.com URLs', () => {
    const otherUrl = 'https://www.lichess.org/game/abc123';
    const username = 'testuser';
    const result = convertTo6chessUrl(otherUrl, username);
    
    expect(result).toBeNull();
  });

  it('returns null for Chess.com URLs that are not game URLs', () => {
    const chessComUrl = 'https://www.chess.com/member/testuser';
    const username = 'testuser';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBeNull();
  });

  it('returns null for malformed URLs', () => {
    const malformedUrl = 'not-a-valid-url';
    const username = 'testuser';
    const result = convertTo6chessUrl(malformedUrl, username);
    
    expect(result).toBeNull();
  });

  it('returns null for Chess.com game URLs without game ID', () => {
    const chessComUrl = 'https://www.chess.com/game/live';
    const username = 'testuser';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBeNull();
  });

  it('includes move=0 parameter', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const username = 'testuser';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toContain('move=0');
  });

  it('handles chess.com subdomain', () => {
    const chessComUrl = 'https://chess.com/game/live/172923974924';
    const username = 'testuser';
    const result = convertTo6chessUrl(chessComUrl, username);
    
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=testuser&move=0');
  });

  it('supports empty username in query parameters', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const result = convertTo6chessUrl(chessComUrl, '');
    
    // The function should still work with empty username, just with empty parameter
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=&move=0');
  });

  describe('security and strict validation', () => {
    it('returns null for prefix hostname lookalikes', () => {
      expect(convertTo6chessUrl('https://evil-chess.com/game/live/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://notchess.com/game/live/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://attackerchess.com/game/daily/12345', 'user')).toBeNull();
    });

    it('returns null for suffix and attacker subdomain lookalikes', () => {
      expect(convertTo6chessUrl('https://chess.com.attacker.com/game/live/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://chess.com.evil.org/game/daily/12345', 'user')).toBeNull();
    });

    it('returns null for non-allowlisted subdomains of chess.com', () => {
      expect(convertTo6chessUrl('https://blog.chess.com/game/live/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://support.chess.com/game/live/12345', 'user')).toBeNull();
    });

    it('handles case-insensitive valid hostnames', () => {
      expect(convertTo6chessUrl('https://WWW.CHESS.COM/game/live/12345', 'user')).toBe(
        'https://www.6chess.com/game/live/12345?username=user&move=0'
      );
      expect(convertTo6chessUrl('https://Chess.Com/game/daily/12345', 'user')).toBe(
        'https://www.6chess.com/game/daily/12345?username=user&move=0'
      );
    });

    it('returns null for unsupported URL schemes', () => {
      expect(convertTo6chessUrl('ftp://www.chess.com/game/live/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('javascript://www.chess.com/game/live/12345', 'user')).toBeNull();
    });

    it('returns null for userinfo spoofing attempts', () => {
      expect(convertTo6chessUrl('https://chess.com@evil.com/game/live/12345', 'user')).toBeNull();
    });

    it('returns null for unsupported game types', () => {
      expect(convertTo6chessUrl('https://www.chess.com/game/puzzles/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://www.chess.com/game/tactics/12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://www.chess.com/game/other/12345', 'user')).toBeNull();
    });

    it('returns null for invalid or path-traversal game IDs', () => {
      expect(convertTo6chessUrl('https://www.chess.com/game/live/../12345', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://www.chess.com/game/live/123/extra/path', 'user')).toBeNull();
      expect(convertTo6chessUrl('https://www.chess.com/game/live/invalid@id', 'user')).toBeNull();
    });
  });
});
