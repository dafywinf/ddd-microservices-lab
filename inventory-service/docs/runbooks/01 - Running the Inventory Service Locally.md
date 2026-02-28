# 01 — Running the Inventory Service Locally

This runbook walks you through starting the inventory service, seeding stock, pushing a test event onto Kafka, and validating the outcome — both in Redpanda's web console and via a CLI tail tool.

---

## Prerequisites

| Tool | Why |
|---|---|
| Docker Desktop (running) | All infrastructure runs in containers |
| Java 21 + Maven | To compile and run the Spring Boot app |
| Postman (or curl) | To call the seed API |

All commands below are run from **`inventory-service/`** unless stated otherwise.

---

## Step 1 — Start the platform

The platform compose file starts MongoDB and Redpanda, initialises the MongoDB replica set automatically, pre-creates the Kafka topics, and starts the web consoles.

```bash
# Optional - cleans volumes
docker compose -f docker/platform.yml down -v
docker compose -f docker/platform.yml up -d
```

Wait until everything is healthy (takes ~15 s on first run while images download):

```bash
docker compose -f docker/platform.yml ps
```

You should see all services as **healthy** or **exited 0** (the `-init` containers exit after completing their one-off tasks).

| Web UI | URL | Credentials |
|---|---|---|
| Redpanda Console | http://localhost:8089 | — |
| Mongo Express | http://localhost:8091 | admin / pass |

---

## Step 2 — Start the inventory service

### Option A — Run on your host (recommended for development)

```bash
mvn spring-boot:run
```

The service starts on **port 8082**. You should see something like:

```
Started InventoryServiceApplication in 3.2 seconds
```

### Option B — Run in Docker

Build the jar first, then let Docker Compose build the image and start the container:

```bash
mvn package -DskipTests
docker compose -f docker/app.yml up --build
```

> When running in Docker the service talks to MongoDB and Redpanda over the
> internal `lab-net` network using the container hostnames (`mongo-inventory`,
> `redpanda`). The environment variables in `app.yml` override the defaults
> in `application.yml` automatically.

---

## Step 3 — Seed stock via Postman (or curl)

The service has no stock by default. Use `POST /inventory/seed` to add units before placing an order.

### Postman

1. Create a new **POST** request.
2. URL: `http://localhost:8082/inventory/seed`
3. Set the **Body** tab to **raw → JSON** and paste:

```json
{
  "sku": "ABC-123",
  "available": 10
}
```

4. Click **Send**. Expect `204 No Content`.

Seed a second SKU so you can test a multi-line order later:

```json
{
  "sku": "XYZ-999",
  "available": 5
}
```

### curl equivalent

```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/inventory/seed \
  -H "Content-Type: application/json" \
  -d '{"sku":"ABC-123","available":10}'
# → 204

curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/inventory/seed \
  -H "Content-Type: application/json" \
  -d '{"sku":"XYZ-999","available":5}'
# → 204
```

### Verify in Mongo Express

Open **http://localhost:8091**, navigate to **inventory → stock_items**. You should see two documents:

```json
{ "_id": "ABC-123", "available": 10, "reserved": 0 }
{ "_id": "XYZ-999", "available": 5,  "reserved": 0 }
```

---

## Step 4 — Open the Kafka tail tool

In a **new terminal**, start the streaming consumers so you can watch both topics in real time:

```bash
docker compose -f docker/kafka-tail.yml up
```

Each line of output is prefixed with the service name:

```
tail-orders     | (waiting for messages...)
tail-inventory  | (waiting for messages...)
```

Leave this terminal open — messages will appear here as the service processes events.

---

## Step 5 — Publish an `OrderPlaced` event

The inventory service **consumes** from `orders.v1`. To trigger it, you need to produce an `OrderPlaced` event onto that topic. There are two ways.

### Option A — Redpanda Console (visual)

1. Open **http://localhost:8089**.
2. Click **Topics** → **orders.v1**.
3. Click **Actions → Produce record** (top-right).
4. Set **Key**: `order-001`
5. Set **Value** (raw JSON):

```json
{
  "eventId": "evt-001",
  "occurredAt": "2026-02-28T10:00:00Z",
  "orderId": "order-001",
  "items": [
    { "sku": "ABC-123", "quantity": 3 },
    { "sku": "XYZ-999", "quantity": 2 }
  ]
}
```

6. Click **Produce**.

### Option B — CLI via the produce helper

Use the `produce-orders` service from `kafka-tail.yml` to pipe JSON directly into Redpanda:

```bash
echo '{
  "eventId": "evt-001",
  "occurredAt": "2026-02-28T10:00:00Z",
  "orderId": "order-001",
  "items": [
    { "sku": "ABC-123", "quantity": 3 },
    { "sku": "XYZ-999", "quantity": 2 }
  ]
}' | docker compose -f docker/kafka-tail.yml run --rm -T produce-orders
```

> The `-T` flag disables TTY allocation so stdin piping works correctly.

---

## Step 6 — Validate the outcome

### 6a. Watch the kafka-tail terminal

Within ~1 second you should see two new lines in the terminal from Step 4.

**`tail-orders`** shows the event you just produced:

```json
{
  "topic": "orders.v1",
  "key": "order-001",
  "value": "{\"eventId\":\"evt-001\",\"orderId\":\"order-001\",\"items\":[...]}",
  "partition": 0,
  "offset": 0
}
```

**`tail-inventory`** shows the service's response (published via the outbox, up to ~500 ms later):

```json
{
  "topic": "inventory.v1",
  "key": "order-001",
  "value": "{\"eventId\":\"...\",\"occurredAt\":\"...\",\"orderId\":\"order-001\"}",
  "partition": 0,
  "offset": 0
}
```

The value for a successful reservation has **no `reason` field** — that's a `StockReserved` event.
If it has `"reason": "insufficient stock for sku=..."` it's a `StockReservationFailed`.

### 6b. Validate stock changed in Mongo Express

Open **http://localhost:8091 → inventory → stock_items**:

```json
{ "_id": "ABC-123", "available": 7, "reserved": 3 }
{ "_id": "XYZ-999", "available": 3, "reserved": 2 }
```

`available` went down, `reserved` went up — the reservation is committed.

### 6c. Validate the outbox message was sent

Open **http://localhost:8091 → inventory → outbox**. The message should show:

```json
{
  "_id": "...",
  "aggregateId": "order-001",
  "topic": "inventory.v1",
  "type": "StockReserved",
  "status": "SENT",
  ...
}
```

`status: SENT` confirms the `OutboxPublisher` picked it up and published it to Kafka.

### 6d. Validate in Redpanda Console

1. Open **http://localhost:8089 → Topics → inventory.v1**.
2. Click **Messages**. You should see one record keyed on `order-001`.

---

## Step 7 — Test the failure path (insufficient stock)

Try to reserve more than is available to confirm `StockReservationFailed` is emitted instead.

```bash
echo '{
  "eventId": "evt-002",
  "occurredAt": "2026-02-28T10:01:00Z",
  "orderId": "order-002",
  "items": [
    { "sku": "ABC-123", "quantity": 99 }
  ]
}' | docker compose -f docker/kafka-tail.yml run --rm -T produce-orders
```

The `tail-inventory` output should show a value containing a `reason` field:

```json
{
  "eventId": "...",
  "occurredAt": "...",
  "orderId": "order-002",
  "reason": "insufficient stock for sku=ABC-123"
}
```

And in Mongo Express, the `ABC-123` stock document is **unchanged** — no partial write occurred.

---

## Tear-down

```bash
# Stop the kafka-tail containers (Ctrl+C, then):
docker compose -f docker/kafka-tail.yml down

# Stop the app (if running in Docker):
docker compose -f docker/app.yml down

# Stop and wipe the platform (removes all data):
docker compose -f docker/platform.yml down -v
```

To restart cleanly from scratch, re-run from Step 1.

---

## Quick reference

| What | Where |
|---|---|
| Inventory API | http://localhost:8082 |
| Redpanda Console | http://localhost:8089 |
| Mongo Express | http://localhost:8091 (admin / pass) |
| MongoDB port | localhost:27018 |
| Kafka bootstrap (host) | localhost:9092 |
| Kafka bootstrap (Docker) | redpanda:29092 |
| Platform compose | `docker/platform.yml` |
| App compose | `docker/app.yml` |
| Kafka tail / produce | `docker/kafka-tail.yml` |
