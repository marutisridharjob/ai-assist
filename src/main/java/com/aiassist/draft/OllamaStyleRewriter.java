package com.aiassist.draft;

import java.time.Duration;
import java.util.Map;

import com.aiassist.config.OllamaProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Optional free-form style drafting through a locally running open-source
 * LLM served by Ollama — enabled with {@code ai-assist.ollama.enabled=true},
 * still fully offline. When absent or failing, the deterministic rule-based
 * recipes in {@link StyleRewriteService} take over.
 */
@Component
@ConditionalOnProperty(prefix = "ai-assist.ollama", name = "enabled", havingValue = "true")
public class OllamaStyleRewriter {

    private final RestClient restClient;
    private final OllamaProperties properties;

    public OllamaStyleRewriter(OllamaProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    record OllamaResponse(String response) {
    }

    public String rewrite(String text, StyleRewriteService.Style style) {
        return freeform(text, "use a " + style.display() + " communication style");
    }

    /** Free-form rewrite honoring checkbox options and typed instructions. */
    public String freeform(String text, String request) {
        String prompt = """
                Rewrite the text below. Requirements: %s.
                Correct all grammar. Keep the meaning and every fact; do not invent anything new.
                Return only the rewritten text, no preamble.

                Text:
                %s
                """.formatted(request, text);
        OllamaResponse result = restClient.post()
                .uri("/api/generate")
                .body(Map.of("model", properties.model(), "prompt", prompt, "stream", false))
                .retrieve()
                .body(OllamaResponse.class);
        if (result == null || result.response() == null || result.response().isBlank()) {
            throw new IllegalStateException("empty response from Ollama");
        }
        return result.response().strip();
    }
}
