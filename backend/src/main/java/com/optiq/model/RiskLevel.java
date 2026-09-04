package com.optiq.model;

public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public boolean isMoreSevereThan(RiskLevel other) {
        return this.ordinal() > other.ordinal();
    }
}
