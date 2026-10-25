package org.caps.columbo.api.dto;

import lombok.Data;

/**
 * 历史会话列表项
 *
 * @author caps
 */
@Data
public class ChatSessionResponseDTO {

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private String agentId;

    /** 智能体名称 */
    private String agentName;

    /** 标题（首条用户消息） */
    private String title;

    /** 归档空间名（如: 值班）；null=未归档 */
    private String space;

    /** 最后活跃时间（毫秒时间戳） */
    private Long updatedAt;
}
