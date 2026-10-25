package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.auth.adapter.port.IAuthPort;
import org.caps.columbo.domain.auth.adapter.port.IUserRepository;
import org.caps.columbo.domain.auth.model.entity.AuthUser;
import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import org.caps.columbo.domain.auth.model.valobj.LoginUserVO;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.caps.columbo.domain.auth.model.valobj.EmpNo;

import java.util.Optional;

/**
 * 标准认证实现：sys_user 表工号 + BCrypt 密码校验（校验行为在 AuthUser 实体上）。
 * 统一登录接入后：SSO 回调换取工号，按工号查同一张表补全姓名后直接建立会话，本类校验分支退役。
 */
@Slf4j
@Component
public class StandardAuthPortImpl implements IAuthPort {

    private final IUserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthProperties authProperties;

    public StandardAuthPortImpl(IUserRepository userRepository, PasswordEncoder passwordEncoder,
                                AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
    }

    @Override
    public LoginUserVO verify(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号与密码不能为空");
        }
        Optional<AuthUser> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号或密码错误");
        }
        AuthUser user = found.get();
        if (!user.matches(password, passwordEncoder)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号或密码错误");
        }
        return LoginUserVO.builder()
                .username(user.getUsername())
                .nick(StringUtils.hasText(user.getName()) ? user.getName() : user.getUsername())
                .build();
    }

    @Override
    public LoginUserVO register(String username, String name, String password) {
        if (Boolean.FALSE.equals(authProperties.getRegisterEnabled())) {
            throw new AppException(ResponseCode.B001.getCode(), "注册未开放：请联系管理员添加账号");
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(name) || !StringUtils.hasText(password)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号、姓名与密码均不能为空");
        }
        String empNo = EmpNo.of(username).value();
        if (password.length() < 6 || password.length() > 64) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "密码长度需在 6-64 位之间");
        }
        if (userRepository.existsByUsername(empNo)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号已存在：" + empNo);
        }
        AuthUser user = AuthUser.builder()
                .username(empNo)
                .name(name.trim())
                .passwordHash(passwordEncoder.encode(password))
                .build();
        userRepository.save(user, empNo);
        log.info("新用户注册 username={}", empNo);
        return LoginUserVO.builder().username(user.getUsername()).nick(user.getName()).build();
    }
}
