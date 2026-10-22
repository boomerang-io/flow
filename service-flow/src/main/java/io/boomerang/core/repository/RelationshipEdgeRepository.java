package io.boomerang.core.repository;

import java.util.List;
import java.util.Map;

import io.boomerang.core.entity.RelationshipEdgeEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface RelationshipEdgeRepository
    extends MongoRepository<RelationshipEdgeEntity, String> {

  List<RelationshipEdgeEntity> findByFromAndLabel(String from, String label);

  List<RelationshipEdgeEntity> findByToAndLabel(String to, String label);

  @Query(value = "{'$or': [{'from': ?0},{to: ?0}]}", delete = true)
  void deleteByFromOrTo(String fromOrTo);

  @Query(value = "{'from': ?0,to: ?1}", delete = true)
  void deleteByFromAndTo(String from, String to);

  @Query("{'from': ?0, to: ?1}")
  @Update("{ '$set' : { 'data' : ?2 } }")
  long updateDataByFromAndTo(String from, String to, Map<String, String> data);
}
