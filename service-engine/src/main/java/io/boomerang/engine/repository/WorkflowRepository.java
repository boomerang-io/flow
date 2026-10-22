package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.WorkflowStatus;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowRepository extends MongoRepository<WorkflowEntity, String> {

  List<WorkflowEntity> findByStatus(WorkflowStatus status);
}
