package com.dafywinf.inventory;

import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxRepository;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
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

@Epic("Inventory Management")
@Feature("Kafka Event Pipeline")
@SpringBootTest
class KafkaEndToEndTest {

    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        var mongoThread = new Thread(MONGO::start, "start-mongo");
        var kafkaThread = new Thread(KAFKA::start, "start-kafka");
        mongoThread.start();
        kafkaThread.start();
        try {
            mongoThread.join();
            kafkaThread.join();
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500);
        } catch (Exception e) {
            throw new RuntimeException("Container startup failed", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () ->
                "mongodb://localhost:" + MONGO.getMappedPort(27017)
                        + "/inventory_db?directConnection=true&replicaSet=rs0");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper                  mapper;
    @Autowired StockItemRepository           stockRepo;
    @Autowired OutboxRepository              outboxRepo;
    @Autowired ProcessedEventRepository      processedRepo;

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
        inventoryConsumer.poll(Duration.ofSeconds(1));
    }

    @AfterEach
    void teardown() {
        inventoryConsumer.close();
    }

    @Test
    @Story("Successful Stock Reservation End-to-End")
    @Description("Verifies that placing an order with sufficient stock flows through Kafka, updates Mongo, and emits a StockReserved event.")
    void whenOrderPlacedWithSufficientStock_stockReservedMessageAppearsOnInventoryTopic() throws Exception {
        seedInventory(List.of(new StockItem("SKU-A", 10), new StockItem("SKU-B", 5)));

        var orderId = "order-e2e-1";
        var event = new Events.OrderPlaced(
                UUID.randomUUID().toString(), Instant.now(), orderId,
                List.of(new Events.OrderPlaced.LineItem("SKU-A", 2),
                        new Events.OrderPlaced.LineItem("SKU-B", 1)));

        publishOrderEvent(orderId, event);

        var message = waitForInventoryEvent(orderId);

        assertThat(message).doesNotContain("reason");
        assertThat(message).contains("\"orderId\":\"" + orderId + "\"");

        await().atMost(5, SECONDS).until(() ->
                outboxRepo.findAll().stream().allMatch(m -> "SENT".equals(m.getStatus())));
    }

    @Test
    @Story("Insufficient Stock End-to-End")
    @Description("Verifies that placing an order without sufficient stock emits a StockReservationFailed event and rolls back DB changes.")
    void whenOrderPlacedWithInsufficientStock_stockReservationFailedMessageAppearsOnInventoryTopic() throws Exception {
        seedInventory(List.of(new StockItem("SKU-A", 1)));

        var orderId = "order-e2e-2";
        var event = new Events.OrderPlaced(
                UUID.randomUUID().toString(), Instant.now(), orderId,
                List.of(new Events.OrderPlaced.LineItem("SKU-A", 99)));

        publishOrderEvent(orderId, event);

        var message = waitForInventoryEvent(orderId);

        assertThat(message).contains("reason");
        assertThat(message).contains("\"orderId\":\"" + orderId + "\"");
        assertThat(stockRepo.findById("SKU-A").orElseThrow().getAvailable()).isEqualTo(1);
    }

    // --- Allure Step Helpers ---

    @Step("Seed inventory database with starting stock")
    private void seedInventory(List<StockItem> items) {
        stockRepo.saveAll(items);
    }

    @Step("Publish OrderPlaced event to Kafka topic 'orders.v1'")
    private void publishOrderEvent(String orderId, Events.OrderPlaced event) throws Exception {
        kafkaTemplate.send("orders.v1", orderId, mapper.writeValueAsString(event)).get();
    }

    @Step("Wait for response event on 'inventory.v1' for order {orderId}")
    private String waitForInventoryEvent(String orderId) {
        var received = new ArrayList<String>();
        await().atMost(30, SECONDS).until(() -> {
            inventoryConsumer.poll(Duration.ofMillis(500))
                    .forEach(r -> received.add(r.value()));
            return received.stream().anyMatch(m -> m.contains(orderId));
        });
        return received.stream().filter(m -> m.contains(orderId)).findFirst().orElseThrow();
    }
}