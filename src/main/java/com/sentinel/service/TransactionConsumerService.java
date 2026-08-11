package com.sentinel.service;

import com.sentinel.domain.CaseStatus;
import com.sentinel.domain.FraudCase;
import com.sentinel.domain.Transaction;
import com.sentinel.dto.TransactionEvent;
import com.sentinel.repository.FraudCaseRepository;
import com.sentinel.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Entry point of the pipeline: consumes raw transaction events off Kafka,
 * persists them, and runs the rules engine. Anything flagged opens a
 * FraudCase, which the AI investigation agent (next phase) picks up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionConsumerService {

    private final TransactionRepository transactionRepository;
    private final FraudCaseRepository fraudCaseRepository;
    private final RulesEngineService rulesEngineService;

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

        RulesEngineService.RuleResult result = rulesEngineService.evaluate(txn);
        txn.setFlagged(result.flagged());
        txn.setAnomalyScore(result.score());
        transactionRepository.save(txn);

        if (result.flagged()) {
            openCase(txn, result);
        }
    }

    private void openCase(Transaction txn, RulesEngineService.RuleResult result) {
        FraudCase fraudCase = FraudCase.builder()
                .transactionId(txn.getTransactionId())
                .accountId(txn.getAccountId())
                .status(CaseStatus.OPEN)
                .flagReason(result.reason())
                .ruleScore(result.score())
                .createdAt(Instant.now())
                .build();
        fraudCaseRepository.save(fraudCase);
        log.info("Opened fraud case {} for transaction {} — {}",
                fraudCase.getCaseId(), txn.getTransactionId(), result.reason());
    }
}
