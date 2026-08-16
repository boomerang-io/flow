package io.boomerang.core.audit;

import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.core.model.Token;
import io.boomerang.core.security.IdentityService;
import java.lang.reflect.InvocationTargetException;
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

  /*
   * WorkflowCanvas argument taken as Object and read via reflection (getName()) rather than
   * importing io.boomerang.workflow.model.WorkflowCanvas - core must not depend on workflow.
   */
  @AfterReturning(
      pointcut =
          "execution(* io.boomerang.service.WorkflowService.composeApply(..)) && args(team, request, replace)",
      returning = "entity")
  private void updateWorkflow(
      JoinPoint thisJoinPoint, String team, Object request, boolean replace, Object entity) {
    String entityName = reflectGetter(entity, "getName");
    updateLog(
        AuditScope.WORKFLOW,
        AuditType.updated,
        entityName,
        Optional.of(entityName),
        Optional.of(getTeamAuditIdFromName(team)),
        Optional.of(Map.of("name", entityName)));
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
   *
   * The advice below takes Workspace/WorkspaceRequest arguments as Object and reads them via reflection
   * (getId()/getName()) rather than importing io.boomerang.workspace.model types - core must not
   * depend on workspace. This mirrors the implicit "cast" AspectJ would otherwise perform when
   * binding these args to a typed parameter.
   */
  @AfterReturning(
      pointcut = "execution(* io.boomerang.service.WorkspaceService.create(..)) && args(request)",
      returning = "entity")
  private void createTeam(JoinPoint thisJoinPoint, Object request, Object entity) {
    String entityId = reflectGetter(entity, "getId");
    String entityName = reflectGetter(entity, "getName");
    AuditEntity log =
        createLog(
            AuditScope.WORKSPACE,
            entityId,
            Optional.of(entityName),
            Optional.empty(),
            Optional.of(Map.of("name", entityName)));
    teamNameToAuditId.put(entityName, log.getId());
  }

  @AfterReturning(
      pointcut = "execution(* io.boomerang.service.WorkspaceService.patch(..))",
      returning = "entity")
  private void updateTeam(JoinPoint thisJoinPoint, Object entity) {
    String entityId = reflectGetter(entity, "getId");
    String entityName = reflectGetter(entity, "getName");
    AuditEntity log =
        updateLog(
            AuditScope.WORKSPACE,
            AuditType.updated,
            entityId,
            Optional.of(entityName),
            Optional.empty(),
            Optional.of(Map.of("name", entityName)));
    teamNameToAuditId.put(entityName, log.getId());
  }

  @AfterReturning("execution(* io.boomerang.service.WorkspaceService.delete(..))" + " && args(id)")
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

  /*
   * Reads a no-arg String-returning getter (e.g. getId/getName) off an Object via reflection.
   * Used so this interceptor can read Workspace/WorkflowCanvas fields without importing those types.
   */
  private String reflectGetter(Object obj, String getterName) {
    if (obj == null) {
      return "";
    }
    try {
      Object value = obj.getClass().getMethod(getterName).invoke(obj);
      return value != null ? value.toString() : "";
    } catch (NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException ex) {
      LOGGER.error(
          "AuditInterceptor - Unable to reflectively invoke {} on {} with exception: {}.",
          getterName,
          obj.getClass(),
          ex.toString());
      return "";
    }
  }

  private String getTeamAuditIdFromName(String name) {
    if (teamNameToAuditId.containsKey(name)) {
      return teamNameToAuditId.get(name);
    }
    Optional<AuditEntity> optAuditEntity =
        auditRepository.findFirstByScopeAndSelfName(AuditScope.WORKSPACE, name);
    if (optAuditEntity.isPresent()) {
      teamNameToAuditId.put(name, optAuditEntity.get().getId());
      return optAuditEntity.get().getId();
    }
    //    AuditEntity log = createLog(AuditScope.WORKSPACE, "", Optional.of(name), Optional.empty(),
    // Optional.of(Map.of("name", name)));
    //    teamNameToAuditId.put(name, log.getId());
    //    return log.getId();
    return "";
  }
}
