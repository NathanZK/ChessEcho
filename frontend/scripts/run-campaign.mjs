import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { parseCampaignArgs } from '../journeys/model/campaignConfig.ts';
import {
  aggregateCampaignMetrics,
  formatCampaignSummaryTable,
} from '../journeys/model/campaignAggregator.ts';

const config = parseCampaignArgs(process.argv.slice(2));

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
  `--workers=${config.workers}`,
  `--timeout=${config.timeoutMs}`,
];

console.log(
  `Executing Exploration Campaign: ${config.seeds.length} seeds (budget: ${config.budget}, workers: ${config.workers}, trace: ${config.trace})...`
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

      const runs = metrics.map((m) => ({
        ...m,
        uniqueStates: new Set(m.uniqueStates || []),
        transitions: new Set(m.transitions || []),
        stateActions: new Set(m.stateActions || []),
        transitionPairs: new Set(m.transitionPairs || []),
        configurationTransitions: new Set(m.configurationTransitions || []),
        error: m.error ? new Error(m.error) : undefined,
      }));

      const summary = aggregateCampaignMetrics(
        runs,
        config.budget,
        config.startingState,
        config.strategy
      );
      console.log('\n' + formatCampaignSummaryTable(summary) + '\n');
    }
  }

  process.exit(code ?? 0);
});
