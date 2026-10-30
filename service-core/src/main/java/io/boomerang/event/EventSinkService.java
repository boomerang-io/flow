package io.boomerang.event;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.model.TaskRun;
import io.boomerang.event.config.EventSinkProperties;
import io.boomerang.event.config.EventSinkProperties.Destination;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.model.TaskRunStatusEvent;
import io.boomerang.event.model.WorkflowRunStatusEvent;
import io.boomerang.workflow.TaskService;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class EventSinkService {

  protected static final String LABEL_KEY_INITIATOR_ID = "initiatorId";
  protected static final String LABEL_KEY_INITIATOR_CONTEXT = "initiatorContext";

  private EventFormat CEFormat =
      EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);

  private final EventSinkProperties properties;
  private final RestTemplate restTemplate;
  private final TaskService taskService;

  public EventSinkService(
      EventSinkProperties properties,
      @Qualifier("internalRestTemplate") RestTemplate restTemplate,
      TaskService taskService) {
    this.properties = properties;
    this.restTemplate = restTemplate;
    this.taskService = taskService;
  }

  /**
   * Synchronous delivery of one outbox row for the outbox dispatcher - any build or transport
   * failure propagates so the caller can retry. A disabled sink delivers trivially.
   */
  public void deliverStatusCloudEvent(TaskRunEntity taskRunEntity, EventOutboxEntity row)
      throws Exception {
    if (properties.enabled()) {
      httpSinkStrict(statusEvent(taskRunEntity, row).toCloudEvent(), row, taskRunEntity.getLabels());
    }
  }

  /**
   * Synchronous delivery of one outbox row for the outbox dispatcher - any build or transport
   * failure propagates so the caller can retry. A disabled sink delivers trivially.
   */
  public void deliverStatusCloudEvent(WorkflowRunEntity workflowRunEntity, EventOutboxEntity row)
      throws Exception {
    if (properties.enabled()) {
      httpSinkStrict(
          statusEvent(workflowRunEntity, row).toCloudEvent(), row, workflowRunEntity.getLabels());
    }
  }

  private TaskRunStatusEvent statusEvent(TaskRunEntity taskRunEntity, EventOutboxEntity row) {
    TaskRunStatusEvent statusEvent =
        EventFactory.buildStatusUpdateEvent(taskRunEntity, row, properties.payload());
    // A full payload is the public model, whose task reference is the slug form.
    if (statusEvent.getData() instanceof TaskRun taskRun && taskRun.getTaskRef() != null) {
      taskRun.setTaskRef(
          taskService
              .slugsByRef(List.of(taskRun.getTaskRef()))
              .getOrDefault(taskRun.getTaskRef(), taskRun.getTaskRef()));
    }
    statusEvent.setInitiatorId(initiatorLabel(taskRunEntity.getLabels(), LABEL_KEY_INITIATOR_ID));
    statusEvent.setInitiatorContext(
        initiatorLabel(taskRunEntity.getLabels(), LABEL_KEY_INITIATOR_CONTEXT));
    return statusEvent;
  }

  private WorkflowRunStatusEvent statusEvent(
      WorkflowRunEntity workflowRunEntity, EventOutboxEntity row) {
    WorkflowRunStatusEvent statusEvent =
        EventFactory.buildStatusUpdateEvent(workflowRunEntity, row, properties.payload());
    statusEvent.setInitiatorId(
        initiatorLabel(workflowRunEntity.getLabels(), LABEL_KEY_INITIATOR_ID));
    statusEvent.setInitiatorContext(
        initiatorLabel(workflowRunEntity.getLabels(), LABEL_KEY_INITIATOR_CONTEXT));
    return statusEvent;
  }

  private static String initiatorLabel(Map<String, String> labels, String key) {
    return labels != null && labels.get(key) != null ? labels.get(key) : "";
  }

  // Delivery to every configured sink whose filter admits the row - transport failures propagate so
  // the outbox dispatcher can retry the row; a row no sink admits posts nothing and counts as sent.
  private void httpSinkStrict(
      CloudEvent cloudEvent, EventOutboxEntity row, Map<String, String> labels) {
    final byte[] body = CEFormat.serialize(cloudEvent);
    for (Destination sink : properties.sinks()) {
      if (!sink.admits(
          row.getRefType(), row.getTo().status(), row.getTo().phase(), labels)) {
        continue;
      }
      restTemplate.exchange(
          sink.url(), HttpMethod.POST, new HttpEntity<>(body, headers(sink)), String.class);
    }
  }

  // A sink's secret travels in its own header, on its own request, and is never logged. A bare URL
  // sink sends the content type alone, exactly as before.
  private static HttpHeaders headers(Destination sink) {
    final HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", JsonFormat.CONTENT_TYPE);
    if (sink.authenticated()) {
      headers.add(sink.headerName(), sink.headerValue());
    }
    return headers;
  }
}
