package io.boomerang.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

public class KubeClientConfigTest {

  @Test
  public void testBuildClientThrowsWhenNoNamespaceResolves() {
    KubeClientConfig kubeClientConfig = new KubeClientConfig("");

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class, () -> kubeClientConfig.buildClient(Config.empty()));
    assertTrue(exception.getMessage().contains("kube.namespace"));
    assertTrue(exception.getMessage().contains("kubeconfig context"));
  }

  @Test
  public void testBuildClientUsesConfiguredNamespace() {
    KubeClientConfig kubeClientConfig = new KubeClientConfig("foo");

    try (KubernetesClient client = kubeClientConfig.buildClient(Config.empty())) {
      assertEquals("foo", client.getNamespace());
    }
  }
}
