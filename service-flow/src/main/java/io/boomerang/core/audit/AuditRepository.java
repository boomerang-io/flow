package io.boomerang.core.audit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditRepository extends MongoRepository<AuditEntity, String> {
  
  Optional<AuditEntity> findFirstByScopeAndSelfRef(AuditScope scope, String selfRef);

  Optional<AuditEntity> findFirstByScopeAndSelfName(AuditScope scope, String selfName);

  @Aggregation(pipeline = {"{'$match':{'data.duplicateOf': ?0}}", "{'$sort': {'creationDate': -1}}", "{'$limit': 1}"})
  Optional<AuditEntity> findFirstByWorkflowDuplicateOf(String duplicateOf);
  
  List<AuditEntity> findByScopeAndParent(AuditScope scope, String parent);
}

