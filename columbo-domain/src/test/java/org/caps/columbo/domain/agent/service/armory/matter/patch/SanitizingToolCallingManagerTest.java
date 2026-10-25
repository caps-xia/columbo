package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * oneai 网关（GLM）会输出 "{}{\"request\":...}" 形式的 toolCall arguments，
 * Jackson 只解析出第一个空对象导致工具方法参数绑定失败，这里回归清洗逻辑。
 */
class SanitizingToolCallingManagerTest {

    @Test
    void stripsGatewayEmptyObjectPrefix() {
        assertEquals("{\"request\":{\"a\":1}}",
                SanitizingToolCallingManager.stripLeadingEmptyObjects("{}{\"request\":{\"a\":1}}"));
        assertEquals("{\"request\":{\"a\":1}}",
                SanitizingToolCallingManager.stripLeadingEmptyObjects("{ }{ }{\"request\":{\"a\":1}}"));
    }

    @Test
    void keepsNormalArgumentsUntouched() {
        assertEquals("{\"request\":{}}", SanitizingToolCallingManager.stripLeadingEmptyObjects("{\"request\":{}}"));
        assertEquals("{}", SanitizingToolCallingManager.stripLeadingEmptyObjects("{}"));
        assertNull(SanitizingToolCallingManager.stripLeadingEmptyObjects(null));
        assertEquals("{}", SanitizingToolCallingManager.stripLeadingEmptyObjects("  "));
    }

    @Test
    void rebuildsChatResponseWithCleanedArguments() {
        AssistantMessage.ToolCall dirty = new AssistantMessage.ToolCall(
                "id", "function", "countClsLog", "{}{\"request\":{\"appNames\":[\"工单\"]}}");
        AssistantMessage message = AssistantMessage.builder()
                .content(null)
                .properties(java.util.Map.of())
                .toolCalls(List.of(dirty))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));

        ChatResponse cleaned = SanitizingToolCallingManager.sanitizeToolCallArguments(response);

        assertEquals("{\"request\":{\"appNames\":[\"工单\"]}}",
                cleaned.getResult().getOutput().getToolCalls().get(0).arguments());
        assertEquals("countClsLog", cleaned.getResult().getOutput().getToolCalls().get(0).name());
    }

    @Test
    void returnsSameInstanceWhenNothingToFix() {
        AssistantMessage message = new AssistantMessage("纯文本回复，无工具调用");
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));
        assertSame(response, SanitizingToolCallingManager.sanitizeToolCallArguments(response));
    }

    /** 参数 JSON 非法（缺逗号）时不再炸流：错误文本作为工具结果回传，模型可修正重调 */
    @Test
    void brokenArgumentsBecomeToolErrorResultInsteadOfException() {
        // column 139 缺逗号的坏参数（复现线上形态）
        String broken = "{\"request\":{\"appNames\":[\"海南admin\"]\"keyword\":\"下单失败\"}}";
        AssistantMessage.ToolCall bad = new AssistantMessage.ToolCall("id-1", "function", "queryClsLog", broken);
        AssistantMessage message = AssistantMessage.builder()
                .content(null)
                .properties(java.util.Map.of())
                .toolCalls(List.of(bad))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(message)));
        Prompt prompt = Prompt.builder()
                .messages(List.of(new org.springframework.ai.chat.messages.UserMessage("查日志")))
                .build();

        SanitizingToolCallingManager manager = new SanitizingToolCallingManager();
        org.springframework.ai.model.tool.ToolExecutionResult result = manager.executeToolCalls(prompt, response);

        // 未抛异常；历史末尾是 ToolResponseMessage，内容含解析失败提示与修正指引
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                org.springframework.ai.chat.messages.ToolResponseMessage.class, last);
        ToolResponseMessage toolResponse = (ToolResponseMessage) last;
        assertEquals("queryClsLog", toolResponse.getResponses().get(0).name());
        org.junit.jupiter.api.Assertions.assertTrue(toolResponse.getResponses().get(0).responseData()
                .contains("工具未执行：参数解析失败"));
        org.junit.jupiter.api.Assertions.assertTrue(toolResponse.getResponses().get(0).responseData()
                .contains("重新调用"));
        org.junit.jupiter.api.Assertions.assertFalse(result.returnDirect());
    }
}
