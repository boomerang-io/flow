package io.boomerang.core;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.model.TokenCreateResponse;
import io.boomerang.workflow.ScheduleService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workflow.model.SettingConfig;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/*
 * Endpoints in this controller are unauthenticated and only meant to be used by the Engine
 */
@RestController
@RequestMapping("/internal")
@Hidden
public class InternalController {
  private static final Logger LOGGER = LogManager.getLogger(InternalController.class);

  @Autowired private SettingsService settingsService;

  @Autowired private ScheduleService workflowScheduleService;

  @Autowired private WorkflowService workflowService;

  @Autowired private RelationshipService relationshipService;

  @Autowired private TokenService tokenService;

  // Used by Engine for RunScheduledWorkflow task
  // Team will be blank and the QuartzSchedulerJob will handle it
  @PostMapping(value = "/workflow/schedule")
  @Operation(summary = "Create a Schedule.")
  public WorkflowScheduleEntity createSchedule(@RequestBody WorkflowSchedule schedule) {
    return workflowScheduleService.internalCreate("", schedule);
  }

  // Used by Engine for RunWorkflow tasks
  // Team will be blank
  @PostMapping(value = "/workflow/{ref}/submit")
  @Operation(summary = "Submit a Workflow")
  public WorkflowRun submitWorkflow(
      @Parameter(
              name = "ref",
              description = "Workflow name",
              example = "my-amazing-workflow",
              required = true)
          @PathVariable
          String ref,
      @RequestBody WorkflowSubmitRequest request,
      @Parameter(
              name = "start",
              description = "Start the WorkflowRun immediately after submission",
              required = false)
          @RequestParam(required = false, defaultValue = "false")
          boolean start) {
    String team =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, ref);
    return workflowService.internalSubmit(team, ref, request, start);
  }

  // Used by Engine for RunWorkflow tasks
  // Team will be blank
  @PostMapping(value = "/workflow/{workflow}/run/{run}/relationship")
  @Operation(summary = "Creation the relationship for a WorkflowRun")
  public ResponseEntity<Void> createRelationship(
      @Parameter(name = "run", description = "WorkflowRun Id", required = true) @PathVariable
          String run,
      @Parameter(name = "workflow", description = "Workflow Reference", required = true)
          @PathVariable
          String workflow) {
    try {
      String team =
          relationshipService.getParentByLabel(
              RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflow);
      relationshipService.createNodeAndEdge(
          RelationshipType.TEAM,
          team,
          RelationshipLabel.HAS_WORKFLOWRUN,
          RelationshipType.WORKFLOWRUN,
          run,
          run,
          Optional.empty(),
          Optional.empty());
      LOGGER.info("Created relationship for team({}) and workflowrun({})", team, run);
    } catch (Exception e) {
      LOGGER.error("Error creating relationship for workflow run: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
    return ResponseEntity.ok().build();
  }

  // Used by Handler to get the feature flags and real time value for settings
  @GetMapping(value = "/settings/{key}")
  public ResponseEntity<Map<String, String>> getTaskSettings(
      @Parameter(name = "key", description = "Key of the Settings collection", required = true)
          @PathVariable
          String key) {
    try {
      SettingEntity settings = settingsService.getSettingByKey(key);
      return ResponseEntity.ok(
          settings.getConfig().stream()
              .collect(Collectors.toMap(SettingConfig::getKey, SettingConfig::getValue)));
    } catch (Exception e) {
      return ResponseEntity.noContent().build();
    }
  }

  // Used to create debug token
  // TODO add greater checks and limitations
  @PostMapping(value = "/token")
  @Operation(summary = "Create a Debug Token")
  public TokenCreateResponse createToken(@RequestBody TokenCreateRequest request) {
    return tokenService.create(request);
  }
}
