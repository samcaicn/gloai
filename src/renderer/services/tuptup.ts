import crypto from 'crypto';

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

class TuptupService {
  private config: TuptupConfig | null = null;

  setConfig(config: TuptupConfig) {
    this.config = config;
  }

  getConfig(): TuptupConfig | null {
    return this.config;
  }

  clearConfig() {
    this.config = null;
  }

  isLoggedIn(): boolean {
    return this.config !== null && 
           this.config.apiKey.length > 0 && 
           this.config.apiSecret.length > 0 && 
           this.config.userId.length > 0;
  }

  private generateSignature(timestamp: number, apiKey: string, apiSecret: string): string {
    const data = `${timestamp}${apiKey}${apiSecret}`;
    return crypto.createHash('sha256').update(data).digest('hex');
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
    const API_KEY = 'gk_981279d245764a1cb53738da';
    const API_SECRET = 'gs_7a8b9c0d1e2f3g4h5i6j7k8l9m0n1o2';
    const timestamp = Date.now();
    
    const data = `${timestamp}${API_KEY}${API_SECRET}`;
    const signature = crypto.createHash('sha256').update(data).digest('hex');

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

    return response.json();
  }
}

export const tuptupService = new TuptupService();
