package org.caps.columbo.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 注册请求（工号 + 姓名 + 密码；注册成功直接发 token）
 */
@Data
public class RegisterRequestDTO {

    @NotBlank(message = "username 不能为空")
    private String username;

    @NotBlank(message = "name 不能为空")
    private String name;

    @NotBlank(message = "password 不能为空")
    private String password;
}
