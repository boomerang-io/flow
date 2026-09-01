package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.fabric8.knative.pkg.apis.Condition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.tekton.client.TektonClient;
import io.fabric8.tekton.v1.TaskRunStatus;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
@TestPropertySource(properties = "dispatcher.tasks.runtimeClassName=kata-qemu")
public class TektonServiceImplTest {

  KubernetesClient client;

  @Autowired private TektonServiceImpl tektonService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

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
    task.setWorkspaces(List.of());
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

  @MockitoBean private EngineClient engineClient;

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
    task.setWorkspaces(List.of());
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
}
