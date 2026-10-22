package io.boomerang.workflow.repository;

import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArtifactRepository extends MongoRepository<ArtifactEntity, String> {

  List<ArtifactEntity> findByWorkflowRunRefOrderByCreationDateAsc(String workflowRunRef);

  // The run's own records: run- and log-scoped (a record without a scope is run-scoped), never
  // the workflow-scoped ones a run uploaded, which outlive it.
  List<ArtifactEntity> findByWorkflowRunRefAndScopeNot(String workflowRunRef, ArtifactScope scope);

  Optional<ArtifactEntity> findByWorkflowRunRefAndName(String workflowRunRef, String name);

  boolean existsByWorkflowRunRefAndName(String workflowRunRef, String name);

  // The workflow's kept artifact of a name: at most one, under the partial unique index on
  // {workflowRef, name} for scope workflow and status available.
  Optional<ArtifactEntity> findByWorkflowRefAndNameAndScopeAndStatus(
      String workflowRef, String name, ArtifactScope scope, ArtifactStatus status);

  // Served by the {status, expirationDate} index: the expiry sweep and the stale-upload sweep.
  List<ArtifactEntity> findByStatusAndExpirationDateBefore(
      ArtifactStatus status, Date now, Pageable pageable);

  List<ArtifactEntity> findByStatusAndCreationDateBefore(
      ArtifactStatus status, Date cutoff, Pageable pageable);

  List<ArtifactEntity> findByWorkflowRef(String workflowRef, Pageable pageable);
}
