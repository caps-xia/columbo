package org.caps.columbo.domain.agent.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 对话消息预加工链（链名约定为 "chat"）上下文：消息进入智能体前的确定性加工（脱敏/限流/审计/消息增强等）。
 *
 * @author caps
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatPipelineContext implements PipelineContext {

    private String agentId;

    private String userId;

    private String sessionId;

    /** 待加工的消息原文；处理器可改写，改写结果即最终送入智能体的消息 */
    private String message;

    /** 处理器间传值的扩展属性 */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
}
