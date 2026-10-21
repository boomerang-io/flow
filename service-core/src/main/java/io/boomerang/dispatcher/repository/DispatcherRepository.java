package io.boomerang.dispatcher.repository;

import io.boomerang.dispatcher.entity.DispatcherEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DispatcherRepository extends MongoRepository<DispatcherEntity, String> {

  boolean existsById(String id);
}
