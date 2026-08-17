/**
 * MainNav — simplified sidebar navigation (old-style layout).
 *
 * Layout (top to bottom):
 *   1. Brand header: tenant first tag (top-left) + workspace folder icon
 *      （原工作区按钮显示 workspace name + path，已缩小为只展示文件夹图标）
 *   2. Skills section (always visible, search + cached skill list)
 *   3. Automation section (Automation + Flowchart entries, no folding)
 *
 * All other sections (sessions, assistant, agents, extensions expand) are hidden.
 */

import React, { useCallback, useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { FolderOpen, FolderPlus, History, Check, Workflow, Clock, MessageSquare, X, Tag, IterationCw } from 'lucide-react';
import { Tooltip } from '@/component-library';
import { useSceneManager } from '../../hooks/useSceneManager';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import type { SceneTabId } from '../SceneBar/types';
import { isTauriRuntime, openExternalUrl } from '@/infrastructure/runtime';
import { workspaceManager } from '@/infrastructure/services/business/workspaceManager';
import { useWorkspaceContext } from '@/infrastructure/contexts/WorkspaceContext';
import { createLogger } from '@/shared/utils/logger';
import { isRemoteWorkspace, WorkspaceInfo } from '@/shared/types';
import { getRecentWorkspaceLineParts } from '@/shared/utils/recentWorkspaceDisplay';
import { computeFixedPopoverPosition } from '@/shared/utils/fixedPopoverViewport';
import { useSSHRemoteContext, SSHConnectionDialog, RemoteFileBrowser } from '@/features/ssh-remote';
import {
  getCachedRecordingResult,
  skillExecute,
  tenantInfo,
  reportSkillFailure,
  reportSkillSuccess,
} from '@/infrastructure/api/tupai';
import type { TeachingStopResult, TenantInfo } from '@/infrastructure/api/tupai';
import { getBrandInfo } from '@/infrastructure/api/tupai/brand';
import { notificationService } from '@/shared/notification-system';
import SkillsNavSection from './sections/skills/SkillsNavSection';
import PluginMarketNavSection from './sections/plugin-market/PluginMarketNavSection';
import AutoskillNavSection from './sections/autoskill/AutoskillNavSection';
import './NavPanel.scss';

const log = createLogger('MainNav');

/** 租户 tag 跳转链接的兜底地址。
 *  当 MCP `tenant.get` 未返回 website / 解析失败 / 非 http(s) 协议时，
 *  用公司官网作 fallback —— 避免 tag 退化为不可点的纯文本。
 *  集中放在一处，方便后续替换为公司主域。 */
const FALLBACK_TENANT_WEBSITE = 'https://www.safeopc.cn';

interface MainNavProps {
  isDeparting?: boolean;
  anchorNavSceneId?: SceneTabId | null;
}

const MainNav: React.FC<MainNavProps> = ({
  isDeparting: _isDeparting = false,
  anchorNavSceneId: _anchorNavSceneId = null,
}) => {
  const sshRemote = useSSHRemoteContext();
  const [isSSHConnectionDialogOpen, setIsSSHConnectionDialogOpen] = useState(false);

  useEffect(() => {
    if (sshRemote.showFileBrowser) {
      setIsSSHConnectionDialogOpen(false);
    }
  }, [sshRemote.showFileBrowser]);

  const { openScene, openTabs, activeTabId, activateScene, closeScene, tabDefs } = useSceneManager();
  const { t } = useI18n('common');
  const {
    currentWorkspace,
    recentWorkspaces,
    openedWorkspacesList,
    switchWorkspace,
  } = useWorkspaceContext();

  // ── Workspace menu ──────────────────────────────
  const workspaceMenuButtonRef = useRef<HTMLButtonElement | null>(null);
  const workspaceMenuRef = useRef<HTMLDivElement | null>(null);
  const [workspaceMenuOpen, setWorkspaceMenuOpen] = useState(false);
  const [workspaceMenuClosing, setWorkspaceMenuClosing] = useState(false);
  const [workspaceMenuPos, setWorkspaceMenuPos] = useState({ top: 0, left: 0 });

  // ── Tenant tag (top-left, 来自 MCP tenant.get) ───
  // 品牌名优先级：MCP tenant.logoText / tags[0] / name → 本地 BrandInfo.publisher → i18n 占位文案
  // 网址优先级：MCP tenant.websiteUrl / website → 本地 BrandInfo.homepage → FALLBACK_TENANT_WEBSITE
  // tenantWebsiteFromMcp 区分「真实 MCP 配置」vs「本地/兜底」，用于 tooltip 文案。
  //
  // 修复点：
  //   1. 传 device_token 给 tenant_info（MCP 鉴权必需）
  //   2. 加重试机制（指数退避 3 次：3s / 6s / 12s）
  //   3. 加载中状态 + pulse 动效
  //   4. 监听 token 刷新事件，token 变了立即重试
  //   5. MCP 拉不到时用本地 BrandInfo 兜底（编译期注入的 publisher / homepage）
  const [tenantFirstTag, setTenantFirstTag] = useState<string | null>(null);
  const [tenantWebsite, setTenantWebsite] = useState<string | null>(null);
  const [tenantWebsiteFromMcp, setTenantWebsiteFromMcp] = useState(false);
  const [tenantLoading, setTenantLoading] = useState(true);
  const tenantRetryRef = useRef(0);
  const tenantMaxRetry = 3;
  // 本地品牌兜底（编译期注入，启动时读一次）—— MCP 拿不到时用
  const localBrandRef = useRef<{ name: string; site: string }>({ name: '', site: '' });

  const loadTenantInfo = useCallback(async (retryCount?: number) => {
    const attempt = retryCount ?? 0;
    const { name: localName, site: localSite } = localBrandRef.current;
    // 读取 device token（MCP 鉴权必需）
    let deviceToken: string | null = null;
    try {
      deviceToken = typeof localStorage !== 'undefined' ? localStorage.getItem('trae_device_token') : null;
    } catch { /* ignore */ }
    try {
      const info: TenantInfo = await tenantInfo(deviceToken ?? undefined);
      // MCP 品牌名：优先 logoText（服务器配置），回退 tags[0]，再回退 name
      const logoText = typeof info?.logoText === 'string' ? info.logoText.trim() : '';
      const mcpName = logoText || info?.tags?.[0] || info?.name || '';
      // MCP 网址：优先 websiteUrl（新格式），回退 website（旧格式）
      const mcpSite = (typeof info?.websiteUrl === 'string' ? info.websiteUrl.trim() : '')
        || (typeof info?.website === 'string' ? info.website.trim() : '');
      // 合并：MCP 优先，本地 BrandInfo 兜底
      const finalName = (typeof mcpName === 'string' && mcpName.trim()) ? mcpName.trim() : localName;
      setTenantFirstTag(finalName || null);
      if (mcpSite) {
        setTenantWebsite(mcpSite);
        setTenantWebsiteFromMcp(true);
      } else if (localSite) {
        setTenantWebsite(localSite);
        setTenantWebsiteFromMcp(false);
      } else {
        setTenantWebsite(null);
        setTenantWebsiteFromMcp(false);
      }
      setTenantLoading(false);
      tenantRetryRef.current = 0; // 成功则重置重试计数
    } catch (err) {
      log.warn('tenantInfo fetch failed', { error: err, attempt });
      // 指数退避重试：3s / 6s / 12s
      if (attempt < tenantMaxRetry) {
        const delay = 3000 * Math.pow(2, attempt);
        window.setTimeout(() => {
          void loadTenantInfo(attempt + 1);
        }, delay);
      } else {
        // 重试耗尽，用本地 BrandInfo 兜底；本地也没有则显示占位文案
        setTenantFirstTag(localName || null);
        if (localSite) {
          setTenantWebsite(localSite);
          setTenantWebsiteFromMcp(false);
        } else {
          setTenantWebsite(null);
          setTenantWebsiteFromMcp(false);
        }
        setTenantLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    let disposed = false;
    // 先读本地 BrandInfo（编译期注入的 publisher / homepage），再触发 MCP 拉取
    // 本地数据存 ref，供 loadTenantInfo 在 MCP 失败时兜底使用
    void (async () => {
      let localName = '';
      let localSite = '';
      try {
        const localBrand = await getBrandInfo();
        localName = localBrand?.publisher || '';
        localSite = localBrand?.homepage || '';
      } catch (err) {
        log.warn('getBrandInfo failed', err);
      }
      if (disposed) return;
      localBrandRef.current = { name: localName, site: localSite };
      void loadTenantInfo();
    })();
    // 监听 token 刷新事件 —— token 变了立即重试加载租户信息
    const onTokenChanged = () => {
      setTenantLoading(true);
      tenantRetryRef.current = 0;
      void loadTenantInfo();
    };
    window.addEventListener('tupai:device-token-changed', onTokenChanged);
    return () => {
      disposed = true;
      window.removeEventListener('tupai:device-token-changed', onTokenChanged);
    };
  }, [loadTenantInfo]);

  // Combine recent workspaces and opened workspaces, de-duplicating by id
  const allWorkspaces = React.useMemo(() => {
    const workspaceMap = new Map<string, WorkspaceInfo>();
    recentWorkspaces.forEach(ws => workspaceMap.set(ws.id, ws));
    openedWorkspacesList.forEach(ws => {
      if (!workspaceMap.has(ws.id)) {
        workspaceMap.set(ws.id, ws);
      }
    });
    return Array.from(workspaceMap.values());
  }, [recentWorkspaces, openedWorkspacesList]);

  const closeWorkspaceMenu = useCallback(() => {
    setWorkspaceMenuClosing(true);
    window.setTimeout(() => {
      setWorkspaceMenuOpen(false);
      setWorkspaceMenuClosing(false);
    }, 150);
  }, []);

  const updateWorkspaceMenuPos = useCallback(() => {
    const btn = workspaceMenuButtonRef.current;
    if (!btn || !workspaceMenuOpen) return;
    const rect = btn.getBoundingClientRect();
    const viewportPadding = 8;
    const gap = 6;
    const fallbackWidth = 300;
    const fallbackHeight = 420;

    const apply = () => {
      const menuEl = workspaceMenuRef.current;
      const w = menuEl?.offsetWidth ?? fallbackWidth;
      const h = menuEl?.offsetHeight ?? fallbackHeight;
      setWorkspaceMenuPos(computeFixedPopoverPosition(rect, w, h, gap, viewportPadding));
    };

    apply();
    requestAnimationFrame(apply);
  }, [workspaceMenuOpen]);

  const openWorkspaceMenu = useCallback(async () => {
    try {
      await workspaceManager.cleanupInvalidWorkspaces();
    } catch (error) {
      log.warn('Failed to cleanup invalid workspaces before opening workspace menu', { error });
    }
    const rect = workspaceMenuButtonRef.current?.getBoundingClientRect();
    if (!rect) return;
    setWorkspaceMenuPos(computeFixedPopoverPosition(rect, 300, 420, 6, 8));
    setWorkspaceMenuOpen(true);
    setWorkspaceMenuClosing(false);
  }, []);

  const toggleWorkspaceMenu = useCallback(() => {
    if (workspaceMenuOpen) { closeWorkspaceMenu(); return; }
    void openWorkspaceMenu();
  }, [closeWorkspaceMenu, openWorkspaceMenu, workspaceMenuOpen]);

  const handleOpenProject = useCallback(async () => {
    try {
      const { open } = await import('@tauri-apps/plugin-dialog');
      const selected = await open({ directory: true, multiple: false, title: t('header.selectProjectDirectory') });
      if (selected && typeof selected === 'string') {
        await workspaceManager.openWorkspace(selected);
      }
    } catch (err) {
      log.error('Failed to open project', err);
    }
  }, [t]);

  const handleNewProject = useCallback(() => {
    window.dispatchEvent(new Event('nav:new-project'));
  }, []);

  const handleSwitchWorkspace = useCallback(async (workspaceId: string) => {
    const targetWorkspace = allWorkspaces.find(item => item.id === workspaceId);
    if (!targetWorkspace) return;
    closeWorkspaceMenu();
    await switchWorkspace(targetWorkspace);
  }, [closeWorkspaceMenu, allWorkspaces, switchWorkspace]);

  const handleOpenRemoteSSH = useCallback(() => {
    closeWorkspaceMenu();
    setIsSSHConnectionDialogOpen(true);
  }, [closeWorkspaceMenu]);

  const handleSelectRemoteWorkspace = useCallback(async (path: string) => {
    try {
      await sshRemote.openWorkspace(path);
      sshRemote.setShowFileBrowser(false);
      setIsSSHConnectionDialogOpen(false);
    } catch (err) {
      log.error('Failed to open remote workspace', err);
    }
  }, [sshRemote]);

  useEffect(() => {
    if (!workspaceMenuOpen) return;
    const handleClickOutside = (event: MouseEvent) => {
      const target = event.target as Node | null;
      if (!target) return;
      if (workspaceMenuButtonRef.current?.contains(target)) return;
      if (workspaceMenuRef.current?.contains(target)) return;
      closeWorkspaceMenu();
    };
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeWorkspaceMenu();
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [closeWorkspaceMenu, workspaceMenuOpen]);

  useEffect(() => {
    if (!workspaceMenuOpen) return;
    updateWorkspaceMenuPos();
    const handleViewportChange = () => updateWorkspaceMenuPos();
    window.addEventListener('resize', handleViewportChange);
    window.addEventListener('scroll', handleViewportChange, true);
    return () => {
      window.removeEventListener('resize', handleViewportChange);
      window.removeEventListener('scroll', handleViewportChange, true);
    };
  }, [workspaceMenuOpen, updateWorkspaceMenuPos]);

  // ── Automation entries ──────────────────────────
  const handleOpenAutomation = useCallback(() => {
    openScene('automation', t('scenes.automation'));
  }, [openScene, t]);

  // ── 定时任务入口 ──
  const handleOpenTasks = useCallback(() => {
    openScene('tasks');
  }, [openScene]);

  // ── Pipelines 入口 ──
  const handleOpenPipelines = useCallback(() => {
    openScene('pipelines', t('scenes.pipelines'));
  }, [openScene, t]);

  // handleOpenRecording: 保留供 Tauri 事件监听跳转流程图使用（侧栏「最近录制」区域已移除，
  // 但 session:finish-recording 事件仍需跳转）
  const handleOpenRecording = useCallback((appName: string) => {
    // sessionStorage 兜底:FlowchartScene 首次挂载时读取(visitedTabs 机制下
    // 未访问过的 tab 不挂载,dispatchEvent 会丢失,sessionStorage 是唯一通道)。
    try {
      sessionStorage.setItem('tupai:flowchart:selectedApp', appName);
    } catch { /* ignore */ }
    // 先 openScene 触发 FlowchartScene 挂载/激活,再在下一 tick 派发事件,
    // 确保监听器已注册。已挂载场景下 setTimeout(0) 也能正常派发。
    openScene('flowchart');
    window.setTimeout(() => {
      window.dispatchEvent(new CustomEvent('tupai:flowchart:selectApp', { detail: { appName } }));
    }, 0);
  }, [openScene]);

  // 监听 Tauri 事件 `session:finish-recording`：录制/执行浮窗调用
  // `fw_finish_session` 后由后端 emit，本端负责跳转到流程图并加载节点。
  // 修复前该事件无任何前端订阅，导致主窗口被拉回前台后用户仍停留在
  // 之前的场景，看不到刚刚录制的流程图。
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    let disposed = false;
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ appName?: string }>('session:finish-recording', (event) => {
          const appName = event.payload?.appName;
          if (!appName || disposed) return;
          log.info('Recording session finished, navigating to flowchart', { appName });
          handleOpenRecording(appName);
        }),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch((err) => {
        if (!disposed) {
          log.warn('Failed to listen for session:finish-recording', err);
        }
      });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [handleOpenRecording]);

  // 录制完成通知：toast + 立即执行 / 查看流程图 两个 action。
  // 必须在监听 useEffect 之前定义，否则闭包拿到的是旧实例。
  const showRecordingCompletedNotification = useCallback(
    (appName: string | null, result: TeachingStopResult) => {
      const title = t('floatingWindow.recordingCompletedTitle');
      const baseMessage =
        result.stepCount > 0
          ? t('floatingWindow.recordingCompleted', { count: result.stepCount })
          : t('floatingWindow.recordingStoppedEmpty');
      const runNowLabel = t('floatingWindow.runNow');
      const viewFlowchartLabel = t('floatingWindow.viewFlowchart');
      const runNowStarting = t('floatingWindow.runNowStarting');
      const runNowFailedTpl = t('floatingWindow.runNowFailed');

      const actions: Array<{ label: string; onClick: () => void; variant?: 'primary' | 'secondary' | 'danger' }> = [];

      const hasMcp = !!result.mcpBlobBase64;
      actions.push({
        label: runNowLabel,
        variant: 'primary',
        onClick: () => {
          // 优先用本通知载荷里的 mcpBlob；缓存里若有更新的也兜底用一次。
          const cached = getCachedRecordingResult();
          const blob = result.mcpBlobBase64 || cached?.result?.mcpBlobBase64;
          if (!blob) {
            notificationService.error(runNowFailedTpl.replace('{{error}}', 'mcp blob missing'));
            return;
          }
          notificationService.info(runNowStarting, { duration: 2500 });
          const startedAt = performance.now();
          // 此处 skillExecute 第一参数是 mcpBlobBase64（录制宏二进制），非真实 skill_id，
          // 上报时用稳定哨兵 'recording-replay' 作为 skill_id，避免巨长 base64 污染上报数据。
          const REPORT_SKILL_ID = 'recording-replay';
          void skillExecute(blob, {})
            .then((output) => {
              if (output?.success === false) {
                notificationService.error(
                  runNowFailedTpl.replace('{{error}}', output?.error || 'unknown'),
                );
                // 静默上报逻辑失败
                reportSkillFailure(REPORT_SKILL_ID, output?.error || 'unknown');
              } else if (output?.output) {
                notificationService.success(output.output, { duration: 5000 });
                // 静默上报执行成功
                reportSkillSuccess(REPORT_SKILL_ID, output.output, performance.now() - startedAt);
              }
            })
            .catch((err) => {
              const msg = err?.message || String(err);
              log.error('Run now failed', err);
              notificationService.error(runNowFailedTpl.replace('{{error}}', msg));
              // 静默上报执行失败
              reportSkillFailure(REPORT_SKILL_ID, msg);
            });
        },
      });
      // mcpBlob 缺失时禁用 Run now：把 label 替换为不点击的 secondary 占位。
      if (!hasMcp) {
        actions[0] = { ...actions[0], label: `${runNowLabel} (unavailable)` };
        // 通过 onClick 提前返回仍然可达，但显式 noop 让用户感知。
        const original = actions[0].onClick;
        actions[0].onClick = () => {
          notificationService.warning(t('floatingWindow.recordingStoppedEmpty'));
          void original();
        };
      }

      if (appName) {
        actions.push({
          label: viewFlowchartLabel,
          variant: 'secondary',
          onClick: () => {
            handleOpenRecording(appName);
          },
        });
      }

      notificationService.success(baseMessage, {
        title,
        duration: 8000,
        actions,
      });
    },
    [t, handleOpenRecording],
  );

  // 监听 Tauri 事件 `recording:stopped`：后端 stop_recording 完成时 emit，
  // 载荷包含完整 TeachingStopResult（mcp_blob + flowchart + step_count）。
  // 这里负责在主窗口弹"录制完成"通知，提供「立即执行」和「查看流程图」两个动作。
  // 此前所有调用方都把后端编译好的 mcp_blob 丢弃掉，本订阅让录制产物被真正消费。
  // 依赖：showRecordingCompletedNotification（t 语言变化会重注册一次，开销可忽略）。
  useEffect(() => {
    if (!isTauriRuntime()) {
      return;
    }
    let disposed = false;
    let unlisten: (() => void) | null = null;
    void import('@tauri-apps/api/event')
      .then(({ listen }) =>
        listen<{ appName?: string | null; result: TeachingStopResult }>(
          'recording:stopped',
          (event) => {
            if (disposed) return;
            const appName = event.payload?.appName ?? null;
            const result = event.payload?.result;
            if (!result) {
              log.warn('recording:stopped event missing result payload');
              return;
            }
            log.info('Recording stopped, showing completion notification', {
              appName,
              stepCount: result.stepCount,
            });
            showRecordingCompletedNotification(appName, result);
          },
        ),
      )
      .then((removeListener) => {
        if (disposed) {
          removeListener();
          return;
        }
        unlisten = removeListener;
      })
      .catch((err) => {
        if (!disposed) {
          log.warn('Failed to listen for recording:stopped', err);
        }
      });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [showRecordingCompletedNotification]);

  const isAutomationActive = activeTabId === 'automation';
  const isTasksActive = activeTabId === 'tasks';
  const isPipelinesActive = activeTabId === 'pipelines';
  const automationTooltip = t('scenes.automation');
  const tasksTooltip = t('scenes.tasks');
  const pipelinesTooltip = t('scenes.pipelines');

  const workspaceMenuPortal = workspaceMenuOpen ? createPortal(
    <div
      ref={workspaceMenuRef}
      className={`bitfun-nav-panel__workspace-menu${workspaceMenuClosing ? ' is-closing' : ''}`}
      role="menu"
      style={{ top: workspaceMenuPos.top, left: workspaceMenuPos.left }}
    >
      <button
        type="button"
        className="bitfun-nav-panel__workspace-menu-item"
        role="menuitem"
        onClick={() => { closeWorkspaceMenu(); void handleOpenProject(); }}
      >
        <FolderOpen size={13} />
        <span>{t('header.openProject')}</span>
      </button>
      <button
        type="button"
        className="bitfun-nav-panel__workspace-menu-item"
        role="menuitem"
        onClick={() => { closeWorkspaceMenu(); handleNewProject(); }}
      >
        <FolderPlus size={13} />
        <span>{t('header.newProject')}</span>
      </button>
      <button
        type="button"
        className="bitfun-nav-panel__workspace-menu-item"
        role="menuitem"
        onClick={handleOpenRemoteSSH}
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
          <path d="M9 3H5a2 2 0 0 0-2 2v4m6-6h10a2 2 0 0 1 2 2v4M9 3v18m0 0h10a2 2 0 0 0 2-2v-4M9 21H5a2 2 0 0 1-2-2v-4m0-6v6" />
        </svg>
        <span>{t('ssh.remote.connect')}</span>
      </button>
      <div className="bitfun-nav-panel__workspace-menu-divider" role="separator" />
      <div className="bitfun-nav-panel__workspace-menu-section-title">
        <History size={12} aria-hidden="true" />
        <span>{t('header.recentWorkspaces')}</span>
      </div>
      {allWorkspaces.length === 0 ? (
        <div className="bitfun-nav-panel__workspace-menu-empty">
          <span>{t('header.noRecentWorkspaces')}</span>
        </div>
      ) : (
        <div className="bitfun-nav-panel__workspace-menu-workspaces">
          {allWorkspaces.map((workspace) => {
            const { hostPrefix, folderLabel, tooltip } = getRecentWorkspaceLineParts(workspace);
            return (
            <button
              key={workspace.id}
              type="button"
              className="bitfun-nav-panel__workspace-menu-item bitfun-nav-panel__workspace-menu-item--workspace"
              role="menuitem"
              title={tooltip}
              onClick={() => { void handleSwitchWorkspace(workspace.id); }}
            >
              <FolderOpen size={13} aria-hidden="true" />
              <span className="bitfun-nav-panel__workspace-menu-item-main">
                {hostPrefix ? (
                  <>
                    <span className="bitfun-nav-panel__workspace-menu-item-host">{hostPrefix}</span>
                    <span className="bitfun-nav-panel__workspace-menu-item-host-sep" aria-hidden>
                      ·
                    </span>
                  </>
                ) : null}
                <span className="bitfun-nav-panel__workspace-menu-item-name">{folderLabel}</span>
              </span>
              {workspace.id === currentWorkspace?.id ? <Check size={12} aria-hidden="true" /> : null}
            </button>
            );
          })}
        </div>
      )}
    </div>,
    document.body
  ) : null;

  return (
    <>
      {/* ── Top-left: tenant tag (MCP) + workspace folder icon ──
        租户第 1 个 tag 单独放最左；工作区设置区域缩小为单文件夹图标。
        tag 文字带飘动动画（无下划线）；始终是外链：
        跳转目标 = MCP website ?? 公司官网兜底 (FALLBACK_TENANT_WEBSITE)。 */}
      <div className="bitfun-nav-panel__brand-header">
        <Tooltip
          content={tenantWebsiteFromMcp
            ? t('navBar.tenantTagTooltipWithLink', { url: tenantWebsite })
            : t('navBar.tenantTagTooltipWithFallback', { url: FALLBACK_TENANT_WEBSITE })}
          placement="right"
          followCursor
        >
          <button
            className={`bitfun-nav-panel__tenant-tag bitfun-nav-panel__tenant-tag--linked${tenantLoading ? ' is-loading' : ''}`}
            data-testid="nav-panel-tenant-tag"
            data-website-source={tenantWebsiteFromMcp ? 'mcp' : 'fallback'}
            onClick={(e) => {
              e.stopPropagation();
              void openExternalUrl(tenantWebsite ?? FALLBACK_TENANT_WEBSITE);
            }}
            disabled={tenantLoading}
          >
            <Tag size={11} aria-hidden="true" className="bitfun-nav-panel__tenant-tag-icon" />
            <span className="bitfun-nav-panel__tenant-tag-text">
              {tenantLoading ? '···' : (tenantFirstTag || t('navBar.tenantTagFallback'))}
            </span>
          </button>
        </Tooltip>
        <Tooltip content={t('navBar.workspaceTooltip')} placement="right" followCursor disabled={workspaceMenuOpen}>
          <button
            ref={workspaceMenuButtonRef}
            type="button"
            className={`bitfun-nav-panel__workspace-trigger bitfun-nav-panel__workspace-trigger--icon-only${workspaceMenuOpen ? ' is-active' : ''}`}
            onClick={toggleWorkspaceMenu}
            aria-label={t('navBar.workspaceTooltip')}
            aria-expanded={workspaceMenuOpen}
          >
            <FolderOpen size={15} aria-hidden="true" />
          </button>
        </Tooltip>
      </div>

      {/* ── Skills section (always visible, no outer folding) ─── */}
      <SkillsNavSection isOpen={true} onToggle={() => {}} />

      {/* ── Plugin Market section (everything is a plugin) ─── */}
      <PluginMarketNavSection />

      {/* ── Autoskill entry (auto-suggestions with pending badge) ─── */}
      <AutoskillNavSection />

      {/* ── Automation section ─────────────────────── */}
      <div className="bitfun-nav-panel__top-actions">
        <Tooltip content={automationTooltip} placement="right" followCursor>
          <button
            type="button"
            className={`bitfun-nav-panel__top-action-btn${isAutomationActive ? ' is-active' : ''}`}
            onClick={handleOpenAutomation}
            aria-label={automationTooltip}
          >
            <span className="bitfun-nav-panel__top-action-icon-slot" aria-hidden="true">
              <Workflow size={15} />
            </span>
            <span>{automationTooltip}</span>
          </button>
        </Tooltip>
        <Tooltip content={tasksTooltip} placement="right" followCursor>
          <button
            type="button"
            className={`bitfun-nav-panel__top-action-btn${isTasksActive ? ' is-active' : ''}`}
            onClick={handleOpenTasks}
            aria-label={tasksTooltip}
          >
            <span className="bitfun-nav-panel__top-action-icon-slot" aria-hidden="true">
              <Clock size={15} />
            </span>
            <span>{tasksTooltip}</span>
          </button>
        </Tooltip>
        <Tooltip content={pipelinesTooltip} placement="right" followCursor>
          <button
            type="button"
            className={`bitfun-nav-panel__top-action-btn${isPipelinesActive ? ' is-active' : ''}`}
            onClick={handleOpenPipelines}
            aria-label={pipelinesTooltip}
          >
            <span className="bitfun-nav-panel__top-action-icon-slot" aria-hidden="true">
              <IterationCw size={15} />
            </span>
            <span>{pipelinesTooltip}</span>
          </button>
        </Tooltip>
      </div>

      {/* ── Open sessions / tasks (临时会话入口，可点 X 关闭) ── */}
      {openTabs.filter(tab => tab.id !== 'skills' && tab.id !== 'welcome').length > 0 && (
        <div className="bitfun-nav-panel__open-tabs">
          <div className="bitfun-nav-panel__open-tabs-label">
            <MessageSquare size={12} />
            <span>{t('navBar.sessions')}</span>
          </div>
          {openTabs
            .filter(tab => tab.id !== 'skills' && tab.id !== 'welcome')
            .map(tab => {
              const def = tabDefs.find(d => d.id === tab.id);
              if (!def) return null;
              const Icon = def.Icon;
              const label = tab.label || (def.labelKey ? t(def.labelKey) : def.label);
              const isActive = tab.id === activeTabId;
              return (
                <Tooltip key={String(tab.id)} content={label} placement="right" followCursor>
                  <div
                    role="button"
                    tabIndex={0}
                    className={`bitfun-nav-panel__open-tab${isActive ? ' is-active' : ''}`}
                    onClick={() => activateScene(tab.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        activateScene(tab.id);
                      }
                    }}
                  >
                    {Icon && <Icon size={13} className="bitfun-nav-panel__open-tab-icon" aria-hidden="true" />}
                    <span className="bitfun-nav-panel__open-tab-name">{label}</span>
                    <button
                      type="button"
                      className="bitfun-nav-panel__open-tab-close"
                      onClick={(e) => { e.stopPropagation(); closeScene(tab.id); }}
                      aria-label={`Close ${label}`}
                      tabIndex={-1}
                    >
                      <X size={11} aria-hidden="true" />
                    </button>
                  </div>
                </Tooltip>
              );
            })}
        </div>
      )}

      {/* ── Recording history 区域已移除（会话里的流程图场景已包含此功能） ── */}

      {/* ── Spacer (remaining space, empty) ────────── */}
      <div className="bitfun-nav-panel__sections" />

      {workspaceMenuPortal}

      {/* SSH Remote Dialogs */}
      <SSHConnectionDialog
        open={isSSHConnectionDialogOpen}
        onClose={() => setIsSSHConnectionDialogOpen(false)}
      />
      {sshRemote.showFileBrowser && sshRemote.connectionId && (
        <RemoteFileBrowser
          connectionId={sshRemote.connectionId}
          initialPath={sshRemote.remoteFileBrowserInitialPath}
          homePath={sshRemote.remoteFileBrowserInitialPath}
          selectDirectoriesOnly
          onSelect={handleSelectRemoteWorkspace}
          onCancel={() => {
            const hasActiveRemoteWorkspace =
              Boolean(sshRemote.remoteWorkspace) ||
              openedWorkspacesList.some(workspace =>
                isRemoteWorkspace(workspace) &&
                workspace.connectionId === sshRemote.connectionId
              );
            sshRemote.setShowFileBrowser(false);
            if (!hasActiveRemoteWorkspace) {
              void sshRemote.disconnect();
            }
          }}
        />
      )}
    </>
  );
};

export default MainNav;
