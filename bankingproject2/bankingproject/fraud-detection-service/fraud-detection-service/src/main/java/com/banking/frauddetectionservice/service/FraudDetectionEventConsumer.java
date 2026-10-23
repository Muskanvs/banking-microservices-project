package com.banking.frauddetectionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionEventConsumer {

    private final FraudDetectionService fraudDetectionService;

    /**
     * Listens to the transaction.initiated topic.
     * Every transaction goes through a fraud check before completing.
     *
     * @param payload transaction event received from Kafka
     */
    @KafkaListener(
            topics = "transaction.initiated",
            groupId = "fraud-detection-group"
    )
    public void consumeTransactionInitiated(
            @Payload Map<String, Object> payload) {

        log.info("Received transaction for fraud check: {}",
                payload.get("transactionId"));

        try {
            fraudDetectionService.checkTransaction(payload);

            log.info("Fraud check processing finished for transaction: {}",
                    payload.get("transactionId"));

        } catch (Exception e) {
            log.error("Fraud check failed for transaction: {}",
                    payload.get("transactionId"), e);
        }
    }
}
