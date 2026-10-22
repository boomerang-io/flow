package io.boomerang.workflow;

import io.boomerang.client.EngineClient;
import io.boomerang.client.TaskResponsePage;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.User;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.security.IdentityService;
import io.boomerang.workflow.tekton.TektonConverter;
import io.boomerang.workflow.tekton.TektonTask;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;

/*
 * This service replicates the required calls for Engine TaskTemplateV1 APIs
 *
 * - Checks Relationships
 * - Determines if to add or remove elements
 * - Forward call onto Engine (converts slug to ID)
 * - Converts response as needed for UI (including converting ID to Slug)
 */
@Service
public class TaskService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final String NAME_REGEX = "^([0-9a-zA-Z\\-]+)$";

  private final EngineClient engineClient;
  private final RelationshipService relationshipService;
  private final IdentityService identityService;
  private final UserService userService;

  public TaskService(
      EngineClient engineClient,
      RelationshipService relationshipService,
      IdentityService identityService,
      UserService userService) {
    this.engineClient = engineClient;
    this.relationshipService = relationshipService;
    this.identityService = identityService;
    this.userService = userService;
  }

  /*
   * Retrieve a TEAMTASK by team, name and optional version. If no version specified, will retrieve the latest.
   */
  public Task get(String team, String name, Optional<Integer> version) {
    // Checks principal and provided Task has relationship to Team.
    if (!Objects.isNull(name) && !name.isBlank()) {
      List<String> taskRefs =
          relationshipService.filter(
              RelationshipType.TEAMTASK,
              Optional.of(List.of(name)),
              Optional.of(RelationshipType.TEAM),
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
  public Task get(String name, Optional<Integer> version) {
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
    Task taskTemplate = engineClient.getTask(id, version);

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
            Optional.of(RelationshipType.TEAM),
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
  public TaskResponsePage query(
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
    TaskResponsePage response =
        engineClient.queryTask(
            queryLimit, queryPage, querySort, queryLabels, queryStatus, queryRefs);

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
        RelationshipType.TEAM, team, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }

    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Check Slugs for Tasks in team
    if (relationshipService.check(
        RelationshipType.TEAMTASK,
        request.getName(),
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      throw new BoomerangException(BoomerangError.TASK_ALREADY_EXISTS, request.getName());
    }

    // Create Task
    Task task = internalCreate(request);

    // Create Relationship
    relationshipService.createNodeAndEdge(
        RelationshipType.TEAM,
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

  public Task create(Task request) {
    // Check name matches the requirements
    if (request.getName().isBlank() || !request.getName().matches(NAME_REGEX)) {
      throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, request.getName());
    }

    // Check Slugs for GlobalTasks
    if (relationshipService.check(
        RelationshipType.TASK, request.getName(), Optional.empty(), Optional.empty())) {
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

  private Task internalCreate(Task request) {
    // Ignore any provided Ids as this is a create
    request.setId(null);
    // Set verified to false - this is only able to be set via Engine or Loader
    request.setVerified(false);

    // Update Changelog
    updateChangeLog(request.getChangelog());

    // Come back to this once we have separated the controllers - works better for scope checks.
    Task taskTemplate = engineClient.createTask(request);
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
            Optional.of(RelationshipType.TEAM),
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

  public Task apply(String name, Task request, boolean replace) {
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
      return this.create(request);
    }
  }

  private Task internalApply(Task request, boolean replace) {
    // Set verfied to false - this is only able to be set via Engine or Loader
    request.setVerified(false);

    // Update Changelog
    updateChangeLog(request.getChangelog());

    Task template = engineClient.applyTask(request, replace);
    switchChangeLogAuthorToUserName(template.getChangelog());

    return template;
  }

  // Override changelog date and set author. Used on creation/update of TaskTemplate
  private void updateChangeLog(ChangeLog changelog) {
    if (changelog == null) {
      changelog = new ChangeLog();
    }
    changelog.setDate(new Date());
    if (identityService.getCurrentIdentity().getPrincipal() != null) {
      changelog.setAuthor(identityService.getCurrentIdentity().getPrincipal());
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
      } else {
        changelog.setAuthor("---");
      }
    }
  }

  public TektonTask getAsTekton(String team, String name, Optional<Integer> version) {
    Task template = this.get(team, name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask getAsTekton(String name, Optional<Integer> version) {
    Task template = this.get(name, version);
    return TektonConverter.convertTaskTemplateToTektonTask(template);
  }

  public TektonTask createAsTekton(String team, TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.create(team, template);
    return tektonTask;
  }

  public TektonTask createAsTekton(TektonTask tektonTask) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.create(template);
    return tektonTask;
  }

  public TektonTask applyAsTekton(
      String name, String team, TektonTask tektonTask, boolean replace) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.apply(name, team, template, replace);
    return tektonTask;
  }

  public TektonTask applyAsTekton(String name, TektonTask tektonTask, boolean replace) {
    Task template = TektonConverter.convertTektonTaskToTaskTemplate(tektonTask);
    this.apply(name, template, replace);
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
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      return internalChangelog(refs.get(0));
    }
    // TODO - change error to don't have access
    throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
  }

  public List<ChangeLogVersion> changelog(String name) {
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
    List<ChangeLogVersion> changeLog = engineClient.getTaskChangeLog(id);
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
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)),
            false);
    if (!refs.isEmpty()) {
      engineClient.deleteTask(refs.get(0));
    }
    // TODO - change error to don't have access
    throw new BoomerangException(BoomerangError.TASK_INVALID_NAME, name);
  }
}
