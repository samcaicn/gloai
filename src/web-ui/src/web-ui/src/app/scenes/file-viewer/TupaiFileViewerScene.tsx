/**
 * TupaiFileViewerScene — 技能查看器（UI-5-3）。
 *
 * 左侧：技能列表（skillList）
 * 右侧：选中技能的元信息、执行按钮（skillExecute）、SKILL.md 原文（skillLoad 返回的 content）
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, Play, RefreshCw } from 'lucide-react';
import { skillExecute, skillList, skillLoad, reportSkillFailure, reportSkillSuccess } from '@/infrastructure/api/tupai';
import { useStarRatingStore } from '@/flow_chat/store/starRatingStore';
import type { Skill, SkillMeta } from '@/infrastructure/api/tupai';
import { ProcessingIndicator } from '@/flow_chat/components/modern/ProcessingIndicator';
import { createLogger } from '@/shared/utils/logger';
import './TupaiFileViewerScene.scss';

const log = createLogger('TupaiFileViewerScene');

const TupaiFileViewerScene: React.FC = () => {
  const { t } = useTranslation('common');
  const [skills, setSkills] = useState<SkillMeta[]>([]);
  const [loadingList, setLoadingList] = useState<boolean>(false);
  const [listError, setListError] = useState<string | null>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<Skill | null>(null);
  const [loadingDetail, setLoadingDetail] = useState<boolean>(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const [executeOutput, setExecuteOutput] = useState<string>('');
  const [executing, setExecuting] = useState<boolean>(false);

  // 加载技能列表
  const loadSkills = useCallback(async () => {
    setLoadingList(true);
    setListError(null);
    try {
      const list = await skillList();
      setSkills(list);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load skill list', err);
      setListError(message);
    } finally {
      setLoadingList(false);
    }
  }, []);

  useEffect(() => {
    void loadSkills();
  }, [loadSkills]);

  // 选中技能：加载详情
  const handleSelect = useCallback(async (skillId: string) => {
    setSelectedId(skillId);
    setDetail(null);
    setDetailError(null);
    setExecuteOutput('');
    setLoadingDetail(true);
    try {
      const result = await skillLoad(skillId);
      setDetail(result);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to load skill detail', { skillId, error: err });
      setDetailError(message);
    } finally {
      setLoadingDetail(false);
    }
  }, []);

  // 执行当前选中技能
  const handleExecute = useCallback(async () => {
    if (!selectedId) return;
    setExecuting(true);
    setExecuteOutput('');
    const startedAt = performance.now();
    try {
      const result = await skillExecute(selectedId, {});
      const text = result.success
        ? t('fileViewerScene.execSuccess', { output: result.output ?? '' })
        : t('fileViewerScene.execFailed', { error: result.error ? `：${result.error}` : '' });
      setExecuteOutput(text);
      // 静默上报：success=true 上报成功，否则上报逻辑失败
      if (result.success) {
        reportSkillSuccess(selectedId, result.output, performance.now() - startedAt);
        // 请求用户星级评分
        useStarRatingStore.getState().promptRating(selectedId, selectedId);
      } else {
        reportSkillFailure(selectedId, result.error || 'execution returned success=false');
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.error('Failed to execute skill', { skillId: selectedId, error: err });
      setExecuteOutput(t('fileViewerScene.execError', { message }));
      // 静默上报执行失败
      reportSkillFailure(selectedId, message);
    } finally {
      setExecuting(false);
    }
  }, [selectedId, t]);

  return (
    <div className="tupai-file-viewer">
      {/* 左侧：技能列表 */}
      <aside className="tupai-file-viewer__sidebar">
        <div className="tupai-file-viewer__sidebar-header">
          <span className="tupai-file-viewer__sidebar-title">{t('fileViewerScene.skillList')}</span>
          <button
            type="button"
            className="tupai-file-viewer__icon-btn"
            onClick={() => void loadSkills()}
            disabled={loadingList}
            aria-label={t('fileViewerScene.refreshSkillList')}
            title={t('fileViewerScene.refreshSkillList')}
          >
            <RefreshCw size={14} />
          </button>
        </div>
        {loadingList ? (
          <div className="tupai-file-viewer__status">
            <ProcessingIndicator visible />
          </div>
        ) : listError ? (
          <div className="tupai-file-viewer__error">
            <AlertTriangle size={16} />
            <span>{listError}</span>
          </div>
        ) : skills.length === 0 ? (
          <div className="tupai-file-viewer__empty">{t('fileViewerScene.noSkills')}</div>
        ) : (
          <ul className="tupai-file-viewer__skill-list">
            {skills.map((s) => (
              <li key={s.skill_id}>
                <button
                  type="button"
                  className={`tupai-file-viewer__skill-item${selectedId === s.skill_id ? ' tupai-file-viewer__skill-item--active' : ''}`}
                  onClick={() => void handleSelect(s.skill_id)}
                >
                  <span className="tupai-file-viewer__skill-name">{s.title}</span>
                  {s.category ? (
                    <span className="tupai-file-viewer__skill-cat">{s.category}</span>
                  ) : null}
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      {/* 右侧：技能详情 */}
      <section className="tupai-file-viewer__main">
        {!selectedId ? (
          <div className="tupai-file-viewer__placeholder">{t('fileViewerScene.selectSkill')}</div>
        ) : (
          <div className="tupai-file-viewer__detail">
            {loadingDetail ? (
              <div className="tupai-file-viewer__status">
                <ProcessingIndicator visible />
              </div>
            ) : detailError ? (
              <div className="tupai-file-viewer__error">
                <AlertTriangle size={16} />
                <span>{t('fileViewerScene.loadDetailFailed', { error: detailError })}</span>
              </div>
            ) : detail ? (
              <>
                <header className="tupai-file-viewer__detail-header">
                  <h3 className="tupai-file-viewer__detail-title">{detail.title}</h3>
                  <div className="tupai-file-viewer__detail-meta">
                    {detail.category ? (
                      <span className="tupai-file-viewer__chip">{detail.category}</span>
                    ) : null}
                    {detail.version ? (
                      <span className="tupai-file-viewer__chip">v{detail.version}</span>
                    ) : null}
                  </div>
                </header>
                {detail.description ? (
                  <p className="tupai-file-viewer__detail-desc">{detail.description}</p>
                ) : null}
                <div className="tupai-file-viewer__actions">
                  <button
                    type="button"
                    className="tupai-file-viewer__btn tupai-file-viewer__btn--primary"
                    onClick={() => void handleExecute()}
                    disabled={executing}
                  >
                    <Play size={14} />
                    <span>{executing ? t('fileViewerScene.executing') : t('fileViewerScene.execute')}</span>
                  </button>
                </div>
                {executeOutput ? (
                  <div className="tupai-file-viewer__block">
                    <div className="tupai-file-viewer__block-label">{t('fileViewerScene.executeOutput')}</div>
                    <pre className="tupai-file-viewer__pre">{executeOutput}</pre>
                  </div>
                ) : null}
                <div className="tupai-file-viewer__block">
                  <div className="tupai-file-viewer__block-label">{t('fileViewerScene.skillContent')}</div>
                  <pre className="tupai-file-viewer__pre">{detail.content}</pre>
                </div>
              </>
            ) : null}
          </div>
        )}
      </section>
    </div>
  );
};

export default TupaiFileViewerScene;
