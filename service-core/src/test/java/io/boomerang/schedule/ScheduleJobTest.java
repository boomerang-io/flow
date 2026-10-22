package io.boomerang.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.WorkflowSchedule;
import io.boomerang.common.model.WorkflowSubmitRequest;
import java.util.List;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.TokenService;
import io.boomerang.core.model.Token;
import io.boomerang.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #359: a schedule-fired WorkflowRun must carry the firing Schedule's id so RunHeader can
 * link back to it. Ruled design (Option A): stamp the existing {@code initiatedByRef} field,
 * mirroring the retry path's convention ({@code WorkflowRunService.java:930-936}) - no new field.
 *
 * <p>The id is threaded internally: {@code ScheduleJob} is the only caller that knows which
 * Schedule fired, so it is the one place that must pass it, via the internal-only
 * {@code WorkflowService.submit(..., initiatedByRef)} overload rather than a client-settable
 * field on {@link WorkflowSubmitRequest}.
 */
class ScheduleJobTest {

  private WorkflowService workflowService;
  private ScheduleService workflowScheduleService;
  private TokenService tokenService;
  private ScheduleJob scheduleJob;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    workflowScheduleService = mock(ScheduleService.class);
    tokenService = mock(TokenService.class);
    RelationshipService relationshipService = mock(RelationshipService.class);
    scheduleJob =
        new ScheduleJob(
            workflowService, workflowScheduleService, tokenService, relationshipService);

    when(tokenService.createWorkflowSchedulerToken(anyString())).thenReturn(mock(Token.class));
  }

  @Test
  void firingASchedulePassesTheScheduleIdAsInitiatedByRef() {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setId("schedule-359");
    schedule.setWorkflowRef("wf-1");
    schedule.setType(WorkflowScheduleType.cron);
    when(workflowScheduleService.internalGet("schedule-359")).thenReturn(schedule);

    scheduleJob.execute("team-1", "wf-1", "schedule-359");

    verify(workflowService)
        .submit(eq("team-1"), eq("wf-1"), any(WorkflowSubmitRequest.class), eq(true),
            eq("schedule-359"));
    // Never the 4-arg overload - that would silently drop lineage.
    verify(workflowService, never())
        .submit(anyString(), anyString(), any(WorkflowSubmitRequest.class), eq(true));
  }

  @Test
  void firingAScheduleSetsTriggerToSchedule() {
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setId("schedule-360");
    schedule.setWorkflowRef("wf-2");
    schedule.setType(WorkflowScheduleType.cron);
    when(workflowScheduleService.internalGet("schedule-360")).thenReturn(schedule);

    scheduleJob.execute("team-1", "wf-2", "schedule-360");

    verify(workflowService)
        .submit(
            eq("team-1"),
            eq("wf-2"),
            argThatTriggerIsSchedule(),
            eq(true),
            eq("schedule-360"));
  }

  private static WorkflowSubmitRequest argThatTriggerIsSchedule() {
    return org.mockito.ArgumentMatchers.argThat(
        request -> request != null && TriggerEnum.schedule.equals(request.getTrigger()));
  }

  @Test
  void firingASchedulePassesTheSchedulesStoredParams() {
    // The submit request carried an empty params list from the v4 rewrite (ef760f016) until
    // 2026-09-01: request.setParams(request.getParams()) was a self-assignment, so the params
    // users configure on a Schedule never reached the run.
    WorkflowSchedule schedule = new WorkflowSchedule();
    schedule.setId("schedule-params");
    schedule.setWorkflowRef("wf-4");
    schedule.setType(WorkflowScheduleType.cron);
    schedule.setParams(List.of(new RunParam("greeting", "hello")));
    when(workflowScheduleService.internalGet("schedule-params")).thenReturn(schedule);

    scheduleJob.execute("team-1", "wf-4", "schedule-params");

    verify(workflowService)
        .submit(
            eq("team-1"),
            eq("wf-4"),
            org.mockito.ArgumentMatchers.<WorkflowSubmitRequest>argThat(
                request ->
                    request.getParams().size() == 1
                        && "greeting".equals(request.getParams().get(0).getName())
                        && "hello".equals(request.getParams().get(0).getValue())),
            eq(true),
            eq("schedule-params"));
  }

  @Test
  void doesNotFireWhenTheScheduleNoLongerExists() {
    when(workflowScheduleService.internalGet("gone")).thenReturn(null);

    scheduleJob.execute("team-1", "wf-3", "gone");

    verify(workflowService, never())
        .submit(
            anyString(),
            anyString(),
            any(WorkflowSubmitRequest.class),
            eq(true),
            anyString());
  }
}
