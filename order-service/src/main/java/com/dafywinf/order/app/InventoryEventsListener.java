package com.dafywinf.order.app;

import com.dafywinf.order.events.Events;
import com.dafywinf.order.idempotency.ProcessedEvent;
import com.dafywinf.order.idempotency.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class InventoryEventsListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final ObjectMapper mapper;
    private final ProcessedEventRepository processed;
    private final OrderApplicationService orders;

    public InventoryEventsListener(ObjectMapper mapper, ProcessedEventRepository processed, OrderApplicationService orders) {
        this.mapper = mapper;
        this.processed = processed;
        this.orders = orders;
    }

    @KafkaListener(topics = "${app.topics.inventory}", groupId = "order-service")
    public void onInventoryEvent(ConsumerRecord<String, String> record) throws Exception {
        Header typeHeader = record.headers().lastHeader("type");
        String type = typeHeader != null ? new String(typeHeader.value(), StandardCharsets.UTF_8) : "";
        var payload = record.value();

        log.info("Received inventory event: type={}, key={}, payload={}", type, record.key(), payload);
        logHeaders(record);

        if ("StockReservationFailed".equals(type)) {
            var evt = mapper.readValue(payload, Events.StockReservationFailed.class);
            if (processed.existsById(evt.eventId())) return;
            processed.save(new ProcessedEvent(evt.eventId()));
            orders.reject(evt.orderId(), evt.reason());
            return;
        }

        if ("StockReserved".equals(type)) {
            var evt = mapper.readValue(payload, Events.StockReserved.class);
            if (processed.existsById(evt.eventId())) return;
            processed.save(new ProcessedEvent(evt.eventId()));
            orders.confirm(evt.orderId());
            return;
        }

        log.warn("Unknown inventory event type '{}', ignoring message", type);
    }

    private void logHeaders(ConsumerRecord<?, ?> record) {
        record.headers().forEach(h ->
            log.info("  Header: {}={}", h.key(), new String(h.value(), StandardCharsets.UTF_8)));
    }
}
