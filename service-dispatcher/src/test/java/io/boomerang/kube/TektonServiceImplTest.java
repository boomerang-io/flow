package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.client.EngineClient;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunSpec;
import io.fabric8.kubernetes.api.model.HostAlias;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.tekton.client.TektonClient;
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

  @MockitoBean private EngineClient engineClient;

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
