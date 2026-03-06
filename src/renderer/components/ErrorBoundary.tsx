import { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    console.error('ErrorBoundary caught an error:', error, errorInfo);
  }

  render(): ReactNode {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <div className="h-screen overflow-hidden flex flex-col">
          <div className="flex-1 flex flex-col items-center justify-center dark:bg-claude-darkBg bg-claude-bg">
            <div className="flex flex-col items-center space-y-6 max-w-md px-6">
              <div className="w-16 h-16 rounded-full bg-red-500 flex items-center justify-center shadow-lg">
                <div className="text-white text-2xl">!</div>
              </div>
              <div className="dark:text-claude-darkText text-claude-text text-xl font-medium text-center">
                渲染出错，请重启应用
              </div>
              <div className="dark:text-claude-darkTextSecondary text-claude-textSecondary text-sm text-center">
                {this.state.error?.message || '未知错误'}
              </div>
              <button
                onClick={() => window.location.reload()}
                className="px-4 py-2 bg-claude-accent text-white rounded-lg hover:bg-claude-accentHover transition-colors"
              >
                重启应用
              </button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
