package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunRepositoryCustomImpl implements WorkflowRunRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  public WorkflowRunRepositoryCustomImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public List<WorkflowRunEntity> findClaimableForProvision(int limit) {
    Query query =
        Query.query(
                Criteria.where("status")
                    .is(RunStatus.ready)
                    .and("phase")
                    .is(RunPhase.pending)
                    .and("claim")
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
                    .and("claim")
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
                .and("claim")
                .exists(false));
    Update update =
        new Update()
            .set("phase", RunPhase.queued)
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("agentRef", claimedBy)
            .inc("claimEpoch", 1);
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
  }

  @Override
  public WorkflowRunEntity tryClaimForTeardown(String id, String claimedBy) {
    Query query =
        Query.query(
            Criteria.where("_id")
                .is(id)
                .and("phase")
                .is(RunPhase.completed)
                .and("claim")
                .exists(false)
                .and("workspaces.0")
                .exists(true));
    Update update =
        new Update()
            .set("claim.by", claimedBy)
            .set("claim.at", new Date())
            .set("agentRef", claimedBy)
            .inc("claimEpoch", 1);
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
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
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(false), WorkflowRunEntity.class);
  }

  @Override
  public WorkflowRunEntity tryStart(String id, Date startTime) {
    // Clearing the dispatch claim frees the completed-phase teardown claimable; the epoch is
    // top-level and survives.
    Query query =
        Query.query(
            Criteria.where("_id").is(id).and("phase").in(RunPhase.pending, RunPhase.queued));
    Update update =
        new Update()
            .set("status", RunStatus.running)
            .set("phase", RunPhase.running)
            .set("startTime", startTime)
            .unset("claim");
    return mongoTemplate.findAndModify(
        query, update, FindAndModifyOptions.options().returnNew(true), WorkflowRunEntity.class);
  }

  @Override
  public void setAwaitingApproval(String id, boolean awaitingApproval) {
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(id)),
        new Update().set("isAwaitingApproval", awaitingApproval),
        WorkflowRunEntity.class);
  }
}
