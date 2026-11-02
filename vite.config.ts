import { defineConfig } from "vite";

// Tauri 2 前端构建配置：dev 时由 `tauri dev` 拉起 vite，prod 产出到 dist。
export default defineConfig({
  // 禁止 vite 清屏，避免冲掉 Tauri 的日志
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    target: "esnext",
    outDir: "dist",
    // Tauri 使用原生 webview，相对路径即可
    base: "./",
  },
});
