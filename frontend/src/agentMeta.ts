import {
  Bot,
  Database,
  FileText,
  Network,
  SearchCode,
  ShoppingCart,
  Stethoscope,
  type LucideIcon,
} from 'lucide-react';

export interface StarterCard {
  icon: LucideIcon;
  /** 图标底色（tailwind 渐变） */
  accent: string;
  title: string;
  desc: string;
  prompt: string;
}

export interface AgentMeta {
  icon: LucideIcon;
  /** tailwind 色系：bg-*-100 text-*-600 */
  chip: string;
  /** 渐变（选中态/主视觉） */
  gradient: string;
  accent: string;
  /** 欢迎页示例卡片（点击填入输入框，不直接发送） */
  suggestions: StarterCard[];
  /** 输入区上下文选择器（环境/日志环境/工程），发送时按约定注入消息 */
  contextOptions?: ContextOptionGroup[];
  /** 输入框占位文案（缺省用通用文案），按智能体场景化 */
  placeholder?: string;
}

export interface ContextOptionGroup {
  key: 'env' | 'logEnv' | 'project';
  label: string;
  options: { value: string; label: string }[];
}

const LOG: AgentMeta = {
  icon: SearchCode,
  chip: 'bg-amber-100 text-amber-600',
  gradient: 'from-amber-500 to-orange-500',
  accent: 'text-amber-600',
  placeholder: '描述问题现象，如：生产环境，订单服务，下单失败（可贴截图/traceId）；Enter 发送，Shift+Enter 换行',
  suggestions: [
    {
      icon: Stethoscope,
      accent: 'from-amber-500 to-orange-500',
      title: '一句话排查',
      desc: '环境 + 业务线 + 现象，自动路由',
      prompt: '生产环境，订单交易，下单失败，帮我排查下原因',
    },
    {
      icon: SearchCode,
      accent: 'from-orange-500 to-rose-500',
      title: '查错误日志',
      desc: 'CLS 日志，按时间/级别过滤',
      prompt: '订单服务查一下近 2 小时的 ERROR 日志',
    },
    {
      icon: Network,
      accent: 'from-amber-500 to-yellow-500',
      title: '日志 + 代码双证据',
      desc: '从异常栈定位到源码行号',
      prompt: '用户反馈订单状态不对，结合日志和代码分析下根因',
    },
  ],
  contextOptions: [
    {
      key: 'env',
      label: '环境',
      options: [
        { value: '', label: '自动推测' },
        { value: '生产', label: '生产环境' },
        { value: '测试', label: '测试环境' },
      ],
    },
    {
      key: 'logEnv',
      label: '日志环境',
      options: [
        { value: 'product', label: 'product' },
        { value: 'qa', label: 'qa' },
      ],
    },
    {
      key: 'project',
      label: '工程',
      // 与后端 troubleshoot.code-analysis.projects 注册表一一对应（注入【本地工程：目录名】）
      options: [
        { value: '', label: '自动推测' },
        { value: 'order-service', label: '订单服务' },
        { value: 'user-service', label: '用户服务' },
      ],
    },
  ],
};

const DB: AgentMeta = {
  icon: Database,
  chip: 'bg-emerald-100 text-emerald-600',
  gradient: 'from-emerald-500 to-teal-500',
  accent: 'text-emerald-600',
  suggestions: [
    {
      icon: Database,
      accent: 'from-emerald-500 to-teal-500',
      title: '找表',
      desc: '按关键词搜表名和注释',
      prompt: '看看有哪些订单相关的表',
    },
    {
      icon: ShoppingCart,
      accent: 'from-teal-500 to-cyan-500',
      title: '查数据',
      desc: '只读 SELECT，自动 LIMIT',
      prompt: '查一下订单表最近的 10 条数据',
    },
    {
      icon: FileText,
      accent: 'from-emerald-500 to-green-500',
      title: '看表结构',
      desc: '字段/类型/注释/索引',
      prompt: '用户表结构是什么样的，帮我看看字段含义',
    },
  ],
};

const DEFAULT_META: AgentMeta = {
  icon: Bot,
  chip: 'bg-sky-100 text-sky-600',
  gradient: 'from-sky-500 to-blue-500',
  accent: 'text-sky-600',
  suggestions: [
    {
      icon: Bot,
      accent: 'from-sky-500 to-blue-500',
      title: '开始对话',
      desc: '先打个招呼试试',
      prompt: '你好，介绍下你能做什么',
    },
    {
      icon: FileText,
      accent: 'from-blue-500 to-indigo-500',
      title: '分析思路',
      desc: '让 AI 帮你梳理问题',
      prompt: '帮我梳理一个问题的分析思路',
    },
  ],
};

const META_BY_AGENT_ID: Record<string, AgentMeta> = {
  '100011': LOG,
  // 100014：日志代码排查助手·直出版（单 agent，无成文阶段）——界面/上下文选择器与工作流版一致
  '100014': LOG,
  '100012': DB,
};

export function agentMeta(agentId: string): AgentMeta {
  return META_BY_AGENT_ID[agentId] || DEFAULT_META;
}
