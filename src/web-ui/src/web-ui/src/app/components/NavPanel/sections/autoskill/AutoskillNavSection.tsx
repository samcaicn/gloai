/**
 * AutoskillNavSection — 侧栏"自进化"活动入口。
 *
 * 始终显示一个入口（Sparkles 图标 + 标签），右侧在有待确认草稿时显示
 * 红色数字徽章。点击打开 AutoskillScene。mount 时后台 loadPendingCount()。
 */

import React, { useEffect } from 'react';
import { Sparkles } from 'lucide-react';
import { listen } from '@tauri-apps/api/event';
import { Tooltip } from '@/component-library';
import { useSceneManager } from '@/app/hooks/useSceneManager';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { useSceneStore } from '@/app/stores/sceneStore';
import { isTauriRuntime } from '@/infrastructure/runtime';
import { useAutoskillNavStore } from './autoskillNavStore';
import './AutoskillNavSection.scss';

const AutoskillNavSection: React.FC = () => {
  const { openScene } = useSceneManager();
  const { t } = useI18n('common');
  const activeTabId = useSceneStore(s => s.activeTabId);

  const pendingCount = useAutoskillNavStore(s => s.pendingCount);
  const loadPendingCount = useAutoskillNavStore(s => s.loadPendingCount);

  // mount 时后台加载 pending 数量（使用缓存）
  useEffect(() => {
    void loadPendingCount();
    // 非 Tauri 环境下跳过事件监听，避免 transformCallback 错误
    if (!isTauriRuntime()) {
      return;
    }
    // 监听后台扫描完成事件，强制刷新徽章
    const unlistenP = listen('autoskill://drafts-updated', () => {
      void loadPendingCount(true);
    });
    return () => {
      void unlistenP.then(fn => fn());
    };
  }, [loadPendingCount]);

  const isActive = activeTabId === 'autoskill';
  const tooltip = t('scenes.autoskill');

  return (
    <div className="bitfun-autoskill-nav">
      <Tooltip content={tooltip} placement="right" followCursor>
        <button
          type="button"
          className={`bitfun-autoskill-nav__entry${isActive ? ' is-active' : ''}`}
          onClick={() => openScene('autoskill')}
          aria-label={tooltip}
        >
          <span className="bitfun-autoskill-nav__icon" aria-hidden="true">
            <Sparkles size={15} />
          </span>
          <span className="bitfun-autoskill-nav__label">{tooltip}</span>
          {pendingCount > 0 && (
            <span className="bitfun-autoskill-nav__badge" aria-label={`${pendingCount} pending`}>
              {pendingCount > 99 ? '99+' : pendingCount}
            </span>
          )}
        </button>
      </Tooltip>
    </div>
  );
};

export default React.memo(AutoskillNavSection);
