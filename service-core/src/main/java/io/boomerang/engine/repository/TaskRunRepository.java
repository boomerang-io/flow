package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TaskRunRepository extends MongoRepository<TaskRunEntity, String> {

  List<TaskRunEntity> findByWorkflowRunRef(String workflowRunRef);

  // The run's graph vertices: every TaskRun except the items of a foreach task.
  List<TaskRunEntity> findByWorkflowRunRefAndParentRefIsNull(String workflowRunRef);

  // A foreach task's items in item order. Served by the sparse (parentRef, index) index.
  List<TaskRunEntity> findByParentRefOrderByIndexAsc(String parentRef);

  Optional<TaskRunEntity> findFirstByNameAndWorkflowRunRef(String name, String workflowRunRef);

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  boolean existsByTaskRefAndPhaseIn(String taskRef, List<RunPhase> phases);
}
