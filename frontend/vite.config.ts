import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteCommonjs } from '@originjs/vite-plugin-commonjs'
import { nodePolyfills } from 'vite-plugin-node-polyfills'

export default defineConfig({
  plugins: [
    react(),
    // dicom-parser ships as CJS; required for Cornerstone DICOM loader under Vite
    viteCommonjs(),
    // @kitware/vtk.js → xmlbuilder2 extends Node's EventEmitter; also needs buffer/process/stream
    nodePolyfills({
      include: ['buffer', 'process', 'stream', 'events', 'util', 'path', 'string_decoder'],
      globals: {
        Buffer: true,
        global: true,
        process: true,
      },
      protocolImports: true,
    }),
  ],
  assetsInclude: ['**/*.wasm'],
  optimizeDeps: {
    exclude: ['@cornerstonejs/dicom-image-loader'],
    include: ['dicom-parser'],
  },
  worker: {
    format: 'es',
  },
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
