package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional integration with a locally running Ollama server. When disabled
 * (the default) drafting falls back to the built-in template engine, so the
 * application works out of the box with no external services.
 */
@ConfigurationProperties(prefix = "ai-assist.ollama")
public record OllamaProperties(boolean enabled, String baseUrl, String model, int timeoutSeconds) {

    public OllamaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (model == null || model.isBlank()) {
            model = "llama3.2";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
    }
}
