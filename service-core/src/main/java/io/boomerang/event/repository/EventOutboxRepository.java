package io.boomerang.event.repository;

import io.boomerang.event.entity.EventOutboxEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventOutboxRepository extends MongoRepository<EventOutboxEntity, String> {}
