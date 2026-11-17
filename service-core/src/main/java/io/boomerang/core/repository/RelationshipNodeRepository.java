package io.boomerang.core.repository;

import io.boomerang.core.entity.RelationshipNodeEntity;
import java.util.Collection;
import java.util.List;
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
   * branch, so it needs BOTH {@code {type, slug}} and {@code {type, ref}} — the {@code type_slug}
   * and {@code type_ref} indexes {@code io.boomerang.migration._0021__Indexes} builds. The entity's
   * own {@code type_slug_idx}/{@code type_ref_idx} annotations are inert ({@code
   * spring.data.mongodb.auto-index-creation=false}).
   */
  @Query("{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}")
  Optional<RelationshipNodeEntity> findOneByTypeAndRefOrSlug(String type, String refOrSlug);

  boolean existsById(String id);

  /**
   * Nodes of one type whose slug or ref is any of the values: {@link #findOneByTypeAndRefOrSlug}
   * for several at once, served by the same {@code type_slug} and {@code type_ref} indexes.
   */
  @Query("{'type': ?0, '$or': [{'slug': {'$in': ?1}},{'ref': {'$in': ?1}}]}")
  List<RelationshipNodeEntity> findByTypeAndRefOrSlugIn(
      String type, Collection<String> refsOrSlugs);

  /**
   * The nodes among the ids that are of the type and whose slug or ref is any of the values - which
   * of a set of nodes a name list matches, served by the {@code _id} index - as type, ref and slug.
   */
  @Query(
      value = "{'_id': {'$in': ?0}, 'type': ?1, '$or': [{'slug': {'$in': ?2}},{'ref': {'$in': ?2}}]}",
      fields = "{'type': 1, 'ref': 1, 'slug': 1}")
  List<RelationshipNodeEntity> findByIdInAndTypeAndRefOrSlugIn(
      Collection<String> ids, String type, Collection<String> refsOrSlugs);

  /**
   * The nodes of these ids as type, ref and slug only, without their data - a walk level's nodes.
   */
  @Query(value = "{'_id': {'$in': ?0}}", fields = "{'type': 1, 'ref': 1, 'slug': 1}")
  List<RelationshipNodeEntity> findRefsByIdIn(Collection<String> ids);

  /**
   * {@link #findRefsByIdIn}, leaving out the nodes of the type whose ref and slug are none of the
   * values: a walk level that looks for named nodes reads only those of the type it names.
   */
  @Query(
      value =
          "{'_id': {'$in': ?0}, '$or': [{'type': {'$ne': ?1}}, {'slug': {'$in': ?2}},"
              + " {'ref': {'$in': ?2}}]}",
      fields = "{'type': 1, 'ref': 1, 'slug': 1}")
  List<RelationshipNodeEntity> findRefsByIdInUnlessTypeAndUnnamed(
      Collection<String> ids, String type, Collection<String> refsOrSlugs);

  /** Nodes of one type carrying any of the slugs; served by the {@code type_slug} index. */
  List<RelationshipNodeEntity> findByTypeAndSlugIn(String type, Collection<String> slugs);

  @Query("{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}")
  @Update("{ '$set' : { 'slug' : ?2 } }")
  long updateSlugByTypeAndRefOrSlug(String type, String refOrSlug, String newSlug);

  @Query(value = "{'type': ?0, '$or': [{'slug': ?1},{'ref': ?1}]}", delete = true)
  RelationshipNodeEntity deleteByRefOrSlug(String type, String refOrSlug);

  @Query(value = "{'type': ?0, 'ref': ?1}", delete = true)
  RelationshipNodeEntity deleteByTypeAndRef(String type, String ref);
}
