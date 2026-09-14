package io.boomerang.core.audit;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The read/query surface over the audit trail for consumers outside {@code io.boomerang.core} and
 * the future admin/workspace audit endpoints. Filters mirror the query indexes: workspace, actor,
 * action, resource type and id, outcome, and a time range; newest first.
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
      Optional<List<String>> actorIds,
      Optional<List<String>> actions,
      Optional<List<String>> resourceTypes,
      Optional<String> resourceId,
      Optional<List<String>> outcomes,
      Optional<Date> from,
      Optional<Date> to) {
    List<Criteria> criteria = new ArrayList<>();
    workspaceIds.ifPresent(values -> criteria.add(Criteria.where("workspaceId").in(values)));
    actorIds.ifPresent(values -> criteria.add(Criteria.where("actorId").in(values)));
    actions.ifPresent(values -> criteria.add(Criteria.where("action").in(values)));
    resourceTypes.ifPresent(values -> criteria.add(Criteria.where("resourceType").in(values)));
    resourceId.ifPresent(value -> criteria.add(Criteria.where("resourceId").is(value)));
    outcomes.ifPresent(values -> criteria.add(Criteria.where("outcome").in(values)));
    timeRange(from, to).ifPresent(criteria::add);

    Query query = new Query();
    if (!criteria.isEmpty()) {
      query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
    }
    long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), AuditEventEntity.class);
    query.with(pageable).with(Sort.by(Sort.Direction.DESC, "time"));
    List<AuditEventEntity> events = mongoTemplate.find(query, AuditEventEntity.class);
    return new PageImpl<>(events, pageable, total);
  }

  /**
   * Events for one workspace, resource type and time window, optionally narrowed by a payload
   * field membership check (e.g. {@code payload.workflowRef} in a set of refs) — the shape the
   * workspace insights rollup reads.
   */
  public List<AuditEventEntity> findByWorkspaceAndResourceType(
      String workspaceId,
      String resourceType,
      Date from,
      Date to,
      Optional<String> payloadField,
      Optional<List<String>> payloadValues) {
    List<Criteria> criteria = new ArrayList<>();
    criteria.add(Criteria.where("workspaceId").is(workspaceId));
    criteria.add(Criteria.where("resourceType").is(resourceType));
    criteria.add(Criteria.where("time").gte(from).lt(to));
    if (payloadField.isPresent() && payloadValues.isPresent()) {
      criteria.add(Criteria.where("payload." + payloadField.get()).in(payloadValues.get()));
    }
    Query query = new Query(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
    query.with(Sort.by(Sort.Direction.ASC, "time"));
    return mongoTemplate.find(query, AuditEventEntity.class);
  }

  /**
   * Count run-creation events for one workspace in a time window — the monthly quota counter in
   * {@code WorkspaceService.setCurrentQuotas}. Served by the {@code workspace_time} index.
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

  private static Optional<Criteria> timeRange(Optional<Date> from, Optional<Date> to) {
    if (from.isEmpty() && to.isEmpty()) {
      return Optional.empty();
    }
    Criteria time = Criteria.where("time");
    from.ifPresent(time::gte);
    to.ifPresent(time::lt);
    return Optional.of(time);
  }
}
