package com.sentinel.domain;

public enum CaseStatus {
    OPEN,                  // just flagged, awaiting AI investigation
    INVESTIGATING,         // AI agent is retrieving context and drafting a report
    REVIEWED,              // AI report generated, awaiting analyst decision
    INVESTIGATION_FAILED,  // AI agent exhausted retries — needs manual review, never silently dropped
    CONFIRMED_FRAUD,
    DISMISSED
}
