package com.sentinel.service;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.domain.Transaction;
import com.sentinel.dto.FraudCaseOpenedEvent;
import com.sentinel.dto.FraudCaseSummary;
import com.sentinel.dto.TransactionEvent;
import com.sentinel.repository.FraudCaseRepository;
import com.sentinel.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Entry point of the pipeline: consumes raw transaction events off Kafka,
 * persists them, and runs them through two independent signals — the
 * deterministic rules engine and the statistical anomaly scorer. Either one
 * can trigger a flag; the stronger of the two scores becomes the
 * transaction's anomalyScore. Anything flagged opens a FraudCase and
 * publishes a FraudCaseOpenedEvent — the AI investigation agent consumes
 * that independently, off its own topic, so a slow LLM call never backs up
 * this consumer group.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionConsumerService {

    private final TransactionRepository transactionRepository;
    private final FraudCaseRepository fraudCaseRepository;
    private final RulesEngineService rulesEngineService;
    private final AnomalyScoringService anomalyScoringService;
    private final KafkaTemplate<String, FraudCaseOpenedEvent> caseEventKafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final CaseEventBroadcaster caseEventBroadcaster;

    @Value("${sentinel.anomaly.flag-threshold}")
    private double flagThreshold;

    @Value("${sentinel.kafka.cases-opened-topic}")
    private String casesOpenedTopic;

    @KafkaListener(topics = "${sentinel.kafka.topic}", containerFactory = "kafkaListenerContainerFactory")
    public void consume(TransactionEvent event) {
        Transaction txn = Transaction.builder()
                .transactionId(event.getTransactionId())
                .accountId(event.getAccountId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .merchant(event.getMerchant())
                .merchantCategory(event.getMerchantCategory())
                .country(event.getCountry())
                .timestamp(event.getTimestamp())
                .ingestedAt(Instant.now())
                .flagged(false)
                .build();

        RulesEngineService.RuleResult ruleResult = rulesEngineService.evaluate(txn);
        AnomalyScoringService.AnomalyResult anomalyResult = anomalyScoringService.score(txn);

        double combinedScore = Math.max(ruleResult.score(), anomalyResult.score());
        boolean flagged = ruleResult.flagged() || combinedScore >= flagThreshold;

        txn.setFlagged(flagged);
        txn.setAnomalyScore(combinedScore);
        transactionRepository.save(txn);
        meterRegistry.counter("sentinel.transactions.ingested").increment();

        if (flagged) {
            String reason = ruleResult.flagged()
                    ? ruleResult.reason()
                    : "anomaly score %.2f above threshold — %s".formatted(combinedScore, anomalyResult.reason());
            openCase(txn, reason, combinedScore);
        }
    }

    private void openCase(Transaction txn, String reason, double score) {
        FraudCase fraudCase = FraudCase.builder()
                .transactionId(txn.getTransactionId())
                .accountId(txn.getAccountId())
                .status(CaseStatus.OPEN)
                .flagReason(reason)
                .ruleScore(score)
                .createdAt(Instant.now())
                .build();
        fraudCaseRepository.save(fraudCase);
        meterRegistry.counter("sentinel.cases.opened").increment();
        caseEventBroadcaster.broadcast(FraudCaseSummary.of(fraudCase, txn));
        log.info("Opened fraud case {} for transaction {} — {}",
                fraudCase.getCaseId(), txn.getTransactionId(), reason);

        caseEventKafkaTemplate.send(casesOpenedTopic, fraudCase.getAccountId(),
                FraudCaseOpenedEvent.builder()
                        .caseId(fraudCase.getCaseId())
                        .transactionId(txn.getTransactionId())
                        .accountId(fraudCase.getAccountId())
                        .build());
    }
}
