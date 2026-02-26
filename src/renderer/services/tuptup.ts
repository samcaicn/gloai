import CryptoJS from 'crypto-js';

interface TuptupUserInfo {
  id: string;
  username: string;
  email: string;
  vipLevel: number;
  vipName: string;
  [key: string]: any;
}

interface TuptupTokenBalance {
  balance: number;
  totalUsed: number;
  [key: string]: any;
}

interface TuptupPlan {
  id: string;
  name: string;
  price: number;
  duration: number;
  [key: string]: any;
}

interface TuptupUserOverview {
  user: TuptupUserInfo;
  tokenBalance: TuptupTokenBalance;
  plan: TuptupPlan;
  [key: string]: any;
}

interface TuptupSmtpConfig {
  host: string;
  port: number;
  username: string;
  password: string;
  from: string;
  secure: boolean;
  [key: string]: any;
}

interface TuptupConfig {
  apiKey: string;
  apiSecret: string;
  userId: string;
}

const TUPTUP_BASE_URL = 'https://claw.hncea.cc';

interface TuptupLoginInfo {
  config: TuptupConfig;
  timestamp: number;
  expiresAt: number;
}

class TuptupService {
  private config: TuptupConfig | null = null;
  private loginInfo: TuptupLoginInfo | null = null;
  private readonly SESSION_EXPIRY = 24 * 60 * 60 * 1000; // 24小时过期

  constructor() {
    this.loadLoginInfo();
  }

  private saveLoginInfo() {
    if (this.loginInfo) {
      localStorage.setItem('tuptupLoginInfo', JSON.stringify(this.loginInfo));
    }
  }

  private loadLoginInfo() {
    try {
      const saved = localStorage.getItem('tuptupLoginInfo');
      if (saved) {
        const loginInfo = JSON.parse(saved) as TuptupLoginInfo;
        if (Date.now() < loginInfo.expiresAt) {
          this.loginInfo = loginInfo;
          this.config = loginInfo.config;
        } else {
          // 登录过期，清除信息
          this.clearLoginInfo();
        }
      }
    } catch (error) {
      console.error('Failed to load login info:', error);
      this.clearLoginInfo();
    }
  }

  private clearLoginInfo() {
    this.config = null;
    this.loginInfo = null;
    localStorage.removeItem('tuptupLoginInfo');
  }

  setConfig(config: TuptupConfig) {
    this.config = config;
    this.loginInfo = {
      config,
      timestamp: Date.now(),
      expiresAt: Date.now() + this.SESSION_EXPIRY
    };
    this.saveLoginInfo();
  }

  getConfig(): TuptupConfig | null {
    this.loadLoginInfo(); // 每次获取配置时检查是否过期
    return this.config;
  }

  clearConfig() {
    this.clearLoginInfo();
  }

  isLoggedIn(): boolean {
    this.loadLoginInfo(); // 每次检查登录状态时检查是否过期
    return this.config !== null && 
           this.config.apiKey.length > 0 && 
           this.config.apiSecret.length > 0 && 
           this.config.userId.length > 0;
  }

  isLoginExpired(): boolean {
    this.loadLoginInfo();
    return this.loginInfo === null || Date.now() >= this.loginInfo.expiresAt;
  }

  private generateSignature(timestamp: number, apiKey: string, apiSecret: string): string {
    const data = `${timestamp}${apiKey}${apiSecret}`;
    return CryptoJS.SHA256(data).toString();
  }

  private generateHeaders(): Record<string, string> {
    if (!this.config) {
      throw new Error('Not logged in');
    }

    const timestamp = Date.now();
    const signature = this.generateSignature(
      timestamp,
      this.config.apiKey,
      this.config.apiSecret
    );

    return {
      'X-App-Key': this.config.apiKey,
      'X-User-Id': this.config.userId,
      'X-Timestamp': timestamp.toString(),
      'X-Signature': signature,
      'Content-Type': 'application/json',
    };
  }

  private async request<T>(endpoint: string): Promise<T> {
    const headers = this.generateHeaders();
    const url = `${TUPTUP_BASE_URL}${endpoint}`;

    const response = await fetch(url, {
      method: 'GET',
      headers,
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`API request failed: ${response.status} ${errorText}`);
    }

    return response.json();
  }

  async getUserInfo(): Promise<TuptupUserInfo> {
    return this.request<TuptupUserInfo>('/api/client/user/info');
  }

  async getTokenBalance(): Promise<TuptupTokenBalance> {
    return this.request<TuptupTokenBalance>('/api/client/user/token-balance');
  }

  async getPlan(): Promise<TuptupPlan> {
    return this.request<TuptupPlan>('/api/client/user/plan');
  }

  async getOverview(): Promise<TuptupUserOverview> {
    return this.request<TuptupUserOverview>('/api/client/user/overview');
  }

  async getSmtpConfig(userId: string = '2'): Promise<TuptupSmtpConfig> {
    // 检查登录状态是否过期
    if (this.isLoginExpired()) {
      console.warn('Login session expired, using default SMTP config');
      // 登录过期时，仍然使用默认的 API 密钥获取配置
    }

    const API_KEY = 'gk_981279d245764a1cb53738da';
    const API_SECRET = 'gs_7a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2';
    const timestamp = Date.now();
    
    const data = `${timestamp}${API_KEY}${API_SECRET}`;
    const signature = CryptoJS.SHA256(data).toString();

    const headers = {
      'X-App-Key': API_KEY,
      'X-User-Id': userId,
      'X-Timestamp': timestamp.toString(),
      'X-Signature': signature,
      'Content-Type': 'application/json',
    };

    const url = `${TUPTUP_BASE_URL}/api/client/smtp/config`;
    const response = await fetch(url, {
      method: 'GET',
      headers,
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`SMTP config request failed: ${response.status} ${errorText}`);
    }

    const smtpConfig = await response.json();
    return smtpConfig;
  }
}

export const tuptupService = new TuptupService();
