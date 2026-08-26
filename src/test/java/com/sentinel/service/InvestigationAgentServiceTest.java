package com.sentinel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.domain.Transaction;
import com.sentinel.dto.FraudCaseOpenedEvent;
import com.sentinel.repository.CaseNoteRepository;
import com.sentinel.repository.FraudCaseRepository;
import com.sentinel.repository.SimilarCaseNote;
import com.sentinel.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The LLM call itself (LlmClient — whichever provider is active) is mocked
 * throughout; these tests verify the orchestration around it: JSON parsing,
 * persistence, and the fallback path when the "LLM" fails.
 */
@ExtendWith(MockitoExtension.class)
class InvestigationAgentServiceTest {

    @Mock
    private FraudCaseRepository fraudCaseRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private CaseNoteRepository caseNoteRepository;
    @Mock
    private LlmClient llmClient;

    private InvestigationAgentService investigationAgentService;

    @BeforeEach
    void setUp() {
        investigationAgentService = new InvestigationAgentService(
                fraudCaseRepository,
                transactionRepository,
                caseNoteRepository,
                new EmbeddingService(),
                new InvestigationPromptBuilder(),
                llmClient,
                new ObjectMapper(),
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(investigationAgentService, "similarCasesK", 3);
        ReflectionTestUtils.setField(investigationAgentService, "recentHistoryLimit", 5);
    }

    private FraudCase openCase() {
        return FraudCase.builder()
                .caseId("case-1")
                .transactionId("txn-1")
                .accountId("acct-1")
                .status(CaseStatus.OPEN)
                .flagReason("high value transaction above 5000.00 threshold")
                .ruleScore(0.5)
                .createdAt(Instant.now())
                .build();
    }

    private Transaction txn() {
        return Transaction.builder()
                .transactionId("txn-1").accountId("acct-1")
                .amount(BigDecimal.valueOf(9000)).currency("USD")
                .merchant("Best Buy").merchantCategory("electronics").country("US")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    void successfulInvestigationMarksCaseReviewed() {
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(openCase()));
        when(transactionRepository.findById("txn-1")).thenReturn(Optional.of(txn()));
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(Collections.emptyList());
        when(caseNoteRepository.findTopKSimilar(any(), anyInt())).thenReturn(List.of(
                new SimilarCaseNote("High value electronics purchase, confirmed legitimate.", "legitimate", 0.2)));
        when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"riskScore\": 0.2, \"reasoning\": \"Consistent with prior spend.\", \"recommendedAction\": \"DISMISS\"}");

        investigationAgentService.consume(new FraudCaseOpenedEvent("case-1", "txn-1", "acct-1"));

        ArgumentCaptor<FraudCase> captor = ArgumentCaptor.forClass(FraudCase.class);
        verify(fraudCaseRepository, atLeastOnce()).save(captor.capture());
        FraudCase finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);

        assertThat(finalState.getStatus()).isEqualTo(CaseStatus.REVIEWED);
        assertThat(finalState.getAiRiskScore()).isEqualTo(0.2);
        assertThat(finalState.getAiReport()).contains("Consistent with prior spend.");
        assertThat(finalState.getAiReport()).contains("DISMISS");
    }

    @Test
    void jsonWrappedInMarkdownFenceIsStillParsed() {
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(openCase()));
        when(transactionRepository.findById("txn-1")).thenReturn(Optional.of(txn()));
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(Collections.emptyList());
        when(caseNoteRepository.findTopKSimilar(any(), anyInt())).thenReturn(Collections.emptyList());
        when(llmClient.complete(anyString(), anyString())).thenReturn(
                "```json\n{\"riskScore\": 0.9, \"reasoning\": \"Matches card testing pattern.\", \"recommendedAction\": \"ESCALATE\"}\n```");

        investigationAgentService.consume(new FraudCaseOpenedEvent("case-1", "txn-1", "acct-1"));

        ArgumentCaptor<FraudCase> captor = ArgumentCaptor.forClass(FraudCase.class);
        verify(fraudCaseRepository, atLeastOnce()).save(captor.capture());
        FraudCase finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);

        assertThat(finalState.getStatus()).isEqualTo(CaseStatus.REVIEWED);
        assertThat(finalState.getAiRiskScore()).isEqualTo(0.9);
    }

    @Test
    void llmFailureMarksCaseInvestigationFailedInsteadOfLosingIt() {
        when(fraudCaseRepository.findById("case-1")).thenReturn(Optional.of(openCase()));
        when(transactionRepository.findById("txn-1")).thenReturn(Optional.of(txn()));
        when(transactionRepository.findTop20ByAccountIdOrderByTimestampDesc(anyString()))
                .thenReturn(Collections.emptyList());
        when(caseNoteRepository.findTopKSimilar(any(), anyInt())).thenReturn(Collections.emptyList());
        when(llmClient.complete(anyString(), anyString()))
                .thenThrow(new LlmClientException("LLM call failed after 3 attempts", null));

        investigationAgentService.consume(new FraudCaseOpenedEvent("case-1", "txn-1", "acct-1"));

        ArgumentCaptor<FraudCase> captor = ArgumentCaptor.forClass(FraudCase.class);
        verify(fraudCaseRepository, atLeastOnce()).save(captor.capture());
        FraudCase finalState = captor.getAllValues().get(captor.getAllValues().size() - 1);

        assertThat(finalState.getStatus()).isEqualTo(CaseStatus.INVESTIGATION_FAILED);
        assertThat(finalState.getAiReport()).contains("AI investigation failed");
    }

    @Test
    void missingCaseOrTransactionIsSkippedWithoutThrowing() {
        when(fraudCaseRepository.findById("missing-case")).thenReturn(Optional.empty());

        investigationAgentService.consume(new FraudCaseOpenedEvent("missing-case", "txn-1", "acct-1"));

        verify(fraudCaseRepository, never()).save(any());
        verifyNoInteractions(llmClient);
    }
}
