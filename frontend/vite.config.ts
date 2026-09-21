import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendTarget = env.VITE_DEV_BACKEND_URL || 'http://localhost:8080';

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
          changeOrigin: true,
          ws: true,
        },
      },
    },
    test: {
      environment: 'jsdom',
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
