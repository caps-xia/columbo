package org.caps.columbo.domain.auth.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录鉴权配置。用户来源是 sys_user 表（工号/姓名/BCrypt 密码）；
 * seed 仅用于首次建表时初始化种子账号。后续接公司统一登录时新增 SSO 回调入口，
 * Sa-Token 会话层与本表结构不变。
 */
@Data
@ConfigurationProperties(prefix = "auth", ignoreInvalidFields = true)
public class AuthProperties {

    /** 是否启用登录鉴权（false 时拦截器整体退避，接口无需 token 即可访问，便于本地联调） */
    private Boolean enabled = false;

    /** 是否开放自助注册（false 时注册接口返回未开放提示，账号由管理员 INSERT） */
    private Boolean registerEnabled = true;

    /** 种子账号：sys_user 表为空时自动创建（改密码后此配置不再生效，以表内为准） */
    private Seed seed = new Seed();

    @Data
    public static class Seed {

        /** 种子工号 */
        private String username = "caps";

        /** 种子姓名 */
        private String name = "caps";

        /** 种子密码明文（入库前 BCrypt 哈希） */
        private String password = "admin123";
    }
}
