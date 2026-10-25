package org.caps.columbo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求（工号 + 密码；统一登录接入后由 SSO 回调替换本入口）
 */
@Data
public class LoginRequestDTO {

    @NotBlank(message = "username 不能为空")
    private String username;

    @NotBlank(message = "password 不能为空")
    private String password;
}
