// 升级相关 Tauri 命令封装。
// 后端静默升级通过 system 命令暴露：
//   - 检查状态：check_silent_upgrade（返回 UpgradeStatus）
//   - 触发升级（含下载）：trigger_silent_upgrade_now
//   - 安装已下载的待应用升级：install_pending_upgrade_now
// 本桥接将 upgrade_* 命名映射到上述实际命令。
import { invoke } from './invoke';

export interface UpgradeInfo {
  version: string;
  url: string;
  notes?: string;
  available: boolean;
}

// 映射到 check_silent_upgrade：返回 UpgradeStatus（原 upgrade_check 期望 UpgradeInfo）。
export async function upgradeCheck(): Promise<UpgradeInfo> {
  return invoke<UpgradeInfo>('check_silent_upgrade');
}

// 映射到 trigger_silent_upgrade_now：触发静默升级流程（规划+下载+就绪）。
export async function upgradeDownload(): Promise<string> {
  return invoke<string>('trigger_silent_upgrade_now');
}

// 映射到 install_pending_upgrade_now：安装已下载的待应用升级。
export async function upgradeApply(): Promise<void> {
  return invoke<void>('install_pending_upgrade_now');
}
