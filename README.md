# 💳 Payment Webhook API

A robust, secure **Third-Party Payment & Webhook Integration API** built with **Java 17** and **Spring Boot 3.x**. This backend service integrates with **Razorpay** to generate secure payment orders and asynchronously listen for payment confirmations via webhooks.

---

## 📋 Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Database Schema](#database-schema)
- [API Endpoints](#api-endpoints)
- [Security](#security)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Running with Docker](#running-with-docker)

---

## 🧾 Overview

This API acts as a secure backend service for any frontend application (React, HTML/JS, mobile) that needs to process payments without handling sensitive card data directly. It targets small to mid-sized businesses and freelance clients who need a reliable payment infrastructure.

---

## ✨ Features

### 🛒 Razorpay Order Creation
- Creates a payment order via the **Razorpay SDK**.
- Converts amount to **paise** (smallest currency unit) before creating the order.
- Stores the order in the database with a `CREATED` status.
- Returns the `razorpay_order_id` to the frontend to initialize Razorpay's checkout widget.

### 🔔 Secure Webhook Listener
- Dedicated endpoint (`POST /api/v1/webhook`) that receives **asynchronous payment events** from Razorpay.
- Listens for events like `order.paid`, `payment.captured`, `payment.failed`, etc.
- Validates the event type before taking any action.

### 🔐 Cryptographic Signature Verification
- Verifies every incoming webhook using **HMAC-SHA256** signature (`X-Razorpay-Signature` header) via `Utils.verifyWebhookSignature(...)`.
- Rejects any request with a missing or invalid signature with **HTTP 400**.
- Protects against **man-in-the-middle attacks** and spoofed webhook events.

### 🔄 Idempotent Webhook Processing
- Every webhook event is tracked by a unique `razorpayEventId` (derived from `paymentId + eventType`).
- Duplicate webhook deliveries (Razorpay retries) are **safely ignored** — the payment is only recorded once.
- Prevents double-counting and database constraint violations on retries.

### 🗃️ Database-Backed Order Tracking
- Every payment order is persisted in **PostgreSQL** with full lifecycle tracking.
- Order statuses: `CREATED` → `PAID` / `FAILED`.
- Orders are linked to **Users** (One-to-Many relationship).
- **TransactionLog** entity records every webhook event for a complete audit trail.

### 💳 Payment Method Resolution
- `GET /api/v1/orders/payment/{paymentId}/bank` — fetches the payment instrument used.
- Supports: **Net Banking**, **UPI**, **Wallet**, **Credit/Debit Card** (returns card network e.g. Visa).

### 🛡️ Spring Security Configuration
- **Stateless REST API** — CSRF disabled.
- Webhook endpoint (`/api/v1/webhook/**`) is **publicly accessible** (required for Razorpay's servers).
- Payment method bank endpoint is publicly accessible for frontend polling.

### ⚠️ Global Exception Handling
- `@RestControllerAdvice` intercepts all unhandled exceptions.
- Returns clean **JSend-compliant** JSON error responses instead of raw Java stack traces.
- Example: `{"status": "error", "message": "User not found with ID: ..."}`

### 📐 JSend API Response Standard
- All responses follow the **JSend standard**:
  ```json
  {
    "status": "success | fail | error",
    "data": { ... },
    "message": "Optional message for errors"
  }
  ```

### 🌐 CORS Support
- `@CrossOrigin` configured on checkout controller.
- Ready to restrict to specific frontend origins for production deployment.

### ⚙️ `.env` Based Configuration
- Uses `spring-dotenv` library to load secrets from a `.env` file.
- Zero hardcoded credentials in source code.

### 🎨 Built-in Test UI
- A beautiful **glassmorphism frontend** (`index.html`) served from `src/main/resources/static/`.
- Live terminal log panel showing real-time API requests.
- Mock database panel showing order lifecycle (CREATED → PAID).
- Powered by Razorpay Checkout JS — no separate frontend server needed.

---

## 🛠️ Tech Stack

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

---

## 🏗️ Architecture

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
          │    (User completes payment here)          │
          └──────────────────┬───────────────────────┘
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

---

## 🗄️ Database Schema

```
USERS ||--o{ ORDERS : places
ORDERS ||--o{ TRANSACTIONS : has

USERS {
    UUID   id           PK
    string email        UNIQUE
    datetime created_at
}

ORDERS {
    UUID    id               PK
    UUID    user_id          FK
    string  product_name
    decimal amount
    string  currency
    string  status           -- CREATED, PAID, FAILED
    string  razorpay_order_id  UNIQUE
    string  razorpay_event_id  UNIQUE
    datetime created_at
}

TRANSACTIONS {
    UUID    id           PK
    UUID    order_id     FK
    string  razorpay_event_id   UNIQUE  -- idempotency key
    string  event_type          -- e.g. order.paid, payment.captured
    datetime processed_at
}
```

---

## 📡 API Endpoints

### Order / Checkout

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/orders` | Public | Create a Razorpay order. Returns `razorpay_order_id`. |
| `GET` | `/api/v1/orders/payment/{paymentId}/bank` | Public | Fetch payment method/bank used for a payment. |

**POST `/api/v1/orders` — Request Body:**
```json
{
  "productName": "Premium Plan",
  "amount": 499.00,
  "currency": "INR"
}
```

**Response:**
```json
{
  "status": "success",
  "data": {
    "orderId": "order_abc123razorpay"
  }
}
```

---

### Webhook

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| `POST` | `/api/v1/webhook` | Public (Razorpay) | Receives and verifies Razorpay webhook events. |

**Headers required by Razorpay:**
```
X-Razorpay-Signature: <hmac-sha256-signature>
```

---

## 🔒 Security

| Concern | Implementation |
|---|---|
| Webhook Authenticity | HMAC-SHA256 signature verification via Razorpay SDK |
| Invalid Signatures | Returns `400 Bad Request` immediately |
| Missing Signature Header | Returns `400 Bad Request` |
| Idempotency | Duplicate events silently ignored via TransactionLog |
| CSRF | Disabled (stateless REST API) |
| Session Management | Stateless |
| Credentials | Loaded from `.env`, never hardcoded |

---

## 📁 Project Structure

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

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose (for PostgreSQL)
- A [Razorpay account](https://razorpay.com/) (free test mode available)

### Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/Z3us7/payment-webhook-api.git
   cd payment-webhook-api
   ```

2. **Configure environment variables**
   ```bash
   cp .env.example .env
   # Fill in your Razorpay and DB credentials in .env
   ```

3. **Start PostgreSQL via Docker**
   ```bash
   docker-compose up -d
   ```

4. **Build and run the application**
   ```bash
   mvn spring-boot:run
   ```

5. **Open the test UI in your browser:**
   ```
   http://localhost:8080/index.html
   ```

---

## 🔑 Environment Variables

Copy `.env.example` to `.env` and fill in your values:

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

> ⚠️ **Never commit your `.env` file to version control.** It is already excluded in `.gitignore`.

---

## 🐳 Running with Docker

The `docker-compose.yml` spins up a **PostgreSQL 15** container:

```bash
docker-compose up -d
```

> The database is exposed on port **5433** (to avoid conflicts with a local PostgreSQL on 5432).

---

## 📝 HTTP Status Codes

| Code | Meaning |
|------|---------|
| `200 OK` | Successful request |
| `201 Created` | New order generated |
| `400 Bad Request` | Invalid webhook / missing signature |
| `404 Not Found` | User or Order not found |
| `500 Internal Server Error` | Razorpay API failures |

---

## 🧪 Testing Webhooks Locally

Use [ngrok](https://ngrok.com/) to expose your local server so Razorpay can reach it:

```bash
ngrok http 8080
# Copy the ngrok HTTPS URL, e.g. https://abc123.ngrok.app
# Set https://abc123.ngrok.app/api/v1/webhook as the webhook URL in your Razorpay Dashboard
```

---

## 📄 License

MIT License — feel free to fork and use this project.
