package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 工具执行管理器补丁，处理 oneai 网关（GLM）三类已知输出瑕疵：
 * 1. toolCall 的 arguments 偶发在参数 JSON 前拼接空对象，如 {}{"request":...}；
 *    Jackson readValue 默认只解析第一个对象，导致工具方法收到空参数（request 为 null）。
 *    在交给 Spring AI 绑定前剥掉前缀空对象。
 * 2. 内部工具循环把模型的 toolCall 响应追加为 assistant 消息时 content 为 null；
 *    oneai 网关按 Claude 规范校验，content 为 null 直接 422。
 *    在工具执行完成后统一补成空串再回传给模型。
 * 3. 模型生成的 arguments 本身是非法 JSON（缺逗号/未转义引号等），参数绑定阶段抛
 *    ToolExecutionException 直接打断整个对话流。这里不炸流：转成错误文本作为工具
 *    结果回传，模型读到错误会修正参数重调（无法修正则改用文字回答）。
 */
@Service
public class SanitizingToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;

    /**
     * Spring AI 2.0 默认每工具每轮 40 次上限且超限抛 ToolCallLimitExceededException；
     * 本项目轮次治理由 ToolLoopGuard 负责，这里放宽到可配值并改用 RETURN_ERROR_RESPONSE
     * （超限返回错误响应让模型自换策略，不抛异常——响应由框架构造，toolCallId 完整）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SanitizingToolCallingManager(
            @org.springframework.beans.factory.annotation.Value("${ai.agent.tool-call-limit:200}") int toolCallLimit) {
        this.delegate = ToolCallingManager.builder()
                .maxCallsPerTool(toolCallLimit)
                .onLimitExceeded(org.springframework.ai.model.tool.ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
    }

    /** 测试/手工装配用：默认上限 200 */
    public SanitizingToolCallingManager() {
        this(200);
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        ToolExecutionResult result;
        try {
            result = delegate.executeToolCalls(prompt, sanitizeToolCallArguments(chatResponse));
        } catch (Exception e) {
            return toolErrorResult(prompt, chatResponse, e);
        }

        List<Message> history = result.conversationHistory();
        List<Message> sanitized = new ArrayList<>(history.size());
        boolean changed = false;
        for (Message message : history) {
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.getText() == null) {
                sanitized.add(AssistantMessage.builder()
                        .content("")
                        .properties(assistantMessage.getMetadata())
                        .toolCalls(assistantMessage.getToolCalls())
                        .media(assistantMessage.getMedia())
                        .build());
                changed = true;
            } else {
                sanitized.add(message);
            }
        }
        if (!changed) {
            return result;
        }
        return ToolExecutionResult.builder()
                .conversationHistory(sanitized)
                .returnDirect(result.returnDirect())
                .build();
    }

    /**
     * 参数绑定/工具执行失败的兜底：错误信息作为工具结果回传模型（自愈重试），
     * 而不是让异常打断对话流。模型侧表现：看到错误后修正参数重调，或放弃工具改文字回答。
     */
    private ToolExecutionResult toolErrorResult(Prompt prompt, ChatResponse chatResponse, Exception error) {
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message == null) {
                continue;
            }
            // 沿用 content 空串修补（oneai 422 问题），再入历史
            history.add(message.getText() == null
                    ? AssistantMessage.builder()
                            .content("")
                            .properties(message.getMetadata())
                            .toolCalls(message.getToolCalls())
                            .media(message.getMedia())
                            .build()
                    : message);
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(),
                        ToolErrorTexts.isNonRetryable(error.getMessage())
                                ? ToolErrorTexts.forExecutionFailure(error)
                                : "工具未执行：参数解析失败（" + error.getMessage() + "）。"
                                        + "请生成严格合法的 JSON 参数（字符串内的双引号用 \\\" 转义、键值对用逗号分隔完整）后重新调用；"
                                        + "若无法构造合法参数，请直接用文字回答用户。"));
            }
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(false)
                .build();
    }

    /** 重建 chatResponse，清洗每个 toolCall 的 arguments 前缀空对象 */
    static ChatResponse sanitizeToolCallArguments(ChatResponse chatResponse) {
        if (chatResponse == null || !chatResponse.hasToolCalls()) {
            return chatResponse;
        }
        List<Generation> generations = chatResponse.getResults();
        List<Generation> rebuilt = new ArrayList<>(generations.size());
        boolean changed = false;
        for (Generation generation : generations) {
            AssistantMessage message = generation.getOutput();
            if (message == null || !message.hasToolCalls()) {
                rebuilt.add(generation);
                continue;
            }
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(message.getToolCalls().size());
            boolean messageChanged = false;
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                String cleaned = stripLeadingEmptyObjects(toolCall.arguments());
                if (!cleaned.equals(toolCall.arguments())) {
                    toolCalls.add(new AssistantMessage.ToolCall(
                            toolCall.id(), toolCall.type(), toolCall.name(), cleaned));
                    messageChanged = true;
                } else {
                    toolCalls.add(toolCall);
                }
            }
            if (messageChanged) {
                rebuilt.add(new Generation(AssistantMessage.builder()
                        .content(message.getText())
                        .properties(message.getMetadata())
                        .toolCalls(toolCalls)
                        .media(message.getMedia())
                        .build(), generation.getMetadata()));
                changed = true;
            } else {
                rebuilt.add(generation);
            }
        }
        if (!changed) {
            return chatResponse;
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        return metadata == null ? new ChatResponse(rebuilt) : new ChatResponse(rebuilt, metadata);
    }

    /** 剥掉参数 JSON 前缀的空对象（含空白形式），如 "{}{\"request\":...}" -> "{\"request\":...}"；空白入参归一为 "{}" */
    static String stripLeadingEmptyObjects(String arguments) {
        if (arguments == null) {
            return null;
        }
        if (arguments.isBlank()) {
            return "{}";
        }
        String stripped = arguments.trim().replaceFirst("^(\\{\\s*\\})+", "").trim();
        return stripped.isEmpty() ? "{}" : stripped;
    }

}
