package org.caps.columbo.domain.auth.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证用户聚合（sys_user 一行）：工号唯一，姓名展示用，密码仅存 BCrypt 哈希。
 * 密码校验是领域行为，收在实体上而非散在 Service。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthUser {

    /** 工号（登录名，同时作为会话归属 userId） */
    private String username;

    /** 姓名 */
    private String name;

    /** BCrypt 哈希（原文不落库不进日志） */
    private String passwordHash;

    /** 校验明文密码是否匹配 */
    public boolean matches(String rawPassword, PasswordEncoder encoder) {
        return passwordHash != null && rawPassword != null && encoder.matches(rawPassword, passwordHash);
    }
}
