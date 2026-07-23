package com.payments.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class PaymentWebhookApiApplication {

    public static void main(String[] args) {
        // Force Java to use standard UTC time so it stops sending "Calcutta"
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        SpringApplication.run(PaymentWebhookApiApplication.class, args);
    }
}
