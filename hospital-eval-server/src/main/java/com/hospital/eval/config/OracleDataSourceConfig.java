package com.hospital.eval.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Oracle 双只读数据源（TASK-06 步骤 3，04 §8.4）。
 *
 * 注意：这里刻意【不】把 HikariDataSource 注册为 Spring Bean——否则会触发
 * DataSourceAutoConfiguration 的 @ConditionalOnMissingBean(DataSource) 回退、
 * 使 PostgreSQL 主数据源丢失。统一装进 OraclePools 持有器（普通 Bean），由
 * 主数据源的 Spring Boot 自动配置继续生效。
 *
 * 「已配置」= URL/用户名/密码三项非空且都不以 '<' 开头（.env.example 的
 * '<DBA 发放的…>' 占位符视为未配置）；未配置的源不创建、不影响启动。
 */
@Slf4j
@Configuration
public class OracleDataSourceConfig {

    private final Map<String, HikariDataSource> pools = new LinkedHashMap<>();

    /** 池持有器：普通 Bean（非 DataSource 类型），供 OracleQueryService 使用。 */
    public record OraclePools(
            Map<String, HikariDataSource> dataSources,
            Map<String, JdbcTemplateSpec> templates,
            Map<String, DataSourceInfo> infos) {
    }

    /** 连接池规格（数据源 + 预设超时参数），替代直接暴露 JdbcTemplate Bean。 */
    public record JdbcTemplateSpec(HikariDataSource dataSource, int queryTimeoutSeconds, int fetchSize) {
    }

    public record DataSourceInfo(String key, String host, boolean configured) {
    }

    @Value("${eval.engine.query-timeout-seconds:600}")
    private int queryTimeoutSeconds;

    @Bean
    public OraclePools oraclePools(
            @Value("${eval.oracle.his.url:}") String hisUrl,
            @Value("${eval.oracle.his.username:}") String hisUser,
            @Value("${eval.oracle.his.password:}") String hisPwd,
            @Value("${eval.oracle.ods.url:}") String odsUrl,
            @Value("${eval.oracle.ods.username:}") String odsUser,
            @Value("${eval.oracle.ods.password:}") String odsPwd) {
        Map<String, JdbcTemplateSpec> templates = new LinkedHashMap<>();
        Map<String, DataSourceInfo> infos = new LinkedHashMap<>();
        build("HIS_RO", hisUrl, hisUser, hisPwd, templates, infos);
        build("ODS_RO", odsUrl, odsUser, odsPwd, templates, infos);
        log.info("Oracle 只读数据源已配置: {}", templates.keySet());
        return new OraclePools(pools, templates, infos);
    }

    private void build(String key, String url, String user, String password,
                       Map<String, JdbcTemplateSpec> templates, Map<String, DataSourceInfo> infos) {
        boolean configured = isNotBlank(url) && isNotBlank(user) && isNotBlank(password)
                && !url.startsWith("<") && !user.startsWith("<") && !password.startsWith("<");
        String host = extractHost(url);
        infos.put(key, new DataSourceInfo(key, host, configured));
        if (!configured) {
            log.info("Oracle 数据源 {} 未配置（占位符或空值），跳过创建", key);
            return;
        }
        try {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(url);
            ds.setUsername(user);
            ds.setPassword(password);
            ds.setMaximumPoolSize(3);
            ds.setMinimumIdle(0);
            ds.setReadOnly(true);
            ds.setConnectionTimeout(10000);
            ds.setIdleTimeout(300000);
            ds.setPoolName(key);
            ds.setAutoCommit(true);
            ds.addDataSourceProperty("oracle.jdbc.ReadTimeout", "600000");
            pools.put(key, ds);
            templates.put(key, new JdbcTemplateSpec(ds, queryTimeoutSeconds, 2000));
        } catch (Exception e) {
            log.warn("Oracle 数据源 {} 创建失败: {}", key, e.getClass().getSimpleName());
            pools.remove(key);
            templates.remove(key);
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 从 jdbc:oracle:thin:@//host:port/service 或 @host:port:SID 中取 host。 */
    static String extractHost(String url) {
        if (url == null) {
            return null;
        }
        int at = url.indexOf('@');
        if (at < 0) {
            return null;
        }
        String rest = url.substring(at + 1);
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        int colon = rest.indexOf(':');
        int slash = rest.indexOf('/');
        int end = Math.min(colon < 0 ? rest.length() : colon, slash < 0 ? rest.length() : slash);
        return rest.substring(0, end);
    }

    @PreDestroy
    public void close() {
        pools.values().forEach(HikariDataSource::close);
    }
}
