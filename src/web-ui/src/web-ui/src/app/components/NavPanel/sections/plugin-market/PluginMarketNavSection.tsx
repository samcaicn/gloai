/**
 * PluginMarketNavSection — 侧边栏「插件市场」入口按钮。
 *
 * 一行：Boxes 图标 + "插件市场" 文字。点击打开插件市场场景
 * （PluginMarketScene）。与 SkillsNavSection 同构，遵循
 * "everything is a plugin" 的统一入口。
 */

import React, { useCallback } from 'react';
import { Boxes } from 'lucide-react';
import { useSceneManager } from '@/app/hooks/useSceneManager';
import { useTranslation } from 'react-i18next';
import './PluginMarketNavSection.scss';

const PluginMarketNavSection: React.FC = () => {
  const { openScene, activeTabId } = useSceneManager();
  const { t } = useTranslation('scenes/plugin-market');

  const isActive = activeTabId === 'plugin-market';

  const handleClick = useCallback(() => {
    openScene('plugin-market', t('navLabel'));
  }, [openScene, t]);

  return (
    <div className="bitfun-pluginmarket-nav">
      <button
        type="button"
        className={`bitfun-pluginmarket-nav__entry${isActive ? ' is-active' : ''}`}
        onClick={handleClick}
        aria-label={t('navLabel')}
        title={t('navLabel')}
      >
        <span className="bitfun-pluginmarket-nav__entry-icon" aria-hidden="true">
          <Boxes size={15} />
        </span>
        <span className="bitfun-pluginmarket-nav__entry-label">{t('navLabel')}</span>
      </button>
    </div>
  );
};

export default React.memo(PluginMarketNavSection);
