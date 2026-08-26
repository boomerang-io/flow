package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.client.EngineClient;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles({"local"})
@EnableKubernetesMockClient(crud = true)
public class KubeServiceImplTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  // The agent registers with the engine at startup; no engine runs in tests.
  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  public void setUp() {
    kubeService.setClient(client);
  }

  @Test
  public void testGetPVCNameThrowsWhenNoPVCMatchesLabels() {
    Map<String, String> labels = Map.of("boomerang.io/workspace-ref", "does-not-exist");

    assertThrows(KubeRuntimeException.class, () -> kubeService.getPVCName(labels));
  }
}
