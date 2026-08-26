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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Consumes FraudCaseOpenedEvent off its own Kafka topic — decoupled from
 * the ingestion consumer group so a slow LLM call never adds lag to
 * transaction processing. For each case: retrieves similar past cases from
 * pgvector, builds a prompt, calls Claude, and persists the structured
 * report. If the LLM call fails after retries, the case is marked
 * INVESTIGATION_FAILED rather than left silently stuck — a flaky API call
 * never causes a fraud case to just disappear from the queue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvestigationAgentService {

    private final FraudCaseRepository fraudCaseRepository;
    private final TransactionRepository transactionRepository;
    private final CaseNoteRepository caseNoteRepository;
    private final EmbeddingService embeddingService;
    private final InvestigationPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${sentinel.investigation.similar-cases-k}")
    private int similarCasesK;

    @Value("${sentinel.investigation.recent-history-limit}")
    private int recentHistoryLimit;

    @KafkaListener(topics = "${sentinel.kafka.cases-opened-topic}", containerFactory = "casesOpenedKafkaListenerContainerFactory")
    public void consume(FraudCaseOpenedEvent event) {
        FraudCase fraudCase = fraudCaseRepository.findById(event.getCaseId()).orElse(null);
        Transaction txn = transactionRepository.findById(event.getTransactionId()).orElse(null);

        if (fraudCase == null || txn == null) {
            log.error("Cannot investigate case {} — case or transaction not found (transaction {})",
                    event.getCaseId(), event.getTransactionId());
            return;
        }

        fraudCase.setStatus(CaseStatus.INVESTIGATING);
        fraudCaseRepository.save(fraudCase);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AiInvestigationReport report = investigate(fraudCase, txn);
            fraudCase.setAiRiskScore(report.riskScore());
            fraudCase.setAiReport("Reasoning: %s\n\nRecommended action: %s"
                    .formatted(report.reasoning(), report.recommendedAction()));
            fraudCase.setStatus(CaseStatus.REVIEWED);
            log.info("AI investigation complete for case {} — riskScore={}, action={}",
                    fraudCase.getCaseId(), report.riskScore(), report.recommendedAction());
        } catch (Exception e) {
            log.error("AI investigation failed for case {}: {}", fraudCase.getCaseId(), e.getMessage());
            fraudCase.setStatus(CaseStatus.INVESTIGATION_FAILED);
            fraudCase.setAiReport("AI investigation failed: %s".formatted(e.getMessage()));
        } finally {
            sample.stop(meterRegistry.timer("sentinel.ai.agent.latency"));
            fraudCaseRepository.save(fraudCase);
        }
    }

    private AiInvestigationReport investigate(FraudCase fraudCase, Transaction txn) throws Exception {
        List<Transaction> recentHistory = transactionRepository
                .findTop20ByAccountIdOrderByTimestampDesc(txn.getAccountId())
                .stream()
                .limit(recentHistoryLimit)
                .toList();

        String queryText = "%s transaction of %s at %s (%s) in %s. Flagged: %s".formatted(
                txn.getAccountId(), txn.getAmount(), txn.getMerchant(), txn.getMerchantCategory(),
                txn.getCountry(), fraudCase.getFlagReason());
        float[] queryEmbedding = embeddingService.embed(queryText);
        List<SimilarCaseNote> similarCases = caseNoteRepository.findTopKSimilar(queryEmbedding, similarCasesK);

        String userPrompt = promptBuilder.buildUserPrompt(txn, fraudCase.getFlagReason(), recentHistory, similarCases);
        String rawResponse = llmClient.complete(InvestigationPromptBuilder.SYSTEM_PROMPT, userPrompt);

        return parseReport(rawResponse);
    }

    AiInvestigationReport parseReport(String rawResponse) throws Exception {
        String cleaned = rawResponse.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
        }
        return objectMapper.readValue(cleaned, AiInvestigationReport.class);
    }
}
