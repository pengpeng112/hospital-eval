import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:48080', changeOrigin: true },
      '/kkview': { target: 'http://localhost:18012', changeOrigin: true, rewrite: p => p.replace(/^\/kkview/, '') }
    }
  },
  build: { chunkSizeWarningLimit: 3000 }
})
