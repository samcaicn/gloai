class LoggerService {
  private logPath: string | null = null;
  private logBuffer: string[] = [];
  private flushInterval: NodeJS.Timeout | null = null;
  private isInitialized = false;

  async init(): Promise<void> {
    if (this.isInitialized) return;
    
    try {
      // 获取日志文件路径
      const path = await window.electron.log.getPath();
      this.logPath = path;
      console.log(`[Logger] Log path: ${path}`);
      
      // 启动定期刷新
      this.flushInterval = setInterval(() => this.flush(), 5000);
      
      // 记录初始化日志
      this.info('Logger initialized');
      
      this.isInitialized = true;
    } catch (error) {
      console.error('Failed to initialize logger:', error);
    }
  }

  private async flush(): Promise<void> {
    if (!this.logPath || this.logBuffer.length === 0) return;
    
    try {
      // 这里应该调用后端API来写入日志
      // 由于暂时没有后端API，我们先将日志输出到控制台
      const logs = this.logBuffer.join('\n');
      console.log('[Logger] Flushing logs:', logs);
      this.logBuffer = [];
    } catch (error) {
      console.error('Failed to flush logs:', error);
    }
  }

  private formatMessage(level: string, message: string, error?: Error): string {
    const timestamp = new Date().toISOString();
    const errorMessage = error ? `\nError: ${error.message}\nStack: ${error.stack}` : '';
    return `[${timestamp}] [${level}] ${message}${errorMessage}`;
  }

  info(message: string): void {
    const formatted = this.formatMessage('INFO', message);
    console.log(formatted);
    this.logBuffer.push(formatted);
  }

  warn(message: string, error?: Error): void {
    const formatted = this.formatMessage('WARN', message, error);
    console.warn(formatted);
    this.logBuffer.push(formatted);
  }

  error(message: string, error?: Error): void {
    const formatted = this.formatMessage('ERROR', message, error);
    console.error(formatted);
    this.logBuffer.push(formatted);
  }

  debug(message: string): void {
    const formatted = this.formatMessage('DEBUG', message);
    console.debug(formatted);
    this.logBuffer.push(formatted);
  }

  async openLogFolder(): Promise<void> {
    try {
      await window.electron.log.openFolder();
    } catch (error) {
      console.error('Failed to open log folder:', error);
    }
  }

  async getLogPath(): Promise<string | null> {
    if (!this.logPath) {
      try {
        this.logPath = await window.electron.log.getPath();
      } catch (error) {
        console.error('Failed to get log path:', error);
      }
    }
    return this.logPath;
  }

  destroy(): void {
    if (this.flushInterval) {
      clearInterval(this.flushInterval);
      this.flushInterval = null;
    }
    this.flush();
  }
}

export const loggerService = new LoggerService();

// 重写console方法，将所有日志都捕获到文件
const originalConsole = {
  log: console.log,
  warn: console.warn,
  error: console.error,
  debug: console.debug,
};

console.log = function(...args: any[]) {
  originalConsole.log(...args);
  loggerService.info(args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' '));
};

console.warn = function(...args: any[]) {
  originalConsole.warn(...args);
  loggerService.warn(args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' '));
};

console.error = function(...args: any[]) {
  originalConsole.error(...args);
  const error = args.find(arg => arg instanceof Error);
  const message = args.map(arg => typeof arg === 'object' && !(arg instanceof Error) ? JSON.stringify(arg) : String(arg)).join(' ');
  loggerService.error(message, error as Error);
};

console.debug = function(...args: any[]) {
  originalConsole.debug(...args);
  loggerService.debug(args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' '));
};
