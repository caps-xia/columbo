package org.caps.columbo.domain.agent.service.armory;

import org.caps.columbo.domain.agent.model.valobj.AiProviderSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型限定写法解析：内置兜底 / 供应商前缀 / 未注册快速失败 / 空注册表宽容。
 */
class AiModelResolverTest {

    private AiProviderSpec provider(AiProviderSpec.AiApiFormat format) {
        AiProviderSpec spec = new AiProviderSpec();
        spec.setApiFormat(format);
        spec.setBaseUrl("https://example.com");
        spec.setApiKey("sk-test");
        return spec;
    }

    @Test
    void 不带前缀走内置网关() {
        AiProviderSpec builtin = provider(AiProviderSpec.AiApiFormat.OPENAI);
        var resolved = AiModelResolver.resolve("glm-5.3-flash", registryOf(), builtin);
        assertTrue(resolved.builtin());
        assertEquals("glm-5.3-flash", resolved.modelId());
        assertEquals(builtin, resolved.provider());
    }

    @Test
    void 注册供应商前缀解析出外部供应商与纯模型名() {
        var resolved = AiModelResolver.resolve("anthropic/claude-sonnet-4", registryOf(), provider(AiProviderSpec.AiApiFormat.OPENAI));
        assertFalse(resolved.builtin());
        assertEquals("claude-sonnet-4", resolved.modelId());
        assertEquals(AiProviderSpec.AiApiFormat.ANTHROPIC, resolved.provider().getApiFormat());
    }

    @Test
    void 未注册供应商前缀快速失败并给出可用清单() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> AiModelResolver.resolve("zhipu/glm-4.6", registryOf(), provider(AiProviderSpec.AiApiFormat.OPENAI)));
        assertTrue(ex.getMessage().contains("anthropic"));
        assertTrue(ex.getMessage().contains("zhipu"));
    }

    @Test
    void 空注册表时含斜杠的模型名整体走内置网关() {
        var resolved = AiModelResolver.resolve("meta/llama-3", AiModelResolver.emptyRegistry(), provider(AiProviderSpec.AiApiFormat.OPENAI));
        assertTrue(resolved.builtin());
        assertEquals("meta/llama-3", resolved.modelId());
    }

    @Test
    void 空模型名安全兜底() {
        var resolved = AiModelResolver.resolve(null, registryOf(), provider(AiProviderSpec.AiApiFormat.OPENAI));
        assertTrue(resolved.builtin());
        assertEquals("", resolved.modelId());
    }

    @Test
    void 未知协议规范化为openai() {
        AiProviderSpec spec = new AiProviderSpec();
        assertEquals(AiProviderSpec.AiApiFormat.OPENAI, AiModelResolver.normalizeFormat(spec));
        assertEquals(AiProviderSpec.AiApiFormat.OPENAI, AiModelResolver.normalizeFormat(null));
    }

    private Map<String, AiProviderSpec> registryOf() {
        Map<String, AiProviderSpec> registry = new LinkedHashMap<>();
        registry.put("anthropic", provider(AiProviderSpec.AiApiFormat.ANTHROPIC));
        registry.put("oneai", provider(AiProviderSpec.AiApiFormat.OPENAI));
        return registry;
    }
}
