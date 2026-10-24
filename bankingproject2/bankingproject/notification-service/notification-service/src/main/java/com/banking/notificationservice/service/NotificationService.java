
package com.banking.notificationservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {

    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOtpGenerated(@Payload Map<String, Object> payload) {
        try {
            String accountNumber = String.valueOf(payload.get("accountNumber"));
            String otp = String.valueOf(payload.get("otp"));
            String amount = String.valueOf(payload.get("amount"));
            String reason = String.valueOf(payload.get("reason"));

            sendAlert(
                    accountNumber,
                    "Transaction Verification Required",
                    String.format(
                            "Suspicious activity detected. Reason: %s. " +
                            "A transaction of %s is pending verification. " +
                            "Your OTP is: %s. Valid for 5 minutes.",
                            reason, amount, otp
                    )
            );
        } catch (Exception e) {
            log.error("Error handling OTP notification", e);
        }
    }

    @KafkaListener(topics = "transaction.completed")
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload) {
        try {
            String senderAccount =
                    String.valueOf(payload.get("senderAccountNumber"));
            String receiverAccount =
                    String.valueOf(payload.get("receiverAccountNumber"));
            String amount = String.valueOf(payload.get("amount"));

            // DEBIT ALERT
            sendAlert(
                    senderAccount,
                    "DEBIT ALERT",
                    String.format(
                            "%s debited from account %s",
                            amount, senderAccount
                    )
            );

            // CREDIT ALERT
            sendAlert(
                    receiverAccount,
                    "CREDIT ALERT",
                    String.format(
                            "%s credited to account %s",
                            amount, receiverAccount
                    )
            );

        } catch (Exception e) {
            log.error("Error handling completed transaction notification", e);
        }
    }

    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload) {
        try {
            String accountNumber =
                    String.valueOf(payload.get("accountNumber"));
            String reason = String.valueOf(payload.get("reason"));

            sendAlert(
                    accountNumber,
                    "SUSPICIOUS ACTIVITY DETECTED",
                    String.format(
                            "Suspicious activity detected for account %s. " +
                            "Reason: %s. Please contact your bank.",
                            accountNumber, reason
                    )
            );

        } catch (Exception e) {
            log.error("Error handling fraud notification", e);
        }
    }

    @KafkaListener(topics = "transaction.refunded")
    public void consumeTransactionRefunded(
            @Payload Map<String, Object> payload) {
        try {
            String senderAccount =
                    String.valueOf(payload.get("senderAccountNumber"));
            String amount = String.valueOf(payload.get("amount"));
            String reason = String.valueOf(payload.get("reason"));

            sendAlert(
                    senderAccount,
                    "REFUND PROCESSED",
                    String.format(
                            "Your transaction of %s was cancelled. " +
                            "Reason: %s. Amount %s has been refunded " +
                            "to account %s.",
                            amount, reason, amount, senderAccount
                    )
            );

        } catch (Exception e) {
            log.error("Error handling refund notification", e);
        }
    }

    @KafkaListener(topics = "payment.completed")
    public void consumePaymentCompleted(
            @Payload Map<String, Object> payload) {
        try {
            String accountNumber =
                    String.valueOf(payload.get("accountNumber"));
            String amount = String.valueOf(payload.get("amount"));
            String razorpayPaymentId =
                    String.valueOf(payload.get("razorpayPaymentId"));

            sendAlert(
                    accountNumber,
                    "PAYMENT SUCCESSFUL",
                    String.format(
                            "Payment of %s completed. Razorpay ID: %s",
                            amount, razorpayPaymentId
                    )
            );

        } catch (Exception e) {
            log.error("Error handling successful payment notification", e);
        }
    }

    @KafkaListener(topics = "payment.failed")
    public void consumePaymentFailed(
            @Payload Map<String, Object> payload) {
        try {
            String accountNumber =
                    String.valueOf(payload.get("accountNumber"));
            String amount = String.valueOf(payload.get("amount"));

            sendAlert(
                    accountNumber,
                    "PAYMENT FAILED",
                    String.format(
                            "Your payment of %s could not be processed. " +
                            "Please try again or contact support.",
                            amount
                    )
            );

        } catch (Exception e) {
            log.error("Error handling failed payment notification", e);
        }
    }

    private void sendAlert(
            String accountNumber,
            String subject,
            String message) {

        log.info("---------------------------------------");
        log.info("Account: {}", accountNumber);
        log.info("Subject: {}", subject);
        log.info("Message: {}", message);
        log.info("---------------------------------------");
    }
}
