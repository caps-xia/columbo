package org.caps.columbo.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import org.caps.columbo.api.IAuthService;
import org.caps.columbo.api.dto.LoginRequestDTO;
import org.caps.columbo.api.dto.LoginResponseDTO;
import org.caps.columbo.api.dto.RegisterRequestDTO;
import org.caps.columbo.api.response.Response;
import org.caps.columbo.domain.auth.adapter.port.IAuthPort;
import org.caps.columbo.domain.auth.model.valobj.LoginUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录鉴权入口（Sa-Token 会话）。面向 api 模块 IAuthService 契约。
 * 认证实现走 domain IAuthPort（当前为 sys_user 表 + BCrypt；统一登录接入时换 SSO 回调入口）。
 */
@Slf4j
@Tag(name = "登录鉴权", description = "工号密码登录/登出/当前用户")
@RestController
@RequestMapping("/api/v1/auth/")
public class AuthController implements IAuthService {

    @Resource
    private IAuthPort authPort;

    @Operation(summary = "登录", description = "工号密码换取 token（有效期 7 天）；后续统一登录接入时改走 SSO 回调")
    @PostMapping("login")
    @Override
    public Response<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        LoginUserVO user = authPort.verify(requestDTO.getUsername(), requestDTO.getPassword());
        StpUtil.login(user.getUsername());
        LoginResponseDTO dto = new LoginResponseDTO();
        dto.setUsername(user.getUsername());
        dto.setNick(user.getNick());
        dto.setToken(StpUtil.getTokenValue());
        dto.setTimeout(StpUtil.getTokenTimeout());
        log.info("用户登录 username={} token 有效期 {}s", user.getUsername(), dto.getTimeout());
        return Response.success(dto);
    }

    @Operation(summary = "注册", description = "工号+姓名+密码注册，成功直接发 token（7 天）；auth.register-enabled=false 时返回未开放")
    @PostMapping("register")
    @Override
    public Response<LoginResponseDTO> register(@Valid @RequestBody RegisterRequestDTO requestDTO) {
        LoginUserVO user = authPort.register(requestDTO.getUsername(), requestDTO.getName(), requestDTO.getPassword());
        StpUtil.login(user.getUsername());
        LoginResponseDTO dto = new LoginResponseDTO();
        dto.setUsername(user.getUsername());
        dto.setNick(user.getNick());
        dto.setToken(StpUtil.getTokenValue());
        dto.setTimeout(StpUtil.getTokenTimeout());
        log.info("用户注册并登录 username={}", user.getUsername());
        return Response.success(dto);
    }

    @Operation(summary = "登出", description = "注销当前 token")
    @PostMapping("logout")
    @Override
    public Response<Boolean> logout() {
        StpUtil.logout();
        return Response.success(true);
    }

    @Operation(summary = "当前登录用户", description = "前端启动时探测登录态；token 失效返回 0301")
    @GetMapping("me")
    @Override
    public Response<LoginResponseDTO> me() {
        LoginResponseDTO dto = new LoginResponseDTO();
        dto.setUsername(StpUtil.getLoginIdAsString());
        dto.setTimeout(StpUtil.getTokenTimeout());
        return Response.success(dto);
    }
}
