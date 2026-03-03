import { WeWorkConfig, WeWorkGatewayStatus, IMMessage, IMPlatform } from './types';
import { EventEmitter } from 'events';
import { fetchJsonWithTimeout } from './http';
import { DEFAULT_WEWORK_STATUS } from './types';

// 企业微信 Webhook 响应结构
interface WeWorkWebhookResponse {
  errcode: number;
  errmsg: string;
}

// 企业微信网关类
export class WeWorkGateway extends EventEmitter {
  private config: WeWorkConfig;
  private status: WeWorkGatewayStatus;

  constructor(config: WeWorkConfig) {
    super();
    this.config = config;
    this.status = { ...DEFAULT_WEWORK_STATUS };
  }

  // 设置配置
  public setConfig(config: WeWorkConfig): void {
    this.config = config;
  }

  // 获取配置
  public getConfig(): WeWorkConfig {
    return { ...this.config };
  }

  // 获取状态
  public getStatus(): WeWorkGatewayStatus {
    return { ...this.status };
  }

  // 启动网关
  public async start(): Promise<boolean> {
    if (!this.config.enabled) {
      return false;
    }

    if (!this.config.webhookUrl) {
      this.status.error = '缺少必要的配置: Webhook URL';
      this.status.lastError = this.status.error;
      this.emit('error', this.status.error);
      this.emit('statusChanged', this.status);
      return false;
    }

    this.status.starting = true;
    this.status.error = null;
    this.status.lastError = null;
    this.emit('statusChanged', this.status);

    // 测试 Webhook URL 是否有效
    try {
      await this.sendTextMessage('企业微信网关已启动');
      this.log('Webhook 测试成功');
    } catch (error) {
      this.status.starting = false;
      this.status.error = `Webhook 测试失败: ${error}`;
      this.status.lastError = this.status.error;
      this.emit('error', this.status.error);
      this.emit('statusChanged', this.status);
      return false;
    }

    this.status.starting = false;
    this.status.connected = true;
    this.status.startedAt = Date.now();
    this.emit('statusChanged', this.status);
    this.emit('connected');

    this.log('企业微信网关已启动（Webhook 模式）');
    return true;
  }

  // 停止网关
  public async stop(): Promise<boolean> {
    if (!this.status.connected && !this.status.starting) {
      return true;
    }

    this.status.connected = false;
    this.status.starting = false;
    this.status.error = null;
    this.emit('statusChanged', this.status);
    this.emit('disconnected');

    this.log('企业微信网关已停止');
    return true;
  }

  // 检查是否连接
  public isConnected(): boolean {
    return this.status.connected;
  }

  // 发送通知
  public async sendNotification(text: string): Promise<boolean> {
    if (!this.isConnected()) {
      throw new Error('网关未连接');
    }

    await this.sendTextMessage(text);
    this.status.lastOutboundAt = Date.now();
    this.emit('statusChanged', this.status);
    return true;
  }

  // 发送消息
  public async sendMessage(conversationId: string, text: string): Promise<boolean> {
    if (!this.isConnected()) {
      throw new Error('网关未连接');
    }

    await this.sendTextMessage(text);
    this.status.lastOutboundAt = Date.now();
    this.emit('statusChanged', this.status);
    return true;
  }

  // 发送媒体消息（企业微信 Webhook 不支持）
  public async sendMediaMessage(conversationId: string, filePath: string): Promise<boolean> {
    throw new Error('企业微信 Webhook 不支持发送媒体消息');
  }

  // 重新连接（如果需要）
  public async reconnectIfNeeded(): Promise<boolean> {
    if (!this.isConnected()) {
      return this.start();
    }
    return true;
  }

  // 编辑消息（企业微信 Webhook 不支持）
  public async editMessage(conversationId: string, messageId: string, newText: string): Promise<boolean> {
    throw new Error('企业微信 Webhook 不支持编辑消息');
  }

  // 删除消息（企业微信 Webhook 不支持）
  public async deleteMessage(conversationId: string, messageId: string): Promise<boolean> {
    throw new Error('企业微信 Webhook 不支持删除消息');
  }

  // 获取消息历史（企业微信 Webhook 不支持）
  public async getMessageHistory(conversationId: string, limit: number): Promise<IMMessage[]> {
    throw new Error('企业微信 Webhook 不支持获取历史消息');
  }

  // 使用群机器人 Webhook 发送消息
  private async sendWebhookMessage(content: string, msgType: string): Promise<void> {
    if (!this.config.webhookUrl) {
      throw new Error('Webhook URL 不能为空');
    }

    const requestData = msgType === 'markdown' 
      ? {
          msgtype: 'markdown',
          markdown: {
            content
          }
        }
      : {
          msgtype: 'text',
          text: {
            content
          }
        };

    const result = await fetchJsonWithTimeout<WeWorkWebhookResponse>(
      this.config.webhookUrl,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestData)
      },
      30000
    );

    if (result.errcode !== 0) {
      throw new Error(`Failed to send message: ${result.errmsg}`);
    }
  }

  // 发送文本消息
  private async sendTextMessage(content: string): Promise<void> {
    await this.sendWebhookMessage(content, 'text');
  }

  // 发送 Markdown 消息
  private async sendMarkdownMessage(content: string): Promise<void> {
    await this.sendWebhookMessage(content, 'markdown');
  }

  // 日志记录
  private log(message: string): void {
    if (this.config.debug) {
      console.log(`[WeWork Gateway] ${message}`);
    }
  }
}
