/**
 * SessionWorkspaceEditor — 修改单个会话的默认工作区位置。
 *
 * 行为:
 *  - 显示当前会话绑定的工作区路径
 *  - 用户可浏览选择新的工作区目录
 *  - 可勾选"迁移数据"（默认开启）:把旧工作区的 img/ 与 files/ 子目录移动到新工作区
 *  - 确认后调用 FlowChatStore.updateSessionWorkspace,同步更新数据库与内存状态
 */

import React, { useCallback, useEffect, useState } from 'react';
import {
  FolderOpen,
  FolderInput,
  Check,
  AlertCircle,
  LoaderCircle,
} from 'lucide-react';
import { Modal, Button } from '@/component-library';
import { useI18n } from '@/infrastructure/i18n';
import { createLogger } from '@/shared/utils/logger';
import { FlowChatStore } from '../../store/FlowChatStore';
import type { SessionWorkspaceUpdateResult } from '@/infrastructure/api/service-api/GlobalAPI';
import './SessionWorkspaceEditor.scss';

const log = createLogger('SessionWorkspaceEditor');

export interface SessionWorkspaceEditorProps {
  isOpen: boolean;
  onClose: () => void;
  sessionId: string;
  currentWorkspacePath?: string;
}

export const SessionWorkspaceEditor: React.FC<SessionWorkspaceEditorProps> = ({
  isOpen,
  onClose,
  sessionId,
  currentWorkspacePath,
}) => {
  const { t } = useI18n('common');
  const [newPath, setNewPath] = useState('');
  const [moveData, setMoveData] = useState(true);
  const [isBrowsing, setIsBrowsing] = useState(false);
  const [isApplying, setIsApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<SessionWorkspaceUpdateResult | null>(null);

  // 打开时重置状态
  useEffect(() => {
    if (isOpen) {
      setNewPath('');
      setMoveData(true);
      setError(null);
      setResult(null);
      setIsApplying(false);
      setIsBrowsing(false);
    }
  }, [isOpen]);

  const handleBrowse = useCallback(async () => {
    try {
      setIsBrowsing(true);
      setError(null);
      const { open } = await import('@tauri-apps/plugin-dialog');
      const selected = await open({
        directory: true,
        multiple: false,
        title: t('sessionWorkspaceEditor.selectNewDirectory'),
      });
      if (selected && typeof selected === 'string') {
        setNewPath(selected);
      }
    } catch (e) {
      log.error('Failed to browse directory', e);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsBrowsing(false);
    }
  }, [t]);

  const handleConfirm = useCallback(async () => {
    if (!newPath.trim()) {
      setError(t('sessionWorkspaceEditor.errorSelectDirectory'));
      return;
    }
    if (newPath.trim() === (currentWorkspacePath || '').trim()) {
      setError(t('sessionWorkspaceEditor.errorSamePath'));
      return;
    }

    setIsApplying(true);
    setError(null);
    try {
      const store = FlowChatStore.getInstance();
      const res = await store.updateSessionWorkspace(
        sessionId,
        newPath.trim(),
        moveData,
      );
      setResult(res);
      log.info('Session workspace updated successfully', {
        sessionId,
        movedFiles: res.movedFiles,
        movedDirs: res.movedDirs,
      });
    } catch (e) {
      log.error('Failed to update session workspace', e);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setIsApplying(false);
    }
  }, [newPath, currentWorkspacePath, sessionId, moveData, t]);

  const handleClose = useCallback(() => {
    if (isApplying) return;
    onClose();
  }, [isApplying, onClose]);

  const isDone = result !== null;

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title={t('sessionWorkspaceEditor.title')}
      size="small"
      showCloseButton={!isApplying}
      contentClassName="session-workspace-editor__content"
    >
      <div className="session-workspace-editor">
        {/* 当前工作区 */}
        <div className="session-workspace-editor__field">
          <label className="session-workspace-editor__label">
            <FolderOpen size={13} />
            {t('sessionWorkspaceEditor.currentPath')}
          </label>
          <div className="session-workspace-editor__path-box">
            {currentWorkspacePath || t('sessionWorkspaceEditor.noCurrentPath')}
          </div>
        </div>

        {/* 新工作区路径 */}
        {!isDone && (
          <div className="session-workspace-editor__field">
            <label className="session-workspace-editor__label">
              <FolderInput size={13} />
              {t('sessionWorkspaceEditor.newPath')}
            </label>
            <div className="session-workspace-editor__path-selector">
              <div className="session-workspace-editor__path-input">
                {newPath || (
                  <span className="session-workspace-editor__placeholder">
                    {t('sessionWorkspaceEditor.newPathPlaceholder')}
                  </span>
                )}
              </div>
              <Button
                type="button"
                variant="secondary"
                size="small"
                onClick={() => void handleBrowse()}
                disabled={isBrowsing || isApplying}
              >
                <FolderOpen size={13} />
                {t('sessionWorkspaceEditor.browse')}
              </Button>
            </div>
          </div>
        )}

        {/* 迁移数据选项 */}
        {!isDone && (
          <div className="session-workspace-editor__option">
            <label className="session-workspace-editor__checkbox-label">
              <input
                type="checkbox"
                checked={moveData}
                onChange={e => setMoveData(e.target.checked)}
                disabled={isApplying}
              />
              <span>{t('sessionWorkspaceEditor.moveData')}</span>
            </label>
            <p className="session-workspace-editor__option-hint">
              {t('sessionWorkspaceEditor.moveDataHint')}
            </p>
          </div>
        )}

        {/* 错误信息 */}
        {error && (
          <div className="session-workspace-editor__error">
            <AlertCircle size={13} />
            <span>{error}</span>
          </div>
        )}

        {/* 成功结果 */}
        {isDone && result && (
          <div className="session-workspace-editor__success">
            <Check size={14} />
            <div className="session-workspace-editor__success-content">
              <span className="session-workspace-editor__success-title">
                {t('sessionWorkspaceEditor.successTitle')}
              </span>
              <span className="session-workspace-editor__success-detail">
                {moveData
                  ? t('sessionWorkspaceEditor.successMoved', {
                      files: result.movedFiles,
                      dirs: result.movedDirs,
                    })
                  : t('sessionWorkspaceEditor.successNoMove')}
              </span>
            </div>
          </div>
        )}

        {/* 底部按钮 */}
        <div className="session-workspace-editor__footer">
          {isDone ? (
            <Button
              variant="primary"
              size="small"
              onClick={handleClose}
            >
              <Check size={13} />
              {t('sessionWorkspaceEditor.done')}
            </Button>
          ) : (
            <>
              <Button
                variant="ghost"
                size="small"
                onClick={handleClose}
                disabled={isApplying}
              >
                {t('sessionWorkspaceEditor.cancel')}
              </Button>
              <Button
                variant="primary"
                size="small"
                onClick={() => void handleConfirm()}
                disabled={!newPath.trim() || isApplying || isBrowsing}
                isLoading={isApplying}
              >
                {isApplying ? (
                  <>
                    <LoaderCircle size={13} className="is-spinning" />
                    {t('sessionWorkspaceEditor.applying')}
                  </>
                ) : (
                  <>
                    <Check size={13} />
                    {t('sessionWorkspaceEditor.confirm')}
                  </>
                )}
              </Button>
            </>
          )}
        </div>
      </div>
    </Modal>
  );
};

export default SessionWorkspaceEditor;
