package org.caps.columbo.config;

import lombok.extern.slf4j.Slf4j;
import org.caps.columbo.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.caps.columbo.domain.agent.model.valobj.properties.PipelineProperties;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionCompactionProperties;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties;
import org.caps.columbo.domain.agent.service.IArmoryService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.Resource;
import java.util.ArrayList;

/**
 * 智能体装配：在全部单例实例化完成后、Web 容器开始接收请求前执行
 * （SmartInitializingSingleton 早于 SmartLifecycle 的 Tomcat 启动），
 * 保证"端口可访问 ⇒ 智能体已装配"，消除启动窗口期的请求竞态。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({AiAgentAutoConfigProperties.class, SessionStoreProperties.class, PipelineProperties.class, SessionCompactionProperties.class})
public class AiAgentAutoConfig implements SmartInitializingSingleton {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IArmoryService armoryService;

    @Override
    public void afterSingletonsInstantiated() {
        // 只打印配置表名称；完整配置含 api-key 等敏感信息，不能整体序列化进日志
        log.info("Ai Agent 智能体装配开始，配置表: {}", aiAgentAutoConfigProperties.getTables().keySet());

        // acceptArmoryAgents 声明了受检异常，装配失败直接终止启动
        try {
            armoryService.acceptArmoryAgents(new ArrayList<>(aiAgentAutoConfigProperties.getTables().values()));
        } catch (Exception e) {
            throw new RuntimeException("智能体装配失败", e);
        }
    }

}
