package org.caps.columbo.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话存储记录（库中 chat_session 一行 + 首条用户事件 JSON，用于生成标题）
 *
 * @author caps
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionRecordVO {

    /** 会话ID */
    private String sessionId;

    /** 应用名（agent 装配 appName） */
    private String appName;

    /** 首条用户事件原文 JSON（可能为 null：还没有用户消息） */
    private String firstUserEventJson;

    /** 自定义标题（用户重命名）；非空时优先于首条用户消息 */
    private String title;

    /** 归档空间名（如: 值班）；null=未归档 */
    private String space;

    /** 最后活跃时间（毫秒时间戳） */
    private Long updatedAt;
}
