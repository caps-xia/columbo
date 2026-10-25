import type { Agent, ChatMessage, ChatSession } from './types';

const API_BASE = '/api/v1';
const TOKEN_KEY = 'columbo_token';

/** 登录 token 存取（localStorage，7 天有效期由后端控制） */
export const authToken = {
  get: () => localStorage.getItem(TOKEN_KEY) || '',
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

/** 登录失效全局事件：任何请求遇到 0301 都广播，App 监听后切回登录页 */
export const NOT_LOGIN_EVENT = 'columbo:not-login';

interface ApiEnvelope<T> {
  code: string;
  info: string;
  data: T;
}

/** 统一带 token 的 fetch：登录态由后端 Sa-Token 校验 */
async function http(url: string, init?: RequestInit): Promise<Response> {
  const headers = new Headers(init?.headers);
  const token = authToken.get();
  if (token) {
    headers.set('satoken', token);
  }
  return fetch(url, { ...init, headers });
}

/** 未登录/token 失效：清 token、广播事件（App/ store 监听切登录页） */
class NotLoginError extends Error {
  constructor(message: string) {
    super(message);
    authToken.clear();
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent(NOT_LOGIN_EVENT, { detail: message }));
    }
  }
}

/** 统一拆 {code, info, data} 包装；code!=0000 抛业务错误 */
async function unwrap<T>(response: Response): Promise<T> {
  // 网关 502/超时页等会返回 HTML：先判类型再解析，报错可读而不是 "Unexpected token '<'"
  const contentType = response.headers.get('content-type') || '';
  if (!contentType.includes('application/json')) {
    const text = await response.text();
    throw new Error(`服务响应异常（HTTP ${response.status}）：${text.slice(0, 120) || contentType || '空响应'}`);
  }
  const body = (await response.json()) as ApiEnvelope<T>;
  if (body.code === '0301') {
    throw new NotLoginError(body.info || '未登录或登录已过期');
  }
  if (body.code !== '0000') {
    throw new Error(body.info || `请求失败（${body.code}）`);
  }
  return body.data;
}

async function getJson<T>(url: string): Promise<T> {
  return unwrap<T>(await http(url));
}

/** 导出给 store 的登录态探测：未登录是预期态，静默清 token 返回 null，不广播全局过期事件 */
async function probeLogin(): Promise<{ username: string; timeout: number } | null> {
  try {
    const response = await http(`${API_BASE}/auth/me`);
    const body = (await response.json()) as ApiEnvelope<{ username: string; timeout: number }>;
    if (body.code === '0000') {
      return body.data;
    }
    if (body.code === '0301') {
      authToken.clear();
    }
    return null;
  } catch {
    return null;
  }
}

/** 剪贴板兜底：非 https 内网环境 navigator.clipboard 可能不可用，退回 execCommand */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* 走降级 */
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

export const api = {
  /** 登录态探测（启动时调用；0301 静默返回 null） */
  probeLogin,

  login: async (username: string, password: string): Promise<{ username: string; nick?: string; token: string; timeout: number }> => {
    const data = await unwrap<{ username: string; nick?: string; token: string; timeout: number }>(
      await http(`${API_BASE}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      }),
    );
    authToken.set(data.token);
    return data;
  },

  /** 注册：成功直接发 token（与登录同构） */
  register: async (username: string, name: string, password: string): Promise<{ username: string; nick?: string; token: string; timeout: number }> => {
    const data = await unwrap<{ username: string; nick?: string; token: string; timeout: number }>(
      await http(`${API_BASE}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, name, password }),
      }),
    );
    authToken.set(data.token);
    return data;
  },

  logout: async (): Promise<void> => {
    try {
      await unwrap<boolean>(await http(`${API_BASE}/auth/logout`, { method: 'POST' }));
    } catch {
      /* token 已失效时登出报错可忽略 */
    }
    authToken.clear();
  },

  queryAgents: () => getJson<Agent[]>(`${API_BASE}/query_ai_agent_config_list`),

  /** 各智能体可切换模型池与当前模型 */
  queryAgentModels: () =>
    getJson<{ agentId: string; agentName: string; models: string[]; current: string }[]>(
      `${API_BASE}/query_agent_models`,
    ),

  /** 运行时切换模型（单 agent 重装配，会话保留） */
  switchAgentModel: async (agentId: string, model: string): Promise<void> => {
    await unwrap<boolean>(
      await http(`${API_BASE}/switch_agent_model`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentId, model }),
      }),
    );
  },

  querySessions: (userId: string) =>
    getJson<ChatSession[]>(`${API_BASE}/query_chat_sessions?userId=${encodeURIComponent(userId)}`),

  queryMessages: (agentId: string, userId: string, sessionId: string) =>
    getJson<ChatMessage[]>(
      `${API_BASE}/query_chat_messages?agentId=${encodeURIComponent(agentId)}&userId=${encodeURIComponent(
        userId,
      )}&sessionId=${encodeURIComponent(sessionId)}`,
    ),

  createSession: async (agentId: string, userId: string): Promise<string> => {
    const data = await unwrap<{ sessionId: string }>(
      await http(`${API_BASE}/create_session`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentId, userId }),
      }),
    );
    return data.sessionId;
  },

  deleteSession: async (agentId: string, userId: string, sessionId: string): Promise<void> => {
    await unwrap<boolean>(
      await http(
        `${API_BASE}/delete_session?agentId=${encodeURIComponent(agentId)}&userId=${encodeURIComponent(
          userId,
        )}&sessionId=${encodeURIComponent(sessionId)}`,
        { method: 'POST' },
      ),
    );
  },

  renameSession: async (agentId: string, userId: string, sessionId: string, title: string): Promise<void> => {
    await unwrap<boolean>(
      await http(
        `${API_BASE}/rename_session?agentId=${encodeURIComponent(agentId)}&userId=${encodeURIComponent(
          userId,
        )}&sessionId=${encodeURIComponent(sessionId)}&title=${encodeURIComponent(title)}`,
        { method: 'POST' },
      ),
    );
  },

  /** 移动会话到归档空间；space 传空串归位未归档 */
  moveSession: async (agentId: string, userId: string, sessionId: string, space: string): Promise<void> => {
    await unwrap<boolean>(
      await http(
        `${API_BASE}/move_session?agentId=${encodeURIComponent(agentId)}&userId=${encodeURIComponent(
          userId,
        )}&sessionId=${encodeURIComponent(sessionId)}&space=${encodeURIComponent(space)}`,
        { method: 'POST' },
      ),
    );
  },

  /** 插入消息到正在生成的对话：返回 true=已注入；false=无进行中的流（调用方转为排队） */
  chatInsert: async (sessionId: string, message: string): Promise<boolean> => {
    return unwrap<boolean>(
      await http(`${API_BASE}/chat_insert`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message }),
      }),
    );
  },

  /** 停止正在生成的对话：后端中止模型流与工具循环，已生成内容按阶段性结果落会话 */
  chatStop: async (sessionId: string): Promise<boolean> => {
    return unwrap<boolean>(
      await http(`${API_BASE}/chat_stop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId }),
      }),
    );
  },

  /** 知识库：目录树 */
  knowledgeTree: async (): Promise<import('./types').KnowledgeNode[]> =>
    getJson<import('./types').KnowledgeNode[]>(`${API_BASE}/knowledge/tree`),

  /** 知识库：读笔记（frontmatter 已拆字段） */
  knowledgeFile: async (path: string): Promise<import('./types').KnowledgeFile> =>
    getJson<import('./types').KnowledgeFile>(
      `${API_BASE}/knowledge/file?path=${encodeURIComponent(path)}`,
    ),

  /** 知识库：搜索 */
  knowledgeSearch: async (keyword: string): Promise<import('./types').KnowledgeHit[]> =>
    getJson<import('./types').KnowledgeHit[]>(
      `${API_BASE}/knowledge/search?keyword=${encodeURIComponent(keyword)}`,
    ),
};

/** 结构化 SSE 对话事件（/api/v1/chat_events），与后端 ChatEventsController 协议一一对应 */
export interface ChatStreamHandlers {
  onSession: (sessionId: string) => void;
  onThinkDelta: (content: string, author?: string) => void;
  onThinkDone: (costMs: number) => void;
  onTextDelta: (content: string) => void;
  onStateUpdate: (step: string, detail: string) => void;
  onToolResult: (tool: string, success: boolean, summary: string) => void;
  onError: (message: string) => void;
  onDone: () => void;
}

export async function streamChat(
  request: { agentId: string; userId: string; sessionId?: string; message?: string; images?: string[] },
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await http(`${API_BASE}/chat_events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
    signal,
  });
  if (!response.ok || !response.body) {
    let detail = `HTTP ${response.status}`;
    try {
      const body = (await response.json()) as ApiEnvelope<unknown> & { message?: string };
      detail = body.info || body.message || detail;
      if (body.code === '0301') {
        throw new NotLoginError(detail);
      }
    } catch (error) {
      if (error instanceof NotLoginError) {
        throw error;
      }
    }
    throw new Error(detail);
  }
  // 未登录时拦截器返回 JSON 而非事件流：显式鉴别并切登录页，避免静默空转
  if ((response.headers.get('Content-Type') || '').includes('application/json')) {
    const body = (await response.json()) as ApiEnvelope<unknown>;
    if (body.code === '0301') {
      throw new NotLoginError(body.info || '未登录或登录已过期');
    }
    throw new Error(body.info || `请求失败（${body.code}）`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = '';

  // 空闲超时：服务端每 15s 有心跳帧，5 分钟无任何字节 = 连接已死（防 UI 永久转圈）
  const IDLE_TIMEOUT_MS = 5 * 60 * 1000;
  const readWithIdle = () =>
    new Promise<ReadableStreamReadResult<Uint8Array>>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('SSE 连接空闲超时（5 分钟无数据），已中断；请重试')), IDLE_TIMEOUT_MS);
      reader.read().then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (error) => {
          clearTimeout(timer);
          reject(error);
        },
      );
    });

  const dispatch = (name: string, raw: string) => {
    if (!name || !raw) {
      return;
    }
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(raw);
    } catch {
      console.warn('[sse] 事件数据解析失败，已跳过：', String(raw).slice(0, 200));
      return;
    }
    switch (name) {
      case 'session':
        handlers.onSession(String(data.sessionId || ''));
        break;
      case 'think_delta':
        handlers.onThinkDelta(String(data.content || ''), data.author ? String(data.author) : undefined);
        break;
      case 'think_done':
        handlers.onThinkDone(Number(data.costMs || 0));
        break;
      case 'text_delta':
        handlers.onTextDelta(String(data.content || ''));
        break;
      case 'state_update':
        handlers.onStateUpdate(String(data.step || ''), String(data.detail || ''));
        break;
      case 'tool_result':
        handlers.onToolResult(String(data.tool || ''), Boolean(data.success), String(data.summary || ''));
        break;
      case 'error':
        handlers.onError(String(data.message || '服务异常'));
        break;
      case 'done':
        handlers.onDone();
        break;
      default:
        break;
    }
  };

  while (true) {
    const { done, value } = await readWithIdle();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dispatch(eventName, line.slice(5).trim());
        // SSE 规范：事件名一次性，dispatch 后归位，避免无 event: 行的 data 沿用上一个事件名
        eventName = '';
      }
    }
  }
}
