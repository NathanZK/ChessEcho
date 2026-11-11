import type { PracticeAbstractState } from './practiceModel';
import type { MonkeySelectionStrategy } from './campaignConfig';

export interface CampaignRunMetric {
  seed: number;
  strategy?: MonkeySelectionStrategy;
  success: boolean;
  executedSteps: number;
  durationMs: number;
  uniqueStates: Set<PracticeAbstractState>;
  transitions: Set<string>;
  stateActions?: Set<string>;
  transitionPairs?: Set<string>;
  configurationTransitions?: Set<string>;
  faultAttempted?: number;
  faultTriggered?: number;
  faultRecovered?: number;
  error?: Error;
  heapUsedBytes?: number;
  testWorkerHeapBytes?: number;
  chromiumHeapBytes?: number;
  browserJsHeapBytes?: number;
  traceSizeBytes?: number;
  screenshotPath?: string;
}

export interface FaultSummary {
  attempted: number;
  triggered: number;
  recovered: number;
  status: 'VALIDATED' | 'NOT_TRIGGERED' | 'FAILED';
}

export interface CampaignSummary {
  strategy?: MonkeySelectionStrategy;
  totalRuns: number;
  passedRuns: number;
  failedRuns: number;
  totalActions: number;
  totalDurationMs: number;
  actionsPerSecond: number;
  uniqueStatesVisited: PracticeAbstractState[];
  uniqueTransitionsExercised: string[];
  uniqueStateActionsCovered: string[];
  uniqueTransitionPairsCovered: string[];
  uniqueConfigurationTransitions?: string[];
  faultSummary?: FaultSummary;
  avgBrowserJsHeapBytes?: number;
  avgTestWorkerHeapBytes?: number;
  failedSeeds: number[];
  replayCommands: string[];
}

export function formatReplayCommand(
  seed: number,
  budget: number,
  startingState: PracticeAbstractState,
  strategy?: MonkeySelectionStrategy,
): string {
  const strategyFlag = strategy && strategy !== 'uniform' ? ` --strategy=${strategy}` : '';
  return `npm run test:monkey:campaign -- --seed=${seed} --budget=${budget} --startingState=${startingState} --workers=1${strategyFlag}`;
}

export function aggregateCampaignMetrics(
  runs: CampaignRunMetric[],
  budget: number,
  startingState: PracticeAbstractState,
  strategy: MonkeySelectionStrategy = 'uniform',
): CampaignSummary {
  let passedRuns = 0;
  let failedRuns = 0;
  let totalActions = 0;
  let totalDurationMs = 0;
  const statesSet = new Set<PracticeAbstractState>();
  const transitionsSet = new Set<string>();
  const stateActionsSet = new Set<string>();
  const transitionPairsSet = new Set<string>();
  const configTransitionsSet = new Set<string>();
  const failedSeeds: number[] = [];
  const replayCommands: string[] = [];

  let totalFaultAttempted = 0;
  let totalFaultTriggered = 0;
  let totalFaultRecovered = 0;
  let hasFaultData = false;

  let totalBrowserJsHeap = 0;
  let browserJsHeapCount = 0;
  let totalTestWorkerHeap = 0;
  let testWorkerHeapCount = 0;

  for (const run of runs) {
    if (run.success) {
      passedRuns++;
    } else {
      failedRuns++;
      failedSeeds.push(run.seed);
      replayCommands.push(formatReplayCommand(run.seed, budget, startingState, strategy));
    }

    totalActions += run.executedSteps;
    totalDurationMs += run.durationMs;

    for (const state of run.uniqueStates) {
      statesSet.add(state);
    }
    for (const transition of run.transitions) {
      transitionsSet.add(transition);
    }
    if (run.stateActions) {
      for (const sa of run.stateActions) {
        stateActionsSet.add(sa);
      }
    }
    if (run.transitionPairs) {
      for (const tp of run.transitionPairs) {
        transitionPairsSet.add(tp);
      }
    }
    if (run.configurationTransitions) {
      for (const ct of run.configurationTransitions) {
        configTransitionsSet.add(ct);
      }
    }

    if (
      run.faultAttempted !== undefined ||
      run.faultTriggered !== undefined ||
      run.faultRecovered !== undefined
    ) {
      hasFaultData = true;
      totalFaultAttempted += run.faultAttempted ?? 0;
      totalFaultTriggered += run.faultTriggered ?? 0;
      totalFaultRecovered += run.faultRecovered ?? 0;
    }

    const browserHeap = run.browserJsHeapBytes ?? run.chromiumHeapBytes;
    if (browserHeap !== undefined && browserHeap > 0) {
      totalBrowserJsHeap += browserHeap;
      browserJsHeapCount++;
    }

    const workerHeap = run.testWorkerHeapBytes ?? run.heapUsedBytes;
    if (workerHeap !== undefined && workerHeap > 0) {
      totalTestWorkerHeap += workerHeap;
      testWorkerHeapCount++;
    }
  }

  const actionsPerSecond =
    totalDurationMs > 0 ? Number(((totalActions / totalDurationMs) * 1000).toFixed(2)) : 0;

  let faultSummary: FaultSummary | undefined;
  if (hasFaultData) {
    let status: 'VALIDATED' | 'NOT_TRIGGERED' | 'FAILED';
    if (totalFaultTriggered === 0) {
      status = 'NOT_TRIGGERED';
    } else if (totalFaultRecovered >= totalFaultTriggered) {
      status = 'VALIDATED';
    } else {
      status = 'FAILED';
    }
    faultSummary = {
      attempted: totalFaultAttempted,
      triggered: totalFaultTriggered,
      recovered: totalFaultRecovered,
      status,
    };
  }

  return {
    strategy,
    totalRuns: runs.length,
    passedRuns,
    failedRuns,
    totalActions,
    totalDurationMs,
    actionsPerSecond,
    uniqueStatesVisited: Array.from(statesSet),
    uniqueTransitionsExercised: Array.from(transitionsSet),
    uniqueStateActionsCovered: Array.from(stateActionsSet),
    uniqueTransitionPairsCovered: Array.from(transitionPairsSet),
    uniqueConfigurationTransitions: Array.from(configTransitionsSet),
    faultSummary,
    avgBrowserJsHeapBytes:
      browserJsHeapCount > 0 ? Math.round(totalBrowserJsHeap / browserJsHeapCount) : undefined,
    avgTestWorkerHeapBytes:
      testWorkerHeapCount > 0 ? Math.round(totalTestWorkerHeap / testWorkerHeapCount) : undefined,
    failedSeeds,
    replayCommands,
  };
}

export function formatCampaignSummaryTable(summary: CampaignSummary): string {
  const lines: string[] = [
    '==================================================',
    '          Exploration Campaign Summary            ',
    '==================================================',
    `Strategy:            ${summary.strategy || 'uniform'}`,
    `Total Runs:          ${summary.totalRuns}`,
    `Passed Runs:         ${summary.passedRuns}`,
    `Failed Runs:         ${summary.failedRuns}`,
    `Total Actions:       ${summary.totalActions}`,
    `Total Duration:      ${(summary.totalDurationMs / 1000).toFixed(2)}s`,
    `Action Throughput:   ${summary.actionsPerSecond} actions/sec`,
    `States Visited:      ${summary.uniqueStatesVisited.join(', ') || 'None'}`,
    `Transitions Count:   ${summary.uniqueTransitionsExercised.length}`,
    `State-Actions:       ${summary.uniqueStateActionsCovered.length}`,
    `Transition Pairs:    ${summary.uniqueTransitionPairsCovered.length}`,
  ];

  if (summary.uniqueConfigurationTransitions && summary.uniqueConfigurationTransitions.length > 0) {
    lines.push(`Config Transitions:  ${summary.uniqueConfigurationTransitions.length}`);
  }

  if (summary.faultSummary) {
    lines.push(
      `Fault Validation:    ${summary.faultSummary.status} (attempted: ${summary.faultSummary.attempted}, triggered: ${summary.faultSummary.triggered}, recovered: ${summary.faultSummary.recovered})`
    );
  }

  if (summary.avgBrowserJsHeapBytes !== undefined) {
    lines.push(`Browser JS Heap:     ${(summary.avgBrowserJsHeapBytes / (1024 * 1024)).toFixed(2)} MB (avg)`);
  }

  if (summary.avgTestWorkerHeapBytes !== undefined) {
    lines.push(`Test Worker Heap:    ${(summary.avgTestWorkerHeapBytes / (1024 * 1024)).toFixed(2)} MB (avg)`);
  }

  if (summary.failedSeeds.length > 0) {
    lines.push('--------------------------------------------------');
    lines.push(`Failed Seeds (${summary.failedSeeds.length}): ${summary.failedSeeds.join(', ')}`);
    lines.push('Replay Commands:');
    for (const cmd of summary.replayCommands) {
      lines.push(`  ${cmd}`);
    }
  }

  lines.push('==================================================');
  return lines.join('\n');
}
