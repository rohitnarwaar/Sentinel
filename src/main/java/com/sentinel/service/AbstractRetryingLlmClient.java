package com.sentinel.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Shared retry-with-backoff loop for LlmClient implementations. Subclasses
 * only implement the actual provider call (callOnce) and a name for
 * logging; retry count and backoff are both provider-agnostic and driven by
 * the same sentinel.llm.max-attempts / initial-backoff-ms config either way.
 */
@Slf4j
public abstract class AbstractRetryingLlmClient implements LlmClient {

    private final int maxAttempts;
    private final Duration initialBackoff;

    protected AbstractRetryingLlmClient(int maxAttempts, long initialBackoffMs) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Duration.ofMillis(initialBackoffMs);
    }

    @Override
    public final String complete(String systemPrompt, String userPrompt) {
        Duration backoff = initialBackoff;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callOnce(systemPrompt, userPrompt);
            } catch (RestClientException e) {
                lastError = e;
                log.warn("{} call failed (attempt {}/{}): {}", providerName(), attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2);
                }
            }
        }
        throw new LlmClientException(providerName() + " call failed after " + maxAttempts + " attempts", lastError);
    }

    protected abstract String callOnce(String systemPrompt, String userPrompt);

    protected abstract String providerName();

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("Interrupted while backing off before retry", e);
        }
    }
}
