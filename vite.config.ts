import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  base: process.env.NODE_ENV === 'development' ? '/' : './',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src/renderer'),
      crypto: 'crypto-js',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true,
        drop_debugger: true,
      },
      mangle: {
        toplevel: true,
        keep_classnames: false,
        keep_fnames: false,
      },
    },
  },
  server: {
    port: 1427,
    strictPort: true,
    host: true,
    fs: {
      allow: ['..', './SKILLs'],
    },
  },
  clearScreen: false,
}); 
