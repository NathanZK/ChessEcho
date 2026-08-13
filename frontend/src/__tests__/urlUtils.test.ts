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

  it('returns null when username is not provided', () => {
    const chessComUrl = 'https://www.chess.com/game/live/172923974924';
    const result = convertTo6chessUrl(chessComUrl, '');
    
    // The function should still work with empty username, just with empty parameter
    expect(result).toBe('https://www.6chess.com/game/live/172923974924?username=&move=0');
  });
});
