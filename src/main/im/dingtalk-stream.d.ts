/**
 * Type declarations for dingtalk-stream
 */

declare module 'dingtalk-stream' {
  export const TOPIC_ROBOT: string;

  export interface DWClientOptions {
    clientId: string;
    clientSecret: string;
    debug?: boolean;
    keepAlive?: boolean;
  }

  export interface CallbackResponse {
    headers?: {
      messageId?: string;
    };
    data: string;
  }

  export class DWClient {
    constructor(options: DWClientOptions);
    registerCallbackListener(
      topic: string,
      callback: (res: CallbackResponse) => void | Promise<void>
    ): void;
    socketCallBackResponse(messageId: string, response: { success: boolean }): void;
    connect(): Promise<void>;
  }
}
