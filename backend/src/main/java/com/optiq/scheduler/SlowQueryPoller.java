package com.optiq.scheduler;

import com.optiq.config.TargetDatabaseConfig;
import com.optiq.model.SlowQuery;
import com.optiq.service.AnalysisOrchestrator;
import com.optiq.service.PostgresMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Periodically polls pg_stat_statements for slow queries.
 *
 * The @Scheduled annotation drives the daemon behavior described in the PRD:
 * "Must run a @Scheduled job every X minutes to query pg_stat_statements
 * and filter by mean_exec_time."
 */
@Component
public class SlowQueryPoller {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryPoller.class);

    private final PostgresMonitorService monitorService;
    private final AnalysisOrchestrator orchestrator;

    @Value("${optiq.polling.slow-query-threshold-ms:500}")
    private double slowThresholdMs;

    @Value("${optiq.polling.max-queries-per-poll:10}")
    private int maxQueriesPerPoll;

    public SlowQueryPoller(PostgresMonitorService monitorService, AnalysisOrchestrator orchestrator) {
        this.monitorService = monitorService;
        this.orchestrator = orchestrator;
    }

    /**
     * Main polling loop. Runs at a configurable interval (default: 5 minutes).
     */
    @Scheduled(fixedDelayString = "${optiq.polling.interval-ms:300000}",
               initialDelayString = "${optiq.polling.interval-ms:300000}")
    public void pollForSlowQueries() {
        log.info("Polling pg_stat_statements (threshold: {}ms, max: {})",
            slowThresholdMs, maxQueriesPerPoll);

        try {
            List<Map<String, Object>> slowQueries = monitorService.fetchSlowQueries(
                slowThresholdMs, maxQueriesPerPoll);

            if (slowQueries.isEmpty()) {
                log.debug("No slow queries detected in this poll cycle");
                return;
            }

            log.info("Found {} slow queries, starting analysis pipeline", slowQueries.size());

            for (Map<String, Object> row : slowQueries) {
                try {
                    String query = (String) row.get("query");
                    double meanExecTime = ((Number) row.get("mean_exec_time")).doubleValue();
                    long calls = ((Number) row.get("calls")).longValue();
                    double totalTime = ((Number) row.get("total_exec_time")).doubleValue();

                    orchestrator.analyzeQuery(query, meanExecTime, calls, totalTime);

                } catch (Exception e) {
                    log.error("Failed to analyze individual query: {}", e.getMessage());
                }
            }

            log.info("Poll cycle complete. Processed {} slow queries", slowQueries.size());

        } catch (Exception e) {
            log.error("Poll cycle failed: {}", e.getMessage(), e);
        }
    }
}
