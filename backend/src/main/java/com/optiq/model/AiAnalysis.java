package com.optiq.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/**
 * Stores the AI's structured analysis of a slow query.
 * Maps to the required JSON output format from the PRD.
 */
@Entity
@Table(name = "ai_analyses")
public class AiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slow_query_id", nullable = false, unique = true)
    private SlowQuery slowQuery;

    /** 2-sentence explanation of why the query is slow. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String rootCause;

    /** The exact SQL command to fix the issue (e.g., CREATE INDEX). */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String suggestedSql;

    /** AI's confidence score (1-100). */
    @Column(nullable = false)
    private int confidenceScore;

    /** Risk level of applying the suggested fix. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel riskLevel;

    /** The AI model used for this analysis. */
    @Column(nullable = false)
    private String modelUsed;

    /** The full prompt sent to the AI (for audit/debugging). */
    @Column(columnDefinition = "TEXT")
    private String promptUsed;

    /** Raw AI response before parsing (for debugging). */
    @Column(columnDefinition = "TEXT")
    private String rawResponse;

    @Column(nullable = false)
    private Instant analyzedAt = Instant.now();

    // --- Constructors ---

    protected AiAnalysis() {}

    public AiAnalysis(SlowQuery slowQuery, String rootCause, String suggestedSql,
                      int confidenceScore, RiskLevel riskLevel, String modelUsed) {
        this.slowQuery = slowQuery;
        this.rootCause = rootCause;
        this.suggestedSql = suggestedSql;
        this.confidenceScore = confidenceScore;
        this.riskLevel = riskLevel;
        this.modelUsed = modelUsed;
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }

    public SlowQuery getSlowQuery() { return slowQuery; }
    public void setSlowQuery(SlowQuery slowQuery) { this.slowQuery = slowQuery; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getSuggestedSql() { return suggestedSql; }
    public void setSuggestedSql(String suggestedSql) { this.suggestedSql = suggestedSql; }

    public int getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(int confidenceScore) { this.confidenceScore = confidenceScore; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public String getPromptUsed() { return promptUsed; }
    public void setPromptUsed(String promptUsed) { this.promptUsed = promptUsed; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AiAnalysis that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
