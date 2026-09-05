package com.optiq.model;

public enum AnalysisStatus {
    PENDING,
    ANALYZING,
    COMPLETED,
    FAILED,
    DISMISSED,
    APPLIED,
    /**
     * Detected and measured, but not analyzed: the hosted trial ran out of
     * analyses or expired. Detection keeps running, so the query still shows
     * its cost in the dashboard — only the diagnosis is missing.
     */
    QUOTA_EXCEEDED
}
