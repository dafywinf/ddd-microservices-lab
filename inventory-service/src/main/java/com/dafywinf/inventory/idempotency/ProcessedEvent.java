package com.dafywinf.inventory.idempotency;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("processed_events")
public class ProcessedEvent {
    @Id
    private String eventId;

    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
}
