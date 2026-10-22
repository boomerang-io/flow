package io.boomerang.event;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.EventPayload;
import io.boomerang.event.enums.EventType;
import io.boomerang.event.model.RunStatusSummary;
import io.boomerang.event.model.TaskRunStatusEvent;
import io.boomerang.event.model.WorkflowRunStatusEvent;
import io.boomerang.event.model.WorkflowStatusEvent;
import io.boomerang.workflow.ConvertUtil;
import java.net.URI;
import java.text.MessageFormat;
import java.util.Date;
import java.util.UUID;

public class EventFactory {

  private static final String EVENT_SOURCE_URI = "/apis/v1/events";

  private EventFactory() {}

  /*
   * The status event for one outbox row: its id is the row's, so a retried delivery keeps it, and
   * its time is when the transition occurred.
   */
  public static WorkflowRunStatusEvent buildStatusUpdateEvent(
      WorkflowRunEntity wfRunEntity, EventOutboxEntity row, EventPayload payload) {

    // Event subject
    // @formatter:off
    String eventSubject =
        MessageFormat.format(
            "/workflowrun/{0}/status/{1}",
            wfRunEntity.getId(), wfRunEntity.getStatus().toString().toLowerCase());
    // @formatter:off

    // Create workflow status update event
    WorkflowRunStatusEvent statusEvent = new WorkflowRunStatusEvent();
    statusEvent.setId(row.getId());
    statusEvent.setSource(URI.create(EVENT_SOURCE_URI));
    statusEvent.setSubject(eventSubject);
    statusEvent.setDate(row.getOccurredAt());
    statusEvent.setType(EventType.WORKFLOWRUN_STATUS_UPDATE);
    statusEvent.setData(
        RunStatusSummary.project(
            ConvertUtil.entityToModel(wfRunEntity, WorkflowRun.class), payload));

    return statusEvent;
  }

  public static TaskRunStatusEvent buildStatusUpdateEvent(
      TaskRunEntity taskRunEntity, EventOutboxEntity row, EventPayload payload) {

    // Event subject
    // @formatter:off
    String eventSubject =
        MessageFormat.format(
            "/taskrun/{0}/status/{1}",
            taskRunEntity.getId(), taskRunEntity.getStatus().toString().toLowerCase());
    // @formatter:on

    // Create task status update event
    TaskRunStatusEvent statusUpdateEvent = new TaskRunStatusEvent();
    statusUpdateEvent.setId(row.getId());
    statusUpdateEvent.setSource(URI.create(EVENT_SOURCE_URI));
    statusUpdateEvent.setSubject(eventSubject);
    statusUpdateEvent.setDate(row.getOccurredAt());
    statusUpdateEvent.setType(EventType.TASKRUN_STATUS_UPDATE);
    statusUpdateEvent.setData(RunStatusSummary.project(new TaskRun(taskRunEntity), payload));

    return statusUpdateEvent;
  }

  public static WorkflowStatusEvent buildStatusUpdateEvent(WorkflowEntity workflowEntity) {

    // Event subject
    // @formatter:off
    String eventSubject =
        MessageFormat.format(
            "/workflow/{0}/status/{1}",
            workflowEntity.getId(), workflowEntity.getStatus().toString().toLowerCase());
    // @formatter:on

    // Create task status update event
    WorkflowStatusEvent statusUpdateEvent = new WorkflowStatusEvent();
    statusUpdateEvent.setId(UUID.randomUUID().toString());
    statusUpdateEvent.setSource(URI.create(EVENT_SOURCE_URI));
    statusUpdateEvent.setSubject(eventSubject);
    statusUpdateEvent.setDate(new Date());
    statusUpdateEvent.setType(EventType.TASKRUN_STATUS_UPDATE);
    statusUpdateEvent.setWorkflow(workflowEntity);

    return statusUpdateEvent;
  }
}
