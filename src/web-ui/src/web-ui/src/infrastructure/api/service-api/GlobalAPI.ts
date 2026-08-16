 

import { api } from './ApiClient';
import { createTauriCommandError } from '../errors/TauriCommandError';

/**
 * 后端 src-tauri/src/commands/legacy.rs 中的 Workspace 结构只有
 *   { id, name, path, icon }
 * 字段，但前端 WorkspaceInfo 期望 rootPath / workspaceType / workspaceKind 等
 * 完整字段。adaptLegacyWorkspace 把后端简单结构适配为前端期望的 WorkspaceInfo。
 *
 * 命令名对应关系：
 *   前端调用                          → 后端实际命令
 *   get_current_workspace             → get_current_workspace  (已匹配)
 *   setActiveWorkspace (set_active_workspace)  → set_workspace
 *   getRecentWorkspaces (get_recent_workspaces) → get_workspaces
 *   getOpenedWorkspaces (get_opened_workspaces) → get_workspaces
 *   openWorkspace (open_workspace)              → create_workspace + set_workspace
 *   其他无对应后端命令的方法静默 resolve
 */
interface LegacyWorkspace {
  id: string;
  name: string;
  path: string;
  icon?: string;
}

interface LegacyWorkspaceSwitchResult {
  workspace: LegacyWorkspace;
  gateway_restarted: boolean;
}

function adaptLegacyWorkspace(w: LegacyWorkspace): WorkspaceInfo {
  const nowIso = new Date().toISOString();
  return {
    id: w.id,
    name: w.name,
    rootPath: w.path,
    workspaceType: 'singleProject',
    workspaceKind: 'normal',
    languages: [],
    openedAt: nowIso,
    lastAccessed: nowIso,
    tags: [],
    sshHost: 'localhost',
  };
}

export interface ApplicationState {
  status: AppStatus;
  workspace?: WorkspaceInfo;
  version: string;
  uptime: number;
}

export interface AppStatus {
  isInitialized: boolean;
  hasError: boolean;
  errorMessage?: string;
}

export interface ProjectStatistics {
  totalFiles: number;
  totalLines: number;
  totalSize: number;
  filesByLanguage: Record<string, number>;
  filesByExtension: Record<string, number>;
  lastUpdated: string;
}

export interface WorkspaceIdentity {
  name?: string | null;
  creature?: string | null;
  vibe?: string | null;
  emoji?: string | null;
}

export interface WorkspaceWorktreeInfo {
  path: string;
  branch?: string | null;
  mainRepoPath: string;
  isMain: boolean;
}

export interface RelatedPath {
  path: string;
  description?: string | null;
}

export interface WorkspaceInfo {
  id: string;
  name: string;
  rootPath: string;
  workspaceType: string;
  workspaceKind: string;
  assistantId?: string | null;
  languages: string[];
  openedAt: string;
  lastAccessed: string;
  description?: string | null;
  tags: string[];
  statistics?: ProjectStatistics | null;
  identity?: WorkspaceIdentity | null;
  worktree?: WorkspaceWorktreeInfo | null;
  relatedPaths?: RelatedPath[];
  connectionId?: string;
  connectionName?: string;
  /** With `rootPath`, forms logical key `{sshHost}:{rootPath}`; local uses `localhost`. */
  sshHost?: string;
}

export interface UpdateAppStatusRequest {
  status: AppStatus;
}

export interface OpenWorkspaceRequest {
  path: string;
}

export interface OpenRemoteWorkspaceRequest {
  remotePath: string;
  connectionId: string;
  connectionName: string;
  /** Passed through to Rust so session files map to ~/.bitfun/remote_ssh/{host}/... before/during connect. */
  sshHost?: string;
}

export type CreateAssistantWorkspaceRequest = Record<string, never>;

export interface CloseWorkspaceRequest {
  workspaceId: string;
}

export interface SetActiveWorkspaceRequest {
  workspaceId: string;
}

export interface ReorderOpenedWorkspacesRequest {
  workspaceIds: string[];
}

export interface UpdateWorkspaceInfoRequest {
  workspaceId: string;
  name?: string;
  description?: string | null;
  tags?: string[];
  relatedPaths?: RelatedPath[];
}

export interface DeleteAssistantWorkspaceRequest {
  workspaceId: string;
}

export interface ResetAssistantWorkspaceRequest {
  workspaceId: string;
}

export interface ScanWorkspaceInfoRequest {
  workspacePath: string;
}

interface SessionWorkspaceUpdateResultRaw {
  session_id: string;
  old_workspace_path?: string | null;
  new_workspace_path: string;
  workspace_registered: boolean;
  moved_files?: number;
  moved_dirs?: number;
}

export interface SessionWorkspaceUpdateResult {
  sessionId: string;
  oldWorkspacePath: string | null;
  newWorkspacePath: string;
  workspaceRegistered: boolean;
  movedFiles: number;
  movedDirs: number;
}

export class GlobalAPI {

  /**
   * 后端无 initialize_global_state 命令；workspaceManager.initialize 会调用它
   * 作为启动握手。这里静默返回空字符串让初始化流程继续，避免 emit
   * workspace:error 导致 currentWorkspace 被置 null、UI 工作区按钮显示空白。
   */
  async initializeGlobalState(): Promise<string> {
    return '';
  }

  /**
   * 后端无 get_app_state 命令；返回最小可用 ApplicationState 让上层
   * 不依赖该字段时也能工作。
   */
  async getAppState(): Promise<ApplicationState> {
    return {
      status: { isInitialized: true, hasError: false },
      version: '0.0.0',
      uptime: 0,
    };
  }

  /**
   * 后端无 update_app_status 命令；静默 resolve。
   */
  async updateAppStatus(_status: AppStatus): Promise<void> {
    return;
  }


  /**
   * 后端无 open_workspace 命令；用 create_workspace + set_workspace 模拟。
   * 若目录已存在对应 workspace 记录则直接复用，避免重复创建。
   */
  async openWorkspace(path: string): Promise<WorkspaceInfo> {
    try {
      const trimmed = path.trim();
      if (!trimmed) {
        throw new Error('openWorkspace: path is required');
      }
      const name = trimmed.split(/[\\/]/).filter(Boolean).pop() ?? trimmed;
      let workspace: LegacyWorkspace;
      try {
        workspace = await api.invoke<LegacyWorkspace>('create_workspace', {
          name,
          path: trimmed,
          icon: '📁',
        });
      } catch (_createErr) {
        // 已存在则查找并复用
        const list = await api.invoke<LegacyWorkspace[]>('get_workspaces', {});
        const found = list.find(item => item.path === trimmed);
        if (!found) {
          throw _createErr;
        }
        workspace = found;
      }
      const result = await api.invoke<LegacyWorkspaceSwitchResult>('set_workspace', {
        workspaceId: workspace.id,
      });
      return adaptLegacyWorkspace(result.workspace);
    } catch (error) {
      throw createTauriCommandError('open_workspace', error, { path });
    }
  }

  /**
   * 后端无 open_remote_workspace 命令；当前阶段不支持远程工作区，
   * 调用方拿到 rejection 后自行降级提示。
   */
  async openRemoteWorkspace(
    _remotePath: string,
    _connectionId: string,
    _connectionName: string,
    _sshHost?: string
  ): Promise<WorkspaceInfo> {
    throw createTauriCommandError(
      'open_remote_workspace',
      new Error('Remote workspace is not supported by the backend'),
    );
  }

  /**
   * 后端无 create_assistant_workspace 命令；当前阶段不支持，
   * 抛错让调用方降级。
   */
  async createAssistantWorkspace(): Promise<WorkspaceInfo> {
    throw createTauriCommandError(
      'create_assistant_workspace',
      new Error('Assistant workspace is not supported by the backend'),
    );
  }

  /**
   * 后端无 close_workspace 命令；本项目采用 launch_new_instance 模式，
   * 工作区切换通过 set_workspace 完成，关闭即关闭进程，前端无需后端操作。
   */
  async closeWorkspace(_workspaceId: string): Promise<void> {
    return;
  }

  /**
   * 后端对应命令为 set_workspace（无 _active 前缀），
   * 参数 workspaceId 通过 Tauri 默认 camelCase → snake_case 映射到 Rust 端 workspace_id。
   */
  async setActiveWorkspace(workspaceId: string): Promise<WorkspaceInfo> {
    try {
      const result = await api.invoke<LegacyWorkspaceSwitchResult>('set_workspace', {
        workspaceId,
      });
      return adaptLegacyWorkspace(result.workspace);
    } catch (error) {
      throw createTauriCommandError('set_workspace', error, { workspaceId });
    }
  }

  /**
   * 后端无 reorder_opened_workspaces 命令；工作区顺序由 create_workspace
   * 顺序决定。静默 resolve，让拖拽 UI 不抛错。
   */
  async reorderOpenedWorkspaces(_workspaceIds: string[]): Promise<void> {
    return;
  }

  /**
   * 后端无 update_workspace_info 命令；用 update_workspace 替代（仅支持 name/path/icon）。
   * 其他字段（description/tags/relatedPaths）会被忽略。
   */
  async updateWorkspaceInfo(request: UpdateWorkspaceInfoRequest): Promise<WorkspaceInfo> {
    try {
      const updated = await api.invoke<LegacyWorkspace>('update_workspace', {
        workspaceId: request.workspaceId,
        name: request.name ?? '',
        path: '',
        icon: '📁',
      });
      return adaptLegacyWorkspace(updated);
    } catch (error) {
      throw createTauriCommandError('update_workspace', error, { request });
    }
  }

  /**
   * 后端无 delete_assistant_workspace 命令；用 delete_workspace 替代。
   */
  async deleteAssistantWorkspace(workspaceId: string): Promise<void> {
    try {
      await api.invoke('delete_workspace', { workspaceId });
    } catch (error) {
      throw createTauriCommandError('delete_workspace', error, { workspaceId });
    }
  }

  /**
   * 后端无 reset_assistant_workspace 命令；当前阶段不支持，抛错让调用方降级。
   */
  async resetAssistantWorkspace(_workspaceId: string): Promise<WorkspaceInfo> {
    throw createTauriCommandError(
      'reset_assistant_workspace',
      new Error('Reset assistant workspace is not supported by the backend'),
    );
  }


  // In-flight deduplicator: if many components call getCurrentWorkspace at the
  // same time (e.g. 20+ Markdown blocks mounting after a workspace switch) only
  // one Tauri IPC round-trip is made; all callers share the same Promise.
  private _getCurrentWorkspaceInFlight: Promise<WorkspaceInfo | null> | null = null;

  /**
   * 后端 get_current_workspace 在 cfg.workspace_path 不匹配任何工作区时
   * 返回 "Workspace not found" 错误；这里 catch 并返回 null，让上层视为
   * "无当前工作区"。
   */
  async getCurrentWorkspace(): Promise<WorkspaceInfo | null> {
    if (this._getCurrentWorkspaceInFlight) {
      return this._getCurrentWorkspaceInFlight;
    }
    this._getCurrentWorkspaceInFlight = (async () => {
      try {
        const legacy = await api.invoke<LegacyWorkspace | null>('get_current_workspace', {});
        return legacy ? adaptLegacyWorkspace(legacy) : null;
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        if (msg.includes('Workspace not found') || msg.includes('not found')) {
          return null;
        }
        throw createTauriCommandError('get_current_workspace', error);
      } finally {
        this._getCurrentWorkspaceInFlight = null;
      }
    })();
    return this._getCurrentWorkspaceInFlight;
  }

  /**
   * 后端无 get_recent_workspaces 命令，复用 get_workspaces 返回的列表。
   * recentWorkspaces 与 openedWorkspaces 在后端共享同一份数据（无区分）。
   */
  async getRecentWorkspaces(): Promise<WorkspaceInfo[]> {
    try {
      const list = await api.invoke<LegacyWorkspace[]>('get_workspaces', {});
      return (list ?? []).map(adaptLegacyWorkspace);
    } catch (error) {
      throw createTauriCommandError('get_workspaces', error);
    }
  }

  /**
   * 后端无 remove_recent_workspace 命令；当前阶段工作区记录由
   * create_workspace / delete_workspace 管理，recent 列表与 opened 共享。
   * 静默 resolve 让调用方正常完成，不阻塞 UI 流程。
   */
  async removeRecentWorkspace(_workspaceId: string): Promise<void> {
    return;
  }

  /**
   * 后端无 cleanup_invalid_workspaces 命令；本项目工作区目录由用户主动
   * 创建，invalid 检测由文件系统访问失败时自然报错。
   */
  async cleanupInvalidWorkspaces(): Promise<number> {
    return 0;
  }

  /**
   * 后端无 get_opened_workspaces 命令，复用 get_workspaces 返回的列表。
   */
  async getOpenedWorkspaces(): Promise<WorkspaceInfo[]> {
    try {
      const list = await api.invoke<LegacyWorkspace[]>('get_workspaces', {});
      return (list ?? []).map(adaptLegacyWorkspace);
    } catch (error) {
      throw createTauriCommandError('get_workspaces', error);
    }
  }

  /**
   * 后端无 scan_workspace_info 命令；返回当前工作区信息作为降级值，
   * 让调用方拿到非 null 结果继续工作。忽略 workspacePath 参数。
   */
  async scanWorkspaceInfo(_workspacePath: string): Promise<WorkspaceInfo | null> {
    return this.getCurrentWorkspace();
  }

  /**
   * 列出所有已注册工作区（供"选择工作区"对话框使用）。
   * 复用 get_workspaces 命令，与 getRecentWorkspaces 同源。
   */
  async listAllWorkspaces(): Promise<WorkspaceInfo[]> {
    try {
      const list = await api.invoke<LegacyWorkspace[]>('get_workspaces', {});
      return (list ?? []).map(adaptLegacyWorkspace);
    } catch (error) {
      throw createTauriCommandError('get_workspaces', error);
    }
  }

  /**
   * 更新单个会话的默认工作区位置（含可选数据迁移）。
   * 对应后端 update_session_workspace 命令。
   */
  async updateSessionWorkspace(
    sessionId: string,
    newWorkspacePath: string,
    moveData: boolean = true,
  ): Promise<SessionWorkspaceUpdateResult> {
    try {
      const raw = await api.invoke<SessionWorkspaceUpdateResultRaw>(
        'update_session_workspace',
        {
          sessionId,
          newWorkspacePath,
          moveData,
        },
      );
      return {
        sessionId: raw.session_id,
        oldWorkspacePath: raw.old_workspace_path ?? null,
        newWorkspacePath: raw.new_workspace_path,
        workspaceRegistered: raw.workspace_registered,
        movedFiles: Number(raw.moved_files ?? 0),
        movedDirs: Number(raw.moved_dirs ?? 0),
      };
    } catch (error) {
      throw createTauriCommandError('update_session_workspace', error, {
        sessionId,
        newWorkspacePath,
        moveData,
      });
    }
  }

  async getCurrentWorkspacePath(): Promise<string | undefined> {
    try {
      const workspace = await this.getCurrentWorkspace();
      return workspace?.rootPath;
    } catch (error) {
      throw createTauriCommandError('get_current_workspace', error);
    }
  }
}


export const globalAPI = new GlobalAPI();
