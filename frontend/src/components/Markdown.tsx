import { memo, useState, type ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { Check, Copy } from 'lucide-react';
import { copyText } from '../api';

/** 递归提取 React 子树的纯文本（复制用） */
function nodeText(node: unknown): string {
  if (node == null) {
    return '';
  }
  if (typeof node === 'string' || typeof node === 'number') {
    return String(node);
  }
  if (Array.isArray(node)) {
    return node.map(nodeText).join('');
  }
  const props = (node as { props?: { children?: unknown } }).props;
  return props ? nodeText(props.children) : '';
}

/** 代码块外壳：语言标签 + 一键复制（高亮着色由 rehype-highlight + hljs 主题提供） */
function CodeCard({ lang, raw, children }: { lang: string; raw: string; children: ReactNode }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    const ok = await copyText(raw);
    if (ok) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
  };
  return (
    <div className="code-card group/code">
      <div className="code-card-bar">
        <span className="code-card-lang">{lang || 'text'}</span>
        <button onClick={copy} className="code-card-copy" title="复制代码">
          {copied ? <Check size={11} className="text-emerald-400" /> : <Copy size={11} />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="!my-0">{children}</pre>
    </div>
  );
}

/** 统一 Markdown 渲染（排版样式见 index.css 的 .md-body；代码块带语法高亮与复制） */
export const Markdown = memo(function Markdown({ content }: { content: string }) {
  return (
    <div className="md-body">
      <ReactMarkdown
        // singleTilde:false 关闭单波浪号删除线——模型会写"85~95""A~B"这类半角波浪号区间，
        // 默认 GFM 把两个 ~ 之间的整段文字当删除线渲染（大段正文莫名划线）
        remarkPlugins={[[remarkGfm, { singleTilde: false }]]}
        rehypePlugins={[rehypeHighlight]}
        components={{
          pre({ children }) {
            const child = (Array.isArray(children) ? children[0] : children) as
              | { props?: { className?: string; children?: unknown } }
              | undefined;
            const lang = /language-([\w-]+)/.exec(child?.props?.className || '')?.[1] || '';
            const raw = nodeText(child?.props?.children).replace(/\n$/, '');
            return <CodeCard lang={lang} raw={raw}>{children}</CodeCard>;
          },
          a({ children, href }) {
            return (
              <a href={href} target="_blank" rel="noreferrer noopener">
                {children}
              </a>
            );
          },
          table({ children }) {
            return (
              <div className="md-table-wrap">
                <table>{children}</table>
              </div>
            );
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
});
