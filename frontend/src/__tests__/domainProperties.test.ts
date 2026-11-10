import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { Chess } from 'chess.js';
import { areMovesEqual, toCanonicalMove, type CanonicalMove } from '../utils/chessMove';
import { parseFenInput, parsePgnMainLine } from '../utils/positionImport';
import {
  applyPlayerMove,
  applyChessProbeMove,
  createBlinfoldGameState,
  rebuildPositionFromHistory,
  type BlindfoldGameState,
} from '../utils/blindfoldGameState';
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

const BENCHMARK_PGNS = [
  '1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7',
  '1. d4 d5 2. c4 e6 3. Nc3 Nf6 4. Bg5 Be7 5. e3 O-O',
  '1. c4 e5 2. Nc3 Nf6 3. g3 d5 4. cxd5 Nxd5 5. Bg2 Nb6',
  '1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6',
  '1. d4 Nf6 2. c4 g6 3. Nc3 Bg7 4. e4 d6 5. Nf3 O-O',
  '[SetUp "1"]\n[FEN "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"]\n\n2. Nf3 Nc6',
  '[SetUp "1"]\n[FEN "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"]\n\n1... e5 2. Nf3',
];

describe('PGN Parsing & Move Attribution Properties (positionImport.ts)', () => {
  it('Mainline Invariants: Continuity, transition consistency, and label parity hold for orthodox and setup games', () => {
    const pgnArb = fc.constantFrom(...BENCHMARK_PGNS);
    const headerArb = fc.constantFrom(
      '',
      '[Event "Casual Game"]\n[Site "ChessEcho"]\n\n',
      '[Event "FIDE Grand Prix"]\n[White "Player1"]\n[Black "Player2"]\n[Result "*"]\n\n',
    );

    fc.assert(
      fc.property(
        pgnArb,
        headerArb,
        WHITESPACE_ARB,
        (pgnBody, headers, padding) => {
          const fullPgn = `${padding}${headers}${pgnBody}${padding}`;
          const result = parsePgnMainLine(fullPgn);

          expect(result.ok).toBe(true);
          if (!result.ok) return;

          expect(typeof result.startFen).toBe('string');
          expect(result.plies.length).toBeGreaterThan(1);

          // Ply 0 is always the Start ply
          const startPly = result.plies[0];
          expect(startPly.ply).toBe(0);
          expect(startPly.san).toBe('');
          expect(startPly.sanHistory).toEqual([]);
          expect(startPly.label).toBe('Start');
          expect(startPly.fen).toBe(result.startFen);

          const initialColor = startPly.color;

          for (let i = 1; i < result.plies.length; i++) {
            const ply = result.plies[i];
            const prevPly = result.plies[i - 1];

            // 1. Move History Continuity
            expect(ply.ply).toBe(i);
            expect(ply.sanHistory.length).toBe(i);
            expect(ply.sanHistory[i - 1]).toBe(ply.san);
            expect(ply.sanHistory.slice(0, i - 1)).toEqual(prevPly.sanHistory);

            // 2. Legal Transition Consistency
            const chess = new Chess(prevPly.fen);
            const moveResult = chess.move(ply.san);
            expect(moveResult).not.toBeNull();
            expect(chess.fen()).toBe(ply.fen);

            // 3. Color & Notation Label Parity (alternates from initial active color)
            const expectedColor = initialColor === 'w'
              ? (i % 2 === 1 ? 'w' : 'b')
              : (i % 2 === 1 ? 'b' : 'w');
            expect(ply.color).toBe(expectedColor);
            const expectedLabel = `${ply.moveNumber}${ply.color === 'b' ? '...' : '.'} ${ply.san}`;
            expect(ply.label).toBe(expectedLabel);
          }
        },
      ),
      { numRuns: 30 },
    );
  });

  it('Deterministic Error Rejection: Variations and unclosed comments are deterministically rejected', () => {
    const basePgnArb = fc.constantFrom('1. e4 e5', '1. d4 d5 2. c4', '1. Nf3 d5');
    const variationArb = fc.constantFrom('(1... c5)', '(2. Nf3)', '(1. d4 d5)');
    const unclosedCommentArb = fc.constantFrom(
      '{ this comment has no closing brace',
      '{ analysis in progress...',
      '{ note: 1. e4',
    );

    // Variation rejection
    fc.assert(
      fc.property(
        basePgnArb,
        variationArb,
        (basePgn, variation) => {
          const pgnWithVariation = `${basePgn} ${variation}`;
          const result = parsePgnMainLine(pgnWithVariation);

          expect(result.ok).toBe(false);
          if (!result.ok) {
            expect(result.error).toBe("PGN variations aren't supported — paste just the main line.");
          }
        },
      ),
      { numRuns: 20 },
    );

    // Unclosed comment rejection
    fc.assert(
      fc.property(
        basePgnArb,
        unclosedCommentArb,
        (basePgn, unclosed) => {
          const pgnWithUnclosed = `${basePgn} ${unclosed}`;
          const result = parsePgnMainLine(pgnWithUnclosed);

          expect(result.ok).toBe(false);
          if (!result.ok) {
            expect(result.error).toBe("Couldn't read that PGN — check the move text.");
          }
        },
      ),
      { numRuns: 20 },
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

describe('Blindfold State Transition & Reversibility Properties (blindfoldGameState.ts)', () => {
  it('Terminal & Empty Notation Barriers: Completed games and empty notations return explicit string error barriers', () => {
    const initialState = createBlinfoldGameState(BENCHMARK_FENS[0]);

    // Empty notation error
    const emptyResult = applyPlayerMove(initialState, '   ', BENCHMARK_FENS[0]);
    expect(emptyResult).toEqual({ error: 'Illegal move: notation is empty' });

    // Terminal barrier error
    const terminalState: BlindfoldGameState = {
      ...initialState,
      isTerminal: true,
      terminalReason: 'CHECKMATE',
      winner: 'PLAYER',
    };
    const afterTerminalResult = applyPlayerMove(terminalState, 'e4', BENCHMARK_FENS[0]);
    expect(afterTerminalResult).toEqual({ error: 'Illegal move: game is complete' });

    const afterTerminalEngineResult = applyChessProbeMove(terminalState, 'e4', BENCHMARK_FENS[0]);
    expect(afterTerminalEngineResult).toEqual({ error: 'Illegal move: game is complete' });
  });

  it('Reconstruction & Prefix Consistency: Legal move sequences preserve complete history reconstruction, prefix slices, monotonicity, and turn alternation', () => {
    // Array of move choice indices determining path through legal move tree
    const moveChoiceArb = fc.array(fc.integer({ min: 0, max: 100 }), { minLength: 1, maxLength: 8 });
    const fenArb = fc.constantFrom(BENCHMARK_FENS[0], BENCHMARK_FENS[1], BENCHMARK_FENS[2]);

    fc.assert(
      fc.property(fenArb, moveChoiceArb, (startFen, choices) => {
        let state: BlindfoldGameState = createBlinfoldGameState(startFen);
        const chess = new Chess(startFen);
        let expectedTurn: 'PLAYER' | 'CHESSPROBE' = 'PLAYER';

        for (const choice of choices) {
          if (state.isTerminal) break;

          const legalMoves = chess.moves();
          if (legalMoves.length === 0) break;

          const san = legalMoves[choice % legalMoves.length];
          chess.move(san);

          if (expectedTurn === 'PLAYER') {
            const result = applyPlayerMove(state, san, state.currentFen);
            expect('error' in result).toBe(false);
            if ('error' in result) return;
            state = result;
            expectedTurn = 'CHESSPROBE';
          } else {
            const result = applyChessProbeMove(state, san, state.currentFen);
            expect('error' in result).toBe(false);
            if ('error' in result) return;
            state = result;
            expectedTurn = 'PLAYER';
          }

          if (!state.isTerminal) {
            expect(state.currentTurn).toBe(expectedTurn);
          }
        }

        // 1. Move Count Monotonicity
        expect(state.moveCount).toBe(state.moveHistory.length);

        // 2. Complete History Reconstruction
        const reconstructedFen = rebuildPositionFromHistory(startFen, state.moveHistory);
        expect(reconstructedFen).toBe(state.currentFen);

        // 3. Prefix Consistency: For all 1 <= k <= history.length
        for (let k = 1; k <= state.moveHistory.length; k++) {
          const prefixSlice = state.moveHistory.slice(0, k);
          const prefixFen = rebuildPositionFromHistory(startFen, prefixSlice);
          expect(prefixFen).toBe(state.moveHistory[k - 1].fen);
        }

        // 4. Terminal Barrier Rejection
        if (state.isTerminal) {
          expect(applyPlayerMove(state, 'e4', state.currentFen)).toEqual({
            error: 'Illegal move: game is complete',
          });
          expect(applyChessProbeMove(state, 'e4', state.currentFen)).toEqual({
            error: 'Illegal move: game is complete',
          });
        }
      }),
      { numRuns: 25 },
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
