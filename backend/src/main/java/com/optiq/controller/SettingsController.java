package com.optiq.controller;

import com.optiq.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for application settings.
 * Test endpoints work with ANY database and ANY AI provider.
 */
@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("settings", settingsService.listGrouped());
        response.put("aiProvider", settingsService.getAiProvider());
        response.put("dbType", settingsService.getDbType());
        response.put("builtUrl", settingsService.getDbUrl());
        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<Map<String, String>> updateSettings(@RequestBody Map<String, String> updates) {
        return ResponseEntity.ok(settingsService.saveAll(updates));
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, String>> updateSetting(
            @PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.getOrDefault("value", "");
        settingsService.save(key, value);
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    /**
     * Test ANY database connection.
     * Accepts: type, host, port, name, username, password (all optional — falls back to stored settings).
     * Auto-detects the JDBC driver class based on db.type.
     * Returns: version string + connection info.
     */
    @PostMapping("/test-db")
    public ResponseEntity<Map<String, Object>> testDatabaseConnection(@RequestBody(required = false) Map<String, String> body) {
        try {
            String type = body != null ? body.getOrDefault("type", settingsService.getDbType()) : settingsService.getDbType();
            String host = body != null ? body.getOrDefault("host", settingsService.getDbHost()) : settingsService.getDbHost();
            String port = body != null ? body.getOrDefault("port", settingsService.getDbPort()) : settingsService.getDbPort();
            String name = body != null ? body.getOrDefault("name", settingsService.getDbName()) : settingsService.getDbName();
            String user = body != null ? body.getOrDefault("username", settingsService.getDbUsername()) : settingsService.getDbUsername();
            String pass = body != null ? body.getOrDefault("password", settingsService.getDbPassword()) : settingsService.getDbPassword();

            // Build JDBC URL
            String url = buildJdbcUrl(type, host, port, name);

            // Load the appropriate driver
            String driverClass = getDriverClass(type);
            Class.forName(driverClass);

            java.sql.Connection conn = null;
            try {
                conn = java.sql.DriverManager.getConnection(url, user, pass);
                var stmt = conn.createStatement();

                // Get version
                String versionQuery = getVersionQuery(type);
                var rs = stmt.executeQuery(versionQuery);
                rs.next();
                String version = rs.getString(1);

                // Get extra info based on DB type
                String extra = getExtraInfo(stmt, type);

                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connected successfully!",
                    "version", version,
                    "type", type,
                    "url", url,
                    "driver", driverClass,
                    "extra", extra != null ? extra : ""
                ));
            } finally {
                if (conn != null) conn.close();
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "...";
            return ResponseEntity.ok(Map.of("success", false, "message", "Connection failed: " + msg));
        }
    }

    /**
     * Test ANY AI provider — sends a minimal chat completion request.
     */
    @PostMapping("/test-ai")
    public ResponseEntity<Map<String, Object>> testAiConnection(@RequestBody(required = false) Map<String, String> body) {
        try {
            String providerType = body != null ? body.getOrDefault("providerType", settingsService.getAiProvider()) : settingsService.getAiProvider();
            String baseUrl = body != null ? body.getOrDefault("baseUrl", settingsService.getAiBaseUrl()) : settingsService.getAiBaseUrl();
            String apiKey = body != null ? body.getOrDefault("apiKey", settingsService.getAiApiKey()) : settingsService.getAiApiKey();
            String model = body != null ? body.getOrDefault("model", settingsService.getAiModel()) : settingsService.getAiModel();

            // ── Anthropic (different API format) ──
            if ("anthropic".equalsIgnoreCase(providerType)) {
                if (apiKey == null || apiKey.isBlank()) {
                    return ResponseEntity.ok(Map.of("success", false, "message", "Anthropic API key is required"));
                }
                String url = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "https://api.anthropic.com/v1/messages";
                var client = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"%s\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"Say hi\"}]}"
                            .formatted(model != null ? model : "claude-3-5-sonnet-20241022")))
                    .build();
                var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return ResponseEntity.ok(Map.of("success", true, "message", "Anthropic connection successful! ✓"));
                } else {
                    return ResponseEntity.ok(Map.of("success", false, "message", "Status " + resp.statusCode() + ": " + truncate(resp.body(), 200)));
                }
            }

            // ── Everything else: OpenAI-compatible protocol ──
            if (baseUrl == null || baseUrl.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "Base URL is required. Examples:\n• Ollama: http://localhost:11434/v1\n• DeepSeek: https://api.deepseek.com/v1\n• Groq: https://api.groq.com/openai/v1\n• LM Studio: http://localhost:1234/v1"));
            }

            String chatUrl = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

            var client = java.net.http.HttpClient.newHttpClient();
            var reqBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(chatUrl))
                .header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hi in 5 words\"}],\"max_tokens\":20}"
                        .formatted(model != null ? model : "default")));

            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            var resp = client.send(reqBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                String modelName = extractModelName(resp.body());
                String msg = "Connection successful! ✓";
                if (modelName != null && !modelName.isBlank()) {
                    msg += " (model: " + modelName + ")";
                }
                return ResponseEntity.ok(Map.of("success", true, "message", msg));
            } else {
                return ResponseEntity.ok(Map.of("success", false, "message", "Status " + resp.statusCode() + ": " + truncate(resp.body(), 300)));
            }
        } catch (java.net.ConnectException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Cannot connect. Is the server running?\n" + e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Test failed: " + e.getMessage()));
        }
    }

    /**
     * Test a webhook by sending a test message.
     */
    @PostMapping("/test-webhook")
    public ResponseEntity<Map<String, Object>> testWebhook(@RequestBody(required = false) Map<String, String> body) {
        try {
            String type = body != null ? body.getOrDefault("type", settingsService.getNotifyType()) : settingsService.getNotifyType();
            String webhookUrl = body != null ? body.getOrDefault("url", settingsService.getNotifyWebhookUrl()) : settingsService.getNotifyWebhookUrl();

            if (webhookUrl == null || webhookUrl.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "Webhook URL is required"));
            }

            String payload;
            if ("discord".equalsIgnoreCase(type)) {
                payload = "{\"content\": \"OptiQuery test message - if you see this, Discord is connected!\"}";
            } else if ("teams".equalsIgnoreCase(type)) {
                payload = "{\"@type\":\"MessageCard\",\"summary\":\"OptiQuery Test\",\"text\":\"OptiQuery test message - if you see this, Teams is connected!\"}";
            } else {
                payload = "{\"text\": \"OptiQuery test message - if you see this, webhook is connected!\"}";
            }

            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(webhookUrl))
                .header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build();
            var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Webhook test sent successfully! \u2713"));
            } else {
                return ResponseEntity.ok(Map.of("success", false, "message", "Status " + resp.statusCode() + ": " + resp.body()));
            }
        } catch (java.net.ConnectException e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Cannot connect to webhook URL"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Test failed: " + e.getMessage()));
        }
    }

    // ── Helpers ──

    private String buildJdbcUrl(String type, String host, String port, String name) {
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

    private String getDriverClass(String type) {
        return switch (type.toLowerCase()) {
            case "postgresql", "postgres" -> "org.postgresql.Driver";
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "mariadb" -> "org.mariadb.jdbc.Driver";
            case "sqlserver", "mssql" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "clickhouse" -> "com.clickhouse.jdbc.ClickHouseDriver";
            case "sqlite" -> "org.sqlite.JDBC";
            default -> "org.postgresql.Driver";
        };
    }

    private String getVersionQuery(String type) {
        return switch (type.toLowerCase()) {
            case "postgresql", "postgres" -> "SELECT version()";
            case "mysql", "mariadb" -> "SELECT version()";
            case "clickhouse" -> "SELECT version()";
            case "sqlserver", "mssql" -> "SELECT @@VERSION";
            case "sqlite" -> "SELECT sqlite_version()";
            default -> "SELECT version()";
        };
    }

    private String getExtraInfo(java.sql.Statement stmt, String type) {
        try {
            return switch (type.toLowerCase()) {
                case "postgresql", "postgres" -> {
                    var rs = stmt.executeQuery("SELECT count(*) FROM pg_stat_statements");
                    rs.next();
                    yield "pg_stat_statements: " + rs.getLong(1) + " statements";
                }
                case "mysql", "mariadb" -> {
                    var rs = stmt.executeQuery("SELECT count(*) FROM information_schema.processlist");
                    rs.next();
                    yield "Active connections: " + rs.getLong(1);
                }
                case "clickhouse" -> {
                    var rs = stmt.executeQuery("SELECT count() FROM system.tables");
                    rs.next();
                    yield "Tables: " + rs.getLong(1);
                }
                case "sqlite" -> {
                    var rs = stmt.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'");
                    rs.next();
                    yield "Tables: " + rs.getLong(1);
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private String extractModelName(String json) {
        try {
            int idx = json.indexOf("\"model\"");
            if (idx < 0) return null;
            int colonIdx = json.indexOf(":", idx);
            int startQuote = json.indexOf("\"", colonIdx + 1);
            int endQuote = json.indexOf("\"", startQuote + 1);
            return json.substring(startQuote + 1, endQuote);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
