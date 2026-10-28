package io.boomerang.event;

import static io.cloudevents.core.CloudEventUtils.mapData;

import com.fasterxml.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.boomerang.workflow.WorkflowService;
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
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.integrations.GitHubService;
import io.boomerang.integrations.IntegrationService;
import io.boomerang.workflow.WorkflowRunService;
import io.boomerang.engine.model.WorkflowRunEventRequest;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.data.PojoCloudEventData;
import io.cloudevents.jackson.PojoCloudEventDataMapper;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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
  private static final JsonMapper JSON = JsonMapper.builder().build();

  // io.cloudevents:cloudevents-json-jackson's PojoCloudEventDataMapper.from(...) only offers
  // Jackson 2 overloads (com.fasterxml.jackson.databind.ObjectMapper /
  // com.fasterxml.jackson.core.type.TypeReference) - it knows nothing of tools.jackson (the
  // Boot-managed Jackson 3 mapper). eventToRunParams() below is deliberately still on Jackson 2
  // for that reason.
  private static final ObjectMapper JACKSON2_MAPPER =
      com.fasterxml.jackson.databind.json.JsonMapper.builder().build();

  @Value("${flow.workflowrun.auto-start-on-submit}")
  private boolean autoStart;

  private final WorkflowService workflowService;
  private final WorkflowRunService workflowRunService;
  private final IntegrationService integrationService;
  private final GitHubService gitHubService;
  private final RelationshipService relationshipService;

  public WebhookEventService(
      WorkflowService workflowService,
      WorkflowRunService workflowRunService,
      IntegrationService integrationService,
      GitHubService gitHubService,
      RelationshipService relationshipService) {
    this.workflowService = workflowService;
    this.workflowRunService = workflowRunService;
    this.integrationService = integrationService;
    this.gitHubService = gitHubService;
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

    LOGGER.debug(
        "Webhook Request: params [{}]", ParameterUtil.runParamNames(request.getParams()));

    // Check principal has relationship to the Workflow
    if (!relationshipService.check(
        RelationshipType.WORKFLOW, workflowRef, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }

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

    LOGGER.debug(
        "Webhook Request: params [{}]", ParameterUtil.runParamNames(request.getParams()));

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
   * A delivery from the GitHub App. Every one must carry GitHub's signature: the installation named
   * in the body decides whose workflows run, so an unsigned event could start another workspace's.
   * An installation event links or unlinks the installation; any other event is offered to every
   * workflow in the linked workspace, and each workflow's GitHub trigger decides.
   */
  public ResponseEntity<?> processGitHubWebhook(
      String eventType,
      Optional<String> deliveryId,
      Optional<String> signature,
      String rawBody,
      JsonNode payload) {
    if (!gitHubService.verifyWebhookSignature(signature.orElse(null), rawBody)) {
      LOGGER.warn("GitHub {} webhook rejected - missing or invalid signature.", eventType);
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    LOGGER.debug("GitHub webhook received - {} ({})", eventType, deliveryId.orElse("no delivery id"));
    // GitHub's ping, sent when the webhook is saved, names no installation.
    JsonNode installation = payload == null ? null : payload.get("installation");
    if (installation == null || installation.get("id") == null) {
      return ResponseEntity.ok().build();
    }
    if ("installation".equals(eventType)) {
      String action = payload.path("action").asText();
      if ("created".equals(action)) {
        integrationService.create("github_app", installation);
      } else if ("deleted".equals(action)) {
        integrationService.delete("github_app", installation);
      }
      return ResponseEntity.ok().build();
    }
    String workspace = integrationService.getWorkspaceByRef(installation.get("id").asText());
    if (workspace != null && !workspace.isBlank()) {
      workflowService.internalSubmitForWorkspace(
          workspace, () -> gitHubSubmitRequest(eventType, deliveryId, payload), autoStart);
    }
    return ResponseEntity.ok().build();
  }

  // The payload as the run's data param, and the event GitHub named in its headers as its event.
  private static WorkflowSubmitRequest gitHubSubmitRequest(
      String eventType, Optional<String> deliveryId, JsonNode payload) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", eventType);
    deliveryId.ifPresent(id -> event.put("id", id));
    List<RunParam> params = payloadToRunParams(payload);
    params.add(new RunParam("event", event, ParamType.object));
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.github);
    request.setParams(params);
    return request;
  }

  // An eventId makes a redelivery of the same callback a no-op: the run's event inbox records it.
  public void processWFE(
      String workflowRunId,
      String topic,
      String status,
      Optional<JsonNode> payload,
      Optional<String> eventId) {
    WorkflowRunEventRequest request = new WorkflowRunEventRequest();
    eventId.ifPresent(request::setId);
    request.setTopic(topic);
    request.setStatus(RunStatus.getRunStatus(status));
    // Stored as plain maps and lists: a JSON node would be written with its class and could not be
    // read back.
    payload.ifPresent(
        data ->
            request.setResults(List.of(new RunResult("data", JSON.convertValue(data, Object.class)))));
    workflowRunService.event(workflowRunId, request);
  }

  /*
   * The webhook payload as the run's data param, stored as plain maps and lists: a JSON node would be
   * written with its class and the run could not be read back.
   */
  private static List<RunParam> payloadToRunParams(JsonNode payload) {
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("data", JSON.convertValue(payload, Object.class), ParamType.object));
    return params;
  }

  /*
   * Convert the CloudEvent and the event's Data and create params
   */
  private List<RunParam> eventToRunParams(CloudEvent event) {
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("event", (Object) event, ParamType.object));

    PojoCloudEventData<Map<String, Object>> data =
        mapData(
            event,
            PojoCloudEventDataMapper.from(
                JACKSON2_MAPPER, new TypeReference<Map<String, Object>>() {}));
    // A CloudEvent may carry no data at all - the run then gets only the event param.
    if (data != null) {
      params.add(new RunParam("data", (Object) data, ParamType.object));
      params.addAll(ParameterUtil.mapToRunParamList(data.getValue()));
    }
    return params;
  }
}
