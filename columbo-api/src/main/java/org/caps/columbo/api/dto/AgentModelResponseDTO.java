package org.caps.columbo.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能体可切换模型信息（query_agent_models）
 */
@Data
public class AgentModelResponseDTO {

    private String agentId;

    private String agentName;

    /** 可用模型池（chat-model.models 未配置时 = [model]） */
    private List<String> models;

    /** 当前模型（运行时切换后变化，重启回到 yml 默认） */
    private String current;
}
