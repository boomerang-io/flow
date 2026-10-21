package io.boomerang.engine;
import io.boomerang.core.config.SecretValueMongoListener;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.WorkflowRunTransition;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
  // An Update never reaches the save hook, so the admission write seals secret values itself.
  private final SecretValueMongoListener secretValues;

  public WorkflowRunStateHelper(
      MongoTemplate mongoTemplate,
      ApplicationEventPublisher eventPublisher,
      SecretValueMongoListener secretValues) {
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
    this.secretValues = secretValues;
  }

  // Return the page of ready/pending unclaimed WorkflowRuns for an agent to provision, oldest
  // first. workspace exists = the run actually has a workspace to provision; a run with none
  // is started directly and never needs a provisioning claimant.
  public List<WorkflowRunEntity> findClaimableForProvision(int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.pending)
                    .and("claim.by")
                    .exists(false)
                    .and("workspace")
                    .exists(true))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaimForProvision transitions by id.
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
                .exists(false)
                .and("workspace")
                .exists(true));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", now)
            .inc("claim.seq", 1);
    WorkflowRunEntity preImage = cas(query, update, null, RunPhase.queued);
    if (preImage != null) {
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setClaim(TaskRunService.claimApplied(preImage.getClaim(), claimedBy, now));
    }
    return preImage;
  }

  // Return the page of provisioning claims taken before claimedBefore that never reached start,
  // oldest first. Served by claim_page {status, phase, creationDate}; claim.at filters the small
  // ready/queued bucket.
  public List<WorkflowRunEntity> findStaleProvisionClaims(Date claimedBefore, int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.queued)
                    .and("claim.at")
                    .lte(claimedBefore))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  // Release a stale provisioning claim back to claimable, fenced on the observed claim.seq so a
  // run released and re-claimed since it was read is left alone.
  public WorkflowRunEntity tryReleaseProvisionClaim(String id, long observedSeq) {
    Update update =
        new Update()
            .set("phase", RunPhase.pending)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt");
    return cas(provisionClaimed(id, observedSeq), update, null, RunPhase.pending);
  }

  // Fail a run whose provisioning attempts are spent, fenced like the release.
  public WorkflowRunEntity tryFailProvision(String id, long observedSeq, String statusMessage) {
    Update update =
        new Update()
            .set("status", RunStatus.failed)
            .set("phase", RunPhase.completed)
            .set("statusMessage", statusMessage)
            .set("duration", 0L);
    return cas(provisionClaimed(id, observedSeq), update, RunStatus.failed, RunPhase.completed);
  }

  private static Query provisionClaimed(String id, long observedSeq) {
    return Query.query(
        Criteria.where("_id")
            .is(id)
            .and("status")
            .is(RunStatus.ready)
            .and("phase")
            .is(RunPhase.queued)
            .and("claim.seq")
            .is(observedSeq));
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
    Update update = new Update().set("status", RunStatus.ready).set("params", secretValues.sealRunParams(resolvedParams));
    return cas(query, update, RunStatus.ready, null);
  }

  public WorkflowRunEntity tryStart(String id, Date startTime, Long timeoutMinutes) {
    // The dispatch claim is cleared on start; claim.seq is never cleared and survives.
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
    WorkflowRunEntity preImage = cas(query, update, RunStatus.running, RunPhase.running);
    if (preImage == null) {
      return null;
    }
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
            .unset("timeoutAt")
            .unset("waitingSince");
    if (statusMessage != null) {
      update.set("statusMessage", statusMessage);
    }
    return cas(query, update, status, RunPhase.completed);
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

  // Resume Compare-And-Set: clears the pause flag and moves the deadline forward by the time
  // paused. Returns whether this caller won.
  public boolean tryResume(String id) {
    WorkflowRunEntity run = mongoTemplate.findById(id, WorkflowRunEntity.class);
    return run != null
        && run.getPauseRequestedAt() != null
        && tryClearStop(run, "pauseRequestedAt", run.getPauseRequestedAt(), run.getWaitingSince());
  }

  // Stop Compare-And-Set: a running run whose in-flight work is all waiting records when the
  // wait began, and the timeout sweep leaves it alone from then on. Returns whether this caller
  // won.
  public boolean tryMarkWaiting(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.running)
                .and("waitingSince")
                .exists(false));
    return mongoTemplate
            .updateFirst(query, new Update().set("waitingSince", new Date()), WorkflowRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // Work resumed: clears the wait and moves the deadline forward by the time spent waiting. A
  // no-op on a run that is not waiting, so every "a task is executing" path may call it.
  public boolean tryResumeWaiting(String id) {
    // Reads only a waiting run, and only the three fields the resume uses: on most calls the run is
    // not waiting and nothing comes back.
    Query query = Query.query(Criteria.where("_id").is(id).and("waitingSince").exists(true));
    query.fields().include("waitingSince").include("pauseRequestedAt").include("timeoutAt");
    WorkflowRunEntity run = mongoTemplate.findOne(query, WorkflowRunEntity.class);
    return run != null
        && tryClearStop(run, "waitingSince", run.getWaitingSince(), run.getPauseRequestedAt());
  }

  // Clear one stop flag and extend the deadline by the time that flag alone accounts for: up to
  // the other flag when it is also set and began later (the rest is that flag's to add when it
  // clears), else up to now. Fenced on the flag's stored value, so a concurrent clear loses and
  // the deadline moves exactly once per stop. An unguarded run has no deadline to move.
  private boolean tryClearStop(WorkflowRunEntity run, String flag, Date since, Date otherSince) {
    long stopped =
        (otherSince != null)
            ? Math.max(0, otherSince.getTime() - since.getTime())
            : System.currentTimeMillis() - since.getTime();
    Update update = new Update().unset(flag);
    if (run.getTimeoutAt() != null && stopped > 0) {
      update.set("timeoutAt", new Date(run.getTimeoutAt().getTime() + stopped));
    }
    Query query = Query.query(Criteria.where("_id").is(run.getId()).and(flag).is(since));
    return mongoTemplate.updateFirst(query, update, WorkflowRunEntity.class).getModifiedCount() > 0;
  }

  // Paused and waiting runs are excluded: their clock is stopped, and the deadline moves forward
  // by the time stopped when they resume, so neither is reaped on resume for time it did not
  // spend executing.
  public List<WorkflowRunEntity> findTimedOut(Date now, int limit) {
    Query query =
        Query.query(
                Criteria.where("timeoutAt")
                    .lte(now)
                    .and("phase")
                    .is(RunPhase.running)
                    .and("pauseRequestedAt")
                    .exists(false)
                    .and("waitingSince")
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "timeoutAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, WorkflowRunEntity.class);
  }

  public List<WorkflowRunEntity> findRunningStartedBefore(
      Date startedBefore, int limit, SweepCursor cursor) {
    Criteria candidates =
        Criteria.where("phase")
            .is(RunPhase.running)
            .and("startTime")
            .lte(startedBefore)
            .and("pauseRequestedAt")
            .exists(false);
    return cursor.read(
        candidates,
        limit,
        query -> {
          query.fields().include("_id").include("startTime");
          return mongoTemplate.find(query, WorkflowRunEntity.class);
        },
        WorkflowRunEntity::getStartTime,
        WorkflowRunEntity::getId);
  }

  // The runs among the given ones whose pause is requested - one read for a page of runs.
  public Set<String> pausedAmong(Collection<String> ids) {
    return idsMatching(Criteria.where("_id").in(ids).and("pauseRequestedAt").exists(true));
  }

  // The runs among the given ones still in flight - one read for a page of runs.
  public Set<String> inFlightAmong(Collection<String> ids) {
    return idsMatching(
        Criteria.where("_id")
            .in(ids)
            .and("phase")
            .in(RunPhase.pending, RunPhase.queued, RunPhase.running));
  }

  private Set<String> idsMatching(Criteria criteria) {
    Query query = Query.query(criteria).maxTimeMsec(5000);
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRunEntity.class).stream()
        .map(WorkflowRunEntity::getId)
        .collect(Collectors.toSet());
  }

  // The revisions among the given refs that exist, read by id only.
  public Set<String> existingRevisions(Collection<String> revisionRefs) {
    Query query = Query.query(Criteria.where("_id").in(revisionRefs)).maxTimeMsec(5000);
    query.fields().include("_id");
    return mongoTemplate.find(query, WorkflowRevisionEntity.class).stream()
        .map(WorkflowRevisionEntity::getId)
        .collect(Collectors.toSet());
  }

  // Return the page of in-flight WorkflowRuns, oldest first - the orphan backstop checks each
  // one's revision ref resolves; not itself an indexed predicate, so the page stays narrow and
  // any miss is caught again on the next tick.
  // The distinct revision refs of every in-flight run - one round trip, no documents hydrated -
  // so the missing-revision reaper can ask "which of these no longer exist?" instead of paging
  // in-flight runs it would mostly skip (a first page of healthy runs starved everything behind it).
  public List<String> findInFlightRevisionRefs() {
    Query query =
        Query.query(Criteria.where("phase").in(RunPhase.pending, RunPhase.queued, RunPhase.running))
            .maxTimeMsec(5000);
    return mongoTemplate.findDistinct(
        query, "workflowRevisionRef", WorkflowRunEntity.class, String.class);
  }

  public List<WorkflowRunEntity> findInFlightWithRevisionIn(
      Collection<String> revisionRefs, int limit) {
    Query query =
        Query.query(
                Criteria.where("phase")
                    .in(RunPhase.pending, RunPhase.queued, RunPhase.running)
                    .and("workflowRevisionRef")
                    .in(revisionRefs))
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

  // Keyed by name: a second setwfproperty for the same key updates the value rather than
  // appending a duplicate (issue #241), still without a read-modify-write.
  public void appendResult(String id, RunResult result) {
    ResultUtil.upsertResultByName(mongoTemplate, id, WorkflowRunEntity.class, result);
  }

  // Timeout Compare-And-Set: a running run reads timed out at once and stays in the running phase
  // until timeoutWorkflow completes it, so a completion that is lost - a crash, an executor shut
  // down - is driven again by the timeout sweep. It publishes nothing: the completion's transition
  // is the one event a timeout sends. Returns the pre-image, or null when the run is not running.
  public WorkflowRunEntity tryMarkTimedOut(String id) {
    return mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running)),
        new Update().set("status", RunStatus.timedout),
        FindAndModifyOptions.options().returnNew(false),
        WorkflowRunEntity.class);
  }

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches and publish the transition. Returns the pre-image, or null when another caller won, so
  // the caller does nothing. A null target status or phase is the pre-image's, unchanged.
  private WorkflowRunEntity cas(Query query, Update update, RunStatus toStatus, RunPhase toPhase) {
    WorkflowRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
    if (preImage != null) {
      publish(
          preImage,
          (toStatus != null) ? toStatus : preImage.getStatus(),
          (toPhase != null) ? toPhase : preImage.getPhase());
    }
    return preImage;
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
