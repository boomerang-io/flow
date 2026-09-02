package io.boomerang.workflow;

import io.boomerang.common.validation.ResourceName;
import io.boomerang.common.model.TaskResponsePage;
import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRevisionEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.TaskStatus;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.User;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.tekton.TektonConverter;
import io.boomerang.workflow.tekton.TektonTask;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import io.boomerang.common.util.ParameterUtil;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

/**
 * The Task (template) domain service - one service for every Task operation the product performs.
 *
 * <p>Tasks are stored in a main TaskEntity with fields that have limited change scope and a
 * TaskRevisionEntity that holds the versioned elements. It utilises a {@code @DocumentReference}
 * for the parent field that allows us to retrieve the TaskEntity from within the TaskRevisionEntity
 * when reading.
 *
 * <p>It has three entry shapes over the same operations:
 *
 * <ul>
 *   <li><b>Workspace-scoped</b> ({@code get(team, name, ...)}, {@code create(team, ...)}, ...) - the
 *       {@code /api/v2/workspace/&#123;workspace&#125;/task} surface. Each one resolves the slug
 *       through {@link RelationshipService#filter} anchored on {@code TEAMTASK} within the named
 *       workspace, so an unreachable Task is indistinguishable from a missing one.
 *   <li><b>Global-scoped</b> ({@code getGlobal}, {@code createGlobal}, ...) - the {@code
 *       /api/v2/task} catalogue surface. Same shape, but the filter anchors on {@code TASK} with no
 *       workspace narrowing, which is what the global catalogue means. These carry the {@code
 *       Global} suffix because they take the same argument list as the unscoped operations below
 *       and could not otherwise be told apart.
 *   <li><b>Unscoped</b> ({@code get(ref, version)}, {@code create(request)}, {@code
 *       retrieveAndValidateTask}, ...) - internal callers that carry no workspace and are
 *       authorized elsewhere or not at all: {@code engine.DAGUtility}, {@link WorkflowService} and
 *       {@link WorkflowTemplateService} resolving a workflow's task references.
 * </ul>
 *
 * <p>F3 collapsed the former {@code api.WorkspaceTaskService} pass-through into this class: the
 * slug resolution and the operation now live together instead of being split across a service
 * boundary that only existed because the engine used to be a separate deployable (DD-02).
 */
@Service
public class TaskService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final String CHANGELOG_INITIAL = "Initial Task Template";
  private static final String CHANGELOG_UPDATE = "Updated Task Template";
  private static final String NAME_REGEX = ResourceName.REGEX;
  private static final String ANNOTATION_GENERATION = "4";
  private static final String ANNOTATION_KIND = "Task";

  @Value("${flow.uniquenames.enabled}")
  private boolean uniqueNamesEnabled;

  private final TaskRepository taskRepository;
  private final TaskRevisionRepository taskRevisionRepository;
  private final TaskRunRepository taskRunRepository;
  private final MongoTemplate mongoTemplate;
  private final RelationshipService relationshipService;
  private final IdentityService identityService;
  private final UserService userService;

  public TaskService(
      TaskRepository taskRepository,
      TaskRevisionRepository taskRevisionRepository,
      TaskRunRepository taskRunRepository,
      MongoTemplate mongoTemplate,
      RelationshipService relationshipService,
      IdentityService identityService,
      UserService userService) {
    this.taskRepository = taskRepository;
    this.taskRevisionRepository = taskRevisionRepository;
    this.taskRunRepository = taskRunRepository;
    this.mongoTemplate = mongoTemplate;
    this.relationshipService = relationshipService;
    this.identityService = identityService;
    this.userService = userService;
  }

  // ── Workspace- and global-scoped operations (the /api/v2 surface) ──────────
  //
  // Every method here performs the SAME relationship call the deleted
  // api.WorkspaceTaskService performed, then does the work inline.

  /*
   * Retrieve a TEAMTASK by team, name and optional version. If no version specified, will retrieve the latest.
   */
  public Task get(String team, String name, Optional<Integer> version) {
    // Checks principal and provided Task has relationship to Workspace.
    if (!Objects.isNull(name) && !name.isBlank()) {
      List<String> taskRefs =
          relationshipService.filter(
              RelationshipType.TEAMTASK,
              Optional.of(List.of(name)),
              Optional.of(RelationshipType.WORKSPACE),
              Optional.of(List.of(team)),
              false);
      if (!taskRefs.isEmpty()) {
        // Assumes there is only one task of that slug in a team
        return internalGet(taskRefs.get(0), version);
      }
    }
    throw new BoomerangException(
        BoomerangError.TASK_INVALID_REF, name, version.isPresent() ? version.get() : "latest");
  }

  /*
   * Retrieve a TASK by name and optional version. If no version specified, will retrieve the latest.
   */
  public Task getGlobal(String name, Optional<Integer> version) {
    if (!Objects.isNull(name) && !name.isBlank()) {
      List<String> taskRefs =
          relationshipService.filter(
              RelationshipType.TASK,
              Optional.of(List.of(name)),
              Optional.empty(),
              Optional.empty(),
              false);
      if (!taskRefs.isEmpty()) {
        // Assumes there is only one task of that slug in a team
        return internalGet(taskRefs.get(0), version);
      }
    }
    throw new BoomerangException(
        BoomerangError.TASK_INVALID_REF, name, version.isPresent() ? version.get() : "latest");
  }

  private Task internalGet(String id, Optional<Integer> version) {
    Task taskTemplate = get(id, version);

    // Switch author from ID to Name
    switchChangeLogAuthorToUserName(taskTemplate.getChangelog());

    // Remove ID
    taskTemplate.setId(null);

    return taskTemplate;
  }

  /*
   * Query for TEAMTASKS.
   */
  public TaskResponsePage query(
      String queryTeam,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames) {

    // Check for relationship
    List<String> refs =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            queryNames,
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(queryTeam)),
            false);
    LOGGER.debug("Task Refs: {}", refs.toString());
    if (refs == null || refs.size() == 0) {
      return new TaskResponsePage();
    }
    return internalQuery(queryLimit, queryPage, querySort, queryLabels, queryStatus, refs);
  }

  /*
   * Query for TASKS.
   */
  public TaskResponsePage queryGlobal(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames) {

    List<String> refs =
        relationshipService.filter(
            RelationshipType.TASK, queryNames, Optional.empty(), Optional.empty(), false);
    LOGGER.debug("Global Task Refs: {}", refs.toString());
    if (refs == null || refs.size() == 0) {
      return new TaskResponsePage();
    }
    return internalQuery(queryLimit, queryPage, querySort, queryLabels, queryStatus, refs);
  }

  private TaskResponsePage internalQuery(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      List<String> queryRefs) {
    // The old URL builder always appended an "ids" query param (even for an empty list), so the
    // Engine's queryIds Optional was always present - preserved here as Optional.of(queryRefs)
    // rather than being conditioned on emptiness.
    Page<Task> page =
        query(
            queryLimit,
            queryPage,
            querySort,
            queryLabels,
            queryStatus,
            Optional.empty(),
            Optional.of(queryRefs));
    TaskResponsePage response =
        new TaskResponsePage(page.getContent(), page.getPageable(), page.getTotalElements());

    if (!response.getContent().isEmpty()) {
      response
          .getContent()
          .forEach(
              t -> {
                switchChangeLogAuthorToUserName(t.getChangelog());
                // Remove ID
                t.setId(null);
              });
    }
    return response;
  }

  /*
   * Creates the Task and Relationship
   */
  public Task create(String team, Task request) {
    // Validate Access
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }

    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Check Slugs for Tasks in team
    List<String> existingTeamTaskRefs =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            Optional.of(List.of(request.getName())),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!existingTeamTaskRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Create Task
    Task task = internalCreate(request);

    // Create Relationship
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        RelationshipLabel.HAS_TASK,
        RelationshipType.TEAMTASK,
        task.getId(),
        task.getName(),
        Optional.empty(),
        Optional.empty());

    // Remove ID
    task.setId(null);
    return task;
  }

  public Task createGlobal(Task request) {
    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Check Slugs for GlobalTasks
    List<String> existingTaskRefs =
        relationshipService.filter(
            RelationshipType.TASK,
            Optional.of(List.of(request.getName())),
            Optional.empty(),
            Optional.empty(),
            false);
    if (!existingTaskRefs.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Create Task
    Task task = internalCreate(request);

    // Create Relationship
    relationshipService.createNodeAndEdge(
        RelationshipType.ROOT,
        "root",
        RelationshipLabel.HAS_TASK,
        RelationshipType.TASK,
        task.getId(),
        task.getName(),
        Optional.empty(),
        Optional.empty());

    // Remove ID
    task.setId(null);
    return task;
  }

  /*
   * Declared param names must not be case/separator variants of each other: param matching is
   * case-insensitive (ruled 2026-08-26) and every param becomes a PARAM_<NAME> env var, so such
   * names collide. Rejected at template save, mirroring WorkflowService.validateDeclaredParams.
   */
  private void validateDeclaredParamNames(Task request) {
    if (request.getSpec() == null || request.getSpec().getParams() == null) {
      return;
    }
    request.getSpec().getParams().stream()
        .map(AbstractParam::getName)
        .filter(name -> !ParameterUtil.isValidParamName(name))
        .findFirst()
        .ifPresent(
            name -> {
              throw new BoomerangException(BoomerangError.PARAM_INVALID_NAME, name);
            });
    List<List<String>> collisions =
        ParameterUtil.paramNameCollisions(
            request.getSpec().getParams().stream()
                .map(AbstractParam::getName)
                .collect(Collectors.toList()));
    if (!collisions.isEmpty()) {
      throw new BoomerangException(
          BoomerangError.PARAM_NAME_COLLISION,
          collisions.toString(),
          ParameterUtil.envFold(collisions.get(0).get(0)));
    }
  }

  private Task internalCreate(Task request) {
    // Ignore any provided Ids as this is a create
    request.setId(null);
    // Set verified to false - this is only able to be set via Engine or Loader
    request.setVerified(false);

    // Update Changelog
    stampChangeLog(request.getChangelog());

    // Come back to this once we have separated the controllers - works better for scope checks.
    Task taskTemplate = create(request);
    switchChangeLogAuthorToUserName(taskTemplate.getChangelog());

    return taskTemplate;
  }

  /*
   * Apply allows you to create a new version as well as create new
   *
   * Names are akin to a slug and are immutable. If the name changes, a new TaskTemplate is created
   *
   */
  public Task apply(String name, String team, Task request, boolean replace) {
    if (name.isBlank() || !name.matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    List<String> refs =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      request.setId(refs.get(0));
      // Name is immutable
      request.setName(name);
      Task task = this.internalApply(request, replace);

      // Remove ID
      task.setId(null);
      return task;
    } else {
      return this.create(team, request);
    }
  }

  public Task applyGlobal(String name, Task request, boolean replace) {
    LOGGER.debug("Applying Task: {}", request.toString());
    if (name.isBlank() || !name.matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }
    List<String> refs =
        relationshipService.filter(
            RelationshipType.TASK,
            Optional.of(List.of(name)),
            Optional.empty(),
            Optional.empty(),
            false);
    if (!refs.isEmpty()) {
      request.setId(refs.get(0));
      request.setName(name); // name is immutable
      Task task = this.internalApply(request, replace);

      // Remove ID
      task.setId(null);
      return task;
    } else {
      return this.createGlobal(request);
    }
  }

  private Task internalApply(Task request, boolean replace) {
    // Set verfied to false - this is only able to be set via Engine or Loader
    request.setVerified(false);

    // Update Changelog
    stampChangeLog(request.getChangelog());

    Task template = apply(request, replace);
    switchChangeLogAuthorToUserName(template.getChangelog());

    return template;
  }

  // Override changelog date and set author. Used on creation/update of TaskTemplate.
  // Named apart from the two-argument updateChangeLog below, which copies a REQUEST's changelog
  // fields onto a new revision's - a different job that happens to share the old name.
  private void stampChangeLog(ChangeLog changelog) {
    if (changelog == null) {
      changelog = new ChangeLog();
    }
    changelog.setDate(new Date());
    // No identity or principal (e.g. security disabled) leaves the author unset - same as a
    // resolved identity with no principal string, which this already tolerated.
    Token identity = identityService.getCurrentIdentity();
    if (identity == null || identity.getPrincipal() == null) {
      return;
    }
    // A user or session principal is a user id and is resolved to the user's name on read. Any
    // other token's principal (key = workspace ref, global = varies) is NOT a user id, so record
    // the token's own name - falling back to its scope - rather than an id that a reader would
    // mislabel as a user.
    if (AuthScope.user.equals(identity.getType()) || AuthScope.session.equals(identity.getType())) {
      changelog.setAuthor(identity.getPrincipal());
    } else {
      changelog.setAuthor(
          (identity.getName() != null && !identity.getName().isBlank())
              ? identity.getName()
              : identity.getType().getLabel());
    }
  }

  // TODO - need to make more performant
  private void switchChangeLogAuthorToUserName(ChangeLog changelog) {
    if (changelog != null && changelog.getAuthor() != null) {
      Optional<User> user = userService.getUserByID(changelog.getAuthor());
      if (user.isPresent()) {
        changelog.setAuthor(
            user.get().getDisplayName().isEmpty()
                ? user.get().getName()
                : user.get().getDisplayName());
      }
      // Not a user id: the author is a token's name (or a pre-existing non-user id) stamped by
      // stampChangeLog - keep it as recorded rather than masking it.
    }
  }

  public TektonTask getAsTekton(String team, String name, Optional<Integer> version) {
    Task template = this.get(team, name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask getAsTektonGlobal(String name, Optional<Integer> version) {
    Task template = this.getGlobal(name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask createAsTekton(String team, TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.create(team, template);
    return tektonTask;
  }

  public TektonTask createAsTektonGlobal(TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.createGlobal(template);
    return tektonTask;
  }

  public TektonTask applyAsTekton(
      String name, String team, TektonTask tektonTask, boolean replace) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.apply(name, team, template, replace);
    return tektonTask;
  }

  public TektonTask applyAsTektonGlobal(String name, TektonTask tektonTask, boolean replace) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.applyGlobal(name, template, replace);
    return tektonTask;
  }

  public void validateAsTekton(TektonTask tektonTask) {
    TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
  }

  public List<ChangeLogVersion> changelog(String team, String name) {
    if (name.isBlank() || !name.matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
    }
    List<String> refs =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      return internalChangelog(refs.get(0));
    }
    // TODO - change error to don't have access
    throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
  }

  public List<ChangeLogVersion> changelogGlobal(String name) {
    List<String> refs =
        relationshipService.filter(
            RelationshipType.TASK,
            Optional.of(List.of(name)),
            Optional.empty(),
            Optional.empty(),
            false);
    if (!refs.isEmpty()) {
      return internalChangelog(refs.get(0));
    }
    // TODO - change error to don't have access
    throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
  }

  private List<ChangeLogVersion> internalChangelog(String id) {
    List<ChangeLogVersion> changeLog = changelog(id);
    changeLog.forEach(clv -> switchChangeLogAuthorToUserName(clv));
    return changeLog;
  }

  /*
   * Deletes a TeamTask - team is required as you cannot delete a global template (only make
   * inactive)
   */
  public void delete(String team, String name) {
    if (Objects.isNull(name) || name.isBlank()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF);
    }
    List<String> refs =
        relationshipService.filter(
            RelationshipType.TEAMTASK,
            Optional.of(List.of(name)),
            Optional.of(RelationshipType.WORKSPACE),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      delete(refs.get(0));
      return;
    }
    // TODO - change error to don't have access
    throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
  }

  // ── Unscoped operations (engine, workflow-definition and template callers) ─

  public Task get(String ref, Optional<Integer> version) {
    Optional<TaskEntity> taskEntity =
        uniqueNamesEnabled ? taskRepository.findByName(ref) : taskRepository.findById(ref);
    if (taskEntity.isPresent()) {
      Optional<TaskRevisionEntity> taskRevisionEntity;
      if (version.isPresent()) {
        taskRevisionEntity =
            taskRevisionRepository.findByParentRefAndVersion(
                taskEntity.get().getId(), version.get());
      } else {
        taskRevisionEntity =
            taskRevisionRepository.findByParentRefAndLatestVersion(taskEntity.get().getId());
      }
      if (taskRevisionEntity.isPresent()) {
        return convertEntityToModel(taskEntity.get(), taskRevisionEntity.get());
      }
    }
    throw new BoomerangException(
        BoomerangError.TASK_INVALID_REF, ref, version.isPresent() ? version.get() : "latest");
  }

  /*
   * Create Task
   *
   * TODO additional checks for mandatory fields
   */
  public Task create(Task request) {
    // Remove ID
    request.setId(null);

    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    validateDeclaredParamNames(request);

    // Unique Name Check
    if (uniqueNamesEnabled && taskRepository.existsByName(request.getName().toLowerCase())) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Set Display Name if not provided
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      request.setDisplayName(request.getName());
    }

    // Set System Generated Annotations
    request.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    request.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);

    // Set as initial version
    request.setVersion(1);
    ChangeLog changelog = new ChangeLog(CHANGELOG_INITIAL);
    updateChangeLog(request, changelog);
    request.setChangelog(changelog);

    // Save
    TaskEntity taskTemplateEntity = new TaskEntity(request);
    TaskRevisionEntity taskTemplateRevisionEntity = new TaskRevisionEntity(request);
    taskRepository.save(taskTemplateEntity);
    taskTemplateRevisionEntity.setParentRef(taskTemplateEntity.getId());
    taskRevisionRepository.save(taskTemplateRevisionEntity);

    return convertEntityToModel(taskTemplateEntity, taskTemplateRevisionEntity);
  }

  public Task apply(Task request, boolean replace) {
    // Name Check
    if (!request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    validateDeclaredParamNames(request);

    if (!uniqueNamesEnabled && request.getId().isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, request.getName(), "latest");
    }

    // Does it already exist?
    Optional<TaskEntity> taskOpt =
        uniqueNamesEnabled
            ? taskRepository.findByName(request.getName())
            : taskRepository.findById(request.getId());
    if (taskOpt.isEmpty()) {
      return this.create(request);
    }
    TaskEntity taskEntity = taskOpt.get();

    // Check for active status
    if (TaskStatus.inactive.equals(taskEntity.getStatus())
        && !TaskStatus.active.equals(request.getStatus())) {
      throw new BoomerangException(
          BoomerangError.TASK_INACTIVE_STATUS, request.getName(), "latest");
    }

    // Get latest revision
    Optional<TaskRevisionEntity> taskRevisionEntity =
        taskRevisionRepository.findByParentRefAndLatestVersion(request.getId());
    if (taskRevisionEntity.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, request.getName(), "latest");
    }

    // Update TaskTemplateEntity
    // Set System Generated Annotations
    // Name (slug), Type, Creation Date, and Verified cannot be updated
    if (!request.getName().isBlank()) {
      taskEntity.setName(request.getName());
    }
    if (request.getStatus() != null) {
      taskEntity.setStatus(request.getStatus());
    }
    if (!request.getAnnotations().isEmpty()) {
      taskEntity.getAnnotations().putAll(request.getAnnotations());
    }
    taskEntity.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    taskEntity.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);
    if (!request.getLabels().isEmpty()) {
      taskEntity.getLabels().putAll(request.getLabels());
    }

    // Create / Replace TaskRevisionEntity
    TaskRevisionEntity newTaskRevisionEntity = new TaskRevisionEntity(request);
    if (replace) {
      newTaskRevisionEntity.setId(taskRevisionEntity.get().getId());
      newTaskRevisionEntity.setVersion(taskRevisionEntity.get().getVersion());
    } else {
      newTaskRevisionEntity.setVersion(taskRevisionEntity.get().getVersion() + 1);
    }
    // Set Display Name if not provided
    if (newTaskRevisionEntity.getDisplayName() == null
        || newTaskRevisionEntity.getDisplayName().isBlank()) {
      newTaskRevisionEntity.setDisplayName(request.getName());
    }

    // Update changelog
    ChangeLog changelog =
        new ChangeLog(
            taskRevisionEntity.get().getVersion().equals(1) ? CHANGELOG_INITIAL : CHANGELOG_UPDATE);
    updateChangeLog(request, changelog);
    newTaskRevisionEntity.setChangelog(changelog);

    // Save entities
    TaskEntity savedEntity = taskRepository.save(taskEntity);
    newTaskRevisionEntity.setParentRef(taskEntity.getId());
    TaskRevisionEntity savedRevision = taskRevisionRepository.save(newTaskRevisionEntity);
    return convertEntityToModel(savedEntity, savedRevision);
  }

  private void updateChangeLog(Task taskTemplate, ChangeLog changelog) {
    if (taskTemplate.getChangelog() != null) {
      if (taskTemplate.getChangelog().getAuthor() != null
          && !taskTemplate.getChangelog().getAuthor().isBlank()) {
        changelog.setAuthor(taskTemplate.getChangelog().getAuthor());
      }
      if (taskTemplate.getChangelog().getReason() != null
          && !taskTemplate.getChangelog().getReason().isBlank()) {
        changelog.setReason(taskTemplate.getChangelog().getReason());
      }
      if (taskTemplate.getChangelog().getDate() != null) {
        changelog.setDate(taskTemplate.getChangelog().getDate());
      }
    }
  }

  public Page<Task> query(
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames,
      Optional<List<String>> queryIds) {
    Pageable pageable = Pageable.unpaged();
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    if (queryLimit.isPresent()) {
      pageable = PageRequest.of(queryPage.get(), queryLimit.get(), sort);
    }
    List<Criteria> criteriaList = new ArrayList<>();

    if (queryLabels.isPresent()) {
      queryLabels.get().stream()
          .forEach(
              l -> {
                String decodedLabel = "";
                try {
                  decodedLabel = URLDecoder.decode(l, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                  throw new BoomerangException(e, BoomerangError.QUERY_INVALID_FILTERS, "labels");
                }
                LOGGER.debug(decodedLabel.toString());
                String[] label = decodedLabel.split("[=]+");
                Criteria labelsCriteria =
                    Criteria.where("labels." + label[0].replace(".", "#")).is(label[1]);
                criteriaList.add(labelsCriteria);
              });
    }

    if (queryStatus.isPresent()) {
      if (queryStatus.get().stream()
          .allMatch(q -> EnumUtils.isValidEnumIgnoreCase(TaskStatus.class, q))) {
        Criteria criteria = Criteria.where("status").in(queryStatus.get());
        criteriaList.add(criteria);
      } else {
        throw new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "status");
      }
    }

    if (queryNames.isPresent()) {
      Criteria criteria = Criteria.where("name").in(queryNames.get());
      criteriaList.add(criteria);
    }

    if (queryIds.isPresent()) {
      List<ObjectId> queryOIds =
          queryIds.get().stream().map(ObjectId::new).collect(Collectors.toList());
      Criteria criteria = Criteria.where("_id").in(queryOIds);
      criteriaList.add(criteria);
    }

    Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
    Criteria allCriteria = new Criteria();
    if (criteriaArray.length > 0) {
      allCriteria.andOperator(criteriaArray);
    }
    Query query = new Query(allCriteria);
    if (queryLimit.isPresent()) {
      query.with(pageable);
    } else {
      query.with(sort);
    }

    List<TaskEntity> taskEntities = mongoTemplate.find(query.with(pageable), TaskEntity.class);

    List<Task> tasks = new LinkedList<>();
    taskEntities.forEach(
        e -> {
          LOGGER.debug(e.toString());
          Optional<TaskRevisionEntity> taskRevisionEntity =
              taskRevisionRepository.findByParentRefAndLatestVersion(e.getId());
          if (taskRevisionEntity.isPresent()) {
            Task tt = convertEntityToModel(e, taskRevisionEntity.get());
            tasks.add(tt);
          }
        });

    Page<Task> pages =
        PageableExecutionUtils.getPage(
            tasks,
            pageable,
            () -> mongoTemplate.count(Query.of(query).skip(-1).limit(-1), TaskEntity.class));

    return pages;
  }

  /*
   * Retrieve all the changelogs and return by version
   */
  public List<ChangeLogVersion> changelog(String ref) {
    Task task = this.get(ref, Optional.empty());
    List<TaskRevisionEntity> taskRevisionEntities =
        taskRevisionRepository.findByParentRef(task.getId());
    if (taskRevisionEntities.isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REF, ref, "latest");
    }
    List<ChangeLogVersion> changelogs = new LinkedList<>();
    taskRevisionEntities.forEach(
        v -> {
          ChangeLogVersion cl = new ChangeLogVersion();
          cl.setVersion(v.getVersion());
          if (v.getChangelog() != null) {
            cl.setAuthor(v.getChangelog().getAuthor());
            cl.setReason(v.getChangelog().getReason());
            cl.setDate(v.getChangelog().getDate());
          }
          changelogs.add(cl);
        });
    return changelogs;
  }

  public Task retrieveAndValidateTask(final WorkflowTask wfTask) {
    // Get TaskEntity - this will check valid ref and Version
    Task task = this.get(wfTask.getTaskRef(), Optional.ofNullable(wfTask.getTaskVersion()));

    // Check Task Status
    if (TaskStatus.inactive.equals(task.getStatus())) {
      throw new BoomerangException(
          BoomerangError.TASK_INACTIVE_STATUS, wfTask.getTaskRef(), wfTask.getTaskVersion());
    }
    return task;
  }

  /*
   * Deletes the Task and its revisions. Refuses while any in-flight TaskRun references it —
   * unpinned workflow tasks resolve "latest" at run time and would lose their definition.
   */
  public void delete(String name) {
    if (taskRunRepository.existsByTaskRefAndPhaseIn(
        name, List.of(RunPhase.pending, RunPhase.queued, RunPhase.running))) {
      throw new BoomerangException(BoomerangError.TASK_DELETE_IN_USE);
    }
    taskRevisionRepository.deleteByParentRef(name);
    taskRepository.deleteById(name);
  }

  private Task convertEntityToModel(TaskEntity entity, TaskRevisionEntity revision) {
    Task task = new Task();
    BeanUtils.copyProperties(entity, task);
    BeanUtils.copyProperties(revision, task, "id"); // want to keep the TaskEntity ID
    return task;
  }
}
