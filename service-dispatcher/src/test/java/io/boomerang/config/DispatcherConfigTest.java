package io.boomerang.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.dispatcher.TaskService;
import io.boomerang.dispatcher.WorkflowService;
import io.boomerang.dispatcher.sdk.Dispatcher;
import io.boomerang.dispatcher.sdk.DispatcherClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The application wires the SDK to its handlers: the async-proxied container handler is still
 * injected by its class, the engine URL derives from {@code flow.engine.service.host}, and the
 * configuration builds a dispatcher from the dispatcher properties.
 */
@SpringBootTest
@ActiveProfiles({"local"})
class DispatcherConfigTest {

  @Autowired private DispatcherClient dispatcherClient;

  @Autowired private TaskService taskService;

  @Autowired private WorkflowService workflowService;

  @Value("${flow.engine.url}")
  private String engineUrl;

  // The dispatcher registers with the engine at startup; no engine runs in tests.
  @MockitoBean private Dispatcher dispatcher;

  @Test
  void theContainerHandlerIsAsyncProxiedAndStillInjectedByClass() {
    assertThat(AopUtils.isCglibProxy(taskService)).isTrue();
  }

  @Test
  void theEngineUrlDerivesFromTheEngineHost() {
    assertThat(engineUrl).isEqualTo("http://localhost:7701");
  }

  // A plain instance: the context's configuration proxy would hand back the mocked bean instead.
  @Test
  void aDispatcherIsBuiltFromTheDispatcherProperties() {
    try (Dispatcher built =
        new DispatcherConfig().dispatcher(
            dispatcherClient,
            taskService,
            workflowService,
            "flow-tekton-dispatcher",
            List.of("template", "ai"),
            25,
            true,
            30000)) {
      assertThat(built.client()).isSameAs(dispatcherClient);
      assertThat(built.isRegistered()).isFalse();
      assertThat(built.inFlight()).isEmpty();
    }
  }
}
