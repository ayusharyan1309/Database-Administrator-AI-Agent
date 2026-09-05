package com.optiq.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a slow query detected from pg_stat_statements.
 * Groups identical queries to prevent alert fatigue (idempotency requirement).
 */
@Entity
@Table(name = "slow_queries", indexes = {
    @Index(name = "idx_sq_fingerprint", columnList = "queryFingerprint"),
    @Index(name = "idx_sq_status", columnList = "status"),
    @Index(name = "idx_sq_detected_at", columnList = "detectedAt")
})
public class SlowQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Normalized fingerprint of the query (lowercased, constants replaced with placeholders).
     * Used to group identical slow queries and prevent duplicate alerts.
     */
    @Column(nullable = false)
    private String queryFingerprint;

    /** Raw SQL as seen in pg_stat_statements. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawQuery;

    /** Mean execution time in milliseconds from pg_stat_statements. */
    @Column(nullable = false)
    private double meanExecTimeMs;

    /** Total number of times this query was executed. */
    @Column(nullable = false)
    private long callCount;

    /** Total time spent on this query across all calls (ms). */
    @Column(nullable = false)
    private double totalExecTimeMs;

    /** Tables referenced by this query (comma-separated). */
    @Column(length = 1024)
    private String referencedTables;

    /** The EXPLAIN ANALYZE output (populated during context gathering). */
    @Column(columnDefinition = "TEXT")
    private String explainPlan;

    /** Schema context extracted for the AI (table structures involved). */
    @Column(columnDefinition = "TEXT")
    private String schemaContext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisStatus status = AnalysisStatus.PENDING;

    /** How many times this fingerprint has been detected (incremented on re-detection). */
    @Column(nullable = false)
    private int detectionCount = 1;

    /** When this query was first detected. */
    @Column(nullable = false)
    private Instant detectedAt = Instant.now();

    /** When analysis was last updated. */
    private Instant updatedAt;

    /** Link to the AI analysis, if one exists. */
    @OneToOne(mappedBy = "slowQuery", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private AiAnalysis analysis;

    // --- Constructors ---

    protected SlowQuery() {}

    public SlowQuery(String queryFingerprint, String rawQuery, double meanExecTimeMs,
                     long callCount, double totalExecTimeMs, String referencedTables) {
        this.queryFingerprint = queryFingerprint;
        this.rawQuery = rawQuery;
        this.meanExecTimeMs = meanExecTimeMs;
        this.callCount = callCount;
        this.totalExecTimeMs = totalExecTimeMs;
        this.referencedTables = referencedTables;
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public String getQueryFingerprint() { return queryFingerprint; }
    public void setQueryFingerprint(String queryFingerprint) { this.queryFingerprint = queryFingerprint; }

    public String getRawQuery() { return rawQuery; }
    public void setRawQuery(String rawQuery) { this.rawQuery = rawQuery; }

    public double getMeanExecTimeMs() { return meanExecTimeMs; }
    public void setMeanExecTimeMs(double meanExecTimeMs) { this.meanExecTimeMs = meanExecTimeMs; }

    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }

    public double getTotalExecTimeMs() { return totalExecTimeMs; }
    public void setTotalExecTimeMs(double totalExecTimeMs) { this.totalExecTimeMs = totalExecTimeMs; }

    public String getReferencedTables() { return referencedTables; }
    public void setReferencedTables(String referencedTables) { this.referencedTables = referencedTables; }

    public String getExplainPlan() { return explainPlan; }
    public void setExplainPlan(String explainPlan) { this.explainPlan = explainPlan; }

    public String getSchemaContext() { return schemaContext; }
    public void setSchemaContext(String schemaContext) { this.schemaContext = schemaContext; }

    public AnalysisStatus getStatus() { return status; }
    public void setStatus(AnalysisStatus status) { this.status = status; }

    public int getDetectionCount() { return detectionCount; }
    public void setDetectionCount(int detectionCount) { this.detectionCount = detectionCount; }
    public void incrementDetectionCount() { this.detectionCount++; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public AiAnalysis getAnalysis() { return analysis; }
    public void setAnalysis(AiAnalysis analysis) { this.analysis = analysis; }

    // --- Helpers ---

    public void markUpdated() {
        this.updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlowQuery that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
