package com.dafywinf.inventory.outbox;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxRepository extends MongoRepository<OutboxMessage, String> {
    List<OutboxMessage> findTop50ByStatusOrderByCreatedAtAsc(String status);
}
