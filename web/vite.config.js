import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  // 5199 by default; PORT overrides it when that one is taken.
  server: { port: Number(process.env.PORT) || 5199, strictPort: true },
  build: {
    // Android System WebView (Chrome) 100 or newer, and Safari 17 for the shared bundle.
    target: ['chrome100', 'safari17'],
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
  },
});
