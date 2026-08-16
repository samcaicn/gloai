/**
 * TupaiCloudModelsSection
 *
 * Read-only catalog of the tupAI cloud models that the local
 * embedded Hermes gateway exposes via `/v1/models`. The catalog
 * is the curated list baked into `hermes::model_catalog`, so
 * anything we render here is guaranteed to be a model the
 * gateway can actually route to.
 *
 * This section intentionally does NOT mutate any user state — it
 * is purely informational. The active model pin (if any) is
 * surfaced as a badge so the user can see what the dashboard
 * already has selected.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Cloud, RefreshCw, Loader, AlertTriangle } from 'lucide-react';
import { IconButton, CubeLoading } from '@/component-library';
import { ConfigPageSection, ConfigPageRow } from './common';
import { aiApi } from '@/infrastructure/api/service-api/AIApi';
import type { TupaiCloudModel } from '@/infrastructure/api/service-api/AIApi';
import { notificationService } from '@/shared/notification-system';
import { createLogger } from '@/shared/utils/logger';
import './TupaiCloudModelsSection.scss';

const log = createLogger('TupaiCloudModelsSection');

interface ProviderGroup {
  provider: string;
  models: TupaiCloudModel[];
}

const sortProviderGroups = (groups: ProviderGroup[]): ProviderGroup[] => {
  return [...groups].sort((a, b) => {
    if (a.provider === 'tupai') return -1;
    if (b.provider === 'tupai') return 1;
    return a.provider.localeCompare(b.provider);
  });
};

export const TupaiCloudModelsSection: React.FC = () => {
  const { t } = useTranslation('settings/ai-model');
  const [models, setModels] = useState<TupaiCloudModel[]>([]);
  const [activeModel, setActiveModel] = useState<string | null>(null);
  const [activeProvider, setActiveProvider] = useState<string | null>(null);
  const [source, setSource] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const fetchModels = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await aiApi.listTupaiCloudModels();
      setModels(Array.isArray(result.models) ? result.models : []);
      setActiveModel(result.activeModel ?? null);
      setActiveProvider(result.activeProvider ?? null);
      setSource(result.source ?? '');
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn('Failed to fetch tupAI cloud models', { error: message });
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void fetchModels();
  }, [fetchModels]);

  const handleRefresh = useCallback(async () => {
    await fetchModels();
    if (!error) {
      notificationService.success(t('tupaiCloud.refresh'), { duration: 1500 });
    } else {
      notificationService.error(t('tupaiCloud.loadFailed', { error }));
    }
  }, [fetchModels, error, t]);

  const providerGroups = useMemo<ProviderGroup[]>(() => {
    const map = new Map<string, TupaiCloudModel[]>();
    for (const model of models) {
      const key = model.provider || t('tupaiCloud.noProvider');
      const list = map.get(key) ?? [];
      list.push(model);
      map.set(key, list);
    }
    return sortProviderGroups(
      Array.from(map.entries()).map(([provider, items]) => ({
        provider,
        models: items,
      }))
    );
  }, [models, t]);

  return (
    <ConfigPageSection
      title={t('tupaiCloud.sectionTitle')}
      description={t('tupaiCloud.sectionDescription')}
      extra={(
        <IconButton
          variant="ghost"
          size="small"
          onClick={handleRefresh}
          disabled={loading}
          tooltip={t('tupaiCloud.refresh')}
        >
          {loading
            ? <Loader size={16} className="bitfun-tupai-cloud__spin" />
            : <RefreshCw size={16} />}
        </IconButton>
      )}
    >
      {loading ? (
        <div className="bitfun-tupai-cloud__loading">
          <CubeLoading size="small" />
        </div>
      ) : error ? (
        <div className="bitfun-tupai-cloud__error">
          <AlertTriangle size={16} />
          <span>{t('tupaiCloud.loadFailed', { error })}</span>
        </div>
      ) : models.length === 0 ? (
        <div className="bitfun-tupai-cloud__empty">
          <Cloud size={24} />
          <span>{t('tupaiCloud.empty')}</span>
        </div>
      ) : (
        <>
          {activeModel && (
            <ConfigPageRow label={t('tupaiCloud.active')} align="center">
              <span className="bitfun-tupai-cloud__active-pill">
                {activeProvider ? `${activeProvider} / ${activeModel}` : activeModel}
              </span>
            </ConfigPageRow>
          )}
          <div className="bitfun-tupai-cloud__groups">
            {providerGroups.map(group => (
              <div key={group.provider} className="bitfun-tupai-cloud__group">
                <div className="bitfun-tupai-cloud__group-header">
                  <span className="bitfun-tupai-cloud__group-name">{group.provider}</span>
                  <span className="bitfun-tupai-cloud__group-count">
                    {t('tupaiCloud.providerGroup', {
                      provider: group.provider,
                      count: group.models.length,
                    })}
                  </span>
                </div>
                <div className="bitfun-tupai-cloud__model-list">
                  {group.models.map(model => (
                    <span
                      key={model.id}
                      className="bitfun-tupai-cloud__model-tag"
                      title={model.displayName && model.displayName !== model.id
                        ? `${model.displayName} (${model.id})`
                        : model.id}
                    >
                      {model.displayName && model.displayName !== model.id
                        ? model.displayName
                        : model.id}
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
          {source && (
            <p className="bitfun-tupai-cloud__source">{source}</p>
          )}
        </>
      )}
    </ConfigPageSection>
  );
};

export default TupaiCloudModelsSection;
