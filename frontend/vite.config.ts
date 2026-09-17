import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, type ProxyOptions } from 'vite';

const BACKEND_URL = process.env.GONDOLIA_BACKEND_URL ?? 'http://localhost:8080';
const BACKEND_ORIGIN = new URL(BACKEND_URL).origin;

/**
 * El navegador manda `Origin: http://<ip-o-host>:5173`. Si se abre el dev server desde otra máquina (celular en la
 * LAN, `127.0.0.1`) ese origen no está en el CORS del backend y rechaza POST/PUT y el handshake de `/ws`.
 * Para el backend las requests del proxy son del mismo origen.
 */
const sameOriginAsBackend: ProxyOptions['configure'] = (proxyServer) => {
  proxyServer.on('proxyReq', (proxyReq) => {
    if (proxyReq.getHeader('origin')) proxyReq.setHeader('origin', BACKEND_ORIGIN);
  });
  proxyServer.on('proxyReqWs', (proxyReq) => {
    if (proxyReq.getHeader('origin')) proxyReq.setHeader('origin', BACKEND_ORIGIN);
  });
};

const proxy: Record<string, ProxyOptions> = {
  '/api': { target: BACKEND_URL, changeOrigin: true, configure: sameOriginAsBackend },
  '/ws': { target: BACKEND_URL.replace(/^http/, 'ws'), ws: true, changeOrigin: true, configure: sameOriginAsBackend },
};

const CHART_PACKAGES =
  /[\/]node_modules[\/](recharts|recharts-scale|react-smooth|victory-vendor|d3-[^\/]+|internmap|decimal\.js-light|lodash)[\/]/;
const SCANNER_PACKAGES = /[\/]node_modules[\/]@zxing[\/]/;
const REACT_PACKAGES = /[\/]node_modules[\/](react|react-dom|scheduler|react-router|react-router-dom|@remix-run)[\/]/;

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    strictPort: true,
    proxy,
  },
  preview: {
    host: true,
    port: 4173,
    proxy,
  },
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          if (CHART_PACKAGES.test(id)) return 'charts';
          if (SCANNER_PACKAGES.test(id)) return 'scanner';
          if (REACT_PACKAGES.test(id)) return 'react-vendor';
          return undefined;
        },
      },
    },
  },
});
