
package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String PAYMENT_COMPLETED_TOPIC =
            "payment.completed";

    private static final String PAYMENT_FAILED_TOPIC =
            "payment.failed";

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    /**
     * Creates a Razorpay order and saves the corresponding payment record.
     *
     * The amount supplied by the client is in INR.
     * Razorpay expects the amount in paise.
     */
    public PaymentOrderResponse createPaymentOrder(
            CreatePaymentRequest request) throws RazorpayException {

        BigDecimal amount = request.getAmount();

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero"
            );
        }

        final int amountInPaise;

        try {
            amountInPaise = amount
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount must have at most two decimal places "
                            + "and fit within the supported range",
                    e
            );
        }

        log.info(
                "Creating payment order for account: {}",
                request.getAccountNumber()
        );

        RazorpayClient razorpayClient =
                new RazorpayClient(keyId, keySecret);

        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", amountInPaise);
        orderRequest.put("currency", "INR");
        orderRequest.put(
                "receipt",
                "rcpt_" + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
        );

        Order razorpayOrder =
                razorpayClient.orders.create(orderRequest);

        String orderId = razorpayOrder.get("id").toString();

        Payment payment = new Payment();
        payment.setRazorpayOrderId(orderId);
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(
                amount.setScale(2, RoundingMode.UNNECESSARY)
        );
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        // If this save fails, reconcile the Razorpay order
        // before attempting to create another one.
        Payment savedPayment = paymentRepository.save(payment);

        log.info(
                "Payment record created with ID: {}",
                savedPayment.getId()
        );

        return new PaymentOrderResponse(
                savedPayment.getId(),
                orderId,
                savedPayment.getAmount(),
                savedPayment.getCurrency(),
                savedPayment.getStatus().name(),
                keyId
        );
    }

    /**
     * Processes a webhook only after PaymentController has verified
     * the Razorpay webhook signature.
     */
    @Transactional
    public void handleWebhook(Map<String, Object> payload) {

        Object eventValue = payload.get("event");

        if (!(eventValue instanceof String eventType)
                || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "Webhook event type is missing or invalid"
            );
        }

        log.info("Processing Razorpay event: {}", eventType);

        switch (eventType) {
            case "payment.captured" ->
                    handlePaymentSuccess(payload);

            case "payment.failed" ->
                    handlePaymentFailure(payload);

            default ->
                    log.info(
                            "Ignoring unsupported Razorpay event: {}",
                            eventType
                    );
        }
    }

    private void handlePaymentSuccess(
            Map<String, Object> payload) {

        try {
            Map<String, Object> paymentData =
                    extractPaymentData(payload);

            String orderId =
                    requiredString(paymentData, "order_id");

            String razorpayPaymentId =
                    requiredString(paymentData, "id");

            validateCapturedPayment(paymentData);

            Payment payment = findPayment(orderId);

            validateAmountAndCurrency(paymentData, payment);

            if (payment.getStatus() == PaymentStatus.COMPLETED) {

                if (razorpayPaymentId.equals(
                        payment.getRazorpayPaymentId())) {

                    log.info(
                            "Duplicate capture ignored for payment: {}",
                            payment.getId()
                    );
                    return;
                }

                throw new IllegalStateException(
                        "A different Razorpay payment is already "
                                + "recorded for order " + orderId
                );
            }

            if (payment.getStatus() == PaymentStatus.FAILED
                    || payment.getStatus() == PaymentStatus.REFUNDED) {
                throw new IllegalStateException(
                        "Cannot complete payment in status: "
                                + payment.getStatus()
                );
            }

            payment.setRazorpayPaymentId(razorpayPaymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setFailureReason(null);

            Payment savedPayment =
                    paymentRepository.saveAndFlush(payment);

            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", savedPayment.getId());
            event.put("accountNumber", savedPayment.getAccountNumber());
            event.put("amount", savedPayment.getAmount());
            event.put("razorpayPaymentId", razorpayPaymentId);

            publishEvent(
                    PAYMENT_COMPLETED_TOPIC,
                    savedPayment.getId(),
                    event
            );

            log.info(
                    "Payment marked COMPLETED: {}",
                    savedPayment.getId()
            );

        } catch (Exception e) {
            log.error("Failed to process payment success webhook", e);

            throw new IllegalStateException(
                    "Failed to process payment success webhook",
                    e
            );
        }
    }

    private void handlePaymentFailure(
            Map<String, Object> payload) {

        try {
            Map<String, Object> paymentData =
                    extractPaymentData(payload);

            String orderId =
                    requiredString(paymentData, "order_id");

            String razorpayPaymentId =
                    requiredString(paymentData, "id");

            Payment payment = findPayment(orderId);

            validateAmountAndCurrency(paymentData, payment);

            // Never let a late failure notification overwrite a success.
            if (payment.getStatus() == PaymentStatus.COMPLETED
                    || payment.getStatus() == PaymentStatus.REFUNDED) {

                log.warn(
                        "Ignoring failure event for payment {} in status {}",
                        payment.getId(),
                        payment.getStatus()
                );
                return;
            }

            if (payment.getStatus() == PaymentStatus.FAILED) {
                log.info(
                        "Duplicate failure event ignored for payment: {}",
                        payment.getId()
                );
                return;
            }

            payment.setRazorpayPaymentId(razorpayPaymentId);
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(
                    "Payment failed via Razorpay"
            );

            Payment savedPayment =
                    paymentRepository.saveAndFlush(payment);

            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", savedPayment.getId());
            event.put("accountNumber", savedPayment.getAccountNumber());
            event.put("amount", savedPayment.getAmount());
            event.put("reason", savedPayment.getFailureReason());

            publishEvent(
                    PAYMENT_FAILED_TOPIC,
                    savedPayment.getId(),
                    event
            );

            log.warn(
                    "Payment marked FAILED: {}",
                    savedPayment.getId()
            );

        } catch (Exception e) {
            log.error("Failed to process payment failure webhook", e);

            throw new IllegalStateException(
                    "Failed to process payment failure webhook",
                    e
            );
        }
    }

    private Payment findPayment(String orderId) {
        return paymentRepository
                .findByRazorpayOrderId(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment not found for Razorpay order: " + orderId
                ));
    }

    private void validateCapturedPayment(
            Map<String, Object> paymentData) {

        if (!"captured".equals(paymentData.get("status"))) {
            throw new IllegalArgumentException(
                    "Payment is not in captured status"
            );
        }
    }

    private void validateAmountAndCurrency(
            Map<String, Object> paymentData,
            Payment payment) {

        Object rawAmount = paymentData.get("amount");
        Object rawCurrency = paymentData.get("currency");

        if (!(rawAmount instanceof Number amountNumber)) {
            throw new IllegalArgumentException(
                    "Webhook amount is missing or invalid"
            );
        }

        if (!(rawCurrency instanceof String currency)) {
            throw new IllegalArgumentException(
                    "Webhook currency is missing or invalid"
            );
        }

        long receivedPaise = new BigDecimal(
                amountNumber.toString()
        ).longValueExact();

        long expectedPaise = payment.getAmount()
                .movePointRight(2)
                .longValueExact();

        if (receivedPaise != expectedPaise) {
            throw new IllegalArgumentException(
                    "Webhook amount does not match the stored payment"
            );
        }

        if (!payment.getCurrency().equals(currency)) {
            throw new IllegalArgumentException(
                    "Webhook currency does not match the stored payment"
            );
        }
    }

    private String requiredString(
            Map<String, Object> data,
            String key) {

        Object value = data.get(key);

        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing or invalid webhook field: " + key
            );
        }

        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPaymentData(
            Map<String, Object> payload) {

        Object rawPayload = payload.get("payload");

        if (!(rawPayload instanceof Map<?, ?> payloadMap)) {
            throw new IllegalArgumentException(
                    "Webhook payload section is missing"
            );
        }

        Object rawPayment = payloadMap.get("payment");

        if (!(rawPayment instanceof Map<?, ?> paymentMap)) {
            throw new IllegalArgumentException(
                    "Webhook payment section is missing"
            );
        }

        Object rawEntity = paymentMap.get("entity");

        if (!(rawEntity instanceof Map<?, ?> entityMap)) {
            throw new IllegalArgumentException(
                    "Webhook payment entity is missing"
            );
        }

        return (Map<String, Object>) entityMap;
    }

    private void publishEvent(
            String topic,
            String key,
            Map<String, Object> event) {

        try {
            kafkaTemplate.send(topic, key, event)
                    .get(10, TimeUnit.SECONDS);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to publish Kafka event to " + topic,
                    e
            );
        }
    }
}
