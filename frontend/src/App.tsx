import { useEffect } from 'react';
import { useStore } from './store';
import { NOT_LOGIN_EVENT } from './api';
import { Sidebar } from './components/Sidebar';
import { LoginPage } from './components/LoginPage';
import { ChatPanel } from './components/ChatPanel';
import { KnowledgePage } from './components/KnowledgePage';
import { CommandPalette } from './components/CommandPalette';
import { ToastStack } from './components/ToastStack';
import { GlobalDialog } from './components/GlobalDialog';
import { ErrorBoundary } from './components/ErrorBoundary';

export default function App() {
  const init = useStore((state) => state.init);
  const authUser = useStore((state) => state.authUser);
  const authChecked = useStore((state) => state.authChecked);
  const handleNotLogin = useStore((state) => state.handleNotLogin);
  const view = useStore((state) => state.view);
  const theme = useStore((state) => state.theme);
  const sidebarOpen = useStore((state) => state.sidebarOpen);
  const setSidebarOpen = useStore((state) => state.setSidebarOpen);

  useEffect(() => {
    init();
  }, [init]);

  // token 过期/被顶下线：任何请求收到 0301 都会广播此事件，统一切回登录页
  useEffect(() => {
    const onNotLogin = (event: Event) => {
      handleNotLogin((event as CustomEvent<string>).detail);
    };
    window.addEventListener(NOT_LOGIN_EVENT, onNotLogin);
    return () => window.removeEventListener(NOT_LOGIN_EVENT, onNotLogin);
  }, [handleNotLogin]);

  // 主题应用：<html class="dark">，配合 @custom-variant dark
  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);

  // 正文字号档位应用：<html data-fontscale>，CSS 按 .md-body 基准缩放
  const fontScale = useStore((state) => state.fontScale);
  useEffect(() => {
    document.documentElement.dataset.fontscale = fontScale;
  }, [fontScale]);

  // 窄屏抽屉打开时锁定滚动
  useEffect(() => {
    document.body.style.overflow = sidebarOpen && window.innerWidth < 1024 ? 'hidden' : '';
    return () => {
      document.body.style.overflow = '';
    };
  }, [sidebarOpen]);

  // 登录探测未完成时空白（避免闪登录页）；未登录显示登录页
  if (!authChecked) {
    return <div className="h-full bg-surface-panel" />;
  }
  if (!authUser) {
    return (
      <ErrorBoundary>
        <LoginPage />
        <GlobalDialog />
      </ErrorBoundary>
    );
  }

  return (
    <ErrorBoundary>
      <div className="flex h-full overflow-hidden">
        {/* 桌面端静态侧栏 */}
        <div className="max-lg:hidden">
          <Sidebar />
        </div>
        {/* 窄屏抽屉侧栏 */}
        {sidebarOpen && (
          <div className="fixed inset-0 z-40 lg:hidden">
            <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={() => setSidebarOpen(false)} />
            <div className="absolute inset-y-0 left-0 animate-fade-in-up shadow-panel">
              <Sidebar onNavigate={() => setSidebarOpen(false)} />
            </div>
          </div>
        )}
        <main className="flex min-w-0 flex-1">
          {view === 'knowledge' ? <KnowledgePage /> : <ChatPanel />}
        </main>
        <CommandPalette />
        <ToastStack />
        <GlobalDialog />
      </div>
    </ErrorBoundary>
  );
}
