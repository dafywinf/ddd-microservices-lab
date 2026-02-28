package com.dafywinf.inventory;

import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration tests for the full Kafka message pipeline.
 *
 * <p>These tests verify every hop in the pipeline:
 * <ol>
 *   <li>Test publishes an {@code OrderPlaced} JSON message to {@code orders.v1}.</li>
 *   <li>{@code OrdersListener} consumes it and calls
 *       {@code InventoryApplicationService.handleOrderPlaced()}.</li>
 *   <li>The service runs its {@code @Transactional} logic and writes an
 *       {@code OutboxMessage} with status {@code PENDING}.</li>
 *   <li>{@code OutboxPublisher} (scheduled every 500 ms) reads the pending
 *       message and publishes it to {@code inventory.v1}, then marks it
 *       {@code SENT}.</li>
 *   <li>The test's own Kafka consumer reads from {@code inventory.v1} and
 *       we assert on the payload.</li>
 * </ol>
 *
 * <h2>Infrastructure</h2>
 * <ul>
 *   <li><b>MongoDB</b> – Testcontainer, single-node replica set (required for
 *       {@code @Transactional}).</li>
 *   <li><b>Kafka</b> – Testcontainer using the Confluent Platform image.
 *       Both containers are started in parallel in a static block so they are
 *       ready before Spring creates the application context.</li>
 * </ul>
 *
 * <h2>Async assertions</h2>
 * The pipeline is async (Kafka polling + scheduler delay), so we use Awaitility
 * to poll until the expected message arrives rather than sleeping a fixed time.
 * Awaitility is included transitively via {@code spring-boot-starter-test}.
 */
@SpringBootTest
class KafkaEndToEndTest {

    // -----------------------------------------------------------------------
    // MongoDB — same replica-set setup as HandleOrderPlacedTest
    // -----------------------------------------------------------------------
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    // -----------------------------------------------------------------------
    // Kafka — Confluent Platform image (Kafka-compatible)
    // -----------------------------------------------------------------------
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        // Start both containers in parallel to keep test startup fast.
        var mongoThread = new Thread(MONGO::start, "start-mongo");
        var kafkaThread = new Thread(KAFKA::start, "start-kafka");
        mongoThread.start();
        kafkaThread.start();
        try {
            mongoThread.join();
            kafkaThread.join();
            // MongoDB must be running before we can initialise the replica set.
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500); // wait for primary election
        } catch (Exception e) {
            throw new RuntimeException("Container startup failed", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () ->
                "mongodb://localhost:" + MONGO.getMappedPort(27017)
                + "/inventory_db?directConnection=true&replicaSet=rs0");
        // Tell Spring Boot's KafkaAutoConfiguration where the broker lives.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    // -----------------------------------------------------------------------
    // Spring beans (wired from the full application context)
    // -----------------------------------------------------------------------
    @Autowired KafkaTemplate<String, String> kafkaTemplate; // publish OrderPlaced events
    @Autowired ObjectMapper                  mapper;        // JSON serialisation
    @Autowired StockItemRepository           stockRepo;
    @Autowired OutboxRepository              outboxRepo;
    @Autowired ProcessedEventRepository      processedRepo;

    // -----------------------------------------------------------------------
    // Per-test Kafka consumer for inventory.v1
    //
    // A fresh consumer with a random group id is created for every test.
    // After subscribing we do one empty poll so the consumer gets its
    // partition assignment and seeks to the LATEST offset.  This ensures we
    // only see messages produced AFTER that point — not leftovers from a
    // previous test.
    // -----------------------------------------------------------------------
    KafkaConsumer<String, String> inventoryConsumer;

    @BeforeEach
    void setup() throws Exception {
        stockRepo.deleteAll();
        outboxRepo.deleteAll();
        processedRepo.deleteAll();

        inventoryConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,         KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,                  "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,         "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,    StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,  StringDeserializer.class
        ));
        inventoryConsumer.subscribe(List.of("inventory.v1"));
        // Force partition assignment so subsequent polls start from "now".
        inventoryConsumer.poll(Duration.ofSeconds(1));
    }

    @AfterEach
    void teardown() {
        inventoryConsumer.close();
    }

    // -----------------------------------------------------------------------
    // Test 1 — Happy path end-to-end
    //
    // We seed enough stock, publish an OrderPlaced event, and expect a
    // StockReserved message on inventory.v1.
    // StockReserved has fields: { eventId, occurredAt, orderId }.
    // StockReservationFailed additionally has: { reason }.
    // We verify the absence of "reason" to confirm the right type was sent.
    // -----------------------------------------------------------------------
    @Test
    void whenOrderPlacedWithSufficientStock_stockReservedMessageAppearsOnInventoryTopic()
            throws Exception {

        // --- Given ---
        stockRepo.saveAll(List.of(
                new StockItem("SKU-A", 10),
                new StockItem("SKU-B", 5)
        ));

        var orderId = "order-e2e-1";
        var event   = new Events.OrderPlaced(
                UUID.randomUUID().toString(), Instant.now(), orderId,
                List.of(new Events.OrderPlaced.LineItem("SKU-A", 2),
                        new Events.OrderPlaced.LineItem("SKU-B", 1)));

        // --- When: publish to orders.v1 using orderId as the Kafka key ---
        // The Kafka key is used by both services to preserve per-order ordering.
        kafkaTemplate.send("orders.v1", orderId, mapper.writeValueAsString(event)).get();

        // --- Then: wait for a matching message on inventory.v1 ---
        // The pipeline is: OrdersListener → handleOrderPlaced → OutboxMessage(PENDING)
        //                  → OutboxPublisher (every 500 ms) → inventory.v1
        var received = new ArrayList<String>();
        await().atMost(30, SECONDS).until(() -> {
            inventoryConsumer.poll(Duration.ofMillis(500))
                    .forEach(r -> received.add(r.value()));
            // We wait until we see a message that belongs to our specific order.
            // Using the orderId prevents false positives from any stale messages.
            return received.stream().anyMatch(m -> m.contains(orderId));
        });

        var message = received.stream().filter(m -> m.contains(orderId)).findFirst().orElseThrow();

        // StockReserved has { eventId, occurredAt, orderId } — no "reason" field.
        assertThat(message).doesNotContain("reason");
        assertThat(message).contains("\"orderId\":\"" + orderId + "\"");

        // The outbox message should have transitioned from PENDING → SENT.
        await().atMost(5, SECONDS).until(() ->
                outboxRepo.findAll().stream().allMatch(m -> "SENT".equals(m.getStatus())));
    }

    // -----------------------------------------------------------------------
    // Test 2 — Insufficient stock end-to-end
    //
    // When stock is insufficient the service writes a StockReservationFailed
    // outbox message.  The OutboxPublisher then sends it to inventory.v1.
    // We verify the "reason" field is present in the payload.
    // We also verify that no stock was written (all-or-nothing protection).
    // -----------------------------------------------------------------------
    @Test
    void whenOrderPlacedWithInsufficientStock_stockReservationFailedMessageAppearsOnInventoryTopic()
            throws Exception {

        // --- Given: only 1 unit of SKU-A in stock ---
        stockRepo.save(new StockItem("SKU-A", 1));

        var orderId = "order-e2e-2";
        var event   = new Events.OrderPlaced(
                UUID.randomUUID().toString(), Instant.now(), orderId,
                List.of(new Events.OrderPlaced.LineItem("SKU-A", 99))); // requests 99

        // --- When ---
        kafkaTemplate.send("orders.v1", orderId, mapper.writeValueAsString(event)).get();

        // --- Then: a StockReservationFailed message appears on inventory.v1 ---
        var received = new ArrayList<String>();
        await().atMost(30, SECONDS).until(() -> {
            inventoryConsumer.poll(Duration.ofMillis(500))
                    .forEach(r -> received.add(r.value()));
            return received.stream().anyMatch(m -> m.contains(orderId));
        });

        var message = received.stream().filter(m -> m.contains(orderId)).findFirst().orElseThrow();

        // StockReservationFailed includes a human-readable reason explaining why.
        assertThat(message).contains("reason");
        assertThat(message).contains("\"orderId\":\"" + orderId + "\"");

        // Stock must not have been modified — no partial writes.
        assertThat(stockRepo.findById("SKU-A").orElseThrow().getAvailable()).isEqualTo(1);
    }
}
