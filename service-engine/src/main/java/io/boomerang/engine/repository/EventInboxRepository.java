package io.boomerang.engine.repository;

import io.boomerang.engine.entity.EventInboxEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventInboxRepository extends MongoRepository<EventInboxEntity, String> {}
