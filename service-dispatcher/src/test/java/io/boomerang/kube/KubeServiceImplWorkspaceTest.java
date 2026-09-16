package io.boomerang.kube;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.client.EngineClient;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
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
 * Pins what the workspace reconciler stands on: the listing sees every workspace claim in the
 * namespace and nothing else, and deleting a claim that is already gone is a success, not an
 * error - the same tick may run twice over the same ref.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnableKubernetesMockClient(crud = true)
class KubeServiceImplWorkspaceTest {

  KubernetesClient client;

  @Autowired private KubeServiceImpl kubeService;

  @MockitoBean private EngineClient engineClient;

  @BeforeEach
  void setUp() {
    kubeService.setClient(client);
  }

  private void createWorkspaceClaim(String workspaceRef, String workspaceType) {
    try {
      // The mock server never writes a phase, so the bound/pending wait times out after 1 s; the
      // claim itself is created before the wait.
      kubeService.createWorkspacePVC(
          "wf-1", workspaceRef, workspaceType, Map.of(), "1Gi", "", "ReadWriteOnce", 1);
    } catch (Exception ignored) {
      // wait timeout on the mock server
    }
  }

  @Test
  void listingReturnsWorkspaceClaimsAndIgnoresOtherTiers() {
    createWorkspaceClaim("run-listed", "workflowrun");
    PersistentVolumeClaim foreign =
        new PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName("someone-elses-claim")
            .withLabels(Map.of("boomerang.io/product", "bmrg-flow", "boomerang.io/tier", "task"))
            .endMetadata()
            .build();
    client.persistentVolumeClaims().resource(foreign).create();

    List<PersistentVolumeClaim> claims = kubeService.listWorkspacePVCs();

    assertEquals(1, claims.size(), "only the workspace-tier claim");
    assertEquals(
        "run-listed", claims.get(0).getMetadata().getLabels().get("boomerang.io/workspace-ref"));
    assertEquals(
        "workflowrun",
        claims.get(0).getMetadata().getLabels().get("boomerang.io/workspace-type"));
  }

  @Test
  void deletingAClaimThatIsAlreadyGoneIsNotAnError() {
    assertDoesNotThrow(() -> kubeService.delete("run-never-existed", "workflowrun"));
  }
}
