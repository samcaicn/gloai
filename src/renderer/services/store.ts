import { tauriApi, isTauriReady, localStorageFallback } from './tauriApi';

// 删除重复的类型声明，使用全局类型定义
export interface LocalStore {
  getItem<T>(key: string): Promise<T | null>;
  setItem<T>(key: string, value: T): Promise<void>;
  removeItem(key: string): Promise<void>;
}

class LocalStoreService implements LocalStore {
  async getItem<T>(key: string): Promise<T | null> {
    try {
      if (isTauriReady()) {
        const value = await tauriApi.store.get(key);
        return value || null;
      } else {
        return localStorageFallback.get(key) || null;
      }
    } catch (error) {
      console.error('Failed to get item from store:', error);
      return localStorageFallback.get(key) || null;
    }
  }

  async setItem<T>(key: string, value: T): Promise<void> {
    try {
      if (isTauriReady()) {
        await tauriApi.store.set(key, value);
      } else {
        localStorageFallback.set(key, value);
      }
    } catch (error) {
      console.error('Failed to set item in store:', error);
      localStorageFallback.set(key, value);
    }
  }

  async removeItem(key: string): Promise<void> {
    try {
      if (isTauriReady()) {
        await tauriApi.store.remove(key);
      } else {
        localStorageFallback.remove(key);
      }
    } catch (error) {
      console.error('Failed to remove item from store:', error);
      localStorageFallback.remove(key);
    }
  }
}

export const localStore = new LocalStoreService(); 