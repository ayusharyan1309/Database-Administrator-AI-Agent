package com.optiq.controller;

import com.optiq.config.FirebaseConfig;
import com.optiq.model.Entitlement;
import com.optiq.service.EntitlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vendor-side control over hosted trials. Not part of the customer UI.
 *
 * Guarded by a shared token that must be set deliberately: with no token
 * configured every endpoint here returns 404, so an installation that never
 * opted in has no admin surface at all rather than an unprotected one.
 *
 *   curl -X POST localhost:8080/api/admin/entitlement/grant \
 *        -H "X-Optiq-Admin-Token: $TOKEN" \
 *        -d 'analyses=50&days=14&note=POC with Acme'
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final EntitlementService entitlements;
    private final FirebaseConfig firebase;

    @Value("${optiq.admin.token:${OPTIQ_ADMIN_TOKEN:}}")
    private String adminToken;

    public AdminController(EntitlementService entitlements, FirebaseConfig firebase) {
        this.entitlements = entitlements;
        this.firebase = firebase;
    }

    @GetMapping("/entitlement")
    public ResponseEntity<?> show(@RequestHeader(value = "X-Optiq-Admin-Token", required = false) String token) {
        ResponseEntity<?> denied = authorize(token, "read the entitlement");
        if (denied != null) return denied;
        return ResponseEntity.ok(detail(entitlements.current()));
    }

    @PostMapping("/entitlement/grant")
    public ResponseEntity<?> grant(
            @RequestHeader(value = "X-Optiq-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "50") int analyses,
            @RequestParam(defaultValue = "14") int days,
            @RequestParam(defaultValue = "admin") String by,
            @RequestParam(defaultValue = "") String note) {

        ResponseEntity<?> denied = authorize(token, "grant a trial");
        if (denied != null) return denied;
        if (analyses <= 0) return badRequest("analyses must be greater than 0");
        if (!firebase.isAvailable()) return firestoreRequired();

        return ResponseEntity.ok(detail(entitlements.grant(analyses, days, by, note)));
    }

    @PostMapping("/entitlement/extend")
    public ResponseEntity<?> extend(
            @RequestHeader(value = "X-Optiq-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "0") int analyses,
            @RequestParam(defaultValue = "admin") String by) {

        ResponseEntity<?> denied = authorize(token, "extend a trial");
        if (denied != null) return denied;
        if (days <= 0 && analyses <= 0) return badRequest("provide days, analyses, or both");
        if (!firebase.isAvailable()) return firestoreRequired();

        return ResponseEntity.ok(detail(entitlements.extend(days, analyses, by)));
    }

    @PostMapping("/entitlement/revoke")
    public ResponseEntity<?> revoke(
            @RequestHeader(value = "X-Optiq-Admin-Token", required = false) String token,
            @RequestParam(defaultValue = "admin") String by) {

        ResponseEntity<?> denied = authorize(token, "revoke a trial");
        if (denied != null) return denied;
        if (!firebase.isAvailable()) return firestoreRequired();

        return ResponseEntity.ok(detail(entitlements.revoke(by)));
    }

    // ── Guard ──────────────────────────────────────────────────────────────

    /** Returns a response when the caller must be turned away, else null. */
    private ResponseEntity<?> authorize(String presented, String action) {
        if (adminToken == null || adminToken.isBlank()) {
            // No admin token configured: behave as though the surface does not exist.
            return ResponseEntity.notFound().build();
        }
        if (presented == null || !constantTimeEquals(adminToken, presented)) {
            log.warn("Rejected an unauthorized attempt to {}", action);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "A valid X-Optiq-Admin-Token header is required"));
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }

    // ── Responses ──────────────────────────────────────────────────────────

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<?> firestoreRequired() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "error", "Firestore is not configured, so entitlements cannot be changed",
            "reason", firebase.unavailableReason()
        ));
    }

    private static Map<String, Object> detail(Entitlement e) {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgId", e.orgId());
        body.put("mode", e.mode().name());
        body.put("analysesUsed", e.analysesUsed());
        body.put("analysesLimit", e.analysesLimit());
        body.put("analysesRemaining", e.analysesRemaining());
        body.put("expiresAt", e.expiresAt() != null ? e.expiresAt().toString() : null);
        body.put("daysRemaining", e.daysRemaining(now));
        body.put("expired", e.isExpired(now));
        body.put("exhausted", e.isExhausted());
        body.put("grantedBy", e.grantedBy());
        body.put("note", e.note());
        return body;
    }
}
