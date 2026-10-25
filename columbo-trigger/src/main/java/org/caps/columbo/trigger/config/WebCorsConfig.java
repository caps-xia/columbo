package org.caps.columbo.trigger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 集中式跨域配置（替代各控制器散落的 @CrossOrigin(origins = "*")）：
 * 默认放开所有来源保持脚手架开箱即用；生产环境通过 ai.agent.cors.allowed-origins
 * 收紧为白名单（逗号分隔），例如 https://ai.example.com,https://office.example.com。
 *
 * @author caps
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Value("${ai.agent.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .maxAge(3600);
    }
}
