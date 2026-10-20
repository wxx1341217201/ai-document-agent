import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  outputDir: 'test-results/mock',
  reporter: [['html', { outputFolder: 'playwright-report/mock', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      testIgnore: [
        '**/chat.spec.ts',
        '**/progress.spec.ts',
        '**/agent-panel.spec.ts',
        '**/workflow.spec.ts',
        '**/live-workflow.spec.ts',
        '**/deployment.spec.ts',
      ],
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chat',
      testMatch: [
        '**/chat.spec.ts',
        '**/progress.spec.ts',
        '**/agent-panel.spec.ts',
        '**/workflow.spec.ts',
      ],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://127.0.0.1:5174',
        serviceWorkers: 'block',
      },
    },
  ],
  webServer: [
    {
      command: 'npm run dev -- --mode e2e --host 127.0.0.1 --port 5173 --strictPort',
      url: 'http://127.0.0.1:5173',
      reuseExistingServer: !process.env.CI,
    },
    {
      command: 'npm run dev -- --mode e2e --host 127.0.0.1 --port 5174 --strictPort',
      url: 'http://127.0.0.1:5174',
      env: { VITE_USE_MSW: 'false' },
      reuseExistingServer: !process.env.CI,
    },
  ],
});
