package org.caps.columbo.domain.auth.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录用户（认证通过后返回；username 同时作为会话归属 userId）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginUserVO {

    /** 登录名 */
    private String username;

    /** 显示昵称（未配置时同 username） */
    private String nick;
}
