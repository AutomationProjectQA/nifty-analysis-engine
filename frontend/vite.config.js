import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // sockjs-client references `global`, which the browser doesn't define.
  define: {
    global: 'globalThis',
  },
  server: {
    port: 3000
  }
})
