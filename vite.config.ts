import { defineConfig } from "vite";

// Tauri 2 前端构建配置：dev 时由 `tauri dev` 拉起 vite，prod 产出到 dist。
export default defineConfig({
  // 禁止 vite 清屏，避免冲掉 Tauri 的日志
  clearScreen: false,
  server: {
    // 注意：本机 5173 常被豆包(Doubao)等桌面应用的 sandbox runtime 占用，
    // strictPort=true 会导致 tauri dev 的 vite 直接启动失败、整个应用起不来。
    // 故改到 5174 规避端口冲突（与 tauri.conf.json 的 devUrl 保持一致）。
    port: 5174,
    strictPort: true,
  },
  build: {
    target: "esnext",
    outDir: "dist",
    // Tauri 使用原生 webview，相对路径即可
    base: "./",
  },
});
