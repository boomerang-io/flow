package io.boomerang.workflow.repository;

import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.enums.ArtifactStatus;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArtifactRepository extends MongoRepository<ArtifactEntity, String> {

  List<ArtifactEntity> findByWorkflowRunRefOrderByCreationDateAsc(String workflowRunRef);

  Optional<ArtifactEntity> findByWorkflowRunRefAndName(String workflowRunRef, String name);

  boolean existsByWorkflowRunRefAndName(String workflowRunRef, String name);

  Page<ArtifactEntity> findByWorkflowRefInAndStatusIn(
      List<String> workflowRefs, List<ArtifactStatus> statuses, Pageable pageable);

  // Served by the {status, expirationDate} index: the expiry sweep and the stale-upload sweep.
  List<ArtifactEntity> findByStatusAndExpirationDateBefore(
      ArtifactStatus status, Date now, Pageable pageable);

  List<ArtifactEntity> findByStatusAndCreationDateBefore(
      ArtifactStatus status, Date cutoff, Pageable pageable);

  List<ArtifactEntity> findByWorkflowRef(String workflowRef, Pageable pageable);
}
