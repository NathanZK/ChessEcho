import type { PracticeAbstractState } from './practiceModel';

export const VALID_PRACTICE_STATES: readonly PracticeAbstractState[] = [
  'DISCOVERY',
  'PRACTICE_IDLE',
  'EVALUATING',
  'CORRECT',
  'INCORRECT',
  'HISTORICAL_MISTAKE',
  'EVALUATION_ERROR',
  'EXPLORING',
] as const;

export type CampaignTraceMode = 'failures-only' | 'all' | 'off';

export type MonkeySelectionStrategy = 'uniform' | 'least-used-transition' | 'rare-state-bias';

export const VALID_SELECTION_STRATEGIES: readonly MonkeySelectionStrategy[] = [
  'uniform',
  'least-used-transition',
  'rare-state-bias',
] as const;

export interface CampaignConfig {
  seeds: number[];
  budget: number;
  workers: number;
  startingState: PracticeAbstractState;
  timeoutMs: number;
  trace: CampaignTraceMode;
  strategy: MonkeySelectionStrategy;
  fault?: boolean;
}

/**
 * Parses seed string specification into an array of positive integer seeds.
 * Formats:
 * - Single integer count: "5" -> [1, 2, 3, 4, 5]
 * - Range: "1-5" -> [1, 2, 3, 4, 5]
 * - Comma-separated list: "42,100,2026" -> [42, 100, 2026]
 */
export function parseSeedArgument(arg: string): number[] {
  const trimmed = arg.trim();

  // Comma-separated list: "1,5,42"
  if (trimmed.includes(',')) {
    const parts = trimmed.split(',').map((s) => s.trim());
    const seeds = parts.map((part) => {
      const num = Number(part);
      if (!Number.isInteger(num) || num <= 0) {
        throw new Error(`Invalid seed specification: "${part}" is not a positive integer.`);
      }
      return num;
    });
    return seeds;
  }

  // Range: "10-14"
  if (trimmed.includes('-')) {
    const [startStr, endStr] = trimmed.split('-').map((s) => s.trim());
    const start = Number(startStr);
    const end = Number(endStr);
    if (!Number.isInteger(start) || start <= 0 || !Number.isInteger(end) || end <= 0) {
      throw new Error(`Invalid seed specification: range "${trimmed}" must contain positive integers.`);
    }
    if (start > end) {
      throw new Error(`Invalid seed range: start (${start}) must be <= end (${end}).`);
    }
    const seeds: number[] = [];
    for (let i = start; i <= end; i++) {
      seeds.push(i);
    }
    return seeds;
  }

  // Single number
  const num = Number(trimmed);
  if (!Number.isInteger(num) || num <= 0) {
    throw new Error(`Invalid seed specification: "${trimmed}" must be a positive integer.`);
  }

  // Count expansion: if specified as e.g. 5 seeds -> [1, 2, 3, 4, 5]
  const seeds: number[] = [];
  for (let i = 1; i <= num; i++) {
    seeds.push(i);
  }
  return seeds;
}

export function parseCampaignArgs(argv: string[]): CampaignConfig {
  let seeds: number[] = [1, 2, 3];
  let budget = 20;
  let workers = 1;
  let startingState: PracticeAbstractState = 'DISCOVERY';
  let timeoutMs = 30_000;
  let trace: CampaignTraceMode = 'failures-only';
  let strategy: MonkeySelectionStrategy = 'uniform';
  let fault = false;

  for (const arg of argv) {
    if (arg.startsWith('--seed=')) {
      const val = Number(arg.slice('--seed='.length));
      if (!Number.isInteger(val) || val <= 0) {
        throw new Error(`Invalid seed specification: "${arg}" must be a positive integer.`);
      }
      seeds = [val];
    } else if (arg.startsWith('--seeds=')) {
      const val = arg.slice('--seeds='.length);
      seeds = parseSeedArgument(val);
    } else if (arg.startsWith('--budget=')) {
      const val = Number(arg.slice('--budget='.length));
      if (!Number.isInteger(val) || val <= 0) {
        throw new Error(`Budget must be a positive integer, received: ${arg}`);
      }
      budget = val;
    } else if (arg.startsWith('--workers=')) {
      const val = Number(arg.slice('--workers='.length));
      if (!Number.isInteger(val) || val <= 0) {
        throw new Error(`Workers must be a positive integer, received: ${arg}`);
      }
      workers = val;
    } else if (arg.startsWith('--startingState=')) {
      const val = arg.slice('--startingState='.length) as PracticeAbstractState;
      if (!VALID_PRACTICE_STATES.includes(val)) {
        throw new Error(
          `Invalid starting state "${val}". Allowed states: ${VALID_PRACTICE_STATES.join(', ')}`
        );
      }
      startingState = val;
    } else if (arg.startsWith('--timeout=')) {
      const val = Number(arg.slice('--timeout='.length));
      if (!Number.isInteger(val) || val <= 0) {
        throw new Error(`Timeout must be a positive integer, received: ${arg}`);
      }
      timeoutMs = val;
    } else if (arg.startsWith('--trace=')) {
      const val = arg.slice('--trace='.length);
      if (val !== 'failures-only' && val !== 'all' && val !== 'off') {
        throw new Error(`Invalid trace mode "${val}". Allowed: failures-only, all, off`);
      }
      trace = val;
    } else if (arg.startsWith('--strategy=')) {
      const val = arg.slice('--strategy='.length) as MonkeySelectionStrategy;
      if (!VALID_SELECTION_STRATEGIES.includes(val)) {
        throw new Error(
          `Invalid selection strategy "${val}". Allowed: ${VALID_SELECTION_STRATEGIES.join(', ')}`
        );
      }
      strategy = val;
    } else if (arg === '--fault' || arg === '--fault=true') {
      fault = true;
    } else if (arg === '--fault=false') {
      fault = false;
    }
  }

  return {
    seeds,
    budget,
    workers,
    startingState,
    timeoutMs,
    trace,
    strategy,
    fault,
  };
}
