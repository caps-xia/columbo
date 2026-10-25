import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react';
import {
  ArrowDown,
  AlertCircle,
  ArrowUp,
  GripVertical,
  Pencil,
  Loader2,
  Menu,
  Paperclip,
  RefreshCw,
  Send,
  Square,
  TypeOutline,
  X,
} from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';
import { api } from '../api';
import { agentMeta } from '../agentMeta';
import { MessageView } from './MessageView';
import { ContextBar } from './ContextBar';
import logo from '../assets/logo.png';

const MAX_IMAGES = 4;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/** 欢迎页：按本地时间给问候语，示例卡点击只填入输入框（不直接发送） */
function Welcome() {
  const agents = useStore((state) => state.agents);
  const currentAgentId = useStore((state) => state.currentAgentId);
  const authUser = useStore((state) => state.authUser);
  const setDraft = useStore((state) => state.setDraft);
  const focusComposer = useStore((state) => state.focusComposer);
  const agent = agents.find((item) => item.agentId === currentAgentId);
  const meta = agentMeta(currentAgentId);

  const hour = new Date().getHours();
  const greeting = hour < 5 ? '夜深了' : hour < 9 ? '早上好' : hour < 12 ? '上午好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
  const who = authUser || '';

  const fill = (prompt: string) => {
    setDraft(prompt);
    focusComposer();
  };

  return (
    <div className="flex flex-1 flex-col items-center justify-center px-8 pb-10">
      <div className="relative mb-5">
        <img src={logo} alt="logo" className="h-16 w-16 rounded-2xl shadow-panel" />
        <span className="absolute -inset-3 -z-10 rounded-3xl bg-gradient-to-br from-brand-400/25 to-violet-400/25 blur-xl" />
      </div>
      <h1 className="text-2xl font-bold tracking-tight text-balance text-content-primary">
        {greeting}{who ? `，${who}` : ''}
      </h1>
      <p className="mt-2 max-w-md text-center text-sm leading-6 text-content-muted">
        我是{agent?.agentName || 'Columbo AI 工作台'} · {agent?.agentDesc || '选择左侧智能体开始对话'}
      </p>
      <div className="mt-8 grid w-full max-w-2xl grid-cols-1 gap-2.5 sm:grid-cols-2">
        {meta.suggestions.map((card) => {
          const Icon = card.icon;
          return (
            <button
              key={card.title}
              onClick={() => fill(card.prompt)}
              className="group flex items-center gap-3 rounded-xl border border-line bg-surface-panel/70 px-4 py-3 text-left backdrop-blur transition-all hover:-translate-y-0.5 hover:border-brand-200 hover:bg-surface-panel hover:shadow-soft dark:hover:border-brand-500/30"
            >
              <span className={clsx('flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br text-white shadow-sm', card.accent)}>
                <Icon size={17} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-[13px] font-semibold text-content-primary">{card.title}</span>
                <span className="mt-0.5 block truncate text-xs text-content-muted">{card.desc}</span>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function formatElapsed(seconds: number): string {
  if (seconds < 60) {
    return `${seconds}s`;
  }
  return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function Composer() {
  const streaming = useStore((state) => state.streaming);
  const streamingSessionId = useStore((state) => state.streamingSessionId);
  const currentSessionId = useStore((state) => state.currentSessionId);
  const streamStatus = useStore((state) => state.streamStatus);
  const pendingImages = useStore((state) => state.pendingImages);
  const currentAgentId = useStore((state) => state.currentAgentId);
  const draft = useStore((state) => state.draft);
  const composerFocusTick = useStore((state) => state.composerFocusTick);
  const sendMessage = useStore((state) => state.sendMessage);
  const stopStreaming = useStore((state) => state.stopStreaming);
  const queuedMessages = useStore((state) => state.queuedMessages);
  const queueMessage = useStore((state) => state.queueMessage);
  const reorderQueue = useStore((state) => state.reorderQueue);
  const editQueued = useStore((state) => state.editQueued);
  const removeQueued = useStore((state) => state.removeQueued);
  const insertQueuedNow = useStore((state) => state.insertQueuedNow);
  const insertMessage = useStore((state) => state.insertMessage);
  const addImage = useStore((state) => state.addImage);
  const removeImage = useStore((state) => state.removeImage);
  const setDraft = useStore((state) => state.setDraft);
  const [elapsed, setElapsed] = useState(0);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editingText, setEditingText] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const meta = agentMeta(currentAgentId);

  /** 本会话正是产生流的那一个：贴身展示生成状态；其他会话只提示后台在忙 */
  const ownsStream = !streaming || streamingSessionId === currentSessionId;

  /** 触屏设备没有 Shift 键：Enter 改为换行，发送只走按钮（桌面保持 Enter 发送） */
  const coarsePointer = useMemo(
    () => typeof window !== 'undefined' && !!window.matchMedia?.('(pointer: coarse)').matches,
    [],
  );

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return;
    }
    textarea.style.height = 'auto';
    const focused = document.activeElement === textarea;
    /* 聚焦态给更高的最小高度（3 行左右），输入更从容；失焦回到紧凑双行 */
    const min = focused ? 96 : 64;
    textarea.style.height = `${Math.min(Math.max(textarea.scrollHeight, min), 180)}px`;
  }, [draft, composerFocusTick]);

  // 欢迎页示例卡等外部入口触发聚焦
  useEffect(() => {
    if (composerFocusTick > 0) {
      textareaRef.current?.focus();
    }
  }, [composerFocusTick]);

  // 流式耗时计时（状态胶囊展示）
  useEffect(() => {
    if (!streaming || !ownsStream) {
      setElapsed(0);
      return;
    }
    const startAt = Date.now();
    setElapsed(0);
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - startAt) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [streaming, ownsStream]);

  const submit = () => {
    const hasContent = !!draft.trim() || pendingImages.length > 0;
    if (!hasContent) {
      return;
    }
    // 生成中：Enter 默认排队（生成结束按队列顺序自动发送）；插入只通过队列卡片的"立即"按钮
    if (streaming) {
      if (ownsStream && draft.trim()) {
        queueMessage(draft.trim());
        setDraft('');
        useStore.getState().pushToast('info', '已排队，生成结束按序自动发送；可点"立即"优先插入');
      }
      return;
    }
    sendMessage(draft);
    setDraft('');
  };

  const pickImages = (files: FileList | null) => {
    if (!files) {
      return;
    }
    for (const file of Array.from(files)) {
      if (!file.type.startsWith('image/')) {
        continue;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        useStore.getState().pushToast('error', `图片 ${file.name} 超过 5MB，已跳过`);
        continue;
      }
      const reader = new FileReader();
      reader.onerror = () => {
        useStore.getState().pushToast('error', `图片 ${file.name} 读取失败`);
      };
      reader.onload = () => {
        if (useStore.getState().pendingImages.length < MAX_IMAGES) {
          addImage(String(reader.result));
        }
      };
      reader.readAsDataURL(file);
    }
  };

  // 支持直接粘贴截图（Ctrl/Cmd+V，迁自 trace-bot 的体验）
  const onPasteImages = (event: React.ClipboardEvent) => {
    const items = event.clipboardData?.files;
    if (items && items.length > 0 && Array.from(items).some((file) => file.type.startsWith('image/'))) {
      event.preventDefault();
      pickImages(items);
    }
  };

  return (
    <div className="border-t border-line bg-surface-panel/70 px-4 pb-3 pt-4 backdrop-blur sm:px-6">
      {/* 输入卡片：单一浮层，通栏全宽——聚焦时整卡亮起品牌描边 + 品牌辉光 */}
      <div
        className="rounded-2xl border border-line-strong bg-surface-panel p-2.5 shadow-panel transition-all focus-within:border-brand-400 focus-within:ring-4 focus-within:ring-brand-500/10"
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault();
          pickImages(event.dataTransfer.files);
        }}
      >
        <ContextBar />
        {pendingImages.length > 0 && (
          <div className="mb-2 flex flex-wrap gap-2 px-1.5 pt-1">
            {pendingImages.map((dataUrl) => (
              <div key={dataUrl} className="group relative">
                <img src={dataUrl} alt="附图" className="h-16 w-16 rounded-xl border border-line object-cover shadow-soft" />
                <button
                  onClick={() => removeImage(dataUrl)}
                  aria-label="移除附图"
                  className="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-content-secondary text-white opacity-100 shadow-soft transition-opacity hover:bg-content-primary sm:opacity-0 sm:group-hover:opacity-100"
                >
                  <X size={11} />
                </button>
              </div>
            ))}
          </div>
        )}
        <div className="px-1.5 pb-1 pt-0.5">
          <textarea
            ref={textareaRef}
            rows={2}
            value={draft}
            placeholder={meta.placeholder || '描述你的需求，Enter 发送，Shift+Enter 换行；可直接粘贴截图'}
            className="thin-scroll min-h-[64px] max-h-[180px] w-full resize-none border-0 bg-transparent p-0 text-[15px] leading-[1.7] text-content-primary outline-hidden transition-[min-height] placeholder:text-content-muted focus:min-h-[96px]"
            onFocus={() => {
              /* 聚焦即时展开（useEffect 只在 draft 变化时兜底） */
              const textarea = textareaRef.current;
              if (textarea) {
                textarea.style.height = 'auto';
                textarea.style.height = `${Math.min(Math.max(textarea.scrollHeight, 96), 180)}px`;
              }
            }}
            onBlur={() => {
              const textarea = textareaRef.current;
              if (textarea && !textarea.value) {
                textarea.style.height = 'auto';
                textarea.style.height = '64px';
              }
            }}
            onChange={(event) => setDraft(event.target.value)}
            onPaste={onPasteImages}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                if (coarsePointer || event.shiftKey) {
                  // 触屏/Shift+Enter：换行（交由原生行为插入换行）
                  return;
                }
                event.preventDefault();
                submit();
              }
            }}
          />
          <div className="mt-1.5 flex flex-wrap items-center justify-between gap-2.5">
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-1.5">
              {queuedMessages.length > 0 && (
                <div className="w-full rounded-xl border border-amber-500/25 bg-amber-500/[0.06] p-1.5">
                  <div className="px-1.5 pb-1 text-[10.5px] font-medium text-amber-700/80 dark:text-amber-400/80">
                    排队中 {queuedMessages.length} 条 · 拖拽排序，生成结束按序自动发送
                  </div>
                  <div className="flex flex-col gap-1">
                    {queuedMessages.map((item, index) => (
                      <div
                        key={item.id}
                        draggable
                        onDragStart={(event) => {
                          event.dataTransfer.setData('text/queue-id', String(item.id));
                          event.dataTransfer.effectAllowed = 'move';
                        }}
                        onDragOver={(event: DragEvent<HTMLDivElement>) => {
                          event.preventDefault();
                          event.dataTransfer.dropEffect = 'move';
                        }}
                        onDrop={(event: DragEvent<HTMLDivElement>) => {
                          event.preventDefault();
                          const fromId = Number(event.dataTransfer.getData('text/queue-id'));
                          if (!Number.isNaN(fromId) && fromId !== item.id) {
                            reorderQueue(fromId, item.id);
                          }
                        }}
                        className="flex items-center gap-1.5 rounded-lg bg-surface-panel/80 px-1.5 py-1 ring-1 ring-black/[0.03] dark:ring-white/[0.06]"
                      >
                        <GripVertical size={12} className="shrink-0 cursor-grab text-content-faint" />
                        <span className="shrink-0 text-[10.5px] text-content-faint">{index + 1}.</span>
                        {editingId === item.id ? (
                          <input
                            autoFocus
                            value={editingText}
                            onChange={(event) => setEditingText(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter' && !event.nativeEvent.isComposing && editingText.trim()) {
                                editQueued(item.id, editingText.trim());
                                setEditingId(null);
                              } else if (event.key === 'Escape') {
                                setEditingId(null);
                              }
                            }}
                            onBlur={() => {
                              if (editingText.trim()) {
                                editQueued(item.id, editingText.trim());
                              }
                              setEditingId(null);
                            }}
                            className="min-w-0 flex-1 rounded-md border border-brand-300 bg-surface-input px-1.5 py-0.5 text-[12px] text-content-primary outline-hidden"
                          />
                        ) : (
                          <span
                            className="min-w-0 flex-1 cursor-text truncate text-[12px] text-content-secondary"
                            onDoubleClick={() => {
                              setEditingId(item.id);
                              setEditingText(item.text);
                            }}
                            title="双击编辑"
                          >
                            {item.text}
                          </span>
                        )}
                        <button
                          onClick={() => void insertQueuedNow(item.id)}
                          disabled={!streaming || !ownsStream}
                          className="inline-flex shrink-0 items-center gap-0.5 rounded-md bg-brand-500/10 px-1.5 py-0.5 text-[10.5px] font-medium text-brand-700 transition-colors hover:bg-brand-500/20 disabled:cursor-not-allowed disabled:opacity-40 dark:text-brand-300"
                          title="跳过剩余队列，立即插入到正在生成的对话（模型下一工具轮前可见）"
                        >
                          <ArrowUp size={10} /> 立即
                        </button>
                        <button
                          onClick={() => {
                            setEditingId(item.id);
                            setEditingText(item.text);
                          }}
                          aria-label="编辑排队消息"
                          className="shrink-0 rounded-md p-1 text-content-faint transition-colors hover:bg-surface-raise hover:text-content-secondary"
                          title="编辑"
                        >
                          <Pencil size={11} />
                        </button>
                        <button
                          onClick={() => removeQueued(item.id)}
                  aria-label="删除排队消息"
                  className="shrink-0 rounded-md p-1 text-content-faint transition-colors hover:bg-surface-raise hover:text-content-secondary"
                  title="删除（文本不保留）"
                >
                  <X size={11} />
                </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {streaming && ownsStream ? (
                <span className="inline-flex h-8 max-w-full items-center gap-1.5 rounded-full bg-surface-panel/85 px-2.5 text-[11px] font-medium text-content-secondary ring-1 ring-black/[0.04] dark:ring-white/10">
                  <Loader2 size={12} className="shrink-0 animate-spin text-content-muted" />
                  <span className="truncate">{streamStatus?.step || '生成中'}</span>
                  <span className="shrink-0 text-content-faint">·</span>
                  <span className="shrink-0 tnum text-content-muted">{formatElapsed(elapsed)}</span>
                </span>
              ) : null}
            </div>
            <div className="flex items-center justify-end gap-2">
              {streaming && ownsStream ? (
                <>
                  <button
                    onClick={stopStreaming}
                    className="inline-flex h-9 items-center gap-1.5 rounded-full bg-red-50 px-3.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-100 dark:bg-red-500/10 dark:text-red-400 dark:hover:bg-red-500/20"
                    title="停止生成"
                  >
                    <Square size={13} fill="currentColor" />
                    停止
                  </button>
                  {/* 流式期间发送按钮变为"排队"入口：有输入可点（加入队列），无输入占位禁用 */}
                  <div className="inline-flex items-center gap-1 rounded-full border border-line bg-surface-input p-[3px] shadow-xs">
                    <button
                      onClick={submit}
                      disabled={!draft.trim()}
                      title={draft.trim() ? '加入队列，本轮生成结束按序自动发送；队列卡片上可"立即"插入' : '生成中，输入内容后回车排队'}
                      className={clsx(
                        'inline-flex h-[30px] items-center gap-1.5 rounded-full px-3.5 text-[11px] font-semibold text-white transition-all',
                        draft.trim()
                          ? 'bg-brand-600 shadow-soft hover:-translate-y-[1px] hover:bg-brand-500 hover:shadow-brand'
                          : 'cursor-not-allowed bg-content-faint/40 text-content-muted shadow-none',
                      )}
                    >
                      <Send size={13} />
                      排队
                    </button>
                  </div>
                </>
              ) : (
                <>
                <div className="inline-flex items-center gap-1 rounded-full border border-line bg-surface-input p-[3px] shadow-xs">
                  <button
                    type="button"
                    onClick={() => fileRef.current?.click()}
                    className="inline-flex h-[30px] w-[30px] items-center justify-center rounded-full text-content-muted transition-all hover:bg-surface-panel hover:text-content-primary"
                    title={`附图（最多 ${MAX_IMAGES} 张，支持拖拽/粘贴；排查助手会自动识别）`}
                  >
                    <Paperclip size={15} />
                  </button>
                  <input
                    ref={fileRef}
                    type="file"
                    accept="image/*"
                    multiple
                    className="hidden"
                    onChange={(event) => {
                      pickImages(event.target.files);
                      event.target.value = '';
                    }}
                  />
                  <button
                    onClick={submit}
                    disabled={(!draft.trim() && pendingImages.length === 0) || streaming}
                    className={clsx(
                      'inline-flex h-[30px] items-center gap-1.5 rounded-full px-3.5 text-[11px] font-semibold text-white transition-all',
                      (draft.trim() || pendingImages.length) && !streaming
                        ? 'bg-brand-600 shadow-soft hover:-translate-y-[1px] hover:bg-brand-500 hover:shadow-brand'
                        : 'cursor-not-allowed bg-content-faint/40 text-content-muted shadow-none',
                    )}
                    title="发送"
                  >
                    <Send size={13} />
                    发送
                  </button>
                </div>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
      <div className="mt-2 text-center text-[11px] text-content-faint">
        内容由 AI 生成，请注意核对关键信息
      </div>
    </div>
  );
}

/** 错误卡片：明确提示 + 重试（重新生成上一轮）/忽略 */
function ErrorCard({ message }: { message: string }) {
  const regenerate = useStore((state) => state.regenerate);
  const streaming = useStore((state) => state.streaming);
  const hasLastRound = useStore((state) => {
    for (let i = state.messages.length - 1; i >= 0; i--) {
      if (state.messages[i]?.role === 'user') {
        return true;
      }
    }
    return false;
  });
  return (
    <div className="mx-auto flex w-full max-w-3xl items-start gap-3 rounded-2xl border border-rose-200 bg-rose-50/80 px-4 py-3 dark:border-rose-500/25 dark:bg-rose-500/10">
      <AlertCircle size={16} className="mt-0.5 shrink-0 text-rose-500" />
      <div className="min-w-0 flex-1">
        <div className="break-words text-[13px] leading-6 text-rose-600 dark:text-rose-400">{message}</div>
        <div className="mt-2 flex gap-2">
          {hasLastRound && (
            <button
              onClick={() => regenerate()}
              disabled={streaming}
              className="inline-flex items-center gap-1.5 rounded-full bg-rose-500/10 px-3 py-1.5 text-[11px] font-medium text-rose-600 transition-colors hover:bg-rose-500/20 disabled:opacity-50 dark:text-rose-400"
            >
              <RefreshCw size={11} /> 重新生成
            </button>
          )}
          <button
            onClick={() => useStore.setState({ error: null })}
            className="rounded-full px-3 py-1.5 text-[11px] font-medium text-content-muted transition-colors hover:bg-surface-raise hover:text-content-secondary"
          >
            知道了
          </button>
        </div>
      </div>
    </div>
  );
}

/** 顶栏字号切换：小 / 中 / 大 三档分段控件（作用于助手正文排版） */
function FontScaleSwitch() {
  const fontScale = useStore((state) => state.fontScale);
  const setFontScale = useStore((state) => state.setFontScale);
  const options: { value: 'small' | 'medium' | 'large'; label: string; text: string }[] = [
    { value: 'small', label: '小', text: 'text-[11px]' },
    { value: 'medium', label: '中', text: 'text-xs' },
    { value: 'large', label: '大', text: 'text-[13px]' },
  ];
  return (
    <div
      className="flex items-center rounded-full border border-line bg-surface-raise p-[2px]"
      role="group"
      aria-label="正文字号"
      title="调整助手回复正文字号"
    >
      <TypeOutline size={12} className="ml-1.5 shrink-0 text-content-faint" />
      {options.map((option) => (
        <button
          key={option.value}
          onClick={() => setFontScale(option.value)}
          aria-pressed={fontScale === option.value}
          className={clsx(
            'rounded-full px-2 py-0.5 font-medium transition-colors',
            option.text,
            fontScale === option.value
              ? 'bg-surface-panel text-content-primary shadow-xs'
              : 'text-content-faint hover:text-content-secondary',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function ChatPanel() {
  const messages = useStore((state) => state.messages);
  const currentAgentId = useStore((state) => state.currentAgentId);
  const error = useStore((state) => state.error);
  const agents = useStore((state) => state.agents);
  const sessions = useStore((state) => state.sessions);
  const streaming = useStore((state) => state.streaming);
  const streamingSessionId = useStore((state) => state.streamingSessionId);
  const currentSessionId = useStore((state) => state.currentSessionId);
  const setSidebarOpen = useStore((state) => state.setSidebarOpen);
  const agent = agents.find((item) => item.agentId === currentAgentId);
  const meta = agentMeta(currentAgentId);
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);
  const [showJumpButton, setShowJumpButton] = useState(false);

  // 贴底才自动跟随滚动：用户上翻阅读时不被强行拉回底部
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) {
      return;
    }
    const onScroll = () => {
      const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
      atBottomRef.current = atBottom;
      setShowJumpButton(!atBottom && container.scrollHeight > container.clientHeight + 200);
    };
    container.addEventListener('scroll', onScroll, { passive: true });
    return () => container.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    // 流式期间每个增量都会触发本 effect：用瞬时跟随（smooth 动画逐 delta 叠加
    // 会产生连续"翻页"感，且把用户上翻阅读的动作拽回去）；新消息完成态仍用平滑滚动
    if (atBottomRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: streaming ? 'auto' : 'smooth', block: 'end' });
    }
  }, [messages, streaming]);

  // 用户自己发消息（最后一条是 user）时无条件贴底
  useEffect(() => {
    const last = messages[messages.length - 1];
    if (last?.role === 'user') {
      atBottomRef.current = true;
      bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }, [messages]);

  // 后台流横幅：当前查看的不是生成中的会话时提示（不阻塞阅读）
  const streamingSession = useMemo(
    () =>
      streaming && streamingSessionId && streamingSessionId !== currentSessionId
        ? sessions.find((item) => item.sessionId === streamingSessionId)
        : undefined,
    [streaming, streamingSessionId, currentSessionId, sessions],
  );

  const currentSession = useMemo(
    () => sessions.find((item) => item.sessionId === currentSessionId),
    [sessions, currentSessionId],
  );

  return (
    <section className="flex min-w-0 flex-1 flex-col bg-surface-app">
      <header className="flex items-center gap-3 border-b border-line bg-surface-panel/70 px-4 py-2.5 backdrop-blur sm:px-5">
        <button
          onClick={() => setSidebarOpen(true)}
          aria-label="打开侧栏"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-content-muted transition-colors hover:bg-surface-raise hover:text-content-primary lg:hidden"
        >
          <Menu size={16} />
        </button>
        <span className={clsx('flex h-8 w-8 shrink-0 items-center justify-center rounded-lg', meta.chip)}>
          <meta.icon size={16} strokeWidth={2.2} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm font-semibold text-content-primary">{agent?.agentName || '…'}</span>
            {currentSession && (
              <>
                <span className="text-content-faint">/</span>
                <span className="truncate text-sm text-content-secondary" title={currentSession.title}>
                  {currentSession.title || '新对话'}
                </span>
              </>
            )}
          </div>
          <div className="truncate text-xs text-content-muted">{agent?.agentDesc || ''}</div>
        </div>
        <div className="ml-auto flex shrink-0 items-center gap-2">
          <FontScaleSwitch />
        </div>
      </header>

      {streamingSession && (
        <button
          onClick={() => useStore.getState().selectSession(streamingSession.sessionId)}
          className="flex items-center justify-center gap-2 border-b border-line bg-emerald-500/10 px-4 py-1.5 text-[11.5px] font-medium text-emerald-700 transition-colors hover:bg-emerald-500/15 dark:text-emerald-400"
        >
          <Loader2 size={12} className="animate-spin" />
          会话「{streamingSession.title || '新对话'}」正在后台生成 · 点击切换过去
        </button>
      )}

      <div ref={scrollRef} className="thin-scroll relative flex flex-1 flex-col gap-3.5 overflow-y-auto px-4 py-4 sm:px-5 xl:px-8">
        <div className="mx-auto flex w-full max-w-5xl flex-col gap-3.5">
          {messages.length === 0 ? (
            <Welcome />
          ) : (
            messages.map((message, index) => {
              if (!message || !message.role) {
                return null;
              }
              return (
                <MessageView
                  key={`${currentSessionId}-${index}-${message.role}`}
                  message={message}
                  isLast={index === messages.length - 1}
                />
              );
            })
          )}
          {error && <ErrorCard message={error} />}
          <div ref={bottomRef} className="h-px" />
        </div>

        {/* 上翻后浮动"回到底部"按钮 */}
        {showJumpButton && (
          <button
            onClick={() => {
              atBottomRef.current = true;
              bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
            }}
            aria-label="回到底部"
            className="sticky bottom-2 left-1/2 z-10 flex h-9 w-9 -translate-x-1/2 items-center justify-center self-center rounded-full border border-line bg-surface-panel/95 text-content-secondary shadow-panel backdrop-blur transition-all hover:-translate-y-0.5 hover:text-content-primary"
          >
            <ArrowDown size={15} />
          </button>
        )}
      </div>

      <Composer />
    </section>
  );
}
