import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the Vite dev server proxies the REST API and the WebSocket to Spring Boot on :8080.
// `npm run build` writes the bundle into the Spring Boot static resources, so one JAR serves both.
// DATAGOCAR_API=http://localhost:8081 points the proxy at another backend.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};
const api = env.DATAGOCAR_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': api,
      '/ws': { target: api.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
});
