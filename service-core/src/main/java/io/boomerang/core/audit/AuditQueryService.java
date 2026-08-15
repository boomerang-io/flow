package io.boomerang.core.audit;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/*
 * The read/query surface over Audit data for consumers outside of io.boomerang.core.
 *
 * Feature modules (e.g. workspace) must not import AuditRepository/AuditEntity directly -
 * core is the platform substrate and its persistence types stay internal to core.audit. This
 * service exposes the same query capability those consumers actually use, projected onto the
 * AuditRecord read model.
 */
@Service
public class AuditQueryService {

  private final AuditRepository auditRepository;
  private final MongoTemplate mongoTemplate;

  public AuditQueryService(AuditRepository auditRepository, MongoTemplate mongoTemplate) {
    this.auditRepository = auditRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public Optional<AuditRecord> findFirstByScopeAndSelfName(AuditScope scope, String selfName) {
    return auditRepository.findFirstByScopeAndSelfName(scope, selfName).map(this::toRecord);
  }

  public Optional<AuditRecord> findFirstByScopeAndSelfRef(AuditScope scope, String selfRef) {
    return auditRepository.findFirstByScopeAndSelfRef(scope, selfRef).map(this::toRecord);
  }

  public List<AuditRecord> findByScopeAndParent(AuditScope scope, String parent) {
    return auditRepository.findByScopeAndParent(scope, parent).stream().map(this::toRecord).toList();
  }

  /*
   * Queries Audit records by scope, a creation date range [from, to), and a `data.<dataField>`
   * value membership check. Used for scope/time-window rollups (e.g. Workflow Run Insights)
   * that can't be served by a repository-derived query.
   */
  public List<AuditRecord> findByScopeAndDateRangeAndDataFieldIn(
      AuditScope scope, Date from, Date to, String dataField, List<String> dataFieldValues) {
    List<Criteria> criteriaList = new ArrayList<>();
    criteriaList.add(Criteria.where("scope").is(scope));
    criteriaList.add(Criteria.where("creationDate").gte(from).lt(to));
    criteriaList.add(Criteria.where("data." + dataField).in(dataFieldValues));

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    return mongoTemplate.find(query, AuditEntity.class).stream().map(this::toRecord).toList();
  }

  private AuditRecord toRecord(AuditEntity entity) {
    return new AuditRecord(
        entity.getId(),
        entity.getScope(),
        entity.getSelfRef(),
        entity.getSelfName(),
        entity.getParent(),
        entity.getCreationDate(),
        entity.getData());
  }
}
