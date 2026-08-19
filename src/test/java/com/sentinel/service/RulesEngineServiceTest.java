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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the deterministic rules engine. Each check is exercised in
 * isolation by stubbing exactly the repository query it depends on — the
 * thresholds come from the same @Value defaults as application.yml.
 */
@ExtendWith(MockitoExtension.class)
class RulesEngineServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private RulesEngineService rulesEngineService;

    @BeforeEach
    void setUp() {
        rulesEngineService = new RulesEngineService(transactionRepository);
        ReflectionTestUtils.setField(rulesEngineService, "maxTransactionsPerMinute", 5);
        ReflectionTestUtils.setField(rulesEngineService, "highValueThreshold", BigDecimal.valueOf(5000.00));
        ReflectionTestUtils.setField(rulesEngineService, "deviationMultiplier", 4.0);
    }

    private Transaction txn(String accountId, BigDecimal amount) {
        return Transaction.builder()
                .transactionId("txn-1")
                .accountId(accountId)
                .amount(amount)
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void cleanTransactionIsNotFlagged() {
        when(transactionRepository.findRecentByAccount(anyString(), any())).thenReturn(Collections.emptyList());
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(Collections.emptyList());

        RulesEngineService.RuleResult result = rulesEngineService.evaluate(txn("acct-1", BigDecimal.valueOf(50)));

        assertThat(result.flagged()).isFalse();
    }

    @Test
    void velocityCheckFlagsTooManyTransactionsInOneMinute() {
        List<Transaction> recent = List.of(
                txn("acct-1", BigDecimal.TEN), txn("acct-1", BigDecimal.TEN),
                txn("acct-1", BigDecimal.TEN), txn("acct-1", BigDecimal.TEN),
                txn("acct-1", BigDecimal.TEN), txn("acct-1", BigDecimal.TEN));
        when(transactionRepository.findRecentByAccount(anyString(), any())).thenReturn(recent);

        RulesEngineService.RuleResult result = rulesEngineService.evaluate(txn("acct-1", BigDecimal.valueOf(50)));

        assertThat(result.flagged()).isTrue();
        assertThat(result.reason()).contains("velocity");
    }

    @Test
    void amountDeviationCheckFlagsOutlierAgainstAccountHistory() {
        when(transactionRepository.findRecentByAccount(anyString(), any())).thenReturn(Collections.emptyList());
        List<Transaction> history = List.of(
                txn("acct-1", BigDecimal.valueOf(20)),
                txn("acct-1", BigDecimal.valueOf(25)),
                txn("acct-1", BigDecimal.valueOf(15)));
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString())).thenReturn(history);

        // history average is 20; 4x deviation multiplier -> threshold is 80
        RulesEngineService.RuleResult result = rulesEngineService.evaluate(txn("acct-1", BigDecimal.valueOf(500)));

        assertThat(result.flagged()).isTrue();
        assertThat(result.reason()).contains("deviation");
    }

    @Test
    void highValueCheckFlagsTransactionAboveThreshold() {
        when(transactionRepository.findRecentByAccount(anyString(), any())).thenReturn(Collections.emptyList());
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(Collections.emptyList());

        RulesEngineService.RuleResult result = rulesEngineService.evaluate(txn("acct-1", BigDecimal.valueOf(9000)));

        assertThat(result.flagged()).isTrue();
        assertThat(result.reason()).contains("high value");
    }
}