import { AlertCircle, CheckCircle2, Info } from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';

/** 右下角轻提示栈：操作确定性反馈（删除/切换/导出等），2.6s 自动消失 */
export function ToastStack() {
  const toasts = useStore((state) => state.toasts);
  const dismissToast = useStore((state) => state.dismissToast);
  if (toasts.length === 0) {
    return null;
  }
  return (
    <div className="pointer-events-none fixed bottom-6 right-6 z-[60] flex flex-col items-end gap-2">
      {toasts.map((toast) => {
        const Icon = toast.type === 'success' ? CheckCircle2 : toast.type === 'error' ? AlertCircle : Info;
        return (
          <button
            key={toast.id}
            onClick={() => dismissToast(toast.id)}
            className={clsx(
              'pointer-events-auto flex max-w-sm items-center gap-2.5 rounded-xl border px-4 py-2.5 text-[13px] font-medium shadow-panel backdrop-blur animate-toast-in',
              toast.type === 'success' && 'border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
              toast.type === 'error' && 'border-rose-500/30 bg-rose-500/10 text-rose-600 dark:text-rose-400',
              toast.type === 'info' && 'border-line bg-surface-panel/95 text-content-secondary',
            )}
          >
            <Icon size={15} className="shrink-0" />
            {toast.message}
          </button>
        );
      })}
    </div>
  );
}
