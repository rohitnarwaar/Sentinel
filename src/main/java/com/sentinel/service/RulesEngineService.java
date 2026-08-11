package com.sentinel.service;

import com.sentinel.domain.Transaction;
import com.sentinel.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Fast, deterministic checks that run on every transaction before anything
 * touches the AI layer. Cheap to run, easy to explain to an auditor, and
 * catches the obvious cases so the AI agent only spends effort on the
 * genuinely ambiguous ones.
 */
@Service
@RequiredArgsConstructor
public class RulesEngineService {

    private final TransactionRepository transactionRepository;

    @Value("${sentinel.rules.velocity.max-transactions-per-minute}")
    private int maxTransactionsPerMinute;

    @Value("${sentinel.rules.amount.high-value-threshold}")
    private BigDecimal highValueThreshold;

    @Value("${sentinel.rules.amount.deviation-multiplier}")
    private double deviationMultiplier;

    public record RuleResult(boolean flagged, String reason, double score) {
        static RuleResult clean() {
            return new RuleResult(false, "no rule triggered", 0.0);
        }
    }

    public RuleResult evaluate(Transaction txn) {
        RuleResult velocity = checkVelocity(txn);
        if (velocity.flagged()) return velocity;

        RuleResult deviation = checkAmountDeviation(txn);
        if (deviation.flagged()) return deviation;

        RuleResult highValue = checkHighValue(txn);
        if (highValue.flagged()) return highValue;

        return RuleResult.clean();
    }

    private RuleResult checkVelocity(Transaction txn) {
        Instant oneMinuteAgo = txn.getTimestamp().minus(1, ChronoUnit.MINUTES);
        List<Transaction> recent = transactionRepository.findRecentByAccount(txn.getAccountId(), oneMinuteAgo);
        if (recent.size() > maxTransactionsPerMinute) {
            return new RuleResult(true,
                    "velocity: %d transactions in the last minute".formatted(recent.size()),
                    0.7);
        }
        return RuleResult.clean();
    }

    private RuleResult checkAmountDeviation(Transaction txn) {
        List<Transaction> history = transactionRepository
                .findTop20ByAccountIdOrderByTimestampDesc(txn.getAccountId());

        Optional<BigDecimal> avg = history.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal::add)
                .map(sum -> history.isEmpty() ? sum : sum.divide(BigDecimal.valueOf(history.size()), 2, java.math.RoundingMode.HALF_UP));

        if (avg.isPresent() && avg.get().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal threshold = avg.get().multiply(BigDecimal.valueOf(deviationMultiplier));
            if (txn.getAmount().compareTo(threshold) > 0) {
                return new RuleResult(true,
                        "amount deviation: %.2f is %.1fx the account's recent average".formatted(
                                txn.getAmount().doubleValue(), deviationMultiplier),
                        0.6);
            }
        }
        return RuleResult.clean();
    }

    private RuleResult checkHighValue(Transaction txn) {
        if (txn.getAmount().compareTo(highValueThreshold) > 0) {
            return new RuleResult(true,
                    "high value transaction above %.2f threshold".formatted(highValueThreshold.doubleValue()),
                    0.5);
        }
        return RuleResult.clean();
    }
}
