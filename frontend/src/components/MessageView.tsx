import { memo, useEffect, useRef, useState } from 'react';
import {
  Bot,
  Brain,
  Check,
  CheckCircle2,
  ChevronDown,
  Copy,
  Database,
  FileCode2,
  FileText,
  GitBranch,
  Loader2,
  PieChart,
  Presentation,
  RefreshCw,
  SearchCode,
  Sheet,
  TerminalSquare,
  Wrench,
  XCircle,
  type LucideIcon,
} from 'lucide-react';
import clsx from 'clsx';
import type { ChatMessage, MsgSegment, ToolTrace } from '../types';
import { parseThinkSegments, useStore } from '../store';
import { copyText } from '../api';
import { Markdown } from './Markdown';
import logo from '../assets/logo.png';

const TOOL_ICON: Record<string, LucideIcon> = {
  md_generate: FileCode2,
  doc_generate: FileText,
  sheet_generate: Sheet,
  chart_generate: PieChart,
  drawio_generate: GitBranch,
  ppt_generate: Presentation,
  runCommand: TerminalSquare,
  readLocalFile: FileText,
  countClsLog: SearchCode,
  queryClsLog: SearchCode,
  queryClsLogContext: SearchCode,
  resolveRouting: SearchCode,
  searchCode: SearchCode,
  findFiles: SearchCode,
  readCodeFile: FileCode2,
  listLocalProjects: Wrench,
  listDbConnections: Database,
  listDbTables: Database,
  describeDbTable: Database,
  queryDb: Database,
};

function toolIconOf(tool: string): LucideIcon {
  return TOOL_ICON[tool] || Wrench;
}

/** 悬浮复制按钮：思考段/正文/用户消息共用，成功后"已复制"回显 1.5s */
function CopyButton({ text, label = '复制', title }: {
  text: string;
  label?: string;
  title?: string;
}) {
  const [copied, setCopied] = useState(false);
  const onCopy = async () => {
    const ok = await copyText(text);
    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } else {
      useStore.getState().pushToast('error', '复制失败（浏览器不支持剪贴板）');
    }
  };
  return (
    <button
      onClick={onCopy}
      className={clsx(
        'flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-medium text-content-faint transition-all hover:bg-surface-raise hover:text-content-secondary',
        copied && 'text-emerald-500',
      )}
      title={title || label}
    >
      {copied ? <Check size={11} className="text-emerald-500" /> : <Copy size={11} />}
      {copied ? '已复制' : label}
    </button>
  );
}

/** 工具轨迹组：连续工具结果紧凑列表（过程面板内联） */
function ToolsSegment({ traces }: { traces: ToolTrace[] }) {
  return (
    <div className="space-y-1">
      {traces.map((trace, index) => {
        const Icon = toolIconOf(trace.tool);
        return (
          <div key={index} className="flex items-start gap-2.5 text-[13px]">
            <span
              className={clsx(
                'mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-md',
                trace.success ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400' : 'bg-rose-500/10 text-rose-500',
              )}
            >
              {trace.success ? <Icon size={12} /> : <XCircle size={12} />}
            </span>
            <span className="min-w-0 flex-1">
              <span className="font-medium text-content-secondary">{trace.tool}</span>
              {trace.summary && <span className="ml-1.5 text-content-muted">{trace.summary}</span>}
            </span>
            {trace.success ? (
              <CheckCircle2 size={13} className="mt-1 shrink-0 text-emerald-500" />
            ) : (
              <XCircle size={13} className="mt-1 shrink-0 text-rose-400" />
            )}
          </div>
        );
      })}
    </div>
  );
}

/** 文档态正文判据：取证档案/最终报告这类成段输出——长文、带【小节】标题或 markdown 标题 */
function isDocumentText(text: string): boolean {
  return text.length >= 200 || /【[^【】]{2,16}】/.test(text) || /^#{1,6}\s/m.test(text);
}

/**
 * 分割：最后一个思考段之前（含）为过程时间线，之后为最终答案正文。
 * 两阶段工作流的取证档案位于两段思考之间——它与最终报告内容高度重叠
 * （报告即由档案改写而来），渲染两份正文只会重复刷屏：档案不进面板、
 * 不进答案区（见 ProcessTimeline 的文档段跳过），只留在会话库供成文阶段
 * 输入与事后追溯；用户可见的正文只有最终报告。
 */
function splitProcessAndAnswer(segments: MsgSegment[]): { process: MsgSegment[]; answer: MsgSegment[] } {
  let lastThinkIdx = -1;
  segments.forEach((segment, index) => {
    if (segment.kind === 'think') {
      lastThinkIdx = index;
    }
  });
  return {
    process: segments.slice(0, lastThinkIdx + 1),
    answer: segments.slice(lastThinkIdx + 1),
  };
}

type ThinkSeg = Extract<MsgSegment, { kind: 'think' }>;

/** 思考文本清洗：模型思考里偶发 <br> 分隔符与 </thinking> 模板残留，展示/复制时归一 */
function normalizeThinkText(text: string): string {
  return text
    .replace(/<\/?thinking>/g, '')
    .replace(/<br\s*\/?>/gi, '\n');
}

/**
 * 思考与执行过程（单面板，时间线内容）：
 * 生成中自动展开实时跟随；完成即折叠为摘要头（N 段思考 · M 个工具 · 总用时），
 * 点击展开回看完整时序——每段思考带阶段标签与独立用时，中间穿插工具步骤与过程叙述。
 */
function ProcessTimeline({ segments, streaming, wallMs }: { segments: MsgSegment[]; streaming: boolean; wallMs: number | null }) {
  const [open, setOpen] = useState(streaming);
  const userTouched = useRef(false);

  useEffect(() => {
    if (!userTouched.current) {
      setOpen(streaming);
    }
  }, [streaming]);

  if (segments.length === 0) {
    return null;
  }
  const thinkSegs = segments.filter((s): s is ThinkSeg => s.kind === 'think');
  const toolCount = segments.reduce((count, s) => (s.kind === 'tools' ? count + s.traces.length : count), 0);
  const totalMs = thinkSegs.reduce((sum, s) => sum + (s.costMs ?? 0), 0);
  const hasCost = thinkSegs.some((s) => s.costMs != null);
  const last = segments[segments.length - 1];
  const liveLabel = last.kind === 'think' && last.active
    ? `正在${last.stage ?? '深度'}思考…`
    : '正在执行…';
  // 完成态优先展示墙钟总用时（含工具执行与轮间排队——那才是用户体感的"想了多久"）；
  // 历史消息无墙钟，退回各思考段求和，并如实标注"思考用时"（不含工具时间）
  const wallLabel = wallMs != null ? `用时 ${(wallMs / 1000).toFixed(1)}s` : '';
  const thinkLabel = hasCost ? `思考用时 ${(totalMs / 1000).toFixed(1)}s` : '';
  const summary = [
    streaming ? '' : (wallLabel || thinkLabel),
    thinkSegs.length > 1 ? `${thinkSegs.length} 段思考` : '',
    toolCount > 0 ? `${toolCount} 个工具` : '',
  ].filter(Boolean).join(' · ');
  const thinkText = thinkSegs.map((s) => normalizeThinkText(s.text)).join('\n\n');
  // 流式期间全部内容（含过程正文）都在面板内，光标常驻面板末尾
  const liveInPanel = streaming;

  return (
    <div className="mb-2 overflow-hidden rounded-xl border border-line/70 bg-surface-raise/40">
      <div className="flex w-full items-center gap-2 px-3 py-1.5 transition-colors hover:bg-surface-raise/70">
        <button
          className="flex min-w-0 flex-1 items-center gap-2 text-left"
          onClick={() => {
            userTouched.current = true;
            setOpen(!open);
          }}
        >
          {streaming ? (
            <Loader2 size={13} className="animate-spin text-brand-400" />
          ) : (
            <Brain size={13} className="text-brand-400" />
          )}
          <span className={clsx('text-[13px] font-medium', streaming ? 'text-brand-600 dark:text-brand-400' : 'text-content-secondary')}>
            {streaming ? liveLabel : `已完成思考与执行${summary ? ` · ${summary}` : ''}`}
          </span>
          {streaming && last.kind === 'think' && last.active && (
            <span className="thinking-shimmer h-1 w-10 rounded-full" />
          )}
          <ChevronDown size={14} className={clsx('ml-auto shrink-0 text-content-muted transition-transform', open && 'rotate-180')} />
        </button>
        {thinkText && <CopyButton text={thinkText} label="复制全部思考" title="拼接复制全部思考段" />}
      </div>
      {open && (
        <div className="border-t border-line/60 px-3 py-2">
          <div className="thin-scroll max-h-80 space-y-2.5 overflow-y-auto">
            {segments.map((segment, index) => {
              if (segment.kind === 'think') {
                return (
                  <div key={index} className="group/round">
                    <div className="mb-0.5">
                      <span className="text-[11px] font-medium text-content-faint">
                        {segment.stage ? `${segment.stage}思考` : '思考'}
                        {segment.costMs != null ? ` · 用时 ${(segment.costMs / 1000).toFixed(1)}s` : ''}
                      </span>
                    </div>
                    <div className="whitespace-pre-wrap text-[13px] leading-6 text-content-muted">
                      {normalizeThinkText(segment.text)}
                    </div>
                  </div>
                );
              }
              if (segment.kind === 'tools') {
                return <ToolsSegment key={index} traces={segment.traces} />;
              }
              // 文档态正文（取证档案）不进面板：它与最终报告内容高度重叠，且只该
              // 以报告形态出现在正文区——这里跳过渲染，档案仅存于会话库
              if (isDocumentText(segment.text)) {
                return null;
              }
              return (
                <div key={index} className="group/round">
                  <div className="mb-0.5 flex items-center justify-between gap-2">
                    <span className="text-[11px] font-medium text-content-faint">回复</span>
                    <CopyButton text={segment.text} label="复制回复" title="复制本段回复" />
                  </div>
                  <div className="text-[13px] leading-6 text-content-muted [&_p]:my-1">
                    <Markdown content={segment.text} />
                  </div>
                </div>
              );
            })}
            {liveInPanel && (
              <span className="ml-0.5 inline-block h-4 w-2 animate-blink rounded-sm bg-brand-500 align-[-2px]" />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

const ARTIFACT_ICON: Record<string, typeof FileText> = {
  markdown: FileCode2,
  document: FileText,
  sheet: Sheet,
  chart: PieChart,
  drawio: GitBranch,
  ppt: Presentation,
};

/** 用户消息头像：logo 小方标，与助手侧品牌头像形成左右对称的语言 */
function UserAvatar() {
  return (
    <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-surface-raise ring-1 ring-line">
      <img src={logo} alt="我" className="h-[18px] w-[18px] rounded-md object-cover" />
    </span>
  );
}

function UserBubble({ message }: { message: ChatMessage }) {
  return (
    <div className="group flex animate-fade-in-up items-start justify-end gap-3">
      <div className="flex max-w-[75%] flex-col items-end">
        <div className="rounded-2xl rounded-br-md bg-brand-600 px-4 py-2.5 text-sm leading-6 text-white shadow-brand">
          <div className="whitespace-pre-wrap">{message.content}</div>
          {message.inserted && (
            <div className="mt-1.5 text-right text-[10px] text-white/70">已插入 · 模型已在下一轮看到</div>
          )}
          {message.images && message.images.length > 0 && (
            <div className="mt-2.5 flex flex-wrap justify-end gap-2">
              {message.images.map((dataUrl, index) => (
                <img key={index} src={dataUrl} alt="附图" loading="lazy" decoding="async" className="h-20 w-20 rounded-xl border border-white/30 object-cover" />
              ))}
            </div>
          )}
        </div>
        <CopyButton text={message.content} label="复制" title="复制本条消息" />
      </div>
      <UserAvatar />
    </div>
  );
}

/** 助手消息头像：品牌色圆角方块 + 机器人图标，与侧栏智能体 chip 同语言 */
function AssistantAvatar() {
  return (
    <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-brand-500 to-violet-500 text-white shadow-soft">
      <Bot size={15} />
    </span>
  );
}

function AssistantView({ message, isLast }: { message: ChatMessage; isLast: boolean }) {
  const streaming = useStore((state) => state.streaming);
  // 性能：只有最后一条消息订阅流式状态（分段每个 delta 都变），
  // 历史消息零订阅，流式期间不再全列表重渲染
  if (!isLast) {
    return <AssistantPresentation message={message} streaming={false} onRegenerate={null} />;
  }
  return <AssistantLive message={message} streaming={streaming} />;
}

/** 最后一条消息：流式分段直接挂在消息上（事件增量实时构建） */
function AssistantLive({ message, streaming }: { message: ChatMessage; streaming: boolean }) {
  const regenerate = useStore((state) => state.regenerate);
  return <AssistantPresentation message={message} streaming={streaming} onRegenerate={regenerate} />;
}

/**
 * 展示层：单个"思考与执行过程"面板（折叠摘要/展开完整时间线）+ 最终答案正文。
 * 分段来源：流式/刚完成的消息用 message.segments；历史消息从 content 标记解析。
 * 流式期间：所有分段（含过程正文）都渲染在面板时间线内，完成时才切分——
 * 避免"过程正文先出现在答案区、下一轮思考开始又跳回面板"的视觉抖动；
 * 完成态：最后一个思考段之前的内容归面板，其后正文段是最终答案。
 */
function AssistantPresentation({ message, streaming, onRegenerate }: {
  message: ChatMessage;
  streaming: boolean;
  onRegenerate: (() => void) | null;
}) {
  const segments: MsgSegment[] = message.segments ?? parseThinkSegments(message.content);
  // 墙钟：本地流式消息带 startedAt/endedAt；流式中以当前时刻估算（增量渲染足够频繁）
  const wallEnd = message.endedAt ?? (streaming ? Date.now() : null);
  const wallMs = message.startedAt != null && wallEnd != null ? Math.max(0, wallEnd - message.startedAt) : null;
  // 流式期间全部分段渲染在面板内（文档态正文在面板中被跳过——档案与正文重叠，
  // 正文等完成后一次性输出），完成态按位置切分：最后一个思考段之后是最终答案
  const { process, answer } = streaming
    ? { process: segments, answer: [] as MsgSegment[] }
    : splitProcessAndAnswer(segments);
  const answerText = answer
    .filter((s): s is Extract<MsgSegment, { kind: 'text' }> => s.kind === 'text')
    .map((s) => s.text)
    .join('\n\n');

  return (
    <div className="group animate-fade-in-up">
      <div className="flex items-start gap-3">
        <AssistantAvatar />
        <div className="min-w-0 flex-1">
          <ProcessTimeline segments={process} streaming={streaming} wallMs={wallMs} />

          {!streaming && answer.length > 0 && (
            <>
              {answer.map((segment, index) => (
                <div key={index} className="mb-1">
                  <Markdown content={segment.kind === 'text' ? segment.text : ''} />
                </div>
              ))}
            </>
          )}
          {streaming && process.length === 0 && (
            <span className="inline-flex items-center gap-2 py-1 text-sm text-content-muted">
              <Loader2 size={14} className="animate-spin" />
              {message.awaitingInjection
                ? '已插入，等待当前轮结束（下一轮生效）…'
                : '正在处理…'}
            </span>
          )}
          {!streaming && process.length === 0 && answer.length === 0 && (
            <span className="py-1 text-sm text-content-muted">（无内容）</span>
          )}

          {!streaming && (answerText || process.length > 0) && (
            <div className="mt-1 flex items-center gap-1">
              {onRegenerate && (
                <button
                  onClick={() => onRegenerate()}
                  className="flex items-center gap-1 rounded-lg px-2 py-1 text-[11px] font-medium text-content-faint opacity-0 transition-all hover:bg-surface-raise hover:text-content-secondary focus-visible:opacity-100 group-hover:opacity-100"
                  title="重新生成本条回复"
                >
                  <RefreshCw size={11} />
                  重新生成
                </button>
              )}
              {answerText && <CopyButton text={answerText} label="复制" title="复制结论" />}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export const MessageView = memo(function MessageView({ message, isLast }: { message: ChatMessage; isLast: boolean }) {
  if (message.role === 'user') {
    return <UserBubble message={message} />;
  }
  return <AssistantView message={message} isLast={isLast} />;
});
