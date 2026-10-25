package org.caps.columbo.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史消息项（user/assistant 文本）
 *
 * @author caps
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageVO {

    /** user / assistant */
    private String role;

    /** 消息文本 */
    private String content;
}
