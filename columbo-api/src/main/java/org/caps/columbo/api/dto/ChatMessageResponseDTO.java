package org.caps.columbo.api.dto;

import lombok.Data;

/**
 * 历史消息项
 *
 * @author caps
 */
@Data
public class ChatMessageResponseDTO {

    /** user / assistant */
    private String role;

    /** 消息文本 */
    private String content;
}
