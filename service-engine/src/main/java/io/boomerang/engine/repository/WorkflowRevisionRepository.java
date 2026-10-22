package io.boomerang.engine.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.common.entity.WorkflowRevisionEntity;

public interface WorkflowRevisionRepository
    extends MongoRepository<WorkflowRevisionEntity, String> {

  Integer countByWorkflowRef(String workflowRef);

  Optional<WorkflowRevisionEntity> findByWorkflowRefAndVersion(String workflowRef, Integer version);

  List<WorkflowRevisionEntity> findByWorkflowRef(String string);

  @Aggregation(
      pipeline = {"{'$match':{'workflowRef': ?0}}", "{'$sort': {version: -1}}", "{'$limit': 1}"})
  Optional<WorkflowRevisionEntity> findByWorkflowRefAndLatestVersion(String workflowRef);

  void deleteByWorkflowRef(String workflowRef);
}
