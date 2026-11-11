import { test, expect } from '@playwright/test';
import {
  STATE_ORACLES,
  ensureDiscoveryState,
  createPracticeContext,
  executeConcreteAction,
} from '../model/practiceModel';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  MOCK_PUZZLES,
} from '../fixtures/mockApi';

test.describe('Monkey Behavioral Oracle Invariant Proofs (Direct DOM Mutation)', () => {
  test('Case 1: Rejects with [PIECE_TOPOLOGY_MISMATCH] citing square d4, expected wP, found bP', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: MOCK_PUZZLES,
    });

    await page.goto('/#puzzles');
    await ensureDiscoveryState(page);

    const fixture = MOCK_PUZZLES[0];
    let context = createPracticeContext(fixture, MOCK_PUZZLES, 0, {
      corpus: MOCK_PUZZLES,
      expectedSolvedCount: 0,
      expectedFailedCount: 0,
      faultInjectionEnabled: false,
      timerMode: null,
    });

    // 1. Select puzzle from discovery
    await executeConcreteAction(page, 'SELECT_PUZZLE', context);

    // 2. Play optimal move d2 -> d4
    await executeConcreteAction(page, 'SUBMIT_OPTIMAL', context);

    // Context after optimal move
    context = {
      ...context,
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedSideToMove: 'black',
      attemptCount: 1,
      expectedSolvedCount: 1,
      lastMoveSan: 'd4',
      expectedHistory: ['d4'],
      historyIndex: 1,
      historyLength: 1,
      solved: true,
    };

    // Confirm that the oracle passes prior to mutation
    await expect(STATE_ORACLES.CORRECT.assert(page, context)).resolves.not.toThrow();

    // 3. Mutate target square in DOM via page.evaluate
    await page
      .locator('[data-square="d4"] [data-piece]')
      .evaluate((el) => el.setAttribute('data-piece', 'bP'));

    // 4. Independently verify the mutation
    const observedPiece = await page
      .locator('[data-square="d4"] [data-piece]')
      .getAttribute('data-piece');
    expect(observedPiece).toBe('bP');

    // 5. Assert STATE_ORACLES.CORRECT.assert rejects with [PIECE_TOPOLOGY_MISMATCH] citing square d4, expected wP, found bP
    await expect(STATE_ORACLES.CORRECT.assert(page, context)).rejects.toThrow(
      /\[PIECE_TOPOLOGY_MISMATCH\] Square "d4" expected piece "wP".*found "bP"/
    );
  });

  test('Case 2: Rejects with [COUNTER_DESYNCHRONIZATION] citing expected vs actual count', async ({
    page,
  }) => {
    await setupJourneyApiMocks(page, {
      authenticated: true,
      accounts: [DEFAULT_MOCK_ACCOUNT],
      puzzles: MOCK_PUZZLES,
    });

    await page.goto('/#puzzles');
    await ensureDiscoveryState(page);

    const fixture = MOCK_PUZZLES[0];
    let context = createPracticeContext(fixture, MOCK_PUZZLES, 0, {
      corpus: MOCK_PUZZLES,
      expectedSolvedCount: 0,
      expectedFailedCount: 0,
      faultInjectionEnabled: false,
      timerMode: null,
    });

    await executeConcreteAction(page, 'SELECT_PUZZLE', context);
    await executeConcreteAction(page, 'SUBMIT_OPTIMAL', context);

    context = {
      ...context,
      expectedFen: 'r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq - 0 3',
      expectedSideToMove: 'black',
      attemptCount: 1,
      expectedSolvedCount: 1,
      lastMoveSan: 'd4',
      expectedHistory: ['d4'],
      historyIndex: 1,
      historyLength: 1,
      solved: true,
    };

    await expect(STATE_ORACLES.CORRECT.assert(page, context)).resolves.not.toThrow();

    // Mutate attempt counter text via page.evaluate: set text content to "Solved: 99"
    await page.getByText(/Solved:\s*\d+/i).evaluate((el) => {
      el.textContent = 'Solved: 99';
    });

    // Independently verify mutation
    const observedText = await page.getByText(/Solved:\s*\d+/i).textContent();
    expect(observedText).toContain('Solved: 99');

    // Assert STATE_ORACLES.CORRECT.assert rejects with [COUNTER_DESYNCHRONIZATION]
    await expect(STATE_ORACLES.CORRECT.assert(page, context)).rejects.toThrow(
      /\[COUNTER_DESYNCHRONIZATION\] Expected "Solved: 1", but UI rendered "Solved: 99"/
    );
  });
});
