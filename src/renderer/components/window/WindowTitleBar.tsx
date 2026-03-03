import React, { useEffect, useState } from 'react';
import { tauriApi, isTauriReady } from '../../services/tauriApi';

interface WindowTitleBarProps {
  isOverlayActive?: boolean;
  inline?: boolean;
  className?: string;
}

type WindowState = {
  isMaximized: boolean;
  isFullscreen: boolean;
  isFocused: boolean;
};

const DEFAULT_STATE: WindowState = {
  isMaximized: false,
  isFullscreen: false,
  isFocused: true,
};

const WindowTitleBar: React.FC<WindowTitleBarProps> = ({
  isOverlayActive = false,
  inline = false,
  className = '',
}) => {
  const [state, setState] = useState<WindowState>(DEFAULT_STATE);

  useEffect(() => {
    let disposed = false;
    let unsubscribe: (() => void) | null = null;

    if (isTauriReady()) {
      tauriApi.window.isMaximized().then((isMaximized) => {
        if (!disposed) {
          setState((prev) => ({ ...prev, isMaximized }));
        }
      }).catch((error) => {
        console.error('Failed to get initial maximize state:', error);
      });

      tauriApi.on('window_state_changed', (nextState: WindowState) => {
        setState(nextState);
      }).then((unsub) => {
        unsubscribe = unsub;
      });
    }

    return () => {
      disposed = true;
      if (unsubscribe) {
        unsubscribe();
      }
    };
  }, []);

  const handleMinimize = () => {
    if (isTauriReady()) {
      tauriApi.window.minimize().catch(console.error);
    }
  };

  const handleToggleMaximize = () => {
    if (isTauriReady()) {
      tauriApi.window.toggleMaximize().catch(console.error);
    }
  };

  const handleClose = () => {
    if (isTauriReady()) {
      tauriApi.window.close().catch(console.error);
    }
  };

  const handleContextMenu = (event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    // Tauri 目前没有直接显示系统菜单的 API，这里可以添加一个自定义菜单或忽略
  };

  const [isWindows, setIsWindows] = useState(false);

  useEffect(() => {
    const checkPlatform = async () => {
      if (isTauriReady()) {
        try {
          const platform = await tauriApi.platform.get();
          setIsWindows(platform === 'win32');
        } catch (error) {
          console.error('Failed to get platform:', error);
        }
      } else {
        setIsWindows(navigator.platform === 'Win32');
      }
    };

    checkPlatform();
  }, []);

  const handleDoubleClick = () => {
    if (!state.isFullscreen) {
      handleToggleMaximize();
    }
  };

  if (!isWindows) {
    return null;
  }

  const containerClassName = inline
    ? `window-controls-floating non-draggable flex h-8 items-center gap-0.5 transition-colors ${!state.isFocused ? 'opacity-70' : 'opacity-100'} ${className}`.trim()
    : `window-controls-floating non-draggable absolute top-0 right-0 z-[55] flex h-full items-center gap-0.5 rounded-bl-xl pl-1 pb-1 pt-0.5 transition-colors ${
      !state.isFocused ? 'opacity-70' : 'opacity-100'
    } ${
      isOverlayActive
        ? 'bg-transparent'
        : 'dark:bg-claude-darkSurface/35 bg-claude-surface/35 backdrop-blur-sm'
    } ${className}`.trim();

  return (
    <div
      className={containerClassName}
      onDoubleClick={handleDoubleClick}
      onContextMenu={handleContextMenu}
    >
      <button
        type="button"
        onClick={handleMinimize}
        className="non-draggable h-8 w-8 inline-flex items-center justify-center rounded-lg transition-colors dark:text-claude-darkTextSecondary text-claude-textSecondary hover:dark:bg-claude-darkSurfaceHover hover:bg-claude-surfaceHover"
        aria-label="Minimize"
        title="Minimize"
      >
        <svg viewBox="0 0 12 12" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
          <path d="M2 6h8" />
        </svg>
      </button>
      <button
        type="button"
        onClick={handleToggleMaximize}
        className="non-draggable h-8 w-8 inline-flex items-center justify-center rounded-lg transition-colors dark:text-claude-darkTextSecondary text-claude-textSecondary hover:dark:bg-claude-darkSurfaceHover hover:bg-claude-surfaceHover"
        aria-label={state.isMaximized ? 'Restore' : 'Maximize'}
        title={state.isMaximized ? 'Restore' : 'Maximize'}
      >
        {state.isMaximized ? (
          <svg viewBox="0 0 12 12" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
            <path d="M4 2h6.5v6.5" />
            <path d="M1.5 4h7v7h-7z" />
          </svg>
        ) : (
          <svg viewBox="0 0 12 12" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
            <path d="M2 2h8v8H2z" />
          </svg>
        )}
      </button>
      <button
        type="button"
        onClick={handleClose}
        className="non-draggable h-8 w-8 inline-flex items-center justify-center rounded-lg transition-colors dark:text-claude-darkTextSecondary text-claude-textSecondary hover:bg-red-500 hover:text-white dark:hover:bg-red-500"
        aria-label="Close"
        title="Close"
      >
        <svg viewBox="0 0 12 12" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 3l6 6" />
          <path d="M9 3L3 9" />
        </svg>
      </button>
    </div>
  );
};

export default WindowTitleBar;
