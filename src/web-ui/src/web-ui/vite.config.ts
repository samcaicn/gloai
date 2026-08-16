import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "path";
import { versionInjectionPlugin } from "./vite.config.version-plugin";

const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(({ mode, command }) => {
  const isProduction = mode === 'production' || (command === 'build' && mode !== 'development');
  
  return {
    plugins: [
      react(),
      versionInjectionPlugin()
    ],

    // ── Windows WebView2 compatibility ──────────────────────────────────
    // Tauri's WebView2 (Edge/Chromium) loads assets from http://tauri.localhost.
    // The default base '/' works, but we set it explicitly for clarity and to
    // guard against future Vite defaults changing.
    base: '/',

    // Replace process.env.NODE_ENV at build time. Vite normally does this
    // automatically, but some dynamically-imported chunks or third-party
    // libraries may reference `process.env` directly, causing a
    // ReferenceError in the WebView2 environment where `process` is
    // undefined. The define below ensures every occurrence is replaced
    // with a string literal, eliminating the crash.
    define: {
      'process.env.NODE_ENV': JSON.stringify(isProduction ? 'production' : 'development'),
      'process.env': JSON.stringify({ NODE_ENV: isProduction ? 'production' : 'development' }),
    },

    // Path resolution
    resolve: {
      dedupe: ['react', 'react-dom'],
      alias: {
        "@": path.resolve(__dirname, "./src"),
        "@/shared": path.resolve(__dirname, "./src/shared"),
        "@/core": path.resolve(__dirname, "./src/core"),
        "@/tools": path.resolve(__dirname, "./src/tools"),
        "@/hooks": path.resolve(__dirname, "./src/hooks"),
        "@/styles": path.resolve(__dirname, "./src/component-library/styles"),
        "@/types": path.resolve(__dirname, "./src/shared/types"),
        "@/utils": path.resolve(__dirname, "./src/shared/utils"),
        "@components": path.resolve(__dirname, "./src/component-library/components"),
      },
    },

  css: {
    preprocessorOptions: {
      scss: {
        // SCSS preprocessing options (sourcemap is controlled by build.sourcemap)
      },
    },
    // dev mode enabled, release mode disabled
    devSourcemap: !isProduction,
  },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 5173,
    // Tauri devUrl is fixed to http://localhost:5173.
    // If Vite silently falls back to another port, the desktop webview stays blank.
    strictPort: true,
    host: host || "localhost",
    hmr: {
      protocol: "ws",
      host: host || "localhost",
      port: 5174,
    },
    // Allow access to workspace root for dependencies like monaco-editor
    fs: {
      allow: [
        path.resolve(__dirname, '../../'), // Workspace root
      ],
    },
    watch: {
      // 3. tell Vite to ignore watching `src-tauri` and `apps`
      ignored: ["**/src-tauri/**", "**/apps/**"],
      // Increase polling interval for stability (especially on Windows)
      usePolling: true,
      interval: 100,
    },
  },

  // Optimize dependency pre-building
  optimizeDeps: {
    // Exclude dependencies that need to be dynamically loaded
    exclude: [],
    // Force pre-building dependencies
    // Resolve Vite 7 and React 18 compatibility issues
    include: [
      'react',
      'react-dom',
      'react-dom/client',
      'react/jsx-runtime',
      'react/jsx-dev-runtime',
      'mermaid',
      'mermaid/dist/mermaid.esm.min.mjs',
    ],
  },

  // Build options
  build: {
    // Enable CSS code splitting
    cssCodeSplit: true,
    // release version disable sourcemap, dev/debug version enable
    sourcemap: !isProduction,
    // Output to the tupai project root dist/ (4 levels up from src/web-ui/src/web-ui/)
    outDir: '../../../../dist',
    // Empty the output directory
    emptyOutDir: true,
    // Disable gzip size reporting to avoid OOM on low-memory machines
    reportCompressedSize: false,

    // ── Rollup chunking strategy ──────────────────────────────────────
    // Without manualChunks, Vite produces a single giant vendor chunk
    // (~4 MB) that must be downloaded and parsed before anything renders.
    // On slow Windows machines with HDDs, this causes a long white-screen
    // pause. Splitting into smaller, focused chunks allows the browser
    // to parse them in parallel and, critically, lets the critical
    // (React + app shell) chunk fail independently of heavy optional
    // libraries like mermaid/monaco.
    //
    // CRITICAL: React's internal module state (React.Children, hooks, etc.)
    // uses a singleton pattern — there must be exactly ONE React instance
    // in the entire bundle. If react and react-dom end up in different
    // chunks than react-i18next / @xyflow/react / @tiptap/react / etc.,
    // those libraries import a *different* React object that has never
    // been initialized, causing:
    //   "Cannot set properties of undefined (setting 'Children')"
    //
    // Rule: ALL React ecosystem packages (react, react-dom, react-*,
    // @*/react) MUST share the same chunk so they resolve to the same
    // module instance. Only truly independent libraries (mermaid, monaco,
    // xterm, katex, highlight.js) can be split into separate chunks.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            // ── Heavy, standalone libraries ─────────────────────────
            // These have ZERO dependency on React and can be safely
            // isolated. If these chunks fail to load, the app still
            // renders (the relevant scene shows a Suspense fallback).
            if (id.includes('mermaid')) return 'vendor-mermaid';
            if (id.includes('monaco-editor') || id.includes('@monaco-editor')) return 'vendor-monaco';
            if (id.includes('@xterm') || id.includes('/xterm')) return 'vendor-xterm';
            if (id.includes('katex')) return 'vendor-katex';
            if (id.includes('highlight.js') || id.includes('prismjs')) return 'vendor-highlight';

            // ── React core (must share one chunk) ──────────────────
            // ONLY react, react-dom, and scheduler go here. Other
            // React-ecosystem packages (@tiptap/react, @xyflow/react,
            // lucide-react, react-i18next, i18next) must fall through
            // to the general 'vendor' chunk to avoid a CIRCULAR ESM
            // dependency between vendor and vendor-react chunks.
            //
            // Circular dependency symptom: the shared vendor chunk
            // imports React from vendor-react, while vendor-react
            // imports utilities from vendor. If vendor-react pulls
            // in packages that vendor needs (e.g. zustand from
            // @tiptap/react), a circular ESM import chain forms,
            // causing "Cannot access uninitialized variable" TDZ
            // errors at startup.
            if (
              id.includes('node_modules/react/') ||
              id.includes('node_modules/react-dom/') ||
              id.includes('node_modules/scheduler/')
            ) {
              return 'vendor-react';
            }

            // Everything else goes into a general vendor chunk.
            return 'vendor';
          }
        },
      },
    },
  }
  };
});
