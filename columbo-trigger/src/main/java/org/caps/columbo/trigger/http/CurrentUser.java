package org.caps.columbo.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 当前请求的归属用户解析：会话与登录用户强绑定的唯一入口。
 * 鉴权开启时无视前端传参，一律取登录态工号——谁登录就只能读写自己的会话/文件；
 * 鉴权关闭（本地联调）时回退前端传参，保持脚本直连可用。
 */
@Component
public class CurrentUser {

    private final AuthProperties authProperties;

    public CurrentUser(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** 覆盖式解析：enabled=true 取登录工号；否则用请求参数（缺省 caps） */
    public String resolve(String requestUserId) {
        if (Boolean.TRUE.equals(authProperties.getEnabled())) {
            return StpUtil.getLoginIdAsString();
        }
        return StringUtils.hasText(requestUserId) ? requestUserId : "caps";
    }
}
