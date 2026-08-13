type LogFn = (message: string, extra?: unknown) => void;

function write(level: string, message: string, extra?: unknown): void {
  const payload = extra === undefined ? message : `${message} ${safe(extra)}`;
  if (level === "error") {
    console.error(`[dshg] ${payload}`);
    return;
  }
  console.info(`[dshg] ${payload}`);
}

function safe(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function createLogger(scope: string): { info: LogFn; error: LogFn } {
  return {
    info: (message, extra) => write("info", `${scope}: ${message}`, extra),
    error: (message, extra) => write("error", `${scope}: ${message}`, extra),
  };
}
