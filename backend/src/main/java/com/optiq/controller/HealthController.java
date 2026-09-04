package com.optiq.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${optiq.ai.provider:openai}")
    private String aiProvider;

    @Value("${optiq.polling.slow-query-threshold-ms:500}")
    private double slowThresholdMs;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "OptiQuery Backend",
            "timestamp", Instant.now().toString(),
            "aiProvider", aiProvider,
            "slowQueryThresholdMs", slowThresholdMs
        ));
    }
}
