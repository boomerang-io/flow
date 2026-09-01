package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.client.EngineClient;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the storage-class contract of workspace claims: a blank class leaves {@code
 * storageClassName} unset so the cluster default applies (an empty string would disable dynamic
 * provisioning and the claim would never bind), while a named class is passed through.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
class KubeServiceImplPvcTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  void setUp() {
    kubeService.setClient(client);
  }

  private PersistentVolumeClaim create(String workspaceRef, String className) {
    try {
      // The mock server never writes a phase, so the bound/pending wait times out after 1 s;
      // the claim itself is created before the wait and is what this test inspects.
      kubeService.createWorkspacePVC(
          "wf-1", workspaceRef, "workflowrun", Map.of(), "1Gi", className, "ReadWriteOnce", 1);
    } catch (Exception ignored) {
      // wait timeout on the mock server
    }
    List<PersistentVolumeClaim> claims =
        client.persistentVolumeClaims().inAnyNamespace().list().getItems().stream()
            .filter(c -> workspaceRef.equals(c.getMetadata().getLabels().get("boomerang.io/workspace-ref")))
            .toList();
    assertEquals(1, claims.size(), "exactly one claim for " + workspaceRef);
    return claims.get(0);
  }

  @Test
  void blankClassLeavesStorageClassNameUnset() {
    PersistentVolumeClaim pvc = create("run-blank", "");
    assertNull(pvc.getSpec().getStorageClassName());
    assertEquals(List.of("ReadWriteOnce"), pvc.getSpec().getAccessModes());
  }

  @Test
  void namedClassIsPassedThrough() {
    PersistentVolumeClaim pvc = create("run-named", "local-path");
    assertEquals("local-path", pvc.getSpec().getStorageClassName());
  }
}
