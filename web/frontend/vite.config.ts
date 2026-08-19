import path from "path"

import tailwindcss from "@tailwindcss/vite"
import { tanstackRouter } from "@tanstack/router-plugin/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// https://www.tuptup.top
export default defineConfig({
  plugins: [
    tanstackRouter({
      target: "react",
      autoCodeSplitting: true,
    }),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    chunkSizeWarningLimit: 2048,
  },
  server: {
    proxy: {
      "/api": {
        target: "https://www.tuptup.top",
        changeOrigin: true,
      },
      "/pico/media": {
        target: "https://www.tuptup.top",
        changeOrigin: true,
      },
      "/pico/ws": {
        target: "https://www.tuptup.top",
        ws: true,
      },
    },
  },
})
