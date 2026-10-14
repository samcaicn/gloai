import { Button, Message, Modal, Space, Typography } from '@arco-design/web-react';
import type { TFunction } from 'i18next';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

// TupAI's own releases: every installer (and every architecture) is attached to a release there.
const MU_RELEASES_URL = 'https://github.com/samcaicn/mu/releases';

type InstallationIntegrityDialogKind =
  | 'incomplete_installation'
  | 'data_migration'
  | 'database_newer_than_app'
  | 'local_data_repair'
  | 'recoverable_database_corruption'
  | 'transient_concurrent_startup'
  | 'startup_directory'
  | 'backend_exited'
  | 'port_report_timeout'
  | 'startup_failed';

export function openDownloadLatest(): void {
  window.open(MU_RELEASES_URL, '_blank', 'noopener,noreferrer');
}

/**
 * Per-kind dialog configuration: which `common.backendStartup.*` section the
 * copy lives in, and which footer actions the dialog offers. One row per kind
 * replaces the previous per-suffix ternary chains. The dialog reports nothing
 * to anyone: its only actions are local (open the log folder, which every kind
 * offers, download the latest release, rebuild a corrupted database).
 */
const DIALOG_KIND_CONFIG: Record<
  InstallationIntegrityDialogKind,
  {
    i18nSection: string;
    showDownloadLatest?: boolean;
    showRecover?: boolean;
  }
> = {
  incomplete_installation: { i18nSection: 'incompleteInstallation', showDownloadLatest: true },
  data_migration: { i18nSection: 'dataMigration' },
  database_newer_than_app: { i18nSection: 'databaseNewerThanApp', showDownloadLatest: true },
  local_data_repair: { i18nSection: 'localDataRepair' },
  recoverable_database_corruption: { i18nSection: 'recoverableDatabaseCorruption', showRecover: true },
  transient_concurrent_startup: { i18nSection: 'transientConcurrentStartup' },
  startup_directory: { i18nSection: 'startupDirectory' },
  backend_exited: { i18nSection: 'exited' },
  port_report_timeout: { i18nSection: 'portReportTimeout' },
  startup_failed: { i18nSection: 'startupFailed' },
};

function dialogKindText(t: TFunction, diagnosticsKind: InstallationIntegrityDialogKind, suffix: string): string {
  return t(`common.backendStartup.${DIALOG_KIND_CONFIG[diagnosticsKind].i18nSection}.${suffix}`);
}

export function getInstallationIntegrityTitle(
  t: TFunction,
  diagnosticsKind: InstallationIntegrityDialogKind = 'incomplete_installation'
): string {
  return dialogKindText(t, diagnosticsKind, 'title');
}

export function getBackendStartupInstallationDescription(t: TFunction): string {
  return t('common.backendStartup.incompleteInstallation.description');
}

export function getRuntimeComponentInstallationDescription(t: TFunction, resource: string): string {
  return t('common.backendStartup.incompleteInstallation.runtimeComponentDescription', { resource });
}

export function getInstallationIntegrityDownloadText(t: TFunction): string {
  return t('common.backendStartup.incompleteInstallation.downloadLatest');
}

export function getInstallationIntegrityModalActions(
  t: TFunction,
  options: {
    diagnosticsKind?: InstallationIntegrityDialogKind;
    onDownloadLatest?: () => void;
    onRecoverCorruptedDatabase?: () => Promise<unknown> | void;
  } = {}
): {
  downloadText?: string;
  onDownloadLatest: () => void;
  onRecoverCorruptedDatabase: () => Promise<unknown> | void;
  recoverText?: string;
} {
  const diagnosticsKind = options.diagnosticsKind ?? 'incomplete_installation';
  const config = DIALOG_KIND_CONFIG[diagnosticsKind];
  return {
    downloadText: config.showDownloadLatest ? getInstallationIntegrityDownloadText(t) : undefined,
    onDownloadLatest: options.onDownloadLatest ?? openDownloadLatest,
    onRecoverCorruptedDatabase: options.onRecoverCorruptedDatabase ?? (() => Promise.resolve()),
    recoverText: config.showRecover ? dialogKindText(t, diagnosticsKind, 'confirmRebuild') : undefined,
  };
}

export function getDownloadLatestModalActionProps(t: TFunction): {
  cancelButtonProps: {
    style: {
      display: 'none';
    };
  };
  okText: string;
  onOk: () => void;
} {
  return {
    okText: getInstallationIntegrityDownloadText(t),
    onOk: openDownloadLatest,
    cancelButtonProps: {
      style: {
        display: 'none',
      },
    },
  };
}

export const InstallationIntegrityContent: React.FC<{ description: string }> = ({ description }) => (
  <div className='text-t-primary' data-testid='installation-integrity-dialog'>
    <Typography.Paragraph className='mb-0 text-t-secondary' data-testid='installation-integrity-description'>
      {description}
    </Typography.Paragraph>
  </div>
);

export const InstallationIntegrityFooter: React.FC<{
  diagnosticsKind?: InstallationIntegrityDialogKind;
}> = ({ diagnosticsKind = 'incomplete_installation' }) => {
  const { t } = useTranslation();
  const [recovering, setRecovering] = useState(false);
  const actions = getInstallationIntegrityModalActions(t, {
    diagnosticsKind,
    onRecoverCorruptedDatabase: () => window.electronAPI?.recoverCorruptedDatabase?.(),
  });

  const handleRecoverCorruptedDatabase = () => {
    if (recovering) return;
    // Rebuild is destructive (backs up the corrupted DB and creates an empty one),
    // so gate it behind an explicit second confirmation before invoking recovery.
    Modal.confirm({
      title: t('common.backendStartup.recoverableDatabaseCorruption.confirmDialog.title'),
      content: t('common.backendStartup.recoverableDatabaseCorruption.confirmDialog.content'),
      okText: t('common.backendStartup.recoverableDatabaseCorruption.confirmDialog.okText'),
      cancelText: t('common.backendStartup.recoverableDatabaseCorruption.confirmDialog.cancelText'),
      onOk: async () => {
        setRecovering(true);
        try {
          await actions.onRecoverCorruptedDatabase();
        } catch {
          Message.error(t('common.backendStartup.recoverableDatabaseCorruption.rebuildFailed'));
          setRecovering(false);
        }
      },
    });
  };

  // Only the desktop app can open a folder.
  const openLogFolder = window.electronAPI?.openLogFolder;
  const handleOpenLogFolder = () => {
    openLogFolder?.().catch(() => Message.error(t('common.backendStartup.openLogsFailed')));
  };

  return (
    <Space>
      {openLogFolder ? (
        <Button data-testid='installation-integrity-open-logs' onClick={handleOpenLogFolder}>
          {t('common.backendStartup.openLogs')}
        </Button>
      ) : null}
      {actions.downloadText ? (
        <Button data-testid='installation-integrity-download' type='primary' onClick={actions.onDownloadLatest}>
          {actions.downloadText}
        </Button>
      ) : null}
      {actions.recoverText ? (
        <Button
          data-testid='recoverable-database-corruption-rebuild'
          loading={recovering}
          status='danger'
          type='outline'
          onClick={handleRecoverCorruptedDatabase}
        >
          {actions.recoverText}
        </Button>
      ) : null}
    </Space>
  );
};

type InstallationIntegrityModalController = ReturnType<typeof Modal.useModal>[0];

export function showInstallationIntegrityModal(
  modal: InstallationIntegrityModalController,
  t: TFunction,
  description: string,
  diagnosticsKind: InstallationIntegrityDialogKind = 'incomplete_installation'
): ReturnType<InstallationIntegrityModalController['error']> {
  return modal.error({
    title: getInstallationIntegrityTitle(t, diagnosticsKind),
    content: <InstallationIntegrityContent description={description} />,
    footer: <InstallationIntegrityFooter diagnosticsKind={diagnosticsKind} />,
    closable: false,
    maskClosable: false,
  });
}

export const InstallationIntegrityModalHost: React.FC<{
  description: string;
  diagnosticsKind?: InstallationIntegrityDialogKind;
}> = ({ description, diagnosticsKind = 'incomplete_installation' }) => {
  const [modal, modalContextHolder] = Modal.useModal();
  const { t } = useTranslation();
  const shownRef = useRef(false);

  useEffect(() => {
    if (shownRef.current) return;
    shownRef.current = true;
    showInstallationIntegrityModal(modal, t, description, diagnosticsKind);
  }, [description, diagnosticsKind, modal, t]);

  return <>{modalContextHolder}</>;
};
