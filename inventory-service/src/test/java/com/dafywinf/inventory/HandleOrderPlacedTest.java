package com.dafywinf.inventory;

import com.dafywinf.inventory.app.InventoryApplicationService;
import com.dafywinf.inventory.app.OutboxPublisher;
import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link InventoryApplicationService#handleOrderPlaced}.
 *
 * <p>These tests call the application service directly — no Kafka message
 * delivery is involved. This isolates the business logic and makes failures
 * easy to read and diagnose.
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><b>MongoDB</b> – a Testcontainer running as a single-node replica set.
 *       A replica set is required because {@code handleOrderPlaced} is
 *       {@code @Transactional}, and MongoDB only supports multi-document
 *       transactions on replica sets.</li>
 *   <li><b>Kafka</b> – an in-process {@code @EmbeddedKafka} broker.  We don't
 *       publish or consume any messages here; the broker simply satisfies
 *       Spring Boot's auto-configuration so the application context starts.</li>
 *   <li><b>OutboxPublisher</b> – replaced with a mock so the scheduler never
 *       runs.  This keeps outbox messages in the {@code PENDING} state after
 *       each test, making assertions straightforward.</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"orders.v1", "inventory.v1"})
class HandleOrderPlacedTest {

    // -----------------------------------------------------------------------
    // MongoDB replica-set container
    //
    // We use GenericContainer rather than MongoDBContainer because the latter
    // starts a standalone instance.  Transactions require a replica set, so we
    // pass --replSet rs0 and initialise it below.
    //
    // The container is started in a static block that runs before Spring reads
    // @DynamicPropertySource.  That guarantees the container is up and the
    // replica set is elected before Spring tries to open a connection.
    // -----------------------------------------------------------------------
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static {
        MONGO.start();
        try {
            // rs.initiate with host=localhost:27017 (the container's own view of itself).
            // We then connect with directConnection=true so the driver talks to this
            // specific node rather than trying to resolve the replica-set topology.
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500); // wait for primary election
        } catch (Exception e) {
            throw new RuntimeException("MongoDB replica-set initialisation failed", e);
        }
    }

    @DynamicPropertySource
    static void mongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () ->
                "mongodb://localhost:" + MONGO.getMappedPort(27017)
                + "/inventory_db?directConnection=true&replicaSet=rs0");
    }

    // Replace the real OutboxPublisher (which publishes to Kafka on a 500 ms
    // schedule) with a no-op mock.  This prevents it from transitioning outbox
    // messages from PENDING → SENT while our assertions are running.
    @MockitoBean
    OutboxPublisher outboxPublisher;

    @Autowired InventoryApplicationService service;
    @Autowired StockItemRepository          stockRepo;
    @Autowired OutboxRepository             outboxRepo;
    @Autowired ProcessedEventRepository     processedRepo;

    @BeforeEach
    void resetDatabase() {
        stockRepo.deleteAll();
        outboxRepo.deleteAll();
        processedRepo.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Test 1 — Happy path
    //
    // All ordered items are in stock.  The service should:
    //   • reduce available stock and increase reserved stock for every SKU
    //   • write exactly one StockReserved outbox message
    //   • record the event id so a duplicate delivery is ignored
    // -----------------------------------------------------------------------
    @Test
    void whenAllItemsAreInStock_stockIsReservedAndStockReservedOutboxMessageIsCreated()
            throws Exception {

        // --- Given: two SKUs with plenty of stock ---
        stockRepo.saveAll(List.of(
                new StockItem("SKU-A", 10),
                new StockItem("SKU-B", 5)
        ));

        var event = orderPlaced("evt-happy", "order-1",
                new Events.OrderPlaced.LineItem("SKU-A", 3),
                new Events.OrderPlaced.LineItem("SKU-B", 2));

        // --- When ---
        service.handleOrderPlaced(event);

        // --- Then: available counts drop, reserved counts rise ---
        var skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(7);  // 10 - 3
        assertThat(skuA.getReserved()).isEqualTo(3);

        var skuB = stockRepo.findById("SKU-B").orElseThrow();
        assertThat(skuB.getAvailable()).isEqualTo(3);  // 5 - 2
        assertThat(skuB.getReserved()).isEqualTo(2);

        // A single StockReserved outbox message should exist, keyed on orderId
        var messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReserved");
        assertThat(messages.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(messages.get(0).getAggregateId()).isEqualTo("order-1");

        // The event id is persisted so a duplicate Kafka delivery is a no-op
        assertThat(processedRepo.existsById("evt-happy")).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 2 — Insufficient stock (all-or-nothing semantics)
    //
    // This is the core correctness test for the partial-reservation fix.
    //
    // The old code iterated line items and persisted each StockItem immediately.
    // If item 2 of 3 failed, item 1 was already saved — partial state.
    //
    // The fixed code (reserveAll) applies all mutations in memory first,
    // then calls saveAll() only after every item has passed validation.
    // A failure throws before saveAll() is reached, so no stock is written.
    // -----------------------------------------------------------------------
    @Test
    void whenOneItemHasInsufficientStock_noStockIsWrittenAndStockReservationFailedOutboxMessageIsCreated()
            throws Exception {

        // --- Given: SKU-A has plenty of stock, SKU-B only has 1 unit ---
        stockRepo.saveAll(List.of(
                new StockItem("SKU-A", 10),
                new StockItem("SKU-B", 1)   // only 1 available
        ));

        // The order requests 5 of SKU-B — more than the 1 available
        var event = orderPlaced("evt-short", "order-2",
                new Events.OrderPlaced.LineItem("SKU-A", 3),
                new Events.OrderPlaced.LineItem("SKU-B", 5));  // will fail

        // --- When ---
        service.handleOrderPlaced(event);

        // --- Then: SKU-A stock is UNCHANGED despite being validated first ---
        // reserveAll mutates SKU-A in memory, then fails on SKU-B.
        // Because saveAll() is never called, the SKU-A mutation is discarded.
        var skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(10); // unchanged — no partial write
        assertThat(skuA.getReserved()).isEqualTo(0);

        var skuB = stockRepo.findById("SKU-B").orElseThrow();
        assertThat(skuB.getAvailable()).isEqualTo(1);  // unchanged
        assertThat(skuB.getReserved()).isEqualTo(0);

        // A StockReservationFailed outbox message is written instead of StockReserved
        var messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReservationFailed");
        assertThat(messages.get(0).getStatus()).isEqualTo("PENDING");

        // The idempotency mark IS committed: a known domain failure should not
        // be retried endlessly; the order service will receive the failure event
        // and reject the order.
        assertThat(processedRepo.existsById("evt-short")).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 3 — Unknown SKU
    //
    // If the order references a SKU that doesn't exist in the catalog, the
    // service treats it as a domain failure and writes StockReservationFailed.
    // -----------------------------------------------------------------------
    @Test
    void whenSkuDoesNotExist_stockReservationFailedOutboxMessageIsCreated()
            throws Exception {

        // --- Given: inventory only knows about SKU-A ---
        stockRepo.save(new StockItem("SKU-A", 10));

        // The order asks for a SKU that has never been seeded
        var event = orderPlaced("evt-unknown", "order-3",
                new Events.OrderPlaced.LineItem("GHOST-SKU", 1));

        // --- When ---
        service.handleOrderPlaced(event);

        // --- Then: failure message, SKU-A untouched ---
        var messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReservationFailed");

        assertThat(stockRepo.findById("SKU-A").orElseThrow().getAvailable()).isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // Test 4 — Idempotency (at-least-once Kafka delivery)
    //
    // Kafka guarantees at-least-once delivery, so the same event can arrive
    // more than once.  The service guards against this by saving a ProcessedEvent
    // record on first delivery and returning early on subsequent ones.
    // -----------------------------------------------------------------------
    @Test
    void whenSameEventArrivesMoreThanOnce_onlyTheFirstDeliveryIsProcessed()
            throws Exception {

        // --- Given ---
        stockRepo.save(new StockItem("SKU-A", 10));

        var event = orderPlaced("evt-dup", "order-4",
                new Events.OrderPlaced.LineItem("SKU-A", 3));

        // --- When: same event id delivered twice (duplicate Kafka message) ---
        service.handleOrderPlaced(event); // first delivery  — real work
        service.handleOrderPlaced(event); // second delivery — should be a no-op

        // --- Then: stock is deducted only once, not twice ---
        var skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(7);  // 10 - 3, not 10 - 6
        assertThat(skuA.getReserved()).isEqualTo(3);

        // Only one outbox message exists (the second call returned before writing)
        assertThat(outboxRepo.count()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Events.OrderPlaced orderPlaced(
            String eventId, String orderId, Events.OrderPlaced.LineItem... items) {
        return new Events.OrderPlaced(eventId, Instant.now(), orderId, List.of(items));
    }
}
