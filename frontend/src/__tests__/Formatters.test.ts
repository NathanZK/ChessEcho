import { describe, it, expect } from 'vitest';
import {
  formatDecimal,
  formatPawnLoss,
  formatScoreRate,
  formatTimerMs,
} from '../utils/formatters';

describe('formatters', () => {
  describe('formatDecimal', () => {
    it('formats numbers with default precision of 2', () => {
      expect(formatDecimal(1.5)).toBe('1.50');
      expect(formatDecimal(0)).toBe('0.00');
      expect(formatDecimal(12.3456)).toBe('12.35');
    });

    it('formats numbers with custom precision', () => {
      expect(formatDecimal(1.5, 1)).toBe('1.5');
      expect(formatDecimal(1.5, 0)).toBe('2');
      expect(formatDecimal(0.876, 3)).toBe('0.876');
    });

    it('handles non-finite and falsy inputs by falling back to 0', () => {
      expect(formatDecimal(null, 2)).toBe('0.00');
      expect(formatDecimal(undefined, 2)).toBe('0.00');
      expect(formatDecimal(NaN, 2)).toBe('0.00');
      expect(formatDecimal(Infinity, 2)).toBe('0.00');
      expect(formatDecimal(-Infinity, 2)).toBe('0.00');
    });
  });

  describe('formatPawnLoss', () => {
    it('formats pawn loss with 2 decimal places by default', () => {
      expect(formatPawnLoss(1.25)).toBe('1.25');
      expect(formatPawnLoss(0)).toBe('0.00');
      expect(formatPawnLoss(0.2)).toBe('0.20');
      expect(formatPawnLoss(1.3)).toBe('1.30');
    });

    it('supports custom decimal places if provided', () => {
      expect(formatPawnLoss(1.256, 3)).toBe('1.256');
      expect(formatPawnLoss(1.256, 1)).toBe('1.3');
    });

    it('handles null, undefined, and non-finite numbers safely', () => {
      expect(formatPawnLoss(null)).toBe('0.00');
      expect(formatPawnLoss(undefined)).toBe('0.00');
      expect(formatPawnLoss(NaN)).toBe('0.00');
      expect(formatPawnLoss(Infinity)).toBe('0.00');
    });
  });

  describe('formatScoreRate', () => {
    it('converts decimal ratio to percentage with 1 decimal place', () => {
      expect(formatScoreRate(0.625)).toBe('62.5%');
      expect(formatScoreRate(0.38)).toBe('38.0%');
      expect(formatScoreRate(0.6667)).toBe('66.7%');
      expect(formatScoreRate(1)).toBe('100.0%');
      expect(formatScoreRate(0)).toBe('0.0%');
    });

    it('uses default fallback of 0.0% when value is null, undefined, or NaN', () => {
      expect(formatScoreRate(null)).toBe('0.0%');
      expect(formatScoreRate(undefined)).toBe('0.0%');
      expect(formatScoreRate(NaN)).toBe('0.0%');
      expect(formatScoreRate(Infinity)).toBe('0.0%');
    });

    it('uses custom fallback when provided', () => {
      expect(formatScoreRate(null, '—')).toBe('—');
      expect(formatScoreRate(undefined, 'N/A')).toBe('N/A');
      expect(formatScoreRate(NaN, '—')).toBe('—');
    });
  });

  describe('formatTimerMs', () => {
    it('formats milliseconds into mm:ss', () => {
      expect(formatTimerMs(65000)).toBe('1:05');
      expect(formatTimerMs(0)).toBe('0:00');
      expect(formatTimerMs(9000)).toBe('0:09');
      expect(formatTimerMs(600000)).toBe('10:00');
    });

    it('clamps negative milliseconds to 0:00', () => {
      expect(formatTimerMs(-500)).toBe('0:00');
      expect(formatTimerMs(-10000)).toBe('0:00');
    });

    it('handles fractional milliseconds and rounding', () => {
      expect(formatTimerMs(65400.6)).toBe('1:05');
      expect(formatTimerMs(999.4)).toBe('0:00');
      expect(formatTimerMs(999.6)).toBe('0:01');
    });
  });
});
