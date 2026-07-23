package com.payments.api.controller;

import com.payments.api.dto.CheckoutRequest;
import com.payments.api.service.RazorpayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = { "*", "null" })
// @CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500",
// "http://localhost:3000"})

// TODO: Update with your frontend's production domain

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class CheckoutController {

    private final RazorpayService razorpayService;

    @PostMapping
    public ResponseEntity<java.util.Map<String, Object>> createOrder(@RequestBody CheckoutRequest request) {
        String orderId = razorpayService.createRazorpayOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(java.util.Map.of(
                        "status", "success",
                        "data", java.util.Map.of("orderId", orderId)
                ));
    }

    @GetMapping("/payment/{paymentId}/bank")
    public ResponseEntity<String> getPaymentBank(@PathVariable String paymentId) {
        String bank = razorpayService.getPaymentBank(paymentId);
        return ResponseEntity.ok(bank);
    }
}
