package io.boomerang.event;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.event.model.TaskRunStatusEvent;
import io.boomerang.event.model.WorkflowRunStatusEvent;
import io.boomerang.event.EventFactory;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

  @Value("${flow.events.sink.urls}")
  private String sinkUrls;

  @Value("${flow.events.sink.enabled}")
  private boolean sinkEnabled;

  private final RestTemplate restTemplate;

  public EventSinkService(@Qualifier("internalRestTemplate") RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Synchronous delivery for the outbox dispatcher - any build or transport failure propagates so
   * the caller can retry. A disabled sink delivers trivially.
   */
  public void deliverStatusCloudEvent(TaskRunEntity taskRunEntity) throws Exception {
    if (sinkEnabled) {
      httpSinkStrict(statusEvent(taskRunEntity).toCloudEvent());
    }
  }

  /**
   * Synchronous delivery for the outbox dispatcher - any build or transport failure propagates so
   * the caller can retry. A disabled sink delivers trivially.
   */
  public void deliverStatusCloudEvent(WorkflowRunEntity workflowRunEntity) throws Exception {
    if (sinkEnabled) {
      httpSinkStrict(statusEvent(workflowRunEntity).toCloudEvent());
    }
  }

  private TaskRunStatusEvent statusEvent(TaskRunEntity taskRunEntity) {
    TaskRunStatusEvent statusEvent = EventFactory.buildStatusUpdateEvent(taskRunEntity);
    statusEvent.setInitiatorId(initiatorLabel(taskRunEntity.getLabels(), LABEL_KEY_INITIATOR_ID));
    statusEvent.setInitiatorContext(
        initiatorLabel(taskRunEntity.getLabels(), LABEL_KEY_INITIATOR_CONTEXT));
    return statusEvent;
  }

  private WorkflowRunStatusEvent statusEvent(WorkflowRunEntity workflowRunEntity) {
    WorkflowRunStatusEvent statusEvent = EventFactory.buildStatusUpdateEvent(workflowRunEntity);
    statusEvent.setInitiatorId(
        initiatorLabel(workflowRunEntity.getLabels(), LABEL_KEY_INITIATOR_ID));
    statusEvent.setInitiatorContext(
        initiatorLabel(workflowRunEntity.getLabels(), LABEL_KEY_INITIATOR_CONTEXT));
    return statusEvent;
  }

  private static String initiatorLabel(java.util.Map<String, String> labels, String key) {
    return labels != null && labels.get(key) != null ? labels.get(key) : "";
  }

  // Best-effort delivery to every configured sink - transport failures propagate so the outbox
  // dispatcher can retry the row.
  private void httpSinkStrict(CloudEvent cloudEvent) {
    if (sinkUrls == null || sinkUrls.isEmpty()) {
      return;
    }
    final HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", JsonFormat.CONTENT_TYPE);
    final HttpEntity<byte[]> req = new HttpEntity<>(CEFormat.serialize(cloudEvent), headers);
    for (String sinkUrl : sinkUrls.split(",")) {
      restTemplate.exchange(sinkUrl, HttpMethod.POST, req, String.class);
    }
  }
}
