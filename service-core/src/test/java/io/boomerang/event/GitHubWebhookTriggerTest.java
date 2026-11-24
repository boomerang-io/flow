package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TriggerConditionOperation;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.Trigger;
import io.boomerang.common.model.TriggerCondition;
import io.boomerang.common.model.Workflow;
import io.boomerang.common.model.WorkflowTrigger;
import io.boomerang.core.RestExceptionHandler;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditEventRepository;
import io.boomerang.core.entity.SettingEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.SettingConfig;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.integrations.IntegrationService;
import io.boomerang.workflow.WorkflowService;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.entity.WorkspaceEntity;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import io.boomerang.workspace.repository.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A GitHub App delivery reaches every workflow in the workspace the installation is linked to, and
 * each workflow's GitHub trigger decides: its own switch, then the event and repository filters the
 * trigger form saves. A workflow that refuses never stops the next, an unsigned delivery starts
 * nothing, and a run keeps the payload as {@code data} and the event as {@code event}. A workflow
 * that takes the event and whose run is refused is audited; when nothing started the refusal is
 * the answer, so GitHub records a failed delivery, and when something started the answer stays 200
 * because a redelivery starts every matching workflow again.
 */
class GitHubWebhookTriggerTest extends AbstractEngineIntegrationTest {

  private static final String SECRET = "github-webhook-trigger-secret";
  private static final String TASK_SLUG = "github-webhook-trigger-task";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private WebhookEventService webhookEventService;
  @Autowired private WorkflowService workflowService;
  @Autowired private IntegrationService integrationService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private WorkspaceRepository workspaceRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private RestExceptionHandler restExceptionHandler;
  @Autowired private ObjectMapper objectMapper;

  private String previousSecret;
  private boolean seededAuditCapture;

  @BeforeEach
  void seedFixtures() {
    setFeatureSetting("workspaceQuotas", false);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    seedGlobalTask(TASK_SLUG);
    previousSecret = setSettingValue("integration", "github.webhookSecret", SECRET);
  }

  @AfterEach
  void restoreSecret() {
    setSettingValue("integration", "github.webhookSecret", previousSecret);
    // Shared Testcontainers Mongo: leave quotas and audit capture as the other classes seed them.
    setFeatureSetting("workspaceQuotas", false);
    if (seededAuditCapture) {
      settingsRepository.delete(settingsRepository.findOneByKey("audit"));
      seededAuditCapture = false;
    }
  }

  @Test
  void onlyTheWorkflowsWithTheGitHubTriggerOnAreStarted() {
    String workspace = linkedWorkspace("github-switch-ws", 4101);
    // Created first, so a refusal that stopped the fan-out would leave the second without a run.
    String webhookOnly = createWorkflow(workspace, "github-switch-webhook-only", webhookOnlyTriggers());
    String githubOn = createWorkflow(workspace, "github-switch-on", githubTriggers(List.of()));

    assertThat(deliver("push", 4101, "payments").getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(runs(webhookOnly)).isEmpty();
    List<WorkflowRunEntity> runs = runs(githubOn);
    assertThat(runs).hasSize(1);
    WorkflowRunEntity run = runs.get(0);
    assertThat(run.getTrigger()).isEqualTo("github");
    assertThat(param(run, "event"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("type", "push")
        .containsEntry("id", "delivery-push");
    // Read back as plain values: a stored JSON node could not be mapped back into the run.
    assertThat(param(run, "data"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .extractingByKey("repository")
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("name", "payments");
  }

  @Test
  void theEventAndRepositoryFiltersDecide() {
    String workspace = linkedWorkspace("github-filter-ws", 4102);
    // A filter on a field the payload lacks matches nothing, and stops no other workflow.
    String absentField =
        createWorkflow(
            workspace,
            "github-filter-absent-field",
            githubTriggers(List.of(condition("$.sender.login", TriggerConditionOperation.equals, "octocat"))));
    String filtered =
        createWorkflow(
            workspace,
            "github-filter-push-payments",
            githubTriggers(
                List.of(
                    condition("events", TriggerConditionOperation.in, "push", "release"),
                    condition("repositories", TriggerConditionOperation.in, "payments"))));

    deliver("push", 4102, "payments");
    deliver("pull_request", 4102, "payments");
    deliver("push", 4102, "ledger");

    assertThat(runs(filtered))
        .singleElement()
        .satisfies(
            run ->
                assertThat(param(run, "data"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .extractingByKey("repository")
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("name", "payments"))
        .satisfies(
            run ->
                assertThat(param(run, "event"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("type", "push"));
    assertThat(runs(absentField)).isEmpty();
  }

  @Test
  void anUnsignedDeliveryStartsNothing() {
    String workspace = linkedWorkspace("github-unsigned-ws", 4103);
    String githubOn = createWorkflow(workspace, "github-unsigned-on", githubTriggers(List.of()));
    String body = payload(4103, "payments");

    ResponseEntity<?> response =
        webhookEventService.processGitHubWebhook(
            "push", Optional.of("delivery-forged"), Optional.of("sha256=" + "0".repeat(64)), body, JSON.readTree(body));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(runs(githubOn)).isEmpty();
  }

  @Test
  void thePingIsAcceptedAndStartsNothing() {
    String body = "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":1}";

    ResponseEntity<?> response =
        webhookEventService.processGitHubWebhook(
            "ping", Optional.of("delivery-ping"), Optional.of(sign(body)), body, JSON.readTree(body));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aRefusedSingleMatchAnswersWithTheRefusalAndItsRedeliveryStartsTheRunOnceTheQuotaAllows()
      throws Exception {
    String workspace = quotaWorkspace("github-refused-single-ws", 4104, 1);
    String githubOn = createWorkflow(workspace, "github-refused-single-on", githubTriggers(List.of()));
    // A workflow the event is not for is never a refusal, whatever the quota says.
    createWorkflow(workspace, "github-refused-single-webhook-only", webhookOnlyTriggers());
    setFeatureSetting("workspaceQuotas", true);

    // Accepted: answered as before, 200 with no body.
    send("delivery-accepted", 4104).andExpect(status().isOk()).andExpect(content().string(""));
    assertThat(runs(githubOn)).hasSize(1);

    send("delivery-refused", 4104)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(1020))
        .andExpect(jsonPath("$.reason").value("QUOTA_EXCEEDED"))
        .andExpect(jsonPath("$.status").value("429 TOO_MANY_REQUESTS"))
        .andExpect(
            jsonPath("$.message")
                .value("A quota has been exceeded. Number of runs (executions): 1 / 1"));
    assertThat(runs(githubOn)).hasSize(1);

    setMonthlyRuns(workspace, 2);
    send("delivery-refused", 4104).andExpect(status().isOk());

    assertThat(runs(githubOn))
        .extracting(run -> String.valueOf(((java.util.Map<?, ?>) param(run, "event")).get("id")))
        .containsExactlyInAnyOrder("delivery-accepted", "delivery-refused");
  }

  @Test
  void aDeliveryThatStartsSomeRunsAnswers200AndAuditsTheRefusedOnes() throws Exception {
    String workspace = quotaWorkspace("github-refused-some-ws", 4105, 1);
    String first = createWorkflow(workspace, "github-refused-some-first", githubTriggers(List.of()));
    String second = createWorkflow(workspace, "github-refused-some-second", githubTriggers(List.of()));
    setFeatureSetting("workspaceQuotas", true);
    seedAuditCapture();

    // The quota admits one of the two: one run starts, the other workflow is refused.
    send("delivery-some", 4105).andExpect(status().isOk()).andExpect(content().string(""));

    assertThat(runs(first).size() + runs(second).size()).isEqualTo(1);
    String refusedName =
        runs(first).isEmpty() ? "github-refused-some-first" : "github-refused-some-second";
    awaitEngine("the refused submit's audit event")
        .untilAsserted(
            () ->
                assertThat(refusedSubmits(workspace))
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.getResourceId()).isEqualTo(refusedName);
                          assertThat(event.getPayload().get("errorSummary"))
                              .asString()
                              .startsWith("QUOTA_EXCEEDED: A quota has been exceeded.");
                          assertThat(event.getPayload().get("detail"))
                              .asInstanceOf(InstanceOfAssertFactories.MAP)
                              .containsEntry("trigger", "github")
                              .containsEntry("event", "push")
                              .containsEntry("delivery", "delivery-some");
                        }));

    // With the quota used up every match is refused: nothing starts, so the refusal is the answer.
    send("delivery-none", 4105)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(1020))
        .andExpect(jsonPath("$.reason").value("QUOTA_EXCEEDED"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "No run started: all 2 workflows this delivery matched were refused. The"
                        + " first: A quota has been exceeded. Number of runs (executions): 1 / 1"));
    assertThat(runs(first).size() + runs(second).size()).isEqualTo(1);
  }

  // Why a delivery that started something is never answered as failed: nothing records a delivery
  // id as seen, so the same delivery sent again starts its workflow again.
  @Test
  void aRedeliveryOfAnAcceptedDeliveryStartsItsRunAgain() throws Exception {
    String workspace = linkedWorkspace("github-redelivery-ws", 4106);
    String githubOn = createWorkflow(workspace, "github-redelivery-on", githubTriggers(List.of()));

    send("delivery-twice", 4106).andExpect(status().isOk());
    send("delivery-twice", 4106).andExpect(status().isOk());

    assertThat(runs(githubOn)).hasSize(2);
  }

  // The delivery as GitHub sends it, through the route and the error handler.
  private ResultActions send(String deliveryId, int installationId) throws Exception {
    String body = payload(installationId, "payments");
    return MockMvcBuilders.standaloneSetup(
            new WebhookEventControllerV2(webhookEventService, objectMapper))
        .setControllerAdvice(restExceptionHandler)
        .build()
        .perform(
            post("/api/v2/webhook")
                .contentType(MediaType.parseMediaType("application/json; charset=utf-8"))
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", sign(body))
                .content(body));
  }

  // A workspace of its own, held to a number of runs a month, with the installation linked.
  private String quotaWorkspace(String name, int installationId, int monthlyRuns) {
    if (workspaceRepository.findByNameIgnoreCase(name).isEmpty()) {
      Quotas quotas = new Quotas();
      quotas.setMaxWorkflowRunMonthly(monthlyRuns);
      WorkspaceRequest request = new WorkspaceRequest();
      request.setName(name);
      request.setDisplayName(name);
      request.setQuotas(quotas);
      workspaceService.create(request);
    }
    return linkedWorkspace(name, installationId);
  }

  private void setMonthlyRuns(String workspace, int monthlyRuns) {
    WorkspaceEntity entity = workspaceRepository.findByNameIgnoreCase(workspace).orElseThrow();
    entity.getQuotas().setMaxWorkflowRunMonthly(monthlyRuns);
    workspaceRepository.save(entity);
  }

  private List<AuditEventEntity> refusedSubmits(String workspace) {
    return auditEventRepository.findAll().stream()
        .filter(
            event ->
                workspace.equals(event.getWorkspaceName())
                    && "SUBMIT".equals(event.getAction())
                    && "FAILED".equals(event.getOutcome())
                    && "workflow".equals(event.getResourceType()))
        .toList();
  }

  private void seedAuditCapture() {
    if (settingsRepository.findOneByKey("audit") != null) {
      return;
    }
    SettingEntity settings = new SettingEntity();
    settings.setKey("audit");
    settings.setName("Audit");
    settings.setConfig(List.of(auditConfig("enabled", "true"), auditConfig("level", "WRITE")));
    settingsRepository.save(settings);
    seededAuditCapture = true;
  }

  private static SettingConfig auditConfig(String key, String value) {
    SettingConfig config = new SettingConfig();
    config.setKey(key);
    config.setValue(value);
    return config;
  }

  private ResponseEntity<?> deliver(
      String eventType, int installationId, String repository) {
    String body = payload(installationId, repository);
    return webhookEventService.processGitHubWebhook(
        eventType, Optional.of("delivery-" + eventType), Optional.of(sign(body)), body, JSON.readTree(body));
  }

  private static String payload(int installationId, String repository) {
    return "{\"action\":\"completed\",\"installation\":{\"id\":"
        + installationId
        + "},\"repository\":{\"name\":\""
        + repository
        + "\",\"full_name\":\"acme/"
        + repository
        + "\"}}";
  }

  private static String sign(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // A workspace with the GitHub App installation linked to it, as the install callback leaves it.
  private String linkedWorkspace(String workspace, int installationId) {
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
    if (integrationService.getWorkspaceByRef(String.valueOf(installationId)) == null) {
      String integrationId =
          integrationService.create("github_app", JSON.readTree("{\"id\":" + installationId + "}")).getId();
      relationshipService.createEdge(
          RelationshipType.WORKSPACE,
          workspace,
          RelationshipLabel.HAS_INTEGRATION,
          RelationshipType.INTEGRATION,
          integrationId,
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

  private static WorkflowTrigger webhookOnlyTriggers() {
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setWebhook(new Trigger(true));
    triggers.setGithub(new Trigger(false));
    return triggers;
  }

  private static WorkflowTrigger githubTriggers(List<TriggerCondition> conditions) {
    WorkflowTrigger triggers = new WorkflowTrigger();
    triggers.setWebhook(new Trigger(false));
    Trigger github = new Trigger(true);
    github.setConditions(conditions);
    triggers.setGithub(github);
    return triggers;
  }

  private static TriggerCondition condition(
      String field, TriggerConditionOperation operation, String... values) {
    TriggerCondition condition = new TriggerCondition();
    condition.setField(field);
    condition.setOperation(operation);
    if (operation == TriggerConditionOperation.in) {
      condition.setValues(List.of(values));
    } else {
      condition.setValue(values[0]);
    }
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
