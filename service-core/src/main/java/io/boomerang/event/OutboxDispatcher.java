package io.boomerang.event;

import io.boomerang.common.util.Backoff;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.OutboxStatus;
import io.boomerang.engine.EngineConstants;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.Date;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Drains the events_outbox: re-reads the referenced run and delivers its status CloudEvent, then
 * marks the row sent via a Compare-And-Set. Runs on every instance; at-least-once - a racing
 * double delivery is possible but only one dispatcher marks the row sent. Rows that exhaust
 * their retries go dead (kept, logged, never silently dropped), with the failure and the time
 * they gave up recorded on the row for the operator route to show. Scheduling rides the same
 * {@code flow.watcher.enabled} test hook as the watcher; the bean itself only exists when the
 * sink is enabled.
 */
@Service
@ConditionalOnProperty(
    name = "flow.events.sink.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class OutboxDispatcher {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int PAGE_SIZE = EngineConstants.SWEEP_PAGE_SIZE;
  private static final int MAX_ATTEMPTS = 3;
  // Cap on the recorded failure text: a sink that answers with an HTML error page or a stack
  // trace would otherwise grow the row without bound.
  private static final int MAX_ERROR_LENGTH = 1024;

  private final MongoTemplate mongoTemplate;
  private final TaskRunRepository taskRunRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final EventSinkService eventSinkService;

  public OutboxDispatcher(
      MongoTemplate mongoTemplate,
      TaskRunRepository taskRunRepository,
      WorkflowRunRepository workflowRunRepository,
      EventSinkService eventSinkService) {
    this.mongoTemplate = mongoTemplate;
    this.taskRunRepository = taskRunRepository;
    this.workflowRunRepository = workflowRunRepository;
    this.eventSinkService = eventSinkService;
  }

  @Scheduled(
      initialDelayString = "#{T(java.util.concurrent.ThreadLocalRandom).current().nextLong(5000)}",
      fixedDelayString = "${flow.events.outbox.interval-ms:5000}")
  public void drain() {
    for (EventOutboxEntity row : findDeliverable(new Date(), PAGE_SIZE)) {
      try {
        deliver(row);
        if (tryMarkSent(row.getId(), new Date()) != null) {
          LOGGER.debug("[{}] Outbox row delivered ({} {}).", row.getId(), row.getRefType(), row.getRef());
        }
      } catch (Exception ex) {
        int attempts = row.getAttempts() + 1;
        String error = describe(ex);
        if (attempts >= MAX_ATTEMPTS) {
          if (tryMarkDead(row.getId(), error, new Date()) != null) {
            LOGGER.error(
                "[{}] Outbox row dead after {} delivery attempts ({} {}): {}",
                row.getId(), attempts, row.getRefType(), row.getRef(), ex.getMessage());
          }
        } else {
          tryRequeueDelivery(row.getId(), Backoff.nextRetryAt(attempts), attempts, error);
          LOGGER.warn(
              "[{}] Outbox delivery failed (attempt {}), retrying: {}",
              row.getId(), attempts, ex.getMessage());
        }
      }
    }
  }

  // The referenced run is the payload source of truth - a row whose run no longer exists has
  // nothing to deliver and goes dead.
  private void deliver(EventOutboxEntity row) throws Exception {
    if (EventOutboxEntity.REF_TYPE_TASKRUN.equals(row.getRefType())) {
      eventSinkService.deliverStatusCloudEvent(
          taskRunRepository
              .findById(row.getRef())
              .orElseThrow(() -> new IllegalStateException("TaskRun no longer exists")));
    } else {
      eventSinkService.deliverStatusCloudEvent(
          workflowRunRepository
              .findById(row.getRef())
              .orElseThrow(() -> new IllegalStateException("WorkflowRun no longer exists")));
    }
  }

  // Pending rows whose retry backoff has elapsed, oldest first. Package-private so the outbox
  // contract test can assert a sent row is never redelivered.
  List<EventOutboxEntity> findDeliverable(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(OutboxStatus.pending)
                    .orOperator(
                        Criteria.where("retry.after").exists(false),
                        Criteria.where("retry.after").lte(now)))
            .with(Sort.by(Sort.Direction.ASC, "occurredAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, EventOutboxEntity.class);
  }

  private EventOutboxEntity tryMarkSent(String id, Date sentAt) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update().set("status", OutboxStatus.sent).set("sentAt", sentAt).unset("retry.after"),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  private EventOutboxEntity tryRequeueDelivery(
      String id, Date retryAfter, int attempts, String error) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update()
            .set("retry.after", retryAfter)
            .set("attempts", attempts)
            .set("lastError", error),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  private EventOutboxEntity tryMarkDead(String id, String error, Date deadAt) {
    return mongoTemplate.findAndModify(
        pendingById(id),
        new Update()
            .set("status", OutboxStatus.dead)
            .set("lastError", error)
            .set("deadAt", deadAt)
            .unset("retry.after"),
        FindAndModifyOptions.options().returnNew(false),
        EventOutboxEntity.class);
  }

  // The failure as the operator reads it off the row: type plus message, so a message-less
  // exception still says something, capped at MAX_ERROR_LENGTH characters.
  private static String describe(Exception ex) {
    String detail =
        ex.getClass().getSimpleName() + ((ex.getMessage() != null) ? ": " + ex.getMessage() : "");
    return ((detail.length() > MAX_ERROR_LENGTH) ? detail.substring(0, MAX_ERROR_LENGTH) : detail);
  }

  private static Query pendingById(String id) {
    return Query.query(Criteria.where("_id").is(id).and("status").is(OutboxStatus.pending));
  }
}
