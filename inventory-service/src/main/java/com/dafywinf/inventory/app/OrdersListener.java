package com.dafywinf.inventory.app;

import com.dafywinf.inventory.events.Events;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrdersListener {

    private final ObjectMapper mapper;
    private final InventoryApplicationService inventory;

    public OrdersListener(ObjectMapper mapper, InventoryApplicationService inventory) {
        this.mapper = mapper;
        this.inventory = inventory;
    }

    @KafkaListener(topics = "${app.topics.orders}", groupId = "inventory-service")
    public void onOrderPlaced(String payload) throws Exception {
        var evt = mapper.readValue(payload, Events.OrderPlaced.class);
        inventory.handleOrderPlaced(evt);
    }
}
