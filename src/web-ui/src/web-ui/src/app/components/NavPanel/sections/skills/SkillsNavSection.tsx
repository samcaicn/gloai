/**
 * SkillsNavSection — 侧边栏技能入口按钮。
 *
 * 仅显示一行：Puzzle图标 + "技能"文字 + 数量徽章。
 * 点击打开技能场景（TupaiSkillsScene）。
 * 技能列表展示已移至技能页面本身。
 */

import React, { useCallback, useEffect } from 'react';
import { Puzzle } from 'lucide-react';
import { useSceneManager } from '@/app/hooks/useSceneManager';
import { useNavSkillsStore } from './navSkillsStore';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import './SkillsNavSection.scss';

interface SkillsNavSectionProps {
  isOpen?: boolean;
  onToggle?: () => void;
}

const SkillsNavSection: React.FC<SkillsNavSectionProps> = () => {
  const { openScene, activeTabId } = useSceneManager();
  const { t } = useI18n('common');

  const skills = useNavSkillsStore(s => s.skills);
  const loadSkills = useNavSkillsStore(s => s.loadSkills);

  const isSkillsActive = activeTabId === 'skills';

  // 后台预加载缓存（有缓存则用缓存）
  useEffect(() => {
    void loadSkills();
  }, [loadSkills]);

  const handleHeaderClick = useCallback(
    () => {
      openScene('skills', t('scenes.skills'));
    },
    [openScene, t],
  );

  const totalCount = skills.length;

  return (
    <div className="bitfun-skills-nav">
      <button
        type="button"
        className={`bitfun-skills-nav__entry${isSkillsActive ? ' is-active' : ''}`}
        onClick={handleHeaderClick}
        aria-label={t('nav.items.skills')}
        title={t('nav.items.skills')}
      >
        <span className="bitfun-skills-nav__entry-icon" aria-hidden="true">
          <Puzzle size={15} />
        </span>
        <span className="bitfun-skills-nav__entry-label">{t('nav.items.skills')}</span>
        {totalCount > 0 && (
          <span className="bitfun-skills-nav__entry-count">{totalCount}</span>
        )}
      </button>
    </div>
  );
};

export default React.memo(SkillsNavSection);
