package com.dafywinf.order;

import com.dafywinf.order.app.OrderApplicationService;
import com.dafywinf.order.domain.OrderRepository;
import com.dafywinf.order.domain.OrderStatus;
import com.dafywinf.order.events.Events;
import com.dafywinf.order.idempotency.ProcessedEventRepository;
import com.dafywinf.order.outbox.OutboxRepository;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Epic("Order Management")
@Feature("Kafka Event Pipeline")
@SpringBootTest
class KafkaEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaEndToEndTest.class);

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
                        + "/orders?directConnection=true&replicaSet=rs0");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper                  mapper;
    @Autowired OrderApplicationService       service;
    @Autowired OrderRepository               orderRepo;
    @Autowired OutboxRepository              outboxRepo;
    @Autowired ProcessedEventRepository      processedRepo;

    KafkaConsumer<String, String> ordersConsumer;
    List<ConsumerRecord<String, String>> received;

    @BeforeEach
    void setup() {
        orderRepo.deleteAll();
        outboxRepo.deleteAll();
        processedRepo.deleteAll();
        received = new ArrayList<>();

        ordersConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,                 "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "latest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ));
        ordersConsumer.subscribe(List.of("orders.v1"));
        ordersConsumer.poll(Duration.ofSeconds(1));
    }

    @AfterEach
    void teardown() {
        ordersConsumer.close();
    }

    @Test
    @Story("Successful Order Placement End-to-End")
    @Description("Verifies that placing an order flows through to Kafka, emitting an OrderPlaced event on the orders topic, and all outbox messages become SENT.")
    void whenOrderIsPlaced_orderPlacedEventAppearsOnOrdersTopic() throws Exception {
        var orderId = createAndPlaceOrder("SKU-A", 2, "SKU-B", 1);

        ConsumerRecord<String, String> record = waitForOrdersEvent(orderId);

        assertThat(new String(record.headers().lastHeader("type").value())).isEqualTo("OrderPlaced");
        assertThat(record.value()).contains("\"orderId\":\"" + orderId + "\"");

        await().atMost(5, SECONDS).until(() ->
                outboxRepo.findAll().stream().allMatch(m -> "SENT".equals(m.getStatus())));
    }

    @Test
    @Story("Order Confirmed After Stock Reserved End-to-End")
    @Description("Verifies that when a StockReserved event arrives on the inventory topic, the order transitions from PLACED to CONFIRMED.")
    void whenStockReservedArrivesOnKafka_orderBecomesConfirmed() throws Exception {
        var orderId = createAndPlaceOrder("SKU-A", 2);

        waitForOrdersEvent(orderId);

        var evt = new Events.StockReserved(UUID.randomUUID().toString(), Instant.now(), orderId);
        publishInventoryEvent(orderId, mapper.writeValueAsString(evt), "StockReserved");

        waitForOrderStatus(orderId, OrderStatus.CONFIRMED);

        assertThat(orderRepo.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @Story("Order Rejected After Stock Reservation Failed End-to-End")
    @Description("Verifies that when a StockReservationFailed event arrives on the inventory topic, the order transitions from PLACED to REJECTED.")
    void whenStockReservationFailedArrivesOnKafka_orderBecomesRejected() throws Exception {
        var orderId = createAndPlaceOrder("SKU-A", 99);

        waitForOrdersEvent(orderId);

        var evt = new Events.StockReservationFailed(UUID.randomUUID().toString(), Instant.now(), orderId, "Insufficient stock for SKU-A");
        publishInventoryEvent(orderId, mapper.writeValueAsString(evt), "StockReservationFailed");

        waitForOrderStatus(orderId, OrderStatus.REJECTED);

        assertThat(orderRepo.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    // --- Allure Step Helpers ---

    @Step("Create and place order with line items")
    private String createAndPlaceOrder(Object... skuQtyPairs) {
        String orderId = service.createDraft();
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            service.addLine(orderId, (String) skuQtyPairs[i], (int) skuQtyPairs[i + 1]);
        }
        service.placeOrder(orderId);
        return orderId;
    }

    @Step("Wait for OrderPlaced event on 'orders.v1' for order {orderId}")
    private ConsumerRecord<String, String> waitForOrdersEvent(String orderId) {
        await().atMost(30, SECONDS).until(() -> {
            ordersConsumer.poll(Duration.ofMillis(500)).forEach(r -> {
                log.info("Received on orders.v1: key={}, value={}", r.key(), r.value());
                r.headers().forEach(h ->
                    log.info("  Header: {}={}", h.key(), new String(h.value(), StandardCharsets.UTF_8)));
                received.add(r);
            });
            return received.stream().anyMatch(r -> r.value().contains(orderId));
        });
        return received.stream().filter(r -> r.value().contains(orderId)).findFirst().orElseThrow();
    }

    @Step("Publish inventory event to 'inventory.v1' for order {orderId}")
    private void publishInventoryEvent(String orderId, String payload, String type) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>("inventory.v1", null, orderId, payload);
        record.headers().add("type", type.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
    }

    @Step("Wait for order {orderId} to reach status {expectedStatus}")
    private void waitForOrderStatus(String orderId, OrderStatus expectedStatus) {
        await().atMost(30, SECONDS).until(() ->
                orderRepo.findById(orderId)
                        .map(o -> o.getStatus() == expectedStatus)
                        .orElse(false));
    }
}
