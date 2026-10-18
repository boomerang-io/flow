package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import java.util.Date;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowRunRepository extends MongoRepository<WorkflowRunEntity, String> {

  void deleteByWorkflowRef(String workflowRef);

  boolean existsByWorkflowRefAndPhaseIn(String workflowRef, List<RunPhase> phases);

  List<WorkflowRunEntity> findByWorkflowRefAndPhaseIn(String workflowRef, List<RunPhase> phases);

  // The child runs a set of TaskRuns submitted that are still in flight - the cascade cancel of a
  // parent run. Served by the {initiatedByRef, phase} index.
  List<WorkflowRunEntity> findByInitiatedByRefInAndPhaseIn(
      List<String> initiatedByRefs, List<RunPhase> phases);

  // Whether any run still points at this one as its origin or parent. Served by the same index.
  boolean existsByInitiatedByRef(String initiatedByRef);

  // The retention page: finished runs created inside a window, oldest first. Served by the
  // {phase, creationDate} index; the lower bound is the cursor between pages.
  List<WorkflowRunEntity> findByPhaseAndCreationDateBetween(
      RunPhase phase, Date after, Date before, Pageable pageable);
}
