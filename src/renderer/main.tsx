import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { store } from './store';
import App from './App';
import './index.css';
import { initTauri } from './services/tauriApi';
import ErrorBoundary from './components/ErrorBoundary';

// 主初始化函数
const initApp = async () => {
  // 不等待 Tauri 初始化，直接渲染应用
  // 应用内部会处理 Tauri 不可用的情况
  console.log('[main] Starting app initialization...');
  
  // 尝试初始化 Tauri API，但不阻塞应用启动
  initTauri().catch(console.error);
  
  // 渲染应用
  const rootElement = document.getElementById('root');
  if (!rootElement) {
    throw new Error('Failed to find the root element');
  }

  try {
    ReactDOM.createRoot(rootElement).render(
      <React.StrictMode>
        <ErrorBoundary>
          <Provider store={store}>
            <App />
          </Provider>
        </ErrorBoundary>
      </React.StrictMode>
    );
    console.log('[main] App rendered successfully');
  } catch (error) {
    console.error('Failed to render the app:', error);
  }
};

// 启动应用
initApp();
