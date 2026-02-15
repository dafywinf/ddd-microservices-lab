package com.dafywinf.inventory.events;

import java.time.Instant;
import java.util.List;

public final class Events {
    private Events() {}

    public record OrderPlaced(String eventId, Instant occurredAt, String orderId, List<LineItem> items) {
        public record LineItem(String sku, int quantity) {}
    }

    public record StockReserved(String eventId, Instant occurredAt, String orderId) {}

    public record StockReservationFailed(String eventId, Instant occurredAt, String orderId, String reason) {}
}
