# DDD Microservices Lab
Spring Boot + MongoDB + Redpanda (Kafka) + Event-Driven + Outbox

A minimal learning project for Domain Driven Design in microservices using Spring Boot.
Two services communicate via events from the start and each owns its own database.

Services:
- order-service manages Orders and publishes OrderPlaced
- inventory-service manages stock and reacts to orders
- Redpanda provides Kafka-compatible event streaming
- MongoDB per service provides isolated persistence
- Outbox pattern ensures reliable event publishing

--------------------------------------------------

ARCHITECTURE OVERVIEW

Bounded contexts:
- Order
- Inventory

Each context:
- Owns its own MongoDB
- Publishes integration events
- Consumes only public events from other services
- Does not share domain models

Event flow:
1. Client creates order
2. Order is placed -> OrderPlaced event written to outbox
3. Outbox publisher sends event to orders.v1
4. Inventory consumes event, reserves stock
5. Inventory publishes outcome:
    - StockReserved
    - or StockReservationFailed
6. Order consumes outcome and updates status:
    - CONFIRMED
    - or REJECTED

--------------------------------------------------

TECHNOLOGY STACK

- Java 21
- Spring Boot 3
- Spring Data MongoDB
- Spring Kafka
- MongoDB per service
- Redpanda (Kafka compatible broker)
- Docker Compose

--------------------------------------------------

PREREQUISITES

Install:

- Docker
- Docker Compose
- Java 21
- Maven 3.9+

Verify:

docker --version
mvn -version
java -version

--------------------------------------------------

FIRST TIME STARTUP

1) Start infrastructure

```
docker compose up -d
```

2) Initialise Mongo replica sets


**Run once only:**
```
docker exec -it mongo-orders mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-orders:27017"}]})'
docker exec -it mongo-inventory mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-inventory:27017"}]})'
```


3) Start services

Terminal 1:
```
cd order-service
mvn spring-boot:run
```

Terminal 2:
```
cd inventory-service
mvn spring-boot:run
```

4) Seed inventory

```
curl -X POST http://localhost:8082/inventory/seed   -H "Content-Type: application/json"   -d '{"sku":"ABC-123","available":10}'
```

5) Create and place an order

```
ORDER_ID=$(curl -s -X POST http://localhost:8081/orders | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
curl -X POST http://localhost:8081/orders/$ORDER_ID/lines   -H "Content-Type: application/json"   -d '{"sku":"ABC-123","quantity":2}'
curl -X POST http://localhost:8081/orders/$ORDER_ID/place
```

Check result:

```
curl http://localhost:8081/orders/$ORDER_ID
```

Expected:
- CONFIRMED if stock exists
- REJECTED if insufficient stock

--------------------------------------------------

REDPANDA CONSOLE

http://localhost:8089

Use it to inspect topics and events.

--------------------------------------------------

TOPICS

orders.v1
inventory.v1

Kafka key: orderId

--------------------------------------------------

DOMAIN MODEL SUMMARY

Order states:
- DRAFT
- PLACED
- CONFIRMED
- REJECTED

Rules:
- Cannot place empty order
- Cannot confirm before placement
- Cannot reject after confirmation

Inventory rules:
- Cannot reserve more stock than available

--------------------------------------------------

OUTBOX PATTERN

Each service:
1. Updates aggregate
2. Writes integration event to outbox collection
3. Background publisher sends events to Kafka
4. Marks message SENT

Prevents lost events and dual-write problems.

--------------------------------------------------

IDEMPOTENCY

Each consumer stores processed eventId.

Prevents:
- duplicate reservations
- duplicate confirmations
- replay side effects

--------------------------------------------------

RESET ENVIRONMENT

```
docker compose down -v
docker compose up -d
```

Reinitialise replica sets.

```
docker exec -it mongo-orders mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-orders:27017"}]})'
docker exec -it mongo-inventory mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-inventory:27017"}]})'
```

--------------------------------------------------

TROUBLESHOOTING

Services cannot connect to Mongo:
- Replica set not initialised

Events missing:
- Check Redpanda running
- Check outbox publisher logs
- Check topic names

Order stuck in PLACED:
- Inventory may have failed
- Check inventory logs and events

--------------------------------------------------

LEARNING PATH

1. Run system end-to-end
2. Inspect Mongo collections
3. Inspect Kafka topics
4. Read Order aggregate rules
5. Read Inventory aggregate rules
6. Understand Outbox publisher
7. Follow event lifecycle

--------------------------------------------------

PURPOSE

This project teaches:

- Domain Driven Design in practice
- Bounded contexts
- Aggregates and invariants
- Integration events
- Event-driven microservices
- Outbox reliability pattern
- Idempotent consumers
- Eventual consistency

Intentionally minimal, not production hardened.
