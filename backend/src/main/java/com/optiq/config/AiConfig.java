package com.optiq.config;

import com.optiq.service.SettingsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Generic AI provider configuration.
 * Supports ANY LLM via OpenAI-compatible endpoints:
 *   - Ollama (http://localhost:11434/v1)
 *   - LM Studio (http://localhost:1234/v1)
 *   - vLLM (http://localhost:8000/v1)
 *   - DeepSeek (https://api.deepseek.com/v1)
 *   - Groq (https://api.groq.com/openai/v1)
 *   - Mistral (https://api.mistral.ai/v1)
 *   - Together AI (https://api.together.xyz/v1)
 *   - OpenAI (https://api.openai.com/v1)
 *   - Any custom OpenAI-compatible server
 *
 * Also supports Anthropic natively.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    private final AtomicReference<ChatLanguageModel> currentModel = new AtomicReference<>();
    private final AtomicReference<String> currentModelName = new AtomicReference<>();
    private final AtomicReference<String> currentProviderLabel = new AtomicReference<>();

    /**
     * Create or reinitialize the AI model from current settings.
     * Works with any OpenAI-compatible endpoint — no provider lock-in.
     */
    public ChatLanguageModel reinitialize(SettingsService settings) {
        String providerType = settings.getAiProvider();
        if (providerType == null || providerType.isBlank()) providerType = "openai-compatible";

        ChatLanguageModel model = null;
        String modelName = "none";
        String label = providerType;

        try {
            switch (providerType.toLowerCase()) {
                case "anthropic" -> {
                    String key = settings.getAnthropicApiKey();
                    if (key == null || key.isBlank()) {
                        log.warn("Anthropic API key not configured");
                    } else {
                        model = AnthropicChatModel.builder()
                            .apiKey(key)
                            .modelName(settings.getAnthropicModel())
                            .temperature(0.2)
                            .maxTokens(2048)
                            .build();
                        modelName = settings.getAnthropicModel();
                    }
                }
                default -> {
                    // Everything else uses OpenAI-compatible protocol
                    // This covers: openai, openai-compatible, ollama, deepseek,
                    // groq, mistral, together, lmstudio, vllm, localai, custom
                    String baseUrl = settings.getAiBaseUrl();
                    String key = settings.getAiApiKey();
                    String modelNameStr = settings.getAiModel();

                    if (baseUrl == null || baseUrl.isBlank()) {
                        log.warn("AI base URL not configured");
                    } else {
                        var builder = OpenAiChatModel.builder()
                            .baseUrl(baseUrl)
                            .modelName(modelNameStr != null ? modelNameStr : "gpt-4o")
                            .temperature(0.2)
                            .maxTokens(2048);

                        if (key != null && !key.isBlank()) {
                            builder.apiKey(key);
                        } else {
                            builder.apiKey("no-key");
                        }

                        model = builder.build();
                        modelName = modelNameStr;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize AI provider '{}': {}", providerType, e.getMessage());
            model = null;
            modelName = "error";
        }

        currentModel.set(model);
        currentModelName.set(modelName);
        currentProviderLabel.set(label);

        if (model != null) {
            log.info("AI provider ready: {} (model: {})", label, modelName);
        } else {
            log.warn("AI provider NOT ready: {} — analysis will be skipped", label);
        }

        return model;
    }

    public ChatLanguageModel getModel(SettingsService settings) {
        ChatLanguageModel model = currentModel.get();
        if (model == null && currentProviderLabel.get() == null) {
            return reinitialize(settings);
        }
        return model;
    }

    public String getModelName(SettingsService settings) {
        String name = currentModelName.get();
        if (name == null) {
            reinitialize(settings);
            return currentModelName.get();
        }
        return name;
    }

    public boolean isReady() {
        return currentModel.get() != null;
    }
}
