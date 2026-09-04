package com.optiq.service;

import com.optiq.model.*;
import com.optiq.repository.SlowQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The main orchestration pipeline.
 * Coordinates: Detection → Context Gathering → AI Analysis → Notification
 *
 * This is the "Senior DBA workflow" automated end-to-end.
 */
@Service
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private final PostgresMonitorService monitorService;
    private final AiDiagnosticService aiService;
    private final NotificationService notificationService;
    private final SlowQueryRepository queryRepository;

    public AnalysisOrchestrator(PostgresMonitorService monitorService,
                                AiDiagnosticService aiService,
                                NotificationService notificationService,
                                SlowQueryRepository queryRepository) {
        this.monitorService = monitorService;
        this.aiService = aiService;
        this.notificationService = notificationService;
        this.queryRepository = queryRepository;
    }

    /**
     * Full analysis pipeline for a single slow query.
     * Called by the polling scheduler or on-demand via API.
     */
    @Transactional
    public Optional<SlowQuery> analyzeQuery(String rawQuery, double meanExecTimeMs,
                                             long callCount, double totalExecTimeMs) {
        // Step 1: Build fingerprint and check for duplicates (idempotency)
        String fingerprint = monitorService.buildFingerprint(rawQuery);

        Optional<SlowQuery> existing = queryRepository.findByQueryFingerprint(fingerprint);
        if (existing.isPresent()) {
            SlowQuery sq = existing.get();
            sq.incrementDetectionCount();
            sq.markUpdated();

            // Only re-analyze if status is DISMISSED or APPLIED (user already dealt with it)
            if (sq.getStatus() == AnalysisStatus.PENDING || sq.getStatus() == AnalysisStatus.ANALYZING) {
                log.debug("Query fingerprint already being tracked (count: {}), skipping re-analysis",
                    sq.getDetectionCount());
                queryRepository.save(sq);
                return Optional.of(sq);
            }

            // Reset status for re-analysis
            sq.setStatus(AnalysisStatus.PENDING);
            queryRepository.save(sq);
            return processSlowQuery(sq);
        }

        // Step 2: New query — extract table names and create record
        Set<String> tables = monitorService.extractTableNames(rawQuery);
        String referencedTables = String.join(",", tables);

        SlowQuery sq = new SlowQuery(fingerprint, rawQuery, meanExecTimeMs,
                                      callCount, totalExecTimeMs, referencedTables);
        sq = queryRepository.save(sq);

        return processSlowQuery(sq);
    }

    /**
     * Process a slow query through the full pipeline.
     */
    private Optional<SlowQuery> processSlowQuery(SlowQuery sq) {
        try {
            // Step 3: Gather context — extract schemas and run EXPLAIN
            sq.setStatus(AnalysisStatus.ANALYZING);
            sq.markUpdated();
            sq = queryRepository.save(sq);

            String schemaContext = gatherSchemaContext(sq.getReferencedTables());
            sq.setSchemaContext(schemaContext);

            String explainPlan = monitorService.explainQuery(sq.getRawQuery());
            sq.setExplainPlan(explainPlan);

            sq = queryRepository.save(sq);

            // Step 4: AI Diagnosis
            AnalysisResult result = aiService.analyzeQuery(
                sq.getRawQuery(), schemaContext, explainPlan);

            // Step 5: Store analysis
            AiAnalysis analysis = new AiAnalysis(
                sq, result.rootCause(), result.suggestedSql(),
                result.confidenceScore(), result.riskLevel(),
                aiService.getModelName()
            );
            analysis.setPromptUsed("System prompt + user prompt (see AiDiagnosticService)");
            analysis.setRawResponse(result.toString());

            sq.setAnalysis(analysis);
            sq.setStatus(AnalysisStatus.COMPLETED);
            sq.markUpdated();
            sq = queryRepository.save(sq);

            log.info("Analysis complete for query #{}: risk={}, confidence={}%",
                sq.getId(), result.riskLevel(), result.confidenceScore());

            // Step 6: Notify
            notificationService.notifySlowQuery(sq, analysis);

            return Optional.of(sq);

        } catch (Exception e) {
            log.error("Analysis pipeline failed for query: {}", e.getMessage(), e);
            sq.setStatus(AnalysisStatus.FAILED);
            sq.markUpdated();
            queryRepository.save(sq);
            return Optional.of(sq);
        }
    }

    /**
     * Build schema context by extracting info for all referenced tables.
     */
    private String gatherSchemaContext(String referencedTables) {
        if (referencedTables == null || referencedTables.isBlank()) {
            return "No table information available.\n";
        }

        StringBuilder sb = new StringBuilder();
        for (String table : referencedTables.split(",")) {
            String trimmed = table.trim();
            if (!trimmed.isEmpty()) {
                sb.append(monitorService.extractTableSchema(trimmed));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Manually trigger analysis for a specific slow query (from the API).
     */
    @Transactional
    public Optional<SlowQuery> reanalyzeQuery(Long queryId) {
        return queryRepository.findById(queryId).flatMap(sq -> {
            sq.setStatus(AnalysisStatus.PENDING);
            sq.markUpdated();
            queryRepository.save(sq);
            return processSlowQuery(sq);
        });
    }

    /**
     * Dismiss a slow query (user reviewed and decided to ignore it).
     */
    @Transactional
    public Optional<SlowQuery> dismissQuery(Long queryId) {
        return queryRepository.findById(queryId).map(sq -> {
            sq.setStatus(AnalysisStatus.DISMISSED);
            sq.markUpdated();
            return queryRepository.save(sq);
        });
    }
}
