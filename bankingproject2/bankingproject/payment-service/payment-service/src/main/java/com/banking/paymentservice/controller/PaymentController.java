
package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/payments")
@Slf4j
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping("/create-order")
    public ResponseEntity<PaymentOrderResponse> createPaymentOrder(
            @Valid @RequestBody CreatePaymentRequest request)
            throws RazorpayException {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createPaymentOrder(request));
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(
                    value = "X-Razorpay-Signature",
                    required = false
            ) String signature) {

        // A missing signature must never be accepted.
        if (signature == null || signature.isBlank()) {
            log.warn("Rejected Razorpay webhook: signature is missing");
            return ResponseEntity.badRequest()
                    .body("Missing webhook signature");
        }

        try {
            // Verify the signature against the ORIGINAL, unmodified body.
            if (!isValidWebhookSignature(rawBody, signature)) {
                log.warn("Rejected Razorpay webhook: invalid signature");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid webhook signature");
            }

            // Parse JSON only after signature verification succeeds.
            Map<String, Object> payload = objectMapper.readValue(
                    rawBody,
                    new TypeReference<Map<String, Object>>() {}
            );

            paymentService.handleWebhook(payload);

            return ResponseEntity.ok("Webhook processed");

        } catch (JsonProcessingException e) {
            log.warn("Rejected Razorpay webhook: invalid JSON");
            return ResponseEntity.badRequest()
                    .body("Invalid webhook payload");

        } catch (Exception e) {
            log.error("Failed to process verified Razorpay webhook", e);

            // A server error allows Razorpay to retry delivery.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Webhook processing failed");
        }
    }

    private boolean isValidWebhookSignature(
            String rawBody,
            String receivedSignature) throws Exception {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "Razorpay webhook secret is not configured"
            );
        }

        Mac mac = Mac.getInstance("HmacSHA256");

        SecretKeySpec keySpec = new SecretKeySpec(
                webhookSecret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );

        mac.init(keySpec);

        byte[] calculatedHash = mac.doFinal(
                rawBody.getBytes(StandardCharsets.UTF_8)
        );

        String expectedSignature = HexFormat.of()
                .formatHex(calculatedHash);

        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                receivedSignature.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
