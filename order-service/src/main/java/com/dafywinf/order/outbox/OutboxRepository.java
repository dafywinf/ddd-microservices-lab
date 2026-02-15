package com.dafywinf.order.outbox;

import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface OutboxRepository extends MongoRepository<OutboxMessage, String> {
    List<OutboxMessage> findTop50ByStatusOrderByCreatedAtAsc(String status);
}
