import { useEffect, useMemo, useRef, useState } from 'react';
import { Command, Loader2, MessageSquarePlus, BookOpen, Moon, Sun, Trash2, UserRound, ChevronDown, LogOut } from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';
import { agentMeta } from '../agentMeta';
import logo from '../assets/logo.png';

/** 每个智能体分组默认展示的最近会话条数 */
const COLLAPSED_GROUP_SIZE = 5;

/** 每次点击「查看更多」追加的条数 */
const EXPAND_STEP = 5;

/** 未归档空间的筛选哨兵值（区分 null=全部） */
const NONE_SPACE = '__none__';

function formatTime(updatedAt: string): string {
  if (!updatedAt) {
    return '';
  }
  const date = new Date(updatedAt);
  if (Number.isNaN(date.getTime())) {
    return updatedAt.slice(5, 16);
  }
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  return sameDay
    ? date.toTimeString().slice(0, 5)
    : `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** 会话落进哪个时间桶：今天/昨天/近 7 天/更早（后端已按最后活跃倒序） */
function timeBucket(updatedAt: string): string {
  const date = new Date(updatedAt);
  if (Number.isNaN(date.getTime())) {
    return '更早';
  }
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const t = date.getTime();
  if (t >= startOfToday) {
    return '今天';
  }
  if (t >= startOfToday - 86400000) {
    return '昨天';
  }
  if (t >= startOfToday - 7 * 86400000) {
    return '近 7 天';
  }
  return '更早';
}

const BUCKET_ORDER = ['今天', '昨天', '近 7 天', '更早'];

export function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const agents = useStore((state) => state.agents);
  const agentsLoading = useStore((state) => state.agentsLoading);
  const currentAgentId = useStore((state) => state.currentAgentId);
  const sessions = useStore((state) => state.sessions);
  const currentSessionId = useStore((state) => state.currentSessionId);
  const streamingSessionId = useStore((state) => state.streamingSessionId);
  const activeSpace = useStore((state) => state.activeSpace);
  const userId = useStore((state) => state.userId);
  const authUser = useStore((state) => state.authUser);
  const theme = useStore((state) => state.theme);
  const setAgent = useStore((state) => state.setAgent);
  const selectSession = useStore((state) => state.selectSession);
  const deleteSession = useStore((state) => state.deleteSession);
  const renameSession = useStore((state) => state.renameSession);
  const moveSession = useStore((state) => state.moveSession);
  const setActiveSpace = useStore((state) => state.setActiveSpace);
  const confirmDialog = useStore((state) => state.confirmDialog);
  const promptDialog = useStore((state) => state.promptDialog);
  const pushToast = useStore((state) => state.pushToast);
  const logout = useStore((state) => state.logout);
  const newChat = useStore((state) => state.newChat);
  const setUserId = useStore((state) => state.setUserId);
  const setView = useStore((state) => state.setView);
  const toggleTheme = useStore((state) => state.toggleTheme);
  const setPaletteOpen = useStore((state) => state.setPaletteOpen);

  const [editingUser, setEditingUser] = useState(false);
  const [userDraft, setUserDraft] = useState(userId);
  /** 各分组当前展示条数：默认 5，查看更多 +5，收起重置 */
  const [visibleCounts, setVisibleCounts] = useState<Map<string, number>>(new Map());
  /** 归档空间拖拽：正在拖拽的会话 id + 悬停高亮的空间 */
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragOverSpace, setDragOverSpace] = useState<string | null>(null);
  /** 右击菜单：目标会话 + 屏幕坐标；重命名输入态与菜单互斥 */
  const [renameMenu, setRenameMenu] = useState<{ sessionId: string; x: number; y: number } | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState('');
  const renameInputRef = useRef<HTMLInputElement>(null);

  // 重命名输入框出现时聚焦并全选
  useEffect(() => {
    if (renamingId && renameInputRef.current) {
      renameInputRef.current.focus();
      renameInputRef.current.select();
    }
  }, [renamingId]);

  // 点击任意处关闭右击菜单
  useEffect(() => {
    if (!renameMenu) return;
    const close = () => setRenameMenu(null);
    window.addEventListener('click', close);
    window.addEventListener('blur', close);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('blur', close);
    };
  }, [renameMenu]);

  // 归档空间清单（从会话动态聚合；空库时只有 全部/未归档）
  const spaces = useMemo(
    () => Array.from(new Set(sessions.map((item) => item.space).filter((v): v is string => !!v))),
    [sessions],
  );

  // 按当前空间筛选后，按时间桶分组（组内保持后端的最后活跃倒序）
  const grouped = useMemo(() => {
    const filtered =
      activeSpace == null
        ? sessions
        : activeSpace === NONE_SPACE
          ? sessions.filter((item) => !item.space)
          : sessions.filter((item) => item.space === activeSpace);
    const map = new Map<string, typeof filtered>();
    for (const session of filtered) {
      const bucket = timeBucket(session.updatedAt);
      const list = map.get(bucket) || [];
      list.push(session);
      map.set(bucket, list);
    }
    return BUCKET_ORDER.filter((bucket) => map.has(bucket)).map((bucket) => [bucket, map.get(bucket)!] as const);
  }, [sessions, activeSpace]);

  const visibleOf = (agentName: string) => visibleCounts.get(agentName) ?? COLLAPSED_GROUP_SIZE;
  const expandBy = (agentName: string, delta: number) =>
    setVisibleCounts((prev) => {
      const next = new Map(prev);
      next.set(agentName, (prev.get(agentName) ?? COLLAPSED_GROUP_SIZE) + delta);
      return next;
    });
  const collapseTo = (agentName: string) =>
    setVisibleCounts((prev) => {
      const next = new Map(prev);
      next.delete(agentName);
      return next;
    });

  /** 拖拽落点到空间 chip：value 为空串=未归档；NEW 时弹输入创建 */
  const dropOnSpace = async (event: React.DragEvent, spaceValue: string | 'NEW') => {
    event.preventDefault();
    setDragOverSpace(null);
    const sessionId = event.dataTransfer.getData('text/session-id');
    if (!sessionId) return;
    if (spaceValue === 'NEW') {
      const name = await promptDialog({ title: '新建归档空间', placeholder: '空间名称（如：值班、日常）', message: '拖入的会话将移动到该空间，最长 32 字。' });
      if (!name || !name.trim()) return;
      const trimmed = name.trim().slice(0, 32);
      await moveSession(sessionId, trimmed);
      setActiveSpace(trimmed);
    } else {
      await moveSession(sessionId, spaceValue);
    }
  };

  const chipBase = 'rounded-full border px-2.5 py-0.5 text-[11px] transition-all select-none';

  return (
    <aside className="flex h-full w-72 shrink-0 flex-col border-r border-line bg-surface-panel">
      {/* 品牌区 */}
      <div className="flex items-center gap-3 px-5 pb-4 pt-5">
        <img src={logo} alt="logo" className="h-10 w-10 rounded-xl shadow-soft" />
        <div className="min-w-0 flex-1">
          <div className="text-sm font-bold tracking-wide text-content-primary">Columbo AI 工作台</div>
          <div className="text-[11px] text-content-muted">对话即交付 · 一站式智能工作台</div>
        </div>
        <button
          onClick={toggleTheme}
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-content-muted transition-colors hover:bg-surface-raise hover:text-content-primary"
          aria-label={theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'}
          title={theme === 'dark' ? '切换到亮色模式' : '切换到暗色模式'}
        >
          {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
        </button>
      </div>

      {/* 智能体列表 */}
      <div className="thin-scroll flex-1 overflow-y-auto px-3 pb-2">
        <div className="px-2 pb-1.5 pt-1 text-xs font-medium text-content-muted">智能体</div>
        {agentsLoading && (
          <div className="space-y-1.5 px-1 pt-1">
            {[0, 1, 2].map((index) => (
              <div key={index} className="skeleton h-12 w-full" />
            ))}
          </div>
        )}
        <div className="space-y-1">
          {agents.map((agent) => {
            const meta = agentMeta(agent.agentId);
            const Icon = meta.icon;
            const active = agent.agentId === currentAgentId;
            return (
              <button
                key={agent.agentId}
                onClick={() => {
                  setAgent(agent.agentId);
                  onNavigate?.();
                }}
                title={agent.agentDesc}
                className={clsx(
                  'group relative flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left transition-all',
                  active ? 'bg-brand-500/10 dark:bg-brand-500/15' : 'hover:bg-surface-raise',
                )}
              >
                {/* 当前智能体品牌侧标 */}
                {active && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r-full bg-brand-500" aria-hidden />}
                <span className={clsx('flex h-8 w-8 shrink-0 items-center justify-center rounded-lg', meta.chip)}>
                  <Icon size={17} strokeWidth={2.2} />
                </span>
                <span className="min-w-0 flex-1">
                  <span className={clsx('block truncate text-sm', active ? 'font-semibold text-brand-700 dark:text-brand-300' : 'font-medium text-content-secondary')}>
                    {agent.agentName}
                  </span>
                  <span className="block truncate text-xs text-content-muted">{agent.agentDesc}</span>
                </span>
              </button>
            );
          })}
        </div>

        {/* 历史会话 */}
        <div className="mt-4 border-t border-line/60 pt-3">
          <div className="px-2 pb-1.5 text-xs font-medium text-content-muted">历史会话</div>

          {/* 归档空间：点按筛选；拖会话到 chip 归档；＋ 新建空间（也是放置目标） */}
          <div className="flex flex-wrap items-center gap-1 px-1 pb-2">
            <button
              onClick={() => setActiveSpace(null)}
              className={clsx(chipBase, activeSpace == null
                ? 'border-brand-400 bg-brand-500/10 text-content-primary'
                : 'border-line text-content-muted hover:border-brand-300')}
            >
              全部
            </button>
            <button
              onClick={() => setActiveSpace(NONE_SPACE)}
              onDragOver={(event) => {
                event.preventDefault();
                setDragOverSpace(NONE_SPACE);
              }}
              onDragLeave={() => setDragOverSpace(null)}
              onDrop={(event) => dropOnSpace(event, '')}
              className={clsx(chipBase,
                activeSpace === NONE_SPACE
                  ? 'border-brand-400 bg-brand-500/10 text-content-primary'
                  : 'border-line text-content-muted hover:border-brand-300',
                draggingId && 'ring-1 ring-brand-300/60',
                draggingId && dragOverSpace === NONE_SPACE && 'border-brand-400 bg-brand-500/15')}
            >
              未归档
            </button>
            {spaces.map((space) => (
              <button
                key={space}
                onClick={() => setActiveSpace(space)}
                onDragOver={(event) => {
                  event.preventDefault();
                  setDragOverSpace(space);
                }}
                onDragLeave={() => setDragOverSpace(null)}
                onDrop={(event) => dropOnSpace(event, space)}
                title="拖动会话到此归档"
                className={clsx(chipBase,
                  activeSpace === space
                    ? 'border-brand-400 bg-brand-500/10 text-content-primary'
                    : 'border-line text-content-muted hover:border-brand-300',
                  draggingId && 'ring-1 ring-brand-300/60',
                  draggingId && dragOverSpace === space && 'border-brand-400 bg-brand-500/15')}
              >
                {space}
              </button>
            ))}
            <button
              onClick={() =>
                pushToast('info', '把会话拖到「＋」上，输入空间名即可创建并归档')
              }
              onDragOver={(event) => {
                event.preventDefault();
                setDragOverSpace('NEW');
              }}
              onDragLeave={() => setDragOverSpace(null)}
              onDrop={(event) => dropOnSpace(event, 'NEW')}
              title="新建空间：把会话拖到这里，输入名称即可创建"
              className={clsx(chipBase, 'border-dashed border-line text-content-faint hover:border-brand-300 hover:text-content-muted',
                draggingId && 'ring-1 ring-brand-300/60',
                draggingId && dragOverSpace === 'NEW' && 'border-brand-400 bg-brand-500/15')}
            >
              ＋
            </button>
          </div>

          {grouped.length === 0 && !agentsLoading && <div className="px-2 py-2 text-xs text-content-faint">暂无会话</div>}
          {grouped.map(([bucket, list]) => {
            const visibleCount = visibleOf(bucket);
            const visible = list.slice(0, visibleCount);
            const hidden = list.length - visible.length;
            return (
            <div key={bucket} className="mb-2">
              <div className="sticky top-0 z-[1] bg-surface-panel/95 px-2 py-1 text-[11px] font-medium text-content-faint backdrop-blur">{bucket}</div>
              {visible.map((session) => (
                <div
                  key={session.sessionId}
                  draggable={renamingId !== session.sessionId}
                  onDragStart={(event) => {
                    event.dataTransfer.setData('text/session-id', session.sessionId);
                    event.dataTransfer.effectAllowed = 'move';
                    setDraggingId(session.sessionId);
                  }}
                  onDragEnd={() => {
                    setDraggingId(null);
                    setDragOverSpace(null);
                  }}
                  className={clsx(
                    'group flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 transition-colors',
                    session.sessionId === currentSessionId
                      ? 'bg-brand-500/10 dark:bg-brand-500/15'
                      : 'hover:bg-surface-raise',
                    draggingId === session.sessionId && 'opacity-40',
                  )}
                  onClick={() => {
                    selectSession(session.sessionId);
                    onNavigate?.();
                  }}
                  onContextMenu={(event) => {
                    event.preventDefault();
                    // 菜单出现在点击处附近，不越出侧栏右缘
                    setRenameMenu({
                      sessionId: session.sessionId,
                      x: Math.min(event.clientX, window.innerWidth - 150),
                      y: Math.min(event.clientY, window.innerHeight - 60),
                    });
                  }}
                >
                  <div className="min-w-0 flex-1">
                    {renamingId === session.sessionId ? (
                      <input
                        ref={renameInputRef}
                        value={renameDraft}
                        maxLength={100}
                        onChange={(event) => setRenameDraft(event.target.value)}
                        onClick={(event) => event.stopPropagation()}
                        onContextMenu={(event) => event.stopPropagation()}
                        onBlur={() => {
                          // 失焦提交：空值放弃修改
                          const next = renameDraft.trim();
                          setRenamingId(null);
                          if (next && next !== (session.title || '')) {
                            renameSession(session.sessionId, next);
                          }
                        }}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter') (event.target as HTMLInputElement).blur();
                          if (event.key === 'Escape') setRenamingId(null);
                        }}
                        className="w-full rounded border border-brand-400 bg-surface-panel px-1 py-0.5 text-[13px] text-content-primary outline-hidden"
                      />
                    ) : (
                      <div className="flex items-center gap-1.5">
                        <span className="min-w-0 flex-1 truncate text-[13px] text-content-secondary" title={`${session.agentName} · 右击可重命名`}>
                          {session.title || '新对话'}
                        </span>
                        {session.sessionId === streamingSessionId && (
                          <span className="relative flex h-1.5 w-1.5 shrink-0" title="生成中">
                            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
                            <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-emerald-500" />
                          </span>
                        )}
                      </div>
                    )}
                    <div className="flex items-center gap-1.5 text-[11px] text-content-faint">
                      <span className="max-w-[9em] truncate">{session.agentName}</span>
                      <span>·</span>
                      <span className="tnum">{formatTime(session.updatedAt)}</span>
                    </div>
                  </div>
                  <button
                    className={clsx(
                      'shrink-0 rounded p-1 text-content-faint transition-all hover:bg-rose-50 hover:text-rose-500 dark:hover:bg-rose-500/10',
                      /* 桌面端悬停才出现（减少列表噪音），触屏常显 */
                      'max-lg:opacity-100 lg:opacity-0 lg:group-hover:opacity-100',
                    )}
                    aria-label="删除会话" title="删除会话"
                    onClick={async (event) => {
                      event.stopPropagation();
                      const ok = await confirmDialog({
                        title: '删除会话',
                        message: `「${session.title || '新对话'}」将被删除，云端历史一并清除，不可恢复。`,
                        danger: true,
                        confirmText: '删除',
                      });
                      if (ok) {
                        deleteSession(session.sessionId);
                      }
                    }}
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              ))}
              {(hidden > 0 || visibleCount > COLLAPSED_GROUP_SIZE) && (
                <div className="flex items-center gap-1 px-1 pt-0.5">
                  {hidden > 0 && (
                    <button
                      onClick={() => expandBy(bucket, EXPAND_STEP)}
                      className="rounded-lg px-2 py-1 text-[11px] text-content-faint transition-colors hover:bg-surface-raise hover:text-content-muted"
                    >
                      查看更多（剩余 {hidden} 条）
                    </button>
                  )}
                  {visibleCount > COLLAPSED_GROUP_SIZE && (
                    <button
                      onClick={() => collapseTo(bucket)}
                      className="rounded-lg px-2 py-1 text-[11px] text-content-faint transition-colors hover:bg-surface-raise hover:text-content-muted"
                    >
                      收起
                    </button>
                  )}
                </div>
              )}
            </div>
            );
          })}
        </div>
      </div>

      {/* 底部：新对话 + 知识库 + 用户 */}
      <div className="border-t border-line/60 p-3">
        <button
          onClick={() => {
            newChat();
            onNavigate?.();
          }}
          title="开始新对话"
          className="btn-primary w-full px-4 py-2.5 text-sm"
        >
          <MessageSquarePlus size={16} /> 新对话
        </button>
        <div className="mt-2 grid grid-cols-2 gap-2">
          <button
            onClick={() => {
              setView('knowledge');
              onNavigate?.();
            }}
            title="排查经验知识库（速记/案例/词典）"
            className="flex w-full items-center justify-center gap-1.5 rounded-xl border border-line bg-surface-panel px-2 py-2 text-[13px] font-medium text-content-secondary transition-colors hover:bg-surface-raise"
          >
            <BookOpen size={14} /> 知识库
          </button>
          <button
            onClick={() => setPaletteOpen(true)}
            className="flex w-full items-center justify-center gap-1.5 rounded-xl border border-line bg-surface-panel px-3 py-2 text-[13px] font-medium text-content-secondary transition-colors hover:bg-surface-raise"
            title="全局搜索与快捷动作"
          >
            <Command size={13} /> K 搜索
          </button>
        </div>
        <div className="mt-2.5 flex items-center gap-2 rounded-xl bg-surface-raise px-3 py-2">
          <UserRound size={15} className="shrink-0 text-content-muted" />
          {authUser ? (
            <span className="min-w-0 flex-1 truncate text-[13px] text-content-secondary" title={`${authUser}（登录态）`}>
              {userId}
            </span>
          ) : editingUser ? (
            <input
              autoFocus
              value={userDraft}
              onChange={(event) => setUserDraft(event.target.value)}
              onBlur={() => {
                setEditingUser(false);
                if (userDraft.trim() && userDraft.trim() !== userId) {
                  setUserId(userDraft.trim());
                }
              }}
              onKeyDown={(event) => event.key === 'Enter' && (event.target as HTMLInputElement).blur()}
              className="w-full bg-transparent text-[13px] text-content-secondary outline-hidden"
            />
          ) : (
            <button
              className="flex w-full items-center justify-between text-[13px] text-content-muted"
              onClick={() => {
                setUserDraft(userId);
                setEditingUser(true);
              }}
              title="切换用户（会话归属）"
            >
              <span className="truncate">{userId}</span>
              <ChevronDown size={13} className="text-content-faint" />
            </button>
          )}
          <button
            onClick={() => logout()}
            title="退出登录"
            className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg text-content-faint transition-colors hover:bg-rose-50 hover:text-rose-500 dark:hover:bg-rose-500/10"
          >
            <LogOut size={14} />
          </button>
        </div>
      </div>
      {/* 右击重命名菜单：portal 到 body，避免被侧栏 overflow 裁剪 */}
      {renameMenu && (
        <div
          style={{ position: 'fixed', left: renameMenu.x, top: renameMenu.y, zIndex: 50 }}
          onClick={(event) => event.stopPropagation()}
          className="w-40 overflow-hidden rounded-lg border border-line bg-surface-panel py-1 shadow-lg"
        >
          <button
            onClick={() => {
              const target = sessions.find((item) => item.sessionId === renameMenu.sessionId);
              setRenameDraft(target?.title || '');
              setRenamingId(renameMenu.sessionId);
              setRenameMenu(null);
            }}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-[13px] text-content-secondary transition-colors hover:bg-surface-raise"
          >
            重命名会话
          </button>
          <div className="my-1 border-t border-line/70" />
          <div className="px-3 py-0.5 text-[10px] text-content-faint">移动到空间</div>
          <button
            onClick={() => {
              moveSession(renameMenu.sessionId, '');
              setRenameMenu(null);
            }}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-[13px] text-content-secondary transition-colors hover:bg-surface-raise"
          >
            未归档
          </button>
          {spaces.map((space) => (
            <button
              key={space}
              onClick={() => {
                moveSession(renameMenu.sessionId, space);
                setRenameMenu(null);
              }}
              className="flex w-full items-center gap-2 px-3 py-1.5 text-[13px] text-content-secondary transition-colors hover:bg-surface-raise"
            >
              {space}
            </button>
          ))}
          <button
            onClick={async () => {
              const name = await promptDialog({ title: '新建归档空间', placeholder: '空间名称（最长 32 字）' });
              if (name && name.trim()) {
                const trimmed = name.trim().slice(0, 32);
                moveSession(renameMenu.sessionId, trimmed);
                setActiveSpace(trimmed);
              }
              setRenameMenu(null);
            }}
            className="flex w-full items-center gap-2 px-3 py-1.5 text-[13px] text-content-faint transition-colors hover:bg-surface-raise hover:text-content-secondary"
          >
            ＋ 新建空间…
          </button>
        </div>
      )}
    </aside>
  );
}
