package io.boomerang.core.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEventRepository extends MongoRepository<AuditEventEntity, String> {}
