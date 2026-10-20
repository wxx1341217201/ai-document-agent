import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import liveConfig from './playwright.live.config';

const credentialFile = process.env.PLAYWRIGHT_PUBLIC_CREDENTIALS_FILE;
const credentials = credentialFile
  ? (JSON.parse(readFileSync(credentialFile, 'utf8').replace(/^\uFEFF/, '')) as {
      username: string;
      password: string;
    })
  : undefined;
if (credentials && (!credentials.username || !credentials.password)) {
  throw new Error('Public deployment credentials are incomplete.');
}

/** Uses the built Nginx site. Never starts Vite or intercepts backend requests. */
export default defineConfig({
  ...liveConfig,
  testMatch: [
    '**/deployment.spec.ts',
    '**/advanced-tools.spec.ts',
    '**/document-content.spec.ts',
    '**/live-workflow.spec.ts',
  ],
  timeout: 300_000,
  outputDir: 'test-results/deployment',
  reporter: [['list'], ['html', { outputFolder: 'playwright-report/deployment', open: 'never' }]],
  use: {
    ...liveConfig.use,
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:8088',
    httpCredentials: credentials
      ? { username: credentials.username, password: credentials.password }
      : undefined,
  },
  webServer: undefined,
});
