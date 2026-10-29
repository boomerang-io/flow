package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface TaskRunRepository extends MongoRepository<TaskRunEntity, String> {

  List<TaskRunEntity> findByWorkflowRunRef(String workflowRunRef);

  // A run's TaskRuns by id only, for the retention check that a child run points at none of
  // them. Served by the {workflowRunRef, status, name} index.
  @Query(value = "{ 'workflowRunRef': ?0 }", fields = "{ '_id': 1 }")
  List<TaskRunEntity> findIdsByWorkflowRunRef(String workflowRunRef);

  // The run's graph vertices: every TaskRun except the items of a foreach task.
  List<TaskRunEntity> findByWorkflowRunRefAndParentRefIsNull(String workflowRunRef);

  // A foreach task's items in item order. Served by the sparse (parentRef, index) index.
  List<TaskRunEntity> findByParentRefOrderByIndexAsc(String parentRef);

  Optional<TaskRunEntity> findFirstByNameAndWorkflowRunRef(String name, String workflowRunRef);

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  boolean existsByTaskRefAndPhaseIn(String taskRef, List<RunPhase> phases);

  // Whether the run's tasks have settled: one still ending (a cancel ends them off the caller's
  // thread), or a cancelled or timed-out one a dispatcher still holds, which keeps claim.by until
  // the dispatcher's terminate poll releases it. Served by the run index.
  @Query(
      value =
          "{ 'workflowRunRef': ?0, $or: [ { 'phase': { $ne: 'completed' } },"
              + " { 'status': { $in: ?1 }, 'claim.by': { $exists: true } } ] }",
      exists = true)
  boolean existsUnsettledTask(String workflowRunRef, Collection<RunStatus> terminated);
}
