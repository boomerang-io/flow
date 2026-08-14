package io.boomerang.event.repository;

import io.boomerang.event.entity.EventInboxEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventInboxRepository extends MongoRepository<EventInboxEntity, String> {}
