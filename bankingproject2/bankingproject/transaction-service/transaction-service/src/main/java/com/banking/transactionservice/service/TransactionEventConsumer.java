
        package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionService transactionService;

    private static final long OTP_EXPIRY_MINUTES = 5;

    private static final String TRANSACTION_OTP_GENERATED_TOPIC =
            "transaction.otp.generated";

    private static final String VERIFICATION_REQUIRED_TOPIC =
            "verification.required";

    private static final String FRAUD_CHECK_CLEAN_TOPIC =
            "fraud.check.clean";

    private static final String OTP_REDIS_KEY_PREFIX =
            "verification:otp:";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @KafkaListener(
            topics = VERIFICATION_REQUIRED_TOPIC,
            groupId = "transaction-service-group"
    )
    public void consumeVerificationRequired(
            @Payload Map<String, Object> payload) {

        String logTransactionId = "unknown";

        try {
            log.info("Received verification-required event: {}", payload);

            if (payload == null || payload.get("transactionId") == null) {
                log.error(
                        "Verification event is missing transactionId: {}",
                        payload
                );
                return;
            }

            // Assign transactionId only once so it is effectively final.
            final String transactionId =
                    payload.get("transactionId").toString();

            logTransactionId = transactionId;

            String accountNumber = payload.get("accountNumber") == null
                    ? null
                    : payload.get("accountNumber").toString();

            String reason = payload.get("reason") == null
                    ? null
                    : payload.get("reason").toString();

            Transaction transaction = transactionRepository
                    .findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Transaction not found: " + transactionId
                    ));

            if (transaction.getStatus() != TransactionStatus.PROCESSING) {
                log.warn(
                        "Transaction {} has status {} instead of PROCESSING; OTP generation skipped",
                        transactionId,
                        transaction.getStatus()
                );
                return;
            }

            // Generate a secure six-digit OTP.
            String otp = String.format(
                    "%06d",
                    SECURE_RANDOM.nextInt(900000) + 100000
            );

            // Store the OTP in Redis for five minutes.
            String otpKey = OTP_REDIS_KEY_PREFIX + transactionId;

            redisTemplate.opsForValue().set(
                    otpKey,
                    otp,
                    OTP_EXPIRY_MINUTES,
                    TimeUnit.MINUTES
            );

            // Move the transaction into verification-pending state.
            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

            // Development only: do not log OTPs in production.
            log.info(
                    "DEV ONLY: OTP for transaction {} is {}",
                    transactionId,
                    otp
            );
            log.info(
                    "OTP expires in {} minutes",
                    OTP_EXPIRY_MINUTES
            );

            // Publish OTP details for downstream notification components.
            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId", transactionId);
            otpEvent.put("accountNumber", accountNumber);
            otpEvent.put("reason", reason);
            otpEvent.put("otp", otp);
            otpEvent.put("amount", transaction.getAmount());

            kafkaTemplate.send(
                    TRANSACTION_OTP_GENERATED_TOPIC,
                    transactionId,
                    otpEvent
            ).whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error(
                            "Failed to publish OTP-generated event for transaction {}",
                            transactionId,
                            exception
                    );
                } else {
                    log.info(
                            "OTP-generated event published for transaction {}",
                            transactionId
                    );
                }
            });

        } catch (Exception e) {
            log.error(
                    "Error handling verification-required event for transaction {}",
                    logTransactionId,
                    e
            );
        }
    }

    @KafkaListener(
            topics = FRAUD_CHECK_CLEAN_TOPIC,
            groupId = "transaction-service-group"
    )
    public void consumeFraudCheckCleanResult(
            @Payload Map<String, Object> payload) {

        try {
            log.info("Received fraud-check-clean event: {}", payload);

            if (payload == null || payload.get("transactionId") == null) {
                log.error(
                        "Clean-result event is missing transactionId: {}",
                        payload
                );
                return;
            }

            String transactionId =
                    payload.get("transactionId").toString();

            transactionService.processCleanResult(transactionId);

        } catch (Exception e) {
            log.error("Error processing fraud-check-clean event", e);
        }
    }
}
