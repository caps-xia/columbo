package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.auth.adapter.port.IUserRepository;
import org.caps.columbo.domain.auth.model.entity.AuthUser;
import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标准认证端口：查库 + BCrypt 校验 + 未知名统一报错
 */
class StandardAuthPortImplTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private IUserRepository repository;
    private StandardAuthPortImpl authPort;

    @BeforeEach
    void setUp() {
        repository = mock(IUserRepository.class);
        authPort = new StandardAuthPortImpl(repository, encoder, new AuthProperties());
    }

    @Test
    void verifyReturnsUserWithNick() {
        when(repository.findByUsername("caps")).thenReturn(Optional.of(AuthUser.builder()
                .username("caps").name("张三")
                .passwordHash(encoder.encode("passw0rd-demo")).build()));
        assertEquals("张三", authPort.verify("caps", "passw0rd-demo").getNick());
    }

    @Test
    void verifyFallsBackNickToUsername() {
        when(repository.findByUsername("caps")).thenReturn(Optional.of(AuthUser.builder()
                .username("caps")
                .passwordHash(encoder.encode("passw0rd-demo")).build()));
        assertEquals("caps", authPort.verify("caps", "passw0rd-demo").getNick());
    }

    @Test
    void wrongPasswordThrowsGenericMessage() {
        when(repository.findByUsername("caps")).thenReturn(Optional.of(AuthUser.builder()
                .username("caps")
                .passwordHash(encoder.encode("passw0rd-demo")).build()));
        AppException e = assertThrows(AppException.class, () -> authPort.verify("caps", "bad"));
        assertEquals("工号或密码错误", e.getInfo());
    }

    @Test
    void unknownUserThrowsGenericMessage() {
        when(repository.findByUsername("nobody")).thenReturn(Optional.empty());
        AppException e = assertThrows(AppException.class, () -> authPort.verify("nobody", "x"));
        assertEquals("工号或密码错误", e.getInfo());
    }

    @Test
    void blankInputThrows() {
        assertThrows(AppException.class, () -> authPort.verify("", "x"));
        assertThrows(AppException.class, () -> authPort.verify("caps", " "));
    }

    @Test
    void registerSuccessReturnsUser() {
        when(repository.existsByUsername("newuser")).thenReturn(false);
        assertEquals("新用户", authPort.register("newuser", "新用户", "pass123").getNick());
    }

    @Test
    void registerDuplicateRejected() {
        when(repository.existsByUsername("caps")).thenReturn(true);
        AppException e = assertThrows(AppException.class, () -> authPort.register("caps", "张三", "pass123"));
        assertEquals("工号已存在：caps", e.getInfo());
    }

    @Test
    void registerValidatesFields() {
        // 工号非法
        assertThrows(AppException.class, () -> authPort.register("非法工号!", "张三", "pass123"));
        // 密码过短
        assertThrows(AppException.class, () -> authPort.register("newuser", "张三", "123"));
        // 空姓名
        assertThrows(AppException.class, () -> authPort.register("newuser", " ", "pass123"));
    }

    @Test
    void registerDisabledWhenConfigured() {
        AuthProperties disabled = new AuthProperties();
        disabled.setRegisterEnabled(false);
        StandardAuthPortImpl port = new StandardAuthPortImpl(repository, encoder, disabled);
        AppException e = assertThrows(AppException.class, () -> port.register("newuser", "张三", "pass123"));
        assertEquals("注册未开放：请联系管理员添加账号", e.getInfo());
    }
}
