package com.sentinel.service;

/** Thrown once an LlmClient exhausts its retries. Never swallowed silently — see InvestigationAgentService. */
public class LlmClientException extends RuntimeException {
    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
