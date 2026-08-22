package com.sentinel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Published to the "cases opened" Kafka topic whenever the ingestion
 * pipeline flags a transaction and opens a FraudCase. Deliberately carries
 * only IDs, not the full case/transaction payload — the investigation agent
 * re-reads current state from the DB when it picks this up, so there's no
 * risk of acting on a stale snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCaseOpenedEvent {
    private String caseId;
    private String transactionId;
    private String accountId;
}
