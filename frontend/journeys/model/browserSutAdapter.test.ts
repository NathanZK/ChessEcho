import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Page } from '@playwright/test';
import { PlaywrightBrowserPracticeSutAdapter } from './browserSutAdapter';

describe('PlaywrightBrowserPracticeSutAdapter', () => {
  let mockPage: Record<string, unknown>;
  let locatorMap: Map<string, Record<string, unknown>>;

  const createMockLocator = (customAttrs: Record<string, string | null> = {}): Record<string, unknown> => {
    const loc: Record<string, unknown> = {
      first: () => loc,
      isVisible: vi.fn().mockResolvedValue(true),
      click: vi.fn().mockResolvedValue(undefined),
      waitFor: vi.fn().mockResolvedValue(undefined),
      getAttribute: vi.fn((attr: string) => Promise.resolve(customAttrs[attr] ?? null)),
      evaluate: vi.fn().mockResolvedValue(false),
      getByRole: vi.fn(() => loc),
      or: vi.fn(() => loc),
    };
    return loc;
  };

  beforeEach(() => {
    locatorMap = new Map();
    mockPage = {
      locator: vi.fn((selector: string) => {
        if (!locatorMap.has(selector)) {
          locatorMap.set(selector, createMockLocator());
        }
        return locatorMap.get(selector);
      }),
      getByRole: vi.fn(() => createMockLocator()),
      getByTitle: vi.fn(() => createMockLocator()),
      evaluate: vi.fn().mockResolvedValue('#puzzles'),
      url: vi.fn().mockReturnValue('http://localhost:3000/#puzzles'),
      waitForFunction: vi.fn().mockResolvedValue(undefined),
      waitForTimeout: vi.fn().mockResolvedValue(undefined),
      goto: vi.fn().mockResolvedValue(null),
      reload: vi.fn().mockResolvedValue(null),
    };
  });

  it('implements the BrowserPracticeSutAdapter contract and reports isDomLevel true', () => {
    const sut = new PlaywrightBrowserPracticeSutAdapter(mockPage as unknown as Page);
    expect(sut.isDomLevel()).toBe(true);
    expect(sut.getCurrentState()).toBe('INITIAL');
  });

  it('observes state from live DOM data attributes', async () => {
    const boardAreaAttrs = {
      'data-puzzle-id': 'test-puz-1',
      'data-puzzle-source': 'supplied',
      'data-target-move': 'd4',
      'data-board-fen': 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      'data-feedback-status': 'IDLE',
    };
    locatorMap.set('[data-testid="practice-board-area"]', createMockLocator(boardAreaAttrs));

    const feedbackAttrs = {
      'data-interactive': 'true',
    };
    locatorMap.set('[data-testid="puzzle-feedback-panel"]', createMockLocator(feedbackAttrs));

    const sut = new PlaywrightBrowserPracticeSutAdapter(mockPage as unknown as Page);
    const state = await sut.observeState();

    expect(state.currentView).toBe('PRACTICE');
    expect(state.activePuzzle?.id).toBe('test-puz-1');
    expect(state.activePuzzle?.source).toBe('supplied');
    expect(state.activePuzzle?.targetMove).toBe('d4');
    expect(state.boardFen).toBe('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1');
    expect(state.feedbackPanel.status).toBe('IDLE');
    expect(state.feedbackPanel.isInteractive).toBe(true);
  });

  it('executes actions and updates internal state tracking', async () => {
    const sut = new PlaywrightBrowserPracticeSutAdapter(mockPage as unknown as Page);

    await sut.executeAction('SELECT_PUZZLE', { puzzleId: 'puzzle-123' });
    expect(sut.getCurrentState()).toBe('INITIAL');

    await sut.executeAction('SOLVE');
    expect(sut.getCurrentState()).toBe('SOLVED');

    await sut.executeAction('ENTER_LINE_EXPLORATION');
    expect(sut.getCurrentState()).toBe('LINE_EXPLORATION');

    await sut.executeAction('EXIT_EXPLORATION');
    expect(sut.getCurrentState()).toBe('INITIAL');
  });

  it('resets page and context on reset() via reload when on #puzzles', async () => {
    mockPage.getByRole = vi.fn(() => ({
      first: () => ({
        isVisible: vi.fn().mockResolvedValue(false),
        click: vi.fn().mockResolvedValue(undefined),
      }),
    }));

    const sut = new PlaywrightBrowserPracticeSutAdapter(mockPage as unknown as Page);
    await sut.reset({ puzzleId: 'puz-new' });
    expect(sut.getCurrentState()).toBe('INITIAL');
    expect(mockPage.reload).toHaveBeenCalledWith({ waitUntil: 'domcontentloaded' });
  });

  it('resets page and context on reset() via goto when not on #puzzles', async () => {
    (mockPage.url as ReturnType<typeof vi.fn>).mockReturnValue('http://localhost:3000/');
    mockPage.getByRole = vi.fn(() => ({
      first: () => ({
        isVisible: vi.fn().mockResolvedValue(false),
        click: vi.fn().mockResolvedValue(undefined),
      }),
    }));

    const sut = new PlaywrightBrowserPracticeSutAdapter(mockPage as unknown as Page);
    await sut.reset({ puzzleId: 'puz-new' });
    expect(sut.getCurrentState()).toBe('INITIAL');
    expect(mockPage.goto).toHaveBeenCalledWith('/#puzzles', { waitUntil: 'domcontentloaded' });
  });
});
