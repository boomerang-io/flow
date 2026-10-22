package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.ArtifactScope;
import io.boomerang.common.enums.ArtifactStatus;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * A file a task uploaded. The run that uploaded it owns it: access goes through the run's
 * relationship to its workspace, and the workspace-wide list and storage total go through the
 * workspace's workflows. A run-scoped artifact (the default) is downloadable only by that run and
 * expires on its retention date; a workflow-scoped one is the workflow's kept state, downloadable
 * by any later run of the workflow by name, with no expiry, replaced by the next verified upload
 * of that name. The stored file lives at storageKey in the artifact store; this record outlives it
 * once expired.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('artifacts')}")
public class ArtifactEntity {

  @Id private String id;
  private String name;
  private String workflowRef;
  private String workflowRunRef;
  private String taskRunRef;
  /** Absent on records written before scopes existed; read as {@link ArtifactScope#run}. */
  private ArtifactScope scope;
  private long size;
  private String sha256;
  private String contentType;
  private String storageKey;
  private ArtifactStatus status;
  private Date creationDate;
  private int retentionDays;
  /** Null for a workflow-scoped artifact: it is expired only when replaced. */
  private Date expirationDate;

  public ArtifactScope scopeOrRun() {
    return scope == null ? ArtifactScope.run : scope;
  }
}
