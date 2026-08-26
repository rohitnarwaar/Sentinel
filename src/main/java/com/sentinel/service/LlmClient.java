package com.sentinel.service;

/**
 * A provider-agnostic LLM completion call. AnthropicClient is the real,
 * committed implementation (Claude, per the project's fixed tech stack);
 * OllamaClient is an explicit opt-in alternative for free local testing —
 * selected via sentinel.llm.provider, never silently.
 */
public interface LlmClient {

    /** Sends a single-turn message and returns the model's raw text response. */
    String complete(String systemPrompt, String userPrompt);
}
