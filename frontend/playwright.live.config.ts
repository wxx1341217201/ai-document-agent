import { defineConfig, devices } from '@playwright/test';

/** Explicit live backend suite: no MSW, REST routes, WebSocket routes, or model mocks. */
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/live-workflow.spec.ts',
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  outputDir: 'test-results/live',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/live', open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: 'http://127.0.0.1:5175',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --mode live --host 127.0.0.1 --port 5175 --strictPort',
    url: 'http://127.0.0.1:5175',
    env: { VITE_USE_MSW: 'false' },
    reuseExistingServer: false,
  },
});
