import { create } from 'zustand';
import { api, streamChat, NOT_LOGIN_EVENT } from './api';
import type { Agent, ChatMessage, ChatSession, MsgSegment, StreamStatus, ToolTrace } from './types';

const USER_ID_KEY = 'tongxin.user.id';
const AGENT_ID_KEY = 'tongxin.agent.id';
const CONTEXT_KEY = 'tongxin.agent.context';
const THEME_KEY = 'tongxin.theme';
const FONT_SCALE_KEY = 'tongxin.fontScale';

export type Theme = 'light' | 'dark';
/** 正文字号档位：小 / 中（默认）/ 大 */
export type FontScale = 'small' | 'medium' | 'large';


export interface Toast {
  id: number;
  type: 'success' | 'error' | 'info';
  message: string;
}

/** 每个智能体的上下文选择（环境/日志环境/工程/数据库），发送时按约定注入消息 */
export interface AgentContext {
  env?: string;
  logEnv?: string;
  project?: string;
  db?: string;
}

function loadContext(): Record<string, AgentContext> {
  try {
    return JSON.parse(localStorage.getItem(CONTEXT_KEY) || '{}');
  } catch {
    return {};
  }
}

/** 排查类智能体（工作流版 100011 / 直出版 100014）：共用区域/日志环境/工程上下文注入 */
const TROUBLESHOOT_AGENT_IDS = new Set(['100011', '100014']);

/** 按智能体约定拼接上下文前缀/后缀（与后端 instruction 的【xx：yy】约定一致，迁自 trace-bot） */
function applyContext(agentId: string, context: AgentContext | undefined, message: string): string {
  if (!context) {
    return message;
  }
  if (TROUBLESHOOT_AGENT_IDS.has(agentId)) {
    let prefix = '';
    if (context.env) {
      prefix += `【环境：${context.env}】`;
    }
    if (context.logEnv && context.logEnv !== 'product') {
      prefix += `【日志环境：${context.logEnv}】`;
    }
    if (context.project) {
      prefix += `【本地工程：${context.project}】`;
    }
    return prefix ? `${prefix} ${message}` : message;
  }
  if (agentId === '100012' && context.db) {
    return `【数据库：${context.db}】 ${message}`;
  }
  return message;
}

/**
 * 历史消息 content 里的 ⟦think⟧ 标记 → 时间线分段（思考/正文按出现顺序交错，
 * 工具轨迹不落库、历史无 tools 段）。流式消息的分段由事件直接构建，不走这里。
 */
export function parseThinkSegments(content: string): MsgSegment[] {
  const segments: MsgSegment[] = [];
  const pattern = /⟦think⟧([\s\S]*?)⟦\/think⟧(⟦thinktime⟧(\d+)[^⟦]*⟦\/thinktime⟧)?/g;
  let cursor = 0;
  for (const match of content.matchAll(pattern)) {
    const before = content.slice(cursor, match.index);
    if (before.trim()) {
      segments.push({ kind: 'text', text: before.trim() });
    }
    if (match[1]?.trim()) {
      segments.push({
        kind: 'think',
        text: match[1].trim(),
        active: false,
        costMs: match[3] ? Number(match[3]) : null,
      });
    }
    cursor = (match.index ?? 0) + match[0].length;
  }
  const tail = content.slice(cursor);
  if (tail.trim()) {
    segments.push({ kind: 'text', text: tail.trim() });
  }
  return segments;
}

/** 兼容旧调用（MessageView 之外无人用）：保留拆解形态 */
export function parseThinkMarkers(content: string): { think: string; costMs: number | null; text: string } {
  const segments = parseThinkSegments(content);
  const thinks = segments.filter((s): s is Extract<MsgSegment, { kind: 'think' }> => s.kind === 'think');
  const text = segments.filter((s) => s.kind === 'text').map((s) => s.text).join('\n\n');
  const lastCost = [...thinks].reverse().find((s) => s.costMs != null)?.costMs ?? null;
  return { think: thinks.map((s) => s.text).join('\n\n'), costMs: lastCost, text };
}

// ---------- 流式分段构建（时间线：思考→工具→思考→…→正文） ----------

function segmentsOf(message: ChatMessage): MsgSegment[] {
  return message.segments ?? [];
}

/** 思考增量：活跃思考段追加；上一段已闭合（新工具轮/新阶段）则开新段 */
function appendThinkDelta(message: ChatMessage, content: string, stage: string | null): MsgSegment[] {
  const segments = [...segmentsOf(message)];
  const last = segments[segments.length - 1];
  if (last && last.kind === 'think' && last.active) {
    segments[segments.length - 1] = { ...last, text: last.text + content };
  } else {
    segments.push({ kind: 'think', text: content, active: true, costMs: null, stage });
  }
  return segments;
}

/** 思考段闭合（该面板独立计时） */
function closeActiveThink(message: ChatMessage, costMs: number): MsgSegment[] {
  const segments = [...segmentsOf(message)];
  const last = segments[segments.length - 1];
  if (last && last.kind === 'think' && last.active) {
    segments[segments.length - 1] = { ...last, active: false, costMs };
  }
  return segments;
}

/** 正文增量：追加到末尾正文段（无则新建——与前面的思考/工具段按时间顺序交错） */
function appendTextDelta(message: ChatMessage, content: string): MsgSegment[] {
  const segments = [...segmentsOf(message)];
  const last = segments[segments.length - 1];
  if (last && last.kind === 'text') {
    segments[segments.length - 1] = { ...last, text: last.text + content };
  } else {
    segments.push({ kind: 'text', text: content });
  }
  return segments;
}

/** 工具结果：连续工具结果并入同一 tools 段 */
function pushToolTrace(message: ChatMessage, trace: ToolTrace): MsgSegment[] {
  const segments = [...segmentsOf(message)];
  const last = segments[segments.length - 1];
  if (last && last.kind === 'tools') {
    segments[segments.length - 1] = { ...last, traces: [...last.traces, trace] };
  } else {
    segments.push({ kind: 'tools', traces: [trace] });
  }
  return segments;
}

/** 思考增量的来源 agent → 阶段标签（多阶段工作流前端标注，未登记的 agent 不标注） */
const THINK_STAGE_LABELS: Record<string, string> = {
  logEvidenceAgent: '取证',
  logReportAgent: '成文',
};

export function thinkStageLabelOf(author?: string): string | null {
  return (author && THINK_STAGE_LABELS[author]) || null;
}

/** 全局确认/输入弹窗（替代 window.confirm / window.prompt，与整体质感一致） */
export interface DialogRequest {
  type: 'confirm' | 'prompt';
  title: string;
  message?: string;
  placeholder?: string;
  value?: string;
  danger?: boolean;
  confirmText?: string;
  resolve: (result: boolean | string | null) => void;
}

interface AppState {
  userId: string;
  agents: Agent[];
  agentsLoading: boolean;
  currentAgentId: string;
  /** 各智能体可切换模型池与当前模型（chat-model.models 配置） */
  agentModels: { agentId: string; models: string[]; current: string }[];
  modelSwitching: boolean;

  sessions: ChatSession[];
  currentSessionId: string;
  messages: ChatMessage[];

  /** 是否有流在生成（后端单活跃流约定）；streamingSessionId/streamingAgentId 指明归属 */
  streaming: boolean;
  streamingSessionId: string;
  streamingAgentId: string;
  /** 生成中排队下一条追问；null=无排队 */
  queueMessage: (text: string) => void;
  cancelQueued: () => void;
  reorderQueue: (fromId: number, toId: number) => void;
  editQueued: (id: number, text: string) => void;
  removeQueued: (id: number) => void;
  insertQueuedNow: (id: number) => Promise<void>;
  /** 流式期间插入指令：模型在下一工具轮前可见；后端无进行中流时自动转为排队 */
  insertMessage: (text: string) => Promise<void>;
  /** 排队追问退回所属会话的输入框 */
  restoreQueued: (text: string, sessionId: string | null) => void;
  streamStatus: StreamStatus | null;
  /** 流式期间排队的追问队列（FIFO；支持拖拽排序/编辑/删除/立即插入） */
  queuedMessages: { id: number; sessionId: string; text: string }[];

  pendingImages: string[];
  error: string | null;
  /** 输入框草稿（当前会话的；欢迎页示例卡等外部入口可填入） */
  draft: string;
  /** 各会话草稿存档：切换会话时恢复各自的，不串内容（key 为 sessionId，新对话用空 key） */
  draftBySession: Record<string, string>;
  /** 触发输入框聚焦的信号（自增即可） */
  composerFocusTick: number;
  /** 主视图：chat 对话 | knowledge 知识库 */
  view: 'chat' | 'knowledge';
  /** 主题（亮/暗），localStorage 持久化，默认跟随系统 */
  theme: Theme;
  /** 正文字号档位：small 小 / medium 中（默认）/ large 大，localStorage 持久化 */
  fontScale: FontScale;
  setFontScale: (scale: FontScale) => void;
  /** 轻提示栈 */
  toasts: Toast[];
  /** Cmd+K 命令面板开关 */
  paletteOpen: boolean;
  /** 各智能体的上下文选择（区域/工程/数据库/工具），localStorage 持久化 */
  contextByAgent: Record<string, AgentContext>;
  /** 确认/输入弹窗（全局单例） */
  dialog: DialogRequest | null;
  /** 窄屏侧栏抽屉开关（lg 以下生效） */
  sidebarOpen: boolean;

  init: () => Promise<void>;
  /** 登录态：null=未登录（显示登录页）；authChecked=false 表示启动探测未完成 */
  authUser: string | null;
  authChecked: boolean;
  /** 登录过期：清空本地登录态与界面数据，切回登录页 */
  handleNotLogin: (message?: string) => void;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, name: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  setUserId: (userId: string) => void;
  setAgent: (agentId: string) => void;
  setContext: (agentId: string, patch: AgentContext) => void;
  newChat: () => void;
  selectSession: (sessionId: string) => Promise<void>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  moveSession: (sessionId: string, space: string) => Promise<void>;
  /** 归档空间筛选：null=全部、NONE_SPACE=未归档、其余=空间名 */
  activeSpace: string | null;
  setActiveSpace: (space: string | null) => void;
  sendMessage: (text: string) => Promise<void>;
  /** 用上一轮用户消息原文重新生成（不再叠加上下文前缀） */
  regenerate: () => Promise<void>;
  /** 发送执行体（sendMessage/regenerate 共用）：message 已是最终文案 */
  startStream: (args: { message: string; images: string[]; raw?: boolean }) => Promise<void>;
  stopStreaming: () => void;
  addImage: (dataUrl: string) => void;
  removeImage: (dataUrl: string) => void;
  setDraft: (text: string) => void;
  focusComposer: () => void;
  setView: (view: 'chat' | 'knowledge') => void;
  toggleTheme: () => void;
  pushToast: (type: Toast['type'], message: string) => void;
  dismissToast: (id: number) => void;
  setPaletteOpen: (open: boolean) => void;
  refreshSessions: () => Promise<void>;
  refreshAgentModels: () => Promise<void>;
  switchModel: (agentId: string, model: string) => Promise<void>;
  /** 全局确认弹窗（await 确认结果） */
  confirmDialog: (options: { title: string; message?: string; danger?: boolean; confirmText?: string }) => Promise<boolean>;
  /** 全局输入弹窗（await 输入内容；取消返回 null） */
  promptDialog: (options: { title: string; message?: string; placeholder?: string; value?: string }) => Promise<string | null>;
  resolveDialog: (result: boolean | string | null) => void;
  setSidebarOpen: (open: boolean) => void;
}

let abortController: AbortController | null = null;
/** 排队消息自增 id（拖拽排序/编辑/删除的稳定键） */
let nextQueueId = 1;
const detachedStreams = new Map<string, DetachedStream>();
/** 当前活跃流的会话 id（停止生成时只清自己的缓冲） */
const activeStreamSid: { value: string } = { value: '' };
/** StrictMode 下 init 双跑守卫（每次显式登录/登出会重置） */
let lastInitAt = 0;

/** 切走后仍在后台生成的流（按会话缓冲增量，切回时恢复视图；分段直接挂在 assistantMsg 上） */
interface DetachedStream {
  sessionId: string;
  agentId: string;
  userMsg: ChatMessage;
  assistantMsg: ChatMessage;
}
/** 递增的发送序号：旧流中止后的 finally 不得覆盖新一轮发送的 streaming 状态 */
let activeSendSeq = 0;
/** 会话消息加载序号：快速连点会话时，慢响应不得覆盖后选会话 */
let selectSeq = 0;

export const useStore = create<AppState>((set, get) => ({
  authUser: null,
  authChecked: false,
  userId: localStorage.getItem(USER_ID_KEY) || 'caps',
  agents: [],
  agentsLoading: true,
  currentAgentId: localStorage.getItem(AGENT_ID_KEY) || '',
  agentModels: [],
  modelSwitching: false,

  sessions: [],
  currentSessionId: '',
  messages: [],

  streaming: false,
  streamingSessionId: '',
  streamingAgentId: '',
  streamStatus: null,
  queuedMessages: [],

  pendingImages: [],
  error: null,
  draft: '',
  draftBySession: {},
  composerFocusTick: 0,
  view: 'chat',
  theme: ((): Theme => {
    const saved = localStorage.getItem(THEME_KEY);
    if (saved === 'light' || saved === 'dark') {
      return saved;
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  })(),
  fontScale: ((): FontScale => {
    const saved = localStorage.getItem(FONT_SCALE_KEY);
    return saved === 'small' || saved === 'large' ? saved : 'medium';
  })(),
  toasts: [],
  paletteOpen: false,
  contextByAgent: loadContext(),
  dialog: null,
  sidebarOpen: false,

  init: async () => {
    // StrictMode 双跑守卫：500ms 内的重复调用直接跳过（login/logout 会重置时间戳以支持重放）
    if (Date.now() - lastInitAt < 500) {
      return;
    }
    lastInitAt = Date.now();
    // 登录态探测：未登录只置状态不拉业务数据（登录页展示）
    const me = await api.probeLogin();
    if (!me) {
      set({ authUser: null, authChecked: true, agentsLoading: false });
      return;
    }
    set({ authUser: me.username });
    // 会话归属跟随登录名（换账号登录时自动切到自己的会话）
    if (get().userId !== me.username) {
      localStorage.setItem(USER_ID_KEY, me.username);
      set({ userId: me.username, sessions: [], currentSessionId: '', messages: [] });
    }
    try {
      const agents = await api.queryAgents();
      const currentAgentId =
        get().currentAgentId && agents.some((agent) => agent.agentId === get().currentAgentId)
          ? get().currentAgentId
          : agents[0]?.agentId || '';
      set({ agents, agentsLoading: false, currentAgentId });
      localStorage.setItem(AGENT_ID_KEY, currentAgentId);
      await get().refreshSessions();
      await get().refreshAgentModels();
    } catch (error) {
      set({ agentsLoading: false, error: (error as Error).message });
    } finally {
      set({ authChecked: true });
    }
  },

  handleNotLogin: (message) => {
    get().stopStreaming();
    set({
      authUser: null,
      authChecked: true,
      agents: [],
      sessions: [],
      messages: [],
      currentSessionId: '',
      error: message || null,
      dialog: null,
      queuedMessages: [],
      draft: '',
      draftBySession: {},
      pendingImages: [],
    });
    if (message) {
      get().pushToast('error', message);
    }
  },

  login: async (username, password) => {
    const data = await api.login(username, password);
    set({ authUser: data.username, error: null });
    localStorage.setItem(USER_ID_KEY, data.username);
    set({ userId: data.username, sessions: [], currentSessionId: '', messages: [] });
    lastInitAt = 0;
    await get().init();
  },

  register: async (username, name, password) => {
    const data = await api.register(username, name, password);
    set({ authUser: data.username, error: null });
    localStorage.setItem(USER_ID_KEY, data.username);
    set({ userId: data.username, sessions: [], currentSessionId: '', messages: [] });
    lastInitAt = 0;
    await get().init();
  },

  logout: async () => {
    get().stopStreaming();
    await api.logout();
    set({ authUser: null, agents: [], sessions: [], messages: [], currentSessionId: '', queuedMessages: [], draft: '', draftBySession: {}, pendingImages: [] });
  },

  setUserId: (userId) => {
    localStorage.setItem(USER_ID_KEY, userId);
    set({ userId, sessions: [], currentSessionId: '', messages: [] });
    get().refreshSessions();
  },

  setAgent: (agentId) => {
    // 生成中切换不再中断流：增量进 detachedStream，切回该会话可恢复显示
    localStorage.setItem(AGENT_ID_KEY, agentId);
    set({
      currentAgentId: agentId,
      currentSessionId: '',
      messages: [],
      view: 'chat',
      error: null,
    });
  },

  setContext: (agentId, patch) => {
    const merged = { ...get().contextByAgent[agentId], ...patch };
    const next = { ...get().contextByAgent, [agentId]: merged };
    const persisted = next;
    localStorage.setItem(CONTEXT_KEY, JSON.stringify(persisted));
    set({ contextByAgent: next });
  },

  newChat: () => {
    if (get().view !== 'chat') {
      set({ view: 'chat' });
    }
    // 新对话重置工具选择回"自动"（区域/数据库上下文保留）
    const agentId = get().currentAgentId;
    set({ currentSessionId: '', messages: [], error: null, draft: get().draftBySession[''] ?? '' });
  },

  selectSession: async (sessionId) => {
    // 生成中切换不再中断流：切走的流进 detachedStream 后台继续，切回时恢复
    // 会话归属的智能体可能与当前选中不同（侧栏按助手分组展示全部历史）：
    // 先切到会话所属智能体，否则后端在错误的 appName 下查不到会话，表现为"点击没反应"
    const seq = ++selectSeq;
    const session = get().sessions.find((item) => item.sessionId === sessionId);
    const belongsTo = session && get().agents.some((agent) => agent.agentId === session.agentId)
      ? session.agentId
      : get().currentAgentId;
    if (belongsTo !== get().currentAgentId) {
      localStorage.setItem(AGENT_ID_KEY, belongsTo);
      set({ currentAgentId: belongsTo });
    }
    const { userId } = get();
    set({
      currentSessionId: sessionId,
      error: null,
      view: 'chat',
      // 恢复该会话自己的草稿（各会话互不串）
      draft: get().draftBySession[sessionId] ?? '',
    });
    // 该会话正在后台生成：优先用缓冲里的进行中消息（后端尚未落库）
    const detached = detachedStreams.get(sessionId);
    try {
      const history = await api.queryMessages(belongsTo, userId, sessionId);
      if (seq !== selectSeq) {
        return; // 快速连点：慢响应不覆盖后选会话
      }
      const messages = (Array.isArray(history) ? history : []).filter(Boolean);
      if (detached) {
        set({
          messages: [...messages, detached.userMsg, detached.assistantMsg].filter(Boolean),
          streamStatus: { phase: 'thinking', step: '生成中（已恢复显示）', detail: '' },
        });
      } else {
        set({ messages });
      }
    } catch (error) {
      if (seq !== selectSeq) {
        return;
      }
      set({ error: (error as Error).message, messages: [] });
    }
  },

  deleteSession: async (sessionId) => {
    const { userId } = get();
    // 侧栏会话来自所有智能体：必须用会话归属的 agentId 删，
    // 用当前选中的 agentId 会在错误的 appName 下删不到（后端仍返回成功但什么都没删）
    const session = get().sessions.find((item) => item.sessionId === sessionId);
    const belongsTo = session && get().agents.some((agent) => agent.agentId === session.agentId)
      ? session.agentId
      : get().currentAgentId;
    try {
      await api.deleteSession(belongsTo, userId, sessionId);
      get().pushToast('success', '会话已删除');
    } catch (error) {
      get().pushToast('error', `删除失败: ${(error as Error).message}`);
      return;
    }
    // 清该会话草稿；排队追问属于它则一并取消（文本退回当前输入框）
    set((state) => {
      const drafts = { ...state.draftBySession };
      delete drafts[sessionId];
      return { draftBySession: drafts };
    });
    set((state) => ({ queuedMessages: state.queuedMessages.filter((q) => q.sessionId !== sessionId) }));
    await get().refreshSessions();
    if (get().currentSessionId === sessionId) {
      get().newChat();
    }
  },

  activeSpace: null,
  setActiveSpace: (space) => set({ activeSpace: space }),

  moveSession: async (sessionId, space) => {
    const { userId } = get();
    const normalized = space.trim();
    const session = get().sessions.find((item) => item.sessionId === sessionId);
    const belongsTo = session && get().agents.some((agent) => agent.agentId === session.agentId)
      ? session.agentId
      : get().currentAgentId;
    try {
      await api.moveSession(belongsTo, userId, sessionId, normalized);
    } catch (error) {
      get().pushToast('error', `归档失败: ${(error as Error).message}`);
      return;
    }
    await get().refreshSessions();
    get().pushToast('success', normalized ? `已移至「${normalized}」` : '已移回未归档');
  },

  renameSession: async (sessionId, title) => {
    const { userId } = get();
    const trimmed = title.trim();
    if (!trimmed) return;
    // 与删除同款：用会话归属的 agentId 调用（后端按 userId+sessionId 定位，agentId 仅作鉴权入口）
    const session = get().sessions.find((item) => item.sessionId === sessionId);
    const belongsTo = session && get().agents.some((agent) => agent.agentId === session.agentId)
      ? session.agentId
      : get().currentAgentId;
    try {
      await api.renameSession(belongsTo, userId, sessionId, trimmed);
    } catch (error) {
      get().pushToast('error', `重命名失败: ${(error as Error).message}`);
      return;
    }
    // 本地立即生效，避免整列表闪烁
    set({ sessions: get().sessions.map((item) => (item.sessionId === sessionId ? { ...item, title: trimmed } : item)) });
    get().pushToast('success', '会话已重命名');
  },

  refreshSessions: async () => {
    try {
      const sessions = await api.querySessions(get().userId);
      set({ sessions });
    } catch {
      /* 会话列表失败不阻断主流程 */
    }
  },

  refreshAgentModels: async () => {
    try {
      const agentModels = await api.queryAgentModels();
      set({ agentModels });
    } catch {
      /* 模型列表失败不阻断主流程（chip 不显示） */
    }
  },

  switchModel: async (agentId, model) => {
    if (get().modelSwitching) {
      return;
    }
    set({ modelSwitching: true });
    try {
      await api.switchAgentModel(agentId, model);
      await get().refreshAgentModels();
      get().pushToast('success', `模型已切换为 ${model}（重启服务后回到默认）`);
    } catch (error) {
      get().pushToast('error', `模型切换失败: ${(error as Error).message}`);
    } finally {
      set({ modelSwitching: false });
    }
  },

  sendMessage: async (text) => {
    const { currentAgentId, userId, currentSessionId, pendingImages, streaming, contextByAgent } = get();
    const message = applyContext(currentAgentId, contextByAgent[currentAgentId], text.trim());
    const images = pendingImages;
    if (streaming) {
      // 后端单活跃流：生成中不可并发发送。给出明确提示并指路，而不是静默忽略
      const target = get().sessions.find((item) => item.sessionId === get().streamingSessionId);
      get().pushToast('info', target
        ? `会话「${target.title || '新对话'}」正在生成，可切过去查看或先停止`
        : '上一条消息还在生成，请稍候或先停止');
      return;
    }
    if (!text.trim() && images.length === 0) {
      return;
    }
    await get().startStream({ message, images, raw: false });
  },

  regenerate: async () => {
    const { streaming, messages } = get();
    if (streaming) {
      get().pushToast('info', '正在生成中，等本轮结束或停止后重试');
      return;
    }
    // 找最后一轮的用户消息原文（含上下文前缀，直接重发，不再叠加前缀）
    let lastUser: ChatMessage | undefined;
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i]?.role === 'user') {
        lastUser = messages[i];
        break;
      }
    }
    if (!lastUser) {
      return;
    }
    // 弹出最后一轮（user + assistant）后重发
    const trimmed = [...messages];
    while (trimmed.length && trimmed[trimmed.length - 1]?.role === 'assistant') {
      trimmed.pop();
    }
    if (trimmed.length && trimmed[trimmed.length - 1]?.role === 'user') {
      trimmed.pop();
    }
    set({ messages: trimmed, error: null });
    await get().startStream({ message: lastUser.content, images: lastUser.images || [], raw: true });
  },

  /** 实际发起流式请求（sendMessage 的执行体；raw=true 表示 message 已含上下文，直接用） */
  startStream: async ({ message, images }: { message: string; images: string[]; raw?: boolean }) => {
    // 本轮是否失败（失败/停止时排队追问退回输入框而非自动发出）
    let failed = false;
    const { currentAgentId, userId, currentSessionId } = get();
    set({
      error: null,
      pendingImages: [],
      streaming: true,
      streamingSessionId: currentSessionId,
      streamingAgentId: currentAgentId,
      streamStatus: { phase: 'thinking', step: '已发送', detail: '' },
      messages: [
        ...get().messages,
        { role: 'user', content: message || '（请结合附图处理）', images: images.length > 0 ? images : undefined },
        { role: 'assistant', content: '', startedAt: Date.now() },
      ],
    });

    // 发送时刻的消息基线快照：onSession 事件（RTT 窗口）到达前用户切走也不会快照到别的会话
    const baselineMessages = get().messages;
    const baselineUser = baselineMessages[baselineMessages.length - 2];
    const baselineAssistant = baselineMessages[baselineMessages.length - 1];

    // 最后一条 assistant 消息的追加写（流式期间所有增量都落在它上面）。
    // detached 缓冲是全量镜像：视图期同步写入，中途切走/切回不丢任何已流出内容
    const streamSid = { value: currentSessionId };
    activeStreamSid.value = currentSessionId;
    const originView = { agentId: currentAgentId, sessionId: currentSessionId };
    const appendAssistant = (mutate: (message: ChatMessage) => ChatMessage) => {
      // 首个正文增量到达即摘除"等待注入"标记（气泡从占位态进入真实生成态）
      const stripWaiting = (message: ChatMessage) =>
        message.awaitingInjection && message.content ? { ...message, awaitingInjection: false } : message;
      const detached = streamSid.value ? detachedStreams.get(streamSid.value) : null;
      if (detached) {
        detached.assistantMsg = stripWaiting(mutate(detached.assistantMsg));
      }
      const belongsToView =
        get().currentAgentId === originView.agentId && get().currentSessionId === streamSid.value;
      if (!belongsToView) {
        return;
      }
      const messages = [...get().messages];
      const index = messages.length - 1;
      if (!messages[index]) {
        return;
      }
      messages[index] = stripWaiting(mutate(messages[index]));
      set({ messages });
    };
    /** 视图是否仍在流所属会话（思考/工具轨迹/产物只写可见视图，缓冲侧存 detached） */
    const viewOwnsStream = () =>
      get().currentAgentId === originView.agentId && get().currentSessionId === streamSid.value;
    const detachedOf = () => (streamSid.value ? detachedStreams.get(streamSid.value) : null);

    const sendSeq = ++activeSendSeq;
    abortController = new AbortController();
    try {
      await streamChat(
        { agentId: currentAgentId, userId, sessionId: currentSessionId || undefined, message, images },
        {
          onSession: (sessionId) => {
            if (!sessionId) {
              return;
            }
            // 服务端刚建的新会话：登记 detached 缓冲（基线取发送时刻快照，不受切走影响）
            if (!detachedStreams.has(sessionId) && baselineUser && baselineAssistant) {
              detachedStreams.set(sessionId, {
                sessionId,
                agentId: currentAgentId,
                userMsg: baselineUser,
                assistantMsg: baselineAssistant,
              });
            }
            streamSid.value = sessionId;
            activeStreamSid.value = sessionId;
            // 视图还停在本会话（发送时的视图未变）才同步视图 currentSessionId
            if (
              get().currentAgentId === originView.agentId &&
              get().currentSessionId === originView.sessionId &&
              sessionId !== get().currentSessionId
            ) {
              set({ currentSessionId: sessionId, streamingSessionId: sessionId });
            } else {
              set({ streamingSessionId: sessionId });
            }
          },
          onThinkDelta: (content, author) => {
            // 思考增量直接构建消息分段：活跃思考段追加，上一段已闭合（新工具轮/工作流
            // 下一阶段）则开新段——各阶段思考按时间顺序独立成段，全程可回看
            const stage = thinkStageLabelOf(author);
            appendAssistant((last) => ({ ...last, segments: appendThinkDelta(last, content, stage) }));
            if (viewOwnsStream()) {
              set({ streamStatus: { phase: 'thinking', step: stage ? `正在${stage}阶段思考` : '正在深度思考', detail: '' } });
            }
          },
          onThinkDone: (costMs) => {
            // 闭合当前活跃思考段（每段独立计时，完成态折叠后头显"用时"）
            appendAssistant((last) => ({ ...last, segments: closeActiveThink(last, costMs) }));
          },
          onTextDelta: (content) => {
            // 正文增量：内容累计进 content（复制/去重口径），同时作为时间线正文段
            appendAssistant((last) => ({
              ...last,
              content: last.content + content,
              segments: appendTextDelta(last, content),
            }));
          },
          onStateUpdate: (step, detail) => {
            // 状态胶囊全局唯一（生成中只有一个流），直接更新；切回该会话时视觉自动恢复
            set({ streamStatus: { phase: 'tool', step, detail } });
          },
          onToolResult: (tool, success, summary) => {
            // 工具轨迹进时间线：连续工具结果并入同一 tools 段（位于两次思考段之间）
            appendAssistant((last) => ({
              ...last,
              segments: pushToolTrace(last, { tool, success, summary }),
            }));
          },
          onError: (message) => {
            failed = true;
            appendAssistant((last) => ({
              ...last,
              content: last.content + (last.content ? '\n\n' : '') + `⚠️ ${message}`,
            }));
            if (viewOwnsStream()) {
              set({ error: message });
            }
          },
          onDone: () => {
            /* 流正常结束 */
          },
        },
        abortController.signal,
      );
    } catch (error) {
      if ((error as Error).name !== 'AbortError') {
        failed = true;
        set({ error: (error as Error).message });
      }
    } finally {
      abortController = null;
      // 只有仍是最新一轮发送时才收尾（停止后立刻重发时，旧流的 finally 不覆盖新一轮发送的 streaming 状态）
      if (sendSeq === activeSendSeq) {
        // 完成态：分段已在流式期间直接构建在消息上（思考段已各自闭合），无需再固化；
        // 未闭合的思考段（流异常中止时）此处兜底闭合，避免面板永远转圈
        appendAssistant((last) => ({
          ...last,
          endedAt: Date.now(),
          segments: last.segments?.map((s) => (s.kind === 'think' && s.active ? { ...s, active: false } : s)),
        }));
        // 插入占位气泡始终没接到增量（本轮中止/无工具轮直接收尾）：空气泡移除——
        // 插入内容已落库，历史与下一轮对话仍可见
        set((state) => {
          const last = state.messages[state.messages.length - 1];
          if (last && last.role === 'assistant' && last.awaitingInjection
              && !last.content && !(last.segments && last.segments.length > 0)) {
            return { messages: state.messages.slice(0, -1) };
          }
          return {};
        });
        set({
          streaming: false,
          streamingSessionId: '',
          streamingAgentId: '',
          streamStatus: { phase: 'done', step: '完成', detail: '' },
        });
        // 后台完成的 detached 流：最终消息已由后端落库，切回时 queryMessages 可读，缓冲即可释放
        if (streamSid.value) {
          detachedStreams.delete(streamSid.value);
        }
        activeStreamSid.value = '';
        get().refreshSessions();
        // 排队的追问自动补发（仅自然结束；停止/失败时退回输入框，不意外发出）
        const queue = get().queuedMessages;
        if (queue.length > 0) {
          const head = queue[0];
          set({ queuedMessages: queue.slice(1) });
          if (failed) {
            get().restoreQueued(head.text, head.sessionId);
          } else if (get().currentSessionId === head.sessionId) {
            void get().sendMessage(head.text);
          } else {
            // 排队后用户切走了会话：不跨会话误发，退回队首所属会话的输入框，其余留在队列
            get().restoreQueued(head.text, head.sessionId);
            get().pushToast('info', '队首消息已退回原会话输入框，其余保留在队列');
            set({ queuedMessages: queue });
          }
        }
      }
    }
  },

  stopStreaming: () => {
    // 先通知后端中止（模型流 + 工具循环）再断开 SSE：只断连接的话后端会继续空烧到自然结束
    if (activeStreamSid.value) {
      api.chatStop(activeStreamSid.value).catch(() => {});
    }
    abortController?.abort();
    abortController = null;
    // 只清当前流的缓冲；其他会话的后台流还在跑，缓冲必须保留
    if (activeStreamSid.value) {
      detachedStreams.delete(activeStreamSid.value);
      activeStreamSid.value = '';
    }
    // 手动停止：排队的追问退回输入框（不丢用户输入，也不意外发出）
    const queued = get().queuedMessages;
    set({
      streaming: false,
      streamingSessionId: '',
      streamingAgentId: '',
      streamStatus: { phase: 'done', step: '已停止', detail: '' },
      queuedMessages: [],
    });
    queued.forEach((item) => get().restoreQueued(item.text, item.sessionId));
  },

  queueMessage: (text) => set((state) => ({
    queuedMessages: [...state.queuedMessages, { id: nextQueueId++, sessionId: state.currentSessionId, text }],
  })),

  cancelQueued: () => set({ queuedMessages: [] }),
  reorderQueue: (fromId, toId) => set((state) => {
    const queue = [...state.queuedMessages];
    const fromIdx = queue.findIndex((item) => item.id === fromId);
    const toIdx = queue.findIndex((item) => item.id === toId);
    if (fromIdx < 0 || toIdx < 0 || fromIdx === toIdx) {
      return {};
    }
    const [moved] = queue.splice(fromIdx, 1);
    queue.splice(toIdx, 0, moved);
    return { queuedMessages: queue };
  }),
  editQueued: (id, text) => set((state) => ({
    queuedMessages: state.queuedMessages.map((item) => (item.id === id ? { ...item, text } : item)),
  })),
  removeQueued: (id) => set((state) => ({ queuedMessages: state.queuedMessages.filter((item) => item.id !== id) })),
  insertQueuedNow: async (id) => {
    const item = get().queuedMessages.find((q) => q.id === id);
    if (!item) {
      return;
    }
    set((state) => ({ queuedMessages: state.queuedMessages.filter((q) => q.id !== id) }));
    await get().insertMessage(item.text);
  },

  insertMessage: async (text) => {
    const trimmed = text.trim();
    const { streaming, streamingSessionId, currentSessionId } = get();
    if (!trimmed) {
      return;
    }
    if (!streaming) {
      // 面板状态与实际不符：退化为普通发送
      get().pushToast('info', '当前没有进行中的对话，已按普通消息发送');
      void get().sendMessage(trimmed);
      return;
    }
    const sid = streamingSessionId || currentSessionId;
    let accepted = false;
    try {
      accepted = await api.chatInsert(sid, trimmed);
    } catch (error) {
      get().pushToast('error', `插入失败: ${(error as Error).message}`);
      return;
    }
    // 乐观上屏：在流式助手消息后追加 用户插入消息 + 新的空助手气泡（继续承接后续增量）。
    // awaitingInjection 标记等待注入窗口（当前模型轮未结束时气泡尚无内容），首个正文增量到达即清除
    set((state) => ({
      messages: [
        ...state.messages,
        { role: 'user', content: trimmed, inserted: true },
        { role: 'assistant', content: '', awaitingInjection: true },
      ] as ChatMessage[],
    }));
    if (accepted) {
      get().pushToast('success', '已插入：模型在下一个工具调用前可见');
    } else {
      get().pushToast('info', '本轮已无工具调用，已转为排队');
      set((state) => ({
        queuedMessages: [...state.queuedMessages, { id: nextQueueId++, sessionId: sid, text: trimmed }],
      }));
    }
  },

  /** 排队追问退回所属会话的输入框（当前会话即时可见，其他会话存 draftBySession） */
  restoreQueued: (text, sessionId) => {
    if (sessionId === get().currentSessionId) {
      set({ draft: text });
    } else {
      set((state) => ({ draftBySession: { ...state.draftBySession, [sessionId || '']: text } }));
    }
  },

  addImage: (dataUrl) => set({ pendingImages: [...get().pendingImages, dataUrl] }),

  removeImage: (dataUrl) => set({ pendingImages: get().pendingImages.filter((item) => item !== dataUrl) }),

  setDraft: (text) =>
    set((state) => ({
      draft: text,
      // 随写随存：切会话不丢草稿、不串会话
      draftBySession: { ...state.draftBySession, [state.currentSessionId]: text },
    })),

  focusComposer: () => set({ composerFocusTick: get().composerFocusTick + 1 }),

  setView: (view) => {
    set({ view });
  },

  toggleTheme: () => {
    const theme = get().theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem(THEME_KEY, theme);
    set({ theme });
  },

  setFontScale: (scale) => {
    localStorage.setItem(FONT_SCALE_KEY, scale);
    set({ fontScale: scale });
  },

  pushToast: (type, message) => {
    const id = Date.now() + Math.random();
    set({ toasts: [...get().toasts.slice(-3), { id, type, message }] });
    window.setTimeout(() => get().dismissToast(id), 2600);
  },

  dismissToast: (id) => set({ toasts: get().toasts.filter((toast) => toast.id !== id) }),

  setPaletteOpen: (open) => set({ paletteOpen: open }),

  confirmDialog: (options) =>
    new Promise<boolean>((resolve) => {
      set({
        dialog: {
          type: 'confirm',
          title: options.title,
          message: options.message,
          danger: options.danger,
          confirmText: options.confirmText,
          resolve: (result) => resolve(Boolean(result)),
        },
      });
    }),

  promptDialog: (options) =>
    new Promise<string | null>((resolve) => {
      set({
        dialog: {
          type: 'prompt',
          title: options.title,
          message: options.message,
          placeholder: options.placeholder,
          value: options.value,
          resolve: (result) => resolve(typeof result === 'string' ? result : null),
        },
      });
    }),

  resolveDialog: (result) => {
    const dialog = get().dialog;
    if (dialog) {
      dialog.resolve(result);
      set({ dialog: null });
    }
  },

  setSidebarOpen: (open) => set({ sidebarOpen: open }),
}));
