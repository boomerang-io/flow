package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The monthly cumulative duration quota refuses a submit once the month's minutes reach the limit,
 * with the figure and the limit in its message; a quota of 0 is unlimited, and a workspace that
 * stores none is held to the platform default.
 */
class CumulativeDurationQuotaTest extends AbstractEngineIntegrationTest {

  private static final String TASK_SLUG = "cumulative-duration-quota-task";
  private static final String WORKFLOW = "cumulative-duration-quota-workflow";

  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private MessageSource messageSource;

  private final List<String> runs = new ArrayList<>();

  @BeforeEach
  void seedFixtures() {
    seedGlobalTask(TASK_SLUG);
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    setFeatureSetting("workspaceQuotas", true);
  }

  @AfterEach
  void tidy() {
    setFeatureSetting("workspaceQuotas", false);
    runs.forEach(workflowRunService::cancel);
    runs.clear();
  }

  @Test
  void theMonthIsSpentWhenItsMinutesReachTheLimit() {
    String workspace = workspace("cumulative-limit", 10);
    minutesUsed(workspace, 9);

    submit(workspace);
    assertThat(quotaService.current(workspace).getCurrentCumulativeDuration()).isEqualTo(9L);

    minutesUsed(workspace, 1);
    BoomerangException spent = assertThatRefused(workspace);
    assertThat(spent.getArgs()).containsExactly(QuotaService.CUMULATIVE_DURATION, 10L, 10);
    assertThat(messageSource.getMessage(spent.getReason(), spent.getArgs(), Locale.ENGLISH))
        .isEqualTo("A quota has been exceeded. Cumulative duration this month (minutes): 10 / 10");
    assertThat(quotaService.current(workspace).getCurrentCumulativeDuration()).isEqualTo(10L);
  }

  @Test
  void aQuotaOfZeroIsUnlimited() {
    String workspace = workspace("cumulative-unlimited", 0);
    minutesUsed(workspace, 100_000);

    submit(workspace);
  }

  @Test
  void aWorkspaceThatStoresNoneIsHeldToThePlatformDefault() {
    String workspace = workspace("cumulative-inherits", (Integer) null);
    minutesUsed(workspace, 5);
    submit(workspace);

    String previous = setSettingValue("quotas", QuotaService.QUOTA_MAX_WORKFLOWRUN_CUMULATIVE, "5");
    try {
      assertThat(quotaService.current(workspace).getMaxCumulativeDuration()).isEqualTo(5);
      BoomerangException spent = assertThatRefused(workspace);
      assertThat(spent.getArgs()).containsExactly(QuotaService.CUMULATIVE_DURATION, 5L, 5);
    } finally {
      setSettingValue("quotas", QuotaService.QUOTA_MAX_WORKFLOWRUN_CUMULATIVE, previous);
    }
  }

  // The month's runs are checked first: with both spent, the refusal names the runs.
  @Test
  void theMonthlyRunsAreCheckedBeforeTheMinutes() {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(0);
    quotas.setMaxWorkflowRunMonthly(1);
    quotas.setMaxCumulativeDuration(1);
    String workspace = workspace("cumulative-after-runs", quotas);
    minutesUsed(workspace, 1);
    submitUnchecked(workspace);

    BoomerangException spent = assertThatRefused(workspace);

    assertThat(spent.getArgs()[0]).isEqualTo(QuotaService.MONTHLY_RUNS);
  }

  private BoomerangException assertThatRefused(String workspace) {
    BoomerangException[] refused = new BoomerangException[1];
    assertThatThrownBy(() -> submit(workspace))
        .isInstanceOfSatisfying(BoomerangException.class, e -> refused[0] = e);
    assertThat(refused[0].getReason()).isEqualTo("QUOTA_EXCEEDED");
    return refused[0];
  }

  private void submit(String workspace) {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    WorkflowRun run = workflowService.submit(workspace, WORKFLOW, request, false);
    runs.add(run.getId());
  }

  // A submit with quotas off, so a test can spend the month's runs.
  private void submitUnchecked(String workspace) {
    setFeatureSetting("workspaceQuotas", false);
    try {
      submit(workspace);
    } finally {
      setFeatureSetting("workspaceQuotas", true);
    }
  }

  private String workspace(String name, Integer maxCumulativeDuration) {
    Quotas quotas = new Quotas();
    quotas.setMaxConcurrentRuns(0);
    quotas.setMaxWorkflowRunMonthly(0);
    quotas.setMaxCumulativeDuration(maxCumulativeDuration);
    return workspace(name, quotas);
  }

  private String workspace(String name, Quotas quotas) {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName(name + "-" + System.nanoTime());
    request.setDisplayName(request.getName());
    request.setQuotas(quotas);
    String workspace = workspaceService.create(request).getName();
    workflowService.create(workspace, runnableWorkflow(WORKFLOW, TASK_SLUG));
    return workspace;
  }

  // A run of the month that ended having used these minutes, as its completion's audit event.
  private void minutesUsed(String workspace, long minutes) {
    AuditEventEntity event = new AuditEventEntity();
    // The trail keys a workspace's events on its id.
    event.setWorkspaceId(workspaceService.get(workspace).getId());
    event.setWorkspaceName(workspace);
    event.setAction("UPDATE");
    event.setResourceType("workflowrun");
    event.setResourceId("cumulative-quota-run-" + System.nanoTime());
    event.setTime(new Date());
    event.getPayload().put("cumulativeDuration", minutes * 60_000L);
    mongoTemplate.insert(event);
  }
}
