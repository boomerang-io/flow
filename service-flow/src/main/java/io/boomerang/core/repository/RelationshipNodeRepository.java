package io.boomerang.core.repository;

import io.boomerang.core.entity.RelationshipNodeEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface RelationshipNodeRepository
    extends MongoRepository<RelationshipNodeEntity, String> {
  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", exists = true)
  boolean existsByTypeAndRefOrSlug(String type, String refOrSlug);

  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", fields = "{ '_id': 1 }")
  RelationshipNodeEntity findByTypeAndRefOrSlug(String type, String refOrSlug);

  boolean existsById(String id);

  @Query("{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}")
  @Update("{ '$set' : { 'slug' : ?2 } }")
  long updateSlugByTypeAndRefOrSlug(String type, String refOrSlug, String newSlug);

  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", delete = true)
  RelationshipNodeEntity deleteByRefOrSlug(String type, String refOrSlug);

  @Query(value = "{'type': ?0, 'ref': ?1}", delete = true)
  RelationshipNodeEntity deleteByTypeAndRef(String type, String ref);
}
