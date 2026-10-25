package org.caps.columbo.infrastructure.adapter.port.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * chat_session 表实体（会话登记，uk_app_session 幂等）。
 *
 * @author caps
 */
@Data
@TableName("chat_session")
public class ChatSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    private String sessionId;

    private String userId;

    /** 自定义标题（用户重命名）；空串时标题回退为首条用户消息 */
    private String title;

    /** 归档空间名（如: 值班、日常）；空串=未归档 */
    private String space;

    /** 逻辑删除：0-否，1-是 */
    private Integer isDeleted;

    /** 创建人（=user_id） */
    private String createBy;

    /** 修改人（重命名/移动/删除操作者） */
    private String modifiedBy;

    private LocalDateTime gmtCreate;

    private LocalDateTime gmtModified;
}
