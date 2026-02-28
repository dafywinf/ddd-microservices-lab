package com.dafywinf.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

@Configuration
public class MongoConfig {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        // This tells Spring to use MongoDB's transaction capabilities
        // whenever it sees a @Transactional annotation
        return new MongoTransactionManager(dbFactory);
    }
}
