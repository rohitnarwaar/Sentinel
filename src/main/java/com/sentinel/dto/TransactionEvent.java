package com.sentinel.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The wire format for a transaction event on the Kafka topic.
 * Kept flat and small on purpose — this is what gets serialized/deserialized
 * millions of times, so no nested objects or heavy fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionEvent {

    private String transactionId;
    private String accountId;
    private BigDecimal amount;
    private String currency;
    private String merchant;
    private String merchantCategory;
    private String country;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;
}
