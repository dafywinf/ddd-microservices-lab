package com.dafywinf.inventory;

import com.dafywinf.inventory.app.InventoryApplicationService;
import com.dafywinf.inventory.app.OutboxPublisher;
import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxMessage;
import com.dafywinf.inventory.outbox.OutboxRepository;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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

@Epic("Inventory Management")
@Feature("Order Placement Business Logic")
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"orders.v1", "inventory.v1"})
class HandleOrderPlacedTest {

    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static {
        MONGO.start();
        try {
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500);
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

    @Test
    @Story("Reserve Stock Successfully")
    @Description("Validates that an order with fully available SKUs successfully deducts stock, increments reserved count, and creates a pending outbox event.")
    void whenAllItemsAreInStock_stockIsReservedAndStockReservedOutboxMessageIsCreated() throws Exception {
        stockRepo.saveAll(List.of(
                new StockItem("SKU-A", 10),
                new StockItem("SKU-B", 5)
        ));

        Events.OrderPlaced event = orderPlaced("evt-happy", "order-1",
                new Events.OrderPlaced.LineItem("SKU-A", 3),
                new Events.OrderPlaced.LineItem("SKU-B", 2));

        service.handleOrderPlaced(event);

        StockItem skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(7);
        assertThat(skuA.getReserved()).isEqualTo(3);

        StockItem skuB = stockRepo.findById("SKU-B").orElseThrow();
        assertThat(skuB.getAvailable()).isEqualTo(3);
        assertThat(skuB.getReserved()).isEqualTo(2);

        List<OutboxMessage> messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReserved");
        assertThat(messages.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(messages.get(0).getAggregateId()).isEqualTo("order-1");

        assertThat(processedRepo.existsById("evt-happy")).isTrue();
    }

    @Test
    @Story("Reject Order Due to Insufficient Stock")
    @Description("Validates all-or-nothing semantics: if one item in the order lacks stock, the entire transaction is rejected and no partial stock is written.")
    void whenOneItemHasInsufficientStock_noStockIsWrittenAndStockReservationFailedOutboxMessageIsCreated() throws Exception {
        stockRepo.saveAll(List.of(
                new StockItem("SKU-A", 10),
                new StockItem("SKU-B", 1)
        ));

        Events.OrderPlaced event = orderPlaced("evt-short", "order-2",
                new Events.OrderPlaced.LineItem("SKU-A", 3),
                new Events.OrderPlaced.LineItem("SKU-B", 5));

        service.handleOrderPlaced(event);

        StockItem skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(10);
        assertThat(skuA.getReserved()).isEqualTo(0);

        StockItem skuB = stockRepo.findById("SKU-B").orElseThrow();
        assertThat(skuB.getAvailable()).isEqualTo(1);
        assertThat(skuB.getReserved()).isEqualTo(0);

        List<OutboxMessage> messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReservationFailed");
        assertThat(messages.get(0).getStatus()).isEqualTo("PENDING");

        assertThat(processedRepo.existsById("evt-short")).isTrue();
    }

    @Test
    @Story("Reject Order Due to Unknown SKU")
    @Description("Validates that requesting a non-existent product fails the reservation entirely.")
    void whenSkuDoesNotExist_stockReservationFailedOutboxMessageIsCreated() throws Exception {
        stockRepo.save(new StockItem("SKU-A", 10));

        Events.OrderPlaced event = orderPlaced("evt-unknown", "order-3",
                new Events.OrderPlaced.LineItem("GHOST-SKU", 1));

        service.handleOrderPlaced(event);

        List<OutboxMessage> messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("StockReservationFailed");
        assertThat(stockRepo.findById("SKU-A").orElseThrow().getAvailable()).isEqualTo(10);
    }

    @Test
    @Story("Idempotent Event Processing")
    @Description("Validates that if Kafka delivers the exact same event multiple times, the business logic only executes exactly once.")
    void whenSameEventArrivesMoreThanOnce_onlyTheFirstDeliveryIsProcessed() throws Exception {
        stockRepo.save(new StockItem("SKU-A", 10));

        Events.OrderPlaced event = orderPlaced("evt-dup", "order-4",
                new Events.OrderPlaced.LineItem("SKU-A", 3));

        service.handleOrderPlaced(event);
        service.handleOrderPlaced(event);

        StockItem skuA = stockRepo.findById("SKU-A").orElseThrow();
        assertThat(skuA.getAvailable()).isEqualTo(7);
        assertThat(skuA.getReserved()).isEqualTo(3);

        assertThat(outboxRepo.count()).isEqualTo(1);
    }

    private static Events.OrderPlaced orderPlaced(
            String eventId, String orderId, Events.OrderPlaced.LineItem... items) {
        return new Events.OrderPlaced(eventId, Instant.now(), orderId, List.of(items));
    }
}