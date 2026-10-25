/** 智能体（后端 /api/v1/query_ai_agent_config_list） */
export interface Agent {
  agentId: string;
  agentName: string;
  agentDesc: string;
}

/** 历史会话（后端 /api/v1/query_chat_sessions） */
export interface ChatSession {
  sessionId: string;
  agentId: string;
  agentName: string;
  title: string;
  /** 归档空间名（如: 值班）；null/undefined=未归档 */
  space?: string | null;
  updatedAt: string;
}

/**
 * 消息内容分段（时间线渲染）：一条 assistant 回复的真实生成顺序是
 * 思考→工具→思考→…→正文，分段按发生顺序排列，替代"固定思考面板+正文"两层结构。
 */
export type MsgSegment =
  | { kind: 'think'; text: string; active: boolean; costMs?: number | null; stage?: string | null }
  | { kind: 'tools'; traces: ToolTrace[] }
  | { kind: 'text'; text: string };

/** 历史消息（后端 /api/v1/query_chat_messages；一轮回复的思考/正文已合并为一条） */
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  /** 前端本地字段：用户消息随信发送的附图（气泡内回显） */
  images?: string[];
  /**
   * 前端本地字段：时间线分段。流式期间按事件顺序构建；历史消息从 content 的
   * ⟦think⟧ 标记解析（工具轨迹不落库，历史无 tools 段）。渲染优先用分段。
   */
  segments?: MsgSegment[];
  /** 流式期间插入的追问（模型在下一工具轮前可见） */
  inserted?: boolean;
  /** 前端本地字段：插入产生的空助手气泡尚未接到首个增量（等待注入窗口，与"正在处理"区分展示） */
  awaitingInjection?: boolean;
  /** 前端本地字段：流起止墙钟。完成态面板"用时"取墙钟而非思考段求和（工具执行与排队不占思考用时） */
  startedAt?: number;
  endedAt?: number;
}

/** 流式过程状态（一条 assistant 消息的生成过程） */
export interface StreamStatus {
  /** thinking | tool | done */
  phase: 'thinking' | 'tool' | 'done';
  step: string;
  detail?: string;
}

/** 工具执行记录 */
export interface ToolTrace {
  tool: string;
  success: boolean;
  summary: string;
}

/** 知识库目录树节点（/api/v1/knowledge/tree） */
export interface KnowledgeNode {
  name: string;
  path: string;
  type: 'dir' | 'file';
  size?: number;
  modifiedAt?: number;
  children?: KnowledgeNode[];
}

/** 知识库笔记（/api/v1/knowledge/file）：frontmatter 已拆字段 */
export interface KnowledgeFile {
  path: string;
  title: string;
  tags?: string[];
  date?: string;
  system?: string;
  content: string;
}

/** 知识库搜索命中（/api/v1/knowledge/search） */
export interface KnowledgeHit {
  path: string;
  title: string;
  lineNo?: number;
  snippet: string;
}
