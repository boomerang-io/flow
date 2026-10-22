package io.boomerang.event.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunRetry;
import io.boomerang.event.enums.OutboxStatus;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Outbox row for one externally-visible status transition. Written by the transition winner
 * (after its Compare-And-Set, not transactionally - a crash in that window loses the event, the
 * database remains the source of truth). The dispatcher delivers rows at-least-once and marks
 * them sent; rows that exhaust their retries are marked dead, never silently dropped, carrying
 * the failure that killed them.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('events_outbox')}")
public class EventOutboxEntity {

  public static final String REF_TYPE_WORKFLOWRUN = "workflowrun";
  public static final String REF_TYPE_TASKRUN = "taskrun";

  @Id private String id;
  private String refType;
  private String ref;
  private RunState from;
  private RunState to;
  private Date occurredAt;
  private Routing routing;
  private OutboxStatus status = OutboxStatus.pending;
  private int attempts;

  // Only retry.after is used - the delivery attempt counter is the attempts field.
  private RunRetry retry;
  private Date sentAt;

  // Why the last delivery attempt failed, and when the row gave up. Both are operator-facing
  // only - the dispatcher never reads them - and both are cleared when a replay requeues the row.
  private String lastError;
  private Date deadAt;

  public record RunState(RunStatus status, RunPhase phase) {}

  public record Routing(String workflowRef, String workflowRunRef) {}
}
