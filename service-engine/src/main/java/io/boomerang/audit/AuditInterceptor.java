package io.boomerang.audit;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/*
 * Intercepts all of the Create, Update, Delete, and Actions performed on objects and creates an Audit log
 * 
 * Ref: https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html
 * Ref: https://www.baeldung.com/spring-boot-authentication-audit
 */
@Component
public class AuditInterceptor {  
  private static final Logger LOGGER = LogManager.getLogger();
  
  @Autowired
  private AuditRepository auditRepository;
  
  private Map<String, String> wfRunIdToParentAuditId = new HashMap<>();
  
  /*
   * Creates an AuditEntity
   */
  public AuditEntity createWfRunLog(String selfRef, String parent, Optional<Map<String, String>> data) {
    try {
      LOGGER.debug("AuditInterceptor - Creating Audit for: {}.", selfRef);
      AuditEvent auditEvent = new AuditEvent(AuditType.notstarted);
      return auditRepository.insert(new AuditEntity(AuditScope.WORKFLOWRUN, selfRef, Optional.empty(), Optional.of(getParentAuditIdFromWorkflowRunId(selfRef, parent)), auditEvent, data));
    } catch (Exception ex) {
      LOGGER.error("Unable to create Audit record with exception: {}.", ex.toString());
    }
    return null;
  }
  
  /*
   * Updates an AuditEntity
   */
  public AuditEntity updateWfRunLog(AuditType type, String selfRef, Optional<Map<String, String>> data) {
    try {
      LOGGER.debug("AuditInterceptor - Updating Audit for: {} with event: {}.", selfRef, type);
      Optional<AuditEntity> auditEntity = auditRepository.findFirstByScopeAndSelfRef(AuditScope.WORKFLOWRUN, selfRef);
      if (auditEntity.isPresent()) {
        if (data.isPresent()) {
          auditEntity.get().getData().putAll(data.get());
        }
        AuditEvent auditEvent = new AuditEvent(type);
        auditEntity.get().getEvents().add(auditEvent);
        return auditRepository.save(auditEntity.get());
      } 
    } catch (Exception ex) {
      LOGGER.error("Unable to create Audit record with exception: {}.", ex.toString());
    }
    return null;
  }

  private String getParentAuditIdFromWorkflowRunId(String wfRunId, String parent) {
    if (wfRunIdToParentAuditId.containsKey(wfRunId)) {
      return wfRunIdToParentAuditId.get(wfRunId);
    }
    //Retrieve the WORKFLOW Audit and get its parentRef which is the parent we need
    Optional<AuditEntity> wfEntity = auditRepository.findFirstByScopeAndSelfRef(AuditScope.WORKFLOW, parent);
    if (wfEntity.isPresent()) {
      wfRunIdToParentAuditId.put(wfRunId, wfEntity.get().getParent());
      return wfEntity.get().getParent();
    }
    LOGGER.error("Unable to find Audit record for WorkflowRun: {}", wfRunId);
    return "";
  }
}