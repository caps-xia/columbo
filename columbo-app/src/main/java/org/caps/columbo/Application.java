package org.caps.columbo;

import org.caps.columbo.domain.agent.service.armory.matter.tools.DatabaseTools;
import org.caps.columbo.domain.agent.service.armory.matter.tools.KnowledgeTools;
import org.caps.columbo.domain.agent.service.armory.matter.tools.LocalCodeTools;
import org.caps.columbo.domain.agent.service.armory.matter.tools.TroubleshootTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Configurable
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /** 日志排查工具（CLS 日志代码排查助手） */
    @Bean("troubleshootToolCallbackProvider")
    public ToolCallbackProvider troubleshootTools(TroubleshootTools troubleshootTools) {
        return MethodToolCallbackProvider.builder().toolObjects(troubleshootTools).build();
    }

    /** 本地源码分析工具（日志代码排查助手） */
    @Bean("localCodeToolCallbackProvider")
    public ToolCallbackProvider localCodeTools(LocalCodeTools localCodeTools) {
        return MethodToolCallbackProvider.builder().toolObjects(localCodeTools).build();
    }

    /** 本地知识库工具（日志代码排查助手：检索历史案例/沉淀速记） */
    @Bean("knowledgeToolCallbackProvider")
    public ToolCallbackProvider knowledgeTools(KnowledgeTools knowledgeTools) {
        return MethodToolCallbackProvider.builder().toolObjects(knowledgeTools).build();
    }

    /** 数据库只读查询工具（数据库查询助手，可选：配置连接后启用） */
    @Bean("databaseToolCallbackProvider")
    public ToolCallbackProvider databaseTools(DatabaseTools databaseTools) {
        return MethodToolCallbackProvider.builder().toolObjects(databaseTools).build();
    }

}
