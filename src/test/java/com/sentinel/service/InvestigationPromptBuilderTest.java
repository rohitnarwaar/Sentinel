package com.sentinel.service;

import com.sentinel.domain.Transaction;
import com.sentinel.repository.SimilarCaseNote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestigationPromptBuilderTest {

    private final InvestigationPromptBuilder promptBuilder = new InvestigationPromptBuilder();

    private Transaction txn() {
        return Transaction.builder()
                .transactionId("txn-1")
                .accountId("acct-1001")
                .amount(BigDecimal.valueOf(4200))
                .currency("USD")
                .merchant("Best Buy")
                .merchantCategory("electronics")
                .country("US")
                .timestamp(Instant.parse("2026-08-19T12:00:00Z"))
                .build();
    }

    @Test
    void promptIncludesTransactionDetailsAndFlagReason() {
        String prompt = promptBuilder.buildUserPrompt(txn(), "high value transaction above 5000.00 threshold",
                Collections.emptyList(), Collections.emptyList());

        assertThat(prompt).contains("acct-1001");
        assertThat(prompt).contains("4200");
        assertThat(prompt).contains("Best Buy");
        assertThat(prompt).contains("electronics");
        assertThat(prompt).contains("high value transaction above 5000.00 threshold");
    }

    @Test
    void promptIncludesRecentHistoryWhenPresent() {
        Transaction history = Transaction.builder()
                .transactionId("txn-0").accountId("acct-1001")
                .amount(BigDecimal.valueOf(45)).currency("USD")
                .merchant("Shell").merchantCategory("fuel").country("US")
                .timestamp(Instant.parse("2026-08-19T10:00:00Z"))
                .build();

        String prompt = promptBuilder.buildUserPrompt(txn(), "reason", List.of(history), Collections.emptyList());

        assertThat(prompt).contains("Shell");
        assertThat(prompt).doesNotContain("No prior transaction history");
    }

    @Test
    void promptNotesAbsenceOfHistoryAndSimilarCases() {
        String prompt = promptBuilder.buildUserPrompt(txn(), "reason", Collections.emptyList(), Collections.emptyList());

        assertThat(prompt).contains("No prior transaction history for this account.");
        assertThat(prompt).contains("No similar past cases found.");
    }

    @Test
    void promptIncludesSimilarCaseNarrativesAndLabels() {
        SimilarCaseNote note = new SimilarCaseNote("Card tested with rapid small charges.", "card_testing", 0.123);

        String prompt = promptBuilder.buildUserPrompt(txn(), "reason", Collections.emptyList(), List.of(note));

        assertThat(prompt).contains("card_testing");
        assertThat(prompt).contains("Card tested with rapid small charges.");
        assertThat(prompt).contains("0.123");
    }

    @Test
    void systemPromptInstructsStrictJsonSchema() {
        assertThat(InvestigationPromptBuilder.SYSTEM_PROMPT).contains("riskScore");
        assertThat(InvestigationPromptBuilder.SYSTEM_PROMPT).contains("reasoning");
        assertThat(InvestigationPromptBuilder.SYSTEM_PROMPT).contains("recommendedAction");
    }
}
