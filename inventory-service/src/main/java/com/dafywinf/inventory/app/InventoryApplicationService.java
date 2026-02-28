package com.dafywinf.inventory.app;

import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import com.dafywinf.inventory.events.Events;
import com.dafywinf.inventory.idempotency.ProcessedEvent;
import com.dafywinf.inventory.idempotency.ProcessedEventRepository;
import com.dafywinf.inventory.outbox.OutboxMessage;
import com.dafywinf.inventory.outbox.OutboxRepository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class InventoryApplicationService {

    private final StockItemRepository stock;
    private final ProcessedEventRepository processed;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final String inventoryTopic;

    public InventoryApplicationService(
            StockItemRepository stock,
            ProcessedEventRepository processed,
            OutboxRepository outbox,
            ObjectMapper mapper,
            @Value("${app.topics.inventory}") String inventoryTopic
    ) {
        this.stock = stock;
        this.processed = processed;
        this.outbox = outbox;
        this.mapper = mapper;
        this.inventoryTopic = inventoryTopic;
    }

    public void seed(String sku, int available) {
        stock.save(new StockItem(sku, available));
    }

    /**
     * Processes an OrderPlaced event inside a single MongoDB transaction.
     * All three writes — idempotency mark, stock reservations, and outbox message —
     * either commit together or roll back together.
     *
     * Domain failures (unknown SKU, insufficient stock) are caught inside the
     * transaction so a StockReservationFailed outbox message is committed instead;
     * no stock changes are written because saveAll() is never reached.
     */
    @Transactional
    public void handleOrderPlaced(Events.OrderPlaced evt) throws Exception {
        if (processed.existsById(evt.eventId())) return;
        processed.save(new ProcessedEvent(evt.eventId()));

        try {
            reserveAll(evt.items());
            var ok = new Events.StockReserved(UUID.randomUUID().toString(), Instant.now(), evt.orderId());
            outbox.save(new OutboxMessage(evt.orderId(), inventoryTopic, "StockReserved", mapper.writeValueAsString(ok)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            var fail = new Events.StockReservationFailed(
                    UUID.randomUUID().toString(), Instant.now(), evt.orderId(), e.getMessage());
            outbox.save(new OutboxMessage(evt.orderId(), inventoryTopic, "StockReservationFailed", mapper.writeValueAsString(fail)));
        }
    }

    // Applies all reservations in-memory first, then persists in one batch.
    // If any item is unknown or has insufficient stock, throws before any write occurs.
    private void reserveAll(List<Events.OrderPlaced.LineItem> items) {
        List<String> skus = items.stream().map(Events.OrderPlaced.LineItem::sku).toList();
        Map<String, StockItem> stockMap = stock.findAllById(skus).stream()
                .collect(Collectors.toMap(StockItem::getSku, item -> item));

        for (var li : items) {
            StockItem item = stockMap.get(li.sku());
            if (item == null) throw new IllegalArgumentException("unknown sku=" + li.sku());
            item.reserve(li.quantity());
        }

        stock.saveAll(stockMap.values());
    }
}
