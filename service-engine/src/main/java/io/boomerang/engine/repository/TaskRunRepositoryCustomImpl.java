package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.engine.model.TaskRunTransition;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

// Requeue contract: a requeue clears claim.by/claim.at/claim.leaseExpiresAt, never claim.seq.
@Component
public class TaskRunRepositoryCustomImpl implements TaskRunRepositoryCustom {

  // Grace added on top of the timeout budget so a run at exactly its budget is not reaped.
  private static final long TIMEOUT_GRACE_MILLIS = 5000;

  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  public TaskRunRepositoryCustomImpl(
      MongoTemplate mongoTemplate, ApplicationEventPublisher eventPublisher) {
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public List<TaskRunEntity> findClaimable(List<TaskType> types, int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.pending)
                    .and("type")
                    .in(types)
                    .and("claim.by")
                    .exists(false)
                    .orOperator(
                        Criteria.where("retry.after").exists(false),
                        Criteria.where("retry.after").lte(new Date())))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  @Override
  public TaskRunEntity tryClaim(String id, String claimedBy) {
    // Re-checks full eligibility between page and claim; racing claimants cannot both win.
    Date now = new Date();
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.ready)
                .and("phase")
                .is(RunPhase.pending)
                .and("claim.by")
                .exists(false)
                .orOperator(
                    Criteria.where("retry.after").exists(false),
                    Criteria.where("retry.after").lte(now)));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", now)
            .set("agentRef", claimedBy)
            .inc("claim.seq", 1)
            .unset("retry.after");
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
    }
    return preImage;
  }

  @Override
  public TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.notstarted)
                .and("phase")
                .is(RunPhase.pending));
    Update update = new Update().set("status", RunStatus.ready).set("params", resolvedParams);
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

  @Override
  public TaskRunEntity tryStartExecution(String id, Date startTime, Long timeoutMinutes) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.ready)
                .and("phase")
                .in(RunPhase.pending, RunPhase.queued));
    Update update =
        new Update()
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("startTime", startTime);
    Date timeoutAt = timeoutAt(startTime, timeoutMinutes);
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
    if (preImage == null) {
      return null;
    }
    publish(preImage, RunStatus.running, RunPhase.running);
    // Return the pre-image with the transition applied - the caller executes on the new state.
    preImage.setStatus(RunStatus.running);
    preImage.setPhase(RunPhase.running);
    preImage.setStartTime(startTime);
    preImage.setTimeoutAt(timeoutAt);
    return preImage;
  }

  @Override
  public TaskRunEntity tryComplete(
      String id,
      Optional<RunStatus> status,
      Optional<String> statusMessage,
      long duration,
      Optional<String> claimedBy,
      Optional<Long> claimSeq) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and("phase")
            .in(RunPhase.pending, RunPhase.queued, RunPhase.running);
    claimedBy.ifPresent(by -> criteria.and("claim.by").is(by));
    claimSeq.ifPresent(seq -> criteria.and("claim.seq").is(seq));
    Update update =
        new Update()
            .set("phase", RunPhase.completed)
            .set("duration", duration)
            .unset("timeoutAt");
    status.ifPresent(s -> update.set("status", s));
    statusMessage.ifPresent(m -> update.set("statusMessage", m));
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            Query.query(criteria),
            update,
            FindAndModifyOptions.options().returnNew(false),
            TaskRunEntity.class);
    if (preImage != null) {
      publish(preImage, status.orElse(preImage.getStatus()), RunPhase.completed);
    }
    return preImage;
  }

  @Override
  public List<TaskRunEntity> findReapable(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("timeoutAt")
                    .lte(now)
                    .and("phase")
                    .in(RunPhase.queued, RunPhase.running))
            .with(Sort.by(Sort.Direction.ASC, "timeoutAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  @Override
  public TaskRunEntity tryTimeout(String id, Long observedClaimSeq) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and("phase")
            .in(RunPhase.queued, RunPhase.running)
            .and("timeoutAt")
            .lte(new Date());
    fence(criteria, observedClaimSeq);
    Update update = new Update().set("status", RunStatus.timedout).unset("timeoutAt");
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            Query.query(criteria),
            update,
            FindAndModifyOptions.options().returnNew(false),
            TaskRunEntity.class);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  @Override
  public TaskRunEntity tryRequeue(String id, Long observedClaimSeq, Date retryAfter, int retryCount) {
    Criteria criteria =
        Criteria.where("_id").is(id).and("phase").in(RunPhase.queued, RunPhase.running);
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set("status", RunStatus.ready)
            .set("phase", RunPhase.pending)
            .set("retry.after", retryAfter)
            .set("retry.count", retryCount)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt")
            .unset("agentRef")
            .unset("timeoutAt");
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            Query.query(criteria),
            update,
            FindAndModifyOptions.options().returnNew(false),
            TaskRunEntity.class);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, RunPhase.pending);
    }
    return preImage;
  }

  @Override
  public boolean existsInFlightByWorkflowRunRef(String workflowRunRef) {
    Query query =
        Query.query(
                Criteria.where("workflowRunRef")
                    .is(workflowRunRef)
                    .orOperator(
                        Criteria.where("phase").in(RunPhase.queued, RunPhase.running),
                        Criteria.where("status").in(RunStatus.ready, RunStatus.waiting)))
            .maxTimeMsec(5000);
    return mongoTemplate.exists(query, TaskRunEntity.class);
  }

  // A null observed seq fences on the run being unclaimed - a claim arriving between page and
  // Compare-And-Set carries a seq and fails the guard.
  private static void fence(Criteria criteria, Long observedClaimSeq) {
    if (observedClaimSeq != null) {
      criteria.and("claim.seq").is(observedClaimSeq);
    } else {
      criteria.and("claim.seq").exists(false);
    }
  }

  private static Date timeoutAt(Date from, Long timeoutMinutes) {
    return (timeoutMinutes != null && timeoutMinutes > 0)
        ? new Date(from.getTime() + timeoutMinutes * 60000 + TIMEOUT_GRACE_MILLIS)
        : null;
  }

  private void publish(TaskRunEntity preImage, RunStatus toStatus, RunPhase toPhase) {
    eventPublisher.publishEvent(
        new TaskRunTransition(
            preImage.getId(),
            preImage.getWorkflowRunRef(),
            preImage.getStatus(),
            preImage.getPhase(),
            toStatus,
            toPhase));
  }
}
