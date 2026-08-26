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
 * Free, local alternative to AnthropicClient — calls a locally running
 * Ollama server instead of a hosted API, for testing the investigation
 * agent's full pipeline without spending anything. Not the project's
 * committed LLM provider (that's Anthropic); this only activates when
 * sentinel.llm.provider=ollama is set explicitly, and requires Ollama
 * running locally (https://ollama.com) with a model already pulled, e.g.
 * `ollama pull llama3.2`.
 */
@Component
@ConditionalOnProperty(name = "sentinel.llm.provider", havingValue = "ollama")
@Slf4j
public class OllamaClient extends AbstractRetryingLlmClient {

    private final RestClient restClient;
    private final String model;

    public OllamaClient(
            @Value("${sentinel.llm.ollama.base-url}") String baseUrl,
            @Value("${sentinel.llm.ollama.model}") String model,
            @Value("${sentinel.llm.max-attempts}") int maxAttempts,
            @Value("${sentinel.llm.initial-backoff-ms}") long initialBackoffMs) {
        super(maxAttempts, initialBackoffMs);
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        log.info("LLM provider: Ollama ({} @ {}) — free local testing, not the committed provider", model, baseUrl);
    }

    @Override
    protected String callOnce(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        JsonNode response = restClient.post()
                .uri("/api/chat")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new LlmClientException("Ollama returned an empty response", null);
        }
        return response.path("message").path("content").asText();
    }

    @Override
    protected String providerName() {
        return "Ollama";
    }
}
