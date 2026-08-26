package io.boomerang.engine;
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
import org.springframework.stereotype.Service;

/**
 * The engine-internal execution mechanics for a WorkflowRun: the claim pages, the Compare-And-Set
 * transitions, the recovery-sweep finders and the field-scoped writers. Everything here is a
 * guarded write against, or a narrow indexed read of, {@code workflow_runs} - there is no
 * orchestration and no authorization.
 *
 * <p>Its only callers are the engine ({@link WorkflowExecutionService}, {@link
 * TaskExecutionService}, {@link WorkflowWatcher}), the dispatcher's claim poller ({@code
 * io.boomerang.dispatcher.DispatcherService}) and {@code io.boomerang.workflow.WorkflowRunService},
 * which composes these primitives into the run operations the product exposes. Split out of the
 * old {@code engine.WorkflowRunService} at F3: the run operations are "outside the Engine's scope"
 * post-merge, these primitives are not.
 */
@Service
public class WorkflowRunStateHelper {

  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  public WorkflowRunStateHelper(
      MongoTemplate mongoTemplate, ApplicationEventPublisher eventPublisher) {
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  // Return the page of ready/pending unclaimed WorkflowRuns for an agent to provision, oldest
  // first.
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
    // The claim page only needs the id - tryClaimForProvision transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

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
    // The claim page only needs the id - tryClaimForTeardown transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public WorkflowRunEntity tryClaimForProvision(String id, String claimedBy) {
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
                .exists(false));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", now)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setClaim(TaskRunService.claimApplied(preImage.getClaim(), claimedBy, now));
    }
    return preImage;
  }

  public WorkflowRunEntity tryClaimForTeardown(String id, String claimedBy) {
    Date now = new Date();
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
            .set("claim.at", now)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), preImage.getPhase());
      // Return the pre-image with the claim owner applied - teardown leaves the phase (completed)
      // unchanged, so only the claim block needs patching for the caller's agent payload.
      preImage.setClaim(TaskRunService.claimApplied(preImage.getClaim(), claimedBy, now));
    }
    return preImage;
  }

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
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

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
    Date timeoutAt = RunTimeouts.deadline(startTime, timeoutMinutes);
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
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
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, status, RunPhase.completed);
    }
    return preImage;
  }

  public WorkflowRunEntity tryMarkTimedOut(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running));
    Update update = new Update().set("status", RunStatus.timedout);
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  public WorkflowRunEntity tryFinalize(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.completed));
    Update update = new Update().set("phase", RunPhase.finalized);
    WorkflowRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.finalized);
    }
    return preImage;
  }

  // Pause Compare-And-Set: a running, not-yet-paused run gains the flag. Returns whether this
  // caller won (the pre-image is not needed - pause publishes no transition).
  public boolean tryPause(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.running)
                .and("pauseRequestedAt")
                .exists(false));
    return mongoTemplate
            .updateFirst(
                query, new Update().set("pauseRequestedAt", new Date()), WorkflowRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // Resume Compare-And-Set: clears the pause flag. Returns whether this caller won.
  public boolean tryResume(String id) {
    Query query = Query.query(Criteria.where("_id").is(id).and("pauseRequestedAt").exists(true));
    return mongoTemplate
            .updateFirst(query, new Update().unset("pauseRequestedAt"), WorkflowRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // Paused runs are excluded from both recovery sweeps. The deadline deliberately does not
  // advance while paused - a run paused past its deadline is reaped on resume.
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

  // Return the page of in-flight WorkflowRuns, oldest first - the orphan backstop checks each
  // one's revision ref resolves; not itself an indexed predicate, so the page stays narrow and
  // any miss is caught again on the next tick.
  public List<WorkflowRunEntity> findInFlight(int limit) {
    Query query =
        Query.query(Criteria.where("phase").in(RunPhase.pending, RunPhase.queued, RunPhase.running))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public List<WorkflowRunEntity> findFinalizableWithoutWorkspaces(int limit) {
    Query query =
        Query.query(
                Criteria.where("phase").is(RunPhase.completed).and("workspaces.0").exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public void setAwaitingApproval(String id, boolean awaitingApproval) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("isAwaitingApproval", awaitingApproval),
        WorkflowRunEntity.class);
  }

  public void setStatusOverride(String id, RunStatus statusOverride) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("statusOverride", statusOverride),
        WorkflowRunEntity.class);
  }

  public void appendResult(String id, RunResult result) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().push("results", result),
        WorkflowRunEntity.class);
  }

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches, returning the pre-image (null = another caller won, so the caller does nothing).
  private WorkflowRunEntity findAndModifyPreImage(Query query, Update update) {
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
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
