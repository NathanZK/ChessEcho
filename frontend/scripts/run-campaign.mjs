import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const argv = process.argv.slice(2);

function parseSeedArgument(arg) {
  const trimmed = arg.trim();
  if (trimmed.includes(',')) {
    return trimmed.split(',').map((s) => Number(s.trim()));
  }
  if (trimmed.includes('-')) {
    const [start, end] = trimmed.split('-').map((s) => Number(s.trim()));
    const seeds = [];
    for (let i = start; i <= end; i++) seeds.push(i);
    return seeds;
  }
  const count = Number(trimmed);
  const seeds = [];
  for (let i = 1; i <= count; i++) seeds.push(i);
  return seeds;
}

let seeds = [1, 2, 3];
let budget = 20;
let workers = 1;
let startingState = 'DISCOVERY';
let timeoutMs = 30000;
let trace = 'failures-only';

for (const arg of argv) {
  if (arg.startsWith('--seed=')) {
    seeds = [Number(arg.slice('--seed='.length))];
  } else if (arg.startsWith('--seeds=')) {
    seeds = parseSeedArgument(arg.slice('--seeds='.length));
  } else if (arg.startsWith('--budget=')) {
    budget = Number(arg.slice('--budget='.length));
  } else if (arg.startsWith('--workers=')) {
    workers = Number(arg.slice('--workers='.length));
  } else if (arg.startsWith('--startingState=')) {
    startingState = arg.slice('--startingState='.length);
  } else if (arg.startsWith('--timeout=')) {
    timeoutMs = Number(arg.slice('--timeout='.length));
  } else if (arg.startsWith('--trace=')) {
    trace = arg.slice('--trace='.length);
  }
}

const config = {
  seeds,
  budget,
  workers,
  startingState,
  timeoutMs,
  trace,
};

const metricsDir = 'test-results/campaign-metrics';
try {
  fs.rmSync(metricsDir, { recursive: true, force: true });
  fs.mkdirSync(metricsDir, { recursive: true });
} catch {
  // Directory initialization
}

const env = {
  ...process.env,
  PLAYWRIGHT_INCLUDE_MONKEY: '1',
  MONKEY_CAMPAIGN_CONFIG: JSON.stringify(config),
};

const playwrightArgs = [
  'playwright',
  'test',
  'journeys/campaign-monkey.spec.ts',
  `--workers=${workers}`,
  `--timeout=${timeoutMs}`,
];

console.log(
  `Executing Exploration Campaign: ${seeds.length} seeds (budget: ${budget}, workers: ${workers}, trace: ${trace})...`
);

const child = spawn('npx', playwrightArgs, {
  stdio: 'inherit',
  env,
});

child.on('exit', (code) => {
  if (fs.existsSync(metricsDir)) {
    const files = fs.readdirSync(metricsDir).filter((f) => f.endsWith('.json'));
    if (files.length > 0) {
      const metrics = files.map((file) => {
        const raw = fs.readFileSync(path.join(metricsDir, file), 'utf-8');
        return JSON.parse(raw);
      });

      let passedRuns = 0;
      let failedRuns = 0;
      let totalActions = 0;
      let totalDurationMs = 0;
      const statesSet = new Set();
      const transitionsSet = new Set();
      const failedSeeds = [];
      const replayCommands = [];

      for (const m of metrics) {
        if (m.success) {
          passedRuns++;
        } else {
          failedRuns++;
          failedSeeds.push(m.seed);
          replayCommands.push(
            `npm run test:monkey:campaign -- --seed=${m.seed} --budget=${budget} --startingState=${startingState} --workers=1`
          );
        }
        totalActions += m.executedSteps || 0;
        totalDurationMs += m.durationMs || 0;

        if (Array.isArray(m.uniqueStates)) {
          for (const s of m.uniqueStates) statesSet.add(s);
        }
        if (Array.isArray(m.transitions)) {
          for (const t of m.transitions) transitionsSet.add(t);
        }
      }

      const actionsPerSecond =
        totalDurationMs > 0 ? Number(((totalActions / totalDurationMs) * 1000).toFixed(2)) : 0;

      const lines = [
        '==================================================',
        '          Exploration Campaign Summary            ',
        '==================================================',
        `Total Runs:          ${metrics.length}`,
        `Passed Runs:         ${passedRuns}`,
        `Failed Runs:         ${failedRuns}`,
        `Total Actions:       ${totalActions}`,
        `Total Duration:      ${(totalDurationMs / 1000).toFixed(2)}s`,
        `Action Throughput:   ${actionsPerSecond} actions/sec`,
        `States Visited:      ${Array.from(statesSet).join(', ') || 'None'}`,
        `Transitions Count:   ${transitionsSet.size}`,
      ];

      if (failedSeeds.length > 0) {
        lines.push('--------------------------------------------------');
        lines.push(`Failed Seeds (${failedSeeds.length}): ${failedSeeds.join(', ')}`);
        lines.push('Replay Commands:');
        for (const cmd of replayCommands) {
          lines.push(`  ${cmd}`);
        }
      }

      lines.push('==================================================');
      console.log('\n' + lines.join('\n') + '\n');
    }
  }

  process.exit(code ?? 0);
});
