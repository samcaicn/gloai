import { defineConfig } from "vite-plus";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    allowedHosts: true,
    proxy: {
      "/api": {
        target: "http://localhost:9800",
        changeOrigin: true,
        ws: true,
      },
      // App frontends are reverse-proxied by the Hub backend and served as
      // sub-paths of the main site (e.g. /apps/ai-transformation-cockpit).
      "/apps": {
        target: "http://localhost:9800",
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: {
    outDir: "../internal/web/dist",
    emptyOutDir: true,
  },
});
