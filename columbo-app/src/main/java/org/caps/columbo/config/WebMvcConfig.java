package org.caps.columbo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 静态资源缓存策略：前端构建产物部署在 classpath:/static/app（访问路径 /app/）。
 * /app/assets/** 文件名带内容哈希 → 一年 immutable（改版自然换 URL，长缓存安全）；
 * 其余（index.html 等入口）走 application.yml 的 no-cache 协商缓存，保证发版即生效。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/assets/**")
                .addResourceLocations("classpath:/static/app/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
