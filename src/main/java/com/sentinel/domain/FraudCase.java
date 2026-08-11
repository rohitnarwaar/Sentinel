package com.sentinel.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A case is opened whenever the rules engine or anomaly detector flags a
 * transaction. The AI investigation agent (added in the next phase) fills
 * in aiReport, aiRiskScore, and aiReasoning after retrieving similar past
 * cases from the vector store.
 */
@Entity
@Table(name = "fraud_cases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String caseId;

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CaseStatus status;

    @Column(nullable = false)
    private String flagReason;

    private Double ruleScore;

    /** Populated later by the AI investigation agent. */
    @Column(columnDefinition = "TEXT")
    private String aiReport;

    private Double aiRiskScore;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant reviewedAt;
}
