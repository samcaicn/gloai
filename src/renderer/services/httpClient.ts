// 使用 Tauri HTTP API 发送请求，绕过浏览器的 CORS 限制
import { fetch as tauriFetch } from '@tauri-apps/plugin-http';

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  headers?: Record<string, string>;
  body?: unknown;
}

export async function httpRequest<T>(url: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', headers = {}, body } = options;

  try {
    const response = await tauriFetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`HTTP ${response.status}: ${errorText}`);
    }

    return await response.json() as T;
  } catch (error) {
    console.error('HTTP request failed:', error);
    throw error;
  }
}
