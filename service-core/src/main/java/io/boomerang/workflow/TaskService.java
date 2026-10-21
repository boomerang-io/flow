package io.boomerang.workflow;

import io.boomerang.common.util.PagingUtil;
import io.boomerang.common.util.QueryCriteria;
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
import io.boomerang.common.model.TaskSpec;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.audit.SecretChangeHolder;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.Token;
import io.boomerang.core.model.User;
import io.boomerang.core.security.IdentityService;
import io.boomerang.core.security.UnauthenticatedGlobalToken;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.tekton.TektonConverter;
import io.boomerang.workflow.tekton.TektonTask;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.util.DataAdapterUtil.FieldType;
import io.boomerang.common.util.ParameterUtil;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
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
 *   <li><b>Workspace-scoped</b> ({@code get(workspace, name, ...)}, {@code create(workspace, ...)}, ...) - the
 *       {@code /api/v2/workspace/&#123;workspace&#125;/task} surface. Each one resolves the slug
 *       through {@link RelationshipService#filter} anchored on {@code WORKSPACETASK} within the named
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
 * boundary that only existed because the engine used to be a separate deployable.
 */
@Service
public class TaskService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final String CHANGELOG_INITIAL = "Initial Task Template";
  private static final String CHANGELOG_UPDATE = "Updated Task Template";
  private static final String NAME_REGEX = ResourceName.REGEX;
  private static final String ANNOTATION_GENERATION = "4";
  private static final String ANNOTATION_KIND = "Task";

  /** Joins a workspace and a task slug in a task's slug form: {@code <workspace>/<slug>}. */
  public static final String SLUG_SEPARATOR = "/";

  // A task's name is immutable, so a resolved slug form stays true until the task is deleted.
  private static final Duration SLUG_TTL = Duration.ofSeconds(30);

  private final Cache<String, String> slugByRef =
      Caffeine.newBuilder().expireAfterWrite(SLUG_TTL).maximumSize(5000).build();

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
  // A catalogue task is either a workspace's own (a WORKSPACETASK node under the workspace) or global (a
  // TASK node under root). Each public pair below resolves the name in its scope and shares one body.

  // The scope a name resolves in: a workspace, or global when the workspace is null.
  private record Scope(String workspace) {
    static final Scope GLOBAL = new Scope(null);

    RelationshipType nodeType() {
      return (workspace != null) ? RelationshipType.WORKSPACETASK : RelationshipType.TASK;
    }
  }

  // The refs of the scope's tasks with the given names, or of all of them.
  private List<String> refsIn(Scope scope, Optional<List<String>> names) {
    return (scope.workspace() != null)
        ? relationshipService.refsInWorkspace(RelationshipType.WORKSPACETASK, scope.workspace(), names)
        : relationshipService.filter(
            RelationshipType.TASK, names, Optional.empty(), Optional.empty(), false);
  }

  /*
   * Retrieve a workspace task by workspace, name and optional version. If no version specified, will retrieve the latest.
   */
  public Task get(String workspace, String name, Optional<Integer> version) {
    return getIn(new Scope(workspace), name, version);
  }

  /*
   * Retrieve a TASK by name and optional version. If no version specified, will retrieve the latest.
   */
  public Task getGlobal(String name, Optional<Integer> version) {
    return getIn(Scope.GLOBAL, name, version);
  }

  private Task getIn(Scope scope, String name, Optional<Integer> version) {
    if (!Objects.isNull(name) && !name.isBlank()) {
      List<String> taskRefs = refsIn(scope, Optional.of(List.of(name)));
      if (!taskRefs.isEmpty()) {
        // Assumes there is only one task of that slug in the scope
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

    return filterSensitiveValues(taskTemplate);
  }

  /*
   * Every read of a catalogue task through the /api/v2 surface - get, query, the Tekton YAML reads
   * and the create and apply responses - shows a password-typed param's default as the redaction
   * marker when set and as nothing when unset. The unscoped reads the engine and the workflow save use (get(ref, version), getSpecs,
   * retrieveAndValidateTask) are not filtered: a task run needs the real default. The spec is
   * copied rather than changed, as the model shares it with the revision entity.
   */
  private static Task filterSensitiveValues(Task task) {
    if (task != null && task.getSpec() != null) {
      TaskSpec spec = new TaskSpec();
      BeanUtils.copyProperties(task.getSpec(), spec);
      spec.setParams(
          DataAdapterUtil.filterParamSpecValueByFieldType(
              task.getSpec().getParams(), FieldType.PASSWORD.value(), DataAdapterUtil.REDACTED));
      task.setSpec(spec);
    }
    return task;
  }

  /*
   * Query for a workspace's tasks.
   */
  public TaskResponsePage query(
      String queryTeam,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames) {
    return queryIn(
        new Scope(queryTeam), queryLimit, queryPage, querySort, queryLabels, queryStatus, queryNames);
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
    return queryIn(
        Scope.GLOBAL, queryLimit, queryPage, querySort, queryLabels, queryStatus, queryNames);
  }

  private TaskResponsePage queryIn(
      Scope scope,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> querySort,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryNames) {
    List<String> refs = refsIn(scope, queryNames);
    LOGGER.debug("Task Refs: {}", refs.toString());
    if (refs.isEmpty()) {
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
                filterSensitiveValues(t);
              });
    }
    return response;
  }

  /*
   * Creates the Task and Relationship
   */
  public Task create(String workspace, Task request) {
    // Validate Access
    if (!relationshipService.check(
        RelationshipType.WORKSPACE, workspace, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
    return createIn(new Scope(workspace), request);
  }

  public Task createGlobal(Task request) {
    return createIn(Scope.GLOBAL, request);
  }

  private Task createIn(Scope scope, Task request) {
    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Check the slug is free in the scope
    if (!refsIn(scope, Optional.of(List.of(request.getName()))).isEmpty()) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Create Task
    Task task = internalCreate(request);

    // Create Relationship: under the workspace, or under root for a global task
    relationshipService.createNodeAndEdge(
        (scope.workspace() != null) ? RelationshipType.WORKSPACE : RelationshipType.ROOT,
        (scope.workspace() != null) ? scope.workspace() : "root",
        RelationshipLabel.HAS_TASK,
        scope.nodeType(),
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
    // Set verified to false - only the migrations' catalogue seeds set it
    request.setVerified(false);

    // Update Changelog
    stampChangeLog(request.getChangelog());

    // Come back to this once we have separated the controllers - works better for scope checks.
    Task taskTemplate = create(request);
    switchChangeLogAuthorToUserName(taskTemplate.getChangelog());

    return filterSensitiveValues(taskTemplate);
  }

  /*
   * Apply allows you to create a new version as well as create new
   *
   * Names are akin to a slug and are immutable. If the name changes, a new TaskTemplate is created
   *
   */
  public Task apply(String name, String workspace, Task request, boolean replace) {
    return applyIn(new Scope(workspace), name, request, replace);
  }

  public Task applyGlobal(String name, Task request, boolean replace) {
    LOGGER.debug("Applying Task: {}", name);
    return applyIn(Scope.GLOBAL, name, request, replace);
  }

  private Task applyIn(Scope scope, String name, Task request, boolean replace) {
    if (name.isBlank() || !name.matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }
    List<String> refs = refsIn(scope, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      request.setId(refs.get(0));
      // Name is immutable
      request.setName(name);
      Task task = this.internalApply(request, replace);

      // Remove ID
      task.setId(null);
      return task;
    }
    return (scope.workspace() != null)
        ? this.create(scope.workspace(), request)
        : this.createGlobal(request);
  }

  private Task internalApply(Task request, boolean replace) {
    // Set verified to false - only the migrations' catalogue seeds set it
    request.setVerified(false);

    // Update Changelog
    stampChangeLog(request.getChangelog());

    Task template = apply(request, replace);
    switchChangeLogAuthorToUserName(template.getChangelog());

    return filterSensitiveValues(template);
  }

  // Override changelog date and set author. Used on creation/update of TaskTemplate.
  // Named apart from the two-argument updateChangeLog below, which copies a REQUEST's changelog
  // fields onto a new revision's - a different job that happens to share the old name.
  private void stampChangeLog(ChangeLog changelog) {
    if (changelog == null) {
      changelog = new ChangeLog();
    }
    changelog.setDate(new Date());
    // No identity or principal leaves the author unset. The security-off synthetic token is
    // treated the same: its name ("system") must not appear as a changelog author - it would read
    // as the platform itself making the change.
    Token identity = identityService.getCurrentIdentity();
    if (identity == null
        || identity.getPrincipal() == null
        || identity instanceof UnauthenticatedGlobalToken) {
      return;
    }
    // A user or session principal is a user id and is resolved to the user's name on read. Any
    // other token's principal (key = workspace ref, global = varies) is NOT a user id, so record
    // the token's own name; a token without a name leaves the author unset rather than inventing
    // one from its scope.
    if (AuthScope.user.equals(identity.getType()) || AuthScope.session.equals(identity.getType())) {
      changelog.setAuthor(identity.getPrincipal());
    } else if (identity.getName() != null && !identity.getName().isBlank()) {
      changelog.setAuthor(identity.getName());
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

  public TektonTask getAsTekton(String workspace, String name, Optional<Integer> version) {
    Task template = this.get(workspace, name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask getAsTektonGlobal(String name, Optional<Integer> version) {
    Task template = this.getGlobal(name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask createAsTekton(String workspace, TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.create(workspace, template);
    return tektonTask;
  }

  public TektonTask createAsTektonGlobal(TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.createGlobal(template);
    return tektonTask;
  }

  public TektonTask applyAsTekton(
      String name, String workspace, TektonTask tektonTask, boolean replace) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.apply(name, workspace, template, replace);
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

  public List<ChangeLogVersion> changelog(String workspace, String name) {
    if (name.isBlank() || !name.matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
    }
    return changelogIn(new Scope(workspace), name);
  }

  public List<ChangeLogVersion> changelogGlobal(String name) {
    return changelogIn(Scope.GLOBAL, name);
  }

  private List<ChangeLogVersion> changelogIn(Scope scope, String name) {
    List<String> refs = refsIn(scope, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      return internalChangelog(refs.get(0));
    }
    throw new BoomerangException(BoomerangError.TASK_INVALID_REF, name, "latest");
  }

  private List<ChangeLogVersion> internalChangelog(String id) {
    List<ChangeLogVersion> changeLog = changelog(id);
    changeLog.forEach(clv -> switchChangeLogAuthorToUserName(clv));
    return changeLog;
  }

  /*
   * Deletes a workspace task - the workspace is required as you cannot delete a global template (only make
   * inactive)
   */
  public void delete(String workspace, String name) {
    deleteIn(new Scope(workspace), name);
  }

  /*
   * Deletes a global Task. Same shape as the workspace-scoped delete above - the name is resolved
   * through the caller's own relationship and the shared delete refuses while a run still
   * references it - so the catalogue is manageable from the global surface rather than only by
   * making a Task inactive.
   */
  public void deleteGlobal(String name) {
    deleteIn(Scope.GLOBAL, name);
  }

  private void deleteIn(Scope scope, String name) {
    if (Objects.isNull(name) || name.isBlank()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REQ);
    }
    List<String> refs = refsIn(scope, Optional.of(List.of(name)));
    if (!refs.isEmpty()) {
      delete(refs.get(0));
      // Mirrors the node create writes - the Task is gone, so its node and the edge to it go with
      // it rather than dangling in the relationship graph.
      relationshipService.removeNodeAndEdgeByRef(scope.nodeType(), refs.get(0));
      return;
    }
    throw new BoomerangException(BoomerangError.TASK_INVALID_REF, name, "latest");
  }

  // ── Unscoped operations (engine, workflow-definition and template callers) ─

  public Task get(String ref, Optional<Integer> version) {
    Optional<TaskEntity> taskEntity = taskRepository.findById(ref);
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

  // ── The slug form of a task reference ──────────────────────────────────────
  //
  // A stored reference is the task's id. On the API and the dispatcher protocol a reference is
  // the task's slug form: `<slug>` for a global task, `<workspace>/<slug>` for a workspace task.

  /**
   * Return the slug form of each task id, keyed by id. An id no catalogue task carries is absent.
   * At most three queries however many ids are given, and a resolved form is cached, so a run
   * response or a claim batch costs nothing once warm.
   */
  public Map<String, String> slugsByRef(Collection<String> taskRefs) {
    Map<String, String> slugs = new HashMap<>();
    Set<String> missing = new HashSet<>();
    for (String ref : taskRefs) {
      String cached = (ref != null) ? slugByRef.getIfPresent(ref) : null;
      if (cached != null) {
        slugs.put(ref, cached);
      } else if (ref != null) {
        missing.add(ref);
      }
    }
    if (missing.isEmpty()) {
      return slugs;
    }
    Map<String, RelationshipNodeEntity> nodes =
        relationshipService.nodesByRef(missing, RelationshipType.TASK, RelationshipType.WORKSPACETASK);
    Map<String, RelationshipNodeEntity> workspaces =
        relationshipService.parentsByLabel(
            RelationshipLabel.HAS_TASK,
            nodes.values().stream()
                .filter(node -> RelationshipType.WORKSPACETASK.getLabel().equals(node.getType()))
                .map(RelationshipNodeEntity::getId)
                .toList());
    nodes.forEach(
        (ref, node) -> {
          if (RelationshipType.TASK.getLabel().equals(node.getType())) {
            slugs.put(ref, node.getSlug());
          } else if (workspaces.containsKey(node.getId())) {
            slugs.put(ref, workspaces.get(node.getId()).getSlug() + SLUG_SEPARATOR + node.getSlug());
          }
        });
    missing.forEach(
        ref -> {
          if (slugs.containsKey(ref)) {
            slugByRef.put(ref, slugs.get(ref));
          }
        });
    return slugs;
  }

  /**
   * Return the task id for each slug form, keyed as given, for a request scoped to {@code
   * workspace}: a bare slug names a global task, {@code <workspace>/<slug>} names that workspace's
   * task. A slug naming another workspace, or one no task carries, is absent.
   */
  public Map<String, String> refsBySlug(String workspace, Collection<String> slugs) {
    Map<String, String> refs = new HashMap<>();
    Set<String> global = new HashSet<>();
    Set<String> scoped = new HashSet<>();
    for (String slug : slugs) {
      if (slug != null && !slug.isBlank()) {
        (slug.contains(SLUG_SEPARATOR) ? scoped : global).add(slug);
      }
    }
    if (!global.isEmpty()) {
      relationshipService
          .nodesBySlug(RelationshipType.TASK, global)
          .forEach((slug, node) -> refs.put(slug, node.getRef()));
    }
    if (!scoped.isEmpty() && workspace != null) {
      String prefix = workspace + SLUG_SEPARATOR;
      Map<String, String> refByWorkspaceSlug = new HashMap<>();
      relationshipService
          .childrenByLabel(RelationshipType.WORKSPACE, workspace, RelationshipLabel.HAS_TASK)
          .forEach(node -> refByWorkspaceSlug.put(node.getSlug(), node.getRef()));
      for (String slug : scoped) {
        String ref =
            slug.startsWith(prefix) ? refByWorkspaceSlug.get(slug.substring(prefix.length())) : null;
        if (ref != null) {
          refs.put(slug, ref);
        }
      }
    }
    return refs;
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

    // Set Display Name if not provided
    if (request.getDisplayName() == null || request.getDisplayName().isBlank()) {
      request.setDisplayName(request.getName());
    }

    // Set System Generated Annotations
    request.getAnnotations().put("boomerang.io/generation", ANNOTATION_GENERATION);
    request.getAnnotations().put("boomerang.io/kind", ANNOTATION_KIND);

    // A hidden default sent back with nothing stored to keep (an import of an exported task) is no
    // value, never the marker.
    if (request.getSpec() != null) {
      DataAdapterUtil.keepParamSpecValueByFieldType(
          request.getSpec().getParams(), List.of(), FieldType.PASSWORD.value());
      SecretChangeHolder.compare(
          Map.of(),
          SecretChangeHolder.secretValues(
              "", request.getSpec().getParams(), FieldType.PASSWORD.value()));
    }

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

    // The scoped and global applies resolve the name through the relationship graph and pass the id.
    if (request.getId() == null || request.getId().isBlank()) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_REQ, request.getName(), "latest");
    }
    Optional<TaskEntity> taskOpt = taskRepository.findById(request.getId());
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

    // The one secret write rule: a password default sent back as the marker, or not at all, keeps
    // the stored one; a blank clears it; anything else replaces it.
    if (request.getSpec() != null && taskRevisionEntity.get().getSpec() != null) {
      DataAdapterUtil.keepParamSpecValueByFieldType(
          request.getSpec().getParams(),
          taskRevisionEntity.get().getSpec().getParams(),
          FieldType.PASSWORD.value());
      SecretChangeHolder.compare(
          SecretChangeHolder.secretValues(
              "", taskRevisionEntity.get().getSpec().getParams(), FieldType.PASSWORD.value()),
          SecretChangeHolder.secretValues(
              "", request.getSpec().getParams(), FieldType.PASSWORD.value()));
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
    final Sort sort = Sort.by(new Order(querySort.orElse(Direction.ASC), "creationDate"));
    Query query =
        new Query(
            new QueryCriteria()
                .labels(queryLabels)
                .in("status", TaskStatus.class, queryStatus, "status")
                .in("name", queryNames)
                .in("_id", queryIds.map(ids -> ids.stream().map(ObjectId::new).toList()))
                .build());
    Pageable pageable = PagingUtil.apply(query, queryLimit, queryPage, sort);

    List<TaskEntity> taskEntities = mongoTemplate.find(query, TaskEntity.class);

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
        PagingUtil.page(tasks, pageable, mongoTemplate, query, TaskEntity.class);

    return pages;
  }

  /*
   * Retrieve all the changelogs and return by version
   */
  public List<ChangeLogVersion> changelog(String ref) {
    Task task = this.get(ref, Optional.empty());
    List<TaskRevisionEntity> taskRevisionEntities =
        taskRevisionRepository.findByParentRefOrderByVersionAsc(task.getId());
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

  /**
   * A catalogue task exactly as a TaskRun records it: {@code taskRef} plus the version resolved at
   * creation ({@code TaskRunEntity.taskRef/taskVersion}).
   */
  public record TaskRef(String ref, Integer version) {}

  /**
   * The specs of the given catalogue task references, resolved in TWO queries however many
   * references are asked for - one for the tasks, one for their revisions - so a run response with
   * many tasks never costs one lookup per task. A reference is the task's id, as {@link
   * #get(String, Optional)} reads it; one the catalogue no longer holds is simply absent from the
   * result.
   *
   * <p>A reference carrying no version is skipped - it cannot be joined in a batch, and {@code
   * DAGUtility.createTaskList} stamps the resolved version beside {@code taskRef} on every TaskRun
   * it creates, so one does not occur.
   */
  public Map<TaskRef, TaskSpec> getSpecs(Collection<TaskRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }
    Set<TaskRef> wanted =
        refs.stream()
            .filter(r -> r != null && r.ref() != null && r.version() != null)
            .collect(Collectors.toSet());
    if (wanted.isEmpty()) {
      return Map.of();
    }
    // The revisions hang off the TaskEntity id, which is the taskRef itself, and are deleted before
    // their task (delete), so a revision found here belongs to a task that exists.
    Set<String> taskRefs = wanted.stream().map(TaskRef::ref).collect(Collectors.toSet());
    Map<String, TaskRevisionEntity> revisions =
        taskRevisionRepository
            .findByParentRefInAndVersionIn(
                taskRefs, wanted.stream().map(TaskRef::version).collect(Collectors.toSet()))
            .stream()
            .collect(
                Collectors.toMap(
                    r -> revisionKey(r.getParentRef(), r.getVersion()), r -> r, (a, b) -> a));
    Map<TaskRef, TaskSpec> specs = new HashMap<>();
    for (TaskRef ref : wanted) {
      TaskRevisionEntity revision = revisions.get(revisionKey(ref.ref(), ref.version()));
      if (revision != null && revision.getSpec() != null) {
        specs.put(ref, revision.getSpec());
      }
    }
    return specs;
  }

  private static String revisionKey(String parentRef, Integer version) {
    return parentRef + "@" + version;
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
