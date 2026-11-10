import { describe, it, expect } from 'vitest';
import type { Page } from '@playwright/test';
import { Chess, type Square } from 'chess.js';
import {
  DEFAULT_PRACTICE_FEN,
  STATE_ORACLES,
  verifyBoardPosition,
  verifyAttemptCounters,
  verifyTurnState,
  verifyMoveFeedback,
  verifyMoveHistory,
  type PageLike,
  type PracticeModelContext,
} from '../../journeys/model/practiceModel';
import { InvariantErrorObserver } from '../../journeys/model/monkeyOracles';

interface MockDomElement {
  role?: string;
  name?: string | RegExp;
  text?: string | RegExp;
  selector?: string;
  visible?: boolean;
  count?: number;
  attributes?: Record<string, string>;
  textContent?: string;
}

interface MockLocator {
  waitFor: (opts?: { state?: string; timeout?: number }) => Promise<void>;
  toBeVisible: () => Promise<boolean>;
  textContent: () => Promise<string | null>;
  getAttribute: (name: string) => Promise<string | null>;
  count: () => Promise<number>;
  first: () => MockLocator;
  locator: (subSelector: string) => MockLocator;
}

function createMockPage(elements: MockDomElement[]): PageLike {
  function matchElement(el: MockDomElement, query: {
    role?: string;
    name?: string | RegExp;
    text?: string | RegExp;
    selector?: string;
  }): boolean {
    if (query.role && el.role !== query.role) return false;
    if (query.name) {
      if (typeof query.name === 'string') {
        if (typeof el.name !== 'string' || !el.name.includes(query.name)) return false;
      } else if (query.name instanceof RegExp) {
        const target = typeof el.name === 'string' ? el.name : '';
        if (!query.name.test(target)) return false;
      }
    }
    if (query.text) {
      if (typeof query.text === 'string') {
        const target = typeof el.text === 'string' ? el.text : (el.textContent ?? '');
        if (!target.includes(query.text)) return false;
      } else if (query.text instanceof RegExp) {
        const target = typeof el.text === 'string' ? el.text : (el.textContent ?? '');
        if (!query.text.test(target)) return false;
      }
    }
    if (query.selector) {
      if (el.selector !== query.selector) return false;
    }
    return true;
  }

  function createLocator(query: {
    role?: string;
    name?: string | RegExp;
    text?: string | RegExp;
    selector?: string;
  }): MockLocator {
    const matched = elements.filter((el) => matchElement(el, query));
    const firstMatch = matched[0];

    const loc: MockLocator = {
      waitFor: async () => {
        if (!firstMatch || firstMatch.visible === false) {
          throw new Error(`Element matching ${JSON.stringify(query)} not visible in mock DOM.`);
        }
      },
      toBeVisible: async () => firstMatch?.visible ?? false,
      textContent: async () => firstMatch?.textContent ?? (typeof firstMatch?.text === 'string' ? firstMatch.text : null),
      getAttribute: async (name: string) => firstMatch?.attributes?.[name] ?? null,
      count: async () => matched.reduce((acc, el) => acc + (el.count ?? (el.visible !== false ? 1 : 0)), 0),
      first: () => loc,
      locator: (subSelector: string) => {
        const combined = query.selector ? `${query.selector} ${subSelector}` : subSelector;
        return createLocator({ selector: combined });
      },
    };
    return loc;
  }

  return {
    locator: (selector: string) => createLocator({ selector }),
    getByRole: (role: string, options?: { name?: string | RegExp }) =>
      createLocator({ role, name: options?.name }),
    getByText: (text: string | RegExp) => createLocator({ text }),
  };
}

function generateBoardElementsFromFen(fen: string): MockDomElement[] {
  const chess = new Chess(fen);
  const elements: MockDomElement[] = [];
  const files = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

  for (let r = 1; r <= 8; r++) {
    for (const f of files) {
      const sq = `${f}${r}` as Square;
      const piece = chess.get(sq);
      if (piece) {
        const code = `${piece.color}${piece.type.toUpperCase()}`;
        elements.push({
          selector: `[data-square="${sq}"] [data-piece]`,
          attributes: { 'data-piece': code },
          count: 1,
          visible: true,
        });
        elements.push({
          selector: `[data-square="${sq}"] [data-piece="${code}"]`,
          attributes: { 'data-piece': code },
          count: 1,
          visible: true,
        });
      }
    }
  }
  return elements;
}

describe('Practice Behavioral Oracles & Invariants', () => {
  describe('DOM-Based Piece & Square Invariant Verification (verifyBoardPosition)', () => {
    it('passes when DOM pieces match expected baseline position exactly', async () => {
      const mockElements = generateBoardElementsFromFen(DEFAULT_PRACTICE_FEN);
      const page = createMockPage(mockElements);

      await expect(verifyBoardPosition(page, DEFAULT_PRACTICE_FEN)).resolves.not.toThrow();
    });

    it('passes when DOM pieces match post-move position (d2 -> d4)', async () => {
      const chess = new Chess(DEFAULT_PRACTICE_FEN);
      chess.move('d4');
      const movedFen = chess.fen();
      const mockElements = generateBoardElementsFromFen(movedFen);
      const page = createMockPage(mockElements);

      await expect(verifyBoardPosition(page, movedFen)).resolves.not.toThrow();
    });

    it('NEGATIVE: detects missing piece on square and throws PIECE_TOPOLOGY_MISMATCH diagnostic', async () => {
      const mockElements = generateBoardElementsFromFen(DEFAULT_PRACTICE_FEN).filter(
        (el) => !el.selector?.includes('d2')
      );
      const page = createMockPage(mockElements);

      await expect(verifyBoardPosition(page, DEFAULT_PRACTICE_FEN)).rejects.toThrow(
        /\[PIECE_TOPOLOGY_MISMATCH\] Square "d2" expected piece "wP" in position/
      );
    });

    it('NEGATIVE: detects incorrect piece on square and throws PIECE_TOPOLOGY_MISMATCH diagnostic', async () => {
      const mockElements = generateBoardElementsFromFen(DEFAULT_PRACTICE_FEN).map((el) => {
        if (el.selector?.includes('d2')) {
          return {
            ...el,
            attributes: { 'data-piece': 'bP' },
          };
        }
        return el;
      });
      const page = createMockPage(mockElements);

      await expect(verifyBoardPosition(page, DEFAULT_PRACTICE_FEN)).rejects.toThrow(
        /\[PIECE_TOPOLOGY_MISMATCH\] Square "d2" expected piece "wP".*found "bP"/
      );
    });

    it('NEGATIVE: detects residual ghost piece on an empty square and throws PIECE_TOPOLOGY_MISMATCH diagnostic', async () => {
      // In baseline FEN, d4 is empty. Inject a rogue piece on d4.
      const mockElements = generateBoardElementsFromFen(DEFAULT_PRACTICE_FEN);
      mockElements.push({
        selector: `[data-square="d4"] [data-piece]`,
        attributes: { 'data-piece': 'wP' },
        count: 1,
        visible: true,
      });
      const page = createMockPage(mockElements);

      await expect(verifyBoardPosition(page, DEFAULT_PRACTICE_FEN)).rejects.toThrow(
        /\[PIECE_TOPOLOGY_MISMATCH\] Square "d4" expected to be empty.*found piece "wP"/
      );
    });
  });

  describe('Attempt Counter Synchronization (verifyAttemptCounters)', () => {
    it('passes when Failed and Solved badges match context counters', async () => {
      const page = createMockPage([
        { text: 'Failed: 0', textContent: 'Failed: 0', visible: true },
        { text: 'Solved: 2', textContent: 'Solved: 2', visible: true },
      ]);

      await expect(verifyAttemptCounters(page, 0, 2)).resolves.not.toThrow();
    });

    it('NEGATIVE: detects Failed counter desynchronization and throws COUNTER_DESYNCHRONIZATION diagnostic', async () => {
      const page = createMockPage([
        { text: 'Failed: 1', textContent: 'Failed: 1', visible: true },
        { text: 'Solved: 0', textContent: 'Solved: 0', visible: true },
      ]);

      await expect(verifyAttemptCounters(page, 0, 0)).rejects.toThrow(
        /\[COUNTER_DESYNCHRONIZATION\] Expected "Failed: 0", but UI rendered "Failed: 1"/
      );
    });

    it('NEGATIVE: detects Solved counter desynchronization and throws COUNTER_DESYNCHRONIZATION diagnostic', async () => {
      const page = createMockPage([
        { text: 'Failed: 0', textContent: 'Failed: 0', visible: true },
        { text: 'Solved: 0', textContent: 'Solved: 0', visible: true },
      ]);

      await expect(verifyAttemptCounters(page, 0, 1)).rejects.toThrow(
        /\[COUNTER_DESYNCHRONIZATION\] Expected "Solved: 1", but UI rendered "Solved: 0"/
      );
    });
  });

  describe('Turn Consistency (verifyTurnState)', () => {
    it('passes when exploration side to move indicator matches expected turn', async () => {
      const page = createMockPage([
        { text: 'Black to move', textContent: 'Black to move', visible: true },
      ]);

      await expect(verifyTurnState(page, 'black', true)).resolves.not.toThrow();
    });

    it('NEGATIVE: detects conflicting turn indicator in exploration and throws TURN_MISMATCH diagnostic', async () => {
      const page = createMockPage([
        { text: 'Black to move', textContent: 'Black to move', visible: true },
      ]);

      await expect(verifyTurnState(page, 'white', true)).rejects.toThrow(
        /\[TURN_MISMATCH\] Expected side to move "white", but UI rendered opposing turn indicator/
      );
    });
  });

  describe('Move Feedback Verification (verifyMoveFeedback)', () => {
    it('verifies best move feedback in CORRECT state', async () => {
      const page = createMockPage([
        { text: 'd4 is the best move!', textContent: 'd4 is the best move!', visible: true },
      ]);

      await expect(verifyMoveFeedback(page, 'CORRECT', 'd4')).resolves.not.toThrow();
    });

    it('verifies inaccurate move feedback banner and notation in INCORRECT state', async () => {
      const page = createMockPage([
        { role: 'heading', name: 'Not the Recommended Move', visible: true },
        { text: 'a3 is not the recommended move', textContent: 'a3 is not the recommended move', visible: true },
      ]);

      await expect(verifyMoveFeedback(page, 'INCORRECT', 'a3')).resolves.not.toThrow();
    });

    it('verifies recurring blunder banner in HISTORICAL_MISTAKE state', async () => {
      const page = createMockPage([
        { text: 'Recurring Weakness Detected!', textContent: 'Recurring Weakness Detected!', visible: true },
      ]);

      await expect(verifyMoveFeedback(page, 'HISTORICAL_MISTAKE', 'h4')).resolves.not.toThrow();
    });
  });

  describe('Move History Verification (verifyMoveHistory)', () => {
    it('passes when move history sequence is rendered in the UI', async () => {
      const page = createMockPage([
        { text: 'd4', textContent: 'd4', visible: true },
        { text: 'Nf6', textContent: 'Nf6', visible: true },
      ]);

      await expect(verifyMoveHistory(page, ['d4', 'Nf6'])).resolves.not.toThrow();
    });

    it('NEGATIVE: detects skipped move log entry in history and throws MOVE_HISTORY_MISMATCH diagnostic', async () => {
      const page = createMockPage([
        { text: 'd4', textContent: 'd4', visible: true },
      ]);

      await expect(verifyMoveHistory(page, ['d4', 'Nf6'])).rejects.toThrow(
        /\[MOVE_HISTORY_MISMATCH\] Expected move log entry "Nf6" from sequence \[d4, Nf6\] was not found in rendered move history/
      );
    });
  });

  describe('STATE_ORACLES Full Invariant Integration', () => {
    it('validates PRACTICE_IDLE state with matching pieces and attempt counters', async () => {
      const mockElements = [
        ...generateBoardElementsFromFen(DEFAULT_PRACTICE_FEN),
        { selector: '[data-square="d2"]', visible: true },
        { role: 'button', name: 'Back to Puzzles', visible: true },
        { text: 'Failed: 0', textContent: 'Failed: 0', visible: true },
        { text: 'Solved: 0', textContent: 'Solved: 0', visible: true },
      ];
      const page = createMockPage(mockElements);
      const context: PracticeModelContext = {
        puzzleLoaded: true,
        attemptCount: 0,
        inExploration: false,
        expectedFen: DEFAULT_PRACTICE_FEN,
        expectedSolvedCount: 0,
        expectedFailedCount: 0,
        expectedSideToMove: 'white',
      };

      await expect(STATE_ORACLES.PRACTICE_IDLE.assert(page, context)).resolves.not.toThrow();
    });

    it('validates CORRECT state with post-move pieces, solved counter, and move feedback', async () => {
      const chess = new Chess(DEFAULT_PRACTICE_FEN);
      chess.move('d4');
      const movedFen = chess.fen();

      const mockElements = [
        ...generateBoardElementsFromFen(movedFen),
        { role: 'button', name: 'Continue Exploration →', visible: true },
        { text: 'Solved: 1', textContent: 'Solved: 1', visible: true },
        { text: 'Failed: 0', textContent: 'Failed: 0', visible: true },
        { text: 'd4 is the best move!', textContent: 'd4 is the best move!', visible: true },
      ];
      const page = createMockPage(mockElements);
      const context: PracticeModelContext = {
        puzzleLoaded: true,
        attemptCount: 1,
        inExploration: false,
        solved: true,
        expectedFen: movedFen,
        expectedSolvedCount: 1,
        expectedFailedCount: 0,
        lastMoveSan: 'd4',
        expectedSideToMove: 'black',
      };

      await expect(STATE_ORACLES.CORRECT.assert(page, context)).resolves.not.toThrow();
    });

    it('validates INCORRECT state with inaccurate move pieces and failed counter', async () => {
      const chess = new Chess(DEFAULT_PRACTICE_FEN);
      chess.move('a3');
      const mistakeFen = chess.fen();

      const mockElements = [
        ...generateBoardElementsFromFen(mistakeFen),
        { role: 'heading', name: 'Not the Recommended Move', visible: true },
        { selector: '[data-square="d2"]', visible: true },
        { text: 'Failed: 1', textContent: 'Failed: 1', visible: true },
        { text: 'Solved: 0', textContent: 'Solved: 0', visible: true },
        { text: 'a3 is not the recommended move', textContent: 'a3 is not the recommended move', visible: true },
      ];
      const page = createMockPage(mockElements);
      const context: PracticeModelContext = {
        puzzleLoaded: true,
        attemptCount: 1,
        inExploration: false,
        expectedFen: mistakeFen,
        expectedSolvedCount: 0,
        expectedFailedCount: 1,
        lastMoveSan: 'a3',
        expectedSideToMove: 'black',
      };

      await expect(STATE_ORACLES.INCORRECT.assert(page, context)).resolves.not.toThrow();
    });

    it('validates EXPLORING state with post-move pieces, turn state, and move history', async () => {
      const chess = new Chess(DEFAULT_PRACTICE_FEN);
      chess.move('d4');
      const movedFen = chess.fen();

      const mockElements = [
        ...generateBoardElementsFromFen(movedFen),
        { text: 'Line Exploration', textContent: 'Line Exploration', visible: true },
        { text: 'Line Exploration (from d4)', textContent: 'Line Exploration (from d4)', visible: true },
        { role: 'button', name: 'Exit Exploration', visible: true },
        { text: 'Black to move', textContent: 'Black to move', visible: true },
        { text: 'd4', textContent: 'd4', visible: true },
      ];
      const page = createMockPage(mockElements);
      const context: PracticeModelContext = {
        puzzleLoaded: true,
        attemptCount: 1,
        inExploration: true,
        solved: true,
        expectedFen: movedFen,
        expectedSolvedCount: 1,
        expectedFailedCount: 0,
        lastMoveSan: 'd4',
        expectedSideToMove: 'black',
        expectedHistory: ['d4'],
      };

      await expect(STATE_ORACLES.EXPLORING.assert(page, context)).resolves.not.toThrow();
    });

    it('maintains backward compatibility when called without context (legacy journey callers)', async () => {
      const page = createMockPage([
        { selector: '[data-square="d2"]', visible: true },
        { role: 'button', name: 'Back to Puzzles', visible: true },
      ]);

      // Call without context
      await expect(STATE_ORACLES.PRACTICE_IDLE.assert(page)).resolves.not.toThrow();
    });
  });

  describe('Clean Teardown Verification', () => {
    it('attaches and detaches page event listeners cleanly without memory leaks', () => {
      const listeners: Record<string, ((...args: unknown[]) => void)[]> = {};
      const fakePage = {
        on: (event: string, handler: (...args: unknown[]) => void) => {
          listeners[event] = listeners[event] || [];
          listeners[event].push(handler);
          return fakePage;
        },
        off: (event: string, handler: (...args: unknown[]) => void) => {
          if (listeners[event]) {
            listeners[event] = listeners[event].filter((h) => h !== handler);
          }
          return fakePage;
        },
      } as Page;

      const observer = new InvariantErrorObserver();
      observer.attach(fakePage);
      expect(listeners['pageerror']?.length).toBe(1);
      expect(listeners['console']?.length).toBe(1);

      observer.detach();
      expect(listeners['pageerror']?.length).toBe(0);
      expect(listeners['console']?.length).toBe(0);
    });
  });
});
