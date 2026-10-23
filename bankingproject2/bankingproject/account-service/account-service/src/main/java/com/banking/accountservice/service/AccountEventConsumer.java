
package com.banking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;

    /**
     * Consume transaction.completed event from Kafka
     * credits to receiver accounts
     */
    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload) {
        try {
            String receiverAccount =
                    (String) payload.get("receiverAccountNumber");

            BigDecimal amount =
                    new BigDecimal(payload.get("amount").toString());

            log.info("Crediting account: {}, amount {}",
                    receiverAccount, amount);

            accountService.creditBalance(receiverAccount, amount);

        } catch (Exception e) {
            log.error("Error crediting account: {}", e.getMessage());
        }
    }
    @KafkaListener(topics="fraud.detected")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {
        try {
            String receiverAccount =
                    (String) payload.get("receiverAccountNumber");
            log.info(" fraud detected blocking account: {}",receiverAccount);
            accountService.blockAccount(receiverAccount);
        }
        catch (Exception e) {
            log.error("Error blocking account: {}", e.getMessage());

        }

    }
}
