package com.dafywinf.inventory.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface StockItemRepository extends MongoRepository<StockItem, String> {}