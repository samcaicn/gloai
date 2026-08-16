/**
 * WorkspacePickerDialog — 选择已注册工作区（不新建）。
 *
 * 列出所有已注册工作区,点击即切换(set_workspace),不会创建新记录。
 * 同时保留"浏览其他目录"入口,以便用户选择未注册的文件夹。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  FolderOpen,
  FolderPlus,
  Check,
  Search,
  RefreshCw,
  AlertCircle,
} from 'lucide-react';
import { Modal, Button } from '@/component-library';
import { useWorkspaceContext } from '@/infrastructure/contexts/WorkspaceContext';
import { useI18n } from '@/infrastructure/i18n';
import { createLogger } from '@/shared/utils/logger';
import { getRecentWorkspaceLineParts } from '@/shared/utils/recentWorkspaceDisplay';
import type { WorkspaceInfo } from '@/shared/types';
import './WorkspacePickerDialog.scss';

const log = createLogger('WorkspacePickerDialog');

export interface WorkspacePickerDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSelected?: (workspace: WorkspaceInfo) => void;
}

export const WorkspacePickerDialog: React.FC<WorkspacePickerDialogProps> = ({
  isOpen,
  onClose,
  onSelected,
}) => {
  const { t } = useI18n('common');
  const {
    currentWorkspace,
    listAllWorkspaces,
    selectWorkspace,
    openWorkspace,
  } = useWorkspaceContext();

  const [workspaces, setWorkspaces] = useState<WorkspaceInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectingId, setSelectingId] = useState<string | null>(null);
  const [isBrowsing, setIsBrowsing] = useState(false);

  const refreshList = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const list = await listAllWorkspaces();
      setWorkspaces(list);
    } catch (e) {
      log.error('Failed to list workspaces', e);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [listAllWorkspaces]);

  useEffect(() => {
    if (isOpen) {
      void refreshList();
    }
  }, [isOpen, refreshList]);

  const filteredWorkspaces = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) return workspaces;
    return workspaces.filter(ws =>
      ws.name.toLowerCase().includes(query) ||
      ws.rootPath.toLowerCase().includes(query)
    );
  }, [workspaces, searchQuery]);

  const handleSelect = useCallback(async (workspace: WorkspaceInfo) => {
    if (workspace.id === currentWorkspace?.id) {
      onSelected?.(workspace);
      onClose();
      return;
    }
    setSelectingId(workspace.id);
    try {
      await selectWorkspace(workspace.id);
      onSelected?.(workspace);
      onClose();
    } catch (e) {
      log.error('Failed to select workspace', e);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSelectingId(null);
    }
  }, [currentWorkspace?.id, selectWorkspace, onSelected, onClose]);

  const handleBrowseFolder = useCallback(async () => {
    try {
      setIsBrowsing(true);
      const { open } = await import('@tauri-apps/plugin-dialog');
      const selected = await open({
        directory: true,
        multiple: false,
        title: t('startup.selectWorkspaceDirectory'),
      });
      if (selected && typeof selected === 'string') {
        const ws = await openWorkspace(selected);
        onSelected?.(ws);
        onClose();
      }
    } catch (e) {
      log.error('Failed to browse folder', e);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsBrowsing(false);
    }
  }, [openWorkspace, onSelected, onClose, t]);

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title={t('workspacePicker.title')}
      size="medium"
      contentClassName="workspace-picker-dialog__content"
    >
      <div className="workspace-picker-dialog">
        {/* 搜索栏 */}
        <div className="workspace-picker-dialog__search">
          <Search size={14} className="workspace-picker-dialog__search-icon" />
          <input
            type="text"
            className="workspace-picker-dialog__search-input"
            placeholder={t('workspacePicker.searchPlaceholder')}
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            autoFocus
          />
          <button
            type="button"
            className="workspace-picker-dialog__refresh-btn"
            onClick={() => void refreshList()}
            disabled={loading}
            title={t('workspacePicker.refresh')}
            aria-label={t('workspacePicker.refresh')}
          >
            <RefreshCw size={14} className={loading ? 'is-spinning' : ''} />
          </button>
        </div>

        {error && (
          <div className="workspace-picker-dialog__error">
            <AlertCircle size={14} />
            <span>{error}</span>
          </div>
        )}

        {/* 工作区列表 */}
        <div className="workspace-picker-dialog__list">
          {filteredWorkspaces.length === 0 && !loading ? (
            <div className="workspace-picker-dialog__empty">
              {searchQuery
                ? t('workspacePicker.noMatch')
                : t('workspacePicker.noWorkspaces')}
            </div>
          ) : (
            filteredWorkspaces.map(ws => {
              const { hostPrefix, folderLabel, tooltip } = getRecentWorkspaceLineParts(ws);
              const isCurrent = ws.id === currentWorkspace?.id;
              const isSelecting = selectingId === ws.id;
              return (
                <button
                  key={ws.id}
                  type="button"
                  className={[
                    'workspace-picker-dialog__item',
                    isCurrent ? 'is-current' : '',
                    isSelecting ? 'is-selecting' : '',
                  ].filter(Boolean).join(' ')}
                  title={tooltip}
                  onClick={() => void handleSelect(ws)}
                  disabled={isSelecting}
                >
                  <FolderOpen size={15} className="workspace-picker-dialog__item-icon" />
                  <span className="workspace-picker-dialog__item-main">
                    {hostPrefix ? (
                      <>
                        <span className="workspace-picker-dialog__item-host">{hostPrefix}</span>
                        <span className="workspace-picker-dialog__item-sep" aria-hidden>·</span>
                      </>
                    ) : null}
                    <span className="workspace-picker-dialog__item-name">{folderLabel}</span>
                  </span>
                  {isCurrent ? (
                    <Check size={14} className="workspace-picker-dialog__item-check" />
                  ) : null}
                </button>
              );
            })
          )}
        </div>

        {/* 底部操作栏 */}
        <div className="workspace-picker-dialog__footer">
          <span className="workspace-picker-dialog__count">
            {t('workspacePicker.count', { count: filteredWorkspaces.length })}
          </span>
          <div className="workspace-picker-dialog__actions">
            <Button
              variant="ghost"
              size="small"
              onClick={() => void handleBrowseFolder()}
              disabled={isBrowsing}
            >
              <FolderPlus size={13} />
              {t('workspacePicker.browseOther')}
            </Button>
            <Button variant="ghost" size="small" onClick={onClose}>
              {t('workspacePicker.cancel')}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default WorkspacePickerDialog;
