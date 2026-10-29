package io.boomerang.workflow.repository;

import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.enums.WorkflowType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WorkflowRepository extends MongoRepository<WorkflowEntity, String> {

  List<WorkflowEntity> findByStatus(WorkflowStatus status);

  List<WorkflowEntity> findByStatus(WorkflowStatus status, Pageable pageable);

  // A template or starter by name: the shared types live only in the system workspace, where a
  // name is unique, so the first match is the one.
  Optional<WorkflowEntity> findFirstByNameAndTypeIn(String name, Collection<WorkflowType> types);

  // Whether any workflow not yet deleted runs this template by reference.
  boolean existsByTemplateRefRefAndStatusNot(String ref, WorkflowStatus status);

  // A workspace's link to a template: among the workspace's workflows (ids from the relationship
  // walk), the one naming the template and still active.
  Optional<WorkflowEntity> findFirstByIdInAndTemplateRefRefAndStatus(
      Collection<String> ids, String ref, WorkflowStatus status);
}
