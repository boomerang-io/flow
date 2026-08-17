package io.boomerang.api;

import io.boomerang.api.model.TaskResponsePage;
import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workflow.tekton.TektonTask;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspace/{workspace}/task")
@Tag(
    name = "Workspace Tasks",
    description = "Create and manage the workspace based Task definitions.")
public class WorkspaceTaskControllerV2 {

  private final WorkspaceTaskService workspaceTaskService;

  public WorkspaceTaskControllerV2(WorkspaceTaskService workspaceTaskService) {
    this.workspaceTaskService = workspaceTaskService;
  }

  @GetMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
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
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "version", description = "Task Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return workspaceTaskService.get(workspace, name, version);
  }

  @GetMapping(value = "{name}", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
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
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "version", description = "Task Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return workspaceTaskService.getAsTekton(workspace, name, version);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(
      summary =
          "Search for Tasks. If workspaces are provided it will query the workspaces. If no workspaces are provided it will query Global Task Templates")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TaskResponsePage queryTaskTemplates(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
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
    return workspaceTaskService.query(workspace, limit, page, sort, labels, statuses, names);
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(
      summary = "Create a new Task",
      description =
          "The name needs to be unique and must only contain alphanumeric and - characeters.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task create(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody Task task) {
    return workspaceTaskService.create(workspace, task);
  }

  @PostMapping(value = "", consumes = "application/x-yaml", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(
      summary = "Create a new Task Template using Tekton Task YAML",
      description =
          "The name needs to be unique and must only contain alphanumeric and - characeters.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public TektonTask createYAML(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody TektonTask tektonTask) {
    return workspaceTaskService.createAsTekton(workspace, tektonTask);
  }

  @PutMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
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
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody Task task,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workspaceTaskService.apply(name, workspace, task, replace);
  }

  @PutMapping(value = "/{name}", consumes = "application/x-yaml", produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
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
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @RequestBody TektonTask tektonTask,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return workspaceTaskService.applyAsTekton(name, workspace, tektonTask, replace);
  }

  @GetMapping(value = "/{name}/changelog")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(
      summary = "Retrieve the changlog",
      description = "Retrieves each versions changelog and returns them all as a list.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
      })
  public List<ChangeLogVersion> getChangelog(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name) {
    return workspaceTaskService.changelog(workspace, name);
  }

  @PostMapping(
      value = "/validate",
      consumes = "application/x-yaml",
      produces = "application/x-yaml")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(
      summary = "Validate Tekton Task YAML",
      description = "Validates the Task YAML as a Tekton Task")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void validateYaml(@RequestBody TektonTask tektonTask) {
    workspaceTaskService.validateAsTekton(tektonTask);
  }

  @DeleteMapping(value = "/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.TASK,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.session, AuthScope.user})
  @Operation(summary = "Delete a Workspace Task")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void delete(
      @Parameter(
              name = "workspace",
              description = "Owning workspace name.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "name", description = "Name of Task", required = true) @PathVariable
          String name) {
    workspaceTaskService.delete(workspace, name);
  }
}
