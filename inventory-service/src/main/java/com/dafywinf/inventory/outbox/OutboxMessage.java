package com.dafywinf.inventory.outbox;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("outbox")
public class OutboxMessage {
    @Id
    private String id;

    private String aggregateId;
    private String topic;
    private String type;
    private String payloadJson;
    private Instant createdAt;
    private String status; // PENDING, SENT, FAILED

    protected OutboxMessage() {}

    public OutboxMessage(String aggregateId, String topic, String type, String payloadJson) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.type = type;
        this.payloadJson = payloadJson;
        this.createdAt = Instant.now();
        this.status = "PENDING";
    }

    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getTopic() { return topic; }
    public String getType() { return type; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }

    public void markSent() { this.status = "SENT"; }
    public void markFailed() { this.status = "FAILED"; }
}
