package org.caps.columbo.domain.agent.service.armory.matter.tools;

import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiToolCallbackToolTest {

    private static final String TOOL_SCHEMA = """
            {"type":"object","properties":{
              "appNames":{"type":"array","items":{"type":"string"},"description":"业务系统名称列表"},
              "keyword":{"type":"string","description":"日志关键词"},
              "logLevel":{"type":"string","enum":["ERROR","FATAL","WARN"]}},
             "required":["keyword"]}""";

    private static ToolCallback callback(String name, String schema, Function<String, String> fn) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("desc of " + name)
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return fn.apply(toolInput);
            }
        };
    }

    @Test
    void declaration_should_carry_name_description_and_full_parameter_schema() {
        SpringAiToolCallbackTool tool = new SpringAiToolCallbackTool(
                callback("queryClsLog", TOOL_SCHEMA, r -> "{}"));

        FunctionDeclaration declaration = tool.declaration().orElseThrow();
        assertEquals("queryClsLog", declaration.name().orElse(null));
        assertEquals("desc of queryClsLog", declaration.description().orElse(null));

        Schema parameters = declaration.parameters().orElseThrow();
        assertEquals("object", parameters.type().orElseThrow().toString());
        assertTrue(parameters.required().isPresent());
        assertTrue(parameters.required().get().contains("keyword"));

        Schema appNames = parameters.properties().orElseThrow().get("appNames");
        assertEquals("array", appNames.type().orElseThrow().toString());
        assertTrue(appNames.items().isPresent(),
                "数组 items 必须保留：官方 ToolConverter 只转 type/description/properties/required，"
                        + "适配器若丢失会导致参数 schema 降级");
        assertEquals("string", appNames.items().orElseThrow().type().orElseThrow().toString());
        assertEquals("业务系统名称列表", appNames.description().orElse(null));

        Schema logLevel = parameters.properties().orElseThrow().get("logLevel");
        assertTrue(logLevel.enum_().isPresent());
        assertTrue(logLevel.enum_().get().contains("ERROR"));
    }

    @Test
    void runAsync_should_delegate_to_original_callback_with_json_args() throws Exception {
        SpringAiToolCallbackTool tool = new SpringAiToolCallbackTool(
                callback("queryClsLog", TOOL_SCHEMA,
                        input -> "{\"echo\":" + input + ",\"ok\":true}"));

        Map<String, Object> result = tool.runAsync(Map.of("keyword", "下单失败"), null)
                .blockingGet();

        assertEquals(Boolean.TRUE, result.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> echo = (Map<String, Object>) result.get("echo");
        assertEquals("下单失败", echo.get("keyword"));
    }

    @Test
    void runAsync_should_pass_empty_object_when_no_args() throws Exception {
        SpringAiToolCallbackTool tool = new SpringAiToolCallbackTool(
                callback("listTools", "{}", input -> "{\"seen\":\"" + input + "\"}"));

        Map<String, Object> result = tool.runAsync(null, null).blockingGet();

        assertEquals("{}", result.get("seen"));
    }

    @Test
    void wrapResult_should_parse_json_and_wrap_plain_text() throws Exception {
        assertTrue(SpringAiToolCallbackTool.wrapResult(null).isEmpty());
        assertTrue(SpringAiToolCallbackTool.wrapResult("  ").isEmpty());
        assertEquals(Map.of("count", 42), SpringAiToolCallbackTool.wrapResult("{\"count\":42}"));
        assertEquals(Map.of("text", "纯文本结果"), SpringAiToolCallbackTool.wrapResult("纯文本结果"));
    }

    @Test
    void toGenaiSchema_should_return_null_for_blank_schema() {
        assertFalse(java.util.Optional.ofNullable(
                SpringAiToolCallbackTool.toGenaiSchema(Map.of())).isPresent());
    }

    @Test
    void originalCallback_should_expose_wrapped_callback() {
        ToolCallback original = callback("countClsLog", "{}", r -> "{}");
        SpringAiToolCallbackTool tool = new SpringAiToolCallbackTool(original);
        assertEquals(original, tool.originalCallback());
        assertEquals("countClsLog", tool.name());
        assertEquals("desc of countClsLog", tool.description());
    }

}
