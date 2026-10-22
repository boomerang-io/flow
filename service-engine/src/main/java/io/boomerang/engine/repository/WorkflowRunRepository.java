package io.boomerang.engine.repository;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface WorkflowRunRepository extends MongoRepository<WorkflowRunEntity, String> {

  void deleteByWorkflowRef(String workflowRef);

  @Query("{ '$or': [ { 'phase': { $in: ?0 }, 'status': { $in: ?1 } }, { 'phase': { $in: ?2 }} ]}")
  List<WorkflowRunEntity> findByPhaseInAndStatusInOrPhaseIn(
      List<RunPhase> phase, List<RunStatus> statuses, List<RunPhase> phaseOr);

  //  List<WorkflowRunEntity> findByPhase(RunPhase phase);

  @Query(
      "{ 'phase': { $in: ?0 }, 'status': { $in: ?1 }, '$or': [ { 'agentRef': '' }, { 'agentRef': { '$exists': false } } ]}")
  @Update("{ '$set': { 'agentRef': ?2, 'phase': ?3} }")
  void updatePhaseAndAgentRef(
      List<RunPhase> phase, List<RunStatus> statuses, String agentRef, RunPhase phaseToSet);
}
