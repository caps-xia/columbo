package org.caps.columbo.trigger.http.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 结构化 SSE 对话请求（/api/v1/chat_events）。
 * message 与 images 至少传一个（截图排查场景允许只传图）。
 */
@Data
public class ChatEventsRequestDTO {

    @NotBlank(message = "agentId 不能为空")
    private String agentId;

    /** 鉴权开启时该值被服务端忽略（一律取登录工号），允许不传；鉴权关闭时缺省 caps */
    private String userId;

    /** 为空时服务端自动创建会话，并通过 session 事件回传 */
    private String sessionId;

    private String message;

    /** 附图（data URL 形式），排查助手会先做多模态识别再进入对话 */
    private List<String> images;
}
