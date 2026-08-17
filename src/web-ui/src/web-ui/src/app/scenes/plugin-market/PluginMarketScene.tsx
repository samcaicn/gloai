import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Boxes,
  CheckCircle2,
  Cpu,
  Download,
  FileArchive,
  FileDown,
  Package,
  Puzzle,
  Search as SearchIcon,
  Star,
  Trash2,
  TrendingUp,
  Upload,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Input, Search, Switch } from '@/component-library';
import { useNotification } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import { useSkillMarket } from '@/app/scenes/skills/hooks/useSkillMarket';
import SkillCard from '@/app/scenes/skills/components/SkillCard';
import type { SkillMarketItem } from '@/infrastructure/config/types';
import { subscribe } from '@/infrastructure/api/tupai/events';
import {
  installDshPlugin,
  listBuiltinPlugins,
  listDshPlugins,
  removeDshPlugin,
  searchDshPlugins,
  setBuiltinPluginEnabled,
  setDshPluginEnabled,
  type BuiltinPluginInfo,
  type DshPluginRef,
  type DshPluginSearchItem,
} from '@/infrastructure/api/pluginMarket';
import {
  runtimeRegistryAPI,
  type RuntimeInstance,
  type SubAgent,
} from '@/infrastructure/api/runtimeRegistry';
import {
  presetPackAPI,
  type PackagePreview,
  type PresetInfo,
} from '@/infrastructure/api/presetPack';
import './PluginMarketScene.scss';

const log = createLogger('PluginMarketScene');

type MarketTab = 'skills' | 'dsh' | 'builtin' | 'runtime' | 'preset';

const PluginMarketScene: React.FC = () => {
  const { t } = useTranslation('scenes/plugin-market');
  const notification = useNotification();
  const [activeTab, setActiveTab] = useState<MarketTab>('skills');

  // ── 全网技能（复用既有技能市场 hook）──────────────────────────────
  const [skillQuery, setSkillQuery] = useState('');
  const market = useSkillMarket({
    searchQuery: skillQuery,
    installedSkillNames: useMemo(() => new Set<string>(), []),
    onInstalledChanged: async () => {},
  });

  // ── DSH 插件 ────────────────────────────────────────────────────
  const [dshQuery, setDshQuery] = useState('');
  const [dshResults, setDshResults] = useState<DshPluginSearchItem[]>([]);
  const [dshInstalled, setDshInstalled] = useState<DshPluginRef[]>([]);
  const [dshLoading, setDshLoading] = useState(false);
  const [dshSearching, setDshSearching] = useState(false);
  const [dshBusyId, setDshBusyId] = useState<string | null>(null);

  const loadDshInstalled = useCallback(async () => {
    try {
      setDshLoading(true);
      const list = await listDshPlugins();
      setDshInstalled(list);
    } catch (err) {
      log.error('Failed to load DSH plugins', err);
      notification.error(t('dsh.loadFailed', { error: String(err) }));
    } finally {
      setDshLoading(false);
    }
  }, [notification, t]);

  const runDshSearch = useCallback(async () => {
    try {
      setDshSearching(true);
      const items = await searchDshPlugins(dshQuery.trim() || undefined);
      setDshResults(items);
    } catch (err) {
      log.error('Failed to search DSH plugins', err);
      notification.error(t('dsh.searchFailed', { error: String(err) }));
    } finally {
      setDshSearching(false);
    }
  }, [dshQuery, notification, t]);

  useEffect(() => {
    void loadDshInstalled();
    void runDshSearch();
  }, [loadDshInstalled, runDshSearch]);

  const installedRepoIds = useMemo(
    () => new Set(dshInstalled.map((p) => p.id)),
    [dshInstalled],
  );

  const handleInstallDsh = useCallback(
    async (item: DshPluginSearchItem) => {
      try {
        setDshBusyId(item.id);
        await installDshPlugin({
          repo: item.repo,
          displayName: item.name,
          description: item.description ?? null,
          stars: item.stars ?? null,
        });
        notification.success(t('dsh.installed', { name: item.name }));
        // 立即刷新执行机制：重拉已安装列表（无需重启）。
        await loadDshInstalled();
      } catch (err) {
        notification.error(t('dsh.installFailed', { error: String(err) }));
      } finally {
        setDshBusyId(null);
      }
    },
    [loadDshInstalled, notification, t],
  );

  const handleRemoveDsh = useCallback(
    async (id: string) => {
      try {
        setDshBusyId(id);
        await removeDshPlugin(id);
        notification.success(t('dsh.removed'));
        await loadDshInstalled();
      } catch (err) {
        notification.error(t('dsh.removeFailed', { error: String(err) }));
      } finally {
        setDshBusyId(null);
      }
    },
    [loadDshInstalled, notification, t],
  );

  const handleToggleDsh = useCallback(
    async (id: string, enabled: boolean) => {
      try {
        setDshBusyId(id);
        await setDshPluginEnabled(id, enabled);
        await loadDshInstalled();
      } catch (err) {
        notification.error(t('dsh.toggleFailed', { error: String(err) }));
      } finally {
        setDshBusyId(null);
      }
    },
    [loadDshInstalled, notification, t],
  );

  // ── 内置能力 ────────────────────────────────────────────────────
  const [builtins, setBuiltins] = useState<BuiltinPluginInfo[]>([]);
  const [builtinLoading, setBuiltinLoading] = useState(false);
  const [builtinBusy, setBuiltinBusy] = useState<string | null>(null);

  const loadBuiltins = useCallback(async () => {
    try {
      setBuiltinLoading(true);
      setBuiltins(await listBuiltinPlugins());
    } catch (err) {
      log.error('Failed to load built-in plugins', err);
      notification.error(t('builtin.loadFailed', { error: String(err) }));
    } finally {
      setBuiltinLoading(false);
    }
  }, [notification, t]);

  useEffect(() => {
    if (activeTab === 'builtin') {
      void loadBuiltins();
    }
  }, [activeTab, loadBuiltins]);

  const handleToggleBuiltin = useCallback(
    async (name: string, enabled: boolean) => {
      try {
        setBuiltinBusy(name);
        await setBuiltinPluginEnabled(name, enabled);
        await loadBuiltins();
      } catch (err) {
        notification.error(t('builtin.toggleFailed', { error: String(err) }));
      } finally {
        setBuiltinBusy(null);
      }
    },
    [loadBuiltins, notification, t],
  );

  // ── 预设包（dsh 等价的可移植 agent 机制）─────────────────────
  // 镜像 dsh-desktop 的 Agent Preset：本地预设可导出为 .dshpreset 分享，
  // 导入时先预览（清单 / 文件数 / 安全警告）再原子安装，绝不覆盖已有 ID。
  const [presets, setPresets] = useState<PresetInfo[]>([]);
  const [presetLoading, setPresetLoading] = useState(false);
  const [importPreview, setImportPreview] = useState<PackagePreview | null>(null);
  const [importBytes, setImportBytes] = useState<Uint8Array | null>(null);
  const [importTargetId, setImportTargetId] = useState('');
  const [importBusy, setImportBusy] = useState(false);
  const [importError, setImportError] = useState<string | null>(null);

  const loadPresets = useCallback(async () => {
    try {
      setPresetLoading(true);
      setPresets(await presetPackAPI.list());
    } catch (err) {
      log.error('Failed to load presets', err);
      notification.error(t('preset.loadFailed', { error: String(err) }));
    } finally {
      setPresetLoading(false);
    }
  }, [notification, t]);

  useEffect(() => {
    if (activeTab === 'preset') {
      void loadPresets();
    }
  }, [activeTab, loadPresets]);

  const handleImportFile = useCallback(
    async (file: File) => {
      try {
        setImportError(null);
        setImportBusy(true);
        const buf = await file.arrayBuffer();
        const bytes = new Uint8Array(buf);
        const preview = await presetPackAPI.preview(bytes);
        setImportBytes(bytes);
        setImportTargetId(preview.suggestedTargetId);
        setImportPreview(preview);
      } catch (err) {
        setImportError(String(err));
        notification.error(t('preset.previewFailed', { error: String(err) }));
      } finally {
        setImportBusy(false);
      }
    },
    [notification, t],
  );

  const handleConfirmImport = useCallback(
    async () => {
      if (!importBytes) return;
      const target = importTargetId.trim();
      if (!target) {
        notification.error(t('preset.idEmpty'));
        return;
      }
      try {
        setImportBusy(true);
        await presetPackAPI.import(importBytes, target);
        notification.success(t('preset.imported', { id: target }));
        setImportPreview(null);
        setImportBytes(null);
        setImportTargetId('');
        await loadPresets();
      } catch (err) {
        setImportError(String(err));
        notification.error(t('preset.importFailed', { error: String(err) }));
      } finally {
        setImportBusy(false);
      }
    },
    [importBytes, importTargetId, loadPresets, notification, t],
  );

  const handleExportPreset = useCallback(
    async (id: string) => {
      try {
        await presetPackAPI.download(id, id);
        notification.success(t('preset.exported', { id }));
      } catch (err) {
        notification.error(t('preset.exportFailed', { error: String(err) }));
      }
    },
    [notification, t],
  );

  const handleDeletePreset = useCallback(
    async (id: string) => {
      try {
        await presetPackAPI.remove(id);
        notification.success(t('preset.deleted', { id }));
        await loadPresets();
      } catch (err) {
        notification.error(t('preset.deleteFailed', { error: String(err) }));
      }
    },
    [loadPresets, notification, t],
  );

  // ── 界面自动根据插件刷新 ───────────────────────────────────────
  // 订阅后端 emit 的 `plugins-changed` Tauri 事件 (由 notify_plugins_changed
  // 在任意 catalog 变更后广播, 含本场景操作和 DSH 运行时 Cordis 热加载回写)。
  // 无需用户手动触发, 列表自动重拉, 实现"安装后立即刷新执行机制"的自动侧。
  useEffect(() => {
    const unlisten = subscribe<{ kind?: string }>('plugins-changed', (payload) => {
      log.debug('plugins-changed 事件到达, 自动刷新插件目录', payload);
      // 已安装 DSH 插件 + 内置能力均重拉 (廉价本地 IPC, 不影响 GitHub 搜索结果)。
      void loadDshInstalled();
      void loadBuiltins();
      // 预设包变更也会广播 kind=preset —— 自动刷新预设列表。
      void loadPresets();
    });
    return () => unlisten();
  }, [loadDshInstalled, loadBuiltins, loadPresets]);

  // ── 运行时（CLI 智能体，一切皆插件）──────────────────────────
  // 复用 runtime-registry：本机 CLI (opencode/claude/codex/kimi/trae) 自动探测
  // 出现（Multica 式），每个挂最小 AgentProviderAdapter，自动成为可调用的
  // 子 agent（<app><n> 编号）。用户也可添加自己的 agent API。这里把它们作为
  // "插件" 融入插件市场，与 DSH 插件 / 内置能力 并列。
  const [runtimes, setRuntimes] = useState<RuntimeInstance[]>([]);
  const [subagents, setSubagents] = useState<SubAgent[]>([]);
  const [runtimeLoading, setRuntimeLoading] = useState(false);
  const [runtimeBusy, setRuntimeBusy] = useState<string | null>(null);
  // 自定义 agent API 表单
  const [customName, setCustomName] = useState('');
  const [customEndpoint, setCustomEndpoint] = useState('');
  const [customModel, setCustomModel] = useState('');
  const [customApiKey, setCustomApiKey] = useState('');
  const [customAdding, setCustomAdding] = useState(false);

  /// 仅保留"本机检测到的内置 CLI"（排除用户自定义 / 上游 / DSH）。
  const detectedRuntimes = useMemo(
    () =>
      runtimes.filter(
        (r) =>
          !r.id.startsWith('rt-user-') &&
          !r.id.startsWith('rt-upstream-') &&
          !r.id.startsWith('rt-dsh-'),
      ),
    [runtimes],
  );

  /// 某 provider 是否作为可调用的 agent 启用：检测到了且存在 available 子 agent。
  const isRuntimeEnabled = useCallback(
    (providerId: string) =>
      subagents.some(
        (s) => s.providerId === providerId && s.status === 'available',
      ),
    [subagents],
  );

  /// 某 provider 自动生成的子 agent id（<app><n>）。
  const runtimeAgentId = useCallback(
    (providerId: string) =>
      subagents.find((s) => s.providerId === providerId)?.id ?? null,
    [subagents],
  );

  const customAgents = useMemo(
    () => subagents.filter((s) => s.kind === 'customApi'),
    [subagents],
  );

  const loadRuntimes = useCallback(async () => {
    try {
      setRuntimeLoading(true);
      const [snap, subs] = await Promise.all([
        runtimeRegistryAPI.listRuntimes(),
        runtimeRegistryAPI.listSubagents(),
      ]);
      setRuntimes(snap.instances);
      setSubagents(subs);
    } catch (err) {
      log.error('Failed to load runtimes', err);
      notification.error(t('runtime.loadFailed', { error: String(err) }));
    } finally {
      setRuntimeLoading(false);
    }
  }, [notification, t]);

  useEffect(() => {
    if (activeTab === 'runtime') {
      void loadRuntimes();
    }
  }, [activeTab, loadRuntimes]);

  const handleRescan = useCallback(async () => {
    try {
      setRuntimeBusy('__scan__');
      await runtimeRegistryAPI.scan();
      await loadRuntimes();
      notification.success(t('runtime.rescanned'));
    } catch (err) {
      notification.error(t('runtime.rescanFailed', { error: String(err) }));
    } finally {
      setRuntimeBusy(null);
    }
  }, [loadRuntimes, notification, t]);

  const handleToggleRuntime = useCallback(
    async (providerId: string, enabled: boolean) => {
      try {
        setRuntimeBusy(providerId);
        await runtimeRegistryAPI.setRuntimeEnabled(providerId, enabled);
        await loadRuntimes();
      } catch (err) {
        notification.error(t('runtime.toggleFailed', { error: String(err) }));
      } finally {
        setRuntimeBusy(null);
      }
    },
    [loadRuntimes, notification, t],
  );

  const handleAddCustom = useCallback(async () => {
    const name = customName.trim();
    const endpoint = customEndpoint.trim();
    if (!name || !endpoint) {
      notification.error(t('runtime.formIncomplete'));
      return;
    }
    try {
      setCustomAdding(true);
      await runtimeRegistryAPI.addCustomAgent({
        name,
        endpoint,
        model: customModel.trim() || undefined,
        apiKey: customApiKey.trim() || undefined,
      });
      setCustomName('');
      setCustomEndpoint('');
      setCustomModel('');
      setCustomApiKey('');
      await loadRuntimes();
      notification.success(t('runtime.customAdded', { name }));
    } catch (err) {
      notification.error(t('runtime.addFailed', { error: String(err) }));
    } finally {
      setCustomAdding(false);
    }
  }, [
    customName,
    customEndpoint,
    customModel,
    customApiKey,
    loadRuntimes,
    notification,
    t,
  ]);

  const handleRemoveCustom = useCallback(
    async (subagentId: string) => {
      try {
        setRuntimeBusy(subagentId);
        await runtimeRegistryAPI.removeAgent(subagentId);
        await loadRuntimes();
      } catch (err) {
        notification.error(t('runtime.removeFailed', { error: String(err) }));
      } finally {
        setRuntimeBusy(null);
      }
    },
    [loadRuntimes, notification, t],
  );

  return (
    <div className="plugin-market-scene">
      <div className="plugin-market-scene__header">
        <div className="plugin-market-scene__title-row">
          <Boxes size={22} strokeWidth={1.6} />
          <h1 className="plugin-market-scene__title">{t('title')}</h1>
        </div>
        <p className="plugin-market-scene__subtitle">{t('subtitle')}</p>
      </div>

      <div className="plugin-market-scene__tabs">
        <button
          type="button"
          className={`plugin-market-scene__tab ${activeTab === 'skills' ? 'is-active' : ''}`}
          onClick={() => setActiveTab('skills')}
        >
          <Puzzle size={14} />
          <span>{t('tabs.skills')}</span>
        </button>
        <button
          type="button"
          className={`plugin-market-scene__tab ${activeTab === 'dsh' ? 'is-active' : ''}`}
          onClick={() => setActiveTab('dsh')}
        >
          <Package size={14} />
          <span>{t('tabs.dsh')}</span>
        </button>
        <button
          type="button"
          className={`plugin-market-scene__tab ${activeTab === 'builtin' ? 'is-active' : ''}`}
          onClick={() => setActiveTab('builtin')}
        >
          <Boxes size={14} />
          <span>{t('tabs.builtin')}</span>
        </button>
        <button
          type="button"
          className={`plugin-market-scene__tab ${activeTab === 'runtime' ? 'is-active' : ''}`}
          onClick={() => setActiveTab('runtime')}
        >
          <Cpu size={14} />
          <span>{t('tabs.runtime')}</span>
        </button>
        <button
          type="button"
          className={`plugin-market-scene__tab ${activeTab === 'preset' ? 'is-active' : ''}`}
          onClick={() => setActiveTab('preset')}
        >
          <FileArchive size={14} />
          <span>{t('tabs.preset')}</span>
        </button>
      </div>

      <div className="plugin-market-scene__body">
        {/* ── 全网技能 ─────────────────────────────────────────── */}
        {activeTab === 'skills' && (
          <div className="plugin-market-scene__panel">
            <div className="plugin-market-scene__search-bar">
              <Search
                value={skillQuery}
                onChange={setSkillQuery}
                onSearch={() => {}}
                onClear={() => setSkillQuery('')}
                placeholder={t('skills.searchPlaceholder')}
                size="medium"
                clearable
                enterToSearch
              />
            </div>

            {market.marketLoading ? (
              <div className="plugin-market-scene__grid" aria-busy="true">
                {Array.from({ length: 8 }).map((_, i) => (
                  <div key={`sk-${i}`} className="plugin-market-scene__skeleton" />
                ))}
              </div>
            ) : market.marketError ? (
              <div className="plugin-market-scene__empty plugin-market-scene__empty--error">
                <Package size={26} />
                <span>{market.marketError}</span>
              </div>
            ) : market.marketSkills.length === 0 ? (
              <div className="plugin-market-scene__empty">
                <Package size={26} />
                <span>{t('skills.empty')}</span>
              </div>
            ) : (
              <div className="plugin-market-scene__grid">
                {market.marketSkills.map((skill: SkillMarketItem, index) => {
                  const isInstalled = false;
                  const isDownloading = market.downloadingPackage === skill.installId;
                  return (
                    <SkillCard
                      key={skill.installId}
                      name={skill.name}
                      description={skill.description}
                      index={index}
                      accentSeed={skill.installId}
                      iconKind="market"
                      badges={
                        isInstalled ? (
                          <Badge variant="success">
                            <CheckCircle2 size={11} />
                            {t('skills.installed')}
                          </Badge>
                        ) : null
                      }
                      meta={(
                        <span className="plugin-market-scene__meta">
                          <TrendingUp size={12} />
                          {skill.installs ?? 0}
                        </span>
                      )}
                      actions={[
                        {
                          id: 'download',
                          icon: <Download size={13} />,
                          ariaLabel: t('skills.download'),
                          title: isDownloading ? t('skills.downloading') : t('skills.download'),
                          disabled: isDownloading,
                          tone: 'primary',
                          onClick: () => void market.handleDownload(skill, 'project'),
                        },
                      ]}
                    />
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* ── DSH 插件 ──────────────────────────────────────────── */}
        {activeTab === 'dsh' && (
          <div className="plugin-market-scene__panel">
            <div className="plugin-market-scene__search-bar">
              <Search
                value={dshQuery}
                onChange={setDshQuery}
                onSearch={() => void runDshSearch()}
                onClear={() => {
                  setDshQuery('');
                  void runDshSearch();
                }}
                placeholder={t('dsh.searchPlaceholder')}
                size="medium"
                clearable
                enterToSearch
              />
            </div>

            {/* 已安装 DSH 插件 */}
            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('dsh.installedTitle')}
                {dshInstalled.length > 0 && (
                  <Badge variant="info">{dshInstalled.length}</Badge>
                )}
              </h2>
              {dshLoading ? (
                <div className="plugin-market-scene__grid" aria-busy="true">
                  {Array.from({ length: 3 }).map((_, i) => (
                    <div key={`di-${i}`} className="plugin-market-scene__skeleton" />
                  ))}
                </div>
              ) : dshInstalled.length === 0 ? (
                <div className="plugin-market-scene__empty">
                  <Package size={24} />
                  <span>{t('dsh.installedEmpty')}</span>
                </div>
              ) : (
                <div className="plugin-market-scene__list">
                  {dshInstalled.map((p) => (
                    <div key={p.id} className="plugin-market-scene__row">
                      <div className="plugin-market-scene__row-main">
                        <span className="plugin-market-scene__row-name">
                          {p.displayName ?? p.repo}
                        </span>
                        {p.description && (
                          <span className="plugin-market-scene__row-desc">
                            {p.description}
                          </span>
                        )}
                        {p.stars ? (
                          <span className="plugin-market-scene__meta">
                            <Star size={11} />
                            {p.stars}
                          </span>
                        ) : null}
                      </div>
                      <div className="plugin-market-scene__row-actions">
                        <Switch
                          checked={p.enabled}
                          onChange={(e) =>
                            void handleToggleDsh(p.id, (e.target as HTMLInputElement).checked)
                          }
                          disabled={dshBusyId === p.id}
                        />
                        <button
                          type="button"
                          className="plugin-market-scene__icon-btn"
                          aria-label={t('dsh.remove')}
                          onClick={() => void handleRemoveDsh(p.id)}
                          disabled={dshBusyId === p.id}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            {/* 全网 DSH 插件搜索结果 */}
            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('dsh.discoverTitle')}
              </h2>
              {dshSearching ? (
                <div className="plugin-market-scene__grid" aria-busy="true">
                  {Array.from({ length: 8 }).map((_, i) => (
                    <div key={`dr-${i}`} className="plugin-market-scene__skeleton" />
                  ))}
                </div>
              ) : dshResults.length === 0 ? (
                <div className="plugin-market-scene__empty">
                  <SearchIcon size={24} />
                  <span>{t('dsh.discoverEmpty')}</span>
                </div>
              ) : (
                <div className="plugin-market-scene__grid">
                  {dshResults.map((item, index) => {
                    const isInstalled = installedRepoIds.has(item.id);
                    const busy = dshBusyId === item.id;
                    return (
                      <div
                        key={item.id}
                        className="plugin-market-scene__card"
                        style={{ '--card-index': index } as React.CSSProperties}
                      >
                        <div className="plugin-market-scene__card-top">
                          <div className="plugin-market-scene__card-icon">
                            <Package size={16} strokeWidth={1.6} />
                          </div>
                          <div className="plugin-market-scene__card-info">
                            <span className="plugin-market-scene__card-name">
                              {item.name}
                            </span>
                            {item.description && (
                              <span className="plugin-market-scene__card-desc">
                                {item.description}
                              </span>
                            )}
                          </div>
                          {isInstalled && (
                            <Badge variant="success">
                              <CheckCircle2 size={11} />
                              {t('dsh.installed')}
                            </Badge>
                          )}
                        </div>
                        <div className="plugin-market-scene__card-meta">
                          {item.language && <Badge variant="purple">{item.language}</Badge>}
                          {item.license && <Badge variant="info">{item.license}</Badge>}
                          <span className="plugin-market-scene__meta">
                            <Star size={11} />
                            {item.stars}
                          </span>
                        </div>
                        <div className="plugin-market-scene__card-actions">
                          <Button
                            variant={isInstalled ? 'secondary' : 'primary'}
                            size="small"
                            disabled={busy || isInstalled}
                            onClick={() => void handleInstallDsh(item)}
                          >
                            {isInstalled ? (
                              <>
                                <CheckCircle2 size={12} />
                                {t('dsh.installed')}
                              </>
                            ) : (
                              <>
                                <Download size={12} />
                                {t('dsh.install')}
                              </>
                            )}
                          </Button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
          </div>
        )}

        {/* ── 内置能力 ──────────────────────────────────────────── */}
        {activeTab === 'builtin' && (
          <div className="plugin-market-scene__panel">
            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('builtin.title')}
              </h2>
              {builtinLoading ? (
                <div className="plugin-market-scene__grid" aria-busy="true">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <div key={`b-${i}`} className="plugin-market-scene__skeleton" />
                  ))}
                </div>
              ) : (
                <div className="plugin-market-scene__list">
                  {builtins.map((b) => (
                    <div key={b.name} className="plugin-market-scene__row">
                      <div className="plugin-market-scene__row-main">
                        <span className="plugin-market-scene__row-name">{b.name}</span>
                        <span className="plugin-market-scene__row-desc">
                          {b.description}
                        </span>
                        <Badge variant="purple">{b.category}</Badge>
                      </div>
                      <div className="plugin-market-scene__row-actions">
                        <Switch
                          checked={b.enabled}
                          onChange={(e) =>
                            void handleToggleBuiltin(b.name, (e.target as HTMLInputElement).checked)
                          }
                          disabled={builtinBusy === b.name}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>
        )}

        {/* ── 运行时（CLI 智能体）────────────────────────────────── */}
        {activeTab === 'runtime' && (
          <div className="plugin-market-scene__panel">
            <div className="plugin-market-scene__search-bar">
              <Button
                variant="secondary"
                size="small"
                disabled={runtimeBusy === '__scan__'}
                onClick={() => void handleRescan()}
              >
                <SearchIcon size={13} />
                {t('runtime.rescan')}
              </Button>
            </div>

            {/* 检测到的 CLI 运行时（Multica 式自动出现） */}
            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('runtime.detectedTitle')}
              </h2>
              {runtimeLoading ? (
                <div className="plugin-market-scene__grid" aria-busy="true">
                  {Array.from({ length: 5 }).map((_, i) => (
                    <div key={`r-${i}`} className="plugin-market-scene__skeleton" />
                  ))}
                </div>
              ) : detectedRuntimes.length === 0 ? (
                <div className="plugin-market-scene__empty">
                  <Cpu size={24} />
                  <span>{t('runtime.detectedEmpty')}</span>
                </div>
              ) : (
                <div className="plugin-market-scene__list">
                  {detectedRuntimes.map((r) => {
                    const enabled = isRuntimeEnabled(r.providerId);
                    const agentId = runtimeAgentId(r.providerId);
                    return (
                      <div key={r.id} className="plugin-market-scene__row">
                        <div className="plugin-market-scene__row-main">
                          <span className="plugin-market-scene__row-name">
                            {r.displayName}
                          </span>
                          <span className="plugin-market-scene__row-desc">
                            {r.providerId}
                          </span>
                          <Badge variant={r.installed ? 'success' : 'info'}>
                            {r.installed
                              ? t('runtime.installed')
                              : t('runtime.notInstalled')}
                          </Badge>
                          <Badge variant="purple">
                            {r.kind === 'acp' ? 'ACP' : 'CliRun'}
                          </Badge>
                          {enabled && agentId && (
                            <Badge variant="info">
                              {t('runtime.agentId', { id: agentId })}
                            </Badge>
                          )}
                        </div>
                        <div className="plugin-market-scene__row-actions">
                          <Switch
                            checked={enabled}
                            onChange={(e) =>
                              void handleToggleRuntime(
                                r.providerId,
                                (e.target as HTMLInputElement).checked,
                              )
                            }
                            disabled={!r.installed || runtimeBusy === r.providerId}
                          />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            {/* 自定义 Agent API（用户自己添加） */}
            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('runtime.customTitle')}
              </h2>
              {customAgents.length === 0 ? (
                <div className="plugin-market-scene__empty">
                  <Cpu size={24} />
                  <span>{t('runtime.customEmpty')}</span>
                </div>
              ) : (
                <div className="plugin-market-scene__list">
                  {customAgents.map((s) => (
                    <div key={s.id} className="plugin-market-scene__row">
                      <div className="plugin-market-scene__row-main">
                        <span className="plugin-market-scene__row-name">{s.id}</span>
                        <span className="plugin-market-scene__row-desc">
                          {s.providerId}
                        </span>
                        <Badge variant="info">{t('runtime.custom')}</Badge>
                      </div>
                      <div className="plugin-market-scene__row-actions">
                        <button
                          type="button"
                          className="plugin-market-scene__icon-btn"
                          aria-label={t('runtime.remove')}
                          onClick={() => void handleRemoveCustom(s.id)}
                          disabled={runtimeBusy === s.id}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <div className="plugin-market-scene__custom-form">
                <Input
                  value={customName}
                  onChange={setCustomName}
                  placeholder={t('runtime.namePlaceholder')}
                  size="small"
                />
                <Input
                  value={customEndpoint}
                  onChange={setCustomEndpoint}
                  placeholder={t('runtime.endpointPlaceholder')}
                  size="small"
                />
                <Input
                  value={customModel}
                  onChange={setCustomModel}
                  placeholder={t('runtime.modelPlaceholder')}
                  size="small"
                />
                <Input
                  value={customApiKey}
                  onChange={setCustomApiKey}
                  placeholder={t('runtime.apiKeyPlaceholder')}
                  size="small"
                  type="password"
                />
                <Button
                  variant="primary"
                  size="small"
                  disabled={customAdding}
                  onClick={() => void handleAddCustom()}
                >
                  <Download size={12} />
                  {t('runtime.add')}
                </Button>
              </div>
            </section>
          </div>
        )}

        {/* ── 预设包（可移植 agent）────────────────────────────── */}
        {activeTab === 'preset' && (
          <div className="plugin-market-scene__panel">
            <div className="plugin-market-scene__search-bar">
              <label className="plugin-market-scene__file-btn">
                <input
                  type="file"
                  accept=".dshpreset,.zip,application/zip"
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    if (f) void handleImportFile(f);
                    e.target.value = '';
                  }}
                  hidden
                />
                <Upload size={13} />
                {importBusy ? t('preset.reading') : t('preset.importPreset')}
              </label>
            </div>

            <section className="plugin-market-scene__section">
              <h2 className="plugin-market-scene__section-title">
                {t('preset.title')}
              </h2>
              {importError && (
                <div className="plugin-market-scene__empty plugin-market-scene__empty--error">
                  <FileArchive size={24} />
                  <span>{importError}</span>
                </div>
              )}
              {presetLoading ? (
                <div className="plugin-market-scene__grid" aria-busy="true">
                  {Array.from({ length: 4 }).map((_, i) => (
                    <div key={`p-${i}`} className="plugin-market-scene__skeleton" />
                  ))}
                </div>
              ) : presets.length === 0 ? (
                <div className="plugin-market-scene__empty">
                  <FileArchive size={24} />
                  <span>{t('preset.empty')}</span>
                </div>
              ) : (
                <div className="plugin-market-scene__list">
                  {presets.map((p) => (
                    <div key={p.id} className="plugin-market-scene__row">
                      <div className="plugin-market-scene__row-main">
                        <span className="plugin-market-scene__row-name">
                          {p.name || p.id}
                        </span>
                        <span className="plugin-market-scene__row-desc">
                          {p.id}
                          {p.description ? ` · ${p.description}` : ''}
                        </span>
                        <Badge variant="purple">
                          {t('preset.count', { count: p.fileCount })}
                        </Badge>
                      </div>
                      <div className="plugin-market-scene__row-actions">
                        <button
                          type="button"
                          className="plugin-market-scene__icon-btn"
                          aria-label={t('preset.export')}
                          onClick={() => void handleExportPreset(p.id)}
                        >
                          <FileDown size={14} />
                        </button>
                        <button
                          type="button"
                          className="plugin-market-scene__icon-btn"
                          aria-label={t('preset.remove')}
                          onClick={() => void handleDeletePreset(p.id)}
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            {/* 导入预览：确认前的安全检查 + 目标 ID 选择 */}
            {importPreview && (
              <div className="plugin-market-scene__preset-overlay">
                <div className="plugin-market-scene__preset-import">
                  <h3>{t('preset.importTitle')}</h3>
                  <p className="plugin-market-scene__preset-intro">
                    {t('preset.importIntro')}
                  </p>
                  <p className="plugin-market-scene__preset-security">
                    {t('preset.importSecurity')}
                  </p>
                  <div className="plugin-market-scene__preset-meta">
                    <div>
                      <span className="plugin-market-scene__preset-label">
                        {t('preset.packageName')}
                      </span>
                      <span>
                        {importPreview.manifest.name || importPreview.manifest.id}
                      </span>
                    </div>
                    <div>
                      <span className="plugin-market-scene__preset-label">
                        {t('preset.packageContents')}
                      </span>
                      <span>
                        {t('preset.packageContentsValue', {
                          count: importPreview.fileCount,
                        })}
                      </span>
                    </div>
                    {importPreview.manifest.sourceDshVersion && (
                      <div>
                        <span className="plugin-market-scene__preset-label">
                          {t('preset.packageVersion')}
                        </span>
                        <span>
                          {t('preset.packageVersionValue', {
                            version: importPreview.manifest.sourceDshVersion,
                          })}
                        </span>
                      </div>
                    )}
                  </div>

                  {importPreview.warnings.length > 0 && (
                    <ul className="plugin-market-scene__preset-warnings">
                      {importPreview.warnings.map((w, i) => (
                        <li key={i}>
                          {w.warningType === 'possibleSecrets'
                            ? t('preset.importWarningPossibleSecrets')
                            : w.warningType === 'absolutePaths'
                            ? t('preset.importWarningAbsolutePaths')
                            : t('preset.importWarningVersionMismatch', {
                                packageVersion: w.packageVersion ?? '',
                                appVersion: w.appVersion ?? '',
                              })}
                        </li>
                      ))}
                    </ul>
                  )}

                  <label className="plugin-market-scene__preset-field">
                    <span>{t('preset.targetId')}</span>
                    <Input
                      value={importTargetId}
                      onChange={setImportTargetId}
                      placeholder={t('preset.targetIdPlaceholder')}
                      size="small"
                    />
                  </label>

                  <div className="plugin-market-scene__preset-actions">
                    <Button
                      variant="secondary"
                      size="small"
                      onClick={() => {
                        setImportPreview(null);
                        setImportBytes(null);
                        setImportTargetId('');
                        setImportError(null);
                      }}
                    >
                      {t('preset.cancel')}
                    </Button>
                    <Button
                      variant="primary"
                      size="small"
                      disabled={importBusy}
                      onClick={() => void handleConfirmImport()}
                    >
                      {importBusy ? t('preset.importing') : t('preset.importConfirm')}
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default PluginMarketScene;
