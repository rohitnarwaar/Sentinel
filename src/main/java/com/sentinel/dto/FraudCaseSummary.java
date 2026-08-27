package com.sentinel.dto;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.domain.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * FraudCase plus the handful of its linked Transaction's fields the
 * dashboard needs to render a case (amount, merchant, country) — without
 * this, the dashboard either omits the amount or fetches each transaction
 * individually per case, which at 100+ cases in view becomes a fetch storm.
 * FraudCase deliberately doesn't hold a JPA relationship to Transaction
 * (see TransactionConsumerService's design note), so this is assembled by
 * the caller from data already in hand or one batched lookup — never N+1.
 */
@Data
@Builder
public class FraudCaseSummary {
    private String caseId;
    private String transactionId;
    private String accountId;
    private CaseStatus status;
    private String flagReason;
    private Double ruleScore;
    private String aiReport;
    private Double aiRiskScore;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant reviewedAt;

    // From the linked transaction — null only if it's somehow missing.
    private BigDecimal amount;
    private String currency;
    private String merchant;
    private String merchantCategory;
    private String country;
    private Instant transactionTimestamp;

    public static FraudCaseSummary of(FraudCase c, Transaction t) {
        FraudCaseSummaryBuilder b = FraudCaseSummary.builder()
                .caseId(c.getCaseId())
                .transactionId(c.getTransactionId())
                .accountId(c.getAccountId())
                .status(c.getStatus())
                .flagReason(c.getFlagReason())
                .ruleScore(c.getRuleScore())
                .aiReport(c.getAiReport())
                .aiRiskScore(c.getAiRiskScore())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .reviewedAt(c.getReviewedAt());
        if (t != null) {
            b.amount(t.getAmount())
                    .currency(t.getCurrency())
                    .merchant(t.getMerchant())
                    .merchantCategory(t.getMerchantCategory())
                    .country(t.getCountry())
                    .transactionTimestamp(t.getTimestamp());
        }
        return b.build();
    }
}
