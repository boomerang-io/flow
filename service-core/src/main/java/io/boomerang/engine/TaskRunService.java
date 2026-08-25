package io.boomerang.engine;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.engine.model.TaskRunTransition;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.ResultUtil;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/*
 * Handles CRUD of TaskRuns
 */
@Service
public class TaskRunService {
  private static final Logger LOGGER = LogManager.getLogger();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Value("${flow.engine.task.results.max-bytes:4096}")
  private int resultsMaxBytes;

  // How far back the terminal termination page looks. See findClaimableForTermination.
  private static final long TERMINATION_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1000;

  private final TaskExecutionService taskExecutionService;
  private final LogClient logClient;
  private final TaskRunRepository taskRunRepository;
  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;

  public TaskRunService(
      @Lazy TaskExecutionService taskExecutionService,
      @Lazy LogClient logClient,
      TaskRunRepository taskRunRepository,
      MongoTemplate mongoTemplate,
      ApplicationEventPublisher eventPublisher) {
    this.taskExecutionService = taskExecutionService;
    this.logClient = logClient;
    this.taskRunRepository = taskRunRepository;
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
  }

  // Return the page of TaskRuns eligible for claiming by an executor of the given types: ready,
  // pending, unclaimed, with any retry backoff elapsed, oldest first.
  public List<TaskRunEntity> findClaimable(List<TaskType> types, int limit) {
    Criteria criteria =
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
                Criteria.where("retry.after").lte(new Date()));
    Query query =
        Query.query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaim re-reads and transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Return the page of TaskRuns whose executor-side work (a Tekton TaskRun and its pod) is still
  // out there and has to be terminated. A surviving claim IS the outstanding-work marker - the run
  // was handed to an executor, so it provisioned something - which mirrors findClaimableForTeardown
  // on WorkflowRunEntity, where the surviving workspaces play exactly that role. Two shapes carry
  // that residue, and each is a separate indexed query rather than one $or, because an $or forces
  // a blocking SORT and drags the whole hot ready/pending bucket through the claim.by filter:
  //
  //   1. TERMINAL - completed + cancelled|timedout. The node is finished; nothing follows.
  //   2. PARKED FOR RETRY - waiting + pending (see tryRequeue). The node is mid-retry: the attempt
  //      that timed out left a pod behind, and the SAME TaskRun runs again once that pod is gone
  //      and the backoff elapses.
  //
  // A run that was never claimed provisioned nothing and is deliberately excluded from both.
  public List<TaskRunEntity> findClaimableForTermination(List<TaskType> types, int limit) {
    List<TaskRunEntity> candidates =
        new ArrayList<>(
            findOwnedResidue(
                types,
                limit,
                Criteria.where("phase")
                    .is(RunPhase.completed)
                    .and("status")
                    .in(RunStatus.cancelled, RunStatus.timedout)
                    // Bound the terminal page to recent history: without it every poll walks the
                    // whole completed cancelled/timedout bucket, which only ever grows, to apply
                    // the claim.by filter. creationDate is the index's last key behind three
                    // equalities, so this is a real index bound and not a residual filter. A pod
                    // older than the window outlived any plausible engine outage and is gone with
                    // its cluster.
                    .and("creationDate")
                    .gte(new Date(System.currentTimeMillis() - TERMINATION_LOOKBACK_MILLIS))));
    candidates.addAll(
        findOwnedResidue(
            types,
            limit,
            Criteria.where("phase").is(RunPhase.pending).and("status").is(RunStatus.waiting)));
    return candidates;
  }

  // One indexed termination page: the given status/phase shape, restricted to the agent's task
  // types and to runs a dispatcher still owns, oldest first.
  private List<TaskRunEntity> findOwnedResidue(List<TaskType> types, int limit, Criteria shape) {
    Query query =
        Query.query(shape.and("type").in(types).and("claim.by").exists(true))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaimForTermination re-reads and transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Termination Compare-And-Set: re-checks one of the two owned-residue shapes and RELEASES the
  // claim (by/at/leaseExpiresAt - claim.seq is never cleared), so exactly one polling agent is
  // told to terminate the executor-side work and the run drops out of the termination page for
  // good. Releasing is the inverse of WorkflowRunStateHelper.tryStart clearing the dispatch claim to
  // free the teardown claimable, and it is what stops the v4 defect of redelivering terminal runs
  // to every agent on every poll. Returns the pre-image, patched to the wire shape the dispatcher
  // acts on, or null when another agent won.
  public TaskRunEntity tryClaimForTermination(String id, String claimedBy) {
    TaskRunEntity preImage = tryClaimTerminalResidue(id);
    if (preImage == null) {
      preImage = tryClaimParkedResidue(id);
    }
    if (preImage != null) {
      LOGGER.debug(
          "[{}] Termination of the {}/{} TaskRun claimed by dispatcher {} (previous owner {}).",
          id,
          preImage.getStatus(),
          preImage.getPhase(),
          claimedBy,
          preImage.getClaim() != null ? preImage.getClaim().getBy() : null);
    }
    return preImage;
  }

  // Shape 1, the finished node: status and phase are untouched - the agent's handler keys off
  // completed + cancelled/timedout, and a terminal status is never rewritten.
  private TaskRunEntity tryClaimTerminalResidue(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.completed)
                .and("status")
                .in(RunStatus.cancelled, RunStatus.timedout)
                .and("claim.by")
                .exists(true));
    Update update = new Update().unset("claim.by").unset("claim.at").unset("claim.leaseExpiresAt");
    TaskRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), preImage.getPhase());
    }
    return preImage;
  }

  // Shape 2, the node parked mid-retry: releasing the claim and re-arming the node are the SAME
  // write, so a task can never be re-claimed for execution while its previous pod is still alive.
  // retry.after (written by tryRequeue) still gates findClaimable, so the backoff is served after
  // the pod is gone. The returned pre-image is patched to the retired attempt's record -
  // completed + timedout - because that is what this dispatch instructs the agent to terminate,
  // and it is the shape its terminate handler keys off; the stored TaskRun stays non-terminal so
  // the DAG never sees the node finish.
  private TaskRunEntity tryClaimParkedResidue(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.pending)
                .and("status")
                .is(RunStatus.waiting)
                .and("claim.by")
                .exists(true));
    Update update =
        new Update()
            .set("status", RunStatus.ready)
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt");
    TaskRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, RunPhase.pending);
      preImage.setStatus(RunStatus.timedout);
      preImage.setPhase(RunPhase.completed);
    }
    return preImage;
  }

  // Claim Compare-And-Set: re-checks full eligibility between page and claim; racing claimants
  // cannot both win. Bakes the task's real deadline (claimedAt + effectiveTimeout + grace) in the
  // same write, computed once here from the pre-claim document - the same RunTimeouts helper
  // tryStartExecution uses, so the two cannot drift. A dispatcher that dies before reporting the
  // start is still caught by the same timeout sweep that reaps a stalled execution.
  // Returns the pre-image, or null when another claimant won.
  public TaskRunEntity tryClaim(String id, String claimedBy) {
    Date now = new Date();
    TaskRunEntity current = taskRunRepository.findById(id).orElse(null);
    if (current == null) {
      return null;
    }
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
    Date timeoutAt = RunTimeouts.deadline(now, current.getTimeout());
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", now)
            .inc("claim.seq", 1)
            .unset("retry.after");
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, preImage.getStatus(), RunPhase.queued);
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setClaim(claimApplied(preImage.getClaim(), claimedBy, now));
      preImage.setTimeoutAt(timeoutAt);
    }
    return preImage;
  }

  // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params
  // AND the resolved spec ($(params.x) substituted into script/command/arguments/envs) in the
  // same guarded write. Returns the pre-image, or null when already admitted.
  public TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams, TaskRunSpec resolvedSpec) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.notstarted)
                .and("phase")
                .is(RunPhase.pending));
    Update update =
        new Update()
            .set("status", RunStatus.ready)
            .set("params", resolvedParams)
            .set("spec", resolvedSpec);
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.ready, preImage.getPhase());
    }
    return preImage;
  }

  // Invalidation Compare-And-Set: a notstarted/pending TaskRun becomes invalid with the given
  // message, so end() can complete it and the run fails with guidance. Mirrors trySkip; used when
  // admission-time validation (the resolved-params payload cap) rejects the task BEFORE tryAdmit
  // would make it claimable. Returns the pre-image, or null when another caller already
  // admitted/started the TaskRun - the loser performs no side effects.
  public TaskRunEntity tryInvalidate(String id, String statusMessage) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("status")
                .is(RunStatus.notstarted)
                .and("phase")
                .is(RunPhase.pending));
    Update update =
        new Update().set("status", RunStatus.invalid).set("statusMessage", statusMessage);
    TaskRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.invalid, preImage.getPhase());
    }
    return preImage;
  }

  // Skip Compare-And-Set: a still-pending TaskRun becomes skipped, so end() can complete it.
  // Returns the pre-image, or null when another caller already admitted/started the TaskRun -
  // the loser must then perform no side effects, end() included.
  //
  // Why `skipped` is accepted alongside `notstarted`: the skip is two writes (this CAS, then
  // end()). A caller that dies in between leaves the TaskRun at status=skipped/phase=pending,
  // and the graph advance re-drives it through queue() (TaskExecutionService:1042,
  // WorkflowWatcher:305). Matching only `notstarted` would fail that re-drive's CAS, so end()
  // would never be called and the run would stall. `ready`/`running` are still excluded, which
  // is the whole point of the guard.
  public TaskRunEntity trySkip(String id) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.pending)
                .and("status")
                .in(RunStatus.notstarted, RunStatus.skipped));
    Update update = new Update().set("status", RunStatus.skipped);
    TaskRunEntity preImage = findAndModifyPreImage(query, update);
    if (preImage != null) {
      publish(preImage, RunStatus.skipped, preImage.getPhase());
    }
    return preImage;
  }

  // Field-scoped event delivery: records the delivered status annotation, marks the TaskRun
  // pre-approved and appends the event's results - and touches nothing else. Not a
  // Compare-And-Set: an inbound event is applied to whatever state the TaskRun is in, exactly as
  // before. What changed is the WRITE: the caller used to mutate the TaskRun it had read from a
  // findByWorkflowRunRef page and save the whole document back, so any claim/phase/status/
  // timeoutAt a concurrent Compare-And-Set had committed in between was silently rolled back.
  //
  // The annotation key is written ESCAPED - "boomerang#io/status", not "boomerang.io/status".
  // MongoConfiguration.setMapKeyDotReplacement("#") escapes dots in map keys when the converter
  // writes a whole Map, and unescapes them on read, but it does NOT rewrite the field paths of an
  // Update: an unescaped "annotations.boomerang.io/status" would be read by Mongo as a path and
  // create a nested annotations -> boomerang -> "io/status" document that
  // TaskExecutionService.processWaitForEventTask would never find.
  //
  // Results are appended with $push/$each, NOT $addToSet: a redelivered event appends its results
  // a second time today, which is inherited behaviour deliberately left alone - see
  // EventDeliveryIdempotencyTest.
  public void applyEventDelivery(String id, RunStatus status, List<RunResult> results) {
    Update update =
        new Update().set("annotations.boomerang#io/status", status).set("preApproved", true);
    if (results != null && !results.isEmpty()) {
      update.push("results").each(results.toArray());
    }
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)), update, TaskRunEntity.class);
  }

  // Execution-entry Compare-And-Set: ready + pending/queued becomes running with the given start
  // time, baking timeoutAt from the given budget. Returns the document with the transition
  // applied, or null on a duplicate dispatch.
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
    Date timeoutAt = RunTimeouts.deadline(startTime, timeoutMinutes);
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    TaskRunEntity preImage =
        findAndModifyPreImage(query, update);
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

  // Completion Compare-And-Set: any non-completed phase becomes completed; status/statusMessage
  // set only when provided; claimant identity enforced as fencing when provided. Returns the
  // pre-image, or null when already completed - a terminal status is never overwritten.
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
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, status.orElse(preImage.getStatus()), RunPhase.completed);
    }
    return preImage;
  }

  // Return the page of TaskRuns whose deadline has passed: timeoutAt due, phase queued/running.
  public List<TaskRunEntity> findReapable(Date now, int limit) {
    Criteria criteria =
        Criteria.where("timeoutAt").lte(now).and("phase").in(RunPhase.queued, RunPhase.running);
    // In-flight tasks reap on their absolute deadline whether or not the run is paused; pause
    // only gates admission of new tasks, it does not extend a running task's deadline.
    Query query =
        Query.query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "timeoutAt"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Return the page of currently-claimed TaskRuns, oldest claim first - the orphan backstop
  // checks each against dispatcher liveness directly, since a claimant that has disappeared is
  // invisible to the deadline-driven timeout reap until (or unless) its deadline arrives.
  public List<TaskRunEntity> findClaimed(int limit) {
    Criteria criteria =
        Criteria.where("phase").in(RunPhase.queued, RunPhase.running).and("claim.by").exists(true);
    Query query =
        Query.query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "claim.at"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // A WorkflowRun's still-open TaskRuns fetched directly by ref, bypassing the DAG revision walk
  // - used only when the revision itself cannot be resolved.
  public List<TaskRunEntity> findNonTerminalByWorkflowRunRef(String workflowRunRef) {
    Query query =
        Query.query(
                Criteria.where("workflowRunRef").is(workflowRunRef).and("phase").ne(RunPhase.completed))
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Timeout Compare-And-Set: a queued/running TaskRun past its deadline gets status timedout and
  // the message in the same atomic write, fenced on the observed claim seq. Returns the
  // pre-image, or null when fenced out or already transitioned.
  public TaskRunEntity tryTimeout(String id, Long observedClaimSeq, String statusMessage) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and("phase")
            .in(RunPhase.queued, RunPhase.running)
            .and("timeoutAt")
            .lte(new Date());
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set("status", RunStatus.timedout)
            .set("statusMessage", statusMessage)
            .unset("timeoutAt");
    TaskRunEntity preImage =
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  // Abandon Compare-And-Set: a queued/running TaskRun gets the same terminal treatment as a
  // deadline reap (status timedout, deadline cleared) without requiring timeoutAt to have
  // elapsed - for a claimant confirmed gone ahead of (or absent) its own deadline. Fenced on the
  // observed claim seq. Returns the pre-image, or null when fenced out or already transitioned.
  public TaskRunEntity tryAbandon(String id, Long observedClaimSeq, String statusMessage) {
    Criteria criteria =
        Criteria.where("_id").is(id).and("phase").in(RunPhase.queued, RunPhase.running);
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set("status", RunStatus.timedout)
            .set("statusMessage", statusMessage)
            .unset("timeoutAt");
    TaskRunEntity preImage =
        findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, RunStatus.timedout, preImage.getPhase());
    }
    return preImage;
  }

  // Requeue Compare-And-Set: writes the retry block, drops the baked deadline and parks the SAME
  // TaskRun for another attempt - Tekton's model, one record per node with an attempt counter, not
  // a second TaskRun per attempt (which the node_uniqueness index forbids anyway). Fenced on the
  // observed claim seq. Returns the pre-image, or null when fenced/already gone.
  //
  // Two landing states, because the attempt that timed out may have left a pod behind:
  //
  //   CLAIMED (an executor was handed this attempt) -> waiting/pending with the claim block KEPT.
  //   The surviving claim.by publishes the run on the dispatcher's termination page, and because
  //   findClaimable admits only ready/pending-with-no-claim, the node cannot start attempt N+1
  //   while attempt N's pod is still alive. claim.seq increments: the requeue supersedes that
  //   claimant, so a late report from it is fenced out even though its identity is still on the
  //   record. The run stays NON-terminal throughout, so the DAG never sees the node finish and
  //   the WorkflowRun keeps running (existsInFlightByWorkflowRunRef counts waiting as in flight,
  //   so the stall recovery leaves it alone too).
  //
  //   UNCLAIMED (nothing was ever provisioned) -> straight back to ready/pending, as before. There
  //   is no pod to terminate, so parking it would strand a node no agent would ever release.
  //
  // Either way retry.after gates re-admission, so the backoff is served in full.
  public TaskRunEntity tryRequeue(String id, Long observedClaimSeq, Date retryAfter, int retryCount) {
    TaskRunEntity preImage =
        tryRequeueWith(id, observedClaimSeq, true, RunStatus.waiting, retryAfter, retryCount);
    if (preImage == null) {
      preImage =
          tryRequeueWith(id, observedClaimSeq, false, RunStatus.ready, retryAfter, retryCount);
    }
    return preImage;
  }

  // One requeue landing, guarded on whether the attempt was claimed. The two guards are mutually
  // exclusive, so trying the claimed shape first and falling back is race-free: whichever
  // Compare-And-Set matches is the only one that can, and a concurrent winner moves the run out of
  // the queued/running phase both guards require.
  private TaskRunEntity tryRequeueWith(
      String id,
      Long observedClaimSeq,
      boolean claimed,
      RunStatus toStatus,
      Date retryAfter,
      int retryCount) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and("phase")
            .in(RunPhase.queued, RunPhase.running)
            .and("claim.by")
            .exists(claimed);
    fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set("status", toStatus)
            .set("phase", RunPhase.pending)
            .set("retry.after", retryAfter)
            .set("retry.count", retryCount)
            .unset("claim.leaseExpiresAt")
            .unset("timeoutAt");
    if (claimed) {
      // The claim block survives as the pod-still-out-there marker; the seq bump supersedes the
      // claimant that timed out.
      update.inc("claim.seq", 1);
    } else {
      update.unset("claim.by").unset("claim.at");
    }
    TaskRunEntity preImage = findAndModifyPreImage(Query.query(criteria), update);
    if (preImage != null) {
      publish(preImage, toStatus, RunPhase.pending);
    }
    return preImage;
  }

  // Whether the run has any in-flight TaskRun: claimed/executing (phase queued/running) or
  // awaiting an external actor (status ready/waiting). Zero in-flight on an active run means the
  // graph advance was lost and must be recovered.
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

  // Return the page of waiting TaskRuns whose wait time has elapsed (sleep/lock parking).
  public List<TaskRunEntity> findWaitingDue(Date now, int limit) {
    Query query =
        Query.query(Criteria.where("status").is(RunStatus.waiting).and("waitUntil").lte(now))
            .with(Sort.by(Sort.Direction.ASC, "waitUntil"))
            .limit(limit)
            .maxTimeMsec(5000);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Park a running TaskRun as a durable wait (sleep, or an acquirelock backoff): status ->
  // waiting and the wake time, as a targeted update so the sweep-control fields written by other
  // Compare-And-Set paths (claim, timeoutAt, retry) are never overwritten by a stale entity save.
  // Fenced on the run still being in the running phase. Returns whether the park was applied.
  public boolean tryPark(String id, Date waitUntil) {
    Query query =
        Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running));
    Update update = new Update().set("status", RunStatus.waiting).set("waitUntil", waitUntil);
    return mongoTemplate.updateFirst(query, update, TaskRunEntity.class).getModifiedCount() > 0;
  }

  // Claim a due waiting TaskRun for resume: clears waitUntil so a second instance's sweep skips
  // it. Returns whether this caller won (the winner resumes).
  public boolean tryStartWaitingResume(String id) {
    Query query =
        Query.query(
            Criteria.where("_id").is(id).and("status").is(RunStatus.waiting).and("waitUntil").lte(new Date()));
    return mongoTemplate
            .updateFirst(query, new Update().unset("waitUntil"), TaskRunEntity.class)
            .getModifiedCount()
        > 0;
  }

  // The in-memory view of the claim block after a winning claim Compare-And-Set (by/at set, seq
  // incremented from the pre-image), so the returned entity mirrors the stored post-claim state.
  static RunClaim claimApplied(RunClaim preClaim, String claimedBy, Date at) {
    RunClaim claim = new RunClaim();
    claim.setBy(claimedBy);
    claim.setAt(at);
    claim.setSeq((preClaim == null || preClaim.getSeq() == null) ? 1L : preClaim.getSeq() + 1);
    return claim;
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

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches, returning the pre-image (null = another caller won, so the caller does nothing).
  private TaskRunEntity findAndModifyPreImage(Query query, Update update) {
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
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

  public ResponseEntity<TaskRun> get(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRun taskRun = new TaskRun(optTaskRunEntity.get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> start(
      String taskRunId, Optional<TaskRunStartRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          taskRunEntity.setParams(
              ParameterUtil.addUniqueParams(
                  taskRunEntity.getParams(), optRunRequest.get().getParams()));
          if (!Objects.isNull(optRunRequest.get().getTimeout())
              && optRunRequest.get().getTimeout() != 0) {
            taskRunEntity.setTimeout(optRunRequest.get().getTimeout());
          }
          // Persist the request merge for the handler to re-read. The handler only acts on a
          // pending or queued TaskRun, so any other phase keeps its record untouched.
          if (RunPhase.pending.equals(taskRunEntity.getPhase())
              || RunPhase.queued.equals(taskRunEntity.getPhase())) {
            taskRunRepository.save(taskRunEntity);
          }
        }
        taskExecutionService.start(taskRunId);
        // Retrieve the refreshed state
        TaskRun taskRun = new TaskRun(taskRunRepository.findById(taskRunId).get());
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public ResponseEntity<TaskRun> end(String taskRunId, Optional<TaskRunEndRequest> optRunRequest) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);
      if (optTaskRunEntity.isPresent()) {
        TaskRunEntity taskRunEntity = optTaskRunEntity.get();
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          if (optRunRequest.get().getStatusMessage() != null
              && !optRunRequest.get().getStatusMessage().isEmpty()) {
            taskRunEntity.setStatusMessage(optRunRequest.get().getStatusMessage());
          }
          // addUniqueResults mutates the entity's list, so the pre-merge state must be copied for
          // the payload-cap rollback below.
          List<RunResult> priorResults =
              taskRunEntity.getResults() != null
                  ? new ArrayList<>(taskRunEntity.getResults())
                  : new ArrayList<>();
          taskRunEntity.setResults(
              ResultUtil.addUniqueResults(
                  taskRunEntity.getResults(), optRunRequest.get().getResults()));
          if (optRunRequest.get().getStatus() == null) {
            taskRunEntity.setStatus(RunStatus.succeeded);
          } else if (!(RunStatus.failed.equals(optRunRequest.get().getStatus())
              || RunStatus.succeeded.equals(optRunRequest.get().getStatus())
              || RunStatus.invalid.equals(optRunRequest.get().getStatus()))) {
            throw new BoomerangException(BoomerangError.TASKRUN_INVALID_END_STATUS);
          } else {
            taskRunEntity.setStatus(optRunRequest.get().getStatus());
          }
          // Engine-enforced results cap, identical on every executor (4096 bytes is the portable
          // Kubernetes termination-message ceiling). Oversize fails the task and keeps the
          // pre-merge results rather than persisting a payload every downstream reader re-reads.
          byte[] resultBytes = OBJECT_MAPPER.writeValueAsBytes(taskRunEntity.getResults());
          if (resultBytes.length > resultsMaxBytes) {
            taskRunEntity.setResults(priorResults);
            taskRunEntity.setStatus(RunStatus.failed);
            taskRunEntity.setStatusMessage(
                "RESULTS_TOO_LARGE - results total "
                    + resultBytes.length
                    + " bytes, exceeding the "
                    + resultsMaxBytes
                    + " byte cap. Pass large outputs by reference (workspace path or URI).");
          }
        }
        // Persist the request merge for the handler to re-read. The handler ignores an end
        // request for a completed TaskRun, so a terminal record stays untouched.
        if (!RunPhase.completed.equals(taskRunEntity.getPhase())) {
          taskRunRepository.save(taskRunEntity);
        }
        taskExecutionService.end(taskRunId);
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  public StreamingResponseBody streamLog(String taskRunId) {
    if (!Objects.isNull(taskRunId) && !taskRunId.isBlank()) {
      LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
      Optional<TaskRunEntity> optTaskRunEntity = taskRunRepository.findById(taskRunId);

      // TODO sanitise and remove secure parameters
      //    List<String> removeList = buildRemovalList(taskId, taskExecution, activity);
      //    LOGGER.debug("Removal List Count: {} ", removeList.size());
      if (optTaskRunEntity.isPresent()) {
        return logClient.streamLog(
            optTaskRunEntity.get().getWorkflowRef(),
            optTaskRunEntity.get().getWorkflowRunRef(),
            optTaskRunEntity.get().getId());
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }
}
