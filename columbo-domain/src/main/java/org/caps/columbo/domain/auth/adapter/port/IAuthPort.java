package org.caps.columbo.domain.auth.adapter.port;

import org.caps.columbo.domain.auth.model.valobj.LoginUserVO;

/**
 * 认证端口：校验用户凭据，返回登录用户。
 * 当前实现为本地账号表（LocalAuthPortImpl）；接公司统一登录后换成 SSO 校验实现，
 * 或保留本端口仅作兜底、主入口改为 SSO 回调后直接建立会话。
 */
public interface IAuthPort {

    /** 校验用户名密码；失败抛 AppException(0002)，成功返回登录用户 */
    LoginUserVO verify(String username, String password);

    /** 注册新用户（工号/姓名/密码校验 + 查重 + 入库），成功返回登录用户 */
    LoginUserVO register(String username, String name, String password);
}
