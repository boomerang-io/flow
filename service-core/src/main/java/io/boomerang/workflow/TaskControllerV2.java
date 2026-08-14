package io.boomerang.workflow;

import io.boomerang.client.TaskResponsePage;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.boomerang.workflow.tekton.TektonTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/task")
@Tag(name = "Tasks", description = "Create and Manage the global Task definitions.")
public class TaskControllerV2 {

  private final TaskService taskTemplateService;

  public TaskControllerV2(TaskService taskTemplateService) {
    this.taskTemplateService = taskTemplateService;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary =
          "Retrieve a specific task. If no version specified, the latest version is returned.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task get(
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name,
      @Parameter(name = "version", description = "Task Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return taskTemplateService.get(name, version);
  }

  @GetMapping(value = "/{name}", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary =
          "Retrieve a specific task as Tekton Task YAML. If no version specified, the latest version is returned.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TektonTask getYAML(
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name,
      @Parameter(name = "version", description = "Task Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return taskTemplateService.getAsTekton(name, version);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary =
          "Search for Task. If teams are provided it will query the teams. If no teams are provided it will query Global Task Templates")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TaskResponsePage query(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "statuses",
              description = "List of statuses to filter for.",
              example = "active,inactive",
              required = false)
          @RequestParam(required = false, defaultValue = "active")
          Optional<List<String>> statuses,
      @Parameter(
              name = "names",
              description = "List of Task Names  to filter for. Defaults to all.",
              example = "switch,event-wait",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> names,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(required = false)
          Optional<Integer> limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          Optional<Integer> page,
      @Parameter(
              name = "sort",
              description = "Ascending (ASC) or Descending (DESC) sort on creationDate",
              example = "ASC",
              required = true)
          @RequestParam(defaultValue = "ASC")
          Optional<Direction> sort) {
    return taskTemplateService.query(limit, page, sort, labels, statuses, names);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Create a new Task",
      description =
          "The name needs to be unique and must only contain alphanumeric and - characeters.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task create(@RequestBody Task task) {
    return taskTemplateService.create(task);
  }

  @PostMapping(value = "", consumes = "application/x-yaml", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Create a new Task using Tekton Task YAML",
      description =
          "The name needs to be unique and must only contain alphanumeric and - characeters.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TektonTask createYAML(@RequestBody TektonTask tektonTask) {
    return taskTemplateService.createAsTekton(tektonTask);
  }

  @PutMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Update, replace, or create new, Task",
      description =
          "The name must only contain alphanumeric and - characeters. If the name exists, apply will create a new version.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task apply(
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name,
      @RequestBody Task task,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return taskTemplateService.apply(name, task, replace);
  }

  @PutMapping(value = "/{name}", consumes = "application/x-yaml", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Update, replace, or create new using Tekton Task YAML",
      description =
          "The name must only contain alphanumeric and - characeters. If the name exists, apply will create a new version.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TektonTask applyYAML(
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name,
      @RequestBody TektonTask tektonTask,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return taskTemplateService.applyAsTekton(name, tektonTask, replace);
  }

  @GetMapping(value = "/{name}/changelog")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Retrieve the changlog",
      description = "Retrieves each versions changelog and returns them all as a list.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<ChangeLogVersion> getChangelog(
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name) {
    return taskTemplateService.changelog(name);
  }

  @PostMapping(
      value = "/validate",
      consumes = "application/x-yaml",
      produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Validate Tekton Task YAML",
      description = "Validates the Task YAML as a Tekton Task")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void validateYaml(@RequestBody TektonTask tektonTask) {
    taskTemplateService.validateAsTekton(tektonTask);
  }
}
