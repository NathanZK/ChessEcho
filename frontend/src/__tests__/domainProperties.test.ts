import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { Chess } from 'chess.js';
import { areMovesEqual, toCanonicalMove, type CanonicalMove } from '../utils/chessMove';
import { parseFenInput } from '../utils/positionImport';
import { computeDurationComparison, evaluateDecisionTimingComparison } from '../utils/decisionTimingComparison';
import { formatPawnLoss, formatScoreRate, formatTimerMs } from '../utils/formatters';

/**
 * Domain Property-Based Tests (Phase 3, Issue #714)
 *
 * This suite formalizes and verifies cross-cutting domain invariants across ChessProbe's
 * pure frontend utility functions using generated input spaces and property-based exploration.
 *
 * Evaluated & Excluded Functions (with rationale):
 * - usePuzzleAttemptCount: Excluded because it is an asynchronous React hook managing
 *   component lifecycle, refs, and HTTP I/O against the backend; stateful side-effects
 *   invalidate shrinking.
 * - StopwatchTimer / CountdownTimer: Excluded from property generators because they are
 *   mutable classes dependent on wall-clock performance.now(); covered by deterministic unit tests.
 * - Four-field SHA-256 position hashing, recurrence calculations, evaluation context attribution:
 *   Excluded because they are backend persistence and domain responsibilities (INV-02)
 *   implemented in Kotlin (GameParserService, ObjectiveWeaknessAnalyzer).
 */

const BENCHMARK_FENS = [
  'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  'r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 0 4',
  '8/4P3/8/8/8/8/8/4K2k w - - 0 1',
  'r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1',
  '8/8/8/3k4/8/8/3K4/8 w - - 0 50',
  'rnbqkb1r/pp2pppp/5n2/2ppP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 4',
];

const WHITESPACE_ARB = fc.constantFrom('', ' ', '  ', '\t', ' \t ');

describe('Move Normalization & Equality Properties (chessMove.ts)', () => {
  it('Format Invariance: toCanonicalMove yields identical canonical representation for SAN, UCI, and coordinate objects', () => {
    const fenArbitrary = fc.constantFrom(...BENCHMARK_FENS);

    fc.assert(
      fc.property(fenArbitrary, (fen) => {
        const chess = new Chess(fen);
        const legalMoves = chess.moves({ verbose: true });
        if (legalMoves.length === 0) return;

        for (const move of legalMoves) {
          const san = move.san;
          const uci = `${move.from}${move.to}${move.promotion ?? ''}`;
          const coordObj = {
            from: move.from,
            to: move.to,
            promotion: move.promotion,
          };

          const canonFromSan = toCanonicalMove(fen, san);
          const canonFromUci = toCanonicalMove(fen, uci);
          const canonFromObj = toCanonicalMove(fen, coordObj);

          expect(canonFromSan).not.toBeNull();
          expect(canonFromUci).not.toBeNull();
          expect(canonFromObj).not.toBeNull();

          // All three formats produce identical canonical coordinates
          expect(canonFromSan!.from).toBe(move.from);
          expect(canonFromSan!.to).toBe(move.to);
          expect(canonFromSan!.promotion).toBe(move.promotion ?? null);

          expect(areMovesEqual(canonFromSan, canonFromUci)).toBe(true);
          expect(canonFromSan).toEqual(canonFromUci);
          expect(canonFromSan).toEqual(canonFromObj);
        }
      }),
      { numRuns: 50 },
    );
  });

  it('Equivalence Relation: areMovesEqual satisfies reflexivity, symmetry, and null-safety', () => {
    const squareArb = fc.constantFrom(
      'a1', 'b1', 'c1', 'd1', 'e1', 'f1', 'g1', 'h1',
      'a8', 'b8', 'c8', 'd8', 'e8', 'f8', 'g8', 'h8',
    );
    const promoArb = fc.constantFrom(null, 'q', 'r', 'b', 'n');

    const canonicalMoveArb: fc.Arbitrary<CanonicalMove> = fc.record({
      from: squareArb,
      to: squareArb,
      promotion: promoArb,
      san: fc.string(),
    });

    fc.assert(
      fc.property(
        canonicalMoveArb,
        canonicalMoveArb,
        (moveA, moveB) => {
          // Reflexivity
          expect(areMovesEqual(moveA, moveA)).toBe(true);

          // Symmetry
          expect(areMovesEqual(moveA, moveB)).toBe(areMovesEqual(moveB, moveA));

          // Null safety
          expect(areMovesEqual(null, moveA)).toBe(false);
          expect(areMovesEqual(moveA, null)).toBe(false);
          expect(areMovesEqual(undefined, moveA)).toBe(false);
          expect(areMovesEqual(moveA, undefined)).toBe(false);
          expect(areMovesEqual(null, null)).toBe(false);

          // Identity condition: true if and only if from, to, and promotion match
          const coordinatesMatch =
            moveA.from === moveB.from &&
            moveA.to === moveB.to &&
            moveA.promotion === moveB.promotion;
          expect(areMovesEqual(moveA, moveB)).toBe(coordinatesMatch);
        },
      ),
      { numRuns: 100 },
    );
  });
});

describe('FEN Parsing & Active Color Partition Properties (positionImport.ts)', () => {
  it('Valid FEN Stability: Every valid orthodox position parses successfully and preserves input FEN', () => {
    const fenArbitrary = fc.constantFrom(...BENCHMARK_FENS);

    fc.assert(
      fc.property(
        fenArbitrary,
        WHITESPACE_ARB,
        (fen, padding) => {
          const paddedFen = `${padding}${fen}${padding}`;
          const result = parseFenInput(paddedFen);

          expect(result.ok).toBe(true);
          if (result.ok) {
            expect(result.fen).toBe(fen);
          }
        },
      ),
      { numRuns: 50 },
    );
  });

  it('Missing Active Color Partition: Bare 8-rank piece placement strings reliably flag missingActiveColor', () => {
    const placementArb = fc.constantFrom(...BENCHMARK_FENS.map((f) => f.split(' ')[0]));

    fc.assert(
      fc.property(
        placementArb,
        WHITESPACE_ARB,
        (placement, padding) => {
          const padded = `${padding}${placement}${padding}`;
          const result = parseFenInput(padded);

          expect(result.ok).toBe(false);
          if (!result.ok) {
            expect(result.missingActiveColor).toBe(true);
            expect(result.piecePlacement).toBe(placement);
          }
        },
      ),
      { numRuns: 50 },
    );
  });

  it('Malformed Input Robustness: Arbitrary strings never throw and cleanly return ok: boolean', () => {
    fc.assert(
      fc.property(fc.string(), (arbitraryString) => {
        let result: ReturnType<typeof parseFenInput> | undefined;
        expect(() => {
          result = parseFenInput(arbitraryString);
        }).not.toThrow();

        expect(result).toBeDefined();
        expect(typeof result!.ok).toBe('boolean');
        if (!result!.ok) {
          expect(typeof result!.error).toBe('string');
        }
      }),
      { numRuns: 200 },
    );
  });
});

describe('Decision Timing & Formatters Properties (decisionTimingComparison.ts, formatters.ts)', () => {
  it('formatTimerMs Invariant: Produces zero-padded m:ss for realistic durations, clamps negatives to 0:00, handles NaN/Infinity', () => {
    // Up to 1000 hours in milliseconds, plus negative, zero, and special float values
    const timerInputArb = fc.oneof(
      fc.integer({ min: -3_600_000, max: 3_600_000_000 }),
      fc.double({ min: -3_600_000, max: 3_600_000_000 }),
      fc.constant(NaN),
      fc.constant(Infinity),
      fc.constant(-Infinity),
    );

    fc.assert(
      fc.property(timerInputArb, (ms) => {
        const formatted = formatTimerMs(ms);

        // Must always match m:ss pattern
        expect(formatted).toMatch(/^\d+:[0-5]\d$/);

        if (!Number.isFinite(ms) || ms <= 0) {
          expect(formatted).toBe('0:00');
        } else {
          const clamped = Math.round(ms);
          const totalSec = Math.floor(clamped / 1000);
          const expectedMin = Math.floor(totalSec / 60);
          const expectedSec = totalSec % 60;
          expect(formatted).toBe(`${expectedMin}:${expectedSec.toString().padStart(2, '0')}`);
        }
      }),
      { numRuns: 200 },
    );
  });

  it('Duration Comparison Formatting: computeDurationComparison formats positive durations and undefined otherwise', () => {
    const maybeMsArb = fc.oneof(
      fc.integer({ min: -10_000, max: 7_200_000 }),
      fc.constant(null),
      fc.constant(undefined),
      fc.constant(NaN),
    );

    fc.assert(
      fc.property(
        maybeMsArb,
        maybeMsArb,
        (attemptElapsedMs, decisionTimeMs) => {
          const result = computeDurationComparison(attemptElapsedMs, decisionTimeMs);

          const isValidAttempt = typeof attemptElapsedMs === 'number' && Number.isFinite(attemptElapsedMs) && attemptElapsedMs > 0;
          const isValidDecision = typeof decisionTimeMs === 'number' && Number.isFinite(decisionTimeMs) && decisionTimeMs > 0;

          if (isValidAttempt && isValidDecision) {
            expect(typeof result).toBe('string');
            expect(result).toMatch(/^Solved in \d+\.\d+s vs\. original game thinking time of \d+:[0-5]\d\.$/);
          } else {
            expect(result).toBeUndefined();
          }
        },
      ),
      { numRuns: 150 },
    );
  });

  it('Decision Timing Comparison Evaluation: evaluateDecisionTimingComparison handles move and quality variations stably', () => {
    const fen = BENCHMARK_FENS[0];
    const userMoveArb = fc.constantFrom('e4', 'd4', 'Nf3', 'invalid', null, '');
    const isCorrectArb = fc.boolean();

    fc.assert(
      fc.property(
        userMoveArb,
        isCorrectArb,
        (userMove, isCorrect) => {
          const result = evaluateDecisionTimingComparison({
            preMoveFen: fen,
            targetMove: 'e4',
            acceptableMoves: [{ move: 'd4', evalLoss: 0.1 }],
            context: {
              movePlayed: 'e4',
              decisionTimeMs: 12000,
              timeControl: 'RAPID',
              severity: 'CRITICAL',
            },
            userMove,
            isCorrect,
            attemptElapsedMs: 8500,
          });

          if (!userMove) {
            expect(result).toBeNull();
          } else {
            expect(result).not.toBeNull();
            expect(typeof result!.title).toBe('string');
            expect(typeof result!.message).toBe('string');
            expect(typeof result!.isIndeterminate).toBe('boolean');
            expect(['OPTIMAL', 'ACCEPTABLE', 'INFERIOR']).toContain(result!.userQuality);
            expect(['OPTIMAL', 'ACCEPTABLE', 'INFERIOR']).toContain(result!.originalQuality);
          }
        },
      ),
      { numRuns: 50 },
    );
  });

  it('Numeric Formatting Safety: formatPawnLoss and formatScoreRate handle extreme/non-finite inputs safely', () => {
    fc.assert(
      fc.property(
        fc.oneof(fc.double({ min: -100, max: 100 }), fc.constant(null), fc.constant(undefined), fc.constant(NaN), fc.constant(Infinity)),
        (val) => {
          const pawnLoss = formatPawnLoss(val);
          expect(typeof pawnLoss).toBe('string');
          expect(pawnLoss).toMatch(/^-?\d+\.\d{2}$/);

          const scoreRate = formatScoreRate(val, 'N/A');
          expect(typeof scoreRate).toBe('string');
          if (typeof val === 'number' && Number.isFinite(val)) {
            expect(scoreRate).toMatch(/^-?\d+(\.\d)?%$/);
          } else {
            expect(scoreRate).toBe('N/A');
          }
        },
      ),
      { numRuns: 100 },
    );
  });
});

describe('Shrinking Demonstration & Minimal Counterexample Regression', () => {
  /**
   * Shrinking Demonstration (PR Verification Evidence):
   * During property-based testing of formatTimerMs across the full numeric space:
   *   fc.assert(fc.property(fc.oneof(fc.integer(), fc.double(), fc.constant(NaN)), ms => {
   *     expect(formatTimerMs(ms)).toMatch(/^\d+:[0-5]\d$/);
   *   }))
   * fast-check caught a real boundary bug and shrank to the minimal counterexample:
   *   Counterexample: [Number.NaN] (Shrunk 0 times - minimal counterexample found immediately)
   *   Pre-fix behavior: formatTimerMs(NaN) evaluated Math.max(0, Math.round(NaN)) -> NaN -> produced "NaN:NaN".
   *   Corrected behavior: formatTimerMs checks Number.isFinite(ms) and normalizes non-finite inputs to 0 ("0:00").
   *
   * Below is the permanent passing regression test asserting this boundary behavior.
   */
  it('Regression: Non-finite timer inputs (NaN, Infinity, -Infinity) safely clamp to 0:00 without NaN drift', () => {
    expect(formatTimerMs(NaN)).toBe('0:00');
    expect(formatTimerMs(Infinity)).toBe('0:00');
    expect(formatTimerMs(-Infinity)).toBe('0:00');
    expect(formatTimerMs(-500)).toBe('0:00');
    expect(formatTimerMs(0)).toBe('0:00');
    expect(formatTimerMs(999)).toBe('0:00'); // floor division: 999ms is 0 seconds
    expect(formatTimerMs(1000)).toBe('0:01');
    expect(formatTimerMs(60_000)).toBe('1:00');
    expect(formatTimerMs(61_000)).toBe('1:01');
  });
});
