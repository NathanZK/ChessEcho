// @ts-check
/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
const config = {
  packageManager: 'npm',
  reporters: ['html', 'clear-text', 'progress', 'json'],
  testRunner: 'vitest',
  coverageAnalysis: 'perTest',
  mutate: [
    'src/utils/urlUtils.ts',
    'src/utils/chessGame.ts',
    'src/utils/positionImport.ts',
  ],
  thresholds: {
    high: 80,
    low: 60,
    break: null,
  },
  cleanTempDir: true,
};

export default config;
