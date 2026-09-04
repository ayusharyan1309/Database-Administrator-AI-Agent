package com.optiq.service;

import com.optiq.config.TargetDatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Reads slow queries from pg_stat_statements, extracts table schemas, and runs EXPLAIN.
 * Uses TargetDatabaseConfig for dynamic connection — supports live DB switching from settings.
 */
@Service
public class PostgresMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PostgresMonitorService.class);

    private final TargetDatabaseConfig targetDbConfig;
    private final SettingsService settingsService;

    public PostgresMonitorService(TargetDatabaseConfig targetDbConfig, SettingsService settingsService) {
        this.targetDbConfig = targetDbConfig;
        this.settingsService = settingsService;
    }

    /** Get the current JdbcTemplate (reads from SettingsService dynamically). */
    private JdbcTemplate getDb() {
        return targetDbConfig.getJdbcTemplate(settingsService);
    }

    /**
     * Reinitialize the target database connection (called after settings change).
     */
    public void reinitializeConnection() {
        targetDbConfig.reinitialize(settingsService);
        log.info("Target database connection reinitialized");
    }

    /**
     * Poll pg_stat_statements for queries slower than thresholdMs.
     */
    public List<Map<String, Object>> fetchSlowQueries(double thresholdMs, int limit) {
        String sql = """
            SELECT
                queryid,
                query,
                calls,
                mean_exec_time,
                total_exec_time,
                rows
            FROM pg_stat_statements
            WHERE mean_exec_time > ?
              AND query NOT LIKE '%%pg_stat_statements%%'
              AND query NOT LIKE 'SET %%'
              AND query NOT LIKE 'SHOW %%'
              AND query NOT LIKE 'BEGIN%%'
              AND query NOT LIKE 'COMMIT%%'
              AND query NOT LIKE 'ROLLBACK%%'
            ORDER BY mean_exec_time DESC
            LIMIT ?
        """;

        try {
            return getDb().queryForList(sql, thresholdMs, limit);
        } catch (Exception e) {
            log.error("Failed to query pg_stat_statements: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract the schema (columns + types + indexes) for a given table.
     */
    public String extractTableSchema(String tableName) {
        try {
            var db = getDb();

            String columnsSql = """
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = ?
                ORDER BY ordinal_position
            """;
            var columns = db.queryForList(columnsSql, tableName);

            String indexSql = """
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE tablename = ?
            """;
            var indexes = db.queryForList(indexSql, tableName);

            String countSql = "SELECT reltuples::bigint AS estimate FROM pg_class WHERE relname = ?";
            var countResult = db.queryForMap(countSql, tableName);
            long estimatedRows = countResult != null ? (Long) countResult.getOrDefault("estimate", 0L) : 0L;

            StringBuilder sb = new StringBuilder();
            sb.append("TABLE: ").append(tableName).append("\n");
            sb.append("Estimated rows: ").append(estimatedRows).append("\n\n");

            sb.append("COLUMNS:\n");
            for (var col : columns) {
                sb.append("  - ")
                  .append(col.get("column_name"))
                  .append(" ")
                  .append(col.get("data_type"))
                  .append(col.get("is_nullable").equals("YES") ? " NULL" : " NOT NULL");
                if (col.get("column_default") != null) {
                    sb.append(" DEFAULT ").append(col.get("column_default"));
                }
                sb.append("\n");
            }

            sb.append("\nINDEXES:\n");
            if (indexes.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (var idx : indexes) {
                    sb.append("  - ").append(idx.get("indexname")).append(": ").append(idx.get("indexdef")).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Could not extract schema for table '{}': {}", tableName, e.getMessage());
            return "Schema unavailable for table: " + tableName + "\n";
        }
    }

    /**
     * Run EXPLAIN ANALYZE on a query and return the plan as text.
     */
    public String explainQuery(String query) {
        try {
            String explainSql = "EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS, FORMAT TEXT) " + query;
            var results = getDb().queryForList(explainSql);
            StringBuilder sb = new StringBuilder();
            for (var row : results) {
                sb.append(row.values().iterator().next()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("EXPLAIN ANALYZE failed: {}", e.getMessage());
            return "EXPLAIN failed: " + e.getMessage();
        }
    }

    /**
     * Extract table names from a SQL query (simple heuristic).
     */
    public Set<String> extractTableNames(String query) {
        Set<String> tables = new LinkedHashSet<>();
        String upper = query.toUpperCase();

        String[] patterns = {
            "FROM\\s+(\\w+)",
            "JOIN\\s+(\\w+)",
            "INTO\\s+(\\w+)",
            "UPDATE\\s+(\\w+)"
        };

        for (String pattern : patterns) {
            var matcher = java.util.regex.Pattern.compile(pattern).matcher(upper);
            while (matcher.find()) {
                String table = matcher.group(1).toLowerCase();
                if (!isSqlKeyword(table)) {
                    tables.add(table);
                }
            }
        }

        return tables;
    }

    private boolean isSqlKeyword(String word) {
        Set<String> keywords = Set.of(
            "select", "where", "and", "or", "not", "null", "true", "false",
            "in", "on", "as", "is", "like", "between", "exists", "limit",
            "order", "group", "having", "offset", "union", "all", "distinct",
            "insert", "values", "set", "delete", "from", "join", "left",
            "right", "inner", "outer", "cross", "full", "lateral",
            "case", "when", "then", "else", "end", "count", "sum", "avg",
            "min", "max", "coalesce", "cast", "extract", "current_timestamp"
        );
        return keywords.contains(word);
    }

    /**
     * Build a normalized fingerprint for deduplication.
     */
    public String buildFingerprint(String query) {
        return query
            .toLowerCase()
            .replaceAll("'[^']*'", "?")
            .replaceAll("\\d+", "?")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
