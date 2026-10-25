package org.caps.columbo.trigger.http.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截配置：/api/v1/** 全量鉴权，仅放行登录入口。
 * auth.enabled=false 时不注册拦截器（本地联调免登录，curl/脚本直连）。
 * 静态资源（/app/）与 Swagger 不在拦截路径内。
 *
 * <p>SSE 流式接口（ResponseBodyEmitter/SseEmitter）走 Servlet 异步模式：流结束时容器会做一次
 * ASYNC dispatch 回到 DispatcherServlet 再次经过拦截器，而异步派发线程没有 SaTokenContext
 * （上下文随首次请求初始化），此时 checkLogin 会抛 SaTokenContextException——所以异步派发
 * 阶段一律放行，鉴权只在请求首次进入时做。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    private final AuthProperties authProperties;

    public SaTokenConfigure(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (Boolean.FALSE.equals(authProperties.getEnabled())) {
            return;
        }
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()) {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/register");
    }
}
