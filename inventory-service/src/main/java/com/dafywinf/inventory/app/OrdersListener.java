package com.dafywinf.inventory.app;

import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEvent;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxMessage;
import com.dafywinf.inventory.outbox.OutboxRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class OrdersListener {

    private final ObjectMapper mapper;
    private final ProcessedEventRepository processed;
    private final OutboxRepository outbox;
    private final InventoryApplicationService inventory;
    private final String inventoryTopic;

    public OrdersListener(
            ObjectMapper mapper,
            ProcessedEventRepository processed,
            OutboxRepository outbox,
            InventoryApplicationService inventory,
            @Value("${app.topics.inventory}") String inventoryTopic
    ) {
        this.mapper = mapper;
        this.processed = processed;
        this.outbox = outbox;
        this.inventory = inventory;
        this.inventoryTopic = inventoryTopic;
    }

    @KafkaListener(topics = "${app.topics.orders}", groupId = "inventory-service")
    public void onOrderPlaced(String payload) throws Exception {
        var evt = mapper.readValue(payload, Events.OrderPlaced.class);

        if (processed.existsById(evt.eventId())) return;
        processed.save(new ProcessedEvent(evt.eventId()));

        try {
            for (var li : evt.items()) {
                inventory.reserve(li.sku(), li.quantity());
            }

            var ok = new Events.StockReserved(UUID.randomUUID().toString(), Instant.now(), evt.orderId());
            outbox.save(new OutboxMessage(evt.orderId(), inventoryTopic, "StockReserved", mapper.writeValueAsString(ok)));
        } catch (Exception e) {
            var fail = new Events.StockReservationFailed(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    evt.orderId(),
                    e.getMessage()
            );
            outbox.save(new OutboxMessage(evt.orderId(), inventoryTopic, "StockReservationFailed", mapper.writeValueAsString(fail)));
        }
    }
}
