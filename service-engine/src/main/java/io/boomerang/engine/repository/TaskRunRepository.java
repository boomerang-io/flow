package io.boomerang.engine.repository;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface TaskRunRepository extends MongoRepository<TaskRunEntity, String> {

  List<TaskRunEntity> findByWorkflowRunRef(String workflowRunRef);

  Optional<TaskRunEntity> findFirstByNameAndWorkflowRunRef(String name, String workflowRunRef);

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  List<TaskRunEntity> findByPhaseInAndStatusInAndTypeIn(
      List<RunPhase> phase, List<RunStatus> statuses, List<TaskType> types);

  @Query(
      "{ 'phase': { $in: ?0 }, 'status': { $in: ?1 }, 'type': { $in: ?2 } '$or': [ { 'agentRef': '' }, { 'agentRef': { '$exists': false } } ]}")
  @Update("{ '$set': { 'agentRef': ?3, 'phase': ?4} }")
  void updatePhaseAndAgentRef(
      List<RunPhase> phase,
      List<RunStatus> statuses,
      List<TaskType> types,
      String agentRef,
      RunPhase phaseToSet);
}
