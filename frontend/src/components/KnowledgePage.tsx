import { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  BookOpen,
  ChevronDown,
  ChevronRight,
  FileText,
  Folder,
  Loader2,
  Menu,
  Search,
  X,
} from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';
import { api } from '../api';
import type { KnowledgeFile, KnowledgeHit, KnowledgeNode } from '../types';
import { Markdown } from './Markdown';

/**
 * 知识库页（只读浏览）：左侧目录树/搜索结果，右侧渲染笔记（frontmatter → chips）。
 * 写入仍由排查助手速记与定时分拣负责，页面不提供编辑。
 */

/** 目录树节点（可折叠；文件点击打开） */
function TreeNode({
  node,
  depth,
  currentPath,
  collapsed,
  toggleDir,
  onOpen,
}: {
  node: KnowledgeNode;
  depth: number;
  currentPath: string;
  collapsed: Set<string>;
  toggleDir: (path: string) => void;
  onOpen: (path: string) => void;
}) {
  if (node.type === 'dir') {
    const open = !collapsed.has(node.path);
    const fileCount = countFiles(node);
    return (
      <div>
        <button
          onClick={() => toggleDir(node.path)}
          className="flex w-full items-center gap-1.5 rounded-lg px-2 py-1.5 text-left text-[13px] font-medium text-content-secondary transition-colors hover:bg-surface-raise"
        >
          {open ? <ChevronDown size={13} className="shrink-0 text-content-faint" /> : <ChevronRight size={13} className="shrink-0 text-content-faint" />}
          <Folder size={13} className="shrink-0 text-amber-500/80" />
          <span className="min-w-0 flex-1 truncate">{node.name}</span>
          <span className="shrink-0 text-[11px] text-content-faint">{fileCount}</span>
        </button>
        {open && node.children && (
          <div className="ml-3 border-l border-line/60 pl-1">
            {node.children.map((child) => (
              <TreeNode
                key={child.path}
                node={child}
                depth={depth + 1}
                currentPath={currentPath}
                collapsed={collapsed}
                toggleDir={toggleDir}
                onOpen={onOpen}
              />
            ))}
          </div>
        )}
      </div>
    );
  }
  const active = node.path === currentPath;
  return (
    <button
      onClick={() => onOpen(node.path)}
      title={node.path}
      className={clsx(
        'flex w-full items-center gap-1.5 rounded-lg px-2 py-1.5 text-left text-[13px] transition-colors',
        active
          ? 'bg-brand-500/10 font-medium text-brand-700 dark:text-brand-300'
          : 'text-content-secondary hover:bg-surface-raise',
      )}
    >
      <FileText size={13} className="shrink-0 text-content-faint" />
      <span className="min-w-0 flex-1 truncate">{node.name.replace(/\.md$/, '')}</span>
    </button>
  );
}

function countFiles(node: KnowledgeNode): number {
  if (node.type === 'file') {
    return 1;
  }
  return (node.children || []).reduce((sum, child) => sum + countFiles(child), 0);
}

export function KnowledgePage() {
  const [tree, setTree] = useState<KnowledgeNode[] | null>(null);
  const [treeError, setTreeError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [file, setFile] = useState<KnowledgeFile | null>(null);
  const [fileLoading, setFileLoading] = useState(false);
  const pushToast = useStore((state) => state.pushToast);
  const setSidebarOpen = useStore((state) => state.setSidebarOpen);
  const setView = useStore((state) => state.setView);

  useEffect(() => {
    let cancelled = false;
    api
      .knowledgeTree()
      .then((nodes) => {
        if (!cancelled) {
          setTree(nodes);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setTreeError((error as Error).message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const openFile = (path: string) => {
    setFileLoading(true);
    api
      .knowledgeFile(path)
      .then(setFile)
      .catch((error) => pushToast('error', (error as Error).message))
      .finally(() => setFileLoading(false));
  };

  const runSearch = () => {
    if (!keyword.trim()) {
      setHits(null);
      return;
    }
    setSearching(true);
    api
      .knowledgeSearch(keyword.trim())
      .then(setHits)
      .catch((error) => pushToast('error', (error as Error).message))
      .finally(() => setSearching(false));
  };

  const toggleDir = (path: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  const totalNotes = useMemo(() => (tree || []).reduce((sum, node) => sum + countFiles(node), 0), [tree]);

  return (
    <section className="flex min-w-0 flex-1 flex-col bg-surface-app">
      {/* 顶栏：标题 + 当前笔记路径 */}
      <header className="flex items-center gap-3 border-b border-line bg-surface-panel/70 px-4 py-2.5 backdrop-blur sm:px-5">
        <button
          onClick={() => setSidebarOpen(true)}
          aria-label="打开侧栏"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-content-muted transition-colors hover:bg-surface-raise hover:text-content-primary lg:hidden"
        >
          <Menu size={16} />
        </button>
        <button
          onClick={() => setView('chat')}
          aria-label="返回对话"
          className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-content-muted transition-colors hover:bg-surface-raise hover:text-content-secondary"
          title="返回对话"
        >
          <ArrowLeft size={16} />
        </button>
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-amber-100 text-amber-600">
          <BookOpen size={16} strokeWidth={2.2} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm font-semibold text-content-primary">知识库</div>
          <div className="truncate text-xs text-content-muted" title={file?.path}>
            {file ? file.path : `排查经验 · 速记/案例/词典（共 ${totalNotes} 篇，只读）`}
          </div>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        {/* 左：搜索 + 目录树 / 搜索结果 */}
        <aside className="max-h-72 shrink-0 overflow-y-auto border-b border-line bg-surface-panel/40 p-3 thin-scroll lg:max-h-none lg:w-72 lg:border-b-0 lg:border-r">
          <div className="flex items-center gap-1.5 rounded-xl border border-line bg-surface-input px-2.5 py-1.5 shadow-xs transition-colors focus-within:border-brand-400">
            <Search size={13} className="shrink-0 text-content-faint" />
            <input
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
                  runSearch();
                }
              }}
              placeholder="搜索笔记（报错文案/系统名/关键词）"
              className="w-full border-0 bg-transparent text-[13px] text-content-primary outline-hidden placeholder:text-content-faint"
            />
            {hits && (
              <button
                onClick={() => {
                  setHits(null);
                  setKeyword('');
                }}
                  className="shrink-0 text-content-faint transition-colors hover:text-content-secondary"
                  title="清除搜索，回到目录树"
                >
                  <X size={12} />
                </button>
            )}
          </div>

          <div className="mt-3">
            {hits ? (
              <div className="flex flex-col gap-1">
                <div className="px-2 pb-1 text-[11px] text-content-faint">
                  搜索结果 {hits.length} 条{hits.length >= 30 ? '（已达上限，试着换更具体的关键词）' : ''}
                </div>
                {hits.map((hit) => (
                  <button
                    key={`${hit.path}-${hit.lineNo ?? 'title'}`}
                    onClick={() => openFile(hit.path)}
                    title={hit.path}
                    className="rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-surface-raise"
                  >
                    <div className="truncate text-[13px] font-medium text-content-primary">{hit.title}</div>
                    <div className="mt-0.5 truncate text-[11px] leading-5 text-content-muted">
                      {hit.lineNo ? `L${hit.lineNo} · ` : ''}
                      {hit.snippet}
                    </div>
                  </button>
                ))}
                {hits.length === 0 && (
                  <div className="px-2 py-6 text-center text-xs text-content-faint">无命中，换个关键词试试</div>
                )}
              </div>
            ) : tree ? (
              <div className="flex flex-col gap-0.5">
                {tree.map((node) => (
                  <TreeNode
                    key={node.path}
                    node={node}
                    depth={0}
                    currentPath={file?.path || ''}
                    collapsed={collapsed}
                    toggleDir={toggleDir}
                    onOpen={openFile}
                  />
                ))}
              </div>
            ) : treeError ? (
              <div className="px-2 py-6 text-center text-xs text-rose-500">{treeError}</div>
            ) : (
              <div className="flex items-center justify-center gap-1.5 px-2 py-6 text-xs text-content-faint">
                <Loader2 size={12} className="animate-spin" /> 加载目录…
              </div>
            )}
          </div>
        </aside>

        {/* 右：笔记内容 */}
        <div className="thin-scroll min-w-0 flex-1 overflow-y-auto">
          {fileLoading ? (
            <div className="flex items-center justify-center gap-2 py-20 text-sm text-content-faint">
              <Loader2 size={14} className="animate-spin" /> 读取笔记…
            </div>
          ) : file ? (
            <article className="mx-auto max-w-3xl px-5 py-6">
              <h1 className="text-xl font-bold tracking-tight text-content-primary">{file.title}</h1>
              {(file.system || file.date || (file.tags && file.tags.length > 0)) && (
                <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
                  {file.system && (
                    <span className="rounded-full bg-brand-500/10 px-2.5 py-0.5 text-[11px] font-medium text-brand-700 dark:text-brand-300">
                      {file.system}
                    </span>
                  )}
                  {file.date && <span className="rounded-full bg-surface-raise px-2.5 py-0.5 text-[11px] text-content-muted">{file.date}</span>}
                  {(file.tags || []).map((tag) => (
                    <span key={tag} className="rounded-full bg-surface-raise px-2.5 py-0.5 text-[11px] text-content-muted">
                      #{tag}
                    </span>
                  ))}
                  <span className="ml-auto text-[11px] text-content-faint" title={file.path}>
                    {file.path}
                  </span>
                </div>
              )}
              <div className="mt-4 border-t border-line/60 pt-4">
                <Markdown content={file.content} />
              </div>
            </article>
          ) : (
            <div className="flex flex-col items-center justify-center gap-2 py-24 text-content-faint">
              <BookOpen size={28} className="opacity-40" />
              <div className="text-sm">选择左侧笔记查看，或搜索关键词</div>
              <div className="text-xs">速记由排查助手自动沉淀，本页只读</div>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
