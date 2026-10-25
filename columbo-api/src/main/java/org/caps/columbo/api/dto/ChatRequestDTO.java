package org.caps.columbo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequestDTO {

    @NotBlank(message = "智能体ID不能为空")
    private String agentId;

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    /** 可为空；为空时服务端自动创建会话 */
    private String sessionId;

    @NotBlank(message = "消息内容不能为空")
    private String message;

}
