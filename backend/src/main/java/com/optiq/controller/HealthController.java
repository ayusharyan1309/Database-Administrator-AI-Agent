package com.optiq.controller;

import com.optiq.model.Entitlement;
import com.optiq.service.EntitlementService;
import com.optiq.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    private final SettingsService settings;
    private final EntitlementService entitlements;

    /** Seed value only — the reported provider comes from Settings. */
    @Value("${optiq.ai.provider:openai}")
    private String defaultAiProvider;

    /** Seed value only — the reported threshold comes from Settings. */
    @Value("${optiq.polling.slow-query-threshold-ms:500}")
    private double defaultSlowThresholdMs;

    /** Seed value only — the reported interval comes from Settings. */
    @Value("${optiq.polling.interval-ms:300000}")
    private long defaultPollIntervalMs;

    public HealthController(SettingsService settings, EntitlementService entitlements) {
        this.settings = settings;
        this.entitlements = entitlements;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "OptiQuery Backend",
            "timestamp", Instant.now().toString(),
            // Everything below is read from Settings, so what the dashboard
            // reports is what the daemon is actually doing.
            "aiProvider", orDefault(settings.getAiProvider(), defaultAiProvider),
            "aiModel", settings.getAiModel(),
            "slowQueryThresholdMs", settings.getSlowThresholdMs(defaultSlowThresholdMs),
            "pollIntervalMs", settings.getPollIntervalMs(defaultPollIntervalMs),
            // Read-only view of the plan, so the dashboard can show what is left.
            "entitlement", entitlementSummary()
        ));
    }

    private Map<String, Object> entitlementSummary() {
        Entitlement e = entitlements.current();
        Instant now = Instant.now();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("mode", e.mode().name());
        summary.put("analysesUsed", e.analysesUsed());
        summary.put("analysesLimit", e.analysesLimit());
        summary.put("analysesRemaining", e.analysesRemaining());
        summary.put("daysRemaining", e.daysRemaining(now));
        summary.put("active", !e.isExpired(now) && !e.isExhausted());
        return summary;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
