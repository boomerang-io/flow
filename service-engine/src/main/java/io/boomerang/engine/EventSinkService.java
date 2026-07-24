package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.engine.model.TaskRunStatusEvent;
import io.boomerang.engine.model.WorkflowRunStatusEvent;
import io.boomerang.engine.model.WorkflowStatusEvent;
import io.boomerang.engine.repository.EventQueueRepository;
import io.boomerang.util.EventFactory;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@Service
public class EventSinkService {
  private static final Logger LOGGER = LogManager.getLogger();

  protected static final String LABEL_KEY_INITIATOR_ID = "initiatorId";
  protected static final String LABEL_KEY_INITIATOR_CONTEXT = "initiatorContext";

  private EventFormat CEFormat =
      EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);

  @Value("${flow.events.sink.urls}")
  private String sinkUrls;

  @Value("${flow.events.sink.enabled}")
  private boolean sinkEnabled;

  private final RestTemplate restTemplate;
  private final EventQueueRepository eventRepository;

  public EventSinkService(
      @Qualifier("internalRestTemplate") RestTemplate restTemplate,
      EventQueueRepository eventRepository) {
    this.restTemplate = restTemplate;
    this.eventRepository = eventRepository;
  }

  public Future<Boolean> publishStatusCloudEvent(TaskRunEntity taskRunEntity) {
    Supplier<Boolean> supplier =
        () -> {
          Boolean isSuccess = Boolean.FALSE;

          try { // Create status update CloudEvent from task execution
            if (sinkEnabled) {
              httpSink(statusEvent(taskRunEntity).toCloudEvent());
            }
            isSuccess = Boolean.TRUE;
          } catch (Exception e) {
            LOGGER.fatal("A fatal error has occurred while publishing the message!", e);
          }
          return isSuccess;
        };

    return CompletableFuture.supplyAsync(supplier);
  }

  public Future<Boolean> publishStatusCloudEvent(WorkflowRunEntity workflowRunEntity) {
    Supplier<Boolean> supplier =
        () -> {
          Boolean isSuccess = Boolean.FALSE;

          try {
            if (sinkEnabled) {
              httpSink(statusEvent(workflowRunEntity).toCloudEvent());
            }
            isSuccess = Boolean.TRUE;
          } catch (Exception e) {
            LOGGER.fatal("A fatal error has occurred while publishing the message!", e);
          }
          return isSuccess;
        };

    return CompletableFuture.supplyAsync(supplier);
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

  public Future<Boolean> publishStatusCloudEvent(WorkflowEntity workflowEntity) {
    Supplier<Boolean> supplier =
        () -> {
          Boolean isSuccess = Boolean.FALSE;

          try {
            if (sinkEnabled) {
              // Create status update CloudEvent
              WorkflowStatusEvent statusEvent = EventFactory.buildStatusUpdateEvent(workflowEntity);

              httpSink(statusEvent.toCloudEvent());
            }
            isSuccess = Boolean.TRUE;
          } catch (Exception e) {
            LOGGER.fatal(
                "A fatal error has occurred while publishing the message! Error: {}",
                e.getMessage());
          }
          return isSuccess;
        };

    return CompletableFuture.supplyAsync(supplier);
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

  public void httpSink(CloudEvent cloudEvent) {
    if (sinkEnabled && sinkUrls != null && !sinkUrls.isEmpty()) {
      final HttpHeaders headers = new HttpHeaders();
      headers.add("Content-Type", JsonFormat.CONTENT_TYPE);

      byte[] serialized = CEFormat.serialize(cloudEvent);

      final HttpEntity<byte[]> req = new HttpEntity<>(serialized, headers);

      String[] sinkUrlList = sinkUrls.split(",");
      for (String sinkUrl : sinkUrlList) {
        LOGGER.debug("httpSink() - URL: " + sinkUrl);

        // 2023-09-12 WIP - Updates to a dead letter queue for replayable events
        try {
          ResponseEntity<String> responseEntity =
              restTemplate.exchange(sinkUrl, HttpMethod.POST, req, String.class);
          LOGGER.debug("httpSink() - Status Code: " + responseEntity.getStatusCode());
          if (responseEntity.getBody() != null) {
            LOGGER.debug("httpSink() - Body: " + responseEntity.getBody().toString());
          }
        } catch (ResourceAccessException rae) {
          LOGGER.fatal("A fatal error has occurred while publishing the message!");
          // eventRepository.save(new EventQueueEntity(sinkUrl, req));
        }
      }
    }
  }
}
