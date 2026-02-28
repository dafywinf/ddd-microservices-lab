package com.dafywinf.order;

import com.dafywinf.order.app.InventoryEventsListener;
import com.dafywinf.order.app.OrderApplicationService;
import com.dafywinf.order.app.OutboxPublisher;
import com.dafywinf.order.domain.OrderRepository;
import com.dafywinf.order.domain.OrderStatus;
import com.dafywinf.order.events.Events;
import com.dafywinf.order.idempotency.ProcessedEventRepository;
import com.dafywinf.order.outbox.OutboxRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Order Management")
@Feature("Order Placement Business Logic")
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"orders.v1", "inventory.v1"})
class PlaceOrderTest {

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
                        + "/orders?directConnection=true&replicaSet=rs0");
    }

    @MockitoBean
    OutboxPublisher outboxPublisher;

    @Autowired OrderApplicationService    service;
    @Autowired InventoryEventsListener   inventoryListener;
    @Autowired OrderRepository           orderRepo;
    @Autowired OutboxRepository          outboxRepo;
    @Autowired ProcessedEventRepository  processedRepo;
    @Autowired ObjectMapper              mapper;

    @BeforeEach
    void resetDatabase() {
        orderRepo.deleteAll();
        outboxRepo.deleteAll();
        processedRepo.deleteAll();
    }

    @Test
    @Story("Place Order Successfully")
    @Description("Validates that placing a draft order with lines transitions it to PLACED status and creates a pending OrderPlaced outbox event.")
    void whenOrderWithLinesIsPlaced_orderIsPlacedAndOrderPlacedOutboxMessageIsCreated() {
        String orderId = service.createDraft();
        service.addLine(orderId, "SKU-A", 2);
        service.addLine(orderId, "SKU-B", 1);

        service.placeOrder(orderId);

        var order = orderRepo.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);

        var messages = outboxRepo.findAll();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getType()).isEqualTo("OrderPlaced");
        assertThat(messages.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(messages.get(0).getAggregateId()).isEqualTo(orderId);
    }

    @Test
    @Story("Confirm Order on Stock Reserved")
    @Description("Validates that receiving a StockReserved inventory event transitions a placed order to CONFIRMED status and records the event for idempotency.")
    void whenStockIsReserved_orderIsConfirmed() throws Exception {
        String orderId = service.createDraft();
        service.addLine(orderId, "SKU-A", 2);
        service.placeOrder(orderId);
        outboxRepo.deleteAll();

        var eventId = UUID.randomUUID().toString();
        var evt = new Events.StockReserved(eventId, Instant.now(), orderId);
        inventoryListener.onInventoryEvent(mapper.writeValueAsString(evt));

        var order = orderRepo.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(processedRepo.existsById(eventId)).isTrue();
    }

    @Test
    @Story("Reject Order on Stock Reservation Failed")
    @Description("Validates that receiving a StockReservationFailed inventory event transitions a placed order to REJECTED status.")
    void whenStockReservationFails_orderIsRejected() throws Exception {
        String orderId = service.createDraft();
        service.addLine(orderId, "SKU-A", 99);
        service.placeOrder(orderId);
        outboxRepo.deleteAll();

        var eventId = UUID.randomUUID().toString();
        var evt = new Events.StockReservationFailed(eventId, Instant.now(), orderId, "Insufficient stock for SKU-A");
        inventoryListener.onInventoryEvent(mapper.writeValueAsString(evt));

        var order = orderRepo.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(processedRepo.existsById(eventId)).isTrue();
    }

    @Test
    @Story("Idempotent Inventory Event Processing")
    @Description("Validates that if the same inventory event is delivered more than once, the order state transition only happens once.")
    void whenSameInventoryEventArrivesMoreThanOnce_onlyFirstDeliveryIsProcessed() throws Exception {
        String orderId = service.createDraft();
        service.addLine(orderId, "SKU-A", 2);
        service.placeOrder(orderId);

        var eventId = UUID.randomUUID().toString();
        var evt = new Events.StockReserved(eventId, Instant.now(), orderId);
        String payload = mapper.writeValueAsString(evt);

        inventoryListener.onInventoryEvent(payload);
        inventoryListener.onInventoryEvent(payload);

        var order = orderRepo.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(processedRepo.count()).isEqualTo(1);
    }
}
