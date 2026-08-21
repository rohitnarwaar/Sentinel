package com.sentinel.service;

import com.sentinel.domain.Transaction;
import com.sentinel.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Scores a transaction against its account's own recent history using a
 * z-score: how many standard deviations the amount sits from that account's
 * rolling mean. This is the statistical counterpart to
 * RulesEngineService.checkAmountDeviation — the rule check is a blunt
 * "N times the average" threshold that runs before this and is cheap to
 * explain in an audit log; this service produces the actual anomalyScore
 * stored on the Transaction, normalized to 0.0-1.0 so it can be combined
 * with rule-based flags.
 */
@Service
@RequiredArgsConstructor
public class AnomalyScoringService {

    private final TransactionRepository transactionRepository;

    @Value("${sentinel.anomaly.min-history-for-stddev}")
    private int minHistoryForStddev;

    @Value("${sentinel.anomaly.zscore-cap}")
    private double zScoreCap;

    public record AnomalyResult(double zScore, double score, String reason) {
    }

    public AnomalyResult score(Transaction txn) {
        List<Transaction> history = transactionRepository
                .findTop20ByAccountIdOrderByTimestampDesc(txn.getAccountId());
        return score(txn, history);
    }

    AnomalyResult score(Transaction txn, List<Transaction> history) {
        if (history.size() < minHistoryForStddev) {
            return new AnomalyResult(0.0, 0.0,
                    "insufficient history (%d transaction(s)) to compute a meaningful z-score"
                            .formatted(history.size()));
        }

        double[] amounts = history.stream()
                .mapToDouble(t -> t.getAmount().doubleValue())
                .toArray();
        double mean = mean(amounts);
        double stdDev = stdDev(amounts, mean);
        double amount = txn.getAmount().doubleValue();

        if (stdDev == 0.0) {
            // Every past transaction was for the exact same amount — any deviation
            // at all is meaningful even though a z-score can't be computed.
            boolean matches = amount == mean;
            return new AnomalyResult(matches ? 0.0 : Double.POSITIVE_INFINITY, matches ? 0.0 : 1.0,
                    matches
                            ? "matches account's constant transaction history"
                            : "deviates from account's previously constant transaction amount (always %.2f)"
                                    .formatted(mean));
        }

        double zScore = (amount - mean) / stdDev;
        double normalizedScore = Math.min(1.0, Math.abs(zScore) / zScoreCap);
        String reason = "z-score %.2f against account's rolling history (mean=%.2f, stddev=%.2f, n=%d)"
                .formatted(zScore, mean, stdDev, amounts.length);

        return new AnomalyResult(zScore, normalizedScore, reason);
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private double stdDev(double[] values, double mean) {
        if (values.length < 2) {
            return 0.0;
        }
        double sumSquaredDiff = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSquaredDiff += diff * diff;
        }
        // Sample standard deviation (n-1): history is a sample used to estimate
        // the account's true spending distribution, not the whole population.
        return Math.sqrt(sumSquaredDiff / (values.length - 1));
    }
}
