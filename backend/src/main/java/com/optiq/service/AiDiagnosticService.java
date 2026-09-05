package com.optiq.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.optiq.config.AiConfig;
import com.optiq.model.AnalysisResult;
import com.optiq.model.RiskLevel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The AI Diagnostic Engine.
 * Uses AiConfig + SettingsService for dynamic provider configuration.
 */
@Service
public class AiDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosticService.class);

    private final AiConfig aiConfig;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public AiDiagnosticService(AiConfig aiConfig, SettingsService settingsService, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Analyze a slow query and return a structured diagnosis.
     */
    public AnalysisResult analyzeQuery(String rawQuery, String schemaContext, String explainPlan) {
        ChatLanguageModel model = aiConfig.getModel(settingsService);
        if (model == null) {
            log.warn("AI not configured — returning fallback analysis");
            return new AnalysisResult(
                "AI provider not configured. Set your API key in Settings to enable AI analysis.",
                "", 1, RiskLevel.HIGH
            );
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(rawQuery, schemaContext, explainPlan);
        String modelName = aiConfig.getModelName(settingsService);

        log.info("Sending query to AI for analysis (model: {})", modelName);

        Response<AiMessage> response = model.generate(
            SystemMessage.from(systemPrompt),
            UserMessage.from(userPrompt)
        );

        String responseText = response.content().text();
        log.info("AI raw response (first 500 chars): {}",
            responseText != null ? responseText.substring(0, Math.min(responseText.length(), 500)) : "<null>");

        return parseResponse(responseText != null ? responseText : "");
    }

    /**
     * Check if AI is ready to analyze.
     */
    public boolean isAiReady() {
        return aiConfig.isReady();
    }

    private String buildSystemPrompt() {
        return """
            You are an expert PostgreSQL Database Administrator (DBA). Your job is to analyze
            slow queries and provide actionable optimization recommendations.

            You MUST respond with ONLY a valid JSON object (no markdown, no code fences, no explanation).
            The JSON must have exactly these fields:

            {
              "root_cause": "A concise 1-2 sentence explanation of why this query is slow.",
              "suggested_sql": "The exact SQL command to fix the issue (e.g., CREATE INDEX, query rewrite). Use an empty string if no fix is possible.",
              "confidence_score": <integer 1-100>,
              "risk_level": "<LOW|MEDIUM|HIGH>"
            }

            Rules:
            - "root_cause": Be specific. Reference the execution plan (Seq Scan, nested loop, etc.).
            - "suggested_sql": Provide the exact SQL. For indexes, use CONCURRENTLY where possible.
            - "confidence_score": Be honest. 90+ means you're very sure, 50-89 is moderate, <50 is speculative.
            - "risk_level":
              - LOW = index on small table, read-only query rewrite
              - MEDIUM = index on large table, may cause brief locks
              - HIGH = DDL on huge production table, potential long lock, or data-altering change
            - NEVER suggest queries that modify data (INSERT, UPDATE, DELETE) unless explicitly asked.
            - If you cannot determine a fix, set suggested_sql to "" and confidence_score to a low value.
        """;
    }

    private String buildUserPrompt(String rawQuery, String schemaContext, String explainPlan) {
        return """
            Analyze the following slow PostgreSQL query and provide optimization recommendations.

            === SLOW QUERY ===
            %s

            === TABLE SCHEMAS ===
            %s

            === EXPLAIN ANALYZE OUTPUT ===
            %s

            Provide your analysis as a JSON object with the required fields.
            """.formatted(rawQuery, schemaContext, explainPlan);
    }

    private AnalysisResult parseResponse(String response) {
        try {
            String json = response.trim();
            // Strip markdown code fences
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }

            // Try to find JSON object in the response (AI might wrap it in text)
            int braceStart = json.indexOf('{');
            int braceEnd = json.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = json.substring(braceStart, braceEnd + 1);
            }

            // Handle truncated JSON — try to repair common truncation patterns
            try {
                JsonNode node = objectMapper.readTree(json);
            } catch (Exception parseEx) {
                log.warn("JSON parse failed, attempting repair: {}", parseEx.getMessage());
                // Try closing unclosed strings/objects
                String repaired = json;
                // Count unmatched braces
                int openBraces = 0;
                for (char c : repaired.toCharArray()) {
                    if (c == '{') openBraces++;
                    if (c == '}') openBraces--;
                }
                // Add missing closing braces
                for (int i = 0; i < openBraces; i++) repaired += "}";
                // If string is unclosed, close it
                long uncloseQuotes = repaired.chars().filter(c -> c == '"').count();
                if (uncloseQuotes % 2 != 0) repaired += '"';
                json = repaired;
            }

            JsonNode node = objectMapper.readTree(json);

            // Handle various field name variations the AI might use
            String rootCause = getField(node, "root_cause", "root cause", "explanation", "cause", "analysis");
            String suggestedSql = getField(node, "suggested_sql", "suggested_sql", "suggested sql", "sql", "fix", "recommendation", "optimization");
            int confidenceScore = getIntField(node, "confidence_score", "confidence", "score", 50);
            String riskStr = getField(node, "risk_level", "risk level", "risk", "severity", "MEDIUM");
            RiskLevel riskLevel = parseRiskLevel(riskStr);

            confidenceScore = Math.max(1, Math.min(100, confidenceScore));

            if (rootCause == null || rootCause.isBlank()) {
                // Use the raw response as root cause if parsing failed
                rootCause = response.substring(0, Math.min(response.length(), 500));
            }

            return new AnalysisResult(rootCause, suggestedSql != null ? suggestedSql : "", confidenceScore, riskLevel);

        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", e.getMessage());
            return new AnalysisResult(
                "AI analysis failed to parse. Manual review required. Raw response was: "
                    + response.substring(0, Math.min(response.length(), 500)),
                "", 1, RiskLevel.HIGH
            );
        }
    }

    private String getField(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode val = node.get(key);
            if (val != null && !val.isNull()) return val.asText();
        }
        return null;
    }

    private int getIntField(JsonNode node, String key1, String key2, String key3, int defaultVal) {
        for (String key : new String[]{key1, key2, key3}) {
            JsonNode val = node.get(key);
            if (val != null && !val.isNull()) return val.asInt(defaultVal);
        }
        return defaultVal;
    }

    private RiskLevel parseRiskLevel(String risk) {
        if (risk == null) return RiskLevel.MEDIUM;
        String upper = risk.toUpperCase().trim();
        if (upper.contains("HIGH")) return RiskLevel.HIGH;
        if (upper.contains("LOW")) return RiskLevel.LOW;
        return RiskLevel.MEDIUM;
    }

    public String getModelName() {
        return aiConfig.getModelName(settingsService);
    }
}
