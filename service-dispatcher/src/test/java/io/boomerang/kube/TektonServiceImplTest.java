package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.config.LogCapture;
import io.boomerang.error.TaskExecutionException;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.boomerang.dispatcher.sdk.Dispatcher;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.RunParam;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunSpec;
import io.boomerang.dispatcher.sdk.model.WorkflowWorkspace;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.v1.TaskRunStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.assertNull;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.tekton.v1.ParamValue;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.tekton.v1.Step;
import io.fabric8.tekton.v1.TaskRunResult;

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(
    properties = {
      "dispatcher.tasks.runtimeClassName=kata-qemu"
    })
@Tag("smoke")
public class TektonServiceImplTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  @Autowired private KubeServiceImpl kubeService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private Dispatcher dispatcher;

  private TektonClient tektonClient;

  @BeforeEach
  public void setUp() {
    tektonClient = client.adapt(TektonClient.class);
    tektonService.setClient(tektonClient);
  }

  @Test
  public void testCreateTaskRunCarriesRuntimeClassNameOnThePodTemplate() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-rtc");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());
    // The same deployment-wide isolation setting the Kubernetes Jobs executor honours.
    assertEquals(
        "kata-qemu", taskRuns.get(0).getSpec().getPodTemplate().getRuntimeClassName());
    assertTrue(taskRuns.get(0).getSpec().getWorkspaces().isEmpty(), "no workspace declared");
  }

  @Test
  public void testCreateTaskRunLogsNoParamEnvOrScriptValue() throws Exception {
    String secret = "tekton-s3cr3t-7777";
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-log");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    RunParam password = new RunParam("apiKey", secret);
    password.setType(io.boomerang.dispatcher.sdk.model.ParamType.secret);
    task.setParams(List.of(password));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setScript("echo " + secret);
    spec.setEnvs(List.of(new io.boomerang.dispatcher.sdk.model.TaskEnvVar("TOKEN", secret)));
    spec.setDebug(false);
    task.setSpec(spec);

    List<String> lines;
    try (LogCapture logs = LogCapture.start(org.apache.logging.log4j.Level.DEBUG, "io.boomerang")) {
      tektonService.create(task, 30L);
      lines = logs.lines();
    }

    // The value really went into the TaskRun, and only the name, namespace and image were logged.
    String created =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().stream()
            .filter(t -> t.getMetadata().getName().contains("taskrun-tekton-log"))
            .findFirst()
            .orElseThrow()
            .toString();
    assertTrue(created.contains(secret), "the TaskRun carries the value");
    assertTrue(
        lines.stream().anyMatch(l -> l.startsWith("Created TaskRun ") && l.contains("alpine:3.19")),
        "the creation is logged by name: " + lines);
    assertTrue(
        lines.stream().noneMatch(l -> l.contains(secret)), "a secret value was logged: " + lines);
  }

  @Test
  public void testCreateTaskRunMountsTheRunWorkspaceWhereItSays() throws Exception {
    kubeService.setClient(client);
    try {
      // The mock server never reports a claim phase, so the bound/pending wait times out; the
      // claim itself is created before the wait and is all getPVCName() needs.
      kubeService.createWorkspacePVC("wf-1", "wfr-ws", null, "1Gi", "", "ReadWriteMany", 1L);
    } catch (Exception ignored) {
      // wait timeout on the mock server
    }
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-ws");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-ws");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    WorkflowWorkspace workspace = new WorkflowWorkspace();
    workspace.setMountPath("/shared");
    task.setWorkspace(workspace);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    io.fabric8.tekton.v1.TaskRun taskRun =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().get(0);
    assertEquals(1, taskRun.getSpec().getWorkspaces().size());
    assertEquals("workspace", taskRun.getSpec().getWorkspaces().get(0).getName());
    assertNotNull(taskRun.getSpec().getWorkspaces().get(0).getPersistentVolumeClaim().getClaimName());
    assertEquals(1, taskRun.getSpec().getTaskSpec().getWorkspaces().size());
    assertEquals("workspace", taskRun.getSpec().getTaskSpec().getWorkspaces().get(0).getName());
    assertEquals("/shared", taskRun.getSpec().getTaskSpec().getWorkspaces().get(0).getMountPath());
  }
  @Test
  public void testCreateTaskRunSizesTheStepFromTheConfiguredResources() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-resources");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());
    Step step = taskRuns.get(0).getSpec().getTaskSpec().getSteps().get(0);

    assertEquals(new Quantity("2Gi"), step.getComputeResources().getRequests().get("memory"));
    assertEquals(
        new Quantity("2Gi"), step.getComputeResources().getRequests().get("ephemeral-storage"));
    assertEquals(new Quantity("16Gi"), step.getComputeResources().getLimits().get("memory"));
    assertEquals(
        new Quantity("16Gi"), step.getComputeResources().getLimits().get("ephemeral-storage"));
    // CPU ships blank - a CPU limit throttles rather than fails, so an operator opts in.
    assertNull(step.getComputeResources().getRequests().get("cpu"));
    assertNull(step.getComputeResources().getLimits().get("cpu"));
  }

  @Test
  public void testCreateAiTaskRunsTheImageItsRevisionDeclaresWithTheParamEnv() throws Exception {
    // An `ai` task runs the image and command its catalogue revision declares, never a script.
    // Everything else (PARAM_<NAME> env, RESULTS_PATH) is what every other type gets.
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-ai");
    task.setName("Ask the model");
    task.setType(TaskType.ai);
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(
        List.of(
            new RunParam("endpoint", "https://api.example.com/v1"),
            new RunParam("maxTokens", 1024),
            new RunParam("prompt", "Summarise the run")));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("boomerangio/task-ai:1.2.3");
    spec.setCommand(List.of("prompt"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());
    Step step = taskRuns.get(0).getSpec().getTaskSpec().getSteps().get(0);
    assertEquals("boomerangio/task-ai:1.2.3", step.getImage());
    assertEquals(List.of("prompt"), step.getCommand());
    assertNull(step.getScript());

    List<EnvVar> env = step.getEnv();
    assertTrue(
        env.stream()
            .anyMatch(
                e ->
                    "PARAM_ENDPOINT".equals(e.getName())
                        && "https://api.example.com/v1".equals(e.getValue())));
    assertTrue(
        env.stream().anyMatch(e -> "PARAM_MAXTOKENS".equals(e.getName()) && "1024".equals(e.getValue())));
    assertTrue(
        env.stream()
            .anyMatch(
                e -> "RESULTS_PATH".equals(e.getName()) && "/tekton/results".equals(e.getValue())));
    assertTrue(
        env.stream()
            .anyMatch(e -> "RESULTS_MAX_BYTES".equals(e.getName()) && "4096".equals(e.getValue())));
  }

  @Test
  public void testCreateAdoptsARunningTaskRunAndCancelCancelsEveryRunningOne() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-attempts");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    // The attempt handed out again while its TaskRun still runs continues that TaskRun.
    tektonService.create(task, 30L);
    tektonService.create(task, 30L);
    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());

    // A cancelled attempt is finished: the next attempt gets a TaskRun of its own, and a cancel
    // reaches every attempt still running under the same labels.
    tektonService.cancel(task);
    tektonService.create(task, 30L);
    io.fabric8.tekton.v1.TaskRun another =
        new io.fabric8.tekton.v1.TaskRunBuilder()
            .withNewMetadata()
            .withGenerateName("manual-attempt-")
            .withLabels(taskRuns.get(0).getMetadata().getLabels())
            .endMetadata()
            .build();
    tektonClient.v1().taskRuns().resource(another).create();
    tektonService.cancel(task);

    taskRuns = tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(3, taskRuns.size());
    assertTrue(
        taskRuns.stream()
            .allMatch(
                t -> "TaskRunCancelled".equals(t.getStatus().getConditions().get(0).getReason())),
        "every attempt is cancelled: " + taskRuns);
  }

  @Test
  public void testToRunResultsConvertsTektonResultsRegardlessOfTaskOutcome() {
    // watchTaskRun() calls this on both the success return and the failure throw - a failed Task
    // (e.g. an HTTP Task that records a 404 status code before exiting non-zero) must not lose the
    // Result Parameters it wrote (issue #361).
    TaskRunResult tknResult = new TaskRunResult();
    tknResult.setName("statusCode");
    tknResult.setValue(new ParamValue("404"));
    TaskRunResult tknResultWithNullValue = new TaskRunResult();
    tknResultWithNullValue.setName("empty");

    List<RunResult> results =
        TektonServiceImpl.toRunResults(List.of(tknResult, tknResultWithNullValue));

    assertEquals(2, results.size());
    assertEquals("statusCode", results.get(0).getName());
    assertEquals("404", results.get(0).getValue());
    assertEquals("empty", results.get(1).getName());
    assertNull(results.get(1).getValue());
  }

}

/**
 * Pins the reconcile loop: the watch never delivers a terminating event in this test (the
 * terminal status is written to the mock server before the watch is opened), so the terminal
 * TaskRun is picked up by the label-list poll on a one-second {@code
 * kube.timeout.reconcileSeconds}.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(properties = "kube.timeout.reconcileSeconds=1")
class TektonServiceImplReconcileTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  @MockitoBean private Dispatcher dispatcher;

  private TektonClient tektonClient;

  @BeforeEach
  public void setUp() {
    tektonClient = client.adapt(TektonClient.class);
    tektonService.setClient(tektonClient);
  }

  @Test
  public void testWatchReconcilesAListedTerminalTaskRunAfterTheWatchMissesIt() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-reconcile");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    io.fabric8.tekton.v1.TaskRun created =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().get(0);
    Condition succeeded = new Condition();
    succeeded.setType("Succeeded");
    succeeded.setStatus("True");
    TaskRunStatus status = new TaskRunStatus();
    status.setConditions(List.of(succeeded));
    status.setResults(List.of());
    created.setStatus(status);
    tektonClient.v1().taskRuns().resource(created).updateStatus();

    List<RunResult> results = tektonService.watch(task, 30L);

    assertNotNull(results);
  }

  @Test
  public void testAFailedTaskRunsMessageIgnoresAnotherAttemptsPod() throws Exception {
    // The only pod under the task's labels belongs to an earlier attempt (its TaskRun name
    // differs): it must not supply this attempt's failure reason or detail.
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-other-attempts-pod");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);
    io.fabric8.tekton.v1.TaskRun created =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().get(0);

    ContainerStateTerminated terminated = new ContainerStateTerminated();
    terminated.setExitCode(137);
    terminated.setReason("OOMKilled");
    ContainerState state = new ContainerState();
    state.setTerminated(terminated);
    ContainerStatus step = new ContainerStatus();
    step.setName("step-task");
    step.setState(state);
    PodStatus podStatus = new PodStatus();
    podStatus.setPhase("Failed");
    podStatus.setContainerStatuses(List.of(step));
    Map<String, String> earlierAttemptPodLabels = new HashMap<>(created.getMetadata().getLabels());
    earlierAttemptPodLabels.put("tekton.dev/taskRun", "earlier-attempt");
    Pod pod =
        new PodBuilder()
            .withNewMetadata()
            .withGenerateName("earlier-attempt-pod-")
            .withLabels(earlierAttemptPodLabels)
            .endMetadata()
            .build();
    Pod createdPod = client.pods().resource(pod).create();
    createdPod.setStatus(podStatus);
    client.pods().resource(createdPod).updateStatus();

    Condition failed = new Condition();
    failed.setType("Succeeded");
    failed.setStatus("False");
    failed.setReason("Failed");
    failed.setMessage("\"step-task\" exited with code 2");
    TaskRunStatus status = new TaskRunStatus();
    status.setConditions(List.of(failed));
    status.setResults(List.of());
    created.setStatus(status);
    tektonClient.v1().taskRuns().resource(created).updateStatus();

    TaskExecutionException ex =
        assertThrows(TaskExecutionException.class, () -> tektonService.watch(task, 30L));
    assertEquals("JobFailed", ex.getStatusReason());
    assertEquals("Failed - \"step-task\" exited with code 2", ex.getMessage());
  }

  @Test
  public void testWatchIsNotEndedByAnEarlierAttemptsCancelledTaskRun() throws Exception {
    // Every attempt of a task carries the same labels. The first attempt's TaskRun was cancelled
    // and left in place (deletion policy Never); the second attempt gets a TaskRun of its own and
    // its watch must wait for that one, whatever the first reports.
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-attempt");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);
    io.fabric8.tekton.v1.TaskRun first =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems().get(0);
    Condition cancelled = new Condition();
    cancelled.setType("Succeeded");
    cancelled.setStatus("False");
    cancelled.setReason("TaskRunCancelled");
    cancelled.setMessage("The TaskRun was cancelled successfully.");
    TaskRunStatus cancelledStatus = new TaskRunStatus();
    cancelledStatus.setConditions(List.of(cancelled));
    cancelledStatus.setResults(List.of());
    first.setStatus(cancelledStatus);
    tektonClient.v1().taskRuns().resource(first).updateStatus();

    tektonService.create(task, 30L);
    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(2, taskRuns.size());
    io.fabric8.tekton.v1.TaskRun second =
        taskRuns.stream()
            .filter(t -> !first.getMetadata().getName().equals(t.getMetadata().getName()))
            .findFirst()
            .orElseThrow();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<List<RunResult>> future = executor.submit(() -> tektonService.watch(task, 30L));

      // The watch is open: the first attempt's TaskRun reports its cancellation again, and the
      // reconcile poll (1 s) runs. Neither may end the second attempt.
      Thread.sleep(500);
      tektonClient.v1().taskRuns().resource(first).updateStatus();
      Thread.sleep(1500);
      assertFalse(future.isDone(), "the earlier attempt's cancelled TaskRun ended the watch");

      Condition succeeded = new Condition();
      succeeded.setType("Succeeded");
      succeeded.setStatus("True");
      TaskRunStatus status = new TaskRunStatus();
      status.setConditions(List.of(succeeded));
      status.setResults(List.of());
      second.setStatus(status);
      tektonClient.v1().taskRuns().resource(second).updateStatus();

      assertNotNull(future.get(10, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }
}

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(
    properties = {
      "dispatcher.tasks.tolerations=[{\"key\":\"dedicated\",\"operator\":\"Equal\",\"value\":\"worker\",\"effect\":\"NoSchedule\"}]",
      "dispatcher.tasks.hostaliases=[{\"ip\":\"127.0.0.1\",\"hostnames\":[\"foo.local\",\"bar.local\"]}]"
    })
class TektonServiceImplTolerationsHostAliasesTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  @MockitoBean private Dispatcher dispatcher;

  private TektonClient tektonClient;

  @BeforeEach
  public void setUp() {
    tektonClient = client.adapt(TektonClient.class);
    tektonService.setClient(tektonClient);
  }

  // Proves the fabric8 Serialization.unmarshal replacement for Gson parses
  // dispatcher.tasks.tolerations / dispatcher.tasks.hostaliases identically to before.
  @Test
  public void testCreateTaskRunAppliesConfiguredTolerationsAndHostAliases() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-tolerations-hostaliases");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());

    List<Toleration> tolerations = taskRuns.get(0).getSpec().getPodTemplate().getTolerations();
    assertEquals(1, tolerations.size());
    Toleration toleration = tolerations.get(0);
    assertEquals("dedicated", toleration.getKey());
    assertEquals("Equal", toleration.getOperator());
    assertEquals("worker", toleration.getValue());
    assertEquals("NoSchedule", toleration.getEffect());

    List<HostAlias> hostAliases = taskRuns.get(0).getSpec().getPodTemplate().getHostAliases();
    assertEquals(1, hostAliases.size());
    HostAlias hostAlias = hostAliases.get(0);
    assertEquals("127.0.0.1", hostAlias.getIp());
    assertEquals(List.of("foo.local", "bar.local"), hostAlias.getHostnames());
  }
}

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(
    properties = {
      "dispatcher.tasks.tolerations=[{}]",
      "dispatcher.tasks.hostaliases=[{}]"
    })
class TektonServiceImplEmptyTolerationsHostAliasesTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  @MockitoBean private Dispatcher dispatcher;

  private TektonClient tektonClient;

  @BeforeEach
  public void setUp() {
    tektonClient = client.adapt(TektonClient.class);
    tektonService.setClient(tektonClient);
  }

  // A legacy "[{}]" property value carries no toleration/hostAlias data, so it must not reach
  // the API server as one - Kubernetes rejects a Toleration with an empty operator outright.
  @Test
  public void testCreateTaskRunDropsEmptyTolerationAndHostAliasEntries() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-empty-tolerations-hostaliases");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());

    List<Toleration> tolerations = taskRuns.get(0).getSpec().getPodTemplate().getTolerations();
    assertTrue(tolerations == null || tolerations.isEmpty());

    List<HostAlias> hostAliases = taskRuns.get(0).getSpec().getPodTemplate().getHostAliases();
    assertTrue(hostAliases == null || hostAliases.isEmpty());
  }
}

/**
 * Every {@code kube.resource.*} value blank: the step must carry no computeResources block at all,
 * rather than an empty one or a zero limit.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(
    properties = {
      "kube.resource.request.memory=",
      "kube.resource.limit.memory=",
      "kube.resource.request.ephemeral-storage=",
      "kube.resource.limit.ephemeral-storage=",
      "kube.resource.request.cpu=",
      "kube.resource.limit.cpu="
    })
class TektonServiceImplNoResourcesTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  @MockitoBean private Dispatcher dispatcher;

  private TektonClient tektonClient;

  @BeforeEach
  public void setUp() {
    tektonClient = client.adapt(TektonClient.class);
    tektonService.setClient(tektonClient);
  }

  @Test
  public void testCreateTaskRunLeavesResourcesUnsetWhenNothingIsConfigured() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-tekton-no-resources");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspace(null);
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    tektonService.create(task, 30L);

    List<io.fabric8.tekton.v1.TaskRun> taskRuns =
        tektonClient.v1().taskRuns().inAnyNamespace().list().getItems();
    assertEquals(1, taskRuns.size());
    assertNull(
        taskRuns.get(0).getSpec().getTaskSpec().getSteps().get(0).getComputeResources());
  }
}
