import { Component, type ErrorInfo, type ReactNode } from 'react';
import { TriangleAlert } from 'lucide-react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/** 全局渲染异常兜底：任何组件树异常显示可恢复的错误页，而不是白屏 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] 渲染异常:', error, info.componentStack);
  }

  render() {
    if (!this.state.error) {
      return this.props.children;
    }
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-surface-app px-8">
        <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-rose-500/10 text-rose-500">
          <TriangleAlert size={26} />
        </span>
        <div className="text-base font-semibold text-content-primary">页面出错了</div>
        <div className="max-w-md text-center text-xs leading-5 text-content-muted">
          {this.state.error.message || '发生了未知渲染异常'}
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => this.setState({ error: null })}
            className="btn-primary px-4 py-2 text-xs"
          >
            重试渲染
          </button>
          <button
            onClick={() => window.location.reload()}
            className="btn-ghost px-4 py-2 text-xs"
          >
            刷新页面
          </button>
        </div>
      </div>
    );
  }
}
