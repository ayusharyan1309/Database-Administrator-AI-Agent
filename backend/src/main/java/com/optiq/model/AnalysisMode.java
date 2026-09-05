package com.optiq.model;

/** How an organization pays for AI analysis. */
public enum AnalysisMode {
    /** Customer's own provider and key. Nothing to meter — the default. */
    BYO,
    /** OptiQuery's key, capped by an analysis count and an expiry date. */
    HOSTED_TRIAL,
    /** OptiQuery's key, uncapped. */
    PAID
}
