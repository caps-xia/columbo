package org.caps.columbo.trigger.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪 + 访问日志：
 * <pre>
 * 1. 优先透传调用方请求头 X-Trace-Id（跨服务串联），没有则本地生成
 * 2. 写入 MDC，logback pattern 中的 %X{trace-id} 即每行日志带上链路ID；同时回写响应头，排查时让调用方直接反馈该值
 * 3. 记录请求入口/出口与耗时；URL 之外不打参数——聊天内容、密钥等敏感数据不落日志
 * </pre>
 * 注意：流式接口（chat_stream）在控制器返回 ResponseBodyEmitter 后即打印「请求结束」，
 * 该耗时代表流交接完成，不代表流式输出结束。
 *
 * @author caps
 */
@Slf4j
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key，与 logback-spring.xml pattern 中 %X{trace-id} 对应 */
    public static final String TRACE_ID = "trace-id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 文档类端点不记访问日志、不占链路ID */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/swagger-ui") || uri.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        long start = System.currentTimeMillis();
        try {
            log.info("请求开始 {} {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
        } finally {
            log.info("请求结束 {} {} status:{} 耗时:{}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), System.currentTimeMillis() - start);
            MDC.remove(TRACE_ID);
        }
    }

}
