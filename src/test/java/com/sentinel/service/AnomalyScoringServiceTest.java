package com.sentinel.service;

import com.sentinel.domain.Transaction;
import com.sentinel.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the z-score anomaly scorer. Tests call the package-private
 * score(txn, history) overload directly so history can be hand-built per
 * scenario without repository mocking noise.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyScoringServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private AnomalyScoringService anomalyScoringService;

    @BeforeEach
    void setUp() {
        anomalyScoringService = new AnomalyScoringService(transactionRepository);
        ReflectionTestUtils.setField(anomalyScoringService, "minHistoryForStddev", 3);
        ReflectionTestUtils.setField(anomalyScoringService, "zScoreCap", 4.0);
    }

    private Transaction txn(BigDecimal amount) {
        return Transaction.builder()
                .transactionId("txn-1")
                .accountId("acct-1")
                .amount(amount)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void normalTransactionCloseToAccountMeanScoresLow() {
        List<Transaction> history = List.of(
                txn(BigDecimal.valueOf(100)), txn(BigDecimal.valueOf(110)),
                txn(BigDecimal.valueOf(90)), txn(BigDecimal.valueOf(105)));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(102)), history);

        assertThat(result.score()).isLessThan(0.3);
    }

    @Test
    void singleOutlierScoresHigh() {
        List<Transaction> history = List.of(
                txn(BigDecimal.valueOf(100)), txn(BigDecimal.valueOf(110)),
                txn(BigDecimal.valueOf(90)), txn(BigDecimal.valueOf(105)));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(5000)), history);

        assertThat(result.zScore()).isPositive();
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void insufficientHistoryReturnsNeutralScoreWithoutDivideByZero() {
        List<Transaction> history = List.of(txn(BigDecimal.valueOf(100)));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(9000)), history);

        assertThat(result.score()).isZero();
        assertThat(result.reason()).contains("insufficient history");
    }

    @Test
    void noHistoryReturnsNeutralScore() {
        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(50)), Collections.emptyList());

        assertThat(result.score()).isZero();
    }

    @Test
    void constantHistoryWithMatchingAmountScoresZero() {
        List<Transaction> history = List.of(
                txn(BigDecimal.valueOf(50)), txn(BigDecimal.valueOf(50)), txn(BigDecimal.valueOf(50)));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(50)), history);

        assertThat(result.score()).isZero();
    }

    @Test
    void constantHistoryWithDeviatingAmountScoresMax() {
        List<Transaction> history = List.of(
                txn(BigDecimal.valueOf(50)), txn(BigDecimal.valueOf(50)), txn(BigDecimal.valueOf(50)));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(500)), history);

        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void publicScoreMethodDelegatesToRepositoryHistory() {
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(List.of(txn(BigDecimal.valueOf(100)), txn(BigDecimal.valueOf(100)), txn(BigDecimal.valueOf(100))));

        AnomalyScoringService.AnomalyResult result = anomalyScoringService.score(txn(BigDecimal.valueOf(100)));

        assertThat(result.score()).isZero();
    }
}
