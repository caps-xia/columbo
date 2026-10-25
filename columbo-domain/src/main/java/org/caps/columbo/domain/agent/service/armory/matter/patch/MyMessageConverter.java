package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.adk.tools.BaseTool;
import org.caps.columbo.domain.agent.service.armory.matter.tools.SpringAiToolCallbackTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spring AI 补丁：做官方 MessageConverter 不做的三件事——
 * 1. 每次调用把服务器当前时间并入系统提示（LLM 不感知宿主时钟，缺了会把“最近1小时”
 *    换算成编造的日期；ADK 1.2.0 官方类仍无此能力）；
 * 2. 工具调用轮回传的 assistant 消息 content 为 null 时补空串（oneai 网关按 Claude
 *    规范校验会 422）；
 * 3. Agent 级工具经官方 ToolConverter 转换会丢 schema 的 items/enum，
 *    按工具名还原为原始 ToolCallback 直传（见 restoreOriginalToolCallbacks）。
 * 多模态 media 转换官方 1.2.0 已原生支持（UserMessage.builder().media(...)），
 * 此处不再覆写，避免 media 被重复添加。
 *
 * @author caps
 * 2026/1/9 08:17
 */
public class MyMessageConverter extends MessageConverter {

    private static final DateTimeFormatter TIME_CONTEXT_FORMATTER = LocalDateTimeUtils.DATETIME_FORMATTER;

    /** 历史消息中要剥离的思考块：⟦think⟧ 标记（耗时标记可选）+ 模型回显的 <thinking> 块 + 残留孤儿耗时标记 */
    private static final java.util.regex.Pattern THINK_BLOCK_IN_HISTORY = java.util.regex.Pattern.compile(
            "⟦think⟧[\\s\\S]*?⟦/think⟧(?:⟦thinktime⟧\\d+⟦/thinktime⟧)?"
                    + "|<thinking>[\\s\\S]*?</thinking>"
                    + "|⟦thinktime⟧\\d+⟦/thinktime⟧");

    public MyMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest, ChatOptions modelDefaultOptions) {
        // 官方 1.9 起 Prompt options 以模型默认 options 为基构建（保持 OpenAiChatOptions 具体类型，
        // SA 2.0 的 OpenAI 模型会直接强转 Prompt options，泛型 options 会 ClassCastException）
        Prompt prompt = super.toLlmPrompt(llmRequest, modelDefaultOptions);
        // Spring AI 2.0 起 options 不可变，还原工具后需用新 options 重建 Prompt
        Prompt restored = restoreOriginalToolCallbacks(llmRequest, prompt);
        return withCurrentTimeContext(sanitizeNullAssistantContent(stripThinkFromHistory(restored)));
    }

    /**
     * 历史消息里的思考内容剥离：会话里落库的 assistant 消息带 ⟦think⟧ 全文标记
     * （含耗时标记），思考回传模型既浪费上下文（2026-09-15 事故里 30MB 历史直接把
     * 网关打爆 400 prompt is too long），也会诱发模型在正文里自我复述；
     * 模型回显的 <thinking> 块同属思考性内容，一并剥离。
     */
    private Prompt stripThinkFromHistory(Prompt prompt) {
        boolean changed = false;
        List<Message> instructions = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistant
                    && assistant.getText() != null
                    && (assistant.getText().contains(MySpringAI.THINK_OPEN)
                        || assistant.getText().contains("<thinking>"))) {
                instructions.add(AssistantMessage.builder()
                        .content(THINK_BLOCK_IN_HISTORY.matcher(assistant.getText()).replaceAll("").trim())
                        .properties(assistant.getMetadata())
                        .toolCalls(assistant.getToolCalls())
                        .media(assistant.getMedia())
                        .build());
                changed = true;
            } else {
                instructions.add(message);
            }
        }
        return changed ? new Prompt(instructions, prompt.getOptions()) : prompt;
    }

    /**
     * 官方 ToolConverter 把 Agent 级工具（BaseTool）包成新的 FunctionToolCallback 时，
     * schema 只保留 type/description/properties/required（items/enum 丢失）。
     * 对经 SpringAiToolCallbackTool 适配的工具，按工具名把包装回调换回原始 ToolCallback：
     * 参数 schema 原样直传模型，执行也省掉一层 ADK runAsync 间接调用。
     */
    private Prompt restoreOriginalToolCallbacks(LlmRequest llmRequest, Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                || llmRequest.tools() == null || llmRequest.tools().isEmpty()) {
            return prompt;
        }
        Map<String, ToolCallback> originals = new HashMap<>();
        for (BaseTool tool : llmRequest.tools().values()) {
            if (tool instanceof SpringAiToolCallbackTool adapted) {
                originals.put(adapted.name(), adapted.originalCallback());
            }
        }
        if (originals.isEmpty()) {
            return prompt;
        }
        List<ToolCallback> current = options.getToolCallbacks();
        if (current == null || current.isEmpty()) {
            return prompt;
        }
        List<ToolCallback> restored = new ArrayList<>(current.size());
        boolean changed = false;
        for (ToolCallback callback : current) {
            String name = callback.getToolDefinition() == null
                    ? null : callback.getToolDefinition().name();
            ToolCallback original = name == null ? null : originals.get(name);
            if (original != null) {
                restored.add(original);
                changed = true;
            } else {
                restored.add(callback);
            }
        }
        if (!changed) {
            return prompt;
        }
        return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(restored).build());
    }

    /**
     * 每次调用把服务器当前时间并入系统提示：LLM 不感知宿主时钟，缺了这行会把"最近1小时"
     * 换算成一年前的日期（实测 glm-5.3.3 编造过 2025-08-29）。已存在系统消息时追加，避免多条 system。
     */
    static List<Message> withCurrentTimeContext(List<Message> instructions, LocalDateTime now) {
        String timeLine = "当前时间：" + now.format(TIME_CONTEXT_FORMATTER)
                + "（" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "）。"
                + "换算相对时间（如：最近1小时）或确定具体日期时，必须以当前时间为准，不要凭空猜测。";
        List<Message> merged = new ArrayList<>(instructions);
        if (!merged.isEmpty() && merged.get(0) instanceof SystemMessage systemMessage) {
            String text = systemMessage.getText() == null ? "" : systemMessage.getText();
            merged.set(0, new SystemMessage(text + "\n\n" + timeLine));
            return merged;
        }
        merged.add(0, new SystemMessage(timeLine));
        return merged;
    }

    private Prompt withCurrentTimeContext(Prompt prompt) {
        return new Prompt(withCurrentTimeContext(prompt.getInstructions(), LocalDateTime.now()),
                prompt.getOptions());
    }

    /**
     * oneai 网关按 Claude 规范校验，要求每条消息 content 为非null的字符串或内容块数组；
     * 工具调用轮回传的 assistant 消息只有 toolCalls 没有文本（content=null），会被网关 422 拒绝，
     * 这里统一补成空串。
     */
    private Prompt sanitizeNullAssistantContent(Prompt prompt) {
        List<Message> instructions = new ArrayList<>();
        boolean changed = false;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.getText() == null) {
                instructions.add(AssistantMessage.builder()
                        .content("")
                        .properties(assistantMessage.getMetadata())
                        .toolCalls(assistantMessage.getToolCalls())
                        .media(assistantMessage.getMedia())
                        .build());
                changed = true;
            } else {
                instructions.add(message);
            }
        }
        return changed ? new Prompt(instructions, prompt.getOptions()) : prompt;
    }

}
