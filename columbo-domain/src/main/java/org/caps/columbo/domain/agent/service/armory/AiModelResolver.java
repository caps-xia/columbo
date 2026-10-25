package org.caps.columbo.domain.agent.service.armory;

import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;
import org.caps.columbo.domain.agent.model.valobj.AiProviderSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型限定写法解析器：模型池条目支持「供应商key/模型名」引用外部供应商，
 * 不带供应商前缀（或前缀未注册）的条目走 agent 内置 ai-api 网关。
 *
 * <p>解析规则（按第一个 '/' 切分）：前缀命中注册表 → 外部供应商，其余为模型名；
 * 前缀未注册 → 整串视为内置网关的模型名（模型名本身含 '/' 的场景不受影响）。
 * 显式指定了未注册供应商（如 zhipu/xxx 但注册表没有 zhipu）按装配错误处理，快速失败。
 *
 * @author caps
 */
public final class AiModelResolver {

    /** 解析结果：生效供应商 + 纯模型名 */
    public record ResolvedModel(String modelId, AiProviderSpec provider, boolean builtin) {
    }

    private AiModelResolver() {
    }

    /**
     * @param qualifiedModel 模型池条目（如 glm-5.3-flash、oneai/glm-5.3、anthropic/claude-sonnet-4）
     * @param registry       供应商注册表（ai.agent.providers，可为空）
     * @param builtin        agent 内置 ai-api 网关（兜底供应商）
     */
    public static ResolvedModel resolve(String qualifiedModel,
                                        Map<String, AiProviderSpec> registry,
                                        AiProviderSpec builtin) {
        String model = qualifiedModel == null ? "" : qualifiedModel.trim();
        int slash = model.indexOf('/');
        if (slash > 0 && slash < model.length() - 1 && registry != null) {
            String prefix = model.substring(0, slash);
            AiProviderSpec spec = registry.get(prefix);
            if (spec != null) {
                return new ResolvedModel(model.substring(slash + 1), spec, false);
            }
            if (!registry.isEmpty()) {
                // 明确写了「xx/模型」但 xx 未注册：大概率是配置笔误，装配期快速失败并给出可用清单
                throw new IllegalArgumentException("模型 '" + qualifiedModel + "' 的供应商 '" + prefix
                        + "' 未在 ai.agent.providers 注册，已注册: " + registry.keySet());
            }
        }
        return new ResolvedModel(model, builtin, true);
    }

    /** 规范化供应商协议：空/未知值按 openai 兼容处理（宽容绑定，配置笔误不至于断装配） */
    public static AiProviderSpec.AiApiFormat normalizeFormat(AiProviderSpec spec) {
        return spec == null || spec.getApiFormat() == null
                ? AiProviderSpec.AiApiFormat.OPENAI
                : spec.getApiFormat();
    }

    /** 便捷构造：把 agent 内置 ai-api 包装成供应商形态（统一分支处理） */
    public static AiProviderSpec builtinProvider(String baseUrl, String apiKey, String completionsPath) {
        AiProviderSpec spec = new AiProviderSpec();
        spec.setApiFormat(AiProviderSpec.AiApiFormat.OPENAI);
        spec.setBaseUrl(baseUrl);
        spec.setApiKey(apiKey);
        spec.setCompletionsPath(completionsPath);
        return spec;
    }

    /** 空注册表便捷值（避免调用方判空） */
    public static Map<String, AiProviderSpec> emptyRegistry() {
        return new LinkedHashMap<>();
    }
}
