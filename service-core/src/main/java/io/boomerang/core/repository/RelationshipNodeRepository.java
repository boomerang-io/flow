package io.boomerang.core.repository;

import io.boomerang.core.entity.RelationshipNodeEntity;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface RelationshipNodeRepository
    extends MongoRepository<RelationshipNodeEntity, String> {
  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", exists = true)
  boolean existsByTypeAndRefOrSlug(String type, String refOrSlug);

  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", fields = "{ '_id': 1 }")
  RelationshipNodeEntity findByTypeAndRefOrSlug(String type, String refOrSlug);

  /**
   * Full node by type + ref-or-slug. The {@code $or} is planned as a union of one index scan per
   * branch, so it needs BOTH {@code {type, slug}} and {@code {type, ref}} — created by the loader's
   * {@code _0036__RelationshipAndAuditIndexes}. The entity's own {@code type_slug_idx}/{@code
   * type_ref_idx} annotations are inert ({@code spring.data.mongodb.auto-index-creation=false}).
   */
  @Query("{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}")
  Optional<RelationshipNodeEntity> findOneByTypeAndRefOrSlug(String type, String refOrSlug);

  boolean existsById(String id);

  @Query("{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}")
  @Update("{ '$set' : { 'slug' : ?2 } }")
  long updateSlugByTypeAndRefOrSlug(String type, String refOrSlug, String newSlug);

  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", delete = true)
  RelationshipNodeEntity deleteByRefOrSlug(String type, String refOrSlug);

  @Query(value = "{'type': ?0, 'ref': ?1}", delete = true)
  RelationshipNodeEntity deleteByTypeAndRef(String type, String ref);
}
