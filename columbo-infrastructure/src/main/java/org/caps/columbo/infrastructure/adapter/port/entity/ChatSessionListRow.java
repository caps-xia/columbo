package org.caps.columbo.infrastructure.adapter.port.entity;

import lombok.Data;

/**
 * 会话列表查询投影行（selectUserSessions 专用，列别名按驼峰映射）。
 *
 * @author caps
 */
@Data
public class ChatSessionListRow {

    private String sessionId;

    private String appName;

    /** 该会话首条 user 事件 JSON，用于生成标题；可为 null */
    private String firstUser;

    /** 自定义标题（用户重命名）；非空时优先于 firstUser */
    private String title;

    /** 归档空间名；null=未归档 */
    private String space;

    private Long updatedMs;
}
