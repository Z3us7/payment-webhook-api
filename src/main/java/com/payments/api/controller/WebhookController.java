package com.payments.api.controller;

import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final com.payments.api.service.RazorpayService razorpayService;

    @Value("${razorpay.webhook.secret}")
    private String secret;

    @PostMapping
    public ResponseEntity<String> handleRazorpayWebhook(org.springframework.http.HttpEntity<String> httpEntity,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        try {
            String rawBody = httpEntity.getBody();
            if (signature == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing signature");
            }
            
            // verifyWebhookSignature throws RazorpayException on signature mismatch
            Utils.verifyWebhookSignature(rawBody, signature, secret);

            JSONObject json = new JSONObject(rawBody);
            String eventType = json.getString("event");
            System.out.println("Received Razorpay Event: " + eventType);

            razorpayService.processWebhook(json);

            return ResponseEntity.ok("Webhook processed");
        } catch (com.razorpay.RazorpayException e) {
            System.err.println("Webhook signature verification failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature");
        } catch (Exception e) {
            System.err.println("Webhook processing failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
