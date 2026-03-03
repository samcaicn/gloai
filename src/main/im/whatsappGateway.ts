import { WhatsAppConfig, WhatsAppGatewayStatus, IMMessage, IMPlatform } from './types';
import { EventEmitter } from 'events';
import { fetchJsonWithTimeout } from './http';
import { DEFAULT_WHATSAPP_STATUS } from './types';
import fs from 'fs';
import path from 'path';

// WhatsApp Webhook 响应结构
interface WhatsAppWebhookResponse {
  messaging_product: string;
  contacts: Array<{
    input: string;
    wa_id: string;
  }>;
  messages: Array<{
    id: string;
    message_id: string;
    status: string;
  }>;
}

// WhatsApp 媒体上传响应
interface WhatsAppMediaUploadResponse {
  id: string;
}

// WhatsApp 媒体信息
interface WhatsAppMediaInfo {
  id: string;
  mime_type: string;
  file_size: number;
  url: string;
}

// WhatsApp 网关类
export class WhatsAppGateway extends EventEmitter {
  private config: WhatsAppConfig;
  private status: WhatsAppGatewayStatus;
  private lastChatId: string | null = null;

  constructor(config: WhatsAppConfig) {
    super();
    this.config = config;
    this.status = { ...DEFAULT_WHATSAPP_STATUS };
  }

  // 设置配置
  public setConfig(config: WhatsAppConfig): void {
    this.config = config;
  }

  // 获取配置
  public getConfig(): WhatsAppConfig {
    return { ...this.config };
  }

  // 获取状态
  public getStatus(): WhatsAppGatewayStatus {
    return { ...this.status };
  }

  // 启动网关
  public async start(): Promise<boolean> {
    if (!this.config.enabled) {
      return false;
    }

    if (!this.config.phoneNumberId) {
      this.status.error = '缺少必要的配置: phoneNumberId';
      this.status.lastError = this.status.error;
      this.emit('error', this.status.error);
      this.emit('statusChanged', this.status);
      return false;
    }

    if (!this.config.accessToken) {
      this.status.error = '缺少必要的配置: accessToken';
      this.status.lastError = this.status.error;
      this.emit('error', this.status.error);
      this.emit('statusChanged', this.status);
      return false;
    }

    this.status.starting = true;
    this.status.error = null;
    this.status.lastError = null;
    this.emit('statusChanged', this.status);

    // 验证凭据
    try {
      await this.verifyCredentials();
      this.log('WhatsApp API 验证成功');
    } catch (error) {
      this.status.starting = false;
      this.status.error = `验证凭据失败: ${error}`;
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

    this.log('WhatsApp 网关已启动');
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

    this.log('WhatsApp 网关已停止');
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

    if (!this.lastChatId) {
      throw new Error('需要指定收件人');
    }

    await this.sendMessage(this.lastChatId, text);
    this.status.lastOutboundAt = Date.now();
    this.emit('statusChanged', this.status);
    return true;
  }

  // 发送消息
  public async sendMessage(conversationId: string, text: string): Promise<boolean> {
    if (!this.isConnected()) {
      throw new Error('网关未连接');
    }

    await this.sendTextMessage(conversationId, text);
    this.lastChatId = conversationId;
    this.status.lastOutboundAt = Date.now();
    this.emit('statusChanged', this.status);
    return true;
  }

  // 发送媒体消息
  public async sendMediaMessage(conversationId: string, filePath: string): Promise<boolean> {
    if (!this.isConnected()) {
      throw new Error('网关未连接');
    }

    await this.uploadAndSendMedia(conversationId, filePath);
    this.lastChatId = conversationId;
    this.status.lastOutboundAt = Date.now();
    this.emit('statusChanged', this.status);
    return true;
  }

  // 重新连接（如果需要）
  public async reconnectIfNeeded(): Promise<boolean> {
    if (!this.isConnected()) {
      return this.start();
    }
    return true;
  }

  // 编辑消息（WhatsApp 不支持）
  public async editMessage(conversationId: string, messageId: string, newText: string): Promise<boolean> {
    throw new Error('WhatsApp 不支持编辑消息');
  }

  // 删除消息（WhatsApp 不支持）
  public async deleteMessage(conversationId: string, messageId: string): Promise<boolean> {
    throw new Error('WhatsApp 不支持删除消息');
  }

  // 获取消息历史（WhatsApp 不支持）
  public async getMessageHistory(conversationId: string, limit: number): Promise<IMMessage[]> {
    throw new Error('WhatsApp 不支持获取历史消息');
  }

  // 验证凭据
  private async verifyCredentials(): Promise<void> {
    const url = 'https://graph.facebook.com/v18.0/me';

    interface FacebookUserResponse {
      id: string;
      name: string;
    }

    const result = await fetchJsonWithTimeout<FacebookUserResponse>(
      url,
      {
        method: 'GET',
        headers: this.getHeaders()
      },
      30000
    );

    if (!result.id) {
      throw new Error('验证失败：无法获取用户信息');
    }
  }

  // 获取 API URL
  private getApiUrl(endpoint: string): string {
    return `https://graph.facebook.com/v18.0/${this.config.phoneNumberId}${endpoint}`;
  }

  // 获取请求头
  private getHeaders(): Record<string, string> {
    return {
      'Authorization': `Bearer ${this.config.accessToken}`,
      'Content-Type': 'application/json'
    };
  }

  // 发送文本消息
  private async sendTextMessage(to: string, text: string): Promise<void> {
    const url = this.getApiUrl('/messages');

    const requestData = {
      messaging_product: 'whatsapp',
      to,
      type: 'text',
      text: {
        body: text
      }
    };

    const result = await fetchJsonWithTimeout<WhatsAppWebhookResponse>(
      url,
      {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(requestData)
      },
      30000
    );

    if (!result.messages || result.messages.length === 0) {
      throw new Error('发送消息失败');
    }
  }

  // 上传并发送媒体消息
  private async uploadAndSendMedia(to: string, filePath: string): Promise<void> {
    const mediaId = await this.uploadMedia(filePath);
    const extension = path.extname(filePath).toLowerCase().substring(1);

    switch (extension) {
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'webp':
        await this.sendImageMessage(to, mediaId);
        break;
      case 'mp4':
      case 'avi':
      case 'mov':
        await this.sendVideoMessage(to, mediaId);
        break;
      case 'mp3':
      case 'wav':
      case 'ogg':
      case 'aac':
        await this.sendAudioMessage(to, mediaId);
        break;
      case 'pdf':
      case 'doc':
      case 'docx':
      case 'xls':
      case 'xlsx':
      case 'ppt':
      case 'pptx':
      case 'txt':
      case 'zip':
      case 'rar':
        await this.sendDocumentMessage(to, mediaId, path.basename(filePath));
        break;
      default:
        throw new Error('不支持的文件类型');
    }
  }

  // 上传媒体文件
  private async uploadMedia(filePath: string): Promise<string> {
    const fileBytes = fs.readFileSync(filePath);
    const fileName = path.basename(filePath);
    
    const mimeTypes: Record<string, string> = {
      jpg: 'image/jpeg',
      jpeg: 'image/jpeg',
      png: 'image/png',
      gif: 'image/gif',
      webp: 'image/webp',
      mp4: 'video/mp4',
      mp3: 'audio/mpeg',
      ogg: 'audio/ogg',
      pdf: 'application/pdf',
      doc: 'application/msword',
      docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    };

    const extension = path.extname(filePath).toLowerCase().substring(1);
    const mimeType = mimeTypes[extension] || 'application/octet-stream';

    const url = 'https://graph.facebook.com/v18.0/me/media';

    // 创建 FormData
    const formData = new FormData();
    formData.append('messaging_product', 'whatsapp');
    formData.append('file', new Blob([fileBytes]), fileName);

    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.config.accessToken}`
      },
      body: formData
    });

    if (!response.ok) {
      throw new Error(`上传媒体失败: ${response.statusText}`);
    }

    const result = await response.json() as WhatsAppMediaUploadResponse;
    return result.id;
  }

  // 发送图片消息
  private async sendImageMessage(to: string, mediaId: string): Promise<void> {
    const url = this.getApiUrl('/messages');

    const requestData = {
      messaging_product: 'whatsapp',
      to,
      type: 'image',
      image: {
        id: mediaId
      }
    };

    await fetchJsonWithTimeout(
      url,
      {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(requestData)
      },
      30000
    );
  }

  // 发送视频消息
  private async sendVideoMessage(to: string, mediaId: string): Promise<void> {
    const url = this.getApiUrl('/messages');

    const requestData = {
      messaging_product: 'whatsapp',
      to,
      type: 'video',
      video: {
        id: mediaId
      }
    };

    await fetchJsonWithTimeout(
      url,
      {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(requestData)
      },
      30000
    );
  }

  // 发送音频消息
  private async sendAudioMessage(to: string, mediaId: string): Promise<void> {
    const url = this.getApiUrl('/messages');

    const requestData = {
      messaging_product: 'whatsapp',
      to,
      type: 'audio',
      audio: {
        id: mediaId
      }
    };

    await fetchJsonWithTimeout(
      url,
      {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(requestData)
      },
      30000
    );
  }

  // 发送文档消息
  private async sendDocumentMessage(to: string, mediaId: string, filename: string): Promise<void> {
    const url = this.getApiUrl('/messages');

    const requestData = {
      messaging_product: 'whatsapp',
      to,
      type: 'document',
      document: {
        id: mediaId,
        filename
      }
    };

    await fetchJsonWithTimeout(
      url,
      {
        method: 'POST',
        headers: this.getHeaders(),
        body: JSON.stringify(requestData)
      },
      30000
    );
  }

  // 处理 Webhook 消息
  public handleWebhookMessage(data: any): void {
    if (data.entry && data.entry.length > 0) {
      const entry = data.entry[0];
      if (entry.changes && entry.changes.length > 0) {
        const change = entry.changes[0];
        const value = change.value;
        
        if (value.messages && value.messages.length > 0) {
          const message = value.messages[0];
          const from = message.from;
          const text = message.text?.body || '';
          
          // 存储最后一个聊天 ID
          this.lastChatId = from;
          
          // 创建 IM 消息
          const imMessage: IMMessage = {
            platform: 'whatsapp',
            messageId: message.id,
            conversationId: from,
            senderId: from,
            content: text,
            chatType: 'direct',
            timestamp: parseInt(message.timestamp) * 1000
          };
          
          // 触发消息事件
          this.emit('message', imMessage);
          
          // 更新状态
          this.status.lastInboundAt = Date.now();
          this.emit('statusChanged', this.status);
        }
      }
    }
  }

  // 验证 Webhook 令牌
  public verifyWebhookToken(token: string): boolean {
    return token === this.config.verifyToken;
  }

  // 日志记录
  private log(message: string): void {
    if (this.config.debug) {
      console.log(`[WhatsApp Gateway] ${message}`);
    }
  }
}
