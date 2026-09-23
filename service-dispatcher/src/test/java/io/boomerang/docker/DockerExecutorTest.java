package io.boomerang.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.dispatcher.LeaseRegistry;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.executor.TaskResourceResolver;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.kube.KubeHelperService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The docker executor against a mocked Docker Engine API: what the container is created with, and
 * what each way of finishing reports back to the engine.
 */
class DockerExecutorTest {

  private final DockerClient client = mock(DockerClient.class);

  private final KubeHelperService helperKubeService = new KubeHelperService();

  private final DockerWorkspaceStore workspaceStore = mock(DockerWorkspaceStore.class);

  private final LeaseRegistry leaseRegistry = mock(LeaseRegistry.class);

  private final TaskResourceResolver resourceResolver = new TaskResourceResolver();

  private DockerExecutor executor;

  private CreateContainerCmd createCmd;

  private ListContainersCmd listCmd;

  private InspectContainerResponse.ContainerState state;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(helperKubeService, "bmrgProduct", "bmrg-flow");
    ReflectionTestUtils.setField(helperKubeService, "bmrgInstance", "bmrg-flow");
    ReflectionTestUtils.setField(helperKubeService, "flowVersion", "5.0.0");
    ReflectionTestUtils.setField(helperKubeService, "proxyEnabled", Boolean.FALSE);

    limits("", "");

    executor =
        new DockerExecutor(
            client, helperKubeService, workspaceStore, leaseRegistry, new TaskImageResolver(), resourceResolver);
    ReflectionTestUtils.setField(executor, "imagePullPolicy", "IfNotPresent");
    ReflectionTestUtils.setField(executor, "taskStorageDataMemory", Boolean.FALSE);
    ReflectionTestUtils.setField(executor, "pollSeconds", 0L);

    listCmd = mock(ListContainersCmd.class);
    when(client.listContainersCmd()).thenReturn(listCmd);
    when(listCmd.withShowAll(true)).thenReturn(listCmd);
    when(listCmd.withLabelFilter(any(Map.class))).thenReturn(listCmd);
    when(listCmd.exec()).thenReturn(List.of());

    InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
    when(client.inspectImageCmd(anyString())).thenReturn(inspectImageCmd);

    createCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.createContainerCmd(anyString())).thenReturn(createCmd);
    CreateContainerResponse created = mock(CreateContainerResponse.class);
    when(created.getId()).thenReturn("container-1");
    when(createCmd.exec()).thenReturn(created);
    when(client.startContainerCmd(anyString())).thenReturn(mock(StartContainerCmd.class));

    state = mock(InspectContainerResponse.ContainerState.class);
    InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
    when(inspectResponse.getState()).thenReturn(state);
    InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
    when(inspectCmd.exec()).thenReturn(inspectResponse);
    when(client.inspectContainerCmd(anyString())).thenReturn(inspectCmd);
  }

  private TaskRun task(boolean withWorkspace) {
    TaskRun task = new TaskRun();
    task.setId("taskrun-1");
    task.setName("Test Task");
    task.setWorkflowRef("wf-1");
    task.setWorkflowRunRef("wfr-1");
    task.setLabels(new HashMap<>());
    task.setParams(List.of(new RunParam("greeting", "hello")));
    task.setResults(List.of(new RunResult("greeting", null)));

    TaskRunSpec spec = new TaskRunSpec();
    spec.setImage("alpine:3.19");
    spec.setCommand(List.of("echo"));
    spec.setArguments(List.of("hello"));
    spec.setWorkingDir("/data");
    spec.setDebug(false);
    task.setSpec(spec);

    if (withWorkspace) {
      TaskWorkspace workspace = new TaskWorkspace();
      workspace.setName("ws");
      workspace.setType("workflow");
      task.setWorkspaces(List.of(workspace));
    } else {
      task.setWorkspaces(List.of());
    }
    return task;
  }

  private void containerExists(String stateName) {
    Container container = mock(Container.class);
    when(container.getId()).thenReturn("container-1");
    when(container.getState()).thenReturn(stateName);
    when(listCmd.exec()).thenReturn(List.of(container));
  }

  private void resultsFile(String body) {
    ByteArrayOutputStream tar = new ByteArrayOutputStream();
    try (TarArchiveOutputStream archive = new TarArchiveOutputStream(tar)) {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry("results.json");
      entry.setSize(bytes.length);
      archive.putArchiveEntry(entry);
      archive.write(bytes);
      archive.closeArchiveEntry();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    InputStream stream = new ByteArrayInputStream(tar.toByteArray());
    CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class);
    when(copyCmd.exec()).thenReturn(stream);
    when(client.copyArchiveFromContainerCmd(anyString(), anyString())).thenReturn(copyCmd);
  }

  private void exited(long exitCode) {
    when(state.getRunning()).thenReturn(Boolean.FALSE);
    when(state.getStatus()).thenReturn("exited");
    when(state.getExitCodeLong()).thenReturn(exitCode);
  }

  @Test
  void createStartsOneLabelledContainerWithTheTaskEnvironment() throws Exception {
    executor.create(task(false), 30L);

    ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
    verify(createCmd).withName(name.capture());
    assertEquals("bmrg-flow-task-taskrun-1", name.getValue());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
    verify(createCmd).withLabels(labels.capture());
    assertEquals("task", labels.getValue().get("boomerang.io/tier"));
    assertEquals("taskrun-1", labels.getValue().get("boomerang.io/taskrun-ref"));
    assertEquals("wfr-1", labels.getValue().get("boomerang.io/workflowrun-ref"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
    verify(createCmd).withEnv(env.capture());
    assertTrue(env.getValue().contains("PARAM_GREETING=hello"), env.getValue().toString());
    assertTrue(env.getValue().contains("PARAM_NAMES=greeting"));
    assertTrue(env.getValue().contains("RESULTS_PATH=" + DockerExecutor.RESULTS_PATH));
    assertTrue(env.getValue().contains("CI=true"));

    // Kubernetes command/args map onto Docker's entrypoint/cmd.
    verify(createCmd).withEntrypoint(List.of("echo"));
    verify(createCmd).withCmd(List.of("hello"));
    verify(createCmd).withWorkingDir("/data");
    verify(client).startContainerCmd("container-1");
  }

  @Test
  void createMountsOnlyTheWorkspacesTheTaskDeclares() throws Exception {
    when(workspaceStore.volumeNameFor("wf-1", "workflow")).thenReturn("bmrg-flow-vol-ws-workflow-wf-1");

    executor.create(task(true), 30L);

    Bind[] binds = capturedHostConfig().getBinds();
    assertEquals(1, binds.length);
    assertEquals("bmrg-flow-vol-ws-workflow-wf-1", binds[0].getPath());
    assertEquals("/workspace/workflow", binds[0].getVolume().getPath());
  }

  @Test
  void createAdoptsAnExistingContainerRatherThanStartingASecond() throws Exception {
    containerExists("running");

    executor.create(task(false), 30L);

    verify(client, never()).createContainerCmd(anyString());
  }

  @Test
  void createCopiesAScriptInAsAnExecutableFile() throws Exception {
    TaskRun task = task(false);
    task.getSpec().setScript("#!/bin/sh\necho hello");
    CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.copyArchiveToContainerCmd(anyString())).thenReturn(copyCmd);

    executor.create(task, 30L);

    verify(copyCmd).withRemotePath("/");
    verify(copyCmd).exec();
    verify(createCmd).withEntrypoint(List.of(DockerExecutor.SCRIPT_PATH));
  }

  @Test
  void anExitOfZeroReturnsTheDeclaredResults() throws Exception {
    containerExists("exited");
    exited(0L);
    resultsFile("{\"greeting\":\"hello\",\"undeclared\":\"dropped\"}");

    List<RunResult> results = executor.watch(task(false), 30L);

    assertEquals(1, results.size());
    assertEquals("greeting", results.get(0).getName());
    assertEquals("hello", results.get(0).getValue());
  }

  @Test
  void aNonZeroExitFailsButStillCarriesWhatTheTaskWrote() {
    containerExists("exited");
    exited(1L);
    resultsFile("{\"greeting\":\"partial\"}");

    TaskExecutionException failure =
        assertThrows(TaskExecutionException.class, () -> executor.watch(task(false), 30L));

    assertEquals("JobFailed", failure.getStatusReason());
    assertTrue(failure.getMessage().contains("exited with code 1"), failure.getMessage());
    assertEquals("partial", failure.getResults().get(0).getValue());
  }

  @Test
  void anOutOfMemoryKillIsReportedAsSuch() {
    containerExists("exited");
    exited(137L);
    when(state.getOOMKilled()).thenReturn(Boolean.TRUE);
    resultsFile("");

    TaskExecutionException failure =
        assertThrows(TaskExecutionException.class, () -> executor.watch(task(false), 30L));

    assertEquals("OOMKilled", failure.getStatusReason());
  }

  @Test
  void aContainerStillRunningAtItsTimeoutIsStoppedAndReportedAsDeadlineExceeded() {
    containerExists("running");
    when(state.getRunning()).thenReturn(Boolean.TRUE);
    when(state.getStatus()).thenReturn("running");
    resultsFile("");
    StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.stopContainerCmd(anyString())).thenReturn(stopCmd);

    // Zero minutes: the deadline has already passed on the first poll.
    TaskExecutionException failure =
        assertThrows(TaskExecutionException.class, () -> executor.watch(task(false), 0L));

    assertEquals("DeadlineExceeded", failure.getStatusReason());
    verify(stopCmd).exec();
  }

  @Test
  void aContainerThatHasGoneIsReportedAsDeleted() {
    when(listCmd.exec()).thenReturn(List.of());

    TaskExecutionException failure =
        assertThrows(TaskExecutionException.class, () -> executor.watch(task(false), 30L));

    assertEquals("JobDeleted", failure.getStatusReason());
  }

  @Test
  void theLeaseIsStampedWhileWatchingAndReleasedWhenTheTaskEnds() throws Exception {
    containerExists("exited");
    exited(0L);
    resultsFile("");

    executor.watch(task(false), 30L);

    verify(leaseRegistry).beat("taskrun-1");
    verify(leaseRegistry).remove("taskrun-1");
  }

  @Test
  void cancelStopsAndRemovesTheContainer() {
    containerExists("running");
    StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.stopContainerCmd(anyString())).thenReturn(stopCmd);
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.removeContainerCmd(anyString())).thenReturn(removeCmd);

    executor.cancel(task(false));

    verify(stopCmd).exec();
    verify(removeCmd).exec();
  }

  @Test
  void cancellingWhenNothingIsRunningIsAnError() {
    when(listCmd.exec()).thenReturn(List.of());

    assertThrows(BoomerangException.class, () -> executor.cancel(task(false)));
  }

  @Test
  void deleteRemovesTheContainerAndIsQuietWhenThereIsNone() {
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
    when(client.removeContainerCmd(anyString())).thenReturn(removeCmd);
    containerExists("exited");

    executor.delete(task(false));
    verify(removeCmd).exec();

    when(listCmd.exec()).thenReturn(List.of());
    executor.delete(task(false));
  }

  @Test
  void theConfiguredLimitsSizeTheContainer() throws Exception {
    // The same properties the Kubernetes executors apply, as the counts Docker takes.
    limits("2Gi", "500m");

    executor.create(task(false), 30L);

    HostConfig hostConfig = capturedHostConfig();
    assertEquals(2L * 1024 * 1024 * 1024, hostConfig.getMemory());
    assertEquals(500_000_000L, hostConfig.getNanoCPUs());
  }

  @Test
  void blankLimitsSizeNothing() throws Exception {
    limits("", "");

    executor.create(task(false), 30L);

    HostConfig hostConfig = capturedHostConfig();
    assertNull(hostConfig.getMemory());
    assertNull(hostConfig.getNanoCPUs());
  }

  private void limits(String memory, String cpu) {
    ReflectionTestUtils.setField(resourceResolver, "limitMemory", memory);
    ReflectionTestUtils.setField(resourceResolver, "limitCpu", cpu);
  }

  private HostConfig capturedHostConfig() {
    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfig.capture());
    return hostConfig.getValue();
  }
}
