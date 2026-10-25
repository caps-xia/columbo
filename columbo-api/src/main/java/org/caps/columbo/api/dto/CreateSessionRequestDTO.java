package org.caps.columbo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    @NotBlank(message = "智能体ID不能为空")
    private String agentId;

    @NotBlank(message = "用户ID不能为空")
    private String userId;

}
