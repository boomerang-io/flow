package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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

  private final MongoTemplate mongoTemplate;

  public TaskRunRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
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
                    .exists(false))
            .with(Sort.by(Sort.Direction.ASC, "creationDate"))
            .limit(limit);
    return mongoTemplate.find(query, TaskRunEntity.class);
  }

  @Override
  public TaskRunEntity tryClaim(String id, String claimedBy) {
    // Re-checks full eligibility between page and claim; racing claimants cannot both win.
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
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
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
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), TaskRunEntity.class);
  }

  @Override
  public TaskRunEntity tryStartExecution(String id, Date startTime) {
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
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(true), TaskRunEntity.class);
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
    Update update = new Update().set("phase", RunPhase.completed).set("duration", duration);
    status.ifPresent(s -> update.set("status", s));
    statusMessage.ifPresent(m -> update.set("statusMessage", m));
    return mongoTemplate.findAndModify(
        Query.query(criteria),
        update,
        FindAndModifyOptions.options().returnNew(false),
        TaskRunEntity.class);
  }
}
