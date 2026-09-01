package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.error.BoomerangException;
import io.boomerang.executor.TaskExecutionException;
import io.boomerang.kube.KubeJobsExecutor;
import io.boomerang.kube.KubeServiceImpl;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(properties = "dispatcher.executor=kube-jobs")
public class KubeJobsExecutorTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  @Autowired private KubeJobsExecutor kubeJobsExecutor;

  @Value("${flow.product}")
  private String flowProduct;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
    kubeJobsExecutor.setClient(client);
  }

  private TaskRun commandTask(String taskRunRef, boolean withWorkspace) {
    TaskRun task = new TaskRun();
    task.setId(taskRunRef);
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of());

    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setWorkingDir("/data");
    spec.setDebug(false);
    task.setSpec(spec);

    if (withWorkspace) {
      TaskWorkspace ws = new TaskWorkspace();
      ws.setName("ws");
      ws.setType("workflow");
      task.setWorkspaces(List.of(ws));
    } else {
      task.setWorkspaces(List.of());
    }
    return task;
  }

  private Job soleJobFor(String taskRunRef) {
    List<Job> jobs =
        client.batch().v1().jobs().withLabels(Map.of("boomerang.io/taskrun-ref", taskRunRef)).list().getItems();
    assertEquals(1, jobs.size());
    return jobs.get(0);
  }

  @Test
  public void testCreateCommandTaskWithWorkflowWorkspace() throws Exception {
    TaskRun task = commandTask("taskrun-cmd", true);

    // Pre-create the PVC, exactly as TaskService.execute() does before delegating to the
    // executor.
    try {
      kubeService.createWorkspacePVC(
          task.getWorkflowRef(), task.getWorkflowRef(), "workflow", null, "1Gi", "", "ReadWriteMany", 1L);
    } catch (Exception e) {
      // The mock server never reports a PVC phase of Bound/Pending, so the wait times out; the
      // PVC itself is still created and that's all getPVCName() needs.
    }

    kubeJobsExecutor.create(task, 30L);

    List<Job> allJobs = client.batch().v1().jobs().inAnyNamespace().list().getItems();
    assertEquals(1, allJobs.size());

    Job job = allJobs.get(0);
    assertEquals(30L * 60, job.getSpec().getActiveDeadlineSeconds());
    assertEquals(0, job.getSpec().getBackoffLimit());
    assertEquals(7 * 86400, job.getSpec().getTtlSecondsAfterFinished());

    Map<String, String> podLabels = job.getSpec().getTemplate().getMetadata().getLabels();
    assertEquals(task.getId(), podLabels.get("boomerang.io/taskrun-ref"));

    List<Volume> volumes = job.getSpec().getTemplate().getSpec().getVolumes();
    String volPrefix = flowProduct + "-vol";
    List<String> volumeNames = volumes.stream().map(Volume::getName).toList();
    assertTrue(volumeNames.contains(volPrefix + "-data"));
    assertFalse(volumeNames.contains(volPrefix + "-params"));
    assertTrue(volumeNames.contains(volPrefix + "-ws-workflow"));

    Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
    List<VolumeMount> mounts = container.getVolumeMounts();
    assertTrue(mounts.stream().anyMatch(m -> "/workspace/workflow".equals(m.getMountPath())));

    List<EnvVar> env = container.getEnv();
    assertTrue(env.stream().anyMatch(e -> "CI".equals(e.getName()) && "true".equals(e.getValue())));
    // Every Task Param is also exposed as PARAM_<NAME>, exactly as the Tekton executor does.
    assertTrue(env.stream().anyMatch(e -> e.getName().startsWith("PARAM_")));
    assertTrue(
        env.stream()
            .anyMatch(
                e ->
                    "RESULTS_PATH".equals(e.getName())
                        && "/dev/termination-log".equals(e.getValue())));
  }

  @Test
  public void testCreateScriptTaskMountsExecutableConfigMap() throws Exception {
    TaskRun task = commandTask("taskrun-script", false);
    task.getSpec().setCommand(null);
    task.getSpec().setScript("#!/bin/sh\necho hello");

    kubeJobsExecutor.create(task, 30L);

    List<ConfigMap> configMaps =
        client.configMaps().withLabels(Map.of("boomerang.io/taskrun-ref", task.getId())).list().getItems();
    // Only the script ConfigMap create() built — params are env-only, no ConfigMap for them.
    assertEquals(1, configMaps.size());
    ConfigMap scriptConfigMap = configMaps.get(0);
    assertTrue(scriptConfigMap.getMetadata().getGenerateName().contains("-script-"));
    assertEquals("#!/bin/sh\necho hello", scriptConfigMap.getData().get("script"));

    Job job = soleJobFor(task.getId());
    Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
    assertEquals(List.of("/scripts/script"), container.getCommand());
  }

  @Test
  public void testDeleteRemovesScriptConfigMap() throws Exception {
    TaskRun task = commandTask("taskrun-script-delete", false);
    task.getSpec().setCommand(null);
    task.getSpec().setScript("#!/bin/sh\necho hello");

    kubeJobsExecutor.create(task, 30L);
    Map<String, String> taskLabels = Map.of("boomerang.io/taskrun-ref", task.getId());
    assertEquals(1, client.configMaps().withLabels(taskLabels).list().getItems().size());

    kubeJobsExecutor.delete(task);

    assertTrue(client.configMaps().withLabels(taskLabels).list().getItems().isEmpty());
  }

  @Test
  public void testCreateWithoutRuntimeClassNameLeavesItUnset() throws Exception {
    TaskRun task = commandTask("taskrun-no-runtimeclass", false);

    kubeJobsExecutor.create(task, 30L);

    Job job = soleJobFor(task.getId());
    assertNull(job.getSpec().getTemplate().getSpec().getRuntimeClassName());
  }

  @Test
  public void testCancelWithNoMatchingJobThrows() {
    TaskRun task = commandTask("taskrun-missing", false);

    BoomerangException ex = assertThrows(BoomerangException.class, () -> kubeJobsExecutor.cancel(task));
    assertTrue(ex.getDescription().contains("CANCEL_FAILURE"));
  }

  @Test
  public void testParseTerminationMessageObjectShape() {
    List<RunResult> results = TerminationMessageParser.parse("{\"greeting\": \"hello\"}", List.of());
    assertEquals(1, results.size());
    assertEquals("greeting", results.get(0).getName());
    assertEquals("hello", results.get(0).getValue());
  }

  @Test
  public void testParseTerminationMessageArrayShape() {
    List<RunResult> results =
        TerminationMessageParser.parse("[{\"key\": \"greeting\", \"value\": \"hello\"}]", List.of());
    assertEquals(1, results.size());
    assertEquals("greeting", results.get(0).getName());
    assertEquals("hello", results.get(0).getValue());
  }

  @Test
  public void testParseTerminationMessageFiltersToDeclaredResults() {
    List<RunResult> declared = List.of(new RunResult("greeting", null));
    List<RunResult> results =
        TerminationMessageParser.parse("{\"greeting\": \"hello\", \"other\": \"skip\"}", declared);
    assertEquals(1, results.size());
    assertEquals("greeting", results.get(0).getName());
  }

  @Test
  public void testParseTerminationMessageTreatsGarbageAsNoResults() {
    assertTrue(TerminationMessageParser.parse("not json at all !!", List.of()).isEmpty());
    assertTrue(TerminationMessageParser.parse("", List.of()).isEmpty());
    assertTrue(TerminationMessageParser.parse(null, List.of()).isEmpty());
  }
}

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(properties = {"dispatcher.executor=kube-jobs", "dispatcher.tasks.runtimeClassName=gvisor"})
class KubeJobsExecutorRuntimeClassNamePropertyTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  @Autowired private KubeJobsExecutor kubeJobsExecutor;

  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
    kubeJobsExecutor.setClient(client);
  }

  @Test
  public void testCreateSetsRuntimeClassNameWhenConfigured() throws Exception {
    TaskRun task = new TaskRun();
    task.setId("taskrun-runtimeclass");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspaces(List.of());
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);

    kubeJobsExecutor.create(task, 30L);

    List<Job> jobs =
        client
            .batch()
            .v1()
            .jobs()
            .withLabels(Map.of("boomerang.io/taskrun-ref", task.getId()))
            .list()
            .getItems();
    assertEquals(1, jobs.size());
    assertEquals("gvisor", jobs.get(0).getSpec().getTemplate().getSpec().getRuntimeClassName());
  }
}

/**
 * Pins the reconcile loop: the watch never delivers a terminating event in these tests (the
 * terminal state is written to the mock server before the watch is opened), so every case is
 * resolved by the label-list poll on a one-second {@code kube.timeout.reconcileSeconds}.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(properties = {"dispatcher.executor=kube-jobs", "kube.timeout.reconcileSeconds=1"})
class KubeJobsExecutorReconcileTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  @Autowired private KubeJobsExecutor kubeJobsExecutor;

  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
    kubeJobsExecutor.setClient(client);
  }

  private TaskRun task(String taskRunRef) {
    TaskRun task = new TaskRun();
    task.setId(taskRunRef);
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of());
    task.setResults(List.of());
    task.setWorkspaces(List.of());
    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo", "hello"));
    spec.setDebug(false);
    task.setSpec(spec);
    return task;
  }

  @Test
  public void testWatchReconcilesAGenericJobFailureAsJobFailed() throws Exception {
    TaskRun task = task("taskrun-reconcile-failed");
    kubeJobsExecutor.create(task, 30L);

    Job created = client.batch().v1().jobs().inAnyNamespace().list().getItems().get(0);
    JobStatus status = new JobStatus();
    status.setFailed(1);
    created.setStatus(status);
    client.batch().v1().jobs().resource(created).updateStatus();

    TaskExecutionException ex =
        assertThrows(TaskExecutionException.class, () -> kubeJobsExecutor.watch(task, 30L));
    assertEquals("JobFailed", ex.getStatusReason());
  }

  @Test
  public void testWatchReconcilesAMissingJobAsJobDeleted() {
    TaskRun task = task("taskrun-reconcile-missing");
    // No Job is ever created - the watch never fires and the reconcile poll finds nothing.
    TaskExecutionException ex =
        assertThrows(TaskExecutionException.class, () -> kubeJobsExecutor.watch(task, 30L));
    assertEquals("JobDeleted", ex.getStatusReason());
  }

  @Test
  public void testWatchReconcilesAnOOMKilledPodAsOOMKilled() throws Exception {
    TaskRun task = task("taskrun-reconcile-oom");
    kubeJobsExecutor.create(task, 30L);

    Job created = client.batch().v1().jobs().inAnyNamespace().list().getItems().get(0);
    Map<String, String> taskLabels = created.getMetadata().getLabels();

    JobStatus status = new JobStatus();
    status.setFailed(1);
    created.setStatus(status);
    client.batch().v1().jobs().resource(created).updateStatus();

    ContainerStateTerminated terminated = new ContainerStateTerminated();
    terminated.setReason("OOMKilled");
    ContainerState state = new ContainerState();
    state.setTerminated(terminated);
    ContainerStatus containerStatus = new ContainerStatus();
    containerStatus.setName("task");
    containerStatus.setState(state);
    PodStatus podStatus = new PodStatus();
    podStatus.setContainerStatuses(List.of(containerStatus));

    Pod pod =
        new PodBuilder()
            .withNewMetadata()
            .withGenerateName("test-pod-")
            .withLabels(taskLabels)
            .endMetadata()
            .withStatus(podStatus)
            .build();
    Pod createdPod = client.pods().resource(pod).create();
    createdPod.setStatus(podStatus);
    client.pods().resource(createdPod).updateStatus();

    TaskExecutionException ex =
        assertThrows(TaskExecutionException.class, () -> kubeJobsExecutor.watch(task, 30L));
    assertEquals("OOMKilled", ex.getStatusReason());
  }

  @Test
  public void testCreateAdoptsAnExistingJobRatherThanCreatingASecondOne() throws Exception {
    TaskRun task = task("taskrun-reconcile-adopt");
    kubeJobsExecutor.create(task, 30L);
    assertEquals(1, client.batch().v1().jobs().inAnyNamespace().list().getItems().size());

    kubeJobsExecutor.create(task, 30L);

    assertEquals(1, client.batch().v1().jobs().inAnyNamespace().list().getItems().size());
  }
}
