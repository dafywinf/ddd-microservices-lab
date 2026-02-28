# inventory-service

Part of the [DDD Microservices Lab](../README.md). Owns the **Inventory** bounded context.

Consumes `OrderPlaced` events from `orders.v1`, attempts to reserve stock, and publishes the outcome (`StockReserved` or `StockReservationFailed`) to `inventory.v1` via the outbox pattern.

- **Port:** 8082
- **Database:** MongoDB at `localhost:27018` (database: `inventory`)
- **Kafka consumer group:** `inventory-service`

---

## Domain model

**Aggregate: `StockItem`** (collection: `stock_items`, keyed by `sku`)

| Field | Type | Description |
|---|---|---|
| `sku` | String | Primary key |
| `available` | int | Units available to reserve |
| `reserved` | int | Units currently reserved |

Invariants enforced by `StockItem.reserve(qty)`:
- `qty` must be > 0
- `available` must be >= `qty`
- On success: `available -= qty`, `reserved += qty`

---

## Event flow

```
orders.v1  -->  OrdersListener
                  1. Idempotency check (skip if eventId already in processed_events)
                  2. For each line item: StockItem.reserve(qty)
                  3a. All succeed  --> OutboxMessage(StockReserved)      --> inventory.v1
                  3b. Any failure  --> OutboxMessage(StockReservationFailed) --> inventory.v1
```

The `OutboxPublisher` runs every 500 ms, sends `PENDING` outbox messages to Kafka keyed by `orderId`, then marks them `SENT` (or `FAILED` on error).

---

## API

### `POST /inventory/seed`

Seeds or overwrites a stock item. Use this to set up inventory before running the end-to-end flow.

**Request:**
```json
{ "sku": "ABC-123", "available": 10 }
```

**Response:** `204 No Content`

**Example:**
```bash
curl -X POST http://localhost:8082/inventory/seed \
  -H "Content-Type: application/json" \
  -d '{"sku":"ABC-123","available":10}'
```

---

## Running locally

Infrastructure must be running first. See the [repo README](../README.md) for `docker compose` and replica set init instructions.

```bash
mvn spring-boot:run
```

---

## MongoDB collections

| Collection | Purpose |
|---|---|
| `stock_items` | StockItem aggregates |
| `outbox` | Integration events queued for Kafka (`PENDING` → `SENT` / `FAILED`) |
| `processed_events` | Deduplication log keyed by `eventId` |

---

## Integration events consumed

**Topic:** `orders.v1`

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-02-20T12:34:56Z",
  "orderId": "uuid",
  "items": [{ "sku": "ABC-123", "quantity": 2 }]
}
```

## Integration events published

**Topic:** `inventory.v1` — Kafka key is always `orderId`.

`StockReserved`:
```json
{ "eventId": "uuid", "occurredAt": "2026-02-20T12:34:56Z", "orderId": "uuid" }
```

`StockReservationFailed`:
```json
{ "eventId": "uuid", "occurredAt": "2026-02-20T12:34:56Z", "orderId": "uuid", "reason": "insufficient stock for sku=ABC-123" }
```

---

## Running tests

Docker must be running — Testcontainers pulls `mongo:7.0` and `confluentinc/cp-kafka:7.7.0` on the first run.

```bash
mvn test                                    # all tests
mvn test -Dtest=HandleOrderPlacedTest       # service logic only (faster, no Kafka container)
mvn test -Dtest=KafkaEndToEndTest           # full pipeline via Kafka
```

---

## Test design

### `HandleOrderPlacedTest` — application service logic

Calls `InventoryApplicationService.handleOrderPlaced()` directly (no Kafka message delivery). Each test seeds MongoDB, invokes the service, then asserts on the resulting MongoDB state.

Infrastructure: MongoDB Testcontainer (single-node replica set, required for `@Transactional`) + `@EmbeddedKafka` (in-process broker, satisfies Spring Boot's Kafka wiring without a Docker container). `OutboxPublisher` is replaced with a mock so outbox messages stay `PENDING` and assertions are deterministic.

| Test | What it proves |
|---|---|
| `whenAllItemsAreInStock_…` | Happy path — `available`/`reserved` counts are updated for every SKU, one `StockReserved` outbox message is created |
| `whenOneItemHasInsufficientStock_…` | **Partial-reservation fix** — SKU-A stock is unchanged even though it was validated first; all mutations are held in memory and only committed via `saveAll()` if every item passes |
| `whenSkuDoesNotExist_…` | Unknown SKU is treated as a domain failure — `StockReservationFailed` outbox message created, no stock written |
| `whenSameEventArrivesMoreThanOnce_…` | **Idempotency** — the second delivery of the same `eventId` is silently ignored; stock is deducted only once, only one outbox message exists |

### `KafkaEndToEndTest` — full pipeline via Kafka

Publishes an `OrderPlaced` JSON message to `orders.v1` and waits (via Awaitility) for the resulting message to appear on `inventory.v1`. Exercises every hop: `OrdersListener` → `handleOrderPlaced()` → `OutboxMessage(PENDING)` → `OutboxPublisher` → Kafka.

Infrastructure: MongoDB Testcontainer (replica set) + Kafka Testcontainer (Confluent Platform image). Both containers are started in parallel. Each test creates a fresh Kafka consumer with a random group id seeking to the latest offset, so it only sees messages produced during that test.

| Test | What it proves |
|---|---|
| `whenOrderPlacedWithSufficientStock_…` | Full happy path — a `StockReserved` payload (no `reason` field) appears on `inventory.v1`; outbox message transitions to `SENT` |
| `whenOrderPlacedWithInsufficientStock_…` | Full failure path — a `StockReservationFailed` payload (has `reason` field) appears on `inventory.v1`; stock count is unchanged |