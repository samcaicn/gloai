import React, { useState, useEffect } from 'react';
import Sidebar from './components/Sidebar';
import { CoworkView } from './components/cowork';
import { SkillsView } from './components/skills';
import { ScheduledTasksView } from './components/scheduledTasks';
import Settings from './components/Settings';
import AppUpdateModal from './components/update/AppUpdateModal';
import AppUpdateBadge from './components/update/AppUpdateBadge';
import { i18nService } from './services/i18n';
import { loggerService } from './services/logger';
import { configService } from './services/config';
import { themeService } from './services/theme';
import { scheduledTaskService } from './services/scheduledTask';
import { tauriApiService } from './services/tauriApi';

const App: React.FC = () => {
  const [mainView, setMainView] = useState<'cowork' | 'skills' | 'scheduledTasks'>('cowork');
  const [isInitialized, setIsInitialized] = useState(false);
  const [initError, setInitError] = useState<string | null>(null);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  // Update related state
  const [updateInfo, setUpdateInfo] = useState<{ version: string; release_notes: string; url: string } | null>(null);
  const [isUpdateModalOpen, setIsUpdateModalOpen] = useState(false);
  const [isUpdateDownloading, setIsUpdateDownloading] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<{ percent: number; speed: number } | null>(null);

  useEffect(() => {
    const initializeApp = async () => {
      // 添加超时机制，确保初始化过程不会无限卡住
      const timeoutPromise = new Promise<void>((_, reject) => {
        setTimeout(() => reject(new Error('App initialization timeout')), 10000); // 10秒超时
      });

      try {
        await Promise.race([
          (async () => {
            // 初始化日志服务
            await loggerService.init();
            loggerService.info('Starting app initialization');

            // 初始化配置
            await configService.init();
            loggerService.info('Config service initialized');
            
            // 初始化主题
            themeService.initialize();
            loggerService.info('Theme service initialized');

            // 初始化语言
            await i18nService.initialize();
            loggerService.info('i18n service initialized');
            
            // 初始化定时任务服务
            await scheduledTaskService.init();
            loggerService.info('Scheduled task service initialized');

            // Get current app version
            const version = await tauriApiService.getAppVersion();
            loggerService.info(`App version: ${version}`);

            // Set up update listeners
            setupUpdateListeners();

            // Check for updates
            checkForUpdates();

            loggerService.info('App initialization completed');
          })(),
          timeoutPromise
        ]);
        setIsInitialized(true);
      } catch (error) {
        loggerService.error('Failed to initialize app:', error as Error);
        setInitError('初始化失败，请检查应用配置');
        setIsInitialized(true);
      }
    };

    const setupUpdateListeners = () => {
      // Listen for update available
      window.addEventListener('update_available', (_event: any) => {
        const detail = _event.detail;
        setUpdateInfo({
          version: detail.version,
          release_notes: detail.release_notes,
          url: '' // Will be set when we get the download URL
        });
        setIsUpdateModalOpen(true);
        loggerService.info(`Update available: ${detail.version}`);
      });

      // Listen for update progress
      window.addEventListener('update_progress', (_event: any) => {
        const detail = _event.detail;
        setUpdateProgress({
          percent: detail.percent || 0,
          speed: detail.speed || 0
        });
        loggerService.info(`Update progress: ${detail.percent}%`);
      });

      // Listen for update downloaded
      window.addEventListener('update_downloaded', (_event: any) => {
        setIsUpdateDownloading(false);
        setUpdateProgress(null);
        loggerService.info('Update downloaded successfully');
        // Show notification that update will be installed on restart
        alert('Update downloaded successfully. Will install on restart.');
      });
    };

    const checkForUpdates = async () => {
      try {
        const result = await tauriApiService.updateCheck();
        if (result.update_available) {
          setUpdateInfo({
            version: result.version,
            release_notes: result.release_notes,
            url: '' // Will be set when we get the download URL
          });
          setIsUpdateModalOpen(true);
        }
      } catch (error) {
        loggerService.error('Failed to check for updates:', error as Error);
      }
    };

    void initializeApp();
  }, []);

  if (!isInitialized) {
    return (
      <div className="h-screen overflow-hidden flex flex-col">
        <div className="flex-1 flex items-center justify-center dark:bg-claude-darkBg bg-claude-bg">
          <div className="flex flex-col items-center space-y-4">
            <div className="w-16 h-16 rounded-full bg-gradient-to-br from-claude-accent to-claude-accentHover flex items-center justify-center shadow-glow-accent animate-pulse">
              <div className="text-white text-2xl">G</div>
            </div>
            <div className="w-24 h-1 rounded-full bg-claude-accent/20 overflow-hidden">
              <div className="h-full w-1/2 rounded-full bg-claude-accent animate-shimmer" />
            </div>
            <div className="dark:text-claude-darkText text-claude-text text-xl font-medium">{i18nService.t('loading')}</div>
          </div>
        </div>
      </div>
    );
  }

  if (initError) {
    return (
      <div className="h-screen overflow-hidden flex flex-col">
        <div className="flex-1 flex flex-col items-center justify-center dark:bg-claude-darkBg bg-claude-bg">
          <div className="flex flex-col items-center space-y-6 max-w-md px-6">
            <div className="w-16 h-16 rounded-full bg-red-500 flex items-center justify-center shadow-lg">
              <div className="text-white text-2xl">G</div>
            </div>
            <div className="dark:text-claude-darkText text-claude-text text-xl font-medium text-center">{initError}</div>
          </div>
        </div>
      </div>
    );
  }

  // Update handling functions
  const handleUpdateConfirm = async () => {
    setIsUpdateModalOpen(false);
    setIsUpdateDownloading(true);
    
    try {
      // For now, we'll use a dummy download URL
      // In a real implementation, we would get the actual download URL from the update info
      const downloadUrl = 'https://example.com/update.zip';
      await tauriApiService.updateDownload(downloadUrl, undefined);
    } catch (error) {
      loggerService.error('Failed to download update:', error as Error);
      setIsUpdateDownloading(false);
      alert('Failed to download update. Please try again later.');
    }
  };

  const handleUpdateCancel = () => {
    setIsUpdateModalOpen(false);
  };

  const handleUpdateBadgeClick = () => {
    if (updateInfo) {
      setIsUpdateModalOpen(true);
    }
  };

  // Create update badge if update is available
  const updateBadge = updateInfo ? (
    <AppUpdateBadge
      latestVersion={updateInfo.version}
      onClick={handleUpdateBadgeClick}
    />
  ) : null;

  return (
    <div className="h-screen overflow-hidden flex flex-col dark:bg-claude-darkSurfaceMuted bg-claude-surfaceMuted">
      <div className="flex flex-1 min-h-0 overflow-hidden">
        <Sidebar
          onShowLogin={() => {}}
          onShowSettings={() => setIsSettingsOpen(true)}
          activeView={mainView}
          onShowSkills={() => setMainView('skills')}
          onShowCowork={() => setMainView('cowork')}
          onShowScheduledTasks={() => setMainView('scheduledTasks')}
          onNewChat={() => {}}
          isCollapsed={isSidebarCollapsed}
          onToggleCollapse={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
          updateBadge={updateBadge}
        />
        <div className={`flex-1 min-w-0 py-1.5 pr-1.5 ${isSidebarCollapsed ? 'pl-1.5' : ''}`}>
          <div className="h-full rounded-xl dark:bg-claude-darkBg bg-claude-bg overflow-hidden">
            {mainView === 'skills' ? (
              <SkillsView
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={updateBadge}
              />
            ) : mainView === 'scheduledTasks' ? (
              <ScheduledTasksView
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={updateBadge}
              />
            ) : (
              <CoworkView
                onRequestAppSettings={() => setIsSettingsOpen(true)}
                onShowSkills={() => setMainView('skills')}
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={updateBadge}
              />
            )}
          </div>
        </div>
      </div>
      {isSettingsOpen && (
        <Settings
          onClose={() => setIsSettingsOpen(false)}
        />
      )}
      {isUpdateModalOpen && updateInfo && (
        <AppUpdateModal
          latestVersion={updateInfo.version}
          onConfirm={handleUpdateConfirm}
          onCancel={handleUpdateCancel}
        />
      )}
      {isUpdateDownloading && updateProgress && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-backdrop">
          <div className="modal-content w-full max-w-sm mx-4 dark:bg-claude-darkSurface bg-claude-surface rounded-2xl shadow-modal overflow-hidden">
            <div className="px-5 pt-5 pb-3">
              <h3 className="text-base font-semibold dark:text-claude-darkText text-claude-text">
                下载更新中
              </h3>
              <p className="mt-2 text-sm dark:text-claude-darkTextSecondary text-claude-textSecondary">
                版本: {updateInfo?.version}
              </p>
              <div className="mt-4 h-2 bg-claude-surface rounded-full overflow-hidden">
                <div 
                  className="h-full bg-claude-accent transition-all duration-300 ease-out"
                  style={{ width: `${updateProgress.percent}%` }}
                />
              </div>
              <p className="mt-2 text-xs dark:text-claude-darkTextSecondary text-claude-textSecondary">
                {Math.round(updateProgress.percent)}% - {Math.round(updateProgress.speed / 1024 / 1024 * 10) / 10} MB/s
              </p>
            </div>
            <div className="px-5 pb-5 flex items-center justify-end">
              <button
                type="button"
                onClick={async () => {
                  await tauriApiService.updateCancelDownload();
                  setIsUpdateDownloading(false);
                  setUpdateProgress(null);
                }}
                className="px-3 py-1.5 text-sm rounded-lg bg-claude-accent text-white hover:bg-claude-accentHover transition-colors"
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default App;