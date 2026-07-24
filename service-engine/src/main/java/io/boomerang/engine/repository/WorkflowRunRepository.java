package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowRunRepository
    extends MongoRepository<WorkflowRunEntity, String>, WorkflowRunRepositoryCustom {

  void deleteByWorkflowRef(String workflowRef);

  boolean existsByWorkflowRefAndPhaseIn(String workflowRef, List<RunPhase> phases);

}
