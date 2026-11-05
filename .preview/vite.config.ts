import { defineConfig } from "vite";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "..");
const MOCK = resolve(HERE, "mock-tauri.ts");

// 仅本地 UI 走查用：把 Tauri API 换成本地打桩，其余与生产 vite.config.ts 完全一致。
// 不需要装 Rust / 启动 Tauri，普通浏览器就能走查 5 个页签：
//   pnpm exec vite --config .preview/vite.config.ts
//   浏览器打开 http://localhost:5176/
// 注意：mock 只返回演示数据，不能用于验证真实后端链路；生产构建用根 vite.config.ts。
// 若浏览器走了系统代理导致 127.0.0.1 资源 404，改用 localhost 主机名访问。
export default defineConfig({
  root: ROOT,
  clearScreen: false,
  resolve: {
    alias: [
      { find: /^@tauri-apps\/api\/core$/, replacement: MOCK },
      { find: /^@tauri-apps\/api\/event$/, replacement: MOCK },
    ],
  },
  server: { port: 5176, strictPort: true },
  build: { target: "esnext", outDir: "dist-preview", base: "./" },
});
