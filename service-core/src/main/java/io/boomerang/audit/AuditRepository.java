package io.boomerang.audit;

import java.util.Optional;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

// Explicitly named (E8.2a merge): avoids a Spring bean-name clash with the unrelated
// io.boomerang.core.audit.AuditRepository (same simple interface name) now that service-engine
// and service-core share one context. See merge commit message.
@Repository("engineAuditRepository")
public interface AuditRepository extends MongoRepository<AuditEntity, String> {
  
  Optional<AuditEntity> findFirstByScopeAndSelfRef(AuditScope scope, String selfRef);

  Optional<AuditEntity> findFirstByScopeAndSelfName(AuditScope scope, String selfName);

  @Aggregation(pipeline = {"{'$match':{'data.duplicateOf': ?0}}", "{'$sort': {'creationDate': -1}}", "{'$limit': 1}"})
  Optional<AuditEntity> findFirstByWorkflowDuplicateOf(String duplicateOf);
}

