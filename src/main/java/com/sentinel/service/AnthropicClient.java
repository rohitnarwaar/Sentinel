package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Anthropic's Messages API — the real, committed LLM
 * provider for this project's fixed tech stack. Active by default; only
 * disabled if sentinel.llm.provider is explicitly set to something else
 * (see OllamaClient for the free-local-testing alternative).
 */
@Component
@ConditionalOnProperty(name = "sentinel.llm.provider", havingValue = "anthropic", matchIfMissing = true)
@Slf4j
public class AnthropicClient extends AbstractRetryingLlmClient {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public AnthropicClient(
            @Value("${sentinel.llm.anthropic-api-key}") String apiKey,
            @Value("${sentinel.llm.model}") String model,
            @Value("${sentinel.llm.max-tokens}") int maxTokens,
            @Value("${sentinel.llm.max-attempts}") int maxAttempts,
            @Value("${sentinel.llm.initial-backoff-ms}") long initialBackoffMs) {
        super(maxAttempts, initialBackoffMs);
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com/v1")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        log.info("LLM provider: Anthropic ({})", model);
    }

    @Override
    protected String callOnce(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)));

        JsonNode response = restClient.post()
                .uri("/messages")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new LlmClientException("Anthropic API returned an empty response", null);
        }
        return response.path("content").path(0).path("text").asText();
    }

    @Override
    protected String providerName() {
        return "Anthropic API";
    }
}
