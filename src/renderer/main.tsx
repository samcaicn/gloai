import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { store } from './store';
import App from './App';
import './index.css';
import { injectElectronCompat } from './services/electronCompat';
import { initTauri } from './services/tauriApi';

// 等待 Tauri 初始化的函数
const waitForTauri = (): Promise<void> => {
  return new Promise((resolve) => {
    // 检查 Tauri 是否已经初始化的函数
    const checkTauriReady = () => {
      return typeof window !== 'undefined' && 
             ((window as any).__TAURI_INTERNALS__ || 
              (window as any).__TAURI__ || 
              (window as any).isTauri ||
              // 检查是否已经有 Tauri API 可用
              (window as any).electron?.store?.get !== undefined);
    };

    // 如果 Tauri 已经初始化，直接返回
    if (checkTauriReady()) {
      console.log('[main] Tauri already initialized');
      resolve();
      return;
    }

    // 否则等待一段时间
    console.log('[main] Waiting for Tauri initialization...');
    let attempts = 0;
    const maxAttempts = 50; // 最多等待 5 秒
    
    const checkInterval = setInterval(() => {
      attempts++;
      if (checkTauriReady()) {
        console.log('[main] Tauri initialized after', attempts, 'attempts');
        clearInterval(checkInterval);
        resolve();
      } else if (attempts >= maxAttempts) {
        console.warn('[main] Tauri initialization timeout, proceeding anyway');
        clearInterval(checkInterval);
        resolve();
      }
    }, 100);
  });
};

// 主初始化函数
const initApp = async () => {
  // 注入 Electron 兼容层
  injectElectronCompat();
  
  // 等待 Tauri 初始化
  await waitForTauri();
  
  // 初始化 Tauri API
  await initTauri().catch(console.error);
  
  // 渲染应用
  const rootElement = document.getElementById('root');
  if (!rootElement) {
    throw new Error('Failed to find the root element');
  }

  try {
    ReactDOM.createRoot(rootElement).render(
      <React.StrictMode>
        <Provider store={store}>
          <App />
        </Provider>
      </React.StrictMode>
    );
  } catch (error) {
    console.error('Failed to render the app:', error);
  }
};

// 启动应用
initApp();
