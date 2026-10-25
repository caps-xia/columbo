package org.caps.columbo.api.dto;

import lombok.Data;

@Data
public class ChatResponseDTO {

    private String content;

    private String type;

    /** 本次对话所用会话 ID：脚本/外部 agent 持有它续聊追问 */
    private String sessionId;

}
