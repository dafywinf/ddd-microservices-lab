# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project layout

This repo contains two independent Spring Boot services under the git root (`ddd-microservices-lab/`):

```
ddd-microservices-lab/
  docker-compose.yml      # infra: mongo-orders, mongo-inventory, redpanda, consoles
  README.md
  order-service/          # Spring Boot app, port 8081
  inventory-service/      # Spring Boot app, port 8082  <-- you are here
```

Each service is a self-contained Maven project with its own `pom.xml`. There is no parent POM.

## Tech stack

- Java 21, Spring Boot 4.0.2, Maven
- Persistence: Spring Data MongoDB (per-service database)
- Messaging: Spring Kafka against Redpanda (Kafka-compatible)
- Lombok used in inventory-service (`@Getter`, etc.)

## Common commands

Run from inside the service directory (e.g. `inventory-service/`):

```bash
mvn spring-boot:run          # start the service
mvn test                     # run all tests
mvn test -Dtest=FooTest      # run a single test class
mvn package -DskipTests      # build jar
```

Infrastructure (run from repo root):

```bash
docker compose up -d         # start all infra
docker compose down -v       # stop and wipe volumes (reset)
```

Mongo replica set init (run once after first `docker compose up -d`):

```bash
docker exec -it mongo-orders    mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-orders:27017"}]})'
docker exec -it mongo-inventory mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"mongo-inventory:27017"}]})'
```

## Package structure (both services follow the same layout)

```
com.dafywinf.inventory (or .order)
  domain/        # aggregates, value objects, repository interfaces
  app/           # application services, Kafka listeners, outbox publisher
  api/           # REST controllers
  events/        # integration event record definitions (Events.java)
  outbox/        # OutboxMessage document + OutboxRepository
  idempotency/   # ProcessedEvent document + ProcessedEventRepository
```

## Architecture: key patterns

### Outbox pattern
Both services never publish directly to Kafka. Instead:
1. The listener/service saves the aggregate change **and** an `OutboxMessage` (status=`PENDING`) in the same MongoDB operation.
2. `OutboxPublisher` (`@Scheduled(fixedDelay=500)`) polls for `PENDING` messages and sends them via `KafkaTemplate`, then marks them `SENT` or `FAILED`.
3. Kafka key is always `orderId` (the `aggregateId` field on `OutboxMessage`) to preserve per-order ordering.

### Idempotent consumers
Before processing any incoming Kafka event, the listener checks `ProcessedEventRepository.existsById(eventId)`. If already present, the event is skipped. Otherwise it saves the `ProcessedEvent` first, then handles the event.

### Event flow
```
order-service                              inventory-service
POST /orders/{id}/place
  -> Order.place()
  -> OutboxMessage(OrderPlaced) PENDING
  -> OutboxPublisher -> orders.v1
                                           OrdersListener.onOrderPlaced()
                                             -> idempotency check
                                             -> StockItem.reserve() per line
                                             -> OutboxMessage(StockReserved | StockReservationFailed) PENDING
                                             -> OutboxPublisher -> inventory.v1
InventoryEventsListener
  -> Order.confirm() or Order.reject()
```

## MongoDB collections per service

| Service | Collections |
|---|---|
| order-service | `orders`, `outbox`, `processed_events` |
| inventory-service | `stock_items`, `outbox`, `processed_events` |

MongoDB must run as a replica set (`rs0`) because Spring Data MongoDB uses change streams/transactions that require it.

## Kafka topics

| Topic | Producer | Consumer |
|---|---|---|
| `orders.v1` | order-service | inventory-service |
| `inventory.v1` | inventory-service | order-service |

## Integration events

All event records are defined in `events/Events.java` per service. They are plain Java records serialised as JSON. The `OrderPlaced` event consumed by inventory-service is defined in `inventory-service`'s own `Events.java` (no shared library).

## UI consoles (local only)

- Redpanda Console: http://localhost:8089
- Mongo Express (orders): http://localhost:8090 (admin/pass)
- Mongo Express (inventory): http://localhost:8091 (admin/pass)

## Note on CLAUDE.md spec file

The original project spec lives in the git history. The canonical source of truth for requirements is the README.md at the repo root and the source code itself.

---

## Git conventions

### Commit messages
Use **Conventional Commits** format: `<type>(<scope>): <description>`

Common types: `feat`, `fix`, `chore`, `refactor`, `test`, `docs`, `ci`

Examples from this project:
- `feat(observability): add Prometheus metrics and Grafana monitoring stack`
- `chore(build): reorder pom.xml to Maven best-practice conventions`

### Branch naming
Use `<type>/<short-description>` kebab-case, matching the conventional commit type:
- `feature/prometheus-support`
- `fix/partial-reservation`
- `chore/pom-cleanup`

### Squashing
Squash incremental/WIP commits before merging. Each commit on `main` should represent one coherent unit of work with a single conventional commit message.

---

## Session notes (last updated 2026-02-28)

### Jackson import
Spring Boot 4.0.2 uses Jackson 3.x (`tools.jackson`), not 2.x (`com.fasterxml.jackson`). The `tools.jackson.databind.ObjectMapper` imports throughout this service are **correct** — do not change them.

### Partial-reservation fix (done)
The original `OrdersListener` iterated over line items calling `inventory.reserve()` one at a time, saving each to MongoDB immediately. If item 2 of 3 failed, item 1 was already persisted — partial state.

**Fix applied:** All MongoDB writes for a single `OrderPlaced` event now happen inside one `@Transactional` method — `InventoryApplicationService.handleOrderPlaced()`. The method covers:
1. Idempotency mark (`ProcessedEvent` save)
2. All stock reservations via private `reserveAll()` — items mutated in-memory first, committed in one `saveAll()` only if every item passes
3. `OutboxMessage` save (`StockReserved` or `StockReservationFailed`)

Domain exceptions (`IllegalArgumentException`, `IllegalStateException`) are caught **inside** the transaction so the failure path commits the idempotency mark + failure outbox message with zero stock writes. Infrastructure exceptions propagate out and trigger a full rollback so the event is reprocessed.

`@EnableTransactionManagement` was added to `InventoryServiceApplication`. Spring Boot auto-configures `MongoTransactionManager` because the MongoDB URI already specifies `replicaSet=rs0`.

`OrdersListener` is now a thin adapter: deserialise payload → call `inventory.handleOrderPlaced(evt)`.

### Known remaining issues (not yet fixed)
- **Failed outbox messages never retried** — `OutboxPublisher` marks them `FAILED` and never polls them again. Events can be silently dropped if Kafka is down.
- **Blocking `.get()` in `OutboxPublisher`** — synchronous Kafka send blocks the scheduler thread.
- **No index on `outbox.status`** — the 500 ms query table-scans the collection.
- **No optimistic locking on `StockItem`** — concurrent transactions on the same SKU can produce write conflicts; MongoDB will raise an error that the caller must handle/retry.
- **No tests** — zero unit or integration tests exist.