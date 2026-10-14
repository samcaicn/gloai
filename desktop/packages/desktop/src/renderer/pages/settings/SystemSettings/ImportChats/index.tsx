/**
 * The conversations page's way in for Claude Code and Codex conversations: one row, and the dialog it opens
 * (./ImportChatsModal.tsx), which looks for them only once it is open. An action, not a setting: nothing here is
 * stored.
 */
import { Button } from '@arco-design/web-react';
import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import PreferenceRow from '@/renderer/components/settings/SettingsModal/contents/SystemModalContent/PreferenceRow';
import ImportChatsModal from './ImportChatsModal';

const ImportChats: React.FC = () => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  // A list of its own under the page's rows (settings.css): a hairline above, no box around it.
  return (
    <>
      <div className='settings-list' data-testid='import-chats'>
        <PreferenceRow label={t('mu.importChats.title')} description={t('mu.importChats.description')}>
          <Button onClick={() => setOpen(true)}>{t('mu.importChats.open')}</Button>
        </PreferenceRow>
      </div>
      {open ? <ImportChatsModal visible onClose={() => setOpen(false)} /> : null}
    </>
  );
};

export default ImportChats;
