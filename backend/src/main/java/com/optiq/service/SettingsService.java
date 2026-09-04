package com.optiq.service;

import com.optiq.model.AppSetting;
import com.optiq.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central settings service — fully generic database + AI + notifications.
 * Notifications: Slack, Discord, Microsoft Teams, or any generic webhook.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    // ── Database keys (granular) ──
    public static final String DB_TYPE = "db.type";
    public static final String DB_HOST = "db.host";
    public static final String DB_PORT = "db.port";
    public static final String DB_NAME = "db.name";
    public static final String DB_USERNAME = "db.username";
    public static final String DB_PASSWORD = "db.password";
    public static final String DB_JDBC_URL = "db.jdbc_url";

    // ── Generic AI keys ──
    public static final String AI_PROVIDER = "ai.provider";
    public static final String AI_BASE_URL = "ai.base_url";
    public static final String AI_API_KEY = "ai.api_key";
    public static final String AI_MODEL = "ai.model";

    // ── Notification keys (generic — works with Slack, Discord, Teams, etc.) ──
    public static final String NOTIFY_ENABLED = "notify.enabled";
    public static final String NOTIFY_TYPE = "notify.type";           // slack, discord, teams, generic
    public static final String NOTIFY_WEBHOOK_URL = "notify.webhook_url";

    // ── Legacy keys ──
    public static final String DB_URL = "db.url";
    public static final String OPENAI_API_KEY = "ai.openai.api_key";
    public static final String OPENAI_MODEL = "ai.openai.model";
    public static final String ANTHROPIC_API_KEY = "ai.anthropic.api_key";
    public static final String ANTHROPIC_MODEL = "ai.anthropic.model";
    public static final String SLACK_WEBHOOK_URL = "slack.webhook_url";
    public static final String SLACK_ENABLED = "slack.enabled";

    // ── Polling keys ──
    public static final String POLL_INTERVAL_MS = "polling.interval_ms";
    public static final String SLOW_THRESHOLD_MS = "polling.slow_threshold_ms";

    private final AppSettingRepository settingsRepo;

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/optiq_store}")
    private String defaultInternalUrl;
    @Value("${spring.datasource.username:optiq_store}")
    private String defaultInternalUser;
    @Value("${spring.datasource.password:changeme}")
    private String defaultInternalPassword;
    @Value("${optiq.ai.provider:openai-compatible}")
    private String defaultAiProvider;
    @Value("${optiq.slack.webhook-url:}")
    private String defaultSlackUrl;
    @Value("${optiq.slack.enabled:false}")
    private String defaultSlackEnabled;
    @Value("${optiq.polling.interval-ms:300000}")
    private String defaultPollInterval;
    @Value("${optiq.polling.slow-query-threshold-ms:500}")
    private String defaultSlowThreshold;

    public SettingsService(AppSettingRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    @PostConstruct
    public void initDefaults() {
        // Database
        seedIfMissing(DB_TYPE, "postgresql", "Database type: postgresql, mysql, mariadb, sqlite, sqlserver, clickhouse", false);
        seedIfMissing(DB_HOST, "localhost", "Database host", false);
        seedIfMissing(DB_PORT, "5432", "Database port", false);
        seedIfMissing(DB_NAME, "optiq_target", "Database name to monitor", false);
        seedIfMissing(DB_USERNAME, "optiq_monitor", "Username with read access", false);
        seedIfMissing(DB_PASSWORD, "", "Password for the target database user", true);
        seedIfMissing(DB_JDBC_URL, "", "JDBC URL override (leave empty to auto-build)", false);
        seedIfMissing(DB_URL, "", "Legacy JDBC URL", false);

        // AI
        seedIfMissing(AI_PROVIDER, defaultAiProvider, "Provider type: openai-compatible or anthropic", false);
        seedIfMissing(AI_BASE_URL, "", "API Base URL", false);
        seedIfMissing(AI_API_KEY, "", "API Key (empty for local LLMs)", true);
        seedIfMissing(AI_MODEL, "gpt-4o", "Model name", false);
        seedIfMissing(OPENAI_API_KEY, "", "Legacy OpenAI API key", true);
        seedIfMissing(OPENAI_MODEL, "gpt-4o", "Legacy OpenAI model", false);
        seedIfMissing(ANTHROPIC_API_KEY, "", "Legacy Anthropic API key", true);
        seedIfMissing(ANTHROPIC_MODEL, "claude-sonnet-4-20250514", "Legacy Anthropic model", false);

        // Notifications (generic)
        seedIfMissing(NOTIFY_ENABLED, defaultSlackEnabled, "Enable notifications (true/false)", false);
        seedIfMissing(NOTIFY_TYPE, "slack", "Notification provider: slack, discord, teams, generic", false);
        seedIfMissing(NOTIFY_WEBHOOK_URL, defaultSlackUrl, "Webhook URL for notifications", true);

        // Legacy
        seedIfMissing(SLACK_WEBHOOK_URL, defaultSlackUrl, "Legacy Slack webhook URL", true);
        seedIfMissing(SLACK_ENABLED, defaultSlackEnabled, "Legacy Slack enabled", false);

        // Polling
        seedIfMissing(POLL_INTERVAL_MS, defaultPollInterval, "Polling interval in milliseconds", false);
        seedIfMissing(SLOW_THRESHOLD_MS, defaultSlowThreshold, "Slow query threshold in milliseconds", false);

        log.info("Settings initialized with {} entries", settingsRepo.count());
    }

    private void seedIfMissing(String key, String value, String description, boolean sensitive) {
        if (settingsRepo.existsById(key)) return;
        settingsRepo.save(new AppSetting(key, value != null ? value : "", description, sensitive));
    }

    // ── Getters ──

    public String get(String key) {
        return settingsRepo.findById(key).map(AppSetting::getValue).orElse("");
    }

    // Database
    public String getDbType() { return get(DB_TYPE); }
    public String getDbHost() { return get(DB_HOST); }
    public String getDbPort() { return get(DB_PORT); }
    public String getDbName() { return get(DB_NAME); }
    public String getDbUsername() { return get(DB_USERNAME); }
    public String getDbPassword() { return get(DB_PASSWORD); }
    public String getDbJdbcUrl() { return get(DB_JDBC_URL); }

    public String getDbUrl() {
        String override = get(DB_JDBC_URL);
        if (override != null && !override.isBlank()) return override;
        String legacy = get(DB_URL);
        if (legacy != null && !legacy.isBlank()) return legacy;

        String type = getDbType();
        String host = getDbHost();
        String port = getDbPort();
        String name = getDbName();

        return switch (type.toLowerCase()) {
            case "postgresql", "postgres" -> "jdbc:postgresql://%s:%s/%s".formatted(host, port, name);
            case "mysql" -> "jdbc:mysql://%s:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC".formatted(host, port, name);
            case "mariadb" -> "jdbc:mariadb://%s:%s/%s".formatted(host, port, name);
            case "sqlserver", "mssql" -> "jdbc:sqlserver://%s:%s;databaseName=%s;encrypt=true;trustServerCertificate=true".formatted(host, port, name);
            case "clickhouse" -> "jdbc:clickhouse://%s:%s/%s".formatted(host, port, name);
            case "sqlite" -> "jdbc:sqlite:" + name;
            default -> "jdbc:postgresql://%s:%s/%s".formatted(host, port, name);
        };
    }

    public String getJdbcDriverClass() {
        return switch (getDbType().toLowerCase()) {
            case "postgresql", "postgres" -> "org.postgresql.Driver";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "mariadb" -> "org.mariadb.jdbc.Driver";
            case "sqlserver", "mssql" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "clickhouse" -> "com.clickhouse.jdbc.ClickHouseDriver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> "org.postgresql.Driver";
        };
    }

    public String getVersionQuery() {
        return switch (getDbType().toLowerCase()) {
            case "postgresql", "postgres" -> "SELECT version()";
            case "mysql", "mariadb" -> "SELECT version()";
            case "clickhouse" -> "SELECT version()";
            case "sqlserver", "mssql" -> "SELECT @@VERSION";
            case "sqlite" -> "SELECT sqlite_version()";
            default -> "SELECT version()";
        };
    }

    // AI
    public String getAiProvider() { return get(AI_PROVIDER); }
    public String getAiBaseUrl() { return get(AI_BASE_URL); }
    public String getAiApiKey() { return get(AI_API_KEY); }
    public String getAiModel() { return get(AI_MODEL); }
    public String getOpenaiApiKey() { return get(AI_API_KEY); }
    public String getOpenaiModel() { return get(AI_MODEL); }
    public String getAnthropicApiKey() { return get(AI_API_KEY); }
    public String getAnthropicModel() { return get(AI_MODEL); }

    // Notifications (generic)
    public boolean isNotifyEnabled() { return Boolean.parseBoolean(get(NOTIFY_ENABLED)); }
    public String getNotifyType() { return get(NOTIFY_TYPE); }
    public String getNotifyWebhookUrl() { return get(NOTIFY_WEBHOOK_URL); }

    // Legacy slack getters (backward compat)
    public String getSlackWebhookUrl() { return getNotifyWebhookUrl(); }
    public boolean isSlackEnabled() { return isNotifyEnabled(); }

    // Polling
    public long getPollIntervalMs() { return Long.parseLong(get(POLL_INTERVAL_MS)); }
    public double getSlowThresholdMs() { return Double.parseDouble(get(SLOW_THRESHOLD_MS)); }

    // ── Save ──

    public AppSetting save(String key, String value) {
        AppSetting setting = settingsRepo.findById(key)
            .orElse(new AppSetting(key, value, "", false));
        setting.setValue(value);
        setting.markUpdated();
        return settingsRepo.save(setting);
    }

    public Map<String, String> saveAll(Map<String, String> updates) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : updates.entrySet()) {
            save(entry.getKey(), entry.getValue());
            result.put(entry.getKey(), entry.getValue());
        }
        log.info("Updated {} settings", updates.size());
        return result;
    }

    // ── List grouped ──

    public Map<String, List<Map<String, Object>>> listGrouped() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        grouped.put("database", listByPrefix("db."));
        grouped.put("ai", listByPrefix("ai."));
        grouped.put("notifications", listByPrefix("notify."));
        grouped.put("slack", listByPrefix("slack."));
        grouped.put("polling", listByPrefix("polling."));
        return grouped;
    }

    private List<Map<String, Object>> listByPrefix(String prefix) {
        return settingsRepo.findAll().stream()
            .filter(s -> s.getKey().startsWith(prefix))
            .map(s -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("key", s.getKey());
                map.put("value", s.isSensitive() ? maskValue(s.getValue()) : s.getValue());
                map.put("description", s.getDescription());
                map.put("sensitive", s.isSensitive());
                return map;
            })
            .toList();
    }

    private String maskValue(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() <= 8) return "••••••••";
        return value.substring(0, 4) + "•••" + value.substring(value.length() - 4);
    }
}
