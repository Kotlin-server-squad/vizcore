import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import { TanStackRouterVite } from '@tanstack/router-vite-plugin'
import path, { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    TanStackRouterVite(),
    react()
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@vizcor/api-types': resolve(__dirname, '../shared/api-types'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        // Default 8080; override with VITE_PROXY_TARGET when the backend runs elsewhere
        // (e.g. VITE_PROXY_TARGET=http://localhost:8090 pnpm dev when 8080 is taken).
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

