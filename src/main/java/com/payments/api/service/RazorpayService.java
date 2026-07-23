package com.payments.api.service;

import com.payments.api.dto.CheckoutRequest;
import com.payments.api.entity.Order;
import com.payments.api.entity.User;
import com.payments.api.repository.OrderRepository;
import com.payments.api.repository.UserRepository;
import com.payments.api.repository.TransactionLogRepository;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class RazorpayService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final TransactionLogRepository transactionLogRepository;
    private final RazorpayClient razorpayClient;

    @Transactional
    public String createRazorpayOrder(CheckoutRequest request) {
        User user;
        if (request.userId() != null) {
            user = userRepository.findById(request.userId())
                    .orElseThrow(() -> new RuntimeException("User not found with ID: " + request.userId()));
        } else {
            // Find-or-create a guest user so the test flow never crashes on a missing userId.
            user = userRepository.findByEmail("test@webhook.dev")
                    .orElseGet(() -> userRepository.save(
                            User.builder().email("test@webhook.dev").build()
                    ));
        }

        int amountInPaise = request.amount().multiply(new BigDecimal("100")).intValue();
        String currency = request.currency() != null ? request.currency() : "INR";

        Order order = Order.builder()
                .user(user)
                .productName(request.productName())
                .amount(request.amount())
                .currency(currency)
                .status("CREATED")
                .build();

        order = orderRepository.save(order);

        try {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountInPaise);
            orderRequest.put("currency", currency);
            orderRequest.put("receipt", order.getId().toString());

            com.razorpay.Order razorpayOrder = razorpayClient.orders.create(orderRequest);
            String razorpayOrderId = razorpayOrder.get("id");

            order.setRazorpayOrderId(razorpayOrderId);
            orderRepository.save(order);

            return razorpayOrderId;
        } catch (RazorpayException e) {
            throw new RuntimeException("Failed to create Razorpay order", e);
        }
    }

    public String getPaymentBank(String paymentId) {
        try {
            com.razorpay.Payment payment = razorpayClient.payments.fetch(paymentId);
            String method = payment.get("method");
            
            if ("netbanking".equals(method)) {
                return payment.get("bank");
            } else if ("wallet".equals(method)) {
                return payment.get("wallet");
            } else if ("upi".equals(method)) {
                return "UPI";
            } else if ("card".equals(method)) {
                // For cards, Razorpay sometimes gives network (e.g. Visa)
                JSONObject card = payment.get("card");
                if (card != null && card.has("network")) {
                    return card.getString("network");
                }
                return "CARD";
            }
            return method != null ? method.toUpperCase() : "UNKNOWN";
        } catch (RazorpayException e) {
            log.error("Failed to fetch payment details for " + paymentId, e);
            return "UNKNOWN";
        }
    }

    @Transactional
    public void processWebhook(JSONObject json) {
        String eventType = json.getString("event");
        
        // Razorpay webhooks have event ID at the root level or within headers.
        // Since razorpay_event_id isn't always at root, we use the payment/order id as unique fallback,
        // but typically webhooks have an account_id or similar. Let's assume we extract it from the header in controller, 
        // or generate one. Let's check if there is an event id.
        // Actually, Razorpay doesn't send an event id in the payload root. The header "X-Razorpay-Event-Id" contains it.
        // For now, we will use the payment ID to check idempotency if event ID is not available.
        // Let's rely on Razorpay's entity.id.
        
        if (!json.has("payload")) {
            return;
        }

        JSONObject payload = json.getJSONObject("payload");
        
        if ("order.paid".equals(eventType) || "payment.captured".equals(eventType)) {
            JSONObject paymentEntity = payload.has("payment") ? payload.getJSONObject("payment").getJSONObject("entity") : null;
            JSONObject orderEntity = payload.has("order") ? payload.getJSONObject("order").getJSONObject("entity") : null;
            
            String razorpayOrderId = null;
            String paymentId = null;

            if (orderEntity != null) {
                razorpayOrderId = orderEntity.getString("id");
            } else if (paymentEntity != null) {
                razorpayOrderId = paymentEntity.optString("order_id");
                paymentId = paymentEntity.getString("id");
            }
            
            if (razorpayOrderId == null) {
                log.warn("Webhook received without order_id: {}", eventType);
                return;
            }

            // Simple idempotency: use paymentId + eventType as event identifier
            String eventId = paymentId != null ? paymentId + "-" + eventType : razorpayOrderId + "-" + eventType;

            if (transactionLogRepository.findByRazorpayEventId(eventId).isPresent()) {
                log.info("Idempotency check passed: Event {} already processed", eventId);
                return; // Already processed
            }

            final String finalOrderId = razorpayOrderId;
            Order order = orderRepository.findByRazorpayOrderId(finalOrderId)
                    .orElseThrow(() -> new RuntimeException("Order not found with razorpayOrderId: " + finalOrderId));

            // Idempotency for status update
            if (!"PAID".equals(order.getStatus())) {
                order.setStatus("PAID");
                order.setRazorpayEventId(eventId);
                orderRepository.save(order);
                log.info("Order {} successfully updated to PAID", order.getId());
            } else {
                log.info("Order {} is already PAID. Skipping status update but logging event.", order.getId());
            }

            com.payments.api.entity.TransactionLog logEntry = com.payments.api.entity.TransactionLog.builder()
                    .order(order)
                    .razorpayEventId(eventId)
                    .eventType(eventType)
                    .build();
            
            transactionLogRepository.save(logEntry);
        }
    }
}
