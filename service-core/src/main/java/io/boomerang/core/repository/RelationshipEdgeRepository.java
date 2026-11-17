package io.boomerang.core.repository;

import java.util.Collection;
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

  /** Outgoing edges for a set of nodes. */
  List<RelationshipEdgeEntity> findByFromIn(Collection<String> from);

  /** Outgoing edges for a set of nodes as their ends only - one query per downward walk level. */
  @Query(value = "{'from': {'$in': ?0}}", fields = "{'from': 1, 'to': 1}")
  List<RelationshipEdgeEntity> findEndsByFromIn(Collection<String> from);

  /**
   * {@link #findEndsByFromIn}, but for the edges with one label - a walk level that leaves out a
   * workspace's runs. Served by the from_label index, so the edges left out are never read.
   */
  @Query(value = "{'from': {'$in': ?0}, 'label': {'$ne': ?1}}", fields = "{'from': 1, 'to': 1}")
  List<RelationshipEdgeEntity> findEndsByFromInAndLabelNot(Collection<String> from, String label);

  /** The edges from any of a set of nodes to any of another - served by the from_label index. */
  List<RelationshipEdgeEntity> findByFromInAndToIn(Collection<String> from, Collection<String> to);

  /** The edges from one node to any of a set of nodes - served by the from_label index. */
  List<RelationshipEdgeEntity> findByFromAndToIn(String from, Collection<String> to);

  /** Outgoing edges with one label for a set of nodes - the children of each, in one query. */
  List<RelationshipEdgeEntity> findByFromInAndLabel(Collection<String> from, String label);

  /** Incoming edges, whatever their label, for a set of nodes - served by the to_label index. */
  List<RelationshipEdgeEntity> findByToIn(Collection<String> to);

  /** Incoming edges with one label for a set of nodes - the parents of each, in one query. */
  List<RelationshipEdgeEntity> findByToInAndLabel(Collection<String> to, String label);

  @Query(value = "{'$or': [{'from': ?0},{to: ?0}]}", delete = true)
  void deleteByFromOrTo(String fromOrTo);

  @Query(value = "{'from': ?0,to: ?1}", delete = true)
  void deleteByFromAndTo(String from, String to);

  /** Delete the edge of the id only while it still runs to {@code to}; the count deleted. */
  long deleteByIdAndTo(String id, String to);

  @Query("{'from': ?0, to: ?1}")
  @Update("{ '$set' : { 'data' : ?2 } }")
  long updateDataByFromAndTo(String from, String to, Map<String, String> data);
}
