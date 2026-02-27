interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  headers?: Record<string, string>;
  body?: unknown;
}

function isInTauri(): boolean {
  return typeof window !== 'undefined' && 
         (!!(window as any).__TAURI_INTERNALS__ || !!(window as any).__TAURI__);
}

async function invokeWithFallback<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  try {
    const { invoke } = await import('@tauri-apps/api/core');
    return await invoke<T>(cmd, args);
  } catch (error) {
    console.warn('Tauri invoke failed:', error);
    throw error;
  }
}

export async function httpRequest<T>(url: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', headers = {}, body } = options;

  if (!isInTauri()) {
    console.error('Not in Tauri environment. Please use the desktop app to login.');
    throw new Error('请在桌面应用中登录，浏览器模式不支持此功能');
  }

  try {
    console.log('Using make_http_request command for:', url);
    
    const response = await invokeWithFallback<T>('make_http_request', {
      url,
      method,
      headers,
      body: body ? JSON.stringify(body) : null,
    });

    return response;
  } catch (error) {
    console.error('make_http_request failed:', error);
    throw error;
  }
}
