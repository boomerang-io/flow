package io.boomerang.engine.repository;

import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import java.util.Date;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

// Explicitly named (E8.2a merge; retained post-J3 merge of the former
// io.boomerang.workflow.repository.ActionRepository, whose finder methods are now unioned below).
@Repository("engineActionRepository")
public interface ActionRepository extends MongoRepository<ActionEntity, String> {

  ActionEntity findByTaskRunRef(String taskRunRef);

  boolean existsByWorkflowRunRefAndStatus(String workflowRunRef, ActionStatus status);

  long countByWorkflowRunRefAndStatus(String workflowRunRef, ActionStatus status);

  long countByCreationDateBetween(Date from, Date to);

  long countByTypeAndCreationDateBetween(ActionType type, Date from, Date to);

  long countByTypeAndCreationDateBetweenAndWorkflowRefInAndStatus(
      ActionType type, Date from, Date to, List<String> workflowRefs, ActionStatus status);

  long countByType(ActionType type);

  long countByStatus(ActionStatus submitted);

  long countByStatusAndCreationDateBetween(ActionStatus submitted, Date date, Date date2);

  long countByStatusAndTypeAndCreationDateBetween(
      ActionStatus submitted, ActionType type, Date date, Date date2);

  long countByStatusAndType(ActionStatus submitted, ActionType type);

  void deleteByWorkflowRef(String workflowRef);

  void deleteByWorkflowRunRef(String workflowRunRef);

  @Query("{'workflowRunRef': ?0 }")
  @Update("{ '$set' : { 'status' : ?1 } }")
  long updateStatusByWorkflowRunRef(String ref, ActionStatus status);
}
