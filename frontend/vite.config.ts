import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // Force IPv4 — localhost can resolve to ::1 and hang against the JVM listener
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
})
