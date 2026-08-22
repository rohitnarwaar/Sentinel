package com.sentinel.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around Anthropic's Messages API. Retries transient failures
 * (network errors, 5xx, rate limits) with exponential backoff; after
 * max-attempts is exhausted it throws, and the caller (InvestigationAgentService)
 * is responsible for turning that into a fallback case status rather than
 * losing the case silently.
 */
@Component
@Slf4j
public class AnthropicClient {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public AnthropicClient(
            @Value("${sentinel.llm.anthropic-api-key}") String apiKey,
            @Value("${sentinel.llm.model}") String model,
            @Value("${sentinel.llm.max-tokens}") int maxTokens,
            @Value("${sentinel.llm.max-attempts}") int maxAttempts,
            @Value("${sentinel.llm.initial-backoff-ms}") long initialBackoffMs) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Duration.ofMillis(initialBackoffMs);
        this.restClient = RestClient.builder()
                .baseUrl("https://api.anthropic.com/v1")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    /** Sends a single-turn message and returns the model's raw text response. */
    public String complete(String systemPrompt, String userPrompt) {
        Duration backoff = initialBackoff;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callOnce(systemPrompt, userPrompt);
            } catch (RestClientException e) {
                lastError = e;
                log.warn("Anthropic API call failed (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2);
                }
            }
        }
        throw new AnthropicApiException("Anthropic API call failed after " + maxAttempts + " attempts", lastError);
    }

    private String callOnce(String systemPrompt, String userPrompt) {
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
            throw new AnthropicApiException("Anthropic API returned an empty response", null);
        }
        return response.path("content").path(0).path("text").asText();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnthropicApiException("Interrupted while backing off before retry", e);
        }
    }

    public static class AnthropicApiException extends RuntimeException {
        public AnthropicApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
