package com.dafywinf.order.app;

import com.dafywinf.order.domain.Order;
import com.dafywinf.order.domain.OrderRepository;
import com.dafywinf.order.events.Events;
import com.dafywinf.order.outbox.OutboxMessage;
import com.dafywinf.order.outbox.OutboxRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderApplicationService {

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;

    private final String ordersTopic;

    public OrderApplicationService(
            OrderRepository orders,
            OutboxRepository outbox,
            ObjectMapper mapper,
            @Value("${app.topics.orders}") String ordersTopic
    ) {
        this.orders = orders;
        this.outbox = outbox;
        this.mapper = mapper;
        this.ordersTopic = ordersTopic;
    }

    public String createDraft() {
        Order o = Order.draft();
        orders.save(o);
        return o.getId();
    }

    public void addLine(String orderId, String sku, int qty) {
        Order o = orders.findById(orderId).orElseThrow();
        o.addLine(sku, qty);
        orders.save(o);
    }

    @Transactional
    public void placeOrder(String orderId) {
        Order o = orders.findById(orderId).orElseThrow();
        o.place();
        orders.save(o);

        var event = new Events.OrderPlaced(
                UUID.randomUUID().toString(),
                Instant.now(),
                o.getId(),
                o.getLines().stream()
                        .map(l -> new Events.OrderPlaced.LineItem(l.sku(), l.quantity()))
                        .toList()
        );

        try {
            String json = mapper.writeValueAsString(event);
            outbox.save(new OutboxMessage(o.getId(), ordersTopic, "OrderPlaced", json));
        } catch (Exception e) {
            throw new RuntimeException("failed to serialise event", e);
        }
    }

    public void confirm(String orderId) {
        Order o = orders.findById(orderId).orElseThrow();
        o.confirm();
        orders.save(o);
    }

    public void reject(String orderId, String reason) {
        Order o = orders.findById(orderId).orElseThrow();
        o.reject(reason);
        orders.save(o);
    }
}
