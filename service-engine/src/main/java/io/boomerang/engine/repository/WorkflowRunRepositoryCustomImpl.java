package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.model.WorkflowRunTransition;
import java.util.Date;
import java.util.List;
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
public class WorkflowRunRepositoryCustomImpl implements WorkflowRunRepositoryCustom {

  // Grace added on top of the timeout budget so a run at exactly its budget is not reaped.
  private static final long TIMEOUT_GRACE_MILLIS = 5000;

  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  public WorkflowRunRepositoryCustomImpl(
      MongoTemplate mongoTemplate, ApplicationEventPublisher eventPublisher) {
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public List<WorkflowRunEntity> findClaimableForProvision(int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.pending)
                    .and("claim.by")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  @Override
  public List<WorkflowRunEntity> findClaimableForTeardown(int limit) {
    // workspaces.0 exists = the run still has workspaces for the claimant to tear down.
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.completed)
                    .and("claim.by")
                    .exists(false)
                    .and("workspaces.0")
                    .exists(true))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  @Override
  public WorkflowRunEntity tryClaimForProvision(String id, String claimedBy) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.ready)
                .and("phase")
                .is(RunPhase.pending)
                .and("claim.by")
                .exists(false));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("agentRef", claimedBy)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryClaimForTeardown(String id, String claimedBy) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.completed)
                .and("claim.by")
                .exists(false)
                .and("workspaces.0")
                .exists(true));
    Update update =
        new Update()
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("agentRef", claimedBy)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), preImage.getPhase());
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryAdmit(String id, List<RunParam> resolvedParams) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.notstarted)
                .and("phase")
                .is(RunPhase.pending));
    Update update = new Update().set("status", RunStatus.ready).set("params", resolvedParams);
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryStart(String id, Date startTime, Long timeoutMinutes) {
    // Clearing the dispatch claim frees the completed-phase teardown claimable; claim.seq is
    // never cleared and survives.
    Query query =
        Query.query(
            Criteria.where("_id").is(id).and("phase").in(RunPhase.pending, RunPhase.queued));
    Update update =
        new Update()
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("startTime", startTime)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt");
    Date timeoutAt =
        (timeoutMinutes != null && timeoutMinutes > 0)
            ? new Date(startTime.getTime() + timeoutMinutes * 60000 + TIMEOUT_GRACE_MILLIS)
            : null;
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage == null) {
      return null;
    }
    publish(preImage, RunStatus.running, RunPhase.running);
    // Return the pre-image with the transition applied - the caller executes on the new state.
    preImage.setStatus(RunStatus.running);
    preImage.setPhase(RunPhase.running);
    preImage.setStartTime(startTime);
    preImage.setTimeoutAt(timeoutAt);
    if (preImage.getClaim() != null) {
      preImage.getClaim().setBy(null);
      preImage.getClaim().setAt(null);
      preImage.getClaim().setLeaseExpiresAt(null);
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryComplete(
      String id, List<RunPhase> fromPhases, RunStatus status, String statusMessage, long duration) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").in(fromPhases));
    Update update =
        new Update()
            .set("status", status)
            .set("phase", RunPhase.completed)
            .set("duration", duration)
            .unset("timeoutAt");
    if (statusMessage != null) {
      update.set("statusMessage", statusMessage);
    }
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, status, RunPhase.completed);
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryMarkTimedOut(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running));
    Update update = new Update().set("status", RunStatus.timedout);
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryFinalize(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.completed));
    Update update = new Update().set("phase", RunPhase.finalized);
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.finalized);
    }
    return preImage;
  }

  @Override
  public WorkflowRunEntity tryPause(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.running)
                .and("pauseRequestedAt")
                .exists(false));
    return mongoTemplate.findAndModify(
        query,
        new Update().set("pauseRequestedAt", new Date()),
        FindAndModifyOptions.options().returnNew(false),
        WorkflowRunEntity.class);
  }

  @Override
  public WorkflowRunEntity tryResume(String id) {
    Query query =
        Query.query(Criteria.where("_id").is(id).and("pauseRequestedAt").exists(true));
    return mongoTemplate.findAndModify(
        query,
        new Update().unset("pauseRequestedAt"),
        FindAndModifyOptions.options().returnNew(false),
        WorkflowRunEntity.class);
  }

  // Paused runs are excluded from both recovery sweeps. The deadline deliberately does not
  // advance while paused - a run paused past its deadline is reaped on resume.
  @Override
  public List<WorkflowRunEntity> findTimedOut(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("timeoutAt")
                    .lte(now)
                    .and("phase")
                    .is(RunPhase.running)
                    .and("pauseRequestedAt")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "timeoutAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  @Override
  public List<WorkflowRunEntity> findRunningStartedBefore(Date startedBefore, int limit) {
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.running)
                    .and("startTime")
                    .lte(startedBefore)
                    .and("pauseRequestedAt")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "startTime"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  @Override
  public List<WorkflowRunEntity> findFinalizableWithoutWorkspaces(int limit) {
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.completed)
                    .and("workspaces.0")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  @Override
  public void setAwaitingApproval(String id, boolean awaitingApproval) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("isAwaitingApproval", awaitingApproval),
        WorkflowRunEntity.class);
  }

  @Override
  public void appendResult(String id, RunResult result) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().push("results", result),
        WorkflowRunEntity.class);
  }

  private void publish(WorkflowRunEntity preImage, RunStatus toStatus, RunPhase toPhase) {
    eventPublisher.publishEvent(
        new WorkflowRunTransition(
            preImage.getId(),
            preImage.getWorkflowRef(),
            preImage.getStatus(),
            preImage.getPhase(),
            toStatus,
            toPhase));
  }
}
