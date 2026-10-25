import clsx from 'clsx';
import { ChevronDown } from 'lucide-react';
import { useStore, type AgentContext } from '../store';
import { agentMeta } from '../agentMeta';

/**
 * 输入区上方的上下文选择器：
 * 所有 chip 常显品牌高亮（表示"选择生效中"），值偏离默认时额外加实心圆点提醒。
 * 日志助手：区域/日志环境/工程；数据库助手：连接；办公助手：工具；模型 chip 同规则。
 */
export function ContextBar() {
  const currentAgentId = useStore((state) => state.currentAgentId);
  const contextByAgent = useStore((state) => state.contextByAgent);
  const setContext = useStore((state) => state.setContext);
  const agentModels = useStore((state) => state.agentModels);
  const switchModel = useStore((state) => state.switchModel);
  const modelSwitching = useStore((state) => state.modelSwitching);
  const meta = agentMeta(currentAgentId);
  const context = contextByAgent[currentAgentId] || {};
  const modelInfo = agentModels.find((item) => item.agentId === currentAgentId);

  const hasContextChips = meta.contextOptions && meta.contextOptions.length > 0;
  const hasModelChip = !!modelInfo && modelInfo.models.length > 1;
  if (!hasContextChips && !hasModelChip) {
    return null;
  }

  const chipClass = (nonDefault: boolean) =>
    clsx(
      'flex items-center gap-1.5 rounded-full border px-3 py-1.5 transition-colors',
      nonDefault
        ? 'border-brand-400/70 bg-brand-500/10 text-brand-700 dark:border-brand-500/50 dark:bg-brand-500/15 dark:text-brand-300'
        : 'border-brand-200 bg-brand-500/5 text-brand-600 dark:border-brand-500/30 dark:bg-brand-500/10 dark:text-brand-400',
    );

  const Dot = () => <span className="h-1.5 w-1.5 rounded-full bg-brand-500" title="非默认值" />;

  return (
    <div className="flex flex-wrap items-center gap-2 px-1 pb-2.5">
      {meta.contextOptions?.map((group) => {
        const value = (context as AgentContext)[group.key] ?? group.options[0].value;
        const isNonDefault =
          group.key === 'logEnv' ? value !== 'product' : value !== '' && value !== group.options[0].value;
        return (
          <label
            key={group.key}
            className={chipClass(isNonDefault)}
            title={isNonDefault ? '已指定非默认值' : '当前为默认值（圆点表示非默认）'}
          >
            {isNonDefault && <Dot />}
            <span className="text-[11px] font-medium opacity-80">{group.label}</span>
            <select
              value={value}
              onChange={(event) => setContext(currentAgentId, { [group.key]: event.target.value })}
              className="max-w-[150px] cursor-pointer appearance-none bg-transparent text-xs font-medium outline-none"
            >
              {group.options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            {/* 下拉可发现性：appearance-none 去掉了原生箭头，补一个，否则 chip 看起来是静态徽章 */}
            <ChevronDown size={11} className="shrink-0 opacity-50" />
          </label>
        );
      })}
      {hasModelChip && (
        <label
          className={clsx(chipClass(modelInfo.current !== modelInfo.models[0]), modelSwitching && 'opacity-60')}
          title={
            modelInfo.current !== modelInfo.models[0]
              ? '已切换为非默认模型（圆点提示）；重启服务后回到默认'
              : '默认模型；切换即时生效（历史会话保留，重启服务后回到默认）'
          }
        >
          {modelInfo.current !== modelInfo.models[0] && <Dot />}
          <span className="text-[11px] font-medium opacity-80">模型</span>
          <select
            value={modelInfo.current}
            disabled={modelSwitching}
            onChange={(event) => switchModel(currentAgentId, event.target.value)}
            className="max-w-[170px] cursor-pointer appearance-none bg-transparent text-xs font-medium outline-none"
          >
            {modelInfo.models.map((model) => (
              <option key={model} value={model}>
                {model}
              </option>
            ))}
          </select>
          <ChevronDown size={11} className="shrink-0 opacity-50" />
        </label>
      )}
    </div>
  );
}
