package io.boomerang.dispatcher.repository;

import io.boomerang.dispatcher.entity.DispatcherEntity;
import java.util.Date;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface DispatcherRepository extends MongoRepository<DispatcherEntity, String> {

  boolean existsById(String id);

  /** The dispatchers connected since the given time, by id only. */
  @Query(value = "{ 'lastConnectedDate': { '$gt': ?0 } }", fields = "{ '_id': 1 }")
  List<DispatcherEntity> findConnectedSince(Date since);
}
