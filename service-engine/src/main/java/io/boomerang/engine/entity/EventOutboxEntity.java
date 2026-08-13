package io.boomerang.engine.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunRetry;
import io.boomerang.engine.enums.OutboxStatus;
import java.util.Date;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Outbox row for one externally-visible status transition. Written by the transition winner
 * (after its Compare-And-Set, not transactionally - a crash in that window loses the event, the
 * database remains the source of truth). The dispatcher delivers rows at-least-once and marks
 * them sent; rows that exhaust their retries are marked dead, never silently dropped.
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

  public record RunState(RunStatus status, RunPhase phase) {}

  public record Routing(String workflowRef, String workflowRunRef) {}

  /** Mongo field paths used in queries — keep in sync with the field names above. */
  public static final class Fields {
    private Fields() {}

    public static final String STATUS = "status";
    public static final String RETRY_AFTER = "retry.after";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String SENT_AT = "sentAt";
    public static final String ATTEMPTS = "attempts";
  }
}
