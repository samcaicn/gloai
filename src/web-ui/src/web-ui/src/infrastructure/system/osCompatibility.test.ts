// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  checkOsCompatibility,
  openOsPermissionPanel,
  type OsCompatibilityReport,
} from './osCompatibility';

// osCompatibility.ts 不直接调用 isTauriRuntime —— 它委托给 invoke 包装层,
// invoke 内部在非 Tauri 环境返回 undefined。因此这里只需 mock invoke 即可
// 覆盖三条路径: 成功返回数据 / 返回 undefined (非 Tauri) / 抛错。
const invokeMock = vi.hoisted(() => vi.fn());

vi.mock('@/infrastructure/api/tupai/invoke', () => ({
  invoke: (...args: unknown[]) => invokeMock(...args),
}));

const SAMPLE_REPORT: OsCompatibilityReport = {
  macosAccessibilityGranted: false,
  windowsOcrAvailable: true,
  windowsOcrLanguages: ['en-US', 'zh-CN'],
  osVersion: 'macOS 14.5',
};

beforeEach(() => {
  invokeMock.mockReset();
});

describe('checkOsCompatibility', () => {
  it('returns the report with camelCase fields when invoke resolves', async () => {
    invokeMock.mockResolvedValue(SAMPLE_REPORT);

    const result = await checkOsCompatibility();

    expect(result).toEqual(SAMPLE_REPORT);
    expect(result?.macosAccessibilityGranted).toBe(false);
    expect(result?.windowsOcrAvailable).toBe(true);
    expect(result?.windowsOcrLanguages).toEqual(['en-US', 'zh-CN']);
    expect(result?.osVersion).toBe('macOS 14.5');
    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('check_os_compatibility');
  });

  it('returns null when invoke yields undefined (non-Tauri guard path)', async () => {
    // 真实 invoke 包装层在非 Tauri 环境 (jsdom dev) 返回 undefined;
    // checkOsCompatibility 通过 `result ?? null` 归一为 null, 横幅不渲染。
    invokeMock.mockResolvedValue(undefined);

    const result = await checkOsCompatibility();

    expect(result).toBeNull();
  });

  it('returns null and does not throw when invoke rejects', async () => {
    // 兼容性检查失败不应阻塞启动或闪退 —— 内部 catch 后返回 null。
    invokeMock.mockRejectedValue(new Error('ipc boom'));

    const result = await checkOsCompatibility();

    expect(result).toBeNull();
  });
});

describe('openOsPermissionPanel', () => {
  it('calls invoke with the command and target for macos-accessibility', async () => {
    invokeMock.mockResolvedValue(undefined);

    await openOsPermissionPanel('macos-accessibility');

    expect(invokeMock).toHaveBeenCalledTimes(1);
    expect(invokeMock).toHaveBeenCalledWith('open_os_permission_panel', {
      target: 'macos-accessibility',
    });
  });

  it('calls invoke with the command and target for windows-ocr', async () => {
    invokeMock.mockResolvedValue(undefined);

    await openOsPermissionPanel('windows-ocr');

    expect(invokeMock).toHaveBeenCalledWith('open_os_permission_panel', {
      target: 'windows-ocr',
    });
  });

  it('swallows errors and does not throw when invoke rejects', async () => {
    // 横幅按钮点击失败不应弹未捕获异常。
    invokeMock.mockRejectedValue(new Error('spawn failed'));

    await expect(openOsPermissionPanel('macos-accessibility')).resolves.toBeUndefined();
  });
});
