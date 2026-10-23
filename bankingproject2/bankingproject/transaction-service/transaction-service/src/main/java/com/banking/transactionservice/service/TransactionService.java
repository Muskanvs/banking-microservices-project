
package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private static final String TRANSACTION_INITIATED_TOPIC =
            "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC =
            "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC =
            "transaction.refunded";

    /**
     * Initiates a transfer.
     * Deducts money from the sender, saves the transaction,
     * and publishes an event for fraud checking.
     */
    public TransactionResponse transfer(TransferRequest request) {

        // Remove accidental leading and trailing spaces.
        String senderAccountNumber =
                request.getSenderAccountNumber().trim();

        String receiverAccountNumber =
                request.getReceiverAccountNumber().trim();

        log.info(
                "SAGA START - Transfer: {} -> {} amount: {}",
                senderAccountNumber,
                receiverAccountNumber,
                request.getAmount()
        );

        // Step 1: Deduct money from the sender.
        accountServiceClient.deductBalance(
                senderAccountNumber,
                request.getAmount()
        );

        // Step 2: Create the transaction.
        Transaction transaction = new Transaction();

        transaction.setSenderAccountNumber(senderAccountNumber);
        transaction.setReceiverAccountNumber(receiverAccountNumber);
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        // Step 3: Save the transaction.
        Transaction savedTransaction =
                transactionRepository.save(transaction);

        log.info(
                "Transaction saved as PROCESSING: {}",
                savedTransaction.getId()
        );

        // Step 4: Create the Kafka event.
        TransactionInitiatedEvent event =
                new TransactionInitiatedEvent(
                        savedTransaction.getId(),
                        savedTransaction.getSenderAccountNumber(),
                        savedTransaction.getReceiverAccountNumber(),
                        savedTransaction.getAmount(),
                        savedTransaction.getDescription()
                );

        // Step 5: Publish the event for fraud checking.
        kafkaTemplate.send(
                TRANSACTION_INITIATED_TOPIC,
                savedTransaction.getId(),
                event
        );

        log.info(
                "Transaction initiated event published: {}",
                savedTransaction.getId()
        );

        // Step 6: Return the response.
        return mapToResponse(savedTransaction);
    }

    public TransactionResponse getTransaction(String transactionId) {

        Transaction transaction = transactionRepository
                .findById(transactionId)
                .orElseThrow(() -> new RuntimeException(
                        "Transaction not found: " + transactionId
                ));

        return mapToResponse(transaction);
    }


    public List<TransactionResponse> getTransactionHistory(String accountNumber) {

        String cleanedAccountNumber = accountNumber.trim();

        List<Transaction> transactions = transactionRepository
                .findBySenderAccountNumberOrReceiverAccountNumberOrderByCreatedAtDesc(
                        cleanedAccountNumber,
                        cleanedAccountNumber
                );

        return transactions.stream()
                .map(this::mapToResponse)
                .toList();
    }

    public TransactionResponse verifyOTP(
            String transactionID,
            String otp) {

        log.info(
                "OTP verification for transaction: {}",
                transactionID
        );

        Transaction transaction = transactionRepository
                .findById(transactionID)
                .orElseThrow(() -> new RuntimeException(
                        "Transaction not found: " + transactionID
                ));

        String otpKey = "verification:otp" + transactionID;
        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if (storedOtp == null) {
            // OTP expired.
            log.warn(
                    "OTP expired for transaction: {}",
                    transactionID
            );

            compensateTransaction(
                    transaction,
                    "OTP expired; transaction cancelled and amount refunded"
            );

            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)) {
            // Wrong OTP: publish a fraud event and refund the sender.
            log.warn(
                    "Wrong OTP - blocking account and refunding: {}",
                    transactionID
            );

            redisTemplate.delete(otpKey);

            blockAccountAndCompensate(
                    transaction,
                    "Wrong OTP entered - transaction cancelled, "
                            + "account blocked for security"
            );

            return mapToResponse(transaction);
        }

        // Correct OTP: complete the transaction.
        log.info(
                "OTP verified - completing transaction: {}",
                transactionID
        );

        redisTemplate.delete(otpKey);
        completeTransaction(transaction);

        return mapToResponse(transaction);
    }

    private void compensateTransaction(
            Transaction transaction,
            String reason) {

        log.warn(
                "SAGA COMPENSATION - refunding: {} amount: {}",
                transaction.getSenderAccountNumber(),
                transaction.getAmount()
        );

        // Credit the sender's account.
        accountServiceClient.creditBalance(
                transaction.getSenderAccountNumber().trim(),
                transaction.getAmount()
        );

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(
                reason
                        + " - SAGA compensation executed, amount refunded at "
                        + LocalDateTime.now()
        );

        transactionRepository.save(transaction);

        // Publish the refund event for Notification Service.
        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put(
                "senderAccountNumber",
                transaction.getSenderAccountNumber()
        );
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(
                TRANSACTION_REFUNDED_TOPIC,
                transaction.getId(),
                refundEvent
        );

        log.info(
                "SAGA COMPENSATION COMPLETE - {} refunded to {}",
                transaction.getAmount(),
                transaction.getSenderAccountNumber()
        );
    }

    private void blockAccountAndCompensate(
            Transaction transaction,
            String reason) {

        // Publish fraud.detected for the account-handling consumer.
        Map<String, Object> fraudEvent = new HashMap<>();
        fraudEvent.put("transactionId", transaction.getId());
        fraudEvent.put(
                "accountNumber",
                transaction.getSenderAccountNumber()
        );
        fraudEvent.put("reason", reason);

        kafkaTemplate.send(
                FRAUD_DETECTED_TOPIC,
                transaction.getSenderAccountNumber(),
                fraudEvent
        );

        log.warn(
                "fraud.detected published - account: {} may be blocked",
                transaction.getSenderAccountNumber()
        );

        // Refund the sender.
        compensateTransaction(transaction, reason);
    }

    public void processCleanResult(String transactionID) {

        Transaction transaction = transactionRepository
                .findById(transactionID)
                .orElseThrow(() -> new RuntimeException(
                        "Transaction not found: " + transactionID
                ));

        if (transaction.getStatus() != TransactionStatus.PROCESSING) {
            log.warn(
                    "Transaction {} not PROCESSING - skipping",
                    transactionID
            );
            return;
        }

        completeTransaction(transaction);
    }

    private void completeTransaction(Transaction transaction) {

        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        TransactionCompletedEvent completedEvent =
                new TransactionCompletedEvent(
                        transaction.getId(),
                        transaction.getSenderAccountNumber(),
                        transaction.getReceiverAccountNumber(),
                        transaction.getAmount(),
                        transaction.getDescription()
                );

        kafkaTemplate.send(
                TRANSACTION_COMPLETED_TOPIC,
                transaction.getId(),
                completedEvent
        );

        log.info(
                "SAGA COMPLETE - Transaction {} completed",
                transaction.getId()
        );
    }

    private TransactionResponse mapToResponse(Transaction transaction) {

        TransactionResponse response = new TransactionResponse();

        response.setId(transaction.getId());
        response.setSenderAccountNumber(
                transaction.getSenderAccountNumber()
        );
        response.setReceiverAccountNumber(
                transaction.getReceiverAccountNumber()
        );
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }
}
