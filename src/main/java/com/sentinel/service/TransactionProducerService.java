package com.sentinel.service;

import com.sentinel.dto.TransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Publishes transaction events to Kafka. Also runs a scheduled simulator so
 * you can see the whole pipeline working end to end without needing a real
 * payments feed — useful for local dev and for demoing the project.
 * Set sentinel.simulator.enabled=false to turn the scheduler off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionProducerService {

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Value("${sentinel.kafka.topic}")
    private String topic;

    @Value("${sentinel.simulator.enabled:true}")
    private boolean simulatorEnabled;

    private static final List<String> ACCOUNTS =
            List.of("acct-1001", "acct-1002", "acct-1003", "acct-1004", "acct-1005");
    private static final List<String> MERCHANTS =
            List.of("Amazon", "Walmart", "Shell", "Steam", "Uber", "Delta Airlines", "Best Buy");
    private static final List<String> CATEGORIES =
            List.of("retail", "fuel", "gaming", "travel", "electronics", "dining");
    private static final List<String> COUNTRIES =
            List.of("US", "US", "US", "GB", "NG", "RU");

    public void publish(TransactionEvent event) {
        kafkaTemplate.send(topic, event.getAccountId(), event);
    }

    /** Every few seconds, emit a batch of mostly-normal transactions with occasional outliers. */
    @Scheduled(fixedDelayString = "${sentinel.simulator.interval-ms:4000}")
    public void simulate() {
        if (!simulatorEnabled) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int batchSize = rnd.nextInt(1, 4);

        for (int i = 0; i < batchSize; i++) {
            boolean isOutlier = rnd.nextInt(100) < 8; // ~8% anomalous
            String account = ACCOUNTS.get(rnd.nextInt(ACCOUNTS.size()));

            BigDecimal amount = isOutlier
                    ? BigDecimal.valueOf(rnd.nextDouble(3000, 15000)).setScale(2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(rnd.nextDouble(5, 300)).setScale(2, java.math.RoundingMode.HALF_UP);

            TransactionEvent event = TransactionEvent.builder()
                    .transactionId(UUID.randomUUID().toString())
                    .accountId(account)
                    .amount(amount)
                    .currency("USD")
                    .merchant(MERCHANTS.get(rnd.nextInt(MERCHANTS.size())))
                    .merchantCategory(CATEGORIES.get(rnd.nextInt(CATEGORIES.size())))
                    .country(isOutlier ? COUNTRIES.get(COUNTRIES.size() - 1) : "US")
                    .timestamp(Instant.now())
                    .build();

            publish(event);
        }
        log.debug("Simulated batch of {} transactions", batchSize);
    }
}
