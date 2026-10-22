package io.boomerang.engine;

import io.boomerang.common.model.ChangeLogVersion;
import io.boomerang.common.model.Task;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/task")
@Tag(name = "Task Management", description = "Create and Manage the Task, or Task Definitions.")
public class TaskControllerV1 {

  private final TaskService taskService;

  public TaskControllerV1(TaskService taskService) {
    this.taskService = taskService;
  }

  @GetMapping(value = "/{ref}")
  @Operation(
      summary =
          "Retrieve a specific task. If no version specified, the latest version is returned.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task get(
      @Parameter(name = "ref", description = "Task reference", required = true) @PathVariable
          String ref,
      @Parameter(name = "version", description = "Task Version", required = false)
          @RequestParam(required = false)
          Optional<Integer> version) {
    return taskService.get(ref, version);
  }

  @GetMapping(value = "/query")
  @Operation(summary = "Search for Task")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Page<Task> query(
      @Parameter(
              name = "labels",
              description =
                  "List of url encoded labels. For example Organization=Boomerang,customKey=test would be encoded as Organization%3DBoomerang,customKey%3Dtest)",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> labels,
      @Parameter(
              name = "status",
              description = "List of statuses to filter for.",
              example = "inactive",
              required = false)
          @RequestParam(required = false, defaultValue = "active")
          Optional<List<String>> status,
      @Parameter(
              name = "names",
              description = "List of Task Names to filter for. Defaults to all.",
              example = "switch,event-wait",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> names,
      @Parameter(
              name = "ids",
              description = "List of Task Ids to filter for. Defaults to all.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> ids,
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
    return taskService.query(limit, page, sort, labels, status, names, ids);
  }

  @PostMapping(value = "")
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
    return taskService.create(task);
  }

  @PutMapping(value = "")
  @Operation(
      summary = "Update, replace, or create new, Task",
      description =
          "The name must only contain alphanumeric and - characeters. If the id exists, apply will create a new version.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public Task apply(
      @RequestBody Task task,
      @Parameter(name = "replace", description = "Replace existing version", required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean replace) {
    return taskService.apply(task, replace);
  }

  @GetMapping(value = "/{ref}/changelog")
  @Operation(
      summary = "Retrieve the changlog",
      description = "Retrieves each versions changelog and returns them all as a list.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public List<ChangeLogVersion> getChangelog(
      @Parameter(name = "ref", description = "Task reference", required = true) @PathVariable
          String ref) {
    return taskService.changelog(ref);
  }

  @DeleteMapping(value = "/{ref}")
  @Operation(
      summary = "Delete a Task and associated versions. This is destructive and irreversible.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "No Content"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public void delete(
      @Parameter(name = "ref", description = "Task reference", required = true) @PathVariable
          String ref) {
    taskService.delete(ref);
  }
}
