package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.error.BoomerangException;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.boomerang.workflow.model.CronValidationResponse;
import io.boomerang.workflow.model.WorkflowScheduleCalendar;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.domain.Sort.Order;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v2/team/{team}/schedule")
@Tag(name = "Schedules", description = "Create, list and manage your Schedules.")
public class TeamScheduleControllerV2 {

  private final ScheduleService workflowScheduleService;

  private final CronService cronService;

  public TeamScheduleControllerV2(
      ScheduleService workflowScheduleService, CronService cronService) {
    this.workflowScheduleService = workflowScheduleService;
    this.cronService = cronService;
  }

  @GetMapping(value = "/validate-cron")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Validate a Schedules CRON.")
  public CronValidationResponse validateCron(
      @Parameter(name = "cron", description = "A CRON expression to validate", required = true)
          @RequestParam
          String cron) {
    return cronService.validateCron(cron);
  }

  @GetMapping(value = "/{scheduleId}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Retrieve a Schedule.")
  public WorkflowSchedule get(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @PathVariable String scheduleId) {
    return workflowScheduleService.get(team, scheduleId);
  }

  @GetMapping(value = "/query")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Search for Schedules")
  public Page<WorkflowSchedule> query(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @Parameter(
              name = "statuses",
              description = "List of statuses to filter for. Defaults to all.",
              example = "active,archived",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> statuses,
      @Parameter(
              name = "types",
              description = "List of types to filter for. Defaults to all.",
              example = "cron,advancedCron",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> types,
      @Parameter(
              name = "workflows",
              description = "List of workflows to filter for.",
              required = false)
          @RequestParam(required = false)
          Optional<List<String>> workflows,
      @Parameter(name = "limit", description = "Result Size", example = "10", required = true)
          @RequestParam(defaultValue = "10")
          int limit,
      @Parameter(name = "page", description = "Page Number", example = "0", required = true)
          @RequestParam(defaultValue = "0")
          int page) {
    final Sort sort = Sort.by(new Order(Direction.ASC, "creationDate"));
    return workflowScheduleService.query(team, page, limit, sort, statuses, types, workflows);
  }

  @GetMapping(value = "/calendars")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Retrieve Calendars for Schedules by Dates.")
  public List<WorkflowScheduleCalendar> getCalendarsForSchedules(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @RequestParam List<String> schedules,
      @RequestParam Long fromDate,
      @RequestParam Long toDate) {
    if (schedules != null && !schedules.isEmpty() && fromDate != null && toDate != null) {
      Date from = new Date(fromDate * 1000);
      Date to = new Date(toDate * 1000);
      return workflowScheduleService.calendars(team, schedules, from, to);
    } else {
      throw new BoomerangException(0, "Invalid fromDate or toDate", HttpStatus.BAD_REQUEST);
    }
  }

  @PostMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Create a Schedule.")
  public WorkflowSchedule createSchedule(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @RequestBody WorkflowSchedule schedule) {
    return workflowScheduleService.create(team, schedule);
  }

  @PutMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.WRITE,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Apply a Schedule.")
  public WorkflowSchedule updateSchedule(
      @RequestBody WorkflowSchedule schedule,
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team) {
    return workflowScheduleService.apply(team, schedule);
  }

  @DeleteMapping(value = "/{scheduleId}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.SCHEDULE,
      assignableScopes = {
        AuthScope.global,
        AuthScope.team,
        AuthScope.user,
        AuthScope.workflow,
        AuthScope.session
      })
  @Operation(summary = "Delete a Schedule.")
  public void deleteSchedule(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @PathVariable String scheduleId) {
    workflowScheduleService.delete(team, scheduleId);
  }

  // TODO: commented out in Web - is this useful?
  //  @GetMapping(value = "/{scheduleId}/calendar")
  //  @Operation(summary = "Retrieve a Calendar based on dates.")
  //  public List<Date> getScheduleForDates(@PathVariable String scheduleId, @RequestParam Long
  // fromDate, @RequestParam Long toDate) {
  //    if (fromDate != null && toDate != null) {
  //      Date from = new Date(fromDate * 1000);
  //      Date to = new Date(toDate * 1000);
  //      return workflowScheduleService.getCalendarForDates(scheduleId, from, to);
  //    } else {
  //      throw new BoomerangException(0, "Invalid fromDate or toDate", HttpStatus.BAD_REQUEST);
  //    }
  //  }
}
