package org.caps.columbo.api;

import org.caps.columbo.api.dto.LoginRequestDTO;
import org.caps.columbo.api.dto.LoginResponseDTO;
import org.caps.columbo.api.dto.RegisterRequestDTO;
import org.caps.columbo.api.response.Response;

/**
 * 登录鉴权契约（本地账号模式；统一登录接入时在此追加 SSO 回调签名）
 */
public interface IAuthService {

    /** 工号密码换取 token（7 天） */
    Response<LoginResponseDTO> login(LoginRequestDTO requestDTO);

    /** 注册新用户（查重+入库），成功直接发 token；auth.register-enabled=false 时返回未开放 */
    Response<LoginResponseDTO> register(RegisterRequestDTO requestDTO);

    /** 注销当前 token */
    Response<Boolean> logout();

    /** 当前登录用户（前端启动探测；token 失效返回 0301） */
    Response<LoginResponseDTO> me();
}
