package io.boomerang.engine.repository;

import io.boomerang.engine.entity.EventIngressEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventIngressRepository extends MongoRepository<EventIngressEntity, String> {}
