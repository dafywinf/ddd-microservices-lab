package com.dafywinf.order.app;

import com.dafywinf.order.outbox.OutboxRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 500)
    public void publishPending() {
        var pending = outbox.findTop50ByStatusOrderByCreatedAtAsc("PENDING");

        for (var msg : pending) {
            try {
                kafka.send(msg.getTopic(), msg.getAggregateId(), msg.getPayloadJson()).get();
                msg.markSent();
                outbox.save(msg);
            } catch (Exception e) {
                msg.markFailed();
                outbox.save(msg);
            }
        }
    }
}
