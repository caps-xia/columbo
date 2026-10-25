package org.caps.columbo.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话持久化配置（默认开箱即用：H2 文件库 + write-through + 重启重放恢复）。
 * <p>双档位：
 * <ul>
 *   <li>默认 H2 文件库（零外部依赖，库就是 ./data/ 下一个文件，单机开发/演示够用）；</li>
 *   <li>生产/多服务共享：换 MySQL url 即切（表结构与 SQL 兼容，Mapper 已按方言适配）。</li>
 * </ul>
 * enabled=false 时纯内存会话（重启即失）。
 *
 * @author caps
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.session-store", ignoreInvalidFields = true)
public class SessionStoreProperties {

    /** 是否启用会话持久化（默认开，H2 零依赖） */
    private boolean enabled = true;

    /**
     * JDBC 连接。默认 H2 文件库；生产切 MySQL 示例：
     * jdbc:mysql://127.0.0.1:3306/agent_session?useSSL=false&characterEncoding=utf8&connectTimeout=3000&socketTimeout=10000
     * 库/表不存在时启动后首次写入会自动创建
     */
    private String url = "jdbc:h2:file:./data/agent_session;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private String username = "sa";

    private String password = "";
}
