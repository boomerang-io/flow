package io.boomerang.event;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.format.EventSerializationException;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import io.cloudevents.rw.CloudEventRWException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
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
  private static final EventFormat CLOUD_EVENT_JSON =
      EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);

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
   * A CloudEvent for a workflow named by id: the ref, else the first segment of the subject.
   */
  public WorkflowRun processEvent(CloudEvent event, Optional<String> workflow) {
    String workflowRef = workflow.filter(ref -> !ref.isBlank()).orElseGet(() -> subjectWorkflow(event));
    if (!relationshipService.check(
        RelationshipType.WORKFLOW, workflowRef, Optional.empty(), Optional.empty())) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }
    String workspace =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, workflowRef);
    return workflowService.submit(workspace, workflowRef, eventSubmitRequest(event), autoStart);
  }

  /*
   * A CloudEvent for a workflow named in a workspace: the ref, else the first segment of the
   * subject. A workflow the caller cannot reach in that workspace is not found.
   */
  public WorkflowRun processEvent(String workspace, CloudEvent event, Optional<String> workflow) {
    String name = workflow.filter(ref -> !ref.isBlank()).orElseGet(() -> subjectWorkflow(event));
    return workflowService.submit(workspace, name, eventSubmitRequest(event), autoStart);
  }

  public WorkflowRun processWebhook(String workflowRef, JsonNode payload) {
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
    return workflowService.submit(teamRef, workflowRef, webhookSubmitRequest(payload), autoStart);
  }

  /*
   * A webhook for a workflow named in a workspace. A workflow the caller cannot reach in that
   * workspace is not found.
   */
  public WorkflowRun processWebhook(String workspace, String workflow, JsonNode payload) {
    return workflowService.submit(workspace, workflow, webhookSubmitRequest(payload), autoStart);
  }

  private static WorkflowSubmitRequest webhookSubmitRequest(JsonNode payload) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.webhook);
    request.setParams(payloadToRunParams(payload));
    LOGGER.debug(
        "Webhook Request: params [{}]", ParameterUtil.runParamNames(request.getParams()));
    return request;
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
   * The workflow a CloudEvent's subject names: its first segment, so "/wf-1/topic" names wf-1.
   */
  private static String subjectWorkflow(CloudEvent event) {
    String subject = event.getSubject() == null ? "" : event.getSubject();
    return Arrays.stream(subject.split("/"))
        .filter(segment -> !segment.isBlank())
        .findFirst()
        .orElseThrow(() -> new BoomerangException(BoomerangError.WORKFLOW_INVALID_REQ));
  }

  /*
   * The run's params: "event", the event's attributes as the CloudEvents JSON format writes them,
   * and "data", its data. Both are plain maps, lists and values, so the run can be read back.
   */
  private static WorkflowSubmitRequest eventSubmitRequest(CloudEvent event) {
    Map<String, Object> attributes;
    try {
      attributes =
          JSON.readValue(CLOUD_EVENT_JSON.serialize(event), new TypeReference<Map<String, Object>>() {});
    } catch (EventSerializationException | CloudEventRWException | JacksonException e) {
      // Data whose content type says JSON but that does not parse.
      throw new BoomerangException(e, BoomerangError.WORKFLOW_INVALID_REQ);
    }
    Object data = removeData(attributes);
    List<RunParam> params = new LinkedList<>();
    params.add(new RunParam("event", attributes, ParamType.object));
    if (data != null) {
      params.add(new RunParam("data", data, paramType(data)));
    }
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.event);
    request.setParams(params);
    LOGGER.debug("Event Request: params [{}]", ParameterUtil.runParamNames(params));
    return request;
  }

  /*
   * JSON data as it was sent; text data as a string; anything else as the base64 text the JSON
   * format writes for it.
   */
  private static Object removeData(Map<String, Object> attributes) {
    if (attributes.containsKey("data")) {
      return attributes.remove("data");
    }
    Object encoded = attributes.remove("data_base64");
    return encoded instanceof String base64
            && attributes.get("datacontenttype") instanceof String contentType
            && contentType.startsWith("text/")
        ? new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
        : encoded;
  }

  private static ParamType paramType(Object value) {
    return value instanceof String ? ParamType.string
        : value instanceof List ? ParamType.array
        : ParamType.object;
  }
}
