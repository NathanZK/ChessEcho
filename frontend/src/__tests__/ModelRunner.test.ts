import { describe, it, expect, vi } from 'vitest';
import type { Page } from '@playwright/test';
import type { StateModel } from '../../journeys/model/stateModel';
import { InvariantErrorObserver } from '../../journeys/model/monkeyOracles';
import {
  findBfsPath,
  executeModelPath,
  type ModelPathExecutionResult,
} from '../../journeys/model/modelRunner';

describe('Generic Model Runner', () => {
  type TestState = 'A' | 'B' | 'C' | 'UNREACHABLE';
  type TestAction = 'TO_B' | 'TO_C' | 'FAIL_ACTION' | 'UNGUARDED';
  interface TestContext {
    flag: boolean;
    stepCount: number;
  }

  const createTestModel = (overrides?: {
    actionExecutor?: (page: Page, action: TestAction, context: TestContext) => Promise<void>;
    oracleOverrides?: Partial<Record<TestState, (page: Page, context: TestContext) => Promise<void>>>;
  }): StateModel<TestState, TestAction, TestContext> => {
    return {
      domain: 'test-domain',
      initialState: 'A',
      createInitialContext: () => ({ flag: false, stepCount: 0 }),
      transitions: [
        {
          from: 'A',
          action: 'TO_B',
          to: 'B',
          effect: (ctx) => ({ ...ctx, stepCount: ctx.stepCount + 1 }),
        },
        {
          from: 'B',
          action: 'TO_C',
          to: 'C',
          guard: (ctx) => ctx.stepCount > 0,
          effect: (ctx) => ({ ...ctx, stepCount: ctx.stepCount + 1, flag: true }),
        },
        {
          from: 'B',
          action: 'FAIL_ACTION',
          to: 'C',
        },
        {
          from: 'A',
          action: 'UNGUARDED',
          to: 'C',
          guard: () => false, // guarded out
        },
      ],
      oracles: {
        A: overrides?.oracleOverrides?.A ?? vi.fn().mockResolvedValue(undefined),
        B: overrides?.oracleOverrides?.B ?? vi.fn().mockResolvedValue(undefined),
        C: overrides?.oracleOverrides?.C ?? vi.fn().mockResolvedValue(undefined),
        UNREACHABLE: overrides?.oracleOverrides?.UNREACHABLE ?? vi.fn().mockResolvedValue(undefined),
      },
      executeAction:
        overrides?.actionExecutor ??
        vi.fn().mockImplementation(async (_page: Page, action: TestAction) => {
          if (action === 'FAIL_ACTION') {
            throw new Error('Concrete DOM interaction failed');
          }
        }),
      contextSignature: (ctx) => `${ctx.flag}:${ctx.stepCount}`,
    };
  };

  describe('findBfsPath', () => {
    it('returns empty array when targetState is identical to initialState', () => {
      const model = createTestModel();
      const path = findBfsPath(model, 'A');
      expect(path).toEqual([]);
    });

    it('finds the shortest path across single and multi-step transitions', () => {
      const model = createTestModel();
      expect(findBfsPath(model, 'B')).toEqual(['TO_B']);
      expect(findBfsPath(model, 'C')).toEqual(['TO_B', 'TO_C']);
    });

    it('returns null when target state is guarded out or unreachable', () => {
      const model = createTestModel();
      expect(findBfsPath(model, 'UNREACHABLE')).toBeNull();
    });

    it('supports custom starting state and context', () => {
      const model = createTestModel();
      const path = findBfsPath(model, 'C', 'B', { flag: false, stepCount: 1 });
      expect(path).toEqual(['TO_C']);
    });
  });

  describe('executeModelPath', () => {
    it('invokes ensureInitialState hook if defined on model', async () => {
      const model = createTestModel();
      const ensureHook = vi.fn().mockResolvedValue(undefined);
      model.ensureInitialState = ensureHook;

      const fakePage = {} as Page;
      const result = await executeModelPath(fakePage, model, ['TO_B']);

      expect(ensureHook).toHaveBeenCalledTimes(1);
      expect(result.success).toBe(true);
    });

    it('executes actions and evaluates state oracles sequentially, committing visited states on success', async () => {
      const model = createTestModel();
      const fakePage = {} as Page;

      const result: ModelPathExecutionResult<TestState, TestContext> = await executeModelPath(
        fakePage,
        model,
        ['TO_B', 'TO_C']
      );

      expect(result.success).toBe(true);
      expect(result.finalState).toBe('C');
      expect(result.finalContext).toEqual({ flag: true, stepCount: 2 });
      expect(result.actionLog).toHaveLength(2);
      expect(result.visitedStates.has('B')).toBe(true);
      expect(result.visitedStates.has('C')).toBe(true);
      expect(result.executedTransitions.has('A->TO_B->B')).toBe(true);
      expect(result.executedTransitions.has('B->TO_C->C')).toBe(true);
      expect(model.oracles.B).toHaveBeenCalled();
      expect(model.oracles.C).toHaveBeenCalled();
    });

    it('stops execution and does not commit failed step when concrete action throws', async () => {
      const model = createTestModel();
      const fakePage = {} as Page;

      const result = await executeModelPath(fakePage, model, ['TO_B', 'FAIL_ACTION']);

      expect(result.success).toBe(false);
      expect(result.error?.message).toContain('Concrete DOM interaction failed');
      expect(result.finalState).toBe('B');
      expect(result.finalContext.stepCount).toBe(1);
      expect(result.visitedStates.has('B')).toBe(true);
      expect(result.visitedStates.has('C')).toBe(false);
      expect(result.executedTransitions.has('B->FAIL_ACTION->C')).toBe(false);
    });

    it('stops execution and does not commit failed step when state oracle throws', async () => {
      const oracleError = new Error('Oracle assertion failed: element not visible');
      const model = createTestModel({
        oracleOverrides: {
          C: vi.fn().mockRejectedValue(oracleError),
        },
      });
      const fakePage = {} as Page;

      const result = await executeModelPath(fakePage, model, ['TO_B', 'TO_C']);

      expect(result.success).toBe(false);
      expect(result.error).toBe(oracleError);
      expect(result.finalState).toBe('B');
      expect(result.visitedStates.has('B')).toBe(true);
      expect(result.visitedStates.has('C')).toBe(false);
    });

    it('supports object-style oracles with description and assert method', async () => {
      const assertMock = vi.fn().mockResolvedValue(undefined);
      const model = createTestModel();
      model.oracles.B = {
        description: 'Verify state B presentation',
        assert: assertMock,
      };

      const fakePage = {} as Page;
      const result = await executeModelPath(fakePage, model, ['TO_B']);

      expect(result.success).toBe(true);
      expect(assertMock).toHaveBeenCalledWith(fakePage, { flag: false, stepCount: 1 });
    });

    it('captures full-page screenshot on failure when page.screenshot is available', async () => {
      const model = createTestModel();
      const screenshotMock = vi.fn().mockResolvedValue(undefined);
      const fakePageMock: Partial<Page> = {
        screenshot: screenshotMock,
      };

      const result = await executeModelPath(fakePageMock as Page, model, ['FAIL_ACTION' as TestAction]);

      expect(result.success).toBe(false);
      expect(screenshotMock).toHaveBeenCalledWith(
        expect.objectContaining({ fullPage: true, path: expect.stringContaining('test-results/model-failure-test-domain-') })
      );
      expect(result.screenshotPath).toBeDefined();
    });

    it('cleans up observer in finally block even if execution fails', async () => {
      const model = createTestModel();
      const fakePage = {} as Page;
      const observer = new InvariantErrorObserver();
      const detachSpy = vi.spyOn(observer, 'detach');

      const result = await executeModelPath(fakePage, model, ['FAIL_ACTION' as TestAction], {
        observer,
      });

      expect(result.success).toBe(false);
      expect(detachSpy).toHaveBeenCalled();
    });
  });
});
