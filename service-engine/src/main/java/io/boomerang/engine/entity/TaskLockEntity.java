package io.boomerang.engine.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A user-facing cross-workflow mutex, one document per lock key. The claim pattern applied to the
 * lock key itself: {@code _id} is the workspace-scoped key, {@code holder} is the acquiring
 * TaskRun, and {@code expiresAt} is the lease - a TTL index garbage-collects a crashed holder's
 * lock, but correctness is the acquire Compare-And-Set checking {@code expiresAt}, not the sweep.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('task_locks')}")
public class TaskLockEntity {

  @Id private String id;
  private String holder;
  private String workflowRunRef;
  private Date acquiredAt;
  private Date expiresAt;
}
