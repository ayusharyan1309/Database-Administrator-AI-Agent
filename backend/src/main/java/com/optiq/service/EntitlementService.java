package com.optiq.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.optiq.config.FirebaseConfig;
import com.optiq.model.AnalysisMode;
import com.optiq.model.Entitlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether an AI analysis may run, and meters it when OptiQuery is paying.
 *
 * Stored in Firestore at {@code entitlements/{orgId}} — a path that moves under
 * {@code organizations/{orgId}/private/entitlement} unchanged once organizations
 * exist. Customers may read it; only this service writes it.
 *
 * Two rules shape the implementation:
 *
 *  - Reserve before spending. The counter is incremented inside a transaction
 *    *before* the provider call, so ten concurrent analyses cannot all read
 *    "99 of 100 used" and each decide they are the last one. A failed provider
 *    call refunds the reservation.
 *
 *  - Fail open, never closed. If Firestore is unreachable or unconfigured, the
 *    customer's own key keeps working. A billing system that can take the
 *    product down when it has an outage is worse than one that occasionally
 *    lets an analysis through.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private static final String COLLECTION = "entitlements";

    private final FirebaseConfig firebase;

    /** Single-tenant for now; becomes the real organization id when orgs land. */
    @Value("${optiq.org-id:default}")
    private String orgId;

    public EntitlementService(FirebaseConfig firebase) {
        this.firebase = firebase;
    }

    /** Why an analysis was allowed or refused, for logging and for the UI. */
    public enum Decision {
        /** Customer's own key — not metered. */
        ALLOWED_BYO,
        /** OptiQuery is paying and a unit was reserved. */
        ALLOWED_RESERVED,
        /** Firestore is unavailable; allowed rather than blocking the product. */
        ALLOWED_UNMETERED,
        /** The trial used all of its analyses. */
        DENIED_EXHAUSTED,
        /** The trial's expiry date has passed. */
        DENIED_EXPIRED;

        public boolean allowed() {
            return this == ALLOWED_BYO || this == ALLOWED_RESERVED || this == ALLOWED_UNMETERED;
        }

        /** True when a refund is owed if the analysis then fails. */
        public boolean consumedQuota() {
            return this == ALLOWED_RESERVED;
        }

        public String userMessage() {
            return switch (this) {
                case DENIED_EXHAUSTED -> "The trial's included analyses have all been used.";
                case DENIED_EXPIRED -> "The trial period has ended.";
                default -> "";
            };
        }
    }

    // ── Enforcement ────────────────────────────────────────────────────────

    /**
     * Claim one analysis. Call immediately before the provider request, and
     * {@link #refund()} if that request fails.
     */
    public Decision reserve() {
        Optional<Firestore> db = firebase.firestore();
        if (db.isEmpty()) return Decision.ALLOWED_UNMETERED;

        DocumentReference ref = db.get().collection(COLLECTION).document(orgId);
        Instant now = Instant.now();

        try {
            return db.get().runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();

                // No document means nobody granted a trial: the customer is on
                // their own key, which this service does not meter.
                if (!snap.exists()) return Decision.ALLOWED_BYO;

                Entitlement e = fromSnapshot(snap);
                if (e.mode() == AnalysisMode.BYO) return Decision.ALLOWED_BYO;
                if (e.mode() == AnalysisMode.PAID) return Decision.ALLOWED_RESERVED;

                if (e.isExpired(now)) return Decision.DENIED_EXPIRED;
                if (e.isExhausted()) return Decision.DENIED_EXHAUSTED;

                tx.update(ref, Map.of(
                    Entitlement.F_USED, e.analysesUsed() + 1,
                    Entitlement.F_UPDATED, Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), 0)
                ));
                return Decision.ALLOWED_RESERVED;
            }).get();

        } catch (Exception ex) {
            log.warn("Entitlement check failed, allowing the analysis through: {}", ex.getMessage());
            return Decision.ALLOWED_UNMETERED;
        }
    }

    /** Hand back a reserved analysis after a failed provider call. */
    public void refund() {
        Optional<Firestore> db = firebase.firestore();
        if (db.isEmpty()) return;

        DocumentReference ref = db.get().collection(COLLECTION).document(orgId);
        try {
            db.get().runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(ref).get();
                if (!snap.exists()) return null;
                Entitlement e = fromSnapshot(snap);
                if (e.mode() != AnalysisMode.HOSTED_TRIAL || e.analysesUsed() <= 0) return null;
                tx.update(ref, Entitlement.F_USED, e.analysesUsed() - 1);
                return null;
            }).get();
        } catch (Exception ex) {
            log.warn("Could not refund a reserved analysis: {}", ex.getMessage());
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** Current entitlement, or a BYO placeholder when none is stored. */
    public Entitlement current() {
        Optional<Firestore> db = firebase.firestore();
        if (db.isEmpty()) return Entitlement.bringYourOwnKey(orgId);
        try {
            DocumentSnapshot snap = db.get().collection(COLLECTION).document(orgId).get().get();
            return snap.exists() ? fromSnapshot(snap) : Entitlement.bringYourOwnKey(orgId);
        } catch (Exception e) {
            log.warn("Could not read the entitlement: {}", e.getMessage());
            return Entitlement.bringYourOwnKey(orgId);
        }
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    /** Start or replace a hosted trial. */
    public Entitlement grant(int analyses, int days, String grantedBy, String note) {
        Instant now = Instant.now();
        Entitlement granted = new Entitlement(
            orgId, AnalysisMode.HOSTED_TRIAL, 0, analyses,
            days > 0 ? now.plus(days, ChronoUnit.DAYS) : null,
            grantedBy, now, note, now
        );
        write(granted.toDocument());
        log.info("Granted a hosted trial to {}: {} analyses, {} days, by {}",
            orgId, analyses, days > 0 ? days : "no", grantedBy);
        return granted;
    }

    /** Push the expiry out and optionally top up the analysis count. */
    public Entitlement extend(int extraDays, int extraAnalyses, String grantedBy) {
        Entitlement e = current();
        Instant now = Instant.now();
        Instant base = (e.expiresAt() == null || e.expiresAt().isBefore(now)) ? now : e.expiresAt();

        Entitlement extended = new Entitlement(
            orgId, AnalysisMode.HOSTED_TRIAL, e.analysesUsed(),
            e.analysesLimit() + Math.max(0, extraAnalyses),
            extraDays > 0 ? base.plus(extraDays, ChronoUnit.DAYS) : e.expiresAt(),
            grantedBy, e.grantedAt() != null ? e.grantedAt() : now, e.note(), now
        );
        write(extended.toDocument());
        log.info("Extended the trial for {}: +{} days, +{} analyses", orgId, extraDays, extraAnalyses);
        return extended;
    }

    /** End the hosted trial. The customer's own key still works. */
    public Entitlement revoke(String revokedBy) {
        Entitlement e = current();
        Entitlement revoked = new Entitlement(
            orgId, AnalysisMode.BYO, e.analysesUsed(), e.analysesLimit(),
            Instant.now(), revokedBy, e.grantedAt(), "Revoked by " + revokedBy, Instant.now()
        );
        write(revoked.toDocument());
        log.info("Revoked the hosted trial for {} (by {})", orgId, revokedBy);
        return revoked;
    }

    // ── Firestore mapping ──────────────────────────────────────────────────

    private void write(Map<String, Object> doc) {
        Firestore db = firebase.firestore().orElseThrow(
            () -> new IllegalStateException("Firestore is not configured — cannot change entitlements"));
        try {
            db.collection(COLLECTION).document(orgId).set(doc).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the entitlement: " + e.getMessage(), e);
        }
    }

    private Entitlement fromSnapshot(DocumentSnapshot snap) {
        return new Entitlement(
            snap.getId(),
            parseMode(snap.getString(Entitlement.F_MODE)),
            intOf(snap.get(Entitlement.F_USED)),
            intOf(snap.get(Entitlement.F_LIMIT)),
            instantOf(snap.get(Entitlement.F_EXPIRES)),
            snap.getString(Entitlement.F_GRANTED_BY),
            instantOf(snap.get(Entitlement.F_GRANTED_AT)),
            snap.getString(Entitlement.F_NOTE),
            instantOf(snap.get(Entitlement.F_UPDATED))
        );
    }

    private static AnalysisMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) return AnalysisMode.BYO;
        try {
            return AnalysisMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AnalysisMode.BYO;
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Timestamp ts) return ts.toDate().toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null;
    }
}
