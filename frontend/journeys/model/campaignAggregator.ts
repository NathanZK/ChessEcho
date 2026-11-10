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
  error?: Error;
  heapUsedBytes?: number;
  chromiumHeapBytes?: number;
  traceSizeBytes?: number;
  screenshotPath?: string;
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
  const failedSeeds: number[] = [];
  const replayCommands: string[] = [];

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
  }

  const actionsPerSecond =
    totalDurationMs > 0 ? Number(((totalActions / totalDurationMs) * 1000).toFixed(2)) : 0;

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
