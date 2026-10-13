package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.ArtifactStatus;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * A file a task uploaded to a run. The run owns it: access goes through the run's relationship to
 * its workspace, and the workspace-wide list and storage total go through the workspace's
 * workflows. The stored file lives at storageKey in the artifact store; this record outlives it
 * once expired.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('artifacts')}")
@CompoundIndexes({
  @CompoundIndex(name = "run_name_idx", def = "{'workflowRunRef': 1, 'name': 1}", unique = true),
  @CompoundIndex(name = "status_expiration_idx", def = "{'status': 1, 'expirationDate': 1}"),
  @CompoundIndex(name = "workflow_status_idx", def = "{'workflowRef': 1, 'status': 1}")
})
public class ArtifactEntity {

  @Id private String id;
  private String name;
  private String workflowRef;
  private String workflowRunRef;
  private String taskRunRef;
  private long size;
  private String sha256;
  private String contentType;
  private String storageKey;
  private ArtifactStatus status;
  private Date creationDate;
  private int retentionDays;
  private Date expirationDate;
}
