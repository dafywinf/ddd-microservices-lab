package com.dafywinf.inventory.app;

import com.dafywinf.inventory.events.Events;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class OrdersListener {

    private static final Logger log = LoggerFactory.getLogger(OrdersListener.class);

    private final ObjectMapper mapper;
    private final InventoryApplicationService inventory;

    public OrdersListener(ObjectMapper mapper, InventoryApplicationService inventory) {
        this.mapper = mapper;
        this.inventory = inventory;
    }

    @KafkaListener(topics = "${app.topics.orders}", groupId = "inventory-service")
    public void onOrderPlaced(ConsumerRecord<String, String> record) throws Exception {
        Header typeHeader = record.headers().lastHeader("type");
        String type = typeHeader != null ? new String(typeHeader.value(), StandardCharsets.UTF_8) : "unknown";
        log.info("Received order event: type={}, key={}, payload={}", type, record.key(), record.value());
        record.headers().forEach(h ->
            log.info("  Header: {}={}", h.key(), new String(h.value(), StandardCharsets.UTF_8)));
        var evt = mapper.readValue(record.value(), Events.OrderPlaced.class);
        inventory.handleOrderPlaced(evt);
    }
}
