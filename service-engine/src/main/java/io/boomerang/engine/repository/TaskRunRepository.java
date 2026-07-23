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

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  boolean existsByTaskRefAndPhaseIn(String taskRef, List<RunPhase> phases);
}
