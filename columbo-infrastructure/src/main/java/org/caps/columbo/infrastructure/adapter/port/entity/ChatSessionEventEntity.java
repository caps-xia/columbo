package org.caps.columbo.infrastructure.adapter.port.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * chat_session_event 表实体（ADK Event 完整 JSON 快照，uk_event 幂等去重）。
 *
 * @author caps
 */
@Data
@TableName("chat_session_event")
public class ChatSessionEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    private String sessionId;

    private String eventId;

    private String author;

    private String eventJson;

    /** 逻辑删除：0-否，1-是（随会话软删） */
    private Integer isDeleted;

    private LocalDateTime gmtCreate;
}
