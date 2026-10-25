package org.caps.columbo.api.dto;

import lombok.Data;

/**
 * 登录结果：token + 用户（工号/姓名）
 */
@Data
public class LoginResponseDTO {

    /** 工号（登录名，同时作为会话归属 userId） */
    private String username;

    /** 姓名 */
    private String nick;

    /** token 值（前端存 localStorage，请求头 satoken 携带） */
    private String token;

    /** token 剩余有效期（秒） */
    private Long timeout;
}
