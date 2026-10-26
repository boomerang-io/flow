package io.boomerang.mongo.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import io.boomerang.mongo.entity.RevisionEntity;
import io.boomerang.mongo.model.WorkFlowRevisionCount;

public interface FlowWorkflowVersionRepository
    extends MongoRepository<RevisionEntity, String> {

  long countByworkFlowId(String workFlowId);

  @Override
  Optional<RevisionEntity> findById(String id);

  RevisionEntity findByworkFlowIdAndVersion(String workFlowId, long version);

  Page<RevisionEntity> findByworkFlowId(String string, Pageable pageable);

  RevisionEntity findByworkFlowIdAndVersionAndDagTasksTaskIdAndDagTasksPropertiesKey(String workflowId,
      long workflowVersion, String taskId, String propertyKey);
  
  @Aggregation(pipeline = {
		  "{'$match':{'workFlowId': {$in: ?0}}}",
	      "{'$sort':{'workFlowId': -1, version: -1}}",
	      "{'$group': { _id: '$workFlowId', 'count': { $sum: 1 }}}"
	})
  @Meta(allowDiskUse = "true")
  List<WorkFlowRevisionCount> findWorkFlowVersionCounts(List<String> workflowIds);

  @Aggregation(pipeline = {
		  "{'$match':{'workFlowId': {$in: ?0}}}",
	      "{'$sort':{'workFlowId': -1, version: -1}}",
	      "{'$group': { _id: '$workFlowId', 'count': { $sum: 1 }, 'latestVersion': {$first: '$$ROOT'}}}"
	})
  @Meta(allowDiskUse = "true")
  List<WorkFlowRevisionCount> findWorkFlowVersionCountsAndLatestVersion(List<String> workflowIds);

}
