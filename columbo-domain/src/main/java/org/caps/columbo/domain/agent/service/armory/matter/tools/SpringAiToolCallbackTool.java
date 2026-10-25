package org.caps.columbo.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring AI ToolCallback → ADK BaseTool 适配器。
 *
 * <p>Agent 级挂工具（LlmAgent.builder().tools(...)）要求 ADK 工具对象，而 armory 的
 * local/MCP/skills 工厂产出的都是 Spring AI ToolCallback。本适配器把任意 ToolCallback
 * 包装成 ADK BaseTool：声明（name/description/参数 schema）取自 ToolDefinition，
 * 执行委托回 ToolCallback 本体，对 local/MCP/skills 三类来源统一适用。
 *
 * <p>注意 declaration 必须走 parameters(genai Schema) 而非 parametersJsonSchema：
 * google-adk-spring-ai 的 ToolConverter 只读 declaration().parameters()，
 * 传 JSON 形态会导致参数 schema 丢失。
 */
public class SpringAiToolCallbackTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback callback;

    public SpringAiToolCallbackTool(ToolCallback callback) {
        super(nameOf(callback), descriptionOf(callback));
        this.callback = callback;
    }

    /** 被包装的原始 ToolCallback，供 MyMessageConverter 还原直传（避免官方转换丢 items/enum） */
    public ToolCallback originalCallback() {
        return callback;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        ToolDefinition definition = callback.getToolDefinition();
        FunctionDeclaration.Builder builder = FunctionDeclaration.builder()
                .name(definition.name())
                .description(descriptionOf(callback));
        String inputSchema = definition.inputSchema();
        if (inputSchema != null && !inputSchema.isBlank()) {
            try {
                Map<String, Object> schemaJson = MAPPER.readValue(inputSchema, MAP_TYPE);
                Schema schema = toGenaiSchema(schemaJson);
                if (schema != null) {
                    builder.parameters(schema);
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "工具 " + definition.name() + " inputSchema 解析失败: " + inputSchema, e);
            }
        }
        return Optional.of(builder.build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            String input = (args == null || args.isEmpty()) ? "{}" : MAPPER.writeValueAsString(args);
            return wrapResult(callback.call(input));
        });
    }

    /** 工具结果是 JSON 对象则原样返回，否则按 ADK 惯例包成 {"text": ...} */
    static Map<String, Object> wrapResult(String result) throws JsonProcessingException {
        if (result == null || result.isBlank()) {
            return Map.of();
        }
        String trimmed = result.trim();
        if (trimmed.startsWith("{")) {
            return MAPPER.readValue(trimmed, MAP_TYPE);
        }
        return Map.of("text", result);
    }

    /** JSON Schema（Map 形态）→ genai Schema，递归处理 properties/items/required */
    static Schema toGenaiSchema(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Schema.Builder builder = Schema.builder();
        Object type = json.get("type");
        if (type instanceof String typeText && !typeText.isBlank()) {
            builder.type(typeText);
        }
        Object description = json.get("description");
        if (description instanceof String descriptionText && !descriptionText.isBlank()) {
            builder.description(descriptionText);
        }
        Object format = json.get("format");
        if (format instanceof String formatText && !formatText.isBlank()) {
            builder.format(formatText);
        }
        if (json.get("enum") instanceof List<?> enumValues && !enumValues.isEmpty()) {
            List<String> enumTexts = new ArrayList<>();
            for (Object value : enumValues) {
                enumTexts.add(String.valueOf(value));
            }
            builder.enum_(enumTexts);
        }
        if (json.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
            Map<String, Schema> propertySchemas = new HashMap<>();
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> propertyJson) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedPropertyJson = (Map<String, Object>) propertyJson;
                    Schema propertySchema = toGenaiSchema(typedPropertyJson);
                    if (propertySchema != null) {
                        propertySchemas.put(String.valueOf(entry.getKey()), propertySchema);
                    }
                }
            }
            if (!propertySchemas.isEmpty()) {
                builder.properties(propertySchemas);
            }
        }
        if (json.get("required") instanceof List<?> required && !required.isEmpty()) {
            List<String> requiredNames = new ArrayList<>();
            for (Object name : required) {
                requiredNames.add(String.valueOf(name));
            }
            builder.required(requiredNames);
        }
        if (json.get("items") instanceof Map<?, ?> items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedItems = (Map<String, Object>) items;
            Schema itemSchema = toGenaiSchema(typedItems);
            if (itemSchema != null) {
                builder.items(itemSchema);
            }
        }
        return builder.build();
    }

    private static String nameOf(ToolCallback callback) {
        ToolDefinition definition = callback.getToolDefinition();
        return definition == null || definition.name() == null ? "unknownTool" : definition.name();
    }

    private static String descriptionOf(ToolCallback callback) {
        ToolDefinition definition = callback.getToolDefinition();
        return definition == null || definition.description() == null ? "" : definition.description();
    }

}
