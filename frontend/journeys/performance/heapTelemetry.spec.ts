import { test, expect, CDPSession } from '@playwright/test';
import { setupJourneyApiMocks, DEFAULT_MOCK_PUZZLE } from '../fixtures/mockApi';

interface TelemetryCheckpoint {
  jsHeapUsedBytes: number;
  liveNodes: number;
  detachedNodes: number;
}

async function collectTelemetry(cdp: CDPSession): Promise<TelemetryCheckpoint> {
  // 1. Explicitly trigger forced garbage collection to eliminate V8 generational variance
  await cdp.send('HeapProfiler.collectGarbage');

  // 2. Measure active document tree size & JavaScript heap via Performance.getMetrics
  await cdp.send('Performance.enable');
  const metricsResult = await cdp.send('Performance.getMetrics');
  const metricsMap = new Map(metricsResult.metrics.map((m) => [m.name, m.value]));
  const jsHeapUsedBytes = metricsMap.get('JSHeapUsedSize') ?? 0;
  const liveNodes = metricsMap.get('Nodes') ?? 0;

  // 3. Automated In-Runner Detached DOM detection via Runtime.queryObjects on HTMLElement.prototype
  let detachedNodes = 0;
  const protoResult = await cdp.send('Runtime.evaluate', {
    expression: 'HTMLElement.prototype',
    objectGroup: 'heap-telemetry',
  });

  if (protoResult.result.objectId) {
    const queryResult = await cdp.send('Runtime.queryObjects', {
      prototypeObjectId: protoResult.result.objectId,
      objectGroup: 'heap-telemetry',
    });

    if (queryResult.objects.objectId) {
      const callResult = await cdp.send('Runtime.callFunctionOn', {
        objectId: queryResult.objects.objectId,
        functionDeclaration: `function() {
          let count = 0;
          for (const el of this) {
            if (el && typeof el === 'object' && 'isConnected' in el && !el.isConnected) {
              count++;
            }
          }
          return count;
        }`,
        returnByValue: true,
      });
      detachedNodes = typeof callResult.result.value === 'number' ? callResult.result.value : 0;
    }
  }

  // 4. Properly release object handles
  await cdp.send('Runtime.releaseObjectGroup', { objectGroup: 'heap-telemetry' });

  return { jsHeapUsedBytes, liveNodes, detachedNodes };
}

test.describe('CDP Heap & Memory Telemetry Protocol', () => {
  test('measures post-GC heap, attached DOM nodes, and detached DOM retention across 50 puzzle transitions', async ({
    page,
    browserName,
  }) => {
    // CDP session requires Chromium
    test.skip(browserName !== 'chromium', 'CDP memory telemetry is Chromium-only');

    // 1. Prepare 60 mock puzzles for 50 transition checkpoints
    const mockPuzzles = Array.from({ length: 60 }, (_, i) => ({
      ...DEFAULT_MOCK_PUZZLE,
      puzzleId: `puzzle-telemetry-${i + 1}`,
      openingTitle: `Opening Variation ${i + 1}`,
      fen:
        i % 2 === 0
          ? 'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3'
          : 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
      targetMove: i % 2 === 0 ? 'd4' : 'Nc6',
      solutionLine: [i % 2 === 0 ? 'd4' : 'Nc6'],
    }));

    await setupJourneyApiMocks(page, {
      authenticated: true,
      puzzles: mockPuzzles,
    });

    // 2. Establish CDP session
    const cdp = await page.context().newCDPSession(page);

    // 3. Mount page and verify initial hydration
    await page.goto('/#puzzles', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('Puzzle 1 of 60')).toBeVisible();

    // Checkpoint M0: Baseline post-mount and initial forced GC
    const m0 = await collectTelemetry(cdp);
    expect(m0.jsHeapUsedBytes).toBeGreaterThan(0);
    expect(m0.liveNodes).toBeGreaterThan(0);

    const nextButton = page.getByRole('button', { name: /Next Puzzle/i }).first();
    await expect(nextButton).toBeVisible();

    // Transitions 1 to 10
    for (let i = 1; i <= 10; i++) {
      await nextButton.click();
      await expect(page.getByText(`Puzzle ${i + 1} of 60`)).toBeVisible();
    }
    const m10 = await collectTelemetry(cdp);
    expect(m10.jsHeapUsedBytes).toBeGreaterThan(0);
    expect(m10.liveNodes).toBeGreaterThan(0);

    // Transitions 11 to 25
    for (let i = 11; i <= 25; i++) {
      await nextButton.click();
      await expect(page.getByText(`Puzzle ${i + 1} of 60`)).toBeVisible();
    }
    const m25 = await collectTelemetry(cdp);

    // Transitions 26 to 50
    for (let i = 26; i <= 50; i++) {
      await nextButton.click();
      await expect(page.getByText(`Puzzle ${i + 1} of 60`)).toBeVisible();
    }
    const m50 = await collectTelemetry(cdp);

    // Assertions and Telemetry Evaluation:
    // 1. Attached live DOM nodes remain bounded and stabilized after initial fixture mount
    expect(m50.liveNodes).toBeLessThanOrEqual(m0.liveNodes * 2.5);

    // 2. Detached DOM retention: no persistent unbounded accumulation after warm-up
    // Ephemeral detached elements may exist during transitions, but must not monotonically accumulate
    expect(m50.detachedNodes).toBeLessThanOrEqual(m25.detachedNodes + 15);

    // 3. Heap Growth Evaluation:
    // Net heap delta across 50 puzzle transitions
    const deltaWarmup = m25.jsHeapUsedBytes - m0.jsHeapUsedBytes;
    const deltaSaturated = m50.jsHeapUsedBytes - m25.jsHeapUsedBytes;

    // Saturated marginal heap growth should be bounded (not demonstrating persistent unbounded exponential expansion)
    // As caches saturate, deltaSaturated should be comparable to or less than initial warm-up growth plus buffer
    expect(deltaSaturated).toBeLessThanOrEqual(Math.max(deltaWarmup * 1.75, 15 * 1024 * 1024));

    await cdp.detach();
  });
});
