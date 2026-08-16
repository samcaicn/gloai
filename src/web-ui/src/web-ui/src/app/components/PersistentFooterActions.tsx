/**
 * PersistentFooterActions — tupai 阶段 3 侧栏底部操作。
 *
 * 仅保留 1 个按钮："迷你模式"。
 * 点击调用 Tauri command `fw_open` 打开浮窗。
 *
 * 与 app/components/NavPanel/components/PersistentFooterActions（旧版，5 项）不同，
 * 本组件由 NavPanel 在侧栏底部渲染。
 */

import React, { useCallback } from 'react';
import { invoke } from '@/infrastructure/api/tupai/invoke';
import { useI18n } from '@/infrastructure/i18n';
import { createLogger } from '@/shared/utils/logger';
import './PersistentFooterActions.scss';

const log = createLogger('PersistentFooterActions');

const PersistentFooterActions: React.FC = () => {
  const { t } = useI18n('common');

  const handleOpenMini = useCallback(async () => {
    try {
      await invoke('fw_open', {
        input: {
          id: 'main-mini-' + Date.now(),
          url: 'index.html#/mini',
          title: t('footer.miniMode'),
          width: 320,
          height: 480,
          decorations: false,
          alwaysOnTop: true,
          skipTaskbar: true,
        },
      });
    } catch (error) {
      log.error('Failed to open mini window', error);
    }
  }, [t]);

  return (
    <div className="tupai-footer-actions">
      <button
        type="button"
        className="tupai-footer-actions__btn"
        onClick={handleOpenMini}
        aria-label={t('footer.miniMode')}
      >
        {t('footer.miniMode')}
      </button>
    </div>
  );
};

export default PersistentFooterActions;
