# Payment Webhook API

A secure, production-ready **payment and webhook integration service** built with Java 17 and Spring Boot 3.x. It integrates with Razorpay to create payment orders and asynchronously process payment confirmations via signed webhooks, giving frontend applications a safe way to handle payments without ever touching sensitive card data.

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](#license)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)

**Languages:** ![Java](https://img.shields.io/badge/Java-45.3%25-b07219) ![CSS](https://img.shields.io/badge/CSS-33%25-563d7c) ![JavaScript](https://img.shields.io/badge/JavaScript-12.6%25-f1e05a) ![HTML](https://img.shields.io/badge/HTML-9.1%25-e34c26)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Languages](#languages)
- [Architecture](#architecture)
- [Database Schema](#database-schema)
- [API Reference](#api-reference)
- [Security](#security)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running with Docker](#running-with-docker)
- [Testing Webhooks Locally](#testing-webhooks-locally)
- [HTTP Status Codes](#http-status-codes)
- [License](#license)

---

## Overview

This API serves as a secure backend for any frontend application — React, plain HTML/JS, or mobile — that needs to process payments without directly handling sensitive card data. It's designed for small to mid-sized businesses and freelance developers who need reliable, self-hosted payment infrastructure built on Razorpay.

The service handles the full payment lifecycle: creating orders, verifying asynchronous webhook events cryptographically, and persisting an auditable transaction history — all while remaining idempotent against Razorpay's retry behavior.

## Features

- **Razorpay Order Creation** — Generates orders through the Razorpay SDK, converts amounts to paise, and persists them with a `CREATED` status before returning the `razorpay_order_id` to initialize the Razorpay checkout widget.
- **Secure Webhook Listener** — A dedicated `POST /api/v1/webhook` endpoint that receives asynchronous events (`order.paid`, `payment.captured`, `payment.failed`, etc.) and validates event type before acting on it.
- **Cryptographic Signature Verification** — Every webhook is verified against the `X-Razorpay-Signature` header using HMAC-SHA256, rejecting missing or invalid signatures with `400 Bad Request` to prevent spoofed events and man-in-the-middle attacks.
- **Idempotent Webhook Processing** — Each event is tracked by a unique `razorpayEventId`, so duplicate deliveries from Razorpay's retry mechanism are safely ignored — no double-counted payments, no constraint violations.
- **Database-Backed Order Tracking** — Full lifecycle persistence in PostgreSQL (`CREATED → PAID / FAILED`), with orders linked to users and every webhook event recorded in a `TransactionLog` audit trail.
- **Payment Method Resolution** — `GET /api/v1/orders/payment/{paymentId}/bank` resolves the instrument used for a payment (Net Banking, UPI, Wallet, or Card network such as Visa).
- **Global Exception Handling** — A `@RestControllerAdvice` layer intercepts unhandled exceptions and returns clean, JSend-compliant JSON instead of raw stack traces.
- **JSend-Standard Responses** — Every API response follows a consistent `{ status, data, message }` shape.
- **Environment-Based Configuration** — Secrets are loaded via `spring-dotenv` from a `.env` file; zero credentials are hardcoded in source.
- **Built-in Test UI** — A glassmorphism-styled `index.html`, served statically, with a live request log and mock order-lifecycle panel powered by Razorpay Checkout.js — no separate frontend server required.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2.4 |
| Web | Spring Web (REST) |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security |
| Database | PostgreSQL 15 |
| Payment Gateway | Razorpay Java SDK v1.4.9 |
| Containerization | Docker / Docker Compose |
| Build Tool | Maven |
| Utilities | Lombok, org.json, spring-dotenv |
| Frontend | Vanilla HTML/CSS/JS + Razorpay Checkout.js |

## Languages

| Language | Usage | Role in the Project |
|---|---|---|
| 🟠 Java | 45.3% | Core backend — controllers, services, entities, security, and business logic |
| 🟣 CSS | 33% | Styling for the built-in glassmorphism test UI |
| 🟡 JavaScript | 12.6% | Frontend checkout logic and Razorpay Checkout.js integration |
| 🔴 HTML | 9.1% | Markup for the static test UI (`index.html`) |

## Architecture

```
Frontend (Browser — localhost:8080/index.html)
       │
       │  POST /api/v1/orders  (Buy Now click)
       ▼
CheckoutController
       │
       ▼
RazorpayService ──► Razorpay API ──► Returns order_id
       │
       ▼
OrderRepository (PostgreSQL) — Status: CREATED

          ┌──────────────────────────────────────────┐
          │      Razorpay Payment Hosted Page         │
          │      (User completes payment here)        │
          └──────────────────┬─────────────────────────┘
                              │
               Razorpay sends async Webhook POST
                              │
                              ▼
                    WebhookController
                              │
               Verify X-Razorpay-Signature (HMAC-SHA256)
                              │
                     Check idempotency key
                              │
                     Update Order → PAID
                    + Append to TransactionLog
```

## Database Schema

```
USERS ||--o{ ORDERS : places
ORDERS ||--o{ TRANSACTIONS : has

USERS {
    UUID     id           PK
    string   email        UNIQUE
    datetime created_at
}

ORDERS {
    UUID    id                 PK
    UUID    user_id            FK
    string  product_name
    decimal amount
    string  currency
    string  status              -- CREATED, PAID, FAILED
    string  razorpay_order_id   UNIQUE
    string  razorpay_event_id   UNIQUE
    datetime created_at
}

TRANSACTIONS {
    UUID     id                PK
    UUID     order_id          FK
    string   razorpay_event_id UNIQUE  -- idempotency key
    string   event_type                -- e.g. order.paid, payment.captured
    datetime processed_at
}
```

## API Reference

### Order / Checkout

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/orders` | Public | Create a Razorpay order. Returns the `razorpay_order_id`. |
| `GET` | `/api/v1/orders/payment/{paymentId}/bank` | Public | Fetch the payment method/bank used for a given payment. |

**`POST /api/v1/orders`** — Request body:

```json
{
  "productName": "Premium Plan",
  "amount": 499.00,
  "currency": "INR"
}
```

Response:

```json
{
  "status": "success",
  "data": {
    "orderId": "order_abc123razorpay"
  }
}
```

### Webhook

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/webhook` | Public (Razorpay) | Receives and cryptographically verifies Razorpay webhook events. |

Required header from Razorpay:

```
X-Razorpay-Signature: <hmac-sha256-signature>
```

## Security

| Concern | Implementation |
|---|---|
| Webhook authenticity | HMAC-SHA256 signature verification via the Razorpay SDK |
| Invalid signatures | Rejected immediately with `400 Bad Request` |
| Missing signature header | Rejected immediately with `400 Bad Request` |
| Idempotency | Duplicate events silently ignored via `TransactionLog` |
| CSRF | Disabled (stateless REST API) |
| Session management | Stateless |
| Credentials | Loaded from `.env`; never hardcoded |

## Project Structure

```
payment-webhook-api/
├── src/main/java/com/payments/api/
│   ├── config/
│   │   ├── RazorpayConfig.java           # Razorpay SDK client bean
│   │   └── SecurityConfig.java           # Spring Security filter chain
│   ├── controller/
│   │   ├── CheckoutController.java       # POST /api/v1/orders
│   │   └── WebhookController.java        # POST /api/v1/webhook
│   ├── dto/
│   │   └── CheckoutRequest.java          # Immutable request record
│   ├── entity/
│   │   ├── User.java                     # Users table
│   │   ├── Order.java                    # Orders table
│   │   └── TransactionLog.java           # Transaction audit log
│   ├── exception/
│   │   └── GlobalExceptionHandler.java   # @RestControllerAdvice
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── OrderRepository.java
│   │   └── TransactionLogRepository.java
│   ├── service/
│   │   └── RazorpayService.java          # Business logic
│   └── PaymentWebhookApiApplication.java # Entry point
├── src/main/resources/
│   ├── application.yml                   # Spring Boot configuration
│   └── static/
│       ├── index.html                    # Test UI frontend
│       ├── styles.css                    # Glassmorphism UI styles
│       └── app.js                        # Frontend logic
├── pom.xml                               # Maven dependencies
├── docker-compose.yml                    # PostgreSQL container
├── .env.example                          # Environment variable template
└── .gitignore
```

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for PostgreSQL)
- A Razorpay account (free test mode available)

### Setup

1. **Clone the repository**

   ```bash
   git clone https://github.com/Z3us7/payment-webhook-api.git
   cd payment-webhook-api
   ```

2. **Configure environment variables**

   ```bash
   cp .env.example .env
   # Fill in your Razorpay and database credentials in .env
   ```

3. **Start PostgreSQL via Docker**

   ```bash
   docker-compose up -d
   ```

4. **Build and run the application**

   ```bash
   mvn spring-boot:run
   ```

5. **Open the test UI**

   ```
   http://localhost:8080/index.html
   ```

## Environment Variables

Copy `.env.example` to `.env` and populate the following values:

```env
# Docker / PostgreSQL
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_db_password
POSTGRES_DB=payments

# Spring Boot Database Connection
DB_USERNAME=postgres
DB_PASSWORD=your_db_password
DB_URL=jdbc:postgresql://localhost:5433/payments?options=-c%20timezone=UTC

# Razorpay API Credentials (from https://dashboard.razorpay.com/)
RAZORPAY_KEY_ID=rzp_test_xxxxxxxxxxxxxxxx
RAZORPAY_KEY_SECRET=your_razorpay_key_secret
RAZORPAY_WEBHOOK_SECRET=your_webhook_secret
```

> ⚠️ **Never commit your `.env` file to version control.** It is already excluded via `.gitignore`.

## Running with Docker

`docker-compose.yml` spins up a PostgreSQL 15 container:

```bash
docker-compose up -d
```

The database is exposed on port `5433` to avoid conflicts with a local PostgreSQL instance running on the default `5432`.

## Testing Webhooks Locally

Use [ngrok](https://ngrok.com/) to expose your local server so Razorpay can reach it:

```bash
ngrok http 8080
```

Copy the resulting HTTPS URL (e.g. `https://abc123.ngrok.app`) and set `https://abc123.ngrok.app/api/v1/webhook` as the webhook URL in your Razorpay Dashboard.

## HTTP Status Codes

| Code | Meaning |
|---|---|
| `200 OK` | Successful request |
| `201 Created` | New order generated |
| `400 Bad Request` | Invalid webhook / missing signature |
| `404 Not Found` | User or order not found |
| `500 Internal Server Error` | Razorpay API failure |

## License

This project is licensed under the **MIT License** — see the [LICENSE](./LICENSE) file for the full text.

```
MIT License
Copyright (c) 2026 Z3us7
```

You are free to use, modify, distribute, privately use, and sublicense this project — commercially or otherwise — provided the original copyright notice and license text are included in any copy or substantial portion of the software.
