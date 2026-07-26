package io.boomerang.workflow.repository;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowScheduleRepository
    extends MongoRepository<WorkflowScheduleEntity, String> {

  // The ScheduleWatcher's due-work page: schedules whose next fire time has passed.
  List<WorkflowScheduleEntity> findByStatusAndNextFireAtLessThanEqual(
      WorkflowScheduleStatus status, Date now, Pageable page);

  // Schedules that carry no next fire time yet (created under the legacy JobRunr model).
  List<WorkflowScheduleEntity> findByStatusAndNextFireAtIsNull(
      WorkflowScheduleStatus status, Pageable page);

  Optional<List<WorkflowScheduleEntity>> findByWorkflowRef(String ref);

  Optional<List<WorkflowScheduleEntity>> findByIdInAndStatusIn(
      List<String> ids, List<WorkflowScheduleStatus> statuses);

  Optional<List<WorkflowScheduleEntity>> findByWorkflowRefInAndStatusIn(
      List<String> workflowRefs, List<WorkflowScheduleStatus> statuses);
}
