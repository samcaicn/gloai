/**
 * SettingsOverlay — 设置浮层。
 *
 * 不再作为顶栏 tab 出现：复用 Modal 作为浮层容器，内部左右分栏复用
 * `SettingsNav`（左侧导航 + 搜索）与 `SettingsScene`（右侧配置内容）。
 *
 * 浮层显隐由 `useSettingsOverlayStore` 驱动，所有入口（齿轮按钮、状态
 * 指示灯、quickActions.openSettings 等）调用 `openSettingsOverlay(tab?)`
 * 即可拉起浮层并定位到指定 tab。
 *
 * 跨平台：基于 Modal 组件（Portal 到 document.body），Windows / macOS
 * 表现一致；不依赖原生窗口装饰，也不走 Tauri floating-window webview，
 * 避免单独开窗带来的状态同步成本。
 */

import React, { Suspense } from 'react';
import { Modal } from '@/component-library';
import { useI18n } from '@/infrastructure/i18n';
import { useSettingsOverlayStore } from './settingsOverlayStore';
import { useSettingsStore } from './settingsStore';
import SettingsScene from './SettingsScene';
import SettingsNav from './SettingsNav';
import './SettingsOverlay.scss';

const SettingsOverlay: React.FC = () => {
  const { t } = useI18n('common');
  const isOpen = useSettingsOverlayStore((s) => s.isOpen);
  const close = useSettingsOverlayStore((s) => s.close);
  const activeTab = useSettingsStore((s) => s.activeTab);

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      title={t('settingsOverlay.title')}
      titleExtra={
        <span className="bitfun-settings-overlay__autosave-hint">
          {t('settingsOverlay.autosaveHint')}
        </span>
      }
      size="xlarge"
      closeOnOverlayClick
      showCloseButton
      contentInset
      draggable
      resizable
      overlayClassName="bitfun-settings-overlay-host"
      contentClassName="bitfun-settings-overlay__content"
    >
      <div className="bitfun-settings-overlay" role="dialog" aria-label={t('settingsOverlay.title')}>
        <aside className="bitfun-settings-overlay__nav" aria-hidden={!isOpen}>
          <Suspense fallback={null}>
            <SettingsNav />
          </Suspense>
        </aside>
        <section className="bitfun-settings-overlay__scene" key={String(activeTab)}>
          <Suspense fallback={null}>
            <SettingsScene />
          </Suspense>
        </section>
      </div>
    </Modal>
  );
};

export default SettingsOverlay;
