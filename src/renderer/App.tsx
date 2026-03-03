import React, { useState, useEffect } from 'react';
import Sidebar from './components/Sidebar';
import { CoworkView } from './components/cowork';
import { SkillsView } from './components/skills';
import { ScheduledTasksView } from './components/scheduledTasks';
import Settings from './components/Settings';
import { i18nService } from './services/i18n';
import { loggerService } from './services/logger';
import { configService } from './services/config';
import { themeService } from './services/theme';
import { scheduledTaskService } from './services/scheduledTask';

const App: React.FC = () => {
  const [mainView, setMainView] = useState<'cowork' | 'skills' | 'scheduledTasks'>('cowork');
  const [isInitialized, setIsInitialized] = useState(false);
  const [initError, setInitError] = useState<string | null>(null);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  useEffect(() => {
    const initializeApp = async () => {
      try {
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

        loggerService.info('App initialization completed');
        setIsInitialized(true);
      } catch (error) {
        loggerService.error('Failed to initialize app:', error as Error);
        setInitError('初始化失败，请检查应用配置');
        setIsInitialized(true);
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
          updateBadge={null}
        />
        <div className={`flex-1 min-w-0 py-1.5 pr-1.5 ${isSidebarCollapsed ? 'pl-1.5' : ''}`}>
          <div className="h-full rounded-xl dark:bg-claude-darkBg bg-claude-bg overflow-hidden">
            {mainView === 'skills' ? (
              <SkillsView
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={null}
              />
            ) : mainView === 'scheduledTasks' ? (
              <ScheduledTasksView
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={null}
              />
            ) : (
              <CoworkView
                onRequestAppSettings={() => setIsSettingsOpen(true)}
                onShowSkills={() => setMainView('skills')}
                isSidebarCollapsed={isSidebarCollapsed}
                onToggleSidebar={() => setIsSidebarCollapsed(!isSidebarCollapsed)}
                onNewChat={() => {}}
                updateBadge={null}
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
    </div>
  );
};

export default App;