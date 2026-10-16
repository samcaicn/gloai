/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Preview 模块统一导出
 * Preview module unified exports
 *
 * 这是一个独立的、可复用的文档预览模块
 * This is an independent, reusable document preview module
 *
 * 只导出轻量部分（context / 类型 / hooks / 常量 / 工具）。
 * 组件（PreviewPanel、各 viewer/editor）刻意不走本 barrel：本 barrel 被窗口外壳
 * （Layout/WorkPanel/SendBox…）与 PreviewContext 静态引用，一旦 re-export 组件，
 * rollup 会因 MermaidBlock→本 barrel 的循环把整个 viewer/editor 树
 * （语法高亮、KaTeX、CodeMirror legacy 语言模式，数 MB）保留进渲染器入口 chunk。
 * 组件请从各自模块直导，例如：
 *   import PreviewPanel from '@/renderer/pages/conversation/Preview/components/PreviewPanel/PreviewPanel';
 *
 * @example
 * ```typescript
 * // 使用Context
 * import { PreviewProvider, usePreviewContext } from '@/renderer/pages/conversation/Preview';
 *
 * // 使用类型
 * import type { PreviewContentType } from '@/renderer/pages/conversation/Preview';
 * ```
 */

// Context
export * from './context';

// Types
export type * from './types';

// Hooks
export * from './hooks';

// Constants
export * from './constants';

// Utils
export * from './fileUtils';
