package com.sentinel.service;

/** Structured LLM output, parsed from the JSON the model is instructed to return. */
public record AiInvestigationReport(double riskScore, String reasoning, String recommendedAction) {
}
