package io.boomerang.core.audit;

import io.boomerang.common.util.QueryCriteria;
import java.util.Date;
import java.util.EnumMap;
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

  /** How many runs a workspace's run events cover in a window, and their total duration. */
  public record RunTotals(long runs, long duration) {}

  /**
   * Total a workspace's runs and their durations over a time window from the run events, so runs
   * of since-deleted Workflows still count - the run-duration figures in {@code
   * QuotaService.current}. Each run counts once, with the duration its latest event in
   * the window carries. One aggregation over the {@code workspace_time} index.
   */
  public RunTotals runTotals(String workspaceId, Date from, Date to) {
    Aggregation aggregation =
        Aggregation.newAggregation(
            Aggregation.match(
                new Criteria()
                    .andOperator(
                        Criteria.where("workspaceId").is(workspaceId),
                        Criteria.where("resourceType").is("workflowrun"),
                        Criteria.where("time").gte(from).lt(to))),
            Aggregation.sort(Sort.Direction.ASC, "time"),
            Aggregation.group("resourceId").last("payload.duration").as("duration"),
            Aggregation.group().count().as("runs").sum("duration").as("duration"));
    Document totals =
        mongoTemplate
            .aggregate(aggregation, AuditEventEntity.class, Document.class)
            .getUniqueMappedResult();
    if (totals == null) {
      return new RunTotals(0L, 0L);
    }
    return new RunTotals(
        ((Number) totals.get("runs")).longValue(),
        (totals.get("duration") instanceof Number duration) ? duration.longValue() : 0L);
  }

  /**
   * Count run-creation events for one workspace in a time window — the monthly quota counter in
   * {@code QuotaService.current}. Served by the {@code workspace_time} index.
   */
  public long countRunsCreated(String workspaceId, Date from, Date to) {
    Query query =
        new Query(
            new Criteria()
                .andOperator(
                    Criteria.where("workspaceId").is(workspaceId),
                    Criteria.where("action").is(AuditAction.CREATE.name()),
                    Criteria.where("resourceType").is("workflowrun"),
                    Criteria.where("time").gte(from).lt(to)));
    return mongoTemplate.count(query, AuditEventEntity.class);
  }
}
