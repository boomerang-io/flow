package io.boomerang.schedule;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.api.WorkspaceWorkflowService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/*
 * Invoked by the ScheduleWatcher when a schedule fires: submits the WorkflowRun.
 *
 * E8: schedule is unsupported in engine mode (ruling I2) - standalone only, matching
 * ScheduleWatcher (its only caller).
 */
@Component
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class ScheduleJob {

  private static final Logger logger = LoggerFactory.getLogger(ScheduleJob.class);

  private final WorkspaceWorkflowService workspaceWorkflowService;
  private final ScheduleService workflowScheduleService;
  private final TokenService tokenService;
  private final RelationshipService relationshipService;

  public ScheduleJob(
      WorkspaceWorkflowService workspaceWorkflowService,
      @Lazy ScheduleService workflowScheduleService,
      TokenService tokenService,
      RelationshipService relationshipService) {
    this.workspaceWorkflowService = workspaceWorkflowService;
    this.workflowScheduleService = workflowScheduleService;
    this.tokenService = tokenService;
    this.relationshipService = relationshipService;
  }

  /**
   * This is the method that will be executed each time the Schedule is fired.
   *
   * <p>Note: we no longer check the relationship of a schedule to a team as this is done via the
   * CRUD of the schedule
   */
  public void execute(String teamRef, String workflowRef, String scheduleId) {
    logger.info("Schedule Job ({}) executed for Workflow ({})", scheduleId, workflowRef);

    WorkflowSchedule schedule = workflowScheduleService.internalGet(scheduleId);
    if (schedule != null) {
      WorkflowSubmitRequest request = new WorkflowSubmitRequest();
      request.setLabels(schedule.getLabels());
      request.setParams(request.getParams());
      request.setTrigger(TriggerEnum.schedule);

      // Hoist token to ThreadLocal SecurityContext - this AuthN/AuthZ allows the WorkflowRun to be
      // triggered
      Token token = tokenService.createWorkflowSchedulerToken(workflowRef);
      final List<GrantedAuthority> authorities = new ArrayList<>();
      final UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(workflowRef, null, authorities);
      authToken.setDetails(token);
      SecurityContextHolder.getContext().setAuthentication(authToken);

      // TODO: fix setting of start to come from somewhere
      workspaceWorkflowService.submit(teamRef, workflowRef, request, false);
      if (schedule.getType().equals(WorkflowScheduleType.runOnce)) {
        logger.debug("Executing runOnce schedule: {}, and marking as completed.", schedule.getId());
        workflowScheduleService.complete(schedule.getId());
      }
    }
  }
}
