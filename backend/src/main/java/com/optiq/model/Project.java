package com.optiq.model;

import java.time.Instant;

/**
 * One monitored database.
 *
 * Connection details are deliberately absent: they belong to the agent that
 * runs inside the customer's network, never to this record. A project holds
 * the name, the polling policy, and the findings.
 */
public record Project(
    String id,
    String name,
    String environment,
    boolean enabled,
    long pollIntervalMs,
    double slowThresholdMs,
    int maxQueriesPerPoll,
    Instant createdAt,
    String createdBy
) {
    public static final String ENV_PRODUCTION = "production";
    public static final String ENV_STAGING = "staging";
    public static final String ENV_DEVELOPMENT = "development";
}
