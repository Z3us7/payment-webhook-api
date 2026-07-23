package com.payments.api.repository;

import com.payments.api.entity.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, UUID> {
    Optional<TransactionLog> findByRazorpayEventId(String razorpayEventId);
}
