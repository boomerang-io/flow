package io.boomerang.core.audit;

import io.boomerang.common.util.QueryCriteria;
import java.util.Collection;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The read/query surface over the audit trail, behind {@link AuditControllerV2} and the workspace
 * quota's run figures. Filters mirror the query indexes: workspace, actor, action, capture level,
 * resource type and id, outcome, and a time range; newest first.
 *
 * <p>Every listing is bounded by the time range the caller supplies - the controller defaults it
 * to the last 30 days - so both the page and its count ride the {@code time} index on an
 * insert-only collection. The low-cardinality filters (action, outcome, level) and the actor
 * substring narrow inside that window rather than carrying indexes of their own.
 */
@Service
public class AuditQueryService {

  private final MongoTemplate mongoTemplate;

  public AuditQueryService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public Page<AuditEventEntity> query(
      Pageable pageable,
      Optional<List<String>> workspaceIds,
      Optional<String> actor,
      Optional<List<String>> actions,
      Optional<List<String>> levels,
      Optional<List<String>> resourceTypes,
      Optional<String> resourceId,
      Optional<List<String>> outcomes,
      Optional<Date> from,
      Optional<Date> to) {
    Query query =
        new Query(
            filtered(
                workspaceIds, actor, actions, levels, resourceTypes, resourceId, outcomes, from,
                to));
    long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), AuditEventEntity.class);
    query.with(pageable);
    if (pageable.getSort().isUnsorted()) {
      query.with(Sort.by(Sort.Direction.DESC, "time"));
    }
    List<AuditEventEntity> events = mongoTemplate.find(query, AuditEventEntity.class);
    return new PageImpl<>(events, pageable, total);
  }

  /**
   * Count the same filtered window by outcome - the stat tiles beside the listing. One aggregation
   * over the window the listing already scans, so it adds no index requirement; an outcome with no
   * events in the window answers zero rather than being absent.
   */
  public Map<AuditOutcome, Long> outcomeCounts(
      Optional<List<String>> workspaceIds,
      Optional<String> actor,
      Optional<List<String>> actions,
      Optional<List<String>> levels,
      Optional<List<String>> resourceTypes,
      Optional<String> resourceId,
      Optional<List<String>> outcomes,
      Optional<Date> from,
      Optional<Date> to) {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(
                filtered(
                    workspaceIds, actor, actions, levels, resourceTypes, resourceId, outcomes,
                    from, to)),
            Aggregation.group("outcome").count().as("count"));
    Map<AuditOutcome, Long> counts = new EnumMap<>(AuditOutcome.class);
    for (AuditOutcome outcome : AuditOutcome.values()) {
      counts.put(outcome, 0L);
    }
    mongoTemplate
        .aggregate(aggregation, AuditEventEntity.class, Document.class)
        .forEach(
            row -> {
              AuditOutcome outcome = parseOutcome(row.getString("_id"));
              if (outcome != null) {
                counts.merge(outcome, ((Number) row.get("count")).longValue(), Long::sum);
              }
            });
    return counts;
  }

  /** The shared predicate behind the listing and its counts; unfiltered matches everything. */
  private static Criteria filtered(
      Optional<List<String>> workspaceIds,
      Optional<String> actor,
      Optional<List<String>> actions,
      Optional<List<String>> levels,
      Optional<List<String>> resourceTypes,
      Optional<String> resourceId,
      Optional<List<String>> outcomes,
      Optional<Date> from,
      Optional<Date> to) {
    QueryCriteria criteria = new QueryCriteria().in("workspaceId", workspaceIds);
    actor.filter(value -> !value.isBlank()).ifPresent(value -> criteria.add(actorMatch(value)));
    criteria.in("action", actions).in("level", levels).in("resourceType", resourceTypes);
    resourceId.ifPresent(value -> criteria.add(Criteria.where("resourceId").is(value)));
    return criteria.in("outcome", outcomes).between("time", from, to).build();
  }

  /**
   * Match an actor by id or display name, case-insensitively, on any part of either - one search
   * box serves "who is bfu_a1b2" and "everything Jane did". The term is quoted, so a regex
   * metacharacter pasted in from an id is matched literally.
   */
  private static Criteria actorMatch(String value) {
    Pattern term = Pattern.compile(Pattern.quote(value.trim()), Pattern.CASE_INSENSITIVE);
    return new Criteria()
        .orOperator(
            Criteria.where("actorId").regex(term), Criteria.where("actorName").regex(term));
  }

  private static AuditOutcome parseOutcome(String value) {
    if (value == null) {
      return null;
    }
    try {
      return AuditOutcome.valueOf(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * How many runs a workspace's run events cover in a window, how many of them ended in it, and the
   * total duration of those that ended.
   */
  public record RunTotals(long runs, long endedRuns, long duration) {}

  /** {@link #runTotals(Collection, Date, Date)} for one workspace. */
  public RunTotals runTotals(String workspaceId, Date from, Date to) {
    return runTotals(List.of(workspaceId), from, to)
        .getOrDefault(workspaceId, new RunTotals(0L, 0L, 0L));
  }

  /**
   * Total each workspace's runs and their durations over a time window from the run events, keyed
   * by workspace id, so runs of since-deleted Workflows still count - the run-duration figures in
   * {@code QuotaService.current}. Only the engine's CREATE and UPDATE events are read, not a
   * route's own (a cancel's DELETE): each run counts once, with the duration its UPDATE, its
   * completion, carries, and as ended when that UPDATE is in the window; a workspace with no run
   * events is absent. One aggregation over the {@code workspace_time} index, however many
   * workspaces are asked for.
   */
  public Map<String, RunTotals> runTotals(Collection<String> workspaceIds, Date from, Date to) {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(
                new Criteria()
                    .andOperator(
                        Criteria.where("workspaceId").in(workspaceIds),
                        Criteria.where("action")
                            .in(AuditAction.CREATE.name(), AuditAction.UPDATE.name()),
                        Criteria.where("resourceType").is("workflowrun"),
                        Criteria.where("time").gte(from).lt(to))),
            context ->
                new Document(
                    "$group",
                    new Document(
                            "_id",
                            new Document("workspaceId", "$workspaceId")
                                .append("resourceId", "$resourceId"))
                        .append("duration", new Document("$max", "$payload.duration"))
                        .append("ended", new Document("$max", isAction(AuditAction.UPDATE)))),
            context ->
                new Document(
                    "$group",
                    new Document("_id", "$_id.workspaceId")
                        .append("runs", new Document("$sum", 1))
                        .append("endedRuns", new Document("$sum", "$ended"))
                        .append("duration", new Document("$sum", "$duration"))));
    Map<String, RunTotals> totals = new HashMap<>();
    for (Document workspace :
        mongoTemplate.aggregate(aggregation, AuditEventEntity.class, Document.class)) {
      totals.put(
          workspace.getString("_id"),
          new RunTotals(
              ((Number) workspace.get("runs")).longValue(),
              ((Number) workspace.get("endedRuns")).longValue(),
              (workspace.get("duration") instanceof Number duration) ? duration.longValue() : 0L));
    }
    return totals;
  }

  /**
   * A workspace's use of its monthly quotas in a window: the runs created, less the unmetered ones,
   * and the cumulative duration of the runs that ended, in minutes, each run rounded up on its own.
   */
  public record MonthlyUsage(long runs, long cumulativeMinutes) {}

  /**
   * Each workspace's {@link MonthlyUsage} in a time window, keyed by workspace id; a workspace with
   * no run events is absent. A run counts once by its CREATE event, unless that event marks it
   * unmetered, and its minutes once by its UPDATE event, the completion's only one. One aggregation
   * over the {@code workspace_time} index, which reads the window's run events as the count of
   * creations alone did.
   */
  public Map<String, MonthlyUsage> monthlyUsage(
      Collection<String> workspaceIds, Date from, Date to) {
    Document metered = new Document("$ne", List.of("$payload.unmetered", true));
    Document minutes =
        new Document(
            "$ceil",
            new Document(
                "$divide",
                List.of(
                    new Document("$ifNull", List.of("$payload.cumulativeDuration", 0)),
                    MILLIS_PER_MINUTE)));
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(
                new Criteria()
                    .andOperator(
                        Criteria.where("workspaceId").in(workspaceIds),
                        Criteria.where("action")
                            .in(AuditAction.CREATE.name(), AuditAction.UPDATE.name()),
                        Criteria.where("resourceType").is("workflowrun"),
                        Criteria.where("time").gte(from).lt(to))),
            context ->
                new Document(
                    "$group",
                    new Document("_id", "$workspaceId")
                        .append(
                            "runs",
                            new Document(
                                "$sum",
                                new Document(
                                    "$cond",
                                    List.of(
                                        new Document(
                                            "$and",
                                            List.of(
                                                isActionTest(AuditAction.CREATE), metered)),
                                        1,
                                        0))))
                        .append(
                            "cumulativeMinutes",
                            new Document(
                                "$sum",
                                new Document(
                                    "$cond",
                                    List.of(isActionTest(AuditAction.UPDATE), minutes, 0))))));
    Map<String, MonthlyUsage> usage = new HashMap<>();
    for (Document workspace :
        mongoTemplate.aggregate(aggregation, AuditEventEntity.class, Document.class)) {
      usage.put(
          workspace.getString("_id"),
          new MonthlyUsage(
              ((Number) workspace.get("runs")).longValue(),
              ((Number) workspace.get("cumulativeMinutes")).longValue()));
    }
    return usage;
  }

  private static final long MILLIS_PER_MINUTE = 60_000L;

  // 1 for an event of the action, else 0.
  private static Document isAction(AuditAction action) {
    return new Document("$cond", List.of(isActionTest(action), 1, 0));
  }

  private static Document isActionTest(AuditAction action) {
    return new Document("$eq", List.of("$action", action.name()));
  }
}
