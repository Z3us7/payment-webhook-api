package com.payments.api.repository;

import com.payments.api.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByRazorpayOrderId(String razorpayOrderId);
    boolean existsByRazorpayEventId(String razorpayEventId);
}
