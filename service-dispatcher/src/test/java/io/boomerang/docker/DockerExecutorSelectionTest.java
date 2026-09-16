package io.boomerang.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.client.EngineClient;
import io.boomerang.dispatcher.TaskLogStore;
import io.boomerang.dispatcher.WorkspaceStore;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.kube.KubeJobsExecutor;
import io.boomerang.kube.KubeLogService;
import io.boomerang.kube.KubeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * dispatcher.executor=docker must stand on its own: the Docker implementations are the ones wired,
 * and no Kubernetes bean is created, so the dispatcher starts with no cluster and no kubeconfig.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "dispatcher.executor=docker")
class DockerExecutorSelectionTest {

  @Autowired private ApplicationContext context;

  @Autowired private TaskExecutor taskExecutor;

  // The dispatcher registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  @Test
  void theDockerImplementationsAreTheOnesWired() {
    assertInstanceOf(DockerExecutor.class, taskExecutor);
    assertInstanceOf(DockerWorkspaceStore.class, context.getBean(WorkspaceStore.class));
    assertInstanceOf(DockerLogService.class, context.getBean(TaskLogStore.class));
  }

  @Test
  void noKubernetesBeanIsCreated() {
    assertEquals(0, context.getBeanNamesForType(KubeServiceImpl.class).length);
    assertEquals(0, context.getBeanNamesForType(KubeLogService.class).length);
    assertEquals(0, context.getBeanNamesForType(KubeJobsExecutor.class).length);
    assertTrue(context.getBeanNamesForType(TaskExecutor.class).length == 1, "exactly one executor is active");
  }
}
