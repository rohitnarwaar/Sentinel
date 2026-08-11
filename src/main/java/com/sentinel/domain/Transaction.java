package com.sentinel.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_account_ts", columnList = "accountId,timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    private String transactionId;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private BigDecimal amount;

    private String currency;
    private String merchant;
    private String merchantCategory;
    private String country;

    @Column(nullable = false)
    private Instant timestamp;

    /** Set by the rules engine / anomaly detector after evaluation. */
    private Double anomalyScore;

    @Column(nullable = false)
    private boolean flagged;

    @Column(nullable = false)
    private Instant ingestedAt;
}
