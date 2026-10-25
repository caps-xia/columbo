import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, X } from 'lucide-react';
import { useStore } from '../store';

/**
 * 全局确认/输入弹窗（替代 window.confirm / window.prompt）：
 * 通过 store.confirmDialog / promptDialog 发起，Promise 拿结果。
 */
export function GlobalDialog() {
  const dialog = useStore((state) => state.dialog);
  const resolveDialog = useStore((state) => state.resolveDialog);
  const [value, setValue] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (dialog?.type === 'prompt') {
      setValue(dialog.value || '');
      window.setTimeout(() => {
        inputRef.current?.focus();
        inputRef.current?.select();
      }, 0);
    }
  }, [dialog]);

  useEffect(() => {
    if (!dialog) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        resolveDialog(dialog.type === 'prompt' ? null : false);
      } else if (event.key === 'Enter' && dialog.type === 'prompt' && (event.target as HTMLElement)?.tagName === 'INPUT') {
        event.preventDefault();
        resolveDialog(value);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [dialog, resolveDialog, value]);

  if (!dialog) {
    return null;
  }

  const confirm = () => resolveDialog(dialog.type === 'prompt' ? value.trim() || null : true);
  const cancel = () => resolveDialog(dialog.type === 'prompt' ? null : false);

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm" onClick={cancel}>
      <div
        className="w-[400px] max-w-full animate-fade-in-up rounded-2xl border border-line bg-surface-panel p-5 shadow-lg"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={dialog.title}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2.5">
            {dialog.danger && (
              <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-rose-500/10 text-rose-500">
                <AlertTriangle size={16} />
              </span>
            )}
            <div className="text-sm font-semibold text-content-primary">{dialog.title}</div>
          </div>
          <button
            onClick={cancel}
            aria-label="关闭"
            className="flex h-6 w-6 items-center justify-center rounded-md text-content-faint transition-colors hover:bg-surface-raise hover:text-content-secondary"
          >
            <X size={13} />
          </button>
        </div>
        {dialog.message && <div className="mt-2 text-[13px] leading-6 text-content-muted">{dialog.message}</div>}
        {dialog.type === 'prompt' && (
          <input
            ref={inputRef}
            value={value}
            maxLength={100}
            placeholder={dialog.placeholder}
            onChange={(event) => setValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                confirm();
              }
            }}
            className="mt-3 w-full rounded-xl border border-line bg-surface-input px-3 py-2 text-sm text-content-primary outline-hidden transition-colors focus:border-brand-400"
          />
        )}
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={cancel}
            className="rounded-full border border-line bg-surface-panel px-4 py-2 text-xs font-medium text-content-secondary transition-colors hover:bg-surface-raise"
          >
            取消
          </button>
          <button
            onClick={confirm}
            className={
              dialog.danger
                ? 'inline-flex select-none items-center justify-center rounded-full bg-rose-500 px-4 py-2 text-xs font-medium text-white shadow-soft transition-opacity hover:opacity-90'
                : 'btn-primary px-4 py-2 text-xs'
            }
          >
            {dialog.confirmText || '确定'}
          </button>
        </div>
      </div>
    </div>
  );
}
