
        package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String VERIFICATION_REQUIRED_TOPIC =
            "verification.required";

    private static final String FRAUD_CHECK_CLEAN_RESULT =
            "fraud.check.clean";

    @Value("${fraud.max-transactions-per-minute}")
    private int maxTransactionsPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    public void checkTransaction(Map<String, Object> map) {

        log.info("Received transaction event payload: {}", map);

        if (map == null
                || map.get("transactionId") == null
                || map.get("senderAccountNumber") == null
                || map.get("amount") == null) {

            log.error("Invalid transaction event payload: {}", map);
            return;
        }

        String transactionId =
                map.get("transactionId").toString();

        String accountNumber =
                map.get("senderAccountNumber").toString();

        BigDecimal amount;

        try {
            amount = new BigDecimal(map.get("amount").toString());

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                log.error(
                        "Invalid transaction amount for transaction {}: {}",
                        transactionId, amount
                );
                return;
            }
        } catch (NumberFormatException e) {
            log.error(
                    "Invalid amount in transaction event: {}",
                    map, e
            );
            return;
        }

        try {
            BigDecimal senderBalance =
                    accountServiceClient.getBalance(accountNumber);

            if (senderBalance == null) {
                log.error(
                        "Could not retrieve balance for account {}",
                        accountNumber
                );
                return;
            }

            log.info(
                    "Checking transaction: {}, account: {}, amount: {}, balance: {}",
                    transactionId, accountNumber, amount, senderBalance
            );

            FraudCheckResult result =
                    performFraudChecks(
                            accountNumber, amount, senderBalance
                    );

            if (result.isFraud()) {

                log.warn(
                        "Suspicious activity detected for account {}. Reason: {}",
                        accountNumber, result.getReason()
                );

                Map<String, Object> verificationEvent =
                        new HashMap<>();

                verificationEvent.put("transactionId", transactionId);
                verificationEvent.put("accountNumber", accountNumber);
                verificationEvent.put("amount", amount);
                verificationEvent.put("reason", result.getReason());

                kafkaTemplate.send(
                        VERIFICATION_REQUIRED_TOPIC,
                        transactionId,
                        verificationEvent
                ).whenComplete((sendResult, exception) -> {
                    if (exception != null) {
                        log.error(
                                "Failed to publish verification-required event for transaction {}",
                                transactionId, exception
                        );
                    } else {
                        log.info(
                                "Verification-required event published for transaction {}",
                                transactionId
                        );
                    }
                });

            } else {

                log.info(
                        "Transaction classified as clean: {}",
                        transactionId
                );

                Map<String, Object> cleanEvent =
                        new HashMap<>();

                cleanEvent.put("transactionId", transactionId);
                cleanEvent.put("isFraud", false);
                cleanEvent.put("reason", null);

                kafkaTemplate.send(
                        FRAUD_CHECK_CLEAN_RESULT,
                        transactionId,
                        cleanEvent
                ).whenComplete((sendResult, exception) -> {
                    if (exception != null) {
                        log.error(
                                "Failed to publish clean result for transaction {}",
                                transactionId, exception
                        );
                    } else {
                        log.info(
                                "Fraud-check-clean event published for transaction {}",
                                transactionId
                        );
                    }
                });
            }

        } catch (Exception e) {
            log.error(
                    "Fraud check failed for transaction {} and account {}",
                    transactionId, accountNumber, e
            );
            throw e;
        }
    }

    private FraudCheckResult performFraudChecks(
            String accountNumber,
            BigDecimal amount,
            BigDecimal senderBalance) {

        // Pattern 1: Velocity check
        if (isVelocityExceeded(accountNumber)) {
            return new FraudCheckResult(
                    true,
                    "Too many transactions in 60 seconds - Velocity limit exceeded"
            );
        }

        // Pattern 2: Amount check
        if (isAmountSuspicious(accountNumber, amount)) {
            return new FraudCheckResult(
                    true,
                    "Unusual transaction amount - exceeds the configured average multiplier"
            );
        }

        // Pattern 3: Balance check
        if (senderBalance.compareTo(BigDecimal.ZERO) > 0
                && isBalanceCheckFailed(senderBalance, amount)) {

            return new FraudCheckResult(
                    true,
                    "Transaction exceeds the configured percentage of account balance"
            );
        }

        return new FraudCheckResult(false, null);
    }

    private boolean isVelocityExceeded(String accountNumber) {

        String key = "fraud:velocity:" + accountNumber;

        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        log.info(
                "Velocity check - account: {}, count: {}, limit: {}",
                accountNumber, count, maxTransactionsPerMinute
        );

        return count != null && count > maxTransactionsPerMinute;
    }

    private boolean isAmountSuspicious(
            String accountNumber,
            BigDecimal amount) {

        String avgKey = "fraud:avg_amount:" + accountNumber;

        String avgStr = redisTemplate.opsForValue().get(avgKey);

        // First transaction establishes the initial reference amount.
        if (avgStr == null) {
            redisTemplate.opsForValue().set(avgKey, amount.toString());

            log.info(
                    "Initial average amount recorded for account {}: {}",
                    accountNumber, amount
            );

            return false;
        }

        BigDecimal avgAmount = new BigDecimal(avgStr);

        BigDecimal threshold = avgAmount.multiply(
                BigDecimal.valueOf(suspiciousAmountMultiplier)
        );

        boolean suspicious = amount.compareTo(threshold) > 0;

        log.info(
                "Amount check - amount: {}, average: {}, threshold: {}, suspicious: {}",
                amount, avgAmount, threshold, suspicious
        );

        // Update the running reference amount.
        BigDecimal newAvg = avgAmount.add(amount)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        redisTemplate.opsForValue().set(avgKey, newAvg.toString());

        return suspicious;
    }

    private boolean isBalanceCheckFailed(
            BigDecimal senderBalance,
            BigDecimal amount) {

        BigDecimal maxAllowed = senderBalance.multiply(
                BigDecimal.valueOf(maxBalancePercentage)
        );

        boolean suspicious = amount.compareTo(maxAllowed) > 0;

        log.info(
                "Balance check - amount: {}, max allowed: {}, suspicious: {}",
                amount, maxAllowed, suspicious
        );

        return suspicious;
    }
}
