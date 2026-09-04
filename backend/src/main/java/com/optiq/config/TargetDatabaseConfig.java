package com.optiq.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.optiq.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic target database configuration.
 * Reads connection details from SettingsService (DB-persisted settings).
 * Supports live reload — call reinitialize() after settings change.
 */
@Configuration
public class TargetDatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(TargetDatabaseConfig.class);

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/optiq_target}")
    private String defaultUrl;

    @Value("${spring.datasource.username:optiq_monitor}")
    private String defaultUsername;

    @Value("${spring.datasource.password:changeme}")
    private String defaultPassword;

    // Holds the current datasource + JdbcTemplate so they can be swapped on reload
    private final AtomicReference<HikariDataSource> currentDataSource = new AtomicReference<>();
    private final AtomicReference<JdbcTemplate> currentJdbcTemplate = new AtomicReference<>();

    /**
     * Create or reinitialize the target datasource with new settings.
     */
    public JdbcTemplate reinitialize(SettingsService settings) {
        // Close old pool
        HikariDataSource old = currentDataSource.getAndSet(null);
        if (old != null) {
            try { old.close(); } catch (Exception e) { log.warn("Error closing old pool: {}", e.getMessage()); }
        }

        String url = settings.getDbUrl();
        String user = settings.getDbUsername();
        String pass = settings.getDbPassword();

        if (url == null || url.isBlank()) url = defaultUrl;
        if (user == null || user.isBlank()) user = defaultUsername;
        if (pass == null || pass.isBlank()) pass = defaultPassword;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setReadOnly(true);
        config.setPoolName("optiq-target-pool");
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");

        HikariDataSource ds = new HikariDataSource(config);
        currentDataSource.set(ds);

        JdbcTemplate tpl = new JdbcTemplate(ds);
        currentJdbcTemplate.set(tpl);

        log.info("Target database reinitialized: {}", url);
        return tpl;
    }

    /**
     * Get the current JdbcTemplate, creating it lazily if needed.
     */
    public JdbcTemplate getJdbcTemplate(SettingsService settings) {
        JdbcTemplate tpl = currentJdbcTemplate.get();
        if (tpl == null) {
            tpl = reinitialize(settings);
        }
        return tpl;
    }

    /**
     * Get the current DataSource (for health checks, etc.)
     */
    public DataSource getDataSource() {
        return currentDataSource.get();
    }
}
