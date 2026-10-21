package io.boomerang.engine;
import io.boomerang.common.entity.ArtifactEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.TaskRunStartRequest;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.util.Backoff;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.config.SecretValueMongoListener;
import io.boomerang.engine.model.ClaimFilter;
import io.boomerang.engine.model.TaskOutcome;
import io.boomerang.common.model.TaskRunTransition;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.workflow.ArtifactService;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.ResultUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.slf4j.helpers.MessageFormatter;

/*
 * Handles CRUD of TaskRuns
 */
@Service
public class TaskRunService {
  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.engine.task.results.max-bytes:1048576}")
  private int resultsMaxBytes;

  // The most bytes of a finished log a dispatcher may store for a task; see storeLog.
  @Value("${flow.engine.task.log.max-bytes:10485760}")
  private int logMaxBytes;

  // Lease length set at claim and renewed by each heartbeat - see the property's own comment in
  // application.properties for the floor and why it must stay there.
  @Value("${flow.dispatcher.lease-ms:90000}")
  private long leaseMillis;

  // How far back the terminal termination page looks. See findClaimableForTermination.
  private static final long TERMINATION_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1000;

  private final TaskExecutionService taskExecutionService;
  private final LogClient logClient;
  private final TaskRunRepository taskRunRepository;
  private final MongoTemplate mongoTemplate;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  // The upload task's completion and the stored logs live with the artifacts.
  private final ArtifactService artifactService;
  // An Update never reaches the save hook, so the admission write seals secret values itself.
  private final SecretValueMongoListener secretValues;

  public TaskRunService(
      @Lazy TaskExecutionService taskExecutionService,
      @Lazy LogClient logClient,
      TaskRunRepository taskRunRepository,
      MongoTemplate mongoTemplate,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      ArtifactService artifactService,
      SecretValueMongoListener secretValues) {
    this.taskExecutionService = taskExecutionService;
    this.logClient = logClient;
    this.taskRunRepository = taskRunRepository;
    this.mongoTemplate = mongoTemplate;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.artifactService = artifactService;
    this.secretValues = secretValues;
  }

  // Return the page of TaskRuns eligible for claiming by an executor of the given types: ready,
  // pending, unclaimed, with any retry backoff elapsed, oldest first.
  public List<TaskRunEntity> findClaimable(List<TaskType> types, int limit) {
    return findClaimable(ClaimFilter.of(types), limit);
  }

  // The same page, narrowed to a dispatcher poll's task and workflow filters.
  public List<TaskRunEntity> findClaimable(ClaimFilter filter, int limit) {
    Criteria criteria =
        narrowed(
            Criteria.where("status")
                .is(RunStatus.ready)
                .and("phase")
                .is(RunPhase.pending)
                .and("type")
                .in(filter.types())
                .and("claim.by")
                .exists(false),
            filter);
    criteria.orOperator(
        Criteria.where("retry.after").exists(false),
        Criteria.where("retry.after").lte(new Date()));
    Query query =
        Query.query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page carries the id and the timeout the claim stamps its deadline from - nothing
    // changes a TaskRun's timeout between its creation and its claim - and tryClaim transitions by
    // id.
    query.fields().include("_id").include("timeout");
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
    return findClaimableForTermination(ClaimFilter.of(types), limit);
  }

  // The same pages, narrowed to a dispatcher poll's task and workflow filters, so a filtered
  // dispatcher is told to terminate exactly the work it could have claimed.
  public List<TaskRunEntity> findClaimableForTermination(ClaimFilter filter, int limit) {
    List<TaskRunEntity> candidates =
        new ArrayList<>(
            findOwnedResidue(
                filter,
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
            filter,
            limit,
            Criteria.where("phase").is(RunPhase.pending).and("status").is(RunStatus.waiting)));
    return candidates;
  }

  // One indexed termination page: the given status/phase shape, restricted to the agent's task
  // types and filters and to runs a dispatcher still owns, oldest first.
  private List<TaskRunEntity> findOwnedResidue(ClaimFilter filter, int limit, Criteria shape) {
    Query query =
        Query.query(
                narrowed(shape.and("type").in(filter.types()).and("claim.by").exists(true), filter))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    // The claim page only needs the id - tryClaimForTermination re-reads and transitions by id.
    query.fields().include("_id");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // A poll's task and workflow filters apply after the index has picked the ready (or owned) runs
  // of the right types, so the oldest-first order is kept and the unfiltered page loses nothing.
  // Criteria.and builds on a copy of the chain, so only the returned criteria carries the filter.
  private static Criteria narrowed(Criteria criteria, ClaimFilter filter) {
    Criteria narrowed = criteria;
    if (filter.taskRefs() != null) {
      narrowed = narrowed.and("taskRef").in(filter.taskRefs());
    }
    if (filter.workflowRefs() != null) {
      narrowed = narrowed.and("workflowRef").in(filter.workflowRefs());
    }
    return narrowed;
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
    return cas(query, update, null, null);
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
    TaskRunEntity preImage = cas(query, update, RunStatus.ready, RunPhase.pending);
    if (preImage != null) {
      preImage.setStatus(RunStatus.timedout);
      preImage.setPhase(RunPhase.completed);
    }
    return preImage;
  }

  // Claim Compare-And-Set: re-checks full eligibility between page and claim; racing claimants
  // cannot both win. Bakes the task's real deadline (claimedAt + effectiveTimeout + grace) in the
  // same write, computed once here from the pre-claim document - the same RunTimeouts helper
  // tryStartExecution uses, so the two cannot drift. The same write sets the first lease, so a
  // claim its dispatcher never heartbeats - one lost in a crash and restart under the same
  // dispatcher id - is reaped once the lease lapses.
  // Returns the pre-image, or null when another claimant won.
  public TaskRunEntity tryClaim(String id, String claimedBy) {
    Query timeoutOnly = Query.query(Criteria.where("_id").is(id));
    timeoutOnly.fields().include("timeout");
    TaskRunEntity current = mongoTemplate.findOne(timeoutOnly, TaskRunEntity.class);
    return (current != null) ? tryClaim(id, current.getTimeout(), claimedBy) : null;
  }

  // The claim, given the timeout the claim page read with the id.
  public TaskRunEntity tryClaim(String id, Long timeout, String claimedBy) {
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
    Date timeoutAt = RunTimeouts.deadline(now, timeout);
    Date leaseExpiresAt = new Date(now.getTime() + leaseMillis);
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", now)
            .set("claim.leaseExpiresAt", leaseExpiresAt)
            .inc("claim.seq", 1)
            .unset("retry.after");
    if (timeoutAt != null) {
      update.set("timeoutAt", timeoutAt);
    }
    TaskRunEntity preImage = cas(query, update, null, RunPhase.queued);
    if (preImage != null) {
      // Return the pre-image with the claim transition applied - the caller ships this to the
      // agent, so it must reflect the post-claim phase and owner, not the stale pre-claim values.
      preImage.setPhase(RunPhase.queued);
      preImage.setClaim(claimApplied(preImage.getClaim(), claimedBy, now));
      preImage.getClaim().setLeaseExpiresAt(leaseExpiresAt);
      preImage.setTimeoutAt(timeoutAt);
    }
    return preImage;
  }

  // Admission Compare-And-Set: notstarted/pending becomes ready, persisting the resolved params -
  // their values frozen here, secret values encrypted - in the same guarded write. The spec is not
  // written: it keeps its $(...) references, filled in when a dispatcher claims the task
  // (ParameterManager.fillSpec). Returns the pre-image, or null when already admitted.
  public TaskRunEntity tryAdmit(String id, List<RunParam> resolvedParams) {
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
            .set("params", secretValues.sealRunParams(resolvedParams));
    return cas(query, update, RunStatus.ready, null);
  }

  // Field-scoped event delivery: records the delivered status as the TaskRun's pre-approved status
  // (succeeded when the event names none) and appends the event's results - and touches nothing
  // else. Not a
  // Compare-And-Set: an inbound event is applied to whatever state the TaskRun is in, exactly as
  // before. What changed is the WRITE: the caller used to mutate the TaskRun it had read from a
  // findByWorkflowRunRef page and save the whole document back, so any claim/phase/status/
  // timeoutAt a concurrent Compare-And-Set had committed in between was silently rolled back.
  //
  // Results are merged by name, the same rule end() applies (ResultUtil.addUniqueResults): a
  // redelivered event with the same keys converges on one element per key instead of appending
  // duplicates - see EventDeliveryIdempotencyTest.
  public void applyEventDelivery(String id, RunStatus status, List<RunResult> results) {
    Update update =
        new Update().set("preApprovedStatus", (status != null) ? status : RunStatus.succeeded);
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)), update, TaskRunEntity.class);
    if (results != null) {
      results.forEach(r -> ResultUtil.upsertResultByName(mongoTemplate, id, TaskRunEntity.class, r));
    }
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
    TaskRunEntity preImage = cas(query, update, RunStatus.running, RunPhase.running);
    if (preImage == null) {
      return null;
    }
    // Return the pre-image with the transition applied - the caller executes on the new state.
    preImage.setStatus(RunStatus.running);
    preImage.setPhase(RunPhase.running);
    preImage.setStartTime(startTime);
    preImage.setTimeoutAt(timeoutAt);
    return preImage;
  }

  // Fan-out Compare-And-Set for a foreach parent: notstarted/pending becomes running with the given
  // start time, recording the resolved item array (null when it failed to resolve) in the same
  // guarded write. The parent never passes through ready, so it is never claimable, and it carries
  // no claim and no timeoutAt, so no lease, orphan or timeout sweep ever reaps it - its items carry
  // the timeouts. Returns the pre-image, or null when another caller already fanned it out.
  public TaskRunEntity tryStartForeach(String id, Date startTime, List<Object> items) {
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
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("startTime", startTime)
            .set("foreach.items", items);
    return cas(query, update, RunStatus.running, RunPhase.running);
  }

  // Return the page of foreach parents in the running phase since before the given time - the
  // candidates for recovering a fan-out or a parent completion that a crash cut short. Matched by
  // phase alone: a parent whose outcome is recorded but whose end was lost keeps a terminal status
  // in the running phase.
  public List<TaskRunEntity> findRunningForeachParents(Date startedBefore, int limit) {
    Query query =
        Query.query(
                Criteria.where("phase")
                    .is(RunPhase.running)
                    .and("foreach")
                    .exists(true)
                    .and("startTime")
                    .lte(startedBefore))
            .with(Sort.by(Sort.Direction.ASC, "startTime"))
            .limit(limit)
            .maxTimeMsec(5000);
    query.fields().include("_id").include("status").include("workflowRunRef");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Whether every item of a foreach parent is completed and all the expected items exist - an
  // exists and a count on the parent's items, so no item document is loaded until the last ends.
  public boolean allItemsCompleted(String parentRef, int expected) {
    Query unfinished =
        Query.query(Criteria.where("parentRef").is(parentRef).and("phase").ne(RunPhase.completed));
    return !mongoTemplate.exists(unfinished, TaskRunEntity.class)
        && mongoTemplate.count(Query.query(Criteria.where("parentRef").is(parentRef)), TaskRunEntity.class)
            >= expected;
  }

  // A foreach parent's items in item order, holding only what the parent's outcome is built from.
  public List<TaskRunEntity> findItemOutcomes(String parentRef) {
    Query query =
        Query.query(Criteria.where("parentRef").is(parentRef))
            .with(Sort.by(Sort.Direction.ASC, "index"));
    query.fields().include("index").include("status").include("results");
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  // Whether a foreach parent has an item in flight, by the same test as
  // existsInFlightByWorkflowRunRef. None in flight on a running parent means its items are all
  // terminal or never queued, and the fan-out must be recovered.
  public boolean existsInFlightItem(String parentRef) {
    Query query =
        Query.query(
                Criteria.where("parentRef")
                    .is(parentRef)
                    .orOperator(
                        Criteria.where("phase").in(RunPhase.queued, RunPhase.running),
                        Criteria.where("status").in(RunStatus.ready, RunStatus.waiting)))
            .maxTimeMsec(5000);
    return mongoTemplate.exists(query, TaskRunEntity.class);
  }

  // Completion Compare-And-Set for a TaskRun that will not run - its run is gone or already
  // completed - with its status and a formatted message, so its end is published like any other.
  public TaskRunEntity tryComplete(
      TaskRunEntity taskRun, RunStatus status, String message, Object... messageArgs) {
    long duration =
        (taskRun.getStartTime() != null)
            ? new Date().getTime() - taskRun.getStartTime().getTime()
            : 0;
    return tryComplete(
        taskRun.getId(),
        Optional.of(status),
        Optional.of(MessageFormatter.arrayFormat(message, messageArgs).getMessage()),
        duration,
        Optional.empty(),
        Optional.empty());
  }

  // Completion Compare-And-Set: any non-completed phase becomes completed; status/statusMessage
  // set only when provided; claimant identity enforced as fencing when provided. The deadline and
  // the lease go with it, so the timeout and lease sweeps' sparse indexes hold only live work.
  // Returns the pre-image, or null when already completed - a terminal status is never overwritten.
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
    Update update = completion(duration);
    status.ifPresent(s -> update.set("status", s));
    statusMessage.ifPresent(m -> update.set("statusMessage", m));
    return cas(Query.query(criteria), update, status.orElse(null), RunPhase.completed);
  }

  // Completion Compare-And-Set carrying the outcome the engine decided, so the TaskRun ends in this
  // one write: from one of the outcome's expected statuses it completes with the outcome's status
  // and, where set, its reason, message, results and decision value. Returns the pre-image, or null
  // when the TaskRun has moved on - completed, timed out, requeued - and the caller does nothing.
  public TaskRunEntity tryComplete(String id, TaskOutcome outcome, long duration) {
    Criteria criteria =
        Criteria.where("_id")
            .is(id)
            .and("phase")
            .in(RunPhase.pending, RunPhase.queued, RunPhase.running)
            .and("status")
            .in(outcome.from());
    Update update = completion(duration).set("status", outcome.status());
    if (outcome.statusReason() != null) {
      update.set("statusReason", outcome.statusReason());
    }
    if (outcome.statusMessage() != null) {
      update.set("statusMessage", outcome.statusMessage());
    }
    if (outcome.results() != null) {
      update.set("results", outcome.results());
    }
    if (outcome.decisionValue() != null) {
      update.set("decisionValue", outcome.decisionValue());
    }
    return cas(Query.query(criteria), update, outcome.status(), RunPhase.completed);
  }

  // What every completion writes: the phase and duration, and the end of the deadline and lease.
  private static Update completion(long duration) {
    return new Update()
        .set("phase", RunPhase.completed)
        .set("duration", duration)
        .unset("timeoutAt")
        .unset("claim.leaseExpiresAt");
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

  // Renew the lease on the ids this dispatcher still owns, one lease length from now, fenced on
  // claim.by so ids the caller does not own are silently skipped.
  public long renewLeases(List<String> ids, String by) {
    Date until = new Date(System.currentTimeMillis() + leaseMillis);
    Query query =
        Query.query(
            Criteria.where("_id")
                .in(ids)
                .and("claim.by")
                .is(by)
                .and("phase")
                .in(RunPhase.queued, RunPhase.running));
    Update update = new Update().set("claim.leaseExpiresAt", until);
    return mongoTemplate.updateMulti(query, update, TaskRunEntity.class).getModifiedCount();
  }

  // Return the page of TaskRuns whose lease has expired - a dispatcher that stopped heartbeating a
  // claim without its registration going stale (reapClaimsFromGoneDispatchers only catches a
  // dispatcher whose registration itself is gone/stale). Oldest lease first.
  public List<TaskRunEntity> findLeaseExpired(int limit) {
    Criteria criteria =
        Criteria.where("claim.leaseExpiresAt")
            .lte(new Date())
            .and("phase")
            .in(RunPhase.queued, RunPhase.running);
    Query query =
        Query.query(criteria)
            .with(Sort.by(Sort.Direction.ASC, "claim.leaseExpiresAt"))
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

  // Abandon Compare-And-Set: a queued/running TaskRun becomes timed out with the message and typed
  // reason in one write, deadline and lease cleared, fenced on the observed claim seq - for a
  // deadline reap (pastDeadline: only once timeoutAt has elapsed) and for a claimant confirmed gone
  // ahead of, or without, its own deadline. Returns the pre-image, or null when fenced out or
  // already transitioned.
  public TaskRunEntity tryAbandon(
      String id,
      Long observedClaimSeq,
      String statusMessage,
      String statusReason,
      boolean pastDeadline) {
    Criteria criteria =
        Criteria.where("_id").is(id).and("phase").in(RunPhase.queued, RunPhase.running);
    if (pastDeadline) {
      criteria = criteria.and("timeoutAt").lte(new Date());
    }
    criteria = fence(criteria, observedClaimSeq);
    Update update =
        new Update()
            .set("status", RunStatus.timedout)
            .set("statusMessage", statusMessage)
            .set("statusReason", statusReason)
            .unset("timeoutAt");
    // The lease stays until the completion lands, so a lost end is reaped and ended again.
    return cas(Query.query(criteria), update, RunStatus.timedout, null);
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
    criteria = fence(criteria, observedClaimSeq);
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
    return cas(Query.query(criteria), update, toStatus, RunPhase.pending);
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

  // Whether the run's in-flight work is all waiting: at least one waiting TaskRun and none
  // executing - admitted (ready), claimed (queued) or running. The run clock stops only then.
  public boolean allInFlightWaiting(String workflowRunRef) {
    Query waiting =
        Query.query(
                Criteria.where("workflowRunRef")
                    .is(workflowRunRef)
                    .and("status")
                    .is(RunStatus.waiting)
                    .and("phase")
                    .ne(RunPhase.completed))
            .maxTimeMsec(5000);
    Query executing =
        Query.query(
                Criteria.where("workflowRunRef")
                    .is(workflowRunRef)
                    .orOperator(
                        Criteria.where("status").is(RunStatus.ready),
                        Criteria.where("phase")
                            .in(RunPhase.queued, RunPhase.running)
                            .and("status")
                            .ne(RunStatus.waiting)))
            .maxTimeMsec(5000);
    return mongoTemplate.exists(waiting, TaskRunEntity.class)
        && !mongoTemplate.exists(executing, TaskRunEntity.class);
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

  // Arm a wait with no wake time - an eventwait, an approval or manual task, or a runworkflow task
  // waiting on its child.
  // status -> waiting as a field-scoped Compare-And-Set fenced on the running phase, leaving
  // waitUntil absent so the watcher's time-based resume never picks the row up: only the arriving
  // event, the child's completion, or the timeout reap ends it. An event delivered between
  // execute()'s entry read and this write lands through applyEventDelivery (preApproved, status
  // annotation, results) and survives; the whole-document save this replaces rolled those fields
  // back. Returns whether the arm was applied.
  public boolean tryArmWait(String id) {
    Query query =
        Query.query(Criteria.where("_id").is(id).and("phase").is(RunPhase.running));
    Update update = new Update().set("status", RunStatus.waiting);
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

  // Return the criteria fenced on the observed claim seq. A null observed seq fences on the run
  // being unclaimed - a claim arriving between page and Compare-And-Set carries a seq and fails
  // the guard. Criteria.and builds on a copy of the chain, so a caller must query with the
  // returned criteria, never the one it passed in.
  private static Criteria fence(Criteria criteria, Long observedClaimSeq) {
    return (observedClaimSeq != null)
        ? criteria.and("claim.seq").is(observedClaimSeq)
        : criteria.and("claim.seq").exists(false);
  }

  // The Compare-And-Set primitive: apply the update only when the query's expected prior state
  // matches and publish the transition. Returns the pre-image, or null when another caller won, so
  // the caller does nothing. A null target status or phase is the pre-image's, unchanged.
  private TaskRunEntity cas(Query query, Update update, RunStatus toStatus, RunPhase toPhase) {
    TaskRunEntity preImage =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
    if (preImage != null) {
      publish(
          preImage,
          (toStatus != null) ? toStatus : preImage.getStatus(),
          (toPhase != null) ? toPhase : preImage.getPhase());
    }
    return preImage;
  }

  private void publish(TaskRunEntity preImage, RunStatus toStatus, RunPhase toPhase) {
    eventPublisher.publishEvent(
        new TaskRunTransition(
            preImage.getId(),
            preImage.getWorkflowRunRef(),
            preImage.getType(),
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
        Optional<String> claimedBy =
            optRunRequest.map(TaskRunStartRequest::getDispatcherRef).filter(StringUtils::hasText);
        rejectSupersededClaimant(taskRunEntity, claimedBy);
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          TaskRunStartRequest request = optRunRequest.get();
          List<String> fields = new ArrayList<>();
          if (request.getLabels() != null && !request.getLabels().isEmpty()) {
            taskRunEntity.getLabels().putAll(request.getLabels());
            fields.add("labels");
          }
          if (request.getAnnotations() != null && !request.getAnnotations().isEmpty()) {
            taskRunEntity.getAnnotations().putAll(request.getAnnotations());
            fields.add("annotations");
          }
          if (request.getParams() != null && !request.getParams().isEmpty()) {
            // Sealed here: a field write never reaches the listener that encrypts on save.
            taskRunEntity.setParams(
                secretValues.sealRunParams(
                    ParameterUtil.addUniqueParams(taskRunEntity.getParams(), request.getParams())));
            fields.add("params");
          }
          if (!Objects.isNull(request.getTimeout()) && request.getTimeout() != 0) {
            taskRunEntity.setTimeout(request.getTimeout());
            fields.add("timeout");
          }
          // Persist the request merge for the handler to re-read, field by field: a whole-document
          // save would write back the phase, status and claim read above, undoing a cancel or a
          // reap that landed meanwhile. Only a TaskRun still waiting to run, and still held by the
          // dispatcher that sent the request, takes it.
          if (!fields.isEmpty()) {
            mongoTemplate.updateFirst(
                heldInPhase(taskRunId, claimedBy, RunPhase.pending, RunPhase.queued),
                fieldsAsSaved(taskRunEntity, fields),
                TaskRunEntity.class);
          }
        }
        taskExecutionService.start(taskRunId, claimedBy, Optional.empty());
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
        // Fence before the merge below: a superseded claimant's result must not be written over
        // the current claimant's record, let alone complete it.
        Optional<String> claimedBy =
            optRunRequest.map(TaskRunEndRequest::getDispatcherRef).filter(StringUtils::hasText);
        rejectSupersededClaimant(taskRunEntity, claimedBy);
        if (optRunRequest.isPresent() && requeuedForRetry(taskRunEntity, optRunRequest.get())) {
          return ResponseEntity.ok(
              new TaskRun(taskRunRepository.findById(taskRunId).orElse(taskRunEntity)));
        }
        // Add values from Run Request
        if (optRunRequest.isPresent()) {
          taskRunEntity.getLabels().putAll(optRunRequest.get().getLabels());
          taskRunEntity.getAnnotations().putAll(optRunRequest.get().getAnnotations());
          if (optRunRequest.get().getStatusMessage() != null
              && !optRunRequest.get().getStatusMessage().isEmpty()) {
            taskRunEntity.setStatusMessage(optRunRequest.get().getStatusMessage());
          }
          if (optRunRequest.get().getStatusReason() != null
              && !optRunRequest.get().getStatusReason().isBlank()) {
            taskRunEntity.setStatusReason(optRunRequest.get().getStatusReason());
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
          // Storage guard on results, so a faulty dispatcher cannot grow a TaskRun toward Mongo's
          // document limit; each executor enforces its own limit before this. Oversize fails the
          // task and keeps the pre-merge results rather than persisting them.
          byte[] resultBytes = objectMapper.writeValueAsBytes(taskRunEntity.getResults());
          if (resultBytes.length > resultsMaxBytes) {
            taskRunEntity.setResults(priorResults);
            taskRunEntity.setStatus(RunStatus.failed);
            taskRunEntity.setStatusMessage(
                "RESULTS_TOO_LARGE - results total "
                    + resultBytes.length
                    + " bytes, exceeding the "
                    + resultsMaxBytes
                    + " byte cap. Pass large outputs by reference (workspace path or URI).");
            taskRunEntity.setStatusReason(StatusReason.RESULTS_TOO_LARGE);
          }
          completeArtifactUpload(taskRunEntity);
          reportArtifactFound(taskRunEntity);
          // Persist the request merge for the handler to re-read, field by field: a whole-document
          // save would write back the phase and claim read above - before the artifact check, which
          // takes time - undoing a cancel or a timeout that completed the TaskRun meanwhile. Only an
          // unfinished TaskRun still held by the dispatcher that sent the request takes it; the
          // handler ignores an end for a completed one.
          List<String> fields =
              new ArrayList<>(List.of("labels", "annotations", "results", "status"));
          if (taskRunEntity.getStatusMessage() != null) {
            fields.add("statusMessage");
          }
          if (taskRunEntity.getStatusReason() != null) {
            fields.add("statusReason");
          }
          mongoTemplate.updateFirst(
              heldInPhase(
                  taskRunId, claimedBy, RunPhase.pending, RunPhase.queued, RunPhase.running),
              fieldsAsSaved(taskRunEntity, fields),
              TaskRunEntity.class);
        }
        taskExecutionService.end(taskRunId, claimedBy, Optional.empty());
        TaskRun taskRun = new TaskRun(taskRunEntity);
        return ResponseEntity.ok(taskRun);
      }
    }
    throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
  }

  /**
   * Requeue a task its dispatcher could not start, while its retry budget lasts, through the same
   * fenced transition a timed-out claimant takes: the cluster refused it on quota, its pod never
   * left Pending, or the runtime or a gateway behind it refused it for rate. Nothing ran, so it is
   * handed back for another attempt on any dispatcher. Return false when the end must go ahead
   * instead: any other reason, a type the engine does not requeue, a spent budget, or a lost race.
   */
  private boolean requeuedForRetry(TaskRunEntity taskRun, TaskRunEndRequest request) {
    if (!RunStatus.failed.equals(request.getStatus())
        || request.getStatusReason() == null
        || !StatusReason.REQUEUEABLE.contains(request.getStatusReason())) {
      return false;
    }
    int attempts = (taskRun.getRetry() != null) ? taskRun.getRetry().getCount() : 0;
    if (!EngineConstants.REQUEUEABLE_TYPES.contains(taskRun.getType())
        || attempts >= EngineConstants.MAX_RETRIES) {
      return false;
    }
    Long observedSeq = (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null;
    if (tryRequeue(taskRun.getId(), observedSeq, Backoff.nextRetryAt(attempts), attempts + 1)
        == null) {
      return false;
    }
    LOGGER.info(
        "[{}] Dispatcher could not start the task ({}: {}). Requeued as attempt {}.",
        taskRun.getId(),
        request.getStatusReason(),
        request.getStatusMessage(),
        attempts + 1);
    return true;
  }

  /*
   * A succeeded upload task is only done once the engine has verified its file; a refusal (too
   * large, storage quota reached, nothing uploaded) ends the task failed with the reason.
   */
  private void completeArtifactUpload(TaskRunEntity taskRunEntity) {
    if (!TaskType.uploadartifact.equals(taskRunEntity.getType())
        || !RunStatus.succeeded.equals(taskRunEntity.getStatus())) {
      return;
    }
    try {
      artifactService.completeUpload(taskRunEntity);
    } catch (BoomerangException e) {
      taskRunEntity.setStatus(RunStatus.failed);
      taskRunEntity.setStatusReason(StatusReason.ARTIFACT_REFUSED);
      taskRunEntity.setStatusMessage(artifactService.refusalMessage(e));
    }
  }

  /*
   * A succeeded download task reports found=true. The engine owns the result: the worker does not
   * write it, and an end that already carries one (an optional download that found nothing ends
   * succeeded with found=false before it is dispatched) stands.
   */
  private void reportArtifactFound(TaskRunEntity taskRunEntity) {
    if (!TaskType.downloadartifact.equals(taskRunEntity.getType())
        || !RunStatus.succeeded.equals(taskRunEntity.getStatus())) {
      return;
    }
    List<RunResult> results =
        taskRunEntity.getResults() != null ? taskRunEntity.getResults() : new ArrayList<>();
    if (results.stream().noneMatch(r -> ArtifactService.FOUND_RESULT.equals(r.getName()))) {
      taskRunEntity.setResults(
          ResultUtil.addUniqueResults(
              results, List.of(new RunResult(ArtifactService.FOUND_RESULT, "true"))));
    }
  }

  // Sets the named fields to the values a save of the TaskRun would store: the same converter writes
  // them, so map keys are escaped and values converted exactly as on a whole-document save.
  private Update fieldsAsSaved(TaskRunEntity taskRun, List<String> fields) {
    Document saved = new Document();
    mongoTemplate.getConverter().write(taskRun, saved);
    Update update = new Update();
    fields.forEach(field -> update.set(field, saved.get(field)));
    return update;
  }

  // A TaskRun in one of the given phases and, when the request names its dispatcher, still held by
  // it - the condition a dispatcher's start or end writes its fields under.
  private static Query heldInPhase(String id, Optional<String> claimedBy, RunPhase... phases) {
    Criteria criteria = Criteria.where("_id").is(id).and("phase").in((Object[]) phases);
    if (claimedBy.isPresent()) {
      criteria = criteria.and("claim.by").is(claimedBy.get());
    }
    return Query.query(criteria);
  }

  // Wire-level fence, the same rule TaskExecutionService.claimantIsValid applies after it: a
  // request that names its dispatcher must match claim.by, and an unfinished TaskRun with no
  // claimant - its reaped claim released by a termination hand-off - has none to match. Refused
  // before anything is written. A request with no dispatcherRef is the legacy protocol and passes
  // unfenced. claim.seq is not on the wire, so a dispatcher that lost and re-won the same claim is
  // not distinguished from its earlier self.
  public static void rejectSupersededClaimant(TaskRunEntity taskRun, Optional<String> claimedBy) {
    String current = (taskRun.getClaim() != null) ? taskRun.getClaim().getBy() : null;
    if (claimedBy.isEmpty()
        || (current == null && RunPhase.completed.equals(taskRun.getPhase()))) {
      return;
    }
    if (!claimedBy.get().equals(current)) {
      LOGGER.error(
          "[{}] Rejecting request from superseded claimant {}. Current claim is {} (seq {}).",
          taskRun.getId(),
          claimedBy.get(),
          current,
          (taskRun.getClaim() != null) ? taskRun.getClaim().getSeq() : null);
      throw new BoomerangException(BoomerangError.TASKRUN_CLAIM_SUPERSEDED);
    }
  }

  /**
   * The task's raw log, unscrubbed - the workspace-scoped route scrubs it. While the task runs it
   * is the live read from the claim's dispatcher; a copy stored during this attempt answers only
   * when that dispatcher has no live route or its route is unavailable. Once the task has ended,
   * the stored copy is the whole log, outlives the dispatcher's runtime object and needs no
   * dispatcher to be up; without one, the live read.
   */
  public StreamingResponseBody streamLog(String taskRunId) {
    if (Objects.isNull(taskRunId) || taskRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.TASKRUN_INVALID_REF);
    }
    LOGGER.info("Getting TaskRun[{}] log...", taskRunId);
    TaskRunEntity taskRun =
        taskRunRepository
            .findById(taskRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.TASKRUN_INVALID_REF));
    Optional<ArtifactEntity> stored = artifactService.taskLog(taskRun);
    if (!RunPhase.completed.equals(taskRun.getPhase())) {
      StreamingResponseBody thisAttempt =
          stored
              .filter(log -> storedThisAttempt(log, taskRun))
              .map(this::storedLog)
              .orElse(null);
      return logClient.streamLog(taskRun, thisAttempt);
    }
    return stored
        .map(this::storedLog)
        .orElseGet(() -> logClient.streamLog(taskRun));
  }

  // A running task's stored copy is a snapshot of this attempt only when it was stored after the
  // current claim was made; an earlier attempt's log - a requeued task's - is not this run of it.
  private static boolean storedThisAttempt(ArtifactEntity log, TaskRunEntity taskRun) {
    Date claimedAt = (taskRun.getClaim() != null) ? taskRun.getClaim().getAt() : null;
    return claimedAt != null
        && log.getCreationDate() != null
        && !log.getCreationDate().before(claimedAt);
  }

  private StreamingResponseBody storedLog(ArtifactEntity log) {
    return outputStream -> {
      try (InputStream in = artifactService.openLog(log)) {
        in.transferTo(outputStream);
        outputStream.flush();
      }
    };
  }

  /**
   * Store the finished log a dispatcher sends for a task as the task's log artifact, fenced on the
   * claim like start and end. A body over {@code flow.engine.task.log.max-bytes} is refused whole:
   * a truncated log would lose its tail, which is where a failure shows.
   */
  public void storeLog(String taskRunId, Optional<String> dispatcherRef, InputStream body) {
    TaskRunEntity taskRun =
        taskRunRepository
            .findById(taskRunId)
            .orElseThrow(() -> new BoomerangException(BoomerangError.TASKRUN_INVALID_REF));
    rejectSupersededClaimant(taskRun, dispatcherRef);
    byte[] content;
    try {
      content = body.readNBytes(logMaxBytes + 1);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (content.length > logMaxBytes) {
      throw new BoomerangException(BoomerangError.TASKRUN_LOG_TOO_LARGE, logMaxBytes);
    }
    artifactService.storeTaskLog(taskRun, content);
    LOGGER.info("[{}] Stored the task log ({} bytes).", taskRunId, content.length);
  }
}
