import fs from 'node:fs';
import { test, expect, type CDPSession } from '@playwright/test';
import {
  type PracticeAbstractState,
  createInitialContext,
} from './model/practiceModel';
import { runConstrainedMonkey, type MonkeyRunResult } from './model/monkeyRunner';
import { parseCampaignArgs, type CampaignConfig } from './model/campaignConfig';
import { type CampaignRunMetric } from './model/campaignAggregator';
import {
  setupJourneyApiMocks,
  DEFAULT_MOCK_ACCOUNT,
  MOCK_PUZZLES,
  DYNAMIC_EVAL_MOCK_PUZZLE,
} from './fixtures/mockApi';

// Parse configuration from environment variable if passed by CLI runner, or fallback to defaults
const config: CampaignConfig = process.env.MONKEY_CAMPAIGN_CONFIG
  ? JSON.parse(process.env.MONKEY_CAMPAIGN_CONFIG)
  : parseCampaignArgs(process.argv);

const collectedMetrics: CampaignRunMetric[] = [];

test.describe('Multi-Seed Exploration Campaign Runner', () => {
  test.describe.configure({ mode: config.workers > 1 ? 'parallel' : 'default' });

  for (const seed of config.seeds) {
    test(`Seed ${seed}: Bounded exploration (budget: ${config.budget}, state: ${config.startingState})`, async ({
      page,
    }) => {
      let cdpSession: CDPSession | undefined;
      if (config.workers === 1) {
        try {
          cdpSession = await page.context().newCDPSession(page);
          await cdpSession.send('Performance.enable');
        } catch {
          // CDP metrics optional
        }
      }

      await setupJourneyApiMocks(page, {
        authenticated: true,
        accounts: [DEFAULT_MOCK_ACCOUNT],
        puzzles: config.fault ? [DYNAMIC_EVAL_MOCK_PUZZLE, ...MOCK_PUZZLES] : MOCK_PUZZLES,
        ...(config.fault ? { evaluateMoveFault: { fault: 'server_error_500', failureCount: 1 } } : {}),
      });

      const startTime = Date.now();
      const startHeap = process.memoryUsage().heapUsed;

      await page.goto('/#puzzles');

      let result: MonkeyRunResult;
      let caughtError: Error | undefined;

      try {
        result = await runConstrainedMonkey({
          page,
          seed,
          actionBudget: config.budget,
          initialState: config.startingState,
          initialContext: createInitialContext({
            corpus: config.fault ? [DYNAMIC_EVAL_MOCK_PUZZLE, ...MOCK_PUZZLES] : MOCK_PUZZLES,
            faultInjectionEnabled: Boolean(config.fault),
          }),
          traceMode: config.trace,
          strategy: config.strategy,
        });
        if (!result.success && result.error) {
          caughtError = result.error;
        }
      } catch (err) {
        caughtError = err as Error;
        result = {
          seed,
          executedSteps: 0,
          actionLog: [],
          finalState: config.startingState,
          finalContext: createInitialContext({
            faultInjectionEnabled: Boolean(config.fault),
          }),
          success: false,
          error: caughtError,
        };
      }

      const durationMs = Date.now() - startTime;
      const endHeap = process.memoryUsage().heapUsed;

      let chromiumHeapBytes: number | undefined;
      if (cdpSession) {
        try {
          const metrics = await cdpSession.send('Performance.getMetrics');
          const jsHeap = metrics.metrics.find((m) => m.name === 'JSHeapUsedSize');
          if (jsHeap) {
            chromiumHeapBytes = jsHeap.value;
          }
        } catch {
          // Ignore CDP teardown failures
        }
      }

      let traceSizeBytes: number | undefined;
      if (result.tracePath && fs.existsSync(result.tracePath)) {
        traceSizeBytes = fs.statSync(result.tracePath).size;
      }

      const uniqueStates = new Set<PracticeAbstractState>();
      const transitions = new Set<string>();
      const stateActions = new Set<string>();
      const transitionPairs = new Set<string>();
      const configurationTransitions = new Set<string>();
      let faultAttempted = 0;
      let faultTriggered = 0;
      let faultRecovered = 0;

      if (result.actionLog) {
        for (const entry of result.actionLog) {
          uniqueStates.add(entry.fromState);
          uniqueStates.add(entry.toState);
          transitions.add(`${entry.fromState}->${entry.toState}`);
          stateActions.add(`${entry.fromState}:${entry.action}`);
          transitionPairs.add(`${entry.fromState}->${entry.action}->${entry.toState}`);
          if (entry.contextSignature) {
            configurationTransitions.add(
              `${entry.fromState}[${entry.contextSignature}]->${entry.action}->${entry.toState}`
            );
          }

          if (entry.action === 'SUBMIT_FAULT') {
            faultAttempted++;
          }
          if (entry.toState === 'EVALUATION_ERROR') {
            faultTriggered++;
          }
          if (
            entry.fromState === 'EVALUATION_ERROR' &&
            (entry.action === 'RETRY' || entry.action === 'BACK_TO_PUZZLES') &&
            (entry.toState === 'CORRECT' || entry.toState === 'DISCOVERY' || entry.toState === 'INCORRECT')
          ) {
            faultRecovered++;
          }
        }
      } else {
        uniqueStates.add(config.startingState);
      }

      const metric: CampaignRunMetric = {
        seed,
        strategy: config.strategy,
        success: result.success,
        executedSteps: result.executedSteps,
        durationMs,
        uniqueStates,
        transitions,
        stateActions,
        transitionPairs,
        configurationTransitions,
        ...(config.fault
          ? {
              faultAttempted,
              faultTriggered,
              faultRecovered,
            }
          : {}),
        error: caughtError,
        heapUsedBytes: Math.max(0, endHeap - startHeap),
        testWorkerHeapBytes: Math.max(0, endHeap - startHeap),
        chromiumHeapBytes,
        browserJsHeapBytes: chromiumHeapBytes,
        traceSizeBytes,
        screenshotPath: result.screenshotPath,
      };

      collectedMetrics.push(metric);

      try {
        fs.mkdirSync('test-results/campaign-metrics', { recursive: true });
        fs.writeFileSync(
          `test-results/campaign-metrics/metric-${seed}.json`,
          JSON.stringify({
            ...metric,
            error: metric.error?.message,
            uniqueStates: Array.from(metric.uniqueStates),
            transitions: Array.from(metric.transitions),
            stateActions: Array.from(metric.stateActions || []),
            transitionPairs: Array.from(metric.transitionPairs || []),
            configurationTransitions: Array.from(metric.configurationTransitions || []),
          })
        );
      } catch {
        // Metric file caching non-fatal
      }

      if (!result.success) {
        console.error(`Seed ${seed} FAILED at step ${result.executedSteps}: ${result.error?.message}`);
      }
      expect(result.success).toBe(true);
    });
  }
});
