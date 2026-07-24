package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TaskRunRepository
    extends MongoRepository<TaskRunEntity, String>, TaskRunRepositoryCustom {

  List<TaskRunEntity> findByWorkflowRunRef(String workflowRunRef);

  Optional<TaskRunEntity> findFirstByNameAndWorkflowRunRef(String name, String workflowRunRef);

  // All generations of a node (live + superseded) - used to number the next superseded attempt.
  List<TaskRunEntity> findByNameAndWorkflowRunRef(String name, String workflowRunRef);

  // Live generation only (superseded absent). Gating, result resolution and default API reads use
  // these so an old generation never doubles a graph node or shadows the current result.
  List<TaskRunEntity> findByWorkflowRunRefAndSupersededAtIsNull(String workflowRunRef);

  Optional<TaskRunEntity> findFirstByNameAndWorkflowRunRefAndSupersededAtIsNull(
      String name, String workflowRunRef);

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  boolean existsByTaskRefAndPhaseIn(String taskRef, List<RunPhase> phases);
}
