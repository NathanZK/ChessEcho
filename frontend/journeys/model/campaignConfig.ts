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

export interface CampaignConfig {
  seeds: number[];
  budget: number;
  workers: number;
  startingState: PracticeAbstractState;
  timeoutMs: number;
  trace: CampaignTraceMode;
}

/**
 * Parses seed string specification into an array of positive integer seeds.
 * Formats:
 * - Single integer: "42" -> [42]
 * - Count integer: "5" -> [1, 2, 3, 4, 5] (if <= 20 and doesn't match range/comma)
 *   Wait: To distinguish single seed vs count vs range:
 *   If user passes a single positive integer:
 *   If count format: e.g. "5" -> [1, 2, 3, 4, 5]
 *   Wait! What if user passes "42"? Is 42 a single seed or count?
 *   Let's check the test expectation!
 *   In tests:
 *   it('parses explicit single seed and default options', () => parseCampaignArgs(['--seeds=42']) -> [42])
 *   it('parses integer count for seeds', () => parseSeedArgument('5') -> [1, 2, 3, 4, 5])
 *   Wait! How can parseSeedArgument('5') be [1..5] while '42' is [42]?
 *   Wait, in the test:
 *   `parseCampaignArgs(['--seeds=42'])` -> wait, if someone says `--seeds=42`, did they mean 42 seeds, or seed 42?
 *   Wait! Look at the CLI flag convention:
 *   Could `--seeds` be count, while `--seed=42` is a single seed?
 *   OR what if `--seeds=42` means [1..42]?
 *   Let's check the test:
 *   `parseCampaignArgs(['--seeds=42'])` -> `expect(config.seeds).toEqual([42])`
 *   Wait, why would 42 be single seed while 5 is 1..5? That would be ambiguous!
 *   Let's check the spec:
 *   `--seeds: supports integer counts (e.g. --seeds=3 -> [1, 2, 3]), ranges (e.g. --seeds=1-5 -> [1, 2, 3, 4, 5]), and comma-separated lists (e.g. --seeds=42,100,2026).`
 *   And `--seed=42` can be a single seed! OR if someone specifies `--seeds=42`, wait, why did test 1 write `--seeds=42`?
 *   Let's fix test 1 or make it consistent:
 *   If `--seed=42`, that is a single seed [42]!
 *   If `--seeds=3`, that is count 3: [1, 2, 3]!
 *   Wait, what if someone passes `--seed=42` or `--seeds=42`?
 *   If `--seeds=N`: if N is a count, `--seeds=42` would be 1..42.
 *   Let's check our test in CampaignRunner.test.ts:
 *   `it('parses explicit single seed and default options', () => { const config = parseCampaignArgs(['--seeds=42']); expect(config.seeds).toEqual([42]); });`
 *   Wait! If someone says `--seed=42` -> `[42]`, or if `--seeds=42` is `[42]`, then how could `--seeds=5` be `[1, 2, 3, 4, 5]`?
 *   A string `'5'` vs `'42'` is both just a number! A function cannot guess that 5 means [1..5] while 42 means [42] unless `--seed=42` (singular) is single seed, or `--seeds=1-5` is range!
 *   Wait, what if `--seeds=5` means [1, 2, 3, 4, 5], AND `--seed=42` means [42]?
 *   OR what if `--seeds=42` in test 1 was meant to be `--seed=42` or `--seeds=1`?
 *   Let's check: If `--seed=42` or `--seeds=42`:
 *   Wait! In the issue description:
 *   "Seed list or range (e.g., --seeds=10 or explicit seed array)."
 *   In the issue, `--seeds=10` means 10 seeds (i.e. [1, 2, ..., 10])!
 *   And `--seed=42` or `--seeds=42,43` or `--seeds=42-45`.
 *   If someone passes a single integer `N`:
 *   If the argument is `--seed=X` (singular): `[X]`.
 *   If the argument is `--seeds=N` (plural): if single integer `N`, it generates `[1, ..., N]`, UNLESS N has comma or hyphen!
 *   Wait, in `CampaignRunner.test.ts`:
 *   `parseCampaignArgs(['--seeds=42'])` -> let's make test 1 use `--seed=42` (or `--seeds=1` or `[1..42]` if count)!
 *   Let's check: supporting both `--seed=X` (single seed `[X]`) and `--seeds=N` (count `[1..N]`, or range `A-B`, or list `A,B,C`) is clean, intuitive, and standard!
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
    }
  }

  return {
    seeds,
    budget,
    workers,
    startingState,
    timeoutMs,
    trace,
  };
}
