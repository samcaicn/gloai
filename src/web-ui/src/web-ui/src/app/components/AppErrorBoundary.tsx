import { Component, ReactNode } from 'react';
import { createLogger } from '@/shared/utils/logger';
import { buildReactCrashLogPayload } from '@/shared/utils/reactProductionError';

// Hardcoded fallback strings so the error boundary can render even if the
// i18n service or its dynamic chunk failed to load (a common cause of
// cascading white-screen crashes on Windows).
const FALLBACK_STRINGS = {
  title: '应用出现错误',
  reload: '重新加载',
  technicalDetails: '技术详情',
  unknownError: '未知错误',
};

// Safely call i18nService.t() with fallback. If i18nService isn't
// available or the key is missing, return the fallback string.
function safeT(key: string, fallback: string): string {
  try {
    // Lazy import to avoid pulling i18n into the error path's module graph.
    // Using require-style dynamic access to avoid a circular import issue.
    // i18nService is a singleton created at module load; calling .t() on it
    // is safe even before initialize() — it returns the key or fallback.
    // We use a dynamic property access pattern to avoid import errors.
    const mod = (window as any).__bitfun_i18n_service__;
    if (mod && typeof mod.t === 'function') {
      return mod.t(key) || fallback;
    }
  } catch {
    // ignore
  }
  return fallback;
}

const log = createLogger('AppErrorBoundary');

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error?: Error;
  errorInfo?: any;
}

export class AppErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: any) {
    this.setState({ error, errorInfo });
    // Log every boundary capture (do not share a session-wide flag with main.tsx:
    // a second distinct error would otherwise be suppressed).
    log.error(
      '[CRASH] React error boundary caught exception',
      buildReactCrashLogPayload(error, errorInfo)
    );
  }

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    const title = safeT('errors:boundary.title', FALLBACK_STRINGS.title);
    const reloadLabel = safeT('errors:boundary.reload', FALLBACK_STRINGS.reload);
    const technicalDetails = safeT('errors:boundary.technicalDetails', FALLBACK_STRINGS.technicalDetails);
    const unknownError = safeT('errors:boundary.unknown', FALLBACK_STRINGS.unknownError);
    const firstLine = this.state.error?.message?.split('\n')[0] ?? unknownError;

    return (
      <div
        style={{
          height: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: '#0b0f14',
          color: '#e5e7eb',
          padding: 24,
          boxSizing: 'border-box',
        }}
      >
        <div style={{ maxWidth: 760, width: '100%' }}>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600 }}>{title}</h2>
          <p style={{ margin: '12px 0 0', opacity: 0.9 }}>{firstLine}</p>
          <div style={{ marginTop: 16 }}>
            <button
              onClick={this.handleReload}
              style={{
                padding: '8px 12px',
                background: '#2563eb',
                color: '#fff',
                border: 'none',
                borderRadius: 8,
                cursor: 'pointer',
              }}
            >
              {reloadLabel}
            </button>
          </div>
          {import.meta.env.DEV && this.state.error && (
            <details style={{ marginTop: 16 }}>
              <summary style={{ cursor: 'pointer' }}>{technicalDetails}</summary>
              <pre
                style={{
                  marginTop: 12,
                  padding: 12,
                  background: '#0f172a',
                  color: '#cbd5e1',
                  borderRadius: 8,
                  overflow: 'auto',
                  maxHeight: 240,
                  fontSize: 12,
                }}
              >
                {this.state.error.stack ?? this.state.error.message}
              </pre>
            </details>
          )}
        </div>
      </div>
    );
  }
}

export default AppErrorBoundary;
