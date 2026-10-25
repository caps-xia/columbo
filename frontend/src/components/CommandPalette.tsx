import { useEffect, useMemo, useRef, useState } from 'react';
import {
  BookOpen,
  CornerDownLeft,
  FileText,
  MessageSquare,
  MessageSquarePlus,
  Moon,
  Search,
  Sparkles,
  Sun,
} from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';
import { agentMeta } from '../agentMeta';

interface PaletteItem {
  id: string;
  icon: typeof Search;
  title: string;
  subtitle?: string;
  keywords: string;
  run: () => void;
}

/** Cmd/Ctrl+K 命令面板：智能体切换 / 历史会话 / 快捷动作 */
export function CommandPalette() {
  const open = useStore((state) => state.paletteOpen);
  const setPaletteOpen = useStore((state) => state.setPaletteOpen);
  const agents = useStore((state) => state.agents);
  const sessions = useStore((state) => state.sessions);
  const setAgent = useStore((state) => state.setAgent);
  const selectSession = useStore((state) => state.selectSession);
  const newChat = useStore((state) => state.newChat);
  const setView = useStore((state) => state.setView);
  const theme = useStore((state) => state.theme);
  const toggleTheme = useStore((state) => state.toggleTheme);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const isMac = useMemo(
    () => typeof navigator !== 'undefined' && /mac|iphone|ipad/i.test(navigator.platform || navigator.userAgent),
    [],
  );

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setPaletteOpen(!useStore.getState().paletteOpen);
      }
      if (event.key === 'Escape') {
        setPaletteOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [setPaletteOpen]);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  const items = useMemo<PaletteItem[]>(() => {
    const close = (fn: () => void) => () => {
      setPaletteOpen(false);
      fn();
    };
    const actions: PaletteItem[] = [
      {
        id: 'action-new-chat',
        icon: MessageSquarePlus,
        title: '新对话',
        subtitle: '开始全新会话',
        keywords: '新对话 new chat',
        run: close(newChat),
      },
      {
        id: 'action-knowledge',
        icon: BookOpen,
        title: '打开知识库',
        subtitle: '排查经验 · 速记/案例/词典',
        keywords: '知识库 笔记 knowledge 搜索',
        run: close(() => setView('knowledge')),
      },
      {
        id: 'action-theme',
        icon: theme === 'dark' ? Sun : Moon,
        title: theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式',
        keywords: '主题 暗色 dark 亮色 light',
        run: close(toggleTheme),
      },
    ];
    const agentItems: PaletteItem[] = agents.map((agent) => ({
      id: `agent-${agent.agentId}`,
      icon: agentMeta(agent.agentId).icon,
      title: `切换到 ${agent.agentName}`,
      subtitle: agent.agentDesc,
      keywords: `智能体 ${agent.agentName} ${agent.agentDesc}`,
      run: close(() => setAgent(agent.agentId)),
    }));
    const sessionItems: PaletteItem[] = sessions.map((session) => ({
      id: `session-${session.sessionId}`,
      icon: MessageSquare,
      title: session.title || '未命名会话',
      subtitle: session.agentName,
      keywords: `会话 ${session.title || ''} ${session.agentName}`,
      run: close(() => selectSession(session.sessionId)),
    }));
    return [...actions, ...agentItems, ...sessionItems];
  }, [agents, sessions, theme, newChat, setAgent, selectSession, setPaletteOpen, setView, toggleTheme]);

  // 简易打分匹配：标题前缀 > 标题包含 > 关键词包含
  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) {
      return items.slice(0, 12);
    }
    const scored = items
      .map((item) => {
        const title = item.title.toLowerCase();
        const keywords = item.keywords.toLowerCase();
        let score = -1;
        if (title.startsWith(keyword)) score = 100;
        else if (title.includes(keyword)) score = 60;
        else if (keywords.includes(keyword)) score = 30;
        return { item, score };
      })
      .filter((entry) => entry.score >= 0)
      .sort((a, b) => b.score - a.score);
    return scored.slice(0, 12).map((entry) => entry.item);
  }, [items, query]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  if (!open) {
    return null;
  }

  const runActive = () => {
    const item = filtered[active];
    if (item) {
      item.run();
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 pt-[14vh] backdrop-blur-sm"
      onClick={() => setPaletteOpen(false)}
    >
      <div
        className="w-[560px] max-w-[92vw] animate-fade-in-up overflow-hidden rounded-2xl border border-line bg-surface-panel shadow-lg"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-2.5 border-b border-line px-4 py-3">
          <Search size={15} className="text-content-muted" />
          <input
            ref={inputRef}
            value={query}
            placeholder="搜索：智能体 / 会话 / 动作…"
            className="w-full bg-transparent text-sm text-content-primary outline-none placeholder:text-content-muted"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown') {
                event.preventDefault();
                setActive(Math.min(filtered.length - 1, active + 1));
              } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                setActive(Math.max(0, active - 1));
              } else if (event.key === 'Enter') {
                event.preventDefault();
                runActive();
              }
            }}
          />
          <kbd className="rounded border border-line bg-surface-raise px-1.5 py-0.5 text-[10px] text-content-muted">Esc</kbd>
        </div>
        <div className="thin-scroll max-h-[46vh] overflow-y-auto p-1.5">
          {filtered.length === 0 && (
            <div className="flex items-center gap-2 px-3 py-6 text-sm text-content-muted">
              <Sparkles size={14} /> 没有匹配项
            </div>
          )}
          {filtered.map((item, index) => {
            const Icon = item.icon;
            return (
              <button
                key={item.id}
                className={clsx(
                  'flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors',
                  index === active ? 'bg-brand-500/10 text-brand-700 dark:bg-brand-500/15 dark:text-brand-300' : 'text-content-secondary hover:bg-surface-raise',
                )}
                onMouseEnter={() => setActive(index)}
                onClick={item.run}
              >
                <Icon size={15} className="shrink-0" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-medium text-content-primary">{item.title}</span>
                  {item.subtitle && <span className="block truncate text-xs text-content-muted">{item.subtitle}</span>}
                </span>
                {index === active && <CornerDownLeft size={13} className="shrink-0 text-content-muted" />}
              </button>
            );
          })}
        </div>
        <div className="flex items-center gap-3 border-t border-line px-4 py-2 text-[11px] text-content-faint">
          <span className="flex items-center gap-1"><FileText size={11} /> {filtered.length} 项</span>
          <span>↑↓ 选择</span>
          <span>↵ 执行</span>
          <span className="ml-auto">{isMac ? '⌘K' : 'Ctrl+K'}</span>
        </div>
      </div>
    </div>
  );
}
