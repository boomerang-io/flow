package io.boomerang.workflow.repository;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowScheduleRepository
    extends MongoRepository<WorkflowScheduleEntity, String> {

  Optional<List<WorkflowScheduleEntity>> findByWorkflowRef(String ref);

  Optional<List<WorkflowScheduleEntity>> findByIdInAndStatusIn(
      List<String> ids, List<WorkflowScheduleStatus> statuses);

  Optional<List<WorkflowScheduleEntity>> findByWorkflowRefInAndStatusIn(
      List<String> workflowRefs, List<WorkflowScheduleStatus> statuses);
}
