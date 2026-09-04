package com.optiq.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Structured JSON response from the AI diagnostic engine.
 * This is the strict output format enforced via system prompt.
 */
public record AnalysisResult(
    @JsonProperty("root_cause") String rootCause,
    @JsonProperty("suggested_sql") String suggestedSql,
    @JsonProperty("confidence_score") int confidenceScore,
    @JsonProperty("risk_level") RiskLevel riskLevel
) {
    public AnalysisResult {
        if (confidenceScore < 1 || confidenceScore > 100) {
            throw new IllegalArgumentException("confidence_score must be between 1 and 100, got: " + confidenceScore);
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("risk_level must not be null");
        }
    }
}
