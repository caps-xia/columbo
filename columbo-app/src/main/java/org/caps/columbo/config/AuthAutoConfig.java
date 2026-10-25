package org.caps.columbo.config;

import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 登录鉴权配置：属性绑定 + 密码编码器（BCrypt，仅 crypto 模块无 Spring Security 全家桶）
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthAutoConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
