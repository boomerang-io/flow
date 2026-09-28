package io.boomerang.workflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.enums.ArtifactStatus;
import java.util.Date;

/*
 * The public view of an artifact. The storage key never leaves service-core: files are reached
 * only through the artifact endpoints, which check access through the run.
 */
@JsonInclude(Include.NON_NULL)
public record Artifact(
    String id,
    String name,
    String workflowRef,
    String workflowRunRef,
    String taskRunRef,
    long size,
    String sha256,
    String contentType,
    ArtifactStatus status,
    Date creationDate,
    int retentionDays,
    Date expirationDate) {

  public Artifact(ArtifactEntity entity) {
    this(
        entity.getId(),
        entity.getName(),
        entity.getWorkflowRef(),
        entity.getWorkflowRunRef(),
        entity.getTaskRunRef(),
        entity.getSize(),
        entity.getSha256(),
        entity.getContentType(),
        entity.getStatus(),
        entity.getCreationDate(),
        entity.getRetentionDays(),
        entity.getExpirationDate());
  }
}
