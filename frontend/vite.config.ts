import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendTarget = env.VITE_DEV_BACKEND_URL || 'http://localhost:8080';
  const progressToken = process.env.PROGRESS_SUBSCRIPTION_TOKEN;

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
        '/ws': {
          target: backendTarget,
          // Spring's default same-origin check needs the browser-facing Host/Origin pair.
          changeOrigin: false,
          ws: true,
          // Native browser WebSocket cannot set an Authorization handshake header.
          // This optional deployment token is read by the local dev server only.
          headers: progressToken ? { Authorization: `Bearer ${progressToken}` } : undefined,
        },
      },
    },
    test: {
      environment: 'jsdom',
      environmentMatchGlobs: [
        ['**/features/reading/*.test.tsx', './src/test/reading-environment.ts'],
      ],
      exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: true,
      coverage: {
        reporter: ['text', 'html'],
      },
    },
  };
});
