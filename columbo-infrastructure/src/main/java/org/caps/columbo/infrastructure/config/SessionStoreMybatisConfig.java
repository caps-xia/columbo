package org.caps.columbo.infrastructure.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * 会话持久化的 MyBatis-Plus 装配：ai.agent.session-store.enabled=true（默认，缺配置即开）时建池、扫 Mapper。
 * 项目无全局 spring.datasource——这个池只服务会话两张表，连接来自 ai.agent.session-store.*
 * （默认 H2 文件库，换 MySQL url 即切）。enabled=false 时退化为纯内存会话。
 *
 * <p>采用「显式 SqlSessionFactory 分包绑定」的多数据源形态（参照 car-price-platform 的 MybatisConfiguration）：
 * 本配置类独占 sessionStoreDataSource + sessionSqlSessionFactory + 会话 Mapper 包，
 * 与其他数据源互不干扰——将来接入业务库时，为业务库写一个对称的配置类（占 spring.datasource、
 * 扫业务 Mapper 包）即可，本类无需任何改动。
 *
 * @author caps
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.agent.session-store", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "org.caps.columbo.infrastructure.adapter.port.mapper", sqlSessionFactoryRef = "sessionSqlSessionFactory")
public class SessionStoreMybatisConfig {

    /**
     * 无参构造 + setter 是惰性建池：MySQL 未启动时应用照常启动，
     * 首次写入前 SessionStorePortImpl.ensureSchema 会先自愈建库建表。
     */
    @Bean(destroyMethod = "close")
    public DataSource sessionStoreDataSource(SessionStoreProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("agent-session-store");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setMaximumPoolSize(4);
        // 写路径在对话链路里，连接拿不到要快速失败（上层只告警不阻断）
        dataSource.setConnectionTimeout(5000);
        return dataSource;
    }

    /**
     * 方言标识（按 url 前缀判断，不连库）：H2 / MySQL 映射为 databaseId，Mapper 按方言选择 SQL 变体。
     * 不用 VendorDatabaseIdProvider——它启动期就连库取产品名，库未自愈创建/数据库未启动时会让应用起不来，
     * 破坏"数据库不可用不阻断启动"的容错语义。
     */
    @Bean
    public org.apache.ibatis.mapping.DatabaseIdProvider sessionStoreDatabaseIdProvider(SessionStoreProperties properties) {
        return new org.apache.ibatis.mapping.DatabaseIdProvider() {
            @Override
            public void setProperties(Properties props) { }
            @Override
            public String getDatabaseId(DataSource dataSource) {
                String url = properties.getUrl();
                if (url.startsWith("jdbc:h2")) {
                    return "h2";
                }
                if (url.startsWith("jdbc:mysql")) {
                    return "mysql";
                }
                return null;
            }
        };
    }

    /** 会话库专属 SqlSessionFactory（MyBatis-Plus 实现，支持 BaseMapper），显式绑定会话池 */
    @Bean
    public SqlSessionFactory sessionSqlSessionFactory(
            @Qualifier("sessionStoreDataSource") DataSource sessionStoreDataSource,
            org.apache.ibatis.mapping.DatabaseIdProvider sessionStoreDatabaseIdProvider) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(sessionStoreDataSource);
        factory.setDatabaseIdProvider(sessionStoreDatabaseIdProvider);
        return factory.getObject();
    }
}
