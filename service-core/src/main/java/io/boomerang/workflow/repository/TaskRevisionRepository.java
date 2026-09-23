package io.boomerang.workflow.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.common.entity.TaskRevisionEntity;

public interface TaskRevisionRepository extends MongoRepository<TaskRevisionEntity, String> {
  Integer countByParentRef(String parent);

  List<TaskRevisionEntity> findByParentRef(String parent);

  Optional<TaskRevisionEntity> findByParentRefAndVersion(String parent, Integer version);

  /**
   * Every revision of the given parents at any of the given versions - one round trip for a whole
   * page of (taskRef, taskVersion) pairs. The match is the cross product, so callers pick the
   * exact pair they asked for; the over-fetch is bounded by the distinct versions on one response.
   */
  List<TaskRevisionEntity> findByParentRefInAndVersionIn(
      Collection<String> parentRefs, Collection<Integer> versions);

  @Aggregation(
      pipeline = {"{'$match':{'parentRef': ?0}}", "{'$sort': {version: -1}}", "{'$limit': 1}"})
  Optional<TaskRevisionEntity> findByParentRefAndLatestVersion(String parent);

  void deleteByParentRef(String parent);
}
