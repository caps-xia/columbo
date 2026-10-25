package org.caps.columbo.domain.auth.model.entity;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证用户实体：密码校验行为
 */
class AuthUserTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void matchesCorrectPassword() {
        AuthUser user = AuthUser.builder()
                .username("caps").name("测试")
                .passwordHash(encoder.encode("passw0rd-demo"))
                .build();
        assertTrue(user.matches("passw0rd-demo", encoder));
    }

    @Test
    void rejectsWrongPassword() {
        AuthUser user = AuthUser.builder()
                .username("caps")
                .passwordHash(encoder.encode("passw0rd-demo"))
                .build();
        assertFalse(user.matches("wrong", encoder));
    }

    @Test
    void rejectsNullPasswordOrHash() {
        AuthUser noHash = AuthUser.builder().username("caps").build();
        assertFalse(noHash.matches("any", encoder));

        AuthUser user = AuthUser.builder()
                .username("caps").passwordHash(encoder.encode("x"))
                .build();
        assertFalse(user.matches(null, encoder));
    }
}
