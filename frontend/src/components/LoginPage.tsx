import { useState } from 'react';
import { Eye, EyeOff, Loader2, LogIn, ShieldCheck, Sparkles, UserRoundPlus } from 'lucide-react';
import clsx from 'clsx';
import { useStore } from '../store';
import logo from '../assets/logo.png';

const FEATURES = [
  { icon: Sparkles, text: '一句话生成 Word / Excel / PPT / 图表' },
  { icon: ShieldCheck, text: '日志排查 · 数据库查询 · 本地终端' },
];

/** 登录/注册页：极光渐变背景 + 毛玻璃卡片（后端 Sa-Token，token 7 天有效） */
export function LoginPage() {
  const login = useStore((state) => state.login);
  const register = useStore((state) => state.register);
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [shake, setShake] = useState(false);

  const switchMode = (next: 'login' | 'register') => {
    setMode(next);
    setError('');
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (!username.trim() || !password) {
      setError('请输入工号和密码');
      nudge();
      return;
    }
    if (mode === 'register' && !name.trim()) {
      setError('请输入姓名');
      nudge();
      return;
    }
    setBusy(true);
    setError('');
    try {
      if (mode === 'login') {
        await login(username.trim(), password);
      } else {
        await register(username.trim(), name.trim(), password);
      }
    } catch (err) {
      setError((err as Error).message || '操作失败');
      nudge();
    } finally {
      setBusy(false);
    }
  };

  const nudge = () => {
    setShake(true);
    window.setTimeout(() => setShake(false), 450);
  };

  const inputCls =
    'w-full rounded-xl border border-line bg-surface-input/80 px-3.5 py-2.5 text-sm text-content-primary outline-hidden transition-all placeholder:text-content-faint focus:border-brand-400 focus:bg-surface-panel focus:ring-4 focus:ring-brand-100/60 dark:focus:ring-brand-500/15';

  return (
    <div className="relative flex h-full items-center justify-center overflow-hidden bg-surface-app px-4">
      {/* 极光背景：三团彩色光斑缓慢漂移 + 细网格 */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div
          className="aurora-blob animate-float-slow"
          style={{ width: 480, height: 480, left: '8%', top: '12%', background: 'radial-gradient(circle, #7d95fb 0%, transparent 70%)' }}
        />
        <div
          className="aurora-blob animate-float-slower"
          style={{ width: 520, height: 520, right: '5%', top: '30%', background: 'radial-gradient(circle, #a78bfa 0%, transparent 70%)' }}
        />
        <div
          className="aurora-blob animate-float-slow"
          style={{ width: 420, height: 420, left: '35%', bottom: '-8%', background: 'radial-gradient(circle, #38bdf8 0%, transparent 70%)', animationDelay: '-7s' }}
        />
        <div
          className="absolute inset-0 opacity-[0.35] dark:opacity-[0.18]"
          style={{
            backgroundImage:
              'linear-gradient(rgba(100,116,139,0.12) 1px, transparent 1px), linear-gradient(90deg, rgba(100,116,139,0.12) 1px, transparent 1px)',
            backgroundSize: '44px 44px',
            maskImage: 'radial-gradient(ellipse 70% 60% at 50% 40%, black 40%, transparent 100%)',
            WebkitMaskImage: 'radial-gradient(ellipse 70% 60% at 50% 40%, black 40%, transparent 100%)',
          }}
        />
      </div>

      <div
        className={clsx(
          'relative w-[400px] max-w-full',
          shake && 'animate-shake',
        )}
      >
        <form
          onSubmit={submit}
          className="glass rounded-3xl border border-white/40 p-8 shadow-[0_24px_70px_rgba(24,24,60,0.18)] dark:border-white/10"
        >
          <div className="mb-7 flex flex-col items-center gap-2.5">
            <div className="relative">
              <img src={logo} alt="logo" className="h-16 w-16 rounded-2xl shadow-panel ring-1 ring-white/50" />
              <span className="absolute -inset-2 -z-10 rounded-3xl bg-gradient-to-br from-brand-400/40 to-violet-400/40 blur-lg" />
            </div>
            <div className="text-xl font-bold tracking-wide text-content-primary">Columbo AI 工作台</div>
            <div className="text-xs text-content-muted">
              {mode === 'login' ? '登录后开始对话 · 登录态保持 7 天' : '注册新账号（工号 + 姓名 + 密码）'}
            </div>
          </div>

          <label className="mb-1.5 block text-xs font-medium text-content-secondary">工号</label>
          <input
            autoFocus
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            className={clsx('mb-3.5', inputCls)}
            placeholder="2-64 位字母/数字/._-"
            autoComplete="username"
          />

          {mode === 'register' && (
            <>
              <label className="mb-1.5 block text-xs font-medium text-content-secondary">姓名</label>
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                className={clsx('mb-3.5', inputCls)}
                placeholder="真实姓名（展示用）"
                autoComplete="name"
              />
            </>
          )}

          <label className="mb-1.5 block text-xs font-medium text-content-secondary">密码</label>
          <div className="relative mb-4">
            <input
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className={clsx('pr-11', inputCls)}
              placeholder={mode === 'register' ? '6-64 位' : '请输入密码'}
              autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
            />
            <button
              type="button"
              onClick={() => setShowPassword((v) => !v)}
              aria-label={showPassword ? '隐藏密码' : '显示密码'}
              className="absolute right-2 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-lg text-content-faint transition-colors hover:bg-surface-raise hover:text-content-secondary"
            >
              {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
            </button>
          </div>

          {error && (
            <div className="mb-3.5 rounded-xl bg-rose-50 px-3.5 py-2.5 text-xs leading-5 text-rose-600 dark:bg-rose-500/10 dark:text-rose-400">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={busy}
            className="group flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-brand-500 to-violet-500 px-4 py-3 text-sm font-semibold text-white shadow-brand-lg transition-all hover:-translate-y-px hover:shadow-brand active:translate-y-0 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {busy ? <Loader2 size={15} className="animate-spin" /> : mode === 'login' ? <LogIn size={15} /> : <UserRoundPlus size={15} />}
            {busy ? '处理中…' : mode === 'login' ? '登 录' : '注册并登录'}
          </button>

          <div className="mt-5 text-center text-xs text-content-muted">
            {mode === 'login' ? (
              <>
                没有账号？
                <button type="button" onClick={() => switchMode('register')} className="ml-1 font-medium text-brand-500 hover:underline">
                  注册新用户
                </button>
              </>
            ) : (
              <>
                已有账号？
                <button type="button" onClick={() => switchMode('login')} className="ml-1 font-medium text-brand-500 hover:underline">
                  返回登录
                </button>
              </>
            )}
          </div>
        </form>

        {/* 能力速览条 */}
        <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
          {FEATURES.map(({ icon: Icon, text }) => (
            <span
              key={text}
              className="glass inline-flex items-center gap-1.5 rounded-full border border-white/30 px-3 py-1.5 text-[11px] font-medium text-content-secondary dark:border-white/10"
            >
              <Icon size={12} className="text-brand-500" />
              {text}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
