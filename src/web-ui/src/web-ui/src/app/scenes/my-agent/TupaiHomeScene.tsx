/**
 * TupaiHomeScene — tupai 主页（技能市场）。
 *
 * 实现「全面改写计划.md」UI-4-1：
 *  - Section 1: 顶部搜索栏
 *  - Section 2: 技能市场（navSkillsStore.displaySkills 按优先级排序 + 客户端过滤）
 *  - Section 3: 快捷功能入口
 *  - Section 4: 设备状态
 *
 * 技能展示优先级（复用 navSkillsStore.mergeSkills 逻辑）：
 *   1. 内置自带技能 (builtin)
 *   2. 租户自有技能 (tenant)
 *   3. 平台标签技能 (platform tag)
 *   4. 本地已安装技能
 *   5. 其他市场技能
 *   6. 上次搜索结果
 *
 * 替代原 InsightsScene 作为 tupai 主页，渲染在 SceneViewport 的
 * 'insights' case 中。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Search, RefreshCw, Play, Globe, Puzzle, MessageSquare,
  RadioTower, AlertTriangle,
} from 'lucide-react';
import { skillExecute, reportSkillFailure, reportSkillSuccess, fetchSkillParams } from '@/infrastructure/api/tupai';
import { useStarRatingStore } from '@/flow_chat/store/starRatingStore';
import type { SkillMeta } from '@/infrastructure/api/tupai';
import type { ParamField } from '@/infrastructure/api/tupai/skill';
import { SkillParamModal } from '@/app/components/SkillParamModal/SkillParamModal';
import { useNavSkillsStore } from '@/app/components/NavPanel/sections/skills/navSkillsStore';
import { useSceneManager } from '../../hooks/useSceneManager';
import { openSettingsOverlay } from '../settings/settingsOverlayStore';
import { notificationService } from '@/shared/notification-system';
import { ProcessingIndicator } from '@/flow_chat/components/modern/ProcessingIndicator';
import { createLogger } from '@/shared/utils/logger';
import './TupaiHomeScene.scss';

const log = createLogger('TupaiHomeScene');

const DEVICE_TOKEN_KEY = 'trae_device_token';

/** 快捷功能入口定义。 */
interface ShortcutDef {
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
}

function readDeviceConnected(): boolean {
  try {
    const token = localStorage.getItem(DEVICE_TOKEN_KEY);
    return Boolean(token && token.length > 0);
  } catch (error) {
    log.warn('Failed to read device token from localStorage', error);
    return false;
  }
}

const TupaiHomeScene: React.FC = () => {
  const { t } = useTranslation('common');
  const { openScene } = useSceneManager();

  const skills = useNavSkillsStore(s => s.displaySkills);
  const loading = useNavSkillsStore(s => s.loading);
  const storeError = useNavSkillsStore(s => s.error);
  const loadSkillsFromStore = useNavSkillsStore(s => s.loadSkills);

  const [executingId, setExecutingId] = useState<string | null>(null);

  const [searchQuery, setSearchQuery] = useState<string>('');
  const [searchInput, setSearchInput] = useState<string>('');

  const [deviceConnected, setDeviceConnected] = useState<boolean>(() => readDeviceConnected());

  const loadSkills = useCallback(() => {
    return loadSkillsFromStore(true);
  }, [loadSkillsFromStore]);

  useEffect(() => {
    void loadSkills();
  }, [loadSkills]);

  // 窗口重新获得焦点时刷新设备状态（token 可能已被主窗口修改）。
  useEffect(() => {
    const refresh = () => setDeviceConnected(readDeviceConnected());
    window.addEventListener('focus', refresh);
    window.addEventListener('storage', refresh);
    window.addEventListener('tupai:device-token-changed', refresh);
    return () => {
      window.removeEventListener('focus', refresh);
      window.removeEventListener('storage', refresh);
      window.removeEventListener('tupai:device-token-changed', refresh);
    };
  }, []);

  // ---- 搜索（客户端过滤）----
  const filteredSkills = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return skills;
    return skills.filter((s) => {
      return (
        s.title.toLowerCase().includes(q) ||
        s.description.toLowerCase().includes(q)
      );
    });
  }, [skills, searchQuery]);

  const handleSearch = useCallback(() => {
    setSearchQuery(searchInput);
  }, [searchInput]);

  const handleSearchKeyDown = useCallback((e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      handleSearch();
    }
  }, [handleSearch]);

  // ---- 技能参数确认弹窗 ----
  const [pendingSkill, setPendingSkill] = useState<{
    skillId: string;
    skillName: string;
    skillDescription: string;
    params: ParamField[];
  } | null>(null);

  // ---- 技能执行（实际执行） ----
  const doExecute = useCallback(async (skillId: string, params: Record<string, unknown>) => {
    if (executingId) return;
    setExecutingId(skillId);
    const startedAt = performance.now();
    try {
      const result = await skillExecute(skillId, params);
      if (result.success) {
        const preview = result.output?.slice(0, 200) || '';
        notificationService.success(
          t('tupaiHome.skillExecSuccess', { title: skillId, preview: preview ? `：${preview}` : '' })
        );
        reportSkillSuccess(skillId, result.output, performance.now() - startedAt);
        // 请求用户星级评分
        useStarRatingStore.getState().promptRating(skillId, skillId);
      } else {
        notificationService.error(
          t('tupaiHome.skillExecFailed', { title: skillId, error: result.error ? `：${result.error}` : '' })
        );
        reportSkillFailure(skillId, result.error || 'execution returned success=false');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to execute skill', { skillId, error: err });
      notificationService.error(t('tupaiHome.skillExecError', { title: skillId, message }));
      reportSkillFailure(skillId, message);
    } finally {
      setExecutingId(null);
    }
  }, [executingId, t]);

  // ---- 技能点击：仅当有参数时才弹确认窗，否则直接执行 ----
  const handleSkillClick = useCallback(async (skill: SkillMeta) => {
    if (executingId) return;
    const skillId = skill.skill_id;
    const skillName = skill.title || skillId;
    const skillDescription = skill.description || '';

    // 异步获取参数 schema（仅 builtin 技能有）
    let params: ParamField[] = [];
    try {
      const fetched = await fetchSkillParams(skillId);
      if (fetched) params = fetched;
    } catch { /* ignore */ }

    if (params.length > 0) {
      // 有参数定义 → 弹窗让用户填写
      setPendingSkill({ skillId, skillName, skillDescription, params });
    } else {
      // 无参数 → 直接执行
      void doExecute(skillId, {});
    }
  }, [executingId, doExecute]);

  const handleSkillConfirm = useCallback((values: Record<string, unknown>) => {
    if (!pendingSkill) return;
    const { skillId } = pendingSkill;
    setPendingSkill(null);
    void doExecute(skillId, values);
  }, [pendingSkill, doExecute]);

  const handleSkillSkip = useCallback(() => {
    if (!pendingSkill) return;
    const { skillId } = pendingSkill;
    setPendingSkill(null);
    void doExecute(skillId, {});
  }, [pendingSkill, doExecute]);

  const handleSkillModalClose = useCallback(() => {
    setPendingSkill(null);
  }, []);

  // ---- 快捷功能入口 ----
  const shortcuts: ShortcutDef[] = useMemo(() => [
    {
      label: t('tupaiHome.shortcuts.recordAutomation'),
      icon: <Globe size={18} />,
      onClick: () => openScene('browser'),
    },
    {
      label: t('tupaiHome.shortcuts.skillManagement'),
      icon: <Puzzle size={18} />,
      onClick: () => openScene('skills', t('scenes.skills')),
    },
    {
      label: t('tupaiHome.shortcuts.sessionHistory'),
      icon: <MessageSquare size={18} />,
      onClick: () => openScene('session'),
    },
    {
      label: t('tupaiHome.shortcuts.imChannels'),
      icon: <RadioTower size={18} />,
      onClick: () => {
        openScene('session');
        window.dispatchEvent(new CustomEvent('session:open-im'));
      },
    },
  ], [openScene, t]);

  // ---- 设备未连接时跳转设置 ----
  const handleGoToSettings = useCallback(() => {
    // 设置已改为浮层展示：直接拉起 overlay 并定位到设备 tab (tupai)。
    openSettingsOverlay('tupai');
  }, []);

  return (
    <div className="tupai-home">
      {/* Section 1: 顶部搜索栏 */}
      <div className="tupai-home__search">
        <input
          className="tupai-home__search-input"
          type="text"
          placeholder={t('tupaiHome.searchPlaceholder')}
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onKeyDown={handleSearchKeyDown}
        />
        <button
          className="tupai-home__search-btn"
          type="button"
          onClick={handleSearch}
          disabled={loading}
        >
          <Search size={14} />
          <span>{t('tupaiHome.search')}</span>
        </button>
        <button
          className="tupai-home__refresh-btn"
          type="button"
          onClick={() => void loadSkills()}
          disabled={loading}
          title={t('tupaiHome.reloadSkills')}
        >
          <RefreshCw size={14} />
          <span>{t('tupaiHome.refresh')}</span>
        </button>
      </div>

      {/* Section 2: 技能市场 */}
      <section className="tupai-home__market">
        <h3 className="tupai-home__section-title">{t('tupaiHome.skillMarket')}</h3>
        {loading ? (
          <div className="tupai-home__status-row">
            <ProcessingIndicator visible />
          </div>
        ) : storeError ? (
          <div className="tupai-home__error">
            <AlertTriangle size={18} />
            <span>{t('tupaiHome.loadFailed', { error: storeError })}</span>
            <button
              className="tupai-home__refresh-btn"
              type="button"
              onClick={() => void loadSkills()}
            >
              <RefreshCw size={14} />
              <span>{t('tupaiHome.retry')}</span>
            </button>
          </div>
        ) : filteredSkills.length === 0 ? (
          <div className="tupai-home__empty">
            {searchQuery ? t('tupaiHome.noMatch') : t('tupaiHome.noSkills')}
          </div>
        ) : (
          <div className="tupai-home__skill-grid">
            {filteredSkills.map((skill) => (
              <button
                key={skill.skill_id}
                className="tupai-home__skill-card"
                type="button"
                onClick={() => void handleSkillClick(skill)}
                disabled={executingId === skill.skill_id}
              >
                <div className="tupai-home__skill-title">{skill.title}</div>
                <p className="tupai-home__skill-desc">{skill.description}</p>
                <div className="tupai-home__skill-meta">
                  {skill.category && (
                    <span className="tupai-home__skill-tag">{skill.category}</span>
                  )}
                  {skill.version && (
                    <span className="tupai-home__skill-tag">v{skill.version}</span>
                  )}
                  {skill.source && (
                    <span className="tupai-home__skill-tag tupai-home__skill-tag--source">
                      {skill.source}
                    </span>
                  )}
                  {executingId === skill.skill_id && (
                    <span className="tupai-home__skill-tag">
                      <Play size={10} /> {t('tupaiHome.executing')}
                    </span>
                  )}
                </div>
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Section 3: 快捷功能入口 */}
      <section className="tupai-home__shortcuts">
        <h3 className="tupai-home__section-title">{t('tupaiHome.quickActions')}</h3>
        <div className="tupai-home__shortcut-grid">
          {shortcuts.map((sc) => (
            <button
              key={sc.label}
              className="tupai-home__shortcut-card"
              type="button"
              onClick={sc.onClick}
            >
              <span className="tupai-home__shortcut-icon">{sc.icon}</span>
              <span className="tupai-home__shortcut-label">{sc.label}</span>
            </button>
          ))}
        </div>
      </section>

      {/* Section 4: 设备状态 */}
      <section className="tupai-home__device">
        <h3 className="tupai-home__section-title">{t('tupaiHome.deviceStatus')}</h3>
        <div className="tupai-home__device-row">
          <span
            className={[
              'tupai-home__device-badge',
              deviceConnected
                ? 'tupai-home__device-badge--connected'
                : 'tupai-home__device-badge--disconnected',
            ].join(' ')}
          >
            <span className="tupai-home__device-dot" />
            {deviceConnected ? t('tupaiHome.connected') : t('tupaiHome.disconnected')}
          </span>
          {!deviceConnected && (
            <button
              className="tupai-home__device-btn"
              type="button"
              onClick={handleGoToSettings}
            >
              {t('tupaiHome.goToSettings')}
            </button>
          )}
        </div>
      </section>

      {/* ── 技能参数确认弹窗 ── */}
      <SkillParamModal
        isOpen={!!pendingSkill}
        skillName={pendingSkill?.skillName ?? ''}
        skillDescription={pendingSkill?.skillDescription ?? ''}
        skillContent=""
        params={pendingSkill?.params ?? []}
        onConfirm={handleSkillConfirm}
        onSkip={handleSkillSkip}
        onClose={handleSkillModalClose}
      />
    </div>
  );
};

export default TupaiHomeScene;
