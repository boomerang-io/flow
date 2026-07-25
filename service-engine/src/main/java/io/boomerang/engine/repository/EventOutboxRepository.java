package io.boomerang.engine.repository;

import io.boomerang.engine.entity.EventOutboxEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventOutboxRepository extends MongoRepository<EventOutboxEntity, String> {}
