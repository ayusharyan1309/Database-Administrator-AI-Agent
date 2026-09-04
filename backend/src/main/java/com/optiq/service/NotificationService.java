package com.optiq.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiq.model.AiAnalysis;
import com.optiq.model.SlowQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Sends alerts to Slack, Discord, Teams, or any generic webhook.
 * Formats the payload based on the configured notification type.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public NotificationService(ObjectMapper objectMapper, SettingsService settingsService) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    /**
     * Send a notification about a diagnosed slow query.
     * Automatically formats the payload based on the notification provider.
     */
    public void notifySlowQuery(SlowQuery query, AiAnalysis analysis) {
        if (!settingsService.isNotifyEnabled()) {
            log.debug("Notifications disabled, skipping");
            return;
        }

        String webhookUrl = settingsService.getNotifyWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("No webhook URL configured, skipping notification");
            return;
        }

        String type = settingsService.getNotifyType();
        try {
            String payload = switch (type.toLowerCase()) {
                case "discord" -> buildDiscordPayload(query, analysis);
                case "teams" -> buildTeamsPayload(query, analysis);
                case "generic" -> buildGenericPayload(query, analysis);
                default -> buildSlackPayload(query, analysis);  // slack is default
            };

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // Discord requires no extra headers, Teams needs certain format
            if ("slack".equalsIgnoreCase(type)) {
                // Slack Block Kit — no extra headers needed
            }

            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Notification sent via {} for slow query #{}", type, query.getId());
            } else {
                log.warn("Notification failed via {} with status: {}", type, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to send {} notification: {}", type, e.getMessage());
        }
    }

    /**
     * Send a simple text notification (fallback).
     */
    public void sendSimpleAlert(String message) {
        if (!settingsService.isNotifyEnabled()) return;

        String webhookUrl = settingsService.getNotifyWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) return;

        String type = settingsService.getNotifyType();
        try {
            String payload = switch (type.toLowerCase()) {
                case "discord" -> objectMapper.writeValueAsString(Map.of("content", message));
                case "teams" -> objectMapper.writeValueAsString(Map.of(
                    "@type", "MessageCard",
                    "summary", "OptiQuery Alert",
                    "text", message
                ));
                default -> objectMapper.writeValueAsString(Map.of("text", message));
            };

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(payload, headers);
            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            log.error("Failed to send simple {} alert: {}", type, e.getMessage());
        }
    }

    // ── Slack (Block Kit) ──

    private String buildSlackPayload(SlowQuery query, AiAnalysis analysis) throws Exception {
        String riskEmoji = getRiskEmoji(analysis);
        String displayQuery = truncate(query.getRawQuery(), 500);
        String displaySql = truncate(analysis.getSuggestedSql(), 1000);

        Map<String, Object> payload = Map.of(
            "blocks", new Object[]{
                Map.of("type", "header", "text", Map.of("type", "plain_text", "text", "\uD83D\uDEA8 Slow Query Detected \u2014 OptiQuery")),
                Map.of("type", "section", "fields", new Object[]{
                    Map.of("type", "mrkdwn", "text", "*Mean Exec Time:*\n" + String.format("%.1f ms", query.getMeanExecTimeMs())),
                    Map.of("type", "mrkdwn", "text", "*Call Count:*\n" + query.getCallCount()),
                    Map.of("type", "mrkdwn", "text", "*Risk Level:*\n" + riskEmoji + " " + analysis.getRiskLevel()),
                    Map.of("type", "mrkdwn", "text", "*Confidence:*\n" + analysis.getConfidenceScore() + "%")
                }),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*Root Cause:*\n" + analysis.getRootCause())),
                Map.of("type", "divider"),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*Suggested Fix:*\n```" + displaySql + "```")),
                Map.of("type", "context", "elements", new Object[]{
                    Map.of("type", "mrkdwn", "text", "\uD83E\uDD16 OptiQuery | " + analysis.getModelUsed() + " | Query #" + query.getId())
                })
            }
        );
        return objectMapper.writeValueAsString(payload);
    }

    // ── Discord (Embeds) ──

    private String buildDiscordPayload(SlowQuery query, AiAnalysis analysis) throws Exception {
        String riskEmoji = getRiskEmoji(analysis);
        int color = switch (analysis.getRiskLevel()) {
            case LOW -> 0x22C55E;      // green
            case MEDIUM -> 0xF59E0B;   // amber
            case HIGH -> 0xEF4444;     // red
        };
        String displaySql = truncate(analysis.getSuggestedSql(), 1000);

        Map<String, Object> embed = Map.of(
            "title", "\uD83D\uDEA8 Slow Query Detected",
            "description", analysis.getRootCause(),
            "color", color,
            "fields", List.of(
                Map.of("name", "Mean Exec Time", "value", String.format("%.1f ms", query.getMeanExecTimeMs()), "inline", true),
                Map.of("name", "Call Count", "value", String.valueOf(query.getCallCount()), "inline", true),
                Map.of("name", "Risk Level", "value", riskEmoji + " " + analysis.getRiskLevel(), "inline", true),
                Map.of("name", "Confidence", "value", analysis.getConfidenceScore() + "%", "inline", true),
                Map.of("name", "Suggested Fix", "value", "```\n" + displaySql + "\n```", "inline", false)
            ),
            "footer", Map.of("text", "OptiQuery | " + analysis.getModelUsed())
        );

        Map<String, Object> payload = Map.of("embeds", new Object[]{embed});
        return objectMapper.writeValueAsString(payload);
    }

    // ── Microsoft Teams (MessageCard) ──

    private String buildTeamsPayload(SlowQuery query, AiAnalysis analysis) throws Exception {
        String riskEmoji = getRiskEmoji(analysis);
        String displaySql = truncate(analysis.getSuggestedSql(), 1000);

        Map<String, Object> payload = Map.of(
            "@type", "MessageCard",
            "@context", "http://schema.org/extensions",
            "themeColor", switch (analysis.getRiskLevel()) {
                case LOW -> "22C55E";
                case MEDIUM -> "F59E0B";
                case HIGH -> "EF4444";
            },
            "summary", "OptiQuery: Slow Query Detected",
            "sections", List.of(
                Map.of(
                    "activityTitle", "\uD83D\uDEA8 Slow Query Detected",
                    "facts", List.of(
                        Map.of("name", "Mean Exec Time", "value", String.format("%.1f ms", query.getMeanExecTimeMs())),
                        Map.of("name", "Call Count", "value", String.valueOf(query.getCallCount())),
                        Map.of("name", "Risk Level", "value", riskEmoji + " " + analysis.getRiskLevel()),
                        Map.of("name", "Confidence", "value", analysis.getConfidenceScore() + "%"),
                        Map.of("name", "Root Cause", "value", analysis.getRootCause())
                    ),
                    "markdown", true
                ),
                Map.of(
                    "text", "**Suggested Fix:**\n```\n" + displaySql + "\n```",
                    "markdown", true
                )
            )
        );
        return objectMapper.writeValueAsString(payload);
    }

    // ── Generic (simple JSON with all fields) ──

    private String buildGenericPayload(SlowQuery query, AiAnalysis analysis) throws Exception {
        Map<String, Object> payload = Map.of(
            "event", "slow_query_detected",
            "query_id", query.getId(),
            "mean_exec_time_ms", query.getMeanExecTimeMs(),
            "call_count", query.getCallCount(),
            "risk_level", analysis.getRiskLevel().toString(),
            "confidence_score", analysis.getConfidenceScore(),
            "root_cause", analysis.getRootCause(),
            "suggested_sql", analysis.getSuggestedSql(),
            "model_used", analysis.getModelUsed()
        );
        return objectMapper.writeValueAsString(payload);
    }

    // ── Helpers ──

    private String getRiskEmoji(AiAnalysis analysis) {
        return switch (analysis.getRiskLevel()) {
            case LOW -> "\uD83D\uDFE2";
            case MEDIUM -> "\uD83D\uDFE1";
            case HIGH -> "\uD83D\uDD34";
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
