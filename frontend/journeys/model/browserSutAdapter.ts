import type { Page } from '@playwright/test';
import { Chess } from 'chess.js';
import type { SutAdapter } from './sutAdapter';
import type { PracticeState, PracticeAction, PracticeContext } from './modelFsm';
import type { ObservedPracticeState } from './invariantEvaluator';

/**
 * Browser-Level SUT Adapter Contract (Milestone 3 Playwright, Issue #790).
 * Defines the concrete methods for driving real DOM elements, capturing rendered state,
 * and asserting application-level defects (such as DOM leaks, focus, and URL changes).
 */
export interface BrowserPracticeSutAdapter
  extends SutAdapter<PracticeState, PracticeAction, ObservedPracticeState> {
  isDomLevel(): true;

  // DOM-level user interaction contracts
  clickPuzzleCard(puzzleId: string): Promise<void>;
  dragPiece(fromSquare: string, toSquare: string): Promise<void>;
  clickExplorationButton(): Promise<void>;
  clickChallengeModeButton(): Promise<void>;
  clickBlindfoldModeButton(): Promise<void>;
  clickExitExplorationButton(): Promise<void>;
  clickRetryButton(): Promise<void>;

  // Real browser URL & layout inspection
  getCurrentRouteHash(): Promise<string>;
  isElementVisible(selector: string): Promise<boolean>;
  hasSquareSelectionHighlight(square: string): Promise<boolean>;
}

/**
 * Concrete Playwright-backed Browser SUT Adapter for live application testing.
 * Implements the BrowserPracticeSutAdapter contract without parallel abstractions.
 */
export class PlaywrightBrowserPracticeSutAdapter implements BrowserPracticeSutAdapter {
  private currentState: PracticeState = 'INITIAL';

  constructor(
    private readonly page: Page,
    private context?: Partial<PracticeContext>
  ) {}

  isDomLevel(): true {
    return true;
  }

  getCurrentState(): PracticeState {
    return this.currentState;
  }

  async clickPuzzleCard(puzzleId: string): Promise<void> {
    if (puzzleId) {
      const card = this.page
        .locator(`[data-testid="puzzle-card-${puzzleId}"]`)
        .or(this.page.locator(`[data-testid="weakness-card-${puzzleId}"]`))
        .first();
      if (await card.isVisible().catch(() => false)) {
        await card.click();
        return;
      }
    }
    const btn = this.page.getByRole('button', { name: /Practice Position|Practice This Weakness|Start Practice/i }).first();
    if (await btn.isVisible().catch(() => false)) {
      await btn.click();
    }
  }

  async dragPiece(fromSquare: string, toSquare: string): Promise<void> {
    const from = this.page.locator(`[data-square="${fromSquare}"]`).first();
    const to = this.page.locator(`[data-square="${toSquare}"]`).first();
    await from.waitFor({ state: 'visible', timeout: 2000 });
    await from.click({ timeout: 3000 });
    await this.page.waitForTimeout(80);
    await to.waitFor({ state: 'visible', timeout: 2000 });
    await to.click({ timeout: 3000 });
    await this.page.waitForTimeout(100);
  }

  async clickExplorationButton(): Promise<void> {
    const btn = this.page
      .locator('[data-testid="puzzle-feedback-panel"]')
      .getByRole('button', { name: /Continue Exploration|Explore/i })
      .first();
    await btn.waitFor({ state: 'visible', timeout: 5000 });
    await btn.click();
    await this.page
      .waitForFunction(
        () =>
          document.querySelector('button[title*="Exit Exploration"]') !== null ||
          Array.from(document.querySelectorAll('button')).some((b) =>
            /Exit/i.test(b.textContent || '')
          ),
        undefined,
        { timeout: 5000 }
      )
      .catch(() => {});
  }

  async clickChallengeModeButton(): Promise<void> {
    const btn = this.page.getByRole('button', { name: /Challenge Mode/i }).first();
    await btn.click();
  }

  async clickBlindfoldModeButton(): Promise<void> {
    const btn = this.page.getByRole('button', { name: /Blindfold/i }).first();
    await btn.click();
  }

  async clickExitExplorationButton(): Promise<void> {
    const exitBtn = this.page
      .locator('button[title*="Exit Exploration"]')
      .or(this.page.locator('[data-testid="puzzle-feedback-panel"] button:has-text("Exit")'))
      .first();
    if (await exitBtn.isVisible().catch(() => false)) {
      await exitBtn.click();
      await exitBtn.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
      return;
    }
    const backBtn = this.page.getByRole('button', { name: /Back to Weaknesses|Exit Exploration|Back to Puzzles/i }).first();
    await backBtn.click();
  }

  async clickRetryButton(): Promise<void> {
    const btn = this.page.getByRole('button', { name: /Try Again|Retry|Reset/i }).first();
    if (await btn.isVisible().catch(() => false)) {
      await btn.click();
    }
  }

  async getCurrentRouteHash(): Promise<string> {
    try {
      return new URL(this.page.url()).hash;
    } catch {
      return '';
    }
  }

  async isElementVisible(selector: string): Promise<boolean> {
    return await this.page.locator(selector).isVisible().catch(() => false);
  }

  async hasSquareSelectionHighlight(square: string): Promise<boolean> {
    const el = this.page.locator(`[data-square="${square}"]`).first();
    if (!(await el.isVisible().catch(() => false))) {
      return false;
    }
    return await el.evaluate((node) => {
      const isAmberHighlight = (val?: string | null) =>
        val ? val.includes('245, 158, 11') || val.includes('245,158,11') : false;

      const inlineBg = (node as HTMLElement).style?.backgroundColor;
      if (isAmberHighlight(inlineBg)) {
        return true;
      }
      const childWithHighlight = Array.from(node.querySelectorAll('*')).some((child) => {
        const bg = (child as HTMLElement).style?.backgroundColor;
        return isAmberHighlight(bg);
      });
      if (childWithHighlight) {
        return true;
      }
      const highlight = node.querySelector('.highlight, [data-highlight="true"]');
      return highlight !== null;
    });
  }

  async hasAnySquareSelectionHighlight(): Promise<boolean> {
    const boardArea = this.page.locator('[data-testid="practice-board-area"]').first();
    if (!(await boardArea.isVisible().catch(() => false))) {
      return false;
    }
    return await boardArea.evaluate((node) => {
      const isAmberHighlight = (val?: string | null) =>
        val ? val.includes('245, 158, 11') || val.includes('245,158,11') : false;

      const squares = Array.from(node.querySelectorAll('[data-square]'));
      for (const sq of squares) {
        const inlineBg = (sq as HTMLElement).style?.backgroundColor;
        if (isAmberHighlight(inlineBg)) return true;
        const childWithHighlight = Array.from(sq.querySelectorAll('*')).some((child) => {
          const bg = (child as HTMLElement).style?.backgroundColor;
          return isAmberHighlight(bg);
        });
        if (childWithHighlight) return true;
      }
      return node.querySelector('.highlight, [data-highlight="true"]') !== null;
    }).catch(() => false);
  }

  async executeAction(action: PracticeAction, context?: unknown): Promise<void> {
    const ctx = (context as PracticeContext) || this.context;
    switch (action) {
      case 'SELECT_PUZZLE':
        await this.clickPuzzleCard(ctx?.puzzleId || '');
        this.currentState = 'INITIAL';
        break;
      case 'MAKE_MOVE_CORRECT': {
        const board = this.page.locator('[data-testid="practice-board-area"]');
        const targetMove = (await board.getAttribute('data-target-move').catch(() => null)) || 'd4';
        await this.playMoveOnBoard(targetMove);
        await this.page
          .waitForFunction(
            () => {
              const el = document.querySelector('[data-testid="puzzle-feedback-panel"]');
              const status = el?.getAttribute('data-feedback-status');
              return status === 'CORRECT' || status === 'SOLVED';
            },
            undefined,
            { timeout: 5000 }
          )
          .catch(() => {});
        this.currentState = 'ATTEMPTED_RIGHT';
        break;
      }
      case 'SOLVE': {
        const feedback = this.page.locator('[data-testid="puzzle-feedback-panel"]');
        const status = await feedback.getAttribute('data-feedback-status').catch(() => '');
        if (status !== 'CORRECT' && status !== 'SOLVED') {
          const board = this.page.locator('[data-testid="practice-board-area"]');
          const targetMove = (await board.getAttribute('data-target-move').catch(() => null)) || 'd4';
          await this.playMoveOnBoard(targetMove);
          await this.page
            .waitForFunction(
              () => {
                const el = document.querySelector('[data-testid="puzzle-feedback-panel"]');
                const s = el?.getAttribute('data-feedback-status');
                return s === 'CORRECT' || s === 'SOLVED';
              },
              undefined,
              { timeout: 5000 }
            )
            .catch(() => {});
        }
        this.currentState = 'SOLVED';
        break;
      }
      case 'MAKE_MOVE_INCORRECT': {
        await this.playMoveOnBoard('a3');
        await this.page
          .waitForFunction(
            () => {
              const el = document.querySelector('[data-testid="puzzle-feedback-panel"]');
              const status = el?.getAttribute('data-feedback-status');
              return (
                status === 'INCORRECT' ||
                status === 'MISTAKE' ||
                status === 'FAILED' ||
                status === 'HISTORICAL_MISTAKE'
              );
            },
            undefined,
            { timeout: 5000 }
          )
          .catch(() => {});
        this.currentState = 'ATTEMPTED_WRONG';
        break;
      }
      case 'ENTER_LINE_EXPLORATION':
        await this.clickExplorationButton();
        this.currentState = 'LINE_EXPLORATION';
        break;
      case 'ENTER_CHALLENGE_MODE':
        await this.clickChallengeModeButton();
        this.currentState = 'CHALLENGE_MODE';
        break;
      case 'ENTER_BLINDFOLD':
        await this.clickBlindfoldModeButton();
        this.currentState = 'BLINDFOLD';
        break;
      case 'RETRY':
        await this.clickRetryButton();
        this.currentState = 'INITIAL';
        break;
      case 'EXIT_EXPLORATION':
      case 'EXIT_CHALLENGE':
        await this.clickExitExplorationButton();
        this.currentState = 'INITIAL';
        break;
      case 'EXIT_BLINDFOLD':
        await this.clickBlindfoldModeButton();
        this.currentState = 'INITIAL';
        break;
      case 'RESET': {
        const resetBtn = this.page
          .locator('button[title="Reset Position"]')
          .or(this.page.getByRole('button', { name: /^Reset$/i }))
          .first();
        if (await resetBtn.isVisible().catch(() => false)) {
          await resetBtn.click();
          await this.page.waitForTimeout(150);
        }
        this.currentState = 'INITIAL';
        break;
      }
      default:
        throw new Error(`Unsupported PracticeAction: ${action}`);
    }
  }

  private async playMoveOnBoard(moveSan: string): Promise<void> {
    const moveCoordMap: Record<string, [string, string]> = {
      d4: ['d2', 'd4'],
      Nc6: ['b8', 'c6'],
      d6: ['d7', 'd6'],
      a3: ['a2', 'a3'],
      h4: ['h2', 'h4'],
      'Bxf7+': ['c4', 'f7'],
    };
    let coords = moveCoordMap[moveSan];
    if (!coords) {
      try {
        const boardArea = this.page.locator('[data-testid="practice-board-area"]').first();
        const fen = (await boardArea.getAttribute('data-board-fen').catch(() => '')) || '';
        if (fen) {
          const chess = new Chess(fen);
          const move = chess.moves({ verbose: true }).find((m) => m.san === moveSan);
          if (move) {
            coords = [move.from, move.to];
          }
        }
      } catch {
        // fallback
      }
    }
    coords = coords || ['d2', 'd4'];
    await this.dragPiece(coords[0], coords[1]);
  }

  async observeState(): Promise<ObservedPracticeState> {
    const hash = await this.getCurrentRouteHash();
    const boardArea = this.page.locator('[data-testid="practice-board-area"]').first();
    const isPracticeVisible = await boardArea.isVisible().catch((e) => {
      console.log('[OBSERVE isVisible ERROR]:', e);
      return false;
    });

    let currentView: 'PRACTICE' | 'WEAKNESSES' | 'OTHER' = 'OTHER';
    if (hash === '#weaknesses') {
      currentView = 'WEAKNESSES';
    } else if (hash === '#puzzles' || isPracticeVisible) {
      currentView = 'PRACTICE';
    }

    if (!isPracticeVisible) {
      console.log(`[OBSERVE NOT VISIBLE] url: ${this.page.url()}, hash: ${hash}`);
      return {
        activePuzzle: null,
        boardFen: '',
        currentView,
        feedbackPanel: {
          status: 'IDLE',
          isInteractive: false,
        },
      };
    }

    const puzzleId = (await boardArea.getAttribute('data-puzzle-id').catch(() => null)) || undefined;
    const source = (await boardArea.getAttribute('data-puzzle-source').catch(() => null)) || undefined;
    const targetMove = (await boardArea.getAttribute('data-target-move').catch(() => null)) || undefined;
    const boardFen = (await boardArea.getAttribute('data-board-fen').catch(() => '')) || '';
    const feedbackStatus = (await boardArea.getAttribute('data-feedback-status').catch(() => 'IDLE')) || 'IDLE';

    const feedbackPanel = this.page.locator('[data-testid="puzzle-feedback-panel"]');
    const isInteractiveAttr = (await feedbackPanel.getAttribute('data-interactive').catch(() => 'true')) || 'true';
    const isInteractive = isInteractiveAttr === 'true';
    const hasResidualHighlight = await this.hasAnySquareSelectionHighlight();

    return {
      activePuzzle: puzzleId ? { id: puzzleId, source, targetMove } : null,
      boardFen,
      currentView,
      feedbackPanel: {
        status: feedbackStatus,
        isInteractive,
      },
      hasResidualHighlight,
    };
  }

  async reset(initialContext?: unknown): Promise<void> {
    this.context = (initialContext as Partial<PracticeContext>) || this.context;
    this.currentState = 'INITIAL';
    const expectedFen = this.context.entryFen || '';

    const boardArea = this.page.locator('[data-testid="practice-board-area"]').first();
    const feedbackPanel = this.page.locator('[data-testid="puzzle-feedback-panel"]');
    let isPracticeVisible = await boardArea.isVisible().catch(() => false);
    let observedFen = (await boardArea.getAttribute('data-board-fen').catch(() => '')) || '';
    let feedbackStatus = (await feedbackPanel.getAttribute('data-feedback-status').catch(() => 'IDLE')) || 'IDLE';

    // If already clean and on practice, return immediately
    if (observedFen && expectedFen && observedFen === expectedFen && isPracticeVisible && feedbackStatus === 'IDLE') {
      return;
    }

    // 1. If in line exploration or challenge mode, exit exploration
    const exitBtn = this.page.locator('button[title="Exit Exploration"]').first();
    if (await exitBtn.isVisible().catch(() => false)) {
      await exitBtn.click().catch(() => {});
      await this.page.waitForTimeout(100);
    }

    // 2. Click Reset button in BoardControls
    const resetBtn = this.page
      .locator('button[title="Reset Position"]')
      .or(this.page.getByRole('button', { name: /^Reset$/i }))
      .first();
    const isResetBtnVis = await resetBtn.isVisible().catch(() => false);
    if (isResetBtnVis) {
      try {
        await resetBtn.click({ timeout: 2000 });
      } catch {
        // Reset button click may fail if disabled or unattached
      }
      if (expectedFen) {
        await this.page
          .waitForFunction(
            (fen) =>
              document.querySelector('[data-testid="practice-board-area"]')?.getAttribute('data-board-fen') === fen,
            expectedFen,
            { timeout: 1500 }
          )
          .catch(() => {});
      }
      await this.page.waitForTimeout(250);
    }

    // 3. Verify if board restored to expected entryFen
    isPracticeVisible = await boardArea.isVisible().catch(() => false);
    observedFen = (await boardArea.getAttribute('data-board-fen').catch(() => '')) || '';
    feedbackStatus = (await feedbackPanel.getAttribute('data-feedback-status').catch(() => 'IDLE')) || 'IDLE';

    if (observedFen && expectedFen && observedFen === expectedFen && isPracticeVisible && feedbackStatus === 'IDLE') {
      return;
    }

    // 4. Otherwise (e.g. when reset handler is mutated or state desynchronized), perform a clean navigation
    if (this.page.url().includes('#puzzles')) {
      await this.page.reload({ waitUntil: 'domcontentloaded' });
    } else {
      await this.page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    }
    await this.page
      .locator('[data-testid="practice-board-area"]')
      .waitFor({ state: 'visible', timeout: 5000 })
      .catch(() => {});
    await this.page.waitForTimeout(250);
  }
}
