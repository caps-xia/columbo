package org.caps.columbo.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 兼容装配：Boot 4 起默认自动配置 Jackson 3（tools.jackson），
 * 不再提供 Jackson 2 的 ObjectMapper Bean。存量代码（网关 JSON 编解码、
 * 工具产物序列化、ADK 事件落库等）统一走 Jackson 2，这里显式提供共享实例，
 * 并注册类路径上的数据类型模块（jackson-datatype-guava 等）。
 *
 * <p>忽略未知字段：Boot 2/3 自动配置默认关闭 FAIL_ON_UNKNOWN_PROPERTIES，
 * 裸 ObjectMapper 默认开启，会导致部分接口响应里的扩展字段（panelResult 等）解析失败。
 *
 * @author caps
 */
@Configuration
public class JacksonCompatConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
