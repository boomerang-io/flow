package io.boomerang.core.audit;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.core.model.Token;
import io.boomerang.security.IdentityService;
import io.boomerang.workflow.model.Team;
import io.boomerang.workflow.model.TeamRequest;
import io.boomerang.workflow.model.WorkflowCanvas;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/*
 * Intercepts all of the Create, Update, Delete, and Actions performed on objects and creates an Audit log
 *
 * Ref: https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/advice.html
 * Ref: https://www.baeldung.com/spring-boot-authentication-audit
 */
@Aspect
@Component
public class AuditInterceptor {
  private static final Logger LOGGER = LogManager.getLogger();

  @Autowired private IdentityService identityService;

  @Autowired private AuditRepository auditRepository;

  private Map<String, String> teamNameToAuditId = new HashMap<>();

  // Future: using an annotation
  //  @AfterReturning("@annotation(audit)")
  //  public void audit(JoinPoint thisJoinPoint, Audit audit) {
  //    LOGGER.debug("AuditInterceptor - using annotation");
  //    LOGGER.debug("AuditInterceptor - {}", thisJoinPoint.getSignature().getDeclaringType());
  //    LOGGER.debug("AuditInterceptor - {}", audit.scope());
  //  }

  /*
   * WORKFLOW auditing
   */
  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.service.WorkflowService.create(..)) && args(team, request)",
      returning = "entity")
  public void createWorkflow(JoinPoint joinPoint, String team, Workflow request, Workflow entity) {
    createLog(
        AuditScope.WORKFLOW,
        entity.getName(),
        Optional.of(entity.getName()),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.of(Map.of("name", entity.getName())));
  }

  /*
   * Duplicate won't be captured by create (even though it calls create) because AOP is only triggered by proxied request
   *
   * Ref: https://docs.spring.io/spring-framework/reference/core/aop/proxying.html#aop-understanding-aop-proxies
   */
  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.service.WorkflowService.duplicate(..)) && args(team, id)",
      returning = "entity")
  private void duplicateWorkflow(JoinPoint joinPoint, String team, String id, Workflow entity) {
    Map<String, String> data = new HashMap<>();
    data.put("duplicateOf", id);
    data.put("name", entity.getName());
    createLog(
        AuditScope.WORKFLOW,
        entity.getName(),
        Optional.of(entity.getName()),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.of(data));
  }

  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.service.WorkflowService.apply(..)) && args(team, request, replace)",
      returning = "entity")
  private void updateWorkflow(
      JoinPoint thisJoinPoint, String team, Workflow request, boolean replace, Workflow entity) {
    updateLog(
        AuditScope.WORKFLOW,
        AuditType.updated,
        entity.getName(),
        Optional.of(entity.getName()),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.of(Map.of("name", entity.getName())));
  }

  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.service.WorkflowService.composeApply(..)) && args(team, request, replace)",
      returning = "entity")
  private void updateWorkflow(
      JoinPoint thisJoinPoint,
      String team,
      WorkflowCanvas request,
      boolean replace,
      WorkflowCanvas entity) {
    updateLog(
        AuditScope.WORKFLOW,
        AuditType.updated,
        entity.getName(),
        Optional.of(entity.getName()),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.of(Map.of("name", entity.getName())));
  }

  @AfterReturning(
      pointcut = "execution(* io.boomerang.service.WorkflowService.submit(..)) && args(team, id)",
      returning = "entity")
  private void updateWorkflow(
      JoinPoint thisJoinPoint, String team, String name, WorkflowRun entity) {
    updateLog(
        AuditScope.WORKFLOW,
        AuditType.submitted,
        name,
        Optional.of(name),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.empty());
  }

  @AfterReturning(
      "execution(* io.boomerang.service.WorkflowService.delete(..))" + " && args(team, id)")
  private void deleteWorkflow(JoinPoint thisJoinPoint, String team, String name) {
    LOGGER.debug("AuditInterceptor - {}", thisJoinPoint.getSignature().getDeclaringType());
    updateLog(
        AuditScope.WORKFLOW,
        AuditType.deleted,
        name,
        Optional.of(name),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.empty());
  }

  /*
   * TEAM auditing
   */
  @AfterReturning(
      pointcut = "execution(* io.boomerang.service.TeamService.create(..)) && args(request)",
      returning = "entity")
  private void createTeam(JoinPoint thisJoinPoint, TeamRequest request, Team entity) {
    AuditEntity log =
        createLog(
            AuditScope.TEAM,
            entity.getId(),
            Optional.of(entity.getName()),
            Optional.empty(),
            Optional.of(Map.of("name", entity.getName())));
    teamNameToAuditId.put(entity.getName(), log.getId());
  }

  @AfterReturning(
      pointcut = "execution(* io.boomerang.service.TeamService.patch(..))",
      returning = "entity")
  private void updateTeam(JoinPoint thisJoinPoint, Team entity) {
    AuditEntity log =
        updateLog(
            AuditScope.TEAM,
            AuditType.updated,
            entity.getId(),
            Optional.of(entity.getName()),
            Optional.empty(),
            Optional.of(Map.of("name", entity.getName())));
    teamNameToAuditId.put(entity.getName(), log.getId());
  }

  @AfterReturning("execution(* io.boomerang.service.TeamService.delete(..))" + " && args(id)")
  private void deleteTeam(JoinPoint thisJoinPoint, String id) {
    updateLogByAuditId(
        AuditType.deleted,
        getTeamAuditIdFromName(id),
        Optional.of(""),
        Optional.empty(),
        Optional.empty());
    teamNameToAuditId.remove(id);
  }

  /*
   * Creates an AuditEntity
   */
  private AuditEntity createLog(
      AuditScope scope,
      String selfRef,
      Optional<String> selfName,
      Optional<String> parent,
      Optional<Map<String, String>> data) {
    try {
      LOGGER.debug(
          "AuditInterceptor - Creating new Audit for: {} - {}.",
          selfRef,
          selfName.isPresent() ? selfName.get() : "n/a");
      Token accessToken = this.identityService.getCurrentIdentity();
      AuditEvent auditEvent = new AuditEvent(AuditType.created, accessToken);
      return auditRepository.insert(
          new AuditEntity(scope, selfRef, selfName, parent, auditEvent, data));
    } catch (Exception ex) {
      LOGGER.error("Unable to create Audit record with exception: {}.", ex.toString());
    }
    return null;
  }

  /*
   * Updates an AuditEntity
   */
  private AuditEntity updateLog(
      AuditScope scope,
      AuditType type,
      String selfRef,
      Optional<String> selfName,
      Optional<String> parent,
      Optional<Map<String, String>> data) {
    try {
      LOGGER.debug("AuditInterceptor - Updating Audit for: {} with event: {}.", selfRef, type);
      Token accessToken = this.identityService.getCurrentIdentity();
      Optional<AuditEntity> auditEntity =
          auditRepository.findFirstByScopeAndSelfRef(scope, selfRef);
      if (auditEntity.isPresent()) {
        if (data.isPresent()) {
          auditEntity.get().getData().putAll(data.get());
        }
        if (selfName.isPresent()) {
          auditEntity.get().setSelfName(selfName.get());
        }
        if (parent.isPresent() && auditEntity.get().getParent().isBlank()) {
          auditEntity.get().setParent(parent.get());
        }
        AuditEvent auditEvent = new AuditEvent(type, accessToken);
        auditEntity.get().getEvents().add(auditEvent);
        return auditRepository.save(auditEntity.get());
      }
    } catch (Exception ex) {
      LOGGER.error("Unable to create Audit record with exception: {}.", ex.toString());
    }
    return null;
  }

  /*
   * Updates an AuditEntity
   */
  private AuditEntity updateLogByAuditId(
      AuditType type,
      String auditId,
      Optional<String> selfName,
      Optional<String> parent,
      Optional<Map<String, String>> data) {
    try {
      LOGGER.debug("AuditInterceptor - Updating Audit for: {} with event: {}.", auditId, type);
      Token accessToken = this.identityService.getCurrentIdentity();
      Optional<AuditEntity> auditEntity = auditRepository.findById(auditId);
      if (auditEntity.isPresent()) {
        if (data.isPresent()) {
          auditEntity.get().getData().putAll(data.get());
        }
        if (selfName.isPresent()) {
          auditEntity.get().setSelfName(selfName.get());
        }
        AuditEvent auditEvent = new AuditEvent(type, accessToken);
        auditEntity.get().getEvents().add(auditEvent);
        return auditRepository.save(auditEntity.get());
      }
    } catch (Exception ex) {
      LOGGER.error("Unable to create Audit record with exception: {}.", ex.toString());
    }
    return null;
  }

  private String getTeamAuditIdFromName(String name) {
    if (teamNameToAuditId.containsKey(name)) {
      return teamNameToAuditId.get(name);
    }
    Optional<AuditEntity> optAuditEntity =
        auditRepository.findFirstByScopeAndSelfName(AuditScope.TEAM, name);
    if (optAuditEntity.isPresent()) {
      teamNameToAuditId.put(name, optAuditEntity.get().getId());
      return optAuditEntity.get().getId();
    }
    //    AuditEntity log = createLog(AuditScope.TEAM, "", Optional.of(name), Optional.empty(),
    // Optional.of(Map.of("name", name)));
    //    teamNameToAuditId.put(name, log.getId());
    //    return log.getId();
    return "";
  }
}
