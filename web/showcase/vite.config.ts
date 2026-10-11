import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  const apiTarget =
    env.SHOWCASE_DEV_API_TARGET || 'http://localhost:8001';

  return {
    plugins: [react(), tailwindcss()],
    server: {
      proxy: {
        '/api': {
          target: apiTarget,
        },
        '/media': {
          target: apiTarget,
        },
        '/releases': {
          target: apiTarget,
      },
      },
    },
  };
});
