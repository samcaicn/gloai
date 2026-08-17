import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Boxes,
  CheckCircle2,
  Download,
  Package,
  Puzzle,
  Search as SearchIcon,
  Star,
  Trash2,
  TrendingUp,
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
import './PluginMarketScene.scss';

const log = createLogger('PluginMarketScene');

type MarketTab = 'skills' | 'dsh' | 'builtin';

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
    });
    return () => unlisten();
  }, [loadDshInstalled, loadBuiltins]);

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
      </div>
    </div>
  );
};

export default PluginMarketScene;
