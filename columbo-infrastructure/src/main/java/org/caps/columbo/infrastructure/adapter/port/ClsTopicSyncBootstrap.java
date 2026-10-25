package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsTopicSyncResultVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 启动时自动同步 CLS 主题注册表（DescribeTopics -> 注册表 overlay）：
 * 新应用自动补录、yml 占位 topicId 自动修正，接入新主题零手工配置。
 * 异步执行不阻塞启动；密钥未配置/网络异常只记 warn，不影响服务（查询走 yml 静态注册表兜底）。
 */
@Slf4j
@Component
public class ClsTopicSyncBootstrap {

    @Resource
    private ClsLogChannelPort clsLogChannelPort;

    @Resource
    private ClsProperties clsProperties;

    /** 走全局业务线程池（统一治理 + MDC 透传），不再占用 commonPool */
    @Resource(name = "bizExecutor")
    private Executor bizExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (Boolean.FALSE.equals(clsProperties.getEnabled()) || clsProperties.getSites().isEmpty()) {
            return;
        }
        bizExecutor.execute(() -> {
            try {
                ClsTopicSyncResultVO result = clsLogChannelPort.syncClsTopics();
                result.getSites().forEach(site -> log.info("启动同步CLS主题 site={} label={} remote={} added={} updated={} err={}",
                        site.getSite(), site.getLabel(), site.getRemoteCount(),
                        site.getAdded(), site.getUpdated(), site.getErrorMsg()));
            } catch (Exception e) {
                log.warn("启动同步CLS主题注册表失败（不影响启动，查询走静态注册表）: {}", e.getMessage());
            }
        });
    }
}
