# DDD Microservices Lab

**Spring Boot 4 + MongoDB + Redpanda (Kafka) + Event‑Driven + Outbox**

A minimal learning project for Domain Driven Design (DDD) in
microservices using Spring Boot 4.\
Two services communicate via events from the start and each owns its own
database.

------------------------------------------------------------------------

## Services

-   **order-service** manages Orders and publishes `OrderPlaced`
-   **inventory-service** manages stock and reacts to orders
-   **Redpanda** provides Kafka-compatible event streaming
-   **MongoDB per service** provides isolated persistence
-   **Outbox pattern** ensures reliable event publishing

------------------------------------------------------------------------

## Architecture Overview

### Bounded Contexts

-   Order
-   Inventory

Each context:

-   Owns its own MongoDB
-   Publishes integration events
-   Consumes only public events from other services
-   Does not share domain models

### Event Flow

1.  Client creates order
2.  Order is placed → `OrderPlaced` event written to outbox
3.  Outbox publisher sends event to `orders.v1`
4.  Inventory consumes event and reserves stock
5.  Inventory publishes outcome:
   -   `StockReserved`
   -   `StockReservationFailed`
6.  Order consumes outcome and updates status:
   -   `CONFIRMED`
   -   `REJECTED`

------------------------------------------------------------------------

## Technology Stack

-   Java 21
-   Spring Boot 4
-   Spring Data MongoDB
-   Spring Kafka
-   MongoDB per service
-   Redpanda (Kafka compatible broker)
-   Docker Compose
-   Allure Test Reporting

------------------------------------------------------------------------

## Prerequisites

Install:

-   Docker
-   Docker Compose
-   Java 21
-   Maven 3.9+

Verify:

``` bash
docker --version
mvn -version
java -version
```

------------------------------------------------------------------------

## First Time Startup

### 1. Start infrastructure

``` bash
docker compose -f platform/platform.yml up -d
```

### 2. Initialise Mongo replica sets (Required for @Transactional)

Run once only:

``` bash
docker exec -it mongo-orders mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-orders:27017"}]})'
docker exec -it mongo-inventory mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-inventory:27017"}]})'
```

### 3. Start services

Terminal 1:

``` bash
cd order-service
mvn spring-boot:run
```

Terminal 2:

``` bash
cd inventory-service
mvn spring-boot:run
```

### 4. Seed inventory

``` bash
curl -X POST http://localhost:8082/inventory/seed   -H "Content-Type: application/json"   -d '{"sku":"ABC-123","available":10}'
```

### 5. Create and place an order

``` bash
ORDER_ID=$(curl -s -X POST http://localhost:8081/orders | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')

curl -X POST http://localhost:8081/orders/$ORDER_ID/lines   -H "Content-Type: application/json"   -d '{"sku":"ABC-123","quantity":2}'

curl -X POST http://localhost:8081/orders/$ORDER_ID/place
```

------------------------------------------------------------------------

## Testing & Reporting

The project includes BDD-style testing with Allure reporting.

Run tests:

``` bash
mvn clean test
```

Generate and serve Allure report:

``` bash
mvn allure:serve
```

### Tests verify

-   **HandleOrderPlacedTest**: Logic isolation with `@EmbeddedKafka`
-   **KafkaEndToEndTest**: Full pipeline using Testcontainers

------------------------------------------------------------------------

## Redpanda Console

http://localhost:8089

Use it to inspect topics and events.

------------------------------------------------------------------------

## Topics

-   `orders.v1`
-   `inventory.v1`

Kafka key: `orderId`

------------------------------------------------------------------------

## Domain Model Summary

### Order States

-   DRAFT
-   PLACED
-   CONFIRMED
-   REJECTED

### Rules

-   Cannot place empty order
-   Cannot confirm before placement
-   Cannot reject after confirmation

### Inventory Rules

-   Cannot reserve more stock than available

------------------------------------------------------------------------

## Outbox Pattern

Each service:

1.  Updates aggregate
2.  Writes integration event to outbox collection
3.  Background publisher sends events to Kafka
4.  Marks message as SENT

Prevents lost events and dual-write problems.

------------------------------------------------------------------------

## Idempotency

Each consumer stores processed `eventId`.

Prevents:

-   duplicate reservations
-   duplicate confirmations
-   replay side effects

------------------------------------------------------------------------

## Reset Environment

``` bash
docker compose -f platform/platform.yml down -v
docker compose -f platform/platform.yml up -d
```

Reinitialise replica sets.

```
docker exec -it mongo-orders mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-orders:27017"}]})'
docker exec -it mongo-inventory mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-inventory:27017"}]})'
```

--------------------------------------------------

## Troubleshooting

### Services cannot connect to Mongo

-   Replica set not initialised

### Events missing

-   Check Redpanda is running
-   Check outbox publisher logs
-   Check topic names

### Order stuck in PLACED

-   Inventory may have failed
-   Check inventory logs and events

------------------------------------------------------------------------

## Purpose

This project teaches:

-   Domain Driven Design in practice
-   Bounded contexts
-   Aggregates and invariants
-   Integration events
-   Event-driven microservices
-   Outbox reliability pattern
-   Idempotent consumers
-   Eventual consistency

**Intentionally minimal and not production hardened.**
