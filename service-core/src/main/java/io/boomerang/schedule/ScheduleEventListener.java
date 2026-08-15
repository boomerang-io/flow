package io.boomerang.schedule;

import io.boomerang.common.model.ScheduleRequested;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ScheduleRequested} events published by the engine's RunScheduledWorkflow task.
 * Replaces the former WorkflowClient -> InternalController {@code POST /internal/workflow/schedule}
 * HTTP callback: same target method, same "team is blank" contract (the scheduled job resolves it
 * later), now an in-process call.
 *
 * <p>{@code @EventListener} runs synchronously on the publisher's thread, so a thrown exception
 * propagates back out of {@code publishEvent()} to the engine call site exactly as the HTTP
 * round-trip used to surface a failed callback as a thrown exception there.
 *
 * <p>E8: schedule is unsupported in engine mode (ruling I2) - full/standalone only. In engine
 * mode, {@link ScheduleRequested} has no listener; the event is published and silently dropped.
 */
@Component
@ConditionalOnFlowMode({FlowMode.FULL, FlowMode.STANDALONE})
public class ScheduleEventListener {

  private final ScheduleService scheduleService;

  public ScheduleEventListener(ScheduleService scheduleService) {
    this.scheduleService = scheduleService;
  }

  @EventListener
  public void onScheduleRequested(ScheduleRequested event) {
    scheduleService.internalCreate("", event.schedule());
  }
}
