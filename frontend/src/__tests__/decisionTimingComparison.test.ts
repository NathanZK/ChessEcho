import { describe, it, expect } from 'vitest';
import { evaluateDecisionTimingComparison } from '../utils/decisionTimingComparison';
import type { LongDecisionContext } from '../types/puzzle';

describe('decisionTimingComparison utility', () => {
  const preMoveFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  const promoFen = '8/4P3/8/8/8/8/8/4K2k w - - 0 1';

  const baseContext: LongDecisionContext = {
    timeControl: 'RAPID',
    decisionTimeMs: 45000,
    movePlayed: 'a3',
    severity: 'SIGNIFICANT',
  };

  it('marks comparison as indeterminate when original move is unparseable or illegal on preMoveFen', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      context: { ...baseContext, movePlayed: 'invalid-or-illegal' },
      userMove: 'e4',
      isCorrect: true,
      attemptElapsedMs: 8000,
    });

    expect(res).not.toBeNull();
    expect(res?.isIndeterminate).toBe(true);
    expect(res?.title).toBe('Comparison Indeterminate');
    expect(res?.message).toContain('could not be verified on this position');
    expect(res?.durationComparison).toBe('Solved in 8.0s vs. original game thinking time of 0:45.');
  });

  it('recognizes optimal replication when original move was optimal and user played the same move', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      context: { ...baseContext, movePlayed: 'e4' },
      userMove: 'e4',
      isCorrect: true,
      attemptElapsedMs: 12500,
    });

    expect(res).not.toBeNull();
    expect(res?.isIndeterminate).toBe(false);
    expect(res?.originalQuality).toBe('OPTIMAL');
    expect(res?.userQuality).toBe('OPTIMAL');
    expect(res?.isSameMove).toBe(true);
    expect(res?.title).toContain('Repeated Strong Game Decision');
    expect(res?.message).toContain('repeating your strong game decision');
    expect(res?.durationComparison).toBe('Solved in 12.5s vs. original game thinking time of 0:45.');
  });

  it('recognizes alternative optimal move when original was optimal and user found a different optimal move', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'd4',
      acceptableMoves: [{ move: 'e4', evalLoss: 0.0 }],
      context: { ...baseContext, movePlayed: 'e4' },
      userMove: 'd4',
      isCorrect: true,
      attemptElapsedMs: 10000,
    });

    expect(res).not.toBeNull();
    expect(res?.isIndeterminate).toBe(false);
    expect(res?.originalQuality).toBe('OPTIMAL');
    expect(res?.userQuality).toBe('OPTIMAL');
    expect(res?.isSameMove).toBe(false);
    expect(res?.title).toContain('Found Alternative Optimal Move');
    expect(res?.message).toContain('You found an alternative optimal move (d4)! Your original game decision (e4) was also optimal.');
    expect(res?.message).not.toContain('improved upon');
  });

  it('recognizes decision improvement when original move was inferior and user found best move', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      context: { ...baseContext, movePlayed: 'a3' },
      userMove: 'e4',
      isCorrect: true,
      attemptElapsedMs: 14200,
    });

    expect(res).not.toBeNull();
    expect(res?.originalQuality).toBe('INFERIOR');
    expect(res?.userQuality).toBe('OPTIMAL');
    expect(res?.isSameMove).toBe(false);
    expect(res?.title).toContain('Decision Improved');
    expect(res?.message).toContain('You improved upon your game decision!');
  });

  it('recognizes better alternative when original move was inferior and user played acceptable move', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      acceptableMoves: [{ move: 'c4', evalLoss: 0.15 }],
      context: { ...baseContext, movePlayed: 'a3' },
      userMove: 'c4',
      isCorrect: true,
    });

    expect(res).not.toBeNull();
    expect(res?.originalQuality).toBe('INFERIOR');
    expect(res?.userQuality).toBe('ACCEPTABLE');
    expect(res?.title).toContain('Better Alternative Found');
  });

  it('recognizes repeated blunder when user repeated their inferior game move', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      context: { ...baseContext, movePlayed: 'a3' },
      userMove: 'a3',
      isCorrect: false,
    });

    expect(res).not.toBeNull();
    expect(res?.originalQuality).toBe('INFERIOR');
    expect(res?.userQuality).toBe('INFERIOR');
    expect(res?.isSameMove).toBe(true);
    expect(res?.title).toContain('Game Blunder Repeated');
    expect(res?.message).toContain('You repeated your game mistake (a3)');
  });

  it('recognizes different inaccurate move when both are inferior and moves differ', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen,
      targetMove: 'e4',
      context: { ...baseContext, movePlayed: 'a3' },
      userMove: 'h3',
      isCorrect: false,
    });

    expect(res).not.toBeNull();
    expect(res?.originalQuality).toBe('INFERIOR');
    expect(res?.userQuality).toBe('INFERIOR');
    expect(res?.isSameMove).toBe(false);
    expect(res?.title).toContain('Inaccurate Move');
  });

  it('handles promotion normalization when comparing moves', () => {
    const res = evaluateDecisionTimingComparison({
      preMoveFen: promoFen,
      targetMove: 'e8=Q',
      context: { ...baseContext, movePlayed: 'e8Q' },
      userMove: 'e7e8q',
      isCorrect: true,
    });

    expect(res).not.toBeNull();
    expect(res?.isSameMove).toBe(true);
    expect(res?.originalQuality).toBe('OPTIMAL');
    expect(res?.userQuality).toBe('OPTIMAL');
  });

  describe('Dual duration measurement scenarios (N2)', () => {
    // 1. Practice duration shorter than original duration
    it('1. formats comparison when practice duration is shorter than original game duration', () => {
      const res = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 12500,
      });

      expect(res?.durationComparison).toBe('Solved in 12.5s vs. original game thinking time of 0:45.');
      expect(res?.message).toContain('(0:45)');
    });

    // 2. Practice duration longer than original duration
    it('2. formats comparison when practice duration is longer than original game duration', () => {
      const res = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 30000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 75200,
      });

      expect(res?.durationComparison).toBe('Solved in 75.2s vs. original game thinking time of 0:30.');
      expect(res?.message).toContain('(0:30)');
    });

    // 3. Equal durations
    it('3. formats comparison when practice and original game durations are equal', () => {
      const res = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 45000,
      });

      expect(res?.durationComparison).toBe('Solved in 45.0s vs. original game thinking time of 0:45.');
    });

    // 4. Missing original duration
    it('4. omits duration comparison when original thinking duration is missing or zero without fabricating values', () => {
      const resMissing = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 0, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 12500,
      });

      expect(resMissing?.durationComparison).toBeUndefined();
      expect(resMissing?.message).not.toContain('(0:00)');
      expect(resMissing?.message).toBe(
        'You improved upon your game decision! In the game you played a3, but in practice you found the best move e4.'
      );
    });

    // 5. Missing or zero practice duration
    it('5. omits duration comparison when practice duration is missing or zero without substituting original duration', () => {
      const resZero = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 0,
      });

      expect(resZero?.durationComparison).toBeUndefined();

      const resNull = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: null,
      });

      expect(resNull?.durationComparison).toBeUndefined();
    });

    // 6. Evaluation failure followed by retry preserving the captured duration
    it('6. preserves captured duration upon evaluation retry and renders both durations', () => {
      // User captured 14300ms on first move submission
      const capturedAttemptDuration = 14300;

      // Upon retry, evaluation comparison receives that exact preserved duration
      const resAfterRetry = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'a3' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: capturedAttemptDuration,
      });

      expect(resAfterRetry?.durationComparison).toBe('Solved in 14.3s vs. original game thinking time of 0:45.');
    });

    // 7. A different move that is equally optimal
    it('7. recognizes alternative optimal move without claiming improvement while preserving duration comparison', () => {
      const res = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'd4',
        acceptableMoves: [{ move: 'e4', evalLoss: 0.0 }],
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'e4' },
        userMove: 'd4',
        isCorrect: true,
        attemptElapsedMs: 18000,
      });

      expect(res?.originalQuality).toBe('OPTIMAL');
      expect(res?.userQuality).toBe('OPTIMAL');
      expect(res?.isSameMove).toBe(false);
      expect(res?.title).toBe('Found Alternative Optimal Move ✨');
      expect(res?.message).toContain('You found an alternative optimal move (d4)! Your original game decision (e4) was also optimal.');
      expect(res?.message).not.toContain('improved upon');
      expect(res?.durationComparison).toBe('Solved in 18.0s vs. original game thinking time of 0:45.');
    });

    // 8. An original move that was already optimal
    it('8. recognizes replicated optimal move without claiming improvement while preserving duration comparison', () => {
      const res = evaluateDecisionTimingComparison({
        preMoveFen,
        targetMove: 'e4',
        context: { ...baseContext, decisionTimeMs: 45000, movePlayed: 'e4' },
        userMove: 'e4',
        isCorrect: true,
        attemptElapsedMs: 15000,
      });

      expect(res?.originalQuality).toBe('OPTIMAL');
      expect(res?.userQuality).toBe('OPTIMAL');
      expect(res?.isSameMove).toBe(true);
      expect(res?.title).toBe('Repeated Strong Game Decision 🎯');
      expect(res?.message).toBe('You found the best move, repeating your strong game decision!');
      expect(res?.durationComparison).toBe('Solved in 15.0s vs. original game thinking time of 0:45.');
    });
  });
});
