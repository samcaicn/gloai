/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** tupai 应用版本号（构建期注入，缺省回退硬编码 '1.8.9'） */
  readonly VITE_APP_VERSION?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}