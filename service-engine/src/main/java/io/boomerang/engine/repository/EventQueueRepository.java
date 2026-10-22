package io.boomerang.engine.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.engine.entity.EventQueueEntity;

public interface EventQueueRepository extends MongoRepository<EventQueueEntity, String> {
  
}

