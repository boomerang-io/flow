package io.boomerang.engine.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.common.entity.TaskRevisionEntity;

public interface TaskRevisionRepository extends MongoRepository<TaskRevisionEntity, String> {
  Integer countByParentRef(String parent);

  List<TaskRevisionEntity> findByParentRef(String parent);

  Optional<TaskRevisionEntity> findByParentRefAndVersion(String parent, Integer version);

  @Aggregation(
      pipeline = {"{'$match':{'parentRef': ?0}}", "{'$sort': {version: -1}}", "{'$limit': 1}"})
  Optional<TaskRevisionEntity> findByParentRefAndLatestVersion(String parent);

  void deleteByParentRef(String parent);
}
