package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TriggerConditionOperation;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.TriggerCondition;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.TokenService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.model.TokenCreateRequest;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.WorkflowService;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.Filter;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * A CloudEvent starts the workflow it names, by id or by name in a workspace, the subject naming it when no ref does. The run keeps the event's attributes as
 * {@code event} and its data as {@code data}, in plain values it can be read back from, and the
 * trigger's filters read both.
 */
class CloudEventTriggerTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "cloudevent-trigger-task";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private WebhookEventService webhookEventService;
  @Autowired private WorkflowService workflowService;
  @Autowired private TokenService tokenService;
  @Autowired private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;
  private String bearer;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    TokenCreateRequest request = new TokenCreateRequest();
    request.setType(AuthScope.global);
    request.setName("cloudevent-trigger-" + UUID.randomUUID());
    request.setPermissions(List.of("**/**"));
    bearer = "Bearer " + tokenService.create(request).getToken();
  }

  @Test
  void theRunKeepsTheAttributesAsEventAndTheDataAsData() {
    String workspace = workspace("ce-shape-ws");
    String workflowId = createWorkflow(workspace, "ce-shape", eventTriggers(List.of()));

    webhookEventService.processEvent(
        workspace, event("io.acme.build.finished", "application/json", "{\"tag\":\"1.4.2\",\"image\":\"acme/api\"}"), Optional.of("ce-shape"));

    WorkflowRunEntity run = runs(workflowId).getFirst();
    assertThat(run.getTrigger()).isEqualTo("event");
    // Read back as plain values; the data's members are not params of their own.
    assertThat(run.getParams()).extracting(RunParam::getName).containsExactly("event", "data");
    assertThat(param(run, "event"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("specversion", "1.0")
        .containsEntry("id", "C234")
        .containsEntry("source", "/ci")
        .containsEntry("type", "io.acme.build.finished")
        .containsEntry("subject", "release")
        .containsEntry("time", "2026-10-24T17:31:00Z")
        .containsEntry("tenant", "acme")
        .doesNotContainKeys("data", "data_base64");
    assertThat(param(run, "data"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("tag", "1.4.2")
        .containsEntry("image", "acme/api");
  }

  @Test
  void textDataIsTextAndOtherDataIsBase64() {
    String workspace = workspace("ce-data-ws");
    String workflowId = createWorkflow(workspace, "ce-data", eventTriggers(List.of()));

    webhookEventService.processEvent(workspace, event("io.acme.text", "text/plain", "hello\nworld"), Optional.of("ce-data"));
    webhookEventService.processEvent(workspace, event("io.acme.bytes", "application/octet-stream", "\u0001\u0002bin"), Optional.of("ce-data"));

    assertThat(runs(workflowId).stream().map(run -> param(run, "data")).toList())
        .containsExactlyInAnyOrder(
            "hello\nworld",
            Base64.getEncoder().encodeToString("\u0001\u0002bin".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void theFiltersReadTheAttributesAndTheData() {
    String workspace = workspace("ce-filter-ws");
    String workflowId =
        createWorkflow(
            workspace,
            "ce-filter",
            eventTriggers(
                List.of(
                    condition("type", TriggerConditionOperation.equals, "io.acme.build.finished"),
                    condition("data.tag", TriggerConditionOperation.matches, "^1\\..*"))));

    webhookEventService.processEvent(
        workspace, event("io.acme.build.finished", "application/json", "{\"tag\":\"1.4.2\"}"), Optional.of("ce-filter"));
    assertThatThrownBy(
            () ->
                webhookEventService.processEvent(
                    workspace, event("io.acme.build.finished", "application/json", "{\"tag\":\"2.0.0\"}"), Optional.of("ce-filter")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOWRUN_TRIGGER_DISABLED");
    assertThatThrownBy(
            () ->
                webhookEventService.processEvent(
                    workspace, event("io.acme.other", "application/json", "{\"tag\":\"1.4.2\"}"), Optional.of("ce-filter")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOWRUN_TRIGGER_DISABLED");
    // A filter on data an event does not carry matches nothing.
    assertThatThrownBy(
            () ->
                webhookEventService.processEvent(
                    workspace, eventWithoutData("io.acme.build.finished", "release"), Optional.of("ce-filter")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOWRUN_TRIGGER_DISABLED");

    assertThat(runs(workflowId)).singleElement()
        .satisfies(run -> assertThat(param(run, "data"))
            .asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry("tag", "1.4.2"));
  }

  @Test
  void theSubjectNamesTheWorkflowWhenNoRefDoes() {
    String workspace = workspace("ce-subject-ws");
    String workflowId = createWorkflow(workspace, "ce-subject", eventTriggers(List.of()));

    // By name in the workspace, and by id on the generic route.
    webhookEventService.processEvent(workspace, eventWithoutData("io.acme.ping", "/ce-subject/topic"), Optional.empty());
    webhookEventService.processEvent(eventWithoutData("io.acme.ping", workflowId + "/topic"), Optional.empty());

    assertThat(runs(workflowId)).hasSize(2);
  }

  @Test
  void anEventNamingNoWorkflowIsRefused() {
    String workspace = workspace("ce-unnamed-ws");

    assertThatThrownBy(
            () -> webhookEventService.processEvent(workspace, eventWithoutData("io.acme.ping", null), Optional.empty()))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOW_INVALID_REQ");
    assertThatThrownBy(
            () -> webhookEventService.processEvent(eventWithoutData("io.acme.ping", "/"), Optional.empty()))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOW_INVALID_REQ");
  }

  @Test
  void aWorkflowIsFoundByNameOnlyInItsOwnWorkspace() {
    String home = workspace("ce-home-ws");
    String other = workspace("ce-other-ws");
    createWorkflow(home, "ce-elsewhere", eventTriggers(List.of()));

    assertThatThrownBy(
            () -> webhookEventService.processEvent(other, eventWithoutData("io.acme.ping", "release"), Optional.of("ce-elsewhere")))
        .isInstanceOf(BoomerangException.class)
        .hasFieldOrPropertyWithValue("reason", "WORKFLOW_INVALID_REFERENCE");
  }

  @Test
  void bothModesAndBothWaysOfNamingTheWorkflowWorkOverHttp() throws Exception {
    String workspace = workspace("ce-http-ws");
    String workflowId = createWorkflow(workspace, "ce-http", eventTriggers(List.of()));

    // Structured mode, the workflow named in its workspace.
    mockMvc
        .perform(
            post("/api/v2/event")
                .param("workspace", workspace)
                .param("ref", "ce-http")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType("application/cloudevents+json")
                .content(
                    "{\"specversion\":\"1.0\",\"id\":\"S1\",\"source\":\"/ci\","
                        + "\"type\":\"io.acme.build.finished\",\"datacontenttype\":\"application/json\","
                        + "\"data\":{\"tag\":\"1.4.2\"}}"))
        .andExpect(status().isOk());
    // Binary mode, the workflow named by id.
    mockMvc
        .perform(
            post("/api/v2/event")
                .param("ref", workflowId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .header("ce-specversion", "1.0")
                .header("ce-id", "B1")
                .header("ce-source", "/ci")
                .header("ce-type", "io.acme.build.finished")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"2.0.0\"}"))
        .andExpect(status().isOk());
    // Neither mode: no specversion anywhere.
    mockMvc
        .perform(
            post("/api/v2/event")
                .param("ref", workflowId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"3.0.0\"}"))
        .andExpect(status().isBadRequest());

    assertThat(runs(workflowId).stream().map(run -> String.valueOf(((Map<?, ?>) param(run, "event")).get("id"))).toList())
        .containsExactlyInAnyOrder("S1", "B1");
  }

  @Test
  void aWebhookNamesItsWorkflowInAWorkspaceOverHttp() throws Exception {
    String workspace = workspace("webhook-http-ws");
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setWebhook(new Trigger(true));
    String workflowId = createWorkflow(workspace, "webhook-http", triggers);

    mockMvc
        .perform(
            post("/api/v2/webhook")
                .param("workspace", workspace)
                .param("ref", "webhook-http")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"1.4.2\"}"))
        .andExpect(status().isOk());

    assertThat(runs(workflowId)).singleElement()
        .satisfies(run -> assertThat(param(run, "data"))
            .asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry("tag", "1.4.2"));
  }

  @Test
  void aWebhookForAWorkflowNamedInAWorkspaceStartsIt() {
    String workspace = workspace("webhook-named-ws");
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setWebhook(new Trigger(true));
    String workflowId = createWorkflow(workspace, "webhook-named", triggers);

    webhookEventService.processWebhook(workspace, "webhook-named", JSON.readTree("{\"tag\":\"1.4.2\"}"));

    assertThat(runs(workflowId)).singleElement()
        .satisfies(run -> assertThat(run.getTrigger()).isEqualTo("webhook"))
        .satisfies(run -> assertThat(param(run, "data"))
            .asInstanceOf(InstanceOfAssertFactories.MAP)
            .containsEntry("tag", "1.4.2"));
  }

  private static CloudEvent event(String type, String contentType, String data) {
    return base(type, "release").withData(contentType, data.getBytes(StandardCharsets.UTF_8)).build();
  }

  private static CloudEvent eventWithoutData(String type, String subject) {
    return base(type, subject).withoutData().build();
  }

  private static CloudEventBuilder base(String type, String subject) {
    CloudEventBuilder builder =
        CloudEventBuilder.v1()
            .withId("C234")
            .withSource(URI.create("/ci"))
            .withType(type)
            .withTime(OffsetDateTime.parse("2026-10-24T17:31:00Z"))
            .withExtension("tenant", "acme");
    return subject == null ? builder : builder.withSubject(subject);
  }

  private String workspace(String workspace) {
    if (!relationshipService.doesSlugOrRefExistForType(RelationshipType.WORKSPACE, workspace)) {
      relationshipService.createNodeAndEdge(
          RelationshipType.ROOT,
          "root",
          RelationshipLabel.CONTAINS,
          RelationshipType.WORKSPACE,
          workspace,
          workspace,
          Optional.empty(),
          Optional.empty());
    }
    return workspace;
  }

  private String createWorkflow(String workspace, String name, WorkflowTrigger triggers) {
    Workflow workflow = runnableWorkflow(name, TASK_SLUG);
    workflow.setTriggers(triggers);
    workflowService.create(workspace, workflow);
    // The workspace-scoped models carry no id, so the workflow is found among the workspace's own.
    return relationshipService
        .refsInWorkspace(RelationshipType.WORKFLOW, workspace, Optional.empty())
        .stream()
        .filter(id -> name.equals(workflowService.get(id, Optional.empty(), false).getBody().getName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkflowTrigger eventTriggers(List<TriggerCondition> conditions) {
    WorkflowTrigger triggers = new WorkflowTrigger();
    Trigger event = new Trigger(true);
    event.setConditions(conditions);
    triggers.setEvent(event);
    return triggers;
  }

  private static TriggerCondition condition(
      String field, TriggerConditionOperation operation, String value) {
    TriggerCondition condition = new TriggerCondition();
    condition.setField(field);
    condition.setOperation(operation);
    condition.setValue(value);
    return condition;
  }

  private List<WorkflowRunEntity> runs(String workflowId) {
    return workflowRunRepository.findByWorkflowRef(workflowId, Pageable.unpaged());
  }

  private static Object param(WorkflowRunEntity run, String name) {
    return run.getParams().stream()
        .filter(param -> name.equals(param.getName()))
        .map(RunParam::getValue)
        .findFirst()
        .orElse(null);
  }
}
