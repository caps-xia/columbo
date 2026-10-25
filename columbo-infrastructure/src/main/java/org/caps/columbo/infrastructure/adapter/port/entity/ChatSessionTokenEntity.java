package org.caps.columbo.infrastructure.adapter.port.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * chat_session_token 表实体：按会话累计的模型调用用量（列累加，uk_app_session 定位）。
 * 会话删除时物理清理（派生数据，无审计价值）。
 *
 * @author caps
 */
@Data
@TableName("chat_session_token")
public class ChatSessionTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    private String sessionId;

    /** 累计输入 token（含缓存命中部分） */
    private Long inputTokens;

    /** 累计输出 token */
    private Long outputTokens;

    /** 其中提示缓存命中的 token（计入 input_tokens） */
    private Long cachedTokens;

    /** 累计总 token */
    private Long totalTokens;

    /** 模型调用次数 */
    private Long llmCalls;

    private LocalDateTime gmtCreate;

    private LocalDateTime gmtModified;
}
