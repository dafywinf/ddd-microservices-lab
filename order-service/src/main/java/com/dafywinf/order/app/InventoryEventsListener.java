package com.dafywinf.order.app;

import com.dafywinf.order.events.Events;
import com.dafywinf.order.idempotency.ProcessedEvent;
import com.dafywinf.order.idempotency.ProcessedEventRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventsListener {

    private final ObjectMapper mapper;
    private final ProcessedEventRepository processed;
    private final OrderApplicationService orders;

    public InventoryEventsListener(ObjectMapper mapper, ProcessedEventRepository processed, OrderApplicationService orders) {
        this.mapper = mapper;
        this.processed = processed;
        this.orders = orders;
    }

    @KafkaListener(topics = "${app.topics.inventory}", groupId = "order-service")
    public void onInventoryEvent(String payload) throws Exception {
        // Try decode as reserved, then as failed (simple for learning)
        if (payload.contains("\"orderId\"") && payload.contains("StockReserved") == false && payload.contains("reason") == false) {
            // no-op, not expected
        }

        if (payload.contains("\"reason\"")) {
            var evt = mapper.readValue(payload, Events.StockReservationFailed.class);
            if (processed.existsById(evt.eventId())) return;
            processed.save(new ProcessedEvent(evt.eventId()));
            orders.reject(evt.orderId(), evt.reason());
            return;
        }

        var evt = mapper.readValue(payload, Events.StockReserved.class);
        if (processed.existsById(evt.eventId())) return;
        processed.save(new ProcessedEvent(evt.eventId()));
        orders.confirm(evt.orderId());
    }
}
