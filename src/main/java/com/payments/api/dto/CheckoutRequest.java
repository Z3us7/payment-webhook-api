package com.payments.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutRequest(
        UUID userId,
        String productName,
        BigDecimal amount,
        String currency
) {}
