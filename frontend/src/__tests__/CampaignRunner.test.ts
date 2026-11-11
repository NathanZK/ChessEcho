import { describe, it, expect } from 'vitest';
import {
  parseCampaignArgs,
  parseSeedArgument,
} from '../../journeys/model/campaignConfig';
import {
  aggregateCampaignMetrics,
  formatCampaignSummaryTable,
  formatReplayCommand,
  type CampaignRunMetric,
} from '../../journeys/model/campaignAggregator';

describe('Campaign CLI Argument Parser (campaignConfig)', () => {
  it('parses explicit single seed and default options', () => {
    const config = parseCampaignArgs(['--seed=42']);
    expect(config.seeds).toEqual([42]);
    expect(config.budget).toBe(20);
    expect(config.workers).toBe(1);
    expect(config.startingState).toBe('DISCOVERY');
    expect(config.timeoutMs).toBe(30_000);
    expect(config.trace).toBe('failures-only');
    expect(config.strategy).toBe('uniform');
  });

  it('parses integer count for seeds (e.g. --seeds=5)', () => {
    const seeds = parseSeedArgument('5');
    expect(seeds).toEqual([1, 2, 3, 4, 5]);
  });

  it('parses integer range for seeds (e.g. --seeds=10-14 and --seeds=5-5)', () => {
    const seeds = parseSeedArgument('10-14');
    expect(seeds).toEqual([10, 11, 12, 13, 14]);

    const singleRange = parseSeedArgument('5-5');
    expect(singleRange).toEqual([5]);
  });

  it('parses comma-separated list of seeds (e.g. --seeds=1,5,42)', () => {
    const seeds = parseSeedArgument('1,5,42');
    expect(seeds).toEqual([1, 5, 42]);
  });

  it('throws descriptive error on invalid seed format', () => {
    expect(() => parseSeedArgument('invalid')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('-5')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('0')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('10-5')).toThrow(/Invalid seed range/);
    expect(() => parseSeedArgument('0-5')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('5-0')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('-1-5')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('1,-2')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('1,0')).toThrow(/Invalid seed specification/);
    expect(() => parseSeedArgument('1,abc')).toThrow(/Invalid seed specification/);
  });

  it('parses explicit single seed boundary options', () => {
    const config1 = parseCampaignArgs(['--seed=1']);
    expect(config1.seeds).toEqual([1]);
    expect(() => parseCampaignArgs(['--seed=0'])).toThrow(/Invalid seed specification/);
    expect(() => parseCampaignArgs(['--seed=-1'])).toThrow(/Invalid seed specification/);
    expect(() => parseCampaignArgs(['--seed=abc'])).toThrow(/Invalid seed specification/);
  });

  it('parses custom budget, workers, startingState, timeout, and trace options', () => {
    const args = [
      '--seeds=3',
      '--budget=150',
      '--workers=4',
      '--startingState=PRACTICE_IDLE',
      '--timeout=45000',
      '--trace=all',
      '--strategy=least-used-transition',
    ];
    const config = parseCampaignArgs(args);
    expect(config.seeds).toEqual([1, 2, 3]);
    expect(config.budget).toBe(150);
    expect(config.workers).toBe(4);
    expect(config.startingState).toBe('PRACTICE_IDLE');
    expect(config.timeoutMs).toBe(45_000);
    expect(config.trace).toBe('all');
    expect(config.strategy).toBe('least-used-transition');
  });

  it('parses rare-state-bias strategy and throws on invalid strategy', () => {
    const config = parseCampaignArgs(['--strategy=rare-state-bias']);
    expect(config.strategy).toBe('rare-state-bias');

    expect(() => parseCampaignArgs(['--strategy=bogus'])).toThrow(
      /Invalid selection strategy "bogus"/,
    );
  });

  it('parses alternative valid trace modes', () => {
    const c1 = parseCampaignArgs(['--trace=off']);
    expect(c1.trace).toBe('off');

    const c2 = parseCampaignArgs(['--trace=failures-only']);
    expect(c2.trace).toBe('failures-only');

    const c3 = parseCampaignArgs(['--trace=all']);
    expect(c3.trace).toBe('all');
  });

  it('rejects invalid budget, worker, and timeout boundaries', () => {
    expect(() => parseCampaignArgs(['--budget=0'])).toThrow(/Budget must be a positive integer/);
    expect(() => parseCampaignArgs(['--budget=-1'])).toThrow(/Budget must be a positive integer/);
    expect(() => parseCampaignArgs(['--budget=abc'])).toThrow(/Budget must be a positive integer/);

    expect(() => parseCampaignArgs(['--workers=0'])).toThrow(/Workers must be a positive integer/);
    expect(() => parseCampaignArgs(['--workers=-1'])).toThrow(/Workers must be a positive integer/);
    expect(() => parseCampaignArgs(['--workers=abc'])).toThrow(/Workers must be a positive integer/);

    expect(() => parseCampaignArgs(['--timeout=0'])).toThrow(/Timeout must be a positive integer/);
    expect(() => parseCampaignArgs(['--timeout=-1'])).toThrow(/Timeout must be a positive integer/);
    expect(() => parseCampaignArgs(['--timeout=abc'])).toThrow(/Timeout must be a positive integer/);

    expect(() => parseCampaignArgs(['--trace=invalid'])).toThrow(/Invalid trace mode/);
    expect(() => parseCampaignArgs(['--startingState=UNKNOWN_STATE'])).toThrow(/Invalid starting state/);
  });

  it('accepts minimal positive boundaries', () => {
    const config = parseCampaignArgs(['--budget=1', '--workers=1', '--timeout=1']);
    expect(config.budget).toBe(1);
    expect(config.workers).toBe(1);
    expect(config.timeoutMs).toBe(1);
    expect(config.fault).toBe(false);
  });

  it('parses fault injection flags correctly', () => {
    const configDefault = parseCampaignArgs([]);
    expect(configDefault.fault).toBe(false);

    const configBare = parseCampaignArgs(['--fault']);
    expect(configBare.fault).toBe(true);

    const configExplicit = parseCampaignArgs(['--fault=true']);
    expect(configExplicit.fault).toBe(true);

    const configFalse = parseCampaignArgs(['--fault=false']);
    expect(configFalse.fault).toBe(false);
  });
});

describe('Campaign Metric Aggregator (campaignAggregator)', () => {
  it('correctly aggregates metrics for passing runs', () => {
    const runs: CampaignRunMetric[] = [
      {
        seed: 1,
        success: true,
        executedSteps: 20,
        durationMs: 2000,
        uniqueStates: new Set(['DISCOVERY', 'PRACTICE_IDLE']),
        transitions: new Set(['DISCOVERY->PRACTICE_IDLE']),
        stateActions: new Set(['DISCOVERY:SELECT_PUZZLE']),
        transitionPairs: new Set(['DISCOVERY->SELECT_PUZZLE->PRACTICE_IDLE']),
      },
      {
        seed: 2,
        success: true,
        executedSteps: 20,
        durationMs: 3000,
        uniqueStates: new Set(['PRACTICE_IDLE', 'CORRECT']),
        transitions: new Set(['PRACTICE_IDLE->CORRECT']),
        stateActions: new Set(['PRACTICE_IDLE:SUBMIT_OPTIMAL']),
        transitionPairs: new Set(['PRACTICE_IDLE->SUBMIT_OPTIMAL->CORRECT']),
      },
    ];

    const summary = aggregateCampaignMetrics(runs, 20, 'DISCOVERY', 'least-used-transition');
    expect(summary.totalRuns).toBe(2);
    expect(summary.passedRuns).toBe(2);
    expect(summary.failedRuns).toBe(0);
    expect(summary.totalActions).toBe(40);
    expect(summary.totalDurationMs).toBe(5000);
    expect(summary.actionsPerSecond).toBe(8.0);
    expect(summary.strategy).toBe('least-used-transition');
    expect(summary.uniqueStatesVisited.sort()).toEqual(['CORRECT', 'DISCOVERY', 'PRACTICE_IDLE'].sort());
    expect(summary.uniqueTransitionsExercised.sort()).toEqual([
      'DISCOVERY->PRACTICE_IDLE',
      'PRACTICE_IDLE->CORRECT',
    ].sort());
    expect(summary.uniqueStateActionsCovered.sort()).toEqual([
      'DISCOVERY:SELECT_PUZZLE',
      'PRACTICE_IDLE:SUBMIT_OPTIMAL',
    ].sort());
    expect(summary.uniqueTransitionPairsCovered.sort()).toEqual([
      'DISCOVERY->SELECT_PUZZLE->PRACTICE_IDLE',
      'PRACTICE_IDLE->SUBMIT_OPTIMAL->CORRECT',
    ].sort());
    expect(summary.failedSeeds).toEqual([]);
    expect(summary.replayCommands).toEqual([]);

    const table = formatCampaignSummaryTable(summary);
    expect(table).toContain('Strategy:            least-used-transition');
    expect(table).toContain('State-Actions:       2');
    expect(table).toContain('Transition Pairs:    2');
    expect(table).toContain('Passed Runs:         2');
    expect(table).toContain('Failed Runs:         0');
    expect(table).not.toContain('Replay Commands:');
  });

  it('correctly identifies failed seeds and formats deterministic replay commands', () => {
    const runs: CampaignRunMetric[] = [
      {
        seed: 101,
        success: true,
        executedSteps: 25,
        durationMs: 2500,
        uniqueStates: new Set(['DISCOVERY', 'PRACTICE_IDLE']),
        transitions: new Set(['DISCOVERY->PRACTICE_IDLE']),
      },
      {
        seed: 102,
        success: false,
        executedSteps: 12,
        durationMs: 1500,
        uniqueStates: new Set(['DISCOVERY', 'PRACTICE_IDLE', 'EVALUATION_ERROR']),
        transitions: new Set(['PRACTICE_IDLE->EVALUATION_ERROR']),
        error: new Error('Invariant violated: unexpected alert banner'),
      },
    ];

    const summary = aggregateCampaignMetrics(runs, 25, 'DISCOVERY');
    expect(summary.totalRuns).toBe(2);
    expect(summary.passedRuns).toBe(1);
    expect(summary.failedRuns).toBe(1);
    expect(summary.failedSeeds).toEqual([102]);
    expect(summary.replayCommands).toHaveLength(1);
    expect(summary.replayCommands[0]).toBe(
      'npm run test:monkey:campaign -- --seed=102 --budget=25 --startingState=DISCOVERY --workers=1'
    );
    expect(formatReplayCommand(102, 25, 'DISCOVERY')).toBe(
      'npm run test:monkey:campaign -- --seed=102 --budget=25 --startingState=DISCOVERY --workers=1'
    );
  });

  it('formats human-readable summary table including failed seeds', () => {
    const runs: CampaignRunMetric[] = [
      {
        seed: 42,
        success: false,
        executedSteps: 8,
        durationMs: 1000,
        uniqueStates: new Set(['DISCOVERY']),
        transitions: new Set(),
        error: new Error('Timeout'),
      },
    ];

    const summary = aggregateCampaignMetrics(runs, 20, 'DISCOVERY');
    const table = formatCampaignSummaryTable(summary);
    expect(table).toContain('Exploration Campaign Summary');
    expect(table).toMatch(/Total Runs:\s+1/);
    expect(table).toMatch(/Failed Runs:\s+1/);
    expect(table).toContain('Replay Commands:');
    expect(table).toContain('--seed=42');
  });

  it('handles empty runs array without division by zero and displays None for states', () => {
    const summary = aggregateCampaignMetrics([], 20, 'DISCOVERY');
    expect(summary.totalRuns).toBe(0);
    expect(summary.actionsPerSecond).toBe(0);
    expect(summary.totalDurationMs).toBe(0);

    const table = formatCampaignSummaryTable(summary);
    expect(table).toContain('States Visited:      None');
    expect(table).toContain('Total Runs:          0');
  });

  it('aggregates tri-partite fault metrics and reports VALIDATED, NOT_TRIGGERED, and FAILED statuses', () => {
    // 1. NOT_TRIGGERED: fault attempted but triggered is 0
    const runsNotTriggered: CampaignRunMetric[] = [
      {
        seed: 1,
        success: true,
        executedSteps: 10,
        durationMs: 1000,
        uniqueStates: new Set(['PRACTICE_IDLE']),
        transitions: new Set(),
        faultAttempted: 2,
        faultTriggered: 0,
        faultRecovered: 0,
      },
    ];
    const summaryNotTriggered = aggregateCampaignMetrics(runsNotTriggered, 10, 'PRACTICE_IDLE');
    expect(summaryNotTriggered.faultSummary?.status).toBe('NOT_TRIGGERED');
    expect(summaryNotTriggered.faultSummary?.attempted).toBe(2);
    expect(summaryNotTriggered.faultSummary?.triggered).toBe(0);
    const tableNotTriggered = formatCampaignSummaryTable(summaryNotTriggered);
    expect(tableNotTriggered).toContain('Fault Validation:    NOT_TRIGGERED (attempted: 2, triggered: 0, recovered: 0)');

    // 2. VALIDATED: triggered > 0 and recovered >= triggered
    const runsValidated: CampaignRunMetric[] = [
      {
        seed: 2,
        success: true,
        executedSteps: 15,
        durationMs: 1500,
        uniqueStates: new Set(['PRACTICE_IDLE', 'EVALUATION_ERROR', 'CORRECT']),
        transitions: new Set(['PRACTICE_IDLE->EVALUATION_ERROR', 'EVALUATION_ERROR->CORRECT']),
        faultAttempted: 1,
        faultTriggered: 1,
        faultRecovered: 1,
      },
    ];
    const summaryValidated = aggregateCampaignMetrics(runsValidated, 15, 'PRACTICE_IDLE');
    expect(summaryValidated.faultSummary?.status).toBe('VALIDATED');
    expect(summaryValidated.faultSummary?.recovered).toBe(1);
    const tableValidated = formatCampaignSummaryTable(summaryValidated);
    expect(tableValidated).toContain('Fault Validation:    VALIDATED (attempted: 1, triggered: 1, recovered: 1)');

    // 3. FAILED: triggered > recovered
    const runsFailed: CampaignRunMetric[] = [
      {
        seed: 3,
        success: false,
        executedSteps: 12,
        durationMs: 1200,
        uniqueStates: new Set(['PRACTICE_IDLE', 'EVALUATION_ERROR']),
        transitions: new Set(['PRACTICE_IDLE->EVALUATION_ERROR']),
        faultAttempted: 2,
        faultTriggered: 2,
        faultRecovered: 1,
      },
    ];
    const summaryFailed = aggregateCampaignMetrics(runsFailed, 12, 'PRACTICE_IDLE');
    expect(summaryFailed.faultSummary?.status).toBe('FAILED');
    const tableFailed = formatCampaignSummaryTable(summaryFailed);
    expect(tableFailed).toContain('Fault Validation:    FAILED (attempted: 2, triggered: 2, recovered: 1)');
  });

  it('aggregates configuration transitions and scoped memory usage metrics', () => {
    const runs: CampaignRunMetric[] = [
      {
        seed: 1,
        success: true,
        executedSteps: 10,
        durationMs: 1000,
        uniqueStates: new Set(['PRACTICE_IDLE']),
        transitions: new Set(['PRACTICE_IDLE->CORRECT']),
        configurationTransitions: new Set([
          'PRACTICE_IDLE[true:false:0:false:false:false:false:false:0:null]->SUBMIT_OPTIMAL->CORRECT',
        ]),
        browserJsHeapBytes: 25 * 1024 * 1024,
        testWorkerHeapBytes: 50 * 1024 * 1024,
      },
      {
        seed: 2,
        success: true,
        executedSteps: 10,
        durationMs: 1000,
        uniqueStates: new Set(['CORRECT']),
        transitions: new Set(['CORRECT->PRACTICE_IDLE']),
        configurationTransitions: new Set([
          'CORRECT[true:false:1:true:false:false:false:false:0:null]->NEXT_PUZZLE->PRACTICE_IDLE',
        ]),
        browserJsHeapBytes: 35 * 1024 * 1024,
        testWorkerHeapBytes: 70 * 1024 * 1024,
      },
    ];

    const summary = aggregateCampaignMetrics(runs, 10, 'PRACTICE_IDLE');
    expect(summary.uniqueConfigurationTransitions).toHaveLength(2);
    expect(summary.avgBrowserJsHeapBytes).toBe(30 * 1024 * 1024);
    expect(summary.avgTestWorkerHeapBytes).toBe(60 * 1024 * 1024);

    const table = formatCampaignSummaryTable(summary);
    expect(table).toContain('Config Transitions:  2');
    expect(table).toContain('Browser JS Heap:     30.00 MB (avg)');
    expect(table).toContain('Test Worker Heap:    60.00 MB (avg)');
  });
});
