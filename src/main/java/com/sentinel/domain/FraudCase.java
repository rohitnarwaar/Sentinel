package com.sentinel.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

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

    /**
     * Populated later by the AI investigation agent. If the LLM call fails
     * after retries, this holds the error summary instead, and status flips
     * to INVESTIGATION_FAILED — the case is never left silently unprocessed.
     */
    @Column(columnDefinition = "TEXT")
    private String aiReport;

    private Double aiRiskScore;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Auto-maintained by Hibernate on every save — bumped whenever a case
     * transitions state (OPEN → INVESTIGATING → REVIEWED/etc.), independent
     * of createdAt. The dashboard's "top 100" is ordered by this, not by
     * creation time: under a backlog (simulator opening cases faster than
     * the investigation agent can clear them), ordering by createdAt would
     * bury exactly the cases that just finished being reviewed under a
     * flood of newer, still-untouched OPEN cases.
     */
    @UpdateTimestamp
    private Instant updatedAt;

    private Instant reviewedAt;
}
