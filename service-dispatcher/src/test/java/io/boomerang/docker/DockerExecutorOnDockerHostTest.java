package io.boomerang.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.dispatcher.LeaseRegistry;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.executor.TaskResourceResolver;
import io.boomerang.dispatcher.model.HeldWorkspace;
import io.boomerang.kube.KubeHelperService;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Runs a real container on whatever Docker daemon this machine can reach, so the parts a mock
 * cannot prove - the environment actually arriving, the results file surviving the container's
 * exit, the log being readable - are exercised end to end. Skips when no daemon answers.
 */
@EnabledIf("dockerIsReachable")
class DockerExecutorOnDockerHostTest {

  private static final String IMAGE = "busybox:1.36";

  static boolean dockerIsReachable() {
    try (DockerClient client = dockerClient()) {
      client.pingCmd().exec();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static DockerClient dockerClient() {
    DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    return DockerClientImpl.getInstance(
        config,
        new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(10))
            .build());
  }

  private KubeHelperService helperKubeService() {
    KubeHelperService helper = new KubeHelperService();
    ReflectionTestUtils.setField(helper, "bmrgProduct", "bmrg-flow-test");
    ReflectionTestUtils.setField(helper, "bmrgInstance", "bmrg-flow-test");
    ReflectionTestUtils.setField(helper, "flowVersion", "5.0.0");
    ReflectionTestUtils.setField(helper, "proxyEnabled", Boolean.FALSE);
    return helper;
  }

  private DockerExecutor executor(DockerClient client, DockerWorkspaceStore store, KubeHelperService helper) {
    TaskResourceResolver resourceResolver = new TaskResourceResolver();
    ReflectionTestUtils.setField(resourceResolver, "limitMemory", "");
    ReflectionTestUtils.setField(resourceResolver, "limitCpu", "");
    DockerExecutor executor =
        new DockerExecutor(client, helper, store, new LeaseRegistry(), new TaskImageResolver(), resourceResolver);
    ReflectionTestUtils.setField(executor, "imagePullPolicy", "IfNotPresent");
    ReflectionTestUtils.setField(executor, "taskStorageDataMemory", Boolean.FALSE);
    ReflectionTestUtils.setField(executor, "pollSeconds", 1L);
    return executor;
  }

  private TaskRun task(String script) {
    TaskRun task = new TaskRun();
    task.setId("it-" + UUID.randomUUID());
    task.setName("Docker host task");
    task.setWorkflowRef("wf-" + UUID.randomUUID());
    task.setWorkflowRunRef("wfr-" + UUID.randomUUID());
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello from flow")));
    task.setResults(List.of(new RunResult("greeting", null)));

    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage(IMAGE);
    spec.setCommand(List.of("/bin/sh", "-c", script));
    spec.setDebug(false);
    task.setSpec(spec);
    task.setWorkspaces(List.of());
    return task;
  }

  @Test
  void aTaskRunsItsImageAndItsResultAndLogComeBack() throws Exception {
    try (DockerClient client = dockerClient()) {
      KubeHelperService helper = helperKubeService();
      DockerWorkspaceStore store = new DockerWorkspaceStore(client, helper);
      DockerExecutor executor = executor(client, store, helper);
      DockerLogService logService = new DockerLogService(client, helper);
      TaskRun task =
          task("echo \"$PARAM_GREETING\"; printf '{\"greeting\":\"%s\"}' \"$PARAM_GREETING\" > \"$RESULTS_PATH\"");

      try {
        executor.create(task, 2L);
        List<RunResult> results = executor.watch(task, 2L);

        assertEquals(1, results.size());
        assertEquals("greeting", results.get(0).getName());
        assertEquals("hello from flow", results.get(0).getValue());
        assertTrue(
            logService
                .get(task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId())
                .contains("hello from flow"));
      } finally {
        executor.delete(task);
      }
    }
  }

  @Test
  void aFailingTaskReportsItsExitCode() throws Exception {
    try (DockerClient client = dockerClient()) {
      KubeHelperService helper = helperKubeService();
      DockerWorkspaceStore store = new DockerWorkspaceStore(client, helper);
      DockerExecutor executor = executor(client, store, helper);
      TaskRun task = task("echo nope >&2; exit 3");

      try {
        executor.create(task, 2L);
        io.boomerang.error.TaskExecutionException failure =
            org.junit.jupiter.api.Assertions.assertThrows(
                io.boomerang.error.TaskExecutionException.class, () -> executor.watch(task, 2L));
        assertEquals("JobFailed", failure.getStatusReason());
        assertTrue(failure.getMessage().contains("exited with code 3"), failure.getMessage());
      } finally {
        executor.delete(task);
      }
    }
  }

  @Test
  void aWorkflowWorkspaceIsAVolumeTheTaskWritesToAndTheReconcilerCanFind() throws Exception {
    try (DockerClient client = dockerClient()) {
      KubeHelperService helper = helperKubeService();
      DockerWorkspaceStore store = new DockerWorkspaceStore(client, helper);
      DockerExecutor executor = executor(client, store, helper);

      TaskRun task = task("echo shared > /workspace/workflow/file");
      TaskWorkspace workspace = new TaskWorkspace();
      workspace.setName("ws");
      workspace.setType("workflow");
      task.setWorkspaces(List.of(workspace));

      String workflowRef = task.getWorkflowRef();
      try {
        store.create(workflowRef, workflowRef, "workflow", null, "1Gi", "", "ReadWriteMany", 30L);
        assertTrue(store.exists(workflowRef, "workflow"));
        assertTrue(store.held().contains(new HeldWorkspace(workflowRef, "workflow")));

        executor.create(task, 2L);
        executor.watch(task, 2L);
      } finally {
        executor.delete(task);
        store.delete(workflowRef, "workflow");
      }
      assertFalse(store.exists(workflowRef, "workflow"));
    }
  }
}
