import React, { useCallback } from 'react';
import { PictureInPicture2 } from 'lucide-react';
import { Tooltip } from '@/component-library';
import { useI18n } from '@/infrastructure/i18n/hooks/useI18n';
import { createLogger } from '@/shared/utils/logger';
import { fwOpen } from '@/infrastructure/api/tupai/floater';

const log = createLogger('PersistentFooterActions');

const PersistentFooterActions: React.FC = () => {
  const { t } = useI18n('common');

  const handleMiniMode = useCallback(async () => {
    try {
      await fwOpen({
        id: 'main-mini-' + Date.now(),
        title: 'tupai Mini',
        width: 320,
        height: 240,
      });
    } catch (err) {
      log.error('Failed to open mini mode floating window', err);
    }
  }, []);

  return (
    <div className="bitfun-nav-panel__footer">
      <div className="bitfun-nav-panel__footer-left">
        <Tooltip content={t('footer.miniMode')} placement="right">
          <button
            type="button"
            className="bitfun-nav-panel__footer-btn bitfun-nav-panel__footer-btn--icon"
            aria-label={t('footer.miniMode')}
            onClick={handleMiniMode}
          >
            <PictureInPicture2 size={15} aria-hidden="true" />
          </button>
        </Tooltip>
      </div>
    </div>
  );
};

export default PersistentFooterActions;
