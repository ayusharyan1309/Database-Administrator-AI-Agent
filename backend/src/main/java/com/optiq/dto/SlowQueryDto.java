package com.optiq.dto;

import com.optiq.model.AnalysisStatus;
import com.optiq.model.RiskLevel;
import com.optiq.model.SlowQuery;
import java.time.Instant;

/**
 * API response DTO for slow queries.
 */
public record SlowQueryDto(
    Long id,
    String queryFingerprint,
    String rawQuery,
    double meanExecTimeMs,
    long callCount,
    double totalExecTimeMs,
    String referencedTables,
    String explainPlan,
    String schemaContext,
    AnalysisStatus status,
    int detectionCount,
    Instant detectedAt,
    Instant updatedAt,
    // Nested analysis fields (null if no analysis yet)
    String rootCause,
    String suggestedSql,
    Integer confidenceScore,
    RiskLevel riskLevel,
    String modelUsed
) {
    public static SlowQueryDto from(SlowQuery sq) {
        var analysis = sq.getAnalysis();
        return new SlowQueryDto(
            sq.getId(),
            sq.getQueryFingerprint(),
            sq.getRawQuery(),
            sq.getMeanExecTimeMs(),
            sq.getCallCount(),
            sq.getTotalExecTimeMs(),
            sq.getReferencedTables(),
            sq.getExplainPlan(),
            sq.getSchemaContext(),
            sq.getStatus(),
            sq.getDetectionCount(),
            sq.getDetectedAt(),
            sq.getUpdatedAt(),
            analysis != null ? analysis.getRootCause() : null,
            analysis != null ? analysis.getSuggestedSql() : null,
            analysis != null ? analysis.getConfidenceScore() : null,
            analysis != null ? analysis.getRiskLevel() : null,
            analysis != null ? analysis.getModelUsed() : null
        );
    }
}
