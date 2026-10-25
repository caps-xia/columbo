package org.caps.columbo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 切换智能体模型请求（switch_agent_model）
 */
@Data
public class SwitchModelRequestDTO {

    @NotBlank(message = "agentId 不能为空")
    private String agentId;

    @NotBlank(message = "model 不能为空")
    private String model;
}
