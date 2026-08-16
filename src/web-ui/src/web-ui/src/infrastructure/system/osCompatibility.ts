// OS 兼容性检查相关 Tauri 命令封装。
// 命令名已对齐后端 commands/system.rs：
//   checkOsCompatibility    → check_os_compatibility   () -> OsCompatibilityReport
//   openOsPermissionPanel   → open_os_permission_panel (target: String)
//
// 设计原则：
//   - 非 Tauri 环境（pnpm dev / jsdom 测试）下静默返回 null / no-op，避免报错。
//   - 复用 @/infrastructure/api/tupai/invoke 的 isTauriRuntime 守卫，不重复造轮子。
//   - UI 组件不直接 invoke，统一经此模块（遵守 AGENTS.md「走 infrastructure 层」）。
import { invoke } from '@/infrastructure/api/tupai/invoke';
import { createLogger } from '@/shared/utils/logger';

const log = createLogger('osCompatibility');

/** check_os_compatibility 命令返回结果（camelCase, 与后端 serde rename_all 对齐） */
export interface OsCompatibilityReport {
  /** macOS Accessibility 是否已授权（非 macOS 平台为 true） */
  macosAccessibilityGranted: boolean;
  /** Windows 是否至少装了一个 OCR 语言包（非 Windows 平台为 true） */
  windowsOcrAvailable: boolean;
  /** 已装的 Windows OCR 语言标签（BCP-47, 如 "en-US"）；非 Windows 或未装为空 */
  windowsOcrLanguages: string[];
  /** 检测到的 OS 显示字符串，如 "Windows 11 Pro 23H2 (build 22631)" / "macOS 14.5" */
  osVersion: string;
}

/** open_os_permission_panel 的目标参数 */
export type OsPermissionTarget = 'macos-accessibility' | 'windows-ocr';

/**
 * 检查 OS 兼容性（macOS Accessibility + Windows OCR + OS 版本字符串）。
 * 首次启动 + 用户从系统设置返回后调用。非 Tauri 环境返回 null。
 *
 * 内部 catch 所有错误：兼容性检查失败不应阻塞启动或闪退，仅返回 null 让
 * 前端跳过横幅渲染。
 */
export async function checkOsCompatibility(): Promise<OsCompatibilityReport | null> {
  try {
    const result = await invoke<OsCompatibilityReport>('check_os_compatibility');
    return result ?? null;
  } catch (error) {
    log.warn('check_os_compatibility 调用失败', error);
    return null;
  }
}

/**
 * 打开平台权限/设置 UI（macOS 系统设置 - 辅助功能 / Windows 设置 - 区域语言）。
 * 用户点击横幅「前往系统设置」按钮时调用。非 Tauri 环境为 no-op。
 */
export async function openOsPermissionPanel(target: OsPermissionTarget): Promise<void> {
  try {
    await invoke('open_os_permission_panel', { target });
  } catch (error) {
    log.warn('open_os_permission_panel 调用失败', { target, error });
  }
}
