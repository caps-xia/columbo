package org.caps.columbo.config;

import org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.DatabaseProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.ImageUnderstandingProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 客服问题排查能力配置
 */
@Configuration
@EnableConfigurationProperties({ClsProperties.class, CodeAnalysisProperties.class, ImageUnderstandingProperties.class, KnowledgeProperties.class, DatabaseProperties.class})
public class TroubleshootAutoConfig {

}
