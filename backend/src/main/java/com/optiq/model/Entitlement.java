package com.optiq.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What an organization is currently allowed to do with AI analysis.
 *
 * Not a JPA entity: this lives in Firestore, where the customer can read it but
 * only the control plane can write it. Keeping it a plain value type means the
 * enforcement logic has no opinion about where it is stored.
 */
public record Entitlement(
    String orgId,
    AnalysisMode mode,
    int analysesUsed,
    int analysesLimit,
    Instant expiresAt,
    String grantedBy,
    Instant grantedAt,
    String note,
    Instant updatedAt
) {

    /** Field names, kept in one place so the document shape is defined once. */
    public static final String F_MODE = "mode";
    public static final String F_USED = "analysesUsed";
    public static final String F_LIMIT = "analysesLimit";
    public static final String F_EXPIRES = "expiresAt";
    public static final String F_GRANTED_BY = "grantedBy";
    public static final String F_GRANTED_AT = "grantedAt";
    public static final String F_NOTE = "note";
    public static final String F_UPDATED = "updatedAt";

    /**
     * The default for any installation with no entitlement document: the
     * customer brings their own key, so there is nothing to meter.
     */
    public static Entitlement bringYourOwnKey(String orgId) {
        return new Entitlement(orgId, AnalysisMode.BYO, 0, 0, null, null, null, null, null);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isExhausted() {
        return analysesLimit > 0 && analysesUsed >= analysesLimit;
    }

    public int analysesRemaining() {
        return Math.max(0, analysesLimit - analysesUsed);
    }

    /** Whole days left, or -1 when the grant has no expiry. */
    public long daysRemaining(Instant now) {
        if (expiresAt == null) return -1;
        long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
        if (seconds <= 0) return 0;
        // Round up: a grant made seconds ago has 7 days left, not 6.
        return (seconds + 86_399) / 86_400;
    }

    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new HashMap<>();
        doc.put(F_MODE, mode.name());
        doc.put(F_USED, analysesUsed);
        doc.put(F_LIMIT, analysesLimit);
        doc.put(F_EXPIRES, expiresAt);
        doc.put(F_GRANTED_BY, grantedBy);
        doc.put(F_GRANTED_AT, grantedAt);
        doc.put(F_NOTE, note);
        doc.put(F_UPDATED, Instant.now());
        return doc;
    }
}
