package io.boomerang.workflow;

import static io.cloudevents.core.CloudEventUtils.mapData;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.boomerang.client.EngineClient;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.common.util.ParameterUtil;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.integrations.IntegrationService;
import io.boomerang.workflow.model.WorkflowRunEventRequest;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.data.PojoCloudEventData;
import io.cloudevents.jackson.PojoCloudEventDataMapper;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class WebhookEventService {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.workflowrun.auto-start-on-submit}")
  private boolean autoStart;

  private final WorkflowService workflowService;
  private final EngineClient engineClient;
  private final IntegrationService integrationService;
  private final RelationshipService relationshipService;

  public WebhookEventService(
      WorkflowService workflowService,
      EngineClient engineClient,
      IntegrationService integrationService,
      RelationshipService relationshipService) {
    this.workflowService = workflowService;
    this.engineClient = engineClient;
    this.integrationService = integrationService;
    this.relationshipService = relationshipService;
  }

  /*
   * Receives request and checks if its a supported event. Processing done async.
   *
   * @return accepted or unprocessable.
   */
  public WorkflowRun processEvent(CloudEvent event, Optional<String> workflow) {
    String eventType = event.getType();
    LOGGER.debug("Event Type: " + eventType);
    String eventSubject = event.getSubject();
    LOGGER.debug("Event Subject: " + eventSubject);

    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    String workflowRef = null;
    if (workflow.isPresent()) {
      workflowRef = workflow.get();
    } else {
      // Assume the WorkflowRef is in the subject as the first element
      workflowRef = eventSubject.replace("/", "").split("/")[0];
    }
    request.setTrigger(TriggerEnum.event);
    request.setParams(eventToRunParams(event));

    LOGGER.debug("Webhook Request: " + request.toString());

    // Check principal has relationship to the Workflow
    relationshipService.check(
        RelationshipType.WORKFLOW, workflowRef, Optional.empty(), Optional.empty());

    // Get the Workflows team
    String teamRef =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef);

    // Auto start is not needed when using the default handler
    // As the default handler will pick up the queued Workflow and start the Workflow when ready.
    // However if using the non-default Handler then this may be needed to be set to true.
    return workflowService.submit(teamRef, workflowRef, request, autoStart);
  }

  public WorkflowRun processWebhook(String workflowRef, JsonNode payload) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.webhook);
    request.setParams(payloadToRunParams(payload));

    LOGGER.debug("Webhook Request: " + request.toString());

    // Get the Workflows team
    String teamRef =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef);
    if (teamRef.isEmpty()) {
      throw new BoomerangException(BoomerangError.WORKFLOW_INVALID_REF);
    }

    // Auto start is not needed when using the default handler
    // As the default handler will pick up the queued Workflow and start the Workflow when ready.
    // However if using the non-default Handler then this may be needed to be set to true.
    return workflowService.submit(teamRef, workflowRef, request, autoStart);
  }

  /*

  */
  public ResponseEntity<?> processGitHubWebhook(String eventType, JsonNode payload) {
    LOGGER.debug("GitHub webhook received - {}: {}", eventType, payload.toString());
    switch (eventType) {
      case "installation" -> {
        if (payload.get("action") != null) {
          if ("created".equals(payload.get("action").asText())) {
            integrationService.create("github_app", payload.get("installation"));
          } else if ("deleted".equals(payload.get("action").asText())) {
            integrationService.delete("github_app", payload.get("installation"));
          }
          return ResponseEntity.ok().build();
        }
      }
      default -> {
        // Events that come in will have installation.id and if related to a repo, a repository.name
        LOGGER.debug("Installation ID: " + payload.get("installation").get("id"));
        String teamRef =
            integrationService.getTeamByRef(payload.get("installation").get("id").asText());
        if (!Objects.isNull(teamRef) && !teamRef.isBlank()) {
          WorkflowSubmitRequest request = new WorkflowSubmitRequest();
          request.setTrigger(TriggerEnum.github);
          request.setParams(payloadToRunParams(payload));

          // Auto start is not needed when using the default handler
          // As the default handler will pick up the queued Workflow and start the Workflow when
          // ready.
          // However if using the non-default Handler then this may be needed to be set to true.
          workflowService.internalSubmitForTeam(teamRef, request, autoStart);
        }
        return ResponseEntity.ok().build();
      }
    }
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  public void processWFE(
      String workflowRunId, String topic, String status, Optional<JsonNode> payload) {
    WorkflowRunEventRequest request = new WorkflowRunEventRequest();
    request.setTopic(topic);
    request.setStatus(RunStatus.getRunStatus(status));
    if (payload.isPresent()) {
      RunResult data = new RunResult("data", (Object) payload);
      request.setResults(List.of(data));
    }
    engineClient.eventWorkflowRun(workflowRunId, request);
  }

  /*
   * Set the webhook payload as a parameter
   */
  private List<RunParam> payloadToRunParams(JsonNode payload) {
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("data", (Object) payload, ParamType.object));
    return params;
  }

  /*
   * Convert the CloudEvent and the event's Data and create params
   */
  private List<RunParam> eventToRunParams(CloudEvent event) {
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("event", (Object) event, ParamType.object));

    ObjectMapper mapper = new ObjectMapper();
    PojoCloudEventData<Map<String, Object>> data =
        mapData(
            event,
            PojoCloudEventDataMapper.from(mapper, new TypeReference<Map<String, Object>>() {}));
    params.add(new RunParam("data", (Object) data, ParamType.object));
    params.addAll(ParameterUtil.mapToRunParamList(data.getValue()));
    return params;
  }
}
