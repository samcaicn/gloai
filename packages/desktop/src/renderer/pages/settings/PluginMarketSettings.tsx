/**
 * @license
 * Copyright 2025 AionUi (aionui.com)
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The plugin market, as a settings page.
 *
 * Two jobs, kept separate: managing the sources (which catalogues the app may read), and browsing the one that is
 * enabled. Browsing never installs anything — an item describes itself, and whether this app can act on it is a
 * decision made elsewhere — so the page is a window onto the ecosystem, not an installer.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Message, Spin, Tag } from '@arco-design/web-react';
import { ArrowDown, ArrowUp, Delete, LinkOne, Plus, Puzzle, Refresh } from '@icon-park/react';
import { pluginBridge, unwrapMarket } from '@/common/plugins/bridge';
import type { MarketCatalogResult, MarketItem, MarketSourceView } from '@/common/plugins/market';
import { openExternalUrl } from '@/renderer/utils/platform';
import { listPlugins, enablePlugin, disablePlugin, onPluginToggle } from '@renderer/plugins';
import SettingsPageWrapper from './components/SettingsPageWrapper';
import SettingsPageHeader from './components/SettingsPageHeader';

const useT = useTranslation;

const PLUGIN_TILE = 'w-36px h-36px rd-8px flex items-center justify-center bg-fill-2 text-t-secondary';

const openUrl = (url: string | undefined): void => {
  if (url) void openExternalUrl(url).catch(() => undefined);
};

/** One source as a row in the management list. */
const SourceRow: React.FC<{
  source: MarketSourceView;
  onEnable: (id: string) => void;
  onRemove: (id: string) => void;
  onMove: (id: string, direction: 'up' | 'down') => void;
}> = ({ source, onEnable, onRemove, onMove }) => (
  <div data-testid={`mu-plugin-source-${source.sourceRecordId}`} className='flex items-center gap-12px py-10px border-b border-[var(--color-border-1)] last:border-b-0'>
    <div className={PLUGIN_TILE}>
      <Puzzle size={18} />
    </div>
    <div className='min-w-0 flex-1'>
      <div className='flex items-center gap-8px'>
        <span className='text-14px font-500 text-t-primary truncate'>{source.name}</span>
        {source.enabled ? <Tag size='small' color='green'>{t('settings.pluginMarket.enabled')}</Tag> : null}
      </div>
      <div className='text-12px text-t-tertiary truncate' dir='ltr'>
        {source.endpoint}
      </div>
      {source.attribution ? <div className='text-12px text-t-tertiary truncate'>{source.attribution.name}</div> : null}
    </div>
    <div className='shrink-0 flex items-center gap-6px'>
      {!source.enabled ? (
        <Button size='mini' type='primary' data-testid='mu-plugin-enable' onClick={() => onEnable(source.sourceRecordId)}>
          {t('settings.pluginMarket.enable')}
        </Button>
      ) : null}
      <Button size='mini' iconOnly type='text' aria-label={t('settings.pluginMarket.moveUp')} disabled={!source.enabled} onClick={() => onMove(source.sourceRecordId, 'up')}>
        <ArrowUp size={14} />
      </Button>
      <Button size='mini' iconOnly type='text' aria-label={t('settings.pluginMarket.moveDown')} disabled={!source.enabled} onClick={() => onMove(source.sourceRecordId, 'down')}>
        <ArrowDown size={14} />
      </Button>
      <Button size='mini' iconOnly type='text' status='danger' aria-label={t('settings.pluginMarket.remove')} onClick={() => onRemove(source.sourceRecordId)}>
        <Delete size={14} />
      </Button>
    </div>
  </div>
);

/** One catalog item as a card. */
const ItemCard: React.FC<{ item: MarketItem; onOpen: (item: MarketItem) => void }> = ({ item, onOpen }) => (
  <button
    type='button'
    data-testid={`mu-plugin-item-${item.id}`}
    onClick={() => onOpen(item)}
    className='text-left w-full flex flex-col gap-6px p-12px rd-8px border border-[var(--color-border-2)] hover:border-[rgb(var(--primary-5))] hover:bg-fill-1 transition-colors'
  >
    <div className='flex items-center gap-8px'>
      <div className={PLUGIN_TILE}>
        <Puzzle size={16} />
      </div>
      <span className='text-14px font-500 text-t-primary truncate'>{item.displayName}</span>
    </div>
    <p className='m-0 text-12px text-t-secondary line-clamp-2 leading-relaxed'>{item.summary}</p>
    {item.categories && item.categories.length ? (
      <div className='flex flex-wrap gap-4px'>
        {item.categories.slice(0, 3).map((category) => (
          <Tag key={category} size='small'>
            {category}
          </Tag>
        ))}
      </div>
    ) : null}
  </button>
);

const PluginMarketSettings: React.FC = () => {
  const { t } = useT();
  const [state, setState] = useState<{ sources: MarketSourceView[]; builtIns: { key: string; name: string; description?: string; endpoint: string; homepage?: string }[] }>();
  const [catalog, setCatalog] = useState<MarketCatalogResult[]>([]);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [loadingCatalog, setLoadingCatalog] = useState(false);
  const [detail, setDetail] = useState<MarketItem | undefined>();
  const [plugins, setPlugins] = useState(() => listPlugins());
  const [pluginBusy, setPluginBusy] = useState<string | undefined>();

  useEffect(() => onPluginToggle(() => setPlugins(listPlugins())), []);

  const refreshState = useCallback(async () => {
    try {
      const next = unwrapMarket(await pluginBridge.marketState.invoke());
      setState(next);
      return next;
    } catch (error) {
      Message.error(t('settings.pluginMarket.stateError', { message: error instanceof Error ? error.message : String(error) }));
      return undefined;
    }
  }, [t]);

  const enabledId = state?.sources.find((source) => source.enabled)?.sourceRecordId;

  const refreshCatalog = useCallback(
    async (search?: string, cursor?: string) => {
      setLoadingCatalog(true);
      try {
        const results = unwrapMarket(await pluginBridge.marketCatalog.invoke({ q: search, cursor, limit: 24 }));
        setCatalog((previous) => (cursor ? [...previous, ...results] : results));
      } catch (error) {
        Message.error(t('settings.pluginMarket.catalogError', { message: error instanceof Error ? error.message : String(error) }));
      } finally {
        setLoadingCatalog(false);
      }
    },
    [t]
  );

  useEffect(() => {
    void refreshState().then((next) => {
      const active = next?.sources.find((source) => source.enabled);
      if (active) void refreshCatalog();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const mutate = useCallback(
    async (mutation: Parameters<typeof pluginBridge.marketSources.invoke>[0]) => {
      setBusy(true);
      try {
        const next = unwrapMarket(await pluginBridge.marketSources.invoke(mutation));
        setState({ sources: next, builtIns: state?.builtIns ?? [] });
        if (mutation.action === 'select' || mutation.action === 'remove') {
          setCatalog([]);
          setDetail(undefined);
          const active = next.find((source) => source.enabled);
          if (active) void refreshCatalog();
        }
      } catch (error) {
        Message.error(t('settings.pluginMarket.mutationError', { message: error instanceof Error ? error.message : String(error) }));
      } finally {
        setBusy(false);
      }
    },
    [state, t, refreshCatalog]
  );

  const onSearch = (value: string): void => {
    setQuery(value);
    setCatalog([]);
    void refreshCatalog(value || undefined);
  };

  const activeResult = catalog[0];
  const hasMore = Boolean(activeResult?.nextCursor);
  const addedKeys = new Set(state?.sources.map((source) => source.builtInKey));
  const availableBuiltIns = (state?.builtIns ?? []).filter((builtIn) => !addedKeys.has(builtIn.key));

  const [customUrl, setCustomUrl] = useState('');
  const addCustom = (): void => {
    const trimmed = customUrl.trim();
    if (!trimmed) return;
    void mutate({ action: 'add-standard', manifestUrl: trimmed }).then(() => setCustomUrl(''));
  };

  return (
    <SettingsPageWrapper>
      <div className='flex flex-col gap-24px'>
        <SettingsPageHeader
          data-testid='mu-plugin-header'
          title={t('settings.plugins')}
          description={t('settings.pluginMarket.description')}
          actions={
            enabledId ? (
              <Button size='small' icon={<Refresh size={14} />} loading={loadingCatalog} onClick={() => void refreshCatalog(query || undefined)}>
                {t('common.refresh')}
              </Button>
            ) : null
          }
        />

        {/* Installed plugins: install (load) or uninstall (unload) any of them at runtime. */}
        <section data-testid='mu-plugin-installed' className='flex flex-col gap-12px'>
          <h2 className='m-0 text-14px font-600 text-t-primary'>{t('settings.pluginMarket.installed')}</h2>
          <div className='settings-list'>
            {plugins.length ? (
              plugins.map((plugin) => (
                <div
                  key={plugin.name}
                  data-testid={`mu-plugin-row-${plugin.name}`}
                  className='flex items-center gap-12px py-10px border-b border-[var(--color-border-1)] last:border-b-0'
                >
                  <div className={PLUGIN_TILE}>
                    <Puzzle size={18} />
                  </div>
                  <div className='min-w-0 flex-1'>
                    <div className='text-14px font-500 text-t-primary truncate'>{plugin.name}</div>
                    {plugin.description ? (
                      <div className='text-12px text-t-tertiary truncate'>{plugin.description}</div>
                    ) : null}
                  </div>
                  {plugin.enabled ? (
                    <Button
                      size='mini'
                      status='danger'
                      data-testid={`mu-plugin-uninstall-${plugin.name}`}
                      loading={pluginBusy === plugin.name}
                      onClick={async () => {
                        setPluginBusy(plugin.name);
                        disablePlugin(plugin.name);
                        setPluginBusy(undefined);
                      }}
                    >
                      {t('settings.pluginMarket.uninstall')}
                    </Button>
                  ) : (
                    <Button
                      size='mini'
                      type='primary'
                      data-testid={`mu-plugin-install-${plugin.name}`}
                      loading={pluginBusy === plugin.name}
                      onClick={async () => {
                        setPluginBusy(plugin.name);
                        await enablePlugin(plugin.name);
                        setPluginBusy(undefined);
                      }}
                    >
                      {t('settings.pluginMarket.install')}
                    </Button>
                  )}
                </div>
              ))
            ) : (
              <div className='text-13px text-t-tertiary py-16px'>{t('settings.pluginMarket.noPlugins')}</div>
            )}
          </div>
        </section>

        {/* Source management. */}
        <section data-testid='mu-plugin-sources' className='flex flex-col gap-12px'>
          <h2 className='m-0 text-14px font-600 text-t-primary'>{t('settings.pluginMarket.sources')}</h2>
          <div className='settings-list'>
            {state?.sources.length ? (
              state.sources.map((source) => (
                <SourceRow
                  key={source.sourceRecordId}
                  source={source}
                  onEnable={(id) => void mutate({ action: 'select', sourceRecordId: id })}
                  onRemove={(id) => void mutate({ action: 'remove', sourceRecordId: id })}
                  onMove={(id, direction) => void mutate({ action: 'move', sourceRecordId: id, direction })}
                />
              ))
            ) : (
              <div className='text-13px text-t-tertiary py-16px'>{t('settings.pluginMarket.noSources')}</div>
            )}
          </div>

          {availableBuiltIns.length ? (
            <div className='flex flex-col gap-8px'>
              <div className='text-12px text-t-tertiary'>{t('settings.pluginMarket.addBuiltIn')}</div>
              <div className='flex flex-wrap gap-8px'>
                {availableBuiltIns.map((builtIn) => (
                  <Button
                    key={builtIn.key}
                    size='mini'
                    icon={<Plus size={14} />}
                    loading={busy}
                    data-testid={`mu-plugin-add-${builtIn.key}`}
                    onClick={() => void mutate({ action: 'add-builtin', key: builtIn.key })}
                  >
                    {builtIn.name}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}

          <div className='flex flex-col gap-8px'>
            <div className='text-12px text-t-tertiary'>{t('settings.pluginMarket.addCustom')}</div>
            <div className='flex gap-8px'>
              <Input
                className='flex-1'
                size='small'
                placeholder={t('settings.pluginMarket.customPlaceholder')}
                value={customUrl}
                onChange={setCustomUrl}
                onPressEnter={addCustom}
              />
              <Button size='small' type='primary' icon={<Plus size={14} />} loading={busy} onClick={addCustom}>
                {t('settings.pluginMarket.add')}
              </Button>
            </div>
          </div>
        </section>

        {/* Browse the enabled source. */}
        <section data-testid='mu-plugin-browse' className='flex flex-col gap-12px'>
          <div className='flex items-center justify-between gap-12px'>
            <h2 className='m-0 text-14px font-600 text-t-primary'>{t('settings.pluginMarket.browse')}</h2>
            <Input.Search
              className='w-200px'
              size='small'
              allowClear
              placeholder={t('settings.pluginMarket.searchPlaceholder')}
              value={query}
              onChange={(value) => onSearch(value)}
            />
          </div>

          {!enabledId ? (
            <div className='text-13px text-t-tertiary py-24px text-center'>{t('settings.pluginMarket.pickSourceFirst')}</div>
          ) : loadingCatalog && catalog.length === 0 ? (
            <div className='flex justify-center py-32px'>
              <Spin />
            </div>
          ) : activeResult?.error ? (
            <div className='text-13px text-t-tertiary py-24px text-center'>{activeResult.error}</div>
          ) : catalog.length === 0 ? (
            <div className='text-13px text-t-tertiary py-24px text-center'>{t('settings.pluginMarket.emptyCatalog')}</div>
          ) : (
            <>
              <div className='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-12px'>
                {catalog.flatMap((result) => result.items).map((item) => (
                  <ItemCard key={item.id} item={item} onOpen={setDetail} />
                ))}
              </div>
              {hasMore ? (
                <div className='flex justify-center pt-8px'>
                  <Button size='small' loading={loadingCatalog} onClick={() => void refreshCatalog(query || undefined, activeResult?.nextCursor)}>
                    {t('settings.pluginMarket.loadMore')}
                  </Button>
                </div>
              ) : null}
            </>
          )}
        </section>
      </div>

      {/* Item detail. */}
      {detail ? (
        <Modal
          title={detail.displayName}
          visible={Boolean(detail)}
          onCancel={() => setDetail(undefined)}
          footer={
            <Button type='primary' onClick={() => setDetail(undefined)}>
              {t('common.close')}
            </Button>
          }
        >
          <div className='flex flex-col gap-12px'>
            {detail.summary ? <p className='m-0 text-13px text-t-secondary leading-relaxed'>{detail.summary}</p> : null}
            {detail.description ? <p className='m-0 text-13px text-t-tertiary leading-relaxed'>{detail.description}</p> : null}
            {detail.categories?.length ? (
              <div className='flex flex-wrap gap-6px'>
                {detail.categories.map((category) => (
                  <Tag key={category} size='small'>
                    {category}
                  </Tag>
                ))}
              </div>
            ) : null}
            <div className='flex flex-col gap-4px text-12px text-t-tertiary'>
              {detail.publisher ? <div>{t('settings.pluginMarket.publisher')}: {detail.publisher.name}</div> : null}
              {detail.latestVersion ? <div>{t('settings.pluginMarket.version')}: {detail.latestVersion}</div> : null}
              {detail.license ? <div>{t('settings.pluginMarket.license')}: {detail.license}</div> : null}
              {detail.repository ? (
                <button type='button' className='flex items-center gap-4px text-t-secondary hover:text-t-primary' onClick={() => openUrl(detail.repository?.url)}>
                  <LinkOne size={14} /> {t('settings.pluginMarket.repository')}
                </button>
              ) : null}
              {detail.homepage ? (
                <button type='button' className='flex items-center gap-4px text-t-secondary hover:text-t-primary' onClick={() => openUrl(detail.homepage)}>
                  <LinkOne size={14} /> {t('settings.pluginMarket.homepage')}
                </button>
              ) : null}
            </div>
          </div>
        </Modal>
      ) : null}
    </SettingsPageWrapper>
  );
};

export default PluginMarketSettings;
